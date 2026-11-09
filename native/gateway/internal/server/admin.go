package server

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/auth"
	"github.com/honkonx/kairos-dev/native/gateway/internal/pending"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
	"github.com/honkonx/kairos-dev/native/gateway/internal/router"
)

func (s *Server) handleAdminListTokens(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"tokens": s.cfg.Tokens.List()})
}

type createTokenRequest struct {
	Name  string           `json:"name"`
	Level permission.Level `json:"level"`
	// ExpiresInHours, opcional (0/ausente = sin vencimiento, comportamiento histórico). Hallazgo
	// real de docs/referencias/pc-movil/: "caducidad de tokens -- hoy no vencen nunca".
	ExpiresInHours float64 `json:"expiresInHours,omitempty"`
}

// handleAdminCreateToken crea un token. El valor en claro viaja solo en esta respuesta.
func (s *Server) handleAdminCreateToken(w http.ResponseWriter, r *http.Request) {
	var body createTokenRequest
	if err := decodeBody(w, r, &body); err != nil {
		writeError(w, http.StatusBadRequest, "cuerpo JSON inválido: "+err.Error())
		return
	}
	if body.ExpiresInHours < 0 {
		writeError(w, http.StatusBadRequest, "expiresInHours no puede ser negativo")
		return
	}
	ttl := time.Duration(body.ExpiresInHours * float64(time.Hour))
	secret, tok, err := s.cfg.Tokens.CreateWithTTL(body.Name, body.Level, ttl)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.record(tok.ID, "admin:token.crear", body.Level, "ejecutada", tok.Name)
	resp := map[string]any{"token": secret, "id": tok.ID, "name": tok.Name, "level": tok.Level}
	if tok.ExpiresAt != nil {
		resp["expiresAt"] = tok.ExpiresAt
	}
	writeJSON(w, http.StatusCreated, resp)
}

func (s *Server) handleAdminRevokeToken(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	switch err := s.cfg.Tokens.Revoke(id); {
	case errors.Is(err, auth.ErrNotFound):
		writeError(w, http.StatusNotFound, "token no encontrado")
	case err != nil:
		writeError(w, http.StatusInternalServerError, err.Error())
	default:
		s.record(id, "admin:token.revocar", permission.Read, "ejecutada", "")
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	}
}

func (s *Server) handleAdminGetPolicy(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.cfg.Policy.Get())
}

// handleAdminSetPolicy cambia el techo de permisos y la casilla de responsabilidad.
func (s *Server) handleAdminSetPolicy(w http.ResponseWriter, r *http.Request) {
	var p permission.Policy
	if err := decodeBody(w, r, &p); err != nil {
		writeError(w, http.StatusBadRequest, "cuerpo JSON inválido: "+err.Error())
		return
	}
	if err := s.cfg.Policy.Set(p); err != nil {
		writeError(w, http.StatusInternalServerError, "no se pudo guardar la política: "+err.Error())
		return
	}
	s.record("admin", "admin:politica", p.Max, "ejecutada", "responsable="+strconv.FormatBool(p.Responsible))
	s.cfg.Hub.Publish("policy", p)
	writeJSON(w, http.StatusOK, p)
}

func (s *Server) handleAdminListPending(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"pending": s.cfg.Pending.List()})
}

type decideRequest struct {
	Approve bool `json:"approve"`
}

// handleAdminDecide es la respuesta del usuario a una confirmación. Al aprobar, el gateway ejecuta
// la acción por su cuenta y deja el resultado en la petición.
func (s *Server) handleAdminDecide(w http.ResponseWriter, r *http.Request) {
	var body decideRequest
	if err := decodeBody(w, r, &body); err != nil {
		writeError(w, http.StatusBadRequest, "cuerpo JSON inválido: "+err.Error())
		return
	}
	id := r.PathValue("id")
	req, err := s.cfg.Pending.Decide(id, body.Approve)
	switch {
	case errors.Is(err, pending.ErrNotFound):
		writeError(w, http.StatusNotFound, err.Error())
		return
	case errors.Is(err, pending.ErrNotPending):
		writeError(w, http.StatusConflict, err.Error())
		return
	case err != nil:
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	decision := "denegada"
	if body.Approve {
		decision = "aprobada"
		if act, ok := s.cfg.Registry.Get(req.ActionID); ok {
			go s.runApproved(id, req, act)
		}
	}
	s.record(req.TokenID, req.ActionID, permission.Ask, decision, "")
	writeJSON(w, http.StatusOK, map[string]string{"state": decision})
}

// runApproved ejecuta una acción aprobada fuera de la petición HTTP y publica el resultado.
func (s *Server) runApproved(id string, req pending.Request, act router.Action) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	res, err := s.cfg.Dispatcher.Run(ctx, act, req.Params)
	out := any(res)
	if err != nil {
		out = map[string]string{"error": err.Error()}
	}
	s.cfg.Pending.Complete(id, out)
	s.record(req.TokenID, req.ActionID, permission.Ask, "ejecutada", resultSummary(res, err))
	// Lo recibe la administración y el token que pidió la acción; ningún otro.
	s.cfg.Hub.PublishFor(req.TokenID, "action_done", map[string]any{"pending_id": id, "action": req.ActionID, "result": out})
}

func (s *Server) handleAdminAudit(w http.ResponseWriter, r *http.Request) {
	n, _ := strconv.Atoi(r.URL.Query().Get("n"))
	if n <= 0 || n > 500 {
		n = 50
	}
	entries, err := s.cfg.Audit.Tail(n)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"entries": entries})
}

// handleAdminShutdown apaga el gateway de forma ordenada. Sirve para que la app lo detenga aunque haya sobrevivido
// a un reinicio de la app (sin tener el proceso hijo a mano).
func (s *Server) handleAdminShutdown(w http.ResponseWriter, _ *http.Request) {
	if s.cfg.OnShutdown == nil {
		writeError(w, http.StatusNotImplemented, "el apagado remoto no está disponible")
		return
	}
	s.record("admin", "admin:apagar", permission.Read, "ejecutada", "")
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	go s.cfg.OnShutdown()
}
