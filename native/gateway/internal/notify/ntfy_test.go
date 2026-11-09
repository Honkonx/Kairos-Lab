package notify

import (
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// doSend es la pieza testeable sin red real (construcción del request) -- ver su KDoc en
// ntfy.go para por qué [Send] en sí no se testea end-to-end contra httptest (su guard de SSRF
// bloquea loopback a propósito, que es justo donde corre cualquier servidor de test).

func TestDoSendPostsMessageWithTitleAndPriorityHeaders(t *testing.T) {
	var gotPath, gotTitle, gotPriority, gotBody, gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotTitle = r.Header.Get("X-Title")
		gotPriority = r.Header.Get("X-Priority")
		gotAuth = r.Header.Get("Authorization")
		body, _ := io.ReadAll(r.Body)
		gotBody = string(body)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	if err := doSend(srv.Client(), srv.URL, "mi-topic", "Backup listo", "El respaldo terminó sin errores", "tok123", PriorityMax); err != nil {
		t.Fatalf("doSend: %v", err)
	}
	if gotPath != "/mi-topic" {
		t.Errorf("path = %q, want /mi-topic", gotPath)
	}
	if gotTitle != "Backup listo" {
		t.Errorf("title header = %q", gotTitle)
	}
	if gotPriority != "5" {
		t.Errorf("priority header = %q, want 5", gotPriority)
	}
	if gotAuth != "Bearer tok123" {
		t.Errorf("auth header = %q", gotAuth)
	}
	if gotBody != "El respaldo terminó sin errores" {
		t.Errorf("body = %q", gotBody)
	}
}

func TestDoSendFailsOnNonSuccessStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer srv.Close()
	if err := doSend(srv.Client(), srv.URL, "topic", "", "m", "", PriorityDefault); err == nil {
		t.Fatal("esperaba error por HTTP 403")
	}
}

func TestSendRejectsEmptyTopic(t *testing.T) {
	if err := Send("", "", "t", "m", "", PriorityDefault); err == nil {
		t.Fatal("esperaba error por topic vacío")
	}
}

func TestSendRejectsTopicWithSlash(t *testing.T) {
	if err := Send("", "a/b", "t", "m", "", PriorityDefault); err == nil {
		t.Fatal("esperaba error por topic con barra")
	}
}

func TestSendDefaultsServerToNtfySh(t *testing.T) {
	if !strings.HasPrefix(DefaultServer, "https://") {
		t.Fatalf("DefaultServer = %q, no es https", DefaultServer)
	}
}

// --- Guard de SSRF (validateServer/isBlockedIP) ---

func TestValidateServerRejectsHTTP(t *testing.T) {
	if _, err := validateServer("http://example.com"); err != ErrServerNotAllowed {
		t.Fatalf("err = %v, want ErrServerNotAllowed", err)
	}
}

func TestValidateServerRejectsLoopbackIP(t *testing.T) {
	if _, err := validateServer("https://127.0.0.1"); err != ErrServerNotAllowed {
		t.Fatalf("err = %v, want ErrServerNotAllowed", err)
	}
}

func TestValidateServerRejectsPrivateIP(t *testing.T) {
	if _, err := validateServer("https://192.168.1.5"); err != ErrServerNotAllowed {
		t.Fatalf("err = %v, want ErrServerNotAllowed", err)
	}
}

func TestValidateServerRejectsPathBeyondRoot(t *testing.T) {
	if _, err := validateServer("https://ntfy.sh/some/path"); err != ErrServerNotAllowed {
		t.Fatalf("err = %v, want ErrServerNotAllowed", err)
	}
}

func TestValidateServerAcceptsPublicHTTPS(t *testing.T) {
	base, err := validateServer("https://ntfy.sh")
	if err != nil {
		t.Fatalf("validateServer: %v", err)
	}
	if base != "https://ntfy.sh" {
		t.Fatalf("base = %q", base)
	}
}

func TestSendRejectsLoopbackServerBeforeDialing(t *testing.T) {
	// Send() en sí (no doSend) debe rechazar un server loopback ANTES de intentar conectar --
	// confirma que el guard real (el que importa en producción) está wireado.
	if err := Send("https://127.0.0.1:9", "topic", "", "m", "", PriorityDefault); err != ErrServerNotAllowed {
		t.Fatalf("err = %v, want ErrServerNotAllowed", err)
	}
}

func TestIsBlockedIPCoversPrivateLoopbackLinkLocal(t *testing.T) {
	cases := []struct {
		ip      string
		blocked bool
	}{
		{"127.0.0.1", true},
		{"10.0.0.5", true},
		{"172.16.0.5", true},
		{"192.168.0.5", true},
		{"169.254.1.1", true},
		{"0.0.0.0", true},
		{"8.8.8.8", false},
		{"1.1.1.1", false},
		// Rangos del 2do hallazgo de revisión automática ("incomplete IP denylist") --
		// ninguno de estos lo cubría ip.IsPrivate()/IsLoopback()/etc.
		{"100.64.0.1", true},            // CGN (RFC 6598)
		{"0.1.2.3", true},               // "este host" (0/8)
		{"198.18.0.1", true},            // benchmarking
		{"240.1.2.3", true},             // reservado
		{"::1", true},                   // loopback IPv6
		{"fe80::1", true},               // link-local IPv6
		{"fec0::1", true},               // site-local IPv6 deprecado
		{"::ffff:127.0.0.1", true},      // IPv4-mapeada a loopback -- debe desenmascararse y bloquearse
		{"::ffff:8.8.8.8", false},       // IPv4-mapeada a una IP pública real -- no bloqueada
		{"2606:4700:4700::1111", false}, // IPv6 público real (1.1.1.1)
	}
	for _, c := range cases {
		got := isBlockedIP(net.ParseIP(c.ip))
		if got != c.blocked {
			t.Errorf("isBlockedIP(%s) = %v, want %v", c.ip, got, c.blocked)
		}
	}
}
