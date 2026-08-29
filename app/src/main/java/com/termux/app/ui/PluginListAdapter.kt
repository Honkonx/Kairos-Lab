package com.termux.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.model.ModuleInfo
import com.termux.app.model.ModuleInfo.Status
import com.termux.app.util.kairosThemeColor

/**
 * Adapter de la Tienda de plugins (Fase B, 2026-08-10 — ver
 * docs/arquitectura/PLAN.md "Sistema de plugins / Tienda de módulos").
 *
 * Misma base visual que ModuleListAdapter (card + icono + nombre + subtítulo), pero con:
 * - **Orden alfabético** por nombre (lo resuelve PluginsFragment.sortCatalog) — 2026-08-16:
 *   antes los plugins con `recommended=true` iban primero por `downloads` desc; se sacó ese
 *   badge/orden a pedido del usuario.
 * - **Badges** de arquitectura (`arch`: bionic/glibc/proot/distro, color por tipo) y de
 *   categoría (`category`).
 * - **Versión visible**: instalada (registry) vs conocida del catálogo (`catalogVersion`) —
 *   cuando difieren muestra "actualización disponible" en ámbar.
 * - **Acciones** (2026-08-10, rediseño de pantallas — la Tienda NO navega al detalle/submenú):
 *   Instalar (no instalado), Desactivar/Activar (instalado — oculta/restaura en la pantalla
 *   Módulos SIN desinstalar, humano97 punto 4), Desinstalar (instalado, dentro del diálogo
 *   manage). El click de la tarjeta de un módulo instalado muestra un diálogo con
 *   Desactivar/Activar / Cambiar método / Desinstalar.
 */
class PluginListAdapter(
    private val onManage: (ModuleInfo) -> Unit,
    private val onInstall: (ModuleInfo) -> Unit,
    private val onToggleHidden: (ModuleInfo) -> Unit
) : ListAdapter<ModuleInfo, PluginListAdapter.ViewHolder>(DiffCallback()) {

    private val moduleStatuses = mutableMapOf<String, Status>()
    private val installedVersions = mutableMapOf<String, String>()
    private val hiddenIds = mutableSetOf<String>()

    // Bug real (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §4): notifyDataSetChanged() incondicional en cada llamada — mismo bug ya identificado y
    // corregido en ModuleListAdapter.updateStatuses() (ver su comentario para el detalle del
    // parpadeo visual). Portado el mismo mecanismo de diffing: solo se notifican las filas
    // cuyo status/versión/oculto realmente cambiaron.
    fun updateStatuses(statuses: Map<String, Status>, versions: Map<String, String>, hidden: Set<String> = emptySet()) {
        val changedIds = mutableSetOf<String>()
        for ((id, status) in statuses) {
            if (moduleStatuses[id] != status) changedIds += id
        }
        for ((id, version) in versions) {
            if (installedVersions[id] != version) changedIds += id
        }
        // Ids cuyo estado "oculto" cambió (entraron o salieron de hiddenIds).
        changedIds += (hiddenIds union hidden) - (hiddenIds intersect hidden)

        moduleStatuses.putAll(statuses)
        installedVersions.putAll(versions)
        hiddenIds.clear()
        hiddenIds.addAll(hidden)

        if (changedIds.isEmpty()) return
        for (id in changedIds) {
            currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }
    }

    fun getStatus(id: String): Status = moduleStatuses[id] ?: Status.NOT_INSTALLED

    fun isHidden(id: String): Boolean = id in hiddenIds

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plugin_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position], onManage, onInstall, onToggleHidden)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.plugin_card)
        private val icon = itemView.findViewById<ImageView>(R.id.plugin_icon)
        private val statusBadge = itemView.findViewById<View>(R.id.plugin_status_badge)
        private val name = itemView.findViewById<TextView>(R.id.plugin_name)
        private val badges = itemView.findViewById<LinearLayout>(R.id.plugin_badges)
        private val description = itemView.findViewById<TextView>(R.id.plugin_description)
        private val statusLine = itemView.findViewById<TextView>(R.id.plugin_status)
        private val actionBtn = itemView.findViewById<TextView>(R.id.plugin_action)

        fun bind(
            module: ModuleInfo,
            onManage: (ModuleInfo) -> Unit,
            onInstall: (ModuleInfo) -> Unit,
            onToggleHidden: (ModuleInfo) -> Unit
        ) {
            val ctx = itemView.context
            val status = moduleStatuses[module.id] ?: Status.NOT_INSTALLED
            val installedVer = installedVersions[module.id] ?: ""
            val catalogVer = module.catalogVersion
            val isHidden = module.id in hiddenIds

            name.text = module.name
            description.text = module.description.ifBlank { module.id }

            bindModuleIcon(icon, module)
            bindStatusBadge(statusBadge, status)

            badges.removeAllViews()
            // 2026-08-16: se sacó el badge "★ Recomendado" (recommended=true) a pedido del
            // usuario — la lista pasó a ser estrictamente alfabética (ver sortCatalog() en
            // PluginsFragment). module.recommended sigue existiendo en el modelo, solo dejó
            // de pintarse acá.
            if (module.arch.isNotEmpty()) badges.addView(badge(ctx, archLabel(module.arch), archColor(module.arch)))
            if (module.category.isNotEmpty()) badges.addView(badge(ctx, module.category.uppercase(), R.attr.kairosText3, ghost = true))
            if (module.downloads > 0) badges.addView(badge(ctx, "⬇ ${module.downloads}", R.attr.kairosText3, ghost = true))

            // Línea de estado + versión.
            val hasCatalogVer = catalogVer.isNotEmpty()
            val verText = buildString {
                if (installedVer.isNotEmpty()) append(ctx.getString(R.string.plugin_adapter_version_format, installedVer)) else if (hasCatalogVer) append(ctx.getString(R.string.plugin_adapter_catalog_version_format, catalogVer))
            }
            when (status) {
                Status.NOT_INSTALLED -> {
                    statusLine.text = if (hasCatalogVer) ctx.getString(R.string.plugin_adapter_status_not_installed_catalog, catalogVer) else ctx.getString(R.string.plugin_adapter_status_not_installed)
                    statusLine.setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                    actionBtn.text = ctx.getString(R.string.plugin_adapter_btn_install)
                    actionBtn.setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                }
                Status.INSTALLED_STOPPED, Status.RUNNING -> {
                    val state = if (status == Status.RUNNING) ctx.getString(R.string.plugin_adapter_status_active) else ctx.getString(R.string.plugin_adapter_status_inactive)
                    val stateColor = if (status == Status.RUNNING) R.attr.kairosGreen else R.attr.kairosText3
                    val update = if (hasCatalogVer && installedVer.isNotEmpty() && catalogVer != installedVer) ctx.getString(R.string.plugin_adapter_update_available_suffix, catalogVer) else ""
                    val hiddenTag = if (isHidden) ctx.getString(R.string.plugin_adapter_hidden_suffix) else ""
                    statusLine.text = "$state$update${if (verText.isNotEmpty()) " · $verText" else ""}$hiddenTag"
                    statusLine.setTextColor(ctx.kairosThemeColor(stateColor))
                    // 2026-08-11 (humano97 punto 4): Desactivar/Activar oculta/restaura el
                    // módulo en la pantalla Módulos SIN desinstalarlo (registry <id>.hidden).
                    // Desinstalar vive en el diálogo de manage (card click).
                    actionBtn.text = if (isHidden) ctx.getString(R.string.plugin_adapter_btn_activate) else ctx.getString(R.string.plugin_adapter_btn_deactivate)
                    actionBtn.setTextColor(ctx.kairosThemeColor(if (isHidden) R.attr.kairosGreen else R.attr.kairosAmber))
                }
                Status.INSTALLING -> {
                    statusLine.text = ctx.getString(R.string.plugin_adapter_status_installing)
                    statusLine.setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
                    actionBtn.text = "…"
                    actionBtn.setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                }
                Status.ERROR -> {
                    statusLine.text = ctx.getString(R.string.plugin_adapter_status_error)
                    statusLine.setTextColor(ctx.kairosThemeColor(R.attr.kairosRed))
                    actionBtn.text = ctx.getString(R.string.plugin_adapter_btn_install)
                    actionBtn.setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                }
            }
            card.setOnClickListener { onManage(module) }
            actionBtn.setOnClickListener {
                when (status) {
                    Status.NOT_INSTALLED, Status.ERROR -> onInstall(module)
                    Status.INSTALLED_STOPPED, Status.RUNNING -> onToggleHidden(module)
                    Status.INSTALLING -> { /* en vuelo */ }
                }
            }
        }

        private fun badge(
            ctx: android.content.Context,
            text: String,
            colorRes: Int,
            ghost: Boolean = false
        ): View {
            val color = ctx.kairosThemeColor(colorRes)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dpFloat(6)
                    val alphaBg = String.format("#22%06X", 0xFFFFFF and color)
                    setColor(if (ghost) ctx.kairosThemeColor(R.attr.kairosBg3) else Color.parseColor(alphaBg))
                }
                addView(TextView(ctx).apply {
                    this.text = text
                    textSize = 10f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(color)
                })
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.rightMargin = dp(6) }
            }
        }

        private fun archLabel(arch: String): String = when (arch) {
            "bionic" -> "bionic"
            "glibc" -> "glibc"
            "proot" -> "proot"
            "distro" -> "proot-distro"
            else -> arch
        }

        private fun archColor(arch: String): Int = when (arch) {
            "bionic" -> R.attr.kairosBlue
            "glibc" -> R.attr.kairosGreen
            "proot", "distro" -> R.attr.kairosAmber
            else -> R.attr.kairosText3
        }

        private fun dp(d: Int): Int = (d * itemView.resources.displayMetrics.density).toInt()
    }

    private class DiffCallback : DiffUtil.ItemCallback<ModuleInfo>() {
        override fun areItemsTheSame(old: ModuleInfo, new: ModuleInfo) = old.id == new.id
        override fun areContentsTheSame(old: ModuleInfo, new: ModuleInfo) = old == new
    }
}
