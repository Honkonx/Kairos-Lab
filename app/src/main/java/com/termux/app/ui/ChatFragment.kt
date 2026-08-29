package com.termux.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Gravity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.util.ChatHistoryStore
import com.termux.app.util.LlmErrorMapper
import com.termux.app.util.LocalModelManager
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.SecureChatPrefs
import com.termux.app.util.SystemPrompts
import com.termux.app.util.TERMUX_CACTUS_PATH
import com.termux.llm.GpuBackend
import com.termux.llm.LlamaEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.termux.app.util.kairosThemeColor

/**
 * El motor LOCAL (llama.cpp embebido vía LlamaEngine, ver docs/ia-local/LLAMA_CPP_EMBEBIDO.md
 * sección "Estado real") sigue sin soporte de imágenes a propósito — necesitaría un
 * mmproj/vision projector aparte que ningún flujo de descarga de Kairos maneja todavía.
 * Ollama SÍ soporta imágenes (campo "images" en /api/generate, base64 sin el prefijo
 * "data:image/...") para modelos multimodales — es una API completamente distinta al
 * motor local, así que el botón de adjuntar (ver mBtnAttach) solo se habilita cuando
 * `!isLocalModel(mSelectedModel)`. Ver también el aviso dinámico en fragment_chat.xml y
 * updateAttachButtonState().
 */
class ChatFragment : Fragment() {

    companion object {
        private const val OLLAMA_URL = "http://127.0.0.1:11434"
        // 2026-08-11 (humano97 punto 3, fusión llama-server + IA Local): el chat usa el motor
        // embebido (JNI, mismo proceso) si puede cargarlo; si NO (fallback HTTP) habla con el
        // servidor llama-server (módulo llamaserver, puerto 8085, OpenAI-compatible /v1) — ver
        // makeLlamaServerRequest. Mismo puerto/contrato que modulos/llamaserver.sh.
        private const val LLAMASERVER_URL = "http://127.0.0.1:8085"
        // Índice del modelo cargado en el servidor llama-server (OpenAI-compatible). El fallback
        // HTTP no baja el modelo: el server ya lo tiene cargado (llamaserver.sh lo arranca con
        // LLAMA_SERVER_MODEL desde ~/.llamaserver_user_config).
        private const val LLAMASERVER_MODEL = "local"
        // Pedido explícito del usuario (repetido, "te dije"): llama.cpp y Ollama NO deben
        // compartir un selector de modelo mezclado — al entrar a "Chat IA" hay que elegir el
        // motor ANTES de ver el chat. Ver mEngine/showEngineSelector()/selectEngine().
        private const val ENGINE_OLLAMA = "ollama"
        private const val ENGINE_LOCAL = "local"
        // BYO API key (2026-08-15, ronda humano126): motores cloud con clave propia del
        // usuario — el selector de motor gana una tercera opción "Cloud API" que despliega
        // los 5 proveedores. La clave ("cloud_api_key_<id>") se guarda cifrada vía
        // SecureChatPrefs (EncryptedSharedPreferences, ver util/SecureChatPrefs.kt); el
        // proveedor activo ("cloud_provider", no sensible) sigue en kairos_llm_prefs plano.
        // Corregido 2026-08-19 tras docs/arquitectura/AUDITORIA_IA_CODIGO_2026-08-19.md hallazgo
        // 2.2 (guardaba la key en texto plano, inconsistente con AiProviderPrefs de Estudio).
        // Mismo patrón de proveedores que la referencia android-code-studio (artificial/agents/,
        // GPL-3.0 — solo el patrón).
        private const val ENGINE_CLOUD = "cloud"
        private const val CLOUD_PROVIDER_KEY = "cloud_provider"
        private fun cloudApiKeyKey(provider: String) = "cloud_api_key_$provider"
        private val CLOUD_PROVIDERS = listOf("gemini", "deepseek", "openai", "anthropic", "grok")
        // Prefijo del modo shell (2026-08-15, ronda humano126, hallazgo #7 whispercode):
        // un mensaje que empieza con "!" se ejecuta como comando shell real y su salida se
        // muestra en el chat como mensaje de sistema — sin pasar por ningún motor de IA.
        private const val SHELL_COMMAND_PREFIX = "!"
        // Internet para modelos (2026-08-15, ronda humano126): la Web Search API de Ollama
        // (https://ollama.com/api/web_search, header "Authorization: Bearer $OLLAMA_API_KEY",
        // ver docs.ollama.com/capabilities/web-search) se usa como servicio de búsqueda
        // compartido para los motores locales (ollama Y llama-server) — los resultados se
        // inyectan como contexto en el prompt. La key se pide en ollama.com/settings/keys y se
        // persiste cifrada vía SecureChatPrefs ("ollama_web_api_key"); el toggle, no sensible,
        // sigue en kairos_llm_prefs plano ("web_search_enabled").
        private const val OLLAMA_WEB_SEARCH_URL = "https://ollama.com/api/web_search"
        private const val WEB_SEARCH_ENABLED_KEY = "web_search_enabled"
        private const val OLLAMA_WEB_API_KEY_KEY = "ollama_web_api_key"
        private const val WEB_SEARCH_MAX_RESULTS = 5
        private const val WEB_SEARCH_TIMEOUT_MS = 10000
        // Fallback SOLO para el primer render, antes de que checkOllamaStatus() termine de
        // consultar los modelos reales — bug real confirmado (reporte del usuario, 2026-07-31,
        // ver docs/humano/humano33.md): esta lista se usaba como si fueran los modelos disponibles
        // de verdad, pero son solo nombres de ejemplo — si el usuario nunca hizo `ollama pull`
        // de ninguno de estos, CADA mensaje fallaba con el error real de Ollama (404 "model
        // not found", a veces 400 según la versión). Ver mOllamaModels, poblado con
        // OllamaApiClient.listModels() (los modelos que el usuario realmente descargó).
        private val MODELS = arrayOf(
            "qwen2.5:0.5b", "qwen2.5:1.5b", "qwen3:4b",
            "gemma3:4b", "gemma3:1b", "deepseek-coder:1.3b-instruct", "llama3.2:1b"
        )
        // Cuántos turnos previos (usuario+asistente) se re-envían como contexto
        // en cada request a Ollama (/api/generate es sin estado — a diferencia
        // del motor local, que mantiene su propia conversación con KV-cache
        // incremental, ver LlamaEngine.kt). Antes esto era CERO — cada mensaje
        // se mandaba sin ningún historial, hallazgo real de la auditoría de
        // docs/referencias/LLM_PROYECTOS_FUNCIONALIDADES.md.
        private const val MAX_CONTEXT_TURNS = 6

        // C6 (humano123, "resume lifecycle streaming"): política del ciclo de vida del
        // streaming HTTP. Antes el loop de lectura era un readLine() pelado hasta EOF o
        // [DONE] con un catch genérico — tres fallas reales (ver auditoría de C6 en
        // docs/humano/humano123.md):
        //   1) Sin watchdog: si el servidor moría a mitad de un stream (proceso muerto,
        //      sleep del SoC, pico de CPU) la lectura quedaba bloqueada hasta el
        //      readTimeout y el error era un "Error de conexion" genérico que no
        //      distinguía un stream colgado de una red caída.
        //   2) Sin feedback de vida: con modelos lentos la barra "procesando..." quedaba
        //      congelada sin indicar si seguía generando o se colgó.
        //   3) Sin reconnect: una caída de red justo al iniciar mataba el mensaje
        //      completo aunque el servidor siguiera vivo.
        // STREAM_READ_TIMEOUT (readTimeout de la JVM) es la red de seguridad para la
        // espera del PRIMER token (el watchdog no aplica stall hasta que el stream arrancó,
        // para no matar modelos que tardan en el primer token). STREAM_STALL_TIMEOUT: si el
        // stream YA empezó y no llegan datos en 25s → stream stale → disconnect() aborta la
        // lectura bloqueada de verdad (mismo mecanismo que cancelRequest). STREAM_MAX_ATTEMPTS:
        // reintento único si la conexión cae ANTES de recibir tokens (reconnect seguro — no
        // duplica texto porque aún no llegó nada al adapter).
        private const val STREAM_READ_TIMEOUT_MS = 30000
        private const val STREAM_STALL_TIMEOUT_MS = 25000L
        private const val STREAM_MAX_ATTEMPTS = 2

        // Cuántos mensajes (usuario+asistente, cuenta ambos) se retienen en memoria y en
        // disco — configurable desde showQuickSettingsDialog(), persistido en las mismas
        // SharedPreferences "kairos_llm_prefs". Default alineado con OL_DISK_MSGS=50 de
        // menu_nativo.sh's _ollama_chat_texto (mismo criterio de retención que ya probó
        // el proyecto original). Hallazgo real de auditoría: antes no existía ningún
        // límite — el JSON de ChatHistoryStore crecía sin techo.
        private const val DEFAULT_HISTORY_LIMIT = 50
        private const val MIN_HISTORY_LIMIT = 10
        private const val MAX_HISTORY_LIMIT = 500
        // -1 = sin límite — preset "∞" agregado en OllamaConfigFragment ("Historial de
        // chat", paridad con el preset "[9] ∞" / OL_DISK_MSGS=9999 de _ollama_config_sql()
        // en menu_nativo.sh). Ver enforceHistoryLimit().
        private const val HISTORY_LIMIT_UNLIMITED = -1

        // Lado mayor máximo (px) de una imagen adjunta antes de codificarla a base64 — sin
        // esto, una foto de cámara moderna (12+ MP) infla el JSON de la request (y lo que
        // persiste ChatHistoryStore) con varios MB por mensaje. Mismo criterio de resize
        // que aplicaba menu_nativo.sh's _ollama_chat_vision (helper Python con Pillow).
        private const val MAX_IMAGE_DIMENSION_PX = 1024

        // Heurística de nombre para modelos de visión conocidos — mismo patrón (mismos
        // nombres de familia) que _ollama_list_vision_models() en menu_nativo.sh. No es
        // exhaustiva a propósito: el usuario puede tener un modelo de visión con un
        // nombre que no reconozcamos, por eso esto solo dispara un aviso (ver
        // confirmSendWithUnknownVisionModel), nunca bloquea el envío.
        private val VISION_MODEL_HINT_REGEX = Regex(
            "llava|moondream|bakllava|llama3\\.2-vision|minicpm-v|qwen.*vl|gemma.*vision",
            RegexOption.IGNORE_CASE
        )

        // MVP (pedido 2026-08-13, ver docs/humano/humano115.md): trigger manual para que el
        // chat ejecute tools de cactus-needle (bash/python/engram) SIN pasar por ningún motor
        // de IA — el usuario pide la ejecución explícitamente con este prefijo, la IA no decide
        // sola cuándo correr comandos (eso es tool-calling completo, fuera de alcance acá).
        private const val RUN_COMMAND_PREFIX = "/run"
        // Pedido explícito del usuario (ver docs/humano/humano118.md): "cactus debe ser con
        // y sin ia" — /run corregido de vuelta a needle SIN IA (comportamiento original),
        // /ai es el nuevo prefijo que sí pasa por el razonador (Ollama/llama-server).
        private const val AI_RUN_COMMAND_PREFIX = "/ai"
        private const val CACTUS_RUN_TIMEOUT_SECONDS = 60L

        // Composer docks (adaptación pragmática, hallazgo #4 de AUDITORIA_CATEGORIA_AGENTES.md):
        // marker de permiso que detecta checkPermissionMarker() en el stream + clave de
        // autoaceptación persistida en kairos_llm_prefs ("permitir siempre"). Ollama/llama-server
        // no emiten "permission.asked" nativo — ver checkPermissionMarker().
        private const val TOOL_PERMISSION_MARKER = "[PERMISO]"
        private const val TOOL_AUTOACCEPT_KEY = "tool_autoaccept"
    }

    private lateinit var mRecycler: RecyclerView
    private lateinit var mAdapter: ChatAdapter
    private val mMessages = ArrayList<ChatMessage>()

    private lateinit var mInput: EditText
    private lateinit var mBtnSend: View
    private lateinit var mBtnClear: View
    private lateinit var mBtnSettings: View
    private lateinit var mErrorBar: View
    private lateinit var mCancelBar: View
    private lateinit var mOfflineOverlay: View
    private lateinit var mErrorText: TextView
    private lateinit var mErrorDismiss: TextView
    private lateinit var mErrorToggleDetail: TextView
    private lateinit var mErrorDetail: TextView
    private lateinit var mCancelText: TextView
    private lateinit var mStatusText: TextView
    private lateinit var mModelSelector: TextView
    private lateinit var mMessageCount: TextView
    private lateinit var mPersonaLabel: TextView
    private lateinit var mStatusDot: View
    private lateinit var mCancelSpinner: ProgressBar
    private lateinit var mChatContent: View
    private lateinit var mBtnAttach: View
    private lateinit var mImagePreviewRow: View
    private lateinit var mImagePreviewThumb: ImageView
    private lateinit var mImagePreviewRemove: View
    private lateinit var mBtnSwitchEngine: View
    private lateinit var mEngineSelectorView: View
    private lateinit var mEngineOllamaSubtitle: TextView
    private lateinit var mEngineLocalSubtitle: TextView
    private lateinit var mEngineCloudSubtitle: TextView
    private lateinit var mBtnMic: View

    // Debe registrarse como campo de instancia (no dentro de onViewCreated/onClick) —
    // requisito del ciclo de vida de ActivityResultLauncher, el mismo patrón que ya usa
    // WizardPermissionsFragment.kt en este proyecto.
    private val mPickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { handlePickedImage(it) }
        }

    // Entrada de voz (ver docs/referencias/REFERENCIA_NEWTERMUX.md) — mismo criterio de campo de
    // instancia que mPickImageLauncher, RECORD_AUDIO se pide recién al tocar el micrófono
    // (no al abrir el chat), y solo arranca SpeechInputManager si el usuario lo concede acá.
    private val mRecordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else toast(getString(R.string.chat_mic_permission_denied))
        }
    private var mSpeechManager: com.termux.app.util.SpeechInputManager? = null

    private var mAttachedImageBase64: String? = null

    private var mSelectedModel = MODELS[0]
    // Modelos REALES pulled en Ollama (ver checkOllamaStatus/refreshOllamaModels) — vacío
    // hasta que la consulta HTTP en background termine. showModelMenu() usa esta lista en
    // vez de MODELS apenas está disponible; MODELS queda solo como fallback pre-fetch.
    private val mOllamaModels = mutableListOf<String>()
    // Motor elegido en la pantalla de selección (ENGINE_OLLAMA/ENGINE_LOCAL) — null mientras
    // se muestra esa pantalla. Filtra qué modelos ofrece showModelMenu(): con un motor
    // elegido, NUNCA se mezclan modelos del otro (antes .gguf y modelos de Ollama convivían
    // en el mismo selector dentro del chat).
    private var mEngine: String? = null
    private var mLoading = false
    private val mCancelled = AtomicBoolean(false)
    private var mRequestThread: Thread? = null
    private val mMainHandler = Handler(Looper.getMainLooper())

    // Bug real (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §1.9): Thread.interrupt() (ver cancelRequest()) no desbloquea una lectura ya bloqueada
    // en HttpURLConnection/Socket (reader.readLine()) — mCancelled solo se revisa ENTRE líneas
    // ya recibidas. Guardar la conexión activa acá permite que cancelRequest() llame
    // .disconnect() sobre ella, que sí corta una lectura bloqueada de verdad (lanza
    // IOException en el reader, capturada por el catch de makeOllamaRequest/
    // makeLlamaServerRequest). @Volatile: se escribe desde el hilo de la request y se lee/
    // limpia desde el hilo de UI (cancelRequest()).
    @Volatile
    private var mActiveConnection: HttpURLConnection? = null

    // Motor local (llama.cpp embebido, ver docs/ia-local/LLAMA_CPP_EMBEBIDO.md) — se
    // carga perezosamente la primera vez que se elige un modelo .gguf, y se
    // mantiene cargado entre mensajes (recargar en cada turno perdería el
    // KV-cache incremental). Se descarta si el usuario cambia a otro modelo
    // local distinto o sale del fragment.
    private var mLocalEngine: LlamaEngine? = null
    private var mLocalEngineModelName: String? = null

    // Parser de <think>...</think> del mensaje del assistant en curso — se
    // resetea en cada sendMessage() nuevo. Aplica tanto al motor local como a
    // Ollama: cualquier modelo (razonador o no) puede en teoría emitir esa
    // etiqueta si su chat template la define, ver ThinkStreamParser.
    private var mThinkParser = ThinkStreamParser()

    // Follow-up queue (Composer docks, ver sendMessage()/drainPendingQueue()): texto de los
    // mensajes encolados mientras el agente trabaja (isWorking == true). Se toca SOLO desde
    // el hilo de UI (sendMessage y finishLoading corren por mMainHandler). La fila de UI
    // ("⏳ N en cola") se construye en código (ver buildQueueRow/updateQueueIndicator).
    private val mPendingQueue = mutableListOf<String>()
    private lateinit var mQueueRow: LinearLayout
    private lateinit var mQueueText: TextView
    // ¿Hay un request de IA en curso? Alias legible de mLoading (misma fuente de verdad).
    private val isWorking: Boolean get() = mLoading
    // Assistant ids que ya recibieron el prompt de permiso (ver checkPermissionMarker()) —
    // un mismo mensaje nunca pregunta dos veces aunque el marker llegue partido en chunks.
    private val mPermissionPrompted = mutableSetOf<String>()

    private fun isLocalModel(name: String) = name.endsWith(".gguf", ignoreCase = true)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mRecycler = view.findViewById(R.id.recycler_chat)
        mRecycler.layoutManager = LinearLayoutManager(requireContext())
        mAdapter = ChatAdapter(mMessages)
        mRecycler.adapter = mAdapter

        mInput = view.findViewById(R.id.chat_input)
        mBtnSend = view.findViewById(R.id.btn_send)
        mBtnClear = view.findViewById(R.id.btn_clear)
        mBtnSettings = view.findViewById(R.id.btn_chat_settings)
        mErrorBar = view.findViewById(R.id.error_bar)
        mErrorText = view.findViewById(R.id.error_text)
        mErrorDismiss = view.findViewById(R.id.error_dismiss)
        mErrorToggleDetail = view.findViewById(R.id.error_toggle_detail)
        mErrorDetail = view.findViewById(R.id.error_detail)
        mCancelBar = view.findViewById(R.id.cancel_bar)
        mCancelText = view.findViewById(R.id.cancel_text)
        mCancelSpinner = view.findViewById(R.id.cancel_spinner)
        mStatusText = view.findViewById(R.id.status_text)
        mStatusDot = view.findViewById(R.id.status_dot)
        mModelSelector = view.findViewById(R.id.model_selector)
        mMessageCount = view.findViewById(R.id.message_count)
        mOfflineOverlay = view.findViewById(R.id.offline_overlay)
        mChatContent = view.findViewById(R.id.chat_content)
        mBtnAttach = view.findViewById(R.id.btn_attach)
        mImagePreviewRow = view.findViewById(R.id.image_preview_row)
        mImagePreviewThumb = view.findViewById(R.id.image_preview_thumb)
        mImagePreviewRemove = view.findViewById(R.id.image_preview_remove)
        mBtnSwitchEngine = view.findViewById(R.id.btn_switch_engine)
        mBtnSwitchEngine.setOnClickListener { showEngineSelector() }
        view.findViewById<View>(R.id.offline_switch_engine).setOnClickListener { showEngineSelector() }

        mBtnMic = view.findViewById(R.id.btn_mic)
        mBtnMic.setOnClickListener { onMicClicked() }
        if (!com.termux.app.util.SpeechInputManager.isAvailable(requireContext())) {
            // Mismo criterio que mBtnAttach cuando el motor local no soporta imágenes: se
            // atenúa en vez de ocultarse, para que el usuario vea que la opción existe pero
            // no está disponible en este dispositivo.
            mBtnMic.alpha = 0.35f
            mBtnMic.isEnabled = false
        }

        mEngineSelectorView = buildEngineSelectorView()
        (view as ViewGroup).addView(mEngineSelectorView)

        mBtnSend.setOnClickListener { sendMessage() }
        mBtnClear.setOnClickListener { clearHistory() }
        mBtnSettings.setOnClickListener { showQuickSettingsDialog() }
        mBtnAttach.setOnClickListener { onAttachClicked() }
        mImagePreviewRemove.setOnClickListener { clearAttachedImage() }
        mErrorDismiss.setOnClickListener { mErrorBar.visibility = View.GONE }
        mErrorToggleDetail.setOnClickListener {
            val expanding = mErrorDetail.visibility != View.VISIBLE
            mErrorDetail.visibility = if (expanding) View.VISIBLE else View.GONE
            mErrorToggleDetail.text = if (expanding) getString(R.string.chat_toggle_hide_details) else getString(R.string.chat_toggle_show_details)
        }
        mCancelText.setOnClickListener { cancelRequest() }

        mModelSelector.text = mSelectedModel
        mModelSelector.setOnClickListener { showModelMenu() }

        // Selector de persona (presets de system prompt, ver util/SystemPrompts.kt) — se
        // agrega en código sobre la model_bar porque fragment_chat.xml no lo declara y el XML
        // está fuera de alcance esta ronda; mismo patrón de UI programática que ya usa
        // buildEngineSelectorView(). Muestra el nombre de la persona activa y abre un diálogo
        // de selección (radio list) persistido en kairos_llm_prefs vía SystemPrompts.save().
        mPersonaLabel = TextView(requireContext()).apply {
            text = getString(R.string.chat_persona_label_format, SystemPrompts.current(requireContext()).name)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosGreen))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.leftMargin = dp(12) }
            setOnClickListener { showPersonaSelector() }
        }
        view.findViewById<LinearLayout>(R.id.model_bar).addView(mPersonaLabel)

        // Toggle "Web" (internet para modelos, 2026-08-15 ronda humano126): activa/desactiva
        // la inyección de resultados de la Web Search API de Ollama en los motores locales.
        // Al activarlo sin key guardada pide la clave (showWebSearchKeyDialog).
        val webToggle = TextView(requireContext()).apply {
            val initiallyEnabled = cloudPrefs().getBoolean(WEB_SEARCH_ENABLED_KEY, false)
            text = if (initiallyEnabled) getString(R.string.chat_web_on) else getString(R.string.chat_web_off)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(
                requireContext().kairosThemeColor(
                    if (initiallyEnabled) R.attr.kairosGreen else R.attr.kairosText3
                )
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.leftMargin = dp(12) }
            setOnClickListener {
                val prefs = cloudPrefs()
                val enabled = prefs.getBoolean(WEB_SEARCH_ENABLED_KEY, false)
                val hasKey = securePrefs().getSecret(OLLAMA_WEB_API_KEY_KEY).trim().length >= 20
                if (!enabled && !hasKey) {
                    showWebSearchKeyDialog()
                } else {
                    prefs.edit().putBoolean(WEB_SEARCH_ENABLED_KEY, !enabled).apply()
                    text = if (!enabled) getString(R.string.chat_web_on) else getString(R.string.chat_web_off)
                    setTextColor(
                        requireContext().kairosThemeColor(
                            if (!enabled) R.attr.kairosGreen else R.attr.kairosText3
                        )
                    )
                    toast(if (!enabled) getString(R.string.chat_web_enabled_toast) else getString(R.string.chat_web_disabled_toast))
                }
            }
        }
        view.findViewById<LinearLayout>(R.id.model_bar).addView(webToggle)

        // Follow-up queue (Composer docks, ver mPendingQueue) — fila "⏳ N en cola" debajo del
        // input. Se agrega en código porque fragment_chat.xml no la declara (mismo patrón que
        // mPersonaLabel/buildEngineSelectorView()); el contenedor es el footer vertical que
        // envuelve la input_row (chat_input → input_row → footer).
        mQueueRow = buildQueueRow()
        val footer = (mInput.parent as? View)?.parent as? LinearLayout
        val inputRow = mInput.parent as? View
        if (footer != null) {
            footer.addView(mQueueRow, if (inputRow != null) footer.indexOfChild(inputRow) + 1 else footer.childCount)
        }
        updateQueueIndicator()

        mInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) { updateSendButton() }
        })

        loadPersistedHistory()
        updateSendButton()
        updateAttachButtonState()
        showEngineSelector()
    }

    /**
     * TermuxActivity mantiene este Fragment vivo (add+hide/show, nunca lo destruye — ver
     * TermuxActivity.initializeAppUi) para no perder el chat en curso al cambiar de tab, así
     * que `onViewCreated` solo corre una vez en la vida de la sesión. Sin este override, la
     * pantalla de elegir motor solo aparecería la PRIMERA vez que se abre el tab — el pedido
     * del usuario es "antes de entrar se decide cuál se va a usar" cada vez que se vuelve al
     * tab, no solo la primera. `hidden == false` es exactamente el momento en que
     * BottomNavigationView vuelve a mostrar este Fragment.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded && ::mEngineSelectorView.isInitialized) {
            showEngineSelector()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Bug real (auditoría IA 2026-08-19, docs/arquitectura/AUDITORIA_IA_CODIGO_2026-08-19.md):
        // si onDestroyView() corre mientras mRequestThread sigue generando con el motor local
        // (makeLocalRequest mantiene su propia referencia a mLocalEngine, mismo objeto JNI),
        // mLocalEngine?.close() de abajo podía cerrar el engine nativo a mitad de una llamada
        // streamResponse()/stop() en el hilo de red — use-after-free potencial en la capa JNI.
        // Mismo mecanismo de cancelación que cancelRequest() (sin el diálogo de
        // confirmDiscardQueue, que no corresponde acá): esto hace que el callback de
        // streamResponse vea mCancelled=true y llame engine.stop() él mismo ANTES de que este
        // método cierre el engine, en vez de que ambos hilos toquen el mismo objeto nativo sin
        // coordinación.
        mCancelled.set(true)
        mActiveConnection?.disconnect()
        mRequestThread?.apply { if (isAlive) interrupt() }
        // Libera el modelo nativo (memoria potencialmente grande, GBs) al salir
        // de la pantalla — se vuelve a cargar perezosamente si el usuario
        // vuelve y manda otro mensaje con un modelo local.
        mLocalEngine?.close()
        mLocalEngine = null
        mLocalEngineModelName = null
        mSpeechManager?.destroy()
        mSpeechManager = null
        persistHistory()
    }

    /**
     * Carga el historial guardado (ver ChatHistoryStore) al abrir la pantalla.
     *
     * Bug real reportado: "al salir y entrar se duplicaba el mensaje" — `mMessages` es un
     * campo de instancia del Fragment, que sobrevive a ciclos de `onCreateView`/`onDestroyView`
     * cuando la navegación de la app oculta/reutiliza el Fragment en vez de destruirlo del
     * todo (patrón común con BottomNavigationView). `onDestroyView()` ya persiste antes de
     * salir, así que en cada `onViewCreated()` nuevo lo que hay en disco es siempre la verdad
     * completa — antes esto hacía `addAll()` sin vaciar primero, así que si `mMessages` ya
     * tenía contenido de un ciclo anterior (por no haberse destruido la instancia del
     * Fragment), los mensajes se duplicaban. Ahora `mMessages` siempre queda como un espejo
     * exacto del disco al empezar cada ciclo.
     */
    private fun loadPersistedHistory() {
        val stored = ChatHistoryStore.load(requireContext())
        mMessages.clear()
        if (stored.isNotEmpty()) {
            mMessages.addAll(stored.map {
                ChatMessage(it.id, it.role, it.content, it.ts, it.model, imageBase64 = it.imageBase64)
            })
        }
        // El límite pudo bajar mientras la app estaba cerrada (ajuste hecho en otra sesión) —
        // se aplica también acá, no solo tras cada mensaje nuevo, para que el disco nunca
        // quede con más mensajes de los que el usuario configuró.
        enforceHistoryLimit()
        mAdapter.notifyDataSetChanged()
        scrollToBottom()
    }

    /** Guarda el historial completo a disco — se llama tras cada mensaje terminado (finishLoading), no en cada chunk de streaming, para no golpear el disco en cada token. */
    private fun persistHistory() {
        val ctx = context ?: return
        val snapshot = mMessages.map {
            ChatHistoryStore.StoredMessage(it.id, it.role, it.content, it.ts, it.model, it.imageBase64)
        }
        ChatHistoryStore.save(ctx, snapshot)
    }

    /**
     * Recorta `mMessages` (memoria) a los últimos N mensajes configurados en
     * "kairos_llm_prefs"/"history_limit" — se llama tras cada turno completo (ver
     * finishLoading) y al mover el slider en showQuickSettingsDialog(), nunca a mitad de
     * un streaming (cortar un mensaje que se está generando lo dejaría a medias). Los
     * mensajes descartados no se re-persisten: persistHistory() siempre escribe el estado
     * actual de mMessages, así que lo que se recorta acá desaparece también de disco en el
     * próximo persistHistory().
     */
    private fun enforceHistoryLimit() {
        val ctx = context ?: return
        val stored = ctx.getSharedPreferences("kairos_llm_prefs", 0)
            .getInt("history_limit", DEFAULT_HISTORY_LIMIT)
        // Preset "∞" (ver OllamaConfigFragment) — sin límite, no se recorta ni memoria ni disco.
        if (stored == HISTORY_LIMIT_UNLIMITED) return
        val limit = stored.coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)
        if (mMessages.size <= limit) return
        val excess = mMessages.size - limit
        repeat(excess) { mMessages.removeAt(0) }
        if (::mAdapter.isInitialized) mAdapter.notifyDataSetChanged()
    }

    /**
     * Pantalla de elegir motor (Ollama Termux vs. llama.cpp local) — pedido explícito del
     * usuario, repetido más de una vez en esta sesión: "toca separarlo de ollama no deben
     * ser igual... deb salir dos opciones... así antes de entrar se decide cuál se va a
     * usar". Se muestra sobre TODO lo demás (chat_content + offline_overlay quedan debajo,
     * ocultos) hasta que el usuario toca una de las 2 opciones — ver selectEngine().
     */
    private fun showEngineSelector() {
        mEngine = null
        mChatContent.visibility = View.GONE
        mOfflineOverlay.visibility = View.GONE
        mEngineSelectorView.visibility = View.VISIBLE
        refreshEngineSelectorState()
    }

    private fun hideEngineSelector() {
        mEngineSelectorView.visibility = View.GONE
    }

    private fun buildEngineSelectorView(): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
            setPadding(dp(28), dp(28), dp(28), dp(28))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }
        root.addView(TextView(ctx).apply {
            text = getString(R.string.chat_engine_selector_title)
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.chat_engine_selector_subtitle)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(6), 0, dp(24))
        })
        mEngineOllamaSubtitle = TextView(ctx)
        mEngineLocalSubtitle = TextView(ctx)
        mEngineCloudSubtitle = TextView(ctx)
        root.addView(engineOptionCard(ctx, getString(R.string.chat_engine_ollama_title), mEngineOllamaSubtitle) { onEngineCardClicked(ENGINE_OLLAMA) })
        root.addView(engineOptionCard(ctx, getString(R.string.chat_engine_local_title), mEngineLocalSubtitle) { onEngineCardClicked(ENGINE_LOCAL) })
        root.addView(engineOptionCard(ctx, getString(R.string.chat_engine_cloud_title), mEngineCloudSubtitle) { onEngineCardClicked(ENGINE_CLOUD) })

        // 2026-08-11 (humano97 R2 — decisión usuario): switch para llamar a llama.cpp SIN puerto
        // (motor embebido JNI, mismo proceso) o CON puerto (servidor llama-server HTTP 8085 via
        // la macbook). Se persistede en kairos_llm_prefs (same prefs que LocalAIFragment).
        root.addView(TextView(ctx).apply {
            text = getString(R.string.chat_llama_transport_label)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(20), 0, dp(6))
        })
        val transportRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val embeddedBtn = transportOptionButton(ctx, getString(R.string.chat_transport_embedded))
        val httpBtn = transportOptionButton(ctx, getString(R.string.chat_transport_http))
        transportRow.addView(embeddedBtn)
        transportRow.addView(httpBtn, LinearLayout.LayoutParams(dp(10), 1))
        root.addView(transportRow)
        val transportPrefs = ctx.getSharedPreferences("kairos_llm_prefs", 0)
        fun applyTransportSelection() {
            val isHttp = transportPrefs.getString("llama_transport", "embedded") == "http"
            embeddedBtn.isSelected = !isHttp
            httpBtn.isSelected = isHttp
            val selColor = ctx.kairosThemeColor(R.attr.kairosBlue)
            val plainColor = ctx.kairosThemeColor(R.attr.kairosBg2)
            setTransportBackground(embeddedBtn, if (!isHttp) selColor else plainColor)
            setTransportBackground(httpBtn, if (isHttp) selColor else plainColor)
        }
        embeddedBtn.setOnClickListener {
            transportPrefs.edit().putString("llama_transport", "embedded").apply()
            applyTransportSelection()
        }
        httpBtn.setOnClickListener {
            transportPrefs.edit().putString("llama_transport", "http").apply()
            applyTransportSelection()
        }
        applyTransportSelection()
        return root
    }

    /** Botón segmentado del switch llama.cpp transport (embebido vs servidor HTTP). */
    private fun transportOptionButton(ctx: Context, title: String): android.widget.Button {
        return android.widget.Button(ctx).apply {
            text = title
            textSize = 12f
            isAllCaps = false
            isSelected = false
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {}
        }
    }

    /** Cambia el background de un botón de transporte al color dado. */
    private fun setTransportBackground(btn: android.widget.Button, color: Int) {
        (btn.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
    }

    private fun engineOptionCard(ctx: Context, title: String, subtitle: TextView, onClick: () -> Unit): View {
        val card = MaterialCardView(ctx).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(12)
            }
            setOnClickListener { onClick() }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        inner.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        subtitle.apply {
            text = getString(R.string.chat_engine_option_loading)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(4), 0, 0)
        }
        inner.addView(subtitle)
        card.addView(inner)
        return card
    }

    /** Refresca el subtítulo de cada card (estado real de Ollama, cantidad de modelos locales) cada vez que se muestra el selector. */
    private fun refreshEngineSelectorState() {
        val ctx = context ?: return
        val localCount = LocalModelManager.listModels(ctx).size
        mEngineLocalSubtitle.text =
            if (localCount > 0) {
                if (localCount == 1) getString(R.string.chat_local_models_singular, localCount)
                else getString(R.string.chat_local_models_plural, localCount)
            }
            else getString(R.string.chat_local_models_none)
        mEngineOllamaSubtitle.text = getString(R.string.chat_verifying)
        val cloudProvider = ctx.getSharedPreferences("kairos_llm_prefs", 0)
            .getString(CLOUD_PROVIDER_KEY, null)
        mEngineCloudSubtitle.text = if (cloudProvider != null) {
            getString(R.string.chat_cloud_connected, cloudProviderName(cloudProvider))
        } else {
            getString(R.string.chat_cloud_not_connected)
        }
        Thread {
            // Bug real confirmado por ADB (2026-08-29): en un dispositivo con Ollama
            // instalado y corriendo de verdad (:11434 activo, confirmado con
            // ModuleController.isRunning y curl), ".android_server_registry" no tenía
            // NINGUNA clave "ollama.*" — el chat mostraba "Ollama Termux — No instalada"
            // pese a que el módulo funcionaba. ModuleInstalled.isInstalled() ya existe
            // para exactamente este caso (ver su comentario de clase: "ollama: registry
            // no sabe pero el binario funciona" — mismo criterio que ya usa ModulesFragment
            // para la lista principal) — un ModuleRegistry crudo, como el que había acá,
            // es sordo a un módulo instalado a mano o cuyo registro nunca se backfilleó.
            val installed = try {
                com.termux.app.data.ModuleInstalled.isInstalled(ctx, "ollama")
            } catch (_: Exception) { false }
            mMainHandler.post {
                if (!isAdded) return@post
                mEngineOllamaSubtitle.text = if (installed) getString(R.string.chat_ollama_installed_subtitle)
                    else getString(R.string.chat_ollama_not_installed_subtitle)
            }
        }.start()
    }

    private fun onEngineCardClicked(engine: String) {
        if (engine == ENGINE_OLLAMA) {
            val ctx = context ?: return
            Thread {
                // Mismo fix que refreshEngineSubtitles() arriba — ver ese comentario.
                val installed = try {
                    com.termux.app.data.ModuleInstalled.isInstalled(ctx, "ollama")
                } catch (_: Exception) { false }
                mMainHandler.post {
                    if (!isAdded) return@post
                    if (!installed) {
                        toast(getString(R.string.chat_ollama_not_installed_toast))
                    } else {
                        selectEngine(ENGINE_OLLAMA)
                    }
                }
            }.start()
        } else if (engine == ENGINE_CLOUD) {
            showCloudProviderSelector()
        } else {
            selectEngine(ENGINE_LOCAL)
        }
    }

    // ── Cloud API (BYO key) ───────────────────────────────────────────────────────────

    private fun cloudPrefs() = requireContext().getSharedPreferences("kairos_llm_prefs", 0)

    // Secretos (API keys) cifrados aparte del resto de "kairos_llm_prefs" — ver
    // docs/arquitectura/AUDITORIA_IA_CODIGO_2026-08-19.md hallazgo 2.2 y SecureChatPrefs.kt.
    private fun securePrefs() = SecureChatPrefs(requireContext())

    private fun currentCloudProvider(): String =
        cloudPrefs().getString(CLOUD_PROVIDER_KEY, null) ?: CLOUD_PROVIDERS.first()

    private fun cloudApiKey(provider: String): String =
        securePrefs().getSecret(cloudApiKeyKey(provider))

    private fun cloudProviderName(provider: String): String = when (provider) {
        "gemini" -> "Gemini"
        "deepseek" -> "DeepSeek"
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "grok" -> "Grok"
        else -> provider
    }

    /** Diálogo de selección de proveedor cloud (radio list) — si falta la API key del
     *  proveedor elegido, pide ingresarla antes de entrar al chat (ver requestCloudApiKey). */
    private fun showCloudProviderSelector() {
        val ctx = context ?: return
        val labels = CLOUD_PROVIDERS.map { cloudProviderName(it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.chat_cloud_provider_dialog_title))
            .setSingleChoiceItems(labels, CLOUD_PROVIDERS.indexOf(currentCloudProvider())) { dialog, which ->
                val provider = CLOUD_PROVIDERS[which]
                dialog.dismiss()
                if (cloudApiKey(provider).trim().length < 20) {
                    requestCloudApiKey(provider)
                } else {
                    cloudPrefs().edit().putString(CLOUD_PROVIDER_KEY, provider).apply()
                    selectEngine(ENGINE_CLOUD)
                }
            }
            .setNegativeButton(getString(R.string.chat_cancel), null)
            .show()
    }

    /** Pide la API key del proveedor (TextInputEditText) y la guarda en kairos_llm_prefs.
     *  Reusa el prefijo "cloud_api_key_<provider>" — misma longitud mínima (20) que la
     *  referencia android-code-studio (ApiKey.hasKey(), GPL-3.0 — solo el patrón). */
    private fun requestCloudApiKey(provider: String) {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = "sk-..."
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.chat_api_key_dialog_title, cloudProviderName(provider)))
            .setMessage(getString(R.string.chat_api_key_dialog_message))
            .setView(input)
            .setPositiveButton(getString(R.string.chat_save)) { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.length < 20) {
                    toast(getString(R.string.chat_api_key_invalid_toast))
                } else {
                    securePrefs().setSecret(cloudApiKeyKey(provider), key)
                    cloudPrefs().edit().putString(CLOUD_PROVIDER_KEY, provider).apply()
                    toast(getString(R.string.chat_api_key_saved_toast, cloudProviderName(provider)))
                    selectEngine(ENGINE_CLOUD)
                }
            }
            .setNegativeButton(getString(R.string.chat_cancel), null)
            .show()
    }

    // ── Modo shell ("!") ────────────────────────────────────────────────────────────────

    /** Extrae el comando shell de un mensaje que empieza con "!" (hallazgo #7 whispercode,
     *  2026-08-15): quita el "!" inicial y el whitespace. null si no empieza con "!". */
    private fun extractShellCommand(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(SHELL_COMMAND_PREFIX)) return null
        return trimmed.removePrefix(SHELL_COMMAND_PREFIX).trim().ifBlank { null }
    }

    /** Ejecuta un comando shell real (bash -c, con el PATH/entorno de Termux vía
     *  applyTermuxEnv) y muestra su salida en el chat. Corre en el Thread de fondo armado
     *  por dispatchMessage(). La salida se trunca (~4000 chars) para no inflar la burbuja. */
    private fun dispatchShellCommand(command: String, assistantId: String) {
        val (exitCode, stdout, stderr) = com.termux.app.util.ManagerNativeUtils.runShell(command, 60)
        if (mCancelled.get()) {
            finishLoading()
            return
        }
        val out = stdout.take(4000)
        val err = stderr.take(2000)
        val sb = StringBuilder()
        if (out.isNotBlank()) sb.append(out)
        if (err.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[stderr]\n").append(err)
        }
        if (exitCode != 0) sb.append("\n[exit $exitCode]")
        val text = sb.toString().trim().ifBlank { "[sin salida — exit $exitCode]" }
        setAssistantContent(assistantId, text)
        finishLoading()
    }

    // ── Cloud API (BYO key) — request ──────────────────────────────────────────────────

    /** Enruta el prompt al proveedor cloud activo (ver makeCloudRequest). La web search se
     *  aplica igual que en los motores locales (augmentPromptWithWebSearch). */
    private fun dispatchCloudRequest(prompt: String, assistantId: String) {
        val provider = currentCloudProvider()
        val key = cloudApiKey(provider)
        if (key.trim().length < 20) {
            setAssistantContent(
                assistantId,
                getString(R.string.chat_no_api_key_saved, cloudProviderName(provider))
            )
            finishLoading()
            return
        }
        mMainHandler.post { if (isAdded) mStatusText.text = getString(R.string.chat_cloud_status, cloudProviderName(provider)) }
        val effectivePrompt = augmentPromptWithWebSearch(prompt)
        makeCloudRequest(provider, key, effectivePrompt) { chunk ->
            if (mCancelled.get()) return@makeCloudRequest
            val currentId = assistantId
            mMainHandler.post { appendToAssistant(currentId, chunk) }
        }
    }

    /**
     * Request HTTP a un proveedor cloud con la API key del usuario (BYO — ver
     * ENGINE_CLOUD). Endpoints por proveedor (2026-08-15, ronda humano126):
     *   - Gemini:   POST .../generateContent?key=KEY        → candidates[0].content.parts[0].text
     *   - DeepSeek: POST https://api.deepseek.com/chat/completions (Bearer) → choices[0].message.content
     *   - OpenAI:   POST https://api.openai.com/v1/chat/completions (Bearer)
     *   - Anthropic: POST https://api.anthropic.com/v1/messages (x-api-key + anthropic-version) → content[0].text
     *   - Grok:     POST https://api.x.ai/v1/chat/completions (Bearer)
     * Emite el texto del assistant vía onPartial (chunk entero por turno, no hay streaming
     * unificado). Llama finishLoading() en todos los caminos.
     */
    private fun makeCloudRequest(
        provider: String,
        apiKey: String,
        prompt: String,
        onPartial: (String) -> Unit
    ) {
        try {
            val system = buildSystemMessage()?.optString("content", "").orEmpty()
            val contextText = buildContextMessages()
                .let { arr ->
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        sb.append("${m.optString("role")}: ${m.optString("content")}\n\n")
                    }
                    sb.toString()
                }
            val fullPrompt = listOfNotNull(system.takeIf { it.isNotBlank() }, contextText.takeIf { it.isNotBlank() }, prompt)
                .joinToString("\n\n")

            val (url, headers, body, contentPath) = cloudRequestSpec(provider, apiKey, fullPrompt)

            val conn = URL(url).openConnection() as HttpURLConnection
            mActiveConnection = conn
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = STREAM_READ_TIMEOUT_MS
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } catch (_: Exception) { null }
                if (!mCancelled.get()) {
                    val msg = if (errorBody != null) {
                        getString(R.string.chat_cloud_http_error_with_body, cloudProviderName(provider), code, errorBody.take(300))
                    } else {
                        getString(R.string.chat_cloud_http_error, cloudProviderName(provider), code)
                    }
                    showError(msg)
                }
                finishLoading()
                return
            }
            val respBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            if (mActiveConnection === conn) mActiveConnection = null
            if (mCancelled.get()) {
                finishLoading()
                return
            }
            val text = extractCloudText(JSONObject(respBody), contentPath)
            if (!text.isNullOrBlank()) onPartial(text)
            finishLoading()
        } catch (e: Exception) {
            if (!mCancelled.get()) {
                showError(getString(R.string.chat_cloud_connection_error, cloudProviderName(provider), e.message))
            }
            finishLoading()
        }
    }

    private data class CloudSpec(val url: String, val headers: Map<String, String>, val body: JSONObject, val contentPath: String)

    /** Construye el spec del request cloud según el proveedor (URL, headers, body,
     *  contentPath) — ver makeCloudRequest para la tabla de endpoints por proveedor. */
    private fun cloudRequestSpec(provider: String, apiKey: String, fullPrompt: String): CloudSpec {
        return when (provider) {
            "gemini" -> CloudSpec(
                url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey",
                headers = emptyMap(),
                body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", fullPrompt) }) })
                        })
                    })
                },
                contentPath = "candidates[0].content.parts[0].text"
            )
            "deepseek" -> CloudSpec(
                url = "https://api.deepseek.com/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                body = openAiBody("deepseek-chat", fullPrompt),
                contentPath = "choices[0].message.content"
            )
            "openai" -> CloudSpec(
                url = "https://api.openai.com/v1/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                body = openAiBody("gpt-4o-mini", fullPrompt),
                contentPath = "choices[0].message.content"
            )
            "anthropic" -> CloudSpec(
                url = "https://api.anthropic.com/v1/messages",
                headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                body = JSONObject().apply {
                    put("model", "claude-3-5-haiku-latest")
                    put("max_tokens", 1024)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", fullPrompt) })
                    })
                },
                contentPath = "content[0].text"
            )
            else -> CloudSpec(
                url = "https://api.x.ai/v1/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                body = openAiBody("grok-2-latest", fullPrompt),
                contentPath = "choices[0].message.content"
            )
        }
    }

    /** Body OpenAI-compatible (deepseek/openai/grok comparten /chat/completions). */
    private fun openAiBody(model: String, prompt: String): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
        }
    }

    /** Extrae el texto del assistant según el contentPath por proveedor — navega por
     *  índices de array y campos con un mini-path style "a[0].b.c". */
    private fun extractCloudText(json: JSONObject, path: String): String? {
        val tokens = path.split(".").toMutableList()
        var node: Any = json
        while (tokens.isNotEmpty()) {
            val tok = tokens.removeAt(0)
            val idxMatch = Regex("^(\\w+)\\[(\\d+)\\]$").matchEntire(tok)
            if (idxMatch != null) {
                val field = idxMatch.groupValues[1]
                val idx = idxMatch.groupValues[2].toInt()
                val arr = (node as? JSONObject)?.optJSONArray(field) ?: return null
                if (idx >= arr.length()) return null
                node = arr.get(idx)
            } else {
                node = (node as? JSONObject)?.opt(tok) ?: return null
            }
        }
        return node as? String
    }

    // ── Internet para modelos (Web Search API de Ollama) ───────────────────────────────

    /** Si el toggle de internet está activo, hace una búsqueda web con el prompt y devuelve
     *  el prompt aumentado con los resultados como contexto — si no hay key, el toggle está
     *  OFF o la búsqueda falla, devuelve el prompt sin cambios (nunca rompe el flujo). */
    private fun augmentPromptWithWebSearch(prompt: String): String {
        if (!cloudPrefs().getBoolean(WEB_SEARCH_ENABLED_KEY, false)) return prompt
        val results = performWebSearch(prompt) ?: return prompt
        mMainHandler.post {
            if (isAdded) mStatusText.text = getString(R.string.chat_web_search_injected_status)
        }
        return getString(R.string.chat_web_search_context_prefix, results, prompt)
    }

    /** Llama a la Web Search API de Ollama (POST https://ollama.com/api/web_search con
     *  "Authorization: Bearer <key>", ver docs.ollama.com/capabilities/web-search) y devuelve
     *  los resultados formateados como texto. null si falta la key o falla la llamada. */
    private fun performWebSearch(query: String): String? {
        val key = securePrefs().getSecret(OLLAMA_WEB_API_KEY_KEY)
        if (key.trim().length < 20) return null
        return try {
            val conn = URL(OLLAMA_WEB_SEARCH_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = WEB_SEARCH_TIMEOUT_MS
            conn.readTimeout = WEB_SEARCH_TIMEOUT_MS
            conn.outputStream.use {
                it.write(JSONObject().apply {
                    put("query", query)
                    put("max_results", WEB_SEARCH_MAX_RESULTS)
                }.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return null }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return null
            val sb = StringBuilder()
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                sb.append("• ").append(r.optString("title", "")).append("\n")
                val url = r.optString("url", "")
                if (url.isNotBlank()) sb.append("  ").append(url).append("\n")
                val content = r.optString("content", "").take(400)
                if (content.isNotBlank()) sb.append("  ").append(content).append("\n")
                sb.append("\n")
            }
            sb.toString().trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Dialog para pedir la key de la Web Search API de Ollama (ollama.com/settings/keys). */
    private fun showWebSearchKeyDialog() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = "ollama_..."
            isSingleLine = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.chat_web_search_dialog_title))
            .setMessage(getString(R.string.chat_web_search_dialog_message))
            .setView(input)
            .setPositiveButton(getString(R.string.chat_web_search_save_and_enable)) { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.length < 20) {
                    toast(getString(R.string.chat_web_search_key_invalid_toast))
                } else {
                    securePrefs().setSecret(OLLAMA_WEB_API_KEY_KEY, key)
                    cloudPrefs().edit().putBoolean(WEB_SEARCH_ENABLED_KEY, true).apply()
                    toast(getString(R.string.chat_web_enabled_toast))
                }
            }
            .setNegativeButton(getString(R.string.chat_cancel), null)
            .show()
    }

    /** Aplica el motor elegido: filtra qué modelos ofrece showModelMenu() y arranca el chequeo correspondiente (Ollama HTTP, o nada para el motor local — siempre disponible, embebido). */
    private fun selectEngine(engine: String) {
        mEngine = engine
        hideEngineSelector()
        if (engine == ENGINE_CLOUD) {
            mChatContent.visibility = View.VISIBLE
            mOfflineOverlay.visibility = View.GONE
            mStatusDot.setBackgroundResource(R.drawable.circle_dot_green)
            mStatusText.text = getString(R.string.chat_cloud_status_byo)
            mModelSelector.text = "☁️ ${cloudProviderName(currentCloudProvider())}"
            mInput.hint = getString(R.string.chat_cloud_input_hint)
            updateAttachButtonState()
        } else if (engine == ENGINE_LOCAL) {
            // Motor local: siempre "disponible" (no depende de un servicio externo) — nunca
            // muestra el offline_overlay, que es un concepto específico de Ollama. Prioriza el
            // primer modelo .gguf ya descargado como default (antes el default quedaba en un
            // nombre de Ollama aunque hubiera un modelo local real ya bajado — bug real
            // reportado, ver docs/humano/humano34.md).
            mChatContent.visibility = View.VISIBLE
            mOfflineOverlay.visibility = View.GONE
            mStatusDot.setBackgroundResource(R.drawable.circle_dot_green)
            mStatusText.text = getString(R.string.chat_local_engine_status)
            val localModels = LocalModelManager.listModels(requireContext()).map { it.name }
            if (localModels.isNotEmpty() && !isLocalModel(mSelectedModel)) {
                mSelectedModel = localModels[0]
                mModelSelector.text = "📱 $mSelectedModel"
                mInput.hint = getString(R.string.chat_input_hint_model, mSelectedModel)
            } else if (localModels.isEmpty()) {
                mModelSelector.text = getString(R.string.chat_no_local_models_selector)
            }
            updateAttachButtonState()
        } else {
            // Motor Ollama: si el modelo activo era uno .gguf (motor local), hace falta un
            // default de Ollama real — checkOllamaStatus() ya se encarga de corregirlo al
            // primer modelo REAL pulled apenas confirma que Ollama está arriba.
            if (isLocalModel(mSelectedModel)) {
                mSelectedModel = mOllamaModels.firstOrNull() ?: MODELS[0]
                mModelSelector.text = mSelectedModel
                mInput.hint = getString(R.string.chat_input_hint_model, mSelectedModel)
            }
            updateAttachButtonState()
            checkOllamaStatus()
        }
    }

    private fun checkOllamaStatus() {
        Thread {
            try {
                val url = URL(OLLAMA_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) {
                    // Ollama está arriba — ahora sí tiene sentido preguntar qué modelos
                    // descargó de verdad el usuario (ver mOllamaModels). Antes esto nunca se
                    // consultaba y el chat asumía que MODELS (nombres de ejemplo) estaba
                    // disponible, ver comentario de esa constante.
                    val pulled = try {
                        com.termux.app.util.OllamaApiClient.listModels().map { it.name }
                    } catch (_: Exception) { emptyList() }
                    mMainHandler.post { showOnline(pulled) }
                } else {
                    showOffline()
                }
            } catch (_: Exception) {
                showOffline()
            }
        }.start()
    }

    private fun showOnline(pulledModels: List<String>) {
        mChatContent.visibility = View.VISIBLE
        mOfflineOverlay.visibility = View.GONE
        mStatusDot.setBackgroundResource(R.drawable.circle_dot_green)

        mOllamaModels.clear()
        mOllamaModels.addAll(pulledModels)

        // Auto-corrige el modelo seleccionado si es uno de los MODELS de ejemplo que el
        // usuario nunca descargó — sin esto, el primer mensaje que manda cualquier usuario
        // nuevo garantizadamente fallaba con HTTP 404 (bug real reportado, ver comentario
        // de mOllamaModels). Si el usuario ya eligió a mano un modelo local (.gguf) o uno
        // que sí está pulled, no se toca nada.
        if (!isLocalModel(mSelectedModel) && mSelectedModel !in mOllamaModels && pulledModels.isNotEmpty()) {
            mSelectedModel = pulledModels[0]
            mModelSelector.text = mSelectedModel
            mInput.hint = getString(R.string.chat_input_hint_model, mSelectedModel)
        }

        mStatusText.text = if (pulledModels.isEmpty()) {
            getString(R.string.chat_ollama_active_no_models)
        } else {
            getString(R.string.chat_ollama_active)
        }
    }

    private fun showOffline() {
        mMainHandler.post {
            if (!isAdded) return@post
            // Con la pantalla de elegir motor (ver selectEngine()), esta función solo se llama
            // cuando el usuario ya eligió ENGINE_OLLAMA explícitamente — no hace falta el
            // fallback a "mostrar igual si hay modelos locales" que tenía antes (esa
            // combinación ahora es la pantalla de selección, no un estado mezclado dentro del
            // chat). Si eligió Ollama y está caído, se avisa sin ambigüedad.
            mChatContent.visibility = View.GONE
            mOfflineOverlay.visibility = View.VISIBLE
            mStatusDot.setBackgroundResource(android.R.color.darker_gray)
            mStatusText.text = getString(R.string.chat_ollama_inactive)
        }
    }

    private fun showModelMenu() {
        val popup = PopupMenu(requireContext(), mModelSelector)
        // Pedido explícito del usuario: los 2 motores NO se mezclan en el mismo selector —
        // con ENGINE_LOCAL elegido, solo aparecen modelos .gguf; con ENGINE_OLLAMA, solo los
        // realmente pulled en Ollama (mOllamaModels tiene prioridad, MODELS es fallback
        // pre-fetch/Ollama caído). Ver selectEngine().
        val allModels = if (mEngine == ENGINE_LOCAL) {
            LocalModelManager.listModels(requireContext()).map { it.name }
        } else {
            mOllamaModels.ifEmpty { MODELS.toList() }
        }
        if (allModels.isEmpty()) {
            toast(if (mEngine == ENGINE_LOCAL) getString(R.string.chat_no_local_models_toast) else getString(R.string.chat_no_ollama_models_toast))
            return
        }
        for (i in allModels.indices) {
            val label = if (isLocalModel(allModels[i])) "📱 ${allModels[i]}" else allModels[i]
            popup.menu.add(0, i, i, label)
        }
        popup.setOnMenuItemClickListener { item ->
            mSelectedModel = allModels[item.itemId]
            mModelSelector.text = if (isLocalModel(mSelectedModel)) "📱 $mSelectedModel" else mSelectedModel
            mInput.hint = getString(R.string.chat_input_hint_model, mSelectedModel)
            updateAttachButtonState()
            true
        }
        popup.show()
    }

    // nombre de modelo Ollama -> soporta "vision" según /api/show (null = todavía sin
    // confirmar por API, o Ollama viejo que no expone "capabilities" — en ese caso se sigue
    // el heurístico de nombre VISION_MODEL_HINT_REGEX como antes, nunca se bloquea solo por
    // falta de dato). Ver refreshVisionCapability().
    private val mVisionCapabilityCache = mutableMapOf<String, Boolean?>()

    /**
     * El botón de adjuntar imagen (ver mBtnAttach) solo tiene sentido con Ollama remoto —
     * el motor local (.gguf, llama.cpp embebido) no tiene vision projector, ver comentario
     * de clase. Si el usuario adjuntó una imagen y después cambia a un modelo local, se
     * descarta en vez de mandarla en silencio a un motor que la ignoraría por completo.
     *
     * Pedido explícito del usuario (ver docs/humano/humano57.md): "si no soporta imagen no
     * debe salir para subir imagen" — además del caso de motor local, ahora también se
     * deshabilita cuando la propia API de Ollama confirma (campo "capabilities" de
     * /api/show, ver refreshVisionCapability) que el modelo elegido no soporta imágenes.
     */
    private fun updateAttachButtonState() {
        if (isLocalModel(mSelectedModel)) {
            setAttachEnabled(false)
            if (mAttachedImageBase64 != null) {
                clearAttachedImage()
                toast(getString(R.string.chat_image_discarded_local))
            }
            return
        }
        when (mVisionCapabilityCache[mSelectedModel]) {
            false -> {
                setAttachEnabled(false)
                if (mAttachedImageBase64 != null) {
                    clearAttachedImage()
                    toast(getString(R.string.chat_image_discarded_no_vision, mSelectedModel))
                }
            }
            true -> setAttachEnabled(true)
            null -> {
                // Sin confirmar todavía — se deja habilitado (mismo criterio que antes:
                // el heurístico de nombre solo avisa al enviar, nunca bloquea por las
                // dudas) mientras se consulta la API en segundo plano.
                setAttachEnabled(true)
                refreshVisionCapability(mSelectedModel)
            }
        }
    }

    private fun setAttachEnabled(enabled: Boolean) {
        mBtnAttach.alpha = if (enabled) 1.0f else 0.35f
        mBtnAttach.isEnabled = enabled
    }

    /**
     * Consulta OllamaApiClient.modelInfo() (una sola vez por modelo, resultado cacheado en
     * mVisionCapabilityCache) para saber con certeza si el modelo soporta imágenes — campo
     * real "capabilities" de /api/show. Si la consulta falla o el modelo no expone ese
     * campo (versión vieja de Ollama), queda como "sin confirmar" (null) y el botón sigue
     * disponible con el aviso heurístico de siempre — nunca se oculta solo por falta de
     * dato, únicamente cuando la API confirma explícitamente que NO soporta.
     */
    private fun refreshVisionCapability(modelName: String) {
        Thread {
            val supportsVision = try {
                val detail = com.termux.app.util.OllamaApiClient.modelInfo(modelName)
                if (detail.capabilities.isEmpty()) null
                else detail.capabilities.any { it.equals("vision", ignoreCase = true) }
            } catch (_: Exception) { null }
            mMainHandler.post {
                if (!isAdded) return@post
                mVisionCapabilityCache[modelName] = supportsVision
                if (modelName == mSelectedModel) updateAttachButtonState()
            }
        }.start()
    }

    /**
     * Atajo pedido explícitamente por el usuario: "los ajustes de mensajes en memoria y
     * contexto" — hoy `temperature`/`context_size` sólo viven en IA Local (pantalla
     * separada), y para el motor local (ver makeLocalRequest) hay que salir del chat,
     * entrar a IA Local, ajustar y volver. Este diálogo lee/escribe las MISMAS
     * SharedPreferences ("kairos_llm_prefs") que LocalAIFragment — no duplica su lógica de
     * catálogo/backend, solo expone los 2 sliders que también aplican mensaje a mensaje acá.
     * `context_size` solo tiene efecto real en el motor local (Ollama's num_ctx ahora se lee
     * de ~/.ollama_user_config vía OllamaApiClient.readConfig() en cada request, ver
     * makeOllamaRequest() — configurable desde OllamaConfigFragment, no desde este diálogo)
     * — se avisa igual porque compartir la misma preferencia entre pantallas es menos
     * confuso que un tercer valor separado.
     */
    private fun showQuickSettingsDialog() {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("kairos_llm_prefs", 0)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val tempLabel = TextView(ctx).apply { textSize = 13f }
        root.addView(tempLabel)
        val savedTemp = prefs.getFloat("temperature", 0.7f)
        fun renderTemp(t: Float) { tempLabel.text = getString(R.string.chat_temperature_label, t) }
        renderTemp(savedTemp)
        val tempSeek = SeekBar(ctx).apply {
            max = 200 // 0.00 .. 2.00, pasos de 0.01 — mismo rango que LocalAIFragment
            progress = (savedTemp * 100).toInt()
        }
        tempSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val t = progress / 100f
                renderTemp(t)
                if (fromUser) prefs.edit().putFloat("temperature", t).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        root.addView(tempSeek)

        val ctxLabel = TextView(ctx).apply { textSize = 13f; setPadding(0, dp(14), 0, 0) }
        root.addView(ctxLabel)
        val savedCtx = prefs.getInt("context_size", 2048)
        fun renderCtx(c: Int) { ctxLabel.text = getString(R.string.chat_context_size_label, c) }
        renderCtx(savedCtx)
        val ctxSeek = SeekBar(ctx).apply {
            max = 8192 - 512 // mismo rango que LocalAIFragment (512..8192)
            progress = (savedCtx - 512).coerceIn(0, max)
        }
        ctxSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val c = progress + 512
                renderCtx(c)
                if (fromUser) prefs.edit().putInt("context_size", c).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        root.addView(ctxSeek)

        // Límite de historial (memoria + disco) — pedido explícito de auditoría: el script
        // original (menu_nativo.sh's _ollama_chat_texto) sí tenía esto como opción de
        // configuración, Kairos no. Ver enforceHistoryLimit(), llamado tras cada mensaje y
        // acá mismo al mover el slider (para que "Limpiar ahora" no dependa de mandar otro
        // mensaje).
        val historyLabel = TextView(ctx).apply { textSize = 13f; setPadding(0, dp(14), 0, 0) }
        root.addView(historyLabel)
        val rawHistoryLimit = prefs.getInt("history_limit", DEFAULT_HISTORY_LIMIT)
        // Preset "∞" (ver OllamaConfigFragment) — se muestra como texto, no como posición de
        // slider (el slider solo llega hasta MAX_HISTORY_LIMIT). isUnlimited se apaga apenas
        // el usuario toca el slider — a partir de ahí vuelve a ser un límite finito normal.
        var isUnlimited = rawHistoryLimit == HISTORY_LIMIT_UNLIMITED
        val savedHistoryLimit = if (isUnlimited) MAX_HISTORY_LIMIT
            else rawHistoryLimit.coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)
        fun renderHistoryLimit(n: Int) {
            historyLabel.text = if (isUnlimited) getString(R.string.chat_history_limit_unlimited) else getString(R.string.chat_history_limit_label, n)
        }
        renderHistoryLimit(savedHistoryLimit)
        val historySeek = SeekBar(ctx).apply {
            max = MAX_HISTORY_LIMIT - MIN_HISTORY_LIMIT
            progress = savedHistoryLimit - MIN_HISTORY_LIMIT
        }
        historySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val n = progress + MIN_HISTORY_LIMIT
                if (fromUser) isUnlimited = false
                renderHistoryLimit(n)
                if (fromUser) {
                    prefs.edit().putInt("history_limit", n).apply()
                    // Aplica de inmediato — si el usuario baja el límite, los mensajes más
                    // viejos se descartan ya (memoria + disco), no recién en el próximo turno.
                    enforceHistoryLimit()
                    persistHistory()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        root.addView(historySeek)

        // Gate de permisos (Composer docks, ver checkPermissionMarker) — "Permitir siempre"
        // del diálogo "El agente solicita permiso" persiste tool_autoaccept; este switch es la
        // salida para desactivarlo sin esperar a que el modelo pida permiso de nuevo.
        root.addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
            text = getString(R.string.chat_autoaccept_permissions)
            isChecked = prefs.getBoolean("tool_autoaccept", false)
            setPadding(0, dp(14), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("tool_autoaccept", checked).apply()
            }
        })

        root.addView(TextView(ctx).apply {
            text = getString(R.string.chat_quick_settings_hint)
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
        })

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.chat_quick_settings_title))
            .setView(root)
            .setPositiveButton(getString(R.string.chat_done), null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Selector de persona (presets de SystemPrompts) — radio list con las personas activas,
     * persiste la elegida en `kairos_llm_prefs` vía SystemPrompts.save() y refresca la
     * etiqueta "🎭" de la model_bar. El prompt de la persona elegida se inyecta en el armado
     * de `messages` de makeOllamaRequest/makeLlamaServerRequest (ver buildSystemMessage()).
     */
    private fun showPersonaSelector() {
        val ctx = requireContext()
        val current = SystemPrompts.currentId(ctx)
        val labels = SystemPrompts.personas.map { p ->
            if (p.prompt.isBlank()) p.name else "${p.name} — ${p.description}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.chat_persona_selector_title))
            .setSingleChoiceItems(labels, SystemPrompts.personas.indexOfFirst { it.id == current }) { dialog, which ->
                val persona = SystemPrompts.personas[which]
                SystemPrompts.save(ctx, persona.id)
                mPersonaLabel.text = getString(R.string.chat_persona_label_format, persona.name)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.chat_cancel), null)
            .show()
    }

    /**
     * Arma el mensaje `system` de los requests de chat combinando la persona elegida
     * (SystemPrompts) con el system prompt custom de Ollama (`OLLAMA_SYSTEM_PROMPT`). La
     * persona por defecto ("Asistente general") tiene prompt vacío, así que con ella el
     * resultado es EXACTAMENTE el comportamiento anterior (solo el custom de Ollama). Con una
     * persona activa + custom, la persona va primero y el custom después, separados por un
     * salto de línea. Devuelve null si no hay nada que inyectar.
     */
    private fun buildSystemMessage(): JSONObject? {
        val parts = mutableListOf<String>()
        context?.let { SystemPrompts.currentPrompt(it) }?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        com.termux.app.util.OllamaApiClient.readConfig()["OLLAMA_SYSTEM_PROMPT"]
            ?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (parts.isEmpty()) return null
        return JSONObject().apply {
            put("role", "system")
            put("content", parts.joinToString("\n\n"))
        }
    }

    private fun updateSendButton() {
        val hasText = mInput.text.toString().trim().isNotEmpty()
        mBtnSend.isEnabled = hasText && !mLoading
        mBtnSend.alpha = if (hasText && !mLoading) 1.0f else 0.4f
    }

    private fun sendMessage() {
        val text = mInput.text.toString().trim()
        if (text.isEmpty()) return

        // Follow-up queue (Composer docks, ver mPendingQueue): con un request en curso el
        // mensaje NO se envía — se encola, el input se limpia y aparece "⏳ N en cola". El
        // request activo termina su streaming y finishLoading()→drainPendingQueue() lo
        // reenvía en orden (FIFO). Los encolados no llevan imagen (el attach se descarta).
        if (isWorking) {
            mPendingQueue.add(text)
            mInput.setText("")
            mInput.clearFocus()
            clearAttachedImage()
            val n = mPendingQueue.size
            updateQueueIndicator()
            toast(getString(R.string.chat_message_queued_toast, n))
            return
        }

        val image = mAttachedImageBase64
        // Aviso honesto, no bloqueo silencioso (pedido explícito): la heurística de nombre
        // (VISION_MODEL_HINT_REGEX) no es exhaustiva, así que un modelo de visión con un
        // nombre raro igual puede funcionar — se avisa y se deja decidir al usuario.
        if (image != null && !isLocalModel(mSelectedModel) && !looksLikeVisionModel(mSelectedModel)) {
            confirmSendWithUnknownVisionModel(text, image)
            return
        }
        dispatchMessage(text, image)
    }

    private fun looksLikeVisionModel(name: String) = VISION_MODEL_HINT_REGEX.containsMatchIn(name)

    private fun confirmSendWithUnknownVisionModel(text: String, image: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.chat_vision_unknown_title))
            .setMessage(getString(R.string.chat_vision_unknown_message, mSelectedModel))
            .setPositiveButton(getString(R.string.chat_send_anyway)) { _, _ -> dispatchMessage(text, image) }
            .setNegativeButton(getString(R.string.chat_cancel), null)
            .show()
    }

    private fun dispatchMessage(text: String, imageBase64: String?) {
        mInput.setText("")
        mInput.clearFocus()
        clearAttachedImage()

        val userMsg = ChatMessage(
            id = System.currentTimeMillis().toString(),
            role = "user",
            content = text,
            ts = System.currentTimeMillis(),
            model = null,
            imageBase64 = imageBase64
        )
        mMessages.add(userMsg)
        mAdapter.notifyItemInserted(mMessages.size - 1)
        scrollToBottom()

        val runQuery = extractPrefixedCommand(text, RUN_COMMAND_PREFIX)
        val aiRunQuery = extractPrefixedCommand(text, AI_RUN_COMMAND_PREFIX)
        val shellCommand = extractShellCommand(text)
        val isCactusCommand = runQuery != null || aiRunQuery != null
        val isShellCommand = shellCommand != null && !isCactusCommand

        val assistantId = (System.currentTimeMillis() + 1).toString()
        val assistantMsg = ChatMessage(
            id = assistantId,
            role = "assistant",
            content = "",
            ts = System.currentTimeMillis(),
            model = if (isCactusCommand) "cactus" else if (isShellCommand) "shell" else mSelectedModel
        )
        mMessages.add(assistantMsg)
        mAdapter.notifyItemInserted(mMessages.size - 1)
        scrollToBottom()

        setLoading(true)
        mCancelled.set(false)
        mThinkParser = ThinkStreamParser()

        mRequestThread = if (runQuery != null) {
            Thread { dispatchCactusRun(runQuery, assistantId, useAi = false) }
        } else if (aiRunQuery != null) {
            Thread { dispatchCactusRun(aiRunQuery, assistantId, useAi = true) }
        } else if (isShellCommand) {
            Thread { dispatchShellCommand(shellCommand, assistantId) }
        } else if (mEngine == ENGINE_CLOUD) {
            Thread { dispatchCloudRequest(text, assistantId) }
        } else if (isLocalModel(mSelectedModel)) {
            // El botón de adjuntar ya queda deshabilitado/vaciado para modelos locales (ver
            // updateAttachButtonState) — imageBase64 debería ser siempre null acá, pero
            // makeLocalRequest ni siquiera acepta el parámetro: no hay forma de que una
            // imagen llegue al motor local por este camino.
            // 2026-08-11 (humano97 R2 — decisión usuario): transporte de llama.cpp elegible
            // en el selector de motor — "embedded" = motor embebido JNI (sin puerto);
            // "http" = servidor llama-server 8085 (con puerto). Persistido en kairos_llm_prefs.
            val transport = requireContext().getSharedPreferences("kairos_llm_prefs", 0)
                .getString("llama_transport", "embedded")
            Thread {
                if (transport == "http") {
                    if (llamaServerAvailable()) {
                        mMainHandler.post { if (isAdded) mStatusText.text = getString(R.string.chat_llamaserver_transport_status) }
                        makeLlamaServerRequest(text, assistantId)
                    } else {
                        showError(getString(R.string.chat_llamaserver_unavailable))
                        finishLoading()
                    }
                } else {
                    makeLocalRequest(text, assistantId)
                }
            }
        } else {
            Thread { makeOllamaRequest(text, assistantId, imageBase64) }
        }
        mRequestThread!!.start()
    }

    /** Devuelve el pedido sin el prefijo si `text` empieza con `prefix` (case-insensitive,
     *  seguido de espacio o fin de string) — null si no matchea, para no confundir "/runaway"
     *  con el comando "/run". Ver dispatchMessage(). */
    private fun extractPrefixedCommand(text: String, prefix: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
        val rest = trimmed.substring(prefix.length)
        if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
        return rest.trim()
    }

    /** Corre `cactus run --json-only <query>` (needle decide la tool SIN pasar por ninguna IA)
     *  o, si `useAi` es true, `cactus ai --model <mSelectedModel> --json-only <query>` (el
     *  razonador — mismo motor/modelo que ya usa el chat, vía Ollama 11434 o llama-server 8085
     *  — interpreta el pedido en lenguaje natural ANTES de que needle decida qué tool ejecutar).
     *  Pedido explícito del usuario (ver docs/humano/humano118.md): "cactus debe ser con y sin
     *  ia" — deben convivir ambos modos, `/run` sin IA y `/ai` con IA (una ronda anterior había
     *  forzado siempre el modo con IA, ver docs/humano/humano116.md — corregido acá). Ya corre
     *  en el Thread de fondo armado por dispatchMessage(). */
    private fun dispatchCactusRun(query: String, assistantId: String, useAi: Boolean) {
        val usagePrefix = if (useAi) AI_RUN_COMMAND_PREFIX else RUN_COMMAND_PREFIX
        if (query.isBlank()) {
            setAssistantContent(assistantId, getString(R.string.chat_run_usage, usagePrefix))
            finishLoading()
            return
        }
        // Ruta absoluta, no "cactus" por nombre relativo — ProcessBuilder(bare name) no
        // resuelve PATH de forma confiable en este proyecto (ver TERMUX_CACTUS_PATH en
        // ProcessBuilderExt.kt, mismo bug real reportado 2026-08-20 y ya confirmado con este
        // mismo patrón para udocker/proot-distro/pkg/python3).
        val cactusArgs = if (useAi) {
            listOf(TERMUX_CACTUS_PATH, "ai", "--model", mSelectedModel, "--json-only", query)
        } else {
            listOf(TERMUX_CACTUS_PATH, "run", "--json-only", query)
        }
        val (exitCode, stdout, stderr) = ManagerNativeUtils.runExec(cactusArgs, CACTUS_RUN_TIMEOUT_SECONDS)
        if (mCancelled.get()) {
            finishLoading()
            return
        }
        if (stdout.isBlank() &&
            (stderr.contains("No such file", ignoreCase = true) || stderr.contains("Cannot run program", ignoreCase = true))
        ) {
            setAssistantContent(
                assistantId,
                getString(R.string.chat_cactus_not_installed, usagePrefix)
            )
            finishLoading()
            return
        }
        if (stdout.isBlank()) {
            val detail = if (stderr.isNotBlank()) getString(R.string.chat_run_no_output_detail, stderr) else getString(R.string.chat_run_no_output_exit, exitCode)
            setAssistantContent(assistantId, getString(R.string.chat_run_no_output, usagePrefix, detail))
            finishLoading()
            return
        }
        val summary = try {
            formatCactusResult(JSONObject(stdout))
        } catch (e: Exception) {
            val extra = if (stderr.isNotBlank()) "\n\n[stderr]\n$stderr" else ""
            "No se pudo interpretar la salida de cactus:\n\n$stdout$extra"
        }
        setAssistantContent(assistantId, summary)
        finishLoading()
    }

    /** Texto plano legible a partir del JSON final de `cactus run --json-only` — mismo shape
     *  que arma run_needle() en cactus_engine.py: {type, confidence, results[], reasoning}. */
    private fun formatCactusResult(json: JSONObject): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.chat_cactus_type, json.optString("type", "?")))
        if (json.has("confidence") && !json.isNull("confidence")) {
            sb.append(getString(R.string.chat_cactus_confidence, json.optDouble("confidence").toString()))
        }
        val reasoning = json.optString("reasoning", "")
        if (reasoning.isNotBlank()) sb.append(getString(R.string.chat_cactus_reasoning, reasoning))
        val reason = json.optString("reason", "")
        if (reason.isNotBlank()) sb.append(getString(R.string.chat_cactus_reason, reason))

        val results = json.optJSONArray("results")
        if (results == null || results.length() == 0) {
            return sb.toString().trim().ifBlank { getString(R.string.chat_cactus_no_results) }
        }
        sb.append("\n")
        for (i in 0 until results.length()) {
            val step = results.optJSONObject(i) ?: continue
            val name = step.optString("name", "?")
            val arguments = step.optJSONObject("arguments") ?: JSONObject()
            sb.append("— $name($arguments)\n")
            val result = step.optJSONObject("result")
            if (result != null) {
                if (result.has("exit_code") && !result.isNull("exit_code")) {
                    sb.append("  exit_code: ${result.opt("exit_code")}\n")
                }
                val toolStdout = result.optString("stdout", "")
                if (toolStdout.isNotBlank()) sb.append("  stdout:\n${toolStdout.trim().prependIndent("    ")}\n")
                val toolStderr = result.optString("stderr", "")
                if (toolStderr.isNotBlank()) sb.append("  stderr:\n${toolStderr.trim().prependIndent("    ")}\n")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    /** Reemplaza el contenido de un mensaje "assistant" ya creado (a diferencia de
     *  appendToAssistant(), que va sumando deltas de streaming vía ThinkStreamParser) —
     *  usado por dispatchCactusRun(), que llega con el texto final completo de una. */
    private fun setAssistantContent(assistantId: String, text: String) {
        mMainHandler.post {
            for (i in mMessages.indices) {
                val msg = mMessages[i]
                if (msg.id == assistantId) {
                    msg.content = text
                    mAdapter.notifyItemChanged(i)
                    scrollToBottom()
                    break
                }
            }
        }
    }

    /** Abre el picker nativo de imágenes — deshabilitado (con aviso) para modelos locales, ver updateAttachButtonState(). */
    private fun onAttachClicked() {
        if (isLocalModel(mSelectedModel)) {
            toast(getString(R.string.chat_attach_disabled_local))
            return
        }
        mPickImageLauncher.launch("image/*")
    }

    /** Pide RECORD_AUDIO en runtime si falta (ver mRecordAudioPermissionLauncher) o arranca a escuchar directo si ya está concedido. */
    private fun onMicClicked() {
        mSpeechManager?.let { if (it.isListening()) { it.stopListening(); return } }
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) startListening() else mRecordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Dicta y agrega el texto reconocido AL FINAL de lo que ya había en mInput (nunca lo
     * reemplaza, nunca envía el mensaje solo) — el usuario siempre revisa/edita antes de
     * tocar enviar, mismo criterio que cualquier dictado de texto normal en Android.
     */
    private fun startListening() {
        val manager = mSpeechManager ?: com.termux.app.util.SpeechInputManager(requireContext()).also { mSpeechManager = it }
        manager.setCallback(object : com.termux.app.util.SpeechInputManager.SpeechCallback {
            override fun onResult(text: String) {
                mMainHandler.post {
                    if (!isAdded) return@post
                    val current = mInput.text?.toString().orEmpty()
                    val separator = if (current.isNotEmpty() && !current.endsWith(" ")) " " else ""
                    mInput.setText(current + separator + text)
                    mInput.setSelection(mInput.text?.length ?: 0)
                }
            }
            override fun onError(error: String) {
                mMainHandler.post { if (isAdded) toast(error) }
            }
            override fun onListeningStarted() {
                mMainHandler.post { if (isAdded) mBtnMic.alpha = 1.0f }
            }
            override fun onListeningStopped() {
                mMainHandler.post { if (isAdded) mBtnMic.alpha = 0.6f }
            }
        })
        manager.startListening()
    }

    private fun handlePickedImage(uri: Uri) {
        Thread {
            val base64 = try { encodeImageForOllama(uri) } catch (_: Exception) { null }
            mMainHandler.post {
                if (!isAdded) return@post
                if (base64 == null) {
                    toast(getString(R.string.chat_image_read_failed))
                    return@post
                }
                mAttachedImageBase64 = base64
                showImagePreview(base64)
            }
        }.start()
    }

    /**
     * Decodifica y reduce la imagen elegida antes de mandarla — sin esto, una foto de
     * cámara moderna (12+ MP) infla el JSON de la request (y lo que persiste
     * ChatHistoryStore) con varios MB por mensaje. Ver MAX_IMAGE_DIMENSION_PX.
     */
    private fun encodeImageForOllama(uri: Uri): String {
        val resolver = requireContext().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_DIMENSION_PX || bounds.outHeight / sample > MAX_IMAGE_DIMENSION_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("No se pudo decodificar la imagen")

        val scaled = if (bitmap.width > MAX_IMAGE_DIMENSION_PX || bitmap.height > MAX_IMAGE_DIMENSION_PX) {
            val scale = MAX_IMAGE_DIMENSION_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        // Sin el prefijo "data:image/...;base64," — el campo "images" de la API de Ollama
        // espera el base64 crudo, ver docs/referencias/REFERENCIA_OLLAMASERVER.md.
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun showImagePreview(base64: String) {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        mImagePreviewThumb.setImageBitmap(bmp)
        mImagePreviewRow.visibility = View.VISIBLE
    }

    private fun clearAttachedImage() {
        mAttachedImageBase64 = null
        mImagePreviewRow.visibility = View.GONE
        mImagePreviewThumb.setImageBitmap(null)
    }

    private fun toast(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    /** Motor local (llama.cpp embebido) — carga perezosa + streaming token a token. */
    private fun makeLocalRequest(prompt: String, assistantId: String) {
        try {
            var engine = mLocalEngine
            if (engine == null || mLocalEngineModelName != mSelectedModel) {
                engine?.close()
                val ctx = requireContext()
                val modelFile = java.io.File(LocalModelManager.modelsDir(ctx), mSelectedModel)
                if (!modelFile.exists()) {
                    showError(getString(R.string.chat_local_model_not_found, mSelectedModel))
                    finishLoading()
                    return
                }
                engine = LlamaEngine()
                engine.loadBackends(ctx.applicationInfo.nativeLibraryDir)
                val prefs = ctx.getSharedPreferences("kairos_llm_prefs", 0)
                val backendPref = GpuBackend.valueOf(
                    prefs.getString("backend", GpuBackend.VULKAN_IF_AVAILABLE.name)
                        ?: GpuBackend.VULKAN_IF_AVAILABLE.name
                )
                val gpuName = engine.getGpuDeviceInfo()
                val nGpuLayers = GpuBackend.resolveGpuLayers(backendPref, gpuName)
                val temperature = prefs.getFloat("temperature", 0.7f)
                val contextSize = prefs.getInt("context_size", 2048).toLong()
                mMainHandler.post { if (isAdded) mStatusText.text = getString(R.string.chat_loading_model) }
                engine.load(
                    modelFile.absolutePath,
                    LlamaEngine.InferenceParams(
                        temperature = temperature,
                        contextSize = contextSize,
                        nGpuLayers = nGpuLayers,
                        numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                    )
                )
                mLocalEngine = engine
                mLocalEngineModelName = mSelectedModel
            }

            engine.streamResponse(prompt) { chunk ->
                if (mCancelled.get()) {
                    engine.stop()
                    return@streamResponse
                }
                mMainHandler.post { appendToAssistant(assistantId, chunk) }
            }
            finishLoading()
        } catch (e: Exception) {
            // 2026-08-11 (humano97 punto 3, fusión llama-server + IA Local): si el motor
            // embebido (JNI) no pudo cargar/responder, se cae al servidor llama-server HTTP
            // (módulo llamaserver, 127.0.0.1:8085, OpenAI-compatible) — que ya tiene un
            // modelo cargado (llamaserver.sh lo arranca con LLAMA_SERVER_MODEL). El usuario
            // decidió "ambos": JNI primero, servidor HTTP como fallback. Si tampoco hay
            // servidor, recién ahí se muestra el error del motor nativo traducido.
            if (!mCancelled.get() && llamaServerAvailable()) {
                mMainHandler.post { if (isAdded) mStatusText.text = getString(R.string.chat_local_unavailable_fallback) }
                makeLlamaServerRequest(prompt, assistantId)
                return
            }
            if (!mCancelled.get()) {
                // El motor nativo (JNI/llama.cpp) tira mensajes crudos tipo
                // "loadModel() failed: unable to allocate CPU_Mapped buffer" —
                // LlmErrorMapper los traduce a una acción humana, sin esconder
                // el texto técnico original (ver "Ver detalles" en showError).
                val friendly = LlmErrorMapper.map(e.message)
                showError(friendly.message, friendly.technicalDetail)
            }
            finishLoading()
        }
    }

    /** ¿Está respondiendo el servidor llama-server HTTP (módulo llamaserver, puerto 8085)? */
    private fun llamaServerAvailable(): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 8085), 800)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fallback HTTP al servidor llama-server (2026-08-11, humano97 punto 3) — el chat le habla
     * por el endpoint OpenAI-compatible /v1/chat/completions del módulo llamaserver (puerto
     * 8085, ver modulos/llamaserver.sh). Streaming SSE (data: {...} y [DONE]), mismos roles que
     * Ollama (/api/chat) — el campo "model" se ignora en llama-server (sirve un solo modelo
     * cargado, el de LLAMA_SERVER_MODEL).
     *
     * Bug real corregido (auditoría categoría I, 2026-08-13, ver docs/humano/humano108.md): el
     * comentario original de esta función asumía que "llama-server mantiene su propia
     * conversación con KV-cache, igual que el motor embebido" — falso. El endpoint
     * /v1/chat/completions de llama-server es STATELESS por request (a diferencia del motor
     * JNI embebido, que sí mantiene historial incremental en proceso) — sin reenviar el array
     * de mensajes completo, cada turno perdía el contexto previo Y el system prompt
     * configurado. Ahora usa el mismo `buildSystemMessage()` (persona de SystemPrompts +
     * `cfg["OLLAMA_SYSTEM_PROMPT"]`) + `buildContextMessages()` que ya usa makeOllamaRequest()
     * — mismo comportamiento sin importar qué motor esté activo en el selector de ChatFragment.
     *
     * C6 (humano123): el transporte del streaming (conexión, watchdog de inactividad, abort de
     * stream stale, heartbeat de feedback y reconnect) vive ahora en postStreamingRequest() —
     * esta función solo construye el request y parsea los payloads SSE.
     */
    private fun makeLlamaServerRequest(prompt: String, assistantId: String) {
        try {
            val effectivePrompt = augmentPromptWithWebSearch(prompt)
            val messages = JSONArray()
            // System prompt = persona elegida (SystemPrompts) + custom de Ollama, ver
            // buildSystemMessage() — con la persona por defecto es el comportamiento original.
            buildSystemMessage()?.let { messages.put(it) }
            val contextMessages = buildContextMessages()
            for (i in 0 until contextMessages.length()) messages.put(contextMessages.getJSONObject(i))
            messages.put(JSONObject().apply { put("role", "user"); put("content", effectivePrompt) })

            val body = JSONObject().apply {
                put("model", LLAMASERVER_MODEL)
                put("messages", messages)
                put("stream", true)
            }

            postStreamingRequest(
                urlString = "$LLAMASERVER_URL/v1/chat/completions",
                body = body,
                sse = true,
                onHttpError = { code, errorBody ->
                    val msg = if (errorBody != null) {
                        getString(R.string.chat_llamaserver_http_error_with_body, code, errorBody)
                    } else {
                        getString(R.string.chat_llamaserver_http_error, code)
                    }
                    showError(msg)
                },
                onPayload = { payload ->
                    try {
                        val json = JSONObject(payload)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val chunk = delta?.optString("content", "") ?: ""
                            if (chunk.isNotEmpty()) {
                                val currentId = assistantId
                                mMainHandler.post { appendToAssistant(currentId, chunk) }
                            }
                        }
                    } catch (_: Exception) { }
                    true
                }
            )
        } catch (e: Exception) {
            // Solo errores de construcción del request (antes de abrir la conexión) — los de
            // red/stream los maneja postStreamingRequest internamente (que ya llama
            // finishLoading() en todos sus caminos).
            if (!mCancelled.get()) {
                showError(getString(R.string.chat_llamaserver_connection_error, e.message))
            }
            finishLoading()
        }
    }

    /**
     * Últimos MAX_CONTEXT_TURNS mensajes ya en pantalla, como array de mensajes
     * role/content — NO como texto plano prefijado al prompt. Bug real reportado
     * (ver docs/humano/humano57.md: "ollama termux no guarda los mensajes o contexto pero
     * llama.cpp si"): la versión anterior mandaba todo por /api/generate (endpoint de un
     * solo turno, sin estado) simulando el historial a mano ("User: ...\nAssistant: ...\n"
     * prepended al prompt) — eso NUNCA pasa por el chat template real del modelo, así que
     * modelos instruct-tuned podían ignorar ese texto plano o responder raro, dando la
     * sensación de que "no recuerda". `/api/chat` (usado ahora, ver makeOllamaRequest) sí
     * tiene el concepto real de conversación — Ollama aplica el chat template correcto del
     * modelo sobre el array de mensajes, igual que hace el motor local con su propio
     * historial incremental (ver makeLocalRequest).
     */
    private fun buildContextMessages(): JSONArray {
        val arr = JSONArray()
        if (mMessages.size <= 1) return arr
        // "Mensajes en RAM" configurable desde OllamaConfigFragment ("Historial de chat",
        // paridad con _ollama_config_sql() de menu_nativo.sh) — MAX_CONTEXT_TURNS queda
        // como default hasta que el usuario ajuste un preset ahí.
        val turnsLimit = context?.getSharedPreferences("kairos_llm_prefs", 0)
            ?.getInt("context_turns", MAX_CONTEXT_TURNS) ?: MAX_CONTEXT_TURNS
        val priorTurns = mMessages.dropLast(1) // el último es el mensaje del assistant recién agregado (vacío)
            .filter { it.content.isNotEmpty() }
            .takeLast(turnsLimit)
        for (msg in priorTurns) {
            arr.put(JSONObject().apply {
                put("role", msg.role) // ya es literalmente "user"/"assistant", mismos roles que espera Ollama
                put("content", msg.content)
            })
        }
        return arr
    }

    private fun makeOllamaRequest(prompt: String, assistantId: String, imageBase64: String? = null) {
        try {
            // /api/chat en vez de /api/generate — ver docstring de buildContextMessages().
            val cfg = com.termux.app.util.OllamaApiClient.readConfig()
            val effectivePrompt = if (imageBase64 == null) augmentPromptWithWebSearch(prompt) else prompt

            val messages = JSONArray()
            // System prompt = persona elegida (SystemPrompts) + custom de Ollama, ver
            // buildSystemMessage() — con la persona por defecto es el comportamiento original.
            buildSystemMessage()?.let { messages.put(it) }
            val contextMessages = buildContextMessages()
            for (i in 0 until contextMessages.length()) messages.put(contextMessages.getJSONObject(i))
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", effectivePrompt)
                // Array de strings base64 (sin prefijo "data:image/..."), campo estándar de
                // /api/chat para modelos multimodales — ver docs/referencias/REFERENCIA_OLLAMASERVER.md.
                if (imageBase64 != null) put("images", JSONArray().apply { put(imageBase64) })
            })

            val body = JSONObject().apply {
                put("model", mSelectedModel)
                put("messages", messages)
                put("stream", true)
                put("options", JSONObject().apply {
                    put("temperature", cfg["OLLAMA_TEMP"]?.toDoubleOrNull() ?: 0.7)
                    put("top_p", cfg["OLLAMA_TOP_P"]?.toDoubleOrNull() ?: 0.9)
                    put("top_k", cfg["OLLAMA_TOP_K"]?.toIntOrNull() ?: 40)
                    put("repeat_penalty", cfg["OLLAMA_REP_PENALTY"]?.toDoubleOrNull() ?: 1.1)
                    put("num_ctx", cfg["OLLAMA_NUM_CTX"]?.toIntOrNull() ?: 2048)
                    put("num_predict", cfg["OLLAMA_NUM_PREDICT"]?.toIntOrNull() ?: 2048)
                    // Paridad con llama.cpp's threads/-ngl (OllamaConfigFragment, 2026-08-28) —
                    // a diferencia de temperature/top_p/etc (siempre tienen un valor real por
                    // default), 0 acá es "no tocado" — omitir la key deja que Ollama decida
                    // (auto-detección de núcleos / capas en GPU), en vez de forzar num_gpu=0
                    // (CPU-only real) cuando el usuario nunca configuró el campo.
                    cfg["OLLAMA_NUM_THREAD"]?.toIntOrNull()?.takeIf { it > 0 }?.let { put("num_thread", it) }
                    cfg["OLLAMA_NUM_GPU"]?.toIntOrNull()?.takeIf { it > 0 }?.let { put("num_gpu", it) }
                })
            }

            postStreamingRequest(
                urlString = "$OLLAMA_URL/api/chat",
                body = body,
                sse = false,
                onHttpError = { code, errorBody ->
                    // Bug real confirmado (reporte del usuario, 2026-07-31 — ver docs/humano/humano33.md):
                    // antes esto mostraba "HTTP 404"/"HTTP 400" a secas, sin leer el cuerpo real
                    // del error de Ollama (`{"error": "model \"x\" not found, try pulling it
                    // first"}`) ni explicar qué hacer. Ver LlmErrorMapper.mapOllamaHttp.
                    val serverError = errorBody?.let {
                        try { JSONObject(it).optString("error").takeIf { e -> e.isNotBlank() } } catch (_: Exception) { null }
                    }
                    val friendly = LlmErrorMapper.mapOllamaHttp(code, serverError, mSelectedModel)
                    showError(friendly.message, friendly.technicalDetail)
                },
                onPayload = { payload ->
                    try {
                        val json = JSONObject(payload)
                        // /api/chat anida el texto en "message":{"role":"assistant","content":"..."}
                        // — a diferencia de /api/generate, que tenía "response" en la raíz.
                        if (json.has("message")) {
                            val chunk = json.getJSONObject("message").optString("content", "")
                            if (chunk.isNotEmpty()) {
                                val currentId = assistantId
                                mMainHandler.post { appendToAssistant(currentId, chunk) }
                            }
                        }
                    } catch (_: Exception) { }
                    true
                }
            )
        } catch (e: Exception) {
            // Solo errores de construcción del request (antes de abrir la conexión) — los de
            // red/stream los maneja postStreamingRequest internamente (que ya llama
            // finishLoading() en todos sus caminos).
            if (!mCancelled.get()) {
                showError(e.message ?: getString(R.string.chat_generic_connection_error))
            }
            finishLoading()
        }
    }

    /**
     * C6 (humano123, "resume lifecycle streaming"): transporte común del streaming HTTP para
     * makeLlamaServerRequest (SSE) y makeOllamaRequest (JSON por línea) — antes cada función
     * duplicaba el mismo loop de `reader.readLine()` pelado con tres fallas reales:
     *
     *  1. Sin watchdog de inactividad — si el servidor moría a mitad de un stream (proceso
     *     caído, sleep del SoC, pico de CPU) la lectura quedaba bloqueada hasta el readTimeout
     *     y el usuario veía "Error de conexión" genérico, sin distinguir un stream stale de una
     *     red caída. Ahora un hilo watchdog monitorea el tiempo desde el último chunk: si pasa
     *     STREAM_STALL_TIMEOUT_MS sin datos se llama mActiveConnection.disconnect() — que SÍ
     *     interrumpe una lectura bloqueada de verdad (mismo mecanismo documentado en
     *     cancelRequest()) — y se reporta "stream cortado: el servidor dejó de responder".
     *
     *  2. Sin feedback de vida (heartbeat) — con modelos lentos la barra de cancelación
     *     quedaba congelada en "procesando..." sin indicar si seguía generando. Ahora, si el
     *     servidor sigue vivo pero tarda >2s entre chunks, se actualiza el texto de mCancelText
     *     con cuántos segundos llevan sin nuevos tokens.
     *
     *  3. Sin reconnect — una caída de red justo al iniciar mataba el mensaje completo. Ahora
     *     si la conexión falla ANTES de recibir ningún payload (chunksSeen == 0), se reintenta
     *     una vez (STREAM_MAX_ATTEMPTS); si ya llegaron tokens no se reintenta (duplicaría
     *     contenido) y se reporta el corte con detalle.
     *
     * El caller NO debe llamar finishLoading() ni en el camino normal ni en el de error: esta
     * función lo invoca siempre (el caller solo cierra con un `finally { finishLoading() }`
     * defensivo para cubrir excepciones de construcción del request, ver makeOllamaRequest).
     *
     * @param urlString  endpoint completo (llama-server /v1/chat/completions u Ollama /api/chat)
     * @param body       JSON de request ya construido (stream=true)
     * @param sse        true = formato "data: {...}" con "[DONE]" final (llama-server);
     *                   false = un JSON plano por línea (Ollama)
     * @param onHttpError callback cuando el servidor responde código != 200 — el caller decide
     *                   cómo mostrarlo (LlmErrorMapper para Ollama, crudo para llama-server).
     *                   NO debe llamar finishLoading() (lo hace esta función después).
     * @param onPayload  callback por cada payload (ya sin el prefijo "data:" en modo SSE).
     *                   Debe retornar true para seguir leyendo; false corta el stream de forma
     *                   controlada (el [DONE] de SSE se maneja aquí, no en el caller).
     * @return true si el stream terminó de forma normal (payloads completos o [DONE]); false si
     *         se abortó (cancelación, HTTP != 200, stream stale, red caída) o se agotaron los
     *         reintentos. En ambos casos finishLoading() ya fue llamado aquí.
     */
    private fun postStreamingRequest(
        urlString: String,
        body: JSONObject,
        sse: Boolean,
        onHttpError: (code: Int, errorBody: String?) -> Unit,
        onPayload: (String) -> Boolean
    ): Boolean {
        var attempt = 0
        var chunksSeen = 0
        while (attempt < STREAM_MAX_ATTEMPTS) {
            attempt++
            var conn: HttpURLConnection? = null
            var watchdog: Thread? = null
            // Stream stale: lo marca el watchdog y lo consultan los catch (disconnect() del
            // watchdog lanza IOException en el reader que cae acá). Declarado fuera del try
            // para que sea visible en los catch.
            val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val url = URL(urlString)
                conn = url.openConnection() as HttpURLConnection
                mActiveConnection = conn
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = STREAM_READ_TIMEOUT_MS

                val os = conn.outputStream
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.close()

                val code = conn.responseCode
                if (code != 200) {
                    val errorBody = try {
                        conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    } catch (_: Exception) { null }
                    onHttpError(code, errorBody)
                    finishLoading()
                    return false
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))

                // ── Watchdog de inactividad (abort de stream stale + heartbeat) ──
                var lastChunkAt = System.currentTimeMillis()
                watchdog = Thread {
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            Thread.sleep(1000)
                            if (mCancelled.get()) break
                            val idle = System.currentTimeMillis() - lastChunkAt
                            if (idle >= STREAM_STALL_TIMEOUT_MS && chunksSeen > 0) {
                                // Stream stale: el servidor dejó de mandar datos a mitad de un
                                // stream que ya arrancó → abort de la lectura bloqueada.
                                stalled.set(true)
                                mActiveConnection?.disconnect()
                                break
                            } else if (idle >= 2000) {
                                // Heartbeat visual: el server sigue vivo pero lento.
                                val secs = idle / 1000
                                mMainHandler.post { mCancelText.text = getString(R.string.chat_streaming_heartbeat, secs) }
                            }
                        }
                    } catch (_: InterruptedException) { }
                }
                watchdog.start()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    lastChunkAt = System.currentTimeMillis()
                    if (mCancelled.get()) {
                        reader.close()
                        finishLoading()
                        return false
                    }
                    val l = line ?: continue
                    if (l.isEmpty()) continue
                    val payload: String
                    if (sse) {
                        if (!l.startsWith("data:")) continue
                        payload = l.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                    } else {
                        payload = l
                    }
                    chunksSeen++
                    if (!onPayload(payload)) break
                }
                watchdog.interrupt()
                reader.close()

                if (stalled.get()) {
                    // Distinguir "stream colgado" de "red caída" — antes ambos eran
                    // "Error de conexión" sin matiz (falla #1 de C6).
                    showError(
                        getString(R.string.chat_stream_stale, STREAM_STALL_TIMEOUT_MS / 1000),
                        "SSE stale abortado por watchdog (sin payloads desde hace ${STREAM_STALL_TIMEOUT_MS / 1000} s)"
                    )
                    finishLoading()
                    return false
                }
                finishLoading()
                // Terminó limpiamente: por [DONE] (SSE), por break de onPayload o por EOF
                // (caso normal de Ollama, que cierra el stream sin centinela).
                return true
            } catch (e: SocketTimeoutException) {
                watchdog?.interrupt()
                if (mCancelled.get()) {
                    finishLoading()
                    return false
                }
                if (stalled.get()) {
                    // El watchdog ya abortó el stream stale y disparó la IOException que
                    // llegó acá — mismo reporte que el camino normal.
                    showError(
                        getString(R.string.chat_stream_stale, STREAM_STALL_TIMEOUT_MS / 1000),
                        "SSE stale abortado por watchdog (SocketTimeoutException, sin payloads desde hace ${STREAM_STALL_TIMEOUT_MS / 1000} s)"
                    )
                    finishLoading()
                    return false
                }
                // Reconnect: si aún no llegó NINGÚN payload, la red caía al inicio (o el
                // servidor tardó más que el readTimeout en arrancar) — reintentar una vez
                // (falla #3 de C6). No se reintenta si ya hubo tokens: duplicaría contenido.
                if (chunksSeen == 0 && attempt < STREAM_MAX_ATTEMPTS) {
                    mMainHandler.post { mCancelText.text = getString(R.string.chat_reconnecting, attempt, STREAM_MAX_ATTEMPTS) }
                    continue
                }
                showError(
                    if (chunksSeen > 0) getString(R.string.chat_stream_timeout_with_data)
                    else getString(R.string.chat_stream_timeout_no_data),
                    "SocketTimeoutException en postStreamingRequest (chunksSeen=$chunksSeen)"
                )
                finishLoading()
                return false
            } catch (e: Exception) {
                watchdog?.interrupt()
                if (mCancelled.get()) {
                    finishLoading()
                    return false
                }
                if (stalled.get()) {
                    // Camino principal del abort stale: el watchdog llama disconnect(), el
                    // reader bloqueado en readLine() recibe la IOException y cae acá.
                    showError(
                        getString(R.string.chat_stream_stale, STREAM_STALL_TIMEOUT_MS / 1000),
                        "SSE stale abortado por watchdog: ${e.message} (chunksSeen=$chunksSeen)"
                    )
                    finishLoading()
                    return false
                }
                // Reconnect para redes que se caen justo al conectar (sin payloads aún).
                if (chunksSeen == 0 && attempt < STREAM_MAX_ATTEMPTS) {
                    mMainHandler.post { mCancelText.text = getString(R.string.chat_reconnecting, attempt, STREAM_MAX_ATTEMPTS) }
                    continue
                }
                showError(
                    if (chunksSeen > 0) getString(R.string.chat_stream_error_with_data)
                    else getString(R.string.chat_stream_error_generic, e.message),
                    "postStreamingRequest: ${e.javaClass.simpleName} (chunksSeen=$chunksSeen)"
                )
                finishLoading()
                return false
            } finally {
                watchdog?.interrupt()
                conn?.disconnect()
                if (mActiveConnection === conn) mActiveConnection = null
            }
        }
        finishLoading()
        return false
    }

    /**
     * Separa la cadena de pensamiento (<think>...</think>) de la respuesta
     * real antes de mostrarla — sin esto, un modelo razonador (DeepSeek-R1,
     * QwQ, ...) mostraría su monólogo interno crudo mezclado con la
     * respuesta. Ver ThinkStreamParser y
     * docs/referencias/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md.
     */
    private fun appendToAssistant(assistantId: String, rawChunk: String) {
        for (i in mMessages.indices) {
            val msg = mMessages[i]
            if (msg.id == assistantId) {
                val parsed = mThinkParser.feed(rawChunk)
                if (parsed.thoughtDelta.isNotEmpty()) msg.thought += parsed.thoughtDelta
                if (parsed.answerDelta.isNotEmpty()) msg.content += parsed.answerDelta
                msg.isThinking = parsed.isThinking
                checkPermissionMarker(assistantId, msg)
                mAdapter.notifyItemChanged(i)
                scrollToBottom()
                break
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        mLoading = loading
        mMainHandler.post {
            mCancelBar.visibility = if (loading) View.VISIBLE else View.GONE
            mCancelSpinner.visibility = if (loading) View.VISIBLE else View.GONE
            val engineLabel = if (isLocalModel(mSelectedModel)) getString(R.string.chat_engine_local_label) else getString(R.string.chat_engine_ollama_label)
            mCancelText.text = getString(R.string.chat_processing_status, engineLabel)
            updateSendButton()
        }
    }

    private fun finishLoading() {
        mMainHandler.post {
            mLoading = false
            mCancelBar.visibility = View.GONE
            updateSendButton()
            // Recorta ANTES de persistir, no después — así lo que se escribe a disco ya
            // respeta el límite configurado (ver enforceHistoryLimit).
            enforceHistoryLimit()
            // Se persiste en el hilo principal (mismo hilo que muta mMessages) y recién acá
            // (mensaje completo), no en cada chunk de appendToAssistant — guardar token a
            // token sería I/O innecesario sin aportar nada a la UX, y persistir desde el
            // hilo de red sería una lectura concurrente de mMessages sin sincronizar.
            persistHistory()
            // Follow-up queue: el request en curso terminó (éxito, error o cancelación) —
            // si hay mensajes encolados, se envía el siguiente automáticamente.
            drainPendingQueue()
        }
    }

    // Bug real (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §1.9): Thread.interrupt() no desbloquea una lectura ya bloqueada en Socket/
    // HttpURLConnection (reader.readLine() en makeOllamaRequest/makeLlamaServerRequest) — si
    // el servidor dejaba de mandar datos a mitad de un stream, "Cancelar" no cortaba nada
    // hasta el próximo chunk o el readTimeout (30s). mActiveConnection.disconnect() sí
    // interrumpe una lectura bloqueada de verdad (el reader recibe IOException).
    private fun cancelRequest() {
        mCancelled.set(true)
        mRequestThread?.apply { if (isAlive) interrupt() }
        mActiveConnection?.disconnect()
        mMainHandler.post { mCancelText.text = getString(R.string.chat_cancelling) }
        // Follow-up queue: al cancelar el request en curso se ofrece descartar también los
        // mensajes encolados (con confirmación, ver confirmDiscardQueue).
        confirmDiscardQueue()
    }

    // ── Follow-up queue (Composer docks) ────────────────────────────────────────────────

    /** Fila "⏳ N mensaje(s) en cola" debajo del input (ver mPendingQueue) — UI programática,
     *  mismo patrón que mPersonaLabel/buildEngineSelectorView(). Oculto hasta que hay 1+.
     */
    private fun buildQueueRow(): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(2))
            visibility = View.GONE
        }
        mQueueText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            setPadding(dp(10), dp(2), dp(10), dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener { clearPendingQueue() }
        }
        row.addView(mQueueText)
        row.addView(clearBtn)
        return row
    }

    /** Refresca la fila "⏳ N en cola" — visible solo si hay mensajes pendientes. */
    private fun updateQueueIndicator() {
        if (!::mQueueRow.isInitialized) return
        val n = mPendingQueue.size
        mQueueRow.visibility = if (n > 0) View.VISIBLE else View.GONE
        mQueueText.text = if (n > 0) {
            if (n == 1) getString(R.string.chat_queue_count_singular, n) else getString(R.string.chat_queue_count_plural, n)
        } else ""
    }

    /** Vacía la cola directamente (botón "✕" de la fila). */
    private fun clearPendingQueue() {
        mPendingQueue.clear()
        updateQueueIndicator()
        toast(getString(R.string.chat_queue_cleared_toast))
    }

    /**
     * Saca el siguiente mensaje encolado (ver mPendingQueue) y lo envía — se llama desde
     * finishLoading(), cuando el request en curso ya terminó. Guard `isWorking`: si un
     * finishLoading() espurio llegara con otro request ya arrancado, no se reenvía (evita
     * duplicar). Los mensajes de la cola no llevan imagen (ver sendMessage()).
     */
    private fun drainPendingQueue() {
        if (!isAdded) return
        if (mPendingQueue.isEmpty()) {
            updateQueueIndicator()
            return
        }
        if (isWorking) return
        val next = mPendingQueue.removeAt(0)
        updateQueueIndicator()
        dispatchMessage(next, null)
    }

    /**
     * Al cancelar el request en curso (ver cancelRequest()) se ofrece descartar también los
     * mensajes pendientes de la cola — no se vacía en silencio para no perder texto que el
     * usuario ya escribió. La "✕" de la fila de cola (ver updateQueueIndicator) sí vacía
     * directo.
     */
    private fun confirmDiscardQueue() {
        if (!isAdded) return
        val count = mPendingQueue.size
        if (count == 0) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.chat_discard_queue_title))
            .setMessage(
                if (count == 1) getString(R.string.chat_discard_queue_message_singular, count)
                else getString(R.string.chat_discard_queue_message_plural, count)
            )
            .setPositiveButton(getString(R.string.chat_discard_yes)) { _, _ -> clearPendingQueue() }
            .setNegativeButton(getString(R.string.chat_keep), null)
            .show()
    }

    // ── Permission dock (Composer docks, adaptación pragmática) ─────────────────────────

    /**
     * Gate de permisos (ver hallazgo #4 de AUDITORIA_CATEGORIA_AGENTES.md): Ollama/llama-server
     * NO emiten un "permission.asked" nativo, así que se detecta un marker propio en el stream
     * — cualquier fragmento `[PERMISO]` en la respuesta del modelo. Al aparecer se muestra el
     * diálogo "El agente solicita permiso" (permitir una vez / permitir siempre / denegar).
     * "Permitir siempre" persiste `tool_autoaccept` en kairos_llm_prefs; con él activo el
     * diálogo se saltea (acepta en silencio). El marker se quita del contenido visible para
     * que no quede crudo en la burbuja; el texto que le sigue se muestra en el diálogo como
     * descripción del pedido.
     *
     * Corre en el hilo de UI: appendToAssistant() siempre llega vía mMainHandler.post (los 3
     * callers — motor local, Ollama, llama-server — hacen el post antes de llamarla).
     */
    private fun checkPermissionMarker(assistantId: String, msg: ChatMessage) {
        val idx = msg.content.indexOf(TOOL_PERMISSION_MARKER)
        if (idx < 0) return
        // Lo que sigue al marker es la descripción del pedido para el diálogo; el marker (y
        // lo que había antes) se limpia de la burbuja.
        val request = msg.content.substring(idx + TOOL_PERMISSION_MARKER.length).trim()
        msg.content = msg.content.substring(0, idx).trimEnd()
        if (assistantId in mPermissionPrompted) return
        mPermissionPrompted.add(assistantId)
        val ctx = context ?: return
        val autoAccept = ctx.getSharedPreferences("kairos_llm_prefs", 0)
            .getBoolean(TOOL_AUTOACCEPT_KEY, false)
        if (autoAccept) return
        showToolPermissionDialog(request)
    }

    /** Diálogo "El agente solicita permiso" — 3 botones, ver checkPermissionMarker(). */
    private fun showToolPermissionDialog(request: String) {
        val ctx = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.chat_permission_dialog_title))
            .setMessage(
                if (request.isNotBlank()) getString(R.string.chat_permission_message_with_request, request)
                else getString(R.string.chat_permission_message_no_request)
            )
            .setPositiveButton(getString(R.string.chat_permit_once)) { d, _ -> d.dismiss() }
            .setNeutralButton(getString(R.string.chat_permit_always)) { d, _ ->
                context?.getSharedPreferences("kairos_llm_prefs", 0)
                    ?.edit()?.putBoolean(TOOL_AUTOACCEPT_KEY, true)?.apply()
                toast(getString(R.string.chat_permissions_auto_accepted_toast))
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.chat_deny)) { d, _ ->
                toast(getString(R.string.chat_permission_denied_toast))
                d.dismiss()
            }
            .show()
    }

    private fun clearHistory() {
        mMessages.clear()
        mAdapter.notifyDataSetChanged()
        mErrorBar.visibility = View.GONE
        mMessageCount.text = ""
        // "Limpiar" antes solo vaciaba la lista en RAM — el archivo en disco seguía
        // existiendo y reaparecía todo al volver a abrir el chat.
        context?.let { ChatHistoryStore.clear(it) }
    }

    /**
     * [technicalDetail] es el mensaje crudo original (excepci\u00f3n nativa,
     * c\u00f3digo HTTP, etc.) \u2014 nunca se descarta, solo queda colapsado detr\u00e1s de
     * "Ver detalles" (ver LlmErrorMapper). Si es null (errores que ya son de
     * Kairos, ej. "HTTP 500"), el toggle queda oculto: no hay nada m\u00e1s
     * t\u00e9cnico para mostrar que no est\u00e9 ya en el mensaje principal.
     */
    private fun showError(message: String, technicalDetail: String? = null) {
        mMainHandler.post {
            mErrorText.text = getString(R.string.chat_error_prefix, message)
            mErrorBar.visibility = View.VISIBLE
            val hasDetail = !technicalDetail.isNullOrBlank() && technicalDetail != message
            mErrorToggleDetail.visibility = if (hasDetail) View.VISIBLE else View.GONE
            mErrorToggleDetail.text = getString(R.string.chat_toggle_show_details)
            mErrorDetail.text = technicalDetail ?: ""
            mErrorDetail.visibility = View.GONE
        }
    }

    private fun scrollToBottom() {
        if (mMessages.isNotEmpty()) {
            mRecycler.smoothScrollToPosition(mMessages.size - 1)
        }
        val count = mMessages.size
        mMessageCount.text = if (count == 1) getString(R.string.chat_message_count_singular, count) else getString(R.string.chat_message_count_plural, count)
    }

    private data class ChatMessage(
        val id: String,
        val role: String,
        var content: String,
        val ts: Long,
        val model: String?,
        // Cadena de pensamiento (<think>...</think>) separada del contenido real
        // por ThinkStreamParser — nunca se persiste entre sesiones (solo vive
        // mientras el chat está abierto), ver ChatHistoryStore.
        var thought: String = "",
        var isThinking: Boolean = false,
        var thoughtExpanded: Boolean = false,
        // Imagen adjunta (solo mensajes "user", solo Ollama remoto) — base64 crudo, mismo
        // formato que se manda en el campo "images" de la request. Sí se persiste (ver
        // ChatHistoryStore) porque el límite de historial (enforceHistoryLimit) acota su
        // crecimiento — no hace falta un cache de imágenes aparte.
        var imageBase64: String? = null,
    ) {
        // Cache de la miniatura ya decodificada — evita re-decodificar el mismo base64 en
        // cada scroll (RecyclerView re-bindea el holder cada vez que la fila vuelve a
        // quedar visible). Vive fuera del constructor primario a propósito: es puro cache
        // de UI, no participa de equals/hashCode/copy como el resto de los campos.
        var decodedThumb: Bitmap? = null
    }

    private class ViewHolderUser(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val content: TextView = itemView.findViewById(R.id.bubble_content)
        val roleLabel: TextView = itemView.findViewById(R.id.bubble_role)
        val timestamp: TextView = itemView.findViewById(R.id.bubble_ts)
        val image: ImageView = itemView.findViewById(R.id.bubble_image)
    }

    private class ViewHolderAssistant(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val content: TextView = itemView.findViewById(R.id.bubble_content)
        val roleLabel: TextView = itemView.findViewById(R.id.bubble_role)
        val timestamp: TextView = itemView.findViewById(R.id.bubble_ts)
        val thought: TextView = itemView.findViewById(R.id.bubble_thought)
    }

    private inner class ChatAdapter(val messages: List<ChatMessage>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_USER = 0
        private val TYPE_ASSISTANT = 1

        override fun getItemViewType(position: Int): Int {
            return if (messages[position].role == "user") TYPE_USER else TYPE_ASSISTANT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_USER) {
                ViewHolderUser(inflater.inflate(R.layout.item_chat_user, parent, false))
            } else {
                ViewHolderAssistant(inflater.inflate(R.layout.item_chat_assistant, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val msg = messages[position]
            when (holder) {
                is ViewHolderUser -> {
                    holder.content.text = msg.content
                    holder.roleLabel.text = getString(R.string.chat_role_user)
                    holder.timestamp.text = formatTime(msg.ts)
                    bindUserImage(holder, msg)
                }
                is ViewHolderAssistant -> {
                    val stillWaiting = msg.content.isEmpty() && !msg.isThinking
                    holder.content.text = if (stillWaiting) getString(R.string.chat_waiting_ellipsis) else msg.content
                    holder.roleLabel.text = "\u2B21 ${msg.model?.uppercase() ?: getString(R.string.chat_role_assistant_fallback)}"
                    holder.timestamp.text = formatTime(msg.ts)
                    bindThought(holder, msg, position)
                }
            }
        }

        override fun getItemCount() = messages.size

        /** Miniatura de la imagen adjunta (solo mensajes "user", ver ChatMessage.imageBase64) — usa msg.decodedThumb como cache para no re-decodificar el base64 en cada scroll. */
        private fun bindUserImage(holder: ViewHolderUser, msg: ChatMessage) {
            val b64 = msg.imageBase64
            if (b64.isNullOrEmpty()) {
                holder.image.visibility = View.GONE
                holder.image.setImageBitmap(null)
                return
            }
            var bmp = msg.decodedThumb
            if (bmp == null) {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                msg.decodedThumb = bmp
            }
            holder.image.visibility = View.VISIBLE
            holder.image.setImageBitmap(bmp)
        }

        /**
         * Muestra el pensamiento del modelo colapsado por defecto (patrón
         * "Show Technical Details" de PrivateLM, ver
         * docs/referencias/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md) — un resumen corto que
         * se expande a texto completo al tocarlo. Oculto del todo si el
         * modelo no emitió ningún <think>.
         */
        private fun bindThought(holder: ViewHolderAssistant, msg: ChatMessage, position: Int) {
            if (msg.thought.isEmpty()) {
                holder.thought.visibility = View.GONE
                holder.thought.setOnClickListener(null)
                return
            }
            holder.thought.visibility = View.VISIBLE
            val livePrefix = if (msg.isThinking) getString(R.string.chat_thinking_live) else getString(R.string.chat_thinking_done)
            holder.thought.text = if (msg.thoughtExpanded) {
                getString(R.string.chat_thought_expanded, livePrefix, msg.thought)
            } else {
                val preview = msg.thought.take(60).replace("\n", " ").trim()
                getString(R.string.chat_thought_collapsed, livePrefix, "$preview${if (msg.thought.length > 60) "…" else ""}")
            }
            holder.thought.setOnClickListener {
                msg.thoughtExpanded = !msg.thoughtExpanded
                notifyItemChanged(position)
            }
        }
    }

    private fun formatTime(ts: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        return sdf.format(Date(ts))
    }
}

/**
 * Separa la cadena de pensamiento (<think>...</think>) de modelos
 * razonadores (DeepSeek-R1, QwQ, ...) de la respuesta real, token a token,
 * mientras llega el streaming. Portado de thought_parser.dart
 * (cross-platform-llm-client/"PrivateLM", MIT — ver
 * docs/referencias/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md, "~45 líneas trivialmente
 * portables").
 *
 * Streaming-aware: la etiqueta puede llegar partida entre dos chunks (ej.
 * "<th" en un chunk y "ink>" en el siguiente) — por eso acumula un buffer
 * chico en vez de mirar cada chunk aislado. Nunca asume que el chunk actual
 * es autocontenido.
 */
class ThinkStreamParser {
    companion object {
        private const val OPEN_TAG = "<think>"
        private const val CLOSE_TAG = "</think>"
    }

    data class Parsed(val thoughtDelta: String, val answerDelta: String, val isThinking: Boolean)

    private val buffer = StringBuilder()
    private var inThinking = false

    /** Alimenta un chunk nuevo y devuelve qué parte de pensamiento/respuesta ya se puede emitir sin ambigüedad de tag partido. */
    fun feed(chunk: String): Parsed {
        buffer.append(chunk)
        val thoughtOut = StringBuilder()
        val answerOut = StringBuilder()

        while (true) {
            val tag = if (inThinking) CLOSE_TAG else OPEN_TAG
            val tagIdx = buffer.indexOf(tag)
            if (tagIdx >= 0) {
                val before = buffer.substring(0, tagIdx)
                if (inThinking) thoughtOut.append(before) else answerOut.append(before)
                buffer.delete(0, tagIdx + tag.length)
                inThinking = !inThinking
                continue
            }
            // Sin match todavía — puede ser el principio partido del tag, así que
            // se retiene una cola del tamaño del tag por si el próximo chunk lo
            // completa, y se emite el resto ya seguro.
            val safeLen = (buffer.length - (tag.length - 1)).coerceAtLeast(0)
            val safe = buffer.substring(0, safeLen)
            if (inThinking) thoughtOut.append(safe) else answerOut.append(safe)
            buffer.delete(0, safeLen)
            break
        }
        return Parsed(thoughtOut.toString(), answerOut.toString(), inThinking)
    }
}
