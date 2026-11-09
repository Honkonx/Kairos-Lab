package server

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Mejora general/universal (auditoría referencia/vps-hosting/, 2026-11-02):
// GET /v1/events (SSE) solo puede autenticarse por query string porque el EventSource del
// navegador no permite mandar cabeceras personalizadas -- limitación real de la plataforma, no
// de Kairos. El token PERMANENTE no debe viajar repetido en la URL en cada reconexión del
// EventSource (queda en logs/historial cada vez). Este archivo agrega un ticket de un solo uso
// y vida corta: el cliente pide un ticket autenticado por header (Authorization: Bearer), y abre
// el EventSource con ese ticket en la URL en vez del token real -- si el ticket se filtra, expira
// solo, no sirve para nada más y nunca revela el token permanente.
//
// Retrocompatible a propósito: ?token=<token real> sigue aceptándose igual que antes (no se
// quita nada existente), este es un camino adicional más seguro, no un reemplazo forzado.

const liveTicketTTL = 30 * time.Second

type liveTicket struct {
	tokenValue string
	expires    time.Time
}

type liveTicketStore struct {
	mu      sync.Mutex
	tickets map[string]liveTicket
}

func newLiveTicketStore() *liveTicketStore {
	return &liveTicketStore{tickets: make(map[string]liveTicket)}
}

// issue crea un ticket nuevo para tokenValue (el valor en claro ya autenticado por header).
func (s *liveTicketStore) issue(tokenValue string) (string, error) {
	raw := make([]byte, 24)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	ticket := hex.EncodeToString(raw)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked()
	s.tickets[ticket] = liveTicket{tokenValue: tokenValue, expires: time.Now().Add(liveTicketTTL)}
	return ticket, nil
}

// consume devuelve el token real si el ticket es válido y no expiró, y lo invalida (un solo uso).
func (s *liveTicketStore) consume(ticket string) (string, bool) {
	if ticket == "" {
		return "", false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.tickets[ticket]
	delete(s.tickets, ticket) // un solo uso, válido o no
	if !ok || time.Now().After(entry.expires) {
		return "", false
	}
	return entry.tokenValue, true
}

// sweepLocked descarta tickets vencidos; se llama con el lock ya tomado, para no dejar crecer el
// mapa sin límite si algunos tickets nunca se consumen.
func (s *liveTicketStore) sweepLocked() {
	now := time.Now()
	for k, v := range s.tickets {
		if now.After(v.expires) {
			delete(s.tickets, k)
		}
	}
}

// handleEventsTicket emite un ticket de un solo uso para abrir /v1/events sin repetir el token
// real en la URL. Requiere el token real por header -- nunca acepta ?token= acá, para no
// defraudar el propósito (un ticket no puede generar otro ticket indefinidamente desde la URL).
func (s *Server) handleEventsTicket(w http.ResponseWriter, r *http.Request) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		writeError(w, http.StatusUnauthorized, "se requiere Authorization: Bearer para pedir un ticket")
		return
	}
	tokenValue := strings.TrimSpace(h[len("Bearer "):])
	if _, ok := s.cfg.Tokens.Authenticate(tokenValue); !ok {
		writeError(w, http.StatusUnauthorized, "token inválido")
		return
	}
	ticket, err := s.liveTickets.issue(tokenValue)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "no se pudo emitir el ticket")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ticket":     ticket,
		"expires_in": int(liveTicketTTL.Seconds()),
	})
}
