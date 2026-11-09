// Command kairos-gateway es el servidor local de Kairos para Homelab: recibe peticiones (web, otros
// dispositivos o el Agente), las valida contra permisos y decide si las atiende la app, un comando
// de la lista blanca o una función nativa. Solo usa la librería estándar.
//
// Configuración (todo opcional):
//
//	KAIROS_GATEWAY_DIR                carpeta de estado (por defecto $HOME/.kairos_gateway)
//	KAIROS_GATEWAY_ADMIN_SECRET       secreto de la app para /v1/admin/* (vacío = administración apagada)
//	KAIROS_GATEWAY_ADMIN_SECRET_FILE  alternativa a la de arriba: ruta a un archivo (0600) que
//	                                  contiene el secreto — evita que quede en el bloque de
//	                                  entorno del proceso (legible por cualquiera con el mismo
//	                                  usuario vía /proc/<pid>/environ en Linux). Si las dos están
//	                                  presentes, gana la de archivo.
//	KAIROS_GATEWAY_APP_URL            endpoint de la app para acciones "inapp"
//	KAIROS_GATEWAY_APP_SECRET         secreto que la app exige en X-Kairos-App-Secret
//	KAIROS_GATEWAY_SCREEN_STUB        "1" activa /v1/screen/ws con un generador simulado (sin
//	                                  dispositivo real) — solo para pruebas, ver internal/screen.
//	KAIROS_GATEWAY_SCREEN_SOURCE_ADDR dirección loopback donde se conectaría el futuro servidor de
//	                                  pantalla real (ver docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md);
//	                                  por defecto 127.0.0.1:8974. Ignorada si SCREEN_STUB está activo.
package main

import (
	"context"
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/audit"
	"github.com/honkonx/kairos-dev/native/gateway/internal/auth"
	"github.com/honkonx/kairos-dev/native/gateway/internal/events"
	"github.com/honkonx/kairos-dev/native/gateway/internal/notify"
	"github.com/honkonx/kairos-dev/native/gateway/internal/pending"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
	"github.com/honkonx/kairos-dev/native/gateway/internal/router"
	"github.com/honkonx/kairos-dev/native/gateway/internal/screen"
	"github.com/honkonx/kairos-dev/native/gateway/internal/server"
)

// version se fija al compilar con -ldflags "-X main.version=...".
var version = "0.1.0-dev"

//go:embed default_manifest.json
var defaultManifest []byte

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "kairos-gateway:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	fs := flag.NewFlagSet("kairos-gateway", flag.ContinueOnError)
	listen := fs.String("listen", "127.0.0.1:8973", "dirección donde escucha; fuera de loopback no hay TLS")
	dir := fs.String("dir", defaultDir(), "carpeta de estado (tokens, política, auditoría, manifiesto)")
	showVersion := fs.Bool("version", false, "muestra la versión y termina")
	// Por defecto solo en Windows (2026-10-16, ver meta 12: "mas alla de un
	// binario CLI") — en Android no tiene sentido (Kotlin ya controla su propio ciclo de vida y
	// nunca invoca este binario con un navegador de escritorio esperando), y en Linux/macOS de
	// escritorio queda disponible pero apagado por defecto (menos sorpresivo). Implementado con
	// os/exec puro (ver openBrowser más abajo) — nada de syscalls Win32 crudas ni dependencias
	// nuevas, mismo principio "solo librería estándar" que ya declara este archivo.
	openBrowserFlag := fs.Bool("open-browser", runtime.GOOS == "windows", "abre el panel en el navegador por defecto al arrancar (Windows)")
	// Apagado por defecto a propósito (ver tray_windows.go) — es la primera ronda de bindings
	// Win32 crudos por syscall, opt-in hasta confirmarlo en más dispositivos de usuarios reales.
	trayFlag := fs.Bool("tray", false, "agrega un ícono de bandeja del sistema (Windows, experimental)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *showVersion {
		fmt.Println("kairos-gateway", version)
		return nil
	}

	logger := slog.New(slog.NewTextHandler(os.Stderr, nil))
	if err := os.MkdirAll(*dir, 0o700); err != nil {
		return fmt.Errorf("creando %s: %w", *dir, err)
	}
	host, _, err := net.SplitHostPort(*listen)
	if err != nil {
		return fmt.Errorf("--listen inválido: %w", err)
	}
	// TLS automático fuera de loopback (hallazgo real de seguridad: "el token viaja en texto
	// plano por WiFi local") -- un certificado autofirmado no da identidad verificable (eso
	// sigue siendo el token), pero cifra el canal contra un sniffer pasivo en la misma red, el
	// ataque real y barato. Loopback (127.0.0.1, el modo por defecto) sigue en HTTP plano sin
	// cambios -- no hay red de por medio, TLS ahí no protegería nada real.
	useTLS := false
	var tlsCertPath, tlsKeyPath string
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		tlsCertPath, tlsKeyPath, err = server.EnsureSelfSignedCert(*dir)
		if err != nil {
			logger.Warn("no se pudo preparar TLS, sigue en HTTP plano -- el token viaja sin cifrar", "error", err)
		} else {
			useTLS = true
		}
		if useTLS {
			logger.Warn("escuchando fuera de loopback con TLS autofirmado -- el certificado no tiene autoridad real detrás, los clientes deben aceptarlo explícitamente (no reemplaza al token)", "listen", *listen)
		} else {
			logger.Warn("escuchando fuera de loopback SIN TLS: el tráfico y los tokens viajan sin cifrar; usa un túnel o una red de confianza", "listen", *listen)
		}
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	srv, err := build(*dir, logger, *listen, stop)
	if err != nil {
		return err
	}
	httpSrv := &http.Server{
		Addr:              *listen,
		Handler:           srv,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       2 * time.Minute,
		// Sin WriteTimeout a propósito: el flujo SSE dura minutos. Cada handler fija sus plazos.
		MaxHeaderBytes: 16 << 10,
		ErrorLog:       slog.NewLogLogger(logger.Handler(), slog.LevelWarn),
	}

	errCh := make(chan error, 1)
	if useTLS {
		go func() { errCh <- httpSrv.ListenAndServeTLS(tlsCertPath, tlsKeyPath) }()
	} else {
		go func() { errCh <- httpSrv.ListenAndServe() }()
	}
	logger.Info("gateway en marcha", "version", version, "listen", *listen, "dir", *dir, "tls", useTLS)

	if *openBrowserFlag {
		go openBrowserWhenReady(*listen, logger)
	}
	if *trayFlag {
		startSystemTray(logger)
		defer stopSystemTray()
	}

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}
	logger.Info("apagando")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpSrv.Shutdown(shutdownCtx); err != nil && !errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	return nil
}

// filteredEnviron es el entorno del propio proceso del gateway MENOS sus variables internas
// (KAIROS_GATEWAY_*: ADMIN_SECRET, APP_SECRET, etc.) — un comando "shell" ejecutado como acción
// no debe poder leer ningún secreto propio del gateway desde su entorno (hallazgo de revisión
// automática, 2026-10-26,: "secret exposure via inherited environment").
// Sin esto, ShellExecutor con Env=nil hereda TODO el entorno del proceso, secretos incluidos.
func filteredEnviron() []string {
	env := os.Environ()
	out := make([]string, 0, len(env))
	for _, kv := range env {
		if strings.HasPrefix(kv, "KAIROS_GATEWAY_") {
			continue
		}
		out = append(out, kv)
	}
	return out
}

// resolveAdminSecret lee KAIROS_GATEWAY_ADMIN_SECRET_FILE si está presente (hallazgo de revisión
// automática, 2026-10-26, "secret exposure (incomplete mitigation)": filtrar el entorno que se le
// pasa a un comando shell no alcanza — el secreto seguía viviendo en el bloque de entorno del
// PROPIO proceso del gateway, legible por cualquier proceso con el mismo usuario vía
// /proc/<pid>/environ en Linux, sin necesitar privilegio extra). Con el archivo, el secreto nunca
// pasa por una variable de entorno — solo por un descriptor de archivo abierto brevemente, con
// permisos 0600 (dueño únicamente). Si no está esa variable, cae a KAIROS_GATEWAY_ADMIN_SECRET
// (compatibilidad con la app Android, que ya lo pasa por entorno — ese camino es de menor riesgo
// real: el gateway ahí es un hijo directo de la app, dentro del sandbox de la propia app Android,
// no un proceso de escritorio compartiendo usuario con cualquier otro programa que corra).
func resolveAdminSecret() (string, error) {
	path := os.Getenv("KAIROS_GATEWAY_ADMIN_SECRET_FILE")
	if path == "" {
		return os.Getenv("KAIROS_GATEWAY_ADMIN_SECRET"), nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("no se pudo leer KAIROS_GATEWAY_ADMIN_SECRET_FILE (%s): %w", path, err)
	}
	return strings.TrimSpace(string(raw)), nil
}

// build arma todas las dependencias a partir de la carpeta de estado.
func build(dir string, logger *slog.Logger, listen string, onShutdown func()) (*server.Server, error) {
	tokens, err := auth.Open(filepath.Join(dir, "tokens.json"))
	if err != nil {
		return nil, err
	}
	policy, err := permission.OpenStore(filepath.Join(dir, "policy.json"))
	if err != nil {
		return nil, err
	}
	registry, err := loadRegistry(filepath.Join(dir, "manifest.json"))
	if err != nil {
		return nil, err
	}
	adminSecret, err := resolveAdminSecret()
	if err != nil {
		return nil, err
	}
	if adminSecret == "" {
		logger.Warn("KAIROS_GATEWAY_ADMIN_SECRET vacío: la administración (tokens, política, confirmaciones) está apagada")
	}
	screenHub, err := buildScreenHub(logger, dir)
	if err != nil {
		return nil, err
	}
	return server.New(server.Config{
		Version:     version,
		AdminSecret: adminSecret,
		Tokens:      tokens,
		Policy:      policy,
		Registry:    registry,
		Dispatcher: router.Dispatcher{
			Shell: router.ShellExecutor{Env: filteredEnviron()},
			InApp: router.InAppExecutor{
				URL:    os.Getenv("KAIROS_GATEWAY_APP_URL"),
				Secret: os.Getenv("KAIROS_GATEWAY_APP_SECRET"),
				Client: &http.Client{Timeout: 5 * time.Minute},
			},
			Native: router.NativeExecutor{Funcs: screenAwareNativeFuncs(version, listen, screenHub)},
		},
		Audit:      audit.Open(filepath.Join(dir, "audit.log"), 1<<20),
		Hub:        events.NewHub(),
		Pending:    pending.NewStore(2 * time.Minute),
		Screen:     screenHub,
		Logger:     logger,
		OnShutdown: onShutdown,
	}), nil
}

// defaultScreenSourceAddr es el puerto loopback donde escucharía el futuro servidor de pantalla
// real del lado Android — distinto del puerto donde el propio gateway escucha (8973) para que no
// choquen. Debe coincidir con ScreenMirrorManager.DEFAULT_RELAY_PORT (app/src/main/java/com/termux/
// app/util/ScreenMirrorManager.kt) — si uno cambia, el otro también.
const defaultScreenSourceAddr = "127.0.0.1:8974"

// screenConnectRetry es cada cuánto se reintenta conectar al servidor de pantalla real mientras no
// existe o no está corriendo — ver screen.Hub.StartConnector.
const screenConnectRetry = 3 * time.Second

// buildScreenHub arma el Hub del relay de pantalla (ver docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md).
// En modo real (por defecto) queda reintentando la conexión al servidor Android indefinidamente —
// hoy ese servidor no existe, así que simplemente no conecta nunca hasta que exista; eso es
// esperado, no un error. KAIROS_GATEWAY_SCREEN_STUB cambia a un generador simulado para poder
// probar el relay completo (websocket incluido) sin ningún dispositivo.
//
// Nota de ciclo de vida: el connector corre con context.Background() (nunca se cancela desde acá) —
// el gateway no tiene hoy un punto central de "apagar todo" más allá de salir del proceso; queda
// documentado como limitación conocida en vez de fingir un apagado prolijo que no existe todavía.
//
// screenSecretFile guarda el secreto que autentica la fuente real de pantalla (handshake.go) — mismo
// patrón que admin.secret/app.secret del lado Kotlin (GatewayFiles.secret() en GatewayManager.kt):
// se genera una sola vez, chmod 600, el futuro servidor Android lo lee de ahí para contestar el reto.
func buildScreenHub(logger *slog.Logger, dir string) (*screen.Hub, error) {
	if os.Getenv("KAIROS_GATEWAY_SCREEN_STUB") == "1" {
		logger.Warn("KAIROS_GATEWAY_SCREEN_STUB=1: /v1/screen/ws sirve video simulado, no la pantalla real")
		hub := screen.NewHub(logger, nil) // el stub es un generador propio, no hace falta autenticarlo
		hub.UseStub(33 * time.Millisecond)
		return hub, nil
	}
	secret, err := loadOrCreateScreenSecret(filepath.Join(dir, "screen.secret"))
	if err != nil {
		return nil, fmt.Errorf("secreto de pantalla: %w", err)
	}
	hub := screen.NewHub(logger, secret)

	// Confirmado contra un dispositivo real: el servidor de pantalla real (scrcpy-server
	// adaptado) corre en el MISMO teléfono que el gateway, así que no hace falta red ni ADB — se
	// habla por un socket abstracto Unix local. scid se deriva del secreto (nunca se manda el secreto
	// completo) y se escribe en screen.scid para que ScreenMirrorManager.kt (Kotlin) lo lea y lance el
	// proceso con el mismo valor. KAIROS_GATEWAY_SCREEN_SOURCE_ADDR (TCP) queda para una fuente que
	// SÍ hable nuestro protocolo propio (remota, o un futuro reemplazo del jar de scrcpy) — las dos
	// vías son mutuamente excluyentes por Hub, nunca se activan juntas.
	//
	// Bug real encontrado y corregido (2026-10-16, ver meta 12 — primer
	// cross-compile real del gateway para Windows): `net.Listen("unix", "@scrcpy_...")` NO es un
	// error en Windows — Go lo acepta como un nombre de archivo literal que empieza con "@", no
	// como el namespace abstracto de Linux que el código asume. El log decía "escuchando" sin
	// avisar que esa escucha nunca podría recibir nada real (no hay ningún proceso en un gateway
	// de escritorio que vaya a conectarse a ese nombre). `runtime.GOOS == "linux"` es la única
	// plataforma real donde el servidor de pantalla local tiene sentido (Android ES Linux por
	// debajo) — cualquier otra plataforma (Windows, un futuro macOS) cae al conector TCP de abajo,
	// que si no hay nada en KAIROS_GATEWAY_SCREEN_SOURCE_ADDR tampoco hace nada dañino: reintenta
	// conectar en loop sin loguear como si estuviera "listo".
	if runtime.GOOS == "linux" && os.Getenv("KAIROS_GATEWAY_SCREEN_SOURCE_ADDR") == "" && os.Getenv("KAIROS_GATEWAY_SCREEN_TCP") != "1" {
		scid := screen.DeriveScid(secret)
		if err := os.WriteFile(filepath.Join(dir, "screen.scid"), []byte(fmt.Sprintf("%08x", scid)), 0o600); err != nil {
			return nil, fmt.Errorf("scid de pantalla: %w", err)
		}
		hub.ListenLocal(scid, dir)
		return hub, nil
	}

	addr := os.Getenv("KAIROS_GATEWAY_SCREEN_SOURCE_ADDR")
	if addr == "" {
		addr = defaultScreenSourceAddr
	}
	hub.StartConnector(context.Background(), addr, screenConnectRetry)
	return hub, nil
}

func loadOrCreateScreenSecret(path string) ([]byte, error) {
	if b, err := os.ReadFile(path); err == nil && len(b) > 0 {
		return b, nil
	}
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return nil, err
	}
	hexSecret := []byte(hex.EncodeToString(secret))
	if err := os.WriteFile(path, hexSecret, 0o600); err != nil {
		return nil, err
	}
	return hexSecret, nil
}

// screenAwareNativeFuncs agrega "screen.relay_status" al catálogo de funciones nativas: a
// diferencia de screen.status (acción "inapp", le pregunta a la app si HAY CÓMO lanzar el
// servidor), esta cuenta si el Hub del gateway tiene una fuente conectada de verdad y cuántos
// visores están mirando — información del propio gateway, no de la app.
// lanIPs filtra las direcciones reales de interfaz a solo las útiles para "conectate desde otra
// máquina" -- extraída de screenAwareNativeFuncs ( lote 28) para poder probarla con
// net.Addr fabricados a mano, sin depender de las interfaces reales de la máquina que corre el
// test (que varían de PC a PC, como ya se vio en vivo: 169.254.x.x y 172.27.64.1 de WSL en esta).
func lanIPs(addrs []net.Addr) []string { return router.LocalLANIPs(addrs) }

// safeInterfaceAddrs envuelve net.InterfaceAddrs() con un fallback real (hallazgo de
// referencia/librerias/fula-build-aar,: Android puede bloquear con EACCES la
// syscall netlink que usa net.InterfaceAddrs() en ciertos dispositivos/versiones -- el error se
// descartaba en silencio y lan_ips salía vacío sin ningún aviso). Si la vía normal no devuelve
// ninguna dirección, se intenta el truco "connect UDP": abrir un socket UDP hacia una IP pública
// conocida (Go no manda ningún paquete real para esto, solo resuelve la ruta local de salida) y
// leer LocalAddr() -- no requiere ningún permiso especial, solo el de red que Kairos ya tiene.
// No reemplaza la vía normal (que sigue siendo más precisa, lista TODAS las interfaces): es
// solo el respaldo cuando esa vía da vacío.
func safeInterfaceAddrs() []net.Addr {
	if addrs, err := net.InterfaceAddrs(); err == nil && len(addrs) > 0 {
		return addrs
	}
	conn, err := net.Dial("udp4", "8.8.8.8:80")
	if err != nil {
		return nil
	}
	defer conn.Close()
	udpAddr, ok := conn.LocalAddr().(*net.UDPAddr)
	if !ok || udpAddr.IP == nil {
		return nil
	}
	return []net.Addr{&net.IPNet{IP: udpAddr.IP, Mask: net.CIDRMask(24, 32)}}
}

func screenAwareNativeFuncs(version string, listen string, hub *screen.Hub) map[string]router.NativeFunc {
	funcs := router.BuiltinFuncs(version)
	// gateway.network_info ( meta 7 -- "ver+controlar el movil completo desde el gateway
	// por red, ip, tunel o usb"): las 3 vias YA funcionan hoy sin codigo nuevo -- LAN directa (si
	// GatewayManager.files.access = LAN), tunnel.* (cloudflared/ngrok, ya wireado), y USB con
	// `adb forward` (comando generico, no hace falta que Kairos lo ejecute el mismo). Lo que
	// faltaba era que ninguna pantalla mostrara la IP real ni el comando exacto -- un usuario sin
	// conocimiento previo de redes no tenia forma de DESCUBRIR que esto ya era posible.
	funcs["gateway.network_info"] = func(context.Context, map[string]string) (string, error) {
		_, port, err := net.SplitHostPort(listen)
		if err != nil {
			return "", err
		}
		addrs := safeInterfaceAddrs() // ver fallback "connect UDP" arriba -- nunca falla la acción entera
		raw, err := json.Marshal(map[string]any{
			"ok": true, "port": port, "lan_ips": lanIPs(addrs),
			"usb_hint": fmt.Sprintf("adb forward tcp:%s tcp:%s", port, port),
		})
		return string(raw), err
	}
	// network.scan_gateways (investigación de referencia/ sobre discovery LAN):
	// pensado sobre todo para el gateway de ESCRITORIO (standalone, sin app Android del otro lado
	// que delegue a RemoteManager.scanSubnet/GatewayLanScanOps como ya hace Kotlin) — pero corre
	// igual en Android, no hace daño tenerlo disponible ahí también. Nunca crea confianza por sí
	// solo (ver ScanLANGateways) — conectarse de verdad sigue pidiendo el token real.
	funcs["network.scan_gateways"] = func(ctx context.Context, _ map[string]string) (string, error) {
		_, port, err := net.SplitHostPort(listen)
		if err != nil {
			return "", err
		}
		addrs := safeInterfaceAddrs()
		scanCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		found := router.ScanLANGateways(scanCtx, lanIPs(addrs), port, 400*time.Millisecond)
		raw, err := json.Marshal(map[string]any{"ok": true, "found": found, "port": port})
		return string(raw), err
	}
	// notify.send (docs/arquitectura/INVESTIGACION_MODULOS_NUEVOS_Y_PENDIENTES_REFERENCIA_2026-11-05.md:
	// "ntfy -- notificaciones push sin necesitar un bot de Telegram"). "native" porque es un POST
	// HTTP puro (ver internal/notify/ntfy.go) -- no necesita nada del lado Android. Sin config
	// persistida a propósito: el caller (una automatización, el Agente) decide server/topic cada
	// vez, ver KDoc del paquete notify.
	funcs["notify.send"] = func(ctx context.Context, params map[string]string) (string, error) {
		priority := notify.PriorityDefault
		if p, err := strconv.Atoi(params["priority"]); err == nil {
			priority = p
		}
		if err := notify.Send(params["server"], params["topic"], params["title"], params["message"], params["token"], priority); err != nil {
			return "", err
		}
		raw, err := json.Marshal(map[string]any{"ok": true})
		return string(raw), err
	}
	funcs["screen.relay_status"] = func(context.Context, map[string]string) (string, error) {
		raw, err := json.Marshal(map[string]any{
			"connected": hub.Connected(),
			"viewers":   hub.ViewerCount(),
		})
		return string(raw), err
	}
	// Grabar el espejo de pantalla es trabajo del GATEWAY, no del servidor real de scrcpy (el jar
	// no tiene ningún flag "record=..." — ver la cita completa en screen.Hub.StartRecording): el relay ya ve
	// pasar cada frame camino a los visores WebSocket, así que basta con escribir el payload crudo a un
	// archivo mientras está activo. screen.record_start/stop son "native" (no "inapp") porque el estado de la
	// grabación vive en este mismo proceso Go (el Hub), no en la app Android.
	funcs["screen.record_start"] = func(context.Context, map[string]string) (string, error) {
		path := filepath.Join(recordingsDir(), fmt.Sprintf("screen_%s.h264", time.Now().Format("20060102_150405")))
		if err := hub.StartRecording(path); err != nil {
			return "", err
		}
		raw, err := json.Marshal(map[string]any{"ok": true, "path": path})
		return string(raw), err
	}
	funcs["screen.record_stop"] = func(context.Context, map[string]string) (string, error) {
		path, err := hub.StopRecording()
		if err != nil {
			return "", err
		}
		raw, err := json.Marshal(map[string]any{"ok": true, "path": path})
		return string(raw), err
	}
	funcs["screen.record_status"] = func(context.Context, map[string]string) (string, error) {
		recording, path := hub.IsRecording()
		raw, err := json.Marshal(map[string]any{"ok": true, "recording": recording, "path": path})
		return string(raw), err
	}
	return funcs
}

// recordingsDir es `$HOME/kairos-grabaciones` (HOME real de Termux, no `.kairos_gateway` — a diferencia de
// los archivos de estado del gateway, una grabación es algo que la persona quiere poder encontrar con un
// explorador de archivos normal, no un detalle interno). Se crea si no existe; si no se puede crear, el
// error real aparece después, al intentar abrir el archivo de grabación en sí (os.OpenFile).
func recordingsDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	dir := filepath.Join(home, "kairos-grabaciones")
	_ = os.MkdirAll(dir, 0o700)
	return dir
}

// loadRegistry usa el manifiesto del usuario si existe y, si no, el incorporado.
func loadRegistry(path string) (*router.Registry, error) {
	if _, err := os.Stat(path); err == nil {
		return router.LoadManifest(path)
	}
	var m router.Manifest
	if err := json.Unmarshal(defaultManifest, &m); err != nil {
		return nil, fmt.Errorf("manifiesto incorporado inválido: %w", err)
	}
	return router.NewRegistry(m)
}

// defaultDir — carpeta de estado (tokens, política, auditoría). Pedido real del usuario
// (2026-10-30,): "el app de escritorio [...] debe crear la carpeta en
// appdata, guardar ahí lo que se suba" — en Windows va a %LOCALAPPDATA%\KairosGateway (no
// %APPDATA%\ Roaming: los tokens/secretos no deben "viajar" entre PCs de un mismo dominio
// corporativo, %LOCALAPPDATA% es la convención de Windows para eso), MISMA carpeta donde el
// instalador ya copia el binario (ver tools/installer/kairos-gateway-windows.nsi,
// InstallDir "$LOCALAPPDATA\KairosGateway") — un solo lugar real, tanto para el portable (que
// antes no tenía ningún default de Windows explícito, caía a $HOME) como para el instalado.
func defaultDir() string {
	if d := os.Getenv("KAIROS_GATEWAY_DIR"); d != "" {
		return d
	}
	if runtime.GOOS == "windows" {
		if local := os.Getenv("LOCALAPPDATA"); local != "" {
			return filepath.Join(local, "KairosGateway")
		}
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ".kairos_gateway"
	}
	return filepath.Join(home, ".kairos_gateway")
}

// openBrowserWhenReady espera a que el propio servidor responda (confirmación real, no un sleep
// fijo arbitrario — el mismo criterio que ya usa local_test.go para esperar a ListenLocal) antes
// de abrir el navegador, para no mandar al usuario a una pestaña con "no se puede conectar".
func openBrowserWhenReady(listen string, logger *slog.Logger) {
	url := "http://" + listen + "/panel"
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", listen, 200*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err := openBrowser(url); err != nil {
		logger.Warn("no se pudo abrir el navegador automáticamente", "error", err, "url", url)
	}
}

// openBrowser usa el comando real de cada sistema operativo para abrir la URL con el navegador
// por defecto — os/exec puro, sin ninguna dependencia nueva ni syscall de bajo nivel.
func openBrowser(url string) error {
	switch runtime.GOOS {
	case "windows":
		return exec.Command("cmd", "/c", "start", "", url).Start()
	case "darwin":
		return exec.Command("open", url).Start()
	default:
		return exec.Command("xdg-open", url).Start()
	}
}
