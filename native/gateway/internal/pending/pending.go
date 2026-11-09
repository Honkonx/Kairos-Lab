// Package pending guarda las acciones que esperan la confirmación del usuario (nivel "pregunta").
//
// Ciclo: pendiente → aprobada → hecha, o pendiente → denegada, o pendiente → expirada.
package pending

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sync"
	"time"
)

// Estados de una petición.
const (
	StatePending  = "pendiente"
	StateApproved = "aprobada"
	StateDone     = "hecha"
	StateDenied   = "denegada"
	StateExpired  = "expirada"
)

// ErrNotFound y ErrNotPending son los errores de Decide.
var (
	ErrNotFound   = errors.New("petición no encontrada")
	ErrNotPending = errors.New("la petición ya no está pendiente")
)

// Request describe lo que el usuario debe aprobar. No lleva valores secretos.
type Request struct {
	ID        string            `json:"id"`
	TokenID   string            `json:"token_id"`
	TokenName string            `json:"token_name"`
	ActionID  string            `json:"action_id"`
	Title     string            `json:"title"`
	Risk      string            `json:"risk"`
	Params    map[string]string `json:"params,omitempty"`
	Created   time.Time         `json:"created"`
}

// Snapshot es el estado visible de una petición.
type Snapshot struct {
	Request Request `json:"request"`
	State   string  `json:"state"`
	Result  any     `json:"result,omitempty"`
}

type entry struct {
	req     Request
	state   string
	result  any
	changed chan struct{} // se cierra y se reemplaza en cada cambio
}

// Store es el almacén, seguro para uso concurrente.
type Store struct {
	mu    sync.Mutex
	ttl   time.Duration
	items map[string]*entry
	now   func() time.Time
}

// NewStore crea un almacén; las peticiones sin decidir expiran pasado ttl.
func NewStore(ttl time.Duration) *Store {
	if ttl <= 0 {
		ttl = 2 * time.Minute
	}
	return &Store{ttl: ttl, items: map[string]*entry{}, now: time.Now}
}

// Create registra una petición nueva y le asigna id y fecha.
func (s *Store) Create(req Request) Request {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("sin entropía: " + err.Error()) // crypto/rand no falla en sistemas soportados
	}
	req.ID = hex.EncodeToString(b[:])
	req.Created = s.now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked()
	s.items[req.ID] = &entry{req: req, state: StatePending, changed: make(chan struct{})}
	return req
}

// Get devuelve el estado actual.
func (s *Store) Get(id string) (Snapshot, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.items[id]
	if !ok {
		return Snapshot{}, false
	}
	s.expireLocked(e)
	return snapshot(e), true
}

// List devuelve las peticiones que siguen pendientes.
func (s *Store) List() []Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []Snapshot
	for _, e := range s.items {
		s.expireLocked(e)
		if e.state == StatePending {
			out = append(out, snapshot(e))
		}
	}
	return out
}

// Decide aprueba o deniega una petición pendiente.
func (s *Store) Decide(id string, approve bool) (Request, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.items[id]
	if !ok {
		return Request{}, ErrNotFound
	}
	s.expireLocked(e)
	if e.state != StatePending {
		return Request{}, ErrNotPending
	}
	if approve {
		s.setLocked(e, StateApproved, nil)
	} else {
		s.setLocked(e, StateDenied, nil)
	}
	return e.req, nil
}

// Complete marca como hecha una petición aprobada y guarda su resultado.
func (s *Store) Complete(id string, result any) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if e, ok := s.items[id]; ok && e.state == StateApproved {
		s.setLocked(e, StateDone, result)
	}
}

// Wait espera hasta que la petición llegue a un estado final (hecha, denegada o expirada) o venza
// ctx. Si vence ctx devuelve el estado en ese momento, sin error.
func (s *Store) Wait(ctx context.Context, id string) (Snapshot, bool) {
	for {
		s.mu.Lock()
		e, ok := s.items[id]
		if !ok {
			s.mu.Unlock()
			return Snapshot{}, false
		}
		s.expireLocked(e)
		snap := snapshot(e)
		ch := e.changed
		until := e.req.Created.Add(s.ttl).Sub(s.now())
		s.mu.Unlock()

		if isFinal(snap.State) {
			return snap, true
		}
		timer := time.NewTimer(max(until, 10*time.Millisecond))
		select {
		case <-ch:
			timer.Stop()
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()
			return snap, true
		}
	}
}

// WaitDecided espera hasta que la petición salga de "pendiente" (aprobada, denegada, hecha o
// expirada) o venza ctx — a diferencia de [Store.Wait] (que solo para en un estado FINAL: hecha/
// denegada/expirada), esto también se detiene en "aprobada". Lo necesita un flujo que, al
// aprobarse, hace su propio trabajo fuera del Dispatcher genérico en vez de dejar que el llamador
// genérico (acción registrada) la complete — ver handlePairRequest en internal/server/pair.go.
func (s *Store) WaitDecided(ctx context.Context, id string) (Snapshot, bool) {
	for {
		s.mu.Lock()
		e, ok := s.items[id]
		if !ok {
			s.mu.Unlock()
			return Snapshot{}, false
		}
		s.expireLocked(e)
		snap := snapshot(e)
		ch := e.changed
		until := e.req.Created.Add(s.ttl).Sub(s.now())
		s.mu.Unlock()

		if snap.State != StatePending {
			return snap, true
		}
		timer := time.NewTimer(max(until, 10*time.Millisecond))
		select {
		case <-ch:
			timer.Stop()
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()
			return snap, true
		}
	}
}

func isFinal(state string) bool {
	return state == StateDone || state == StateDenied || state == StateExpired
}

func snapshot(e *entry) Snapshot {
	return Snapshot{Request: e.req, State: e.state, Result: e.result}
}

func (s *Store) setLocked(e *entry, state string, result any) {
	e.state, e.result = state, result
	close(e.changed)
	e.changed = make(chan struct{})
}

func (s *Store) expireLocked(e *entry) {
	if e.state == StatePending && s.now().After(e.req.Created.Add(s.ttl)) {
		s.setLocked(e, StateExpired, nil)
	}
}

// sweepLocked olvida las peticiones finalizadas hace más de 10 veces el ttl.
func (s *Store) sweepLocked() {
	cutoff := s.now().Add(-10 * s.ttl)
	for id, e := range s.items {
		s.expireLocked(e)
		if isFinal(e.state) && e.req.Created.Before(cutoff) {
			delete(s.items, id)
		}
	}
}
