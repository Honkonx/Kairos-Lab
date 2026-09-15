# BRIDGE_API.md — Bridges reales de Kairos

Hay 2 mecanismos separados y sin relación entre sí, documentados abajo.

## 1. `TermuxBridgeAdapter.kt` — bridge de SESIONES de terminal

Implementa la interfaz `TermuxBridge` (`com.termux.rn`, bridge legacy) — instanciado una vez
por `TermuxService.java` (`BridgeSingleton.setBridge(new TermuxBridgeAdapter(this))`). Su
alcance es exclusivamente sesiones de terminal, NO módulos:

| Método | Args | Returns | Descripción real |
|--------|------|---------|-------------|
| `getSessions` | — | `List<SessionInfo>` | Lista las sesiones activas (`TermuxService.termuxSessions`) — título, shellPath, cwd, isRunning, pid, handle |
| `createSession` | shellPath, cwd? | `String?` (handle) | Crea una sesión nueva vía `TermuxService.createTermuxSession()` |
| `writeToSession` | sessionId, text | `Boolean` | Escribe texto a una sesión existente por índice |
| `resizeSession` | sessionId, cols, rows | `Boolean` | Redimensiona el PTY de una sesión |
| `killSession` | sessionId | `Boolean` | Termina una sesión (`finishIfRunning()`) |

No tiene ningún método de instalar/iniciar/detener módulos, ni de leer el registry, ni de
info del sistema — eso vive en un lugar completamente distinto (sección 2).

## 2. `ModuleController.kt` — control de módulos (SIN capa de bridge)

Objeto Kotlin (`object ModuleController`) llamado DIRECTO desde los Fragments — no implementa
ninguna interfaz de bridge, no pasa por `TermuxBridgeAdapter`. Cada Fragment de módulo
(`OllamaFragment.kt`, `N8nFragment.kt`, etc.) lo invoca directamente.

| Función real | Firma | Descripción |
|---|---|---|
| `installModule` | `(moduleId, variant, onProgress, onComplete)` | `ProcessBuilder("bash", script, "--silent"[, "--variant", variant])`, log línea por línea a `installLogFile(moduleId)` y al callback |
| `startModule` | `(moduleId, onResult)` | Arranca el módulo (tmux/proceso según el tipo) |
| `stopModule` | `(moduleId, onResult)` | Detiene el módulo |
| `isRunning` | `(moduleId): Boolean` | Verificación EN VIVO — `tmux has-session` para módulos tmux-backed (ollama/n8n/openclaw/opencode), `pgrep -x` para módulos por proceso (remote/sshd), false fijo para CLIs sin switch (claude/codex/antigravity/python/hermes/expo) |
| `installLogFile` | `(moduleId): File` | Ruta del log persistente de instalación (`~/kairos_logs/install_<id>.log`) |
| `autoStartEligibleModules` | `(context, onEach)` | Se llama al abrir la app — arranca automáticamente los módulos marcados para auto-inicio (evento "al abrir la app", sin boot receiver) |

Módulos según cómo se determina si están corriendo (ver `getTmuxSession()`/`getProcessName()`
dentro de `ModuleController.kt` para el mapeo real completo, es la fuente de verdad — un
módulo con switch en `modules.json` pero sin entrada en esos mapas se ve togglable en la UI
pero falla silenciosamente).

## 3. `ModuleRegistry.kt` — lectura del registry

```kotlin
class ModuleRegistry(context: Context) {
    fun load(): ModuleRegistry          // lee $HOME/.android_server_registry línea por línea
    fun get(key: String): String?       // valor de una key puntual
    fun getModules(): Map<String, String>  // mapa plano completo
    fun isProotInstalled(): Boolean     // chequea $PREFIX/bin/proot
}
```

- Ruta real: `$HOME/.android_server_registry` (vía `TermuxConstants.TERMUX_HOME_DIR_PATH`,
  NO `context.filesDir`).
- Formato real: `clave=valor` por línea, escrito por los scripts bash (`modulos/*.sh`) durante
  la instalación — keys reales tipo `<modulo>.installed=true`, `<modulo>.version=X.Y.Z`,
  `<modulo>.install_mode=gpu`, etc. (el prefijo exacto varía por módulo, ver
  `docs/modulos/<MODULO>.md` para la lista real de keys de cada uno).
- **NO hay un campo `.status=RUNNING/STOPPED` persistido acá** — el estado "corriendo" nunca
  se guarda en el registry, se calcula en vivo cada vez vía `ModuleController.isRunning()`
  (sección 2).
- `getModules()` devuelve un `Map<String, String>` plano — NO hay una clase `ModuleInfo[]`
  poblada desde acá; `ModuleInfo` (ver `model/ModuleInfo.kt`) es una data class distinta,
  poblada desde `modules.json` + el registry combinados, con
  `enum Status { NOT_INSTALLED, INSTALLED_STOPPED, RUNNING, INSTALLING, ERROR }`.

## Contrato real de `ProcessBuilder` (usado por `ModuleController` y cada Fragment que corre scripts)

```kotlin
val pb = ProcessBuilder("bash", script, "--silent")
pb.applyTermuxEnv()   // ver util/ProcessBuilderExt.kt — HOME/PREFIX/PATH/LD_LIBRARY_PATH/SHELL
                       // el proceso Java de la app NO hereda el PATH de Termux por defecto
pb.redirectErrorStream(true)
val process = pb.start()
```

- `applyTermuxEnv()` es obligatorio para cualquier `ProcessBuilder` que invoque un binario de
  Termux (bash, python3, git, tmux, pgrep, adb, etc.) — sin esto, el proceso Java hereda el
  PATH de Android, no el de Termux, y falla con `Cannot run program ...: error=2`.
- Exit code se verifica post-ejecución; algunos callers también usan
  `decodeExitSignal()` (`ProcessBuilderExt.kt`) para traducir exit codes 128+N a nombres de
  señal POSIX reales (ej. "SIGSEGV") en vez de mostrar el número crudo.
