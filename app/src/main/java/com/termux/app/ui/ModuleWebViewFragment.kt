package com.termux.app.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.util.kairosThemeColor

/**
 * Muestra la interfaz web de un módulo corriendo localmente (OpenCode, n8n,
 * OpenClaw — cualquiera con "webviewUrl" en modules.json) dentro de la app,
 * en vez de exponer la terminal cruda. Módulo debe estar RUNNING.
 *
 * Bug real reportado ("la terminal web no funciona no inicia"): esta pantalla
 * se abre apenas el script de start del módulo devuelve éxito (tmux
 * has-session tras un `sleep 2` fijo, ver ej. opencode_start.sh) — pero eso
 * solo confirma que la SESIÓN existe, no que el servidor HTTP ya esté
 * escuchando el puerto. En un dispositivo lento, el WebView podía cargar
 * antes de que el server terminara de levantar, mostrar el error de
 * "conexión rechazada" una sola vez y quedarse ahí sin reintentar. Ahora
 * reintenta unas pocas veces con backoff corto antes de rendirse.
 */
class ModuleWebViewFragment : Fragment() {

    private var webView: WebView? = null
    private var addressBar: TextView? = null
    private var webBackBtn: ImageButton? = null
    private var webForwardBtn: ImageButton? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var retriesLeft = 5

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val url = requireArguments().getString(ARG_URL) ?: ""
        val title = requireArguments().getString(ARG_TITLE) ?: "Web"

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }

        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // ── Barra superior: cerrar · título · atrás/adelante · recargar ──
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val closeBtn = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText))
            setOnClickListener { parentFragmentManager.popBackStack() }
        }
        topBar.addView(closeBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        topBar.addView(TextView(ctx).apply {
            text = title
            textSize = 14f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val webBack = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            background = null
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText2))
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { webView?.goBack() }
        }
        topBar.addView(webBack, LinearLayout.LayoutParams(dp(36), dp(36)))
        webBackBtn = webBack
        val webForward = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            background = null
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText2))
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { webView?.goForward() }
        }
        topBar.addView(webForward, LinearLayout.LayoutParams(dp(36), dp(36)))
        webForwardBtn = webForward
        val reloadBtn = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            background = null
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText2))
            setOnClickListener { webView?.reload() }
        }
        topBar.addView(reloadBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(topBar)

        // ── Barra de dirección (solo lectura) ────────────────────
        val addrBar = TextView(ctx).apply {
            text = url
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        root.addView(addrBar)
        addressBar = addrBar

        // ── WebView + barra de progreso ──────────────────────────
        val webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, dp(3), Gravity.TOP)
        }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, loadedUrl, favicon)
                    addressBar?.text = loadedUrl ?: view?.url ?: ""
                }
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    addressBar?.text = loadedUrl ?: view?.url ?: ""
                    updateNavButtons()
                }
                override fun doUpdateVisitedHistory(view: WebView?, historyUrl: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, historyUrl, isReload)
                    // Cubre navegación SPA (ej. rutas internas de n8n) que no siempre
                    // dispara onPageStarted/onPageFinished de vuelta.
                    addressBar?.text = historyUrl ?: view?.url ?: ""
                    updateNavButtons()
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    // Solo reintentar la carga PRINCIPAL (no sub-recursos como CSS/JS/imágenes
                    // rotos, que no significan que el servidor esté caído) y solo si el error
                    // parece de conectividad (server todavía no escuchando), no un 404 real.
                    if (request?.isForMainFrame != true || retriesLeft <= 0) return
                    retriesLeft--
                    retryHandler.postDelayed({
                        if (isAdded) webView?.reload()
                    }, 1200)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            setBackgroundColor(Color.parseColor("#050505"))
        }
        webView = wv
        webContainer.addView(wv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        webContainer.addView(progressBar)
        root.addView(webContainer)

        if (url.isNotEmpty()) wv.loadUrl(url)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Atrás del sistema navega el historial del WebView primero; solo cierra
        // la pantalla (comportamiento previo) cuando ya no hay historial atrás.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    parentFragmentManager.popBackStack()
                }
            }
        })
    }

    private fun updateNavButtons() {
        val wv = webView ?: return
        webBackBtn?.let { it.isEnabled = wv.canGoBack(); it.alpha = if (wv.canGoBack()) 1f else 0.4f }
        webForwardBtn?.let { it.isEnabled = wv.canGoForward(); it.alpha = if (wv.canGoForward()) 1f else 0.4f }
    }

    override fun onDestroyView() {
        webView?.destroy()
        webView = null
        addressBar = null
        webBackBtn = null
        webForwardBtn = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TITLE = "title"

        fun newInstance(url: String, title: String): ModuleWebViewFragment {
            return ModuleWebViewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                    putString(ARG_TITLE, title)
                }
            }
        }
    }
}
