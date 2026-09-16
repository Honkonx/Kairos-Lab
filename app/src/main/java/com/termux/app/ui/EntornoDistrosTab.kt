package com.termux.app.ui

import android.view.Gravity
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.X11Service
import com.termux.app.util.EntornoNative
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.kairosThemeColor

/**
 * Pestaña "Distros" de EntornoFragment (Mini PC) — proot-distro (sistema Linux completo,
 * opcional) + catálogo de apps dentro de la distro. Extraído de EntornoFragment.kt en el
 * refactor de separación por pestaña (reorganización pura, ver git history de
 * EntornoFragment.kt para el detalle histórico completo de cada decisión de diseño — no
 * repetido acá para no duplicar documentación).
 */
internal class EntornoDistrosTab(private val fragment: EntornoFragment) {

    /**
     * Catálogo curado de apps GUI comunes para instalar dentro de una distro (auditoría
     * GUI/distro 2026-08-28, docs/mini-pc/INVESTIGACION_REFERENCIAS_GUI_DISTRO_2026-08-26.md).
     * Cada `pkg` es el nombre real del paquete apt (Debian/Ubuntu/Kali) — todos verificados
     * contra Debian stable/testing. Filtrado contra `modulos/stacks.sh` para no duplicar
     * (rust/go/nodejs/python3/postgresql/mariadb/php/build-essential ya se instalan ahí).
     * `docker-compose` descartado a propósito: proot no soporta namespaces/cgroups reales.
     */
    private data class DistroApp(val pkg: String, val label: String, val category: String)

    private val curatedDistroApps = listOf(
        DistroApp("firefox-esr", "Firefox", "Internet"),
        DistroApp("chromium", "Chromium", "Internet"),
        DistroApp("thunderbird", "Thunderbird", "Internet"),
        DistroApp("filezilla", "FileZilla", "Internet"),
        DistroApp("remmina", "Remmina (RDP/VNC/SSH)", "Internet"),
        DistroApp("libreoffice", "LibreOffice", "Oficina"),
        DistroApp("evince", "Evince (lector PDF)", "Oficina"),
        DistroApp("keepassxc", "KeePassXC (gestor de contraseñas)", "Oficina"),
        DistroApp("gimp", "GIMP", "Multimedia"),
        DistroApp("inkscape", "Inkscape", "Multimedia"),
        DistroApp("vlc", "VLC", "Multimedia"),
        DistroApp("blender", "Blender", "Multimedia"),
        DistroApp("obs-studio", "OBS Studio", "Multimedia"),
        DistroApp("audacity", "Audacity", "Multimedia"),
        DistroApp("geany", "Geany (editor de código)", "Desarrollo"),
        DistroApp("meld", "Meld (comparar/fusionar archivos)", "Desarrollo"),
        DistroApp("git-cola", "Git Cola (cliente Git gráfico)", "Desarrollo"),
        DistroApp("sqlitebrowser", "DB Browser for SQLite", "Desarrollo"),
        DistroApp("ripgrep", "ripgrep (búsqueda rg, sin GUI)", "Desarrollo"),
        DistroApp("fd-find", "fd (buscar archivos, sin GUI)", "Desarrollo"),
        DistroApp("fzf", "fzf (buscador difuso, sin GUI)", "Desarrollo"),
        DistroApp("neovim", "Neovim (editor, sin GUI)", "Desarrollo"),
        DistroApp("tmux", "tmux (multiplexor terminal, sin GUI)", "Desarrollo"),
        DistroApp("jq", "jq (JSON en terminal, sin GUI)", "Desarrollo"),
        DistroApp("xarchiver", "Xarchiver (archivos comprimidos)", "Utilidades"),
        DistroApp("htop", "htop (monitor de procesos, sin GUI)", "Utilidades"),
        DistroApp("tree", "tree (árbol de directorios, sin GUI)", "Utilidades"),
        DistroApp("ncdu", "ncdu (uso de disco, sin GUI)", "Utilidades")
    )

    private val curatedDistroAppCategories = curatedDistroApps.map { it.category }.distinct()

    /**
     * Pestaña "Distros" — reordenado (ronda "mejorar Mini PC", pedido explícito del usuario:
     * simetría "iniciar sesión en terminal debe estar al lado de iniciar escritorio dentro de
     * distro y así"). `tileGrid()` es un GridLayout de 3 columnas, así que el orden de la
     * lista decide qué queda en la MISMA fila:
     *   Fila 0: Instalar distro · Instalar escritorio en distro   (par "preparar")
     *   Fila 1: Login a distro (terminal) · Iniciar escritorio en distro   (par "entrar")
     * "Perfil recomendado" queda primera, sola, a propósito.
     */
    fun render(parent: LinearLayout) {
        val ctx = fragment.requireContext()
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_seccion_lanzar))
        tileGrid(ctx, parent, listOf(
            TileAction(fragment.getString(R.string.entorno_tile_perfil_recomendado), R.drawable.ic_gpu) { promptRecommendedProfile() },
            TileAction(fragment.getString(R.string.entorno_tile_instalar_distro), R.drawable.ic_install) { promptDistroInstall() },
            TileAction(fragment.getString(R.string.entorno_tile_instalar_escritorio_distro), R.drawable.ic_desktop) { promptDistroInstallDesktop() },
            TileAction(fragment.getString(R.string.entorno_tile_login_distro), R.drawable.ic_terminal) { promptDistroLogin() },
            TileAction(fragment.getString(R.string.entorno_tile_iniciar_escritorio_distro), R.drawable.ic_start) { promptDistroDesktopStart() }
        ))
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_seccion_mantenimiento))
        tileGrid(ctx, parent, listOf(
            TileAction(fragment.getString(R.string.entorno_tile_backup_distro), R.drawable.ic_backup) { promptDistroAction("distro-backup", fragment.getString(R.string.entorno_titulo_backup_distro)) },
            TileAction(fragment.getString(R.string.entorno_tile_restaurar_distro), R.drawable.ic_backup) { promptDistroRestore() },
            TileAction(fragment.getString(R.string.entorno_tile_reiniciar_distro), R.drawable.ic_install) { promptDistroReset() },
            TileAction(fragment.getString(R.string.entorno_tile_eliminar_distro), R.drawable.ic_uninstall) { promptDistroRemove() },
            TileAction(fragment.getString(R.string.entorno_tile_vincular_carpetas), R.drawable.ic_bridge) { promptDistroAction("bridge-mount", fragment.getString(R.string.entorno_titulo_vincular_carpetas)) },
            TileAction(fragment.getString(R.string.entorno_tile_instalar_app_distro), R.drawable.ic_install) { promptDistroAppInstall() },
            TileAction(fragment.getString(R.string.entorno_tile_eliminar_app_distro), R.drawable.ic_uninstall) { promptDistroAppRemove() },
            TileAction(fragment.getString(R.string.entorno_tile_cambiar_fondo), R.drawable.studio_ic_file_image) { promptChangeWallpaperDistro() }
        ))
    }

    /**
     * Grid de tiles (2 columnas) para elegir distro a instalar — patrón adoptado de
     * Linbox-WinEmu (AddContainerScreen.kt), reimplementado con GridLayout nativo.
     */
    private fun promptDistroInstall() {
        val confirmed = arrayOf("ubuntu", "debian", "alpine")
        val experimental = arrayOf("archlinux", "fedora", "void", "kali", "manjaro", "rockylinux", "opensuse-tumbleweed")
        val all = confirmed + experimental
        val ctx = fragment.requireContext()

        val grid = GridLayout(ctx).apply {
            columnCount = 2
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.entorno_dialog_instalar_distro_titulo))
            .setView(grid)
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .create()

        all.forEachIndexed { i, name ->
            val isExperimental = i >= confirmed.size
            val tile = distroTile(ctx, name, isExperimental) {
                dialog.dismiss()
                if (isExperimental) {
                    AlertDialog.Builder(ctx)
                        .setTitle(fragment.getString(R.string.entorno_dialog_distro_experimental_titulo))
                        .setMessage(fragment.getString(R.string.entorno_distro_experimental_mensaje, name))
                        .setPositiveButton(fragment.getString(R.string.entorno_instalar_boton)) { _, _ -> fragment.runEntornoAction("distro-install", name, opKey = "distro:$name") }
                        .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                        .show()
                } else {
                    fragment.runEntornoAction("distro-install", name, opKey = "distro:$name")
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = dp(ctx, 130)
                height = dp(ctx, 96)
                columnSpec = GridLayout.spec(i % 2)
                rowSpec = GridLayout.spec(i / 2)
                setMargins(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
            }
            grid.addView(tile, params)
        }
        dialog.show()
    }

    private fun distroTile(ctx: android.content.Context, name: String, experimental: Boolean, onClick: () -> Unit): android.view.View {
        val card = MaterialCardView(ctx).apply {
            radius = dp(ctx, 12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(ctx, 1)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            setOnClickListener { onClick() }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
        }
        inner.addView(
            distroIconView(ctx, name, 32),
            LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)).apply { gravity = Gravity.CENTER }
        )
        inner.addView(TextView(ctx).apply {
            text = name
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(0, dp(ctx, 6), 0, 0)
        })
        if (experimental) {
            inner.addView(TextView(ctx).apply {
                text = fragment.getString(R.string.entorno_badge_experimental)
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
            })
        } else if (name == "alpine") {
            // Badge informativo (benchmarks glmark2 reales muestran a Alpine como la distro
            // más liviana de las 3 confirmadas de Kairos).
            inner.addView(TextView(ctx).apply {
                text = fragment.getString(R.string.entorno_badge_liviana)
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            })
        }
        card.addView(inner)
        return card
    }

    private fun promptDistroRemove() {
        Thread {
            val json = EntornoNative.distroList()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_distros))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_eliminar_distro_titulo))
                    .setItems(names) { _, which ->
                        AlertDialog.Builder(fragment.requireContext())
                            .setTitle(fragment.getString(R.string.entorno_dialog_confirmar_eliminar_distro, names[which]))
                            .setMessage(fragment.getString(R.string.entorno_mensaje_eliminar_distro_irreversible))
                            .setPositiveButton(fragment.getString(R.string.entorno_eliminar)) { _, _ -> fragment.runEntornoAction("distro-remove", names[which], opKey = "distro:${names[which]}") }
                            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Restaurar distro desde un backup .tar.gz de ~/ (ronda 2026-09-09 — completa el par
     * backup/restore, antes solo existía backup). Los nombres reales que genera
     * distroBackup() son "<distro>_backup_<fecha>.tar.gz" — se usa el prefijo antes de
     * "_backup_" como nombre de distro real a pasar a distroRestore().
     */
    private fun promptDistroRestore() {
        val home = com.termux.app.util.ManagerNativeUtils.home
        val backups = java.io.File(home).listFiles { f -> f.isFile && f.name.contains("_backup_") && f.name.endsWith(".tar.gz") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (backups.isEmpty()) {
            fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_backups))
            return
        }
        val labels = backups.map { it.name }.toTypedArray()
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_restaurar_distro_titulo))
            .setItems(labels) { _, which ->
                val file = backups[which]
                val distroName = file.name.substringBefore("_backup_")
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_restaurar_distro_titulo))
                    .setMessage(fragment.getString(R.string.entorno_dialog_confirmar_restaurar, distroName))
                    .setPositiveButton(fragment.getString(R.string.entorno_restaurar)) { _, _ ->
                        fragment.runEntornoAction("distro-restore", distroName, file.absolutePath, opKey = "distro:$distroName")
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** `proot-distro reset <name>` — reinstala la distro desde cero sin eliminar+reinstalar a mano (ronda 2026-09-09). */
    private fun promptDistroReset() {
        Thread {
            val json = EntornoNative.distroList()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_distros))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_reiniciar_distro_titulo))
                    .setItems(names) { _, which ->
                        AlertDialog.Builder(fragment.requireContext())
                            .setTitle(fragment.getString(R.string.entorno_dialog_reiniciar_distro_titulo))
                            .setMessage(fragment.getString(R.string.entorno_dialog_confirmar_reiniciar, names[which]))
                            .setPositiveButton(fragment.getString(R.string.entorno_reiniciar)) { _, _ -> fragment.runEntornoAction("distro-reset", names[which], opKey = "distro:${names[which]}") }
                            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Lista distros instaladas y abre una consola real dentro del proot elegido — mismo comando que la opción [2] de submenu_terminal. */
    private fun promptDistroLogin() {
        Thread {
            val json = EntornoNative.distroList()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_login_distro_titulo))
                    .setItems(names) { _, which ->
                        fragment.launchTerminal(EntornoNative.distroLoginCommand(names[which]), fragment.getString(R.string.entorno_titulo_sesion_terminal, names[which]))
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Lista distros instaladas y corre `action` sobre la elegida — reusado por backup y bridge-mount. */
    private fun promptDistroAction(action: String, title: String) {
        Thread {
            val json = EntornoNative.distroList()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(title)
                    .setItems(names) { _, which -> fragment.runEntornoAction(action, names[which]) }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Elige una distro instalada y pide el nombre del paquete apt a instalar. */
    private fun promptDistroAppInstall() {
        Thread {
            val json = EntornoNative.distroList()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_en_que_distro_instalar))
                    .setItems(names) { _, which -> promptAppCatalog(names[which]) }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Catálogo curado agrupado por categoría + "Otro" al final para texto libre. Primer paso:
     * elegir categoría o "Otro" directo; segundo paso: elegir la app dentro de la categoría.
     */
    private fun promptAppCatalog(distro: String) {
        val labels = curatedDistroAppCategories.toTypedArray() + fragment.getString(R.string.entorno_opcion_otro_paquete)
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_instalar_en_distro, distro))
            .setItems(labels) { _, which ->
                if (which < curatedDistroAppCategories.size) {
                    promptAppCatalogCategory(distro, curatedDistroAppCategories[which])
                } else {
                    promptPackageName(distro)
                }
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun promptAppCatalogCategory(distro: String, category: String) {
        val apps = curatedDistroApps.filter { it.category == category }
        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(category)
            .setItems(labels) { _, which -> installDistroAppWithProgress(distro, apps[which].pkg) }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** Texto libre para el nombre del paquete apt — usado cuando el catálogo curado (promptAppCatalog()) no trae la app buscada. */
    private fun promptPackageName(distro: String) {
        val edit = EditText(fragment.requireContext()).apply {
            hint = fragment.getString(R.string.entorno_hint_nombre_paquete)
        }
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_instalar_en_distro, distro))
            .setView(edit)
            .setPositiveButton(fragment.getString(R.string.entorno_instalar_boton)) { _, _ ->
                val pkg = edit.text.toString().trim()
                if (pkg.isNotEmpty()) installDistroAppWithProgress(distro, pkg) else fragment.toastMsg(fragment.getString(R.string.entorno_toast_paquete_vacio))
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** apt-get install dentro de la distro puede tardar varios minutos (app GUI real) — mismo patrón ProgressDialogController que installDistroDesktopWithProgress(). */
    private fun installDistroAppWithProgress(distro: String, pkg: String) {
        val opKey = "distro:$distro"
        if (!fragment.beginOp(opKey)) return
        val appContext = fragment.requireContext().applicationContext
        val progress = ProgressDialogController(fragment.requireContext())
        // allowBackground=true — instalar un entorno gráfico dentro de una
        // distro no debe bloquear el resto de la app.
        progress.show(fragment.getString(R.string.entorno_progreso_instalando_app_titulo), fragment.getString(R.string.entorno_progreso_instalando_app_mensaje, pkg, distro), allowBackground = true)
        Thread {
          try {
            val json = EntornoNative.distroAppInstall(distro, pkg)
            val ok = json.optBoolean("ok", false)
            if (progress.isBackgrounded) {
                val detail = json.optString("output", "").ifBlank { json.optString("error", "") }
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, pkg, if (ok) "install_done" else "install_failed", detail.takeLast(300)
                )
            }
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (ok) {
                    progress.success(json.optString("message", fragment.getString(R.string.entorno_instalado)))
                } else {
                    val detail = json.optString("output", "").ifBlank { json.optString("error", fragment.getString(R.string.entorno_error_desconocido_texto)) }
                    progress.failure(fragment.getString(R.string.entorno_error_no_pudo_instalar_pkg, pkg), detail.takeLast(300))
                }
            }
          } finally {
            fragment.endOp(opKey)
          }
        }.start()
    }

    /** Lista todas las apps de distro ya instaladas (todas las distros) y confirma antes de desinstalar. */
    private fun promptDistroAppRemove() {
        Thread {
            val json = EntornoNative.distroAppsInstalled()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val apps = json.optJSONArray("apps")
                if (apps == null || apps.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_apps_distro))
                    return@runOnUiThread
                }
                val labels = Array(apps.length()) {
                    val entry = apps.optJSONObject(it)
                    "${entry?.optString("pkg")} (${entry?.optString("distro")})"
                }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_eliminar_app_titulo))
                    .setItems(labels) { _, which ->
                        val entry = apps.optJSONObject(which) ?: return@setItems
                        val distro = entry.optString("distro")
                        val pkg = entry.optString("pkg")
                        AlertDialog.Builder(fragment.requireContext())
                            .setTitle(fragment.getString(R.string.entorno_dialog_confirmar_eliminar_app, pkg, distro))
                            .setPositiveButton(fragment.getString(R.string.entorno_eliminar)) { _, _ -> fragment.runEntornoAction("distro-app-remove", distro, pkg) }
                            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Lista distros instaladas y pide el paquete apt (dbus + DE elegido) — DENTRO de la distro (EntornoNative.distroInstallDesktop()). */
    private fun promptDistroInstallDesktop() {
        Thread {
            val json = EntornoNative.distroList()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_en_que_distro_instalar_escritorio))
                    .setItems(names) { _, which -> promptDesktopChoiceForDistro(names[which]) }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Toggle "Instalación liviana" agregado antes de elegir la DE — CheckBox simple, se lee
     * UNA vez antes de abrir el picker de DE para no repetirlo por cada DE.
     */
    private fun promptDesktopChoiceForDistro(distro: String) {
        val ctx = fragment.requireContext()
        val cbLite = android.widget.CheckBox(ctx).apply {
            text = fragment.getString(R.string.entorno_checkbox_instalacion_liviana)
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 4))
        }
        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.entorno_dialog_escritorio_para_distro, distro))
            .setView(cbLite)
            .setMessage(fragment.getString(R.string.entorno_mensaje_elegir_escritorio_liviano))
            .setPositiveButton(fragment.getString(R.string.entorno_continuar)) { _, _ ->
                // KNOWN_DESKTOPS_DISTRO (no KNOWN_DESKTOPS) — esta vía "con distro" es la
                // única que puede ofrecer KDE Plasma de verdad.
                val des = EntornoNative.KNOWN_DESKTOPS_DISTRO
                val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(fragment.getString(R.string.entorno_dialog_escritorio_para_distro, distro))
                    .setItems(labels) { _, which ->
                        val de = des[which]
                        if (de == "kde") {
                            // KDE Plasma pesa ~1.5-2GB — avisar de un costo real antes de una
                            // descarga larga.
                            AlertDialog.Builder(ctx)
                                .setTitle(fragment.getString(R.string.entorno_dialog_kde_pesado_titulo))
                                .setMessage(fragment.getString(R.string.entorno_mensaje_kde_pesado))
                                .setPositiveButton(fragment.getString(R.string.entorno_instalar_boton)) { _, _ ->
                                    installDistroDesktopWithProgress(distro, de, cbLite.isChecked)
                                }
                                .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                                .show()
                        } else {
                            installDistroDesktopWithProgress(distro, de, cbLite.isChecked)
                        }
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** apt-get dentro de la distro puede tardar varios minutos — mismo patrón ProgressDialogController que EntornoNativoTab.installDesktopWithProgress() (nativo). */
    private fun installDistroDesktopWithProgress(distro: String, de: String, lite: Boolean = false) {
        val opKey = "distro:$distro"
        if (!fragment.beginOp(opKey)) return
        val appContext = fragment.requireContext().applicationContext
        val label = "${EntornoNative.desktopLabel(de)} ($distro)"
        val progress = ProgressDialogController(fragment.requireContext())
        progress.show(fragment.getString(R.string.entorno_progreso_instalando_escritorio_titulo), fragment.getString(R.string.entorno_progreso_instalando_desktop_en_distro, EntornoNative.desktopLabel(de), distro), allowBackground = true)
        Thread {
          try {
            val json = EntornoNative.distroInstallDesktop(distro, de, lite)
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
                    progress.failure(fragment.getString(R.string.entorno_error_no_pudo_instalar_escritorio_distro, distro), fragment.errorDetail(json))
                }
            }
          } finally {
            fragment.endOp(opKey)
          }
        }.start()
    }

    /**
     * "Perfil recomendado" — pide la sugerencia a EntornoNative.recommendedProfile() y la
     * muestra en un diálogo de confirmación antes de instalar nada.
     */
    private fun promptRecommendedProfile() {
        Thread {
            val profile = EntornoNative.recommendedProfile()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val distro = profile.optString("distro", "debian")
                val de = profile.optString("de", "xfce4")
                val gpuMethod = profile.optString("gpu_method", "auto")
                val gpuType = profile.optString("gpu_type", "unknown")
                val lite = profile.optBoolean("lite", true)
                val liteSuffix = if (lite) fragment.getString(R.string.entorno_perfil_recomendado_modo_liviano) else ""
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_perfil_recomendado_titulo))
                    .setMessage(fragment.getString(
                        R.string.entorno_mensaje_perfil_recomendado,
                        distro, EntornoNative.desktopLabel(de), liteSuffix, gpuMethod, gpuType
                    ))
                    .setPositiveButton(fragment.getString(R.string.entorno_instalar_boton)) { _, _ ->
                        installRecommendedProfile(distro, de, gpuMethod, lite)
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Orquesta la instalación real del perfil recomendado encadenando las 3 llamadas YA
     * existentes (distroInstall/distroInstallDesktop/setGpuMethod) en orden — no reimplementa
     * ninguna de las 3, solo las llama en secuencia. Cada paso chequea su propio "ok" real
     * antes de seguir al siguiente.
     */
    private fun installRecommendedProfile(distro: String, de: String, gpuMethod: String, lite: Boolean) {
        val opKey = "distro:$distro"
        if (!fragment.beginOp(opKey)) return
        val appContext = fragment.requireContext().applicationContext
        val progress = ProgressDialogController(fragment.requireContext())
        progress.show(
            fragment.getString(R.string.entorno_progreso_perfil_recomendado_titulo),
            fragment.getString(R.string.entorno_progreso_perfil_paso_distro, distro),
            allowBackground = true
        )
        Thread {
            val distroJson = EntornoNative.distroInstall(distro)
            if (!distroJson.optBoolean("ok", false)) {
                finishRecommendedProfile(progress, appContext, opKey, false, R.string.entorno_perfil_recomendado_error_distro, fragment.errorDetail(distroJson))
                return@Thread
            }
            progress.update(fragment.getString(R.string.entorno_progreso_perfil_paso_escritorio, EntornoNative.desktopLabel(de)))
            val desktopJson = EntornoNative.distroInstallDesktop(distro, de, lite)
            if (!desktopJson.optBoolean("ok", false)) {
                finishRecommendedProfile(progress, appContext, opKey, false, R.string.entorno_perfil_recomendado_error_escritorio, fragment.errorDetail(desktopJson))
                return@Thread
            }
            progress.update(fragment.getString(R.string.entorno_progreso_perfil_paso_gpu, gpuMethod))
            val gpuJson = EntornoNative.setGpuMethod(gpuMethod)
            if (!gpuJson.optBoolean("ok", false)) {
                finishRecommendedProfile(progress, appContext, opKey, false, R.string.entorno_perfil_recomendado_error_gpu, fragment.errorDetail(gpuJson))
                return@Thread
            }
            finishRecommendedProfile(progress, appContext, opKey, true, R.string.entorno_perfil_recomendado_ok, null)
        }.start()
    }

    private fun finishRecommendedProfile(
        progress: ProgressDialogController,
        appContext: android.content.Context,
        opKey: String,
        ok: Boolean,
        messageRes: Int,
        detail: String?
    ) {
        fragment.endOp(opKey)
        val message = fragment.getString(messageRes)
        if (progress.isBackgrounded) {
            com.termux.app.util.ModuleEventBridge.notifyDirect(
                appContext, fragment.getString(R.string.entorno_dialog_perfil_recomendado_titulo),
                if (ok) "install_done" else "install_failed", detail ?: message
            )
        }
        if (!fragment.isAdded) return
        fragment.requireActivity().runOnUiThread {
            if (ok) progress.success(message) else progress.failure(message, detail)
            fragment.refreshStatus()
        }
    }

    /** Lista distros instaladas y arranca el escritorio DENTRO de la elegida (camino "CON DISTRO") — análogo a EntornoNativoTab.promptStartDesktop() pero para proot-distro. */
    private fun promptDistroDesktopStart() {
        Thread {
            val json = EntornoNative.distroList()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_en_que_distro_iniciar_escritorio))
                    .setItems(names) { _, which ->
                        val distro = names[which]
                        // Filtrado a lo realmente instalado (ver EntornoNative.distroInstallDesktop()) —
                        // ofrecer los 3 KNOWN_DESKTOPS a secas dejaba elegir un DE que nunca se instaló
                        // en ESTA distro, el arranque fallaba con "Couldn't exec <de>-session".
                        val des = EntornoNative.installedDesktopsForDistro(distro)
                        if (des.isEmpty()) {
                            AlertDialog.Builder(fragment.requireContext())
                                .setTitle(distro)
                                .setMessage(fragment.getString(R.string.entorno_mensaje_sin_escritorio_en_distro, distro))
                                .setPositiveButton(fragment.getString(R.string.entorno_entendido), null)
                                .show()
                            return@setItems
                        }
                        val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
                        AlertDialog.Builder(fragment.requireContext())
                            .setTitle(fragment.getString(R.string.entorno_dialog_cual_escritorio_iniciar_en_distro, distro))
                            .setItems(labels) { _, deWhich -> startDistroDesktopOnEmbeddedX11(distro, des[deWhich]) }
                            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Mismo contrato que EntornoNativoTab.startDesktopOnEmbeddedX11() pero para el DE DENTRO de una distro proot (EntornoNative.startDistroDesktop()). */
    private fun startDistroDesktopOnEmbeddedX11(distro: String, de: String = "xfce4") {
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_iniciando_dentro_distro, EntornoNative.desktopLabel(de), distro))
        X11Service.start(fragment.requireContext())
        Thread {
            val json = EntornoNative.startDistroDesktop(distro, de)
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (json.optBoolean("conflict", false)) {
                    fragment.showConflictDialog(json) { startDistroDesktopOnEmbeddedX11(distro, de) }
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

    /** Distro — elige distro instalada, después el DE realmente instalado ahí. */
    private fun promptChangeWallpaperDistro() {
        Thread {
            val json = EntornoNative.distroList()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_toast_no_hay_distros))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_en_que_distro))
                    .setItems(names) { _, which -> promptChangeWallpaperDistroDesktop(names[which]) }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    private fun promptChangeWallpaperDistroDesktop(distro: String) {
        val des = EntornoNative.installedDesktopsForDistro(distro)
        if (des.isEmpty()) {
            fragment.toastMsg(fragment.getString(R.string.entorno_toast_distro_sin_escritorio, distro))
            return
        }
        if (des.size == 1) {
            fragment.pickWallpaper(EntornoFragment.WallpaperTarget.Distro(distro, des[0]))
            return
        }
        val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_cual_escritorio_en_distro, distro))
            .setItems(labels) { _, which ->
                fragment.pickWallpaper(EntornoFragment.WallpaperTarget.Distro(distro, des[which]))
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }
}
