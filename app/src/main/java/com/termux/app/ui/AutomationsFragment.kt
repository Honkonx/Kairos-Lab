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
import com.termux.app.util.AutomationSecrets
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
                // Agrupadas por disparador (rediseño aprobado 2026-10-08, boceto de Homelab): al arrancar,
                // a una hora fija, según la batería. Mismas automatizaciones, solo cambia la lectura.
                listOf(
                    TriggerType.BOOT to R.string.automations_group_boot,
                    TriggerType.SCHEDULE to R.string.automations_group_schedule,
                    TriggerType.BATTERY_LEVEL to R.string.automations_group_battery
                ).forEach { (type, titleRes) ->
                    val group = automations.filter { it.triggerType == type }
                    if (group.isEmpty()) return@forEach
                    listContainer.addView(TextView(requireContext()).apply {
                        text = getString(titleRes)
                        textSize = 11f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        letterSpacing = 0.08f
                        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                        setPadding(dp(4), dp(10), dp(4), dp(6))
                    })
                    group.forEach { a -> listContainer.addView(automationCard(a)) }
                }
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
        TriggerType.BATTERY_LEVEL -> {
            val dirRes = if (a.batteryDirection == AutomationManager.BatteryDirection.BELOW)
                R.string.automations_trigger_battery_below_fmt else R.string.automations_trigger_battery_above_fmt
            getString(dirRes, a.batteryThreshold)
        }
    }

    private fun actionLabel(a: Automation): String = when (a.actionType) {
        ActionType.START_MODULE -> "${getString(R.string.automations_action_start)} ${a.moduleId}"
        ActionType.STOP_MODULE -> "${getString(R.string.automations_action_stop)} ${a.moduleId}"
        ActionType.GATEWAY_ACTION -> gatewayActionLabel(a.gatewayActionId)
    }

    private fun gatewayActionLabel(id: String): String = when (id) {
        "restic.backup" -> getString(R.string.automations_gateway_action_restic_backup)
        "restic.snapshots" -> getString(R.string.automations_gateway_action_restic_snapshots)
        "db.backup" -> getString(R.string.automations_gateway_action_db_backup)
        "notify.send" -> getString(R.string.automations_gateway_action_notify_send)
        else -> id
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
                    AutomationManager.deleteAutomation(ctx, automation.id)
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
        // Sin "return" acá aunque modules esté vacío (hallazgo real: antes bloqueaba TODO el
        // diálogo, incluida la opción nueva ActionType.GATEWAY_ACTION, que no necesita ningún
        // módulo) -- el aviso solo importa de verdad si el usuario termina elige START_MODULE/
        // STOP_MODULE sin nada para elegir, y eso ya lo corta validate() al guardar.
        if (modules.isEmpty()) toast(getString(R.string.automations_msg_sin_modulos))

        val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_nombre)
            setText(existing?.name ?: "")
        }
        view.addView(nameEdit)

        val triggerOrder = listOf(TriggerType.BOOT, TriggerType.SCHEDULE, TriggerType.BATTERY_LEVEL)
        val triggerLabels = arrayOf(
            getString(R.string.automations_trigger_boot),
            getString(R.string.automations_trigger_schedule),
            getString(R.string.automations_trigger_battery)
        )
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

        // Solo visible/usado para BATTERY_LEVEL — umbral 1..100 (EditText numérico) + dirección
        // (Spinner BELOW/ABOVE, ver AutomationManager.BatteryDirection).
        val thresholdEdit = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.automations_hint_umbral_bateria)
            setText((existing?.batteryThreshold?.takeIf { existing.triggerType == TriggerType.BATTERY_LEVEL } ?: 20).toString())
        }
        view.addView(thresholdEdit)
        val directionOrder = listOf(AutomationManager.BatteryDirection.BELOW, AutomationManager.BatteryDirection.ABOVE)
        val directionLabels = arrayOf(getString(R.string.automations_battery_direction_below), getString(R.string.automations_battery_direction_above))
        val directionSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, directionLabels)
            setSelection(existing?.let { directionOrder.indexOf(it.batteryDirection) }?.takeIf { it >= 0 } ?: 0)
        }
        view.addView(directionSpinner)

        val actionOrder = listOf(ActionType.START_MODULE, ActionType.STOP_MODULE, ActionType.GATEWAY_ACTION)
        val actionLabels = arrayOf(
            getString(R.string.automations_action_start),
            getString(R.string.automations_action_stop),
            getString(R.string.automations_action_gateway)
        )
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

        // ActionType.GATEWAY_ACTION — un sub-spinner con las 3 acciones del gateway habilitadas
        // para programarse sin supervisión (ver AutomationManager.SAFE_GATEWAY_ACTIONS) + los
        // campos propios de cada una. No es un formulario genérico por `params` del manifiesto
        // (sería sobre-ingeniería para 3 acciones) -- cada acción tiene sus campos reales.
        val gatewayActionOrder = AutomationManager.SAFE_GATEWAY_ACTIONS
        val gatewayActionLabels = gatewayActionOrder.map { gatewayActionLabel(it) }.toTypedArray()
        val gatewayActionSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, gatewayActionLabels)
            setSelection(existing?.let { gatewayActionOrder.indexOf(it.gatewayActionId) }?.takeIf { it >= 0 } ?: 0)
        }
        view.addView(gatewayActionSpinner)

        val existingGatewayParams = existing?.takeIf { it.actionType == ActionType.GATEWAY_ACTION }?.let {
            try { org.json.JSONObject(it.gatewayParamsJson) } catch (_: Exception) { org.json.JSONObject() }
        } ?: org.json.JSONObject()
        val resticRepoEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_restic_repo)
            setText(existingGatewayParams.optString("repo", ""))
        }
        val resticFolderEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_restic_folder)
            setText(existingGatewayParams.optString("folder", ""))
        }
        // El password NUNCA se precarga (`.claude/rules/kairos-secrets-never-revealed.md`) --
        // vacío al reabrir siempre, con un hint distinto si ya hay uno guardado para avisar que
        // dejarlo vacío CONSERVA el existente en vez de borrarlo.
        val resticPasswordEdit = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(
                if (existing != null && AutomationSecrets.has(ctx, existing.id)) R.string.automations_hint_restic_password_saved
                else R.string.automations_hint_restic_password
            )
        }
        val dbEngineEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_db_engine)
            setText(existingGatewayParams.optString("engine", ""))
        }
        val dbNameEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_db_name)
            setText(existingGatewayParams.optString("name", ""))
        }
        val notifyTopicEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_notify_topic)
            setText(existingGatewayParams.optString("topic", ""))
        }
        val notifyTitleEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_notify_title)
            setText(existingGatewayParams.optString("title", ""))
        }
        val notifyMessageEdit = EditText(ctx).apply {
            hint = getString(R.string.automations_hint_notify_message)
            setText(existingGatewayParams.optString("message", ""))
        }
        listOf(resticRepoEdit, resticFolderEdit, resticPasswordEdit, dbEngineEdit, dbNameEdit, notifyTopicEdit, notifyTitleEdit, notifyMessageEdit).forEach { view.addView(it) }

        // Mostrar/ocultar los campos específicos de cada trigger — el usuario nunca ve un campo
        // irrelevante para el trigger que está configurando (mismo criterio que ya tenía el
        // horario para SCHEDULE, extendido acá a umbral+dirección para BATTERY_LEVEL).
        fun syncTriggerFieldsVisibility() {
            val selected = triggerOrder[triggerSpinner.selectedItemPosition]
            timeButton.visibility = if (selected == TriggerType.SCHEDULE) View.VISIBLE else View.GONE
            val batteryVisible = if (selected == TriggerType.BATTERY_LEVEL) View.VISIBLE else View.GONE
            thresholdEdit.visibility = batteryVisible
            directionSpinner.visibility = batteryVisible
        }
        triggerSpinner.post { syncTriggerFieldsVisibility() }
        triggerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = syncTriggerFieldsVisibility()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // Visibilidad por ActionType + por cuál acción del gateway está elegida -- moduleSpinner
        // solo para START_MODULE/STOP_MODULE; gatewayActionSpinner + sus campos propios solo para
        // GATEWAY_ACTION, y dentro de eso, solo los campos de la acción puntual seleccionada.
        fun syncGatewayActionFieldsVisibility() {
            val isRestic = gatewayActionOrder.getOrNull(gatewayActionSpinner.selectedItemPosition)?.startsWith("restic.") == true
            val isResticBackup = gatewayActionOrder.getOrNull(gatewayActionSpinner.selectedItemPosition) == "restic.backup"
            val isDb = gatewayActionOrder.getOrNull(gatewayActionSpinner.selectedItemPosition) == "db.backup"
            val isNotify = gatewayActionOrder.getOrNull(gatewayActionSpinner.selectedItemPosition) == "notify.send"
            resticRepoEdit.visibility = if (isRestic) View.VISIBLE else View.GONE
            resticFolderEdit.visibility = if (isResticBackup) View.VISIBLE else View.GONE
            resticPasswordEdit.visibility = if (isRestic) View.VISIBLE else View.GONE
            dbEngineEdit.visibility = if (isDb) View.VISIBLE else View.GONE
            dbNameEdit.visibility = if (isDb) View.VISIBLE else View.GONE
            notifyTopicEdit.visibility = if (isNotify) View.VISIBLE else View.GONE
            notifyTitleEdit.visibility = if (isNotify) View.VISIBLE else View.GONE
            notifyMessageEdit.visibility = if (isNotify) View.VISIBLE else View.GONE
        }
        fun syncActionFieldsVisibility() {
            val selected = actionOrder[actionSpinner.selectedItemPosition]
            moduleSpinner.visibility = if (selected == ActionType.GATEWAY_ACTION) View.GONE else View.VISIBLE
            gatewayActionSpinner.visibility = if (selected == ActionType.GATEWAY_ACTION) View.VISIBLE else View.GONE
            if (selected == ActionType.GATEWAY_ACTION) syncGatewayActionFieldsVisibility() else {
                listOf(resticRepoEdit, resticFolderEdit, resticPasswordEdit, dbEngineEdit, dbNameEdit, notifyTopicEdit, notifyTitleEdit, notifyMessageEdit).forEach { it.visibility = View.GONE }
            }
        }
        actionSpinner.post { syncActionFieldsVisibility() }
        actionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = syncActionFieldsVisibility()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        gatewayActionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = syncGatewayActionFieldsVisibility()
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
                val batteryThreshold = thresholdEdit.text.toString().trim().toIntOrNull() ?: 20
                if (triggerType == TriggerType.BATTERY_LEVEL && batteryThreshold !in 1..100) {
                    toast(getString(R.string.automations_msg_umbral_invalido)); return@setPositiveButton
                }
                val batteryDirection = directionOrder[directionSpinner.selectedItemPosition]
                val actionType = actionOrder[actionSpinner.selectedItemPosition]
                val appCtx = ctx.applicationContext

                if (actionType == ActionType.GATEWAY_ACTION) {
                    val gatewayActionId = gatewayActionOrder[gatewayActionSpinner.selectedItemPosition]
                    val params = org.json.JSONObject()
                    val secret: String
                    when (gatewayActionId) {
                        "restic.backup" -> {
                            val repo = resticRepoEdit.text.toString().trim()
                            val folder = resticFolderEdit.text.toString().trim()
                            if (repo.isBlank() || folder.isBlank()) { toast(getString(R.string.automations_msg_campos_gateway_incompletos)); return@setPositiveButton }
                            params.put("repo", repo).put("folder", folder)
                            secret = resticPasswordEdit.text.toString().trim()
                        }
                        "restic.snapshots" -> {
                            val repo = resticRepoEdit.text.toString().trim()
                            if (repo.isBlank()) { toast(getString(R.string.automations_msg_campos_gateway_incompletos)); return@setPositiveButton }
                            params.put("repo", repo)
                            secret = resticPasswordEdit.text.toString().trim()
                        }
                        "db.backup" -> {
                            val engine = dbEngineEdit.text.toString().trim()
                            val dbName = dbNameEdit.text.toString().trim()
                            if (engine !in listOf("mysql", "postgres") || dbName.isBlank()) { toast(getString(R.string.automations_msg_campos_gateway_incompletos)); return@setPositiveButton }
                            params.put("engine", engine).put("name", dbName)
                            secret = ""
                        }
                        "notify.send" -> {
                            val topic = notifyTopicEdit.text.toString().trim()
                            val message = notifyMessageEdit.text.toString().trim()
                            if (topic.isBlank() || message.isBlank()) { toast(getString(R.string.automations_msg_campos_gateway_incompletos)); return@setPositiveButton }
                            params.put("topic", topic).put("message", message)
                            notifyTitleEdit.text.toString().trim().takeIf { it.isNotBlank() }?.let { params.put("title", it) }
                            secret = ""
                        }
                        else -> { secret = "" }
                    }
                    // Sin password NUEVO guardado todavía (ni existe ni se tipeó uno nuevo) y la
                    // acción SÍ necesita uno -> cortar acá, antes de persistir una automatización
                    // inservible (restic fallaría siempre en runtime sin contraseña).
                    val needsPassword = gatewayActionId.startsWith("restic.")
                    val alreadyHasSecret = existing != null && AutomationSecrets.has(appCtx, existing.id)
                    if (needsPassword && secret.isBlank() && !alreadyHasSecret) {
                        toast(getString(R.string.automations_msg_campos_gateway_incompletos)); return@setPositiveButton
                    }
                    val paramsJson = params.toString()
                    if (existing == null) {
                        runInBackground({ AutomationManager.addAutomation(appCtx, name, triggerType, scheduleTime, actionType, "", batteryThreshold, batteryDirection, gatewayActionId, paramsJson, secret) }) { result ->
                            onSaveResult(result, appCtx, result.message.takeIf { result.ok } ?: "", triggerType, scheduleTime, name, actionType, "", gatewayActionId, paramsJson)
                        }
                    } else {
                        runInBackground({ AutomationManager.updateAutomation(appCtx, existing.id, name, triggerType, scheduleTime, actionType, "", batteryThreshold, batteryDirection, gatewayActionId, paramsJson, secret) }) { result ->
                            onSaveResult(result, appCtx, existing.id, triggerType, scheduleTime, name, actionType, "", gatewayActionId, paramsJson)
                        }
                    }
                    return@setPositiveButton
                }

                if (modules.isEmpty()) { toast(getString(R.string.automations_msg_sin_modulos)); return@setPositiveButton }
                val module = modules[moduleSpinner.selectedItemPosition]
                if (existing == null) {
                    runInBackground({ AutomationManager.addAutomation(appCtx, name, triggerType, scheduleTime, actionType, module.id, batteryThreshold, batteryDirection) }) { result ->
                        onSaveResult(result, appCtx, result.message.takeIf { result.ok } ?: "", triggerType, scheduleTime, name, actionType, module.id)
                    }
                } else {
                    runInBackground({ AutomationManager.updateAutomation(appCtx, existing.id, name, triggerType, scheduleTime, actionType, module.id, batteryThreshold, batteryDirection) }) { result ->
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
        moduleId: String,
        gatewayActionId: String = "",
        gatewayParamsJson: String = "{}"
    ) {
        if (result.ok && automationId.isNotBlank()) {
            val effectiveName = name.ifBlank { if (actionType == ActionType.GATEWAY_ACTION) gatewayActionId else moduleId }
            val saved = Automation(
                automationId, effectiveName, triggerType, scheduleTime, actionType, moduleId, enabled = true,
                gatewayActionId = gatewayActionId, gatewayParamsJson = gatewayParamsJson
            )
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
