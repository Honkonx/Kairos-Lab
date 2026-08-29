package com.termux.app.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.data.ModuleRegistry
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.LocalCliProviderNative
import com.termux.app.util.LocalModelManager
import com.termux.app.util.OllamaApiClient
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.showProjectsMenu
import org.json.JSONObject

/**
 * Describe qué soporta realmente cada CLI de agente de IA que cae en [CliToolFragment] —
 * confirmado leyendo cada `modulos/<id>.sh` + docs oficiales reales de cada proyecto (nunca
 * asumido), ver reporte de la ronda 2026-08-17 (docs/humano/ pendiente de numeración) que
 * originó este archivo. NO agregar una entrada nueva sin confirmar la sintaxis real del CLI.
 *
 * - [authCommand] es un subcomando real de shell confirmado (ej. "kimi login"). Cuando es
 *   `null` pero [hasAuth] es `true`, el CLI SÍ soporta autenticarse (env var de API key y/o un
 *   comando `/algo` dentro de su propia sesión interactiva) pero no hay un subcomando de shell
 *   de una sola línea para automatizarlo — el botón abre el CLI base y muestra [authHintRes].
 * - [promptTemplate]/[promptWithModelTemplate] usan los placeholders literales "{PROMPT}" y
 *   "{MODEL}", reemplazados por texto ya escapado para shell de una sola línea (comillas
 *   simples) antes de pasarse a [BaseModuleFragment.launchTerminalCommand].
 * - [localProviderCapable] replica el mismo botón "PROVEEDOR IA LOCAL" (Ollama/llama-server)
 *   que ya vive en GenericModuleFragment.kt para qwencode/mimocode/mistralvibe (investigación
 *   2026-08-13, ver docs/humano/humano116.md) — se repite acá porque esos 3 módulos migran de
 *   fragment, no porque el original se haya tocado.
 */
// Nota i18n: los campos de texto puro (labels/hints que el usuario LEE) se guardan como
// @StringRes Int en vez de String — CLI_MODULE_CONFIGS es un val de nivel de archivo,
// evaluado al cargar la clase, sin Context de Android disponible todavía para llamar
// getString(). Un R.string.xxx es solo un Int constante (no requiere Context), así que la
// resolución real a texto (getString(config.xxxRes)) ocurre recién en CliToolFragment,
// donde el Fragment ya tiene Context. Los campos que son comandos de shell reales
// (baseCommand, authCommand, promptTemplate, etc.) se dejan como String — NUNCA se
// traducen, romperían la ejecución del CLI.
data class CliModuleConfig(
    val baseCommand: String,
    val hasAuth: Boolean = false,
    val authCommand: String? = null,
    val authHintRes: Int? = null,
    val supportsDirectPrompt: Boolean = false,
    val promptTemplate: String = "",
    val hasModelSelector: Boolean = false,
    val promptWithModelTemplate: String = "",
    val localProviderCapable: Boolean = false,
    // Subcomando real de shell de una sola línea para retomar la última sesión
    // (ej. "omp -c" de Oh-My-Pi, confirmado en su README oficial) — null si el
    // CLI no soporta esto o no hay evidencia de un flag real.
    val continueSessionCommand: String? = null,
    // Plantilla de shell de una sola línea para una acción real de "analizar proyecto"
    // (herramientas de análisis estático como CodeGraph, no agentes de chat) — usa el
    // placeholder literal "{PROJECT_PATH}", reemplazado por la carpeta elegida en
    // showProjectsMenu(). Distinto de [promptTemplate]: no abre una sesión interactiva
    // de IA, corre un subcomando real documentado (ej. "codegraph init"/"codegraph
    // status") sobre la carpeta del proyecto. null si el CLI no tiene esta capacidad.
    val analyzeProjectTemplate: String? = null,
    // Bug real confirmado 2026-08-24 por PRUEBA REAL en el dispositivo, vía la terminal
    // real de la app (no solo por ADB directo, ver docs/humano216.md): toda sesión de
    // terminal real de Kairos hereda LD_PRELOAD=.../libtermux-exec-ld-preload.so (confirmado
    // con "env | sort" DENTRO de una sesión real) — necesario para que los shims npm con
    // shebang "#!/usr/bin/env" (ver bug #8, ExpoFragment.kt) resuelvan bien, pero ROMPE
    // cualquier binario glibc-patcheado (patchelf --set-interpreter) o basado en Bun:
    // "codebuff" reventaba con "error while loading shared libraries: .../glibc/lib/libc.so:
    // invalid ELF header" y "freebuff" con un segfault real de Bun ("panic(main thread):
    // Segmentation fault") — ambos confirmados arreglados con "unset LD_PRELOAD;" antes del
    // comando, mismo patrón ya usado en ClaudeNative.kt para el mismo tipo de conflicto.
    // true para los módulos glibc+patchelf o Bun de esta tabla (freebuff/codebuff/
    // copilotcli/mimocode/kilo/cursor/pi/ohmypi) — false (default) para los npm-shim puros,
    // que SÍ necesitan LD_PRELOAD activo.
    val needsGlibcCompat: Boolean = false,

    // ── Campos agregados 2026-08-25 (investigación de 4 agentes en paralelo, ronda pedida
    // explícitamente por el usuario: "faltan opciones... investiga a profundidad") ──────────

    // Comandos de una sola línea, de solo información/utilidad (sin argumentos libres del
    // usuario) — mostrados como botones en una card nueva "MÁS OPCIONES". Ej. "kimi mcp list",
    // "kilo stats", "cursor-agent status", "copilot plugins list", "mmx quota",
    // "mimo session list", "hf cache ls". Cada Pair es (etiqueta del botón como @StringRes,
    // comando real sin traducir — ver nota i18n arriba).
    val extraCommands: List<Pair<Int, String>> = emptyList(),

    // Instalar una extensión/plugin por fuente libre (path/npm/git) — placeholder "{SOURCE}".
    // Confirmado real para Pi ("pi install <source> -l") y reusado con otro sentido semántico
    // para HF ("hf download <repo_id>", ver [installExtensionLabelRes]). null = no soportado.
    val installExtensionTemplate: String? = null,
    val installExtensionLabelRes: Int = R.string.clitool_cfg_default_install_ext_label,
    val installExtensionHintRes: Int = R.string.clitool_cfg_default_install_ext_hint,

    // Acción real de una sola línea que corre DENTRO de un proyecto elegido (distinto de
    // [analyzeProjectTemplate]: ese es para herramientas de análisis estático, esto es para
    // acciones puntuales tipo "generar un commit" que no encajan en esa etiqueta). Placeholder
    // "{PROJECT_PATH}". Ej. Oh-My-Pi: "cd '{PROJECT_PATH}' && omp commit".
    val projectActionLabelRes: Int? = null,
    val projectActionTemplate: String? = null,

    // Búsqueda de una sola línea con texto libre — placeholder "{SYMBOL}", reemplazado por
    // texto libre del usuario (mismo escapado que runPrompt()). Confirmado real para CodeGraph
    // ("codegraph query <término>") y reusado con otro sentido para HF ("hf models ls
    // --search", ver [symbolQueryLabelRes]).
    val symbolQueryTemplate: String? = null,
    val symbolQueryLabelRes: Int = R.string.clitool_cfg_default_search_symbol_label,
    val symbolQueryHintRes: Int = R.string.clitool_cfg_default_search_symbol_hint,

    // Variantes adicionales de búsqueda por símbolo — mismo diálogo/hint que [symbolQueryTemplate]
    // pero con su propia etiqueta y comando. Confirmado real para CodeGraph
    // ("codegraph callers/callees/impact <symbol>", README oficial, antes deliberadamente no
    // expuestos "para no inflar el Fragment" — se agregan ahora reusando este mecanismo en vez
    // de uno nuevo por variante). Pair(etiqueta como @StringRes, comando real).
    val symbolQueryVariants: List<Pair<Int, String>> = emptyList(),

    // Modo de aprobación (Qwen Code "--approval-mode=<modo>") — se antepone al comando base al
    // elegir uno de [approvalModeOptions] (valores reales del flag, no etiquetas traducidas).
    val approvalModeFlag: String? = null,
    val approvalModeOptions: List<String> = emptyList(),

    // Variantes de "enviar prompt" más allá del chat de texto genérico — cada Triple es
    // (etiqueta del botón, plantilla con placeholder "{PROMPT}", hint del EditText). Ej.
    // MiniMax: generar imagen ("mmx image '{PROMPT}'") y voz ("mmx speech synthesize --text
    // '{PROMPT}' --out ~/minimax_speech.mp3"). Se muestran junto al botón de prompt normal.
    // Triple(etiqueta como @StringRes, plantilla de comando real, hint como @StringRes).
    val promptVariants: List<Triple<Int, String, Int>> = emptyList(),

    // Scaffolding de proyecto nuevo desde plantilla — placeholders "{TEMPLATE}" y "{NAME}".
    // Confirmado real para Freebuff/Codebuff (docs/modulos/FREEBUFF.md, CODEBUFF.md,
    // github.com/CodebuffAI/freebuff): "freebuff --create <template> <name>" genera un
    // proyecto nuevo a partir de una plantilla con nombre. null = no soportado.
    val createFromTemplateTemplate: String? = null,
    val createFromTemplateLabelRes: Int = R.string.clitool_cfg_default_create_template_label
)

val CLI_MODULE_CONFIGS: Map<String, CliModuleConfig> = mapOf(
    // Sin login (gratuito, sin cuenta — confirmado github.com/CodebuffAI/freebuff) y sin
    // flag de prompt directo/modelo documentado — queda al nivel de GenericModuleFragment
    // (abrir + proyectos), honestidad sobre lo que el CLI realmente ofrece.
    // Ronda 2026-08-25: confirmado real "freebuff --create <template> <name>" (scaffolding
    // de proyecto, ver docs/modulos/FREEBUFF.md) — se agrega como createFromTemplateTemplate.
    "freebuff" to CliModuleConfig(
        baseCommand = "freebuff",
        needsGlibcCompat = true,
        createFromTemplateTemplate = "freebuff --create '{TEMPLATE}' '{NAME}'"
    ),

    // El binario real ("codecane") solo se descarga en la primera ejecución del launcher npm;
    // la config de proveedor/modelo es un asistente TUI interno (`codebuff` sin subcomando de
    // shell confirmado para login ni flags de prompt/model documentados).
    // Ronda 2026-08-25: confirmado real "codebuff --create <template> <name>" (mismo mecanismo
    // de scaffolding que Freebuff, ver docs/modulos/CODEBUFF.md).
    "codebuff" to CliModuleConfig(
        baseCommand = "codebuff",
        needsGlibcCompat = true,
        createFromTemplateTemplate = "codebuff --create '{TEMPLATE}' '{NAME}'"
    ),

    // GitHub Copilot CLI — docs.github.com confirma "copilot login" (subcomando real) y
    // "-p"/"--prompt" para modo no interactivo de un solo prompt.
    // Ronda 2026-08-25: docs.github.com/en/copilot confirma "copilot plugins list" (inventario
    // real de plugins/MCP/skills instalados, solo lectura). Sin equivalente de --resume/
    // --continue documentado — no se inventa uno.
    "copilotcli" to CliModuleConfig(
        baseCommand = "copilot",
        hasAuth = true,
        authCommand = "copilot login",
        supportsDirectPrompt = true,
        promptTemplate = "copilot -p '{PROMPT}'",
        needsGlibcCompat = true,
        extraCommands = listOf(R.string.clitool_cfg_extra_plugins_mcp to "copilot plugins list")
    ),

    // MiniMax CLI (mmx) — platform.minimax.io confirma "mmx auth login" y el subcomando
    // "mmx text chat --message" para enviar un prompt de texto sin entrar al modo interactivo.
    // Ronda 2026-08-25: investigación confirmó que MiniMax CLI es multi-modal completo, no
    // solo chat de texto (github.com/MiniMax-AI/cli) — imagen/voz/cuota son subcomandos reales
    // de una sola línea, se agregan como promptVariants/extraCommands en vez de un botón chico.
    "minimaxcli" to CliModuleConfig(
        baseCommand = "mmx",
        hasAuth = true,
        authCommand = "mmx auth login",
        supportsDirectPrompt = true,
        promptTemplate = "mmx text chat --message '{PROMPT}'",
        promptVariants = listOf(
            Triple(R.string.clitool_cfg_generate_image_label, "mmx image '{PROMPT}'", R.string.clitool_cfg_generate_image_hint),
            Triple(R.string.clitool_cfg_synthesize_voice_label, "mmx speech synthesize --text '{PROMPT}' --out ~/minimax_speech.mp3", R.string.clitool_cfg_synthesize_voice_hint)
        ),
        extraCommands = listOf(R.string.clitool_cfg_extra_quota to "mmx quota")
    ),

    // MiMo Code (Xiaomi) — mimo.xiaomi.com confirma "mimo auth login" (vía Models.dev) y
    // "mimo run <prompt>" para ejecución no interactiva. Mismo candidato de proveedor local
    // (Ollama/llama-server) que ya tenía en GenericModuleFragment.
    // Ronda 2026-08-25: mimo.xiaomi.com/mimocode/cli-subcommands confirma "--continue" y
    // "session list" — se agrega continueSessionCommand (campo ya existía, faltaba llenarlo) +
    // extraCommands para ver sesiones. MCP ("mimo mcp add") existe pero sin sintaxis exacta de
    // argumentos confirmada en la investigación — no se agrega un botón de Engram con sintaxis
    // adivinada, queda pendiente de confirmar antes de exponerlo.
    "mimocode" to CliModuleConfig(
        baseCommand = "mimo",
        hasAuth = true,
        authCommand = "mimo auth login",
        supportsDirectPrompt = true,
        promptTemplate = "mimo run '{PROMPT}'",
        localProviderCapable = true,
        needsGlibcCompat = true,
        continueSessionCommand = "mimo --continue",
        extraCommands = listOf(R.string.clitool_cfg_extra_sessions to "mimo session list")
    ),

    // Mistral Vibe — docs.mistral.ai confirma "--prompt" para modo no interactivo. Sin
    // subcomando de shell de login confirmado (configuración vía config.toml/MISTRAL_API_KEY,
    // ver mistralvibe.sh) — no se agrega botón de login sin comando real detrás.
    // Ronda 2026-08-25: docs.mistral.ai/vibe confirma "--continue" (retoma sesión más
    // reciente) — campo ya existía, faltaba llenarlo. MCP/modelo activo son config-file
    // (~/.vibe/config.toml), no flags de shell — no se agrega botón sin un comando real detrás.
    "mistralvibe" to CliModuleConfig(
        baseCommand = "vibe",
        supportsDirectPrompt = true,
        promptTemplate = "vibe --prompt '{PROMPT}'",
        localProviderCapable = true,
        continueSessionCommand = "vibe --continue"
    ),

    // Qwen Code — QwenLM/qwen-code confirma que el subcomando standalone "qwen auth" fue
    // eliminado (OAuth discontinuado); la config real es "/auth" DENTRO de la sesión
    // interactiva. "--prompt" sigue confirmado para modo no interactivo.
    // Ronda 2026-08-25: github.com/QwenLM/qwen-code confirma "--resume"/"--continue" y 5 modos
    // reales de "--approval-mode" (plan/default/auto-edit/auto/yolo — control de autonomía del
    // agente, docs/users/features/approval-mode.md) + MCP real ("qwen mcp add <name>
    // <commandOrUrl> [args...] --transport stdio", docs/users/features/mcp.md) — se conecta
    // Engram con la sintaxis documentada, mismo patrón que OpenClawNative.mcpConnectEngram().
    "qwencode" to CliModuleConfig(
        baseCommand = "qwen",
        hasAuth = true,
        authHintRes = R.string.clitool_cfg_authhint_qwencode,
        supportsDirectPrompt = true,
        promptTemplate = "qwen --prompt '{PROMPT}'",
        localProviderCapable = true,
        continueSessionCommand = "qwen --continue",
        approvalModeFlag = "--approval-mode",
        approvalModeOptions = listOf("plan", "default", "auto-edit", "auto", "yolo"),
        extraCommands = listOf(R.string.clitool_cfg_extra_connect_engram to "qwen mcp add engram engram mcp --transport stdio")
    ),

    // Kimi Code (Moonshot AI) — docs/en/reference/kimi-command.md confirma los 3: "kimi login"
    // (device-code flow, subcomando real sin TUI), "-p/--prompt" y "-m/--model".
    // Ronda 2026-08-25: moonshotai.github.io/kimi-cli confirma "kimi mcp list/add/remove/
    // auth/test" — se agrega solo "list" (solo lectura); add/remove/auth necesitan parámetros
    // libres, mejor dejarlos para la terminal completa que ya existe.
    "kimi" to CliModuleConfig(
        baseCommand = "kimi",
        hasAuth = true,
        authCommand = "kimi login",
        supportsDirectPrompt = true,
        promptTemplate = "kimi -p '{PROMPT}'",
        hasModelSelector = true,
        promptWithModelTemplate = "kimi -m '{MODEL}' -p '{PROMPT}'",
        extraCommands = listOf(R.string.clitool_cfg_extra_mcp_servers to "kimi mcp list")
    ),

    // Kilo Code — kilo.ai/docs confirma "kilo auth login" (con -p/-m opcionales), "kilo run
    // <prompt>" y "-m/--model" en formato provider/model.
    // Ronda 2026-08-25: kilo.ai/docs + cheatsheet real confirman "--continue"/"-c" (campo ya
    // existía, faltaba llenarlo) y "kilo stats" (uso/costos de tokens, solo lectura, bajo
    // riesgo). "session"/"models"/"mcp" existen pero necesitan parámetros libres.
    "kilo" to CliModuleConfig(
        baseCommand = "kilo",
        hasAuth = true,
        authCommand = "kilo auth login",
        supportsDirectPrompt = true,
        promptTemplate = "kilo run '{PROMPT}'",
        hasModelSelector = true,
        promptWithModelTemplate = "kilo run '{PROMPT}' -m '{MODEL}'",
        needsGlibcCompat = true,
        continueSessionCommand = "kilo --continue",
        extraCommands = listOf(R.string.clitool_cfg_extra_usage_costs to "kilo stats")
    ),

    // Cursor CLI — cursor.com/docs confirma "agent login"/"-p"/"--model"; el binario real que
    // instala cursor.sh es "cursor-agent" (confirmado en el script, no un alias de doc).
    // Ronda 2026-08-25: cursor.com/docs/cli confirma "--continue" (alias de --resume=-1, campo
    // ya existía) y "status" (estado real de autenticación, solo lectura).
    "cursor" to CliModuleConfig(
        baseCommand = "cursor-agent",
        hasAuth = true,
        authCommand = "cursor-agent login",
        supportsDirectPrompt = true,
        promptTemplate = "cursor-agent -p '{PROMPT}'",
        hasModelSelector = true,
        promptWithModelTemplate = "cursor-agent -p '{PROMPT}' --model '{MODEL}'",
        needsGlibcCompat = true,
        continueSessionCommand = "cursor-agent --continue",
        extraCommands = listOf(R.string.clitool_cfg_extra_account_status to "cursor-agent status")
    ),

    // Hugging Face CLI — gestión de modelos/datasets/spaces, NO es un agente conversacional
    // (sin prompt/modelo) — huggingface.co/docs confirma "hf auth login" como subcomando real.
    // Ronda 2026-08-25: huggingface.co/docs/huggingface_hub confirma gestión real de caché
    // y descarga por ID — justo lo que un dispositivo móvil con poco almacenamiento necesita.
    // "hf models ls --search" y "hf download <repo_id>" usan {SOURCE}/{SYMBOL} vía los mismos
    // campos genéricos ya definidos (instalar/buscar-símbolo), reusados acá con otro sentido
    // semántico — evita un mecanismo nuevo solo para este módulo.
    "hf" to CliModuleConfig(
        baseCommand = "hf",
        hasAuth = true,
        authCommand = "hf auth login",
        symbolQueryTemplate = "hf models ls --search '{SYMBOL}'",
        symbolQueryLabelRes = R.string.clitool_cfg_search_model_dataset_label,
        symbolQueryHintRes = R.string.clitool_cfg_search_term_hint,
        installExtensionTemplate = "hf download '{SOURCE}'",
        installExtensionLabelRes = R.string.clitool_cfg_download_by_id_label,
        installExtensionHintRes = R.string.clitool_cfg_repo_id_hint,
        extraCommands = listOf(
            R.string.clitool_cfg_extra_active_session to "hf auth whoami",
            R.string.clitool_cfg_extra_view_cache to "hf cache ls",
            R.string.clitool_cfg_extra_clear_cache to "hf cache prune"
        )
    ),

    // Pi Coding Agent (earendil-works/pi, npm @earendil-works/pi-coding-agent) —
    // agente de codificación por terminal. Corrección 2026-08-18: la ronda anterior
    // solo miró el wrapper local de core-termux (que reenvía "$@" sin documentar
    // nada) y NO investigó el repo/docs oficiales upstream. Confirmado ahora leyendo
    // https://raw.githubusercontent.com/earendil-works/pi/main/packages/coding-agent/docs/usage.md
    // (contenido real, no resumen): tabla "CLI Reference" documenta "-p/--print"
    // (prompt + salir), "-c/--continue" (retomar última sesión), "--model <pattern>"
    // (acepta "provider/id"). El login NO tiene subcomando de shell fuera de la
    // sesión — es "/login" DENTRO de la sesión interactiva (misma limitación que
    // qwencode: hasAuth=true con authHintRes, sin authCommand).
    // Ronda 2026-08-25: usage.md completo (no solo la tabla de referencia) confirma gestión
    // real de extensiones ("pi install <source> -l"/"pi list") y selector de sesión ("pi -r",
    // distinto de "-c" que retoma la última sin elegir). El propio doc aclara que Pi NO tiene
    // MCP ni subagentes — no se inventa nada de eso.
    "pi" to CliModuleConfig(
        baseCommand = "pi",
        hasAuth = true,
        authHintRes = R.string.clitool_cfg_authhint_pi,
        supportsDirectPrompt = true,
        promptTemplate = "pi -p '{PROMPT}'",
        hasModelSelector = true,
        promptWithModelTemplate = "pi --model '{MODEL}' -p '{PROMPT}'",
        continueSessionCommand = "pi -c",
        needsGlibcCompat = true,
        installExtensionTemplate = "pi install '{SOURCE}' -l",
        extraCommands = listOf(
            R.string.clitool_cfg_extra_installed_extensions to "pi list",
            R.string.clitool_cfg_extra_choose_session to "pi -r"
        )
    ),

    // CodeGraph (colbymchenry/codegraph) — Corrección 2026-08-18: la ronda anterior
    // solo miró el wrapper local (bin/codegraph, que reenvía "$@" a un .js sin
    // exponer flags) y no investigó el repo real. Confirmado ahora contra el README
    // oficial (github.com/colbymchenry/codegraph, verificado con raw.githubusercontent
    // + WebFetch cruzado dos veces): NO es un agente de IA/chat, es una herramienta de
    // análisis estático real con subcomandos propios — "codegraph init [path]"
    // inicializa y construye el grafo del proyecto, "codegraph status [path]" muestra
    // estadísticas. Encaja en el patrón "Analizar proyecto" + selector de carpeta
    // (showProjectsMenu), no en el patrón de prompt de texto libre tipo chat — de ahí
    // el campo nuevo [CliModuleConfig.analyzeProjectTemplate]. Deliberadamente NO se
    // agregan los demás subcomandos (query/callers/callees/impact/affected, etc.):
    // son reales pero exponerlos todos infla este Fragment compartido más allá de lo
    // que amerita esta ronda — init+status ya cubre la acción principal ("analizar").
    // Ronda 2026-08-25: el README real confirma los subcomandos que la ronda anterior sabía
    // que existían pero excluyó a propósito — "query" (búsqueda de símbolos) se agregó antes
    // en esta misma ronda; ahora se agregan también "callers"/"callees"/"impact" (reusan
    // symbolQueryVariants, mismo diálogo de {SYMBOL} que query) y "affected" (opera sobre la
    // carpeta de proyecto entera, no un símbolo — reusa projectActionTemplate/showProjectsMenu,
    // igual que Oh-My-Pi con "omp commit").
    "codegraph" to CliModuleConfig(
        baseCommand = "codegraph",
        analyzeProjectTemplate = "codegraph init '{PROJECT_PATH}' && codegraph status '{PROJECT_PATH}'",
        symbolQueryTemplate = "codegraph query '{SYMBOL}'",
        symbolQueryHintRes = R.string.clitool_cfg_search_function_class_hint,
        symbolQueryVariants = listOf(
            R.string.clitool_cfg_callers_label to "codegraph callers '{SYMBOL}'",
            R.string.clitool_cfg_callees_label to "codegraph callees '{SYMBOL}'",
            R.string.clitool_cfg_impact_label to "codegraph impact '{SYMBOL}'"
        ),
        projectActionLabelRes = R.string.clitool_cfg_affected_files_label,
        projectActionTemplate = "codegraph affected '{PROJECT_PATH}'"
    ),

    // Oh-My-Pi (can1357/oh-my-pi) — versión mejorada/standalone de Pi Coding Agent.
    // Corrección 2026-08-18: la ronda anterior solo confirmó 3 flags desde una copia
    // local desactualizada del README. Confirmado ahora contra el README real
    // (github.com/can1357/oh-my-pi, verificado con raw.githubusercontent + WebFetch
    // cruzado dos veces): además de "-p" (one-shot) y "-c" (continuar sesión), SÍ
    // documenta selección de modelo — "--model" (especifica proveedor/id) y atajos
    // "--smol"/"--slow"/"--plan" (no se exponen estos últimos, serían botones extra
    // sin valor claro sobre el "--model" genérico ya cubierto). NO hay subcomando de
    // login propio documentado para omp específicamente (hereda auth de pi pero eso
    // no está confirmado en su propio README) — no se agrega hasAuth sin evidencia
    // directa del repo de omp.
    // Ronda 2026-08-25: README completo confirma "omp setup" (config interactiva de
    // proveedor/modelo) y "omp commit" (commits atómicos generados y divididos automáticamente
    // — acción real sobre un proyecto elegido). El resto (20+ herramientas internas tipo
    // browser/computer-use/TTS/memoria, Agent Hub) se activan con slash-commands DENTRO de la
    // sesión interactiva, sin equivalente de flag de arranque — no tiene sentido como botón de
    // Kairos, confirmado a propósito que se dejan fuera.
    "ohmypi" to CliModuleConfig(
        baseCommand = "omp",
        supportsDirectPrompt = true,
        promptTemplate = "omp -p '{PROMPT}'",
        hasModelSelector = true,
        promptWithModelTemplate = "omp --model '{MODEL}' -p '{PROMPT}'",
        continueSessionCommand = "omp -c",
        needsGlibcCompat = true,
        extraCommands = listOf(R.string.clitool_cfg_extra_configure_provider to "omp setup"),
        projectActionLabelRes = R.string.clitool_cfg_assisted_commit_label,
        projectActionTemplate = "cd '{PROJECT_PATH}' && omp commit"
    )
)

/**
 * Fragment de detalle COMPARTIDO para los 14 CLIs de agentes/herramientas sin pantalla propia
 * (freebuff/codebuff/copilotcli/minimaxcli/mimocode/mistralvibe/qwencode/kimi/kilo/cursor/hf/
 * pi/codegraph/ohmypi — los últimos 3 agregados 2026-08-17/18, ver ModuleDetailNavigator.kt).
 * Son lo bastante homogéneos (línea de comandos, algunos con login/modelo/prompt propio) como
 * para no justificar 14 Fragments casi idénticos — un solo Fragment parametrizado por
 * `moduleId` que lee su [CliModuleConfig] y arma solo los botones que ese CLI realmente
 * soporta (ver tabla en [CLI_MODULE_CONFIGS], investigación 2026-08-17/18).
 *
 * Todo lo heredado de [BaseModuleFragment] (proyectos, actualizar, reinstalar, desinstalar)
 * se arma igual que en [GenericModuleFragment] — este Fragment es un superset, no un
 * reemplazo genérico: los módulos sin ninguna capacidad extra confirmada quedan exactamente
 * al mismo nivel que el fallback genérico (abrir + proyectos), a propósito.
 */
class CliToolFragment : BaseModuleFragment() {

    private var moduleId: String = ""
    private var moduleName: String = ""
    private var installedVersion: String = ""

    private val config: CliModuleConfig
        get() = CLI_MODULE_CONFIGS[moduleId] ?: CliModuleConfig(baseCommand = moduleId)

    /**
     * Envuelve [BaseModuleFragment.launchTerminalCommand] — antepone "unset LD_PRELOAD; " para
     * los CLIs marcados [CliModuleConfig.needsGlibcCompat] (ver ese campo para el bug real que
     * esto arregla, confirmado 2026-08-24 probando la terminal real de la app). Reemplaza TODOS
     * los call sites de este Fragment — ninguno debe llamar a launchTerminalCommand() directo.
     */
    private fun launchCliTerminalCommand(command: String) {
        val finalCommand = if (config.needsGlibcCompat) "unset LD_PRELOAD; $command" else command
        launchTerminalCommand(finalCommand)
    }

    override fun getModuleId(): String = moduleId
    override fun getModuleName(): String = moduleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments
        moduleId = args?.getString(ARG_ID) ?: ""
        moduleName = args?.getString(ARG_NAME) ?: moduleId
    }

    override fun buildContent() {
        if (moduleId.isEmpty()) return
        if (!isModuleInstalled()) { showNotInstalled(moduleName); return }

        readInstalledVersion()

        addCard(getString(R.string.clitool_card_estado)) {
            addView(infoRow(getString(R.string.clitool_info_id), moduleId))
            if (installedVersion.isNotEmpty()) addView(infoRow(getString(R.string.clitool_info_version), installedVersion))
            addView(infoRow(getString(R.string.clitool_info_comando), config.baseCommand))
        }

        addCard(getString(R.string.clitool_card_usar)) {
            // Mockup aprobado por el usuario 2026-08-26 (ver
            // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md), extendido a "todos los CLI"
            // tras corrección explícita del usuario ("te dije que lo agregaras a todos los cli
            // a todos") — cubre de una sola vez los 14 CLIs de este Fragment compartido
            // (freebuff/codebuff/copilotcli/minimaxcli/mimocode/mistralvibe/qwencode/kimi/kilo/
            // cursor/hf/pi/codegraph/ohmypi), incluido minimaxcli reportado como "sigue igual".
            actionButton(getString(R.string.clitool_btn_open_terminal), GHOST) {
                promptOpenLocation(
                    onDefault = { launchCliTerminalCommand(config.baseCommand) },
                    onChooseFolder = { path -> launchCliTerminalCommand("cd '$path' && ${config.baseCommand}") }
                )
            }
            actionButton(getString(R.string.clitool_btn_manage_projects), GHOST) {
                showProjectsMenu(
                    onToast = { toast(it) },
                    onLaunchInProject = { path -> launchCliTerminalCommand("cd '$path' && ${config.baseCommand}") }
                )
            }
        }

        if (config.hasAuth) {
            addCard(getString(R.string.clitool_card_cuenta)) {
                actionButton(getString(R.string.clitool_btn_login), GHOST) { runAuth() }
            }
        }

        if (config.supportsDirectPrompt) {
            addCard(getString(R.string.clitool_card_prompt_directo)) {
                actionButton(getString(R.string.clitool_btn_send_prompt) + if (config.hasModelSelector) getString(R.string.clitool_btn_send_prompt_model_suffix) else "", PRIMARY) {
                    runDirectPrompt()
                }
                // Variantes de prompt (ej. imagen/voz de MiniMax) — mismo diálogo de texto
                // libre que runDirectPrompt(), pero sin selector de modelo (esas variantes no
                // lo soportan) y con su propia plantilla de comando.
                config.promptVariants.forEach { (labelRes, template, hintRes) ->
                    actionButton(getString(labelRes), GHOST) { runPromptVariant(template, getString(hintRes)) }
                }
                config.continueSessionCommand?.let { cmd ->
                    actionButton(getString(R.string.clitool_btn_continue_session), GHOST) { launchCliTerminalCommand(cmd) }
                }
                config.approvalModeFlag?.let { flag ->
                    actionButton(getString(R.string.clitool_btn_approval_mode), GHOST) { showApprovalModeDialog(flag) }
                }
            }
        }

        config.analyzeProjectTemplate?.let { template ->
            addCard(getString(R.string.clitool_card_analisis_proyecto)) {
                actionButton(getString(R.string.clitool_btn_analyze_project), PRIMARY) {
                    showProjectsMenu(
                        onToast = { toast(it) },
                        onLaunchInProject = { path ->
                            launchCliTerminalCommand(template.replace("{PROJECT_PATH}", path))
                        }
                    )
                }
                config.symbolQueryTemplate?.let { runSymbolQueryButton(getString(config.symbolQueryLabelRes), it) }
                config.symbolQueryVariants.forEach { (labelRes, variantTemplate) ->
                    runSymbolQueryButton(getString(labelRes), variantTemplate)
                }
            }
        } ?: config.symbolQueryTemplate?.let { template ->
            // CLIs sin analyzeProjectTemplate pero con búsqueda de símbolo/término propia
            // (ej. HF: "buscar modelo" no depende de un proyecto elegido) — card propia.
            addCard(getString(config.symbolQueryLabelRes).uppercase()) {
                runSymbolQueryButton(getString(config.symbolQueryLabelRes), template)
                config.symbolQueryVariants.forEach { (labelRes, variantTemplate) ->
                    runSymbolQueryButton(getString(labelRes), variantTemplate)
                }
            }
        }

        config.projectActionTemplate?.let { template ->
            addCard((config.projectActionLabelRes?.let { getString(it) } ?: getString(R.string.clitool_card_accion_proyecto_default)).uppercase()) {
                actionButton(config.projectActionLabelRes?.let { getString(it) } ?: getString(R.string.clitool_card_accion_proyecto_default), PRIMARY) {
                    showProjectsMenu(
                        onToast = { toast(it) },
                        onLaunchInProject = { path ->
                            launchCliTerminalCommand(template.replace("{PROJECT_PATH}", path))
                        }
                    )
                }
            }
        }

        config.installExtensionTemplate?.let { template ->
            addCard(getString(R.string.clitool_card_extensiones)) {
                actionButton(getString(config.installExtensionLabelRes), GHOST) { showInstallExtensionDialog(template) }
            }
        }

        config.createFromTemplateTemplate?.let { template ->
            addCard(getString(R.string.clitool_card_scaffolding)) {
                actionButton(getString(config.createFromTemplateLabelRes), GHOST) { showCreateFromTemplateDialog(template) }
            }
        }

        if (config.extraCommands.isNotEmpty()) {
            addCard(getString(R.string.clitool_card_mas_opciones)) {
                config.extraCommands.forEach { (labelRes, cmd) ->
                    actionButton(getString(labelRes), GHOST) { launchCliTerminalCommand(cmd) }
                }
            }
        }

        if (config.localProviderCapable) {
            addCard(getString(R.string.clitool_card_proveedor_local)) {
                actionButton(getString(R.string.clitool_btn_use_ollama), GHOST) { useOllamaLocal() }
                actionButton(getString(R.string.clitool_btn_use_llama_server), GHOST) { useLlamaServerLocal() }
            }
        }

        addCard(getString(R.string.clitool_card_mantenimiento)) {
            actionButton(getString(R.string.clitool_btn_update), GHOST) {
                toast(getString(R.string.clitool_toast_updating, moduleName))
                updateModuleService { ok ->
                    toast(if (ok) getString(R.string.clitool_toast_updated, moduleName) else getString(R.string.clitool_toast_update_failed, moduleId))
                }
            }
            actionButton(getString(R.string.clitool_btn_uninstall), DANGER) { confirmUninstall() }
        }
    }

    private fun readInstalledVersion() {
        installedVersion = try {
            ModuleRegistry(requireContext()).load().get("$moduleId.version") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun runAuth() {
        val cmd = config.authCommand
        if (cmd != null) {
            launchCliTerminalCommand(cmd)
            return
        }
        config.authHintRes?.let { toast(getString(it)) }
        launchCliTerminalCommand(config.baseCommand)
    }

    // Escapado mínimo para línea de comandos de una sola comilla simple: cierra la comilla,
    // agrega la comilla escapada literal, y vuelve a abrir — patrón estándar de shell POSIX.
    private fun shellEscape(text: String): String = text.replace("'", "'\\''")

    private fun runDirectPrompt() {
        val ctx = requireContext()
        val promptInput = EditText(ctx).apply {
            hint = getString(R.string.clitool_hint_prompt, moduleName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.clitool_title_direct_prompt, moduleName))
            .setView(promptInput)
            .setPositiveButton(getString(R.string.clitool_btn_send)) { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) { toast(getString(R.string.clitool_toast_prompt_empty)); return@setPositiveButton }
                if (config.hasModelSelector) askModelThenRun(prompt) else runPrompt(prompt, null)
            }
            .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
            .show()
    }

    // Botón "Buscar símbolo"/"Buscar modelo" — reusado por CodeGraph y HF con distinta
    // etiqueta/hint (ver [CliModuleConfig.symbolQueryLabelRes]/[symbolQueryHintRes]). Generalizado
    // 2026-08-25 para aceptar [label] propio por llamada — reusado también por
    // [CliModuleConfig.symbolQueryVariants] (callers/callees/impact de CodeGraph), que
    // comparten el mismo diálogo/hint que la búsqueda principal pero con su propio comando.
    private fun runSymbolQueryButton(label: String, template: String) {
        actionButton(label, GHOST) {
            val ctx = requireContext()
            val input = EditText(ctx).apply {
                hint = getString(config.symbolQueryHintRes)
                val pad = dp(20)
                setPadding(pad, pad / 2, pad, 0)
            }
            AlertDialog.Builder(ctx)
                .setTitle(label)
                .setView(input)
                .setPositiveButton(getString(R.string.clitool_btn_search)) { _, _ ->
                    val q = input.text.toString().trim()
                    if (q.isEmpty()) { toast(getString(R.string.clitool_toast_term_empty)); return@setPositiveButton }
                    launchCliTerminalCommand(template.replace("{SYMBOL}", shellEscape(q)))
                }
                .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
                .show()
        }
    }

    private fun showInstallExtensionDialog(template: String) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(config.installExtensionHintRes)
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(config.installExtensionLabelRes))
            .setView(input)
            .setPositiveButton(getString(R.string.clitool_btn_install)) { _, _ ->
                val source = input.text.toString().trim()
                if (source.isEmpty()) { toast(getString(R.string.clitool_toast_source_empty)); return@setPositiveButton }
                launchCliTerminalCommand(template.replace("{SOURCE}", shellEscape(source)))
            }
            .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
            .show()
    }

    // Scaffolding de proyecto nuevo desde plantilla (Freebuff/Codebuff "--create <template>
    // <name>") — 2 EditText en un LinearLayout vertical, mismo patrón de escapado de comillas
    // que runSymbolQueryButton()/showInstallExtensionDialog() pero con 2 placeholders.
    private fun showCreateFromTemplateDialog(template: String) {
        val ctx = requireContext()
        val pad = dp(20)
        val templateInput = EditText(ctx).apply {
            hint = getString(R.string.clitool_hint_template)
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(ctx).apply {
            hint = getString(R.string.clitool_hint_project_name)
            setPadding(pad, pad / 4, pad, pad / 2)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(templateInput)
            addView(nameInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(config.createFromTemplateLabelRes))
            .setView(container)
            .setPositiveButton(getString(R.string.clitool_btn_create)) { _, _ ->
                val tpl = templateInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                if (tpl.isEmpty() || name.isEmpty()) {
                    toast(getString(R.string.clitool_toast_complete_template_name))
                } else {
                    launchCliTerminalCommand(
                        template.replace("{TEMPLATE}", shellEscape(tpl)).replace("{NAME}", shellEscape(name))
                    )
                }
            }
            .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
            .show()
    }

    private fun showApprovalModeDialog(flag: String) {
        val options = config.approvalModeOptions.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.clitool_title_approval_mode, moduleName))
            .setItems(options) { _, which ->
                launchCliTerminalCommand("${config.baseCommand} $flag=${options[which]}")
            }
            .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
            .show()
    }

    // Mismo diálogo que runDirectPrompt(), sin selector de modelo — usado por
    // [CliModuleConfig.promptVariants] (ej. generar imagen/voz de MiniMax).
    private fun runPromptVariant(template: String, hint: String) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(moduleName)
            .setView(input)
            .setPositiveButton(getString(R.string.clitool_btn_send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) { toast(getString(R.string.clitool_toast_text_empty)); return@setPositiveButton }
                launchCliTerminalCommand(template.replace("{PROMPT}", shellEscape(text)))
            }
            .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
            .show()
    }

    private fun askModelThenRun(prompt: String) {
        val ctx = requireContext()
        val modelInput = EditText(ctx).apply {
            hint = getString(R.string.clitool_hint_model)
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.clitool_title_model_optional))
            .setView(modelInput)
            .setPositiveButton(getString(R.string.clitool_btn_continue)) { _, _ ->
                val model = modelInput.text.toString().trim()
                runPrompt(prompt, model.ifEmpty { null })
            }
            .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
            .show()
    }

    private fun runPrompt(prompt: String, model: String?) {
        val escapedPrompt = shellEscape(prompt)
        val command = if (model != null && config.hasModelSelector) {
            config.promptWithModelTemplate
                .replace("{MODEL}", shellEscape(model))
                .replace("{PROMPT}", escapedPrompt)
        } else {
            config.promptTemplate.replace("{PROMPT}", escapedPrompt)
        }
        launchCliTerminalCommand(command)
    }

    // Mismo patrón que GenericModuleFragment.useOllamaLocal()/HermesFragment — lista modelos
    // reales ya descargados en Ollama, nunca un EditText de texto libre.
    private fun useOllamaLocal() {
        Thread {
            val models = try {
                OllamaApiClient.listModels()
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { toast(getString(R.string.clitool_toast_error_generic, e.message ?: getString(R.string.clitool_toast_ollama_unavailable))) }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (models.isEmpty()) { toast(getString(R.string.clitool_toast_no_ollama_models)); return@runOnUiThread }
                val names = models.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.clitool_title_ollama_model, moduleName))
                    .setItems(names) { _, which -> setLocalProvider("http://127.0.0.1:11434/v1", names[which]) }
                    .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun useLlamaServerLocal() {
        val models = LocalModelManager.listModels(requireContext())
        if (models.isEmpty()) {
            toast(getString(R.string.clitool_toast_no_gguf_models))
            return
        }
        val names = models.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.clitool_title_llama_server_model, moduleName))
            .setItems(names) { _, which -> setLocalProvider("http://127.0.0.1:8085/v1", names[which]) }
            .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
            .show()
    }

    private fun setLocalProvider(baseUrl: String, model: String) {
        Thread {
            val json = when (moduleId) {
                "qwencode" -> LocalCliProviderNative.configureQwenCode(baseUrl, model)
                "mimocode" -> LocalCliProviderNative.configureMimoCode(baseUrl, model)
                "mistralvibe" -> LocalCliProviderNative.configureMistralVibe(baseUrl, model)
                else -> JSONObject().put("ok", false).put("error", getString(R.string.clitool_toast_no_local_provider_support))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                toast(if (json.optBoolean("ok", false)) getString(R.string.clitool_toast_configured, model) else getString(R.string.clitool_toast_error_generic, json.optString("error")))
            }
        }.start()
    }

    private fun confirmUninstall() {
        val deepCheckbox = android.widget.CheckBox(requireContext()).apply {
            text = getString(R.string.clitool_checkbox_deep_uninstall)
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.clitool_title_confirm_uninstall, moduleName))
            .setMessage(getString(R.string.clitool_msg_confirm_uninstall))
            .setView(deepCheckbox)
            .setPositiveButton(getString(R.string.clitool_btn_uninstall_confirm)) { _, _ ->
                if (deepCheckbox.isChecked) {
                    com.termux.app.ModuleController.deepUninstallModule(moduleId) { ok, message ->
                        if (!isAdded) return@deepUninstallModule
                        requireActivity().runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            toast(message)
                            if (ok) parentFragmentManager.popBackStack()
                        }
                    }
                } else {
                    com.termux.app.ModuleController.uninstallModule(moduleId) { ok ->
                        if (!isAdded) return@uninstallModule
                        requireActivity().runOnUiThread {
                            if (ok) {
                                toast(getString(R.string.clitool_toast_uninstalled, moduleName))
                                parentFragmentManager.popBackStack()
                            } else toast(getString(R.string.clitool_toast_uninstall_failed, moduleName))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.clitool_btn_cancel), null)
            .show()
    }

    companion object {
        private const val ARG_ID = "module_id"
        private const val ARG_NAME = "module_name"

        fun newInstance(moduleId: String, moduleName: String): CliToolFragment {
            return CliToolFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, moduleId)
                    putString(ARG_NAME, moduleName)
                }
            }
        }
    }
}
