package com.termux.app.ui

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.util.LocalModelManager
import com.termux.app.util.kairosThemeColor

/**
 * Pantalla "IA Local" — gestión de modelos GGUF + parámetros para el motor de
 * inferencia embebido (llama-engine, llama.cpp directo vía NDK, ver
 * docs/ia-local/LLAMA_CPP_EMBEBIDO.md). Los modelos que aparecen acá se ofrecen
 * también en el selector de ChatFragment, junto a los modelos remotos de
 * Ollama — el motor real se decide por qué modelo se eligió, mismo patrón
 * encontrado en la auditoría de PrivateLM (docs/referencias/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md,
 * sección "Profundización"): la complejidad de qué motor nativo hay que
 * inicializar queda oculta detrás de la elección del modelo, nunca un
 * toggle separado de "engine".
 *
 * Decisión "tab independiente vs. chat de Ollama" (feedback directo del
 * usuario tras probar la pantalla, docs/humano*.md de esta ronda): NO se
 * crea una pantalla de chat nueva. ChatFragment YA es un chat unificado —
 * el mismo selector de modelo lista tanto los remotos de Ollama como los
 * .gguf locales (marcados con 📱), y decide qué motor usar según cuál se
 * eligió (ver comentario de arriba). El problema real que reportó el
 * usuario no era arquitectura sino descubribilidad: nada en esta pantalla
 * decía "andá al chat para usar esto" — se resuelve con el botón
 * "Ir al chat" de más abajo, que salta al tab de chat vía BottomNavigationView
 * en vez de duplicar la lógica de mensajería acá.
 */
class LocalAIFragment : Fragment() {

    /** Modelo del catálogo curado — ver CATALOG más abajo. */
    private data class CatalogModel(
        val nameResId: Int,
        val fileName: String,
        val sizeLabel: String,
        val descResId: Int,
        val url: String,
        /** Modelos grandes (7B+) piden bastante más RAM/espacio — se muestran con aviso aparte del resto del catálogo. */
        val isLarge: Boolean = false,
    )

    companion object {
        // Catálogo curado — modelos chicos (0.5B-3B, cuantización Q4_K_M) de
        // repos estables de Hugging Face, verificados uno por uno (listado de
        // archivos del repo) antes de agregarlos acá. Responde al pedido
        // textual del usuario ("debe tener un catalogo para descargar") —
        // antes de esto la única forma de agregar un modelo era pegar una URL
        // a mano en showAddModelDialog(), que sigue existiendo como opción
        // avanzada para modelos fuera de este catálogo.
        private val CATALOG = listOf(
            CatalogModel(
                nameResId = R.string.localai_model_qwen05b_name,
                fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                sizeLabel = "~491 MB",
                descResId = R.string.localai_model_qwen05b_desc,
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_tinyllama11b_name,
                fileName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                sizeLabel = "~483 MB",
                descResId = R.string.localai_model_tinyllama11b_desc,
                url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_smollm217b_name,
                fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
                sizeLabel = "~1.06 GB",
                descResId = R.string.localai_model_smollm217b_desc,
                url = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwencoder15b_name,
                fileName = "Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~0.99 GB",
                descResId = R.string.localai_model_qwencoder15b_desc,
                url = "https://huggingface.co/bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwen15b_name,
                fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                sizeLabel = "~1.12 GB",
                descResId = R.string.localai_model_qwen15b_desc,
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_llama321b_name,
                fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~808 MB",
                descResId = R.string.localai_model_llama321b_desc,
                url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_gemma22b_name,
                fileName = "gemma-2-2b-it-Q4_K_M.gguf",
                sizeLabel = "~1.71 GB",
                descResId = R.string.localai_model_gemma22b_desc,
                url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwencoder3b_name,
                fileName = "Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~1.93 GB",
                descResId = R.string.localai_model_qwencoder3b_desc,
                url = "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwen3b_name,
                fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~1.93 GB",
                descResId = R.string.localai_model_qwen3b_desc,
                url = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_llama323b_name,
                fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~2.02 GB",
                descResId = R.string.localai_model_llama323b_desc,
                url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_phi3mini_name,
                fileName = "Phi-3-mini-4k-instruct-Q4_K_M.gguf",
                sizeLabel = "~2.39 GB",
                descResId = R.string.localai_model_phi3mini_desc,
                url = "https://huggingface.co/bartowski/Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_phi35mini_name,
                fileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
                sizeLabel = "~2.39 GB",
                descResId = R.string.localai_model_phi35mini_desc,
                url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            ),
            // Modelos grandes (7B-14B, cuantización Q4_K_M) — repos de bartowski/TheBloke,
            // verificados uno por uno contra el árbol de archivos real del repo en
            // Hugging Face antes de agregarlos (nombre de archivo y tamaño exactos).
            // Necesitan bastante más RAM/espacio que el resto del catálogo — se
            // muestran en la sección "grandes" de la Tienda con aviso aparte.
            CatalogModel(
                nameResId = R.string.localai_model_qwen7b_name,
                fileName = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~4.68 GB",
                descResId = R.string.localai_model_qwen7b_desc,
                url = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwencoder7b_name,
                fileName = "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~4.68 GB",
                descResId = R.string.localai_model_qwencoder7b_desc,
                url = "https://huggingface.co/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_mistral7b_name,
                fileName = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
                sizeLabel = "~4.37 GB",
                descResId = R.string.localai_model_mistral7b_desc,
                url = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_openhermes7b_name,
                fileName = "openhermes-2.5-mistral-7b.Q4_K_M.gguf",
                sizeLabel = "~4.37 GB",
                descResId = R.string.localai_model_openhermes7b_desc,
                url = "https://huggingface.co/TheBloke/OpenHermes-2.5-Mistral-7B-GGUF/resolve/main/openhermes-2.5-mistral-7b.Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_llama38b_name,
                fileName = "Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~4.92 GB",
                descResId = R.string.localai_model_llama38b_desc,
                url = "https://huggingface.co/bartowski/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_llama318b_name,
                fileName = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~4.92 GB",
                descResId = R.string.localai_model_llama318b_desc,
                url = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_gemma29b_name,
                fileName = "gemma-2-9b-it-Q4_K_M.gguf",
                sizeLabel = "~5.76 GB",
                descResId = R.string.localai_model_gemma29b_desc,
                url = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwen14b_name,
                fileName = "Qwen2.5-14B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~8.99 GB",
                descResId = R.string.localai_model_qwen14b_desc,
                url = "https://huggingface.co/bartowski/Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_phi414b_name,
                fileName = "phi-4-Q4_K_M.gguf",
                sizeLabel = "~8.43 GB",
                descResId = R.string.localai_model_phi414b_desc,
                url = "https://huggingface.co/bartowski/phi-4-GGUF/resolve/main/phi-4-Q4_K_M.gguf",
                isLarge = true,
            ),
            // Expansión 2026-08-28 (auditoría de paridad Ollama/llama.cpp) — cada repo/archivo
            // verificado uno por uno contra la Hub API real (huggingface.co/api/models/<repo>)
            // antes de agregarlo, mismo criterio que el resto del catálogo. Sin modelos de
            // visión acá a propósito: llama-engine (el motor NDK embebido que usa esta pantalla,
            // ver LlamaEngine/LlamaServerFragment) no tiene soporte de mmproj/clip todavía —
            // agregar un GGUF de LLaVA/Qwen-VL sin forma de cargar su mmproj sería un modelo que
            // se descarga pero no funciona como visión, así que se deja fuera (a diferencia de
            // Ollama, que sí soporta imágenes nativamente en /api/chat, ver ModelsFragment.CATALOG).
            CatalogModel(
                nameResId = R.string.localai_model_qwencoder05b_name,
                fileName = "Qwen2.5-Coder-0.5B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~0.37 GB",
                descResId = R.string.localai_model_qwencoder05b_desc,
                url = "https://huggingface.co/bartowski/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-0.5B-Instruct-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_deepseekr115b_name,
                fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
                sizeLabel = "~1.04 GB",
                descResId = R.string.localai_model_deepseekr115b_desc,
                url = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwen34b_name,
                fileName = "Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                sizeLabel = "~2.33 GB",
                descResId = R.string.localai_model_qwen34b_desc,
                url = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_gemma34b_name,
                fileName = "google_gemma-3-4b-it-Q4_K_M.gguf",
                sizeLabel = "~2.32 GB",
                descResId = R.string.localai_model_gemma34b_desc,
                url = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_phi4mini_name,
                fileName = "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf",
                sizeLabel = "~2.32 GB",
                descResId = R.string.localai_model_phi4mini_desc,
                url = "https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf",
            ),
            CatalogModel(
                nameResId = R.string.localai_model_deepseekr17b_name,
                fileName = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
                sizeLabel = "~4.36 GB",
                descResId = R.string.localai_model_deepseekr17b_desc,
                url = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwen38b_name,
                fileName = "Qwen_Qwen3-8B-Q4_K_M.gguf",
                sizeLabel = "~4.68 GB",
                descResId = R.string.localai_model_qwen38b_desc,
                url = "https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF/resolve/main/Qwen_Qwen3-8B-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_mistralnemo12b_name,
                fileName = "Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
                sizeLabel = "~6.96 GB",
                descResId = R.string.localai_model_mistralnemo12b_desc,
                url = "https://huggingface.co/bartowski/Mistral-Nemo-Instruct-2407-GGUF/resolve/main/Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_gemma312b_name,
                fileName = "google_gemma-3-12b-it-Q4_K_M.gguf",
                sizeLabel = "~6.8 GB",
                descResId = R.string.localai_model_gemma312b_desc,
                url = "https://huggingface.co/bartowski/google_gemma-3-12b-it-GGUF/resolve/main/google_gemma-3-12b-it-Q4_K_M.gguf",
                isLarge = true,
            ),
            CatalogModel(
                nameResId = R.string.localai_model_qwencoder14b_name,
                fileName = "Qwen2.5-Coder-14B-Instruct-Q4_K_M.gguf",
                sizeLabel = "~8.37 GB",
                descResId = R.string.localai_model_qwencoder14b_desc,
                url = "https://huggingface.co/bartowski/Qwen2.5-Coder-14B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-14B-Instruct-Q4_K_M.gguf",
                isLarge = true,
            ),
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var modelsContainer: LinearLayout
    private lateinit var catalogListButton: Button
    private lateinit var catalogProgressLabel: TextView
    private lateinit var catalogProgressBar: ProgressBar

    /**
     * Selector SAF de un .gguf ya descargado en el almacenamiento del dispositivo —
     * mismo patrón que `PluginsFragment.localPackagePicker` (registrado como propiedad
     * de clase, no dentro de un callback de click). `.gguf` no tiene MIME estándar
     * registrado en Android, así que se filtra por el MIME comodín (asterisco-barra-asterisco)
     * y se valida la extensión del nombre elegido (más el magic-bytes check que ya hace
     * LocalModelManager).
     */
    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            confirmImport(uri)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(80)) // padding inferior extra: el FAB no debe tapar el último ítem
        }
        scroll.addView(root, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Título simple, sin ícono de Configuración propio (2026-08-24, ver docs/humano210.md).
        // Corrección explícita del usuario: "en ia local ahi dos tuercas [...] deja todo dentro
        // de la tuerca en la pantalla principal" — este catálogo se abre DESDE la pantalla
        // principal de IA Local (LlamaServerFragment), que ya tiene su propia "⚙" única con
        // TODA la configuración (incluida la de este catálogo, ahora en LlamaServerConfigFragment).
        root.addView(TextView(ctx).apply {
            text = getString(R.string.localai_title)
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })

        // ── Card: tienda de modelos ────────────────────────────────────
        // Texto de ayuda recortado (2026-08-23): antes 2 párrafos completos explicando el
        // catálogo — queda solo el conteo real en el propio botón, que ya comunica lo mismo.
        root.addView(card(ctx, getString(R.string.localai_card_store_title)) {
            catalogListButton = Button(ctx).apply {
                isAllCaps = false
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                setOnClickListener { showCatalogListDialog() }
            }
            addView(catalogListButton)
            catalogProgressLabel = TextView(ctx).apply {
                textSize = 12f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                setPadding(0, dp(10), 0, dp(4))
                visibility = View.GONE
            }
            addView(catalogProgressLabel)
            catalogProgressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = false
                visibility = View.GONE
            }
            addView(catalogProgressBar)
        })

        // ── Card: modelos — filas compactas con ícono/swatch (2026-08-23) ──
        root.addView(card(ctx, getString(R.string.localai_card_downloaded_title)) {
            modelsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(modelsContainer)

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(10) }
                addView(Button(ctx).apply {
                    text = getString(R.string.localai_btn_import)
                    isAllCaps = false
                    setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                    setOnClickListener { pickImportFile() }
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also { it.marginEnd = dp(8) }
                })
                addView(Button(ctx).apply {
                    text = getString(R.string.localai_btn_by_url)
                    isAllCaps = false
                    setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                    setOnClickListener { showAddModelDialog() }
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
            })
        })

        // FrameLayout envolvente propio (esta pantalla no usa fragment_module_detail.xml —
        // no extiende BaseModuleFragment, IA Local no es un módulo instalable — así que arma
        // su propio slot de FAB en vez de reusar BaseModuleFragment.showFab()).
        return FrameLayout(ctx).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(TextView(ctx).apply {
                text = "💬"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(ctx.kairosThemeColor(R.attr.kairosBlue))
                }
                elevation = dp(6).toFloat()
                layoutParams = FrameLayout.LayoutParams(dp(56), dp(56)).also {
                    it.gravity = Gravity.BOTTOM or Gravity.END
                    it.setMargins(0, 0, dp(20), dp(20))
                }
                setOnClickListener { goToChat() }
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshModelList()
        refreshCatalogList()
        // .part huérfanos de una descarga cancelada/matada en una sesión anterior
        // — se limpian al entrar a esta pantalla, no solo al iniciar una descarga
        // nueva (ver LocalModelManager.cleanupOrphanedPartFiles). I/O de disco,
        // corre fuera del hilo principal.
        val ctx = requireContext()
        Thread { LocalModelManager.cleanupOrphanedPartFiles(ctx) }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::modelsContainer.isInitialized) refreshModelList()
        if (::catalogListButton.isInitialized) refreshCatalogList()
    }

    /** Salta al tab de Chat sin duplicar lógica de mensajería acá — ver decisión en el comentario de cabecera. */
    private fun goToChat() {
        val bottomNav = activity?.findViewById<BottomNavigationView>(R.id.bottom_navigation)
        if (bottomNav != null) {
            bottomNav.selectedItemId = R.id.nav_chat
        } else {
            toast(getString(R.string.localai_toast_chat_open_failed))
        }
    }

    private fun refreshModelList() {
        val ctx = context ?: return
        modelsContainer.removeAllViews()
        val models = LocalModelManager.listModels(ctx)
        if (models.isEmpty()) {
            modelsContainer.addView(TextView(ctx).apply {
                text = getString(R.string.localai_no_models_downloaded)
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
            return
        }
        for (model in models) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            row.addView(TextView(ctx).apply {
                text = "🧠"
                textSize = 14f
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                }
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { it.marginEnd = dp(11) }
            })
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = model.name
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                })
                addView(TextView(ctx).apply {
                    text = "%.1f GB".format(model.sizeBytes / (1024.0 * 1024 * 1024))
                    textSize = 11f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                })
            })
            row.addView(TextView(ctx).apply {
                text = getString(R.string.localai_delete)
                textSize = 12f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosRed))
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener {
                    LocalModelManager.deleteModel(ctx, model.name)
                    refreshModelList()
                }
            })
            modelsContainer.addView(row)
        }
    }

    /** Actualiza el texto del botón que abre el diálogo de la Tienda con el conteo de descargados. */
    private fun refreshCatalogList() {
        val ctx = context ?: return
        val downloadedNames = LocalModelManager.listModels(ctx).map { it.name }.toSet()
        val downloadedCount = CATALOG.count { downloadedNames.contains(it.fileName) }
        catalogListButton.text = getString(R.string.localai_catalog_button_label, downloadedCount, CATALOG.size)
    }

    /**
     * Diálogo scrolleable con el catálogo completo — mismo patrón que
     * CliToolFragment.useOllamaLocal() (AlertDialog.Builder().setItems(...), ListView interno,
     * no hace falta un RecyclerView a mano para una lista de 20+ ítems). Marca "✓" los ya
     * descargados. Tocar un modelo no descargado dispara el confirm dialog "¿Desea instalar?"
     * — nunca descarga directo desde acá.
     */
    private fun showCatalogListDialog() {
        val ctx = context ?: return
        val downloadedNames = LocalModelManager.listModels(ctx).map { it.name }.toSet()
        val labels = CATALOG.map { entry ->
            val prefix = if (downloadedNames.contains(entry.fileName)) "✓ " else if (entry.isLarge) "⚠ " else "· "
            "$prefix${getString(entry.nameResId)} — ${entry.sizeLabel}"
        }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.localai_catalog_dialog_title))
            .setItems(labels) { _, index ->
                val entry = CATALOG[index]
                if (downloadedNames.contains(entry.fileName)) {
                    toast(getString(R.string.localai_toast_already_downloaded, getString(entry.nameResId)))
                } else {
                    confirmCatalogInstall(entry)
                }
            }
            .setNegativeButton(getString(R.string.localai_close), null)
            .show()
    }

    /** "¿Desea instalar?" — confirm dialog separado del diálogo de lista, pedido explícito del usuario. */
    private fun confirmCatalogInstall(entry: CatalogModel) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(getString(entry.nameResId))
            .setMessage(getString(R.string.localai_install_confirm_message, getString(entry.descResId), entry.sizeLabel))
            .setPositiveButton(getString(R.string.localai_install)) { _, _ -> downloadCatalogModel(entry) }
            .setNegativeButton(getString(R.string.localai_cancel), null)
            .show()
    }

    /**
     * Descarga un modelo del catálogo mostrando una barra de progreso REAL (determinada, con
     * porcentaje) debajo del botón "Lista de modelos" en la pantalla principal — a diferencia
     * de downloadModel()/ProgressDialogController (usado por "Agregar modelo por URL"), que
     * muestra un diálogo modal con spinner indeterminado. El porcentaje se parsea del mismo
     * mensaje que ya arma LocalModelManager.formatDownloadProgress ("... NN% · ...") sin tocar
     * LocalModelManager (compartido con otros fragments).
     */
    private fun downloadCatalogModel(entry: CatalogModel) {
        val ctx = requireContext()
        val appContext = ctx.applicationContext
        catalogProgressLabel.visibility = View.VISIBLE
        catalogProgressBar.visibility = View.VISIBLE
        catalogProgressBar.isIndeterminate = true
        catalogProgressLabel.text = getString(R.string.localai_starting_download, getString(entry.nameResId))

        // Ya no bloquea (esta pantalla nunca usó un AlertDialog modal para este flujo) — el
        // gap real (docs/humano247.md) era que, si el usuario navegaba a OTRA pantalla
        // mientras un .gguf de varios GB seguía bajando, no había ningún aviso al terminar
        // (el Thread seguía vivo, pero `isAdded` ya era false y los `handler.post` con guard
        // se descartaban en silencio). Ahora se dispara una notificación real en ese caso —
        // mismo mecanismo que ModelsFragment.pullModel()/QemuFragment.downloadDiskImage().
        Thread {
            try {
                LocalModelManager.downloadModel(
                    ctx, entry.url, entry.fileName,
                    estimatedSizeBytes = LocalModelManager.parseSizeLabel(entry.sizeLabel),
                ) { p ->
                    handler.post { if (isAdded) updateCatalogProgress(p) }
                }
                if (!isAdded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, appContext.getString(entry.nameResId), "install_done", appContext.getString(R.string.localai_notify_model_downloaded)
                    )
                }
                handler.post {
                    if (!isAdded) return@post
                    catalogProgressBar.visibility = View.GONE
                    catalogProgressLabel.visibility = View.GONE
                    refreshModelList()
                    refreshCatalogList()
                    toast(getString(R.string.localai_toast_model_downloaded, getString(entry.nameResId)))
                }
            } catch (e: Exception) {
                if (!isAdded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, appContext.getString(entry.nameResId), "install_failed", e.message ?: appContext.getString(R.string.localai_unknown_error)
                    )
                }
                handler.post {
                    if (!isAdded) return@post
                    catalogProgressBar.visibility = View.GONE
                    catalogProgressLabel.visibility = View.GONE
                    toast(getString(R.string.localai_toast_download_error, getString(entry.nameResId), e.message ?: getString(R.string.localai_unknown_error)))
                }
            }
        }.start()
    }

    /** Extrae el "NN%" del mensaje de progreso para mover la barra real; sin porcentaje (ej. "Conectando…") queda indeterminada. */
    private fun updateCatalogProgress(message: String) {
        catalogProgressLabel.text = message
        val percent = Regex("(\\d{1,3})%").find(message)?.groupValues?.get(1)?.toIntOrNull()
        if (percent != null) {
            catalogProgressBar.isIndeterminate = false
            catalogProgressBar.progress = percent.coerceIn(0, 100)
        } else {
            catalogProgressBar.isIndeterminate = true
        }
    }

    /** Dispara el selector SAF — MIME genérico porque `.gguf` no tiene tipo MIME estándar en Android. */
    private fun pickImportFile() {
        try {
            importPicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            toast(getString(R.string.localai_toast_picker_error, e.message))
        }
    }

    /** Nombre sugerido para el archivo importado — a partir del display name real del Uri elegido (SAF), si Android lo expone. */
    private fun displayNameOf(uri: Uri): String? {
        val ctx = context ?: return null
        return ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    /** Confirma el nombre final (con extensión .gguf) antes de copiar — mismo criterio que showAddModelDialog, nunca se importa sin que el usuario vea/edite el nombre. */
    private fun confirmImport(uri: Uri) {
        val ctx = requireContext()
        val suggested = displayNameOf(uri)?.let { name ->
            if (name.endsWith(".gguf", ignoreCase = true)) name else "$name.gguf"
        } ?: "modelo-importado.gguf"

        val nameInput = EditText(ctx).apply {
            hint = getString(R.string.localai_add_model_name_hint)
            setText(suggested)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
            addView(nameInput)
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.localai_import_dialog_title))
            .setMessage(getString(R.string.localai_import_dialog_message))
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.localai_import)) { _, _ ->
                var name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.localai_toast_name_required))
                    return@setPositiveButton
                }
                if (!name.endsWith(".gguf", ignoreCase = true)) name = "$name.gguf"
                importModel(uri, name)
            }
            .setNegativeButton(getString(R.string.localai_cancel), null)
            .show()
    }

    private fun importModel(uri: Uri, name: String) {
        val ctx = requireContext()
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        progress.show(getString(R.string.localai_import_progress_title), getString(R.string.localai_import_progress_message))

        Thread {
            try {
                LocalModelManager.importFromUri(ctx, uri, name)
                handler.post {
                    if (!isAdded) return@post
                    refreshModelList()
                    if (::catalogListButton.isInitialized) refreshCatalogList()
                    progress.success(getString(R.string.localai_toast_model_imported, name))
                }
            } catch (e: Exception) {
                handler.post {
                    if (!isAdded) return@post
                    progress.failure(getString(R.string.localai_import_error_title), e.message ?: getString(R.string.localai_unknown_error))
                }
            }
        }.start()
    }

    private fun showAddModelDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
        }
        val urlInput = EditText(ctx).apply { hint = getString(R.string.localai_add_model_url_hint) }
        val nameInput = EditText(ctx).apply { hint = getString(R.string.localai_add_model_name_hint) }
        layout.addView(urlInput)
        layout.addView(nameInput)

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.localai_add_model_dialog_title))
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.localai_download)) { _, _ ->
                val url = urlInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                if (url.isEmpty() || name.isEmpty()) {
                    toast(getString(R.string.localai_toast_url_name_required))
                    return@setPositiveButton
                }
                downloadModel(url, name)
            }
            .setNegativeButton(getString(R.string.localai_cancel), null)
            .show()
    }

    private fun downloadModel(url: String, name: String) {
        val ctx = requireContext()
        val appContext = ctx.applicationContext
        val progress = com.termux.app.util.ProgressDialogController(ctx)
        // allowBackground=true (docs/humano247.md): "Agregar modelo por URL" no tiene tamaño
        // curado como el catálogo, pero puede ser igual de pesado — mismo tratamiento que
        // downloadCatalogModel()/ModelsFragment.pullModel(): se puede mandar a 2do plano y se
        // avisa por notificación al terminar.
        progress.show(getString(R.string.localai_download_dialog_title), getString(R.string.localai_download_starting), allowBackground = true)

        Thread {
            try {
                LocalModelManager.downloadModel(ctx, url, name) { p ->
                    // LocalModelManager.downloadModel() todavía formatea un String único (lo
                    // comparte con downloadCatalogModel(), que ya parsea "NN%" del mismo
                    // formato) — se reusa el mismo parseo acá en vez de duplicar la lógica de
                    // formatDownloadProgress() en dos sitios.
                    val pct = Regex("(\\d{1,3})%").find(p)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    handler.post { if (isAdded) progress.updateProgress(pct, p) }
                }
                if (progress.isBackgrounded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, name, "install_done", appContext.getString(R.string.localai_notify_model_downloaded)
                    )
                }
                handler.post {
                    if (!isAdded) return@post
                    refreshModelList()
                    if (::catalogListButton.isInitialized) refreshCatalogList()
                    progress.success(getString(R.string.localai_toast_model_downloaded, name))
                }
            } catch (e: Exception) {
                if (progress.isBackgrounded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, name, "install_failed", e.message ?: appContext.getString(R.string.localai_unknown_error)
                    )
                }
                handler.post {
                    if (!isAdded) return@post
                    progress.failure(getString(R.string.localai_download_error_title), e.message ?: getString(R.string.localai_unknown_error))
                }
            }
        }.start()
    }

    private fun card(ctx: Context, title: String, content: LinearLayout.() -> Unit): MaterialCardView {
        val card = MaterialCardView(ctx).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(12) }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        inner.addView(TextView(ctx).apply {
            text = title
            textSize = 11f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, 0, 0, dp(8))
        })
        inner.content()
        card.addView(inner)
        return card
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(d: Int) = (d * resources.displayMetrics.density).toInt()
}
