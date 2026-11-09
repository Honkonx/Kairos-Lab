package com.termux.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.termux.app.util.applyTermuxEnv
import kotlin.math.abs

/**
 * Overlay flotante de escaneo de Ciberseguridad en vivo — hallazgo real de
 * MEJORAS_PENDIENTES.md ("Overlay flotante de escaneo de Ciberseguridad en vivo... extiende el
 * patrón ya existente de FloatingWidgetService.kt con un servicio hermano nuevo que muestra el
 * stdout en vivo de un escaneo (nmap/nikto/sqlmap/theHarvester) lanzado desde la pantalla de
 * Ciberseguridad, sin volver a abrir la app"). Burbuja arrastrable + panel con la salida línea
 * por línea en vivo (a diferencia de FloatingWidgetService, que solo lee estado ya resuelto) +
 * botón "Detener".
 *
 * Alcance real de esta ronda (2026-11-09): solo `nmap` tiene el botón "Minimizar y ver en vivo"
 * en CiberseguridadFragment.kt que arranca este servicio — nikto/sqlmap/theHarvester quedan con
 * su diálogo bloqueante de siempre (`runQuickScan()`), reusar este MISMO servicio para ellos es
 * un cambio de UI puntual en una ronda futura, no una limitación de este servicio en sí (solo
 * recibe `EXTRA_CMD`/`EXTRA_TITLE`, es agnóstico al comando real).
 *
 * SIN VERIFICAR EN DISPOSITIVO REAL (ver kairos-minipc-empirical-verification.md -- este no es
 * estrictamente Mini PC, pero el mismo criterio de "no cerrar sin evidencia visual real" aplica
 * a cualquier overlay nuevo sobre SYSTEM_ALERT_WINDOW).
 */
class CiberScanOverlayService : Service() {

    companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_TITLE = "title"

        @JvmStatic
        fun start(context: Context, cmd: List<String>, title: String) {
            val intent = Intent(context, CiberScanOverlayService::class.java)
                .putStringArrayListExtra(EXTRA_CMD, ArrayList(cmd))
                .putExtra(EXTRA_TITLE, title)
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var outputBox: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var scanTitle: String = "Escaneo"
    private var panelExpanded = true

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Kairos: falta el permiso de superposición para ver el escaneo en vivo", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubbleView != null) {
            // Ya hay un escaneo en overlay -- un segundo lanzamiento mientras el primero sigue
            // vivo se descarta en vez de pisarlo (evita 2 procesos de escaneo simultáneos sin
            // que el usuario se dé cuenta de cuál ventana corresponde a cuál).
            Toast.makeText(this, "Ya hay un escaneo en vivo en overlay -- cerralo primero", Toast.LENGTH_SHORT).show()
            return START_NOT_STICKY
        }
        scanTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Escaneo"
        @Suppress("DEPRECATION")
        val cmd = intent.getStringArrayListExtra(EXTRA_CMD)
        if (cmd.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        addBubble()
        showPanel()
        runScan(cmd)
        return START_NOT_STICKY
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun addBubble() {
        val ctx = this
        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()

        val bubble = TextView(ctx).apply {
            text = "🛈"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E1A1A"))
        }

        val params = WindowManager.LayoutParams(
            size, size, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 400

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragging = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try { windowManager.updateViewLayout(bubble, params) } catch (_: Exception) {}
                    repositionPanel()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) togglePanel()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(bubble, params)
            bubbleView = bubble
            bubbleParams = params
        } catch (e: Exception) {
            Toast.makeText(ctx, "No se pudo mostrar el overlay de escaneo: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun togglePanel() {
        panelExpanded = !panelExpanded
        panelView?.visibility = if (panelExpanded) View.VISIBLE else View.GONE
    }

    private fun repositionPanel() {
        val density = resources.displayMetrics.density
        val params = panelView?.layoutParams as? WindowManager.LayoutParams ?: return
        bubbleParams?.let {
            params.x = it.x
            params.y = it.y + (48 * density).toInt()
        }
        try { windowManager.updateViewLayout(panelView, params) } catch (_: Exception) {}
    }

    private fun showPanel() {
        val ctx = this
        val density = resources.displayMetrics.density
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E61A1A2E"))
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }

        panel.addView(TextView(ctx).apply {
            text = "🛈 $scanTitle"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 0, (6 * density).toInt())
        })

        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                (280 * density).toInt(), (220 * density).toInt()
            )
        }
        panel.addView(scroll)
        outputBox = box
        scrollView = scroll

        val actionsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actionsRow.addView(TextView(ctx).apply {
            text = "⏹ Detener"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 12f
            setPadding(0, (8 * density).toInt(), (16 * density).toInt(), 0)
            setOnClickListener { stopScanAndSelf() }
        })
        actionsRow.addView(TextView(ctx).apply {
            text = "▶ Abrir Kairos"
            setTextColor(Color.parseColor("#58A6FF"))
            textSize = 12f
            setPadding(0, (8 * density).toInt(), 0, 0)
            setOnClickListener {
                startActivity(Intent(ctx, TermuxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        panel.addView(actionsRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        bubbleParams?.let {
            params.x = it.x
            params.y = it.y + (48 * density).toInt()
        }

        try {
            windowManager.addView(panel, params)
            panelView = panel
        } catch (_: Exception) {
        }
    }

    private fun appendLine(line: String) {
        handler.post {
            val box = outputBox ?: return@post
            val density = resources.displayMetrics.density
            box.addView(TextView(this).apply {
                text = line
                setTextColor(Color.parseColor("#D1D5DB"))
                textSize = 10.5f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            // Máximo 300 líneas en pantalla -- un escaneo verboso (nmap -sV, nikto) puede
            // generar miles de líneas y una ScrollView sin límite de vistas hijas degrada la
            // UI de forma real con el tiempo.
            if (box.childCount > 300) box.removeViewAt(0)
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Streaming real línea por línea -- a diferencia de CiberseguridadFragment.runQuickScan()
     *  (bloqueante, lee todo el stdout de una sola vez al final), acá se lee con readLine() en
     *  loop para que el overlay muestre progreso real mientras el escaneo corre. */
    private fun runScan(cmd: List<String>) {
        Thread {
            try {
                val pb = ProcessBuilder(cmd)
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val proc = pb.start()
                process = proc
                appendLine("[Kairos] Ejecutando: ${cmd.joinToString(" ")}")
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { appendLine(it) }
                }
                val exit = proc.waitFor()
                appendLine(if (exit == 0) "[Kairos] Escaneo terminado (exit 0)" else "[Kairos] Escaneo terminado (exit $exit)")
            } catch (e: Exception) {
                appendLine("[Kairos] Error: ${e.message}")
            } finally {
                process = null
            }
        }.start()
    }

    private fun stopScanAndSelf() {
        try { process?.destroyForcibly() } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { process?.destroyForcibly() } catch (_: Exception) {}
        panelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panelView = null
        bubbleView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
