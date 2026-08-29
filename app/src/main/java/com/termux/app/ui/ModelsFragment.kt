package com.termux.app.ui

import android.app.ActivityManager
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.OllamaApiClient
import com.termux.app.util.kairosThemeColor

/**
 * Lista real de modelos de Ollama — antes era una lista estática hardcodeada donde tocar un
 * modelo no hacía nada. Ahora usa OllamaApiClient (HTTP directo a la API de Ollama en
 * :11434) en vez de saltar a kairos_manager.py cmd_ollama's models-list/models-pull/
 * models-delete/models-info por python3 — esas acciones eran subprocess+urllib puro sin
 * lógica propia, la API HTTP es la misma que ya habla Ollama (ver ChatFragment para el
 * patrón HttpURLConnection ya probado en esta app).
 */
class ModelsFragment : BaseModuleFragment() {
    override fun getModuleId() = "ollama"
    override fun getModuleName() = getString(R.string.models_module_name)

    /** Categoría real del modelo — determina en qué pestaña del catálogo aparece (2026-08-28,
     * ver `.claude/rules/kairos-product-philosophy.md`: tabs para no tener que scrollear un
     * catálogo de 21 modelos como una sola lista plana). TEXT/VISION nada más — Ollama no
     * tiene una categoría real de "archivos"/embeddings navegable desde este catálogo curado
     * (los embedding models existen pero no encajan en el flujo de Chat IA de esta pantalla). */
    private enum class CatalogCategory { TEXT, VISION }

    /** Modelo del catálogo curado — ver CATALOG más abajo, mismo patrón que LocalAIFragment.
     * `descriptionRes` en vez de un String literal porque CATALOG se construye en el
     * companion object (sin Context disponible ahí) — el ID de recurso se resuelve recién
     * al mostrarlo, en refreshCatalogList(). */
    private data class CatalogModel(
        val tag: String,
        val displayName: String,
        val sizeLabel: String,
        val descriptionRes: Int,
        val category: CatalogCategory = CatalogCategory.TEXT,
    )

    companion object {
        // Catálogo curado de modelos de Ollama (pedido explícito del usuario, 2026-08-01,
        // ver docs/humano/humano42.md: "debes poner modelos para descargar como en llama.cpp, tipo
        // qwen, gemma etc, asi es mas facil") — mismo patrón que LocalAIFragment.CATALOG para
        // GGUF, pero acá el "id" es el tag real de Ollama (no una URL de descarga directa,
        // Ollama resuelve el archivo real internamente al hacer `pullModel(tag)`). Tags y
        // tamaños confirmados contra ollama.com/library el 2026-08-01, no inventados.
        private val CATALOG = listOf(
            CatalogModel(
                tag = "qwen2.5:0.5b",
                displayName = "Qwen2.5 0.5B",
                sizeLabel = "~398 MB",
                descriptionRes = R.string.models_desc_qwen05b,
            ),
            CatalogModel(
                tag = "qwen2.5:1.5b",
                displayName = "Qwen2.5 1.5B",
                sizeLabel = "~986 MB",
                descriptionRes = R.string.models_desc_qwen15b,
            ),
            CatalogModel(
                tag = "qwen2.5:3b",
                displayName = "Qwen2.5 3B",
                sizeLabel = "~1.9 GB",
                descriptionRes = R.string.models_desc_qwen3b,
            ),
            CatalogModel(
                tag = "gemma2:2b",
                displayName = "Gemma 2 2B",
                sizeLabel = "~1.6 GB",
                descriptionRes = R.string.models_desc_gemma2b,
            ),
            CatalogModel(
                tag = "llama3.2:1b",
                displayName = "Llama 3.2 1B",
                sizeLabel = "~1.3 GB",
                descriptionRes = R.string.models_desc_llama1b,
            ),
            CatalogModel(
                tag = "llama3.2:3b",
                displayName = "Llama 3.2 3B",
                sizeLabel = "~2.0 GB",
                descriptionRes = R.string.models_desc_llama3b,
            ),
            // Expansión 2026-08-28 (auditoría de paridad Ollama/llama.cpp) — tags confirmados
            // uno por uno contra ollama.com/library/<modelo>/tags (existencia real del
            // tag exacto, no solo de la familia) antes de agregarlos acá.
            CatalogModel(
                tag = "llama3.1:8b",
                displayName = "Llama 3.1 8B",
                sizeLabel = "~4.7 GB",
                descriptionRes = R.string.models_desc_llama318b,
            ),
            CatalogModel(
                tag = "qwen2.5:7b",
                displayName = "Qwen2.5 7B",
                sizeLabel = "~4.7 GB",
                descriptionRes = R.string.models_desc_qwen257b,
            ),
            CatalogModel(
                tag = "qwen2.5-coder:7b",
                displayName = "Qwen2.5 Coder 7B",
                sizeLabel = "~4.7 GB",
                descriptionRes = R.string.models_desc_qwen25coder7b,
            ),
            CatalogModel(
                tag = "mistral:7b",
                displayName = "Mistral 7B",
                sizeLabel = "~4.1 GB",
                descriptionRes = R.string.models_desc_mistral7b,
            ),
            CatalogModel(
                tag = "mistral-nemo:12b",
                displayName = "Mistral NeMo 12B",
                sizeLabel = "~7.1 GB",
                descriptionRes = R.string.models_desc_mistralnemo12b,
            ),
            CatalogModel(
                tag = "gemma2:9b",
                displayName = "Gemma 2 9B",
                sizeLabel = "~5.4 GB",
                descriptionRes = R.string.models_desc_gemma29b,
            ),
            CatalogModel(
                tag = "gemma3:4b",
                displayName = "Gemma 3 4B",
                sizeLabel = "~3.3 GB",
                descriptionRes = R.string.models_desc_gemma34b,
            ),
            CatalogModel(
                tag = "qwen3:4b",
                displayName = "Qwen3 4B",
                sizeLabel = "~2.6 GB",
                descriptionRes = R.string.models_desc_qwen34b,
            ),
            CatalogModel(
                tag = "qwen3:8b",
                displayName = "Qwen3 8B",
                sizeLabel = "~5.2 GB",
                descriptionRes = R.string.models_desc_qwen38b,
            ),
            CatalogModel(
                tag = "deepseek-r1:1.5b",
                displayName = "DeepSeek R1 1.5B",
                sizeLabel = "~1.1 GB",
                descriptionRes = R.string.models_desc_deepseekr115b,
            ),
            CatalogModel(
                tag = "deepseek-r1:7b",
                displayName = "DeepSeek R1 7B",
                sizeLabel = "~4.7 GB",
                descriptionRes = R.string.models_desc_deepseekr17b,
            ),
            // Vision/multimodal — Ollama soporta imágenes de forma nativa en /api/chat (campo
            // "images", ver ChatFragment.makeOllamaRequest e infra de detección de capability
            // "vision" en OllamaApiClient.modelInfo) — a diferencia del catálogo GGUF de
            // llama.cpp (LocalAIFragment), donde el motor embebido (llama-engine) no tiene
            // soporte de mmproj todavía, así que esos SÍ son reales acá.
            CatalogModel(
                tag = "llama3.2-vision:11b",
                displayName = "Llama 3.2 Vision 11B",
                sizeLabel = "~7.9 GB",
                descriptionRes = R.string.models_desc_llama32vision11b,
                category = CatalogCategory.VISION,
            ),
            CatalogModel(
                tag = "llava:7b",
                displayName = "LLaVA 7B",
                sizeLabel = "~4.7 GB",
                descriptionRes = R.string.models_desc_llava7b,
                category = CatalogCategory.VISION,
            ),
            CatalogModel(
                tag = "moondream:1.8b",
                displayName = "Moondream 1.8B",
                sizeLabel = "~1.7 GB",
                descriptionRes = R.string.models_desc_moondream18b,
                category = CatalogCategory.VISION,
            ),
            CatalogModel(
                tag = "minicpm-v:8b",
                displayName = "MiniCPM-V 8B",
                sizeLabel = "~5.5 GB",
                descriptionRes = R.string.models_desc_minicpmv8b,
                category = CatalogCategory.VISION,
            ),
        )
    }

    private lateinit var modelsContainer: LinearLayout
    /** Pestañas "Texto"/"Imagen" del catálogo — reconstruyen su contenido con renderCatalogTab()
     * cada vez que cambian, ver TabbedSections en BaseModuleFragment. */
    private var catalogTabs: TabbedSections? = null
    /** Última lista de nombres instalados conocida — las pestañas la leen al reconstruirse
     * (build()/render() no reciben el resultado async de refreshModels() directo). */
    private var installedNamesCache: Set<String> = emptySet()

    /**
     * Hallazgo de investigación de foros/GitHub sobre Ollama en Termux (ver docs/humano/humano194.md):
     * un modelo 7B Q4_K_M (~4.7GB en disco) necesita ~6GB de RAM libre para cargar (pesos +
     * KV cache) — en un teléfono de 8GB totales el OOM-killer de Android mata la app a mitad
     * de generación, sin ningún aviso previo. El catálogo no tenía forma de anticipar esto.
     * RAM total real del dispositivo vía ActivityManager.MemoryInfo — no hay equivalente más
     * directo en Android para "cuánta RAM tiene este dispositivo" sin permisos extra.
     */
    private fun totalRamGb(): Double {
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    /** "~398 MB" / "~1.9 GB" (sizeLabel del CATALOG, tamaño real en disco) → GB como Double. */
    private fun parseDiskGb(sizeLabel: String): Double {
        val cleaned = sizeLabel.removePrefix("~").trim()
        val value = cleaned.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0.0
        return if (cleaned.contains("GB")) value else value / 1024.0
    }

    /**
     * RAM necesaria para cargar el modelo (pesos + KV cache), no solo su tamaño en disco.
     * Factor 1.3x según el caso real citado en la investigación (7B Q4_K_M: ~4.7GB en disco
     * → ~6GB de RAM para cargar, 6/4.7 ≈ 1.28) — una estimación, no un cálculo exacto de
     * llama.cpp/Ollama (el KV cache real depende también de OLLAMA_NUM_CTX configurado).
     */
    private fun estimatedRamGb(sizeLabel: String): Double = parseDiskGb(sizeLabel) * 1.3

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        addCard(getString(R.string.models_catalog_title)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.models_catalog_hint)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(4))
            })
            // Pestañas "Texto"/"Imagen" (2026-08-28) — antes una sola lista plana de 6
            // modelos, ahora 21 (11 texto nuevos + 4 vision nuevos + 6 originales) no entraban
            // cómodos sin categorizar. Mismo helper que CiberseguridadFragment/EntornoFragment
            // (BaseModuleFragment.setupTabs()), MODE_FIXED por ser solo 2 pestañas cortas.
            catalogTabs = setupTabs(
                listOf(getString(R.string.models_tab_texto), getString(R.string.models_tab_imagen)),
                parent = this,
                tabMode = TabLayout.MODE_FIXED
            )
                .tab(0) { content -> renderCatalogTab(content, CatalogCategory.TEXT) }
                .tab(1) { content -> renderCatalogTab(content, CatalogCategory.VISION) }
                .build()
        }
        addCard(getString(R.string.models_installed_title)) {
            modelsContainer = this
            addLoadingRow()
        }
        actionButton(getString(R.string.models_download_advanced), GHOST) { showPullDialog() }
        refreshModels()
    }

    /** Puebla una pestaña del catálogo con los modelos de [category] — separado de
     * refreshCatalogList() (que solo dispara el refresco de AMBAS pestañas cuando cambian los
     * modelos instalados) para no reconstruir la pestaña inactiva sin necesidad. */
    private fun renderCatalogTab(content: LinearLayout, category: CatalogCategory) {
        val deviceRamGb = totalRamGb()
        val entries = CATALOG.filter { it.category == category }
        for (entry in entries) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            val neededRamGb = estimatedRamGb(entry.sizeLabel)
            val exceedsRam = neededRamGb > deviceRamGb
            row.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(TextView(requireContext()).apply {
                    text = "${entry.displayName} · ${entry.sizeLabel}"
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                })
                addView(TextView(requireContext()).apply {
                    text = getString(entry.descriptionRes)
                    textSize = 11f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                })
                if (exceedsRam) {
                    // No bloquea la descarga (pedido explícito, ver docs/humano/humano194.md) — solo
                    // avisa antes de que el usuario se encuentre con un OOM-kill a mitad de
                    // generación sin haber tenido forma de anticiparlo.
                    addView(TextView(requireContext()).apply {
                        val neededRounded = String.format("%.1f", neededRamGb)
                        val deviceRounded = String.format("%.1f", deviceRamGb)
                        text = getString(R.string.models_ram_warning, neededRounded, deviceRounded)
                        textSize = 11f
                        setTextColor(requireContext().kairosThemeColor(R.attr.kairosAmber))
                    })
                }
            })
            if (installedNamesCache.contains(entry.tag)) {
                row.addView(TextView(requireContext()).apply {
                    text = getString(R.string.models_installed_check)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosGreen))
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                })
            } else {
                row.addView(TextView(requireContext()).apply {
                    text = getString(R.string.models_download)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosBlue))
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener { pullModel(entry.tag) }
                })
            }
            content.addView(row)
        }
    }

    /** Refresca la pestaña actualmente activa del catálogo con la lista de instalados más
     * reciente conocida — reemplaza al refreshCatalogList(Set) viejo que reconstruía una
     * lista plana única; ahora la fuente de verdad vive en installedNamesCache y cada
     * pestaña se reconstruye sola vía TabbedSections.renderActive(). */
    private fun refreshCatalogList(installedNames: Set<String>) {
        installedNamesCache = installedNames
        catalogTabs?.renderActive()
    }

    private fun addLoadingRow() {
        modelsContainer.addView(TextView(requireContext()).apply {
            text = getString(R.string.models_loading)
            textSize = 13f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
    }

    private fun refreshModels() {
        Thread {
            val result = try {
                OllamaApiClient.listModels() to null
            } catch (e: Exception) {
                null to (e.message ?: getString(R.string.models_unknown))
            }
            runOnMain {
                modelsContainer.removeAllViews()
                val (models, error) = result
                // El catálogo se refresca siempre acá (aunque falle listModels()) para que
                // sus botones "Descargar" reflejen el estado real más reciente conocido — si
                // Ollama está caído, igual se puede intentar descargar desde el catálogo
                // (pullModel arranca el servicio/reporta su propio error si hace falta).
                refreshCatalogList(models?.map { it.name }?.toSet() ?: emptySet())
                if (models == null) {
                    addStatusRow(getString(R.string.models_ollama_unavailable, error))
                    return@runOnMain
                }
                if (models.isEmpty()) {
                    addStatusRow(getString(R.string.models_none_installed))
                    return@runOnMain
                }
                for ((i, m) in models.withIndex()) {
                    modelsContainer.addView(TextView(requireContext()).apply {
                        text = if (m.family.isNotEmpty()) getString(R.string.models_row_with_family, m.name, m.sizeHuman, m.family) else getString(R.string.models_row_no_family, m.name, m.sizeHuman)
                        textSize = 13f
                        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        setOnClickListener { showModelDetail(m.name) }
                    })
                    if (i < models.size - 1) addDividerRow()
                }
            }
        }.start()
    }

    private fun runOnMain(block: () -> Unit) {
        if (!isAdded) return
        activity?.runOnUiThread { if (isAdded) block() }
    }

    private fun addStatusRow(text: String) {
        modelsContainer.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
    }

    private fun addDividerRow() {
        modelsContainer.addView(android.view.View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                it.leftMargin = dp(14); it.rightMargin = dp(14)
            }
            setBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBorder))
        })
    }

    private fun showModelDetail(name: String) {
        Thread {
            val result = try {
                OllamaApiClient.modelInfo(name) to null
            } catch (e: Exception) {
                null to (e.message ?: getString(R.string.models_unknown))
            }
            runOnMain {
                val (detail, error) = result
                val body = if (detail != null) {
                    getString(R.string.models_detail_body, detail.parameterSize, detail.family)
                } else {
                    getString(R.string.models_detail_error, error)
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(name)
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.models_action_close), null)
                    .setNegativeButton(getString(R.string.models_action_delete)) { _, _ -> confirmDelete(name) }
                    .show()
            }
        }.start()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.models_delete_title))
            .setMessage(getString(R.string.models_delete_message, name))
            .setPositiveButton(getString(R.string.models_action_delete)) { _, _ ->
                Thread {
                    val error = try {
                        OllamaApiClient.deleteModel(name)
                        null
                    } catch (e: Exception) {
                        e.message ?: getString(R.string.models_unknown)
                    }
                    runOnMain {
                        toast(if (error == null) getString(R.string.models_deleted) else getString(R.string.models_error_prefix, error))
                        modelsContainer.removeAllViews()
                        addLoadingRow()
                        refreshModels()
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.models_action_cancel), null)
            .show()
    }

    private fun showPullDialog() {
        val edit = EditText(requireContext()).apply { hint = getString(R.string.models_pull_hint) }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.models_pull_title))
            .setMessage(getString(R.string.models_pull_message))
            .setView(edit)
            .setPositiveButton(getString(R.string.models_download)) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isEmpty()) { toast(getString(R.string.models_empty_name)); return@setPositiveButton }
                pullModel(name)
            }
            .setNegativeButton(getString(R.string.models_action_cancel), null)
            .show()
    }

    /**
     * Progreso real en vivo (% + velocidad + ETA, ver OllamaApiClient.pullModel) en vez del
     * toast fijo "Descargando…" que antes se quedaba sin cambios hasta que la descarga
     * enterá terminaba — mismo patrón de diálogo con TextView actualizable que ya usa
     * LocalAIFragment.downloadModel() para los modelos GGUF locales.
     */
    private fun pullModel(name: String) {
        val appContext = requireContext().applicationContext
        val progress = com.termux.app.util.ProgressDialogController(requireContext())
        // allowBackground=true (docs/humano247.md, pedido explícito del usuario): un modelo
        // GGUF puede pesar varios GB y tardar minutos — antes el diálogo no-cancelable
        // bloqueaba toda la pantalla (bottom nav incluido) hasta que terminaba. Ahora el
        // usuario puede tocar "Enviar a 2do plano" y navegar libremente; se avisa por
        // notificación cuando termine (ver isBackgrounded más abajo).
        progress.show(getString(R.string.models_downloading_title, name), getString(R.string.models_downloading_message), allowBackground = true)

        Thread {
            val error = try {
                OllamaApiClient.pullModel(name) { pct, message ->
                    runOnMain { progress.updateProgress(pct, message) }
                }
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.models_unknown)
            }
            val backgrounded = progress.isBackgrounded
            if (backgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, name,
                    if (error == null) "install_done" else "install_failed",
                    if (error == null) getString(R.string.models_downloaded_notification) else error
                )
            }
            runOnMain {
                if (error == null) {
                    progress.success(getString(R.string.models_downloaded_success, name))
                } else {
                    progress.failure(getString(R.string.models_download_error_title, name), error)
                }
                modelsContainer.removeAllViews()
                addLoadingRow()
                refreshModels()
            }
        }.start()
    }
}
