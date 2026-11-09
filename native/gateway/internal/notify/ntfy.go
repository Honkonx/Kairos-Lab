// Package notify envía notificaciones push vía ntfy (https://ntfy.sh o un servidor propio) --
// hallazgo real de docs/arquitectura/INVESTIGACION_MODULOS_NUEVOS_Y_PENDIENTES_REFERENCIA_2026-11-05.md:
// "ntfy -- notificaciones push sin necesitar un bot de Telegram". ntfy es un binario Go de ~15MB
// con SQLite embebida (no se vendoriza nada de eso acá, solo se habla su API HTTP real: un POST
// al topic con metadata en headers) -- más simple que crear un bot de Telegram para quien solo
// quiere un aviso cuando una acción termina.
package notify

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// DefaultServer es el servidor público de ntfy.sh -- un topic ahí es efectivamente público
// (cualquiera que conozca el nombre del topic puede suscribirse), igual que la documentación
// real de ntfy advierte. Quien quiera privacidad real debe usar su propio servidor (self-hosted)
// o un topic con un token de acceso (ver [Send]).
const DefaultServer = "https://ntfy.sh"

// Priority — mismos niveles reales que acepta la API de ntfy (1 min .. 5 max), documentados en
// su propia API (header "X-Priority"). 3 = default si no se pide otra cosa.
const (
	PriorityMin     = 1
	PriorityDefault = 3
	PriorityMax     = 5
)

// ErrServerNotAllowed -- SSRF real (hallazgo de revisión automática 2026-11-05): "server" es un
// parámetro de la acción `notify.send` del manifiesto, controlado por cualquier caller con nivel
// "write" -- sin este chequeo, alguien podría apuntar el gateway a hacer un POST ciego contra
// cualquier IP/puerto alcanzable desde el proceso (loopback, LAN privada, direct metadata
// endpoints si el gateway corriera en una nube) con el topic/mensaje como payload arbitrario.
// Nunca se resuelve la IP una sola vez y se reusa -- guardDial() revalida en el momento real de
// conectar (defensa contra DNS rebinding: un attacker no puede hacer que el hostname resuelva a
// una IP pública al validar y a una privada al conectar).
var ErrServerNotAllowed = errors.New("servidor ntfy no permitido (debe ser https y no apuntar a una dirección privada/loopback)")

// extraBlockedPrefixes -- rangos que ip.IsPrivate()/IsLoopback()/etc NO cubren pero que un
// servidor público nunca debería resolver a (hallazgo real de 2a revisión automática,
// "incomplete IP denylist"): CGN (100.64/10, RFC 6598), "este host" (0/8), benchmarking
// (198.18/15), NAT64 (64:ff9b::/96, 64:ff9b:1::/48), site-local IPv6 deprecado (fec0::/10),
// y el bloque reservado 240/4.
var extraBlockedPrefixes = []netip.Prefix{
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("64:ff9b::/96"),
	netip.MustParsePrefix("64:ff9b:1::/48"),
	netip.MustParsePrefix("fec0::/10"),
}

// isBlockedIP -- allowlist invertida (hallazgo real: un denylist de métodos puntuales de
// net.IP, como la versión anterior de esta función, siempre deja afuera rangos que nadie pensó
// explícitamente, ej. CGN/benchmarking/NAT64) -- el chequeo base es "¿NO es global unicast?"
// (IsGlobalUnicast() ya excluye loopback/link-local/multicast/unspecified de por sí), y
// extraBlockedPrefixes cubre los rangos que SÍ son técnicamente "global unicast" en la tabla de
// asignación de IANA pero no deberían ser alcanzables desde un server público real. Direcciones
// mapeadas IPv4-en-IPv6 (::ffff:a.b.c.d) se desenmascaran ANTES de chequear -- si no, un
// atacante podría codificar una IP privada como mapeada y pasar ambos chequeos sin querer.
func isBlockedIP(ip net.IP) bool {
	addr, ok := netip.AddrFromSlice(ip)
	if !ok {
		return true // IP ilegible -- bloquear por defecto, nunca permitir por defecto
	}
	addr = addr.Unmap()
	if ip.IsPrivate() || ip.IsLinkLocalMulticast() {
		return true // net.IP ya cubre esto bien; netip.Addr no tiene IsPrivate()
	}
	if !addr.IsGlobalUnicast() {
		return true
	}
	for _, prefix := range extraBlockedPrefixes {
		if prefix.Contains(addr) {
			return true
		}
	}
	return false
}

// guardedClient -- mismo timeout de siempre, pero con un DialContext que resuelve el host él
// mismo y rechaza cualquier IP bloqueada ANTES de conectar (no solo antes del request) y sin
// seguir redirects (un server.tld https legítimo podría 302 a una IP privada).
//
// Forzado a IPv4 ("ip4"/"tcp4") a propósito (3er hallazgo de revisión automática): las formas
// de encapsular IPv4 dentro de IPv6 (::/96 IPv4-compatible, 2002::/16 6to4, 2001::/32 Teredo,
// 192.0.0.0/24, los rangos TEST-NET) son una categoría entera de bypass potencial del
// denylist -- en vez de perseguir cada forma de encaje una por una, se elimina IPv6 del todo
// acá: ntfy.sh y cualquier servidor self-hosted real de este caso de uso funcionan sobre IPv4
// sin problema, y el gateway no tiene ninguna necesidad real de IPv6 para esto.
func guardedClient() *http.Client {
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			ips, err := net.DefaultResolver.LookupIP(ctx, "ip4", host)
			if err != nil {
				return nil, err
			}
			for _, ip := range ips {
				if isBlockedIP(ip) {
					return nil, ErrServerNotAllowed
				}
			}
			// Conectar a la IP ya validada (no al host) -- cierra la ventana de DNS rebinding
			// entre la resolución de arriba y el dial real.
			return dialer.DialContext(ctx, "tcp4", net.JoinHostPort(ips[0].String(), port))
		},
	}
	return &http.Client{
		Timeout:   10 * time.Second,
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

func validateServer(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return "", ErrServerNotAllowed
	}
	if u.Path != "" && u.Path != "/" {
		return "", ErrServerNotAllowed
	}
	host := u.Hostname()
	if ip := net.ParseIP(host); ip != nil && isBlockedIP(ip) {
		return "", ErrServerNotAllowed
	}
	return "https://" + u.Host, nil
}

// Send manda un POST real al topic -- sin configuración persistida a propósito (el caller
// decide server/topic/token cada vez, ver KDoc del paquete): esto evita necesitar un Store+
// endpoints de admin nuevos solo para 2-3 campos que de todas formas el usuario puede guardar
// él mismo del lado que invoque esta acción (ej. los params de una automatización, ver
// AutomationManager.SAFE_GATEWAY_ACTIONS en el lado Android).
func Send(server, topic, title, message, token string, priority int) error {
	topic = strings.TrimSpace(topic)
	if topic == "" {
		return fmt.Errorf("falta el topic de ntfy")
	}
	if strings.ContainsAny(topic, "/ \t\n") {
		return fmt.Errorf("topic de ntfy inválido (sin espacios ni barras): %q", topic)
	}
	base := strings.TrimSpace(server)
	if base == "" {
		base = DefaultServer
	}
	base, err := validateServer(base)
	if err != nil {
		return err
	}
	return doSend(guardedClient(), base, topic, title, message, token, priority)
}

// doSend construye y manda el request real -- separado de [Send] para poder testear la
// construcción del request (headers/body/URL) contra un httptest.Server real sin pelear contra
// el guard de SSRF (ese guard bloquea loopback a propósito, que es justo donde corre cualquier
// servidor de test). [Send] es el único caller real en producción -- siempre pasa por
// [guardedClient]+[validateServer] primero.
func doSend(client *http.Client, base, topic, title, message, token string, priority int) error {
	if priority < PriorityMin || priority > PriorityMax {
		priority = PriorityDefault
	}
	req, err := http.NewRequest(http.MethodPost, base+"/"+topic, strings.NewReader(message))
	if err != nil {
		return err
	}
	if title != "" {
		req.Header.Set("X-Title", title)
	}
	req.Header.Set("X-Priority", strconv.Itoa(priority))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(req)
	if err != nil {
		// Nunca se devuelve el error real de red/dial al caller (podría filtrar si un puerto
		// interno está abierto o no) -- mensaje genérico, mismo criterio que pidió la revisión.
		return fmt.Errorf("no se pudo contactar al servidor de notificaciones")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("ntfy respondió HTTP %d", resp.StatusCode)
	}
	return nil
}
