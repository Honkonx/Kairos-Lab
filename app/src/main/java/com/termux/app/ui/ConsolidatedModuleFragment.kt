package com.termux.app.ui

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.app.ModuleController
import com.termux.app.data.ModuleInstalled
import com.termux.app.data.ModuleRegistry
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.applyTermuxEnv
import java.util.concurrent.TimeUnit
import com.termux.app.util.kairosThemeColor

/**
 * Un ítem consolidado dentro de "Lenguajes"/"Paquetes" (LanguagesFragment/PackagesFragment,
 * ver docs/modulos/LENGUAJES.md, 2026-08-18) — cada uno es un `modulos/<id>.sh` REAL sin
 * tocar (ver `modules.json`, campo `internal: true`), solo se cambia cómo se presenta: en vez
 * de una tarjeta propia en la Tienda, es una fila con switch dentro de un módulo contenedor.
 *
 * [versionCommand] es el comando de shell de una sola línea para detectar la versión real
 * instalada (ej. "node --version") — se corre solo si el registry no tiene ya
 * "<id>.version" (más barato, evita spawnear bash para algo que install_single_pkg()/
 * install_npm_global() ya escribieron en modulos/lib.sh::registry_install()).
 */
data class SwitchModuleItem(
    val id: String,
    val name: String,
    val versionCommand: String,
    val note: String = "",
    val runActions: List<RunAction> = emptyList()
)

/**
 * Acción nativa opcional de un [SwitchModuleItem] — pedido explícito del usuario (auditoría de
 * "tools", 2026-08-19): convertir el output real de un CLI en un panel nativo (diálogo con
 * scroll) en vez de forzar al usuario a abrir la terminal a mano, para las herramientas cuyo
 * output de una sola corrida es corto y parseable (`ncu`, `prettier --check`, `vercel whoami`).
 * Se corre con `bash -c "cd \"$HOME\" && <command>"` (mismo patrón que [readVersion]) — no hay
 * noción de "proyecto activo" en este módulo contenedor, así que corre sobre $HOME por defecto;
 * el usuario puede seguir usando "Abrir en terminal" (otro módulo) para correrlo sobre un path
 * específico. [formatter] recibe el output crudo (stdout+stderr combinados) y devuelve el texto
 * a mostrar — por defecto se muestra tal cual.
 */
data class RunAction(
    val label: String,
    val command: String,
    val timeoutSeconds: Long = 25,
    val formatter: (String) -> String = { it },
    // Auditoría "tools" 2026-08-19 (docs/arquitectura/AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_OFICIAL_2026-08-19.md):
    // ncu -u y prettier --write MUTAN archivos reales en $HOME — a diferencia de las acciones
    // de solo lectura de la ronda anterior (ncu bare, prettier --check, vercel whoami), estas
    // requieren un paso de confirmación explícito antes de correr, nunca mutación silenciosa.
    val requiresConfirmation: Boolean = false,
    val confirmMessage: String = "",
    // Caso ngrok config add-authtoken: el comando necesita un valor que solo el usuario tiene
    // (el token), no hardcodeable en el manifest. [command] lleva un placeholder "%s" que se
    // reemplaza por el valor tipeado (citado con comillas simples, escapando comillas internas)
    // antes de ejecutar — el token nunca se loguea/toastea en texto plano en ningún paso.
    val requiresTextInput: Boolean = false,
    val textInputHint: String = "",
    // Caso `vercel login --no-browser` (auditoría "tools" 2026-08-19, 3ra ronda): el proceso
    // imprime la URL/código de verificación y después se queda esperando la confirmación del
    // usuario en el navegador (polling) — nunca cierra stdout por su cuenta, así que el
    // `readText()` bloqueante de [runItemAction] jamás retornaría antes del timeout real. Con
    // este flag, el output se lee en un hilo lector separado y se corta a los
    // [timeoutSeconds] (matando el proceso si sigue vivo) en vez de esperar a que cierre solo —
    // alcanza para capturar la URL/código que se imprime al arrancar, que es lo único que hace
    // falta mostrar (el usuario completa el login en su navegador de todos modos, fuera de la
    // vida del proceso).
    val readPartialOutput: Boolean = false
)

/**
 * Base compartida (DRY) para los 2 módulos contenedor de esta ronda — evita reimplementar la
 * misma fila "nombre + estado/versión + switch real" dos veces. El switch ON llama
 * [ModuleController.installModule] (mismo mecanismo real que BottomSheetInstalacion, sin su
 * hoja de diálogo) y OFF llama [ModuleController.deepUninstallModule] (desinstalación REAL del
 * paquete pkg/npm, no solo un reset de estado) — pedido explícito del usuario: "deben ser
 * modulos ya listos y solo poder desactivar o activar".
 */
abstract class ConsolidatedModuleFragment : BaseModuleFragment() {

    protected abstract val screenId: String
    protected abstract val screenName: String
    protected abstract val introText: String
    protected abstract val items: List<SwitchModuleItem>

    override fun getModuleId(): String = screenId
    override fun getModuleName(): String = screenName

    // Módulo contenedor sin instalador propio (ver ModuleInstalled.BINARY_FALLBACK,
    // sentinel "bash") — siempre "instalado" para no disparar la hoja de instalación de
    // BaseModuleFragment.showNotInstalled().
    override fun isModuleInstalled(): Boolean = true

    private val switchRefs = mutableMapOf<String, SwitchCompat>()
    private val subtitleRefs = mutableMapOf<String, TextView>()
    private val runButtonRefs = mutableMapOf<String, List<TextView>>()

    override fun buildContent() {
        addCard(getString(R.string.consolidated_section_que_es)) {
            addView(TextView(requireContext()).apply {
                text = introText
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                setPadding(dp(14), dp(10), dp(14), dp(10))
            })
        }
        addExtraRows()
        val groups = itemGroups()
        if (groups == null) {
            addCard(sectionTitle()) {
                items.forEach { item -> addView(buildSwitchRow(item)) }
            }
        } else {
            // Pestañas reales por categoría (2026-08-26, pedido explícito del usuario, usando
            // BaseModuleFragment.setupTabs() — ver docs/estructura/COMPONENTES_UI.md y
            // PackagesFragment.itemGroups()). Hook opcional: LanguagesFragment no lo sobreescribe
            // y sigue con la card única de siempre (itemGroups() == null, rama de arriba).
            val builder = setupTabs(groups.map { it.first }, tabMode = com.google.android.material.tabs.TabLayout.MODE_FIXED)
            groups.forEachIndexed { index, (title, groupItems) ->
                builder.tab(index) { parent ->
                    addCard(title, parent) {
                        groupItems.forEach { item -> addView(buildSwitchRow(item)) }
                    }
                }
            }
            builder.build()
        }
        refreshAllStatuses()
    }

    /** Hook opcional para filas extra antes de la lista de switches (ej. Python en Lenguajes). */
    protected open fun addExtraRows() {}

    protected open fun sectionTitle(): String = getString(R.string.consolidated_section_default_title)

    /**
     * Hook opcional (2026-08-26, ver comentario de [buildContent] arriba): cuando no es null,
     * agrupa [items] en pestañas reales (`setupTabs()`) en vez de la card única de
     * [sectionTitle]. Cada `Pair` es (nombre de pestaña, subconjunto de [items] — DEBE ser una
     * partición real de `items`, sin duplicar ni omitir ningún id, o el switch de ese ítem
     * simplemente no aparece en ninguna pestaña). Default null preserva el comportamiento
     * anterior para cualquier subclase que no lo sobreescriba (ej. LanguagesFragment).
     */
    protected open fun itemGroups(): List<Pair<String, List<SwitchModuleItem>>>? = null

    private fun buildSwitchRow(item: SwitchModuleItem): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val textColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(ctx).apply {
            text = item.name
            textSize = 14f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        val subtitle = TextView(ctx).apply {
            text = getString(R.string.consolidated_status_checking)
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        }
        textColumn.addView(subtitle)
        if (item.note.isNotEmpty()) {
            textColumn.addView(TextView(ctx).apply {
                text = item.note
                textSize = 10f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
        }
        subtitleRefs[item.id] = subtitle

        row.addView(textColumn)

        if (item.runActions.isNotEmpty()) {
            val buttons = item.runActions.map { action ->
                val runButton = TextView(ctx).apply {
                    text = action.label
                    textSize = 11f
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    // Oculto hasta que applyStatus() confirme que está instalado — no tiene
                    // sentido ofrecer "Revisar" para un binario que ni siquiera existe todavía.
                    visibility = View.GONE
                    setOnClickListener { onRunActionClicked(item, action) }
                }
                row.addView(runButton)
                runButton
            }
            runButtonRefs[item.id] = buttons
        }

        val toggle = SwitchCompat(ctx).apply {
            thumbTintList = ContextCompat.getColorStateList(ctx, R.color.switch_thumb_color)
            trackTintList = ContextCompat.getColorStateList(ctx, R.color.switch_track_color)
        }
        switchRefs[item.id] = toggle
        row.addView(toggle)
        return row
    }

    private fun refreshAllStatuses() {
        items.forEach { item -> refreshStatus(item) }
    }

    private fun refreshStatus(item: SwitchModuleItem) {
        val ctx = requireContext().applicationContext
        Thread {
            val installed = ModuleInstalled.isInstalledRobust(ctx, item.id)
            val version = if (installed) readVersion(ctx, item) else ""
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                applyStatus(item, installed, version)
            }
        }.start()
    }

    private fun applyStatus(item: SwitchModuleItem, installed: Boolean, version: String) {
        subtitleRefs[item.id]?.text = when {
            installed && version.isNotEmpty() -> getString(R.string.consolidated_status_installed_version, version)
            installed -> getString(R.string.consolidated_status_installed)
            else -> getString(R.string.consolidated_status_not_installed)
        }
        switchRefs[item.id]?.apply {
            setOnCheckedChangeListener(null)
            isChecked = installed
            setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
        }
        val visibility = if (installed) View.VISIBLE else View.GONE
        runButtonRefs[item.id]?.forEach { it.visibility = visibility }
    }

    private fun readVersion(ctx: android.content.Context, item: SwitchModuleItem): String {
        ModuleRegistry(ctx).load().get("${item.id}.version")?.let { if (it.isNotEmpty()) return it }
        return try {
            val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", item.versionCommand)
            pb.applyTermuxEnv()
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().lineSequence().firstOrNull()?.trim() ?: ""
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            output
        } catch (_: Exception) {
            ""
        }
    }

    private fun onToggle(item: SwitchModuleItem, checked: Boolean) {
        if (checked) installItem(item) else confirmUninstallItem(item)
    }

    private fun installItem(item: SwitchModuleItem) {
        subtitleRefs[item.id]?.text = getString(R.string.consolidated_status_installing)
        toast(getString(R.string.consolidated_toast_installing, item.name))
        ModuleController.installModule(item.id, requireContext(), null, false, {}) { ok ->
            if (!isAdded) return@installModule
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (ok) getString(R.string.consolidated_toast_installed, item.name) else getString(R.string.consolidated_toast_install_failed, item.id))
                refreshStatus(item)
            }
        }
    }

    private fun confirmUninstallItem(item: SwitchModuleItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.consolidated_dialog_title_uninstall, item.name))
            .setMessage(getString(R.string.consolidated_dialog_msg_uninstall))
            .setPositiveButton(getString(R.string.consolidated_btn_uninstall)) { _, _ -> uninstallItem(item) }
            .setNegativeButton(getString(R.string.consolidated_btn_cancel)) { _, _ ->
                // Revertir el switch visual sin disparar de nuevo el listener (mismo patrón
                // que ConfigFragment.addToggleRow) — el usuario canceló, sigue instalado.
                switchRefs[item.id]?.apply {
                    setOnCheckedChangeListener(null)
                    isChecked = true
                    setOnCheckedChangeListener { _, isChecked -> onToggle(item, isChecked) }
                }
            }
            .setOnCancelListener {
                switchRefs[item.id]?.apply {
                    setOnCheckedChangeListener(null)
                    isChecked = true
                    setOnCheckedChangeListener { _, isChecked -> onToggle(item, isChecked) }
                }
            }
            .show()
    }

    private fun uninstallItem(item: SwitchModuleItem) {
        subtitleRefs[item.id]?.text = getString(R.string.consolidated_status_uninstalling)
        ModuleController.deepUninstallModule(item.id) { ok, message ->
            if (!isAdded) return@deepUninstallModule
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (ok) message else getString(R.string.consolidated_toast_uninstall_failed, item.name))
                refreshStatus(item)
            }
        }
    }

    /**
     * Punto de entrada único del botón de acción — decide si hace falta pedir texto (token),
     * confirmar antes de mutar archivos, o correr directo (caso de solo lectura de la ronda
     * anterior). Ver docs/arquitectura/AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_OFICIAL_2026-08-19.md.
     */
    private fun onRunActionClicked(item: SwitchModuleItem, action: RunAction) {
        when {
            action.requiresTextInput -> promptTextInputAction(item, action)
            action.requiresConfirmation -> confirmRunAction(item, action)
            else -> runItemAction(item, action)
        }
    }

    private fun confirmRunAction(item: SwitchModuleItem, action: RunAction) {
        AlertDialog.Builder(requireContext())
            .setTitle(action.label)
            .setMessage(action.confirmMessage.ifEmpty { getString(R.string.consolidated_dialog_msg_default_confirm) })
            .setPositiveButton(getString(R.string.consolidated_btn_continue)) { _, _ -> runItemAction(item, action) }
            .setNegativeButton(getString(R.string.consolidated_btn_cancel), null)
            .show()
    }

    /**
     * Diálogo de texto para acciones que necesitan un valor que solo el usuario tiene (ej. el
     * token de `ngrok config add-authtoken`) — [RunAction.command] lleva un placeholder "%s"
     * reemplazado acá por el valor citado con comillas simples (escapando comillas internas al
     * estilo shell: `'` → `'\''`) para no permitir que el texto pegado rompa el `bash -c`. El
     * valor tipeado nunca se loguea/toastea — solo viaja al ProcessBuilder real.
     */
    private fun promptTextInputAction(item: SwitchModuleItem, action: RunAction) {
        val ctx = requireContext()
        val edit = EditText(ctx).apply {
            hint = action.textInputHint
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(ctx)
            .setTitle(action.label)
            .setView(edit)
            .setPositiveButton(getString(R.string.consolidated_btn_save)) { _, _ ->
                val value = edit.text.toString().trim()
                if (value.isEmpty()) {
                    toast(getString(R.string.consolidated_toast_empty_value))
                } else {
                    val quoted = "'" + value.replace("'", "'\\''") + "'"
                    runItemAction(item, action.copy(command = action.command.replace("%s", quoted)))
                }
            }
            .setNegativeButton(getString(R.string.consolidated_btn_cancel), null)
            .show()
    }

    /**
     * Corre [RunAction.command] real vía bash y muestra el resultado parseado en un diálogo
     * nativo — conversión (b)→(a) de la auditoría "tools" 2026-08-19: antes estas herramientas
     * (ncu/prettier/vercel) no tenían NINGUNA acción de uso expuesta en la app (solo instalar/
     * desinstalar), el usuario tenía que abrir la terminal a mano y recordar el comando.
     */
    private fun runItemAction(item: SwitchModuleItem, action: RunAction) {
        toast(getString(R.string.consolidated_toast_running_action, action.label))
        Thread {
            val output = try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "cd \"\$HOME\" && ${action.command}")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                if (action.readPartialOutput) {
                    readPartialOutputWithTimeout(process, action.timeoutSeconds)
                } else {
                    val text = process.inputStream.bufferedReader().readText()
                    val finished = process.waitFor(action.timeoutSeconds, TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        getString(R.string.consolidated_output_timeout, text)
                    } else {
                        text
                    }
                }
            } catch (e: Exception) {
                getString(R.string.consolidated_error_execution, e.message)
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                showOutputDialog("${item.name} — ${action.label}", action.formatter(output))
            }
        }.start()
    }

    /**
     * Lector con corte por tiempo — ver [RunAction.readPartialOutput]. A diferencia de
     * `process.inputStream.bufferedReader().readText()` (que bloquea hasta EOF real), acá se
     * lee en un hilo dedicado mientras el hilo de trabajo espera con [Process.waitFor] con
     * timeout; si el proceso sigue vivo al vencer el plazo se lo mata con
     * [Process.destroyForcibly] y se devuelve lo que se haya podido leer hasta ese momento.
     */
    private fun readPartialOutputWithTimeout(process: Process, timeoutSeconds: Long): String {
        val buffer = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val readerThread = Thread {
            try {
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(buffer) { buffer.append(chunk, 0, read) }
                }
            } catch (_: Exception) {
                // Stream cerrado por destroyForcibly() — esperado, no es un error real.
            }
        }
        readerThread.isDaemon = true
        readerThread.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        readerThread.join(1000)
        val captured = synchronized(buffer) { buffer.toString() }
        return if (finished) captured else getString(R.string.consolidated_output_partial_timeout, captured, timeoutSeconds.toString())
    }

    private fun showOutputDialog(title: String, body: String) {
        val ctx = requireContext()
        val text = TextView(ctx).apply {
            text = body.ifBlank { getString(R.string.consolidated_output_empty) }
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(ctx).apply { addView(text) }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(getString(R.string.consolidated_btn_close), null)
            .show()
    }
}
