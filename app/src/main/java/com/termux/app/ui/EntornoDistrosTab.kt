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
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_distros_seccion))
        val cards = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(cards)
        loadDistroCards(cards)
        // Lo que NO pertenece a una distro concreta: instalar una nueva o el perfil recomendado.
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_distros_seccion_nueva))
        rowList(ctx, parent, listOf(
            actionRow(ctx, RowAction(fragment.getString(R.string.entorno_tile_instalar_distro), fragment.getString(R.string.entorno_distro_row_desc_instalar), R.drawable.ic_install) { promptDistroInstall() }),
            actionRow(ctx, RowAction(fragment.getString(R.string.entorno_tile_perfil_recomendado), fragment.getString(R.string.entorno_distro_row_desc_perfil), R.drawable.ic_gpu) { promptRecommendedProfile() }),
            actionRow(ctx, RowAction(fragment.getString(R.string.entorno_tile_detener_distro_dual), fragment.getString(R.string.entorno_distro_row_desc_detener_dual), R.drawable.ic_stop) { stopDistroDesktopDualAction() })
        ))
    }

    /** Detiene el escritorio de la 2da instancia X11 (distro) -- ver EntornoNative.stopDistroDesktopDual(). */
    private fun stopDistroDesktopDualAction() {
        Thread {
            val json = EntornoNative.stopDistroDesktopDual()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", fragment.getString(R.string.entorno_ok)) else fragment.getString(R.string.entorno_error_prefix, json.optString("error", fragment.getString(R.string.entorno_desconocido)))
                Snackbar.make(fragment.requireView(), msg, Snackbar.LENGTH_LONG).show()
                fragment.refreshStatus()
            }
        }.start()
    }

    /**
     * Una tarjeta por distro instalada (rediseño aprobado 2026-10-08, mensaje 6:
     * "Distros — una tarjeta por distro"). Antes las 13 acciones eran tiles genéricos que pedían
     * elegir la distro en un diálogo cada vez; ahora la acción vive en la distro.
     */
    private fun loadDistroCards(holder: LinearLayout) {
        Thread {
            val installed = EntornoNative.distroList().optJSONArray("installed")
            val names = (0 until (installed?.length() ?: 0)).map { installed!!.optString(it) }
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (!fragment.isAdded) return@runOnUiThread
                holder.removeAllViews()
                if (names.isEmpty()) {
                    holder.addView(android.widget.TextView(holder.context).apply {
                        text = fragment.getString(R.string.entorno_distros_vacio)
                        textSize = 12f
                        setTextColor(holder.context.kairosThemeColor(R.attr.kairosText3))
                        setPadding(dp(holder.context, 4), dp(holder.context, 8), dp(holder.context, 4), dp(holder.context, 8))
                    })
                    return@runOnUiThread
                }
                val sessions = fragment.activeDistroSessions()
                names.forEach { name -> holder.addView(distroCard(holder.context, name, sessions.firstOrNull { it.second == name }?.first)) }
            }
        }.start()
    }

    private fun distroCard(ctx: android.content.Context, name: String, sessionName: String?): android.view.View {
        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = dp(ctx, 12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(ctx, 1)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 8))
            }
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 10))
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(distroIconView(ctx, name, 36), LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 12) })
        header.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(android.widget.TextView(ctx).apply {
                text = name
                textSize = 14f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
            addView(android.widget.TextView(ctx).apply {
                text = fragment.getString(if (sessionName != null) R.string.entorno_distro_estado_sesion else R.string.entorno_distro_estado_sin_sesion)
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(if (sessionName != null) R.attr.kairosGreen else R.attr.kairosText3))
            })
        })
        val menuButton = android.widget.TextView(ctx).apply {
            text = "⋮"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            contentDescription = fragment.getString(R.string.entorno_distro_menu_descripcion, name)
            minimumWidth = dp(ctx, 40)
            minimumHeight = dp(ctx, 40)
        }
        menuButton.setOnClickListener { showDistroMenu(menuButton, name) }
        header.addView(menuButton)
        body.addView(header)

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        fun addAction(label: String, color: Int, onClick: () -> Unit) {
            actions.addView(android.widget.TextView(ctx).apply {
                text = label
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(color)
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                setOnClickListener { onClick() }
            })
        }
        val blue = ctx.kairosThemeColor(R.attr.kairosBlue)
        addAction(fragment.getString(R.string.entorno_distro_btn_login), blue) { promptDistroLogin(name) }
        addAction(fragment.getString(R.string.entorno_distro_btn_escritorio), blue) { promptDistroDesktopStart(name) }
        addAction(fragment.getString(R.string.entorno_distro_btn_instalar_escritorio), blue) { promptDistroInstallDesktop(name) }
        if (sessionName != null) {
            addAction(fragment.getString(R.string.entorno_distro_btn_cerrar_sesion), ctx.kairosThemeColor(R.attr.kairosRed)) {
                (fragment.activity as? com.termux.app.TermuxActivity)?.stopSessionByName(sessionName)
                fragment.toastMsg(fragment.getString(R.string.base_module_terminal_closed, sessionName))
                fragment.refreshStatus()
            }
        }
        val scroller = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }
        body.addView(scroller)
        card.addView(body)
        return card
    }

    /** Menú ⋮ de una distro: mantenimiento y apps, todo sobre ESA distro. */
    private fun showDistroMenu(anchor: android.view.View, distro: String) {
        val menu = android.widget.PopupMenu(anchor.context, anchor)
        val entries: List<Pair<Int, () -> Unit>> = listOf(
            R.string.entorno_tile_instalar_app_distro to { promptAppCatalog(distro) },
            R.string.entorno_tile_eliminar_app_distro to { promptDistroAppRemove(distro) },
            R.string.entorno_tile_vincular_carpetas to { fragment.runEntornoAction("bridge-mount", distro) },
            R.string.entorno_tile_backup_distro to { fragment.runEntornoAction("distro-backup", distro) },
            R.string.entorno_tile_restaurar_distro to { promptDistroRestore(distro) },
            R.string.entorno_tile_cambiar_fondo to { promptChangeWallpaperDistroDesktop(distro) },
            R.string.entorno_tile_reiniciar_distro to { promptDistroReset(distro) },
            R.string.entorno_tile_eliminar_distro to { promptDistroRemove(distro) }
        )
        entries.forEachIndexed { index, (titleRes, _) -> menu.menu.add(0, index, index, fragment.getString(titleRes)) }
        menu.setOnMenuItemClickListener { item -> entries[item.itemId].second(); true }
        menu.show()
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

    private fun promptDistroRemove(distro: String) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_confirmar_eliminar_distro, distro))
            .setMessage(fragment.getString(R.string.entorno_mensaje_eliminar_distro_irreversible))
            .setPositiveButton(fragment.getString(R.string.entorno_eliminar)) { _, _ -> fragment.runEntornoAction("distro-remove", distro, opKey = "distro:$distro") }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /**
     * Restaurar distro desde un backup.tar.gz de ~/ (ronda 2026-09-09, —
     * completa el par backup/restore, antes solo existía backup). Los nombres reales que genera
     * distroBackup() son "<distro>_backup_<fecha>.tar.gz" — se usa el prefijo antes de
     * "_backup_" como nombre de distro real a pasar a distroRestore().
     */
    private fun promptDistroRestore(distro: String) {
        val home = com.termux.app.util.ManagerNativeUtils.home
        val backups = java.io.File(home).listFiles { f -> f.isFile && f.name.startsWith("${distro}_backup_") && f.name.endsWith(".tar.gz") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (backups.isEmpty()) {
            fragment.toastMsg(fragment.getString(R.string.entorno_distro_toast_sin_backups_de, distro))
            return
        }
        val labels = backups.map { it.name }.toTypedArray()
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_restaurar_distro_titulo))
            .setItems(labels) { _, which ->
                val file = backups[which]
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_restaurar_distro_titulo))
                    .setMessage(fragment.getString(R.string.entorno_dialog_confirmar_restaurar, distro))
                    .setPositiveButton(fragment.getString(R.string.entorno_restaurar)) { _, _ ->
                        fragment.runEntornoAction("distro-restore", distro, file.absolutePath, opKey = "distro:$distro")
                    }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** `proot-distro reset <name>` — reinstala la distro desde cero sin eliminar+reinstalar a mano (ronda 2026-09-09). */
    private fun promptDistroReset(distro: String) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_reiniciar_distro_titulo))
            .setMessage(fragment.getString(R.string.entorno_dialog_confirmar_reiniciar, distro))
            .setPositiveButton(fragment.getString(R.string.entorno_reiniciar)) { _, _ -> fragment.runEntornoAction("distro-reset", distro, opKey = "distro:$distro") }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** Lista distros instaladas y abre una consola real dentro del proot elegido — mismo comando que la opción [2] de submenu_terminal. */
    private fun promptDistroLogin(distro: String) {
        fragment.launchTerminal(EntornoNative.distroLoginCommand(distro), fragment.getString(R.string.entorno_titulo_sesion_terminal, distro))
    }

    /** Lista distros instaladas y corre `action` sobre la elegida — reusado por backup y bridge-mount. */

    /** Elige una distro instalada y pide el nombre del paquete apt a instalar. */

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
    private fun promptDistroAppRemove(distro: String) {
        Thread {
            val apps = EntornoNative.distroAppsInstalled().optJSONArray("apps")
            val pkgs = (0 until (apps?.length() ?: 0))
                .mapNotNull { apps?.optJSONObject(it) }
                .filter { it.optString("distro") == distro }
                .map { it.optString("pkg") }
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (pkgs.isEmpty()) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_distro_toast_sin_apps, distro))
                    return@runOnUiThread
                }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_eliminar_app_titulo))
                    .setItems(pkgs.toTypedArray()) { _, which ->
                        val pkg = pkgs[which]
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
    private fun promptDistroInstallDesktop(distro: String) {
        promptDesktopChoiceForDistro(distro)
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
    private fun promptDistroDesktopStart(distro: String) {
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
            return
        }
        if (des.size == 1) {
            startDistroDesktopOnEmbeddedX11(distro, des[0])
            return
        }
        val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_cual_escritorio_iniciar_en_distro, distro))
            .setItems(labels) { _, deWhich -> startDistroDesktopOnEmbeddedX11(distro, des[deWhich]) }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    /**
     * Mismo contrato que EntornoNativoTab.startDesktopOnEmbeddedX11() pero para el DE DENTRO de
     * una distro proot. Usa [EntornoNative.startDistroDesktopDual] ("2 instancias X11
     * conmutables", ver ese doc) -- corre en la 2da instancia X11 (X11Service2, display ":2"),
     * así nunca entra en conflicto con un escritorio nativo que ya esté corriendo en la 1ra
     * instancia ("sin que se vean al mismo tiempo", pero sin que uno tenga que cerrarse para
     * abrir el otro). SIN VERIFICAR EN DISPOSITIVO REAL todavía.
     */
    private fun startDistroDesktopOnEmbeddedX11(distro: String, de: String = "xfce4") {
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_iniciando_dentro_distro, EntornoNative.desktopLabel(de), distro))
        com.termux.app.X11Service2.start(fragment.requireContext())
        Thread {
            val json = EntornoNative.startDistroDesktopDual(distro, de)
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (json.optBoolean("conflict", false)) {
                    fragment.showConflictDialog(json) { startDistroDesktopOnEmbeddedX11(distro, de) }
                    return@runOnUiThread
                }
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", fragment.getString(R.string.entorno_ok)) else fragment.getString(R.string.entorno_error_prefix, json.optString("error", fragment.getString(R.string.entorno_desconocido)))
                val snackbar = Snackbar.make(fragment.requireView(), msg, Snackbar.LENGTH_LONG)
                if (ok) snackbar.setAction(fragment.getString(R.string.entorno_snackbar_accion_abrir_x11)) { fragment.launchX11(com.termux.app.X11Service2.DISPLAY) }
                snackbar.show()
                fragment.refreshStatus()
            }
        }.start()
    }

    /** Distro — elige distro instalada, después el DE realmente instalado ahí. */

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
