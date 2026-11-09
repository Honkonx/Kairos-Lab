package screen

import (
	"context"
	"io"
	"log/slog"
	"net"
	"testing"
	"time"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

var testSecret = []byte("secreto-de-prueba-no-usar-en-produccion")

// waitUntil sondea [check] hasta que devuelva true o se agote [timeout] — más robusto que un
// time.Sleep fijo para esperar efectos de una goroutine, sobre todo en entornos con I/O lento
// (: esta prueba fue flaky con un Sleep fijo por eso).
func waitUntil(t *testing.T, timeout time.Duration, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if check() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("condición no se cumplió en %s", timeout)
}

// fakeSource es un io.ReadWriteCloser controlado a mano, para probar Hub sin tocar la red.
type fakeSource struct {
	r      *io.PipeReader
	w      *io.PipeWriter
	writes chan []byte
	closed chan struct{}
}

func newFakeSource() *fakeSource {
	r, w := io.Pipe()
	return &fakeSource{r: r, w: w, writes: make(chan []byte, 8), closed: make(chan struct{})}
}

func (f *fakeSource) Read(p []byte) (int, error)  { return f.r.Read(p) }
func (f *fakeSource) Write(p []byte) (int, error) { f.writes <- append([]byte{}, p...); return len(p), nil }
func (f *fakeSource) Close() error {
	close(f.closed)
	return f.w.Close()
}

func (f *fakeSource) pushFrame(frame Frame, payload []byte) {
	_, _ = f.w.Write(EncodeFrame(frame, payload))
}

func TestHubBroadcastsFramesToSubscribers(t *testing.T) {
	h := NewHub(discardLogger(), testSecret)
	src := newFakeSource()
	h.setSource(src)
	defer h.Close()

	ch, cancel := h.Subscribe()
	defer cancel()

	payload := []byte("frame-de-prueba")
	src.pushFrame(Frame{PTS: 5, KeyFrame: true, Size: int32(len(payload))}, payload)

	select {
	case raw := <-ch:
		got, err := DecodeHeader(raw[:HeaderSize])
		if err != nil || got.PTS != 5 || !got.KeyFrame {
			t.Fatalf("frame recibido no coincide: %+v %v", got, err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no llegó ningún frame al visor en 2s")
	}
}

func TestHubSendControlReachesSource(t *testing.T) {
	h := NewHub(discardLogger(), testSecret)
	src := newFakeSource()
	h.setSource(src)
	defer h.Close()

	msg := []byte{0x02, 0x00}
	if err := h.SendControl(msg); err != nil {
		t.Fatalf("SendControl: %v", err)
	}
	select {
	case got := <-src.writes:
		if string(got) != string(msg) {
			t.Errorf("mensaje de control distinto: %v vs %v", got, msg)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("la fuente nunca recibió el mensaje de control")
	}
}

func TestHubSendControlWithoutSourceFails(t *testing.T) {
	h := NewHub(discardLogger(), testSecret)
	if err := h.SendControl([]byte("x")); err != ErrNoSource {
		t.Errorf("sin fuente debe dar ErrNoSource, dio %v", err)
	}
}

func TestHubStubSourceProducesFrames(t *testing.T) {
	h := NewHub(discardLogger(), testSecret)
	h.UseStub(10 * time.Millisecond)
	defer h.Close()

	ch, cancel := h.Subscribe()
	defer cancel()

	// El primer paquete simulado es siempre el "de configuración".
	select {
	case raw := <-ch:
		f, err := DecodeHeader(raw[:HeaderSize])
		if err != nil || !f.Config {
			t.Fatalf("el primer paquete simulado debe ser de configuración: %+v %v", f, err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("el stub nunca mandó el frame de configuración")
	}

	if !h.Connected() {
		t.Error("con un stub activo, Connected() debe ser true")
	}
}

func TestHubViewerCountTracksSubscriptions(t *testing.T) {
	h := NewHub(discardLogger(), testSecret)
	if h.ViewerCount() != 0 {
		t.Fatalf("debe arrancar en 0, está en %d", h.ViewerCount())
	}
	_, cancel1 := h.Subscribe()
	_, cancel2 := h.Subscribe()
	if h.ViewerCount() != 2 {
		t.Fatalf("debe haber 2 visores, hay %d", h.ViewerCount())
	}
	cancel1()
	if h.ViewerCount() != 1 {
		t.Fatalf("debe quedar 1 visor, quedan %d", h.ViewerCount())
	}
	cancel2()
	if h.ViewerCount() != 0 {
		t.Fatalf("debe quedar 0 visores, quedan %d", h.ViewerCount())
	}
}

func TestStartConnectorReconnectsAfterSourceDrops(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	accepted := make(chan net.Conn, 4)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			// El servidor falso de la prueba también tiene que contestar el handshake (ver
			// handshake.go) — si no, StartConnector descartaría la conexión por no autenticada y
			// h.Connected() nunca se pondría en true.
			if err := RespondToHandshake(c, testSecret); err != nil {
				_ = c.Close()
				continue
			}
			accepted <- c
		}
	}()

	h := NewHub(discardLogger(), testSecret)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h.StartConnector(ctx, ln.Addr().String(), 50*time.Millisecond)

	var first net.Conn
	select {
	case first = <-accepted:
	case <-time.After(3 * time.Second):
		t.Fatal("el connector nunca se conectó al listener de prueba")
	}
	waitUntil(t, 2*time.Second, h.Connected)

	// Cortar la primera conexión del lado servidor: el connector debe reintentar y reconectar.
	_ = first.Close()
	select {
	case <-accepted:
	case <-time.After(3 * time.Second):
		t.Fatal("el connector nunca reconectó tras perder la fuente")
	}
}
