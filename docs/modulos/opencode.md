# OpenCode

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación, start/stop y estado los maneja la app vía `ProcessBuilder` → `modulos/opencode.sh`.

---

**Script:** `modulos/opencode.sh` (855 líneas) — copia sincronizada en `app/src/main/assets/scripts/opencode.sh`.
**Puerto:** `:3000` (servidor web principal) + `:4096` (segunda instancia opcional).
**Registry:** prefijo `opencode.*`
**`hasSwitch`:** `false` en `modules.json` — es un CLI/servidor sin toggle en la lista de módulos, pero SÍ tiene start/stop propio desde su propia pantalla (vía `ModuleController`, igual que un módulo con switch).

> Convención: **confirmado** = verificado leyendo el código; **sin verificar** = inferencia sin dispositivo.

---

## 1. Descripción general

OpenCode es un agente de codificación con IA (TUI + servidor web `opencode web`) — soporta Ollama y llama-server locales como proveedores. Kairos lo instala como binario del fork de Termux, levanta el servidor web en `127.0.0.1:3000` (o `:4096`) y lo muestra en un WebView, además de abrir la TUI en la terminal.

El binario oficial está compilado con glibc y no corre directamente sobre Bionic (libc nativa de Android): Kairos usa el mismo enfoque que Claude Code/Codex/Antigravity, glibc de Termux (`glibc-repo`) sin proot ni distro completa. No existe modo proot.

Clasificación: **módulo funcional** (el más completo de los CLIs de IA) — `OpenCodeFragment` (`ModuleDetailNavigator.kt:26`) con servicio real (switch + dropdown de puerto, sesiones tmux, WebView), configuración de proveedores locales (Ollama / llama-server) y panel MCP con toggles. **~16 controles**. Es el único de los CLIs de IA con puerto (`3000`, `webviewUrl`, `tmuxSession:"opencode"`). `modules.json` registra `size:~480MB` y `catalogVersion:"2.0.1"` (catálogo estático; no es la versión real instalada, que sale del registry).

## 2. Permisos

No requiere permisos Android especiales — corre 100% dentro de Termux (glibc de Termux, sin proot, sin acceso a almacenamiento externo salvo lo que el usuario ya concedió globalmente vía el Wizard).

## 3. Instalación — `modulos/opencode.sh`

Flags (`opencode.sh:77-81`): `--silent`, `--force`, `--describe`, `--describe-files`, `--variant glibc|bionic`. `modules.json` refleja `hasVariants:true`, `installMethods:["glibc","bionic"]`, `requires:["glibc"]`.

El script tiene **2 vías + fallback universal** (`opencode.sh:10-35`):
- `--variant glibc` (default): `Honkonx/opencode-termux` rama pure-android, `.pkg.tar.xz` con fallback a `.deb` (`install_opencode_glibc`, `:358`).
- `--variant bionic`: ELF Bionic nativo, rama native-android (`:570`).
- Fallback automático a `wallentx/opencode-termux` (`:647`).

### Pasos (checkpoints en `~/.install_opencode_checkpoint`, con datos intermedios `$CHECKPOINT.data.glibc` para reanudar la descarga)

```
PASO 1  Dependencias glibc: glibc-repo, glibc, openssl-glibc, ncurses
PASO 2  Detectar última versión — GitHub API (api.github.com/repos/<owner>/opencode-termux/releases/latest), con reintento
PASO 3  Descargar paquete (.pkg.tar.xz, fallback .deb) del release detectado
PASO 4  Instalar en Termux (extraer y copiar a $PREFIX, o dpkg -i según formato)
PASO 5  Scripts de control: opencode_start.sh, opencode_stop.sh → ~/scripts/opencode/
PASO 6  Aliases en .bashrc + Registry
```

- Descarga: `curl`/`wget` con timeouts y User-Agent explícito (`_oc_download` `:229`, `_oc_fetch_json` `:243`); selecciona asset `aarch64|arm64` excluyendo `sha256|sig|asc` (`:603`, `:667`). **No se descarga ni verifica ningún checksum** (las únicas apariciones de `sha256` en el script son filtros para excluirlo de la selección de asset).
- Verificación: `verify_binary_installed opencode` en cada vía (`:550`, `:627`, `:696`) antes de registrar; registry `opencode.version/location/port=3000/variant` (`update_registry` `:121-126`).
- `--describe` devuelve el manifiesto JSON declarativo del módulo (id, soporte de `--silent`/`--force`, variantes).

### Comportamientos resueltos (evitar reintroducirlos)

- **`ncurses` obligatorio** — el README de `Honkonx/opencode-termux` (rama `pure-android`, sección "Dependencies") lista `ncurses` como obligatorio ("TUI support") junto a glibc/openssl-glibc — el binario usa `@opentui/solid` para la TUI. Sin `ncurses`, `opencode --version` funciona igual (el PASO 1 pasaba en verde), pero `opencode .` (botón "TUI en terminal") fallaba en runtime.
- **Descarga de metadata GitHub cortada a medias** — en conexión móvil, curl puede devolver éxito con el archivo truncado si el server cierra la conexión, lo que producía "No se encontró binario aarch64 en release" pese a que el release SÍ tenía los assets correctos. Ahora reintenta 1 vez con `sleep 2` entre intentos, y si vuelve a fallar el mensaje de error muestra qué se descargó de verdad.
- **Stop-script completo** — `OpenCodeFragment.kt` ofrece servidor web en `:3000` (sesión tmux `opencode`) y `:4096` (sesión tmux separada `opencode-4096`, iniciada vía `OpenCodeNative.webStart(4096)`, sin entrada propia en `modules.json`). `opencode_stop.sh` mata cualquier sesión `opencode`/`opencode-*`, más un `pkill -f 'opencode web'` de seguridad — y del lado Kotlin, el botón "Detener servidor" llama además a `OpenCodeNative.stopAll()` (enumera y mata todas las sesiones tmux `opencode*` directamente, sin depender de que el script instalado en el dispositivo esté actualizado).
- **Output persistente** — si `opencode web` moría antes del `sleep 2`, la sesión tmux se cerraba con él y el output se perdía. Ahora redirige a `~/kairos_logs/opencode_web.log`.

## 4. Ejecución y detección de estado — `ModuleController.kt`

- `getTmuxSession("opencode")` → `"opencode"` — `isRunning()` chequea `tmux has-session -t opencode`.
- `getModulePort("opencode")` → `3000` (`:1238`) — usado por `waitForPortOpen()` para confirmar arranque real antes de reportar éxito a la UI.
- `getModuleStartScript("opencode")` → `$HOME/scripts/opencode/opencode_start.sh` (`:1165`); `getModuleStopInfo("opencode")` → `.../opencode_stop.sh` (`:1187`).
- El script generado (`opencode.sh:747-783`) arranca `tmux new-session -d -s opencode "cd '$CWD' && BROWSER= opencode web --port 3000 --hostname 127.0.0.1 > ~/kairos_logs/opencode_web.log"`.
- La instancia `:4096` (sesión `opencode-4096`, log `.opencode_web_4096.log`) NO tiene entrada en `ModuleController.kt` ni en `modules.json` — se maneja aparte, directo desde `OpenCodeFragment` vía `OpenCodeNative.webStart()` (`OpenCodeNative.kt:88`, Kotlin puro, sin script intermedio), con cwd opcional. Se mata solo con `stopAll()`.
- TUI: `OpenCodeNative.tuiCmd(path)` → `cd <ruta> && opencode .`.

## 5. Pantalla de la app — `OpenCodeFragment.kt`

Card ESTADO: variante (del registry), versión (leída de `OpenCodeNative.info()`), pill "Web server" (estado real, `isModuleRunning()`), pill "Terminal TUI" (vía `terminalStatusPill()`, helper compartido con otros CLIs).

### Controles de la pantalla

| Control | Qué hace | Notas |
|---|---|---|
| Dropdown+switch "Servidor web" (:3000 / :4096) | Elige puerto, switch arranca/para en ese puerto | Bloqueado mientras está ON — hay que apagar antes de cambiar puerto. Sin relación con la TUI |
| "🌐 Abrir" (visible solo si el server corre) | Abre el WebView en `http://localhost:<puerto>` | — |
| Pill "Terminal TUI" | Solo lectura — sesión de terminal minimizada corriendo | Con opción de cerrar la terminal TUI |
| "⌨ TUI en terminal" | `cd ~ && opencode .` (no `opencode` a secas); pregunta dónde abrir | `OpenCodeNative.tuiCmd()` replica exactamente el comando real |
| "🗂 Gestionar proyectos" | Menú compartido de 4 opciones (`showProjectsMenu`, `ProjectActions.kt`: symlink/importar copia/eliminar/sincronizar todos) sobre `~/proyectos`, con `onLaunchInProject`; prefijo de registry `project_origin` compartido con `ClaudeFragment`/`CodexFragment`/`AntigravityFragment` | — |
| "💬 Enviar prompt (no interactivo)" | Diálogo con prompt de texto libre, checkbox "Continuar la última sesión (--continue)" y campo de modelo opcional (formato `provider/model`, ej. `anthropic/claude-sonnet-5`) → `opencode run [--continue] '<prompt escapado>' [--model '<modelo>']` en la terminal | Según `opencode.ai/docs/cli/`: `opencode run` corre no interactivo con el prompt como argumento posicional, `--model`/`-m` acepta `provider/model`, `--continue`/`-c` continúa la última sesión. Distinto de "TUI en terminal", que abre la interfaz interactiva completa. Prompt/modelo con `shellEscape` |
| "🔑 Configurar proveedor (auth login)" | `opencode auth login` en terminal | Configura API keys de proveedores en la nube (Anthropic, OpenAI, etc.), distinto de "Configurar Ollama/llama-server local" (solo escriben `opencode.json` a un endpoint local, sin login) |
| "🔌 Ver servidores MCP" | Panel nativo (lee `~/.config/opencode/opencode.json`): lista, toggle enabled/disabled, detalle de comando/args/env por servidor | OpenCode soporta un campo `enabled` plano por servidor en su schema |
| "🤖 Configurar Ollama local" | Lee modelos reales de Ollama (`OpenCodeNative.ollamaModels()`) y escribe `~/.config/opencode/opencode.json` con el proveedor elegido | Falla con mensaje claro si Ollama no está corriendo |
| "◍ Configurar llama-server local" | Mismo mecanismo `baseURL` genérico compatible OpenAI, apuntando a `http://127.0.0.1:8085/v1`; lista los `.gguf` ya descargados en `LocalModelManager` | llama-server no tiene `/api/tags` para listar modelos. Ver `llama-server.md` §8. Sin confirmar en dispositivo real |
| "↑ Reinstalar / actualizar" | `reinstallModuleService()` | — |
| "🗑 Desinstalar" (card MANTENIMIENTO) | `confirmUninstallModule()` — plan profundo `rm -f $PREFIX/bin/opencode; rm -rf $PREFIX/lib/opencode` (`ModuleController.kt:895`) | — |

OpenCode cubre `--continue`, el modo `web`/servidor headless, MCP con toggle real y proveedores locales (Ollama/llama-server). No se expone ningún otro flag/subcomando documentado (`opencode debug config`, `--port`/`--hostname` del modo `serve`) por bajo valor sin un caso de uso claro en la app.

## 6. Registry (`~/.android_server_registry`)

```
opencode.installed=true
opencode.version=<versión real de "opencode --version">
opencode.install_date=YYYY-MM-DD
opencode.location=termux_native
opencode.port=3000
opencode.variant=<glibc|bionic>
```

## 7. Comandos de referencia

```bash
opencode-web          # alias -> opencode_start.sh (tmux "opencode", puerto 3000)
opencode-stop         # alias -> opencode_stop.sh (mata todas las sesiones opencode*)
opencode-status       # tmux has-session -t opencode
opencode-tui          # alias -> opencode (TUI directo)
```

## 8. Archivos y rutas

`$PREFIX/bin/opencode`, `$PREFIX/lib/opencode/runtime/opencode`, `~/scripts/opencode/opencode_start.sh` y `opencode_stop.sh`, `~/kairos_logs/opencode_web.log`, `~/.opencode_web.log`, `~/.opencode_web_4096.log`, `~/.config/opencode/opencode.json`, `~/.bashrc` (aliases `opencode-web`, `opencode-status`, entre otros), temporales `~/.opencode_install_tmp`, checkpoint `~/.install_opencode_checkpoint`.

## 9. Seguridad

- Servidor web ligado a `127.0.0.1` (no expuesto a la red). No hay contraseña/token en el arranque (`OPENCODE_SERVER_PASSWORD` no aparece en `opencode.sh` ni `OpenCodeNative.kt`): cualquier app local con acceso a loopback puede hablarle (riesgo real sin verificar).
- Descargas de terceros (`Honkonx/…`, `wallentx/…`) sin checksum. Extracción de `.pkg.tar.xz`/`.deb` a `$PREFIX` con permisos del usuario.
- `opencode.json` de Ollama/llama usa `apiKey` ficticia (`"ollama"`, `"llamaserver"`), no secretos reales.

## 10. Limitaciones conocidas

- **Pérdida de datos**: `ollamaConfig()` y `llamaServerConfig()` crean un `JSONObject` nuevo y hacen `configFile.writeText(...)` (`OpenCodeNative.kt:168-181`, `:200+`), **sobrescribiendo todo** `opencode.json` — se pierden `mcp`, otros providers o ajustes que el usuario o el panel MCP hubieran guardado. `ollamaRemoveConfig()` borra el archivo entero (`:188-191`).
- El servidor `:4096` no tiene entrada en `modules.json` → no lo gestionan Procesos/Monitor/ModuleController.
- `:3000` ignora el cwd elegido (toast `opencode_port_3000_default_cwd`); solo `:4096` respeta carpeta.
- Sin checksum en ninguna vía.
- Servidor web sin autenticación (ver §9).
- `opencode.sh` no tiene una función de actualización incremental (comparar versión actual vs. última release); "Reinstalar / actualizar" reinstala.

## 11. Fecha de alta

`modulos/opencode.sh`: **2026-07-19**.
