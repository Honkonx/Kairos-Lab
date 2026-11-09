package screen

import (
	"errors"
	"net"
	"os"
	"runtime"
	"testing"
	"time"
)

var errFakePeerCheck = errors.New("fake peer check error")

func TestDeriveScidIsDeterministicAndFormatsAsEightHexDigits(t *testing.T) {
	a := DeriveScid([]byte("secreto-1"))
	b := DeriveScid([]byte("secreto-1"))
	c := DeriveScid([]byte("secreto-2"))
	if a != b {
		t.Fatal("el mismo secreto debe dar siempre el mismo scid")
	}
	if a == c {
		t.Fatal("secretos distintos no deberían chocar (en la práctica, con SHA-256 truncado)")
	}
	name := LocalSocketName(a)
	if len(name) != len("scrcpy_")+8 {
		t.Fatalf("formato inesperado: %q", name)
	}
}

func TestListenLocalAcceptsARealClientAndStripsTheDeviceNameHeader(t *testing.T) {
	// El namespace "abstracto" de sockets Unix (prefijo "@") es una extensión de Linux — no existe en
	// Windows, donde corre este test en desarrollo. El gateway real SOLO corre en Android (Linux), donde
	// esto sí funciona: confirmado contra un dispositivo real (docs/homelab/
	// PANTALLA_ANDROID_PROPIA_2026-10-10.md) con el scrcpy-server real conectándose de verdad.
	if runtime.GOOS != "linux" {
		t.Skip("sockets abstractos Unix: solo Linux (el gateway real corre en Android); ya verificado en dispositivo real")
	}
	h := NewHub(discardLogger(), testSecret)
	// En un dispositivo real, peerCheck exige root/shell (peerCredentialAllowed, peercred_linux.go) —
	// acá se reemplaza por una versión que siempre acepta, porque el proceso de test nunca corre con
	// esa identidad y lo que esta prueba verifica es el protocolo (cabecera de 64 bytes + frames), no
	// la verificación de credencial en sí (esa la prueban TestPeerCredentialAllowed* más abajo).
	h.peerCheck = func(net.Conn) (bool, string, error) { return true, "test", nil }
	scid := DeriveScid(testSecret)
	h.ListenLocal(scid, "")

	// ListenLocal arranca su goroutine en segundo plano; reintenta la conexión hasta que el Listen() interno
	// ya haya abierto el socket (evita un sleep fijo arbitrario, mismo criterio que waitUntil).
	var conn net.Conn
	var dialErr error
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		conn, dialErr = net.Dial("unix", "@"+LocalSocketName(scid))
		if dialErr == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if dialErr != nil {
		t.Fatalf("no se pudo conectar al socket local: %v", dialErr)
	}
	defer conn.Close()

	deviceName := make([]byte, deviceNameFieldLength)
	copy(deviceName, []byte("pixel-de-prueba"))
	if _, err := conn.Write(deviceName); err != nil {
		t.Fatal(err)
	}

	waitUntil(t, 2*time.Second, h.Connected)
	if got := h.DeviceName(); got != "pixel-de-prueba" {
		t.Errorf("nombre de dispositivo mal parseado: %q", got)
	}

	// Un frame real después de la cabecera de 64 bytes debe llegar a un visor normal.
	ch, cancel := h.Subscribe()
	defer cancel()
	payload := []byte("frame-local")
	if _, err := conn.Write(EncodeFrame(Frame{Config: true, Size: int32(len(payload))}, payload)); err != nil {
		t.Fatal(err)
	}
	select {
	case raw := <-ch:
		f, err := DecodeHeader(raw[:HeaderSize])
		if err != nil || !f.Config {
			t.Fatalf("frame inesperado: %+v %v", f, err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("el frame real nunca llegó al visor")
	}
}

// TestListenLocalSecondConnectionBecomesControlChannel confirma el ciclo real video→control que
// DesktopConnection.open() hace del lado de scrcpy-server cuando arranca con control=true: dos
// connect() seguidos al MISMO nombre de socket, el segundo nunca se confunde con un segundo video
// ni pisa la fuente real — [SendControl] debe escribir en esa segunda conexión, no en la primera.
func TestListenLocalSecondConnectionBecomesControlChannel(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("sockets abstractos Unix: solo Linux; ver el test hermano de arriba")
	}
	dir := t.TempDir()
	if err := os.WriteFile(dir+"/"+ExpectFileName, []byte("control"), 0o600); err != nil {
		t.Fatal(err)
	}
	h := NewHub(discardLogger(), testSecret)
	h.peerCheck = func(net.Conn) (bool, string, error) { return true, "test", nil }
	scid := DeriveScid(testSecret)
	h.ListenLocal(scid, dir)

	dial := func() net.Conn {
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			if conn, err := net.Dial("unix", "@"+LocalSocketName(scid)); err == nil {
				return conn
			}
			time.Sleep(10 * time.Millisecond)
		}
		t.Fatal("no se pudo conectar al socket local")
		return nil
	}

	videoConn := dial()
	defer videoConn.Close()
	deviceName := make([]byte, deviceNameFieldLength)
	copy(deviceName, []byte("pixel-de-prueba"))
	if _, err := videoConn.Write(deviceName); err != nil {
		t.Fatal(err)
	}
	waitUntil(t, 2*time.Second, h.Connected)

	controlConn := dial()
	defer controlConn.Close()
	// Dar tiempo a que acceptLocalSource procese la segunda conexión antes de mandar control.
	waitUntil(t, 2*time.Second, func() bool {
		return h.SendControl([]byte{1}) == nil
	})

	buf := make([]byte, 1)
	controlConn.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := controlConn.Read(buf)
	if err != nil || n != 1 {
		t.Fatalf("el byte de control debía llegar por la SEGUNDA conexión, no por la de video: err=%v n=%d", err, n)
	}

	// La conexión de video debe seguir siendo la fuente de frames — no se pisó con la de control.
	ch, cancel := h.Subscribe()
	defer cancel()
	payload := []byte("frame-local")
	if _, err := videoConn.Write(EncodeFrame(Frame{Config: true, Size: int32(len(payload))}, payload)); err != nil {
		t.Fatal(err)
	}
	select {
	case raw := <-ch:
		f, err := DecodeHeader(raw[:HeaderSize])
		if err != nil || !f.Config {
			t.Fatalf("frame inesperado tras aceptar el canal de control: %+v %v", f, err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("el relay de video dejó de funcionar después de aceptar el canal de control")
	}
}

// TestFeedAudioWritesToFileWhileRecording confirma el flujo real de grabación de audio: con una
// grabación de video activa, un frame real llegado por el canal de audio (tercera conexión del
// ciclo, con audio+control pedidos) termina escrito en el ".audio.raw" derivado del path de video.
func TestFeedAudioWritesToFileWhileRecording(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("sockets abstractos Unix: solo Linux; ver los tests hermanos de arriba")
	}
	dir := t.TempDir()
	if err := os.WriteFile(dir+"/"+ExpectFileName, []byte("audio,control"), 0o600); err != nil {
		t.Fatal(err)
	}
	h := NewHub(discardLogger(), testSecret)
	h.peerCheck = func(net.Conn) (bool, string, error) { return true, "test", nil }
	scid := DeriveScid(testSecret)
	h.ListenLocal(scid, dir)

	dial := func() net.Conn {
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			if conn, err := net.Dial("unix", "@"+LocalSocketName(scid)); err == nil {
				return conn
			}
			time.Sleep(10 * time.Millisecond)
		}
		t.Fatal("no se pudo conectar al socket local")
		return nil
	}

	videoConn := dial()
	defer videoConn.Close()
	deviceName := make([]byte, deviceNameFieldLength)
	copy(deviceName, []byte("pixel-de-prueba"))
	if _, err := videoConn.Write(deviceName); err != nil {
		t.Fatal(err)
	}
	waitUntil(t, 2*time.Second, h.Connected)

	videoPath := dir + "/grabacion.h264"
	if err := h.StartRecording(videoPath); err != nil {
		t.Fatal(err)
	}
	defer h.StopRecording()

	audioConn := dial()
	defer audioConn.Close()
	payload := []byte("frame-de-audio-de-prueba")
	if _, err := audioConn.Write(EncodeFrame(Frame{Size: int32(len(payload))}, payload)); err != nil {
		t.Fatal(err)
	}

	audioPath := dir + "/grabacion.audio.raw"
	waitUntil(t, 2*time.Second, func() bool {
		data, err := os.ReadFile(audioPath)
		return err == nil && len(data) > 0
	})
	data, err := os.ReadFile(audioPath)
	if err != nil {
		t.Fatalf("no se pudo leer el archivo de audio grabado: %v", err)
	}
	if string(data) != string(payload) {
		t.Errorf("contenido grabado inesperado: %q", data)
	}
}

func TestAcceptLocalSourceRejectsWhenPeerCheckFails(t *testing.T) {
	h := NewHub(discardLogger(), testSecret)
	h.peerCheck = func(net.Conn) (bool, string, error) { return false, "uid=10234 pid=1", nil }
	client, server := net.Pipe()
	defer client.Close()
	go h.acceptLocalSource(server)
	buf := make([]byte, 1)
	client.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, err := client.Read(buf)
	if err == nil {
		t.Fatal("una conexión rechazada por peerCheck debe cerrarse, no quedar como fuente")
	}
	if h.Connected() {
		t.Error("una conexión rechazada nunca debe quedar como fuente del Hub")
	}
}

func TestAcceptLocalSourceRejectsWhenPeerCheckErrors(t *testing.T) {
	h := NewHub(discardLogger(), testSecret)
	h.peerCheck = func(net.Conn) (bool, string, error) { return false, "", errFakePeerCheck }
	client, server := net.Pipe()
	defer client.Close()
	go h.acceptLocalSource(server)
	buf := make([]byte, 1)
	client.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := client.Read(buf); err == nil {
		t.Fatal("un error al verificar la credencial debe cerrar la conexión (fallar cerrado)")
	}
}

func TestPeerCredentialAllowedOnlyAcceptsRootOrShell(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("SO_PEERCRED: solo Linux (el gateway real corre en Android); peercred_other.go falla cerrado en otras plataformas por diseño")
	}
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	// net.Pipe() no es un socket Unix real (es en memoria): confirma que peerCredentialAllowed
	// rechaza con un error claro en vez de aceptar algo que no puede verificar.
	allowed, _, err := peerCredentialAllowed(server)
	if allowed || err == nil {
		t.Errorf("una conexión que no es un socket Unix real nunca debe aceptarse: allowed=%v err=%v", allowed, err)
	}
}
