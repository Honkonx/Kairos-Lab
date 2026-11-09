# OpenClaw

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación, start/stop y estado los maneja la app vía `ProcessBuilder` → `modulos/openclaw.sh`.

---

**Script:** `modulos/openclaw.sh` (v5.0.0, 865 líneas) — copia sincronizada en `app/src/main/assets/scripts/openclaw.sh`.
**Puerto:** `:18789` (fijo, sin variantes; `--bind loopback`, `modulos/openclaw.sh:679`)
**Registry:** prefijo `openclaw.*`
**Fragment:** `OpenClawFragment.kt` (628 líneas), con `OpenClawNative.kt` (423 líneas)

> Convención: **confirmado** = verificado leyendo el código; **sin verificar** = inferencia sin prueba en ejecución.

---

## 1. Qué es

OpenClaw es un gateway de agente IA (npm, `openclaw@latest`) con interfaz web y TUI propias, compatible con múltiples proveedores (Ollama local, cloud). En Kairos corre **exclusivamente en modo nativo** (glibc + npm), sin proot (`modulos/openclaw.sh` línea 28: *"rama proot removida — ya no es necesaria... la variante nativa (glibc-runner) es la correcta y la única mantenida"*). Escucha en `127.0.0.1:18789`.

`modules.json`: `"description": "Gateway de IA local compatible con Claude. Versión nativa glibc."`, puerto `18789`, tamaño estimado `~60MB`, `hasSwitch: true`, `arch: glibc`, `requires: [glibc]`, `installMethods: [glibc]`, `catalogVersion 4.1.0` (el script dice v5.0.0: desfase). Alta del módulo: 2026-07-19.

Clasificación: **módulo funcional** — UI propia con estado y servicio (`OpenClawFragment`, `ModuleDetailNavigator.kt:27`), switch de start/stop con sesión tmux y puerto (`ModuleController.kt:1164,1186,1199,1237`), registry, token del gateway, editor de proveedores y de canales que escribe `~/.openclaw/openclaw.json`. ~15 controles en `buildContent()` (`OpenClawFragment.kt:44-172`).

## 2. Permisos

Ninguno de Android específico para OpenClaw en sí — usa la infraestructura ya presente de Termux (almacenamiento, ejecución de procesos vía `ProcessBuilder`) que la app ya pide en el wizard general. No requiere `RECORD_AUDIO`, `POST_NOTIFICATIONS`, ni ningún permiso propio declarado en el manifest a nombre de este módulo.

## 3. Arquitectura de instalación — 100% nativo, sin proot

```
Termux nativo (sin proot)
  ~/.npm-global/bin/openclaw          → paquete npm real (wrapper bash)
  ~/.openclaw-android/bin/node        → wrapper bash → ld.so (glibc-runner) → node.real
  ~/.openclaw-android/bin/npm, npx    → wrappers equivalentes
  ~/.openclaw/glibc-compat.js         → --require en NODE_OPTIONS (fix os.networkInterfaces/homedir)
```

Node.js oficial `linux-arm64` (versión objetivo `22.23.2`, `NODE_VERSION_TARGET`, `:66`; mínimo exigido por OpenClaw: *"Node 22.22.3+, 24.15+, o 25.9+... Node 23 unsupported"*, fuente `docs.openclaw.ai/install/node`) corre sobre el cargador ELF de `glibc-runner`, sin entrar a ningún entorno proot.

## 4. Instalación (`modulos/openclaw.sh`, 8 pasos)

Flags (`:96-102`): `--silent` (sin prompts, modo app), `--force` (reinstala aunque ya esté; limpia el checkpoint, `:181`), `--describe` (manifiesto JSON declarativo, `"variants": []`, `:109`), `--describe-files`, `--variant` (aceptado e ignorado). Sin `--force` y con `openclaw.location=nativo_termux` en el registry, sale con "ya instalado" (`:168-177`).

1. **Infraestructura glibc + Node** (`_ensure_glibc_node`) — si ya hay un Node ≥ mínimo exigido (sistema o wrapper propio) con npm funcional, lo reusa. Si no, instala `glibc-repo` → `glibc-runner`/`patchelf-glibc` → descarga Node oficial `linux-arm64` → genera wrappers `node`/`npm`/`npx` que sanean `NODE_OPTIONS` heredado antes de invocar el binario real (un `--require` a un `glibc-compat.js` borrado rompería CUALQUIER invocación de node/npm).
2. **Verificar Node + npm** — chequeo de versión mínima real (mayor.menor.parche, no solo el major — `_node_meets_openclaw_min()`, `:222`, acepta 22.22.3+, 24.15+, 25.9+, o cualquier 26.x+).
3. **Instalar openclaw** — `npm install -g openclaw@latest --ignore-scripts` con `NODE_LLAMA_CPP_SKIP_DOWNLOAD=true` (`:450-453`), seguido de la ejecución a mano del postinstall PROPIO de openclaw con `npm_config_ignore_scripts=true node <postinstall>` (`:458-470`). `--allow-scripts=openclaw` NO es una flag de npm (`:437-448`); el postinstall manual recupera el hotfix de `baileys` (carrera de promesas y dispatcher incompatible con Undici en descargas de media, ver `scripts/postinstall-bundled-plugins.mjs` del repo `github.com/openclaw/openclaw`) sin ejecutar los scripts nativos de dependencias transitivas. Un wrapper bash real sustituye al symlink de npm (`:498-515`, por restricción de ejecución del symlink). Antes de saltar este paso por checkpoint, verifica que el paquete siga existiendo en disco (`$NPM_GLOBAL/lib/node_modules/openclaw`).
   Tras instalar, **pre-siembra** `~/.openclaw/openclaw.json` con `gateway.mode=local` **y** `gateway.auth.mode=token` + token hex aleatorio de 24 bytes (`:511-553`; mergea sin pisar nada más si el archivo ya existe) — ver sección 10.
4. **Patches Android** — `glibc-compat.js` (fix `os.networkInterfaces()`/`os.homedir()`), stub de `koffi` (módulo nativo no compilado para `android-arm64`), stub de `clipboardy`, parche de rutas `/tmp` → `$HOME/tmp` y `/bin/npm` → ruta real del wrapper, dentro del bundle instalado.
5. **Scripts de control** — genera `openclaw_start.sh`/`openclaw_stop.sh` en `~/scripts/openclaw/` (`:626-762`, detalle en la sección 7).
6. **Aliases** — agrega bloque `# OpenClaw` a `~/.bashrc` (`openclaw-start`, `openclaw-stop`, `openclaw-status`, `openclaw-tui`; `:769-790`).
7. **Registry** — actualiza `~/.android_server_registry` (`update_registry`, `:162-165`). La lectura de `openclaw --version` usa `|| true` para que un fallo ahí no aborte el script bajo `set -euo pipefail`; cae al fallback `"unknown"` y el registry SIEMPRE se actualiza si se llegó hasta este punto (`:793-818`).
8. **Limpieza** — borra el archivo de checkpoint (`~/.install_openclaw_native_checkpoint`); `openclaw update` best-effort con `timeout 120` (`:824-850`).

Verificación: el script **no** usa `verify_binary_installed()` ni exige que el binario ejecute antes de `installed=true` (solo `--describe-files` incluye un `verify_cmd` con `openclaw --version`, usado por moduledeb).

Efectos globales: añade a `~/.bashrc` `NODE_OPTIONS=… --max-old-space-size=5632` (`:402-404`) y, tras el paso 4, un `--require ~/.openclaw/glibc-compat.js` (`:574-575`); crea un stub `$PREFIX/bin/systemctl` si no existe (`:412-418`).

## 5. Detección de estado (`ModuleController.kt`)

Módulo con `hasSwitch: true` y sesión tmux real — `tmuxSession: "openclaw"` en `modules.json`. `ModuleController` resuelve:
- Script de arranque: `$HOME/scripts/openclaw/openclaw_start.sh`
- Script de detención: `$HOME/scripts/openclaw/openclaw_stop.sh`
- Nombre de sesión tmux: `"openclaw"`
- Puerto: `18789`

"Corriendo" se determina vía `tmux has-session` o puerto abierto (`ModuleController.kt:~370-390`, `OpenClawNative.info()`) — no solo lectura de registry. No hay sesión tmux de túnel.

## 6. Pantalla de la app (`OpenClawFragment.kt`)

Workspace propio: OpenClaw usa `$HOME/.openclaw/workspace` (NO la carpeta compartida `~/proyectos` de Claude/OpenCode/Codex/Antigravity), prefijo de registry `openclaw_workspace`. `ProjectsManager` soporta `baseDir`/`regPrefix` como parámetros para este caso.

Tarjeta "ESTADO": variante (texto fijo `native·glibc`), versión (`openclaw.version` del registry), switch Gateway, token enmascarado (`take(6)…takeLast(4)`, `:225`; en ámbar como "— (falta onboarding)" si el campo está vacío, string `openclaw_token_missing`), pill de estado de terminal.

| Control | Qué hace |
|---|---|
| Switch **Gateway** | `on=true` → `startGatewayGuarded()` (llama `startModuleService()` → `openclaw_start.sh`); `on=false` → `stopModuleService()` → `openclaw_stop.sh` |
| Token (fila info) | Solo lectura — token real del gateway, leído con `OpenClawNative.gatewayUrl()` |
| Pill "Terminal TUI" | Solo lectura — si hay una sesión de terminal minimizada corriendo (con cierre) |
| ↻ Reiniciar gateway | Detiene y vuelve a iniciar con `startGatewayGuarded()` |
| 📋 Ver logs | Abre `LogsFragment` sobre `~/openclaw-logs/runtime.log` |
| 🔗 Mostrar URL con token | `OpenClawNative.gatewayUrl()` — lee `gateway.auth.token` de `~/.openclaw/openclaw.json` (con el log de runtime como fallback por regex `token=([a-f0-9]+)`), arma `http://localhost:18789/#token=...`; diálogo con botón copiar |
| 🌐 Abrir interfaz web (local) | Si no está corriendo, lo arranca primero; luego abre `ModuleWebViewFragment` con la URL completa con `#token=...` (cae a la URL pelada solo si todavía no hay token) |
| ⌨ Abrir TUI (terminal) | `launchTerminalCommand("openclaw tui")` (con selector de carpeta) |
| ⚙ Onboarding | `launchTerminalCommand("openclaw onboard")` — wizard interactivo para configurar un proveedor de IA real; no es prerequisito del gateway/token (ver sección 10) |
| 🔌 Proveedor IA / Modelo | Submenú: ver config completa / configurar Ollama / proveedor personalizado (5 campos: nombre, base URL, API key, ID de modelo, context window; para proveedores OpenAI-compatible como DeepSeek, LM Studio, etc.) / restaurar backup. Gemini/Anthropic con OAuth van por "Onboarding" |
| 📡 Configurar canales | Diálogo con 4 canales (Discord/Telegram/WhatsApp/Slack); ver sección 8 |
| 🗂 Gestionar workspaces | `showProjectsMenu()` sobre `~/.openclaw/workspace`: symlink/importar desde Download, eliminar, sincronizar todos |
| 📁 Abrir workspace en TUI | Lista workspaces ya importados, abre `openclaw tui` en el que se elija |
| ↑ Instalar / actualizar | `reinstallModuleService()` (reinstalación completa; sin forma de pasar `--force` explícito desde acá) |
| 🔗 Conectar memoria de Engram (MCP) | `openclaw mcp add engram …` vía `OpenClawNative.mcpConnectEngram()` (`OpenClawNative.kt:397`) — OpenClaw es cliente MCP real y documentado; si el gateway ya corre, ofrece reiniciarlo para tomar el nuevo servidor |
| 🗑 Desinstalar | `confirmUninstallModule()` (`OpenClawFragment.kt:168-170`) |

## 7. Runtime — scripts generados

`openclaw_start.sh` (generado por el PASO 5, `:626-735`):
- Si el gateway ya responde en `:18789`, sale inmediatamente (`[OK] Gateway ya corriendo`).
- Mata `openclaw gateway` previo (patrón de 2 palabras para no auto-matarse), crea sesión tmux `openclaw` pasando el comando DIRECTO a `tmux new -d -s openclaw` (con `TMPDIR`/`NODE_OPTIONS` seteados inline), lanza `openclaw gateway --bind loopback --port 18789 | tee ~/openclaw-logs/runtime.log`.
- Acepta `--no-wait` (dispara y sale sin esperar, para arranque no bloqueante).
- Sin `--no-wait`: health-check HTTP real (`curl` contra `:18789`), 22 intentos de 2 s (~44 s); si no responde corre `openclaw doctor --fix` y reintenta otros ~44 s (`:695-725`).
- **Heap dinámico**: calcula el heap de Node como 60% de `MemAvailable` (clamp 1024–5632MB) en vez de un valor fijo, para evitar que Android mate el proceso por OOM justo cuando Node carga el bundle completo por primera vez.

La ruta Kotlin (`OpenClawNative.gatewayStart`, `:90-103`) refuerza `gateway.mode` y ejecuta el script con timeout 90 s.

`openclaw_stop.sh` (`:736-762`): `pkill -TERM -f "openclaw gateway"` + `tmux kill-session -t openclaw` + espera 3 s + `pkill -9`, y confirma con un `curl` que el gateway ya no responde. El fallback Kotlin sin script usa `pkill -9 -f openclaw` (`OpenClawNative.kt:119`), con el patrón de una palabra que el propio script documenta como auto-asesino; solo se ejecuta si falta el script.

## 8. Configuración — `~/.openclaw/openclaw.json`

El archivo real y vigente es `~/.openclaw/openclaw.json` (confirmado contra `docs.openclaw.ai/gateway/configuration`); `OpenClawNative.providersList()` lo busca primero ahí, y `~/.config/openclaw/config.json` es solo fallback legacy. "Proveedor IA / Modelo" > "Ver config completa" muestra el JSON crudo (`showProviders`, `OpenClawFragment.kt:601-619`; `providersList`, `OpenClawNative.kt:168`).

Cada canal de mensajería (Discord, Telegram, WhatsApp, Slack, Signal, iMessage, WebChat, y más vía plugins — Matrix, Nostr, Twitch, Zalo) tiene su propia sección bajo `channels.<provider>` en ese mismo archivo (auth, control de acceso, multi-cuenta, mention gating) — `docs.openclaw.ai/gateway/config-channels` documenta el schema completo por canal. SMS no es un canal soportado.

**Botón "📡 Configurar canales"**: un diálogo lista 4 canales (Discord/Telegram/WhatsApp/Slack; los demás son reales pero quedan fuera del menú: Signal/iMessage/WebChat y los de plugin). Tocar un canal abre un editor simple: switch "Canal activo" + campo de texto para el token del bot/API, pre-cargado si el canal ya tenía configuración. "Guardar" escribe/actualiza `channels.<provider> = {"enabled": bool, "token": "..."}` en `~/.openclaw/openclaw.json`; si el canal ya existía, aparece también un botón "Eliminar" que saca esa sección del config.

Reusa el mismo mecanismo que "Proveedor IA / Modelo" (`writeProviderEntry()`) — `OpenClawNative.kt` tiene `channelsList()`/`writeChannelEntry()`/`channelDelete()` con backup automático antes de escribir (`openclaw.json.pre-channel-<provider>-<timestamp>`, mismo prefijo que "Restaurar backup") y escritura atómica vía archivo `.tmp` + rename. El schema mínimo (`enabled`/`token`) cubre "activar un canal con su token"; los campos avanzados por proveedor (multi-cuenta, mention gating, control de acceso fino) no tienen editor propio — hay que editar `~/.openclaw/openclaw.json` a mano.

## 9. Registry

```
openclaw.installed=true
openclaw.version=<versión real, o "unknown" si openclaw --version no respondió>
openclaw.install_date=<fecha>
openclaw.location=nativo_termux
openclaw.port=18789
```

## 10. Token del gateway y `gateway.mode=local`

El token vive en `gateway.auth.token` de `~/.openclaw/openclaw.json` y lo genera el propio proceso `openclaw gateway` en su primer arranque. La única condición que bloquea que el gateway arranque y se auto-provisione su token es que `gateway.mode=local` esté presente en el config; elegir proveedor de IA (lo que hace `openclaw onboard` interactivo) es un paso posterior y separable, no un prerequisito del token.

Otros wrappers Android de OpenClaw (p. ej. `mithun50/openclaw-termux`) tuvieron el mismo síntoma ("gateway blocked with 'set gateway.mode=local (current: unset)'") y lo resolvieron pre-sembrando `gateway.mode=local` en `openclaw.json` durante el setup inicial y reforzándolo en cada arranque. Kairos hace lo mismo:

1. `modulos/openclaw.sh` pre-siembra `~/.openclaw/openclaw.json` con `gateway.mode=local` y `gateway.auth.mode=token` + token hex aleatorio de 24 bytes (si el archivo no existe, o mergea los campos sin pisar nada más), después de instalar el binario (PASO 3).
2. `OpenClawNative.kt` — `ensureGatewayModeLocal()` (`:57`, idempotente), llamada al inicio de `gatewayStart()`, refuerza el campo en cada arranque, cubriendo un config que ya existía de antes.
3. `OpenClawFragment.startGatewayGuarded()` no bloquea con un diálogo "Onboarding pendiente": arranca directo. El botón "⚙ Onboarding" sigue disponible para configurar un proveedor de IA real cuando el usuario quiera.

### Alcance del workspace

OpenClaw usa su propio workspace (`workspaceDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".openclaw/workspace")`, `OpenClawFragment.kt` líneas 28-29), separado de `~/proyectos`. Para "agregar un proyecto" (`showProjectsMenu()`, `ProjectActions.kt`), las únicas fuentes del picker son: symlink/copiar desde Descargas, symlink/copiar desde almacenamiento externo (cualquier carpeta de Android), o symlink desde un proyecto que YA está en `~/proyectos` — no hay opción para navegar/symlinkear una carpeta arbitraria del `$HOME` de Termux. Es una limitación de diseño del picker compartido de Kairos, no de OpenClaw upstream: el proceso de OpenClaw no tiene ningún `cwd` fijo impuesto por el binario, y lanzado manualmente desde cualquier carpeta de Termux vía terminal funciona igual.

## 11. Seguridad

- El diálogo del editor de canales precarga el token guardado en el `EditText` (`OpenClawFragment.kt:428-431`, `setText(existing.optString("token"))`), es decir, **muestra un secreto ya guardado**.
- "Proveedor IA / Modelo" > "Ver config completa" muestra `openclaw.json` completo, que contiene `apiKey` de proveedores, tokens de canales y el token del gateway en texto plano.
- "Mostrar URL con token" y el WebView usan la URL completa con `#token=` (por diseño; la card sí enmascara). `view.filterTouchesWhenObscured = true` contra tapjacking (`:26-29`).
- Ni `openclaw.json` ni sus backups `openclaw.json.pre-*` reciben `chmod 600` en ninguno de los dos caminos (script `:540` y Kotlin `OpenClawNative.kt:224-231`, `:341`); dependen del aislamiento de `$HOME`.
- Superficie de red: gateway enlazado a `loopback`, no expuesto a LAN. Sin túnel.
- El formulario de proveedor custom y de canales escribe vía `org.json` (sin shell). `launchTerminalCommand("cd '$path' && openclaw tui")` (`:125,276`) interpola la ruta entre comillas simples; una ruta con `'` rompería el comando (las rutas vienen del selector de carpetas/`ProjectsManager`, no de texto libre; sin verificar).
- `openclaw update` y `npm install -g openclaw@latest` descargan sin fijar versión ni checksum.

## 12. Limitaciones conocidas

- El flujo de instalación no valida que el binario `openclaw` ejecute (puede quedar `installed=true` con versión `unknown`).
- `NODE_OPTIONS` global en `~/.bashrc` con `--max-old-space-size=5632` y `--require glibc-compat.js` afecta a todo Node ejecutado desde ese shell (también otros módulos); el patrón de saneamiento solo existe para el caso de `glibc-compat.js` borrado.
- La descripción de `modules.json` no menciona canales; el editor de canales cubre solo 4 de ellos (Signal/iMessage/WebChat/plugins quedan fuera, `OpenClawFragment.kt:389-394`).
- "Instalar / actualizar" ejecuta `reinstallModuleService` sin forma de pasar `--force` explícito.
- Sin pruebas de ejecución de que `openclaw tui` arranque con el wrapper bash sobre Node glibc en todos los dispositivos (sin verificar).
