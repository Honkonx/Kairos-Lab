package server

import (
	"context"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/pending"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
)

// pairRequestBody — lo único que manda quien pide emparejarse: un nombre para mostrarlo en el
// diálogo del teléfono (ej. "PC de la oficina"). Nada de credenciales todavía — no las tiene.
type pairRequestBody struct {
	Name string `json:"name"`
}

const pairCooldownWindow = 10 * time.Second

// pairCooldown evita que un mismo origen spamee pedidos de pairing (que disparan una
// notificación real en el teléfono cada vez) — hallazgo propio al diseñar este endpoint SIN
// autenticación (es la razón de ser del pairing: quien pide todavía no tiene token). Ver
//, Flujo 3 (pairing USB), investigación de referencia/.
//
// Corrección de revisión automática (2026-10-26, "rate-limit bypass / unbounded memory"): la
// primera versión indexaba por `r.RemoteAddr` COMPLETO (incluye el puerto efímero, distinto en
// cada conexión TCP nueva) — cualquiera podía esquivar el enfriamiento abriendo una conexión
// nueva por pedido. Ahora se indexa solo por el HOST (sin puerto). Además, `last` nunca se
// achicaba (crecimiento sin límite ante muchas IPs distintas) — ahora cada llamada barre
// entradas vencidas antes de insertar la propia.
type pairCooldown struct {
	mu   sync.Mutex
	last map[string]time.Time
}

func newPairCooldown() *pairCooldown { return &pairCooldown{last: map[string]time.Time{}} }

// sourceHost — el host real de [addr], sin puerto, SIN truncar — esto es lo que ve la persona en
// la notificación del teléfono ("desde"). Separado de [cooldownKey] a propósito (hallazgo de
// revisión automática, 2026-10-26, "approval spoofing mitigation weakened"): truncar al /64 sirve
// para que el RATE-LIMITING no se esquive con IPv6, pero mostrarle a la persona un /64 truncado
// en vez de la dirección real debilita la poca verificación que sí tenía — la persona debe ver
// exactamente de dónde viene el pedido, no una versión difuminada.
func sourceHost(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	return host
}

// cooldownKey — el host de [addr], sin puerto. Para IPv6 se trunca al prefijo /64: un atacante
// con un solo bloque IPv6 delegado (algo trivial de conseguir, a diferencia de IPv4) puede
// generar direcciones distintas dentro de ESE /64 a voluntad y esquivar un enfriamiento por
// dirección exacta (hallazgo de revisión automática, 2026-10-26, "key the cooldown by IPv6 /64
// prefix instead of the full address") — IPv4 no tiene ese problema (una sola IP por host real en
// la inmensa mayoría de los casos), se sigue usando entera. Uso INTERNO solo (rate-limiting y el
// tope por origen) — para lo que ve la persona, ver [sourceHost].
func cooldownKey(addr string) string {
	host := sourceHost(addr)
	ip := net.ParseIP(host)
	if ip == nil || ip.To4() != nil {
		return host // IPv4, o no es una IP parseable (ej. un host de prueba) -- sin cambios
	}
	mask := net.CIDRMask(64, 128)
	return ip.Mask(mask).String()
}

// allow — true si [addr] (por host, no por puerto) puede pedir un pairing nuevo ahora (han
// pasado al menos [pairCooldownWindow] desde su último pedido). No es un rate-limiter general,
// es lo mínimo para que reintentar rápido no mande notificaciones casi idénticas en cadena.
func (c *pairCooldown) allow(addr string) bool {
	key := cooldownKey(addr)
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for k, t := range c.last {
		if now.Sub(t) >= pairCooldownWindow {
			delete(c.last, k)
		}
	}
	if t, ok := c.last[key]; ok && now.Sub(t) < pairCooldownWindow {
		return false
	}
	c.last[key] = now
	return true
}

// handlePairRequest — sin `s.authenticate(r)` a propósito: quien pide emparejarse todavía no
// tiene token, ese es el problema que este endpoint resuelve. Reusa el mismo pending.Store +
// evento SSE "pending" que ya usa cualquier acción de nivel "pregunta" — GatewayConfirmationWatcher.kt
// (escucha el evento) y GatewayConfirmReceiver.kt (botones Aprobar/Denegar de la notificación)
// NO necesitan ningún cambio: ya manejan cualquier id/título/params de forma genérica.
//
// Al aprobar, NO se deja que el Dispatcher genérico corra una Action registrada (no hay ninguna
// "gateway.pair_request" en el manifiesto) — acá mismo se emite un token nuevo, de nivel Ask por
// defecto (nunca Full:, recomendación de la investigación de referencia/,
// un dispositivo recién emparejado empieza con el nivel más conservador que sigue siendo útil), y
// se devuelve UNA sola vez en esta misma respuesta — nunca se vuelve a mostrar después
// (`.claude/rules/kairos-secrets-never-revealed.md`).
//
// Limitación real, documentada, no resuelta esta ronda: el nombre que ve la persona en el
// teléfono lo elige quien pide el pairing — no hay verificación criptográfica (SAS/fingerprint,
// como sí hace Sefirah-Android, ver la investigación) de que el nombre corresponda al dispositivo
// real que está del otro lado del cable/red. Para USB (conexión física directa) el riesgo de que
// otra cosa intercepte es bajo; para LAN es mayor — ver Flujo 2/3 en para la
// propuesta completa con verificación de huella, no implementada todavía. Mitigado en parte
// (revisión automática, "unauthenticated credential issuance / approval spoofing"): la dirección
// real de quien pide emparejarse ahora viaja en los params y se muestra en la notificación — la
// persona ve DE DÓNDE viene el pedido, no solo el nombre que esa conexión eligió mostrar.
//
// Historia real de este límite, varias vueltas de revisión automática: la primera versión
// rechazaba un pairing nuevo si YA había uno pendiente (cupo=1) — un cliente sin autenticar podía
// ocupar ese único cupo con pedidos basura en bucle y trabar el pairing para cualquier otro
// dispositivo real, indefinidamente ("unauthenticated DoS / pairing pre-emption"). Se sacó el
// límite por completo — pero sin ningún tope, muchos clientes podían acumular pedidos sin fin
// ("approval spoofing / unbounded unauthenticated pending requests"). Con un tope GLOBAL nada más
// (siguiente vuelta), un solo origen podía ocupar el tope entero él solo y seguir bloqueando a
// los demás ("global cap defeatable by a single origin") — por eso [maxConcurrentPairings] (tope
// global) se combina con [maxPairingsPerOrigin] (tope por origen, calculado con [cooldownKey] —
// la misma clave que ya usa el enfriamiento, truncada a /64 en IPv6 por el mismo motivo).
const (
	maxConcurrentPairings = 3
	maxPairingsPerOrigin  = 1
)

func countPendingPairings(list []pending.Snapshot, origin string) (total int, fromOrigin int) {
	for _, snap := range list {
		if snap.Request.ActionID != "gateway.pair_request" {
			continue
		}
		total++
		if snap.Request.Params["origen"] == origin {
			fromOrigin++
		}
	}
	return total, fromOrigin
}

func (s *Server) handlePairRequest(w http.ResponseWriter, r *http.Request) {
	if !s.pairCooldown.allow(r.RemoteAddr) {
		writeError(w, http.StatusTooManyRequests, "esperá unos segundos antes de pedir emparejarte de nuevo")
		return
	}
	origin := cooldownKey(r.RemoteAddr)
	// Chequeo optimista, no atómico con el Create() de más abajo — una carrera muy angosta podría
	// dejar pasar un pedido de más por origen/en total; dado que esto es anti-abuso (no un límite
	// de seguridad duro) y el pending.Store no expone un "check+create" combinado, se acepta ese
	// margen en vez de ampliar la API del Store solo para esto.
	total, fromOrigin := countPendingPairings(s.cfg.Pending.List(), origin)
	if total >= maxConcurrentPairings {
		writeError(w, http.StatusTooManyRequests, "hay demasiados pedidos de emparejamiento esperando confirmación — resolvé alguno antes de pedir otro")
		return
	}
	if fromOrigin >= maxPairingsPerOrigin {
		writeError(w, http.StatusTooManyRequests, "ya tenés un pedido de emparejamiento esperando confirmación")
		return
	}
	var body pairRequestBody
	if err := decodeBody(w, r, &body); err != nil {
		writeError(w, http.StatusBadRequest, "cuerpo JSON inválido")
		return
	}
	name := strings.TrimSpace(body.Name)
	if name == "" || len(name) > 64 {
		writeError(w, http.StatusBadRequest, "el nombre debe tener entre 1 y 64 caracteres")
		return
	}

	req := s.cfg.Pending.Create(pending.Request{
		ActionID: "gateway.pair_request",
		Title:    "¿Aceptar esta PC?",
		Risk:     "danger",
		// "desde" (lo que ve la persona) usa el host REAL, nunca truncado -- ver [sourceHost].
		// "origen" (uso interno, para el tope por origen de arriba) sí usa la clave truncada.
		Params: map[string]string{"name": name, "desde": sourceHost(r.RemoteAddr), "origen": origin},
	})
	s.cfg.Hub.Publish("pending", req)

	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
	defer cancel()
	snap, _ := s.cfg.Pending.WaitDecided(ctx, req.ID)

	// Si el contexto venció porque el CLIENTE se desconectó (no por nuestro propio timeout de 2
	// minutos) y la petición seguía "pendiente", liberar el cupo ya mismo en vez de dejarlo
	// ocupado hasta que expire solo por su cuenta (hallazgo de revisión automática: "on client
	// disconnect, mark the pending entry expired/denied so it frees its slot").
	if snap.State == pending.StatePending {
		s.cfg.Pending.Decide(req.ID, false)
	}

	switch snap.State {
	case pending.StateApproved:
		raw, tok, err := s.cfg.Tokens.Create(name, permission.Ask)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "no se pudo crear el token: "+err.Error())
			return
		}
		s.cfg.Pending.Complete(req.ID, map[string]string{"token_id": tok.ID})
		writeJSON(w, http.StatusOK, map[string]any{"state": "aceptado", "token": raw, "level": tok.Level.String()})
	case pending.StateDenied:
		writeError(w, http.StatusForbidden, "la persona rechazó la conexión")
	default: // "pendiente" (se liberó recién arriba) o "expirada"
		writeError(w, http.StatusRequestTimeout, "nadie respondió a tiempo")
	}
}
