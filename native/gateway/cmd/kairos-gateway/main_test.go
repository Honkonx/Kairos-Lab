package main

import (
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/honkonx/kairos-dev/native/gateway/internal/auth"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
	"github.com/honkonx/kairos-dev/native/gateway/internal/router"
)

func TestEmbeddedManifestIsValid(t *testing.T) {
	var m router.Manifest
	if err := json.Unmarshal(defaultManifest, &m); err != nil {
		t.Fatal(err)
	}
	reg, err := router.NewRegistry(m)
	if err != nil {
		t.Fatalf("el manifiesto incorporado no valida: %v", err)
	}
	for _, id := range []string{"gateway.version", "module.start", "module.install", "system.metrics"} {
		if _, ok := reg.Get(id); !ok {
			t.Errorf("falta la acción %s", id)
		}
	}
	if a, ok := reg.Get("system.metrics"); !ok || a.Kind != router.KindInApp || a.RiskLevel().String() != "read" {
		t.Error("system.metrics debe existir, ser inapp y de solo lectura")
	}
	if a, ok := reg.Get("fs.write"); ok {
		if err := a.CheckParams(map[string]string{"path": "a", "content": strings.Repeat("x", 600000), "encoding": "base64"}); err != nil {
			t.Errorf("fs.write debe aceptar contenido grande en base64: %v", err)
		}
	} else {
		t.Error("falta la acción fs.write")
	}
	if a, _ := reg.Get("module.install"); a.RiskLevel().String() != "danger" {
		t.Error("instalar un módulo debe ser riesgo danger")
	}
	if a, ok := reg.Get("privilege.run"); !ok || a.RiskLevel().String() != "danger" {
		t.Error("ejecutar con privilegios debe existir y ser riesgo danger")
	} else if min, has := a.RequiredLevel(); !has || min != permission.Full {
		t.Error("ejecutar con privilegios debe exigir el nivel total")
	}
	if a, ok := reg.Get("privilege.status"); !ok || a.RiskLevel().String() != "read" {
		t.Error("el estado de los proveedores debe ser de solo lectura")
	}
	if a, ok := reg.Get("screen.status"); !ok || a.RiskLevel().String() != "danger" {
		t.Error("screen.status debe existir y ser riesgo danger")
	} else if min, has := a.RequiredLevel(); !has || min != permission.Full {
		t.Error("screen.status debe exigir el nivel total")
	}
	if a, ok := reg.Get("screen.relay_status"); !ok || a.Kind != router.KindNative || a.RiskLevel().String() != "read" {
		t.Error("screen.relay_status debe existir, ser nativo y de solo lectura")
	}
	if a, ok := reg.Get("screen.input"); !ok || a.Kind != router.KindInApp || a.RiskLevel().String() != "danger" {
		t.Error("screen.input debe existir, ser inapp y riesgo danger")
	} else if min, has := a.RequiredLevel(); !has || min != permission.Full {
		t.Error("screen.input debe exigir el nivel total")
	} else if err := a.CheckParams(map[string]string{"type": "tap", "x": "10", "y": "20"}); err != nil {
		t.Errorf("screen.input debe aceptar un tap válido: %v", err)
	} else if err := a.CheckParams(map[string]string{"type": "bogus"}); err == nil {
		t.Error("screen.input no debe aceptar un type fuera de tap|swipe|key")
	}
	if a, ok := reg.Get("cloud.status"); !ok || a.Kind != router.KindInApp || a.RiskLevel().String() != "read" {
		t.Error("cloud.status debe existir, ser inapp y de solo lectura")
	}
	for _, id := range []string{"cloud.start", "cloud.stop"} {
		if a, ok := reg.Get(id); !ok || a.Kind != router.KindInApp || a.RiskLevel().String() != "write" {
			t.Errorf("%s debe existir, ser inapp y riesgo write", id)
		}
	}
	// Revisión de seguridad real: devuelve el token maestro de Nube embebido en la URL — con riesgo
	// "read"/sin min_level, cualquier token del gateway (incluso "lectura") podía obtenerlo. Exige total,
	// igual que fs.write.
	if a, ok := reg.Get("cloud.share_link"); !ok || a.Kind != router.KindInApp || a.RiskLevel().String() != "write" {
		t.Error("cloud.share_link debe existir, ser inapp y riesgo write")
	} else if min, hasMin := a.RequiredLevel(); !hasMin || min.String() != "total" {
		t.Error("cloud.share_link debe exigir nivel total (da el token maestro de Nube)")
	} else if err := a.CheckParams(map[string]string{"path": "foto.png"}); err != nil {
		t.Errorf("cloud.share_link debe aceptar un path normal: %v", err)
	} else if err := a.CheckParams(map[string]string{}); err != nil {
		t.Errorf("cloud.share_link debe aceptar no mandar path (raíz de Nube): %v", err)
	}
	if a, ok := reg.Get("agent.ask"); !ok || a.Kind != router.KindInApp || a.RiskLevel().String() != "write" {
		t.Error("agent.ask debe existir, ser inapp y riesgo write")
	} else if min, has := a.RequiredLevel(); !has || min != permission.Full {
		// Un pedido en texto libre puede resolver en una intent de solo lectura o en una que muta el
		// dispositivo (instalar/iniciar/detener) sin saberse cuál ANTES de parsearlo — nivel más
		// conservador (ver AppAgentOps en GatewayAppBridge.kt).
		t.Error("agent.ask debe exigir el nivel total")
	} else if err := a.CheckParams(map[string]string{"text": "préndeme ollama"}); err != nil {
		t.Errorf("agent.ask debe aceptar un pedido normal: %v", err)
	} else if err := a.CheckParams(map[string]string{}); err == nil {
		t.Error("agent.ask no debe aceptar un pedido sin texto")
	}
	// ai.chat (conversación cruda con el LLM, sin el catálogo de intents del Agente): a diferencia de
	// agent.ask nunca puede mutar el dispositivo, así que su riesgo es "read" — pero cada llamada es cara
	// (inferencia real), así que SÍ exige nivel mínimo (semi-total), igual que fs.list/storage.list_dir:
	// sin esto, un token de nivel "lectura" podía martillar el modelo local sin límite (revisión de
	// seguridad real).
	if a, ok := reg.Get("ai.chat"); !ok || a.Kind != router.KindInApp || a.RiskLevel().String() != "read" {
		t.Error("ai.chat debe existir, ser inapp y de solo lectura")
	} else if min, has := a.RequiredLevel(); !has || min.String() != "semi-total" {
		t.Error("ai.chat debe exigir nivel semi-total (cada llamada es costosa, no es gratis para quien sea)")
	} else if err := a.CheckParams(map[string]string{"text": "hola, qué tal"}); err != nil {
		t.Errorf("ai.chat debe aceptar un mensaje normal: %v", err)
	} else if err := a.CheckParams(map[string]string{"text": "hola", "history": `[{"role":"user","content":"hola"}]`}); err != nil {
		t.Errorf("ai.chat debe aceptar un historial opcional: %v", err)
	} else if err := a.CheckParams(map[string]string{}); err == nil {
		t.Error("ai.chat no debe aceptar un mensaje vacío")
	}
}

// TestBuildWiresScreenHub confirma que build() deja un *screen.Hub real conectado al servidor:
// en modo stub (sin ningún dispositivo real), golpeando screen.relay_status por HTTP de punta a
// punta (como lo haría el panel) la respuesta debe reportar una fuente conectada.
func TestBuildWiresScreenHub(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("KAIROS_GATEWAY_SCREEN_STUB", "1")

	// El token se crea y se guarda en tokens.json ANTES de llamar a build(): el Store que build()
	// arma es una instancia propia que lee el archivo una sola vez al abrirlo, así que un token
	// creado en una instancia distinta después de build() nunca llegaría a la que de verdad
	// atiende las peticiones HTTP.
	pre, err := auth.Open(filepath.Join(dir, "tokens.json"))
	if err != nil {
		t.Fatal(err)
	}
	secret, _, err := pre.Create("prueba", permission.Read)
	if err != nil {
		t.Fatal(err)
	}

	srv, err := build(dir, slog.New(slog.NewTextHandler(io.Discard, nil)), "127.0.0.1:8973", nil)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv)
	defer ts.Close()

	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/v1/actions/screen.relay_status", strings.NewReader("{}"))
	req.Header.Set("Authorization", "Bearer "+secret)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		t.Fatalf("screen.relay_status debe responder 200 incluso con nivel de solo lectura (riesgo read): %d %s", resp.StatusCode, raw)
	}
	// El "output" de la acción es un JSON embebido dentro de otro JSON (handleAction lo manda vía
	// json.Marshal del Result completo), así que las comillas internas llegan escapadas.
	if !strings.Contains(string(raw), `connected`) || !strings.Contains(string(raw), `true`) {
		t.Errorf("en modo stub el relay debe reportar una fuente conectada: %s", raw)
	}
}

func TestUserManifestOverridesAndCorruptOneFails(t *testing.T) {
	dir := t.TempDir()
	reg, err := loadRegistry(filepath.Join(dir, "manifest.json"))
	if err != nil || len(reg.List()) == 0 {
		t.Fatalf("sin archivo debe usar el incorporado: %v", err)
	}
	custom := `{"actions":[{"id":"solo","title":"Solo","risk":"read","kind":"native","target":"gateway.version"}]}`
	if err := os.WriteFile(filepath.Join(dir, "manifest.json"), []byte(custom), 0o600); err != nil {
		t.Fatal(err)
	}
	reg, err = loadRegistry(filepath.Join(dir, "manifest.json"))
	if err != nil || len(reg.List()) != 1 {
		t.Fatalf("el del usuario debe mandar: %v %v", reg, err)
	}
	os.WriteFile(filepath.Join(dir, "manifest.json"), []byte("{roto"), 0o600)
	if _, err := loadRegistry(filepath.Join(dir, "manifest.json")); err == nil {
		t.Error("un manifiesto corrupto debe dar error, no caer al incorporado en silencio")
	}
}

func TestBuildServesHealthAndDefaultsToConservativePolicy(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("KAIROS_GATEWAY_ADMIN_SECRET", "s")
	srv, err := build(dir, slog.New(slog.NewTextHandler(io.Discard, nil)), "127.0.0.1:8973", nil)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv)
	defer ts.Close()
	resp, err := http.Get(ts.URL + "/healthz")
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("healthz: %v %v", resp, err)
	}
	resp.Body.Close()

	req, _ := http.NewRequest("GET", ts.URL+"/v1/admin/policy", nil)
	req.Header.Set("X-Kairos-Admin", "s")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !strings.Contains(string(raw), `"max":"pregunta"`) || !strings.Contains(string(raw), `"responsible":false`) {
		t.Errorf("la política inicial debe ser conservadora, es %s", raw)
	}
}

func TestRunRejectsBadListenAddress(t *testing.T) {
	if err := run([]string{"--listen", "sin-puerto", "--dir", t.TempDir()}); err == nil {
		t.Error("una dirección inválida debe fallar antes de arrancar")
	}
}

// TestLanIPsFiltersLinkLocalAndLoopback ( lote 28) -- con net.Addr fabricados a mano, no
// con las interfaces reales de la máquina que corre el test (confirmado en vivo que varían: esta
// PC devuelve 169.254.x.x y un adaptador virtual de WSL mezclados con la IP de LAN real; el bug
// real que motivó el filtro de IsLinkLocalUnicast() se encontró así, corriendo el binario de
// verdad -- este test congela esa corrección para que una regresión futura no la deshaga en
// silencio).
func TestLanIPsFiltersLinkLocalAndLoopback(t *testing.T) {
	mustCIDR := func(s string) net.Addr {
		ip, ipNet, err := net.ParseCIDR(s)
		if err != nil {
			t.Fatal(err)
		}
		ipNet.IP = ip
		return ipNet
	}
	addrs := []net.Addr{
		mustCIDR("192.168.1.8/24"),      // LAN real -- debe quedar
		mustCIDR("127.0.0.1/8"),         // loopback -- fuera
		mustCIDR("169.254.40.15/16"),    // link-local/autoconfig -- fuera (el bug real)
		mustCIDR("::1/128"),             // IPv6 -- fuera (To4() == nil)
		&net.UnixAddr{Name: "no-es-ip"}, // ni siquiera es *net.IPNet -- fuera, sin pánico
	}
	got := lanIPs(addrs)
	if len(got) != 1 || got[0] != "192.168.1.8" {
		t.Errorf("lanIPs() = %v, quería solo [192.168.1.8]", got)
	}
}

func TestFilteredEnvironDropsGatewaySecrets(t *testing.T) {
	t.Setenv("KAIROS_GATEWAY_ADMIN_SECRET", "secreto-admin-no-debe-verse")
	t.Setenv("KAIROS_GATEWAY_APP_SECRET", "secreto-app-no-debe-verse")
	t.Setenv("PATH_NO_TOCAR_ESTA", "valor-normal-debe-quedar")

	got := filteredEnviron()
	for _, kv := range got {
		if strings.HasPrefix(kv, "KAIROS_GATEWAY_") {
			t.Errorf("filteredEnviron() dejó pasar un secreto propio: %q", kv)
		}
	}
	found := false
	for _, kv := range got {
		if kv == "PATH_NO_TOCAR_ESTA=valor-normal-debe-quedar" {
			found = true
		}
	}
	if !found {
		t.Error("filteredEnviron() no debería filtrar variables que no son del gateway")
	}
}
