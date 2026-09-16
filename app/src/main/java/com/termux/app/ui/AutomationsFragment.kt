package com.termux.app.ui

import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.data.ModuleCatalog
import com.termux.app.util.AutomationManager
import com.termux.app.util.AutomationManager.ActionType
import com.termux.app.util.AutomationManager.Automation
import com.termux.app.util.AutomationManager.TriggerType
import com.termux.app.util.AutomationScheduler
import com.termux.app.util.kairosThemeColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Pantalla "Automatizaciones" — ver `docs/modulos/AUTOMATIZACIONES.md` y el KDoc de
 * [AutomationManager] para el diseño completo. Triggers registrables (arranque del dispositivo,
 * horario) + acción real sobre un módulo (iniciar/detener), sin que el usuario tenga que abrir la
 * app — el gap más grande identificado en la auditoría de `rikkahub-agent` esta sesión.
 *
 * 100% Kotlin nativo, sin bash — mismo criterio que [HomelabFragment] (UI 100% programática, no
 * extiende `BaseModuleFragment` porque no es un módulo con instalar/iniciar/detener/lifecycle de
 * proceso propio, es una pantalla de gestión de workflows sobre módulos YA existentes).
 */
class AutomationsFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyState: TextView

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        val ctx = requireContext()
        val root = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
            isFillViewport = true
        }
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        root.addView(container)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(sectionTitle(getString(R.string.automations_title)).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        header.addView(smallButton(getString(R.string.automations_btn_agregar)) { showEditDialog(null) })
        container.addView(header)
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.automations_subtitle)
            textSize = 12f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        emptyState = TextView(requireContext()).apply {
            text = getString(R.string.automations_empty)
            textSize = 12f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        listContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        container.addView(emptyState)
        container.addView(listContainer)

        // Red de seguridad barata (ver KDoc de AutomationScheduler.scheduleAll) — no cuesta
        // nada re-armar alarmas ya armadas, y cubre el caso de una alarma perdida sin depender
        // solo del boot receiver.
        Thread { try { AutomationScheduler.scheduleAll(requireContext().applicationContext) } catch (_: Exception) {} }.start()

        refresh()
    }

    private fun refresh() {
        if (!isAdded) return
        Thread {
            val automations = try { AutomationManager.listAutomations() } catch (_: Exception) { emptyList() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                listContainer.removeAllViews()
                emptyState.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                automations.forEach { a -> listContainer.addView(automationCard(a)) }
            }
        }.start()
    }

    // ── Card por automatización ──────────────────────────────────────────────────────────────

    private fun automationCard(automation: Automation): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = resources.getDimension(R.dimen.kairos_stroke_default)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(8) }
            setOnLongClickListener { showAutomationMenu(automation); true }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = automation.name
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(ctx).apply {
                text = "${triggerLabel(automation)} → ${actionLabel(automation)}"
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
            addView(TextView(ctx).apply {
                text = lastRunLabel(automation.lastRunTs)
                textSize = 10f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
        })
        row.addView(SwitchCompat(ctx).apply {
            isChecked = automation.enabled
            setOnCheckedChangeListener { toggle, checked ->
                if (!toggle.isPressed) return@setOnCheckedChangeListener
                runInBackground({
                    val result = AutomationManager.setEnabled(automation.id, checked)
                    if (result.ok) AutomationScheduler.scheduleOne(ctx.applicationContext, automation.copy(enabled = checked))
                    result
                }) { result -> if (!result.ok) { toast(result.error); refresh() } }
            }
        })
        card.addView(row)
        return card
    }

    private fun triggerLabel(a: Automation): String = when (a.triggerType) {
        TriggerType.BOOT -> getString(R.string.automations_trigger_boot)
        TriggerType.SCHEDULE -> getString(R.string.automations_trigger_schedule_fmt, a.scheduleTime)
    }

    private fun actionLabel(a: Automation): String {
        val verb = when (a.actionType) {
            ActionType.START_MODULE -> getString(R.string.automations_action_start)
            ActionType.STOP_MODULE -> getString(R.string.automations_action_stop)
        }
        return "$verb ${a.moduleId}"
    }

    private fun lastRunLabel(ts: Long): String {
        if (ts <= 0L) return getString(R.string.automations_never_run)
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        return getString(R.string.automations_last_run_fmt, fmt.format(java.util.Date(ts)))
    }

    private fun showAutomationMenu(automation: Automation) {
        val options = arrayOf(getString(R.string.automations_menu_editar), getString(R.string.automations_menu_eliminar))
        AlertDialog.Builder(requireContext())
            .setTitle(automation.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(automation)
                    1 -> confirmDelete(automation)
                }
            }
            .show()
    }

    private fun confirmDelete(automation: Automation) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.automations_dialog_title_eliminar))
            .setMessage(getString(R.string.automations_dialog_msg_eliminar, automation.name))
            .setPositiveButton(getString(R.string.automations_btn_eliminar)) { _, _ ->
                val ctx = requireContext().applicationContext
                runInBackground({
                    AutomationScheduler.cancelOne(ctx, automation.id)
                    AutomationManager.deleteAutomation(automation.id)
                }) { result -> toast(if (result.ok) getString(R.string.automations_msg_eliminada) else result.error); refresh() }
            }
            .setNegativeButton(getString(R.string.automations_btn_cancelar), null)
            .show()
    }

    // ── Diálogo agregar/editar ───────────────────────────────────────────────────────────────

    private fun showEditDialog(existing: Automation?) {
        val ctx = requireContext()

        // hasSwitch=true — mismo campo que ya usa ModuleListAdapter para decidir qué módulos
        // tienen un toggle real de iniciar/detener (servidor con proceso propio: Ollama, n8n,
        // llama-server, etc.) — un CLI puro sin switch (hasSwitch=false, ej. Claude Code, Codex)
        // no tiene start/stop script real, así que ModuleController.startModule()/stopModule()
        // no tendrían nada que hacer con él. !internal excluye los ids "consolidados" dentro de
        // los módulos contenedor languages/packages (ver ModuleInfo.internal).
        val modules = try {
            ModuleCatalog.load(ctx).filter { it.hasSwitch && !it.internal }.sortedBy { it.name.lowercase() }
        } catch (_: Exception) { emptyList() }
        if (modules.isEmpty()) {
            toast(getString(R.string.automations_msg_sin_modulos))
            return
        }

        val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_nombre)
            setText(existing?.name ?: "")
        }
        view.addView(nameEdit)

        val triggerOrder = listOf(TriggerType.BOOT, TriggerType.SCHEDULE)
        val triggerLabels = arrayOf(getString(R.string.automations_trigger_boot), getString(R.string.automations_trigger_schedule))
        val triggerSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, triggerLabels)
            setSelection(existing?.let { triggerOrder.indexOf(it.triggerType) }?.takeIf { it >= 0 } ?: 0)
        }
        view.addView(triggerSpinner)

        var selectedTime = existing?.scheduleTime?.takeIf { AutomationManager.isValidHHmm(it) } ?: ""
        val timeButton = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            setPadding(dp(4), dp(12), dp(4), dp(4))
            text = if (selectedTime.isNotBlank()) getString(R.string.automations_hint_horario_elegido_fmt, selectedTime)
                   else getString(R.string.automations_hint_horario)
            setOnClickListener {
                val cal = Calendar.getInstance()
                val parts = selectedTime.split(":")
                val initHour = parts.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.HOUR_OF_DAY)
                val initMinute = parts.getOrNull(1)?.toIntOrNull() ?: cal.get(Calendar.MINUTE)
                TimePickerDialog(ctx, { _, hour, minute ->
                    selectedTime = String.format(Locale.US, "%02d:%02d", hour, minute)
                    text = getString(R.string.automations_hint_horario_elegido_fmt, selectedTime)
                }, initHour, initMinute, true).show()
            }
        }
        view.addView(timeButton)

        val actionOrder = listOf(ActionType.START_MODULE, ActionType.STOP_MODULE)
        val actionLabels = arrayOf(getString(R.string.automations_action_start), getString(R.string.automations_action_stop))
        val actionSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, actionLabels)
            setSelection(existing?.let { actionOrder.indexOf(it.actionType) }?.takeIf { it >= 0 } ?: 0)
        }
        view.addView(actionSpinner)

        val moduleLabels = modules.map { it.name }.toTypedArray()
        val moduleSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, moduleLabels)
            setSelection(existing?.let { ex -> modules.indexOfFirst { it.id == ex.moduleId } }?.takeIf { it >= 0 } ?: 0)
        }
        view.addView(moduleSpinner)

        // Mostrar/ocultar el selector de horario según el trigger elegido — el usuario nunca ve
        // un campo irrelevante para el trigger que está configurando.
        fun syncTimeVisibility() {
            timeButton.visibility = if (triggerOrder[triggerSpinner.selectedItemPosition] == TriggerType.SCHEDULE) View.VISIBLE else View.GONE
        }
        triggerSpinner.post { syncTimeVisibility() }
        triggerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = syncTimeVisibility()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(if (existing == null) R.string.automations_dialog_title_agregar else R.string.automations_dialog_title_editar))
            .setView(wrapDialogContent(view))
            .setPositiveButton(getString(R.string.automations_btn_guardar)) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val triggerType = triggerOrder[triggerSpinner.selectedItemPosition]
                val scheduleTime = if (triggerType == TriggerType.SCHEDULE) selectedTime else ""
                if (triggerType == TriggerType.SCHEDULE && !AutomationManager.isValidHHmm(scheduleTime)) {
                    toast(getString(R.string.automations_msg_horario_invalido)); return@setPositiveButton
                }
                val actionType = actionOrder[actionSpinner.selectedItemPosition]
                val module = modules[moduleSpinner.selectedItemPosition]
                val appCtx = ctx.applicationContext
                if (existing == null) {
                    runInBackground({ AutomationManager.addAutomation(appCtx, name, triggerType, scheduleTime, actionType, module.id) }) { result ->
                        onSaveResult(result, appCtx, result.message.takeIf { result.ok } ?: "", triggerType, scheduleTime, name, actionType, module.id)
                    }
                } else {
                    runInBackground({ AutomationManager.updateAutomation(appCtx, existing.id, name, triggerType, scheduleTime, actionType, module.id) }) { result ->
                        onSaveResult(result, appCtx, existing.id, triggerType, scheduleTime, name, actionType, module.id)
                    }
                }
            }
            .setNegativeButton(getString(R.string.automations_btn_cancelar), null)
            .show()
    }

    /** Reprograma/cancela la alarma de AlarmManager correspondiente después de guardar — sin
     *  esto, editar el horario de un workflow ya guardado seguiría disparando a la hora VIEJA
     *  hasta el próximo reboot (AutomationScheduler solo re-lee el registry en boot/apertura de
     *  esta pantalla). */
    private fun onSaveResult(
        result: AutomationManager.ActionResult,
        appCtx: android.content.Context,
        automationId: String,
        triggerType: TriggerType,
        scheduleTime: String,
        name: String,
        actionType: ActionType,
        moduleId: String
    ) {
        if (result.ok && automationId.isNotBlank()) {
            val saved = Automation(automationId, name.ifBlank { moduleId }, triggerType, scheduleTime, actionType, moduleId, enabled = true)
            Thread { try { AutomationScheduler.scheduleOne(appCtx, saved) } catch (_: Exception) {} }.start()
        }
        toast(if (result.ok) getString(R.string.automations_msg_guardada) else result.error)
        refresh()
    }

    // ── Helpers de estilo/layout — versión reducida y autónoma (mismo criterio que
    //    HomelabFragment, no extiende BaseModuleFragment) ──────────────────────────────────────

    private fun sectionTitle(text: String): View = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
        setPadding(dp(4), dp(4), dp(4), dp(2))
    }

    private fun smallButton(text: String, onClick: () -> Unit): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(28, 0, 122, 255))
            setOnClickListener { onClick() }
        }
    }

    private fun wrapDialogContent(content: View): View {
        return CardView(requireContext()).apply {
            radius = 0f
            cardElevation = 0f
            setContentPadding(dp(20), dp(8), dp(20), dp(0))
            setCardBackgroundColor(Color.TRANSPARENT)
            addView(content)
        }
    }

    private fun dp(d: Int): Int = (d * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun <T> runInBackground(work: () -> T, onResult: (T) -> Unit) {
        Thread {
            val result = work()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { if (isAdded) onResult(result) }
        }.start()
    }
}
