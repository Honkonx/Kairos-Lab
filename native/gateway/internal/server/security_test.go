package server

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
)

// Hallazgos de la revisión de seguridad automática (2026-10-09): un token no debe ver eventos de
// otros ni de administración, y un WebSocket por el proxy no cuenta como lectura.

// readSSE abre un flujo y devuelve un canal con el tipo de cada evento que llega. Un único goroutine
// lee el cuerpo (varios lectores sobre el mismo bufio.Reader serían una carrera).
func readSSE(t *testing.T, ctx context.Context, url string, headers map[string]string) (<-chan string, func()) {
	t.Helper()
	req, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		resp.Body.Close()
		t.Fatalf("SSE %s = %d", url, resp.StatusCode)
	}
	types := make(chan string, 32)
	go func() {
		defer close(types)
		rd := bufio.NewReader(resp.Body)
		for {
			line, err := rd.ReadString('\n')
			if err != nil {
				return
			}
			if strings.HasPrefix(line, "event: ") {
				types <- strings.TrimSpace(line[len("event: "):])
			}
		}
	}()
	return types, func() { resp.Body.Close() }
}

// firstEventType espera el siguiente evento y devuelve su tipo, o "" si no llega nada en el plazo.
func firstEventType(types <-chan string, wait time.Duration) string {
	select {
	case v := <-types:
		return v
	case <-time.After(wait):
		return ""
	}
}

func TestTokenStreamDoesNotLeakOtherTokensAdminEvents(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask, Responsible: false})
	victim := e.token(t, permission.Ask)
	spy := e.token(t, permission.Read)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	spyStream, closeSpy := readSSE(t, ctx, e.srv.URL+"/v1/events", map[string]string{"Authorization": "Bearer " + spy})
	defer closeSpy()
	adminStream, closeAdmin := readSSE(t, ctx, e.srv.URL+"/v1/admin/events", map[string]string{"X-Kairos-Admin": adminSecret})
	defer closeAdmin()

	// La víctima pide una acción: se publica "pending" con sus parámetros, solo para administración.
	if code, _ := e.do(t, "POST", "/v1/actions/mod.iniciar", victim, "", `{"params":{"id":"n8n"}}`); code != http.StatusAccepted {
		t.Fatalf("pending = %d", code)
	}
	if got := firstEventType(adminStream, 2*time.Second); got != "pending" {
		t.Errorf("la administración debía ver pending, vio %q", got)
	}
	if got := firstEventType(spyStream, 300*time.Millisecond); got != "" {
		t.Errorf("un token ajeno vio el evento %q: fuga entre tokens", got)
	}

	// Un cambio de política tampoco debe llegar a un token.
	e.do(t, "PUT", "/v1/admin/policy", "", adminSecret, `{"max":"total","responsible":true}`)
	if got := firstEventType(spyStream, 300*time.Millisecond); got != "" {
		t.Errorf("un token vio el evento de política %q", got)
	}
	if got := firstEventType(adminStream, 2*time.Second); got != "policy" {
		t.Errorf("la administración debía ver policy, vio %q", got)
	}
}

func TestActionDoneReachesOnlyTheRequester(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask, Responsible: false})
	owner := e.token(t, permission.Ask)
	other := e.token(t, permission.Ask)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ownerStream, c1 := readSSE(t, ctx, e.srv.URL+"/v1/events", map[string]string{"Authorization": "Bearer " + owner})
	defer c1()
	otherStream, c2 := readSSE(t, ctx, e.srv.URL+"/v1/events", map[string]string{"Authorization": "Bearer " + other})
	defer c2()

	_, body := e.do(t, "POST", "/v1/actions/mod.iniciar", owner, "", `{"params":{"id":"n8n"}}`)
	pid := body["pending_id"].(string)
	if code, _ := e.do(t, "POST", "/v1/admin/pending/"+pid, "", adminSecret, `{"approve":true}`); code != 200 {
		t.Fatalf("aprobar = %d", code)
	}
	if got := firstEventType(ownerStream, 3*time.Second); got != "action_done" {
		t.Errorf("el dueño debía recibir action_done, recibió %q", got)
	}
	if got := firstEventType(otherStream, 300*time.Millisecond); got != "" {
		t.Errorf("otro token recibió %q", got)
	}
}

func TestAdminEventsRequireAdminSecret(t *testing.T) {
	e := newEnv(t, permission.DefaultPolicy)
	tok := e.token(t, permission.Full)
	if code, _ := e.do(t, "GET", "/v1/admin/events", tok, "", ""); code != http.StatusForbidden {
		t.Errorf("un token no debe abrir el flujo de administración: %d", code)
	}
}

func TestProxyTreatsWebSocketUpgradeAsWrite(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	reader := e.token(t, permission.Read)
	writer := e.token(t, permission.Semi)

	upgrade := func(token string, connection string) int {
		req, _ := http.NewRequest("GET", e.srv.URL+"/proxy/n8n/ws", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Upgrade", "websocket")
		if connection != "" {
			req.Header.Set("Connection", connection)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if code := upgrade(reader, "Upgrade"); code != http.StatusForbidden {
		t.Errorf("WebSocket con nivel lectura = %d, quiero 403", code)
	}
	if code := upgrade(reader, "keep-alive, Upgrade"); code != http.StatusForbidden {
		t.Errorf("Connection en lista = %d, quiero 403", code)
	}
	// Solo la cabecera Upgrade, sin Connection, también cuenta.
	if code := upgrade(reader, ""); code != http.StatusForbidden {
		t.Errorf("Upgrade sin Connection = %d, quiero 403", code)
	}
	// Con permiso de escritura el mismo intento no se rechaza por permisos (el servicio de prueba responde).
	if code := upgrade(writer, "Upgrade"); code == http.StatusForbidden || code == http.StatusUnauthorized {
		t.Errorf("con semi-total el proxy no debía rechazar por permisos, dio %d", code)
	}
	// Un GET normal sigue siendo lectura.
	if code, _ := e.do(t, "GET", "/proxy/n8n/x", reader, "", ""); code != 200 {
		t.Errorf("GET normal con nivel lectura = %d, quiero 200", code)
	}
}

func TestHealthzProofOnlyForChallengeAndNeedsTheSecret(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask})
	_, plain := e.do(t, "GET", "/healthz", "", "", "")
	if _, ok := plain["proof"]; ok {
		t.Fatal("sin challenge no debe haber prueba")
	}
	_, withChallenge := e.do(t, "GET", "/healthz?challenge=abc123", "", "", "")
	mac := hmac.New(sha256.New, []byte(adminSecret))
	mac.Write([]byte("abc123"))
	if withChallenge["proof"] != hex.EncodeToString(mac.Sum(nil)) {
		t.Fatalf("prueba incorrecta: %v", withChallenge["proof"])
	}
	// Un servidor con otro secreto (otra app que ocupó el puerto) no puede producir la misma prueba.
	other := hmac.New(sha256.New, []byte("otro-secreto"))
	other.Write([]byte("abc123"))
	if withChallenge["proof"] == hex.EncodeToString(other.Sum(nil)) {
		t.Fatal("la prueba no depende del secreto")
	}
}

func TestAdminRefusesForwardedRequestsEvenWithTheRightSecret(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask})
	for _, h := range []string{"X-Forwarded-For", "Cf-Connecting-Ip", "Forwarded", "X-Real-Ip"} {
		req, _ := http.NewRequest("GET", e.srv.URL+"/v1/admin/policy", nil)
		req.Header.Set("X-Kairos-Admin", adminSecret)
		req.Header.Set(h, "203.0.113.9")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("con %s la administración debe rechazarse, dio %d", h, resp.StatusCode)
		}
	}
	if code, _ := e.do(t, "GET", "/v1/admin/policy", "", adminSecret, ""); code != http.StatusOK {
		t.Errorf("la app local directa debe seguir funcionando, dio %d", code)
	}
}
