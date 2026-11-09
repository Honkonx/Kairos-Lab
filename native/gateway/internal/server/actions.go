package server

import (
	"context"
	"net/http"
	"strconv"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/pending"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
	"github.com/honkonx/kairos-dev/native/gateway/internal/router"
)

type actionView struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Risk     string `json:"risk"`
	Kind     string `json:"kind"`
	Decision string `json:"decision"`
}

// handleListActions lista las acciones y, para el token que pregunta, qué pasaría al pedirlas.
func (s *Server) handleListActions(w http.ResponseWriter, r *http.Request) {
	tok, ok := s.authenticate(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "token inválido")
		return
	}
	level := s.effective(tok)
	var out []actionView
	for _, a := range s.cfg.Registry.List() {
		out = append(out, actionView{
			ID: a.ID, Title: a.Title, Risk: a.RiskLevel().String(), Kind: string(a.Kind),
			Decision: decide(level, a).String(),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"effective_level": level, "actions": out})
}

type actionRequest struct {
	Params map[string]string `json:"params"`
}

// decide aplica la matriz de permisos y el nivel mínimo propio de la acción, si lo declara: por debajo de ese nivel se
// rechaza sin pedir confirmación.
func decide(level permission.Level, act router.Action) permission.Decision {
	if min, ok := act.RequiredLevel(); ok && level < min {
		return permission.Deny
	}
	return permission.Decide(level, act.RiskLevel())
}

// handleAction aplica la matriz de permisos: ejecuta, pide confirmación al usuario o rechaza.
func (s *Server) handleAction(w http.ResponseWriter, r *http.Request) {
	tok, ok := s.authenticate(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "token inválido")
		return
	}
	if !s.actionLimiter.allow(tok.ID) {
		writeError(w, http.StatusTooManyRequests, "demasiadas acciones en poco tiempo -- esperá unos segundos")
		return
	}
	act, found := s.cfg.Registry.Get(r.PathValue("id"))
	if !found {
		writeError(w, http.StatusNotFound, "acción desconocida")
		return
	}
	var body actionRequest
	if err := decodeBody(w, r, &body); err != nil {
		writeError(w, http.StatusBadRequest, "cuerpo JSON inválido")
		return
	}
	// Se valida antes de decidir: no se molesta al usuario con una petición mal formada.
	if err := act.CheckParams(body.Params); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	level := s.effective(tok)
	decision := decide(level, act)
	s.record(tok.ID, act.ID, level, decision.String(), "")

	switch decision {
	case permission.Deny:
		writeError(w, http.StatusForbidden, "tu nivel de permiso ("+level.String()+") no permite esta acción")
	case permission.AskUser:
		s.ask(w, r, tok.ID, tok.Name, act, body.Params)
	default:
		res, err := s.cfg.Dispatcher.Run(r.Context(), act, body.Params)
		s.record(tok.ID, act.ID, level, "ejecutada", resultSummary(res, err))
		if err != nil {
			writeError(w, http.StatusBadGateway, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"state": pending.StateDone, "result": res})
	}
}

// ask crea una petición pendiente, avisa por SSE a la app y, si el cliente lo pidió con ?wait=N,
// espera la respuesta del usuario hasta N segundos (máximo 120).
func (s *Server) ask(w http.ResponseWriter, r *http.Request, tokenID, tokenName string, act router.Action, params map[string]string) {
	req := s.cfg.Pending.Create(pending.Request{
		TokenID: tokenID, TokenName: tokenName, ActionID: act.ID, Title: act.Title,
		Risk: act.RiskLevel().String(), Params: params,
	})
	s.cfg.Hub.Publish("pending", req)

	wait, _ := strconv.Atoi(r.URL.Query().Get("wait"))
	if wait <= 0 {
		writeJSON(w, http.StatusAccepted, map[string]any{"state": pending.StatePending, "pending_id": req.ID})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(min(wait, 120))*time.Second)
	defer cancel()
	snap, _ := s.cfg.Pending.Wait(ctx, req.ID)
	replyPending(w, snap)
}

// handlePendingGet permite consultar una petición, solo al token que la creó.
func (s *Server) handlePendingGet(w http.ResponseWriter, r *http.Request) {
	tok, ok := s.authenticate(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "token inválido")
		return
	}
	snap, found := s.cfg.Pending.Get(r.PathValue("id"))
	if !found || snap.Request.TokenID != tok.ID {
		writeError(w, http.StatusNotFound, "petición no encontrada")
		return
	}
	replyPending(w, snap)
}

func replyPending(w http.ResponseWriter, snap pending.Snapshot) {
	status := http.StatusAccepted
	switch snap.State {
	case pending.StateDone:
		status = http.StatusOK
	case pending.StateDenied:
		status = http.StatusForbidden
	case pending.StateExpired:
		status = http.StatusRequestTimeout
	}
	writeJSON(w, status, map[string]any{"state": snap.State, "pending_id": snap.Request.ID, "result": snap.Result})
}

func resultSummary(res router.Result, err error) string {
	if err != nil {
		return "error: " + err.Error()
	}
	if res.OK {
		return "ok"
	}
	return "exit=" + strconv.Itoa(res.ExitCode)
}
