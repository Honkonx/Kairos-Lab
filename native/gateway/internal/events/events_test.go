package events

import (
	"encoding/json"
	"testing"
	"time"
)

func TestPublishReachesAllSubscribers(t *testing.T) {
	h := NewHub()
	a, cancelA := h.Subscribe(All)
	b, cancelB := h.Subscribe(All)
	defer cancelA()
	defer cancelB()

	h.Publish("estado", map[string]string{"modulo": "n8n"})
	for name, ch := range map[string]<-chan Event{"a": a, "b": b} {
		select {
		case ev := <-ch:
			var data map[string]string
			if err := json.Unmarshal(ev.Data, &data); err != nil || ev.Type != "estado" || data["modulo"] != "n8n" {
				t.Errorf("%s recibió %+v (%v)", name, ev, err)
			}
		case <-time.After(time.Second):
			t.Errorf("%s no recibió el evento", name)
		}
	}
}

func TestScopedSubscriberOnlySeesItsOwnEvents(t *testing.T) {
	h := NewHub()
	admin, cancelAdmin := h.Subscribe(All)
	mine, cancelMine := h.Subscribe(ScopedTo("tok-a"))
	other, cancelOther := h.Subscribe(ScopedTo("tok-b"))
	anon, cancelAnon := h.Subscribe(ScopedTo(""))
	none, cancelNone := h.Subscribe(nil)
	defer cancelAdmin()
	defer cancelMine()
	defer cancelOther()
	defer cancelAnon()
	defer cancelNone()

	h.Publish("pending", "solo administración")
	h.PublishFor("tok-a", "action_done", "para tok-a")

	got := func(ch <-chan Event) []string {
		var types []string
		for {
			select {
			case ev := <-ch:
				types = append(types, ev.Type)
			case <-time.After(50 * time.Millisecond):
				return types
			}
		}
	}
	if g := got(admin); len(g) != 2 {
		t.Errorf("administración debía ver ambos, vio %v", g)
	}
	if g := got(mine); len(g) != 1 || g[0] != "action_done" {
		t.Errorf("tok-a debía ver solo lo suyo, vio %v", g)
	}
	for name, ch := range map[string]<-chan Event{"tok-b": other, "sin id": anon, "sin filtro": none} {
		if g := got(ch); len(g) != 0 {
			t.Errorf("%s no debía ver nada, vio %v", name, g)
		}
	}
}

func TestScopeIsNeverSerialized(t *testing.T) {
	raw, err := json.Marshal(Event{Type: "x", Scope: "tok-secreto"})
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) == "" || json.Valid(raw) == false || contains(string(raw), "tok-secreto") {
		t.Errorf("el ámbito no debe salir al cliente: %s", raw)
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

func TestSlowSubscriberDoesNotBlockPublisher(t *testing.T) {
	h := NewHub()
	_, cancel := h.Subscribe(All) // nadie lee este canal
	defer cancel()

	done := make(chan struct{})
	go func() {
		for i := 0; i < subscriberBuffer*3; i++ {
			h.Publish("x", nil)
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Publish se bloqueó por un cliente lento")
	}
}

func TestCancelRemovesAndClosesAndIsIdempotent(t *testing.T) {
	h := NewHub()
	ch, cancel := h.Subscribe(All)
	if h.Count() != 1 {
		t.Fatalf("Count = %d, quiero 1", h.Count())
	}
	cancel()
	cancel() // no debe entrar en pánico
	if h.Count() != 0 {
		t.Errorf("Count = %d tras cancelar", h.Count())
	}
	if _, open := <-ch; open {
		t.Error("el canal debía quedar cerrado")
	}
	h.Publish("x", nil) // sin suscriptores: no debe fallar
}
