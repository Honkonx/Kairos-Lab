package server

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/events"
)

// heartbeat mantiene viva la conexión SSE a través de proxies y túneles.
const heartbeat = 15 * time.Second

// handleEvents entrega a un token solo los eventos dirigidos a él (p. ej. el resultado de su propia
// confirmación). Los eventos de administración (confirmaciones de todos, cambios de política) no
// salen por aquí: ver handleAdminEvents.
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	tok, ok := s.authenticate(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "token inválido")
		return
	}
	s.streamEvents(w, r, events.ScopedTo(tok.ID))
}

// handleAdminEvents es el flujo completo para la app: confirmaciones pendientes de cualquier token,
// política y resultados.
func (s *Server) handleAdminEvents(w http.ResponseWriter, r *http.Request) {
	s.streamEvents(w, r, events.All)
}

func (s *Server) streamEvents(w http.ResponseWriter, r *http.Request, match events.Match) {
	rc := http.NewResponseController(w)
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	if _, err := fmt.Fprint(w, ": conectado\n\n"); err != nil || rc.Flush() != nil {
		return
	}

	ch, cancel := s.cfg.Hub.Subscribe(match)
	defer cancel()
	tick := time.NewTicker(heartbeat)
	defer tick.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-tick.C:
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil || rc.Flush() != nil {
				return
			}
		case ev, open := <-ch:
			if !open {
				return
			}
			data, err := json.Marshal(ev)
			if err != nil {
				continue
			}
			if _, err := fmt.Fprintf(w, "event: %s\ndata: %s\n\n", ev.Type, data); err != nil || rc.Flush() != nil {
				return
			}
		}
	}
}
