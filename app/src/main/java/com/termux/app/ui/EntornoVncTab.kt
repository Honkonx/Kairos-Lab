package com.termux.app.ui

import android.content.Intent
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.util.EntornoNative
import com.termux.app.util.ProgressDialogController

/**
 * Pestaña "VNC" de EntornoFragment (Mini PC) — servidor VNC (TigerVNC) secundario/opcional,
 * alternativa a X11 embebido para conectarse desde otro dispositivo. Extraído de
 * EntornoFragment.kt en el refactor de separación por pestaña (reorganización pura, ver git
 * history de EntornoFragment.kt para el detalle histórico completo de cada decisión).
 */
internal class EntornoVncTab(private val fragment: EntornoFragment) {

    /** Pestaña "VNC" — mapeo 1:1 con las acciones que antes vivían bajo el sectionLabel "VNC — secundario/opcional". */
    fun render(parent: LinearLayout) {
        val ctx = fragment.requireContext()
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_seccion_lanzar))
        tileGrid(ctx, parent, listOf(
            TileAction(fragment.getString(R.string.entorno_tile_instalar_tigervnc), R.drawable.ic_install) { vncInstallWithProgress() },
            TileAction(fragment.getString(R.string.entorno_tile_iniciar_vnc), R.drawable.ic_start, running = { fragment.vncRunning }) { fragment.runEntornoAction("vnc-start") },
            TileAction(fragment.getString(R.string.entorno_tile_configurar_iniciar_vnc), R.drawable.ic_settings) { promptVncConfig() },
            TileAction(fragment.getString(R.string.entorno_tile_abrir_visor_vnc), R.drawable.ic_vnc, running = { fragment.vncRunning }) { openVnc() }
        ))
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_seccion_mantenimiento))
        tileGrid(ctx, parent, listOf(
            TileAction(fragment.getString(R.string.entorno_tile_detener_vnc), R.drawable.ic_stop, running = { fragment.vncRunning }) { fragment.runEntornoAction("vnc-stop") }
        ))
    }

    /**
     * Fix real (bug: "no se sabe cuando esta listo vnc, no sale una barra de
     * progreso al instalar"). Mismo patrón que EntornoNativoTab.installDesktopWithProgress()
     * (ProgressDialogController) — la instalación de TigerVNC es igual de larga (paquete real
     * vía pkg) así que merece el mismo tratamiento. Comparte `nativePkgInstallKey` con las
     * instalaciones nativas de EntornoNativoTab — las 3 usan `pkg install` sobre el MISMO
     * dpkg de Termux.
     */
    private fun vncInstallWithProgress() {
        if (!fragment.beginOp(fragment.nativePkgInstallKey)) return
        val appContext = fragment.requireContext().applicationContext
        val progress = ProgressDialogController(fragment.requireContext())
        progress.show(fragment.getString(R.string.entorno_progreso_instalando_tigervnc_titulo), fragment.getString(R.string.entorno_progreso_instalando_tigervnc_mensaje), allowBackground = true)
        Thread {
          try {
            val json = EntornoNative.vncInstall()
            val ok = json.optBoolean("ok", false)
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, "TigerVNC", if (ok) "install_done" else "install_failed", fragment.errorDetail(json)
                )
            }
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (ok) {
                    progress.success(json.optString("message", fragment.getString(R.string.entorno_tigervnc_instalado)))
                } else {
                    progress.failure(fragment.getString(R.string.entorno_error_no_pudo_instalar_tigervnc), fragment.errorDetail(json))
                }
                fragment.refreshStatus()
            }
          } finally {
            fragment.endOp(fragment.nativePkgInstallKey)
          }
        }.start()
    }

    /**
     * Diálogo "Configurar e iniciar VNC" — resolución, calidad (profundidad de color) y si
     * pedir contraseña, los únicos 3 parámetros reales que soporta
     * EntornoNative.vncStartWithConfig() (puerto/display NO son configurables: :5901/:1 están
     * atados al X11 embebido).
     */
    private fun promptVncConfig() {
        val ctx = fragment.requireContext()
        // Presets: "Nativo del dispositivo" se resuelve acá mismo (DisplayMetrics, sin costo
        // de red/proceso) y se agrega al final del array de labels — el valor real que viaja a
        // vncStartWithConfig() sigue siendo "WxH" siempre, igual que los presets fijos.
        val nativeGeometry = run {
            val dm = ctx.resources.displayMetrics
            "${dm.widthPixels}x${dm.heightPixels}"
        }
        val resolutionValues = arrayOf("1920x1080", "1600x900", "1280x720", "1024x768", nativeGeometry)
        val resolutionLabels = arrayOf(
            "1920x1080", "1600x900", "1280x720", "1024x768",
            fragment.getString(R.string.entorno_resolucion_nativa, nativeGeometry)
        )
        val depthLabels = arrayOf(fragment.getString(R.string.entorno_depth_24bit), fragment.getString(R.string.entorno_depth_16bit))
        val depthValues = intArrayOf(24, 16)

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        }
        layout.addView(TextView(ctx).apply { text = fragment.getString(R.string.entorno_label_resolucion); textSize = 12f })
        val sResolution = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, resolutionLabels)
            // Default 1280x720 (índice 2, no el 1920x1080 de índice 0) — pedido explícito del
            // usuario: "por defecto X11 y VNC debe estar en horizontal con 1280x720p"
            // (2026-09-03). Mismo criterio en el fallback de
            // EntornoNative.vncStartWithConfig() para geometry inválido/vacío.
            setSelection(resolutionValues.indexOf("1280x720").coerceAtLeast(0))
        }
        layout.addView(sResolution)
        // Orientación (faltaba explícitamente) — swap real de ancho/alto
        // sobre el geometry "WxH" elegido arriba, no un toggle cosmético: vncStartWithConfig()
        // solo entiende "WxH" (regex ^\d{2,5}x\d{2,5}$), así que "Vertical" arma la cadena
        // invertida antes de mandarla.
        layout.addView(TextView(ctx).apply {
            text = fragment.getString(R.string.entorno_label_orientacion); textSize = 12f; setPadding(0, dp(ctx, 12), 0, 0)
        })
        val orientationLabels = arrayOf(fragment.getString(R.string.entorno_orientacion_horizontal), fragment.getString(R.string.entorno_orientacion_vertical))
        val sOrientation = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, orientationLabels)
        }
        layout.addView(sOrientation)
        layout.addView(TextView(ctx).apply {
            text = fragment.getString(R.string.entorno_label_calidad); textSize = 12f; setPadding(0, dp(ctx, 12), 0, 0)
        })
        val sDepth = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, depthLabels)
        }
        layout.addView(sDepth)
        val cbPassword = CheckBox(ctx).apply {
            text = fragment.getString(R.string.entorno_checkbox_pedir_contrasena)
            isChecked = true
            setPadding(0, dp(ctx, 12), 0, 0)
        }
        layout.addView(cbPassword)
        // Campo real para escribir la contraseña (2026-08-22): antes el checkbox de
        // arriba no tenía forma de que el usuario la ingresara — vncStartWithConfig() asumía
        // que ya existía ~/.vnc/passwd de una corrida manual de vncpasswd en terminal.
        val tvPasswordLabel = TextView(ctx).apply {
            text = fragment.getString(R.string.entorno_label_contrasena); textSize = 12f; setPadding(0, dp(ctx, 12), 0, 0)
        }
        layout.addView(tvPasswordLabel)
        val etPassword = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = fragment.getString(R.string.entorno_hint_nueva_contrasena_vnc)
        }
        layout.addView(etPassword)
        cbPassword.setOnCheckedChangeListener { _, checked ->
            tvPasswordLabel.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
            etPassword.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }

        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.entorno_dialog_configurar_vnc_titulo))
            .setView(layout)
            .setPositiveButton(fragment.getString(R.string.entorno_iniciar)) { _, _ ->
                val baseGeometry = resolutionValues[sResolution.selectedItemPosition]
                // sOrientation posición 1 = "Vertical" — invierte WxH real (no cosmético);
                // posición 0 = "Horizontal" deja el geometry tal cual.
                val geometry = if (sOrientation.selectedItemPosition == 1) {
                    val parts = baseGeometry.split("x", limit = 2)
                    if (parts.size == 2) "${parts[1]}x${parts[0]}" else baseGeometry
                } else baseGeometry
                val depth = depthValues[sDepth.selectedItemPosition]
                val requirePassword = cbPassword.isChecked
                val password = etPassword.text?.toString().orEmpty()
                if (!requirePassword) {
                    AlertDialog.Builder(ctx)
                        .setTitle(fragment.getString(R.string.entorno_dialog_iniciar_sin_contrasena_titulo))
                        .setMessage(fragment.getString(R.string.entorno_mensaje_iniciar_sin_contrasena))
                        .setPositiveButton(fragment.getString(R.string.entorno_boton_iniciar_igual)) { _, _ -> startVncWithConfig(geometry, depth, false, null) }
                        .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                        .show()
                } else if (password.length < 6) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_contrasena_corta))
                } else {
                    startVncWithConfig(geometry, depth, true, password)
                }
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun startVncWithConfig(geometry: String, depth: Int, requirePassword: Boolean, password: String?) {
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_iniciando_vnc))
        Thread {
            val json = EntornoNative.vncStartWithConfig(geometry, depth, requirePassword, password)
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) {
                    json.optString("message", fragment.getString(R.string.entorno_ok))
                } else {
                    val detail = json.optString("output", "").takeLast(200)
                    val base = fragment.getString(R.string.entorno_error_prefix, json.optString("error", fragment.getString(R.string.entorno_desconocido)))
                    if (detail.isNotBlank()) "$base — $detail" else base
                }
                Snackbar.make(fragment.requireView(), msg, Snackbar.LENGTH_LONG).show()
                fragment.refreshStatus()
            }
        }.start()
    }

    /**
     * "Abrir visor VNC" — se asegura de que el servidor VNC esté instalado y corriendo ANTES
     * de abrir el visor propio (VncViewerActivity) — mismo patrón que EntornoX11Tab.launchX11()
     * (vía EntornoFragment) arranca X11Service antes de abrir el visor de X11.
     */
    private fun openVnc() {
        if (!fragment.isAdded) return
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_preparando_visor_vnc))
        Thread {
            try {
                val status = EntornoNative.status()
                if (!status.optBoolean("vnc_installed", false)) {
                    val install = EntornoNative.vncInstall()
                    if (!install.optBoolean("ok", false)) {
                        if (!fragment.isAdded) return@Thread
                        fragment.requireActivity().runOnUiThread {
                            if (fragment.isAdded) fragment.toastMsg(install.optString("error", fragment.getString(R.string.entorno_error_no_pudo_instalar_vnc)))
                        }
                        return@Thread
                    }
                }
                if (!status.optBoolean("vnc_running", false)) {
                    val start = EntornoNative.vncStart()
                    if (!start.optBoolean("ok", false)) {
                        if (!fragment.isAdded) return@Thread
                        fragment.requireActivity().runOnUiThread {
                            if (fragment.isAdded) fragment.toastMsg(start.optString("error", fragment.getString(R.string.entorno_error_no_pudo_iniciar_servidor_vnc)))
                        }
                        return@Thread
                    }
                    Thread.sleep(1500) // margen para que el servidor termine de publicar el socket
                }
                if (!fragment.isAdded) return@Thread
                fragment.requireActivity().runOnUiThread {
                    if (!fragment.isAdded) return@runOnUiThread
                    fragment.startActivity(Intent(fragment.requireContext(), VncViewerActivity::class.java))
                }
            } catch (e: Exception) {
                if (!fragment.isAdded) return@Thread
                fragment.requireActivity().runOnUiThread {
                    if (fragment.isAdded) fragment.toastMsg(e.message ?: fragment.getString(R.string.entorno_error_desconocido))
                }
            }
        }.start()
    }
}
