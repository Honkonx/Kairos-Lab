# Claude Code — Módulo de Kairos

**Módulo de Kairos** — instalación gestionada por la app vía `ModuleController.installModule()` → `ProcessBuilder` → `modulos/claude.sh`. Sin switch on/off (no es un servicio persistente, es un CLI que se invoca en la terminal).

---

**Script:** `modulos/claude.sh` (1017 líneas, v4.0.0)
**Fragment:** `ClaudeFragment.kt` (509 líneas; `ModuleDetailNavigator.kt:23`)
**`modules.json`:** `id: "claude"`, `hasSwitch: false`, `terminalCommand: "claude"`, icono `A\`
**Registry prefix:** `claude.*`

---

## 1. Descripción general

Claude Code es el CLI de agente de codificación con IA de Anthropic (`claude`). En Kairos corre 100% dentro de Termux, sin proot — dos métodos de instalación posibles, elegidos por el usuario o pasados por `--variant`. Kairos le da una pantalla con: abrir TUI en carpeta, prompt directo no interactivo (`-p`), continuar/reanudar sesión, diagnóstico, panel MCP nativo, token OAuth fijo, permisos por herramienta y gestión de proyectos.

**Clasificación: MÓDULO FUNCIONAL** (pantalla propia): estado real (versión del registry, pill de estado, pill de terminal), flujos con diálogos encadenados y lectura/escritura de config (`~/.claude.json`, `~/.claude_oauth_token`). Sin switch ni puerto. ~14 controles de UI.

## 2. Permisos

Ninguno específico de Android — solo los ya cubiertos por el wizard general (almacenamiento, para que los proyectos en `~/proyectos`/Download sean accesibles). No expone ningún puerto ni corre en segundo plano — es un binario que se invoca a demanda desde la terminal.

## 3. Variantes de instalación

| Variante | Método | Versión pineada | Cuándo usarla |
|---|---|---|---|
| `native` (recomendada) | Binario ELF real, parcheado con `patchelf` para correr vía `glibc-runner` sobre Bionic | `CLAUDE_VERSION_NATIVE="2.1.272"` (`claude.sh:133`; subida por 2 CVE, GHSA-7835-87q9-rgvv y GHSA-fg94-h982-f3mm, ambos < 2.1.163), o `latest` si se pasa `--version latest` | Default — más rápido, sin overhead de Node.js |
| `legacy` | Paquete npm `@anthropic-ai/claude-code`, corre sobre Node.js | `2.1.111` (fija a propósito, "sin deps extra") | Fallback si `native` falla o el dispositivo tiene problemas con `glibc-runner` |

Ambas variantes también aceptan `--source clean` (descarga limpia, default) o `--source github` (restaura desde un backup en GitHub Releases del propio repo, vía `restore.sh --module claude-native`/`claude`; baja `restore.sh` de `raw.githubusercontent.com/Honkonx/termux-ai-stack/main/restore.sh` y lo ejecuta, `:755-764`, sin hash ni firma).

Flags (`claude.sh:59-65`): `--silent`, `--force`, `--describe`, `--describe-files`, `--variant native|legacy`, `--source clean|github`, `--version latest`. Sin `--variant` en silencioso se asume `native` (`:96-99`). En modo `--silent` el script fuerza `USE_LATEST=true` (`:76`): consulta `downloads.claude.ai/claude-code-releases/latest` y usa el pin solo como respaldo.

## 4. Lógica de instalación (paso a paso, `modulos/claude.sh`)

### Variante `native`
1. **`_ensure_termux()`** — `pkg update` (checkpoint `termux_update`, saltado si `ANDROID_SERVER_READY` ya está seteado por el bootstrap de la app).
2. **`_ensure_glibc()`** (`:394-412`) — instala `glibc-repo` → `pkg update` → `glibc-runner`/`patchelf-glibc`/`jq` (checkpoint `glibc_deps`), agrega `~/.local/bin` al `PATH` en `.bashrc` si falta.
3. **Descarga** (hay dos mecanismos; el primero es el primario):
   - **`_download_wallentx_native()`** (`:532`): descarga `claude-termux-aarch64.tar.gz` de `github.com/wallentx/claude-code-termux/releases/latest` (`:148`), extrae `claude`, `claude.glibc` y `claude-termux-update`, con backup/rollback y verificación `--version`. SHA256 **best-effort** (si el `.sha256` no existe continúa sin verificar, `:561-575`).
   - **`_download_and_patch_native()`** (`:460`) — mecanismo clásico, si el anterior falla (o `_install_native_github()` si `--source github`): resuelve la versión (fija o `latest` vía `downloads.claude.ai/claude-code-releases/latest/manifest.json`); descarga el binario ARM64 (`linux-arm64/claude`); **verifica SHA256** contra el `manifest.json` real — si no coincide, borra el binario y reintenta con el pin; si el manifest no se puede leer, solo avisa y sigue sin verificar (`:478-492`); `chmod +x` + `patchelf --set-interpreter` apuntando a `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` (lo que le permite correr un ELF glibc real sobre Bionic sin proot).
   - Genera un wrapper en `~/.local/bin/claude` que hace `unset LD_PRELOAD` antes de ejecutar el binario real (necesario para que el intérprete parcheado no herede `LD_PRELOAD` de Termux, que rompería el binario glibc).
   - Escribe `~/.claude/settings.json` (ver sección 6).
   - Actualiza el registry y verifica que `claude --version` responda antes de dar la instalación por buena; si falla escribe `installed=false reason=binario_no_ejecuta` (`:740-749`, `:615-625`).

### Variante `legacy`
1. `_ensure_termux()` (igual que arriba).
2. `_ensure_nodejs()` (`:373`) — instala `nodejs-lts` si no hay Node ≥18, más `npm install -g npm`.
3. `_install_legacy_clean()` (o `_install_legacy_github()`) — **4 estrategias en cascada** (`:796`), cada una verificada con `_validate_legacy_cli()` (que el `cli.js` exista, no esté vacío, y `node cli.js --version` no tire `SyntaxError`/`not found`) antes de darse por satisfecha:
   1. `npm install -g @anthropic-ai/claude-code@2.1.111 --save-exact` directo.
   2. Si falla: mismo `npm install` con `--ignore-scripts`.
   3. Si sigue fallando: descarga el tarball directo de `registry.npmjs.org` y lo extrae a mano.
   4. Si sigue fallando: repara solo `cli.js` desde un backup en GitHub Releases del propio repo (`part2-claude`).
   - Genera el wrapper `$PREFIX/bin/claude` (`node cli.js` con `DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1`), escribe `settings.json` y aliases (`claude`, `claude-check`).

## 5. Detección de estado (`_detect_method()`/`_detect_version()`)

No depende del registry para decidir si ya está instalado — chequea el filesystem real en cada corrida:
- `native`: existe y es ejecutable `~/.local/share/claude-code/claude`.
- `legacy`: existe `<npm root global>/@anthropic-ai/claude-code/cli.js`.
- `broken`: existe el wrapper pero no el binario/paquete real detrás — dispara reinstalación.
- `none`: nada de lo anterior.

Si ya está instalado con el método pedido (o cualquiera, si no se especifica `--variant`) y no hay `--force`, el script **igual re-sincroniza el registry** antes de salir — evita que un binario funcionando quede mostrando "No instalado" en la app por un registry desactualizado.

Del lado de la app, Claude Code es un módulo **sin switch** — `isModuleInstalled()` en `ClaudeFragment.kt` es lo único que gatea la UI, sin concepto de "corriendo/detenido".

## 6. `settings.json`

`~/.claude/settings.json` NO escribe una key `"autoUpdates": false` — esa key **no existe en el schema real** de Claude Code (según `code.claude.com/docs/en/settings` y los issues `anthropics/claude-code#10079`/`#56723`). En su lugar:
```json
{
  "env": {
    "DISABLE_AUTOUPDATER": "1",
    "DISABLE_UPDATES": "1"
  }
}
```
(la variante `native` agrega además `LD_PRELOAD` apuntando a `libtermux-exec-ld-preload.so` dentro de `env`). Sin esto, el binario `native` (parcheado con `patchelf`) puede autoactualizarse y sobreescribirse con un ELF sin parchear que ya no corre en Bionic — el fallo aparece en un uso posterior, no en la instalación misma.

## 7. Pantalla de la app (`ClaudeFragment.kt`)

| Control | Qué hace | Por qué |
|---|---|---|
| Card ESTADO (Método/Versión/Estado/Terminal) | Solo lectura: método (texto fijo `native·glibc`, aunque se haya instalado legacy, `:49`), versión (`claude.version` del registry), pill "Estado" (`isTermuxBinaryAvailable("claude")`), pill/botón cerrar terminal | — |
| Card CUENTA — TOKEN OAUTH FIJO | "🔑 Usar token OAuth fijo" (`promptSetOauthToken`), "🚫 Quitar token fijo" (`confirmClearOauthToken`, con confirmación); aviso ámbar "⚠ Usando token OAuth fijo (no la cuenta de sesión)" si existe `~/.claude_oauth_token` | Ver sección 9 |
| "▶ Abrir en directorio raíz (~)" | Diálogo ¿dónde abrir? → `ClaudeNative.openCmd(null)` → abre terminal | Elige el comando real según el método detectado (native necesita `unset LD_PRELOAD`, legacy invoca node sobre `cli.js`) — un `claude` desnudo podía no arrancar en ninguno de los dos casos |
| "▶ Abrir en proyecto" | Lista proyectos reales vía `ProjectsManager.projectsList()` (Kotlin nativo, symlinks vía `android.system.Os` + registry con file locking) y abre en el elegido | — |
| "📁 Gestionar proyectos" | Menú compartido symlink desde Download / eliminar / sincronizar todos | — |
| "💬 Enviar prompt (no interactivo)" | Pide prompt → modelo (`Default` omite `--model`, o `sonnet`/`opus`/`haiku`/`fable`) → `--permission-mode` (`askPermissionModeThenSend()`: `Default (settings.json)` omite el flag, o `default`/`acceptEdits`/`plan`/`auto`/`dontAsk`/`bypassPermissions`; `manual` se omite por ser solo alias de `default`) → `-p '<prompt escapado>' --model <alias> --permission-mode <modo>` abierto en la terminal vía `ClaudeNative.openCmd(null, extraArgs)` | Modo headless del CLI (`-p`/`--print`), valores confirmados contra `code.claude.com/docs/en/cli-reference`. `bypassPermissions` está disponible sin advertencia adicional visible (`ClaudeFragment.kt:402`) |
| "🔧 Permisos de herramientas" (card PROMPT DIRECTO) | Diálogo con 2 `EditText` (permitir/denegar, texto libre separado por comas — la lista real depende de qué MCP/servers tenga cada usuario); `buildToolPermissionArgs()` construye `--allow-tool '<tool>'` / `--deny-tool '<tool>'` repetido por herramienta (`:421-422`), aplicado al flujo de prompt directo | Ver limitaciones: verificar los nombres de flag |
| "↻ Continuar última conversación" | `openClaudeHere(null, "--continue")` | — |
| "🕒 Reanudar sesión…" | `openClaudeHere(null, "--resume")` (picker interactivo del propio CLI) | — |
| "🔍 claude doctor" | `runCaptureDialog("doctor", ...)` — diagnóstico de solo lectura (instalación/settings/Remote Control) en background vía `ManagerNativeUtils.runShell()` (timeout 60 s), resultado en `AlertDialog` | Mismo patrón que `CodexFragment.runExecCapture`; sin abrir terminal para un output de una sola pasada |
| "🔑 Estado de autenticación" | `runCaptureDialog("auth status", ...)` — imprime JSON con el estado de sesión | — |
| "🔌 Ver servidores MCP" | `showMcpPanel()` lee `~/.claude.json` directo vía `ClaudeNative.mcpServers()` (sin invocar el CLI) y lo muestra en un panel nativo (solo lectura); `claude mcp list` en terminal queda como alternativa explícita dentro del panel (`onOpenTerminal`) | Premisa de la app: poder hacer todo desde la interfaz sin abrir la terminal |
| Botón Engram | `engramSetupButton("claude-code")` — configura memoria persistente Engram para este CLI | — |
| "⚙ Instalar / cambiar método" | `reinstallModuleService()` (sin `--force`/`--variant`, `:197-201`) | Ver sección 11 |
| "🗑 Desinstalar" | `confirmUninstallModule()` — botón suelto (no `addMaintenanceCard()` completo) para no duplicar "Instalar / cambiar método" | — |

Anti-tapjacking: `filterTouchesWhenObscured=true` (`:36`).

`ClaudeNative.openCmd(project, extraArgs)` (`app/src/main/java/com/termux/app/util/ClaudeNative.kt:109`) arma el comando final: `[CLAUDE_CODE_OAUTH_TOKEN=...] export DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1; unset LD_PRELOAD; "<wrapper>" [extraArgs]` (native) o `... node cli.js` (legacy), con `cd "<proyecto>" &&` si se eligió carpeta. `extraArgs` es argv adicional ya armado/escapado por el caller, concatenado después del binario/`cli.js` y antes del `cd`, para que el mismo comando funcione tanto en `native` como en `legacy`. Las variables `DISABLE_AUTOUPDATER`/`DISABLE_UPDATES` se exportan inline porque `ManagerNativeUtils.runShell()` corre `bash -c <cmd>` no interactivo, que no sourcea `.bashrc` (donde `claude.sh` las deja para la terminal interactiva).

El prompt directo se manda con escapado mínimo de comilla simple (`shellEscape`, `'` → `'\''`, `:359`) — mismo patrón que `CliToolFragment.shellEscape()`; `--permission-mode` viene de lista cerrada.

## 8. Registry (`~/.android_server_registry`)

```
claude.installed=true
claude.version=<x.y.z>
claude.method=native|legacy
claude.install_date=<YYYY-MM-DD>
claude.location=termux_native
```
(`registry_install claude ...`, `:264`.)

## 9. Token OAuth fijo (`CLAUDE_CODE_OAUTH_TOKEN`)

Permite que Claude Code abra siempre con la cuenta de un token fijo en vez de la cuenta asociada al login interactivo de la sesión.

**Hechos de Claude Code** (`code.claude.com/docs/en/authentication`):
- El token se genera con `claude setup-token` — requiere plan Pro/Max/Team/Enterprise; es un token OAuth de larga duración pensado para CI/scripts sin navegador. Kairos no lo automatiza (requiere el flujo OAuth interactivo del propio CLI): el usuario lo pega a mano.
- Se activa con `export CLAUDE_CODE_OAUTH_TOKEN="..."`. Cuando está seteada, Claude Code la usa en silencio en vez de las credenciales de `~/.claude/.credentials.json`.
- Límites: (a) scoped solo a inferencia — **no puede abrir sesiones de Remote Control**; (b) hay un issue abierto (`anthropics/claude-code#16238`) pidiendo que Claude Code avise cuando esta variable pisa las credenciales normales — por eso Kairos lo deja explícito en su UI.

**Código**:
- `ClaudeNative.kt`: `oauthTokenFile` (`~/.claude_oauth_token`), `hasOAuthToken()`, `writeOAuthToken(token)` (escribe el archivo + `chmod 600` + agrega un bloque delimitado por el marcador `# Claude Code OAuth token fijo (Kairos)` a `~/.bashrc`, con dedupe), `clearOAuthToken()` (borra el archivo + filtra cualquier línea que mencione el marcador o `CLAUDE_CODE_OAUTH_TOKEN` de `.bashrc`) y `oauthTokenPrefix()` (privado) — antepone `export CLAUDE_CODE_OAUTH_TOKEN="$(cat ...)"; ` al comando armado por `openCmd()` en AMBAS ramas (native y legacy), cubriendo tanto la terminal interactiva como las llamadas en background.
- `ClaudeFragment.kt`: la card "CUENTA — TOKEN OAUTH FIJO" (botones de usar/quitar y aviso ámbar, `oauthStatusSlot` + `refreshOauthStatus()`).
- La UI nunca muestra el token. El valor no queda literal en el comando pero sí aparece en el entorno del proceso hijo (visible vía `/proc/<pid>/environ` solo para el mismo uid; riesgo bajo).

## 10. Archivos y rutas

`~/.local/share/claude-code/claude` (binario), `~/.local/share/claude-code/claude.glibc` y `claude-termux-update` (método wallentx), wrapper `~/.local/bin/claude` (native) o `$PREFIX/bin/claude` (legacy), `~/.claude/settings.json` (env: `DISABLE_AUTOUPDATER`, `DISABLE_UPDATES`, `LD_PRELOAD` termux-exec), `~/.claude_oauth_token` (chmod 600), bloque marcado en `~/.bashrc`, checkpoint `~/.install_claude_checkpoint`, temporales `~/.claude_wallentx_dl.tar.gz` / `~/.claude_wallentx_extract`.

## 11. Seguridad y limitaciones conocidas

- Descarga: el método wallentx usa un repo de terceros y ejecuta un binario sin firma (SHA256 opcional), y `restore.sh` no se verifica → cadena de confianza débil (sin `curl|bash` literal, pero sí descarga+exec). Checksum no obligatorio en 2 de 3 caminos.
- El comentario de fallback en `claude.sh:~675` todavía dice "2.1.152" (desactualizado; el valor pineado es 2.1.272).
- **Prefijo de registry**: la UI siempre lee `claude.*` (no `claude_code.*`).
- **Conflicto de métodos**: si el usuario tiene `legacy` instalado y pide `native` (o viceversa) en modo `--silent`, el script desinstala el método viejo automáticamente antes de instalar el nuevo (en modo interactivo, pregunta primero).
- **Botón "Instalar / cambiar método" es efectivamente un no-op si ya está instalado**: `ModuleController.installModule()` no puede pasar `--force`/`--variant`, y el script sale temprano si ya detecta el método instalado; no permite cambiar de método ni actualizar si ya hay binario.
- La fila "Método" está hardcodeada a `native·glibc` (`ClaudeFragment.kt:49`) aunque la instalación sea legacy.
- Inferencia (alta): `--allow-tool/--deny-tool` (`ClaudeFragment.kt:421-422`) son flags de GitHub Copilot CLI; Claude Code usa `--allowedTools`/`--disallowedTools` según lo que se conoce. El comentario del código afirma haberlo confirmado contra la documentación, pero no se contrastó ejecutando `claude --help`; verificar en dispositivo antes de confiar en el botón.
- Inferencia: el módulo no declara `hasVariants:true` en modules.json aunque el script tiene native/legacy → la app no ofrece elegir variante al instalar.
- El flujo OAuth fijo y los permisos por herramienta no se probaron en dispositivo.

## 12. Fecha de alta

`modulos/claude.sh`: **2026-07-19** (en este repo; el módulo existía antes en el proyecto de scripts original).
