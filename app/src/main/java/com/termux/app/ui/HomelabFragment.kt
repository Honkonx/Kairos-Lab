package com.termux.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
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
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.ModuleController
import com.termux.app.util.HomelabManager
import com.termux.app.util.HomelabManager.HomelabService
import com.termux.app.util.HomelabManager.ServiceType
import com.termux.app.util.kairosThemeColor
import org.json.JSONArray

/**
 * Pantalla "Homelab" — pedido explícito del usuario: dar a Kairos "la facilidad de uso de un
 * panel real (dashboard)", integrando accesos rápidos a lo que Kairos ya expone (Remote/SSH,
 * módulos con servicio HTTP corriendo) junto con servicios self-hosted EXTERNOS que el usuario
 * ya tiene corriendo en su red local (Docker/Portainer de un NAS, Pi-hole de una Raspberry Pi,
 * Proxmox de un servidor, etc.) — distinto de los módulos que Kairos gestiona dentro del propio
 * teléfono. Ver `docs/modulos/HOMELAB.md` para el diseño completo y
 * `docs/arquitectura/FUTURO.md` sección "Homelab multi-superficie" para lo diferido (web
 * externa, APK externa, SSH como acceso remoto al panel — explícitamente NO implementado acá).
 *
 * Alcance de esta primera versión: 100% Kotlin nativo, sin bash — no es instalación de un
 * binario, es una pantalla de gestión. Persistencia vía [HomelabManager] (mismo patrón de
 * registry que `RemoteManager`), UI 100% programática (mismo criterio que
 * `MoreBottomSheetFragment`/partes de `RemoteFragment`) en vez de un layout XML propio, porque
 * el contenido (lista variable de cards de servicio + diálogos) no gana nada de un XML estático.
 *
 * No extiende `BaseModuleFragment` a propósito: Homelab no es un módulo con
 * instalar/iniciar/detener/lifecycle de proceso — es una pantalla de gestión/dashboard sin
 * script propio, mismo criterio que ya distingue `MonitorFragment`/`NubeFragment` (plain
 * `Fragment`) de los ~50 fragments de módulo real.
 */
class HomelabFragment : Fragment() {

    private lateinit var root: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var deviceContainer: LinearLayout
    private lateinit var servicesContainer: LinearLayout
    private lateinit var servicesEmptyState: TextView

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        val ctx = requireContext()
        root = ScrollView(ctx).apply {
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
        // Anti-tapjacking (mismo criterio que NubeFragment/RemoteFragment): esta pantalla puede
        // guardar tokens de servicios agregados por el usuario.
        view.filterTouchesWhenObscured = true

        container.addView(sectionTitle(getString(R.string.homelab_title)))
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.homelab_subtitle)
            textSize = 12f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        buildDeviceSection()
        buildServicesSection()

        refreshDevice()
        refreshServices()
    }

    // ── "Este dispositivo" — Remote/SSH + módulos con servicio HTTP corriendo ahora ─────────

    private fun buildDeviceSection() {
        container.addView(sectionLabel(getString(R.string.homelab_section_este_dispositivo)))
        val card = MaterialCardView(requireContext()).apply {
            setCardBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBg2))
            radius = resources.getDimension(R.dimen.kairos_stroke_default)
            strokeColor = requireContext().kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(16) }
        }
        deviceContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        card.addView(deviceContainer)
        container.addView(card)
    }

    private fun refreshDevice() {
        if (!isAdded) return
        Thread {
            val remoteRunning = try { ModuleController.isRunning("remote") } catch (_: Exception) { false }
            val candidates = loadHttpModuleCandidates()
            val runningModules = candidates.filter {
                try { ModuleController.isRunning(it.id) } catch (_: Exception) { false }
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                deviceContainer.removeAllViews()
                deviceContainer.addView(deviceRow(
                    name = getString(R.string.homelab_device_remote),
                    statusText = if (remoteRunning) getString(R.string.remote_status_corriendo_8022) else getString(R.string.remote_status_detenido),
                    running = remoteRunning,
                    onOpen = { navigateTo(RemoteFragment()) }
                ))
                if (runningModules.isEmpty()) {
                    deviceContainer.addView(TextView(requireContext()).apply {
                        text = getString(R.string.homelab_device_sin_modulos_http)
                        textSize = 12f
                        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                        setPadding(dp(14), dp(2), dp(14), dp(12))
                    })
                } else {
                    runningModules.forEach { m ->
                        deviceContainer.addView(deviceRow(
                            name = "${m.name} (:${m.port})",
                            statusText = getString(R.string.homelab_status_activo),
                            running = true,
                            onOpen = { navigateTo(ModuleWebViewFragment.newInstance("http://127.0.0.1:${m.port}", m.name)) }
                        ))
                    }
                }
            }
        }.start()
    }

    private data class HttpModuleCandidate(val id: String, val name: String, val port: String)

    /** Lee modules.json y devuelve todo módulo con un `port` real declarado — sin lista
     *  hardcodeada de ids, así cualquier módulo nuevo con puerto aparece acá solo (mismo
     *  criterio "adaptable/escalable" de CLAUDE.md, ver readModulePort() en MonitorFragment.kt
     *  para el mismo patrón de lectura puntual de modules.json). */
    private fun loadHttpModuleCandidates(): List<HttpModuleCandidate> {
        return try {
            val json = requireContext().assets.open("modules.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val port = o.optString("port")
                val id = o.optString("id")
                if (port.isBlank() || id.isBlank() || id == "remote") null
                else HttpModuleCandidate(id, o.optString("name", id), port)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun deviceRow(name: String, statusText: String, running: Boolean, onOpen: () -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also { it.marginEnd = dp(10) }
                setBackgroundColor(
                    if (running) ctx.kairosThemeColor(R.attr.kairosGreen) else ctx.kairosThemeColor(R.attr.kairosText3)
                )
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = name
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                })
                addView(TextView(ctx).apply {
                    text = statusText
                    textSize = 11f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                })
            })
            addView(smallButton(getString(R.string.homelab_btn_abrir), running, onOpen))
        }
    }

    // ── "Servicios" — dashboard real, servicios externos agregados por el usuario ───────────

    private fun buildServicesSection() {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(sectionLabel(getString(R.string.homelab_section_servicios)).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        header.addView(smallButton(getString(R.string.homelab_btn_agregar), true) { showAddServiceDialog() })
        container.addView(header)

        servicesEmptyState = TextView(requireContext()).apply {
            text = getString(R.string.homelab_servicios_vacio)
            textSize = 12f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        servicesContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        container.addView(servicesEmptyState)
        container.addView(servicesContainer)
    }

    private fun refreshServices() {
        if (!isAdded) return
        Thread {
            val services = try { HomelabManager.listServices() } catch (_: Exception) { emptyList() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                servicesContainer.removeAllViews()
                servicesEmptyState.visibility = if (services.isEmpty()) View.VISIBLE else View.GONE
                services.forEach { s -> servicesContainer.addView(serviceCard(s)) }
            }
            // Ping en un segundo pase — no bloquea el primer render de la lista con la latencia
            // de red de cada probe (podría ser varios servicios en LANs lentas).
            if (services.isNotEmpty()) pingServicesAndUpdate(services)
        }.start()
    }

    private fun pingServicesAndUpdate(services: List<HomelabService>) {
        services.forEach { s ->
            val reachable = try { HomelabManager.probe(s.url) } catch (_: Exception) { false }
            if (!isAdded) return
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                (servicesContainer.findViewWithTag<View>("dot_${s.id}"))?.setBackgroundColor(
                    if (reachable) requireContext().kairosThemeColor(R.attr.kairosGreen)
                    else requireContext().kairosThemeColor(R.attr.kairosRed)
                )
            }
        }
    }

    private fun serviceCard(service: HomelabService): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = resources.getDimension(R.dimen.kairos_stroke_default)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(8) }
            setOnLongClickListener { showServiceMenu(service); true }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(View(ctx).apply {
            tag = "dot_${service.id}"
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also { it.marginEnd = dp(10) }
            // Gris mientras no llegó el primer probe (pingServicesAndUpdate lo pinta verde/rojo
            // apenas responde) — nunca se asume "activo" por default.
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = service.name
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(ctx).apply {
                text = "${typeLabel(service.type)} · ${service.url}"
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
        })
        row.addView(smallButton(getString(R.string.homelab_btn_abrir), true) { openService(service) })
        card.addView(row)
        return card
    }

    private fun openService(service: HomelabService) {
        navigateTo(ModuleWebViewFragment.newInstance(service.url, service.name))
    }

    private fun showServiceMenu(service: HomelabService) {
        val options = mutableListOf(getString(R.string.homelab_menu_editar))
        options.add(if (service.hasSecret) getString(R.string.homelab_menu_reemplazar_token) else getString(R.string.homelab_menu_agregar_token))
        if (service.hasSecret) options.add(getString(R.string.homelab_menu_borrar_token))
        options.add(getString(R.string.homelab_menu_eliminar))
        AlertDialog.Builder(requireContext())
            .setTitle(service.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.homelab_menu_editar) -> showAddServiceDialog(service)
                    getString(R.string.homelab_menu_reemplazar_token), getString(R.string.homelab_menu_agregar_token) -> showReplaceSecretDialog(service)
                    getString(R.string.homelab_menu_borrar_token) -> confirmClearSecret(service)
                    getString(R.string.homelab_menu_eliminar) -> confirmDeleteService(service)
                }
            }
            .show()
    }

    private fun confirmClearSecret(service: HomelabService) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.homelab_menu_borrar_token))
            .setMessage(getString(R.string.homelab_dialog_msg_borrar_token, service.name))
            .setPositiveButton(getString(R.string.homelab_btn_eliminar)) { _, _ ->
                runInBackground({ HomelabManager.clearSecret(service.id) }) { result ->
                    toast(if (result.ok) result.message else result.error)
                    refreshServices()
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    private fun confirmDeleteService(service: HomelabService) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.homelab_dialog_title_eliminar_servicio))
            .setMessage(getString(R.string.homelab_dialog_msg_eliminar_servicio, service.name))
            .setPositiveButton(getString(R.string.homelab_btn_eliminar)) { _, _ ->
                runInBackground({ HomelabManager.deleteService(service.id) }) { result ->
                    toast(if (result.ok) result.message else result.error)
                    refreshServices()
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    /** Diálogo "Reemplazar token" — arranca SIEMPRE vacío, nunca pre-cargado con el valor
     *  anterior (regla dura, ver docstring de [HomelabManager.replaceSecret]). */
    private fun showReplaceSecretDialog(service: HomelabService) {
        val ctx = requireContext()
        val edit = EditText(ctx).apply {
            hint = getString(R.string.homelab_hint_token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.homelab_menu_reemplazar_token))
            .setView(wrapDialogContent(edit))
            .setPositiveButton(getString(R.string.homelab_btn_guardar)) { _, _ ->
                val value = edit.text.toString()
                if (value.isBlank()) { toast(getString(R.string.homelab_msg_token_vacio)); return@setPositiveButton }
                runInBackground({ HomelabManager.replaceSecret(service.id, value) }) { result ->
                    toast(if (result.ok) result.message else result.error)
                    refreshServices()
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    /** Diálogo "Agregar/Editar servicio" — [existing] null crea uno nuevo (con campo opcional de
     *  token), no-null edita nombre/tipo/URL/usuario de uno ya guardado (el token se maneja
     *  siempre por separado, ver [showReplaceSecretDialog], nunca mezclado en este diálogo para
     *  no tener que pre-cargarlo). */
    private fun showAddServiceDialog(existing: HomelabService? = null) {
        val ctx = requireContext()
        val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.homelab_hint_nombre)
            setText(existing?.name ?: "")
        }
        val typeLabels = arrayOf(
            getString(R.string.homelab_type_docker),
            getString(R.string.homelab_type_pihole),
            getString(R.string.homelab_type_proxmox),
            getString(R.string.homelab_type_http),
            getString(R.string.homelab_type_otro)
        )
        val typeOrder = listOf(ServiceType.DOCKER, ServiceType.PIHOLE, ServiceType.PROXMOX, ServiceType.HTTP, ServiceType.OTHER)
        val typeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, typeLabels)
            setSelection(existing?.let { typeOrder.indexOf(it.type) }?.takeIf { it >= 0 } ?: 3)
        }
        val urlEdit = EditText(ctx).apply {
            hint = getString(R.string.homelab_hint_url)
            setText(existing?.url ?: "")
        }
        val userEdit = EditText(ctx).apply {
            hint = getString(R.string.homelab_hint_usuario_opcional)
            setText(existing?.username ?: "")
        }
        view.addView(nameEdit)
        view.addView(typeSpinner)
        view.addView(urlEdit)
        view.addView(userEdit)
        var tokenEdit: EditText? = null
        if (existing == null) {
            tokenEdit = EditText(ctx).apply {
                hint = getString(R.string.homelab_hint_token_opcional)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            view.addView(tokenEdit)
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(if (existing == null) R.string.homelab_dialog_title_agregar else R.string.homelab_dialog_title_editar))
            .setView(wrapDialogContent(view))
            .setPositiveButton(getString(R.string.homelab_btn_guardar)) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val url = urlEdit.text.toString().trim()
                val user = userEdit.text.toString().trim()
                val type = typeOrder[typeSpinner.selectedItemPosition]
                if (url.isBlank()) { toast(getString(R.string.homelab_msg_url_vacia)); return@setPositiveButton }
                if (existing == null) {
                    val token = tokenEdit?.text?.toString()?.trim()
                    runInBackground({ HomelabManager.addService(name, type, url, user, token) }) { result ->
                        toast(if (result.ok) result.message else result.error)
                        refreshServices()
                    }
                } else {
                    runInBackground({ HomelabManager.updateService(existing.id, name, type, url, user) }) { result ->
                        toast(if (result.ok) result.message else result.error)
                        refreshServices()
                    }
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    private fun typeLabel(type: ServiceType): String = when (type) {
        ServiceType.DOCKER -> getString(R.string.homelab_type_docker)
        ServiceType.PIHOLE -> getString(R.string.homelab_type_pihole)
        ServiceType.PROXMOX -> getString(R.string.homelab_type_proxmox)
        ServiceType.HTTP -> getString(R.string.homelab_type_http)
        ServiceType.OTHER -> getString(R.string.homelab_type_otro)
    }

    // ── Helpers de estilo/layout — versión reducida y autónoma de los helpers equivalentes de
    //    BaseModuleFragment (addCard/infoRow/pill/dp) — HomelabFragment no extiende
    //    BaseModuleFragment (no es un módulo con lifecycle de proceso, ver KDoc de la clase). ──

    private fun sectionTitle(text: String): View = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
        setPadding(dp(4), dp(4), dp(4), dp(2))
    }

    private fun sectionLabel(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
        letterSpacing = 0.08f
        setPadding(dp(4), dp(12), dp(4), dp(6))
    }

    private fun smallButton(text: String, enabled: Boolean, onClick: () -> Unit): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            alpha = if (enabled) 1f else 0.4f
            isEnabled = enabled
            setBackgroundColor(Color.argb(28, 0, 122, 255))
            setOnClickListener { if (enabled) onClick() }
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

    // Mismo patrón de navegación que BaseModuleFragment.navigateTo() — HomelabFragment vive
    // como tab raíz (agregado directo a R.id.fragment_container por TermuxActivity, igual que
    // Monitor/Nube/Entorno), así que parentFragmentManager acá ES el FragmentManager de la
    // Activity, mismo contrato que el resto de módulos que empujan un detalle con backstack.
    private fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun <T> runInBackground(work: () -> T, onResult: (T) -> Unit) {
        Thread {
            val result = work()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { if (isAdded) onResult(result) }
        }.start()
    }
}
