// Package events es el bus de eventos en memoria que alimenta los flujos SSE.
//
// Cada evento tiene un ámbito (Scope): vacío significa "solo administración" (la app), y un id de token
// significa "además, ese token". Un suscriptor decide qué ámbitos ve con su función de filtro, así un
// token nunca recibe los eventos de otro (confirmaciones ajenas, cambios de política).
package events

import (
	"encoding/json"
	"sync"
	"time"
)

// Event es un mensaje para los clientes conectados.
type Event struct {
	Type string          `json:"type"`
	Time time.Time       `json:"time"`
	Data json.RawMessage `json:"data,omitempty"`
	// Scope no se serializa: es solo para decidir quién puede ver el evento.
	Scope string `json:"-"`
}

// Match decide si un suscriptor recibe un evento.
type Match func(Event) bool

// All entrega todo; es el filtro de la administración (la app).
func All(Event) bool { return true }

// ScopedTo entrega solo los eventos dirigidos a ese token. Un id vacío no recibe nada.
func ScopedTo(tokenID string) Match {
	return func(ev Event) bool { return tokenID != "" && ev.Scope == tokenID }
}

// Hub reparte eventos a los suscriptores. Un cliente lento pierde eventos en vez de frenar al resto.
type Hub struct {
	mu   sync.RWMutex
	subs map[chan Event]Match
}

// subscriberBuffer es cuántos eventos pendientes aguanta un cliente antes de empezar a perderlos.
const subscriberBuffer = 16

// NewHub crea un bus vacío.
func NewHub() *Hub {
	return &Hub{subs: make(map[chan Event]Match)}
}

// Subscribe registra un cliente con su filtro. La función devuelta lo da de baja y cierra el canal.
func (h *Hub) Subscribe(match Match) (<-chan Event, func()) {
	if match == nil {
		match = func(Event) bool { return false } // sin filtro explícito, no se ve nada
	}
	ch := make(chan Event, subscriberBuffer)
	h.mu.Lock()
	h.subs[ch] = match
	h.mu.Unlock()
	var once sync.Once
	return ch, func() {
		once.Do(func() {
			h.mu.Lock()
			delete(h.subs, ch)
			h.mu.Unlock()
			close(ch)
		})
	}
}

// Publish envía un evento solo para administración.
func (h *Hub) Publish(eventType string, data any) { h.publish("", eventType, data) }

// PublishFor envía un evento a la administración y, además, al token indicado.
func (h *Hub) PublishFor(tokenID, eventType string, data any) { h.publish(tokenID, eventType, data) }

func (h *Hub) publish(scope, eventType string, data any) {
	var raw json.RawMessage
	if data != nil {
		b, err := json.Marshal(data)
		if err != nil {
			return
		}
		raw = b
	}
	ev := Event{Type: eventType, Time: time.Now().UTC(), Data: raw, Scope: scope}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for ch, match := range h.subs {
		if !match(ev) {
			continue
		}
		select {
		case ch <- ev:
		default: // cliente lento: se descarta el evento para él
		}
	}
}

// Count devuelve cuántos clientes hay conectados.
func (h *Hub) Count() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.subs)
}
