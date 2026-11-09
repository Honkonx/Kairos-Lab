// Package server expone la API HTTP del gateway: acciones con permisos, confirmaciones, eventos SSE,
// proxy hacia servicios locales y administración (solo para la app).
package server

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"

	"github.com/honkonx/kairos-dev/native/gateway/internal/audit"
	"github.com/honkonx/kairos-dev/native/gateway/internal/auth"
	"github.com/honkonx/kairos-dev/native/gateway/internal/events"
	"github.com/honkonx/kairos-dev/native/gateway/internal/pending"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
	"github.com/honkonx/kairos-dev/native/gateway/internal/router"
	"github.com/honkonx/kairos-dev/native/gateway/internal/screen"
)

// maxBody limita el cuerpo de las peticiones JSON.
const maxBody = 64 * 1024

// Config reúne las dependencias del servidor.
type Config struct {
	Version string
	// AdminSecret protege /v1/admin/*; vacío desactiva la administración por completo.
	AdminSecret string
	Tokens      *auth.Store
	Policy      *permission.Store
	Registry    *router.Registry
	Dispatcher  router.Dispatcher
	Audit       *audit.Log
	Hub         *events.Hub
	Pending     *pending.Store
	// Screen es el relay del espejo de pantalla propio (ver docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md).
	// nil desactiva /v1/screen/ws por completo (404) — igual que AdminSecret vacío desactiva la administración.
	Screen *screen.Hub
	Logger *slog.Logger
	// OnShutdown se llama (en otra goroutine) cuando la app pide apagar el gateway por /v1/admin/shutdown;
	// nil = apagado remoto no disponible.
	OnShutdown func()
}

// Server implementa http.Handler.
type Server struct {
	cfg           Config
	mux           *http.ServeMux
	pairCooldown  *pairCooldown
	liveTickets   *liveTicketStore
	actionLimiter *actionRateLimiter
}

// New arma el servidor y registra las rutas.
func New(cfg Config) *Server {
	if cfg.Logger == nil {
		cfg.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	s := &Server{
		cfg: cfg, mux: http.NewServeMux(),
		pairCooldown: newPairCooldown(), liveTickets: newLiveTicketStore(),
		actionLimiter: newActionRateLimiter(actionRateLimitMax, actionRateLimitWindow),
	}
	s.mux.HandleFunc("GET /{$}", s.handleHome)
	s.mux.HandleFunc("GET /panel", s.handlePanel)
	s.mux.HandleFunc("GET /panel/screen", s.handleScreenWindow)
	s.mux.HandleFunc("GET /healthz", s.handleHealth)
	s.mux.HandleFunc("GET /v1/me", s.handleMe)
	s.mux.HandleFunc("GET /v1/actions", s.handleListActions)
	s.mux.HandleFunc("POST /v1/actions/{id}", s.handleAction)
	s.mux.HandleFunc("GET /v1/pending/{id}", s.handlePendingGet)
	s.mux.HandleFunc("GET /v1/events", s.handleEvents)
	s.mux.HandleFunc("POST /v1/events/ticket", s.handleEventsTicket)
	s.mux.HandleFunc("GET /v1/screen/ws", s.handleScreenWS)
	// Sin autenticar a propósito -- quien pide emparejarse todavía no tiene token, ver pair.go.
	s.mux.HandleFunc("POST /v1/pair", s.handlePairRequest)
	s.mux.HandleFunc("/proxy/{name}/{path...}", s.handleProxy)

	s.mux.HandleFunc("GET /v1/admin/tokens", s.admin(s.handleAdminListTokens))
	s.mux.HandleFunc("POST /v1/admin/tokens", s.admin(s.handleAdminCreateToken))
	s.mux.HandleFunc("DELETE /v1/admin/tokens/{id}", s.admin(s.handleAdminRevokeToken))
	s.mux.HandleFunc("GET /v1/admin/policy", s.admin(s.handleAdminGetPolicy))
	s.mux.HandleFunc("PUT /v1/admin/policy", s.admin(s.handleAdminSetPolicy))
	s.mux.HandleFunc("GET /v1/admin/pending", s.admin(s.handleAdminListPending))
	s.mux.HandleFunc("POST /v1/admin/pending/{id}", s.admin(s.handleAdminDecide))
	s.mux.HandleFunc("GET /v1/admin/audit", s.admin(s.handleAdminAudit))
	s.mux.HandleFunc("GET /v1/admin/events", s.admin(s.handleAdminEvents))
	s.mux.HandleFunc("POST /v1/admin/shutdown", s.admin(s.handleAdminShutdown))
	return s
}

// ServeHTTP aplica las cabeceras comunes y delega en el mux.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	if !strings.HasPrefix(r.URL.Path, "/proxy/") {
		h.Set("Cache-Control", "no-store")
		h.Set("Content-Security-Policy", "default-src 'none'")
	}
	s.mux.ServeHTTP(w, r)
}

// authenticate lee el token de "Authorization: Bearer". Solo para GET (EventSource y enlaces no
// pueden mandar cabeceras) se acepta también ?token= -- o, preferido, un ticket de un solo uso de
// corta vida emitido por POST /v1/events/ticket (ver events_ticket.go), que nunca revela el token
// real si queda en un log o en el historial del navegador.
func (s *Server) authenticate(r *http.Request) (auth.Token, bool) {
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		return s.cfg.Tokens.Authenticate(strings.TrimSpace(h[len("Bearer "):]))
	}
	if r.Method == http.MethodGet {
		if q := r.URL.Query().Get("token"); q != "" {
			if real, ok := s.liveTickets.consume(q); ok {
				return s.cfg.Tokens.Authenticate(real)
			}
			return s.cfg.Tokens.Authenticate(q)
		}
	}
	return auth.Token{}, false
}

// effective devuelve el nivel real de un token bajo la política vigente.
func (s *Server) effective(t auth.Token) permission.Level {
	return s.cfg.Policy.Get().Effective(t.Level)
}

// admin protege un handler con el secreto de la app, comparado en tiempo constante.
func (s *Server) admin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// La administración es solo de la app, en el propio teléfono: aunque el gateway escuche en la red local
		// o detrás de un túnel (cuyas conexiones llegan desde loopback pero traen cabeceras de reenvío), no
		// se atiende ni se prueba el secreto.
		if !fromLocalApp(r) {
			writeError(w, http.StatusForbidden, "administración no autorizada")
			return
		}
		got := r.Header.Get("X-Kairos-Admin")
		if s.cfg.AdminSecret == "" || subtle.ConstantTimeCompare([]byte(got), []byte(s.cfg.AdminSecret)) != 1 {
			writeError(w, http.StatusForbidden, "administración no autorizada")
			return
		}
		next(w, r)
	}
}

// record escribe en la auditoría; un fallo del registro no frena la petición pero se avisa en el log.
func (s *Server) record(tokenID, action string, level permission.Level, decision, result string) {
	err := s.cfg.Audit.Write(audit.Entry{TokenID: tokenID, Action: action, Level: level.String(), Decision: decision, Result: result})
	if err != nil {
		s.cfg.Logger.Warn("no se pudo escribir la auditoría", "error", err)
	}
}

// handleHealth responde "ok". Si trae ?challenge=<texto> y la administración está activa, agrega
// proof = HMAC-SHA256(AdminSecret, texto): así la app comprueba que quien responde en el puerto es su gateway y no
// otra app que ocupó el puerto, antes de enviarle el secreto de administración.
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{"ok": true, "version": s.cfg.Version}
	if c := r.URL.Query().Get("challenge"); c != "" && len(c) <= 128 && s.cfg.AdminSecret != "" {
		mac := hmac.New(sha256.New, []byte(s.cfg.AdminSecret))
		mac.Write([]byte(c))
		out["proof"] = hex.EncodeToString(mac.Sum(nil))
	}
	writeJSON(w, http.StatusOK, out)
}

// fromLocalApp es true solo para conexiones directas desde loopback sin cabeceras de reenvío.
func fromLocalApp(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return false
	}
	for _, h := range []string{"X-Forwarded-For", "Forwarded", "X-Real-Ip", "Cf-Connecting-Ip", "X-Forwarded-Host", "X-Forwarded-Proto"} {
		if r.Header.Get(h) != "" {
			return false
		}
	}
	return true
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	tok, ok := s.authenticate(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "token inválido")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"id": tok.ID, "name": tok.Name,
		"level": tok.Level, "effective_level": s.effective(tok),
	})
}

// decodeBody lee un JSON limitado; un cuerpo vacío es válido y deja v sin tocar.
func decodeBody(w http.ResponseWriter, r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil && !errors.Is(err, io.EOF) {
		return err
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
