package com.termux.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.util.TunnelManager
import com.termux.app.util.kairosThemeColor

/**
 * Tab Túnel — mecanismo genérico y nuevo (cloudflared quick-tunnel por puerto), separado
 * de los túneles bespoke que ya existían para n8n (modulos/n8n.sh, ~/.last_cf_url,
 * N8nFragment) y Remote/SSH (RemoteManager, sesión cf-ssh-tunnel). No los reemplaza ni
 * los toca — es una superficie de control nueva y unificada para CUALQUIER módulo con
 * puerto, vía TunnelManager (ronda 2026-07-31: migrado de kairos_manager.py cmd_tunnel /
 * python3 a Kotlin puro — ver docs/humano/humano.md / INVESTIGACION_FASE4.md para el diseño
 * original del mecanismo).
 */
class TunnelFragment : Fragment() {

    // tcpOnly agregado 2026-08-19 (pedido explícito del usuario: exponer SSH desde este
    // mismo tab para que el dispositivo pueda actuar como VPS sin IP pública) — SSH es TCP
    // crudo, no HTTP, así que el quick-tunnel anónimo de Cloudflare (`--url http://...`) y
    // `ngrok http` no le sirven (ver TunnelManager.TCP_ONLY_MODULES/isTcpOnly()).
    private data class ModuleTunnel(val id: String, val name: String, val port: Int, val tcpOnly: Boolean = false)

    // `by lazy`, no inicialización directa: dos entradas usan getString() (nombres con
    // palabras reales, "SSH (Remote)"/"IA Local (llama.cpp)"), que requiere el Fragment ya
    // adjunto a un Context — un inicializador de campo normal corre en la construcción del
    // objeto, ANTES de attach, y crashearía.
    private val modules by lazy {
        listOf(
        ModuleTunnel("ollama", "Ollama", 11434),
        ModuleTunnel("n8n", "n8n", 5678),
        ModuleTunnel("openclaw", "OpenClaw", 18789),
        ModuleTunnel("opencode", "OpenCode", 3000),
        ModuleTunnel("remote", getString(R.string.tunnel_module_ssh_remote), 8022, tcpOnly = true),
        // Gap real (docs/modulos/N8N.md sección 6, auditoría 2026-08-25) — mismo id que
        // TunnelManager.KNOWN_MODULES/LlamaServerFragment.getModuleId(), puerto real
        // confirmado en registry (llamaserver.port=8085).
        ModuleTunnel("llamaserver", getString(R.string.tunnel_module_ia_local), 8085)
        )
    }

    private lateinit var container: LinearLayout
    private val rowViews = mutableMapOf<Int, RowViews>()
    private val handler = Handler(Looper.getMainLooper())
    private var warningShown = false

    private data class RowViews(
        val statusLabel: TextView,
        val urlRow: LinearLayout,
        val urlText: TextView,
        val startBtn: TextView,
        val startTokenBtn: TextView,
        val ngrokBtn: TextView,
        val ngrokTokenBtn: TextView,
        val stopBtn: TextView
    )

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)

        container.addView(TextView(ctx).apply {
            text = getString(R.string.tunnel_title)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(dp(4), 0, dp(4), dp(4))
        })
        container.addView(TextView(ctx).apply {
            text = getString(R.string.tunnel_subtitle)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        buildConfigPanel()
        buildSavedDomainsPanel()

        for (m in modules) buildModuleCard(m)

        return scroll
    }

    /**
     * Panel de configuración persistente de proveedores (Cloudflare/ngrok) — dominios y
     * tokens guardados en el registry vía TunnelManager (pedido explícito del usuario,
     * 2026-08-13, ver docs/humano/humano100.md). Arriba de las cards de módulos: cada
     * proveedor muestra si tiene token/dominio guardados y permite editarlos o borrarlos.
     */
    private fun buildConfigPanel() {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = dp(14).toFloat()
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(12) }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        inner.addView(TextView(ctx).apply {
            text = getString(R.string.tunnel_config_panel_title)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(0, 0, 0, dp(6))
        })

        inner.addView(buildProviderRow(ctx, "cloudflared", getString(R.string.tunnel_provider_cloudflare), "🔑") { refreshConfigPanel(inner) })
        inner.addView(buildProviderRow(ctx, "ngrok", getString(R.string.tunnel_provider_ngrok), "🚇") { refreshConfigPanel(inner) })

        card.addView(inner)
        container.addView(card)
        refreshConfigPanel(inner)
    }

    private fun buildProviderRow(ctx: Context, provider: String, label: String, icon: String, onChanged: () -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val stateText = TextView(ctx).apply {
            text = "…"
            textSize = 12f
            text = "$icon $label"
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val editBtn = actionChip(ctx, getString(R.string.tunnel_btn_editar)) { promptProviderConfig(provider, label) { onChanged() } }
        val clearBtn = actionChip(ctx, getString(R.string.tunnel_btn_borrar)) { clearProviderConfig(provider, label) { onChanged() } }
        row.addView(stateText)
        row.addView(editBtn)
        row.addView(clearBtn)
        row.tag = ProviderRowTag(provider, stateText)
        return row
    }

    private data class ProviderRowTag(val provider: String, val stateText: TextView)

    private fun refreshConfigPanel(inner: LinearLayout) {
        runInBackground({
            TunnelManager.getConfig("cloudflared") to TunnelManager.getConfig("ngrok")
        }) { (cf, ng) ->
            for (i in 0 until inner.childCount) {
                val row = inner.getChildAt(i) as? LinearLayout ?: continue
                val tag = row.tag as? ProviderRowTag ?: continue
                val cfg = if (tag.provider == "cloudflared") cf else ng
                tag.stateText.text = formatProviderState(tag.provider, cfg)
            }
        }
    }

    private fun formatProviderState(provider: String, cfg: TunnelManager.ProviderConfig): String {
        val icon = if (provider == "cloudflared") "🔑" else "🚇"
        val label = if (provider == "cloudflared") getString(R.string.tunnel_provider_cloudflare) else getString(R.string.tunnel_provider_ngrok)
        if (!cfg.configured) return getString(R.string.tunnel_provider_state_sin_configurar, icon, label)
        val parts = mutableListOf<String>()
        if (cfg.token.isNotBlank()) parts.add(getString(R.string.tunnel_state_token_ok))
        if (cfg.domain.isNotBlank()) parts.add(getString(R.string.tunnel_state_dominio_ok))
        return getString(R.string.tunnel_provider_state_configurado, icon, label, parts.joinToString(" · "))
    }

    private fun promptProviderConfig(provider: String, label: String, onSaved: () -> Unit) {
        val ctx = requireContext()
        val existing = TunnelManager.getConfig(provider)
        // Bug de seguridad real (auditoría 2026-08-26, ver docs/estructura/TUNEL.md): este
        // diálogo precargaba el token guardado en TEXTO PLANO en el campo de edición apenas
        // se abría — cualquiera mirando la pantalla por encima del hombro lo veía completo
        // sin tocar nada. Ahora el campo arranca vacío; el hint muestra si YA hay un token
        // guardado (sin revelarlo) — escribir uno nuevo lo reemplaza, dejarlo vacío conserva
        // el que ya estaba (a diferencia de domain, que si se deja vacío se guarda vacío,
        // porque el dominio no es secreto y "vaciarlo a propósito" es un caso de uso real).
        val tokenEdit = EditText(ctx).apply {
            hint = if (existing.token.isNotBlank()) {
                getString(R.string.tunnel_hint_token_guardado)
            } else if (provider == "cloudflared") {
                getString(R.string.tunnel_hint_token_cloudflare)
            } else {
                getString(R.string.tunnel_hint_authtoken_ngrok)
            }
        }
        val domainEdit = EditText(ctx).apply {
            hint = getString(R.string.tunnel_hint_dominio_propio_opcional)
            setText(existing.domain)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(tokenEdit)
            addView(domainEdit)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.tunnel_dialog_title_configurar, label))
            .setMessage(getString(R.string.tunnel_dialog_msg_configurar))
            .setView(layout)
            .setPositiveButton(getString(R.string.tunnel_btn_guardar)) { _, _ ->
                val typedToken = tokenEdit.text.toString().trim()
                // Vacío = "no lo toqué" (conserva el token guardado) — a diferencia de
                // clearProviderConfig(), que sí borra explícitamente. Ver comentario del
                // hint más arriba.
                val token = typedToken.ifEmpty { existing.token }
                val domain = domainEdit.text.toString().trim()
                runInBackground({ TunnelManager.saveConfig(provider, token, domain) }) { result ->
                    toast(if (result.ok) getString(R.string.tunnel_msg_config_guardada, label) else getString(R.string.tunnel_error_fmt, result.error))
                    onSaved()
                }
            }
            .setNegativeButton(getString(R.string.tunnel_btn_cancelar), null)
            .show()
    }

    private fun clearProviderConfig(provider: String, label: String, onCleared: () -> Unit) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.tunnel_dialog_title_borrar_config, label))
            .setMessage(getString(R.string.tunnel_dialog_msg_borrar_config, label))
            .setPositiveButton(getString(R.string.tunnel_btn_borrar_accion)) { _, _ ->
                runInBackground({ TunnelManager.clearConfig(provider) }) { result ->
                    toast(if (result.ok) getString(R.string.tunnel_msg_config_eliminada, label) else getString(R.string.tunnel_error_fmt, result.error))
                    onCleared()
                }
            }
            .setNegativeButton(getString(R.string.tunnel_btn_cancelar), null)
            .show()
    }

    // ── Dominios/tokens guardados (multi-dominio, agregar/borrar/asignar/verificar) ──
    // Pedido explícito del usuario (ronda 2026-08-26, ver docs/estructura/TUNEL.md): a
    // diferencia del panel de arriba (una sola config global por proveedor), acá se puede
    // guardar una LISTA de dominios/tokens por proveedor y asignar cada uno a un módulo real
    // — sin reemplazar el panel de config global (los botones rápidos "▶ Cloudflare"/
    // "🚇 ngrok" de cada card siguen usando esa config global sin cambios).
    private fun buildSavedDomainsPanel() {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = dp(14).toFloat()
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(12) }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = getString(R.string.tunnel_saved_domains_title)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        header.addView(actionChip(ctx, getString(R.string.tunnel_btn_verificar)) { runVerify() })
        inner.addView(header)
        inner.addView(TextView(ctx).apply {
            text = getString(R.string.tunnel_saved_domains_subtitle)
            textSize = 11f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(2), 0, dp(8))
        })

        val cfSection = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val ngrokSection = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(buildSavedProviderHeader(ctx, "cloudflared", getString(R.string.tunnel_provider_cloudflare)) { refreshSavedList("cloudflared", cfSection) })
        inner.addView(cfSection)
        inner.addView(buildSavedProviderHeader(ctx, "ngrok", getString(R.string.tunnel_provider_ngrok)) { refreshSavedList("ngrok", ngrokSection) })
        inner.addView(ngrokSection)

        card.addView(inner)
        container.addView(card)
        refreshSavedList("cloudflared", cfSection)
        refreshSavedList("ngrok", ngrokSection)
    }

    private fun buildSavedProviderHeader(ctx: Context, provider: String, label: String, onChanged: () -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(actionChip(ctx, getString(R.string.tunnel_btn_agregar)) { promptAddSaved(provider, label, onChanged) })
        return row
    }

    private fun refreshSavedList(provider: String, section: LinearLayout) {
        runInBackground({ TunnelManager.listSaved(provider) }) { entries ->
            if (!isAdded) return@runInBackground
            section.removeAllViews()
            if (entries.isEmpty()) {
                section.addView(TextView(requireContext()).apply {
                    text = getString(R.string.tunnel_sin_dominios_guardados)
                    textSize = 11f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                    setPadding(dp(4), 0, dp(4), dp(4))
                })
                return@runInBackground
            }
            entries.forEach { entry -> section.addView(buildSavedEntryRow(provider, entry, section)) }
        }
    }

    private fun buildSavedEntryRow(provider: String, entry: TunnelManager.SavedTunnel, section: LinearLayout): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val label = TextView(ctx).apply {
            text = getString(R.string.tunnel_saved_entry_label, entry.displayLabel) + (if (entry.token.isNotBlank()) getString(R.string.tunnel_saved_entry_token_suffix) else "")
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { promptAssign(provider, entry) }
        }
        val deleteBtn = TextView(ctx).apply {
            text = "🗑"
            textSize = 14f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { confirmDeleteSaved(provider, entry, section) }
        }
        row.addView(label)
        row.addView(deleteBtn)
        return row
    }

    private fun promptAddSaved(provider: String, label: String, onChanged: () -> Unit) {
        val ctx = requireContext()
        val domainEdit = EditText(ctx).apply { hint = getString(R.string.tunnel_hint_dominio_opcional) }
        val tokenEdit = EditText(ctx).apply { hint = getString(R.string.tunnel_hint_token_authtoken) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(domainEdit)
            addView(tokenEdit)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.tunnel_dialog_title_agregar_dominio, label))
            .setMessage(getString(R.string.tunnel_dialog_msg_agregar_dominio))
            .setView(layout)
            .setPositiveButton(getString(R.string.tunnel_btn_guardar)) { _, _ ->
                val domain = domainEdit.text.toString().trim()
                val token = tokenEdit.text.toString().trim()
                runInBackground({ TunnelManager.addSaved(provider, domain, token) }) { result ->
                    toast(if (result.ok) getString(R.string.tunnel_msg_agregado) else getString(R.string.tunnel_error_fmt, result.error))
                    onChanged()
                }
            }
            .setNegativeButton(getString(R.string.tunnel_btn_cancelar), null)
            .show()
    }

    private fun confirmDeleteSaved(provider: String, entry: TunnelManager.SavedTunnel, section: LinearLayout) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.tunnel_dialog_title_eliminar_lista))
            .setMessage(getString(R.string.tunnel_dialog_msg_eliminar_lista, entry.displayLabel))
            .setPositiveButton(getString(R.string.tunnel_btn_eliminar)) { _, _ ->
                runInBackground({ TunnelManager.deleteSaved(provider, entry.id) }) { result ->
                    toast(if (result.ok) getString(R.string.tunnel_msg_eliminado) else getString(R.string.tunnel_error_fmt, result.error))
                    refreshSavedList(provider, section)
                }
            }
            .setNegativeButton(getString(R.string.tunnel_btn_cancelar), null)
            .show()
    }

    /** Muestra los módulos reales (TunnelManager.KNOWN_MODULES) para asignar [entry]. */
    private fun promptAssign(provider: String, entry: TunnelManager.SavedTunnel) {
        val ctx = requireContext()
        val moduleIds = TunnelManager.KNOWN_MODULES.keys.toList()
        val labels = moduleIds.map { id -> modules.firstOrNull { it.id == id }?.name ?: id }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.tunnel_dialog_title_asignar_a, entry.displayLabel))
            .setItems(labels) { _, which -> confirmAndAssign(provider, entry, moduleIds[which], labels[which]) }
            .setNegativeButton(getString(R.string.tunnel_btn_cancelar), null)
            .show()
    }

    /** Si el módulo ya tenía un dominio DISTINTO asignado, pide confirmación explícita antes
     *  de reemplazarlo (pedido explícito del usuario) — si no tenía nada o es el mismo
     *  dominio, asigna directo. */
    private fun confirmAndAssign(provider: String, entry: TunnelManager.SavedTunnel, moduleId: String, moduleLabel: String) {
        runInBackground({ TunnelManager.getConfig(provider, moduleId) }) { existing ->
            val hasDifferentDomain = existing.configured &&
                (existing.domain != entry.domain || existing.token != entry.token)
            if (hasDifferentDomain) {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.tunnel_dialog_title_reemplazar_dominio, moduleLabel))
                    .setMessage(getString(R.string.tunnel_dialog_msg_reemplazar_dominio, moduleLabel, existing.domain.ifBlank { getString(R.string.tunnel_label_solo_token) }, entry.displayLabel))
                    .setPositiveButton(getString(R.string.tunnel_btn_reemplazar)) { _, _ -> doAssign(provider, entry, moduleId, moduleLabel) }
                    .setNegativeButton(getString(R.string.tunnel_btn_cancelar), null)
                    .show()
            } else {
                doAssign(provider, entry, moduleId, moduleLabel)
            }
        }
    }

    private fun doAssign(provider: String, entry: TunnelManager.SavedTunnel, moduleId: String, moduleLabel: String) {
        runInBackground({ TunnelManager.assignSaved(provider, entry.id, moduleId) }) { result ->
            toast(if (result.ok) getString(R.string.tunnel_msg_asignado_a, moduleLabel) else getString(R.string.tunnel_error_fmt, result.error))
        }
    }

    private fun runVerify() {
        toast(getString(R.string.tunnel_msg_verificando))
        runInBackground({ TunnelManager.verifyAll() }) { results ->
            if (!isAdded) return@runInBackground
            if (results.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.tunnel_dialog_title_verificar_tuneles))
                    .setMessage(getString(R.string.tunnel_dialog_msg_ningun_modulo_asignado))
                    .setPositiveButton(getString(R.string.tunnel_btn_ok), null)
                    .show()
                return@runInBackground
            }
            val text = results.joinToString("\n\n") { r ->
                val icon = if (r.ok) "✓" else "✗"
                val moduleLabel = modules.firstOrNull { it.id == r.moduleId }?.name ?: r.moduleId
                "$icon $moduleLabel (${r.provider}) — ${r.detail}"
            }
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.tunnel_dialog_title_verificar_tuneles))
                .setMessage(text)
                .setPositiveButton(getString(R.string.tunnel_btn_ok), null)
                .show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshAll()
    }

    override fun onDestroyView() {
        // Auditoría 2026-07-27: pollStatus() ya chequea isAdded, así que esto no
        // arreglaba un crash — pero sin cancelar acá, el postDelayed pendiente (hasta
        // ~14s) seguía "vivo" sin necesidad después de cerrar la pantalla.
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun buildModuleCard(m: ModuleTunnel) {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = dp(14).toFloat()
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            setContentPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(10) }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "${m.name}  ·  :${m.port}"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val statusLabel = TextView(ctx).apply {
            text = "…"
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        }
        header.addView(statusLabel)
        inner.addView(header)

        val urlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(8) }
        }
        val urlText = TextView(ctx).apply {
            text = ""
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        urlRow.addView(urlText)
        val copyBtn = TextView(ctx).apply {
            text = getString(R.string.tunnel_btn_copiar)
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tunnel_url", urlText.text))
                toast(getString(R.string.tunnel_msg_url_copiada))
            }
        }
        urlRow.addView(copyBtn)
        inner.addView(urlRow)

        // HorizontalScrollView en vez de pesos iguales (0, WRAP_CONTENT, 1f) — con 5
        // chips (se agregó "ngrok+dominio" esta ronda, ver docs/humano/humano99.md)
        // repartir el ancho en partes iguales dejaba cada chip demasiado angosto para
        // su texto en una pantalla de celular; con scroll horizontal cada chip mantiene
        // su ancho natural y el que no entra queda a un swipe de distancia.
        val btnScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(10) }
        }
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val startBtn = actionChip(ctx, getString(R.string.tunnel_btn_cloudflare)) { confirmAndStart(m, "cloudflared", null, null) }
        val startTokenBtn = actionChip(ctx, getString(R.string.tunnel_btn_con_token)) { promptCloudflareTokenAndStart(m) }
        // Para módulos TCP crudo (SSH) el label deja claro que NO es el mismo comando que
        // el resto de los módulos (ngrok tcp, no ngrok http) — ver TunnelManager.start().
        val ngrokBtn = actionChip(ctx, if (m.tcpOnly) getString(R.string.tunnel_btn_ngrok_tcp) else getString(R.string.tunnel_btn_ngrok)) { confirmAndStart(m, "ngrok", null, null) }
        val ngrokTokenBtn = actionChip(ctx, if (m.tcpOnly) getString(R.string.tunnel_btn_ngrok_tcp_auth) else getString(R.string.tunnel_btn_ngrok_dominio)) { promptNgrokTokenAndStart(m) }
        val stopBtn = actionChip(ctx, getString(R.string.tunnel_btn_detener)) { stopTunnel(m) }
        // El quick-tunnel anónimo de Cloudflare es HTTP-only (`--url http://...`) — no
        // sirve para SSH, así que ni se muestra para módulos tcpOnly (evita un botón que
        // siempre falla con el mismo error, ver TunnelManager.start()).
        if (!m.tcpOnly) btnRow.addView(startBtn)
        btnRow.addView(startTokenBtn)
        btnRow.addView(ngrokBtn)
        btnRow.addView(ngrokTokenBtn)
        btnRow.addView(stopBtn)
        btnScroll.addView(btnRow)
        inner.addView(btnScroll)

        card.addView(inner)
        container.addView(card)

        rowViews[m.port] = RowViews(statusLabel, urlRow, urlText, startBtn, startTokenBtn, ngrokBtn, ngrokTokenBtn, stopBtn)
    }

    private fun actionChip(ctx: Context, text: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    // ── Estado ───────────────────────────────────────────────────────────

    private fun refreshAll() {
        runInBackground({ TunnelManager.list() }) { tunnels ->
            for (t in tunnels) {
                val rv = rowViews[t.port] ?: continue
                applyState(rv, t.serviceUp, t.tunnelRunning, "", t.ownTunnel)
                if (t.tunnelRunning) pollStatus(t.port, t.moduleId, rv)
            }
        }
    }

    private fun applyState(rv: RowViews, serviceUp: Boolean, running: Boolean, url: String, ownTunnel: Boolean = false) {
        // Pedido explícito del usuario (ronda 2026-08-25): un túnel "propio" (n8n, y
        // cualquier otro módulo con su propio switch/control de ciclo de vida en su
        // propia pantalla) se muestra acá SOLO de lectura — ni arrancarlo ni detenerlo
        // desde Tunnel, el control real vive en la pantalla del módulo. Antes el botón
        // "Detener" quedaba habilitado a propósito solo para mostrar un mensaje
        // explicativo al tocarlo (ver TunnelManager.stop()) — ese mensaje se sigue
        // devolviendo por las dudas, pero ya no hace falta que el botón invite a
        // tocarlo.
        rv.startBtn.isEnabled = serviceUp && !running && !ownTunnel
        rv.startTokenBtn.isEnabled = serviceUp && !running && !ownTunnel
        rv.ngrokBtn.isEnabled = serviceUp && !running && !ownTunnel
        rv.ngrokTokenBtn.isEnabled = serviceUp && !running && !ownTunnel
        rv.stopBtn.isEnabled = running && !ownTunnel
        rv.startBtn.alpha = if (rv.startBtn.isEnabled) 1f else 0.4f
        rv.startTokenBtn.alpha = if (rv.startTokenBtn.isEnabled) 1f else 0.4f
        rv.ngrokBtn.alpha = if (rv.ngrokBtn.isEnabled) 1f else 0.4f
        rv.ngrokTokenBtn.alpha = if (rv.ngrokTokenBtn.isEnabled) 1f else 0.4f
        rv.stopBtn.alpha = if (rv.stopBtn.isEnabled) 1f else 0.4f
        rv.statusLabel.text = when {
            !serviceUp -> getString(R.string.tunnel_status_modulo_detenido)
            running && ownTunnel && url.isNotEmpty() -> getString(R.string.tunnel_status_tunel_propio_activo)
            running && ownTunnel -> getString(R.string.tunnel_status_tunel_propio_sin_url)
            running && url.isNotEmpty() -> getString(R.string.tunnel_status_activo)
            running -> getString(R.string.tunnel_status_iniciando)
            else -> getString(R.string.tunnel_status_sin_tunel)
        }
        rv.urlRow.visibility = if (url.isNotEmpty()) View.VISIBLE else View.GONE
        if (url.isNotEmpty()) rv.urlText.text = url
    }

    private fun pollStatus(port: Int, moduleId: String, rv: RowViews, attempt: Int = 0) {
        if (!isAdded) return
        if (attempt > 7) {
            // Bug real (auditoría 2026-08-19, ver docs/arquitectura/AUDITORIA_NUBE_CODIGO_2026-08-19.md):
            // sin este aviso, si cloudflared/ngrok tardaba más de ~14s en imprimir la URL en
            // el log, la card se quedaba mostrando "● iniciando…" para siempre — indistinguible
            // de "todavía está cargando" aunque el poll ya se había rendido.
            rv.statusLabel.text = getString(R.string.tunnel_status_activo_sin_url)
            return
        }
        runInBackground({ TunnelManager.status(port, moduleId) }) { status ->
            applyState(rv, true, status.running, status.url, status.ownTunnel)
            if (status.running && status.url.isEmpty()) {
                handler.postDelayed({ pollStatus(port, moduleId, rv, attempt + 1) }, 2000)
            }
        }
    }

    // ── Acciones ─────────────────────────────────────────────────────────

    private fun confirmAndStart(m: ModuleTunnel, provider: String, token: String?, domain: String?) {
        // Mensaje distinto para SSH (m.tcpOnly): a diferencia de ollama/n8n/openclaw/
        // opencode, sí requiere login (usuario+contraseña o clave) — pero conviene
        // recordar que el puerto queda accesible por internet a cualquiera que lo
        // encuentre, así que la fuerza de esa contraseña/clave importa más que nunca.
        val warningMsg = if (m.tcpOnly) {
            getString(R.string.tunnel_warning_tcp_only, m.name, m.port)
        } else {
            getString(R.string.tunnel_warning_generic, m.name, m.port)
        }
        if (!warningShown) {
            warningShown = true
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.tunnel_dialog_title_exponer_a_internet, m.name))
                .setMessage(warningMsg)
                .setPositiveButton(getString(R.string.tunnel_btn_continuar)) { _, _ -> startTunnel(m, provider, token, domain) }
                .setNegativeButton(getString(R.string.tunnel_btn_cancelar)) { _, _ -> warningShown = false }
                .show()
        } else {
            startTunnel(m, provider, token, domain)
        }
    }

    private fun promptCloudflareTokenAndStart(m: ModuleTunnel) {
        val edit = EditText(requireContext()).apply { hint = getString(R.string.tunnel_hint_token_cloudflare) }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.tunnel_dialog_title_tunel_con_token))
            .setMessage(getString(R.string.tunnel_dialog_msg_tunel_con_token))
            .setView(edit)
            .setPositiveButton(getString(R.string.tunnel_btn_iniciar)) { _, _ ->
                val token = edit.text.toString().trim()
                if (token.isNotEmpty()) confirmAndStart(m, "cloudflared", token, null) else toast(getString(R.string.tunnel_msg_token_vacio))
            }
            .setNegativeButton(getString(R.string.tunnel_btn_cancelar), null)
            .show()
    }

    /**
     * ngrok necesita su propio authtoken (cuenta gratuita de ngrok.com) para dejar de dar
     * URLs efímeras al azar — sin él no hay forma de usar un dominio propio en absoluto.
     * Pedido explícito del usuario, 2026-08-12 (ver docs/humano/humano99.md).
     */
    private fun promptNgrokTokenAndStart(m: ModuleTunnel) {
        val ctx = requireContext()
        val tokenEdit = EditText(ctx).apply { hint = getString(R.string.tunnel_hint_authtoken_ngrok_dashboard) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(tokenEdit)
        }
        // El campo "dominio" no aplica a `ngrok tcp` (SSH) — un dominio propio es una
        // feature de endpoints HTTP; una dirección TCP reservada usa --remote-addr, que no
        // se expone en esta UI todavía (ver TunnelManager.start(), rama tcpOnly). Se oculta
        // en vez de mostrar un campo que TunnelManager ignoraría en silencio.
        val domainEdit = if (!m.tcpOnly) {
            EditText(ctx).apply { hint = getString(R.string.tunnel_hint_dominio_reservado_opcional) }.also { layout.addView(it) }
        } else {
            null
        }
        val message = if (m.tcpOnly) {
            getString(R.string.tunnel_msg_ngrok_tcp_authtoken)
        } else {
            getString(R.string.tunnel_msg_ngrok_authtoken)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.tunnel_dialog_title_tunel_ngrok_authtoken))
            .setMessage(message)
            .setView(layout)
            .setPositiveButton(getString(R.string.tunnel_btn_iniciar)) { _, _ ->
                val token = tokenEdit.text.toString().trim()
                val domain = domainEdit?.text?.toString()?.trim().orEmpty()
                if (token.isNotEmpty()) {
                    confirmAndStart(m, "ngrok", token, domain.ifEmpty { null })
                } else {
                    toast(getString(R.string.tunnel_msg_authtoken_vacio))
                }
            }
            .setNegativeButton(getString(R.string.tunnel_btn_cancelar), null)
            .show()
    }

    private fun startTunnel(m: ModuleTunnel, provider: String, token: String?, domain: String?) {
        toast(getString(R.string.tunnel_msg_iniciando_tunel, provider, m.name))
        val nativeLibDir = requireContext().applicationInfo.nativeLibraryDir
        runInBackground({ TunnelManager.start(m.port, provider, token, domain, m.id, nativeLibDir) }) { result ->
            if (result.ok) {
                rowViews[m.port]?.let { pollStatus(m.port, m.id, it) }
            } else {
                // Para n8n, este "error" suele ser en realidad el aviso de que ya hay un
                // túnel propio activo (TunnelManager.start()) — se muestra igual por
                // toast porque el mensaje ya es autoexplicativo.
                toast(getString(R.string.tunnel_error_fmt, result.error))
                refreshAll()
            }
        }
    }

    private fun stopTunnel(m: ModuleTunnel) {
        runInBackground({ TunnelManager.stop(m.port, m.id) }) { result ->
            toast(if (result.ok) getString(R.string.tunnel_msg_tunel_detenido) else getString(R.string.tunnel_error_fmt, result.error))
            refreshAll()
        }
    }

    // ── TunnelManager bridge — corre en background (bloquea en ProcessBuilder/tmux/
    //    Thread.sleep) y vuelve al hilo principal a aplicar el resultado, mismo patrón
    //    que usaba el antiguo runManagerAction() (eliminado en 2026-08-10, sin llamadores)
    //    antes de dejar de depender de python3. ─────────

    private fun <T> runInBackground(work: () -> T, onResult: (T) -> Unit) {
        Thread {
            val result = work()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { if (isAdded) onResult(result) }
        }.start()
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(d: Int) = (d * resources.displayMetrics.density).toInt()
}
