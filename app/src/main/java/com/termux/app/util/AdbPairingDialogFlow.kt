package com.termux.app.util

import android.content.Intent
import android.os.Build
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.AdbPairingOverlayService

/**
 * Diálogo guiado de emparejamiento ADB inalámbrico (2 pasos) + ventana flotante — extraído de
 * `ConfigFragment.kt` ( lote 27, meta 2: "la configuracion via adb si la dan al
 * princpio o pedrila despues en el modulo") para que `WizardPermissionsFragment.kt` pueda
 * ofrecer EXACTAMENTE el mismo flujo durante el wizard, no solo después en Permisos — un
 * Fragment nuevo no tiene que reimplementar nada, solo llamar [startAdbPairingFlow].
 *
 * Extensión de [Fragment] (no un objeto con parámetro `fragment:`) porque usa
 * `requireContext()`/`requireActivity()`/`isAdded`/`getString()` tal cual, igual que el resto
 * de este proyecto escribe código de UI de Fragment — ver `.claude/rules/kotlin-kairos-android-
 * patterns.md` sobre el guard de Fragment-adjunto en callbacks async.
 */
fun Fragment.startAdbPairingFlow(onPaired: () -> Unit) {
    PhantomProcessKillerHelper.ensureDeveloperOptionsThen(requireActivity()) {
        showAdbPairingStep1(onPaired)
    }
}

private fun Fragment.showAdbPairingStep1(onPaired: () -> Unit) {
    val ctx = requireContext()
    AlertDialog.Builder(ctx)
        .setTitle(getString(R.string.config_permission_adb_step1_title))
        .setMessage(getString(R.string.config_permission_adb_step1_message))
        .setNeutralButton(getString(R.string.monitor_open_dev_options)) { _, _ ->
            PhantomProcessKillerHelper.openDeveloperOptions(requireActivity())
        }
        .setNegativeButton(getString(R.string.config_btn_cancel), null)
        .setPositiveButton(getString(R.string.monitor_continue)) { _, _ -> showAdbPairingStep2(onPaired) }
        .show()
}

private fun Fragment.showAdbPairingStep2(onPaired: () -> Unit) {
    val ctx = requireContext()
    val pairingPortInput = EditText(ctx).apply {
        hint = getString(R.string.monitor_hint_pairing_port)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setText(PermissionsNotifier.lastPairingPort(ctx))
        setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
    }
    val codeInput = EditText(ctx).apply {
        hint = getString(R.string.monitor_hint_code)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
    }
    // Opcional (2026-10-31,: "salen 3 casillas cuando deberia ser dos") —
    // AdbPairingRunner.pairAndConnect() detecta este puerto solo (nmap + probar adb connect,
    // misma técnica ya probada de PhantomProcessKillerHelper.runAutoDetectFix()) cuando se deja
    // vacío — queda como escape hatch para cuando el auto-detect no encuentre nada.
    val connectPortInput = EditText(ctx).apply {
        hint = getString(R.string.monitor_hint_connect_port_optional)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setText(PermissionsNotifier.lastConnectPort(ctx))
        setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
    }
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 0))
        addView(TextView(ctx).apply {
            text = getString(R.string.config_permission_adb_step2_message)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, 0, 0, dp(ctx, 12))
        })
        addView(pairingPortInput)
        addView(codeInput)
        addView(connectPortInput)
    }
    AlertDialog.Builder(ctx)
        .setTitle(getString(R.string.config_permission_adb_step2_title))
        .setView(container)
        .setNegativeButton(getString(R.string.config_btn_cancel), null)
        .setNeutralButton(getString(R.string.config_permission_adb_floating_window)) { _, _ ->
            openAdbPairingOverlay(pairingPortInput.text.toString().trim(), connectPortInput.text.toString().trim())
        }
        .setPositiveButton(getString(R.string.config_permission_pair)) { _, _ ->
            val pairingPort = pairingPortInput.text.toString().trim()
            val code = codeInput.text.toString().trim()
            val connectPort = connectPortInput.text.toString().trim()
            if (!PermissionsValidation.isValidPairingSession(pairingPort, code, connectPort)) {
                android.widget.Toast.makeText(ctx, getString(R.string.monitor_fill_2_fields), android.widget.Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            runAdbPairing(pairingPort, code, connectPort, onPaired)
        }
        .show()
}

private fun Fragment.openAdbPairingOverlay(pairingPort: String, connectPort: String) {
    val ctx = requireContext()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
        android.widget.Toast.makeText(ctx, getString(R.string.config_permission_adb_floating_needs_overlay), android.widget.Toast.LENGTH_SHORT).show()
        OverlayPermissionHelper.requestOverlayPermission(requireActivity())
        return
    }
    val intent = Intent(ctx, AdbPairingOverlayService::class.java).apply {
        putExtra(AdbPairingOverlayService.EXTRA_PAIRING_PORT, pairingPort)
        putExtra(AdbPairingOverlayService.EXTRA_CONNECT_PORT, connectPort)
    }
    ctx.startService(intent)
}

private fun Fragment.runAdbPairing(pairingPort: String, code: String, connectPort: String, onPaired: () -> Unit) {
    val ctx = requireContext()
    val progress = ProgressDialogController(ctx)
    progress.show(getString(R.string.config_permission_adb_progress_title), getString(R.string.config_permission_adb_progress_pairing))
    AdbPairingRunner.pairAndConnect(ctx, pairingPort, code, connectPort) { result ->
        if (!isAdded) return@pairAndConnect
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            if (result.ok) {
                progress.success(getString(R.string.config_permission_adb_success))
                // "Terminar Shizuku": activar el comando de terminal + dejar el puerto vigente
                // apenas se confirma el emparejamiento, sin paso manual aparte (2026-10-31,
                //: "se debe ejecutar una vez confirmado la sincronizacion y
                // emparejamiento de adb inalambrico").
                AdbShellBridge.activateAfterPairing(ctx.applicationContext)
                onPaired()
            } else {
                progress.failure(
                    if (result.message.contains("instalar")) getString(R.string.config_permission_adb_error_install) else getString(R.string.config_permission_adb_error_connect),
                    result.detail
                )
            }
        }
    }
}

private fun dp(ctx: android.content.Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
