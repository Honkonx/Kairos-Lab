package com.termux.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
 *
 * Mejoras 2026-09-01 (ver docs/arquitectura/INVESTIGACION_NAVEGADORES_OSS_2026-09-01.md,
 * investigación de UX de navegadores OSS como Brave/Firefox Android — no se clonó código,
 * solo ideas de diseño para este visor de WebUIs locales): barra de progreso delgada con
 * color de acento (patrón de barra fina en la parte superior, no un spinner genérico),
 * pantalla de error propia con botón "Reintentar" cuando se agotan los reintentos
 * automáticos (en vez de dejar la página en blanco/el error crudo del WebKit), pull-to-refresh
 * habilitado solo cuando el scroll está arriba del todo (gotcha real de SwipeRefreshLayout +
 * WebView: si no se chequea el scroll, un swipe hacia abajo DENTRO de una página larga dispara
 * un refresh no deseado), y menú ⋮ con recargar/copiar URL/abrir en navegador externo.
 */
class ModuleWebViewFragment : Fragment() {

    private var webView: WebView? = null
    private var addressBar: TextView? = null
    private var webBackBtn: ImageButton? = null
    private var webForwardBtn: ImageButton? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var errorView: View? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var retriesLeft = 5

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val url = requireArguments().getString(ARG_URL) ?: ""
        val title = requireArguments().getString(ARG_TITLE) ?: "Web"
        val accentColor = ctx.kairosThemeColor(R.attr.kairosBlue)
        // Origen real del módulo (típicamente 127.0.0.1:<puerto>) — usado por
        // shouldOverrideUrlLoading() más abajo (hallazgo 3, docs/estructura/
        // INVESTIGACION_TERMINAL_PERSONALIZACION_2026-09-01.md) para distinguir navegación
        // interna del propio módulo de un salto a un dominio externo.
        val originUri = Uri.parse(url)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }

        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // ── Barra superior: cerrar · título · atrás/adelante/recargar · menú ⋮ ──
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
        val menuBtn = TextView(ctx).apply {
            text = "⋮" // ⋮ — mismo patrón de íconos-texto que usa el resto de la UI de Kairos
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            isClickable = true
            isFocusable = true
            setOnClickListener { showOverflowMenu(it, ctx) }
        }
        topBar.addView(menuBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
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

        // ── WebView + pull-to-refresh + barra de progreso + estado de error ──
        val webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accentColor)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, dp(3), Gravity.TOP)
        }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                // Sandboxing real del WebView interno (hallazgo 3 de la investigación citada
                // arriba): sin este override, un link/redirect a un dominio externo (ej. un
                // "Powered by X" con link de marketing, o un OAuth redirect a un proveedor
                // externo) cargaba DENTRO del mismo WebView del módulo — técnicamente
                // cualquier dominio externo podía terminar renderizado ahí, sin la barra de
                // direcciones/controles de seguridad de un navegador real. Deja pasar
                // cualquier request que siga siendo del mismo host+puerto que webviewUrl
                // (navegación normal del módulo, incluida su propia UI SPA); cualquier otro
                // origen se lanza al navegador del sistema y se bloquea la carga interna.
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val requestUri = request?.url ?: return false
                    if (isSameOrigin(requestUri, originUri)) return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, requestUri))
                        true
                    } catch (e: ActivityNotFoundException) {
                        // Sin navegador externo disponible — no hay adónde mandarlo, pero
                        // tampoco se lo deja cargar adentro (return true igual bloquea la
                        // carga interna, consistente con el objetivo de este sandboxing).
                        Toast.makeText(ctx, R.string.webview_toast_no_external_browser, Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, loadedUrl, favicon)
                    addressBar?.text = loadedUrl ?: view?.url ?: ""
                    // Un nuevo intento de carga arrancó (manual o automático) — ocultar el
                    // estado de error previo, si había quedado visible.
                    setErrorVisible(false)
                }
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    addressBar?.text = loadedUrl ?: view?.url ?: ""
                    updateNavButtons()
                    swipeRefresh?.isRefreshing = false
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
                    if (request?.isForMainFrame != true) return
                    swipeRefresh?.isRefreshing = false
                    if (retriesLeft <= 0) {
                        // Reintentos automáticos agotados — mostrar estado de error real con
                        // botón "Reintentar" en vez de dejar la pantalla en blanco (mejora de
                        // UX inspirada en la página de error propia de Firefox/Brave Android,
                        // ver doc de investigación citado arriba).
                        setErrorVisible(true)
                        return
                    }
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
            // Pull-to-refresh solo debe activarse cuando el WebView está scrolleado hasta
            // arriba del todo — si no se chequea esto, SwipeRefreshLayout no sabe distinguir
            // "el usuario quiere refrescar" de "el usuario está scrolleando el contenido de la
            // página hacia abajo", y termina disparando un refresh no deseado a mitad de
            // página (gotcha documentado en la investigación de GeckoView/Fenix — ver doc).
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
        webContainer.addView(buildErrorView(ctx, ::dp).also { errorView = it })
        root.addView(webContainer)

        if (url.isNotEmpty()) wv.loadUrl(url)
        return root
    }

    /** Pantalla de error propia (icono + título + descripción + botón "Reintentar"),
     * oculta por defecto — solo aparece cuando los reintentos automáticos se agotan. */
    private fun buildErrorView(ctx: Context, dp: (Int) -> Int): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            addView(TextView(ctx).apply {
                text = "⚠"
                textSize = 40f
                gravity = Gravity.CENTER
                setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
            })
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.webview_error_title)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.webview_error_desc)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, dp(6), 0, dp(20))
            })
            addView(Button(ctx).apply {
                text = ctx.getString(R.string.webview_error_retry)
                setOnClickListener {
                    retriesLeft = 5
                    setErrorVisible(false)
                    webView?.reload()
                }
            })
        }
    }

    /** Mismo host+puerto que [origin] (comparando puerto explícito o el default del esquema
     *  cuando alguno de los dos lo omite) — ver shouldOverrideUrlLoading(). */
    private fun isSameOrigin(candidate: Uri, origin: Uri): Boolean {
        if (origin.host.isNullOrEmpty()) return true // sin origen conocido, no debería pasar — no bloquear
        val candidatePort = if (candidate.port != -1) candidate.port else defaultPortFor(candidate.scheme)
        val originPort = if (origin.port != -1) origin.port else defaultPortFor(origin.scheme)
        return candidate.host == origin.host && candidatePort == originPort
    }

    private fun defaultPortFor(scheme: String?): Int = if (scheme == "https") 443 else 80

    private fun setErrorVisible(visible: Boolean) {
        errorView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showOverflowMenu(anchor: View, ctx: Context) {
        val popup = PopupMenu(ctx, anchor)
        popup.menu.add(0, MENU_RELOAD, 0, ctx.getString(R.string.webview_menu_reload))
        popup.menu.add(0, MENU_COPY_URL, 1, ctx.getString(R.string.webview_menu_copy_url))
        popup.menu.add(0, MENU_OPEN_EXTERNAL, 2, ctx.getString(R.string.webview_menu_open_external))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RELOAD -> webView?.reload()
                MENU_COPY_URL -> copyCurrentUrl(ctx)
                MENU_OPEN_EXTERNAL -> openInExternalBrowser(ctx)
            }
            true
        }
        popup.show()
    }

    private fun copyCurrentUrl(ctx: Context) {
        val currentUrl = webView?.url ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", currentUrl))
        Toast.makeText(ctx, R.string.webview_toast_url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openInExternalBrowser(ctx: Context) {
        val currentUrl = webView?.url ?: return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.webview_toast_no_external_browser, Toast.LENGTH_SHORT).show()
        }
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
        swipeRefresh = null
        errorView = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TITLE = "title"
        private const val MENU_RELOAD = 1
        private const val MENU_COPY_URL = 2
        private const val MENU_OPEN_EXTERNAL = 3

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
