package com.termux.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.content.res.ColorStateList
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.util.BackupManager
import com.termux.app.util.ConfigExportManager
import com.termux.app.util.DiagnosticExportManager
import com.termux.app.util.OverlayPermissionHelper
import com.termux.app.util.TelegramNotifier
import com.termux.app.wizard.WizardActivity
import com.termux.shared.termux.TermuxConstants
import java.io.File
import com.termux.app.util.kairosThemeColor
import com.google.android.material.snackbar.Snackbar

class ConfigFragment : Fragment() {

    private val PREFS_NAME = "kairos_prefs"
    private lateinit var envVarsContainer: LinearLayout

    // Debe registrarse como campo de instancia (no dentro de onViewCreated/onClick) —
    // mismo patrón que ChatFragment.mPickImageLauncher / WizardPermissionsFragment.kt,
    // requisito del ciclo de vida de ActivityResultLauncher.
    private val mPickConfigFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { confirmImportConfig(it) }
        }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_config, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)

        val generalContainer = view.findViewById<LinearLayout>(R.id.general_container)
        // Selector de tema visual (2026-08-22, ver docs/humano/humano190.md, pedido explícito del
        // usuario: "no es eliminar la tematica/tema/estilo que tenemos es agregar una opcion
        // para cambiar el tema... dejar el que tenemos, añadir ese que te dije y tambien un
        // modo claro"). Recreate() es el único mecanismo real para que un cambio de
        // setTheme() (aplicado en TermuxActivity.setActivityTheme(), ANTES de onCreate) tome
        // efecto sin reiniciar la app entera — mismo patrón que ya usa AppCompatActivityUtils.
        // setNightMode() (recreate=true) un poco más arriba en el mismo método.
        addThemePickerRow(generalContainer)
        // Selector de idioma (2026-08-28) — pedido explícito del usuario tras la migración i18n
        // de hoy: "en la pantalla de config no veo donde diga idioma y salga español y ingles".
        // AppCompatDelegate.setApplicationLocales() (ver KairosLanguagePrefs.kt) maneja la
        // recreación de Activities y la persistencia por su cuenta — no hace falta recreate()
        // manual como con el picker de tema.
        addLanguagePickerRow(generalContainer)
        // "Log Kairos" — pedido explícito del usuario (ver docs/humano231.md): "un log interno
        // completo del apk incluso de la terminal [...] Ojo, NO debe ser log de módulos, es log
        // completo del APK en sí". Reusa InlineThemePicker.row (mismo componente que la fila de
        // Tema de arriba) en vez de BaseModuleFragment.dropdownRow() — ConfigFragment no extiende
        // esa clase base, ver comentario de cabecera de InlineThemePicker.kt.
        addLogKairosRow(generalContainer)
        addClickableRow(generalContainer, getString(R.string.config_row_ver_log)) { showKairosLogDialog() }
        addToggleRow(generalContainer, getString(R.string.config_row_auto_start),
            prefs.getBoolean("pref_auto_start", false)) { checked ->
            prefs.edit().putBoolean("pref_auto_start", checked).apply()
            if (checked) toast(getString(R.string.config_toast_auto_start_on))
        }
        // Bug real (2026-08-22, ver docs/humano/humano191.md, pedido explícito del usuario): esta fila
        // duplicaba (peor, más incompleta) lo que MonitorFragment.kt ya hace bien — Monitor
        // detecta el ESTADO real (PowerManager + setting global del phantom process killer),
        // cubre tanto batería como phantom killer, y ofrece 3 vías de fix (root/nmap-auto/
        // tutorial manual). Esta fila solo abría el intent genérico de batería sin detectar
        // nada ni cubrir el phantom killer — "no deberiamos dejarlo en un solo lugar?". Se
        // quita de acá, queda solo en Monitor (Sistema → sección "Fila phantom killer"/
        // "Fila optimización de batería").
        // Misma acción que la pantalla opcional del wizard (RootfsInstaller/cmd_rootfs
        // sync) — acá se puede repetir cuando el usuario quiera, no solo en el primer
        // arranque. Pedido explícito del usuario: "esta opción también debe estar en
        // el apk", ver docs/humano/humano11.md.
        addClickableRow(generalContainer, getString(R.string.config_row_check_system_packages)) {
            runRootfsCheck()
        }
        // Chequeo de versión a nivel de MÓDULO individual (no del rootfs base) — pedido
        // explícito del usuario, complemento de "Comprobar paquetes del sistema" de arriba.
        // 2026-08-14 (humano123 C1): "Verificar todas" — cubre npm + GitHub Releases + PyPI +
        // claude + apt (antes solo npm), ver ModuleVersionChecker.kt. Solo informa (nada se
        // instala automáticamente); actualizar cada módulo lo sigue disparando el usuario
        // desde su propia pantalla con "Actualizar".
        addClickableRow(generalContainer, getString(R.string.config_row_check_module_updates)) {
            runModuleVersionCheck()
        }
        // Sincroniza los scripts on-device (~/scripts/install/<id>.sh) contra el repo público
        // Kairos-Lab — ver KairosLabModuleSync.kt para el mecanismo completo (bug real que
        // resuelve: los scripts extraídos de los assets del APK nunca se re-copian salvo que
        // versionCode cambie, ver KairosBootstrap.isAlreadyExtracted()).
        addClickableRow(generalContainer, getString(R.string.config_row_check_lab_updates)) {
            runKairosLabModuleCheck()
        }
        addToggleRow(generalContainer, getString(R.string.config_row_notify_modules_down),
            prefs.getBoolean("pref_notify_modules", true)) { checked ->
            // La detección real vive en ModulesFragment.pollStatus() (compara el estado
            // del ciclo anterior contra el actual) — acá solo se guarda la preferencia.
            prefs.edit().putBoolean("pref_notify_modules", checked).apply()
        }
        // Nota: si falta el permiso de overlay, el switch queda visualmente "on" hasta
        // que el usuario navegue de vuelta a esta pantalla (addToggleRow no expone el
        // SwitchCompat para resetearlo desde acá) — no crashea ni inicia el servicio de
        // verdad, solo un desfase visual menor. Aceptable para este alcance.
        addToggleRow(generalContainer, getString(R.string.config_row_floating_widget),
            prefs.getBoolean("pref_floating_widget", false)) { checked ->
            if (checked) {
                if (hasOverlayPermission()) {
                    prefs.edit().putBoolean("pref_floating_widget", true).apply()
                    requireContext().startService(Intent(requireContext(), com.termux.app.FloatingWidgetService::class.java))
                } else {
                    toast(getString(R.string.config_toast_grant_overlay))
                    // OverlayPermissionHelper (2026-08-01, ver docs/referencias/REFERENCIA_TERMUX_APP_X11_SUBMODULE.md
                    // "float-ball"): antes esto lanzaba SIEMPRE el intent genérico
                    // ACTION_MANAGE_OVERLAY_PERMISSION — en MIUI/Xiaomi esa pantalla puede quedar
                    // confusa o no reflejar bien el estado real del permiso; el helper prueba el
                    // genérico primero y cae a un atajo específico de MIUI (o, como último
                    // recurso, a los detalles de la app) si hace falta.
                    OverlayPermissionHelper.requestOverlayPermission(requireActivity())
                }
            } else {
                prefs.edit().putBoolean("pref_floating_widget", false).apply()
                requireContext().stopService(Intent(requireContext(), com.termux.app.FloatingWidgetService::class.java))
            }
        }

        // Bug real (2026-08-07, ver docs/humano/humano91.md): "Node proot"/"Python"/
        // "Claude Code"/"Dashboard" quedaban en "—" para siempre — ningún código en este
        // archivo los volvía a tocar. "Dashboard" además referencia un módulo ya eliminado
        // del stack por completo (ver .claude/rules/scripts-rule.md: "dashboard ❌
        // eliminado"), y Python/Claude Code duplican info que ya se muestra de verdad en
        // sus propias pantallas de módulo — se quitan en vez de fingir que son reales.
        // Sección TERMINAL — mejora de menor esfuerzo ya identificada (ver MEJORAS_PENDIENTES.md,
        // "Terminal sin personalizar por CLI", idea 2) para el pedido explícito del usuario
        // "mejorar la terminal de los modulos" (2026-08-12, ver docs/humano/humano99.md): fzf y
        // zsh-autosuggestions son paquetes/plugin reales sin empaquetado custom, se ofrecen acá
        // como instalación opcional en vez de forzarlos en todos los módulos.
        val terminalContainer = view.findViewById<LinearLayout>(R.id.terminal_container)
        addClickableRow(terminalContainer, getString(R.string.config_row_install_fzf_zsh), R.drawable.ic_install) {
            installFzfZshAutosuggestions()
        }
        // Segunda mejora de la terminal de módulos (2026-08-13, ver humano101): fijar nvim
        // como editor por defecto (EDITOR + alias vim) — complementa el módulo IDE nuevo
        // (modulos/ide.sh, Neovim + NvChad) y aplica a todas las terminales nuevas.
        addClickableRow(terminalContainer, getString(R.string.config_row_set_nvim_editor)) {
            setNvimAsDefaultEditor()
        }
        // Pedido explícito del usuario (2026-08-13, ver docs/humano/humano115.md): "facilitar
        // los paquetes para usar teclado y mouse sea por otg o bluetooth" — Android ya soporta
        // teclado/mouse externo de forma nativa en cualquier vista con foco (incluida
        // TerminalView) sin ningún paquete/driver adicional; lo que faltaba era visibilidad de
        // que la opción existe y cómo emparejar, no código de soporte nuevo.
        addClickableRow(terminalContainer, getString(R.string.config_row_external_input)) {
            showExternalInputGuide()
        }
        // Pedido explícito del usuario (2026-08-13, ver docs/humano/humano118.md): poder
        // elegir entre el modo "adaptado" (barra de info + sidebar de acciones rápidas, el
        // default de Kairos para CLIs) y la terminal clásica de Termux (sesión normal, sin la
        // UI encima) — leído por TermuxActivity.openTerminalWithCommand() antes de decidir
        // mTerminalAdaptedMode.
        addToggleRow(terminalContainer, getString(R.string.config_row_classic_terminal),
            prefs.getBoolean("pref_classic_terminal", false)) { checked ->
            prefs.edit().putBoolean("pref_classic_terminal", checked).apply()
            toast(if (checked) getString(R.string.config_toast_classic_terminal_on) else getString(R.string.config_toast_classic_terminal_off))
        }

        val infoContainer = view.findViewById<LinearLayout>(R.id.info_container)
        addInfoRow(infoContainer, getString(R.string.config_info_architecture), Build.SUPPORTED_ABIS[0])
        addDivider(infoContainer)
        addInfoRow(infoContainer, getString(R.string.config_info_kairos), "v0.7.0")
        addDivider(infoContainer)
        // MVP de modo root (2026-08-25, ver docs/arquitectura/INVESTIGACION_MODO_ROOT_2026-08-25.md)
        // — puramente informativo acá, sin acciones extra (las acciones reales que usan root
        // viven en Ciberseguridad/nmap y Monitor/Sistema). RootAccess.hasRoot() bloquea (corre
        // un subproceso "su -c", hasta 3s la primera vez) — se resuelve en background, nunca en
        // el hilo de UI de onViewCreated().
        val rootInfoRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        rootInfoRow.addView(TextView(requireContext()).apply {
            text = getString(R.string.config_info_root_access)
            textSize = 13f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
        })
        val rootInfoValue = TextView(requireContext()).apply {
            text = getString(R.string.config_root_checking)
            textSize = 12f
            gravity = Gravity.END
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
        }
        rootInfoRow.addView(rootInfoValue)
        infoContainer.addView(rootInfoRow)
        Thread {
            val detected = com.termux.app.util.RootAccess.hasRoot()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                rootInfoValue.text = if (detected) getString(R.string.config_root_detected) else getString(R.string.config_root_not_detected)
                rootInfoValue.setTextColor(
                    requireContext().kairosThemeColor(if (detected) R.attr.kairosGreen else R.attr.kairosText)
                )
            }
        }.start()

        envVarsContainer = view.findViewById(R.id.env_vars_container)
        refreshEnvVars()
        view.findViewById<View>(R.id.btn_add_env_var).setOnClickListener { showAddEnvVarDialog() }

        view.findViewById<View>(R.id.btn_rerun_setup).setOnClickListener { showRerunSetupDialog() }
        view.findViewById<View>(R.id.btn_backup_full).setOnClickListener { runFullBackup() }
        // "Restaurar backup" se agrega en código (no en XML) — fragment_config.xml no tiene
        // btn_backup_restore y el XML está fuera de alcance esta ronda; mismo patrón que
        // setupTelegramSection() (que también arma su sección 100% en Kotlin). Se inserta
        // justo debajo de "Backup completo" en la sección MANTENIMIENTO.
        val maintenanceContainer = (view as ViewGroup).getChildAt(0) as LinearLayout
        val restoreRow = TextView(requireContext()).apply {
            text = getString(R.string.config_row_restore_backup)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
            setBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBg3))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(8) }
            setOnClickListener { runRestoreBackup() }
        }
        maintenanceContainer.addView(restoreRow, maintenanceContainer.indexOfChild(view.findViewById(R.id.btn_backup_full)) + 1)
        view.findViewById<View>(R.id.btn_export_config).setOnClickListener { confirmExportConfig() }
        view.findViewById<View>(R.id.btn_import_config).setOnClickListener { confirmPickImportConfig() }
        view.findViewById<View>(R.id.btn_export_diagnostics).setOnClickListener { confirmExportDiagnostics() }
        view.findViewById<View>(R.id.btn_reinstall).setOnClickListener { showReinstallDialog() }
        // Pedido explícito del usuario (auditoría 2026-08-05, ver docs/humano65.md/humano66.md):
        // "en ningún tab o menú... sale para desinstalar módulos" — a diferencia de "Reinstalar
        // stack" (arriba, borra TODO), esto es por módulo individual.
        addClickableRow(generalContainer, getString(R.string.config_row_uninstall_module), R.drawable.ic_uninstall) { showUninstallModuleDialog() }
        // Pedido explícito del usuario (ver docs/humano/humano68.md): "la opcion de salir que
        // mate todos los servicios y luego cierre la app como si pusiera exit en la terminal" —
        // distinto de un botón genérico "cerrar app" (que el propio usuario descartó en la
        // ronda anterior, ver humano67.md, por no ser algo que una app pueda lograr de forma
        // confiable desde adentro): esto SÍ detiene servicios reales primero.
        addClickableRow(generalContainer, getString(R.string.config_row_exit_app), R.drawable.ic_stop) { confirmExitApp() }

        setupTelegramSection(view, prefs)
    }

    // ────────────────────────────────────────────────────────────
    // Notificaciones Telegram — pedido explícito del usuario (2026-08-13, ver
    // docs/humano/humano118.md, plan en docs/mini-pc/PLAN_EXPANSION_HOMELAB_2026-08-13.md
    // sección 4): ítem de mayor valor/menor esfuerzo de la auditoría de referencia/ciberseguridad/
    // i-Haklab-master (patrón walkie-tg). Sección armada 100% en código (sin XML nuevo) porque
    // esta ronda de trabajo tiene fragment_config.xml fuera de alcance — ver TelegramNotifier.kt
    // para el HTTP real.
    // ────────────────────────────────────────────────────────────

    private fun setupTelegramSection(root: View, prefs: android.content.SharedPreferences) {
        val ctx = requireContext()
        val outerContainer = (root as ViewGroup).getChildAt(0) as LinearLayout

        val header = TextView(ctx).apply {
            text = getString(R.string.config_telegram_header)
            textSize = 10f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                it.topMargin = dp(16); it.bottomMargin = dp(8); it.marginStart = dp(4)
            }
        }

        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            setContentPadding(0, 0, 0, 0)
        }
        val cardBody = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        card.addView(cardBody)

        val tokenInput = EditText(ctx).apply {
            hint = getString(R.string.config_telegram_hint_token)
            setText(prefs.getString(TelegramNotifier.PREF_BOT_TOKEN, "") ?: "")
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            textSize = 13f
        }
        val chatIdInput = EditText(ctx).apply {
            hint = getString(R.string.config_telegram_hint_chatid)
            setText(prefs.getString(TelegramNotifier.PREF_CHAT_ID, "") ?: "")
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(6) }
        }
        cardBody.addView(tokenInput)
        cardBody.addView(chatIdInput)

        val testRow = TextView(ctx).apply {
            text = getString(R.string.config_telegram_test_row)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(2))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setOnClickListener {
                testTelegramConfig(prefs, tokenInput.text.toString().trim(), chatIdInput.text.toString().trim())
            }
        }
        cardBody.addView(testRow)

        // Al final de la lista de secciones, antes del spacer de 24dp con el que cierra el layout.
        val insertIndex = (outerContainer.childCount - 1).coerceAtLeast(0)
        outerContainer.addView(header, insertIndex)
        outerContainer.addView(card, insertIndex + 1)
    }

    private fun testTelegramConfig(prefs: android.content.SharedPreferences, token: String, chatId: String) {
        if (token.isBlank() || chatId.isBlank()) {
            toast(getString(R.string.config_toast_telegram_fill_fields))
            return
        }
        // Guarda al probar — así "Probar" confirma exactamente lo que va a quedar en uso
        // (ModuleEventBridge.notify() lee estas mismas claves), en vez de un guardado separado
        // que pueda desincronizarse de lo que el usuario ve en pantalla.
        prefs.edit()
            .putString(TelegramNotifier.PREF_BOT_TOKEN, token)
            .putString(TelegramNotifier.PREF_CHAT_ID, chatId)
            .apply()
        toast(getString(R.string.config_toast_telegram_sending))
        Thread {
            val result = TelegramNotifier.sendMessageDetailed(
                token, chatId, getString(R.string.config_telegram_test_message)
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (result.ok) {
                    resultSnackbar(getString(R.string.config_snackbar_telegram_sent))
                } else {
                    resultSnackbar(getString(R.string.config_snackbar_telegram_error, result.error ?: getString(R.string.config_unknown)))
                }
            }
        }.start()
    }

    private fun confirmExitApp() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_exit_dialog_title))
            .setMessage(getString(R.string.config_exit_dialog_message))
            .setPositiveButton(getString(R.string.config_btn_exit)) { _, _ ->
                toast(getString(R.string.config_toast_stopping_services))
                com.termux.app.ModuleController.stopAllModules {
                    if (!isAdded) return@stopAllModules
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        // Bug real reportado (ver docs/humano231.md): "Salir" solo detenía
                        // scripts de módulos (arriba) y cerraba de golpe con exitProcess(0) —
                        // nunca tocaba las TerminalSession abiertas, dejando cualquier pty con
                        // un job en foreground esperando ENTER. TermuxActivity.requestExitAllSessions()
                        // manda Ctrl-C+"exit" a todas las sesiones en paralelo antes de cerrar.
                        val activity = requireActivity()
                        if (activity is com.termux.app.TermuxActivity) {
                            activity.requestExitAllSessions {
                                if (!isAdded) return@requestExitAllSessions
                                activity.runOnUiThread {
                                    activity.finishAffinity()
                                    kotlin.system.exitProcess(0)
                                }
                            }
                        } else {
                            activity.finishAffinity()
                            kotlin.system.exitProcess(0)
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    // Resultados importantes (backup/restore/desinstalar/config) usan Snackbar, no Toast —
    // pulido visual 2026-08-25 (docs/estructura/ESTILO_VISUAL_2026-08-25.md), mismo patrón ya
    // establecido en PluginsFragment.kt. Guard de isAdded: puede llamarse desde un callback de
    // background thread después de que el Fragment ya se desadjuntó (kotlin-kairos-android-patterns.md).
    private fun resultSnackbar(msg: String) {
        if (!isAdded) return
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show()
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(requireContext())

    // ────────────────────────────────────────────────────────────
    // Terminal — fzf + zsh-autosuggestions (mejora opcional, no forzada en ningún módulo).
    // ────────────────────────────────────────────────────────────

    /**
     * fzf es un paquete real de Termux (`pkg install fzf`). zsh-autosuggestions NO tiene
     * paquete propio — es un plugin de Oh My Zsh que se clona a mano (confirmado vía
     * WebSearch, docs/humano/humano99.md): requiere zsh + git + Oh My Zsh (instalador
     * oficial en modo `--unattended`, sin prompts) + clonar el plugin + activarlo en
     * `~/.zshrc`. No cambia el shell por defecto de la sesión — el usuario sigue entrando
     * a `zsh` a mano cuando quiera probarlo, mismo criterio que el resto de módulos
     * opcionales de Kairos (nunca se auto-activa nada sin que el usuario lo pida).
     */
    private fun installFzfZshAutosuggestions() {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.config_progress_terminal_title), getString(R.string.config_progress_installing_fzf))

        Thread {
            val u = com.termux.app.util.ManagerNativeUtils
            val (fzfRc, _, fzfErr) = u.runShell("pkg install -y fzf zsh git", 120)
            if (fzfRc != 0) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { progress.failure(getString(R.string.config_progress_fzf_error), fzfErr.takeLast(200)) }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { progress.update(getString(R.string.config_progress_installing_omz)) }

            val d = '$'
            val cmd = "if [ ! -d \"${d}HOME/.oh-my-zsh\" ]; then sh -c \"${d}(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\" \"\" --unattended; fi; " +
                "PLUGIN_DIR=\"${d}HOME/.oh-my-zsh/custom/plugins/zsh-autosuggestions\"; " +
                "if [ ! -d \"${d}PLUGIN_DIR\" ]; then git clone --depth=1 https://github.com/zsh-users/zsh-autosuggestions \"${d}PLUGIN_DIR\"; fi; " +
                "if [ -f \"${d}HOME/.zshrc\" ] && ! grep -q zsh-autosuggestions \"${d}HOME/.zshrc\"; then sed -i 's/^plugins=(git)/plugins=(git zsh-autosuggestions)/' \"${d}HOME/.zshrc\"; fi"
            val (rc, _, err) = u.runShell(cmd, 180)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (rc == 0) {
                    progress.success(getString(R.string.config_progress_fzf_success))
                } else {
                    progress.failure(getString(R.string.config_progress_omz_error), err.takeLast(200))
                }
            }
        }.start()
    }

    // Editor por defecto = nvim (pedido 2026-08-13, ver humano101). Escribe en ~/.bashrc
    // (idempotente: grep antes de agregar) y aplica a las terminales nuevas. Si nvim no está
    // instalado, avisa y sugiere el módulo IDE (modulos/ide.sh).
    private fun showExternalInputGuide() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_external_input_title))
            .setMessage(getString(R.string.config_external_input_message))
            .setPositiveButton(getString(R.string.config_btn_close), null)
            .show()
    }

    private fun setNvimAsDefaultEditor() {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.config_progress_terminal_title), getString(R.string.config_progress_checking_nvim))

        Thread {
            val u = com.termux.app.util.ManagerNativeUtils
            val (rc, _, err) = u.runShell(
                "command -v nvim >/dev/null 2>&1 && " +
                    "(grep -q 'EDITOR=nvim' \"${'$'}HOME/.bashrc\" 2>/dev/null || " +
                    "echo 'export EDITOR=nvim' >> \"${'$'}HOME/.bashrc\") && " +
                    "(grep -q 'alias vim=nvim' \"${'$'}HOME/.bashrc\" 2>/dev/null || " +
                    "echo 'alias vim=nvim' >> \"${'$'}HOME/.bashrc\"); echo 'EDITOR seteado en ~/.bashrc'",
                60
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (rc == 0) {
                    progress.success(getString(R.string.config_progress_nvim_success))
                } else {
                    progress.failure(
                        getString(R.string.config_progress_nvim_error),
                        err.takeLast(200)
                    )
                }
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Comprobar paquetes — RootfsPackageChecker.kt (100% Kotlin, sin python3),
    // misma acción que la pantalla opcional del wizard.
    // ────────────────────────────────────────────────────────────

    // Migrado a ProgressDialogController (quick win de la auditoría de referencia/,
    // 2026-08-05, ver docs/humano70.md — "extenderlo más allá de Entorno") — antes era un
    // AlertDialog+ProgressBar armado a mano que solo mostraba el resultado final en un Toast
    // que desaparece solo; ahora el resultado queda visible en el propio diálogo.
    private fun runRootfsCheck() {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.config_progress_check_packages_title), getString(R.string.config_progress_checking_packages))

        Thread {
            val result = try {
                com.termux.app.util.RootfsPackageChecker.sync(ctx) { p ->
                    if (!isAdded) return@sync
                    requireActivity().runOnUiThread { progress.update(p) }
                }
            } catch (e: Exception) {
                null
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (result != null) {
                    progress.success(
                        getString(R.string.config_progress_packages_success, result.installedNow.size, result.updatedNow.size)
                    )
                } else {
                    progress.failure(getString(R.string.config_progress_packages_error))
                }
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Verificar actualizaciones de módulos — ModuleVersionChecker.kt, solo cubre los módulos
    // instalados 100% vía npm global (freebuff/codebuff/mimocode/minimaxcli/copilotcli/
    // qwencode/codex — ver comentario de cabecera de ese archivo para la lista completa de
    // módulos NO cubiertos y por qué). Solo informa — instalar la actualización real se sigue
    // disparando desde la pantalla propia de cada módulo ("Actualizar"), esto no instala nada.
    // ────────────────────────────────────────────────────────────

    private fun runModuleVersionCheck() {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        val checker = com.termux.app.util.ModuleVersionChecker
        // Ronda 2026-08-14 (humano123 C1): antes solo cubría npm. Ahora el chequeo cubre
        // todas las fuentes (npm + GitHub Releases + PyPI + claude + apt) — ver
        // ModuleVersionChecker.kt cabecera para la lista por mecanismo.
        progress.show(getString(R.string.config_progress_module_updates_title), getString(R.string.config_progress_querying_modules, checker.supportedModuleIds().size))

        Thread {
            val results = try {
                checker.checkAllModules()
            } catch (e: Exception) {
                null
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (results == null) {
                    progress.failure(getString(R.string.config_progress_versions_error))
                    return@runOnUiThread
                }
                val withUpdate = results.filter { it.updateAvailable }
                val detail = results.joinToString("\n") { r ->
                    when {
                        r.updateAvailable -> getString(R.string.config_module_update_line_available, r.moduleId, r.installedVersion, r.latestVersion)
                        r.installedVersion != null && r.latestVersion != null -> getString(R.string.config_module_update_line_uptodate, r.moduleId, r.installedVersion)
                        else -> getString(R.string.config_module_update_line_no_data, r.moduleId, r.note ?: getString(R.string.config_module_no_data_fallback))
                    }
                }
                if (withUpdate.isEmpty()) {
                    progress.success(getString(R.string.config_progress_all_uptodate), detail)
                } else {
                    progress.success(
                        getString(R.string.config_progress_updates_available, withUpdate.size, withUpdate.joinToString(", ") { it.moduleId }),
                        detail
                    )
                }
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Verificar actualizaciones de módulos — Kairos-Lab (KairosLabModuleSync.kt). Distinto de
    // runModuleVersionCheck() de arriba: ese compara la versión del BINARIO instalado contra
    // npm/PyPI/GitHub Releases/apt (7-8 mecanismos distintos, cubre solo un subconjunto de
    // módulos); este compara el SCRIPT .sh on-device (~/scripts/install/<id>.sh) contra el
    // contenido real publicado en github.com/Honkonx/Kairos-Lab — cubre TODOS los módulos con
    // script (sin importar cómo instalan su binario), y es lo único que resuelve el bug real de
    // "un fix a un .sh no llega al dispositivo sin bump de versionCode" (ver cabecera de
    // KairosLabModuleSync.kt).
    // ────────────────────────────────────────────────────────────

    private fun runKairosLabModuleCheck() {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.config_progress_lab_updates_title), getString(R.string.config_progress_querying_lab))

        Thread {
            val result = try {
                com.termux.app.util.KairosLabModuleSync.checkForUpdates(ctx)
            } catch (e: Exception) {
                com.termux.app.util.KairosLabModuleSync.CheckResult(false, error = e.message)
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (!result.ok) {
                    progress.failure(getString(R.string.config_lab_check_error), result.error ?: getString(R.string.config_unknown))
                    return@runOnUiThread
                }
                val totalChecked = result.updatable.size + result.upToDate.size + result.newlyTracked.size
                if (result.updatable.isEmpty()) {
                    val msg = if (result.newlyTracked.isNotEmpty()) {
                        getString(R.string.config_lab_all_uptodate_with_new, totalChecked, result.newlyTracked.size)
                    } else {
                        getString(R.string.config_lab_all_uptodate, totalChecked)
                    }
                    progress.success(msg)
                    return@runOnUiThread
                }
                progress.dismiss()
                showKairosLabUpdatesDialog(result.updatable)
            }
        }.start()
    }

    private fun showKairosLabUpdatesDialog(updatable: List<com.termux.app.util.KairosLabModuleSync.RemoteScript>) {
        val ctx = requireContext()
        val labels = updatable.map { it.moduleId }.toTypedArray()
        val checked = BooleanArray(updatable.size) { true }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.config_lab_updates_dialog_title, updatable.size))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(getString(R.string.config_lab_btn_update_selected)) { _, _ ->
                val selected = updatable.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    toast(getString(R.string.config_lab_toast_select_at_least_one))
                } else {
                    applyKairosLabUpdates(selected)
                }
            }
            .setNeutralButton(getString(R.string.config_lab_btn_update_all)) { _, _ ->
                applyKairosLabUpdates(updatable)
            }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    private fun applyKairosLabUpdates(scripts: List<com.termux.app.util.KairosLabModuleSync.RemoteScript>) {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.config_progress_lab_updates_title), getString(R.string.config_lab_applying, scripts.size))

        Thread {
            val results = scripts.map { com.termux.app.util.KairosLabModuleSync.applyUpdate(ctx, it) }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val okCount = results.count { it.ok }
                val failed = results.filterNot { it.ok }
                if (failed.isEmpty()) {
                    progress.success(getString(R.string.config_lab_apply_summary_ok_only, okCount))
                } else {
                    val detail = failed.joinToString("\n") { "${it.moduleId}: ${it.error ?: getString(R.string.config_unknown)}" }
                    progress.success(getString(R.string.config_lab_apply_summary, okCount, failed.size), detail)
                }
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Full backup — BackupManager.kt (100% Kotlin, invoca el `tar` real de Termux
    // vía ProcessBuilder; antes pasaba por kairos_manager.py backup create/list
    // usando tarfile de Python). Respalda scripts + registry + .bashrc + configs de
    // módulos a Download/KairosBackups.
    // ────────────────────────────────────────────────────────────

    private fun runFullBackup() {
        toast(getString(R.string.config_toast_creating_backup))
        val ts = System.currentTimeMillis().toString()
        Thread {
            val result = BackupManager.create(ts)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (result.optBoolean("ok", false)) {
                    resultSnackbar(getString(R.string.config_snackbar_backup_created, result.optString("size_human", ""), result.optString("path", "")))
                } else {
                    resultSnackbar(getString(R.string.config_snackbar_backup_error, result.optString("error", getString(R.string.config_unknown))))
                }
            }
        }.start()
    }

    /**
     * Restaura un backup completo (mismo flujo que "Backup completo" pero invertido): lista
     * los .tar.gz existentes con BackupManager.list(), deja elegir uno en un AlertDialog de
     * radio y corre BackupManager.restore(file) en background — patrón idéntico a
     * runFullBackup() (guard `if (!isAdded) return@Thread` + requireActivity().runOnUiThread).
     */
    private fun runRestoreBackup() {
        toast(getString(R.string.config_toast_searching_backups))
        Thread {
            val result = BackupManager.list()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!result.optBoolean("ok", false)) {
                    toast(getString(R.string.config_toast_list_backups_error, result.optString("error", getString(R.string.config_unknown))))
                    return@runOnUiThread
                }
                val arr = result.optJSONArray("backups") ?: org.json.JSONArray()
                if (arr.length() == 0) {
                    toast(getString(R.string.config_toast_no_backups))
                    return@runOnUiThread
                }
                val names = (0 until arr.length()).map { arr.getJSONObject(it).optString("name", "?") }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.config_restore_dialog_title))
                    .setItems(names) { _, index ->
                        val file = File(arr.getJSONObject(index).optString("path", ""))
                        confirmRestoreBackup(file)
                    }
                    .setNegativeButton(getString(R.string.config_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun confirmRestoreBackup(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_confirm_restore_title, file.name))
            .setMessage(getString(R.string.config_confirm_restore_message))
            .setPositiveButton(getString(R.string.config_btn_restore)) { _, _ ->
                toast(getString(R.string.config_toast_restoring))
                Thread {
                    val result = BackupManager.restore(file)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (result.optBoolean("ok", false)) {
                            resultSnackbar(getString(R.string.config_snackbar_restore_success))
                        } else {
                            resultSnackbar(getString(R.string.config_snackbar_restore_error, result.optString("error", getString(R.string.config_unknown))))
                        }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    // ────────────────────────────────────────────────────────────
    // Exportar/Importar configuración — ConfigExportManager.kt (100% Kotlin), complementa
    // el "Backup completo" de arriba: un JSON liviano (registry + prefs relevantes + config
    // chica por módulo) pensado para migrar ajustes entre instalaciones, en vez del .tar.gz
    // completo con scripts/. Credenciales (tokens, api keys, password) se redactan en el
    // JSON — ver el comentario de cabecera de ConfigExportManager.kt para el criterio.
    // ────────────────────────────────────────────────────────────

    private fun confirmExportConfig() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_export_config_title))
            .setMessage(getString(R.string.config_export_config_message))
            .setPositiveButton(getString(R.string.config_btn_export)) { _, _ -> runExportConfig() }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    private fun runExportConfig() {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.config_export_config_title), getString(R.string.config_progress_generating_file))

        Thread {
            val result = ConfigExportManager.exportConfig(ctx)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (result.optBoolean("ok", false)) {
                    val redacted = result.optInt("redacted_count", 0)
                    val redactedNote = if (redacted > 0) getString(R.string.config_export_redacted_note, redacted) else ""
                    progress.success(getString(R.string.config_export_success, result.optString("size_human", ""), result.optString("path", ""), redactedNote))
                } else {
                    progress.failure(getString(R.string.config_export_error), result.optString("error", getString(R.string.config_unknown)))
                }
            }
        }.start()
    }

    private fun confirmPickImportConfig() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_import_config_title))
            .setMessage(getString(R.string.config_import_config_message))
            .setPositiveButton(getString(R.string.config_btn_choose_file)) { _, _ -> mPickConfigFileLauncher.launch("application/json") }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    private fun confirmImportConfig(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_confirm_import_title))
            .setMessage(getString(R.string.config_confirm_import_message))
            .setPositiveButton(getString(R.string.config_btn_import)) { _, _ -> runImportConfig(uri) }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    private fun runImportConfig(uri: Uri) {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.config_import_config_title), getString(R.string.config_progress_reading_file))

        Thread {
            val tempFile = try {
                File.createTempFile("kairos-config-import", ".json", ctx.cacheDir).also { tmp ->
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw java.io.IOException(getString(R.string.config_import_read_error))
                }
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    progress.failure(getString(R.string.config_import_read_error), e.message ?: getString(R.string.config_unknown))
                }
                return@Thread
            }

            val result = try {
                ConfigExportManager.importConfig(ctx, tempFile)
            } finally {
                tempFile.delete()
            }

            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!result.optBoolean("ok", false) && result.has("error")) {
                    progress.failure(getString(R.string.config_import_error_title), result.optString("error", getString(R.string.config_unknown)))
                    return@runOnUiThread
                }
                val appliedCount = result.optJSONArray("applied")?.length() ?: 0
                val skippedCount = result.optJSONArray("skipped")?.length() ?: 0
                val errorsCount = result.optJSONArray("errors")?.length() ?: 0
                val summary = buildString {
                    append(getString(R.string.config_import_summary_applied, appliedCount))
                    if (skippedCount > 0) append(getString(R.string.config_import_summary_skipped, skippedCount))
                    if (errorsCount > 0) append(getString(R.string.config_import_summary_errors, errorsCount))
                }
                if (errorsCount == 0) {
                    progress.success(summary)
                } else {
                    val firstError = result.optJSONArray("errors")?.optString(0, "") ?: ""
                    progress.failure(summary, firstError)
                }
                refreshEnvVars()
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Exportar diagnóstico — DiagnosticExportManager.kt (100% Kotlin), empaqueta en un solo
    // .tar.gz los logs reales de instalación por módulo (~/kairos_logs/install_*.log), el log
    // del wizard (wizard_debug.log) y datos básicos del dispositivo (SDK, ABI, RAM, versión de
    // la app) — pensado para bajar la fricción de reportar bugs (antes había que compartir
    // cada log manualmente uno por uno). Sin datos sensibles: nada acá se redacta porque no
    // hay tokens/credenciales en estos archivos.
    // ────────────────────────────────────────────────────────────

    private fun confirmExportDiagnostics() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_export_diagnostics_title))
            .setMessage(getString(R.string.config_export_diagnostics_message))
            .setPositiveButton(getString(R.string.config_btn_export)) { _, _ -> runExportDiagnostics() }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    private fun runExportDiagnostics() {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.config_export_diagnostics_title), getString(R.string.config_progress_packaging_diagnostics))

        Thread {
            val result = DiagnosticExportManager.exportDiagnostics(ctx)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (result.optBoolean("ok", false)) {
                    val logCount = result.optInt("log_count", 0)
                    progress.success(getString(R.string.config_diagnostics_success, result.optString("size_human", ""), logCount, result.optString("path", "")))
                } else {
                    progress.failure(getString(R.string.config_diagnostics_error), result.optString("error", getString(R.string.config_unknown)))
                }
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Rerun setup / Reinstalar — ambos fuerzan el wizard reseteando
    // ~/.kairos_ready; "Reinstalar" además borra scripts/registry (módulos y
    // descargas), "Rerun setup" solo re-corre el bootstrap base (kairos.sh).
    // ────────────────────────────────────────────────────────────

    private fun showRerunSetupDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_rerun_setup_title))
            .setMessage(getString(R.string.config_rerun_setup_message))
            .setPositiveButton(getString(R.string.config_btn_continue)) { _, _ ->
                File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".kairos_ready").delete()
                startActivity(Intent(requireContext(), WizardActivity::class.java))
                requireActivity().finish()
            }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    // Fila "🎨 Tema" con selector inline (PopupMenu, aplica al toque) — reemplaza el
    // AlertDialog.setSingleChoiceItems()+"Aplicar" anterior (humano202, 2026-08-22, pedido
    // explícito del usuario: "el boton de tema [...] deberia ser una casilla al tocar salir las
    // demas opciones y al tocar cambiar asi bonito estetico como las apk modernas"). Componente
    // compartido con StudioFragment (ver com.termux.app.ui.widget.InlineThemePicker) — "un solo
    // componente, dos instancias" (docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md).
    private fun addThemePickerRow(container: LinearLayout) {
        val ctx = requireContext()
        val themes = com.termux.app.util.KairosThemePrefs.KairosTheme.entries
        val current = com.termux.app.util.KairosThemePrefs.getSelectedTheme(ctx)
        val options = themes.map {
            com.termux.app.ui.widget.InlineThemePicker.Option(it.id, it.label)
        }
        val row = com.termux.app.ui.widget.InlineThemePicker.row(
            context = ctx,
            label = getString(R.string.config_theme_row_label),
            options = options,
            currentId = current.id,
            labelColor = ctx.kairosThemeColor(R.attr.kairosText),
            valueColor = ctx.kairosThemeColor(R.attr.kairosText2),
            dp = ::dp
        ) { chosen ->
            val newTheme = com.termux.app.util.KairosThemePrefs.KairosTheme.fromId(chosen.id)
            com.termux.app.util.KairosThemePrefs.setSelectedTheme(ctx, newTheme)
            requireActivity().recreate()
        }
        container.addView(row)
    }

    // ────────────────────────────────────────────────────────────
    // Idioma — KairosLanguagePrefs.kt (AppCompatDelegate.setApplicationLocales, no un hack
    // manual de Locale.setDefault()). Mismo componente InlineThemePicker que la fila de Tema
    // de arriba — "un solo componente, dos instancias".
    // ────────────────────────────────────────────────────────────

    private fun addLanguagePickerRow(container: LinearLayout) {
        val ctx = requireContext()
        val languages = com.termux.app.util.KairosLanguagePrefs.KairosLanguage.entries
        val current = com.termux.app.util.KairosLanguagePrefs.getSelectedLanguage()
        val labels = mapOf(
            com.termux.app.util.KairosLanguagePrefs.KairosLanguage.SISTEMA to getString(R.string.config_language_option_system),
            com.termux.app.util.KairosLanguagePrefs.KairosLanguage.ESPANOL to getString(R.string.config_language_option_es),
            com.termux.app.util.KairosLanguagePrefs.KairosLanguage.INGLES to getString(R.string.config_language_option_en)
        )
        val options = languages.map {
            com.termux.app.ui.widget.InlineThemePicker.Option(it.id, labels[it] ?: it.label)
        }
        val row = com.termux.app.ui.widget.InlineThemePicker.row(
            context = ctx,
            label = getString(R.string.config_language_row_label),
            options = options,
            currentId = current.id,
            labelColor = ctx.kairosThemeColor(R.attr.kairosText),
            valueColor = ctx.kairosThemeColor(R.attr.kairosText2),
            dp = ::dp
        ) { chosen ->
            val newLanguage = com.termux.app.util.KairosLanguagePrefs.KairosLanguage.fromId(chosen.id)
            com.termux.app.util.KairosLanguagePrefs.setSelectedLanguage(newLanguage)
        }
        container.addView(row)
    }

    // ────────────────────────────────────────────────────────────
    // Log Kairos — log interno transversal de la app (ver com.termux.app.util.KairosLogger,
    // docs/humano231.md). Distinto del log de instalación por módulo (~/kairos_logs/
    // install_<modulo>.log, ModuleController.installLogFile()), que ya existe y no se toca acá.
    // ────────────────────────────────────────────────────────────

    private fun addLogKairosRow(container: LinearLayout) {
        val ctx = requireContext()
        val levels = com.termux.app.util.KairosLogger.Level.entries
        val current = com.termux.app.util.KairosLogger.getLevel(ctx)
        val options = levels.map { com.termux.app.ui.widget.InlineThemePicker.Option(it.name, it.label) }
        val row = com.termux.app.ui.widget.InlineThemePicker.row(
            context = ctx,
            label = getString(R.string.config_log_row_label),
            options = options,
            currentId = current.name,
            labelColor = ctx.kairosThemeColor(R.attr.kairosText),
            valueColor = ctx.kairosThemeColor(R.attr.kairosText2),
            dp = ::dp
        ) { chosen ->
            val newLevel = com.termux.app.util.KairosLogger.Level.valueOf(chosen.id)
            com.termux.app.util.KairosLogger.setLevel(ctx, newLevel)
            toast(getString(R.string.config_toast_log_level, newLevel.label))
        }
        container.addView(row)
    }

    /** Últimas ~200 líneas del log en un diálogo, con opción de copiar al portapapeles —
     * alcance suficiente para diagnosticar sin necesitar un visor de archivo completo dedicado
     * (mismo criterio de esfuerzo que el resto de diálogos "de resultado" de esta pantalla). */
    private fun showKairosLogDialog() {
        val content = com.termux.app.util.KairosLogger.readLastLines(200)
        val scrollView = android.widget.ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = content
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_log_dialog_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.config_btn_copy)) { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("kairos_app.log", content))
                toast(getString(R.string.config_toast_log_copied))
            }
            .setNegativeButton(getString(R.string.config_btn_close), null)
            .show()
    }

    private fun showReinstallDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.config_reinstall_hint)
            setTextColor(context.kairosThemeColor(R.attr.kairosText))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_reinstall_title))
            .setMessage(getString(R.string.config_reinstall_message))
            .setView(input)
            .setPositiveButton(getString(R.string.config_btn_confirm)) { _, _ ->
                if (input.text.toString() == "REINSTALAR") {
                    performFullReinstall()
                } else {
                    toast(getString(R.string.config_toast_reinstall_mismatch))
                }
            }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    // ────────────────────────────────────────────────────────────
    // Desinstalar un módulo — lista los módulos instalados (registry), confirma con
    // un diálogo Sí/No (pedido explícito del usuario) y llama a
    // ModuleController.uninstallModule() (alcance real: detiene el módulo, borra sus
    // scripts propios/checkpoints/registry — ver comentario en ModuleController.kt).
    // ────────────────────────────────────────────────────────────

    private fun showUninstallModuleDialog() {
        val ctx = requireContext()
        val json = org.json.JSONArray(ctx.assets.open("modules.json").bufferedReader().use { it.readText() })
        val registry = com.termux.app.data.ModuleRegistry(ctx).load()
        val installed = mutableListOf<Pair<String, String>>() // id to name
        for (i in 0 until json.length()) {
            val m = json.getJSONObject(i)
            val id = m.optString("id")
            if (registry.get("$id.installed") == "true") {
                installed.add(id to m.optString("name", id))
            }
        }
        if (installed.isEmpty()) {
            toast(getString(R.string.config_toast_no_modules_installed))
            return
        }
        val labels = installed.map { it.second }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.config_row_uninstall_module))
            .setItems(labels) { _, index ->
                val (id, name) = installed[index]
                confirmUninstallModule(id, name)
            }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    private fun confirmUninstallModule(moduleId: String, moduleName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.config_confirm_uninstall_title, moduleName))
            .setMessage(getString(R.string.config_confirm_uninstall_message))
            .setPositiveButton(getString(R.string.config_btn_yes)) { _, _ ->
                toast(getString(R.string.config_toast_uninstalling, moduleName))
                com.termux.app.ModuleController.uninstallModule(moduleId) { ok ->
                    if (!isAdded) return@uninstallModule
                    requireActivity().runOnUiThread {
                        resultSnackbar(if (ok) getString(R.string.config_snackbar_uninstalled, moduleName) else getString(R.string.config_snackbar_uninstall_error, moduleName))
                    }
                }
            }
            .setNegativeButton(getString(R.string.config_btn_no), null)
            .show()
    }

    private fun performFullReinstall() {
        val home = TermuxConstants.TERMUX_HOME_DIR_PATH
        File(home, "scripts").deleteRecursively()
        File(home, ".android_server_registry").delete()
        File(home, ".kairos_ready").delete()
        File(home, ".kairos_extracted_version").delete()
        startActivity(Intent(requireContext(), WizardActivity::class.java))
        requireActivity().finish()
    }

    // ────────────────────────────────────────────────────────────
    // Variables de entorno — bloque propio y aislado en ~/.bashrc, nunca toca
    // el bloque que escribe kairos.sh (delimitado por "# FIN KAIROS").
    // Los cambios solo aplican a sesiones de terminal NUEVAS.
    // ────────────────────────────────────────────────────────────

    private val envBlockStart = "# ── Variables de entorno (Kairos Config) ──"
    private val envBlockEnd = "# FIN VARS KAIROS"

    private fun bashrcFile() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".bashrc")

    private fun readEnvVars(): List<Pair<String, String>> {
        val file = bashrcFile()
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        val start = lines.indexOf(envBlockStart)
        val end = lines.indexOf(envBlockEnd)
        if (start == -1 || end == -1 || end <= start) return emptyList()
        return lines.subList(start + 1, end).mapNotNull { line ->
            val m = Regex("""^export ([A-Za-z_][A-Za-z0-9_]*)=(.*)$""").find(line.trim())
            m?.let { it.groupValues[1] to it.groupValues[2] }
        }
    }

    private fun writeEnvVars(vars: List<Pair<String, String>>) {
        val file = bashrcFile()
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        val start = lines.indexOf(envBlockStart)
        val end = lines.indexOf(envBlockEnd)
        if (start != -1 && end != -1 && end >= start) {
            repeat(end - start + 1) { lines.removeAt(start) }
        }
        val block = mutableListOf(envBlockStart)
        vars.forEach { (k, v) -> block.add("export $k=$v") }
        block.add(envBlockEnd)
        if (start != -1) lines.addAll(start, block) else lines.addAll(block)
        file.writeText(lines.joinToString("\n") + "\n")
    }

    private fun refreshEnvVars() {
        envVarsContainer.removeAllViews()
        val vars = readEnvVars()
        if (vars.isEmpty()) {
            envVarsContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.config_env_vars_empty)
                textSize = 12f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            })
            return
        }
        vars.forEachIndexed { index, (key, value) ->
            if (index > 0) addDivider(envVarsContainer)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnLongClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.config_delete_var_title))
                        .setMessage(getString(R.string.config_delete_var_message, key))
                        .setPositiveButton(getString(R.string.config_btn_delete)) { _, _ ->
                            writeEnvVars(readEnvVars().filterNot { it.first == key })
                            refreshEnvVars()
                        }
                        .setNegativeButton(getString(R.string.config_btn_cancel), null)
                        .show()
                    true
                }
            }
            row.addView(TextView(requireContext()).apply {
                text = key
                textSize = 13f
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
            })
            row.addView(TextView(requireContext()).apply {
                text = value
                textSize = 12f
                gravity = Gravity.END
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
            })
            envVarsContainer.addView(row)
        }
    }

    private fun showAddEnvVarDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameInput = EditText(ctx).apply {
            hint = getString(R.string.config_hint_var_name)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val valueInput = EditText(ctx).apply {
            hint = getString(R.string.config_hint_var_value)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        container.addView(nameInput)
        container.addView(valueInput)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.config_new_var_title))
            .setView(container)
            .setPositiveButton(getString(R.string.config_btn_add)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val value = valueInput.text.toString().trim()
                if (!Regex("""^[A-Za-z_][A-Za-z0-9_]*$""").matches(name)) {
                    toast(getString(R.string.config_toast_invalid_var_name))
                    return@setPositiveButton
                }
                val vars = readEnvVars().filterNot { it.first == name } + (name to value)
                writeEnvVars(vars)
                refreshEnvVars()
                toast(getString(R.string.config_toast_var_added))
            }
            .setNegativeButton(getString(R.string.config_btn_cancel), null)
            .show()
    }

    private fun addToggleRow(container: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val toggle = SwitchCompat(ctx).apply {
            thumbTintList = ContextCompat.getColorStateList(ctx, R.color.switch_thumb_color)
            trackTintList = ContextCompat.getColorStateList(ctx, R.color.switch_track_color)
            setOnCheckedChangeListener(null)
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        row.addView(toggle)
        container.addView(row)
    }

    // iconRes opcional (pulido visual 2026-08-25, wireo de íconos vectoriales — ver
    // docs/estructura/ESTILO_VISUAL_2026-08-25.md): cuando se pasa, antepone un ImageView
    // de 18dp al label en vez de depender solo del emoji de texto — mismo patrón de
    // "ImageView + TextView en LinearLayout horizontal" que ya usa addToggleRow/addInfoRow
    // de este mismo archivo para sus propias filas de dos columnas.
    private fun addClickableRow(container: LinearLayout, label: String, iconRes: Int? = null, onClick: () -> Unit) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            // Ripple táctil consistente (pulido visual 2026-08-25, mismo patrón que
            // MonitorFragment.kt — docs/estructura/ESTILO_VISUAL_2026-08-25.md): sin esto,
            // el toque no daba ningún feedback visual hasta que el diálogo/acción abría.
            val outValue = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }
        if (iconRes != null) {
            row.addView(android.widget.ImageView(ctx).apply {
                setImageResource(iconRes)
                setColorFilter(ctx.kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(10) }
            })
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        container.addView(row)
    }

    private fun addInfoRow(container: LinearLayout, key: String, value: String) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(TextView(ctx).apply {
            text = key
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
        })
        row.addView(TextView(ctx).apply {
            text = value
            textSize = 12f
            gravity = Gravity.END
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
        })
        container.addView(row)
    }

    private fun addDivider(container: LinearLayout) {
        container.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).also {
                it.leftMargin = dp(14); it.rightMargin = dp(14)
            }
            setBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBorder))
        })
    }

    private fun dp(d: Int) = (d * resources.displayMetrics.density).toInt()
}
