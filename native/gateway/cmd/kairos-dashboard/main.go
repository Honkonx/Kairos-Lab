// Command kairos-dashboard es la ventana nativa de escritorio del gateway de Kairos — pedido
// explícito del usuario: "el gateway de windows no es para abrir el
// navegador debe ser una de sus opciones pero el gateway debe [tener] una interfaz dashboard en
// windows sin navegador" + decisión posterior de usar "la que vos veas universal, fácil de hacer
// y factible a corto y largo plazo, no importa el peso".
//
// Decisión real: webview/webview_go (MIT, https://github.com/webview/webview_go — WebKitGTK en
// Linux, WebView2 en Windows) en vez de escribir dos interfaces nativas distintas a mano
// (Win32/GTK crudo) — mismo código Go para los dos sistemas, reusa el panel HTML que YA existe
// (internal/server/panel/index.html) sin duplicar ni una línea de interfaz. Es la ÚNICA
// dependencia externa de todo native/gateway — kairos-gateway (el binario que de verdad atiende
// pedidos) sigue siendo 100% stdlib, sin CGO, cross-compilable igual que siempre; este binario
// aparte sí necesita CGO + un compilador C (MinGW en Windows, gcc+headers de GTK/WebKitGTK en
// Linux) solo para compilarlo, nunca para los binarios de producción del gateway en sí.
//
// Qué hace: si el gateway no está corriendo en el puerto dado, lo arranca como proceso hijo
// (mismo binario kairos-gateway.exe/kairos-gateway, se espera en el mismo directorio); abre una
// ventana nativa apuntando al panel (/panel/) — nunca un navegador. El panel sigue pidiendo el
// token la primera vez (igual que en cualquier pestaña nueva — `sessionStorage`, no persiste
// entre aperturas de la ventana); no se agregó ningún mecanismo nuevo para saltarse eso. Cerrar
// la ventana NO apaga el gateway (sigue corriendo de fondo, mismo criterio que ya documenta
// tray_windows.go).
//
// No verificado todavía contra un dispositivo con WebView2/WebKitGTK realmente instalado end a
// end (se compiló y se abrió una vez en esta PC de desarrollo,) — falta
// confirmar en una instalación limpia de usuario final.
//
// Verificación de identidad — DOS niveles reales, no uno solo (ronda 2026-10-26, hallazgos de
// revisión automática: "port squatting / credential phishing", "parser/validator differential",
// "TOCTOU / identity check not bound to the connection"):
//
//  1. CAMINO FUERTE (el normal: este binario arranca su propio gateway). [startGateway] genera un
//     secreto aleatorio de un solo uso y lo escribe en un archivo (0600, dueño únicamente) dentro
//     de la carpeta de estado del gateway, pasándole solo la RUTA por entorno
//     (KAIROS_GATEWAY_ADMIN_SECRET_FILE — ver cmd/kairos-gateway/main.go:resolveAdminSecret). El
//     secreto en sí nunca viaja por el bloque de entorno del proceso (hallazgo de revisión
//     automática, 2026-10-26: "secret exposure (incomplete mitigation)" — en Linux, cualquier
//     proceso con el mismo usuario puede leer el entorno de otro vía /proc/<pid>/environ sin
//     privilegio extra; un archivo con permisos 0600 no tiene ese problema). El archivo se borra
//     apenas el desafío confirma que el gateway ya lo leyó — nadie más lo necesita después de eso.
//     Después de que el gateway responde, [verifyByChallenge] manda un desafío aleatorio y
//     comprueba HMAC-SHA256(secreto, desafío) == lo que devolvió (mismo endpoint
//     /healthz?challenge=, mismo mecanismo que ya usa GatewayAdminClient.kt del lado Android,
//     gatewayIsGenuine(), reusado tal cual) — prueba criptográfica atada a ESE proceso exacto, no
//     a una foto del sistema operativo que podría quedar vieja (TOCTOU).
//  2. CAMINO DÉBIL (el gateway YA estaba corriendo, nosotros no lo arrancamos — no tenemos
//     ningún secreto para desafiarlo). Única opción disponible: confirmar con el sistema
//     operativo qué proceso tiene el puerto y que es kairos-gateway.exe en este directorio (ver
//     verify_windows.go) — más débil que el camino 1, documentado como tal, no se puede evitar
//     sin cambiar el protocolo del gateway para pedir un secreto de por vida ya establecido.
//
// Además: un goroutine de fondo vuelve a pedir /healthz cada pocos segundos mientras la ventana
// está abierta — si el gateway deja de responder, la ventana se cierra sola en vez de quedar
// mostrando una página muerta (o, peor, dejar la puerta abierta a que algo más ocupe el puerto
// mientras la ventana sigue viva).
package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/adb"
	"github.com/honkonx/kairos-dev/native/gateway/internal/router"
	webview "github.com/webview/webview_go"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8973", "dirección del gateway (host:puerto)")
	dir := flag.String("dir", "", "carpeta de estado del gateway (--dir del binario kairos-gateway; vacío = su default)")
	pair:= flag.String("pair", "", "en vez de abrir el gateway LOCAL, pedir emparejarse con un teléfono remoto (host:puerto, ej. 192.168.1.8:8973 — ver Flujo 3)")
	pairName := flag.String("name", "", "nombre que ve la persona en el diálogo del teléfono al emparejar (vacío = nombre de esta PC)")
	sshTarget:= flag.String("ssh", "", "en vez de LAN directa, abrir un túnel SSH hacia el gateway del teléfono (user@host[:puerto-ssh], ej. u0_a123@192.168.1.8:8022 — módulo Remote de Kairos, ver Flujo 2)")
	connect := flag.Bool("connect", false, "abrir un formulario real (IP, usuario SSH, nombre) en vez de tener que escribir --pair/--ssh a mano por línea de comandos")
	usbGrant := flag.Bool("usb-grant", false, "otorgarle privilegio elevado (shell) a un teléfono Kairos conectado por USB — Alcance A, docs/arquitectura/PLAN_ADB_USB_GATEWAY_2026-10-31.md")
	discover := flag.Bool("discover", false, "detectar varios teléfonos Kairos a la vez (LAN + USB) en una sola vista consolidada, ver .claude/rules/kairos-gateway-windows-dashboard-and-lan-discovery.md")
	flag.Parse()

	if *pair != "" {
		runPairMode(*pair, *pairName)
		return
	}
	if *sshTarget != "" {
		runSSHTunnelMode(*sshTarget)
		return
	}
	if *connect {
		runConnectMode()
		return
	}
	if *usbGrant {
		runUsbGrantMode()
		return
	}
	if *discover {
		runDiscoverMode()
		return
	}

	host, port, err := hostPort(*listen)
	if err != nil {
		showFatalError("Kairos Dashboard", err.Error())
		os.Exit(1)
	}

	var ourSecret string
	if healthy(*listen) {
		// Ya estaba corriendo — no lo arrancamos nosotros, no hay secreto que desafiar:
		// camino débil, nivel de SO.
		if err := verifyGatewayProcess(host, port); err != nil {
			showFatalError("Kairos Dashboard — no se abre por seguridad", err.Error())
			os.Exit(1)
		}
	} else {
		secret, secretFile, err := startGateway(*listen, *dir)
		if err != nil {
			showFatalError("Kairos Dashboard", "No se pudo arrancar el gateway: "+err.Error())
			os.Exit(1)
		}
		ourSecret = secret
		if !waitHealthy(*listen, 10*time.Second) {
			os.Remove(secretFile)
			showFatalError("Kairos Dashboard", "El gateway no respondió a tiempo — revisá kairos_logs/gateway.log")
			os.Exit(1)
		}
		// Camino fuerte: prueba criptográfica atada al proceso que acabamos de arrancar. Una vez
		// confirmado que YA leyó el secreto, el archivo deja de hacer falta — se borra (nadie
		// más lo necesita; dejarlo ahí solo alargaría la ventana de exposición sin motivo).
		verifyErr := verifyByChallenge(*listen, ourSecret)
		os.Remove(secretFile)
		if verifyErr != nil {
			showFatalError("Kairos Dashboard — no se abre por seguridad", verifyErr.Error())
			os.Exit(1)
		}
	}

	w := webview.New(false)
	defer w.Destroy()
	w.SetTitle("Kairos — Dashboard")
	w.SetSize(1180, 760, webview.HintNone)

	stopWatch := make(chan struct{})
	go watchIdentity(*listen, host, port, ourSecret, &w, stopWatch)
	defer close(stopWatch)

	// "Abrir en el navegador" — pedido real del usuario: la ventana nativa nunca abre uno sola,
	// pero debe ofrecer la opción a quien la prefiera. window.__kairosDesktop +
	// window.kairosOpenInBrowser los lee el propio panel (ver buildNav() en
	// internal/server/panel/index.html) para mostrar el botón solo dentro de esta ventana, nunca
	// cuando el panel se abre desde un navegador normal (ahí no tendría sentido).
	panelURL := schemeFor(*listen) + "://" + *listen + "/panel"
	_ = w.Bind("kairosOpenInBrowser", func() error { return openInSystemBrowser(panelURL) })
	// "No reconoce el móvil" (2026-10-31): la persona abrió esta ventana
	// (gateway normal, SIN --discover/--usb-grant) esperando que buscara el teléfono sola, por
	// LAN o por USB — pero esta ventana solo administra el gateway de la PC, nunca escaneó
	// nada. En vez de pedirle que recuerde un flag/acceso directo distinto, se agrega un botón
	// ACÁ MISMO (mismo patrón que "Abrir en el navegador") que abre --discover como un proceso
	// nuevo (una ventana nueva de verdad, no un segundo webview dentro de este mismo proceso —
	// webview_go no soporta eso de forma segura en todas las plataformas).
	_ = w.Bind("kairosOpenDiscover", func() error {
		exe, err := os.Executable()
		if err != nil {
			return err
		}
		cmd := exec.Command(exe, "--discover")
		return cmd.Start()
	})
	w.Init("window.__kairosDesktop=true")

	// Reporte real del usuario (2026-10-26/30): abrir el dashboard tiraba directo a la pantalla
	// de login del panel pidiendo un token "en blanco" — sin ningún lugar de donde sacarlo, para
	// quien abre el gateway EN SU PROPIA PC. Esto solo aplica al camino FUERTE ([ourSecret] no
	// vacío: nosotros arrancamos este gateway, ya probamos con HMAC que es el nuestro) — es
	// exactamente el mismo nivel de confianza que estar sentado en la consola de administración
	// de la propia máquina, así que acá sí se puede emitir un token de nivel "total" sin pedirle
	// nada a nadie (a diferencia del pairing remoto, que sigue pidiendo aprobación humana y
	// arranca en nivel "pregunta" — ver pair.go). Si falla por cualquier motivo, no bloquea nada:
	// el panel sigue mostrando su pantalla de login normal como fallback.
	// Segundo reporte real del usuario, mismo síntoma (2026-10-31): "al
	// entrar en el app gateway de escritorio me sigue pidiendo el token" — el fix de arriba
	// (camino fuerte) solo corre la PRIMERA vez que se arranca el gateway en la PC. Reabrir el
	// dashboard mientras el gateway YA estaba corriendo de antes (lo normal — queda vivo en
	// segundo plano a propósito) cae siempre al camino DÉBIL (ourSecret vacío), que nunca
	// emitía ni reusaba ningún token — pantalla de login en blanco en CADA apertura salvo la
	// primera. Mismo mecanismo que [ensurePaired] para teléfonos remotos: se guarda el token de
	// nivel total la primera vez (camino fuerte) y se reusa mientras siga vigente, sin volver a
	// pedir nada ni mostrar login — camino débil sin token guardado sigue mostrando el login
	// normal como fallback (nunca se inventa un token sin haber probado ser quien arrancó el
	// proceso).
	if ourSecret != "" {
		if token, err := mintLocalFullToken(*listen, ourSecret); err == nil {
			saveRemoteToken(*listen, pairedPhone{Token: token})
			w.Init("if(location.origin===" + jsStringLiteral(schemeFor(*listen)+"://"+*listen) + "){sessionStorage.setItem('kx_token'," + jsStringLiteral(token) + ")}")
		}
	} else if saved, ok := loadRemoteTokens()[*listen]; ok && remoteTokenStillValid(*listen, saved) {
		w.Init("if(location.origin===" + jsStringLiteral(schemeFor(*listen)+"://"+*listen) + "){sessionStorage.setItem('kx_token'," + jsStringLiteral(saved.Token) + ")}")
	}

	// Pantalla de inicio nativa propia (2026-10-31,: "la interfaz del gateway
	// de pc debe ser distinto al de movil en navegador [...] debe tener mas y bien
	// organizadas") — antes esta ventana navegaba DIRECTO a /panel, la misma página HTML que ve
	// cualquier navegador (móvil o no); la única diferencia eran 2 botones nativos pegados
	// arriba. Ahora el destino inicial es [desktopShellDataURL], una pantalla propia con "Este
	// dispositivo" + la lista de teléfonos ya emparejados ([pairedPhonesList]) — /panel sigue
	// existiendo, reusado desde el botón "Administrar este dispositivo" de esa pantalla.
	bindShellActions(w, panelURL, *listen)
	w.Navigate(desktopShellDataURL())
	w.Run()
}

type pairedPhoneEntry struct {
	Target  string        `json:"target"`
	Online  bool          `json:"online"`
	Metrics *phoneMetrics `json:"metrics,omitempty"`
}

// phoneMetrics -- mismos campos que AppMetricsOps.read() del lado Android (GatewayAppBridge.kt),
// system.metrics. nil cuando no se pudo obtener (teléfono offline, token sin permiso suficiente,
// timeout) -- el panel debe mostrar "sin datos", nunca ceros falsos.
type phoneMetrics struct {
	CPUPercent     *float64 `json:"cpu_percent"`
	RAMUsedMB      *float64 `json:"ram_used_mb"`
	RAMTotalMB     *float64 `json:"ram_total_mb"`
	BatteryPercent *int     `json:"battery_percent"`
}

// fetchPhoneMetrics -- POST /v1/actions/system.metrics con el token ya guardado (mismo pinning
// TLS que el resto de las llamadas a un teléfono emparejado, ver pinnedTLSClient). Hallazgo real
// de la investigación de Beszel (docs/arquitectura/INVESTIGACION_MODULOS_NUEVOS_Y_PENDIENTES_
// REFERENCIA_2026-11-05.md): "agente liviano que se autorregistra con un token y reporta
// métricas a un hub central -- directamente aplicable al objetivo del gateway de escritorio de
// 'ver/monitorear varios teléfonos Kairos desde un solo lugar'" -- ya existía pairedPhonesList()
// (teléfono + online/offline); esto agrega las métricas reales por teléfono a esa misma lista,
// sin que haga falta ningún agente/protocolo nuevo (system.metrics ya existía en el manifiesto).
// Hallazgo real de revisión automática (2026-11-05, "credential exposure / unpinned automatic
// token send"): pinnedTLSClient("") no verifica NADA (InsecureSkipVerify sin callback) -- eso
// es un trade-off aceptado para el PRIMER handshake de emparejamiento (checkPinnedCertificate),
// pero mandar el token real AUTOMÁTICAMENTE en cada refresh periódico por un canal sin ningún
// certificado pinneado deja la credencial expuesta a cualquiera en el camino de la red (ARP
// spoof, AP falso) cada vez que el dashboard refresca la lista. fetchPhoneMetrics NUNCA se
// llama para un target no-loopback sin fingerprint pinneado -- loopback nunca usa TLS
// (schemeFor), así que no hay canal que interceptar ahí.
func canFetchPhoneMetricsSafely(target string, phone pairedPhone) bool {
	return phone.CertFingerprint != "" || schemeFor(target) == "http"
}

func fetchPhoneMetrics(target string, phone pairedPhone) *phoneMetrics {
	scheme := schemeFor(target)
	req, err := http.NewRequest(http.MethodPost, scheme+"://"+target+"/v1/actions/system.metrics", strings.NewReader(`{"params":{}}`))
	if err != nil {
		return nil
	}
	req.Header.Set("Authorization", "Bearer "+phone.Token)
	req.Header.Set("Content-Type", "application/json")
	client := pinnedTLSClient(3*time.Second, phone.CertFingerprint)
	// Mismo criterio que checkPinnedCertificate: nunca seguir un redirect -- podría apuntar la
	// request (con el token Bearer ya puesto) a otro host.
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	var outer struct {
		Result struct {
			Output string `json:"output"`
		} `json:"result"`
	}
	// io.LimitReader: una respuesta maliciosamente enorme (del host real o de quien sea que
	// esté respondiendo en ese target) no debe poder agotar memoria del proceso de escritorio.
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&outer); err != nil {
		return nil
	}
	var m phoneMetrics
	if err := json.Unmarshal([]byte(outer.Result.Output), &m); err != nil {
		return nil
	}
	return &m
}

// pairedPhonesList — todos los teléfonos con token guardado ([loadRemoteTokens]), con su
// estado real (online = responde /healthz ahora mismo) — primera capacidad propia de escritorio
// pedida explícitamente por el usuario ("Lista de teléfonos ya emparejados"). Métricas en vivo
// agregadas 2026-11-05 (ver [fetchPhoneMetrics]) -- solo se piden si el teléfono ya respondió
// /healthz, para no esperar un timeout completo por cada uno que esté apagado/sin red.
func pairedPhonesList(localListen string) []pairedPhoneEntry {
	tokens := loadRemoteTokens()
	out := make([]pairedPhoneEntry, 0, len(tokens))
	for target, phone := range tokens {
		if target == localListen {
			continue // el gateway local de esta PC no es "un teléfono", tiene su propia tarjeta
		}
		online := healthy(target)
		entry := pairedPhoneEntry{Target: target, Online: online}
		if online && canFetchPhoneMetricsSafely(target, phone) {
			entry.Metrics = fetchPhoneMetrics(target, phone)
		}
		out = append(out, entry)
	}
	return out
}

// bindShellActions — funciones nativas que [desktopShellDataURL] necesita: listar teléfonos,
// conectar/ver pantalla de uno ya emparejado (mismo [ensurePaired] que usa --discover, así que
// reconectar a uno ya aceptado nunca vuelve a mostrar el diálogo en el teléfono), abrir el panel
// completo de este dispositivo, y las mismas kairosOpenDiscover/kairosOpenInBrowser de antes.
func bindShellActions(w webview.WebView, panelURL, localListen string) {
	_ = w.Bind("kairosShellListPhones", func() ([]pairedPhoneEntry, error) {
		return pairedPhonesList(localListen), nil
	})
	_ = w.Bind("kairosShellOpenPanel", func() error {
		w.Dispatch(func() {
			unbindShellActions(w)
			w.SetTitle("Kairos — Este dispositivo")
			w.Init(controlBannerInit("este dispositivo"))
			w.Navigate(panelURL)
		})
		return nil
	})
	// connectTo NAVEGA a un teléfono guardado (su propio /panel o /panel/screen) — ese destino
	// es, por diseño, código HTML/JS que ESE teléfono controla, no Kairos. Hallazgo real de
	// revisión automática (2026-10-31, "native bindings exposed to remote origins"): sin
	// desvincular antes de navegar, la página del teléfono de destino podría seguir llamando
	// kairosShellConnect/kairosShellListPhones/kairosOpenDiscover después — abriendo sesiones a
	// OTROS teléfonos guardados, o listándolos, desde JS que no es de Kairos. Mismo criterio ya
	// usado en unbindConnectForm()/unbindDiscoverForm() de este archivo, aplicado acá.
	//
	// Segundo hallazgo de la misma revisión ("stale identity mapping / credential disclosure"):
	// CERRADO 2026-11-05 (hallazgo de seguridad posterior, "improper certificate validation
	// (MITM)") -- ensurePaired() ya revalida el token guardado contra /v1/me antes de reusarlo
	// (nunca lo inyecta a ciegas), y ahora ADEMÁS pinnea el fingerprint SHA-256 del certificado
	// TLS visto en el primer emparejamiento (ver pairedPhone/pinnedTLSClient) -- eso SÍ es una
	// identidad criptográfica estable del gateway remoto, no solo host:puerto. DHCP reasignando
	// la IP a otro dispositivo ya no alcanza para heredar el token: el certificado de ese otro
	// dispositivo no coincide con el pinneado y la conexión se rechaza antes de mandar el Bearer.
	connectTo := func(target, path string) (string, error) {
		token, err := ensurePaired(target, "")
		if err != nil {
			return "", err
		}
		w.Dispatch(func() {
			unbindShellActions(w)
			w.SetTitle("Kairos — " + target)
			w.Init("if(location.origin===" + jsStringLiteral(schemeFor(target)+"://"+target) + "){sessionStorage.setItem('kx_token'," + jsStringLiteral(token) + ")}")
			w.Init(controlBannerInit(target))
			w.Navigate(schemeFor(target) + "://" + target + "/" + path)
		})
		return "conectando…", nil
	}
	_ = w.Bind("kairosShellConnect", func(target string) (string, error) { return connectTo(target, "panel") })
	_ = w.Bind("kairosShellConnectScreen", func(target string) (string, error) { return connectTo(target, "panel/screen") })
}

// unbindShellActions — desvincula TODAS las funciones nativas que la pantalla de inicio
// (desktopShellDataURL) expone, incluidas kairosOpenInBrowser/kairosOpenDiscover (bindeadas
// antes, en main()) — ninguna de las 2 debe seguir disponible una vez que la ventana navega a
// un destino real (el panel local o el de un teléfono remoto), por el mismo motivo que el resto
// de funciones de esta pantalla.
func unbindShellActions(w webview.WebView) {
	_ = w.Unbind("kairosOpenInBrowser")
	_ = w.Unbind("kairosOpenDiscover")
	_ = w.Unbind("kairosShellListPhones")
	_ = w.Unbind("kairosShellOpenPanel")
	_ = w.Unbind("kairosShellConnect")
	_ = w.Unbind("kairosShellConnectScreen")
}

// desktopShellDataURL — pantalla de inicio nativa: tarjeta "Este dispositivo" (abre el panel
// completo) + lista de teléfonos ya emparejados con Conectar/Ver pantalla por fila + botón para
// buscar uno nuevo. HTML/CSS/JS inline, mismo criterio que el resto de ventanas de este binario.
func desktopShellDataURL() string {
	return dataHTML(`<!doctype html><html><head><meta charset="utf-8"><style>
body{font-family:system-ui;background:#0d1117;color:#e6edf3;margin:0;padding:0;display:flex;height:100vh}
.sidebar{width:200px;flex:none;background:#161b22;border-right:1px solid #30363d;padding:20px 0}
.sidebar .grp{font-size:11px;color:#8b949e;text-transform:uppercase;letter-spacing:.05em;padding:14px 20px 6px}
.sidebar .item{padding:10px 20px;font-size:14px;color:#e6edf3;cursor:pointer}
.sidebar .item.on{background:#1f6feb22;border-right:2px solid #1f6feb;font-weight:600}
.main{flex:1;overflow-y:auto;padding:28px 32px}
h1{font-size:20px;margin:0 0 4px}
p.hint{color:#8b949e;font-size:13px;margin:0 0 20px}
.card{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:18px 20px;margin-bottom:16px}
.card h3{margin:0 0 6px;font-size:15px}
.card p{color:#8b949e;font-size:13px;margin:0 0 12px}
button{background:#1f6feb;color:#fff;border:none;border-radius:8px;padding:9px 14px;font-size:13px;cursor:pointer;margin-right:8px}
button.secondary{background:#30363d}
.phone{display:flex;justify-content:space-between;align-items:center;background:#0d1117;border:1px solid #30363d;border-radius:8px;padding:12px 14px;margin-bottom:8px}
.phone .target{font-family:monospace;font-size:13px}
.phone .state{font-size:12px;margin-top:3px}
.phone .state.on{color:#3fb950}
.phone .state.off{color:#8b949e}
.phone .metrics{font-size:11px;color:#6e7681;margin-top:2px;font-family:monospace}
.empty{color:#8b949e;font-size:13px;padding:8px 0}
</style></head><body>
<div class="sidebar">
  <div class="grp">Kairos</div>
  <div class="item on">🏠 Inicio</div>
  <div class="grp">&nbsp;</div>
</div>
<div class="main">
  <h1>Kairos Desktop</h1>
  <p class="hint">Este dispositivo y los teléfonos que ya emparejaste con esta PC.</p>
  <div class="card">
    <h3>💻 Este dispositivo</h3>
    <p>Gateway local de esta PC — módulos, archivos, terminal, todo lo que ya conocés del panel completo.</p>
    <button onclick="window.kairosShellOpenPanel()">Administrar este dispositivo</button>
    <button class="secondary" onclick="window.kairosOpenInBrowser && window.kairosOpenInBrowser()">Abrir en el navegador</button>
  </div>
  <div class="card">
    <h3>📱 Teléfonos</h3>
    <p>Teléfonos Kairos ya emparejados — conectate sin volver a buscar ni pedir permiso de nuevo.</p>
    <div id="phones"><p class="empty">Cargando…</p></div>
    <button onclick="window.kairosOpenDiscover && window.kairosOpenDiscover()">🔎 Buscar teléfono nuevo (LAN/USB)</button>
  </div>
</div>
<script>
function esc(s){return (s||'').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
async function loadPhones(){
  const el = document.getElementById('phones');
  try {
    const phones = await window.kairosShellListPhones();
    if (!phones || phones.length === 0) { el.innerHTML = '<p class="empty">Todavía no empareja­ste ningún teléfono — usá "Buscar teléfono nuevo".</p>'; return; }
    el.innerHTML = '';
    for (const p of phones) {
      const div = document.createElement('div'); div.className = 'phone';
      let metricsHtml = '';
      if (p.metrics) {
        const m = p.metrics;
        const parts = [];
        if (m.cpu_percent != null) parts.push('CPU ' + Math.round(m.cpu_percent) + '%');
        if (m.ram_used_mb != null && m.ram_total_mb != null) parts.push('RAM ' + Math.round(m.ram_used_mb) + '/' + Math.round(m.ram_total_mb) + 'MB');
        if (m.battery_percent != null) parts.push('Batería ' + m.battery_percent + '%');
        if (parts.length > 0) metricsHtml = '<div class="metrics">' + esc(parts.join(' · ')) + '</div>';
      }
      div.innerHTML = '<div><div class="target">' + esc(p.target) + '</div><div class="state ' + (p.online?'on':'off') + '">' + (p.online?'En línea':'Sin respuesta ahora') + '</div>' + metricsHtml + '</div>';
      const btns = document.createElement('div');
      const bConnect = document.createElement('button'); bConnect.textContent = 'Conectar';
      bConnect.onclick = () => window.kairosShellConnect(p.target);
      const bScreen = document.createElement('button'); bScreen.className = 'secondary'; bScreen.textContent = 'Ver pantalla';
      bScreen.onclick = () => window.kairosShellConnectScreen(p.target);
      btns.appendChild(bConnect); btns.appendChild(bScreen);
      div.appendChild(btns);
      el.appendChild(div);
    }
  } catch (e) {
    el.innerHTML = '<p class="empty">Error: ' + esc(String(e)) + '</p>';
  }
}
loadPhones();
</script></body></html>`)
}

const localTokenName = "PC de escritorio (local)"

// mintLocalFullToken emite un token nuevo de nivel "total" para ESTA PC, usando el secreto de
// administración que ya tenemos del camino fuerte — nunca se usa en el camino débil (sin
// secreto, no hay forma de autenticar contra /v1/admin/*). Si ya existe un token con el mismo
// nombre de una apertura anterior, se revoca primero — evita acumular tokens "PC de escritorio
// (local)" sin usar en cada apertura (un token nunca se puede volver a leer una vez creado, ver
// `.claude/rules/kairos-secrets-never-revealed.md`, así que no hay forma de "reusar" el secreto
// viejo — solo de no dejar basura acumulada en la lista).
func mintLocalFullToken(listen, adminSecret string) (string, error) {
	client := insecureTLSClient(5 * time.Second)
	base := schemeFor(listen) + "://" + listen + "/v1/admin/tokens"

	listReq, _ := http.NewRequest(http.MethodGet, base, nil)
	listReq.Header.Set("X-Kairos-Admin", adminSecret)
	if resp, err := client.Do(listReq); err == nil {
		var out struct {
			Tokens []struct {
				ID   string `json:"id"`
				Name string `json:"name"`
			} `json:"tokens"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&out)
		resp.Body.Close()
		for _, t := range out.Tokens {
			if t.Name == localTokenName {
				delReq, _ := http.NewRequest(http.MethodDelete, base+"/"+t.ID, nil)
				delReq.Header.Set("X-Kairos-Admin", adminSecret)
				if r, err := client.Do(delReq); err == nil {
					r.Body.Close()
				}
			}
		}
	}

	body, _ := json.Marshal(map[string]string{"name": localTokenName, "level": "total"})
	createReq, err := http.NewRequest(http.MethodPost, base, strings.NewReader(string(body)))
	if err != nil {
		return "", err
	}
	createReq.Header.Set("Content-Type", "application/json")
	createReq.Header.Set("X-Kairos-Admin", adminSecret)
	resp, err := client.Do(createReq)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		return "", fmt.Errorf("el gateway respondió %d al crear el token local", resp.StatusCode)
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if out.Token == "" {
		return "", fmt.Errorf("respuesta sin token")
	}
	return out.Token, nil
}

// runPairMode — Flujo 3: pedir emparejarse con un teléfono remoto en vez de
// abrir el gateway local. Abre la ventana YA (con un mensaje de espera, `w.Navigate("data:...")`)
// y hace el pedido HTTP en un goroutine aparte — bloquear la UI mientras se espera la confirmación
// en el teléfono (hasta 2 minutos, el mismo timeout que ya tiene `POST /v1/pair` del lado del
// servidor) sería una ventana congelada sin ninguna señal de que algo sigue en curso.
func runPairMode(target, name string) {
	if _, _, err := net.SplitHostPort(target); err != nil {
		showFatalError("Kairos Dashboard", fmt.Sprintf("--pair inválido (%q): se espera host:puerto, ej. 192.168.1.8:8973 (%s)", target, err.Error()))
		return
	}
	if name == "" {
		h, err := os.Hostname()
		if err != nil || h == "" {
			h = "PC de escritorio"
		}
		name = h
	}
	w := webview.New(false)
	defer w.Destroy()
	w.SetTitle("Kairos — Emparejando con " + target)
	w.SetSize(760, 420, webview.HintNone)
	w.Navigate(waitingPageDataURL(target, name))

	go func() {
		token, err := ensurePaired(target, name)
		if err != nil {
			w.Dispatch(func() { w.Navigate(errorPageDataURL(err.Error())) })
			return
		}
		w.Dispatch(func() {
			// sessionStorage ANTES de navegar (Init corre en CADA carga de página, no solo la
			// primera) — el panel ya lee sessionStorage.getItem('kx_token') al cargar
			// (internal/server/panel/index.html), así que esto evita que la persona tenga que
			// pegar el token a mano después de que el teléfono ya lo aprobó.
			//
			// Hallazgo de revisión automática ("credential exposure, token injected into every
			// origin"): como Init() corre en toda carga posterior de ESTA ventana, sin este
			// chequeo el token se intentaría guardar también si la persona navegara a cualquier
			// otro sitio dentro de la misma ventana (ej. un enlace externo) — acotado acá a que
			// SOLO actúe si el documento es realmente el origen de destino.
			w.SetTitle("Kairos — " + target)
			w.Init("if(location.origin===" + jsStringLiteral(schemeFor(target)+"://"+target) + "){sessionStorage.setItem('kx_token'," + jsStringLiteral(token) + ")}")
			w.Init(controlBannerInit(target))
			w.Navigate(schemeFor(target) + "://" + target + "/panel")
		})
	}()
	w.Run()
}

// remoteTokensFile — un solo token guardado por teléfono (target host:puerto), en la carpeta de
// estado del dashboard. Hallazgo real del usuario (2026-10-31): "porque
// todavia el gateway sigue pidiendo el token al entrar... cuando es red local o usb se debe
// detectar automaticamente" — antes CADA apertura de --connect/--discover volvía a pedir
// emparejamiento desde cero (sessionStorage se pierde al cerrar la ventana, nunca había nada
// persistido en el lado de la PC), así que el diálogo de "¿permitir a esta PC?" le aparecía al
// teléfono una y otra vez para el mismo par PC+teléfono ya confiado. Ahora se guarda el token
// la PRIMERA vez (ahí sí hace falta que el teléfono confirme — es la única autenticación real de
// un endpoint sin credenciales) y las siguientes conexiones al MISMO teléfono reusan ese token
// sin volver a pedir nada, mientras siga siendo válido (se revalida contra /v1/me antes de
// confiarlo ciego — un token revocado desde el teléfono cae al flujo de re-emparejar solo).
func remoteTokensPath() (string, error) {
	dir, err := defaultGatewayDir()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	return filepath.Join(dir, "paired_phones.json"), nil
}

// pairedPhone guarda el token Y el fingerprint SHA-256 del certificado TLS visto al emparejar
// (hallazgo real de revisión automática, "improper certificate validation (MITM)" --
// InsecureSkipVerify sin ningún pinning dejaba que un atacante activo en la LAN se hiciera pasar
// por el teléfono ya emparejado y se quedara con el token Bearer). CertFingerprint vacío = par
// guardado ANTES de este cambio, o emparejado por loopback/USB forward (sin TLS real de por
// medio) -- en ese caso no hay nada que pinnear, se sigue confiando solo en el token, igual que
// antes. De paso cierra el riesgo ya documentado de "stale identity mapping" (DHCP reasignando
// la IP a otro dispositivo): el fingerprint es un identificador real del gateway remoto, no solo
// la IP:puerto.
type pairedPhone struct {
	Token           string `json:"token"`
	CertFingerprint string `json:"certFingerprint,omitempty"`
}

func loadRemoteTokens() map[string]pairedPhone {
	path, err := remoteTokensPath()
	if err != nil {
		return map[string]pairedPhone{}
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return map[string]pairedPhone{}
	}
	var out map[string]pairedPhone
	if json.Unmarshal(data, &out) == nil {
		return out
	}
	// Formato viejo (map[string]string, de antes de que existiera el pinning) -- se migra en
	// memoria sin fingerprint; la próxima conexión exitosa lo completa (ver ensurePaired).
	var legacy map[string]string
	if json.Unmarshal(data, &legacy) != nil {
		return map[string]pairedPhone{}
	}
	out = make(map[string]pairedPhone, len(legacy))
	for target, token := range legacy {
		out[target] = pairedPhone{Token: token}
	}
	return out
}

func saveRemoteToken(target string, phone pairedPhone) {
	path, err := remoteTokensPath()
	if err != nil {
		return
	}
	tokens := loadRemoteTokens()
	tokens[target] = phone
	data, err := json.MarshalIndent(tokens, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(path, data, 0o600)
}

// certFingerprintSHA256 — huella del certificado hoja realmente presentado en esta conexión TLS
// (nil si la conexión fue HTTP plano, ej. loopback/USB forward -- no hay nada que pinnear ahí).
func certFingerprintSHA256(resp *http.Response) string {
	if resp == nil || resp.TLS == nil || len(resp.TLS.PeerCertificates) == 0 {
		return ""
	}
	sum := sha256.Sum256(resp.TLS.PeerCertificates[0].Raw)
	return hex.EncodeToString(sum[:])
}

// pinnedTLSClient — como insecureTLSClient, pero si [pinnedFingerprint] no está vacío, rechaza
// la conexión cuando el certificado presentado NO coincide con el pinneado (TOFU: trust on first
// use, mismo modelo que un host key de SSH). InsecureSkipVerify sigue en true porque la
// verificación real de CA/hostname no tiene sentido para un cert autofirmado de una IP de LAN --
// pero con VerifyPeerCertificate, un atacante activo que presente OTRO certificado (el suyo) para
// hacerse pasar por el teléfono ya emparejado ahora sí falla la conexión antes de que el token
// Bearer viaje.
func pinnedTLSClient(timeout time.Duration, pinnedFingerprint string) *http.Client {
	tlsConfig := &tls.Config{InsecureSkipVerify: true} //nolint:gosec
	if pinnedFingerprint != "" {
		tlsConfig.VerifyPeerCertificate = func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return fmt.Errorf("el servidor no presentó ningún certificado")
			}
			sum := sha256.Sum256(rawCerts[0])
			if hex.EncodeToString(sum[:]) != pinnedFingerprint {
				return fmt.Errorf("el certificado del servidor cambió desde el emparejamiento -- posible suplantación, conexión rechazada")
			}
			return nil
		}
	}
	return &http.Client{Timeout: timeout, Transport: &http.Transport{TLSClientConfig: tlsConfig}}
}

// remoteTokenStillValid — GET /v1/me con el token guardado; 200 = sigue vigente.
//
// Riesgo real señalado por revisión automática (2026-10-31, "credential sent to re-assigned
// host / stale identity mapping"): [target] es host:puerto (ej. una IP de LAN) — si esa IP pasa
// a otro dispositivo (DHCP la reasigna), este código podría mandarle el token guardado a quien
// sea que esté ahí ahora. CERRADO 2026-11-05: pinnedTLSClient() usa phone.CertFingerprint (el
// SHA-256 del certificado TLS visto al emparejar, ver [pairedPhone]) para rechazar la conexión
// ANTES de que el header Authorization salga -- si el dispositivo real cambió de ese host:puerto
// (DHCP, o un atacante activo suplantándolo), su certificado no coincide con el pinneado y la
// request falla en el handshake TLS, nunca llega a enviar el token. Pares guardados ANTES de
// este cambio (CertFingerprint vacío, loopback/USB forward sin TLS real) siguen sin pinning --
// se completa solo en la próxima vez que [requestPairing] corra de cero para ese target.
func remoteTokenStillValid(target string, phone pairedPhone) bool {
	req, err := http.NewRequest(http.MethodGet, schemeFor(target)+"://"+target+"/v1/me", nil)
	if err != nil {
		return false
	}
	req.Header.Set("Authorization", "Bearer "+phone.Token)
	client := pinnedTLSClient(5*time.Second, phone.CertFingerprint)
	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}

// ensurePaired devuelve un token usable para [target] — reusa el guardado si sigue vigente
// (sin tocar al teléfono para nada), o pide un emparejamiento nuevo (ahí SÍ aparece el diálogo
// en el teléfono, como corresponde la primera vez) y lo guarda para la próxima.
// ErrCertificatePinMismatch — el certificado presentado por el target ya NO coincide con el que
// se pinneó al emparejar. Hallazgo real de revisión automática ("pin bypass via silent
// re-pair"): la versión anterior de ensurePaired(), al ver que remoteTokenStillValid() daba
// false, caía SIEMPRE a requestPairing() de nuevo sin preguntar por qué falló -- un atacante
// activo presentando su propio certificado lograba exactamente ese "false" y el código volvía a
// emparejar solo, pinneando en silencio el certificado del impostor. Ahora ese caso específico
// corta ACÁ, nunca llega a requestPairing().
var ErrCertificatePinMismatch = errors.New("el certificado del teléfono cambió desde el emparejamiento -- posible suplantación, no se reempareja automáticamente")

// checkPinnedCertificate hace una conexión TLS mínima (GET /healthz, sin token) solo para leer
// el certificado que [target] presenta AHORA MISMO, separado de remoteTokenStillValid() para
// poder distinguir "el certificado no coincide" (bloquea, nunca re-empareja solo) de "no se
// pudo conectar" (el teléfono puede estar apagado/fuera de la red -- eso sí amerita el camino
// normal de reintentar, no es un mismatch confirmado).
// ErrCertificateUnverifiable — había un fingerprint pinneado para este target, pero no se pudo
// CONFIRMAR que sigue siendo el mismo (sin conexión, timeout, o sin info de TLS en la
// respuesta). Hallazgo real de revisión automática ("fail-open certificate pinning"): la
// versión anterior trataba esto como "inconcluso, seguí adelante" -- un atacante que lograra
// que esta sonda fallara (temporizándola, bloqueándola con un firewall, etc.) conseguía que
// ensurePaired() cayera igual al camino normal de abajo (remoteTokenStillValid/requestPairing),
// reabriendo el mismo bypass. Ahora CUALQUIER falla al verificar (no solo un mismatch
// confirmado) bloquea el reemparejamiento automático -- fail-closed, nunca fail-open.
var ErrCertificateUnverifiable = errors.New("no se pudo confirmar el certificado del teléfono (sin conexión, timeout, o sin TLS) -- no se reempareja automáticamente mientras haya un fingerprint pinneado")

// [scheme] se recibe explícito (no se vuelve a derivar con schemeFor acá) para que un test
// pueda forzar "https" aunque [target] sea técnicamente loopback (ej. httptest.NewTLSServer,
// que siempre bindea 127.0.0.1) -- la decisión real de scheme sigue siendo UNA sola, calculada
// una vez en ensurePaired().
func checkPinnedCertificate(scheme, target, pinnedFingerprint string) error {
	if pinnedFingerprint == "" {
		return nil // nada pinneado todavía (par viejo sin fingerprint, o loopback/USB sin TLS)
	}
	// Mismo cliente que ScanLANGateways (internal/router/lanscan.go): nunca sigue redirects.
	// Hallazgo real de revisión automática ("redirect / empty-fingerprint differential") -- un
	// 3xx desde [target] podría apuntar la sonda a OTRO host (ej. el teléfono real, si el
	// atacante puede alcanzarlo) y hacer que el fingerprint capturado coincida con el pinneado
	// sin que la conexión real a [target] haya sido con ese certificado.
	client := &http.Client{
		Timeout:       3 * time.Second,
		Transport:     &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}, //nolint:gosec
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	resp, err := client.Get(scheme + "://" + target + "/healthz")
	if err != nil {
		return fmt.Errorf("%w: %v", ErrCertificateUnverifiable, err)
	}
	defer resp.Body.Close()
	// got=="" también es mismatch (hallazgo real: antes se trataba como "sin info, seguí" --
	// con un fingerprint pinneado, esperamos TLS real; si no lo hay, algo cambió).
	if got := certFingerprintSHA256(resp); got == "" || got != pinnedFingerprint {
		return ErrCertificatePinMismatch
	}
	return nil
}

func ensurePaired(target, name string) (string, error) {
	if saved, ok := loadRemoteTokens()[target]; ok {
		if err := checkPinnedCertificate(schemeFor(target), target, saved.CertFingerprint); err != nil {
			return "", err
		}
		if remoteTokenStillValid(target, saved) {
			return saved.Token, nil
		}
	}
	phone, err := requestPairing(target, name)
	if err != nil {
		return "", err
	}
	saveRemoteToken(target, phone)
	return phone.Token, nil
}

// requestPairing manda POST /v1/pair y devuelve el token + el fingerprint del certificado TLS
// visto en ESTA conexión (vacío si fue HTTP plano, ej. loopback/USB forward) si el teléfono
// aprobó. El cliente HTTP necesita un timeout mayor al del servidor (2 min) para no cortar la
// espera antes de tiempo. Sin fingerprint pinneado todavía en esta llamada -- es la PRIMERA
// conexión a este target, TOFU real: se confía en lo que responda ahora y se fija para las
// próximas (ver pinnedTLSClient).
func requestPairing(target, name string) (pairedPhone, error) {
	body, err := json.Marshal(map[string]string{"name": name})
	if err != nil {
		return pairedPhone{}, err
	}
	client := insecureTLSClient(150 * time.Second)
	resp, err := client.Post(schemeFor(target)+"://"+target+"/v1/pair", "application/json", strings.NewReader(string(body)))
	if err != nil {
		return pairedPhone{}, fmt.Errorf("no se pudo contactar a %s — ¿está encendido el gateway ahí? (%w)", target, err)
	}
	defer resp.Body.Close()
	fingerprint := certFingerprintSHA256(resp)
	var out struct {
		State string `json:"state"`
		Token string `json:"token"`
		Error string `json:"error"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return pairedPhone{}, fmt.Errorf("respuesta inválida de %s: %w", target, err)
	}
	if resp.StatusCode != http.StatusOK {
		msg := out.Error
		if msg == "" {
			msg = fmt.Sprintf("código %d", resp.StatusCode)
		}
		return pairedPhone{}, fmt.Errorf("%s", msg)
	}
	if out.Token == "" {
		return pairedPhone{}, fmt.Errorf("el teléfono aceptó pero no llegó ningún token")
	}
	return pairedPhone{Token: out.Token, CertFingerprint: fingerprint}, nil
}

func jsStringLiteral(s string) string {
	b, _ := json.Marshal(s) // json.Marshal de un string ES un literal JS válido (mismas reglas de escape)
	return string(b)
}

// controlBannerInit — barra fija inyectada SOLO dentro de la app de escritorio (via w.Init(),
// nunca presente si alguien abre el mismo panel desde un navegador normal) mostrando a quién se
// está controlando — hallazgo real del usuario (2026-10-31): "no veo nada que
// diga que esta controlando al movil, todo sigue negro y feo". No reescribe el panel compartido
// (.claude/rules/kairos-gateway-desktop-app-prioridad.md: la prioridad es la app de escritorio,
// no el navegador ni el panel web en sí) — solo le superpone un marco nativo con el contexto que
// faltaba, mismo estilo (paleta oscura tipo GitHub) que el resto de ventanas de este binario.
func controlBannerInit(label string) string {
	return `document.addEventListener('DOMContentLoaded',function(){
  var b=document.createElement('div');
  b.style.cssText='position:fixed;top:0;left:0;right:0;z-index:99999;background:#161b22;border-bottom:1px solid #30363d;color:#e6edf3;font:600 13px system-ui;padding:8px 16px;display:flex;align-items:center;gap:10px;box-shadow:0 2px 8px #0008';
  var icon=document.createElement('span'); icon.style.fontSize='15px'; icon.textContent='🖥️';
  var text=document.createElement('span'); text.textContent='Controlando: ' + ` + jsStringLiteral(label) + `;
  b.appendChild(icon); b.appendChild(text);
  document.body.prepend(b);
  document.body.style.paddingTop='38px';
});`
}

// waitingPageDataURL/errorPageDataURL — hallazgo de revisión automática ("XSS / HTML injection in
// webview"): [msg] en particular puede traer texto de un servidor REMOTO (el error que devuelva
// el "gateway" en --pair, que podría ni siquiera ser un gateway real) — sin escapar, ese texto se
// interpretaría como HTML/JS dentro de esta misma ventana. `html.EscapeString` en los tres
// valores dinámicos (target/name igual, por las dudas, aunque hoy son locales y no de red).
//
// Corrección real (reporte del usuario, 2026-10-26): el texto salía roto ("tÃºnel", "pÃºblica")
// — `url.QueryEscape` codifica el espacio como "+" (el formato de un formulario
// `application/x-www-form-urlencoded`, NO el de la parte de datos de una URI `data:`, donde "+"
// se interpreta literal) y además deja la decisión de qué charset usar al navegador/WebView, que
// puede no asumir UTF-8 por defecto. `dataHTML` arma el `data:` en **base64** — sin ambigüedad de
// escapado ni de charset, es el formato que la propia documentación de webview_go recomienda
// (`data:text/html;base64,...`).
func dataHTML(body string) string {
	return "data:text/html;charset=utf-8;base64," + base64.StdEncoding.EncodeToString([]byte(body))
}

func waitingPageDataURL(target, name string) string {
	return dataHTML(
		"<body style='font-family:system-ui;background:#0d1117;color:#e6edf3;padding:40px;text-align:center'>" +
			"<h2>Pidiendo emparejarse con " + html.EscapeString(target) + "…</h2>" +
			"<p>Revisá el teléfono — debería mostrar un aviso para aceptar <b>\"" + html.EscapeString(name) + "\"</b>.</p>" +
			"<p style='color:#8b949e'>Esto puede tardar hasta 2 minutos.</p></body>")
}

func errorPageDataURL(msg string) string {
	return dataHTML(
		"<body style='font-family:system-ui;background:#0d1117;color:#e6edf3;padding:40px;text-align:center'>" +
			"<h2 style='color:#f85149'>No se pudo emparejar</h2><p>" + html.EscapeString(msg) + "</p></body>")
}

// runSSHTunnelMode — Flujo 2 (investigación de referencia/): en vez de hablar
// directo por LAN (gateway escuchando en 0.0.0.0, sin cifrar), abre un túnel SSH local hacia el
// servidor sshd que YA trae el módulo Remote de Kairos (puerto 8022 por defecto) y conecta el
// panel a través de ESE túnel — el gateway del teléfono se queda en modo local (127.0.0.1
// solamente), nada se expone sin cifrar en la red.
//
// Decisiones tomadas con evidencia real de `referencia/`:
//   - Se usa el `ssh` NATIVO del sistema (nunca una librería SSH embebida) — mismo patrón que
//     OpenClaw (`ssh-client.ts`): en Windows, ruta FIJA a `%SystemRoot%\System32\OpenSSH\ssh.exe`
//     (nunca confiar en que el PATH tenga el correcto), confirmado presente en esta PC
//     (`OpenSSH_for_Windows_9.5p2`); en otros sistemas, `exec.LookPath("ssh")`.
//   - `BatchMode=yes` + `ExitOnForwardFailure=yes`: nunca un prompt interactivo que esta app
//     gráfica no podría mostrar — si la autenticación por clave no está lista (el usuario todavía
//     no agregó su clave pública a `authorized_keys` del teléfono, módulo Remote) o el túnel no
//     se puede abrir, el comando falla limpio en vez de quedarse colgado esperando una contraseña
//     que nadie va a tipear.
//   - Verificación de host key: `StrictHostKeyChecking=accept-new` (nativo de OpenSSH desde 7.6)
//     — confía en la clave la PRIMERA vez (TOFU) y la graba en un `UserKnownHostsFile` PROPIO de
//     Kairos (nunca el `~/.ssh/known_hosts` real del usuario), pero si la clave CAMBIA después,
//     corta — mismo resultado final que el TOFU-con-persistencia de Haven/avnc (mostrar la huella
//     y guardarla, bloquear si cambia) sin tener que escribir ni probar un verificador de huellas
//     a mano: es exactamente lo que ya hace esta opción estándar de OpenSSH. Nunca
//     `StrictHostKeyChecking=no` (ignora cambios de clave por completo — el anti-patrón
//     confirmado en 1Panel/OpenPanel, ver la investigación).
//
// No verificado en dispositivo real todavía: que el `sshd` de Termux (módulo Remote) acepte
// `-L` hacia `127.0.0.1:8973` (`AllowTcpForwarding`, activado por defecto en la mayoría de las
// configuraciones de sshd, pero no confirmado en el `sshd_config` real de Kairos en esta ronda).
func runSSHTunnelMode(target string) {
	w := webview.New(false)
	defer w.Destroy()
	w.SetTitle("Kairos — Túnel SSH con " + target)
	w.SetSize(760, 420, webview.HintNone)
	w.Navigate(waitingSSHPageDataURL(target))

	localListen, cleanup, err := startSSHTunnel(target, 15*time.Second)
	if err != nil {
		w.Navigate(errorPageDataURL(err.Error()))
		w.Run()
		return
	}
	defer cleanup()
	w.Init(controlBannerInit(target + " (vía túnel SSH)"))
	w.Navigate(schemeFor(localListen) + "://" + localListen + "/panel")
	w.Run()
}

// startSSHTunnel arranca el `ssh -L` de verdad y espera hasta [timeout] a que el túnel levante
// (healthz a través de él) — núcleo reusable, sin nada de webview, para que tanto
// [runSSHTunnelMode] (bandera --ssh por línea de comandos) como el formulario de [runConnectMode]
// puedan usar la misma lógica. Devuelve la dirección local del túnel y una función para cerrarlo.
func startSSHTunnel(target string, timeout time.Duration) (localListen string, cleanup func(), err error) {
	sshBin, err := resolveSSHBinary()
	if err != nil {
		return "", nil, err
	}
	localPort, err := freeLocalPort()
	if err != nil {
		return "", nil, fmt.Errorf("no se pudo reservar un puerto local: %w", err)
	}
	knownHosts, err := ownKnownHostsFile()
	if err != nil {
		return "", nil, fmt.Errorf("no se pudo preparar el archivo de huellas conocidas: %w", err)
	}
	localListen = fmt.Sprintf("127.0.0.1:%d", localPort)

	cmd := exec.Command(sshBin,
		"-N", // sin shell remota, solo el túnel
		"-o", "BatchMode=yes",
		"-o", "ExitOnForwardFailure=yes",
		"-o", "StrictHostKeyChecking=accept-new",
		"-o", "UserKnownHostsFile="+knownHosts,
		"-o", "ServerAliveInterval=15",
		"-o", "ServerAliveCountMax=3",
		"-L", localListen+":127.0.0.1:8973",
		target,
	)
	cmd.SysProcAttr = hideConsoleAttr()
	var sshErr strings.Builder
	cmd.Stderr = &sshErr
	if err := cmd.Start(); err != nil {
		return "", nil, fmt.Errorf("no se pudo arrancar ssh: %w", err)
	}
	cleanup = func() { _ = cmd.Process.Kill() }

	if !waitHealthy(localListen, timeout) {
		cleanup()
		msg := "el túnel SSH no levantó a tiempo"
		if sshErr.Len() > 0 {
			msg += ": " + strings.TrimSpace(sshErr.String())
		}
		return "", nil, fmt.Errorf("%s", msg)
	}
	return localListen, cleanup, nil
}

// resolveSSHBinary — en Windows, ruta FIJA (nunca el PATH, ver KDoc de [runSSHTunnelMode]); en
// otros sistemas, busca "ssh" en el PATH (no hay un equivalente de ruta fija ahí).
func resolveSSHBinary() (string, error) {
	fixed := filepath.Join(os.Getenv("SystemRoot"), "System32", "OpenSSH", "ssh.exe")
	if _, err := os.Stat(fixed); err == nil {
		return fixed, nil
	}
	if path, err := exec.LookPath("ssh"); err == nil {
		return path, nil
	}
	return "", fmt.Errorf("no se encontró ssh — en Windows 10/11 viene como función opcional \"Cliente OpenSSH\"; instalala desde Configuración y volvé a intentar")
}

func freeLocalPort() (int, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port, nil
}

// ownKnownHostsFile — NUNCA el ~/.ssh/known_hosts real del usuario (no se toca su configuración
// de SSH para nada ajeno a este túnel) — un archivo propio dentro de la carpeta de estado del
// gateway.
func ownKnownHostsFile() (string, error) {
	dir, err := defaultGatewayDir()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	path := filepath.Join(dir, "ssh_known_hosts")
	if _, err := os.Stat(path); os.IsNotExist(err) {
		if err := os.WriteFile(path, nil, 0o600); err != nil {
			return "", err
		}
	}
	return path, nil
}

func waitingSSHPageDataURL(target string) string {
	return dataHTML(
		"<body style='font-family:system-ui;background:#0d1117;color:#e6edf3;padding:40px;text-align:center'>" +
			"<h2>Abriendo túnel SSH con " + html.EscapeString(target) + "…</h2>" +
			"<p style='color:#8b949e'>Necesita que tu clave pública ya esté en authorized_keys del teléfono (módulo Remote de Kairos).</p></body>")
}

// runConnectMode — pedido explícito del usuario (2026-10-26): "no deberían
// salir casillas o algo para rellenar? no toca poner el ip del móvil y clave ssh del móvil para
// controlarlo?" — hasta esta corrección, --pair/--ssh solo se podían usar escribiendo el target a
// mano por línea de comandos; esto abre un formulario real (campos de texto) dentro de la MISMA
// ventana nativa, sin depender de ningún navegador. Controla el TELÉFONO desde el gateway de PC
// (no al revés) — es la dirección que pidió el pairing/túnel SSH ya implementados.
//
// webview.Bind expone una función Go como `window.<nombre>(...)` en JS — la llamada del lado C
// (webview_bind, ver libs/webview/include) corre en un hilo PROPIO, no en el de la interfaz, así
// que el formulario puede esperar hasta 2 minutos (el pairing) sin congelar la ventana; el botón
// de la página solo se deshabilita mientras espera.
func runConnectMode() {
	w := webview.New(false)
	defer w.Destroy()
	w.SetTitle("Kairos — Conectar a un teléfono")
	w.SetSize(480, 560, webview.HintNone)

	// activeTunnel guarda el cleanup() del último túnel SSH abierto desde este formulario —
	// hallazgo real de revisión automática ("resource-lifecycle / unintended exposure"): la
	// primera versión descartaba el cleanup de [startSSHTunnel] con `_`, así que el proceso ssh
	// hijo quedaba huérfano para siempre (ni se mataba al reintentar con otro host, ni al cerrar
	// la ventana). Con mutex porque [kairosConnect] puede llamarse más de una vez en la misma
	// ventana (ej. el primer intento falla, la persona corrige el host y reintenta).
	var tunnelMu sync.Mutex
	var activeTunnel func()
	killActiveTunnel := func() {
		tunnelMu.Lock()
		defer tunnelMu.Unlock()
		if activeTunnel != nil {
			activeTunnel()
			activeTunnel = nil
		}
	}
	defer killActiveTunnel()

	// kairosScan — pedido explícito del usuario: no debería hacer falta
	// escribir la IP del teléfono a mano cada vez. Barre el /24 de las interfaces de RED LOCAL de
	// esta PC buscando otros gateways de Kairos reales (misma lógica que ya usa
	// network.scan_gateways del lado del teléfono — internal/router.ScanLANGateways,
	// router.LocalLANIPs — reusada tal cual, no reimplementada). Da UBICACIÓN, no identidad de
	// confianza (mismo comentario que ScanLANGateways): elegir un resultado solo rellena el campo
	// IP, el pairing/token real sigue haciendo falta para conectarse de verdad.
	_ = w.Bind("kairosScan", func() ([]string, error) {
		addrs, err := net.InterfaceAddrs()
		if err != nil {
			return nil, fmt.Errorf("no se pudieron leer las interfaces de red: %w", err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		found := router.ScanLANGateways(ctx, router.LocalLANIPs(addrs), "8973", 400*time.Millisecond)
		return found, nil
	})

	_ = w.Bind("kairosConnect", func(mode, host, sshUser, name string) (string, error) {
		host = strings.TrimSpace(host)
		if host == "" {
			return "", fmt.Errorf("falta la IP o el nombre de host del teléfono")
		}
		switch mode {
		case "directo":
			target := host
			if _, _, err := net.SplitHostPort(target); err != nil {
				target = host + ":8973" // puerto del gateway por defecto si no lo escribieron
			}
			if name = strings.TrimSpace(name); name == "" {
				if h, err := os.Hostname(); err == nil && h != "" {
					name = h
				} else {
					name = "PC de escritorio"
				}
			}
			token, err := ensurePaired(target, name)
			if err != nil {
				return "", err
			}
			w.Dispatch(func() {
				unbindConnectForm(w)
				w.SetTitle("Kairos — " + target)
				w.Init("if(location.origin===" + jsStringLiteral(schemeFor(target)+"://"+target) + "){sessionStorage.setItem('kx_token'," + jsStringLiteral(token) + ")}")
				w.Init(controlBannerInit(target))
				w.Navigate(schemeFor(target) + "://" + target + "/panel")
			})
			return "conectando…", nil
		case "ssh":
			sshUser = strings.TrimSpace(sshUser)
			if sshUser == "" {
				return "", fmt.Errorf("falta el usuario SSH (módulo Remote de Kairos, ej. u0_a123)")
			}
			sshHost := host
			if _, _, err := net.SplitHostPort(sshHost); err != nil {
				sshHost = host + ":8022" // puerto sshd por defecto del módulo Remote
			}
			target := sshUser + "@" + sshHost
			killActiveTunnel() // si ya había un túnel de un intento anterior, cerrarlo antes de abrir otro
			localListen, cleanup, err := startSSHTunnel(target, 15*time.Second)
			if err != nil {
				return "", err
			}
			tunnelMu.Lock()
			activeTunnel = cleanup
			tunnelMu.Unlock()
			w.Dispatch(func() {
				unbindConnectForm(w)
				w.SetTitle("Kairos — " + target)
				w.Init(controlBannerInit(target + " (vía túnel SSH)"))
				w.Navigate(schemeFor(localListen) + "://" + localListen + "/panel")
			})
			return "conectando…", nil
		default:
			return "", fmt.Errorf("modo desconocido: %q", mode)
		}
	})

	w.Navigate(connectFormDataURL())
	w.Run()
}

// unbindConnectForm — hallazgo real de revisión automática ("privileged native binding exposed to
// remote/untrusted web content"): webview.Bind queda activo durante toda la vida de la ventana,
// sin importar a qué página se navegue después — si [target] no es de verdad un gateway de
// Kairos (ej. otro programa en esa IP, o alguien que responde /healthz falso en la LAN), esa
// página remota podría seguir llamando a `window.kairosScan()`/`window.kairosConnect()` desde su
// propio JavaScript después de la navegación, abriendo túneles SSH o escaneando la red local a
// pedido de un sitio en el que nunca debería haber confianza. Se desvinculan las DOS funciones
// apenas se navega a un destino real — después de esto, cualquier página que intente llamarlas
// solo encuentra un `ReferenceError`, sea la real o una impostora.
func unbindConnectForm(w webview.WebView) {
	_ = w.Unbind("kairosConnect")
	_ = w.Unbind("kairosScan")
}

// connectFormDataURL — el formulario real: IP/host, elegir Directo (misma red, con el pairing ya
// implementado) o SSH (usuario del módulo Remote), y un nombre opcional para el pairing. Pura
// HTML/CSS/JS inline, sin dependencias externas (mismo criterio que
// .claude/rules/kairos-html-mockups-en-ui-mockups.md para HTML hecho a mano en este proyecto).
func connectFormDataURL() string {
	return dataHTML(`<!doctype html><html><head><meta charset="utf-8"><style>
body{font-family:system-ui;background:#0d1117;color:#e6edf3;padding:28px;margin:0}
h2{margin-top:0;font-size:18px}
label{display:block;margin:14px 0 4px;font-size:13px;color:#8b949e}
input,select{width:100%;box-sizing:border-box;padding:8px;border-radius:6px;border:1px solid #30363d;background:#161b22;color:#e6edf3;font-size:14px}
button{margin-top:20px;width:100%;padding:10px;border-radius:6px;border:none;background:#1f6feb;color:#fff;font-size:14px;cursor:pointer}
button:disabled{opacity:.6;cursor:default}
button.secondary{margin-top:8px;background:#21262d;border:1px solid #30363d}
#err{color:#f85149;margin-top:12px;font-size:13px;min-height:1em}
#ssh-fields{display:none}
#scan-results{margin-top:8px;display:none}
#scan-results div{padding:7px 10px;border:1px solid #30363d;border-radius:6px;margin-top:6px;cursor:pointer;font-size:13px}
#scan-results div:hover{background:#161b22}
#scan-status{font-size:12px;color:#8b949e;margin-top:6px;min-height:1em}
</style></head><body>
<h2>Conectar a un teléfono con Kairos</h2>
<label>Modo</label>
<select id="mode">
<option value="directo">Directo (misma red/USB con pairing)</option>
<option value="ssh">Por SSH (cifrado, recomendado)</option>
</select>
<label>IP o host del teléfono</label>
<input id="host" placeholder="192.168.1.8">
<button id="scan" class="secondary">Buscar teléfonos en esta red</button>
<div id="scan-status"></div>
<div id="scan-results"></div>
<div id="ssh-fields">
<label>Usuario SSH (módulo Remote de Kairos)</label>
<input id="sshUser" placeholder="u0_a123">
</div>
<div id="name-field">
<label>Nombre para esta PC (lo ve la persona al aceptar)</label>
<input id="name" placeholder="PC de escritorio">
</div>
<button id="go">Conectar</button>
<div id="err"></div>
<script>
const mode=document.getElementById('mode'),sshFields=document.getElementById('ssh-fields'),nameField=document.getElementById('name-field');
mode.onchange=()=>{const ssh=mode.value==='ssh';sshFields.style.display=ssh?'block':'none';nameField.style.display=ssh?'none':'block'};
document.getElementById('scan').onclick=async()=>{
  const btn=document.getElementById('scan'),status=document.getElementById('scan-status'),results=document.getElementById('scan-results');
  btn.disabled=true;status.textContent='Buscando en la red local (hasta 20s)…';results.style.display='none';results.innerHTML='';
  try{
    const found=await window.kairosScan();
    if(!found||found.length===0){status.textContent='No se encontró ningún gateway de Kairos en esta red — probá escribiendo la IP a mano.'}
    else{
      status.textContent=found.length+' encontrado(s) — elegí uno:';
      results.style.display='block';
      for(const ip of found){
        const row=document.createElement('div');row.textContent=ip;
        row.onclick=()=>{document.getElementById('host').value=ip};
        results.appendChild(row);
      }
    }
  }catch(e){status.textContent='Error buscando: '+String(e&&e.message?e.message:e)}
  finally{btn.disabled=false}
};
document.getElementById('go').onclick=async()=>{
  const btn=document.getElementById('go'),err=document.getElementById('err');
  btn.disabled=true;err.textContent='';
  try{
    await window.kairosConnect(mode.value,document.getElementById('host').value,document.getElementById('sshUser').value,document.getElementById('name').value);
  }catch(e){err.textContent=String(e&&e.message?e.message:e);btn.disabled=false}
};
</script></body></html>`)
}

// hostPort separa y valida --listen: exige una IP literal (nunca un nombre de host) para que
// verifyGatewayProcess pueda comparar contra LocalAddr de la tabla TCP real sin ambigüedad
// (hallazgo de revisión automática: "reject hostnames").
func hostPort(listen string) (net.IP, uint16, error) {
	hostStr, portStr, err := net.SplitHostPort(listen)
	if err != nil {
		return nil, 0, fmt.Errorf("--listen inválido (%q): %w", listen, err)
	}
	ip := net.ParseIP(hostStr)
	if ip == nil {
		return nil, 0, fmt.Errorf("--listen inválido (%q): el host debe ser una IP literal, no un nombre", listen)
	}
	p, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil {
		return nil, 0, fmt.Errorf("--listen inválido (%q): puerto no numérico", listen)
	}
	return ip, uint16(p), nil
}

// schemeFor decide http/https según si [target] es loopback -- hallazgo real de seguridad
// ("TLS en el listener LAN -- el token viaja en texto plano"): server.EnsureSelfSignedCert()
// activa HTTPS automático en el lado kairos-gateway/Android cuando el bind NO es loopback (modo
// LAN real). target puede ser: (a) el propio subproceso embebido de ESTA PC (siempre loopback,
// nunca TLS), (b) un teléfono emparejado por USB vía adb forward (el target visto acá también
// es loopback -- el teléfono mismo sigue bindeado a 127.0.0.1, sin TLS, aunque llegue por otro
// transporte), o (c) un teléfono real en la LAN (IP no-loopback -- SÍ tiene TLS, porque su
// propio bind no es loopback). Decidir por la IP del target, no por "es un flujo remoto o
// local", es lo único que da el resultado correcto en los 3 casos.
func schemeFor(target string) string {
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		host = target
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		return "http"
	}
	return "https"
}

// insecureTLSClient — mismo criterio que internal/router.ScanLANGateways: el certificado es
// autofirmado, sin autoridad real que pueda haberlo emitido de antemano para una IP de LAN
// desconocida -- la identidad real la da el token Bearer al conectar, nunca el canal de
// transporte en sí. TLS acá es defensa en profundidad contra un sniffer pasivo en la misma red,
// no una PKI real -- InsecureSkipVerify es la única opción posible dado ese modelo de confianza.
func insecureTLSClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout:   timeout,
		Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}, //nolint:gosec
	}
}

func healthy(listen string) bool {
	client := insecureTLSClient(800 * time.Millisecond)
	resp, err := client.Get(schemeFor(listen) + "://" + listen + "/healthz")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}

func waitHealthy(listen string, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if healthy(listen) {
			return true
		}
		time.Sleep(200 * time.Millisecond)
	}
	return false
}

// watchHealth cierra la ventana si el gateway deja de responder mientras está abierta — sin esto,
// la verificación de arranque queda "congelada en el tiempo": el gateway podría morir y otro
// proceso ocupar el puerto después, con la ventana ya abierta sin volver a comprobar nada.
// watchIdentity — hallazgo de revisión automática (2026-10-26, "identity check not re-bound /
// port squatting after verification"): checkear solo /healthz no alcanza, porque algo distinto
// podría haber matado al gateway real y ocupado el puerto DESPUÉS de la verificación inicial, y
// la ventana seguiría abierta mostrando esa página nueva sin que nadie se entere. Re-verifica
// identidad de verdad en cada ciclo, con el mismo camino que se usó al abrir: fuerte (desafío
// HMAC) si tenemos [secret] propio, débil (tabla de procesos del SO) si no.
func watchIdentity(listen string, host net.IP, port uint16, secret string, w *webview.WebView, stop <-chan struct{}) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			var err error
			if secret != "" {
				err = verifyByChallenge(listen, secret)
			} else {
				err = verifyGatewayProcess(host, port)
			}
			if err != nil {
				(*w).Terminate()
				return
			}
		}
	}
}

// startGateway lanza kairos-gateway(.exe) desde el mismo directorio que este binario — el mismo
// par que ya instalan tools/build-gateway-installer-windows.ps1 / install-gateway-linux.sh. Le
// pasa un secreto de un solo uso por el entorno (nunca en el args — no debe aparecer en una lista
// de procesos) para poder desafiarlo después vía /healthz?challenge= y confirmar que la ventana
// termina hablando con ESE proceso exacto, no con otro que haya ocupado el puerto mientras tanto.
// startGateway devuelve el secreto de verificación generado y la ruta del archivo donde se
// escribió (0600) — quien llama borra ese archivo apenas confirma, vía [verifyByChallenge], que
// el gateway ya lo leyó (ver KDoc de cabecera: nunca pasa por el bloque de entorno del proceso).
func startGateway(listen, dir string) (secret, secretFile string, err error) {
	if dir == "" {
		dir, err = defaultGatewayDir()
		if err != nil {
			return "", "", fmt.Errorf("no se pudo resolver la carpeta de estado del gateway: %w", err)
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", "", fmt.Errorf("no se pudo crear %s: %w", dir, err)
	}
	bin, err := extractEmbeddedGateway(dir)
	if err != nil {
		return "", "", err
	}
	secretBytes := make([]byte, 32)
	if _, err := rand.Read(secretBytes); err != nil {
		return "", "", fmt.Errorf("no se pudo generar el secreto de verificación: %w", err)
	}
	secret = hex.EncodeToString(secretBytes)
	secretFile = filepath.Join(dir, "dashboard_verify.secret")
	if err := os.WriteFile(secretFile, []byte(secret), 0o600); err != nil {
		return "", "", fmt.Errorf("no se pudo escribir el secreto de verificación: %w", err)
	}
	// --open-browser=false explícito: el binario kairos-gateway.exe EXTRAÍDO acá por defecto
	// abre un navegador solo al arrancar en Windows (fs.Bool("open-browser", runtime.GOOS ==
	// "windows", ...) en cmd/kairos-gateway/main.go) -- ese default es correcto para alguien que
	// corre kairos-gateway.exe standalone sin ninguna ventana (headless), pero el dashboard YA
	// tiene su propia ventana nativa real. Bug real reportado por el usuario (
	// 2026-11-05): el dashboard seguía abriendo una pestaña de navegador al arrancar a pesar de
	// la regla kairos-gateway-installer-siempre-actualizado.md ("ningún .exe real distribuido
	// abre un navegador por su cuenta") -- esa regla ya cubría kairos-dashboard.exe en sí, pero
	// no el subproceso headless que arranca adentro, que seguía heredando el default de Windows.
	args := []string{"--listen", listen, "--dir", dir, "--open-browser=false"}
	cmd := exec.Command(bin, args...)
	cmd.SysProcAttr = hideConsoleAttr()
	cmd.Env = append(os.Environ(), "KAIROS_GATEWAY_ADMIN_SECRET_FILE="+secretFile)
	if err := cmd.Start(); err != nil {
		os.Remove(secretFile)
		return "", "", err
	}
	return secret, secretFile, nil
}

// extractEmbeddedAdb escribe el binario adb embebido (+ sus DLL en Windows, ver
// embed_adb_windows.go/embed_adb_other.go) a [dir] y devuelve la ruta al ejecutable — mismo
// criterio que extractEmbeddedGateway (sobreescribe siempre, nunca confía en una copia vieja en
// disco de una corrida anterior).
func extractEmbeddedAdb(dir string) (string, error) {
	bin := filepath.Join(dir, embeddedAdbName)
	if err := os.WriteFile(bin, embeddedAdbBin, 0o700); err != nil {
		return "", fmt.Errorf("no se pudo extraer %s: %w", embeddedAdbName, err)
	}
	if len(embeddedAdbWinApiDll) > 0 {
		if err := os.WriteFile(filepath.Join(dir, "AdbWinApi.dll"), embeddedAdbWinApiDll, 0o644); err != nil {
			return "", fmt.Errorf("no se pudo extraer AdbWinApi.dll: %w", err)
		}
	}
	if len(embeddedAdbWinUsbApiDll) > 0 {
		if err := os.WriteFile(filepath.Join(dir, "AdbWinUsbApi.dll"), embeddedAdbWinUsbApiDll, 0o644); err != nil {
			return "", fmt.Errorf("no se pudo extraer AdbWinUsbApi.dll: %w", err)
		}
	}
	return bin, nil
}

// runUsbGrantMode — "mini adb" propio del gateway (Alcance A, docs/arquitectura/
// PLAN_ADB_USB_GATEWAY_2026-10-31.md): detecta un teléfono Kairos conectado por USB y le otorga
// el mismo tipo de privilegio elevado (identidad shell, nunca root real) que ya existe por ADB
// inalámbrico — pero ejecutado DESDE la PC, porque un teléfono no puede ser host de ADB de sí
// mismo por cable (confirmado leyendo el propio Kairos, strings_remote.xml: "el comando se corre
// EN LA PC, no acá"). Ventana propia (mismo patrón que runConnectMode()) — 2026-10-31, segunda
// ronda: antes era CLI-only con un MessageBox, ahora es una pantalla real con "Buscar" + lista de
// dispositivos + "Activar" por fila, consistente con el resto del dashboard.
func runUsbGrantMode() {
	dir, err := defaultGatewayDir()
	if err != nil {
		showFatalError("Kairos Dashboard — ADB USB", "No se pudo resolver la carpeta de estado: "+err.Error())
		return
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		showFatalError("Kairos Dashboard — ADB USB", "No se pudo crear la carpeta de estado: "+err.Error())
		return
	}
	binPath, err := extractEmbeddedAdb(dir)
	if err != nil {
		showFatalError("Kairos Dashboard — ADB USB", err.Error())
		return
	}
	client := adb.New(binPath)
	if err := client.StartServer(); err != nil {
		showFatalError("Kairos Dashboard — ADB USB", "No se pudo arrancar el servidor adb: "+err.Error())
		return
	}

	w := webview.New(false)
	defer w.Destroy()
	w.SetTitle("Kairos — ADB por USB")
	w.SetSize(480, 520, webview.HintNone)

	type deviceOut struct {
		Serial string `json:"serial"`
		State  string `json:"state"`
	}
	_ = w.Bind("kairosUsbDevices", func() ([]deviceOut, error) {
		devices, err := client.Devices()
		if err != nil {
			return nil, err
		}
		out := make([]deviceOut, 0, len(devices))
		for _, d := range devices {
			out = append(out, deviceOut{Serial: d.Serial, State: d.State})
		}
		return out, nil
	})

	type grantOut struct {
		PhantomKillerDisabled bool     `json:"phantomKillerDisabled"`
		BatteryExempted       bool     `json:"batteryExempted"`
		RegistryMarked        bool     `json:"registryMarked"`
		Errors                []string `json:"errors"`
	}
	_ = w.Bind("kairosUsbGrant", func(serial string) (grantOut, error) {
		serial = strings.TrimSpace(serial)
		if serial == "" {
			return grantOut{}, fmt.Errorf("falta el serial del teléfono")
		}
		result := adb.GrantUsbPrivilege(client, serial)
		return grantOut{
			PhantomKillerDisabled: result.PhantomKillerDisabled,
			BatteryExempted:       result.BatteryExempted,
			RegistryMarked:        result.RegistryMarked,
			Errors:                result.Errors,
		}, nil
	})

	w.Navigate(usbGrantFormDataURL())
	w.Run()
}

// usbGrantFormDataURL — pura HTML/CSS/JS inline (mismo criterio que connectFormDataURL()): un
// botón "Buscar teléfono" que lista lo que ve `adb devices` ahora mismo, y un botón "Activar" por
// cada dispositivo autorizado (los no autorizados muestran el aviso de tocar "Permitir").
func usbGrantFormDataURL() string {
	return dataHTML(`<!doctype html><html><head><meta charset="utf-8"><style>
body{font-family:system-ui;background:#0d1117;color:#e6edf3;padding:24px;margin:0}
h2{margin-top:0;font-size:17px}
p.hint{color:#8b949e;font-size:13px;line-height:1.5}
button{background:#1f6feb;color:#fff;border:none;border-radius:8px;padding:10px 16px;font-size:14px;cursor:pointer;margin-bottom:16px}
button:disabled{opacity:.5;cursor:default}
.device{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:12px;margin-bottom:10px}
.device .serial{font-family:monospace;font-size:13px}
.device .state{font-size:12px;margin:4px 0;color:#8b949e}
.device .state.ok{color:#3fb950}
.device .state.warn{color:#d29922}
.device button.grant{margin:8px 0 0 0;font-size:13px;padding:7px 12px}
.result{font-size:12px;line-height:1.6;margin-top:8px;white-space:pre-wrap}
.empty{color:#8b949e;font-size:13px}
</style></head><body>
<h2>ADB por USB</h2>
<p class="hint">Conectá el teléfono con un cable USB y activá "Depuración por USB" en Opciones de desarrollador (ver el tutorial dentro de Kairos, Config → Permisos) antes de buscar.</p>
<button id="scanBtn" onclick="scan()">Buscar teléfono</button>
<div id="list"></div>
<script>
function esc(s){return (s||'').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
async function scan(){
  const btn = document.getElementById('scanBtn'); btn.disabled = true; btn.textContent = 'Buscando…';
  const list = document.getElementById('list'); list.innerHTML = '';
  try {
    const devices = await window.kairosUsbDevices();
    if (!devices || devices.length === 0) {
      list.innerHTML = '<p class="empty">No se detectó ningún teléfono. Revisá el cable y que "Depuración por USB" esté activa.</p>';
    } else {
      for (const d of devices) {
        const div = document.createElement('div'); div.className = 'device';
        const authorized = d.state === 'device';
        div.innerHTML = '<div class="serial">' + esc(d.serial) + '</div>' +
          '<div class="state ' + (authorized ? 'ok' : 'warn') + '">' +
          (authorized ? 'Autorizado' : (d.state === 'unauthorized' ? 'Esperando que toques "Permitir" en el teléfono' : esc(d.state))) +
          '</div>';
        if (authorized) {
          const gbtn = document.createElement('button'); gbtn.className = 'grant'; gbtn.textContent = 'Activar';
          gbtn.onclick = () => grant(d.serial, div, gbtn);
          div.appendChild(gbtn);
        }
        list.appendChild(div);
      }
    }
  } catch (e) {
    list.innerHTML = '<p class="empty">Error: ' + esc(String(e)) + '</p>';
  } finally {
    btn.disabled = false; btn.textContent = 'Buscar teléfono';
  }
}
async function grant(serial, container, btn){
  btn.disabled = true; btn.textContent = 'Activando…';
  try {
    const r = await window.kairosUsbGrant(serial);
    const mark = ok => ok ? '✔' : '✘';
    let html = mark(r.phantomKillerDisabled) + ' Phantom process killer desactivado\n' +
      mark(r.batteryExempted) + ' Exento de optimización de batería\n' +
      mark(r.registryMarked) + ' Marca guardada en el teléfono';
    if (r.errors && r.errors.length) html += '\n\nErrores:\n' + r.errors.map(esc).join('\n');
    const res = document.createElement('div'); res.className = 'result'; res.textContent = html;
    container.appendChild(res);
  } catch (e) {
    const res = document.createElement('div'); res.className = 'result'; res.textContent = 'Error: ' + String(e);
    container.appendChild(res);
  } finally {
    btn.disabled = false; btn.textContent = 'Activar';
  }
}
scan();
</script></body></html>`)
}

// runDiscoverMode — vista consolidada de varios teléfonos Kairos a la vez (pedido explícito del
// usuario, ver .claude/rules/kairos-gateway-windows-dashboard-and-lan-discovery.md): combina el
// escaneo LAN (mismo router.ScanLANGateways ya usado por kairosScan en runConnectMode) con la
// lista de dispositivos USB (mismo adb.Client ya usado por runUsbGrantMode) en UNA sola tabla —
// antes había que abrir --connect para un teléfono por vez o --usb-grant por separado, sin forma
// de ver "estos son los que tengo ahora" de un vistazo.
func runDiscoverMode() {
	dir, err := defaultGatewayDir()
	if err != nil {
		showFatalError("Kairos Dashboard — Descubrir teléfonos", "No se pudo resolver la carpeta de estado: "+err.Error())
		return
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		showFatalError("Kairos Dashboard — Descubrir teléfonos", "No se pudo crear la carpeta de estado: "+err.Error())
		return
	}
	binPath, err := extractEmbeddedAdb(dir)
	if err != nil {
		showFatalError("Kairos Dashboard — Descubrir teléfonos", err.Error())
		return
	}
	adbClient := adb.New(binPath)
	_ = adbClient.StartServer() // best-effort — si falla, el escaneo USB simplemente no encuentra nada

	w := webview.New(false)
	defer w.Destroy()
	w.SetTitle("Kairos — Descubrir teléfonos")
	w.SetSize(520, 600, webview.HintNone)

	type lanEntry struct {
		Target string `json:"target"`
	}
	_ = w.Bind("kairosDiscoverLan", func() ([]lanEntry, error) {
		addrs, err := net.InterfaceAddrs()
		if err != nil {
			return nil, fmt.Errorf("no se pudieron leer las interfaces de red: %w", err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		found := router.ScanLANGateways(ctx, router.LocalLANIPs(addrs), "8973", 400*time.Millisecond)
		out := make([]lanEntry, 0, len(found))
		for _, t := range found {
			out = append(out, lanEntry{Target: t})
		}
		return out, nil
	})

	type usbEntry struct {
		Serial string `json:"serial"`
		State  string `json:"state"`
	}
	_ = w.Bind("kairosDiscoverUsb", func() ([]usbEntry, error) {
		devices, err := adbClient.Devices()
		if err != nil {
			return nil, err
		}
		out := make([]usbEntry, 0, len(devices))
		for _, d := range devices {
			out = append(out, usbEntry{Serial: d.Serial, State: d.State})
		}
		return out, nil
	})

	// path = "panel" (default) o "panel/screen" — ver pantalla de un 2do teléfono desde el
	// gateway, pedido explícito del usuario (ver AskUserQuestion 2026-10-31): reusa el
	// mecanismo YA FUNCIONANDO de /v1/screen/ws (ver docs/homelab/ESPEJO_PANTALLA_FUNCIONAL_
	// 2026-10-13.md, probado punta a punta) en vez del conector TCP experimental
	// (StartConnector/RespondToHandshake) — mismo emparejamiento, solo cambia a qué página del
	// panel remoto se navega después.
	connectTo := func(target, name, path string) (string, error) {
		if name = strings.TrimSpace(name); name == "" {
			if h, err := os.Hostname(); err == nil && h != "" {
				name = h
			} else {
				name = "PC de escritorio"
			}
		}
		token, err := ensurePaired(target, name)
		if err != nil {
			return "", err
		}
		w.Dispatch(func() {
			unbindDiscoverForm(w)
			w.SetTitle("Kairos — " + target)
			w.Init("if(location.origin===" + jsStringLiteral(schemeFor(target)+"://"+target) + "){sessionStorage.setItem('kx_token'," + jsStringLiteral(token) + ")}")
			w.Init(controlBannerInit(target))
			w.Navigate(schemeFor(target) + "://" + target + "/" + path)
		})
		return "conectando…", nil
	}
	_ = w.Bind("kairosDiscoverConnect", func(target, name string) (string, error) {
		return connectTo(target, name, "panel")
	})
	_ = w.Bind("kairosDiscoverConnectScreen", func(target, name string) (string, error) {
		return connectTo(target, name, "panel/screen")
	})

	type grantOut struct {
		PhantomKillerDisabled bool     `json:"phantomKillerDisabled"`
		BatteryExempted       bool     `json:"batteryExempted"`
		RegistryMarked        bool     `json:"registryMarked"`
		Errors                []string `json:"errors"`
	}
	_ = w.Bind("kairosDiscoverUsbGrant", func(serial string) (grantOut, error) {
		serial = strings.TrimSpace(serial)
		if serial == "" {
			return grantOut{}, fmt.Errorf("falta el serial del teléfono")
		}
		result := adb.GrantUsbPrivilege(adbClient, serial)
		return grantOut{
			PhantomKillerDisabled: result.PhantomKillerDisabled,
			BatteryExempted:       result.BatteryExempted,
			RegistryMarked:        result.RegistryMarked,
			Errors:                result.Errors,
		}, nil
	})

	w.Navigate(discoverFormDataURL())
	w.Run()
}

// unbindDiscoverForm — mismo criterio de seguridad que unbindConnectForm() (hallazgo de revisión
// automática: una vez que se navega a un teléfono real, esa página NO debe poder seguir llamando
// a las funciones privilegiadas de esta ventana, sea el teléfono real o un impostor en esa IP).
func unbindDiscoverForm(w webview.WebView) {
	_ = w.Unbind("kairosDiscoverConnect")
	_ = w.Unbind("kairosDiscoverConnectScreen")
	_ = w.Unbind("kairosDiscoverLan")
	_ = w.Unbind("kairosDiscoverUsb")
	_ = w.Unbind("kairosDiscoverUsbGrant")
}

// discoverFormDataURL — tabla consolidada: una sección "En la red local" (botón Conectar por
// fila) y otra "Por USB" (botón Activar por fila, solo si está autorizado) — mismo criterio de
// HTML/CSS/JS inline sin dependencias que el resto del dashboard.
func discoverFormDataURL() string {
	return dataHTML(`<!doctype html><html><head><meta charset="utf-8"><style>
body{font-family:system-ui;background:#0d1117;color:#e6edf3;padding:24px;margin:0}
h2{margin-top:0;font-size:17px}
h3{font-size:13px;color:#8b949e;text-transform:uppercase;letter-spacing:.04em;margin:22px 0 8px}
p.hint{color:#8b949e;font-size:13px;line-height:1.5}
button{background:#1f6feb;color:#fff;border:none;border-radius:8px;padding:10px 16px;font-size:14px;cursor:pointer}
button:disabled{opacity:.5;cursor:default}
.device{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:12px;margin-bottom:10px;display:flex;justify-content:space-between;align-items:center;gap:10px}
.device .info{min-width:0}
.device .serial{font-family:monospace;font-size:13px;word-break:break-all}
.device .state{font-size:12px;margin:4px 0;color:#8b949e}
.device .state.ok{color:#3fb950}
.device .state.warn{color:#d29922}
.device button{font-size:13px;padding:7px 12px;flex:none}
.result{font-size:12px;line-height:1.6;margin-top:8px;white-space:pre-wrap}
.empty{color:#8b949e;font-size:13px}
</style></head><body>
<h2>Descubrir teléfonos Kairos</h2>
<p class="hint">Busca teléfonos con Kairos en esta misma red local y por cable USB — todos los que encuentre aparecen abajo para conectar o activar de una.</p>
<button id="scanBtn" onclick="scanAll()">Buscar</button>
<h3>En la red local</h3>
<div id="lanList"><p class="empty">Tocá "Buscar" para empezar.</p></div>
<h3>Por USB</h3>
<div id="usbList"><p class="empty">Tocá "Buscar" para empezar.</p></div>
<script>
function esc(s){return (s||'').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
async function scanAll(){
  const btn = document.getElementById('scanBtn'); btn.disabled = true; btn.textContent = 'Buscando…';
  await Promise.all([scanLan(), scanUsb()]);
  btn.disabled = false; btn.textContent = 'Buscar';
}
async function scanLan(){
  const list = document.getElementById('lanList'); list.innerHTML = '';
  try {
    const found = await window.kairosDiscoverLan();
    if (!found || found.length === 0) { list.innerHTML = '<p class="empty">No se encontró ningún teléfono en la red local.<br>Revisá en el teléfono: Homelab → Gateway → el gateway debe estar <b>Iniciado</b> Y con el acceso puesto en <b>Red local</b> (no "Solo este teléfono") -- por defecto el gateway solo escucha dentro del propio teléfono, nadie más en la red lo puede ver hasta que cambiés a Red local. La PC y el teléfono deben estar en la MISMA red Wi-Fi.</p>'; return; }
    for (const entry of found) {
      const div = document.createElement('div'); div.className = 'device';
      div.innerHTML = '<div class="info"><div class="serial">' + esc(entry.target) + '</div><div class="state">Gateway Kairos en la LAN</div></div>';
      const btns = document.createElement('div'); btns.style.display = 'flex'; btns.style.gap = '6px';
      const btn = document.createElement('button'); btn.textContent = 'Conectar';
      btn.onclick = () => connectLan(entry.target, div, btn, false);
      const btnScreen = document.createElement('button'); btnScreen.textContent = 'Ver pantalla';
      btnScreen.onclick = () => connectLan(entry.target, div, btnScreen, true);
      btns.appendChild(btn); btns.appendChild(btnScreen);
      div.appendChild(btns);
      list.appendChild(div);
    }
  } catch (e) {
    list.innerHTML = '<p class="empty">Error: ' + esc(String(e)) + '</p>';
  }
}
async function connectLan(target, container, btn, viewScreen){
  const original = btn.textContent;
  btn.disabled = true; btn.textContent = viewScreen ? 'Abriendo…' : 'Conectando…';
  try {
    if (viewScreen) await window.kairosDiscoverConnectScreen(target, '');
    else await window.kairosDiscoverConnect(target, '');
  } catch (e) {
    const res = document.createElement('div'); res.className = 'result'; res.textContent = 'Error: ' + String(e);
    container.appendChild(res);
    btn.disabled = false; btn.textContent = original;
  }
}
async function scanUsb(){
  const list = document.getElementById('usbList'); list.innerHTML = '';
  try {
    const devices = await window.kairosDiscoverUsb();
    if (!devices || devices.length === 0) { list.innerHTML = '<p class="empty">No se detectó ningún teléfono por USB.</p>'; return; }
    for (const d of devices) {
      const authorized = d.state === 'device';
      const div = document.createElement('div'); div.className = 'device';
      div.innerHTML = '<div class="info"><div class="serial">' + esc(d.serial) + '</div><div class="state ' + (authorized?'ok':'warn') + '">' +
        (authorized ? 'Autorizado' : (d.state === 'unauthorized' ? 'Esperando "Permitir" en el teléfono' : esc(d.state))) + '</div></div>';
      if (authorized) {
        const btn = document.createElement('button'); btn.textContent = 'Activar';
        btn.onclick = () => activateUsb(d.serial, div, btn);
        div.appendChild(btn);
      }
      list.appendChild(div);
    }
  } catch (e) {
    list.innerHTML = '<p class="empty">Error: ' + esc(String(e)) + '</p>';
  }
}
async function activateUsb(serial, container, btn){
  btn.disabled = true; btn.textContent = 'Activando…';
  try {
    const r = await window.kairosDiscoverUsbGrant(serial);
    const mark = ok => ok ? '✔' : '✘';
    let html = mark(r.phantomKillerDisabled) + ' Phantom process killer desactivado\n' +
      mark(r.batteryExempted) + ' Exento de optimización de batería\n' +
      mark(r.registryMarked) + ' Marca guardada en el teléfono';
    if (r.errors && r.errors.length) html += '\n\nErrores:\n' + r.errors.map(esc).join('\n');
    const res = document.createElement('div'); res.className = 'result'; res.textContent = html;
    container.appendChild(res);
  } catch (e) {
    const res = document.createElement('div'); res.className = 'result'; res.textContent = 'Error: ' + String(e);
    container.appendChild(res);
  } finally {
    btn.disabled = false; btn.textContent = 'Activar';
  }
}
scanAll();
</script></body></html>`)
}

// extractEmbeddedGateway escribe los bytes embebidos (ver embed_windows.go/embed_other.go) a
// [dir] y devuelve la ruta — siempre sobreescribe para que la copia en disco nunca quede
// desincronizada de lo que ESTE binario de kairos-dashboard realmente lleva adentro (ej. tras
// actualizar kairos-dashboard.exe a una versión más nueva, que podría llevar un gateway distinto
// al que había quedado extraído de una corrida anterior).
func extractEmbeddedGateway(dir string) (string, error) {
	bin := filepath.Join(dir, embeddedGatewayName)
	if err := os.WriteFile(bin, embeddedGateway, 0o700); err != nil {
		return "", fmt.Errorf("no se pudo extraer %s: %w", embeddedGatewayName, err)
	}
	return bin, nil
}

// defaultGatewayDir — misma lógica que defaultDir() en cmd/kairos-gateway/main.go, duplicada
// acá a propósito: el dashboard necesita saber la carpeta ANTES de arrancar el gateway (para
// escribir el archivo del secreto ahí), no puede preguntárselo al proceso que todavía no existe.
// defaultGatewayDir — ver el mismo comentario en cmd/kairos-gateway/main.go:defaultDir — misma
// carpeta real (%LOCALAPPDATA%\KairosGateway en Windows), para que el portable y el gateway que
// extrae no diverjan de dónde cree la app Android/CLI que vive el estado.
func defaultGatewayDir() (string, error) {
	if d := os.Getenv("KAIROS_GATEWAY_DIR"); d != "" {
		return d, nil
	}
	if runtime.GOOS == "windows" {
		if local := os.Getenv("LOCALAPPDATA"); local != "" {
			return filepath.Join(local, "KairosGateway"), nil
		}
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".kairos_gateway"), nil
}

// verifyByChallenge — mismo mecanismo que GatewayAdminClient.kt (gatewayIsGenuine()): un desafío
// aleatorio, el gateway responde proof = HMAC-SHA256(secreto, desafío); solo el proceso que
// conoce el secreto puede producirlo. No se puede falsificar observando la red (el desafío
// cambia cada vez) ni ocupando el puerto después del chequeo (el secreto nunca viajó por ahí).
func verifyByChallenge(listen, secret string) error {
	nonce := make([]byte, 16)
	if _, err := rand.Read(nonce); err != nil {
		return fmt.Errorf("no se pudo generar el desafío: %w", err)
	}
	challenge := hex.EncodeToString(nonce)
	client := insecureTLSClient(2 * time.Second)
	resp, err := client.Get(schemeFor(listen) + "://" + listen + "/healthz?challenge=" + challenge)
	if err != nil {
		return fmt.Errorf("no se pudo contactar al gateway para verificarlo: %w", err)
	}
	defer resp.Body.Close()
	var out struct {
		Proof string `json:"proof"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return fmt.Errorf("respuesta inválida del gateway al verificarlo: %w", err)
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(challenge))
	expected := hex.EncodeToString(mac.Sum(nil))
	if subtle.ConstantTimeCompare([]byte(expected), []byte(out.Proof)) != 1 {
		return fmt.Errorf("el gateway que responde en %s no pudo probar que es el que arrancamos — por seguridad no se abre la ventana", listen)
	}
	return nil
}

// openInSystemBrowser — mismo mecanismo que openBrowser() de cmd/kairos-gateway/main.go (no
// compartido entre los dos binarios a propósito, son paquetes "main" separados — mismo criterio
// que el resto de helpers duplicados de este archivo, ej. hideConsoleAttr). HideWindow en el
// camino de Windows: "cmd /c start" abre su propia consola un instante si no se oculta, el
// mismo problema que [hideConsoleAttr] ya resuelve para los otros procesos hijos.
func openInSystemBrowser(url string) error {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("cmd", "/c", "start", "", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	cmd.SysProcAttr = hideConsoleAttr()
	return cmd.Start()
}
