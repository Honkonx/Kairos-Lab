package router

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

func TestProbeGatewayAcceptsRealHealthzRejectsOther(t *testing.T) {
	gw := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/healthz" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Write([]byte(`{"ok":true,"version":"test"}`))
	}))
	defer gw.Close()

	notGw := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`not even json`))
	}))
	defer notGw.Close()

	client := &http.Client{Timeout: time.Second}
	host, port := splitHostPortForTest(t, gw.URL)
	if !probeGateway(context.Background(), client, host, port) {
		t.Error("un gateway real (healthz OK con ok:true) debería contar como encontrado")
	}
	host2, port2 := splitHostPortForTest(t, notGw.URL)
	if probeGateway(context.Background(), client, host2, port2) {
		t.Error("un servidor que no habla el protocolo del gateway NO debería contar como encontrado")
	}
	if probeGateway(context.Background(), client, "127.0.0.1", "1") {
		t.Error("un puerto sin nada escuchando no debería contar como encontrado")
	}
}

// TestProbeGatewayDoesNotFollowRedirects — hallazgo de revisión automática (2026-10-26, "SSRF
// (redirect following)"): un host malicioso en la LAN que responda 3xx a /healthz no debe poder
// mandar el escaneo a otra URL (interna o externa). Usa el MISMO CheckRedirect que ScanLANGateways
// de verdad construye — si alguien revierte ese cambio sin tocar el test, esto lo detecta.
func TestProbeGatewayDoesNotFollowRedirects(t *testing.T) {
	evil := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", "http://169.254.169.254/latest/meta-data/")
		w.WriteHeader(http.StatusFound)
	}))
	defer evil.Close()

	client := &http.Client{
		Timeout:       time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	host, port := splitHostPortForTest(t, evil.URL)
	if probeGateway(context.Background(), client, host, port) {
		t.Error("un host que responde 3xx no debería contar como gateway encontrado (y no debería seguirse el redirect)")
	}
}

func splitHostPortForTest(t *testing.T, rawURL string) (string, string) {
	t.Helper()
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatal(err)
	}
	host, port, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatal(err)
	}
	return host, port
}
