package main

import (
	"encoding/base64"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// TestDataHTMLRoundTripsUTF8 — reporte real del usuario (2026-10-26): el texto de las páginas
// mostradas en la ventana salía roto ("tÃºnel", "pÃºblica") porque [dataHTML] antes usaba
// url.QueryEscape (pensado para application/x-www-form-urlencoded, no para la parte de datos de
// una URI data:, y deja el charset a criterio del WebView). Decodificando el base64 de vuelta
// debe devolver exactamente los mismos bytes UTF-8 que se le pasaron, tildes incluidas.
func TestDataHTMLRoundTripsUTF8(t *testing.T) {
	body := "<h2>Túnel SSH — clave pública, emparejamiento, á é í ó ú ñ</h2>"
	url := dataHTML(body)
	const prefix = "data:text/html;charset=utf-8;base64,"
	if !strings.HasPrefix(url, prefix) {
		t.Fatalf("dataHTML no empieza con %q, salió: %q", prefix, url)
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(url, prefix))
	if err != nil {
		t.Fatalf("el base64 generado no decodifica: %v", err)
	}
	if string(decoded) != body {
		t.Errorf("round-trip UTF-8 roto:\nesperado: %q\nobtenido: %q", body, string(decoded))
	}
}

// TestSchemeForDecidesByLoopback — hallazgo real de seguridad ("TLS en el listener LAN -- el
// token viaja en texto plano"): schemeFor() debe decidir http/https por si el target ES
// loopback, no por si el flujo "parece" local o remoto -- un teléfono emparejado por USB
// (adb forward) también llega como loopback desde la PC, y NO debe pedir https (su propio
// gateway sigue bindeado a 127.0.0.1, sin TLS).
func TestSchemeForDecidesByLoopback(t *testing.T) {
	cases := map[string]string{
		"127.0.0.1:8973":    "http",  // subproceso embebido local, o teléfono por USB forward
		"localhost:8973":    "https", // "localhost" no es una IP -- net.ParseIP no lo resuelve, cae a https (acorde: solo loopback NUMÉRICO se trata como local)
		"[::1]:8973":        "http",  // loopback IPv6
		"192.168.1.42:8973": "https", // teléfono real en la LAN -- bind no-loopback, SÍ tiene TLS
		"10.0.0.5:8973":     "https",
	}
	for target, want := range cases {
		if got := schemeFor(target); got != want {
			t.Errorf("schemeFor(%q) = %q, quiero %q", target, got, want)
		}
	}
}

// Hallazgo real de revisión automática ("improper certificate validation (MITM)"):
// pinnedTLSClient() debe rechazar un certificado DISTINTO del pinneado -- de lo contrario un
// atacante activo en la LAN presentando su propio cert autofirmado se queda con el token Bearer.
func TestPinnedTLSClientRejectsMismatchedCertificate(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	// Primera conexión real: capturamos el fingerprint de ESTE servidor de prueba, igual que
	// requestPairing() hace con el teléfono real al emparejar por primera vez.
	unpinned := insecureTLSClient(2 * time.Second)
	resp, err := unpinned.Get(srv.URL)
	if err != nil {
		t.Fatalf("la primera conexión (sin pinning) debería funcionar: %v", err)
	}
	fingerprint := certFingerprintSHA256(resp)
	resp.Body.Close()
	if fingerprint == "" {
		t.Fatal("no se capturó ningún fingerprint de una conexión TLS real")
	}

	// Mismo fingerprint pinneado -> sigue conectando (caso normal, el certificado no cambió).
	pinnedOK := pinnedTLSClient(2*time.Second, fingerprint)
	if resp2, err := pinnedOK.Get(srv.URL); err != nil {
		t.Errorf("con el fingerprint correcto pinneado, la conexión no debería fallar: %v", err)
	} else {
		resp2.Body.Close()
	}

	// Fingerprint DISTINTO (simula un impostor con su propio cert) -> debe rechazar la conexión
	// antes de que cualquier header de auth llegue a salir.
	wrongFingerprint := "0000000000000000000000000000000000000000000000000000000000000"
	pinnedWrong := pinnedTLSClient(2*time.Second, wrongFingerprint)
	if _, err := pinnedWrong.Get(srv.URL); err == nil {
		t.Fatal("se esperaba que la conexión fallara con un fingerprint pinneado distinto del real")
	}
}

// TestCheckPinnedCertificateDetectsMismatch — hallazgo real de revisión automática ("pin
// bypass via silent re-pair"): ensurePaired() antes caía SIEMPRE a requestPairing() cuando
// remoteTokenStillValid() daba false, sin importar el motivo -- un atacante activo presentando
// su propio certificado lograba ese "false" y el código volvía a emparejar solo, pinneando en
// silencio el certificado del impostor. checkPinnedCertificate() es la pieza que ensurePaired()
// ahora consulta ANTES de siquiera considerar un re-pairing -- este test prueba esa pieza
// directamente contra un servidor TLS real (scheme "https" forzado a propósito:
// httptest.NewTLSServer siempre bindea 127.0.0.1, que schemeFor() trataría como loopback/http
// -- ver TestSchemeForDecidesByLoopback, que cubre esa decisión aparte).
func TestCheckPinnedCertificateDetectsMismatch(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	target := strings.TrimPrefix(srv.URL, "https://")

	// Sin nada pinneado todavía -> nada que comparar, no bloquea.
	if err := checkPinnedCertificate("https", target, ""); err != nil {
		t.Errorf("sin fingerprint pinneado no debería bloquear, dio: %v", err)
	}

	// Fingerprint real del servidor (capturado como lo haría requestPairing la primera vez) ->
	// coincide, no bloquea.
	resp, err := insecureTLSClient(2 * time.Second).Get(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	realFingerprint := certFingerprintSHA256(resp)
	resp.Body.Close()
	if err := checkPinnedCertificate("https", target, realFingerprint); err != nil {
		t.Errorf("con el fingerprint correcto no debería bloquear, dio: %v", err)
	}

	// Fingerprint DISTINTO (simula que el certificado real cambió -- posible suplantación) ->
	// debe devolver ErrCertificatePinMismatch, nunca nil.
	wrongFingerprint := strings.Repeat("0", 64)
	if err := checkPinnedCertificate("https", target, wrongFingerprint); !errors.Is(err, ErrCertificatePinMismatch) {
		t.Fatalf("checkPinnedCertificate() con fingerprint distinto = %v, quiero ErrCertificatePinMismatch", err)
	}
}

// TestCheckPinnedCertificateFailsClosedOnConnectionError — hallazgo real de revisión
// automática ("fail-open certificate pinning"): la primera versión trataba un error de
// conexión (timeout, firewall, servidor caído) como "inconcluso, seguí adelante" -- un
// atacante que lograra que ESTA sonda en particular fallara (sin necesitar romper la conexión
// real de remoteTokenStillValid/requestPairing) conseguía el mismo bypass. Ahora cualquier
// falla al verificar, no solo un mismatch confirmado, debe bloquear.
func TestCheckPinnedCertificateFailsClosedOnConnectionError(t *testing.T) {
	// 192.0.2.1 (TEST-NET-1, RFC 5737) nunca enruta a nada real -- la conexión falla de forma
	// determinística y rápida (con el timeout de 3s de checkPinnedCertificate).
	err := checkPinnedCertificate("https", "192.0.2.1:8973", strings.Repeat("0", 64))
	if err == nil {
		t.Fatal("se esperaba un error al no poder verificar el certificado -- nunca nil con un fingerprint pinneado")
	}
	if !errors.Is(err, ErrCertificateUnverifiable) {
		t.Errorf("checkPinnedCertificate() con conexión inalcanzable = %v, quiero envolver ErrCertificateUnverifiable", err)
	}
}

// TestCheckPinnedCertificateRejectsEmptyFingerprintWhenPinned — hallazgo real de revisión
// automática ("redirect / empty-fingerprint differential"): un fingerprint capturado vacío
// (sin info de TLS en la respuesta) NUNCA debe tratarse como "no hay nada que comparar" cuando
// SÍ hay un fingerprint pinneado -- eso sería fail-open otra vez.
func TestCheckPinnedCertificateRejectsEmptyFingerprintWhenPinned(t *testing.T) {
	// Servidor HTTP plano (sin TLS) -- certFingerprintSHA256() siempre da "" acá, simulando el
	// caso "se esperaba TLS real pero no lo hay".
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	target := strings.TrimPrefix(srv.URL, "http://")
	if err := checkPinnedCertificate("http", target, strings.Repeat("0", 64)); !errors.Is(err, ErrCertificatePinMismatch) {
		t.Fatalf("checkPinnedCertificate() sin TLS real con un fingerprint pinneado = %v, quiero ErrCertificatePinMismatch", err)
	}
}

func TestCertFingerprintSHA256ReturnsEmptyForPlainHTTP(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	resp, err := http.Get(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if got := certFingerprintSHA256(resp); got != "" {
		t.Errorf("una conexión HTTP plana (loopback/USB forward) no debería tener fingerprint, salió %q", got)
	}
}

func TestWaitingAndErrorPagesUseBase64DataURL(t *testing.T) {
	for _, u := range []string{
		waitingPageDataURL("192.168.1.8:8973", "PC con tilde: ñ"),
		errorPageDataURL("falló con tilde: é"),
		waitingSSHPageDataURL("usuario@192.168.1.8:8022"),
	} {
		if !strings.HasPrefix(u, "data:text/html;charset=utf-8;base64,") {
			t.Errorf("página no usa el formato base64 esperado: %q", u)
		}
	}
}
