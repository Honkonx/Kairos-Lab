package com.termux.app.ui

import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.ModuleController
import com.termux.app.data.ModuleCatalog
import com.termux.app.data.ModuleInstalled
import com.termux.app.data.ModuleRegistry
import com.termux.app.model.ModuleInfo
import com.termux.app.model.ModuleInfo.Status
import com.termux.app.util.LocalPluginManager
import com.termux.app.util.ProjectsManager

/**
 * Tienda de plugins (Fase B del sistema de plugins, 2026-08-10 — ver
 * docs/arquitectura/PLAN.md "Sistema de plugins / Tienda de módulos"). Accesible desde el
 * menú "Más" → Plugins (nav_plugins).
 *
 * Lista el catálogo completo de módulos/plugins con:
 * - **Orden alfabético** por nombre — el orden lo resuelve [sortCatalog] (2026-08-16: antes
 *   ponía los `recommended=true` arriba por downloads/popularidad, se sacó a pedido del
 *   usuario).
 * - **Barra de búsqueda** por nombre/id/descripción.
 * - **Badges** de arquitectura (bionic/glibc/proot/proot-distro), categoría y popularidad
 *   (los pinta PluginListAdapter).
 * - **Estado real** por módulo (registry + tmux en vivo), versión instalada y aviso de
 *   actualización disponible cuando la del catálogo difiere.
 * - **Acciones** (2026-08-10, rediseño de pantallas — la Tienda NO navega al detalle/submenú):
 *   Instalar (hoja BottomSheetInstalacion), Cambiar método (reabre la hoja con force=true),
 *   Desinstalar (confirmación, usa ModuleController.uninstallModule). El detalle de un módulo
 *   se abre SOLO desde la pantalla Módulos (que muestra únicamente lo instalado).
 * - **"↻ Catálogo"**: refresco híbrido — descarga el catálogo remoto (raw GitHub
 *   Honkonx/Kairos) y lo mergea sobre el bundled; fallback silencioso a cache/bundled sin red.
 *
 * El estado (registry + procesos) se relee cada vez que la pantalla se muestra (onResume)
 * y tras cada acción, igual que ModulesFragment.pollStatus().
 */
class PluginsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PluginListAdapter
    private lateinit var searchInput: EditText
    private lateinit var countLabel: TextView
    private lateinit var refreshBtn: TextView
    private lateinit var localBtn: TextView

    private var allModules: List<ModuleInfo> = emptyList()
    private var query: String = ""
    private val localPluginIds = mutableSetOf<String>()

    // Mismo bug de navegación que ModulesFragment (ver MEJORAS_PENDIENTES.md): esta pantalla
    // también se recrea (onDestroyView + onViewCreated) al volver de otra pestaña/pantalla vía
    // backstack, perdiendo el RecyclerView y el adapter. allModules/query ya sobrevivían eso
    // (campos del Fragment), pero onViewCreated() siempre relanzaba loadCatalog() (Thread) y
    // esperaba a que terminara para mostrar algo. cachedStatuses/cachedVersions/cachedHidden
    // guardan el último resultado real de pollStatus() para repoblar sincrónicamente; el
    // savedLayoutState restaura la posición de scroll del LayoutManager, que también es una
    // instancia nueva en cada recreación.
    private var cachedStatuses: Map<String, Status> = emptyMap()
    private var cachedVersions: Map<String, String> = emptyMap()
    private var cachedHidden: Set<String> = emptySet()
    private var savedLayoutState: Parcelable? = null

    /**
     * Selector de paquete local (.deb o .tar.gz) vía Storage Access Framework — 2026-08-13,
     * pedido del usuario (ver docs/humano/humano100.md y LocalPluginManager). El archivo
     * elegido se copia a $HOME y se instala según su formato.
     */
    private val localPackagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            installLocalPackage(uri)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_plugins, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.plugin_recycler)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        // Mismo fix preventivo que ModulesFragment (bug #27, ver docs/humano/humano201.md) — este
        // RecyclerView también hace submitList() tras acciones del usuario (instalar/refrescar
        // catálogo); sin ItemAnimator, esas actualizaciones aplican al instante sin ventana de
        // desfase entre posición visual y real durante un toque.
        recyclerView.itemAnimator = null
        // Restaura la posición de scroll guardada en onDestroyView (si la vista se está
        // recreando al volver de otra pestaña/pantalla) — LinearLayoutManager aplica un
        // onRestoreInstanceState() pendiente en su próximo layout pass, sin importar que el
        // adapter todavía no tenga items en este punto.
        savedLayoutState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }

        adapter = PluginListAdapter(
            onManage = { module -> manageModule(module) },
            onInstall = { module -> showInstallSheet(module) },
            onToggleHidden = { module -> toggleHidden(module) }
        )
        recyclerView.adapter = adapter

        searchInput = view.findViewById(R.id.plugin_search)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })

        countLabel = view.findViewById(R.id.plugin_count)
        refreshBtn = view.findViewById(R.id.plugin_refresh)
        refreshBtn.setOnClickListener { refreshCatalog() }

        localBtn = view.findViewById(R.id.plugin_local)
        localBtn.setOnClickListener { pickLocalPackage() }

        if (allModules.isEmpty()) {
            // Primera vez que se crea esta vista en la sesión — no hay nada cacheado todavía,
            // hay que leer el catálogo real (ModuleCatalog.load(), Thread).
            loadCatalog()
        } else {
            // La vista se recreó (volver de otra pestaña/pantalla vía backstack) pero
            // allModules/query/cachedStatuses ya sobrevivieron en el Fragment — repoblar
            // sincrónicamente con eso en vez de esperar a loadCatalog() + pollStatus() de
            // nuevo. pollStatus() se sigue llamando abajo (onResume) para detectar cambios
            // reales mientras la pantalla estaba en segundo plano.
            if (query.isNotEmpty()) searchInput.setText(query)
            adapter.updateStatuses(cachedStatuses, cachedVersions, cachedHidden)
            applyFilter()
        }
    }

    override fun onResume() {
        super.onResume()
        // Estado real de módulos + catálogo (cache/bundled) cada vez que se muestra la
        // pantalla — refleja instalaciones/desinstalaciones hechas desde la pantalla de
        // módulos mientras la Tienda estaba en segundo plano.
        pollStatus()
    }

    override fun onDestroyView() {
        // Guardar ANTES de super.onDestroyView(): recyclerView sigue siendo la instancia
        // vieja acá — se lee su estado de scroll para restaurarlo si onViewCreated() se
        // vuelve a llamar (ver comentario de savedLayoutState arriba).
        if (::recyclerView.isInitialized) {
            savedLayoutState = recyclerView.layoutManager?.onSaveInstanceState()
        }
        super.onDestroyView()
    }

    // ────────────────────────────────────────────────────────────
    // Catálogo + estado
    // ────────────────────────────────────────────────────────────

    private fun loadCatalog() {
        Thread {
            // internal=true módulos (ver ModuleInfo.kt) no se ofrecen como plugin instalable
            // propio en la Tienda — viven consolidados dentro de "languages"/"packages".
            // hideFromCatalog=true (2026-08-26): módulos como "entorno" (Mini PC) que no deben
            // listarse en la Tienda aunque sigan siendo navegables directo desde otras pantallas.
            val catalog = ModuleCatalog.load(requireContext())
                .filterNot { it.internal || it.hideFromCatalog }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                allModules = catalog
                applyFilter()
                // Bug real confirmado por ADB (2026-08-25, ver docs/humano225.md y siguientes):
                // mismo bug que refreshCatalog() ya tenía arreglado (60f3921) pero en la carga
                // INICIAL — módulos genuinamente instalados (confirmados por registry, ej.
                // Hermes) se mostraban como "No instalado" la primera vez que se abría la
                // Tienda. Causa: onResume() ya llama pollStatus(), pero en la primera entrada
                // a la pantalla corre en paralelo con este Thread — si pollStatus() gana la
                // carrera, opera sobre "allModules" todavía vacío (recién se llena acá) y el
                // estado real nunca se aplica hasta la próxima vez que se re-entra a la
                // pantalla. Mismo fix que refreshCatalog(): recalcular el estado real sobre la
                // lista recién cargada, en vez de depender solo de onResume().
                pollStatus()
            }
        }.start()
    }

    private fun refreshCatalog() {
        val v = view ?: return
        refreshBtn.isEnabled = false
        refreshBtn.text = getString(R.string.plugins_btn_updating)
        Snackbar.make(v, getString(R.string.plugins_msg_searching_updates), Snackbar.LENGTH_SHORT).show()
        Thread {
            val refreshed = ModuleCatalog.refreshRemote(requireContext())
                .filterNot { it.internal || it.hideFromCatalog }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                refreshBtn.isEnabled = true
                refreshBtn.text = getString(R.string.plugins_btn_catalog)
                allModules = refreshed
                applyFilter()
                // Bug real confirmado en dispositivo (2026-08-24, ver docs/humano221.md):
                // refreshCatalog() reemplaza allModules con instancias NUEVAS de ModuleInfo
                // (de ModuleCatalog.refreshRemote()) pero nunca volvía a pedir el estado real
                // — módulos genuinamente instalados (confirmados en la pantalla Módulos y en
                // el registry) aparecían como "No instalado" en la Tienda hasta salir y volver
                // a entrar (onResume() sí llama pollStatus()). Mismo mecanismo que ya usa
                // onResume() para este caso: recalcular el estado sobre la lista nueva.
                pollStatus()
                // Bug real reportado por el usuario (2026-08-26): este Snackbar mostraba
                // "59 plugins" cuando la Tienda en realidad lista 40 — usaba
                // ModuleCatalog.load(...).size SIN el filtro de internal/hideFromCatalog que
                // el resto de esta función SÍ aplica (ver "refreshed" arriba). modules.json
                // tiene 59 entradas totales, pero 19 son módulos internos/ocultos (consolidados
                // dentro de "languages"/"packages", o como "entorno" que no debe listarse en la
                // Tienda) — nunca se muestran como tarjetas propias. Se usa refreshed.size (ya
                // filtrado, la misma lista que se acaba de asignar a allModules) en vez de
                // releer el catálogo crudo de nuevo.
                Snackbar.make(v, getString(R.string.plugins_msg_catalog_updated, refreshed.size), Snackbar.LENGTH_LONG).show()
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Paquete local (.deb / .tar.gz) — 2026-08-13
    // ────────────────────────────────────────────────────────────

    /**
     * Botón "📦 Paquete local": abre un diálogo con el formato soportado y el selector de
     * archivos. Resumen corto siempre visible — el ejemplo completo (mkdir/cat/EOF/tar) queda
     * detrás de "Ver ejemplo completo ↗" (setNeutralButton), que abre [showLocalPackageExample].
     * 2026-08-16: antes el mensaje mostraba las ~40 líneas de LocalPluginManager.packageSpec()
     * de una — pedido explícito del usuario ("mucho texto, toca hacer un resumen rapido").
     */
    private fun pickLocalPackage() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.plugins_dialog_install_local_title))
            .setMessage(getString(R.string.plugins_dialog_install_local_message))
            .setPositiveButton(getString(R.string.plugins_btn_choose_file)) { _, _ ->
                try {
                    localPackagePicker.launch(arrayOf("application/octet-stream", "application/x-deb", "application/gzip", "application/x-gzip", "application/x-tar"))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(R.string.plugins_error_open_picker, e.message), Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(getString(R.string.plugins_btn_view_example)) { _, _ -> showLocalPackageExample() }
            .setNegativeButton(getString(R.string.plugins_cancel), null)
            .show()
    }

    /**
     * Diálogo secundario con el contrato completo del .tar.gz (manifest.json + script,
     * ejemplo mkdir/cat/EOF/tar) — separado del diálogo principal para que ese quede corto.
     */
    private fun showLocalPackageExample() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.plugins_dialog_example_title))
            .setMessage(LocalPluginManager.packageSpec())
            .setPositiveButton(getString(R.string.plugins_close), null)
            .show()
    }

    /**
     * Instala el paquete elegido en background (LocalPluginManager) y recarga el catálogo
     * local para que el plugin aparezca en la Tienda.
     */
    private fun installLocalPackage(uri: android.net.Uri) {
        val v = view ?: return
        Snackbar.make(v, getString(R.string.plugins_msg_installing_local), Snackbar.LENGTH_SHORT).show()
        val ctx = requireContext()
        Thread {
            val result = LocalPluginManager.install(ctx, uri)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val ok = result.optBoolean("ok")
                val msg = if (ok) result.optString("message") else result.optString("error")
                val output = result.optString("output")
                val body = if (ok) msg else if (output.isNotEmpty()) "$msg\n\n$output" else msg
                AlertDialog.Builder(requireContext())
                    .setTitle(if (ok) getString(R.string.plugins_title_local_installed) else getString(R.string.plugins_title_local_install_failed))
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.plugins_ok)) { _, _ -> loadCatalog() }
                    .setNegativeButton(if (ok) null else getString(R.string.plugins_close), null)
                    .show()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = query.lowercase()
        val filtered = allModules.filter { module ->
            q.isEmpty() ||
                module.name.lowercase().contains(q) ||
                module.id.lowercase().contains(q) ||
                module.description.lowercase().contains(q) ||
                module.category.lowercase().contains(q)
        }
        adapter.submitList(sortCatalog(filtered))
        countLabel.text = getString(R.string.plugins_count_label, filtered.size)
    }

    /**
     * Orden alfabético por nombre — 2026-08-16, pedido del usuario: se sacó el badge/orden de
     * "recomendado" (antes ponía recommended=true arriba por downloads desc, ver historial de
     * este archivo). `recommended`/`downloads` siguen existiendo en ModuleInfo/modules.json
     * por si se necesitan para otra cosa, pero ya no afectan el orden ni se muestran acá.
     */
    private fun sortCatalog(modules: List<ModuleInfo>): List<ModuleInfo> =
        modules.sortedBy { it.name.lowercase() }

    private fun pollStatus() {
        val ctx = requireContext()
        Thread {
            try {
                val reg = ModuleRegistry(ctx).load().getModules()
                val statuses = mutableMapOf<String, Status>()
                val versions = mutableMapOf<String, String>()
                val hidden = mutableSetOf<String>()
                val snapshot = allModules
                for (def in snapshot) {
                    val mid = def.id
                    // Bug real reportado por el usuario: esta pantalla era la ÚNICA de la
                    // Tienda que confiaba ciegamente en el registry crudo — si el registry
                    // quedaba desincronizado del filesystem real (mismo patrón ya visto antes
                    // en este proyecto, ej. python.installed nunca escrito por kairos.sh), acá
                    // seguía mostrando "Instalar" aunque el módulo funcionara. El resto de la
                    // app (BaseModuleFragment.isModuleInstalled(), pantallas de detalle) ya usa
                    // ModuleInstalled.isInstalledRobust() — mismo chequeo acá, con su propio
                    // caché (10-30s) así no repite el costo de la verificación en vivo en cada
                    // poll si nada cambió.
                    val isInstalled = ModuleInstalled.isInstalledRobust(ctx, mid)
                    statuses[mid] = when {
                        isInstalled && ModuleController.isRunning(mid) -> Status.RUNNING
                        isInstalled -> Status.INSTALLED_STOPPED
                        else -> Status.NOT_INSTALLED
                    }
                    reg["$mid.version"]?.let { if (it.isNotEmpty()) versions[mid] = it }
                    if (reg["$mid.hidden"] == "true") hidden.add(mid)
                }
                // 2026-08-14 (humano123): plugins locales (.tar.gz) — leídos en background
                // con el resto del estado, para que "🗑 Quitar de la Tienda (local)" aparezca
                // solo en los que corresponde.
                val localIds = LocalPluginManager.localIds()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    localPluginIds.clear()
                    localPluginIds.addAll(localIds)
                    // Cachear el último resultado real conocido — lo usa onViewCreated() para
                    // repoblar sincrónicamente un adapter nuevo tras recrearse la vista (ver
                    // comentario de cachedStatuses/cachedVersions/cachedHidden arriba).
                    cachedStatuses = statuses
                    cachedVersions = versions
                    cachedHidden = hidden
                    adapter.updateStatuses(statuses, versions, hidden)
                }
            } catch (e: Exception) {
                // Same catch-all pattern as ModulesFragment.pollStatus(): never let a status
                // poll kill the screen silently.
                android.util.Log.e("PluginsFragment", "pollStatus() falló", e)
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Acciones
    // ────────────────────────────────────────────────────────────

    /**
     * Click en la tarjeta de un plugin. 2026-08-10 (rediseño de pantallas): la Tienda NO
     * navega al submenú/detalle — eso es exclusivo de la pantalla Módulos (solo instalados).
     * Acá el click de un módulo instalado abre un diálogo con Cambiar método / Desinstalar;
     * si no está instalado, abre directo la hoja de instalación.
     */
    private fun manageModule(module: ModuleInfo) {
        val status = adapter.getStatus(module.id)
        if (status == Status.NOT_INSTALLED || status == Status.ERROR) {
            showInstallSheet(module)
            return
        }
        // Bug real (2026-08-11, humano97): "Cambiar método" se mostraba para TODO módulo
        // instalado. Solo 4 módulos tienen variantes reales (ollama, n8n, claude, codex —
        // hasVariants=true y >1 installMethods en modules.json); opencode/hermes/python/...
        // aceptan --variant como no-op pero no ofrecen opción que elegir.
        val hasVariants = module.hasVariants && module.installMethods.size > 1
        // 2026-08-11 (humano97 punto 4): Desactivar = ocultar del home sin desinstalar;
        // Activar = restaurarlo. Se muestra "Activar"/"Desactivar" en vez de solo
        // "Desinstalar" para que quitar un módulo del inicio sea reversible.
        val isHidden = adapter.isHidden(module.id)
        // Lista de (label, acción) en vez de índices mágicos sobre un array paralelo — esta
        // ronda (ver "Reinstalar limpio" abajo) agregó una quinta opción variable y mantener
        // toggleIndex/uninstallIndex a mano se volvía frágil.
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (hasVariants) actions.add(getString(R.string.plugins_change_install_method) to { showInstallSheet(module, force = true) })
        actions.add((if (isHidden) getString(R.string.plugins_activar) else getString(R.string.plugins_desactivar)) to { toggleHidden(module) })
        // "Reinstalar limpio" (2026-08-14, combo de esta ronda): a diferencia de "Cambiar
        // método de instalación" (reinstala con force=true SIN tocar el paquete real ya
        // instalado, ver installModule()), esto borra el paquete real primero
        // (deepUninstallModule) y recién ahí reinstala desde cero — para módulos que quedaron
        // en un estado roto que la reinstalación normal no soluciona.
        actions.add(getString(R.string.plugins_menu_clean_reinstall) to { confirmCleanReinstall(module) })
        // 2026-08-14 (humano123): quitar de la Tienda los plugins locales (.tar.gz) que se
        // instalaron desde "📦 Paquete local" — antes quedaban permanentemente en la lista
        // sin forma de sacarlos desde la UI (había que borrar ~/kairos_local/catalog.json a
        // mano). El check de local se hace en background porque lee ~/kairos_local/catalog.json.
        if (module.id in localPluginIds) actions.add(getString(R.string.plugins_menu_remove_local) to { confirmRemoveLocal(module) })
        actions.add(getString(R.string.plugins_desinstalar) to { confirmUninstall(module) })
        AlertDialog.Builder(requireContext())
            .setTitle(module.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(getString(R.string.plugins_cancel), null)
            .show()
    }

    /**
     * "Reinstalar limpio" — más agresivo que "Cambiar método de instalación": borra el
     * paquete real del módulo (ModuleController.deepUninstallModule) y recién después
     * reinstala desde cero (force=true), en vez de solo reinstalar encima de lo que ya
     * estaba. Pensado para un módulo que quedó en un estado roto que la reinstalación
     * normal (que nunca toca el paquete ya instalado) no soluciona.
     */
    private fun confirmCleanReinstall(module: ModuleInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.plugins_confirm_clean_reinstall_title, module.name))
            .setMessage(getString(R.string.plugins_confirm_clean_reinstall_message))
            .setPositiveButton(getString(R.string.plugins_btn_clean_reinstall)) { _, _ ->
                val v = view
                if (v != null) Snackbar.make(v, getString(R.string.plugins_msg_clean_reinstalling, module.name), Snackbar.LENGTH_SHORT).show()
                ModuleController.cleanReinstallModule(module.id) { ok, message ->
                    if (!isAdded) return@cleanReinstallModule
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        AlertDialog.Builder(requireContext())
                            .setTitle(if (ok) getString(R.string.plugins_title_reinstalled, module.name) else getString(R.string.plugins_title_reinstall_failed, module.name))
                            .setMessage(message)
                            .setPositiveButton(getString(R.string.plugins_ok), null)
                            .show()
                        pollStatus()
                    }
                }
            }
            .setNegativeButton(getString(R.string.plugins_cancel), null)
            .show()
    }

    /**
     * 2026-08-11 (humano97 punto 4): oculta/restaura un módulo en la pantalla Módulos SIN
     * desinstalarlo. Escribe la clave `<id>.hidden` en el registry real (~/.android_server_registry)
     * con el mismo lock que el resto de escrituras (ProjectsManager.setModuleHidden). El módulo
     * sigue instalado (registry <id>.installed intacto) y se reactiva desde acá sin reinstalar.
     */
    private fun toggleHidden(module: ModuleInfo) {
        val hidden = !adapter.isHidden(module.id)
        val ctx = requireContext()
        Thread {
            ProjectsManager.setModuleHidden(module.id, hidden)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Toast.makeText(
                    ctx,
                    if (hidden) getString(R.string.plugins_toast_hidden, module.name) else getString(R.string.plugins_toast_visible, module.name),
                    Toast.LENGTH_SHORT
                ).show()
                pollStatus()
            }
        }.start()
    }

    private fun showInstallSheet(module: ModuleInfo, force: Boolean = false) {
        val sheet = BottomSheetInstalacion.newInstance(
            id = module.id,
            name = module.name,
            icon = module.icon,
            iconBg = module.iconBg,
            description = module.description,
            port = module.port,
            size = module.size,
            type = module.type,
            requiresProot = module.requiresProot,
            hasVariants = module.hasVariants,
            estimate = module.estimate,
            force = force
        )
        sheet.show(parentFragmentManager, "install")
    }

    // Checkbox "opt-in" de desinstalación profunda (2026-08-13, ver deepUninstallModule() en
    // ModuleController.kt) — armado a mano en vez de un layout XML nuevo, mismo criterio de
    // "lo más simple que funcione" que el resto del diálogo (setMessage plano, sin custom
    // view previo en esta pantalla).
    private fun confirmUninstall(module: ModuleInfo) {
        val deepCheckbox = android.widget.CheckBox(requireContext()).apply {
            text = getString(R.string.plugins_checkbox_deep_uninstall)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.plugins_confirm_uninstall_title, module.name))
            .setMessage(getString(R.string.plugins_confirm_uninstall_message))
            .setView(deepCheckbox)
            .setPositiveButton(getString(R.string.plugins_desinstalar)) { _, _ ->
                if (deepCheckbox.isChecked) {
                    ModuleController.deepUninstallModule(module.id) { _, message ->
                        if (!isAdded) return@deepUninstallModule
                        requireActivity().runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                            pollStatus()
                        }
                    }
                } else {
                    ModuleController.uninstallModule(module.id) { ok ->
                        if (!isAdded) return@uninstallModule
                        requireActivity().runOnUiThread {
                            val msg = if (ok) getString(R.string.plugins_toast_uninstalled, module.name) else getString(R.string.plugins_toast_uninstall_failed, module.name)
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                            pollStatus()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.plugins_cancel), null)
            .show()
    }

    /**
     * "Quitar de la Tienda (local)" — 2026-08-14 (humano123). Borra el plugin del catálogo
     * local persistente y su script (~/scripts/install/<id>.sh), para que desaparezca de la
     * Tienda. NO desinstala el paquete real si el plugin ya estaba instalado — eso sigue
     * siendo trabajo de "Desinstalar". Se avisa en el diálogo para que el usuario decida.
     */
    private fun confirmRemoveLocal(module: ModuleInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.plugins_confirm_remove_local_title, module.name))
            .setMessage(getString(R.string.plugins_confirm_remove_local_message, module.id))
            .setPositiveButton(getString(R.string.plugins_btn_quitar)) { _, _ ->
                val v = view
                if (v != null) Snackbar.make(v, getString(R.string.plugins_msg_removing, module.name), Snackbar.LENGTH_SHORT).show()
                val ctx = requireContext()
                Thread {
                    val removed = LocalPluginManager.removeLocal(module.id)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (removed) {
                            Toast.makeText(ctx, getString(R.string.plugins_toast_removed, module.name), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, getString(R.string.plugins_toast_not_in_local_catalog), Toast.LENGTH_SHORT).show()
                        }
                        loadCatalog()
                        pollStatus()
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.plugins_cancel), null)
            .show()
    }
}
