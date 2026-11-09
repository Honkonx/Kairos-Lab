package server

import (
	"bufio"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/audit"
	"github.com/honkonx/kairos-dev/native/gateway/internal/auth"
	"github.com/honkonx/kairos-dev/native/gateway/internal/events"
	"github.com/honkonx/kairos-dev/native/gateway/internal/pending"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
	"github.com/honkonx/kairos-dev/native/gateway/internal/router"
	"github.com/honkonx/kairos-dev/native/gateway/internal/screen"
)

// screenWSEnv junta un httptest.Server con el *auth.Store que lo alimenta, para poder crear
// tokens de distintos niveles en cada caso de prueba — separado de newEnv (server_test.go) porque
// ningún otro test del paquete necesita el Hub de pantalla.
type screenWSEnv struct {
	srv    *httptest.Server
	tokens *auth.Store
}

func newScreenWSEnv(t *testing.T, hub *screen.Hub, policy permission.Policy) *screenWSEnv {
	t.Helper()
	dir := t.TempDir()
	reg, err := router.NewRegistry(router.Manifest{})
	if err != nil {
		t.Fatal(err)
	}
	tokens, err := auth.Open(filepath.Join(dir, "tokens.json"))
	if err != nil {
		t.Fatal(err)
	}
	pol, _ := permission.OpenStore(filepath.Join(dir, "policy.json"))
	if err := pol.Set(policy); err != nil {
		t.Fatal(err)
	}
	cfg := Config{
		Version: "test", Tokens: tokens, Policy: pol, Registry: reg,
		Audit: audit.Open(filepath.Join(dir, "audit.log"), 0),
		Hub:   events.NewHub(), Pending: pending.NewStore(time.Minute),
		Screen: hub,
	}
	srv := httptest.NewServer(New(cfg))
	t.Cleanup(srv.Close)
	return &screenWSEnv{srv: srv, tokens: tokens}
}

// plainGet hace un GET normal (sin upgrade), opcionalmente con el token ofrecido como subprotocolo
// de WebSocket (como lo hace el panel real) — alcanza para probar los rechazos de permisos/token,
// que pasan todos antes de intentar el handshake de websocket. protocolToken == "" no manda la
// cabecera en absoluto (simula un cliente que nunca ofreció ningún token).
func plainGet(t *testing.T, url, protocolToken string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	if protocolToken != "" {
		req.Header.Set("Sec-WebSocket-Protocol", "kairos-token."+protocolToken)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp
}

func TestScreenWSRejectsWithoutScreenConfigured(t *testing.T) {
	e := newScreenWSEnv(t, nil, permission.Policy{Max: permission.Full, Responsible: true})
	secret, _, _ := e.tokens.Create("dueño", permission.Full)
	resp := plainGet(t, e.srv.URL+"/v1/screen/ws", secret)
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("sin Hub configurado debe dar 404, dio %d", resp.StatusCode)
	}
}

func TestScreenWSRejectsBelowFullLevel(t *testing.T) {
	hub := screen.NewHub(nil, nil)
	e := newScreenWSEnv(t, hub, permission.Policy{Max: permission.Semi, Responsible: true})
	secret, _, _ := e.tokens.Create("dueño", permission.Semi)
	resp := plainGet(t, e.srv.URL+"/v1/screen/ws", secret)
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("con nivel semi-total (no total) debe dar 403, dio %d", resp.StatusCode)
	}
}

func TestScreenWSRejectsWithoutToken(t *testing.T) {
	hub := screen.NewHub(nil, nil)
	e := newScreenWSEnv(t, hub, permission.Policy{Max: permission.Full, Responsible: true})
	resp := plainGet(t, e.srv.URL+"/v1/screen/ws", "")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("sin token debe dar 401, dio %d", resp.StatusCode)
	}
}

// Revisión de seguridad real: el token en la URL (?token=) era el único mecanismo antes
// de este fix — un token ahí queda en el historial del navegador y en logs de acceso de cualquier
// túnel/proxy en el medio, demasiado para la acción de mayor riesgo del gateway. Ahora NO hay
// respaldo por query string: esta prueba confirma que un token válido puesto solo en la URL, sin el
// subprotocolo, se rechaza igual que si no hubiera ningún token.
func TestScreenWSIgnoresTokenInQueryString(t *testing.T) {
	hub := screen.NewHub(nil, nil)
	e := newScreenWSEnv(t, hub, permission.Policy{Max: permission.Full, Responsible: true})
	secret, _, _ := e.tokens.Create("dueño", permission.Full)
	resp := plainGet(t, e.srv.URL+"/v1/screen/ws?token="+secret, "")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("un token solo en la URL ya no debe autenticar nada, dio %d (quiero 401)", resp.StatusCode)
	}
}

// TestScreenWSRelaysStubFrames es la prueba de punta a punta: con el Hub en modo simulado
// (--screen-stub), un visor que completa el handshake real de WebSocket recibe el primer paquete
// (el "de configuración") que genera el stub — sin ningún dispositivo Android real.
func TestScreenWSRelaysStubFrames(t *testing.T) {
	hub := screen.NewHub(nil, nil)
	hub.UseStub(5 * time.Millisecond)
	defer hub.Close()
	e := newScreenWSEnv(t, hub, permission.Policy{Max: permission.Full, Responsible: true})
	secret, _, _ := e.tokens.Create("dueño", permission.Full)

	host := e.srv.URL[len("http://"):]
	conn, err := net.DialTimeout("tcp", host, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	req := "GET /v1/screen/ws HTTP/1.1\r\n" +
		"Host: " + host + "\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
		"Sec-WebSocket-Protocol: kairos-token." + secret + "\r\n" +
		"Sec-WebSocket-Version: 13\r\n\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		t.Fatal(err)
	}
	br := bufio.NewReader(conn)
	statusLine, err := br.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	if len(statusLine) < 12 || statusLine[:12] != "HTTP/1.1 101" {
		t.Fatalf("esperaba 101 Switching Protocols, llegó %q", statusLine)
	}
	sawEchoedProtocol := false
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			t.Fatal(err)
		}
		if line == "\r\n" {
			break
		}
		if line == "Sec-WebSocket-Protocol: kairos-token."+secret+"\r\n" {
			sawEchoedProtocol = true
		}
	}
	if !sawEchoedProtocol {
		t.Error("la respuesta debe repetir el subprotocolo ofrecido (RFC 6455 §4.2.2), no llegó")
	}

	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	hdr := make([]byte, 2)
	if _, err := readFull(br, hdr); err != nil {
		t.Fatalf("no llegó ningún frame del relay: %v", err)
	}
	length := int(hdr[1] & 0x7F)
	payload := make([]byte, length)
	if _, err := readFull(br, payload); err != nil {
		t.Fatal(err)
	}
	// El primer paquete simulado es siempre el "de configuración" (12 bytes de cabecera, luego el
	// payload de texto fijo que genera el stub) — alcanza con confirmar que llegaron bytes reales
	// a través de todo el camino (hub → websocket → este cliente), sin decodificar la cabecera acá
	// (eso ya lo prueba screen.TestHubStubSourceProducesFrames).
	if length == 0 {
		t.Error("el primer frame del relay no debería estar vacío")
	}
}

func readFull(r *bufio.Reader, p []byte) (int, error) {
	total := 0
	for total < len(p) {
		n, err := r.Read(p[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}
