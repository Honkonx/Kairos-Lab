package com.termux.app.ui

import android.content.Context
import android.net.Uri
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ProgressDialogController
import java.io.File
import com.termux.app.util.kairosThemeColor

/**
 * Módulo consolidado "Paquetes" (pedido explícito del usuario, 2026-08-18 — ver
 * docs/modulos/PAQUETES.md): reemplaza las 10 tarjetas sueltas que tenía la Tienda para
 * typescript/nestjs/prettier/livesrv/localtunnel/vercel/markserv/psqlformat/ncu/ngrok (ahora
 * `internal: true` en modules.json, sin tocar sus scripts reales en `modulos/`) por un solo
 * switch por herramienta acá adentro — "deben ser modulos ya listos y solo poder desactivar o
 * activar" (sin diálogos de instalación intermedios).
 *
 * Los 10 dependen de Node.js (instalan `nodejs-lts` solo si falta, ver
 * `modulos/lib.sh::ensure_node_installed()`) — el módulo Lenguajes ya lo cubre como switch
 * propio si el usuario lo quiere instalar por separado primero; acá no hace falta, cada
 * herramienta se asegura Node.js sola.
 *
 * Migración a strings.xml (2026-08-28, soporte español/inglés) — `screenName`, `introText` e
 * `items` eran `val` con evaluación inmediata (corren al construir el objeto, ANTES de que el
 * Fragment esté adjunto a un Context) — usar `getString()` ahí directo revienta con
 * `IllegalStateException: Fragment ... not attached`. Se envuelven en `by lazy {}` para diferir
 * la evaluación al primer acceso real (dentro de `buildContent()`, ya con Fragment adjunto). Los
 * `formatter` de nivel de archivo (fuera de la clase, sin `Context` propio) reciben ahora un
 * `Context` explícito — se siguen registrando como lambda (`{ raw -> formatXxx(requireContext(),
 * raw) }`) en vez de referencia de función (`::formatXxx`) porque la lambda captura `requireContext()`
 * de forma perezosa (solo se ejecuta al tocar el botón, con el Fragment ya adjunto).
 */
class PackagesFragment : ConsolidatedModuleFragment() {

    // psqlformat necesita un archivo .sql real para formatear — a diferencia del resto de
    // RunAction (que corren sobre $HOME sin pedir nada), esto requiere Storage Access Framework
    // (auditoría 2026-08-19, 3ra ronda: "agregá selector de archivo real (SAF/DocumentFile)").
    // Se registra como campo de instancia (no dentro de un onClick) — mismo requisito de ciclo
    // de vida documentado en CactusFragment.mPickImportFileLauncher.
    private val mPickSqlFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { formatSqlFile(it) }
        }

    override val screenId = "packages"
    override val screenName by lazy { getString(R.string.packages_screen_name) }
    override val introText by lazy { getString(R.string.packages_intro_text) }

    override val items by lazy {
        listOf(
            SwitchModuleItem(
                "typescript", getString(R.string.packages_name_typescript), "tsc --version", "npm install -g typescript",
                runActions = listOf(
                    // Auditoría "tools" 2026-08-19 (3ra ronda) — typescriptlang.org/docs confirma
                    // "tsc --init" como el comando oficial para generar un tsconfig.json con
                    // defaults razonables. Seguro por diseño: si ya existe un tsconfig.json en
                    // $HOME, tsc se niega a pisarlo (imprime "A 'tsconfig.json' file is already
                    // defined..." y sale con error) — no hace falta confirmación extra.
                    RunAction(
                        getString(R.string.packages_action_create_tsconfig), "tsc --init",
                        formatter = { raw -> formatTscInit(requireContext(), raw) }
                    )
                )
            ),
            SwitchModuleItem(
                "nestjs", getString(R.string.packages_name_nestjs), "nest --version", "npm install -g @nestjs/cli",
                runActions = listOf(
                    // docs.nestjs.com/cli/usages confirma "--package-manager"/"-p" y "--skip-git"/
                    // "-g" como flags reales de "nest new" — sin ellos el CLI pregunta de forma
                    // interactiva qué gestor de paquetes usar (se cuelga esperando stdin en un
                    // ProcessBuilder sin TTY, mismo problema que motivó --batch en sqlmap). Crea una
                    // carpeta nueva (no pisa nada existente) — el diálogo de texto pide el nombre.
                    RunAction(
                        getString(R.string.packages_action_create_nest_project),
                        "nest new %s --package-manager npm --skip-git",
                        timeoutSeconds = 180, formatter = { raw -> formatNestNew(requireContext(), raw) },
                        requiresTextInput = true, textInputHint = getString(R.string.packages_hint_nest_project_name)
                    )
                )
            ),
            SwitchModuleItem(
                "prettier", getString(R.string.packages_name_prettier), "prettier --version", "npm install -g prettier",
                runActions = listOf(
                    RunAction(
                        getString(R.string.packages_action_check_format), "prettier --check .",
                        formatter = { raw -> formatPrettierCheck(requireContext(), raw) }
                    ),
                    // Auditoría "tools" 2026-08-19 — prettier.io/docs/cli confirma "--write ." como
                    // el flag oficial para reescribir archivos in-place ("This overwrites your
                    // files!"). Par natural del check de solo lectura de arriba, pero muta $HOME —
                    // requiere confirmación (ver RunAction.requiresConfirmation).
                    RunAction(
                        getString(R.string.packages_action_apply_format), "prettier --write .", timeoutSeconds = 60,
                        formatter = { raw -> formatPrettierWrite(requireContext(), raw) },
                        requiresConfirmation = true,
                        confirmMessage = getString(R.string.packages_confirm_apply_format)
                    )
                )
            ),
            SwitchModuleItem("livesrv", getString(R.string.packages_name_live_server), "live-server --version", "npm install -g live-server"),
            SwitchModuleItem("localtunnel", getString(R.string.packages_name_localtunnel), "lt --version", "npm install -g localtunnel"),
            SwitchModuleItem(
                "vercel", getString(R.string.packages_name_vercel), "vercel --version", "npm install -g vercel",
                runActions = listOf(
                    RunAction(
                        getString(R.string.packages_action_view_session), "vercel whoami",
                        formatter = { raw -> formatVercelWhoami(requireContext(), raw) }
                    ),
                    // Auditoría "tools" 2026-08-19 (3ra ronda) — "vercel login: mostrar URL/código de
                    // login real, no scriptear credenciales". Vercel cambió a OAuth 2.0 Device Flow
                    // (github.com/vercel/vercel/discussions/5468, changelog "New Vercel CLI login
                    // flow", 2026): el login por email + flags --github/--gitlab/--bitbucket se
                    // retiraron. "--no-browser" evita que el CLI intente abrir un navegador embebido
                    // (que no existe en este entorno) e imprime la URL de verificación + el código
                    // de un solo uso en stdout para que el usuario los abra a mano en cualquier
                    // navegador — nunca se pide ni se maneja una contraseña/token acá.
                    RunAction(
                        getString(R.string.packages_action_login), "vercel login --no-browser", timeoutSeconds = 20,
                        formatter = { raw -> formatVercelLogin(requireContext(), raw) }, readPartialOutput = true
                    ),
                    // vercel.com/docs/cli/deploy confirma "--prod" como el flag de despliegue a
                    // producción; "--yes" evita los prompts interactivos de setup de proyecto nuevo
                    // (necesarios en un ProcessBuilder sin TTY). Timeout generoso (150s, escala
                    // "deploys pueden tardar" pedida en la auditoría) — limitación real conocida:
                    // requiere que $HOME ya sea un proyecto Vercel enlazado (`vercel link`/login
                    // previo); si no lo es, falla con el error real de Vercel, no hay flujo de
                    // link automático en esta ronda (fuera de alcance, ver informe de la ronda).
                    RunAction(
                        getString(R.string.packages_action_deploy_prod), "vercel --prod --yes", timeoutSeconds = 150,
                        formatter = { raw -> formatVercelDeploy(requireContext(), raw) }
                    )
                )
            ),
            SwitchModuleItem("markserv", getString(R.string.packages_name_markserv), "markserv --version", "npm install -g markserv"),
            // Selector de archivo real agregado en la tarjeta "FORMATEAR ARCHIVO .SQL" (ver
            // addExtraRows() más abajo) — psqlformat necesita un archivo .sql concreto, no tiene
            // sentido como RunAction de una sola línea sobre $HOME como el resto de este módulo.
            SwitchModuleItem(
                "psqlformat", getString(R.string.packages_name_psqlformat), "psqlformat --version",
                "npm install -g psqlformat · requiere Perl (módulo Lenguajes)"
            ),
            SwitchModuleItem(
                "ncu", getString(R.string.packages_name_ncu), "ncu --version", "npm install -g npm-check-updates",
                runActions = listOf(
                    RunAction(
                        getString(R.string.packages_action_check_updates), "ncu", timeoutSeconds = 30,
                        formatter = { raw -> formatNcuOutput(requireContext(), raw) }
                    ),
                    // github.com/raineorshine/npm-check-updates confirma "-u"/"--upgrade" como el
                    // flag que sobreescribe package.json con las versiones nuevas, y que corre sin
                    // prompts por defecto (el modo interactivo es "-i", no "-u"). Muta package.json
                    // en $HOME — requiere confirmación, pedido explícito de la auditoría (antes
                    // deliberadamente omitido "por ser más riesgoso").
                    RunAction(
                        getString(R.string.packages_action_update_package_json), "ncu -u", timeoutSeconds = 30,
                        formatter = { raw -> formatNcuUpgrade(requireContext(), raw) },
                        requiresConfirmation = true,
                        confirmMessage = getString(R.string.packages_confirm_update_package_json)
                    )
                )
            ),
            SwitchModuleItem(
                "ngrok", getString(R.string.packages_name_ngrok), "ngrok --version", "npm install -g ngrok",
                runActions = listOf(
                    // ngrok.com/docs/agent/cli/ confirma "ngrok config add-authtoken <TOKEN>" como
                    // la sintaxis actual documentada (reemplaza el histórico "ngrok authtoken
                    // <TOKEN>" de versiones viejas). El token nunca se loguea/toastea en texto
                    // plano — ver RunAction.requiresTextInput y promptTextInputAction().
                    RunAction(
                        getString(R.string.packages_action_configure_authtoken), "ngrok config add-authtoken %s",
                        formatter = { raw -> formatNgrokAuthtoken(requireContext(), raw) },
                        requiresTextInput = true,
                        textInputHint = getString(R.string.packages_hint_ngrok_token)
                    )
                )
            ),
        )
    }

    override fun sectionTitle() = getString(R.string.packages_section_title)

    // Pestañas reales (2026-08-26, pedido explícito del usuario, usando el hook nuevo
    // ConsolidatedModuleFragment.itemGroups() — ver docs/estructura/COMPONENTES_UI.md). Los 10
    // ítems de este módulo ya son "herramientas CLI npm" en su totalidad (no hay una categoría
    // "Lenguajes" acá — esa es LanguagesFragment, un módulo contenedor aparte que no sobreescribe
    // este hook y sigue con su card única), así que la agrupación real es por función:
    //  - "Formato y calidad": herramientas de solo-código/config, sin servidor propio
    //    (typescript, prettier, ncu, psqlformat).
    //  - "Servidores y túneles": procesos de larga duración / exposición a internet
    //    (livesrv, markserv, localtunnel, ngrok).
    //  - "Deploy y frameworks": scaffolding de proyectos + publicación (nestjs, vercel).
    // Partición real de `items` (confirmada 1:1 contra la lista de arriba, sin duplicar ni
    // omitir ningún id) — ver KDoc de itemGroups() para el contrato.
    override fun itemGroups(): List<Pair<String, List<SwitchModuleItem>>> {
        val byId = items.associateBy { it.id }
        fun group(vararg ids: String) = ids.map { byId.getValue(it) }
        return listOf(
            getString(R.string.packages_group_format_quality) to group("typescript", "prettier", "ncu", "psqlformat"),
            getString(R.string.packages_group_servers_tunnels) to group("livesrv", "markserv", "localtunnel", "ngrok"),
            getString(R.string.packages_group_deploy_frameworks) to group("nestjs", "vercel")
        )
    }

    /**
     * Selector de archivo real para psqlformat (auditoría 2026-08-19, 3ra ronda) — a diferencia
     * del resto de RunAction de este módulo (corren sobre `$HOME` sin pedir nada), psqlformat
     * necesita apuntar a un `.sql` concreto elegido por el usuario. `psqlformat <archivo>` sin
     * `--write` imprime el SQL formateado a stdout por defecto (npmjs.com/package/psqlformat,
     * confirmado 2026-08-19: "By default, output is written to stdout") — solo lectura, nunca
     * modifica el archivo original.
     */
    override fun addExtraRows() {
        addCard(getString(R.string.packages_card_sql_format)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.packages_sql_picker_description)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.packages_sql_picker_button)
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosBlue))
                setPadding(dp(14), dp(6), dp(14), dp(10))
                setOnClickListener { mPickSqlFileLauncher.launch("*/*") }
            })
        }
    }

    /**
     * El Uri elegido por SAF se copia a un archivo temporal real dentro del sandbox de Termux
     * (psqlformat corre en un bash normal — no sabe leer un content:// Uri) y se borra al
     * terminar, con éxito o error (mismo patrón que UdockerFragment.importImageFrom()).
     */
    private fun formatSqlFile(uri: Uri) {
        val ctx = requireContext()
        val progress = ProgressDialogController(ctx)
        progress.show(getString(R.string.packages_sql_progress_title), getString(R.string.packages_sql_progress_reading))
        Thread {
            val tmp = File(ctx.cacheDir, "psqlformat_input_${System.currentTimeMillis()}.sql")
            val copyError = try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw java.io.IOException(getString(R.string.packages_error_open_chosen_file))
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.packages_error_unknown)
            }
            if (copyError != null) {
                tmp.delete()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { progress.failure(getString(R.string.packages_error_cannot_read_file), copyError) }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { progress.update(getString(R.string.packages_sql_progress_formatting)) }
            val (rc, out, err) = ManagerNativeUtils.runShell("psqlformat '${tmp.absolutePath}'", 20)
            tmp.delete()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                progress.dismiss()
                if (rc == 0) {
                    showFormattedSqlDialog(out.ifBlank { getString(R.string.packages_no_output_parenthetical) })
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle(getString(R.string.packages_error_format_failed_title))
                        .setMessage((err.ifBlank { out }).ifBlank { getString(R.string.packages_error_format_failed_fallback) })
                        .setPositiveButton(getString(R.string.packages_button_close), null)
                        .show()
                }
            }
        }.start()
    }

    private fun showFormattedSqlDialog(sql: String) {
        val ctx = requireContext()
        val text = TextView(ctx).apply {
            text = sql
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.packages_sql_formatted_title))
            .setView(ScrollView(ctx).apply { addView(text) })
            .setPositiveButton(getString(R.string.packages_button_close), null)
            .show()
    }
}

/**
 * `ncu` sin `$HOME/package.json` imprime "No package.json found" — se muestra tal cual (no hay
 * nada que parsear). Con un `package.json` real, cada dependencia desactualizada es una línea
 * ` nombre  ^actual  →  ^nueva` (formato real de npm-check-updates, ver github.com/
 * raineorshine/npm-check-updates) — se reformatea a "nombre: actual → nueva" una por línea, más
 * legible en el diálogo monoespaciado que la salida cruda con espaciado de columnas variable.
 */
private val NCU_LINE = Regex("""^\s*(\S+)\s+(\S+)\s+→\s+(\S+)""")

private fun formatNcuOutput(ctx: Context, raw: String): String {
    val matches = raw.lineSequence()
        .mapNotNull { line -> NCU_LINE.find(line) }
        .map { m -> "${m.groupValues[1]}: ${m.groupValues[2]} → ${m.groupValues[3]}" }
        .toList()
    if (matches.isEmpty()) return raw.trim().ifEmpty { ctx.getString(R.string.packages_no_output) }
    return ctx.getString(R.string.packages_ncu_outdated_header) + matches.joinToString("\n")
}

/**
 * `prettier --check .` termina con "All matched files use Prettier code style!" si todo está
 * bien formateado, o imprime una lista de rutas relativas (una por línea) seguida de un aviso
 * "Code style issues found..." si no — ver prettier.io/docs/en/cli.html#--check. Se separan las
 * rutas del resto del ruido para mostrar solo la lista real de archivos con problemas.
 */
private fun formatPrettierCheck(ctx: Context, raw: String): String {
    if (raw.contains("All matched files use Prettier code style", ignoreCase = true)) {
        return ctx.getString(R.string.packages_prettier_all_good)
    }
    val files = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("[") && !it.contains("Code style issues found") }
        .toList()
    if (files.isEmpty()) return raw.trim().ifEmpty { ctx.getString(R.string.packages_no_output) }
    return ctx.getString(R.string.packages_prettier_bad_files_header) + files.joinToString("\n")
}

/**
 * `vercel whoami` imprime el username si hay sesión activa, o un error ("Error: The specified
 * token is not valid...") si no — ver vercel.com/docs/cli/whoami. Se muestra tal cual con un
 * encabezado corto porque ya es de una sola línea, no necesita parseo estructurado.
 */
private fun formatVercelWhoami(ctx: Context, raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.contains("Error", ignoreCase = true) || trimmed.isEmpty()) {
        ctx.getString(R.string.packages_vercel_no_session, trimmed)
    } else {
        ctx.getString(R.string.packages_vercel_active_session, trimmed)
    }
}

/**
 * `ncu -u` imprime el mismo listado de líneas ` nombre  ^actual  →  ^nueva` que la variante de
 * solo lectura, pero termina con "Run npm install to install new versions" — se reusa el mismo
 * parseo de [NCU_LINE] y se agrega el recordatorio real de la próxima acción manual (esta acción
 * solo reescribe `package.json`, no corre `npm install`).
 */
private fun formatNcuUpgrade(ctx: Context, raw: String): String {
    val matches = raw.lineSequence()
        .mapNotNull { line -> NCU_LINE.find(line) }
        .map { m -> "${m.groupValues[1]}: ${m.groupValues[2]} → ${m.groupValues[3]}" }
        .toList()
    if (matches.isEmpty()) return raw.trim().ifEmpty { ctx.getString(R.string.packages_no_output) }
    return ctx.getString(R.string.packages_ncu_updated_header) + matches.joinToString("\n") +
        ctx.getString(R.string.packages_ncu_updated_footer)
}

/**
 * `prettier --write .` imprime una línea por archivo reescrito (ruta relativa + tiempo, ej.
 * "src/index.js 12ms") sin encabezado — ver prettier.io/docs/cli. Se listan los archivos
 * reescritos tal cual, con un encabezado nativo corto.
 */
private fun formatPrettierWrite(ctx: Context, raw: String): String {
    val files = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (files.isEmpty()) return ctx.getString(R.string.packages_prettier_nothing_to_write)
    return ctx.getString(R.string.packages_prettier_written_header) + files.joinToString("\n")
}

/**
 * `vercel --prod --yes` imprime la URL de deployment en stdout si el deploy fue exitoso (ver
 * vercel.com/docs/cli/deploy, sección "Standard output usage": "stdout is always the Deployment
 * URL"), o un mensaje de error real (ej. proyecto no enlazado, sin login) si falla.
 */
private fun formatVercelDeploy(ctx: Context, raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.contains("Error", ignoreCase = true) || trimmed.isEmpty()) {
        ctx.getString(R.string.packages_vercel_deploy_failed, trimmed.ifEmpty { ctx.getString(R.string.packages_no_output_parenthetical) })
    } else {
        ctx.getString(R.string.packages_vercel_deploy_success, trimmed)
    }
}

/**
 * `ngrok config add-authtoken <TOKEN>` no imprime el token de vuelta — solo confirma con
 * "Authtoken saved to configuration file: <ruta>" (ver ngrok.com/docs/agent/cli/) o un error si
 * el token es inválido. Se muestra tal cual, ya es corto y no requiere parseo.
 */
private fun formatNgrokAuthtoken(ctx: Context, raw: String): String {
    val trimmed = raw.trim()
    return trimmed.ifEmpty { ctx.getString(R.string.packages_ngrok_no_output) }
}

/**
 * `tsc --init` imprime "Created a new tsconfig.json..." si funcionó, o el error real ("A
 * 'tsconfig.json' file is already defined...") si ya existía uno en ~ — se muestra tal cual, ya
 * es un mensaje de una o dos líneas.
 */
private fun formatTscInit(ctx: Context, raw: String): String {
    val trimmed = raw.trim()
    return trimmed.ifEmpty { ctx.getString(R.string.packages_tsc_no_output) }
}

/**
 * `nest new <nombre> --package-manager npm --skip-git` imprime el progreso de creación de
 * archivos + la instalación de dependencias (npm install real, puede ser largo — de ahí el
 * timeout de 180s) y termina con un mensaje de éxito real de Nest CLI. Se muestran las últimas
 * líneas (el resumen final), no el listado completo de archivos creados.
 */
private fun formatNestNew(ctx: Context, raw: String): String {
    val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return ctx.getString(R.string.packages_no_output)
    val tail = lines.takeLast(15)
    return tail.joinToString("\n")
}

/**
 * `vercel login --no-browser` (device flow, 2026 — ver vercel.com/changelog/new-vercel-cli-
 * login-flow y github.com/vercel/vercel/discussions/5468) imprime una URL de verificación +
 * un código de un solo uso para completar el login desde CUALQUIER navegador — el proceso se
 * corta a los 20s (ver [RunAction.readPartialOutput]) porque queda esperando la confirmación
 * real, que pasa fuera de este proceso. Se muestra el texto capturado tal cual (contiene la
 * URL/código reales) más un recordatorio de verificar con "Ver sesión" después.
 */
private fun formatVercelLogin(ctx: Context, raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ctx.getString(R.string.packages_vercel_login_no_output)
    return ctx.getString(R.string.packages_vercel_login_instructions, trimmed)
}
