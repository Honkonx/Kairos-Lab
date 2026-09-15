# APP_SCREENS.md — Referencia de pantallas de Kairos

> Qué es y qué hace cada pantalla de la app: propósito, controles principales, y qué
> módulos/funciones expone. El detalle línea-por-línea de cada módulo (permisos, instalación,
> opciones, detección) vive en `docs/modulos/<MODULO>.md` — esto es la vista de pantalla/UI.

## 1. Wizard (primer arranque)

**Archivos:** `app/src/main/java/com/termux/app/wizard/WizardActivity.java` (host, `ViewPager2`
sin swipe) + `WizardPagerAdapter.kt` + varios `Wizard*Fragment.kt`, uno por pantalla.

Se muestra la primera vez que se abre la app (mientras `~/.kairos_ready` no exista). Cada paso
es una pantalla independiente:

0. **`WizardWelcomeFragment`** — bienvenida + resumen, botón "Comenzar".
1. **`WizardPermissionsFragment`** — permisos de almacenamiento (`MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`,
   obligatorio) y notificaciones (opcional, Android 13+). "Continuar" queda deshabilitado hasta
   que ambos se resuelven.
2. **`WizardPhantomProcessFragment`** — quitar el límite de procesos fantasma de
   Android 12+, con 3 métodos (mismo motor que el diagnóstico de Monitor,
   `PhantomProcessKillerHelper.kt`): **auto-detección con nmap** primero (recomendada, menos
   datos manuales — pide puerto de emparejamiento + código, detecta el puerto de conexión solo;
   si falla, recomienda el método manual), **código y puerto manual** segundo, **tutorial 100%
   manual** tercero. No bloquea el avance del wizard.
3. **`WizardBatteryFragment`** — botón "Quitar restricciones"
   (`BatteryRestrictionHelper.requestDisableBatteryRestrictions()`, puede abrir 2 pantallas de
   sistema seguidas — se avisa de entrada). No bloquea el avance.
4. **`WizardInstallFragment`** — bootstrap de Termux + rootfs (opcional) + `kairos.sh`, con
   progreso en vivo. Es la única pantalla que no se puede abandonar con "atrás" (proceso en
   curso). El texto de rootfs distingue 2 casos: **"Extrayendo rootfs"** si está embebido en el
   APK (`RootfsInstaller.isEmbedded()`, sin red) o **"Descargando e instalando rootfs"** si hay
   que bajarlo de una Release — si ninguno de los dos funciona, aparece la ventana "Rootfs no
   disponible" preguntando si usar la instalación clásica (paquete por paquete).
5. **`WizardCheckFragment`** (última, opcional) — "Comprobar y actualizar" paquetes u "Omitir",
   ambos terminan el wizard y navegan a `TermuxActivity`.

Dentro de la pantalla de instalación se listan 11 pasos numerados con un círculo de estado
(pendiente/en progreso/completado) y una barra de progreso: verificando permisos, actualizando
Termux, instalando paquetes core, compiladores, glibc, multimedia, actualizando pip, instalando
npm globales, configurando tema, creando estructura, finalizando.

Secuencia real de fondo:
1. `TermuxInstaller.setupBootstrapIfNeeded()` — descomprime el bootstrap base de Termux
   (busybox/bash/coreutils/apt/dpkg). Idempotente: si `$PREFIX` ya existe y no está vacío, no
   hace nada.
2. `ensureBootstrapSecondStage()` — dispara una shell de login desechable (`bash -l -c true`)
   para forzar el "second stage" de postinst de Termux (busybox/coreutils/npm/openssh/
   proot-distro/python-pip/termux-exec/etc.), que de otra forma solo se dispara al abrir una
   shell de login real.
3. `KairosBootstrap.extractAssetsSync()` — copia los scripts de `assets/scripts/` a
   `~/scripts/install/` y `~/scripts/kairos.sh`/`~/kairos_manager.py`.
4. `installRootfsThenContinue()` — rootfs embebido o descargado; si falla del todo, pregunta si
   usar la instalación clásica.
5. `runKairosSetup()` — corre `~/scripts/kairos.sh --silent`, parseando líneas
   `[STEP] n/total mensaje` / `[OK]` / `[WARN]` / `[ERROR]` de su stdout para actualizar la UI
   paso a paso.
6. Al completar con éxito (`kairos.sh` termina con exit 0 y crea `~/.kairos_ready`), pasa a la
   pantalla de comprobación de paquetes.

Si `kairos.sh` falla, se muestra un botón "Reintentar" que repite los pasos 2-5 (no repite el
bootstrap base, que ya debería estar hecho). El bootstrap tiene un guard de concurrencia
(`sBootstrapLock`/`sBootstrapInProgress`) para evitar que dos llamadas simultáneas corran la
misma extracción en paralelo sobre el mismo directorio.

## 2. Módulos (tab principal)

**Archivos:** `ModulesFragment.kt`, `ModuleListAdapter.kt`, `item_module_row.xml`, `BottomSheetInstalacion.kt`, `ModuleController.kt`.

**Header de estadísticas:** instalados (conteo), activos (conteo), RAM usada/total (leído de
`/proc/meminfo`, refrescado cada 5s), botón "↻ Actualizar" (hace `git -C ~/termuxapp pull` para
autoactualizar el propio repo de la app).

**Lista de módulos:** la lista principal muestra **únicamente los módulos instalados**
(`ModulesFragment.pollStatus()` filtra por `ModuleInstalled.isInstalled()`, que mira el registry
`~/.android_server_registry` o el binario real con `BINARY_FALLBACK`). `python` siempre aparece
(el wizard lo instala y `kairos.sh` lo registra). Si no hay ningún módulo instalado, se muestra
un **estado vacío** (`modules_empty`) con botón "Ir a Plugins →" que navega a la Tienda
(`TermuxActivity.openPlugins()`). El catálogo completo se ve en la **Tienda** (menú Más →
Plugins). Cada fila muestra icono, nombre, subtítulo de estado, y:
- Si el módulo tiene servidor real (ollama, n8n, openclaw, opencode, remote, db, llamaserver):
  un `SwitchCompat` que inicia/detiene el proceso real.
- Si no tiene switch (python, claude, codex, antigravity, hermes, expo): sin switch, solo
  subtítulo de estado.
- Un chevron "›" siempre visible indicando que toda la fila es tocable.
- **Badge de estado superpuesto en el ícono**: círculo chico de color en la esquina
  inferior-derecha (verde=corriendo, gris=instalado y detenido, etc., vía
  `ModuleRowRenderer.bindStatusBadge()`/`statusBadgeColor()`), adicional al texto de estado —
  no lo reemplaza. Mismo mecanismo reusado en la Tienda (`PluginListAdapter`).

Tocar la fila: si no está instalado → abre la hoja de instalación (`BottomSheetInstalacion`); si
está instalado → navega a la pantalla de detalle del módulo.

El estado se recalcula cada 5s (`pollStatus()`): lee `~/.android_server_registry` (fuente real,
escrita por los scripts bash) para saber "¿instalado alguna vez?", y
`ModuleController.isRunning()` (tmux has-session, o pgrep para módulos sin tmux) para saber
"¿corriendo ahora?".

**Hoja de instalación (`BottomSheetInstalacion`):** muestra icono/nombre/descripción/chips
(tamaño, tipo, puerto, tiempo estimado), selector de variante si aplica (ollama: GPU/estándar;
claude: nativo/legacy; n8n: proot/udocker), botón "▼ Instalar" (o "Cambiar método" cuando se
abre en modo forzado desde la Tienda). Al tocar instalar: spinner + "Instalando…"/"Cambiando
método…" (sin output crudo de pkg/apt en pantalla) mientras corre
`~/scripts/install/<id>.sh --silent` vía `ProcessBuilder`; el log completo se escribe a
`~/kairos_logs/install_<id>.log`. Si requiere proot y no está instalado, el botón cambia a
"Instalar proot primero" y queda deshabilitado.

## 3. Pantallas de detalle de módulo

Todas extienden `BaseModuleFragment.kt`, que provee: header con botón volver + nombre,
`addCard(title) { ... }` (tarjeta con título opcional), `infoRow(key, value)`,
`actionButton(text, style, onClick)` (estilos PRIMARY/DANGER/GHOST), `pill(text, isActive)`,
`divider()`, `launchTerminalCommand(cmd)` / `startModuleService()` / `stopModuleService()` /
`isModuleRunning()` / `toast()`. Componentes de fila para paneles de opciones (ver
`docs/arquitectura/APP_SPEC.md` § Sistema de diseño para la tabla completa con ejemplos):
`dropdownSwitchRow()` (elegir 1 de N + switch que bloquea el dropdown mientras está ON, ej. n8n
local/Cloudflare), `switchRow()` (encendido/apagado simple), `dropdownRow()` (elegir 1 de N sin
switch). Todas verifican `isModuleInstalled()` (lee el flag de instalación del registry real) al
entrar — si no está instalado, muestran una pantalla de "Módulo no instalado" con botón volver,
en vez del contenido normal.

| Módulo | Info mostrada | Acciones reales |
|---|---|---|
| **Ollama** | proceso, puerto :11434, versión, modelo activo | Iniciar/Reiniciar; "Abrir Chat IA" navega al tab Chat; "Descargar modelo" navega a `ModelsFragment` (lista real vía `models-list`, tocar un modelo abre detalle/eliminar, botón para descargar uno nuevo vía `models-pull`); "Parámetros de inferencia" navega a `OllamaConfigFragment` (carga/guarda parámetros reales vía `config-get/set/reset`, usados por el chat) |
| **n8n** | entorno (proot), versión, URL túnel, estado | Iniciar/Detener reales; "Abrir interfaz web" (inicia si hace falta, luego WebView); "Ver URL del túnel"; "Ver logs"/"Backup"/"Actualizar" (cada uno abre una sesión de terminal) |
| **OpenClaw** | variante, versión, gateway, token, modelo activo | Iniciar/Detener/Reiniciar gateway reales; Ver logs; abrir interfaz web (start-if-needed); TUI (`openclaw tui`); Onboarding (`openclaw onboard`); Reinstalar/actualizar; mostrar URL con token, proveedor IA/modelo |
| **OpenCode** | variante, versión, "Web server" pill puerto 3000 | TUI en terminal (`opencode`); servidor web (start-if-needed + WebView); Detener servidor; Reinstalar; Importar/Sincronizar y gestionar proyectos |
| **Claude Code** | método, versión, estado | Abrir en terminal (`claude`); Abrir en proyecto/Gestionar proyectos (lista proyectos reales y abre `claude` con `cd` al proyecto elegido); Reinstalar/cambiar método |
| **Codex CLI** | canal, versión, estado | Abrir en terminal (`codex`); `codex login`; Reinstalar/cambiar canal |
| **Antigravity CLI** | método, versión, estado | Abrir en terminal (`agy`); Reinstalar |
| **Python** | versión, pip | Ver versión/info, Abrir REPL (`python3`), Instalar paquete (pip, con diálogo de texto), Listar paquetes, Ejecutar script .py |
| **Expo** | versión EAS CLI, Node, usuario expo.dev, proyecto activo | Build preview/producción, Ver builds, Login (`eas login` en terminal), Info, Configurar proyecto activo, Git push |
| **Remote** | SSH/IP/Usuario/Conexiones, Tunnel — refrescados cada 5s | Iniciar/Detener SSH, Info de conexión, Agregar clave pública, Cambiar contraseña, Iniciar/Detener tunnel Cloudflare, Configurar token CF, Cómo conectarse |
| **Hermes** | versión, gateway, modelo activo | Abrir TUI (`hermes`), Wizard completo (`hermes setup`), Comandos-referencia, Configurar proveedor IA / Usar Ollama local, Estado/diagnóstico, Actualizar/Instalar-reinstalar. `HermesGatewayFragment` (Iniciar/Detener/Ver estado/Ver logs) |

**`DbFragment`** (módulo "Base de Datos" — ver `docs/modulos/base-de-datos.md`): card ESTADO (MySQL/MariaDB
y PostgreSQL con verificación en vivo + versiones del registry, SQLite con su propia versión),
card SERVIDORES (▶ Iniciar / ■ Detener por servidor), card SQLITE con listar BDs en `~`, abrir BD
interactivo con el CLI `sqlite3` en terminal, ver tablas, BD de n8n, exportar a CSV, crear BD
vacía, query SQL — usando `android.database.sqlite.SQLiteDatabase` directo. El switch del módulo
en la lista arranca/detiene ambos servidores juntos.

**`LogsFragment`**: visor de logs con búsqueda/filtro en vivo y coloreado por nivel
(`[OK]`/`[INFO]` azul, `[WARN]` ámbar, `[ERROR]` rojo, "✓"/"success" verde). Recibe una ruta de
archivo por argumento y la lee con `BufferedReader` (snapshot al abrir, no sigue el archivo en
vivo). El botón "Ver logs" de OpenClaw navega acá; el de n8n adjunta una sesión tmux en vivo
directo desde la terminal, ya que no es un archivo estático.

## 4. Monitor (tab)

**`MonitorFragment`**: estado en vivo de los módulos con proceso (ollama/n8n/openclaw/opencode/
remote, vía `ModuleController.isRunning()`), conectividad de red (tipo wifi/datos/ethernet +
validación de internet, vía `ConnectivityManager` nativo de Android), conteo de paquetes de
Termux instalados y de paquetes pip. Refresca módulos/red cada 5s; los conteos de paquetes se
cargan una vez al entrar.

Incluye una sección **"DISPOSITIVO"** con anillo de RAM (`Canvas`/`Paint` sobre
`/proc/meminfo`), anillo de almacenamiento (`StatFs` sobre `Environment.getDataDirectory()` —
si falta el permiso "todos los archivos", la card se vuelve tocable y dispara la configuración
de almacenamiento con polling hasta que se concede), e info de dispositivo (IP local, uptime,
versión de API Android, arquitectura ABI).

**Sección DIAGNÓSTICO — phantom process killer** (ver `docs/modulos/PHANTOM_PROCESS_KILLER.md`
para el detalle completo): fila con estado ("Android puede matar módulos en segundo plano..." /
"Desactivado y verificado en este dispositivo") y botón "Desactivar"/"Volver a aplicar". Al
tocar: intento silencioso vía `su` (si el dispositivo está rooteado); si falla, diálogo con 3
vías — **(a) Configurar automáticamente (sin PC)**: guiado de 3 pasos vía Depuración
inalámbrica (ADB), pide puerto de emparejamiento/código/puerto de conexión, aplica los ajustes
del sistema necesarios y comandos de supervivencia en segundo plano, con verificación real
antes de confirmar éxito; **(b) Auto-detectar puerto (beta)**: mismo flujo pero solo pide
puerto de emparejamiento+código, detecta el puerto de conexión con `nmap`; **(c) Ver tutorial
manual**: solo texto + comandos copiables, sin automatizar nada. Ninguna vía marca éxito sin
verificación real (los ajustes se releen después de aplicar).

**`ModelsFragment`** y **`OllamaConfigFragment`**: ver tabla de Ollama arriba. `ModelsFragment`
tiene lista real de modelos instalados vía `models-list`/`models-pull`/`models-delete` más un
catálogo curado de modelos para descargar con un toque (qwen2.5, gemma2, llama3.2 en varios
tamaños), con velocidad/ETA real en vivo durante la descarga (streaming de la API de Ollama).
`OllamaConfigFragment` carga/guarda parámetros de inferencia reales, consumidos por el chat.

## 5. Chat IA (tab)

**Archivo:** `ChatFragment.kt`.

Chat directo contra la API de Ollama (`http://127.0.0.1:11434`), sin pasar por ningún módulo
intermedio. Al entrar, hace un `GET` a esa URL (timeout 2s) para decidir si mostrar la interfaz
de chat o un overlay de "Ollama inactivo". Selector de modelo (popup menu). Envía mensajes vía
`POST /api/generate` con `stream: true`, parseando cada línea NDJSON de la respuesta y
agregando el texto incrementalmente a la burbuja del asistente. Botón cancelar (interrumpe el
hilo de la petición), botón limpiar historial, contador de mensajes, barra de error.

## 6. Config / Ajustes (tab)

**Archivo:** `ConfigFragment.kt`.

Sección "General": switch "Auto-iniciar módulos" — al ABRIR Kairos (no al encender el
dispositivo, no hay receiver de arranque del sistema), `TermuxActivity.onCreate()` llama
`ModuleController.autoStartEligibleModules()`, que arranca cualquier módulo con switch que ya
esté instalado pero detenido; fila "Optimización batería"; switch "Notificaciones de módulos
caídos" — el loop de polling de `ModulesFragment` detecta transiciones RUNNING→INSTALLED_STOPPED
y dispara una notificación local si el switch está activo.

Sección "Info": Arquitectura (real), versión de Kairos.

Sección **"Variables de entorno"**: lista/agrega/elimina variables `export KEY=value` en un
bloque propio y aislado dentro de `~/.bashrc` (delimitado por marcadores dedicados) — aplican
solo a sesiones de terminal nuevas.

Botones: "Rerun setup" (borra `~/.kairos_ready` y relanza el wizard, sin tocar módulos ya
instalados); "Full backup" (tar.gz real de scripts/registry/.bashrc/configs de módulos a la
carpeta de descargas del dispositivo); "Reinstalar" (gate de confirmación "escribir REINSTALAR",
borra scripts/registry/checkpoints y relanza el wizard).

## 7. Terminal overlay

**Archivos:** `TermuxActivity.java` (`toggleTerminalOverlay()`, `openTerminalWithCommand()`), layout `activity_termux.xml`, `TerminalBridge.java`, `TermuxActivityRootView.java`.

Se abre/cierra con el FAB flotante sobre el bottom nav. La primera vez que se infla configura:
`TerminalView`, drawer de sesiones (lista + botón nueva sesión, con long-press para sesión con
nombre / modo failsafe), toolbar de teclas extra (ESC/TAB/CTRL/flechas, heredado de termux-app),
botón de alternar teclado, botón de "quick settings" (diálogo con `SeekBar` de tamaño de fuente,
aplicado en vivo y persistido en preferencias), y manejo de insets que respeta la barra de
sistema y el teclado en pantalla como padding.

Al mostrarse: oculta el bottom nav + FAB + fragment actual; adjunta la primera sesión existente
o crea una si no hay ninguna. Al ocultarse: los restaura.

**`openTerminalWithCommand(command)`**: método público usado por
`BaseModuleFragment.launchTerminalCommand()` — asegura que el overlay esté visible, crea una
sesión nueva y le escribe `command + "\n"` — así los botones "Abrir en terminal" de los módulos
CLI ejecutan el comando en vez de solo abrir una shell vacía.

### 7.1 Terminal — modo adaptado

Cuando el terminal se abre para un CLI específico (Claude, OpenCode, Hermes, etc., vía
`launchTerminalCommand`) en vez de la terminal genérica, la pantalla cambia a un modo visual
distinto:

- **Barra superior adaptada** (2 filas): título del módulo + una segunda fila con estado/versión
  reales en vivo — "● Activo · v1.18.3" o "○ Inactivo", refrescado en background thread.
- **Barra inferior**: si el módulo tiene un servidor real corriendo, muestra "⏺ escuchando en
  http://127.0.0.1:<puerto>" (poll TCP real cada 500ms) — oculta si no aplica. Envuelve también
  la toolbar de teclas extra heredada.
- **Sidebar deslizable con contenido propio** (distinto del drawer normal, que sigue siendo la
  lista de sesiones genérica): Minimizar, Cerrar sesión, Reiniciar módulo, Ver logs (abre un
  diálogo con contenido real de `~/kairos_logs/`).

Se aplica automáticamente a **todos** los módulos con CLI sin tocar cada Fragment individual —
es un mecanismo compartido en `TermuxActivity.java`/`activity_termux.xml`.

## 8. ModuleWebViewFragment (pantalla genérica de interfaz web)

**Archivo:** `ModuleWebViewFragment.kt`.

WebView programático (sin XML propio) usado por n8n, OpenClaw y OpenCode para mostrar su
interfaz local (`webviewUrl` de `modules.json`) dentro de la app en vez de exponer la terminal
cruda. Barra superior con botón volver + título + atrás/adelante del historial del WebView +
recargar; barra de dirección de solo lectura debajo (se actualiza también en navegación SPA
interna); barra de progreso de carga. El botón atrás del sistema navega el historial del WebView
primero, y solo cierra la pantalla cuando ya no hay historial. JavaScript y DOM storage
habilitados, zoom soportado. Cada fragment que la invoca intenta arrancar el servicio primero si
no está corriendo, antes de navegar acá. Sin pestañas múltiples ni historial persistente entre
sesiones — deliberadamente fuera de alcance, es un visor de un único servicio local por vez, no
un navegador general.

## 9. Archivos — CRUD + editor de texto

`FileManagerFragment.kt`: toque largo en una fila abre un menú (Copiar/Cortar/Renombrar/
Eliminar, más "Pegar aquí" si hay algo en el portapapeles) — portapapeles de un solo elemento,
"mover" es cortar+pegar. Copiar/cortar sin sobreescribir; eliminar pide confirmación; renombrar
valida nombre vacío/colisión. Tocar un archivo de texto (extensión conocida o sin extensión y
<256KB) navega a `EditorFragment` en vez de mostrar solo nombre+tamaño.

Funciones adicionales sobre el listado:

- **Selección múltiple**: modo de selección con barra de acciones batch (copiar, cortar,
  comprimir, eliminar varios archivos a la vez).
- **Marcadores**: cualquier carpeta se puede marcar como favorita (persistido), con un diálogo
  para saltar directo a cualquiera de las guardadas.
- **Historial de navegación** tipo navegador — botones atrás/adelante entre carpetas visitadas,
  no solo "subir un nivel".
- **Mostrar/ocultar archivos ocultos** con un toggle.
- **"Abrir con"** real — delega un archivo a otra app instalada capaz de manejarlo, vía un
  proveedor de contenido propio (no expone rutas de archivo crudas fuera de la app).

**`EditorFragment.kt`**: editor de texto real sobre `io.github.rosemoe.sora.widget.CodeEditor`
(librería `sora-editor`, LGPL-2.1). Carga el archivo, "Guardar" escribe los cambios, confirma
antes de salir si hay cambios sin guardar, rechaza archivos >5MB o inexistentes. Resaltado de
sintaxis real para 12 lenguajes (Java, Kotlin, Python, XML, HTML, JS, TS, Markdown, JSON, YAML,
shell, CSS) vía gramáticas TextMate, tema Darcula. Para archivos `.md` hay un botón de vista
previa que renderiza el Markdown (vía `Markwon`, ya presente como dependencia del proyecto) en
vez de mostrar el texto crudo. Sin soporte de archivos binarios.

## 10. Túnel (menú "Más")

**Archivo:** `TunnelFragment.kt`. Una tarjeta por módulo con puerto conocido (Ollama :11434, n8n
:5678, OpenClaw :18789, OpenCode :3000): estado del servicio (¿está corriendo el módulo?) y del
túnel (sin túnel / iniciando / activo con URL), botón "Iniciar túnel" (cloudflared quick-tunnel
anónimo, sin cuenta), botón "Con token" (túnel nombrado autenticado, pide un token de Cloudflare
vía diálogo), botón "Detener". Al iniciar, hace polling del estado cada 2s hasta ~14s esperando
a que la URL aparezca. Primera vez que se intenta iniciar un túnel en la sesión de la app:
diálogo de advertencia ("esto expone el módulo a internet sin autenticación").

Es una superficie de control unificada, independiente de los túneles propios que ya tienen n8n
y Remote/SSH desde sus propias pantallas de detalle.

## 11. Procesos (menú "Más")

**Archivo:** `ProcesosFragment.kt`. Lista los procesos gestionados por **pm2** (ya se instala
como parte del wizard). Corre `pm2 jlist` directo por `ProcessBuilder`, independiente de Python.
Distingue explícitamente "pm2 no está en el PATH" (mensaje de reinstalar) de "pm2 está pero el
comando falló" (posible daemon caído). No es un módulo de `modules.json` — se asume ya instalado
por el bootstrap.

## 12. IA Local (menú "Más", llama.cpp embebido)

**Archivo:** `LocalAIFragment.kt`. Gestión de modelos GGUF y parámetros para el motor de
inferencia embebido (`llama-engine/`, llama.cpp directo vía NDK — ver
`docs/ia-local/llama-cpp-local-engine.md`) — motor separado de Ollama a propósito: el chat usa un
único selector de modelo que lista tanto los modelos remotos de Ollama como los locales GGUF,
pero el motor real que responde se decide según cuál se eligió, nunca mezclados en la misma
conversación.

Catálogo curado de modelos con URL directa de HuggingFace (Qwen2.5-0.5B-Instruct,
SmolLM2-1.7B-Instruct, Qwen2.5-1.5B-Instruct, Llama-3.2-1B-Instruct, Gemma-2-2B-it,
Llama-3.2-3B-Instruct, cuantización Q4_K_M). Descarga con velocidad/ETA real en vivo (mismo
patrón que el catálogo de Ollama).

## 13. Nube (menú "Más")

**Archivo:** `NubeFragment.kt`. Convierte el dispositivo en una nube de almacenamiento mínima
tipo Drive/Mediafire, acotada a UNA carpeta fija (`$HOME/nube`) y accesible desde cualquier
navegador de la red local, no solo desde la app. Reusa dos piezas ya existentes: **`NubeServer`**
(servidor HTTP embebido, gateado por token, con validación de path traversal) y
**`TunnelManager`** (la misma lógica de cloudflared/ngrok que usa Túnel, apuntada al puerto de
`NubeServer`). La lista de archivos usa el mismo mecanismo de navegación in/out que Archivos pero
acotado a la carpeta "nube" — el usuario no puede navegar fuera de ahí ni ver el resto del
dispositivo desde acá.

## 14. GenericModuleFragment (detalle de módulo genérico)

**Archivo:** `app/src/main/java/com/termux/app/ui/GenericModuleFragment.kt`.

Detalle de módulo METADATA-DRIVEN: en vez de una clase por módulo, este fragment renderiza el
sub-menú completo de cualquier módulo desde `ModuleInfo` (modules.json) + el estado real del
registry. Es el fallback de `ModulesFragment.navigateToModuleDetail()`: los módulos con UI
específica real (Ollama, N8n, Claude, ...) conservan su fragment dedicado; cualquier módulo
nuevo del catálogo cae acá sin necesidad de código nuevo.

Tarjetas/acciones que dibuja automáticamente (según lo que el módulo declare en modules.json):

- **ESTADO** — ID, versión real del registry, puerto, tipo, ejecución (nativa/proot), sesión
  tmux, pill de TUI en terminal (si tiene comando de terminal) y pill de servidor
  corriendo/detenido.
- **QUÉ ES** — la descripción del catálogo.
- **CONTROL** (solo si tiene switch) — ▶ Iniciar / ■ Detener servidor.
- **🌐 Abrir interfaz web** (si declara URL) — navega a `ModuleWebViewFragment`.
- **⌨ Abrir en terminal** (si declara comando) — abre el CLI del módulo.
- **DETALLES** — tamaño y estimación de instalación si existen.
- **MANTENIMIENTO** — 🔄 Actualizar y 🗑 Desinstalar (con diálogo de confirmación que detiene el
  módulo y borra scripts/checkpoints/registry, sin tocar paquetes compartidos).

## 15. Plugins — Tienda de módulos (menú "Más")

**Archivo:** `PluginsFragment.kt` (+ `PluginListAdapter.kt`, layouts `fragment_plugins.xml` /
`item_plugin_row.xml`). Lista el **catálogo completo** de plugins/módulos desde `ModuleCatalog`
(bundled + cache + refresco remoto híbrido).

- **Orden**: recomendados arriba, ordenados por descargas desc; el resto después, también por
  descargas.
- **Búsqueda**: por nombre / id / descripción / categoría.
- **Badges**: ★ Recomendado (verde), arquitectura (bionic/glibc/proot/proot-distro con color por
  tipo), categoría, ⬇ descargas.
- **Estado real**: registry + `ModuleController.isRunning()` — poll al abrir la pantalla y tras
  cada acción.
- **Acciones por estado**: Instalar, Desinstalar (con confirmación), Abrir (navega al detalle).
- **"↻ Catálogo"**: refresca el catálogo remoto con fallback silencioso a cache/bundled.
- Arrancar/parar queda en la pantalla de detalle (mismo patrón que el resto de la app).

## 16. X11 — integrado dentro de Mini PC

X11 no tiene pantalla propia: toda su funcionalidad vive dentro de **`EntornoFragment.kt`** (tab
"Mini PC", ver sección 17), en la sección "NATIVO — X11 + escritorio directo":

- **Estado del servidor**: lee si el proceso del servidor X11 está vivo y muestra
  "● Servidor activo" / "○ Servidor detenido" + display `:1`. Refresh al volver a la pantalla.
- **🚀 Entrar en X11**: arranca el servicio X11 y abre el visor embebido (basado en el fork de
  termux-x11, ver `docs/x11/x11-embedded-architecture.md`).
- **⚙ Configuración de X11**: abre las preferencias originales de termux-x11 con modo de
  resolución (nativa/escala/exacta/custom), escala de display, densidad, estirar, orientación
  forzada horizontal/vertical, fullscreen, ocultar notch, PiP, teclado extra, modo de touch,
  sensibilidad y más. Los cambios aplican en vivo.
- **✕ Cerrar servidor X11**: confirma → cierra el visor y detiene el servicio X11.
- **Back del visor = diálogo Minimizar/Cerrar**: el botón atrás del visor muestra un diálogo con
  "Minimizar" (el servidor sigue corriendo) y "Cerrar servidor X11".

## 17. Mini PC (Entorno) — tab principal

**Archivo:** `EntornoFragment.kt` (extiende `BaseModuleFragment`, lógica en `EntornoNative.kt`).
Es un tab propio del `BottomNavigationView` (`nav_minipc`) — Entorno y X11 comparten el mismo
ecosistema de escritorio, así que se agrupan bajo la misma pantalla en vez de mantener X11 como
pantalla secundaria separada.

También accesible desde el catálogo de Módulos (con backstack real, a diferencia del tab raíz).
Es el punto de entrada al escritorio completo: distros Linux vía proot-distro, entornos gráficos
(XFCE/LXQt/MATE), y el servidor X11 embebido descrito en la sección 16.
