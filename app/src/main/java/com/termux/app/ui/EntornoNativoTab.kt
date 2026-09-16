package com.termux.app.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.X11Service
import com.termux.app.util.EntornoNative
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.kairosThemeColor
import java.io.File

/**
 * Pestaña "Nativo" de EntornoFragment (Mini PC) — escritorio (DE) directo sobre Termux (sin
 * distro/proot), el camino recomendado. Extraído de EntornoFragment.kt en el refactor de
 * separación por pestaña (reorganización pura, ver KDoc de EntornoFragment para el detalle
 * histórico de cada decisión de diseño — no repetido acá para no duplicar documentación).
 *
 * Recibe el Fragment padre para todo lo genuinamente compartido (refreshStatus(), estado
 * `x11Running`, guard `beginOp()`/`endOp()`, `errorDetail()`, `showConflictDialog()`,
 * `launchX11()`, el picker de wallpaper) — ver comentario de cabecera de EntornoFragment.kt
 * para el criterio completo de qué quedó ahí vs. acá.
 */
internal class EntornoNativoTab(private val fragment: EntornoFragment) {

    /**
     * Pestaña "Nativo" — escritorio (DE) directo sobre Termux (sin distro/proot), el camino
     * recomendado. Mapeo 1:1 con las acciones que antes vivían bajo el sectionLabel "NATIVO —
     * X11 + escritorio directo sobre Termux", MENOS las 3 acciones puntuales de X11 en sí
     * (Entrar en X11, Configuración de X11, Detener servidor X11) — esas viven en
     * EntornoX11Tab porque X11 es la base compartida (nativo/distro/futuro Wine+Box64, ver
     * FUTURO.md §9), no algo propio de "Nativo".
     */
    fun render(parent: LinearLayout) {
        val ctx = fragment.requireContext()
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_seccion_lanzar))
        tileGrid(ctx, parent, listOf(
            TileAction(fragment.getString(R.string.entorno_tile_xfce_nativo), R.drawable.ic_desktop) { promptXfceNative() },
            TileAction(fragment.getString(R.string.entorno_tile_iniciar_escritorio), R.drawable.ic_start) { promptStartDesktop() },
            TileAction(fragment.getString(R.string.entorno_tile_configurar_autoinicio), R.drawable.ic_settings) { promptAutostart() }
        ))
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_seccion_mantenimiento))
        tileGrid(ctx, parent, listOf(
            TileAction(fragment.getString(R.string.entorno_tile_instalar_otro_escritorio), R.drawable.ic_install) { promptInstallDesktop() },
            TileAction(fragment.getString(R.string.entorno_tile_actualizar_lanzadores), R.drawable.ic_desktop) { fragment.runEntornoAction("desktop-launchers") },
            TileAction(fragment.getString(R.string.entorno_tile_detener_escritorio), R.drawable.ic_stop) { stopDesktopSessionAction() },
            TileAction(fragment.getString(R.string.entorno_tile_cambiar_fondo), R.drawable.studio_ic_file_image) { promptChangeWallpaperNative() },
            TileAction(fragment.getString(R.string.entorno_tile_crear_acceso_directo), R.drawable.ic_shortcut) { promptCreateShortcut() }
        ))
    }

    /**
     * XFCE4 nativo (sin distro) — interfaz gráfica bionic para ejecutar cosas de la terminal.
     * Acción de una sola pulsación: si ya está instalado solo lo inicia (sobre el X11
     * embebido, abriendo el visor); si no, instala xfce4 + xfce4-terminal con barra de
     * progreso y luego lo arranca.
     */
    private fun promptXfceNative() {
        Thread {
            val json = EntornoNative.status()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed_desktops")
                val already = installed != null &&
                    (0 until installed.length()).any { installed.optString(it) == "xfce4" }
                if (already) {
                    startDesktopOnEmbeddedX11("xfce4")
                } else {
                    installXfceNativeWithProgress()
                }
            }
        }.start()
    }

    /** Instala xfce4 nativo (sin distro) con ProgressDialog y, al terminar, lo inicia sobre el X11 embebido. */
    private fun installXfceNativeWithProgress() {
        if (!fragment.beginOp(fragment.nativePkgInstallKey)) return
        val appContext = fragment.requireContext().applicationContext
        val progress = ProgressDialogController(fragment.requireContext())
        // allowBackground=true (pedido explícito del usuario: instalar un
        // entorno gráfico NATIVO no debe bloquear el resto de la app). Si el usuario manda a
        // 2do plano, el visor X11 no se auto-abre al terminar (necesita una Activity real) —
        // se avisa por notificación para que lo abra manualmente.
        progress.show(fragment.getString(R.string.entorno_progreso_instalando_escritorio_titulo), fragment.getString(R.string.entorno_progreso_instalando_xfce_nativo), allowBackground = true)
        Thread {
          try {
            val install = EntornoNative.installDesktop("xfce4")
            if (!install.optBoolean("ok", false)) {
                if (progress.isBackgrounded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, "XFCE4", "install_failed", fragment.errorDetail(install)
                    )
                }
                if (!fragment.isAdded) return@Thread
                fragment.requireActivity().runOnUiThread {
                    progress.failure(fragment.getString(R.string.entorno_error_no_pudo_instalar_xfce), fragment.errorDetail(install))
                }
                return@Thread
            }
            // Instalado — ahora sí, iniciar sobre el X11 embebido (mismo contrato que
            // startDesktopOnEmbeddedX11: X11Service.start + startDesktop + abrir visor).
            // X11Service.start() debe llamarse en el hilo de UI (es un startForegroundService),
            // por eso se hace desde runOnUiThread justo antes de EntornoNative.startDesktop(),
            // que corre en el hilo background.
            if (!fragment.isAdded) {
                if (progress.isBackgrounded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, "XFCE4", "install_done",
                        fragment.getString(R.string.entorno_notif_xfce_instalado_background)
                    )
                }
                return@Thread
            }
            fragment.requireActivity().runOnUiThread { if (fragment.isAdded) X11Service.start(fragment.requireContext()) }
            Thread.sleep(500)
            val start = EntornoNative.startDesktop("xfce4")
            if (!fragment.isAdded) {
                if (progress.isBackgrounded) {
                    val ok = start.optBoolean("ok", false)
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, "XFCE4",
                        if (ok) "install_done" else "install_failed",
                        if (ok) fragment.getString(R.string.entorno_notif_xfce_instalado_iniciado_background) else fragment.errorDetail(start)
                    )
                }
                return@Thread
            }
            fragment.requireActivity().runOnUiThread {
                if (start.optBoolean("conflict", false)) {
                    progress.dismiss()
                    fragment.showConflictDialog(start) { installXfceNativeWithProgress() }
                    return@runOnUiThread
                }
                val ok = start.optBoolean("ok", false)
                if (ok) {
                    // Fix: iniciar el escritorio NUNCA abre el visor X11
                    // automáticamente — solo se arranca el DE del lado servidor; abrir el
                    // visor queda como acción explícita del usuario vía el Snackbar.
                    progress.success(start.optString("message", fragment.getString(R.string.entorno_mensaje_xfce_iniciado)))
                    Snackbar.make(fragment.requireView(), fragment.getString(R.string.entorno_snackbar_escritorio_listo), Snackbar.LENGTH_LONG)
                        .setAction(fragment.getString(R.string.entorno_snackbar_accion_abrir_x11)) { fragment.launchX11() }
                        .show()
                } else {
                    progress.failure(fragment.getString(R.string.entorno_error_xfce_instalado_no_iniciado), fragment.errorDetail(start))
                }
                fragment.refreshStatus()
            }
          } finally {
            fragment.endOp(fragment.nativePkgInstallKey)
          }
        }.start()
    }

    /**
     * Instalar un escritorio es la acción más larga de este módulo (paquetes reales) —
     * primer uso real de `ProgressDialogController` en Kairos.
     */
    private fun installDesktopWithProgress(desktopId: String) {
        if (!fragment.beginOp(fragment.nativePkgInstallKey)) return
        val appContext = fragment.requireContext().applicationContext
        val label = EntornoNative.desktopLabel(desktopId)
        val progress = ProgressDialogController(fragment.requireContext())
        // allowBackground=true — instalar un escritorio puede tardar
        // minutos; antes bloqueaba toda la app hasta terminar.
        progress.show(fragment.getString(R.string.entorno_progreso_instalando_escritorio_titulo), fragment.getString(R.string.entorno_progreso_instalando_desktop_mensaje, label), allowBackground = true)
        Thread {
          try {
            val json = EntornoNative.installDesktop(desktopId)
            val ok = json.optBoolean("ok", false)
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, label, if (ok) "install_done" else "install_failed", fragment.errorDetail(json)
                )
            }
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (ok) {
                    progress.success(json.optString("message", fragment.getString(R.string.entorno_escritorio_instalado)))
                } else {
                    progress.failure(fragment.getString(R.string.entorno_error_no_pudo_instalar_escritorio), fragment.errorDetail(json))
                }
                fragment.refreshStatus()
            }
          } finally {
            fragment.endOp(fragment.nativePkgInstallKey)
          }
        }.start()
    }

    private fun promptInstallDesktop() {
        val des = EntornoNative.KNOWN_DESKTOPS
        val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_instalar_escritorio_titulo))
            .setItems(labels) { _, which -> installDesktopWithProgress(des[which]) }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** Si hay un solo DE instalado lo arranca directo; si hay varios, pregunta cuál. */
    private fun promptStartDesktop() {
        Thread {
            val json = EntornoNative.status()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed_desktops")
                when {
                    installed == null || installed.length() == 0 ->
                        fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_escritorio))
                    installed.length() == 1 ->
                        startDesktopOnEmbeddedX11(installed.optString(0))
                    else -> {
                        val names = Array(installed.length()) { installed.optString(it) }
                        val labels = names.map { EntornoNative.desktopLabel(it) }.toTypedArray()
                        AlertDialog.Builder(fragment.requireContext())
                            .setTitle(fragment.getString(R.string.entorno_dialog_cual_escritorio_iniciar))
                            .setItems(labels) { _, which -> startDesktopOnEmbeddedX11(names[which]) }
                            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                }
            }
        }.start()
    }

    /**
     * Arranca el servidor X11 embebido (X11Service) y, una vez arriba, el DE elegido — el
     * visor solo se abre si el usuario lo pide explícitamente (Snackbar "Abrir X11" o la tile
     * de EntornoX11Tab), nunca automático.
     */
    private fun startDesktopOnEmbeddedX11(desktopId: String) {
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_iniciando_sobre_x11, EntornoNative.desktopLabel(desktopId)))
        X11Service.start(fragment.requireContext())
        Thread {
            val json = EntornoNative.startDesktop(desktopId)
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (json.optBoolean("conflict", false)) {
                    fragment.showConflictDialog(json) { startDesktopOnEmbeddedX11(desktopId) }
                    return@runOnUiThread
                }
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", fragment.getString(R.string.entorno_ok)) else fragment.getString(R.string.entorno_error_prefix, json.optString("error", fragment.getString(R.string.entorno_desconocido)))
                val snackbar = Snackbar.make(fragment.requireView(), msg, Snackbar.LENGTH_LONG)
                if (ok) snackbar.setAction(fragment.getString(R.string.entorno_snackbar_accion_abrir_x11)) { fragment.launchX11() }
                snackbar.show()
                fragment.refreshStatus()
            }
        }.start()
    }

    /** Detiene SOLO la sesión de escritorio activa (nativa o de distro) sin apagar el servidor X11 — para cambiar de camino sin perder el servidor. */
    private fun stopDesktopSessionAction() {
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_deteniendo_escritorio))
        Thread {
            val json = EntornoNative.stopDesktopSession()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", fragment.getString(R.string.entorno_ok)) else fragment.getString(R.string.entorno_error_prefix, json.optString("error", fragment.getString(R.string.entorno_desconocido)))
                Snackbar.make(fragment.requireView(), msg, Snackbar.LENGTH_LONG).show()
                fragment.refreshStatus()
            }
        }.start()
    }

    /** Picker de checkboxes con los CLIs instalados (EntornoNative.autostartOptions()) — preselecciona los que ya están en "entorno.autostart". */
    private fun promptAutostart() {
        Thread {
            val json = EntornoNative.autostartOptions()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val ids = json.optJSONArray("ids")
                val labels = json.optJSONArray("labels")
                if (ids == null || labels == null || ids.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_cli_autoinicio))
                    return@runOnUiThread
                }
                val enabled = json.optJSONArray("enabled")
                val enabledSet = (0 until (enabled?.length() ?: 0)).map { enabled!!.optString(it) }.toSet()
                val idArr = Array(ids.length()) { ids.optString(it) }
                val labelArr = Array(labels.length()) { labels.optString(it) }
                val checked = BooleanArray(idArr.size) { idArr[it] in enabledSet }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_autoinicio_titulo))
                    .setMultiChoiceItems(labelArr, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(fragment.getString(R.string.entorno_guardar)) { _, _ ->
                        runAutostartSave(idArr.filterIndexed { i, _ -> checked[i] })
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    private fun runAutostartSave(ids: List<String>) {
        Thread {
            val json = EntornoNative.setAutostart(ids)
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                Snackbar.make(fragment.requireView(), json.optString("message", fragment.getString(R.string.entorno_ok)), Snackbar.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Nativo — usa status().installed_desktops (lo realmente instalado, mismo criterio que promptStartDesktop()), no KNOWN_DESKTOPS a secas. */
    private fun promptChangeWallpaperNative() {
        Thread {
            val json = EntornoNative.status()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed_desktops")
                val des = if (installed == null) emptyList() else (0 until installed.length()).map { installed.optString(it) }
                if (des.isEmpty()) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_instala_escritorio_nativo))
                    return@runOnUiThread
                }
                if (des.size == 1) {
                    fragment.pickWallpaper(EntornoFragment.WallpaperTarget.Native(des[0]))
                    return@runOnUiThread
                }
                val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_para_cual_escritorio))
                    .setItems(labels) { _, which ->
                        fragment.pickWallpaper(EntornoFragment.WallpaperTarget.Native(des[which]))
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    // ── Creador de accesos directos .desktop ────────────────────────────────────────────
    //
    // Auditoría 2026-09-01 de referencia/termux/RDeX-main/shortcuts/url_to_app.sh. Destino:
    // SOLO el escritorio NATIVO ($HOME/.local/share/applications + copia en $HOME/Desktop) —
    // generar accesos directos DENTRO de una distro requeriría script nuevo invocado vía
    // `proot-distro login`, lógica de entorno.sh (protegido) — queda como TODO explícito.
    //
    // "App de Android" reusa el mismo puente `am start -n <pkg>/<activity>` que ya usa el
    // resto del módulo Mini PC.

    private data class AndroidAppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
        val icon: Drawable
    )

    private fun promptCreateShortcut() {
        val ctx = fragment.requireContext()
        val options = arrayOf(
            fragment.getString(R.string.entorno_shortcut_tipo_webapp),
            fragment.getString(R.string.entorno_shortcut_tipo_app_android),
            fragment.getString(R.string.entorno_shortcut_tipo_comando)
        )
        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.entorno_dialog_crear_acceso_directo_titulo))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptShortcutWebApp()
                    1 -> promptShortcutAndroidApp()
                    else -> promptShortcutCommand()
                }
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun promptShortcutWebApp() {
        val ctx = fragment.requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        }
        val nameInput = EditText(ctx).apply { hint = fragment.getString(R.string.entorno_shortcut_hint_nombre) }
        val urlInput = EditText(ctx).apply {
            hint = fragment.getString(R.string.entorno_shortcut_hint_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(nameInput)
        layout.addView(urlInput)
        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.entorno_shortcut_tipo_webapp))
            .setView(layout)
            .setPositiveButton(fragment.getString(R.string.entorno_shortcut_crear)) { _, _ ->
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_shortcut_error_campos_vacios))
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                // am start -a VIEW delega en la app que Android tenga asociada a http(s) — el
                // navegador por default del propio sistema.
                writeShortcut(name, "am start -a android.intent.action.VIEW -d $url", "web-browser")
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun promptShortcutAndroidApp() {
        val ctx = fragment.requireContext()
        val progress = android.app.ProgressDialog(ctx).apply {
            setMessage(fragment.getString(R.string.entorno_shortcut_cargando_apps))
            setCancelable(false)
            show()
        }
        Thread {
            val pm = ctx.packageManager
            val entries = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .mapNotNull { appInfo ->
                        val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName) ?: return@mapNotNull null
                        val component = launchIntent.component ?: return@mapNotNull null
                        AndroidAppEntry(
                            label = pm.getApplicationLabel(appInfo).toString(),
                            packageName = component.packageName,
                            activityName = component.className,
                            icon = pm.getApplicationIcon(appInfo)
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                if (!fragment.isAdded) return@runOnUiThread
                if (entries.isEmpty()) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_shortcut_error_sin_apps))
                    return@runOnUiThread
                }
                showAndroidAppPicker(entries)
            }
        }.start()
    }

    private fun showAndroidAppPicker(entries: List<AndroidAppEntry>) {
        val ctx = fragment.requireContext()
        val adapter = object : ArrayAdapter<AndroidAppEntry>(ctx, 0, entries) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val entry = entries[position]
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
                }
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(entry.icon)
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)).apply { marginEnd = dp(ctx, 14) }
                })
                row.addView(TextView(ctx).apply {
                    text = entry.label
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                    textSize = 14f
                })
                return row
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.entorno_shortcut_elegir_app_titulo))
            .setAdapter(adapter) { _, which ->
                val entry = entries[which]
                writeShortcut(entry.label, "am start -n ${entry.packageName}/${entry.activityName}", "android")
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun promptShortcutCommand() {
        val ctx = fragment.requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        }
        val nameInput = EditText(ctx).apply { hint = fragment.getString(R.string.entorno_shortcut_hint_nombre) }
        val cmdInput = EditText(ctx).apply {
            hint = fragment.getString(R.string.entorno_shortcut_hint_comando)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(nameInput)
        layout.addView(cmdInput)
        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.entorno_shortcut_tipo_comando))
            .setView(layout)
            .setPositiveButton(fragment.getString(R.string.entorno_shortcut_crear)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val cmd = cmdInput.text.toString().trim()
                if (name.isEmpty() || cmd.isEmpty()) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_shortcut_error_campos_vacios))
                    return@setPositiveButton
                }
                writeShortcut(name, cmd, "utilities-terminal")
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** Mismo destino/formato que entorno.sh:webapp_launchers.sh (APPS_DIR + copia en DESKTOP_DIR) — generado desde la UI en vez de a mano por script. */
    private fun writeShortcut(name: String, exec: String, icon: String) {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "shortcut" }
        val appsDir = File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".local/share/applications")
        val desktopDir = File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, "Desktop")
        try {
            appsDir.mkdirs()
            desktopDir.mkdirs()
            val content = buildString {
                appendLine("[Desktop Entry]")
                appendLine("Type=Application")
                appendLine("Name=$name")
                appendLine("Exec=$exec")
                appendLine("Icon=$icon")
                appendLine("Categories=Network;Utility;")
                appendLine("Terminal=false")
            }
            val out = File(appsDir, "kairos-shortcut-$slug.desktop")
            out.writeText(content)
            out.setExecutable(true, false)
            val copy = File(desktopDir, out.name)
            out.copyTo(copy, overwrite = true)
            copy.setExecutable(true, false)
            fragment.toastMsg(fragment.getString(R.string.entorno_shortcut_creado_toast, name))
        } catch (e: Exception) {
            fragment.toastMsg(fragment.getString(R.string.entorno_shortcut_error_crear, e.message ?: ""))
        }
    }
}
