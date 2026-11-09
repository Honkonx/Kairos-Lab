package screen

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// viewerBuffer es cuántos frames sin entregar se acumulan por visor antes de empezar a descartar.
// Un visor lento no debe frenar a los demás ni al relay — mejor perder frames (el próximo keyframe
// lo recompone) que acumular memoria sin límite.
const viewerBuffer = 8

// ErrNoSource se devuelve al mandar un mensaje de control cuando no hay ninguna fuente conectada
// (ni real ni simulada).
var ErrNoSource = errors.New("no hay una fuente de pantalla conectada (ni real ni simulada)")

// Hub relay frames de UNA fuente activa hacia N visores WebSocket, y los mensajes de control de
// cualquier visor hacia esa misma fuente. No sabe nada de HTTP ni de WebSocket — eso vive en
// internal/wsutil y en el handler de internal/server; Hub es el estado compartido entre conexiones.
type Hub struct {
	logger *slog.Logger
	secret []byte // ver handshake.go: sin esto, StartConnector nunca acepta una fuente real

	mu          sync.Mutex
	source      io.ReadWriteCloser
	connected   bool
	deviceName  string // nombre real del dispositivo, solo si la fuente actual es local.go/ListenLocal
	controlConn net.Conn // canal de control real (2026-10-16,) — segunda conexión
	// que el servidor real de scrcpy abre cuando arranca con control=true (DesktopConnection.open(),
	// mismo nombre de socket, una connect() más); nil hasta que local.go la acepte, o si el servidor
	// corrió con control=false (SendControl cae al viejo comportamiento de escribir en [source]).
	viewers map[int]chan []byte
	nextID  int

	// peerCheck decide si una conexión al socket local (local.go) se acepta como fuente — por
	// defecto, [peerCredentialAllowed] (solo root/shell). Las pruebas lo reemplazan para poder
	// correr con el UID del propio proceso de test, que en un entorno real nunca sería root/shell.
	peerCheck func(net.Conn) (bool, string, error)

	recMu        sync.Mutex
	recFile      *os.File
	recPath      string
	recAudioFile *os.File // nil si la grabación actual no tiene canal de audio conectado (screen.config
	// con audio=false, o el servidor todavía no abrió esa conexión) — [feedAudio] la crea perezosamente
	// la primera vez que llega un frame real de audio, así nunca queda un archivo vacío de 0 bytes
	// cuando audio=false.
	recAudioPath string

	// Estado del ciclo de conexiones de local.go (2026-10-16,) — el servidor
	// real de scrcpy abre, EN ESTE ORDEN, una conexión por canal habilitado (siempre video; audio y
	// control solo si Kotlin los pidió): DesktopConnection.open() hace connect() video→audio→control,
	// nunca multiplexado en una sola conexión. localExpect cuenta cuál conexión viene a continuación
	// dentro del ciclo actual; se reinicia a 0 (video) cada vez que la fuente de video cambia o se
	// desconecta (setSource/feed ya hacen eso con controlConn, acá solo con el contador).
	localMu     sync.Mutex
	localExpect int
	expectDir   string // carpeta donde local.go busca ExpectFileName — set por [Hub.ListenLocal].
}

// NewHub crea un Hub sin fuente conectada todavía. [secret] autentica cualquier fuente que llegue
// por [StartConnector] (ver handshake.go) — nunca se usa para [UseStub], que es un generador propio
// en el mismo proceso, no una conexión de red que haga falta autenticar.
func NewHub(logger *slog.Logger, secret []byte) *Hub {
	if logger == nil {
		logger = slog.Default()
	}
	return &Hub{logger: logger, secret: secret, viewers: map[int]chan []byte{}, peerCheck: peerCredentialAllowed}
}

// Subscribe agrega un visor nuevo. El canal recibe cada paquete (cabecera+payload) que llegue de la
// fuente desde este momento — no hay "historial": un visor que se conecta tarde no ve los frames
// anteriores, tiene que esperar al próximo keyframe (igual que cualquier reproductor de video en vivo).
// cancel() debe llamarse siempre que el visor se desconecte, para no acumular canales muertos.
func (h *Hub) Subscribe() (<-chan []byte, func()) {
	ch := make(chan []byte, viewerBuffer)
	h.mu.Lock()
	id := h.nextID
	h.nextID++
	h.viewers[id] = ch
	h.mu.Unlock()
	cancel := func() {
		h.mu.Lock()
		delete(h.viewers, id)
		h.mu.Unlock()
		// No se cierra el canal: podría haber un broadcast en vuelo que ya tomó una copia del mapa
		// antes de este Lock y todavía intente escribir en él. Sin close(), esa escritura de más
		// simplemente se pierde (nadie lee) en vez de hacer panic por "send on closed channel".
	}
	return ch, cancel
}

// ViewerCount y Connected son el estado que expone screen.relay_status.
func (h *Hub) ViewerCount() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.viewers)
}

func (h *Hub) Connected() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.connected
}

func (h *Hub) broadcast(raw []byte) {
	h.mu.Lock()
	chans := make([]chan []byte, 0, len(h.viewers))
	for _, ch := range h.viewers {
		chans = append(chans, ch)
	}
	h.mu.Unlock()
	for _, ch := range chans {
		select {
		case ch <- raw:
		default:
			// Visor lento: se descarta este frame para no bloquear el relay ni a los demás visores.
		}
	}
}

// SendControl reenvía los bytes de un mensaje de control (p. ej. ScreenControlMessage.encodeTouch,
// codificado igual del lado Kotlin) al canal de control real si el servidor arrancó con
// control=true (ver [SetControlConn]/local.go) — si no hay canal de control dedicado, cae al
// comportamiento viejo de escribir en la fuente de video (usado por StartConnector/handshake.go y
// por tests, donde fuente y control son la misma conexión). Sin ninguna de las dos, devuelve
// [ErrNoSource] — el visor puede seguir probando la UI (el panel ya muestra "se mandaría esto")
// sin que el relay falle.
func (h *Hub) SendControl(payload []byte) error {
	h.mu.Lock()
	ctrl := h.controlConn
	src := h.source
	h.mu.Unlock()
	if ctrl != nil {
		_, err := ctrl.Write(payload)
		return err
	}
	if src == nil {
		return ErrNoSource
	}
	_, err := src.Write(payload)
	return err
}

// SetControlConn registra la segunda conexión (canal de control real) que local.go acepta cuando
// el servidor arrancó con control=true. Cierra cualquier canal de control previo — no debería
// haber dos vivos a la vez, pero por las dudas no se filtra un file descriptor.
func (h *Hub) SetControlConn(conn net.Conn) {
	h.mu.Lock()
	prev := h.controlConn
	h.controlConn = conn
	h.mu.Unlock()
	if prev != nil {
		_ = prev.Close()
	}
}

// setSource reemplaza la fuente activa (cerrando la anterior, si había) y arranca la goroutine que
// la lee. Se usa tanto para una conexión real (dialed) como para el generador simulado.
func (h *Hub) setSource(src io.ReadWriteCloser) {
	h.mu.Lock()
	prev := h.source
	h.source = src
	h.connected = src != nil
	// Una fuente de video nueva es siempre el arranque de un ciclo nuevo del servidor real — cualquier
	// canal de control de un ciclo anterior ya quedó huérfano (local.go todavía no aceptó el de este
	// ciclo, que llega recién después, como segunda conexión).
	prevCtrl := h.controlConn
	h.controlConn = nil
	h.mu.Unlock()
	if prev != nil {
		_ = prev.Close()
	}
	if prevCtrl != nil {
		_ = prevCtrl.Close()
	}
	if src != nil {
		go h.feed(src)
	}
}

// feed lee paquetes completos de [src] y los reenvía a todos los visores hasta que la fuente falla
// o se cierra; al terminar, limpia el estado "conectado" solo si [src] sigue siendo la fuente activa
// (evita una condición de carrera si mientras tanto ya se conectó una fuente nueva).
func (h *Hub) feed(src io.ReadWriteCloser) {
	for {
		_, raw, err := ReadFrame(src)
		if err != nil {
			h.logger.Debug("fuente de pantalla desconectada", "error", err)
			break
		}
		h.broadcast(raw)
		h.writeRecording(raw)
	}
	h.mu.Lock()
	var staleCtrl net.Conn
	if h.source == src {
		// Se limpia la fuente (no solo "connected"): StartConnector detecta la desconexión mirando
		// si h.source sigue siendo la conexión que abrió, así que dejarla puesta pero "desconectada"
		// haría que waitDisconnect nunca note que ya no sirve y se quede esperando para siempre.
		h.source = nil
		h.connected = false
		// El canal de control, si había uno, murió con el mismo proceso real de scrcpy — no sirve
		// para el próximo ciclo (local.go aceptará uno nuevo cuando el servidor vuelva a arrancar).
		staleCtrl = h.controlConn
		h.controlConn = nil
	}
	h.mu.Unlock()
	if staleCtrl != nil {
		_ = staleCtrl.Close()
	}
}

// Close corta la fuente activa (si hay) y deja el Hub sin fuente; los visores suscritos siguen
// suscritos (seguirán sin recibir nada hasta la próxima fuente).
func (h *Hub) Close() {
	h.setSource(nil)
}

// UseStub conecta el generador simulado ([NewStubSource]) como fuente — para probar el relay sin
// ningún dispositivo real conectado (modo --screen-stub del gateway).
func (h *Hub) UseStub(interval time.Duration) {
	h.setSource(NewStubSource(interval))
}

// ErrAlreadyRecording/ErrNotRecording son los únicos errores propios de [StartRecording]/[StopRecording] —
// cualquier otro error viene de [os.OpenFile]/[os.File.Close] tal cual.
var (
	ErrAlreadyRecording = errors.New("ya hay una grabación de pantalla en curso")
	ErrNotRecording     = errors.New("no hay ninguna grabación de pantalla en curso")
)

// StartRecording abre [path] (se crea, se trunca si ya existía) y empieza a escribir ahí, a partir de este
// momento, el payload crudo (sin la cabecera de 12 bytes) de cada paquete que llegue de la fuente — ver
// [writeRecording]. Grabar NO es un flag del servidor real de scrcpy (`Options.parse` no tiene ningún
// `record=...`; en el scrcpy original el mux a archivo lo hace el CLIENTE de escritorio, ver
// `referencia/pc-movil/scrcpy/app/src/recorder.c`) — es trabajo propio del gateway, no del jar embebido.
//
// El resultado es un stream elemental H.264 "Annex-B" crudo (NAL units con sus start codes, tal como los
// entrega `MediaCodec`/`SurfaceEncoder` del lado Android) — NO un .mp4. Convertir con
// `ffmpeg -i grabacion.h264 -c copy salida.mp4` (sin recodificar, solo remuxear) — ver el README que
// [StartRecording] no escribe (queda para cuando exista el flujo real desde la UI, ver
// docs/homelab/ESPEJO_PANTALLA_FUNCIONAL_2026-10-13.md).
//
// Si la sesión actual tiene audio (screen.config con audio=true), [feedAudio] (local.go) escribe
// EN PARALELO un segundo archivo crudo con el mismo nombre base y sufijo ".audio.raw" — igual de
// elemental que el video (frames Opus/AAC crudos de `AudioEncoder`, sin contenedor), convertir con
// `ffmpeg -f data -i grabacion.audio.raw ...` requiere saber el códec real exacto (ver
// screen.codecs) porque el archivo no lo declara. El archivo de audio se abre PEREZOSAMENTE (recién
// en el primer frame real, dentro de [feedAudio]) para no dejar un ".audio.raw" vacío de 0 bytes
// cuando la sesión no tiene audio — [recAudioPath] solo guarda dónde iría si llega a hacer falta.
func (h *Hub) StartRecording(path string) error {
	h.recMu.Lock()
	defer h.recMu.Unlock()
	if h.recFile != nil {
		return ErrAlreadyRecording
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	h.recFile = f
	h.recPath = path
	h.recAudioPath = strings.TrimSuffix(path, filepath.Ext(path)) + ".audio.raw"
	h.recAudioFile = nil
	return nil
}

// StopRecording cierra el archivo y devuelve la ruta final (para que quien llamó a `screen.record_start`
// pueda mostrarla/ofrecerla, sin tener que reconstruirla por su cuenta).
func (h *Hub) StopRecording() (string, error) {
	h.recMu.Lock()
	defer h.recMu.Unlock()
	if h.recFile == nil {
		return "", ErrNotRecording
	}
	path := h.recPath
	err := h.recFile.Close()
	h.recFile = nil
	h.recPath = ""
	if h.recAudioFile != nil {
		_ = h.recAudioFile.Close() // best-effort — un error cerrando el audio no debe tapar el de video, que es el real resultado de esta llamada.
		h.recAudioFile = nil
	}
	h.recAudioPath = ""
	return path, err
}

// IsRecording es el estado que expone `screen.record_status` (ver main.go) y el panel, para saber si ya
// hay una grabación en curso antes de ofrecer "Grabar" de nuevo.
func (h *Hub) IsRecording() (bool, string) {
	h.recMu.Lock()
	defer h.recMu.Unlock()
	return h.recFile != nil, h.recPath
}

// writeRecording escribe el payload de [raw] (sin los 12 bytes de cabecera) en el archivo de grabación
// activo, si hay uno — no hace nada si no hay grabación en curso. Errores de escritura (disco lleno, etc.)
// se registran pero no interrumpen el relay: un visor no debe perder video en vivo porque la grabación en
// disco falló.
func (h *Hub) writeRecording(raw []byte) {
	h.recMu.Lock()
	f := h.recFile
	h.recMu.Unlock()
	if f == nil || len(raw) <= HeaderSize {
		return
	}
	if _, err := f.Write(raw[HeaderSize:]); err != nil {
		h.logger.Warn("no se pudo escribir en el archivo de grabación de pantalla", "error", err)
	}
}

// feedAudio lee frames reales del canal de audio (local.go, mismo framing de 12 bytes que video —
// confirmado en Server.java: video y audio usan la misma clase Streamer) y los escribe en
// [recAudioPath] SI hay una grabación de video activa en este momento — si no, los descarta (un
// visor no pide audio todavía, no hay nada que mostrar en vivo, solo grabación tiene sentido para
// el audio hoy). Corre hasta que la conexión se cierre; no toca [viewers] ni [broadcast] — el
// protocolo del panel (/v1/screen/ws) hoy solo sabe decodificar video, mezclar audio ahí es trabajo
// aparte, no hecho en esta ronda.
func (h *Hub) feedAudio(conn io.ReadCloser) {
	defer conn.Close()
	for {
		_, raw, err := ReadFrame(conn)
		if err != nil {
			h.logger.Debug("canal de audio de pantalla desconectado", "error", err)
			return
		}
		if len(raw) <= HeaderSize {
			continue
		}
		h.recMu.Lock()
		if h.recFile == nil {
			h.recMu.Unlock()
			continue // no hay grabación activa — se descarta, no hay a dónde escribir.
		}
		if h.recAudioFile == nil {
			f, err := os.OpenFile(h.recAudioPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
			if err != nil {
				h.logger.Warn("no se pudo abrir el archivo de grabación de audio", "error", err)
				h.recMu.Unlock()
				continue
			}
			h.recAudioFile = f
		}
		f := h.recAudioFile
		h.recMu.Unlock()
		if _, err := f.Write(raw[HeaderSize:]); err != nil {
			h.logger.Warn("no se pudo escribir en el archivo de grabación de audio", "error", err)
		}
	}
}

// dialTimeout es el plazo máximo para UN intento de conexión — independiente de [retry] (el tiempo
// de espera ENTRE intentos). Confundir los dos (usar el mismo valor chico para ambos) hacía que un
// Dial sano pero un poco lento abortara antes de completar el handshake TCP, dejando una conexión
// recién aceptada del lado servidor que el cliente ya había descartado.
const dialTimeout = 2 * time.Second

// StartConnector mantiene un intento de conexión al puerto donde correría el servidor de pantalla
// real (ver ScreenMirrorManager en el lado Android — hoy ese servidor no existe todavía, así que
// esto reintenta en el vacío hasta que [ctx] se cancele). Devuelve inmediatamente; la conexión
// corre en una goroutine propia.
//
// Protocolo de arranque/parada: mientras no hay servidor escuchando en [addr], Dial falla y se
// reintenta cada [retry]; en cuanto conecta, pasa a ser la fuente del Hub (vía setSource) y la
// goroutine queda bloqueada en feed() hasta que esa conexión se corte, momento en el que vuelve a
// intentar reconectar — así un restart del lado Android se recupera solo, sin reiniciar el gateway.
func (h *Hub) StartConnector(ctx context.Context, addr string, retry time.Duration) {
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			default:
			}
			conn, err := net.DialTimeout("tcp", addr, dialTimeout)
			if err != nil {
				select {
				case <-ctx.Done():
					return
				case <-time.After(retry):
				}
				continue
			}
			if err := authenticateSource(conn, h.secret); err != nil {
				// No es la fuente real de pantalla (o no tiene el secreto correcto): se descarta
				// sin tratarla como fuente, igual que si nunca hubiera contestado nadie en ese
				// puerto — un proceso cualquiera ocupando 127.0.0.1:8974 no puede suplantarla.
				h.logger.Warn("conexión en el puerto de pantalla rechazada: no superó la autenticación", "addr", addr, "error", err)
				_ = conn.Close()
				select {
				case <-ctx.Done():
					return
				case <-time.After(retry):
				}
				continue
			}
			h.logger.Info("servidor de pantalla conectado y autenticado", "addr", addr)
			h.setSource(conn)
			// Espera a que la conexión actual se corte (o el contexto se cancele) antes de reintentar.
			h.waitDisconnect(ctx, conn)
		}
	}()
}

// waitDisconnect bloquea hasta que [conn] deje de ser la fuente activa o [ctx] se cancele.
func (h *Hub) waitDisconnect(ctx context.Context, conn net.Conn) {
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			h.setSource(nil)
			return
		case <-ticker.C:
			h.mu.Lock()
			still := h.source == conn
			h.mu.Unlock()
			if !still {
				return
			}
		}
	}
}
