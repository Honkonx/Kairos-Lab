package com.termux.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.model.ModuleInfo
import com.termux.app.model.ModuleInfo.Status
import com.termux.app.util.kairosThemeColor

class ModuleListAdapter(
    private val onItemClick: (ModuleInfo) -> Unit,
    private val onToggle: (ModuleInfo, Boolean) -> Unit
) : ListAdapter<ModuleInfo, ModuleListAdapter.ViewHolder>(DiffCallback()) {

    private val moduleStatuses = mutableMapOf<String, Status>()
    private val moduleVersion = mutableMapOf<String, String>()

    // Gap real reportado en auditoría 2026-08-27 (docs/humano273.md): bind() recalcula
    // toggle.isEnabled desde `status` en CADA rebind (incluye el poll automático cada 5s de
    // ModulesFragment) — el "toggle.isEnabled = false" que el propio listener de abajo setea al
    // tocar el switch es puramente local a esa invocación, no sobrevive a un rebind. Si un
    // start/stop tarda más de 5s (común: n8n, ollama con modelos grandes, etc.), el poll
    // periódico recalcula Status desde cero (sin ningún concepto de "operación en curso" — ver
    // ModulesFragment.pollStatus()), reactivando el switch mientras la operación sigue en
    // vuelo — reabre la ventana de doble-tap que el fix de humano/humano57.md ya había cerrado
    // para el caso simple de un solo tap rápido. `pendingToggles` persiste ese estado a nivel
    // adapter (no de View, que Recycler reutiliza) para que sobreviva a cualquier rebind.
    private val pendingToggles = mutableSetOf<String>()

    fun setPending(id: String, pending: Boolean) {
        val changed = if (pending) pendingToggles.add(id) else pendingToggles.remove(id)
        if (changed) currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    fun getStatus(id: String): Status = moduleStatuses[id] ?: Status.NOT_INSTALLED

    fun updateStatus(id: String, status: Status, version: String = "") {
        moduleStatuses[id] = status
        if (version.isNotEmpty()) moduleVersion[id] = version
        currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    // Bug real (2026-08-07, ver docs/humano/humano90.md): "la app pareciera como si se
    // refrescara a cada rato" — ModulesFragment.pollStatus() llama esto cada 5s, y antes
    // llamaba notifyDataSetChanged() SIEMPRE, sin importar si algo cambió de verdad. Eso
    // fuerza un rebind completo de TODAS las filas visibles en cada poll (recrea el
    // GradientDrawable del ícono, retriggerea el ripple/elevation de MaterialCardView) — se
    // siente como un parpadeo/refresco constante aunque nada haya cambiado. Ahora solo
    // notifica las filas cuyo status/versión realmente cambiaron.
    //
    // Bug real #2 (2026-08-07, ver docs/humano/humano91.md): ese mismo fix rompió el caso de
    // "Ollama queda atascado en Iniciando…" — el toggle listener de abajo (ver bind()) muta
    // el subtitle a mano ("↓ Iniciando…") ANTES de que exista ningún Status.INSTALLING real;
    // si el arranque falla y el módulo vuelve al MISMO Status en que ya estaba
    // (INSTALLED_STOPPED), el diff de arriba no detecta ningún cambio y la fila nunca se
    // vuelve a bindear — queda congelada mostrando el texto manual para siempre. [forceIds]
    // permite que quien ya sabe que una fila puntual necesita re-bindearse (ver
    // ModulesFragment.pollStatus(forceModuleId=...)) lo fuerce aunque el Status no cambie.
    fun updateStatuses(statuses: Map<String, Status>, versions: Map<String, String> = emptyMap(), forceIds: Set<String> = emptySet()) {
        val changedIds = mutableSetOf<String>()
        changedIds += forceIds
        for ((id, status) in statuses) {
            if (moduleStatuses[id] != status) changedIds += id
        }
        for ((id, version) in versions) {
            if (moduleVersion[id] != version) changedIds += id
        }
        moduleStatuses.putAll(statuses)
        moduleVersion.putAll(versions)
        if (changedIds.isEmpty()) return
        for (id in changedIds) {
            currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_module_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position], onItemClick, onToggle)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.module_card)
        private val icon = itemView.findViewById<android.widget.ImageView>(R.id.module_icon)
        private val statusBadge = itemView.findViewById<View>(R.id.module_status_badge)
        private val name = itemView.findViewById<android.widget.TextView>(R.id.module_name)
        private val subtitle = itemView.findViewById<android.widget.TextView>(R.id.module_subtitle)
        private val toggle = itemView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.module_toggle)
        private val progressBar = itemView.findViewById<View>(R.id.progress_bar)

        fun bind(
            module: ModuleInfo,
            onItemClick: (ModuleInfo) -> Unit,
            onToggle: (ModuleInfo, Boolean) -> Unit
        ) {
            val ctx = itemView.context
            val status = moduleStatuses[module.id] ?: Status.NOT_INSTALLED
            val version = moduleVersion[module.id] ?: ""
            val isTool = !module.hasSwitch

            name.text = module.name
            bindModuleIcon(icon, module)
            bindStatusBadge(statusBadge, status)

            if (isTool) {
                toggle.visibility = View.GONE
                when (status) {
                    Status.NOT_INSTALLED -> {
                        subtitle.text = ctx.getString(R.string.module_adapter_not_installed)
                        subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                    }
                    Status.INSTALLED_STOPPED, Status.RUNNING -> {
                        val ver = if (version.isNotEmpty()) ctx.getString(R.string.module_adapter_version_suffix, version) else ""
                        subtitle.text = ctx.getString(R.string.module_adapter_installed, ver)
                        subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                    }
                    Status.INSTALLING -> {
                        subtitle.text = ctx.getString(R.string.module_adapter_installing)
                        subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
                    }
                    Status.ERROR -> {
                        subtitle.text = ctx.getString(R.string.module_adapter_error)
                        subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosRed))
                    }
                }
                // El click va en `card`, no en `itemView`: module_card es un
                // MaterialCardView clickable="true" que ocupa toda la fila, así
                // que intercepta el touch antes de que le llegue al FrameLayout
                // padre — un listener en itemView nunca se dispara (bug real,
                // presente desde el commit que creó este layout).
                card.setOnClickListener { onItemClick(module) }
                return
            }

            toggle.visibility = View.VISIBLE
            when (status) {
                Status.NOT_INSTALLED -> {
                    card.strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
                    card.strokeWidth = ctx.resources.getDimensionPixelSize(R.dimen.kairos_stroke_default)
                    subtitle.text = ctx.getString(R.string.module_adapter_not_installed)
                    subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                    toggle.alpha = 0.5f
                    toggle.isEnabled = true
                    toggle.isChecked = false
                    progressBar.visibility = View.GONE
                }
                Status.RUNNING -> {
                    card.strokeColor = Color.parseColor("#4022C55E")
                    card.strokeWidth = ctx.resources.getDimensionPixelSize(R.dimen.kairos_stroke_active)
                    val port = if (module.port.isNotEmpty()) ctx.getString(R.string.module_adapter_port_suffix, module.port) else ""
                    val ver = if (version.isNotEmpty()) ctx.getString(R.string.module_adapter_version_suffix, version) else ""
                    subtitle.text = ctx.getString(R.string.module_adapter_active, port, ver)
                    subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                    toggle.alpha = 1.0f
                    toggle.isEnabled = true
                    toggle.isChecked = true
                    progressBar.visibility = View.GONE
                }
                Status.INSTALLED_STOPPED -> {
                    card.strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
                    card.strokeWidth = ctx.resources.getDimensionPixelSize(R.dimen.kairos_stroke_default)
                    val ver = if (version.isNotEmpty()) ctx.getString(R.string.module_adapter_version_suffix, version) else ""
                    subtitle.text = ctx.getString(R.string.module_adapter_inactive, ver)
                    subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                    toggle.alpha = 1.0f
                    toggle.isEnabled = true
                    toggle.isChecked = false
                    progressBar.visibility = View.GONE
                }
                Status.INSTALLING -> {
                    card.strokeColor = ctx.kairosThemeColor(R.attr.kairosAmber)
                    card.strokeWidth = ctx.resources.getDimensionPixelSize(R.dimen.kairos_stroke_active)
                    subtitle.text = ctx.getString(R.string.module_adapter_installing)
                    subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
                    toggle.alpha = 0.5f
                    toggle.isEnabled = false
                    toggle.isChecked = false
                    progressBar.visibility = View.VISIBLE
                }
                Status.ERROR -> {
                    card.strokeColor = ctx.kairosThemeColor(R.attr.kairosRed)
                    card.strokeWidth = ctx.resources.getDimensionPixelSize(R.dimen.kairos_stroke_active)
                    subtitle.text = ctx.getString(R.string.module_adapter_error_reinstall)
                    subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosRed))
                    toggle.alpha = 0.5f
                    toggle.isEnabled = true
                    toggle.isChecked = false
                    progressBar.visibility = View.GONE
                }
            }

            // Override de "pendiente" — ver comentario de pendingToggles arriba. Se aplica
            // DESPUÉS del switch de arriba (que ya cubrió alpha/progressBar/isChecked según el
            // status real) para no duplicar esa lógica, solo fuerza isEnabled=false mientras
            // haya un start/stop en vuelo para este módulo puntual.
            if (module.id in pendingToggles) {
                toggle.isEnabled = false
                progressBar.visibility = View.VISIBLE
            }

            card.setOnClickListener { onItemClick(module) }

            toggle.setOnCheckedChangeListener(null)
            toggle.setOnCheckedChangeListener { _, isChecked ->
                when (status) {
                    Status.NOT_INSTALLED -> {
                        toggle.isChecked = false
                        onItemClick(module)
                    }
                    Status.INSTALLED_STOPPED -> {
                        if (isChecked) {
                            // Antes esto volvía el switch a "false" sin ningún indicio visual
                            // de que algo estaba pasando — módulos lentos en arrancar (n8n en
                            // proot, puede tardar bien más de lo que dura el Snackbar) daban la
                            // sensación de "el switch no hace nada" (bug real reportado, ver
                            // docs/humano/humano57.md). Ahora deshabilita el switch y muestra el
                            // spinner mientras onToggle() está en vuelo — vuelve al estado real
                            // (encendido/apagado/error) solo cuando pollStatus() re-bindea esta
                            // fila con el resultado definitivo.
                            toggle.isChecked = false
                            toggle.isEnabled = false
                            progressBar.visibility = View.VISIBLE
                            // Directo al set, no vía setPending() — ya estamos a mitad de un
                            // bind() de esta misma fila, no hace falta el notifyItemChanged()
                            // que setPending() dispara para callers externos.
                            pendingToggles.add(module.id)
                            // n8n (sobre todo en udocker) puede demorar ~40s en quedar realmente
                            // listo tras el arranque (pull/extract de la imagen + boot de n8n) —
                            // sin este texto el spinner solo no aclaraba si el switch "no hacía
                            // nada" o si de verdad seguía trabajando (ver docs/humano/humano88.md).
                            subtitle.text = if (module.id == "n8n") ctx.getString(R.string.module_adapter_starting_n8n) else ctx.getString(R.string.module_adapter_starting)
                            subtitle.setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
                            onToggle(module, true)
                        }
                    }
                    Status.RUNNING -> {
                        if (!isChecked) {
                            toggle.isChecked = true
                            toggle.isEnabled = false
                            progressBar.visibility = View.VISIBLE
                            pendingToggles.add(module.id)
                            onToggle(module, false)
                        }
                    }
                    Status.INSTALLING -> toggle.isChecked = false
                    Status.ERROR -> toggle.isChecked = false
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ModuleInfo>() {
        override fun areItemsTheSame(old: ModuleInfo, new: ModuleInfo) = old.id == new.id
        override fun areContentsTheSame(old: ModuleInfo, new: ModuleInfo) = old == new
    }
}
