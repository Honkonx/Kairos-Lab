# CLI Tools compartidas — Módulo de Kairos (Fragment genérico parametrizado)

**Módulo de Kairos** — 14 CLIs de agentes de IA/herramientas por terminal que comparten un único Fragment (`CliToolFragment.kt`) en vez de tener pantalla propia, porque son lo bastante homogéneos (línea de comandos, algunos con login/modelo/prompt propio) como para no justificar 14 Fragments casi idénticos.

---

**Proyecto:** Kairos
**Fragment:** `app/src/main/java/com/termux/app/ui/CliToolFragment.kt`
**Config compartida:** `CliModuleConfig` (data class) + `CLI_MODULE_CONFIGS` (mapa `id → CliModuleConfig`), en el mismo archivo
**Módulos que usan este Fragment:** `freebuff`, `codebuff`, `copilotcli`, `minimaxcli`, `mimocode`, `mistralvibe`, `qwencode`, `kimi`, `kilo`, `cursor`, `hf`, `pi`, `codegraph`, `ohmypi`

---

## 1. Por qué un solo Fragment para 14 módulos

Estos CLIs caerían todos en `GenericModuleFragment` (abrir en terminal + gestionar proyectos + actualizar/desinstalar, sin ningún tratamiento especial) — ninguno tendría botones para login, prompt directo, selección de modelo, ni proveedor IA local, aunque varios de esos CLIs sí soportan esas funciones de verdad. `CliToolFragment` es un **superset** de `GenericModuleFragment`, no un reemplazo genérico: los módulos sin ninguna capacidad extra confirmada (ej. `freebuff`, `codebuff`) quedan exactamente al mismo nivel que el fallback genérico, a propósito — nunca se inventa un botón de login/prompt para un CLI cuyo comando real no está confirmado.

**Regla de oro del archivo** (documentada en el propio código): cada entrada de `CLI_MODULE_CONFIGS` está confirmada leyendo `modulos/<id>.sh` + la documentación oficial real de cada proyecto — nunca asumida. No agregar una entrada nueva sin confirmar la sintaxis real del CLI.

## 2. El contrato `CliModuleConfig`

```kotlin
data class CliModuleConfig(
    val baseCommand: String,               // binario real (puede diferir del id del módulo)
    val hasAuth: Boolean = false,           // ¿tiene mecanismo de login/autenticación?
    val authCommand: String? = null,        // subcomando de shell real (ej. "kimi login") — null si no hay uno de una sola línea
    val authHint: String? = null,           // texto mostrado si hasAuth=true pero authCommand=null (login solo interactivo)
    val supportsDirectPrompt: Boolean = false,
    val promptTemplate: String = "",        // usa el placeholder literal "{PROMPT}"
    val hasModelSelector: Boolean = false,
    val promptWithModelTemplate: String = "", // usa "{PROMPT}" y "{MODEL}"
    val localProviderCapable: Boolean = false, // ¿puede apuntar a Ollama/llama-server local en vez del proveedor cloud?
    val continueSessionCommand: String? = null // subcomando real para retomar la última sesión (ej. "omp -c") — null si no aplica
)
```

- **`authCommand` vs `authHint`**: cuando `authCommand` es `null` pero `hasAuth` es `true`, el CLI SÍ soporta autenticarse (env var de API key y/o un comando `/algo` dentro de su propia sesión interactiva) pero no hay un subcomando de shell de una sola línea para automatizarlo — el botón "Iniciar sesión" abre el CLI base y muestra `authHint` como toast (caso real: `qwencode`, que requiere `/auth` dentro de la sesión).
- Los placeholders `{PROMPT}`/`{MODEL}` se reemplazan por texto ya escapado para shell de una sola línea (comillas simples, `shellEscape()`) antes de pasar a `launchTerminalCommand()`.
- `continueSessionCommand` expone el flag real de continuar sesión (ej. `-c` de Oh-My-Pi) sin crear un Fragment dedicado.
- `localProviderCapable` muestra el botón "PROVEEDOR IA LOCAL" (Ollama/llama-server). La configuración real de proveedor local corre por `LocalCliProviderNative` (`configureQwenCode`/`configureMimoCode`/`configureMistralVibe`) — solo estos 3 CLIs la soportan, aunque los 14 comparten el mismo Fragment.

## 3. Tabla real de capacidades por CLI

| Módulo (`id`) | Comando real | Login | Prompt directo | Modelo | Proveedor IA local |
|---|---|---|---|---|---|
| `freebuff` | `freebuff` | — | — | — | — |
| `codebuff` | `codebuff` | — | — | — | — |
| `copilotcli` | `copilot` | `copilot login` | `copilot -p '{PROMPT}'` | — | — |
| `minimaxcli` | `mmx` | `mmx auth login` | `mmx text chat --message '{PROMPT}'` | — | — |
| `mimocode` | `mimo` | `mimo auth login` | `mimo run '{PROMPT}'` | — | ✅ |
| `mistralvibe` | `vibe` | — (config vía `config.toml`/`MISTRAL_API_KEY`, sin subcomando de shell) | `vibe --prompt '{PROMPT}'` | — | ✅ |
| `qwencode` | `qwen` | Solo interactivo — `/auth` dentro de la sesión (hint, sin `authCommand`) | `qwen --prompt '{PROMPT}'` | — | ✅ |
| `kimi` | `kimi` | `kimi login` (device-code flow) | `kimi -p '{PROMPT}'` | `kimi -m '{MODEL}' -p '{PROMPT}'` | — |
| `kilo` | `kilo` | `kilo auth login` | `kilo run '{PROMPT}'` | `kilo run '{PROMPT}' -m '{MODEL}'` | — |
| `cursor` | `cursor-agent` (binario real de `cursor.sh`, no `cursor`) | `cursor-agent login` | `cursor-agent -p '{PROMPT}'` | `cursor-agent -p '{PROMPT}' --model '{MODEL}'` | — |
| `hf` | `hf` | `hf auth login` | — (gestión de modelos/datasets/spaces, NO es un agente conversacional) | — | — |
| `pi` | `pi` | — | — | — | — |
| `codegraph` | `codegraph` | — | — (NO es un agente de IA, ver nota abajo) | — | — |
| `ohmypi` | `omp` | — (multi-proveedor, sin subcomando de shell confirmado) | `omp -p '{PROMPT}'` (+ `omp -c` continuar sesión) | — | — |

`freebuff` (github.com/CodebuffAI/freebuff): gratuito, sin cuenta — sin login ni flags de prompt/modelo documentados. `codebuff`: el binario real (`codecane`) se descarga en la primera ejecución del launcher npm; la config de proveedor/modelo es un asistente TUI interno, sin subcomando de shell confirmado.

`pi` (github.com/earendil-works/pi, npm `@earendil-works/pi-coding-agent`): agente de codificación por terminal, sin flags/login propios confirmados en el código fuente disponible; queda al mismo nivel que `freebuff`/`codebuff`.

`codegraph` (github.com/colbymchenry/codegraph): **NO es un agente de IA** — es una herramienta de análisis estático/grafo de relaciones entre archivos/funciones/clases/módulos de un proyecto, para navegación y refactor. Se instala como binario ARM64 precompilado de GitHub Releases + wrapper Node (`exec node .../codegraph.js "$@"`). Sus 5 subcomandos documentados más allá de `init`/`status` están expuestos como botones en `CliToolFragment.kt`:
- `codegraph query '<symbol>'` — búsqueda de símbolos (`symbolQueryTemplate`).
- `codegraph callers '<symbol>'` / `codegraph callees '<symbol>'` / `codegraph impact '<symbol>'` — quién llama a un símbolo, a quién llama, e impacto de modificarlo. El contrato incluye `symbolQueryVariants: List<Pair<String, String>>` (etiqueta, plantilla con `{SYMBOL}`), reusando el mismo diálogo que `query` — mismo patrón que `promptVariants`. `runSymbolQueryButton()` acepta una etiqueta por llamada.
- `codegraph affected '<project_path>'` — archivos afectados por cambios pendientes; no toma un símbolo, opera sobre la carpeta del proyecto. Reusa `projectActionTemplate`/`projectActionLabel` (el mismo campo que usa Oh-My-Pi para "omp commit").

`ohmypi` (github.com/can1357/oh-my-pi, comando real `omp`): versión mejorada/standalone de Pi Coding Agent — binario compilado con `bun build --compile` contra glibc, con addons nativos en Rust (AST grep, diff, syntax highlighting, fuzzy find, shell exec), sesiones y soporte MCP. El README oficial documenta 3 flags reales: `omp -p "<prompt>"` (one-shot), `omp -c` (continuar la última sesión) y `omp --version`. `modulos/ohmypi.sh` instala SIEMPRE vía el método "glibc nativo" (glibc-repo + glibc + un helper compilado con clang que invoca el binario a través de `ld-linux-aarch64.so.1`) — los métodos alternativos (glibc+proot, proot-distro/Ubuntu) no se implementan porque agregan overhead sin necesidad real. El README declara a Pi como dependencia, pero `modulos/ohmypi.sh` no fuerza esa instalación en cascada — la relación se documenta (`requires: ["pi"]` en `modules.json`) sin instalar nada no pedido explícitamente.

## 4. Pantalla real (`CliToolFragment.buildContent()`)

Secciones (cards), condicionales según `CliModuleConfig`:

| Card | Siempre visible | Contenido |
|---|---|---|
| ESTADO | ✅ | ID del módulo, versión instalada (leída del registry, `$moduleId.version`), comando base |
| USAR | ✅ | "⌨ Abrir en terminal" (`launchTerminalCommand(baseCommand)`) + "🗂 Gestionar proyectos" (menú compartido symlink/copiar/sincronizar, con `onLaunchInProject` que hace `cd '<path>' && <baseCommand>`) |
| CUENTA | Solo si `hasAuth` | "🔑 Iniciar sesión" — corre `authCommand` si existe, si no muestra `authHint` (toast) y abre el CLI base |
| PROMPT DIRECTO | Solo si `supportsDirectPrompt` | "💬 Enviar prompt" — diálogo de texto libre (multilinea); si `hasModelSelector` es true, pide el modelo después (`provider/model`, opcional, vacío = default del CLI); si `continueSessionCommand` no es null, agrega también "↻ Continuar última sesión" (ej. `ohmypi` → `omp -c`) |
| PROVEEDOR IA LOCAL | Solo si `localProviderCapable` | "⬡ Usar Ollama local" (lista modelos ya descargados vía `OllamaApiClient.listModels()`) + "◍ Usar llama-server local" (lista `.gguf` vía `LocalModelManager.listModels()`) — nunca un campo de texto libre, siempre modelos reales |
| MANTENIMIENTO | ✅ | "🔄 Actualizar" (`updateModuleService()`) + "🗑 Desinstalar" (con checkbox de "desinstalación profunda" — borra también el paquete real, no solo el estado del registry) |

## 5. Registry

Cada módulo escribe su propio prefijo (`<id>.installed`, `<id>.version`, etc.) igual que cualquier otro módulo de `modulos/*.sh` — `CliToolFragment` no introduce un esquema de registry propio, solo lee `$moduleId.version` para mostrarlo en la card ESTADO.

## 6. Notas de diseño

- **Nunca se inventa sintaxis** — cada `authCommand`/`promptTemplate`/`promptWithModelTemplate` de la tabla está confirmado contra la documentación oficial del proyecto real.
- **Escape de shell**: `shellEscape()` hace el escapado mínimo de una sola comilla (cierra, agrega comilla escapada literal, reabre — patrón POSIX estándar) sobre el prompt y el modelo antes de interpolarlos en el comando final.
- Para agregar un CLI nuevo a este grupo, la forma correcta es sumar una entrada a `CLI_MODULE_CONFIGS` (no crear un Fragment nuevo) y actualizar la tabla de la sección 3.

## 7. Pantalla de instalación compartida

Cuando cualquiera de estos 14 módulos todavía no está instalado, `buildContent()` no dibuja nada de lo anterior — se muestra `BottomSheetInstalacion` (`BottomSheetInstalacion.kt`, `app/src/main/java/com/termux/app/ui/`), universal a TODOS los módulos de la app (no es propia de `CliToolFragment`). Controles: botón "Instalar"/"Reinstalar" (dispara `ModuleController.installModule()`), switch "Instalación silenciosa" (opt-in, `false` por default: cierra la ventana y avisa al terminar en vez de bloquear la UI con una barra de progreso), y — solo si el módulo tiene variantes (`hasVariants`) — un selector de variante antes de instalar. `isCancelable = false` a propósito: solo el botón "Cancelar" cierra la pantalla; un tap afuera o el botón atrás no deben poder cerrarla por accidente mientras la instalación puede seguir corriendo en background.

**Fuera de alcance**: `verificar` (módulo con Fragment propio, `VerificarFragment.kt`, no usa `CliToolFragment`).
