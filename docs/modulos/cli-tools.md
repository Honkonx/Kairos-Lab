# CLI Tools compartidas (Fragment genérico parametrizado)

**Módulo de Kairos** — 14 CLIs de agentes de IA/herramientas por terminal que comparten un único Fragment (`CliToolFragment.kt`) en vez de tener pantalla propia, porque son lo bastante homogéneos (línea de comandos, algunos con login/modelo/prompt propio) como para no justificar 14 Fragments casi idénticos.

---

**Fragment:** `app/src/main/java/com/termux/app/ui/CliToolFragment.kt`
**Config compartida:** `CliModuleConfig` (data class) + `CLI_MODULE_CONFIGS` (mapa `id → CliModuleConfig`), en el mismo archivo
**Módulos que usan este Fragment:** `freebuff`, `codebuff`, `copilotcli`, `minimaxcli`, `mimocode`, `mistralvibe`, `qwencode`, `kimi`, `kilo`, `cursor`, `hf`, `pi`, `codegraph`, `ohmypi`

---

## 1. Por qué un solo Fragment para 14 módulos

Antes de este patrón, estos CLIs caían todos en un Fragment genérico (abrir en terminal + gestionar proyectos + actualizar/desinstalar, sin ningún tratamiento especial) — ninguno tenía botones para login, prompt directo, selección de modelo, ni proveedor IA local, aunque varios de esos CLIs sí soportan esas funciones de verdad. `CliToolFragment` es un superset del Fragment genérico, no un reemplazo: los módulos sin ninguna capacidad extra confirmada (ej. `freebuff`, `codebuff`) quedan exactamente al mismo nivel que el fallback genérico, a propósito — nunca se inventa un botón de login/prompt para un CLI cuyo comando real no está confirmado.

**Regla de diseño**: cada entrada de `CLI_MODULE_CONFIGS` está confirmada leyendo el script de instalación del módulo más la documentación oficial real de cada proyecto — nunca asumida.

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

- **`authCommand` vs `authHint`**: cuando `authCommand` es `null` pero `hasAuth` es `true`, el CLI sí soporta autenticarse (env var de API key y/o un comando dentro de su propia sesión interactiva) pero no hay un subcomando de shell de una sola línea para automatizarlo — el botón "Iniciar sesión" abre el CLI base y muestra `authHint` como toast (caso real: `qwencode`, que requiere `/auth` dentro de la sesión).
- Los placeholders `{PROMPT}`/`{MODEL}` se reemplazan por texto ya escapado para shell de una sola línea antes de pasar a la terminal.
- `localProviderCapable` expone el mismo botón "PROVEEDOR IA LOCAL" (Ollama/llama-server) que existe para `qwencode`/`mimocode`/`mistralvibe` — solo estos 3 CLIs soportan configuración de proveedor local, aunque los 14 comparten el mismo Fragment.

## 3. Tabla real de capacidades por CLI

| Módulo (`id`) | Comando real | Login | Prompt directo | Modelo | Proveedor IA local |
|---|---|---|---|---|---|
| `freebuff` | `freebuff` | — | — | — | — |
| `codebuff` | `codebuff` | — | — | — | — |
| `copilotcli` | `copilot` | `copilot login` | `copilot -p '{PROMPT}'` | — | — |
| `minimaxcli` | `mmx` | `mmx auth login` | `mmx text chat --message '{PROMPT}'` | — | — |
| `mimocode` | `mimo` | `mimo auth login` | `mimo run '{PROMPT}'` | — | ✅ |
| `mistralvibe` | `vibe` | — (config vía archivo/variable de entorno, sin subcomando de shell) | `vibe --prompt '{PROMPT}'` | — | ✅ |
| `qwencode` | `qwen` | Solo interactivo — `/auth` dentro de la sesión | `qwen --prompt '{PROMPT}'` | — | ✅ |
| `kimi` | `kimi` | `kimi login` (device-code flow) | `kimi -p '{PROMPT}'` | `kimi -m '{MODEL}' -p '{PROMPT}'` | — |
| `kilo` | `kilo` | `kilo auth login` | `kilo run '{PROMPT}'` | `kilo run '{PROMPT}' -m '{MODEL}'` | — |
| `cursor` | `cursor-agent` | `cursor-agent login` | `cursor-agent -p '{PROMPT}'` | `cursor-agent -p '{PROMPT}' --model '{MODEL}'` | — |
| `hf` | `hf` | `hf auth login` | — (gestión de modelos/datasets/spaces, no es un agente conversacional) | — | — |
| `pi` | `pi` | — | — | — | — |
| `codegraph` | `codegraph` | — | — (no es un agente de IA, ver nota abajo) | — | — |
| `ohmypi` | `omp` | — (multi-proveedor, sin subcomando de shell confirmado) | `omp -p '{PROMPT}'` (+ `omp -c` continuar sesión) | — | — |

`freebuff`: gratuito, sin cuenta — sin login ni flags de prompt/modelo documentados. `codebuff`: el binario real se descarga en la primera ejecución del launcher npm; la configuración de proveedor/modelo es un asistente TUI interno, sin subcomando de shell confirmado.

`pi` (agente de codificación por terminal): sin flags/login propios confirmados en el código fuente disponible, queda al mismo nivel honesto que `freebuff`/`codebuff`.

`codegraph`: **no es un agente de IA** — es una herramienta de análisis estático/grafo de relaciones entre archivos/funciones/clases/módulos de un proyecto, para navegación y refactor. Se instala como binario ARM64 precompilado + wrapper Node. Expone 5 subcomandos reales: `codegraph query '<symbol>'` (búsqueda de símbolos), `codegraph callers`/`callees`/`impact '<symbol>'` (quién llama a un símbolo, a quién llama, e impacto de modificarlo) y `codegraph affected '<project_path>'` (archivos afectados por cambios pendientes, opera sobre la carpeta del proyecto en vez de un símbolo).

`ohmypi` (comando real `omp`): versión mejorada/standalone de Pi Coding Agent — binario compilado contra glibc, con addons nativos en Rust (AST grep, diff, syntax highlighting, fuzzy find, shell exec), sesiones y soporte MCP. Soporta 3 flags reales: `omp -p "<prompt>"` (one-shot), `omp -c` (continuar la última sesión) y `omp --version`. Se instala siempre vía el método "glibc nativo" (glibc-repo + glibc + un helper compilado que invoca el binario a través del intérprete dinámico glibc) — los métodos alternativos (glibc+proot, proot-distro) no se implementan por agregar overhead sin necesidad real.

## 4. Pantalla real (`CliToolFragment.buildContent()`)

Secciones (cards), condicionales según `CliModuleConfig`:

| Card | Siempre visible | Contenido |
|---|---|---|
| ESTADO | ✅ | ID del módulo, versión instalada (leída del registry), comando base |
| USAR | ✅ | "⌨ Abrir en terminal" + "🗂 Gestionar proyectos" (menú compartido symlink/copiar/sincronizar) |
| CUENTA | Solo si `hasAuth` | "🔑 Iniciar sesión" — corre `authCommand` si existe, si no muestra `authHint` (toast) y abre el CLI base |
| PROMPT DIRECTO | Solo si `supportsDirectPrompt` | "💬 Enviar prompt" — diálogo de texto libre (multilinea); si `hasModelSelector` es true, pide el modelo después (opcional, vacío = default del CLI); si `continueSessionCommand` no es null, agrega también "↻ Continuar última sesión" |
| PROVEEDOR IA LOCAL | Solo si `localProviderCapable` | "⬡ Usar Ollama local" (lista modelos reales ya descargados) + "◍ Usar llama-server local" (lista `.gguf` reales) — nunca un campo de texto libre, siempre modelos reales confirmados |
| MANTENIMIENTO | ✅ | "🔄 Actualizar" + "🗑 Desinstalar" (con checkbox de "desinstalación profunda" — borra también el paquete real, no solo el estado del registry) |

## 5. Registry

Cada módulo escribe su propio prefijo (`<id>.installed`, `<id>.version`, etc.) igual que cualquier otro módulo de script — `CliToolFragment` no introduce un esquema de registry propio, solo lee `<id>.version` para mostrarlo en la card ESTADO.

## 6. Notas de diseño

- **Nunca se inventa sintaxis** — cada `authCommand`/`promptTemplate`/`promptWithModelTemplate` de la tabla está confirmado contra la documentación oficial del proyecto real.
- **Escape de shell**: escapado mínimo de una sola comilla (cierra, agrega comilla escapada literal, reabre — patrón POSIX estándar) sobre el prompt y el modelo antes de interpolarlos en el comando final.
- Si en el futuro se agrega un CLI nuevo a este grupo, la forma correcta es sumar una entrada a `CLI_MODULE_CONFIGS` (no crear un Fragment nuevo).

## 7. Pantalla de instalación compartida

Cuando cualquiera de estos 14 módulos todavía no está instalado, se muestra un modal universal a
todos los módulos de la app (no propio de `CliToolFragment`): botón "Instalar"/"Reinstalar",
switch "Instalación silenciosa" (opt-in, `false` por default — cierra la ventana y avisa al
terminar en vez de bloquear la UI con una barra de progreso), y — solo si el módulo tiene
variantes — un selector de variante antes de instalar. El modal no es cancelable por gesto: solo
el botón "Cancelar" lo cierra, un tap afuera o el botón atrás no deben poder cerrarlo por
accidente mientras la instalación puede seguir corriendo en background.
