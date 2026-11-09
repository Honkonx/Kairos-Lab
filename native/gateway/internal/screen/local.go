package screen

import (
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
)

// Este archivo conecta el Hub con el servidor REAL de pantalla (scrcpy-server.jar, adaptado con
// crédito de scrcpy Apache-2.0/Genymobile — ver docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md)
// cuando corre en el MISMO dispositivo que el gateway: ese servidor habla el protocolo real de
// scrcpy, no el nuestro, así que no puede pasar por el handshake HMAC de handshake.go (ese sigue
// siendo el camino para una fuente remota/propia que SÍ hable nuestro protocolo). La autenticación
// acá es otra: el nombre del socket abstracto de Linux (`scrcpy_<scid hex>`) se deriva del mismo
// secreto de 32 bytes (`screen.secret`) que ya genera el gateway — solo un proceso lanzado por
// nuestro propio código (que conoce el secreto) puede adivinar el scid correcto antes de que el
// gateway ya esté escuchando ese nombre exacto.
//
// Confirmado contra un dispositivo real: `net.Listen("unix", "@scrcpy_<scid>")` +
// Accept() recibe la conexión real del scrcpy-server sin ADB, sin cable, sin red — todo en el
// mismo proceso Linux del teléfono (namespace abstracto de sockets Unix, compartido entre procesos
// del mismo usuario). El primer paquete que manda el servidor real son los 64 bytes fijos del
// nombre del dispositivo (DesktopConnection.sendDeviceMeta, DEVICE_NAME_FIELD_LENGTH=64) — se leen
// y se descartan del stream antes de tratar el resto como frames de video.
const deviceNameFieldLength = 64

// DeriveScid deriva un scid de 32 bits (el formato que espera `scid=%08x` en la línea de arranque
// del servidor) del secreto del gateway — determinista: mismo secreto, mismo scid siempre, para que
// el lado que lanza el proceso (hoy Kotlin, ScreenMirrorManager) y el lado que escucha (acá) se
// pongan de acuerdo sin mandarse el secreto completo por ningún lado.
func DeriveScid(secret []byte) uint32 {
	sum := sha256.Sum256(secret)
	return binary.BigEndian.Uint32(sum[:4])
}

// LocalSocketName arma el nombre del socket abstracto tal como lo calcula
// DesktopConnection.getSocketName(scid) del lado Java: "scrcpy_" + 8 dígitos hex.
func LocalSocketName(scid uint32) string {
	return fmt.Sprintf("scrcpy_%08x", scid)
}

// DeviceName es el último nombre de dispositivo recibido de una fuente local real — vacío si
// ninguna se conectó todavía. Expuesto para `screen.relay_status`.
func (h *Hub) DeviceName() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.deviceName
}

// ListenLocal escucha el socket abstracto Unix `scrcpy_<scid>` (sin red, sin ADB) y acepta
// conexiones del servidor de pantalla real cuando corre en este mismo dispositivo. Devuelve
// inmediatamente; el aceptador corre en una goroutine propia, reintentando si `Listen` falla (p.
// ej. el nombre ya está ocupado) con el mismo intervalo que [StartConnector]. Mutuamente excluyente
// con [StartConnector]/[UseStub] — usar solo uno por Hub. [expectDir] es la carpeta donde se busca
// [ExpectFileName] para saber si el próximo ciclo también va a traer audio/control — mismo <dir>
// que ya reciben screen.scid/screen.secret (pasale "" para desactivar la lectura, p. ej. en tests).
func (h *Hub) ListenLocal(scid uint32, expectDir string) {
	h.localMu.Lock()
	h.expectDir = expectDir
	h.localExpect = 0
	h.localMu.Unlock()
	name := "@" + LocalSocketName(scid)
	go func() {
		for {
			ln, err := net.Listen("unix", name)
			if err != nil {
				h.logger.Warn("no se pudo escuchar el socket de pantalla local (¿el gateway ya tiene otra instancia corriendo?)", "name", name, "error", err)
				return
			}
			h.logger.Info("escuchando el socket de pantalla local", "name", name)
			for {
				conn, err := ln.Accept()
				if err != nil {
					h.logger.Debug("el socket de pantalla local se cerró", "error", err)
					break
				}
				h.acceptLocalSource(conn)
			}
			_ = ln.Close()
		}
	}()
}

// ExpectFileName es el archivo que ScreenMirrorManager.kt (Kotlin) escribe en el mismo <dir> del
// gateway, justo ANTES de lanzar cada arranque real del servidor, con los canales que ese arranque
// va a abrir — "audio" y/o "control" en cualquier orden, separados por coma (p. ej. "control" o
// "audio,control"; vacío o ausente = solo video, el comportamiento de antes de esta ronda). Go no
// tiene otra forma de saber qué conexiones esperar después de la de video: Kotlin y el gateway son
// procesos separados (ProcessBuilder, no JNI — ver GatewayManager.kt), así que no hay una llamada
// de función directa para avisar esto; un archivo chico es el mismo patrón ya usado por
// screen.scid/screen.secret.
const ExpectFileName = "screen.expect"

// acceptLocalSource consume la cabecera fija de 64 bytes (nombre del dispositivo, protocolo real de
// scrcpy) SOLO en la conexión de video (la primera del ciclo) y recién después la deja como fuente
// del Hub — así [Hub.feed] nunca intenta interpretar esos 64 bytes como si fueran la cabecera de 12
// bytes de un frame. Las conexiones siguientes del mismo ciclo (audio, control) no llevan esa
// cabecera — DesktopConnection solo la manda una vez, en la conexión de video
// (`sendDeviceMeta`/`DesktopConnection.open()`, confirmado leyendo el código fuente real de scrcpy).
func (h *Hub) acceptLocalSource(conn net.Conn) {
	allowed, desc, err := h.peerCheck(conn)
	if err != nil {
		h.logger.Warn("no se pudo verificar la credencial del proceso que se conectó al socket de pantalla; se rechaza por seguridad", "error", err)
		_ = conn.Close()
		return
	}
	if !allowed {
		h.logger.Warn("conexión rechazada al socket de pantalla local: no tiene identidad root/shell", "peer", desc)
		_ = conn.Close()
		return
	}

	h.localMu.Lock()
	step := h.localExpect
	h.localMu.Unlock()

	switch step {
	case 0: // video — siempre la primera conexión del ciclo.
		name := make([]byte, deviceNameFieldLength)
		if _, err := io.ReadFull(conn, name); err != nil {
			h.logger.Warn("la fuente local se desconectó antes de mandar el nombre del dispositivo", "error", err)
			_ = conn.Close()
			return
		}
		trimmed := trimNullSuffix(name)
		h.mu.Lock()
		h.deviceName = trimmed
		h.mu.Unlock()
		h.logger.Info("fuente de pantalla local conectada (video)", "device", trimmed)
		h.setSource(conn) // setSource ya resetea controlConn a nil — este es el arranque de un ciclo nuevo.
		h.advanceLocalExpect(0)
	case 1: // audio — solo si el archivo de expectativa pidió "audio". Sin decodificador EN VIVO
		// todavía (Opus/AAC, fuera de alcance de esta ronda): [feedAudio] solo guarda los frames
		// crudos a disco cuando hay una grabación activa (screen.record_start) — un visor conectado
		// ahora mismo no recibe audio en vivo, solo queda grabado para convertir después con ffmpeg.
		h.logger.Info("fuente de pantalla local conectada (audio)")
		go h.feedAudio(conn)
		h.advanceLocalExpect(1)
	case 2: // control — el canal real que SendControl usa para mandar los toques/gestos del panel.
		h.logger.Info("fuente de pantalla local conectada (control)")
		h.SetControlConn(conn)
		h.advanceLocalExpect(2)
	default:
		h.logger.Warn("conexión de pantalla local inesperada (ciclo ya completo) — se descarta", "step", step)
		_ = conn.Close()
	}
}

// Límite conocido, documentado en vez de "resuelto a medias": si el proceso real de scrcpy muere
// justo entre abrir la conexión de video y abrir audio/control (ventana muy angosta, solo durante
// el arranque), localExpect queda esperando audio/control y la SIGUIENTE conexión real (un video
// nuevo de un reintento) se interpretaría mal como si fuera ese canal. No se agregó un timeout para
// esto en esta ronda — probabilidad baja (ventana de milisegundos) y el síntoma visible sería "el
// visor no actualiza, hay que Detener+Iniciar de nuevo desde la app", no un crash ni un dato
// corrupto expuesto a otro visor.

// advanceLocalExpect avanza localExpect al siguiente canal esperado según lo que diga
// [ExpectFileName] en este momento — se relee en cada conexión de video (inicio de ciclo) para que
// un cambio de screen.config (activar/desactivar audio) surta efecto desde el próximo arranque, sin
// tener que reiniciar el gateway.
func (h *Hub) advanceLocalExpect(justAccepted int) {
	h.localMu.Lock()
	dir := h.expectDir
	h.localMu.Unlock()
	wantAudio, wantControl := readExpect(dir)
	sequence := []bool{true, wantAudio, wantControl} // video siempre, luego audio/control si aplica
	next := justAccepted + 1
	for next < len(sequence) && !sequence[next] {
		next++
	}
	h.localMu.Lock()
	if next >= len(sequence) {
		h.localExpect = 0 // ciclo completo — la próxima conexión vuelve a ser video.
	} else {
		h.localExpect = next
	}
	h.localMu.Unlock()
}

// readExpect lee [ExpectFileName] dentro de [dir] — cualquier error (no existe, vacío, formato
// raro) se trata como "solo video", nunca como fatal: perder este archivo no debe tirar abajo el
// relay, en el peor caso el panel manda toques que nadie escucha (se degrada, no se rompe).
func readExpect(dir string) (wantAudio, wantControl bool) {
	if dir == "" {
		return false, false
	}
	raw, err := os.ReadFile(filepath.Join(dir, ExpectFileName))
	if err != nil {
		return false, false
	}
	parts := strings.Split(strings.TrimSpace(string(raw)), ",")
	for _, p := range parts {
		switch strings.TrimSpace(p) {
		case "audio":
			wantAudio = true
		case "control":
			wantControl = true
		}
	}
	return wantAudio, wantControl
}

func trimNullSuffix(b []byte) string {
	i := 0
	for i < len(b) && b[i] != 0 {
		i++
	}
	return string(b[:i])
}
