package screen

import (
	"context"
	"fmt"
	"io"
	"time"
)

// fakeConfigPayload simula el primer paquete "de configuración" (SPS/PPS en un stream H.264 real)
// que un visor necesita para empezar a decodificar — en el modo simulado es solo texto, el panel no
// intenta decodificarlo como video, muestra que llegó.
var fakeConfigPayload = []byte("kairos-screen-stub-config")

// stubSource es una fuente simulada: genera paquetes con el mismo formato que una fuente real
// ([EncodeFrame]/[ReadFrame]) sin necesitar ningún dispositivo ni servidor Android — sirve para
// probar el Hub y el relay WebSocket de punta a punta HOY, antes de que exista el servidor de
// pantalla real (ver docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md).
type stubSource struct {
	pr     *io.PipeReader
	pw     *io.PipeWriter
	cancel context.CancelFunc
}

// NewStubSource arranca un generador que escribe un frame "de configuración" y después un frame
// nuevo cada [interval], hasta que el stubSource se cierra. Devuelve un io.ReadWriteCloser listo
// para pasarle a Hub.setSource (o, en pruebas, para leer con ReadFrame directamente).
func NewStubSource(interval time.Duration) io.ReadWriteCloser {
	pr, pw := io.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	s := &stubSource{pr: pr, pw: pw, cancel: cancel}
	go s.generate(ctx, interval)
	return s
}

func (s *stubSource) Read(p []byte) (int, error) { return s.pr.Read(p) }

// Write descarta los mensajes de control: el modo simulado no tiene nada real que inyectar, pero
// tampoco debe fallar — el panel de prueba puede seguir "mandando" controles sin que el relay rompa.
func (s *stubSource) Write(p []byte) (int, error) { return len(p), nil }

func (s *stubSource) Close() error {
	s.cancel()
	_ = s.pw.CloseWithError(io.ErrClosedPipe)
	return nil
}

func (s *stubSource) generate(ctx context.Context, interval time.Duration) {
	cfg := EncodeFrame(Frame{Config: true, Size: int32(len(fakeConfigPayload))}, fakeConfigPayload)
	if _, err := s.pw.Write(cfg); err != nil {
		return
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	var pts int64
	var n int
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			payload := []byte(fmt.Sprintf("frame-simulado-%d", n))
			frame := Frame{PTS: pts, KeyFrame: n%10 == 0, Size: int32(len(payload))}
			if _, err := s.pw.Write(EncodeFrame(frame, payload)); err != nil {
				return
			}
			pts += 33_000 // ~30 fps, en microsegundos, igual que pts real de un codificador de video.
			n++
		}
	}
}
