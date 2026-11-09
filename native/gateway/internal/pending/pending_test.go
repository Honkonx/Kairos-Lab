package pending

import (
	"context"
	"testing"
	"time"
)

func newReq() Request { return Request{TokenID: "t1", ActionID: "mod.iniciar", Risk: "write"} }

func TestApproveThenComplete(t *testing.T) {
	s := NewStore(time.Minute)
	req := s.Create(newReq())
	if req.ID == "" || req.Created.IsZero() {
		t.Fatalf("Create debía completar id y fecha: %+v", req)
	}
	if _, err := s.Decide(req.ID, true); err != nil {
		t.Fatal(err)
	}
	snap, _ := s.Get(req.ID)
	if snap.State != StateApproved {
		t.Fatalf("estado = %s", snap.State)
	}
	s.Complete(req.ID, "listo")
	snap, _ = s.Get(req.ID)
	if snap.State != StateDone || snap.Result != "listo" {
		t.Errorf("snap = %+v", snap)
	}
}

func TestDecideTwiceAndUnknown(t *testing.T) {
	s := NewStore(time.Minute)
	req := s.Create(newReq())
	if _, err := s.Decide(req.ID, false); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Decide(req.ID, true); err != ErrNotPending {
		t.Errorf("decidir dos veces: err = %v, quiero ErrNotPending", err)
	}
	if _, err := s.Decide("nope", true); err != ErrNotFound {
		t.Errorf("id desconocido: err = %v, quiero ErrNotFound", err)
	}
	// Aprobar no puede resucitar una denegada.
	s.Complete(req.ID, "x")
	if snap, _ := s.Get(req.ID); snap.State != StateDenied {
		t.Errorf("una denegada cambió de estado: %s", snap.State)
	}
}

func TestExpiry(t *testing.T) {
	s := NewStore(time.Minute)
	now := time.Now()
	s.now = func() time.Time { return now }
	req := s.Create(newReq())
	s.now = func() time.Time { return now.Add(2 * time.Minute) }
	if snap, _ := s.Get(req.ID); snap.State != StateExpired {
		t.Errorf("estado = %s, quiero expirada", snap.State)
	}
	if _, err := s.Decide(req.ID, true); err != ErrNotPending {
		t.Errorf("aprobar una expirada: err = %v", err)
	}
	if len(s.List()) != 0 {
		t.Error("una expirada no debe listarse como pendiente")
	}
}

func TestWaitReturnsWhenDecided(t *testing.T) {
	s := NewStore(time.Minute)
	req := s.Create(newReq())
	go func() {
		time.Sleep(30 * time.Millisecond)
		s.Decide(req.ID, true)
		s.Complete(req.ID, "ok")
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	snap, ok := s.Wait(ctx, req.ID)
	if !ok || snap.State != StateDone || snap.Result != "ok" {
		t.Errorf("Wait = %+v, %v", snap, ok)
	}
}

func TestWaitStopsAtContextAndReportsPending(t *testing.T) {
	s := NewStore(time.Minute)
	req := s.Create(newReq())
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	snap, ok := s.Wait(ctx, req.ID)
	if !ok || snap.State != StatePending {
		t.Errorf("Wait = %+v, %v; quiero pendiente", snap, ok)
	}
	if _, ok := s.Wait(ctx, "nope"); ok {
		t.Error("una petición inexistente no debe encontrarse")
	}
}
