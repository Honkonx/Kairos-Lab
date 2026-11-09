package server

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/audit"
	"github.com/honkonx/kairos-dev/native/gateway/internal/auth"
	"github.com/honkonx/kairos-dev/native/gateway/internal/events"
	"github.com/honkonx/kairos-dev/native/gateway/internal/pending"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
	"github.com/honkonx/kairos-dev/native/gateway/internal/router"
)

const adminSecret = "admin-secreto-de-prueba"

type env struct {
	srv        *httptest.Server
	cfg        Config
	app        *httptest.Server
	appHits    chan string
	backend    *httptest.Server
	seen       chan string // consulta, cabecera y ruta que llegaron al servicio proxy
	seenCookie chan string // header Cookie crudo que llegó al servicio proxy (nunca debe traer kairos_proxy_*)
}

func newEnv(t *testing.T, policy permission.Policy) *env {
	t.Helper()
	dir := t.TempDir()
	e := &env{appHits: make(chan string, 8), seen: make(chan string, 8), seenCookie: make(chan string, 8)}

	e.app = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		e.appHits <- string(b)
		fmt.Fprint(w, "hecho por la app")
	}))
	t.Cleanup(e.app.Close)
	e.backend = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		e.seen <- r.URL.RawQuery + "|" + r.Header.Get("Authorization") + "|" + r.URL.Path
		e.seenCookie <- r.Header.Get("Cookie")
		fmt.Fprint(w, "respuesta del servicio")
	}))
	t.Cleanup(e.backend.Close)

	reg, err := router.NewRegistry(router.Manifest{
		Actions: []router.Action{
			{ID: "version", Title: "Versión", Risk: "read", Kind: router.KindNative, Target: "gateway.version"},
			{ID: "mod.iniciar", Title: "Iniciar módulo", Risk: "write", Kind: router.KindInApp, Target: "module.start",
				Params: []router.Param{{Name: "id", Pattern: `[a-z0-9_-]{1,40}`, Required: true}}},
			{ID: "cmd.libre", Title: "Comando libre", Risk: "danger", MinLevel: "total", Kind: router.KindInApp, Target: "module.delete"},
			{ID: "mod.borrar", Title: "Borrar módulo", Risk: "danger", Kind: router.KindInApp, Target: "module.delete",
				Params: []router.Param{{Name: "id", Pattern: `[a-z0-9_-]{1,40}`, Required: true}}},
		},
		Services: []router.Service{{Name: "n8n", Target: e.backend.URL}},
	})
	if err != nil {
		t.Fatal(err)
	}
	tokens, _ := auth.Open(filepath.Join(dir, "tokens.json"))
	pol, _ := permission.OpenStore(filepath.Join(dir, "policy.json"))
	if err := pol.Set(policy); err != nil {
		t.Fatal(err)
	}
	e.cfg = Config{
		Version: "test", AdminSecret: adminSecret, Tokens: tokens, Policy: pol, Registry: reg,
		Dispatcher: router.Dispatcher{
			InApp:  router.InAppExecutor{URL: e.app.URL, Secret: "app"},
			Native: router.NativeExecutor{Funcs: router.BuiltinFuncs("test")},
		},
		Audit: audit.Open(filepath.Join(dir, "audit.log"), 0),
		Hub:   events.NewHub(), Pending: pending.NewStore(time.Minute),
	}
	e.srv = httptest.NewServer(New(e.cfg))
	t.Cleanup(e.srv.Close)
	return e
}

func (e *env) token(t *testing.T, level permission.Level) string {
	t.Helper()
	secret, _, err := e.cfg.Tokens.Create("prueba", level)
	if err != nil {
		t.Fatal(err)
	}
	return secret
}

func (e *env) do(t *testing.T, method, path, token, adminKey, body string) (int, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest(method, e.srv.URL+path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if adminKey != "" {
		req.Header.Set("X-Kairos-Admin", adminKey)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	raw, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

func TestRequiresAuthentication(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	for _, p := range []string{"/v1/me", "/v1/actions", "/v1/events"} {
		if code, _ := e.do(t, "GET", p, "", "", ""); code != http.StatusUnauthorized {
			t.Errorf("GET %s sin token = %d, quiero 401", p, code)
		}
	}
	if code, _ := e.do(t, "POST", "/v1/actions/version", "token-falso", "", ""); code != http.StatusUnauthorized {
		t.Errorf("token falso = %d, quiero 401", code)
	}
	if code, body := e.do(t, "GET", "/healthz", "", "", ""); code != 200 || body["ok"] != true {
		t.Errorf("/healthz debe ser público: %d %v", code, body)
	}
}

func TestQueryTokenOnlyWorksForGet(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	tok := e.token(t, permission.Full)
	resp, err := http.Get(e.srv.URL + "/v1/me?token=" + tok)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("GET con ?token debía funcionar: %v %v", resp, err)
	}
	resp.Body.Close()
	resp, err = http.Post(e.srv.URL+"/v1/actions/version?token="+tok, "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("POST con ?token = %d, quiero 401", resp.StatusCode)
	}
}

// Hallazgo real de docs/referencias/pc-movil/: "rate limiting por token en /v1/actions -- hoy
// no hay ninguno" -- un token comprometido o un loop del lado cliente podía disparar
// ejecuciones sin ningún límite.
func TestActionRateLimitBlocksAfterMaxWithinWindow(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	tok := e.token(t, permission.Full)

	blocked := 0
	for i := 0; i < actionRateLimitMax+5; i++ {
		code, _ := e.do(t, "POST", "/v1/actions/version", tok, "", "")
		if code == http.StatusTooManyRequests {
			blocked++
		} else if code != 200 {
			t.Fatalf("intento %d: código inesperado %d", i, code)
		}
	}
	if blocked == 0 {
		t.Fatal("se esperaban rechazos 429 después de superar actionRateLimitMax en la ventana")
	}
}

func TestActionRateLimitIsPerToken(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	tokA := e.token(t, permission.Full)
	tokB := e.token(t, permission.Full)

	for i := 0; i < actionRateLimitMax; i++ {
		if code, _ := e.do(t, "POST", "/v1/actions/version", tokA, "", ""); code != 200 {
			t.Fatalf("token A, intento %d: código %d", i, code)
		}
	}
	// token A ya agotó su cupo -- token B, sin tocar, debe seguir funcionando normal.
	if code, _ := e.do(t, "POST", "/v1/actions/version", tokB, "", ""); code != 200 {
		t.Errorf("el rate limit de token A no debería afectar a token B, código %d", code)
	}
}

func TestReadLevelAllowsReadDeniesWrite(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	tok := e.token(t, permission.Read)

	if code, body := e.do(t, "POST", "/v1/actions/version", tok, "", ""); code != 200 {
		t.Fatalf("lectura debía ejecutarse: %d %v", code, body)
	}
	if code, _ := e.do(t, "POST", "/v1/actions/mod.iniciar", tok, "", `{"params":{"id":"n8n"}}`); code != http.StatusForbidden {
		t.Errorf("escritura con nivel lectura = %d, quiero 403", code)
	}
	select {
	case hit := <-e.appHits:
		t.Fatalf("la app no debía recibir nada, recibió %s", hit)
	default:
	}
	code, body := e.do(t, "GET", "/v1/actions", tok, "", "")
	if code != 200 {
		t.Fatal(code)
	}
	for _, a := range body["actions"].([]any) {
		m := a.(map[string]any)
		want := map[string]string{"read": "permitida", "write": "denegada", "danger": "denegada"}[m["risk"].(string)]
		if m["decision"] != want {
			t.Errorf("acción %v: decision = %v, quiero %s", m["id"], m["decision"], want)
		}
	}
}

func TestInvalidParamsRejectedBeforeAskingUser(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask, Responsible: false})
	tok := e.token(t, permission.Ask)
	if code, _ := e.do(t, "POST", "/v1/actions/mod.iniciar", tok, "", `{"params":{"id":"n8n; rm -rf /"}}`); code != http.StatusBadRequest {
		t.Errorf("parámetro malicioso = %d, quiero 400", code)
	}
	if code, _ := e.do(t, "POST", "/v1/actions/mod.iniciar", tok, "", `{"params":{"id":"n8n"},"extra":1}`); code != http.StatusBadRequest {
		t.Errorf("campos desconocidos = %d, quiero 400", code)
	}
	if n := len(e.cfg.Pending.List()); n != 0 {
		t.Errorf("no debió crearse ninguna petición pendiente, hay %d", n)
	}
}

func TestAskLevelFullFlowWithApproval(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask, Responsible: false})
	tok := e.token(t, permission.Ask)

	code, body := e.do(t, "POST", "/v1/actions/mod.iniciar", tok, "", `{"params":{"id":"n8n"}}`)
	if code != http.StatusAccepted || body["state"] != "pendiente" {
		t.Fatalf("debía quedar pendiente: %d %v", code, body)
	}
	pid := body["pending_id"].(string)
	select {
	case hit := <-e.appHits:
		t.Fatalf("la app recibió la acción antes de aprobarla: %s", hit)
	default:
	}

	// Sin el secreto de administración nadie puede aprobar, ni siquiera el dueño del token.
	if code, _ := e.do(t, "POST", "/v1/admin/pending/"+pid, tok, "", `{"approve":true}`); code != http.StatusForbidden {
		t.Errorf("aprobar sin secreto admin = %d, quiero 403", code)
	}
	if code, _ := e.do(t, "POST", "/v1/admin/pending/"+pid, "", adminSecret, `{"approve":true}`); code != 200 {
		t.Fatalf("aprobar = %d", code)
	}
	select {
	case hit := <-e.appHits:
		if !strings.Contains(hit, "module.start") || !strings.Contains(hit, "n8n") {
			t.Errorf("la app recibió %s", hit)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("la app no recibió la acción aprobada")
	}

	var final map[string]any
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		code, final = e.do(t, "GET", "/v1/pending/"+pid, tok, "", "")
		if code == 200 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if final["state"] != "hecha" {
		t.Fatalf("estado final = %v", final)
	}
	// Otro token no puede ver la petición ajena.
	other := e.token(t, permission.Ask)
	if code, _ := e.do(t, "GET", "/v1/pending/"+pid, other, "", ""); code != http.StatusNotFound {
		t.Errorf("petición ajena = %d, quiero 404", code)
	}
}

func TestAskWithWaitReturnsDenied(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask, Responsible: false})
	tok := e.token(t, permission.Ask)
	go func() {
		for i := 0; i < 100; i++ {
			if list := e.cfg.Pending.List(); len(list) > 0 {
				e.cfg.Pending.Decide(list[0].Request.ID, false)
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()
	code, body := e.do(t, "POST", "/v1/actions/mod.iniciar?wait=5", tok, "", `{"params":{"id":"n8n"}}`)
	if code != http.StatusForbidden || body["state"] != "denegada" {
		t.Errorf("denegada con wait = %d %v", code, body)
	}
}

func TestResponsibilityCheckboxGatesTotalLevel(t *testing.T) {
	// Token "total" pero la casilla de responsabilidad sin marcar: lo peligroso debe preguntar.
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: false})
	tok := e.token(t, permission.Full)
	code, body := e.do(t, "POST", "/v1/actions/mod.borrar", tok, "", `{"params":{"id":"n8n"}}`)
	if code != http.StatusAccepted {
		t.Fatalf("sin responsabilidad debía preguntar: %d %v", code, body)
	}

	// Con la casilla marcada (cambiada por la app), la misma acción se ejecuta directo.
	if code, _ := e.do(t, "PUT", "/v1/admin/policy", "", adminSecret, `{"max":"total","responsible":true}`); code != 200 {
		t.Fatalf("PUT policy = %d", code)
	}
	code, body = e.do(t, "POST", "/v1/actions/mod.borrar", tok, "", `{"params":{"id":"n8n"}}`)
	if code != 200 || body["state"] != "hecha" {
		t.Errorf("con responsabilidad debía ejecutarse: %d %v", code, body)
	}
}

func TestAdminDisabledWithoutSecretAndTokenLifecycle(t *testing.T) {
	e := newEnv(t, permission.DefaultPolicy)
	if code, _ := e.do(t, "GET", "/v1/admin/tokens", "", "", ""); code != http.StatusForbidden {
		t.Errorf("admin sin cabecera = %d", code)
	}
	if code, _ := e.do(t, "GET", "/v1/admin/tokens", "", "otro-secreto", ""); code != http.StatusForbidden {
		t.Errorf("admin con secreto errado = %d", code)
	}

	code, body := e.do(t, "POST", "/v1/admin/tokens", "", adminSecret, `{"name":"tablet","level":"semi-total"}`)
	if code != http.StatusCreated {
		t.Fatalf("crear token = %d %v", code, body)
	}
	secret, id := body["token"].(string), body["id"].(string)
	if code, me := e.do(t, "GET", "/v1/me", secret, "", ""); code != 200 || me["level"] != "semi-total" {
		t.Errorf("/v1/me = %d %v", code, me)
	}
	code, list := e.do(t, "GET", "/v1/admin/tokens", "", adminSecret, "")
	raw, _ := json.Marshal(list)
	if code != 200 || strings.Contains(string(raw), secret) {
		t.Errorf("la lista no debe mostrar el valor del token: %s", raw)
	}
	if code, _ := e.do(t, "DELETE", "/v1/admin/tokens/"+id, "", adminSecret, ""); code != 200 {
		t.Errorf("revocar = %d", code)
	}
	if code, _ := e.do(t, "GET", "/v1/me", secret, "", ""); code != http.StatusUnauthorized {
		t.Errorf("token revocado = %d, quiero 401", code)
	}

	noAdmin := e.cfg
	noAdmin.AdminSecret = ""
	srv := httptest.NewServer(New(noAdmin))
	defer srv.Close()
	req, _ := http.NewRequest("GET", srv.URL+"/v1/admin/tokens", nil)
	req.Header.Set("X-Kairos-Admin", "")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("con AdminSecret vacío la administración debe estar apagada, dio %d", resp.StatusCode)
	}
}

func TestAuditRecordsDecisionsWithoutSecrets(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	tok := e.token(t, permission.Read)
	e.do(t, "POST", "/v1/actions/version", tok, "", "")
	e.do(t, "POST", "/v1/actions/mod.iniciar", tok, "", `{"params":{"id":"n8n"}}`)

	code, body := e.do(t, "GET", "/v1/admin/audit?n=10", "", adminSecret, "")
	raw, _ := json.Marshal(body)
	if code != 200 || !strings.Contains(string(raw), "denegada") || !strings.Contains(string(raw), "ejecutada") {
		t.Errorf("auditoría incompleta: %s", raw)
	}
	if strings.Contains(string(raw), tok) {
		t.Error("la auditoría no debe contener el valor del token")
	}
}

func TestEventsStreamDeliversPublishedEvents(t *testing.T) {
	e := newEnv(t, permission.DefaultPolicy)
	tok := e.token(t, permission.Read)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "GET", e.srv.URL+"/v1/events", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Fatalf("Content-Type = %q", ct)
	}
	rd := bufio.NewReader(resp.Body)
	if line, _ := rd.ReadString('\n'); !strings.HasPrefix(line, ": conectado") {
		t.Fatalf("primera línea = %q", line)
	}
	rd.ReadString('\n')

	go func() {
		for i := 0; i < 50 && e.cfg.Hub.Count() == 0; i++ {
			time.Sleep(10 * time.Millisecond)
		}
		me, _ := e.cfg.Tokens.Authenticate(tok)
		e.cfg.Hub.PublishFor(me.ID, "estado", map[string]string{"modulo": "n8n"})
	}()
	var gotEvent, gotData string
	for gotData == "" {
		line, err := rd.ReadString('\n')
		if err != nil {
			t.Fatalf("leyendo el flujo: %v", err)
		}
		switch {
		case strings.HasPrefix(line, "event: "):
			gotEvent = strings.TrimSpace(line[len("event: "):])
		case strings.HasPrefix(line, "data: "):
			gotData = line
		}
	}
	if gotEvent != "estado" || !strings.Contains(gotData, `"modulo":"n8n"`) {
		t.Errorf("evento = %q datos = %q", gotEvent, gotData)
	}
}

// Flujo real tras el fix de seguridad (revisión automática 2026-10-26): el
// primer GET con ?token= en la URL recibe un 302 + Set-Cookie (scoped a /proxy/<servicio>/) en
// vez de proxyarse directo — un http.Client sin CookieJar (como el http.Get de paquete) NUNCA
// vería la cookie en el siguiente pedido, así que este test usa un cliente con cookiejar real,
// igual que un navegador de verdad.
func TestProxyQueryTokenSetsCookieAndRedirectsToCleanURL(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	tok := e.token(t, permission.Read)
	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar}

	// Paso 1: el link "Abrir" (primer clic) trae el token por query string.
	resp1, err := client.Get(e.srv.URL + "/proxy/n8n/rest/ping?token=" + tok + "&a=1")
	if err != nil {
		t.Fatal(err)
	}
	finalURL := resp1.Request.URL.String()
	body1, _ := io.ReadAll(resp1.Body)
	resp1.Body.Close()
	if strings.Contains(finalURL, "token=") {
		t.Fatalf("la URL final todavía tiene el token: %s", finalURL)
	}
	if resp1.StatusCode != 200 || string(body1) != "respuesta del servicio" {
		t.Fatalf("proxy GET (siguiendo el redirect) = %d %q", resp1.StatusCode, body1)
	}
	select {
	case seen := <-e.seen:
		if seen != "a=1||/rest/ping" {
			t.Errorf("el servicio vio %q; el token no debe llegar y la ruta/query real deben conservarse", seen)
		}
	case <-time.After(time.Second):
		t.Fatal("el servicio no recibió la petición")
	}
	if cookie := <-e.seenCookie; cookie != "" {
		t.Errorf("el servicio vio el header Cookie %q; kairos_proxy_* nunca debe reenviarse (hallazgo de revisión automática)", cookie)
	}

	// Paso 2: un pedido NUEVO sin token en la URL, con la cookie ya puesta, debe seguir andando
	// (la cookie quedó guardada — es justo lo que evita volver a exponer el token en cada link).
	resp2, err := client.Get(e.srv.URL + "/proxy/n8n/rest/otro")
	if err != nil {
		t.Fatal(err)
	}
	body2, _ := io.ReadAll(resp2.Body)
	resp2.Body.Close()
	if resp2.StatusCode != 200 || string(body2) != "respuesta del servicio" {
		t.Fatalf("proxy GET con cookie, sin token en la URL = %d %q", resp2.StatusCode, body2)
	}
	<-e.seen // drenar el hit del paso 2
	if cookie := <-e.seenCookie; cookie != "" {
		t.Errorf("paso 2: el servicio vio el header Cookie %q; kairos_proxy_* nunca debe reenviarse", cookie)
	}

	// Paso 3: un POST con la cookie pero con un Origin de otro sitio se rechaza (hallazgo de
	// revisión automática: "CSRF / cross-service ambient credential") — aunque SameSite=Strict
	// ya debería frenarlo en un navegador real, el servidor no confía solo en eso.
	req3, _ := http.NewRequest("POST", e.srv.URL+"/proxy/n8n/rest/x", strings.NewReader("{}"))
	req3.Header.Set("Origin", "https://atacante.example")
	resp3, err := client.Do(req3)
	if err != nil {
		t.Fatal(err)
	}
	resp3.Body.Close()
	if resp3.StatusCode != http.StatusUnauthorized {
		t.Errorf("POST con cookie + Origin cruzado = %d, quiero 401", resp3.StatusCode)
	}
}

func TestProxyStripsTokenAndEnforcesLevel(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	tok := e.token(t, permission.Read)

	if code, _ := e.do(t, "POST", "/proxy/n8n/rest/x", tok, "", "{}"); code != http.StatusForbidden {
		t.Errorf("POST por el proxy con nivel lectura = %d, quiero 403", code)
	}
	if code, _ := e.do(t, "GET", "/proxy/desconocido/x", tok, "", ""); code != http.StatusNotFound {
		t.Errorf("servicio desconocido = %d, quiero 404", code)
	}
	if code, _ := e.do(t, "GET", "/proxy/n8n/x", "", "", ""); code != http.StatusUnauthorized {
		t.Errorf("proxy sin token = %d, quiero 401", code)
	}
}

func TestAdminShutdownNeedsSecretAndCallsHook(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask})
	called := make(chan struct{}, 1)
	cfg := e.cfg
	cfg.OnShutdown = func() { called <- struct{}{} }
	srv := httptest.NewServer(New(cfg))
	t.Cleanup(srv.Close)

	post := func(key string) int {
		req, _ := http.NewRequest("POST", srv.URL+"/v1/admin/shutdown", nil)
		if key != "" {
			req.Header.Set("X-Kairos-Admin", key)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if code := post(""); code == http.StatusOK {
		t.Fatalf("sin secreto no debe apagar, dio %d", code)
	}
	select {
	case <-called:
		t.Fatal("el gancho se llamó sin secreto")
	case <-time.After(100 * time.Millisecond):
	}
	if code := post(adminSecret); code != http.StatusOK {
		t.Fatalf("con secreto debe responder 200, dio %d", code)
	}
	select {
	case <-called:
	case <-time.After(2 * time.Second):
		t.Fatal("el gancho de apagado no se llamó")
	}
	// Sin gancho configurado: 501, no un pánico.
	if code, _ := e.do(t, "POST", "/v1/admin/shutdown", "", adminSecret, ""); code != http.StatusNotImplemented {
		t.Fatalf("sin gancho debe dar 501, dio %d", code)
	}
}

func TestHomePageIsPublicHTMLAndOtherPathsStay404(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask})
	resp, err := http.Get(e.srv.URL + "/")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || !strings.Contains(resp.Header.Get("Content-Type"), "text/html") {
		t.Fatalf("GET / = %d %q", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
	if resp.Header.Get("Content-Security-Policy") == "" || !strings.Contains(string(body), "Gateway de Kairos") {
		t.Error("falta la CSP o el contenido de la página")
	}
	if code, _ := e.do(t, "GET", "/no-existe", "", "", ""); code != http.StatusNotFound {
		t.Errorf("GET /no-existe = %d, quiero 404", code)
	}
}

func TestActionWithMinLevelIsDeniedBelowItEvenWhenAsking(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	for _, lvl := range []permission.Level{permission.Ask, permission.Semi} {
		if code, _ := e.do(t, "POST", "/v1/actions/cmd.libre", e.token(t, lvl), "", "{}"); code != http.StatusForbidden {
			t.Errorf("nivel %v: cmd.libre = %d, quiero 403 (sin pedir confirmación)", lvl, code)
		}
	}
	if len(e.cfg.Pending.List()) != 0 {
		t.Error("una acción por debajo del nivel mínimo no debe crear una petición pendiente")
	}
	if code, _ := e.do(t, "POST", "/v1/actions/cmd.libre", e.token(t, permission.Full), "", "{}"); code != http.StatusOK {
		t.Errorf("nivel total: cmd.libre = %d, quiero 200", code)
	}
}

func TestPanelIsPublicStaticPageWithStrictCSPAndNoSecrets(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask})
	resp, err := http.Get(e.srv.URL + "/panel")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	csp := resp.Header.Get("Content-Security-Policy")
	if resp.StatusCode != 200 || !strings.Contains(string(body), "Kairos Gateway") {
		t.Fatalf("GET /panel = %d", resp.StatusCode)
	}
	if !strings.Contains(csp, "default-src 'none'") || !strings.Contains(csp, "connect-src 'self'") || !strings.Contains(csp, "frame-ancestors 'none'") {
		t.Errorf("CSP demasiado abierta: %q", csp)
	}
	if strings.Contains(string(body), "X-Kairos-Admin") || strings.Contains(string(body), "/v1/admin") {
		t.Error("el panel no debe usar la API de administración (solo loopback directo)")
	}
}

// TestScreenWindowIsPublicStaticPageWithStrictCSP confirma la ventana aparte del visor de
// pantalla (2026-10-16,) — mismo criterio que el panel completo: página
// estática pública (sin secretos embebidos, el token lo trae el fragmento de la URL del lado del
// navegador, nunca llega al servidor) con CSP estricta.
func TestScreenWindowIsPublicStaticPageWithStrictCSP(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Ask})
	resp, err := http.Get(e.srv.URL + "/panel/screen")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	csp := resp.Header.Get("Content-Security-Policy")
	if resp.StatusCode != 200 || !strings.Contains(string(body), "Pantalla") {
		t.Fatalf("GET /panel/screen = %d", resp.StatusCode)
	}
	if !strings.Contains(csp, "default-src 'none'") || !strings.Contains(csp, "frame-ancestors 'none'") {
		t.Errorf("CSP demasiado abierta: %q", csp)
	}
}
