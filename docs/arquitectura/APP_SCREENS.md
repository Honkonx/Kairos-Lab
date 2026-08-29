# APP_SCREENS.md — Referencia de pantallas de Kairos

> Documentación factual de cada pantalla/interfaz de la app, basada en el código fuente real.
> Cuando un botón o campo no está conectado a lógica real, se marca explícitamente como "no
> implementado" en vez de omitirlo. El detalle línea-por-línea de cada módulo
> (permisos/instalación/opciones/detección) vive en `docs/modulos/<MODULO>.md` — esto acá es
> la vista de pantalla/UI, no reemplaza esos docs.

## 1. Wizard (primer arranque)

**Archivos:** `app/src/main/java/com/termux/app/wizard/WizardActivity.java` (host, `ViewPager2`
sin swipe) + `WizardPagerAdapter.kt` + 6 `Wizard*Fragment.kt`, uno por pantalla.

Se muestra la primera vez que se abre la app (mientras `~/.kairos_ready` no exista). Son 6
pantallas, cada una su propio paso:

0. **`WizardWelcomeFragment`** — bienvenida + resumen, botón "Comenzar".
1. **`WizardPermissionsFragment`** — permisos de almacenamiento
   (`MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, obligatorio) y notificaciones (opcional, Android
   13+). "Continuar" queda deshabilitado hasta que ambos se resuelven.
2. **`WizardPhantomProcessFragment`** — quitar el límite de procesos fantasma de Android 12+, 3
   métodos (mismo motor que el diagnóstico de Monitor, `PhantomProcessKillerHelper.kt`):
   **auto-detección con nmap** primero (recomendada, menos datos manuales — pide puerto de
   emparejamiento + código, detecta el puerto de conexión solo; si falla, recomienda el método
   manual), **código y puerto manual** segundo, **tutorial 100% manual** tercero. No bloquea —
   "Continuar" siempre está habilitado.
3. **`WizardBatteryFragment`** — botón "Quitar restricciones"
   (`BatteryRestrictionHelper.requestDisableBatteryRestrictions()`, puede abrir 2 pantallas de
   sistema seguidas — se avisa de entrada). No bloquea — "Siguiente" siempre está habilitado.
4. **`WizardInstallFragment`** — bootstrap de Termux + rootfs (opcional) + script de setup, con
   progreso en vivo. Es la única pantalla que no se puede abandonar con "atrás" (proceso en
   curso). El texto de rootfs distingue 2 casos fijos: **"Extrayendo rootfs"** si está embebido
   en el APK (`RootfsInstaller.isEmbedded()`, sin red) o **"Descargando e instalando rootfs"** si
   hay que bajarlo de la Release — si ninguno de los dos funciona, aparece la ventana "Rootfs no
   disponible" preguntando si usar la instalación clásica (paquete por paquete); esa ventana es
   exclusiva de esta pantalla.
5. **`WizardCheckFragment`** (última, opcional) — "Comprobar y actualizar" paquetes u "Omitir",
   ambos terminan el wizard y navegan a `TermuxActivity`.

Dentro de la pantalla 4, lista 11 pasos numerados con un círculo de estado
(pendiente/en progreso/completado) y una barra de progreso:

1. Verificando permisos
2. Actualizando Termux
3. Instalando paquetes core
4. Instalando compiladores
5. Instalando glibc
6. Instalando multimedia
7. Actualizando pip
8. Instalando npm globales
9. Configurando tema
10. Creando estructura
11. Finalizando

Secuencia real de fondo (no 1:1 con los labels, que son aproximados):
1. `TermuxInstaller.setupBootstrapIfNeeded()` — descomprime el bootstrap base de Termux
   (busybox/bash/coreutils/apt/dpkg). Idempotente: si `$PREFIX` ya existe y no está vacío, no
   hace nada. Tiene un guard de concurrencia (`sBootstrapLock`/`sBootstrapInProgress`) — una
   segunda llamada mientras la primera sigue en vuelo se encola en vez de arrancar su propia
   extracción, para evitar corromper el resultado si dos pantallas del wizard disparan el
   bootstrap casi al mismo tiempo.
2. `ensureBootstrapSecondStage()` — dispara una shell de login desechable (`bash -l -c true`)
   para forzar el "second stage" de postinst de Termux (busybox/coreutils/npm/openssh/
   proot-distro/python-pip/termux-exec/etc.), que de otra forma solo se dispara al abrir una
   shell de login real.
3. `KairosBootstrap.extractAssetsSync()` — copia los scripts de `assets/scripts/` a
   `~/scripts/install/` y `~/scripts/kairos.sh`/`~/kairos_manager.py`.
4. `installRootfsThenContinue()` — rootfs embebido o descargado (ver arriba); si falla del
   todo, pregunta si usar la instalación clásica.
5. `runKairosSetup()` — corre `~/scripts/kairos.sh --silent`, parseando líneas `[STEP]
   n/total mensaje` / `[OK]` / `[WARN]` / `[ERROR]` de su stdout para actualizar la UI paso a
   paso.
6. Al completar con éxito (`kairos.sh` termina con exit 0 y crea `~/.kairos_ready`), pasa a la
   pantalla 5 (Comprobar paquetes).

Si `kairos.sh` falla, se muestra un botón "Reintentar" que repite los pasos 2-5 (no repite el
bootstrap base, que ya debería estar hecho).

**Nota técnica sobre resolución de rutas:** dentro del wizard, `bash`/`apt` se invocan por
ruta absoluta (`TERMUX_BASH_PATH`/`TERMUX_APT_PATH` en `ProcessBuilderExt.kt`) en vez de por
nombre relativo — la resolución de PATH por nombre relativo no es 100% confiable justo
después de que el bootstrap termina de extraerse. También existe `WizardDebugLog.kt`, un log
persistente en `~/kairos_logs/wizard_debug.log`, activo desde la pantalla 2 en adelante, con
cada fase de `TermuxInstaller`/`RootfsInstaller`/el wizard instrumentada — útil para
diagnosticar instalaciones que fallan sin necesidad de leer logcat.

## 2. Módulos (tab principal)

**Archivos:** `ModulesFragment.kt`, `ModuleListAdapter.kt`, `item_module_row.xml`,
`BottomSheetInstalacion.kt`, `ModuleController.kt`.

**Header de estadísticas:** instalados (conteo), activos (conteo), RAM usada/total (leído de
`/proc/meminfo`, refrescado cada 5s), botón "↻ Actualizar" (hace `git -C ~/termuxapp pull`
para autoactualizar el propio repo de la app).

**Lista de módulos:** la lista principal muestra únicamente los módulos instalados
(`ModulesFragment.pollStatus()` filtra por `ModuleInstalled.isInstalled()`, que mira el
registry `~/.android_server_registry` o el binario real con `BINARY_FALLBACK`). `python`
siempre aparece (el wizard lo instala y `kairos.sh` lo registra). Si no hay ningún módulo
instalado, se muestra un estado vacío (`modules_empty`) con botón "Ir a Plugins →" que navega
a la Tienda (`TermuxActivity.openPlugins()`). El catálogo completo se ve en la **Tienda**
(menú Más → Plugins). Cada fila muestra icono, nombre, subtítulo de estado, y:
- Si `hasSwitch: true` en `modules.json` (ollama, n8n, openclaw, opencode, remote, db,
  llamaserver, ...): un `SwitchCompat` que inicia/detiene el proceso real.
- Si `hasSwitch: false` (python, claude, codex, antigravity, hermes, expo, ...): sin switch,
  solo subtítulo de estado.
- Un chevron "›" siempre visible, indicando que toda la fila es tocable.
- **Badge de estado superpuesto en el ícono** (patrón inspirado en paneles de gestión de
  homelab tipo Proxmox VE): círculo chico de color en la esquina inferior-derecha del ícono de
  cada fila (verde=corriendo, gris=instalado y detenido, etc., vía
  `ModuleRowRenderer.bindStatusBadge()`/`statusBadgeColor()`), adicional al texto de estado ya
  existente — no lo reemplaza, la fuente principal de información sigue siendo el subtítulo de
  texto. Mismo mecanismo reusado en la Tienda (`PluginListAdapter`, sección 15 abajo).

Tocar la fila: si no está instalado → abre la hoja de instalación (`BottomSheetInstalacion`);
si está instalado → navega a la pantalla de detalle del módulo.

El estado se recalcula cada 5s (`pollStatus()`): lee `~/.android_server_registry` (fuente
real, escrita por los scripts bash) para saber "¿instalado alguna vez?" (junto con
`ModuleInstalled.isInstalled()` con fallback a binario real), y `ModuleController.isRunning()`
(tmux has-session, o pgrep para módulos sin tmux) para saber "¿corriendo ahora?".

**Hoja de instalación (`BottomSheetInstalacion`):** muestra icono/nombre/descripción/chips
(tamaño, tipo, puerto, tiempo estimado), selector de variante si aplica (ollama: GPU/estándar;
claude: nativo/legacy; n8n: proot/udocker), botón "▼ Instalar" (o "Cambiar método" cuando se
abre desde la Tienda para un módulo ya instalado). Al tocar instalar: spinner +
"Instalando…"/"Cambiando método…" (sin output crudo de pkg/apt en pantalla) mientras corre
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
`dropdownSwitchRow()` (elegir 1 de N + switch que bloquea el dropdown mientras está ON, ej.
n8n local/Cloudflare), `switchRow()` (encendido/apagado simple), `dropdownRow()` (elegir 1 de
N sin switch). Todas verifican `isModuleInstalled()` (lee `<id>.installed` del registry real)
al entrar — si no está instalado, muestran una pantalla de "Módulo no instalado" con botón
volver, en vez del contenido normal.

| Módulo | Info mostrada | Acciones reales |
|---|---|---|
| **Ollama** | proceso, puerto :11434, versión; modelo activo | Iniciar/Reiniciar (start/stop reales); "Abrir Chat IA" navega al tab Chat; "Descargar modelo" navega a `ModelsFragment` (lista real vía `models-list`, tocar un modelo abre detalle/eliminar, botón para descargar uno nuevo vía `models-pull`); "Parámetros de inferencia" navega a `OllamaConfigFragment` (carga/guarda parámetros reales vía `config-get/set/reset`, genuinamente usados por el chat) |
| **n8n** | entorno (proot), versión, URL túnel, estado (pill) | Iniciar/Detener reales; "Abrir interfaz web" (inicia si hace falta, luego WebView); "Ver URL del túnel" (`cat ~/.last_cf_url`); "Ver logs"/"Backup"/"Actualizar" (cada uno abre una sesión de terminal) |
| **OpenClaw** | variante, versión, gateway, token, modelo activo | Iniciar/Detener/Reiniciar gateway reales; Ver logs → `LogsFragment`; abrir interfaz web (start-if-needed); TUI (`openclaw tui`); Onboarding (`openclaw onboard`); Reinstalar/actualizar; "Mostrar URL con token" y "Proveedor IA/Modelo" (usan `gateway-url`/`providers-list` reales) |
| **OpenCode** | variante, versión, "Web server" pill puerto 3000 | TUI en terminal (`opencode`); servidor web (start-if-needed + WebView); Detener servidor; Reinstalar; Importar/Sincronizar proyectos y Gestionar proyectos (backend real) |
| **Claude Code** | método, versión, estado (pill) | Abrir en terminal (`claude`); Abrir en proyecto/Gestionar proyectos (lista proyectos reales y abre `claude` con `cd` al proyecto elegido); Reinstalar/cambiar método |
| **Codex CLI** | canal, versión, estado | Abrir en terminal (`codex`); `codex login`; Reinstalar/cambiar canal |
| **Antigravity CLI** | método, versión, estado | Abrir en terminal (`agy`); Reinstalar |
| **Python** | versión, pip | Ver versión/info, Abrir REPL (`python3`), Instalar paquete (pip, con diálogo de texto), Listar paquetes, Ejecutar script .py |
| **Expo** | versión EAS CLI, Node, usuario expo.dev, proyecto activo | Build preview/producción, Ver builds, Login (`eas login` en terminal), Info, Configurar proyecto activo (diálogo de selección), Git push |
| **Remote** | SSH/IP/Usuario/Conexiones, Tunnel — se refrescan cada 5s | Iniciar/Detener SSH, Info de conexión, Agregar clave pública, Cambiar contraseña, Iniciar/Detener tunnel Cloudflare, Configurar token CF, Cómo conectarse |
| **Hermes** | versión, gateway, modelo activo | Abrir TUI (`hermes`), Wizard completo (`hermes setup`), Comandos-referencia (diálogo estático), Configurar proveedor IA / Usar Ollama local, Estado/diagnóstico, Actualizar/Instalar-reinstalar. `HermesGatewayFragment` (Iniciar/Detener/Ver estado/Ver logs) también wireado a comandos reales |

**`DbFragment`** (módulo `db` "Base de Datos" — detalle completo en `docs/modulos/DB.md`):
card ESTADO (MySQL/MariaDB y PostgreSQL con `pgrep -x` en vivo + versiones del registry,
SQLite con `sqlite.version`), card SERVIDORES (▶ Iniciar / ■ Detener por servidor vía
wrappers dedicados), card SQLITE con acciones reales (listar BDs en `~`, abrir BD interactivo
con el CLI `sqlite3` en terminal, ver tablas, BD de n8n, exportar a CSV, crear BD vacía, query
SQL) usando `android.database.sqlite.SQLiteDatabase` directo, sin subproceso Python. El
switch del módulo en la lista arranca/detiene ambos servidores juntos.

**`LogsFragment`**: visor de logs con búsqueda/filtro en vivo y coloreado por nivel
(`[OK]`/`[INFO]` azul, `[WARN]` ámbar, `[ERROR]` rojo, "✓"/"success" verde). Recibe una ruta
de archivo por argumento (`LogsFragment.newInstance(path)`) y la lee con `BufferedReader`
(snapshot al abrir, no sigue el archivo en vivo). El botón "Ver logs" de OpenClaw navega acá
(`~/openclaw-logs/runtime.log`). El de n8n usa `launchTerminalCommand` en su lugar, porque
adjunta una sesión tmux en vivo — no encaja con el modelo de snapshot estático de
`LogsFragment`.

## 4. Monitor (menú "Más")

**`MonitorFragment`**: estado en vivo de los módulos con proceso (ollama/n8n/openclaw/
opencode/remote, vía `ModuleController.isRunning()`), conectividad de red (tipo wifi/datos/
ethernet + validación de internet, vía `ConnectivityManager` nativo de Android), conteo de
paquetes de Termux instalados y de paquetes pip. Refresca módulos/red cada 5s; los conteos de
paquetes se cargan una vez al entrar.

Incluye también una sección **"DISPOSITIVO"** (primera sección de la pantalla): anillo de RAM
(`Canvas`/`Paint` sobre `/proc/meminfo`), anillo de almacenamiento (`StatFs` sobre
`Environment.getDataDirectory()` — si falta el permiso "todos los archivos", la card se vuelve
tocable y dispara el flujo de configuración de almacenamiento, con polling hasta que se
concede), e info de dispositivo (IP local vía `NetworkInterface`, uptime desde
`/proc/uptime`, versión de API Android, arquitectura ABI).

**Sección DIAGNÓSTICO — phantom process killer** (ver `docs/modulos/PHANTOM_PROCESS_KILLER.md`
para el detalle completo): fila con estado ("Android puede matar módulos en segundo plano..."
/ "Desactivado y verificado en este dispositivo") y botón "Desactivar"/"Volver a aplicar". Al
tocar: intento silencioso vía `su` (si el dispositivo está rooteado); si falla, diálogo con 3
vías — **(a) Configurar automáticamente (sin PC)**: guiado de 3 pasos vía Depuración
inalámbrica (ADB), pide puerto de emparejamiento/código/puerto de conexión y aplica los
comandos necesarios con verificación real; **(b) Auto-detectar puerto (beta)**: mismo flujo
pero detecta el puerto de conexión con `nmap`; **(c) Ver tutorial manual**: solo texto +
comandos copiables, sin automatizar nada. Ninguna vía marca éxito sin verificación real.

**Bottom nav — 5 tabs fijos** (Módulos/Chat/Sistema/Config/**Más**) más el 5º ítem, que abre
un menú con las pantallas que no entran (Monitor/Archivos/Túnel/Procesos/IA Local/Nube).

**`ModelsFragment`** y **`OllamaConfigFragment`**: `ModelsFragment` tiene lista real de
modelos instalados vía `models-list`/`models-pull`/`models-delete` más un catálogo curado de
modelos para descargar con un toque (qwen2.5, gemma2, llama3.2 en varios tamaños), con
velocidad/ETA real en vivo durante la descarga (streaming real de la API de Ollama).
`OllamaConfigFragment` carga/guarda parámetros de inferencia reales (`config-get/set/reset`),
genuinamente consumidos por el chat.

## 5. Chat IA (tab)

**Archivo:** `ChatFragment.kt`.

Chat directo contra la API de Ollama (`http://127.0.0.1:11434`), sin pasar por ningún módulo
intermedio. Al entrar, hace un `GET` a esa URL (timeout 2s) para decidir si mostrar la
interfaz de chat o un overlay de "Ollama inactivo". Selector de modelo (popup menu, modelos
hardcodeados: qwen2.5:0.5b/1.5b, qwen3:4b, gemma3:4b/1b, deepseek-coder:1.3b-instruct,
llama3.2:1b — no lee modelos realmente instalados). Envía mensajes vía `POST /api/generate`
con `stream: true`, parseando cada línea NDJSON de la respuesta y agregando el texto
incrementalmente a la burbuja del asistente. Botón cancelar (interrumpe el hilo de la
petición), botón limpiar historial, contador de mensajes, barra de error.

## 6. Config / Ajustes (tab)

**Archivo:** `ConfigFragment.kt`.

Sección "General": switch "Auto-iniciar módulos" — al abrir Kairos (no al encender el
teléfono, no hay boot receiver), `TermuxActivity.onCreate()` llama
`ModuleController.autoStartEligibleModules()`, que arranca cualquier módulo con
`hasSwitch:true` que ya esté instalado pero detenido; fila "Optimización batería"; switch
"Notificaciones de módulos caídos" — el poll loop de `ModulesFragment` detecta transiciones
RUNNING→INSTALLED_STOPPED y dispara una notificación local real si el switch está activo.

Sección "Info": Arquitectura (real), y otros campos informativos.

Sección **"Variables de entorno"**: lista/agrega/elimina variables `export KEY=value` en un
bloque propio y aislado dentro de `~/.bashrc` (delimitado por marcadores dedicados, nunca toca
el bloque que escribe el script de setup) — aplican solo a sesiones de terminal nuevas.

Tres botones de mantenimiento: "Rerun setup" (borra `~/.kairos_ready` y relanza el wizard, sin
tocar módulos ya instalados); "Full backup" (corre un backup real — tar.gz de scripts/
registry/.bashrc/configs de módulos a la carpeta de Descargas del dispositivo); "Reinstalar"
(gate de confirmación "escribir REINSTALAR", borra `~/scripts`, el registry, los marcadores de
setup y relanza el wizard).

## 7. Terminal overlay

**Archivos:** `TermuxActivity.java` (`toggleTerminalOverlay()`, `openTerminalWithCommand()`),
layout `activity_termux.xml`, `TerminalBridge.java`, `TermuxActivityRootView.java`.

Se abre/cierra con el FAB flotante sobre el bottom nav. La primera vez que se infla, configura:
`TerminalView`, drawer de sesiones (lista + botón nueva sesión, con long-press para sesión con
nombre / modo failsafe), toolbar de teclas extra (ESC/TAB/CTRL/flechas, heredado del engine),
botón de alternar teclado, botón de "quick settings" (diálogo con `SeekBar` de tamaño de
fuente, aplicado en vivo y persistido en preferencias), y un listener de insets que aplica
`systemBars()` + `Type.ime()` como padding para que el teclado no tape el contenido.

Al mostrarse: oculta el bottom nav + FAB + fragment actual; adjunta la primera sesión
existente o crea una si no hay ninguna. Al ocultarse: los restaura.

**`openTerminalWithCommand(command)`**: método público usado por
`BaseModuleFragment.launchTerminalCommand()` — asegura que el overlay esté visible, crea una
sesión nueva, y le escribe `command + "\n"` — así los botones "Abrir en terminal" de los
módulos CLI realmente ejecutan el comando en vez de solo abrir una shell vacía.

### 7.1 Terminal — modo adaptado

Cuando el terminal se abre para un CLI específico (Claude, OpenCode, Hermes, etc., vía
`launchTerminalCommand`) en vez de la terminal genérica, `activity_termux.xml` cambia a un
modo visual distinto:

- **Barra superior adaptada** (2 filas): título del módulo + una segunda fila con estado/
  versión reales en vivo — "● Activo · v1.18.3" o "○ Inactivo", refrescado en background
  thread.
- **Barra inferior**: si el módulo tiene un servidor real corriendo, muestra "⏺ escuchando en
  http://127.0.0.1:<puerto>" (poll TCP real cada 500ms) — oculta si no aplica. Envuelve
  también la toolbar de teclas extra heredada.
- **Sidebar deslizable con contenido propio**, distinto del contenido normal de la lista de
  sesiones: Minimizar, Cerrar sesión, Reiniciar módulo, Ver logs (abre un diálogo con
  contenido real de `~/kairos_logs/`).

Se aplica automáticamente a todos los módulos con CLI sin tocar cada Fragment individual — es
un mecanismo compartido en `TermuxActivity.java`/`activity_termux.xml`.

## 8. ModuleWebViewFragment (pantalla genérica de interfaz web)

**Archivo:** `ModuleWebViewFragment.kt`.

WebView programático (sin XML propio) usado por n8n, OpenClaw y OpenCode para mostrar su
interfaz local (`webviewUrl` de `modules.json`) dentro de la app en vez de exponer la
terminal cruda. Barra superior con botón volver + título + atrás/adelante del historial del
WebView + recargar; barra de dirección de solo lectura debajo (muestra la URL actual, se
actualiza también en navegación SPA interna); barra de progreso de carga. El botón atrás del
sistema navega el historial del WebView primero, y solo cierra la pantalla cuando ya no hay
historial. JavaScript y DOM storage habilitados, zoom soportado. No verifica de antemano que
el servidor esté respondiendo — si no lo está, el WebView muestra el error de carga estándar
de Android (cada fragment que la invoca intenta arrancar el servicio primero si no está
corriendo, antes de navegar acá). Sin pestañas múltiples ni historial persistente entre
sesiones — deliberadamente fuera de alcance, esto no es un navegador general, es un visor de
un único servicio local por vez.

## 9. Archivos — CRUD + editor de texto

`FileManagerFragment.kt`: toque largo en una fila abre un menú (Copiar/Cortar/Renombrar/
Eliminar, más "Pegar aquí" si hay algo en el portapapeles) — portapapeles de un solo elemento,
"mover" es cortar+pegar. Copiar/cortar usan `copyRecursively()`/`copyTo()` sin sobreescribir;
eliminar pide confirmación; renombrar valida nombre vacío/colisión. Tocar un archivo de texto
(extensión conocida o sin extensión y <256KB) navega a `EditorFragment` en vez de mostrar solo
nombre+tamaño.

**`EditorFragment.kt`**: editor de texto real sobre `io.github.rosemoe.sora.widget.CodeEditor`
(librería `sora-editor`, LGPL-2.1, agregada como dependencia Gradle sin modificar su código).
Carga el archivo con `File.readText()`, "Guardar" escribe con `writeText()`, confirma antes de
salir si hay cambios sin guardar, rechaza archivos >5MB o inexistentes. Resaltado de sintaxis
real para 12 lenguajes (Java, Kotlin, Python, XML, HTML, JS, TS, Markdown, JSON, YAML, shell,
CSS) vía gramáticas TextMate reales, tema `darcula.json`. Sin soporte de archivos binarios.

## 10. Túnel (menú "Más")

**Archivo:** `TunnelFragment.kt`. Una tarjeta por módulo con puerto conocido (Ollama :11434,
n8n :5678, OpenClaw :18789, OpenCode :3000): estado del servicio (¿está corriendo el módulo?)
y del túnel (sin túnel / iniciando / activo con URL), botón "Iniciar túnel" (cloudflared
quick-tunnel anónimo, sin cuenta), botón "Con token" (túnel nombrado autenticado, pide un
token de Cloudflare vía diálogo), botón "Detener". Al iniciar, hace polling de `tunnel status`
cada 2s hasta ~14s esperando a que la URL aparezca en el log de cloudflared. Primera vez que
se intenta iniciar un túnel en la sesión de la app: diálogo de advertencia ("esto expone el
módulo a internet sin autenticación").

Usa un backend genérico (`tunnel start/stop/status/list`, por puerto) — no reemplaza los
túneles cloudflared que ya existían para n8n (dentro de `N8nFragment`/`modulos/n8n.sh`, con su
propio archivo de URL) ni para Remote/SSH (su propia sesión tmux) — esos siguen funcionando
igual desde sus propias pantallas. Es una superficie de control unificada nueva e
independiente, para cualquier módulo con puerto.

## 11. Procesos (menú "Más")

**Archivo:** `ProcesosFragment.kt`. Lista los procesos gestionados por **pm2** (ya se instala
como parte del wizard, entre los paquetes npm globales). Corre `pm2 jlist` directo por
`ProcessBuilder`, sin pasar por Python. Distingue explícitamente "pm2 no está en el PATH"
(mensaje de reinstalar) de "pm2 está pero el comando falló" (posible daemon caído). No es un
módulo de `modules.json` (no tiene switch propio ni pantalla de instalación) — se asume ya
instalado por el bootstrap.

## 12. IA Local (menú "Más", llama.cpp embebido)

**Archivo:** `LocalAIFragment.kt`. Gestión de modelos GGUF y parámetros para el motor de
inferencia embebido (`llama-engine/`, llama.cpp directo vía NDK — ver
`docs/ia-local/LLAMA_CPP_EMBEBIDO.md`) — motor separado de Ollama a propósito: el chat usa un
único selector de modelo que lista tanto los modelos remotos de Ollama como los locales GGUF,
pero el motor real que responde se decide según cuál se eligió, nunca mezclados en la misma
conversación.

**Catálogo curado** (6 modelos reales, con URL directa de HuggingFace): Qwen2.5-0.5B-Instruct,
SmolLM2-1.7B-Instruct, Qwen2.5-1.5B-Instruct, Llama-3.2-1B-Instruct, Gemma-2-2B-it,
Llama-3.2-3B-Instruct — todos cuantización Q4_K_M/q4_k_m. Descarga con velocidad/ETA real en
vivo (mismo patrón que el catálogo de Ollama). Texto de la propia pantalla: *"Inferencia con
llama.cpp embebido — sin Termux, sin red una vez descargado el modelo. Los modelos de acá
también aparecen en el chat."*

## 13. Nube (menú "Más")

**Archivo:** `NubeFragment.kt`. Convierte el teléfono en una nube de almacenamiento mínima
tipo Drive/Mediafire, acotada a UNA carpeta fija (`$HOME/nube`) y accesible desde cualquier
navegador de la red local, no solo desde la app. Reusa dos piezas ya existentes: **`NubeServer`**
(servidor HTTP embebido a mano, gateado por token, con validación de path traversal) y
**`TunnelManager`** (la misma lógica de cloudflared/ngrok que usa `TunnelFragment`, apuntada al
puerto de `NubeServer` en vez de a un módulo). La lista de archivos usa el mismo mecanismo de
navegación in/out que `FileManagerFragment` pero acotado a la carpeta de nube — el usuario no
puede navegar fuera de ella ni ver el resto del teléfono desde acá. Pantalla propia porque
necesita controles de servidor/túnel que `FileManagerFragment` no tiene.

## 14. GenericModuleFragment (detalle de módulo genérico)

**Archivo:** `app/src/main/java/com/termux/app/ui/GenericModuleFragment.kt`.

Detalle de módulo metadata-driven: en vez de una clase por módulo, este fragment renderiza el
sub-menú completo de cualquier módulo desde `ModuleInfo` (`modules.json`) + el estado real del
registry. Es el `else` del dispatch en `ModulesFragment.navigateToModuleDetail()`: los módulos
con UI específica (Ollama, N8n, Claude, ...) conservan su fragment dedicado; cualquier módulo
nuevo del catálogo cae acá sin escribir Kotlin nuevo.

Tarjetas/acciones que dibuja automáticamente (según lo que el módulo declare en
`modules.json`):

- **ESTADO** — ID, versión real del registry, puerto, tipo, ejecución (nativa en gris / proot
  en ámbar), sesión tmux, pill de TUI en terminal (si tiene `terminalCommand`) y pill de
  servidor corriendo/detenido (poll en background thread).
- **QUÉ ES** — la `description` del catálogo.
- **CONTROL** (solo si `hasSwitch=true`) — ▶ Iniciar / ■ Detener servidor.
- **🌐 Abrir interfaz web** (si `webviewUrl`) — navega a `ModuleWebViewFragment`.
- **⌨ Abrir en terminal** (si `terminalCommand`) — `launchTerminalCommand()` con el CLI del módulo.
- **DETALLES** — tamaño y estimación de instalación si existen.
- **MANTENIMIENTO** — 🔄 Actualizar y 🗑 Desinstalar (con diálogo de confirmación que detiene
  el módulo y borra scripts/checkpoints/registry, sin tocar paquetes compartidos).

## 15. Plugins — Tienda de módulos (menú "Más")

**Archivo:** `PluginsFragment.kt` (+ `PluginListAdapter.kt`, layouts `fragment_plugins.xml` /
`item_plugin_row.xml`). Lista el catálogo completo de plugins/módulos desde `ModuleCatalog`
(bundled + cache + refresco remoto híbrido).

- **Orden**: recomendados arriba, ordenados por descargas desc; el resto después, también por
  descargas.
- **Búsqueda**: por nombre / id / descripción / categoría.
- **Badges**: ★ Recomendado (verde), arquitectura (bionic/glibc/proot/proot-distro con color
  por tipo), categoría, ⬇ descargas.
- **Estado real**: `ModuleRegistry` + `ModuleController.isRunning()` — poll al reingresar a la
  pantalla y tras cada acción.
- **Acciones por estado**: Instalar (`BottomSheetInstalacion`), Desinstalar (confirmación),
  Abrir (navega al detalle correspondiente).
- **"↻ Catálogo"**: refresca el catálogo remoto con fallback silencioso a cache/bundled.
- Arrancar/parar queda en la pantalla de detalle (mismo patrón que el resto de la app).

## 16. X11 — funcionalidad integrada dentro de Mini PC

El servidor X11 embebido (Xlorie, `:x11-server`) ya no tiene una pantalla propia — vive
completamente dentro de **`EntornoFragment.kt`** (tab "Mini PC", ver sección 17), sección
"NATIVO — X11 + escritorio directo":

- **Estado del servidor**: lee si el proceso `:xserver` está vivo y muestra "● Servidor
  activo" / "○ Servidor detenido" + display `:1`. Refresh al reingresar a la pantalla.
- **🚀 Entrar en X11**: arranca el servicio X11 + abre el visor (`com.termux.x11.MainActivity`,
  LorieView).
- **⚙ Configuración de X11**: abre `LoriePreferences` — la pantalla de preferencias original de
  termux-x11 con modo de resolución (nativa/escala/exacta/custom), escala de display,
  densidad, estirar, orientación forzada horizontal/vertical, fullscreen, ocultar notch, PiP,
  teclado extra, modo de touch, sensibilidad y más. Los cambios aplican en vivo por broadcast.
- **✕ Cerrar servidor X11**: confirma → cierra el visor + detiene el servicio X11.
- **Back del visor = diálogo Minimizar/Cerrar**: `MainActivity.onBackPressed()` muestra un
  diálogo con "Minimizar" (el servidor sigue corriendo) y "Cerrar servidor X11" (mismo
  contrato que el ✕ de arriba).

## 17. Mini PC (Entorno) — tab principal

**Archivo:** `EntornoFragment.kt` (extiende `BaseModuleFragment`, layout
`fragment_module_detail.xml`, lógica en `EntornoNative.kt`). Tab propio del
`BottomNavigationView`, con acceso alternativo desde el catálogo de Módulos.

- **Item `nav_minipc`** (`bottom_nav_menu.xml`, ícono dedicado, título "Mini PC") — dado el
  límite duro de 5 items de `BottomNavigationView`, la funcionalidad de X11 quedó fusionada
  dentro de este tab (sección "NATIVO — X11 + escritorio directo" de la sección 16 de arriba)
  en vez de vivir como pantalla secundaria propia. Entorno/X11 comparten el mismo ecosistema
  de escritorio (`EntornoNative.startDesktop()`/`startDistroDesktop()` ya arrancan el servicio
  X11 y abren el visor embebido directamente).
- **Doble vía de acceso**: buscar "Entorno" desde el catálogo de Módulos también abre la misma
  pantalla (con backstack real, no como tab raíz).
- **`BaseModuleFragment.showBackButton`**: como tab raíz, `EntornoFragment` se agrega directo
  al contenedor de fragments (no vía backstack) — la flecha "←" de header, que asume por
  defecto que el fragment se abrió vía `addToBackStack()`, se desactiva acá (`showBackButton =
  false`) porque no tiene a dónde volver. El resto de módulos que se abren solo vía backstack
  no cambia de comportamiento.
