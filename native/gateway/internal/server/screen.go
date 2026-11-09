package server

import (
	"net/http"
	"strings"

	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
	"github.com/honkonx/kairos-dev/native/gateway/internal/wsutil"
)

// screenAuthProtocolPrefix: el panel manda el token como subprotocolo WebSocket
// ("kairos-token.<token>"), nunca en la URL — revisión de seguridad real: un token en
// `?token=` queda en el historial del navegador, en logs de acceso del túnel/proxy, y en el header
// Referer si la página navega a otro lado. Para la acción de mayor riesgo del gateway (control
// completo de la pantalla) eso es demasiado — a diferencia de /v1/events (SSE, EventSource tampoco
// puede mandar cabeceras propias, pero es de solo lectura y menor riesgo), acá NO hay respaldo por
// query string: sin el subprotocolo correcto, no hay conexión.
const screenAuthProtocolPrefix = "kairos-token."

// screenTokenFromRequest busca el token en Sec-WebSocket-Protocol (único lugar válido). Devuelve
// "" si no vino ninguno con el prefijo esperado.
func screenTokenFromRequest(r *http.Request) string {
	for _, p := range wsutil.OfferedProtocols(r) {
		if strings.HasPrefix(p, screenAuthProtocolPrefix) {
			return strings.TrimPrefix(p, screenAuthProtocolPrefix)
		}
	}
	return ""
}

// handleScreenWS es el relay de video/control del espejo de pantalla propio (ver
// docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md). Es la acción de mayor riesgo del gateway —
// control completo de la pantalla es control completo del dispositivo — así que, a diferencia de
// handleAction, NO pasa por la matriz de permisos (lectura/pregunta/semi-total/total): exige
// directamente el nivel máximo (total, con la casilla de responsabilidad marcada), sin la opción
// intermedia de "preguntar" que sí tienen otras acciones peligrosas — no hay forma de pedir
// confirmación por cada frame de video. Mismo criterio que la acción screen.status (manifiesto,
// min_level: total) y que session.open/fs.write/distro.install, pero sin pasar por el Registry
// porque esta ruta no es una "acción" de una sola petición, es una conexión de larga duración.
func (s *Server) handleScreenWS(w http.ResponseWriter, r *http.Request) {
	if s.cfg.Screen == nil {
		writeError(w, http.StatusNotFound, "el relay de pantalla no está activo en este gateway")
		return
	}
	offered := screenTokenFromRequest(r)
	tok, ok := s.cfg.Tokens.Authenticate(offered)
	if offered == "" || !ok {
		writeError(w, http.StatusUnauthorized, "falta el token en Sec-WebSocket-Protocol (\"kairos-token.<token>\"); nunca se acepta por la URL")
		return
	}
	level := s.effective(tok)
	if level != permission.Full {
		s.record(tok.ID, "screen.stream", level, "denegada", "")
		writeError(w, http.StatusForbidden, "controlar la pantalla exige el nivel total (con la casilla de responsabilidad marcada)")
		return
	}
	s.record(tok.ID, "screen.stream", level, "permitida", "conexión abierta")

	conn, err := wsutil.Accept(w, r, screenAuthProtocolPrefix+offered)
	if err != nil {
		writeError(w, http.StatusBadRequest, "no se pudo iniciar websocket: "+err.Error())
		return
	}
	defer conn.Close()
	defer s.record(tok.ID, "screen.stream", level, "permitida", "conexión cerrada")

	frames, cancel := s.cfg.Screen.Subscribe()
	defer cancel()

	// Las dos direcciones del relay (video hacia el visor, control hacia la fuente) corren en
	// goroutines separadas porque net.Conn no tiene una forma de "seleccionar" lectura y escritura
	// a la vez. [done] es necesario además de simplemente "salir cuando se acaben los frames": si
	// el Hub no tiene ninguna fuente conectada (el caso de hoy, sin servidor real todavía, y sin
	// --screen-stub) nunca llega ningún frame — sin [done], la goroutine de escritura se quedaría
	// bloqueada para siempre esperando uno, con la conexión ya cerrada del otro lado.
	done := make(chan struct{})
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		for {
			select {
			case raw := <-frames:
				if err := conn.WriteMessage(wsutil.OpBinary, raw); err != nil {
					return
				}
			case <-done:
				return
			}
		}
	}()

	for {
		opcode, payload, err := conn.ReadMessage()
		if err != nil {
			break
		}
		if opcode != wsutil.OpBinary && opcode != wsutil.OpText {
			continue
		}
		// Un error de SendControl (p. ej. sin fuente conectada, modo simulado) no cierra la
		// conexión: el visor puede seguir viendo "qué se mandaría" aunque no haya nada del otro
		// lado todavía (ver la vista de prueba del panel).
		_ = s.cfg.Screen.SendControl(payload)
	}
	close(done)
	<-writerDone
}
