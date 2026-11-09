package router

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"sort"
	"sync"
	"time"
)

// maxScanConcurrency acota cuántas sondas corren a la vez — mismo criterio que Haven/
// RemoteManager.scanSubnet (ver investigación de referencia/): un barrido de
// /24 (254 hosts) en paralelo acotado, nunca sin límite.
const maxScanConcurrency = 64

// maxProbeBody acota cuánto se lee de la respuesta de un host antes de decodificarla — un host
// malicioso en la LAN podría responder con un cuerpo enorme para gastar memoria/tiempo; el JSON
// real de /healthz pesa unos pocos bytes, 4 KB sobra con margen de sobra.
const maxProbeBody = 4 * 1024

// ScanLANGateways busca, en el /24 de cada IP local dada, otros gateways de Kairos reales — un
// host cuenta como "gateway Kairos" SOLO si responde 200 con `{"ok":true,...}` en /healthz. Esto
// da UBICACIÓN, nunca identidad de confianza (mismo criterio que la investigación de referencia/
// confirmó en umbrel: mDNS/discovery nunca prueba quién es alguien por sí solo — acá ni siquiera
// hay mDNS, es un connect+GET directo, y /healthz no exige ningún secreto) — conectarse de
// verdad sigue necesitando el token real, como siempre.
func ScanLANGateways(ctx context.Context, localIPs []string, port string, timeout time.Duration) []string {
	targets := map[string]bool{}
	for _, ipStr := range localIPs {
		ip := net.ParseIP(ipStr).To4()
		if ip == nil {
			continue
		}
		base := fmt.Sprintf("%d.%d.%d.", ip[0], ip[1], ip[2])
		for i := 1; i <= 254; i++ {
			target := fmt.Sprintf("%s%d", base, i)
			if target == ipStr {
				continue // no hace falta sondearse a sí mismo
			}
			targets[target] = true
		}
	}

	var (
		mu    sync.Mutex
		found []string
		wg    sync.WaitGroup
	)
	sem := make(chan struct{}, maxScanConcurrency)
	// Hallazgo de revisión automática (2026-10-26, "SSRF (redirect following)"): el cliente por
	// defecto sigue redirects — un host de la LAN que responda 3xx podría mandar el escaneo a
	// cualquier URL, incluida una fuera de la red local. Nunca seguir redirects acá: un gateway
	// real de Kairos responde /healthz directo, con 200, sin redirigir a ningún lado.
	client := &http.Client{
		Timeout:       timeout,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		// InsecureSkipVerify (hallazgo real de seguridad: "TLS en el listener LAN -- el token
		// viaja en texto plano"): un gateway en modo LAN ahora sirve HTTPS con un certificado
		// autofirmado (ver server.EnsureSelfSignedCert) -- no hay autoridad real que pueda
		// haberlo emitido de antemano para una IP de LAN desconocida, así que no hay nada
		// verificable contra el nombre/IP del target. Esto es lo mismo que ya pasaba en texto
		// plano: la identidad real la da el TOKEN al conectar, nunca el canal de transporte en
		// sí -- TLS acá es defensa en profundidad contra un sniffer pasivo, no una PKI real.
		Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}, //nolint:gosec
	}

	for target := range targets {
		wg.Add(1)
		sem <- struct{}{}
		go func(target string) {
			defer wg.Done()
			defer func() { <-sem }()
			if probeGateway(ctx, client, target, port) {
				mu.Lock()
				found = append(found, target)
				mu.Unlock()
			}
		}(target)
	}
	wg.Wait()
	sort.Strings(found)
	return found
}

// LocalLANIPs filtra las IPv4 locales de verdad (sin loopback, sin link-local, sin IPv6) de una
// lista de direcciones de interfaz — la base para saber qué /24 barrer. Compartida entre
// kairos-gateway (`network.scan_gateways`, el teléfono escaneando su propia LAN) y
// kairos-dashboard (la PC escaneando la LAN para encontrar teléfonos, ver runDiscoverMode) para
// no duplicar el mismo filtro en dos binarios.
func LocalLANIPs(addrs []net.Addr) []string {
	ips := []string{}
	for _, a := range addrs {
		ipNet, ok := a.(*net.IPNet)
		if !ok || ipNet.IP.IsLoopback() || ipNet.IP.IsLinkLocalUnicast() || ipNet.IP.To4() == nil {
			continue
		}
		ips = append(ips, ipNet.IP.String())
	}
	return ips
}

func probeGateway(ctx context.Context, client *http.Client, target, port string) bool {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+target+":"+port+"/healthz", nil)
	if err != nil {
		return false
	}
	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return false
	}
	var out struct {
		OK bool `json:"ok"`
	}
	return json.NewDecoder(io.LimitReader(resp.Body, maxProbeBody)).Decode(&out) == nil && out.OK
}
