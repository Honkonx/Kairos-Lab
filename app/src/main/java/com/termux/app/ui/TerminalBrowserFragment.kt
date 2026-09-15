package com.termux.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.termux.R
import com.termux.app.util.kairosThemeColor

/**
 * Navegador de propósito general embebido en la terminal adaptada — pedido explícito del
 * usuario (2026-09-08, docs/humano324.md: "el navegador no es poner puertos, debe ser un
 * navegador completo como tal"). Deliberadamente un fragment NUEVO y separado de
 * `ModuleWebViewFragment` en vez de reusarlo/flexibilizarlo: ese fragment tiene un sandboxing de
 * origen a propósito (`shouldOverrideUrlLoading` en `ModuleWebViewFragment.kt` — cualquier link a
 * un dominio distinto del propio módulo se manda al navegador externo) que es una decisión de
 * SEGURIDAD real para su caso de uso (ver WebUI de un módulo), no una limitación a "arreglar" —
 * debilitarla para permitir navegación libre ahí rompería esa garantía para n8n/OpenClaw/
 * OpenCode/GenericModule. Este fragment es de uso general a propósito: sin lock de origen, con
 * barra de dirección EDITABLE (a diferencia de la de solo lectura de ModuleWebViewFragment).
 *
 * Sin barra de título/cierre propia — se embebe dentro de
 * `TermuxActivity#showAdaptedBrowserPanel()`, que ya aporta esa franja (ver
 * activity_termux.xml#terminal_adapted_panel_container).
 */
class TerminalBrowserFragment : Fragment() {

    private var webView: WebView? = null
    private var addressInput: EditText? = null
    private var webBackBtn: ImageButton? = null
    private var webForwardBtn: ImageButton? = null
    private var swipeRefresh: SwipeRefreshLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val startUrl = requireArguments().getString(ARG_START_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOME_URL
        val accentColor = ctx.kairosThemeColor(R.attr.kairosBlue)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }

        // ── Fila de navegación: atrás/adelante/recargar + barra de dirección editable ──
        val navRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            setPadding(dp(4), dp(6), dp(8), dp(6))
        }
        val webBack = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            background = null
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText2))
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { webView?.goBack() }
        }
        navRow.addView(webBack, LinearLayout.LayoutParams(dp(36), dp(36)))
        webBackBtn = webBack
        val webForward = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            background = null
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText2))
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { webView?.goForward() }
        }
        navRow.addView(webForward, LinearLayout.LayoutParams(dp(36), dp(36)))
        webForwardBtn = webForward
        val reloadBtn = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            background = null
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText2))
            setOnClickListener { webView?.reload() }
        }
        navRow.addView(reloadBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        val addrInput = EditText(ctx).apply {
            setText(startUrl)
            textSize = 13f
            maxLines = 1
            isSingleLine = true
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setHintTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            hint = ctx.getString(R.string.hint_terminal_adapted_browser_url)
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigateTo(text.toString())
                    true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
        }
        navRow.addView(addrInput)
        addressInput = addrInput
        root.addView(navRow)

        // ── WebView + pull-to-refresh + barra de progreso ──
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
            // Navegador de propósito general a propósito: SIN shouldOverrideUrlLoading — deja
            // que el WebView navegue libremente a cualquier origen (a diferencia de
            // ModuleWebViewFragment, que sandboxea por diseño — ver KDoc de esta clase).
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, loadedUrl, favicon)
                    if (loadedUrl != null) addressInput?.setText(loadedUrl)
                }
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (loadedUrl != null) addressInput?.setText(loadedUrl)
                    updateNavButtons()
                    swipeRefresh?.isRefreshing = false
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            setBackgroundColor(Color.parseColor("#050505"))
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                swipeRefresh?.isEnabled = scrollY == 0
            }
        }
        webView = wv

        val swipe = SwipeRefreshLayout(ctx).apply {
            setColorSchemeColors(accentColor)
            setOnRefreshListener { webView?.reload() }
            addView(wv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        swipeRefresh = swipe
        webContainer.addView(swipe, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        webContainer.addView(progressBar)
        root.addView(webContainer)

        navigateTo(startUrl)
        return root
    }

    private fun navigateTo(rawUrl: String) {
        var url = rawUrl.trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // Sin esquema — heurística simple tipo barra de direcciones real: si "parece" un
            // dominio (tiene un punto, sin espacios), navega directo; si no, lo trata como
            // búsqueda. Mismo criterio liviano que usan la mayoría de navegadores móviles.
            url = if (url.contains(' ') || !url.contains('.')) {
                "https://www.google.com/search?q=" + Uri.encode(url)
            } else {
                "https://$url"
            }
        }
        webView?.loadUrl(url)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Atrás del sistema navega el historial del WebView primero — cuando ya no hay historial,
        // se desactiva este callback para que el evento siga su curso normal (TermuxActivity
        // cierra el panel embebido, ver TermuxActivity#onBackPressed()/hideAdaptedPanel()).
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
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
        addressInput = null
        webBackBtn = null
        webForwardBtn = null
        swipeRefresh = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_START_URL = "start_url"
        private const val DEFAULT_HOME_URL = "https://www.google.com"

        /** [startUrl] vacío o null cae al buscador por defecto — el llamador precarga la URL
         *  conocida del módulo activo cuando la reconoce (ver
         *  TermuxActivity#showAdaptedBrowserPanel()), o la deja vacía para que el usuario
         *  navegue libremente desde la página de inicio. */
        fun newInstance(startUrl: String?): TerminalBrowserFragment {
            return TerminalBrowserFragment().apply {
                arguments = Bundle().apply { putString(ARG_START_URL, startUrl) }
            }
        }
    }
}
