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
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Widget flotante de acceso rápido — botón circular arrastrable sobre otras apps
 * (patrón "chat head", igual que Messenger) con un panel expandible mostrando el
 * estado en vivo de los módulos con proceso y acciones rápidas para
 * iniciarlos/detenerlos sin tener que volver a Kairos. Activado/desactivado desde
 * ConfigFragment (ver "Widget flotante" en Ajustes).
 *
 * No requiere notificación foreground — solo mantiene una vista de overlay, no
 * hace trabajo en background restringido por Android.
 */
class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private val quickModules = listOf(
        "ollama" to "Ollama",
        "n8n" to "n8n",
        "openclaw" to "OpenClaw",
        "opencode" to "OpenCode"
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Kairos: falta el permiso de superposición para el widget flotante", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        addBubble()
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    // TYPE_APPLICATION_OVERLAY requiere API 26+ — antes de eso solo existe TYPE_PHONE
    // (deprecado pero sigue funcionando en el rango minSdk=24..25 del proyecto).
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
            text = "K"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
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
            Toast.makeText(ctx, "No se pudo mostrar el widget flotante: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        val ctx = this
        val density = resources.displayMetrics.density
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        panel.addView(TextView(ctx).apply {
            text = "▶ Abrir Kairos"
            setTextColor(Color.WHITE)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            setOnClickListener {
                val intent = Intent(ctx, TermuxActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                removePanel()
            }
        })

        // Aclaración real (2026-08-01): quickModules es a propósito una lista corta de 4
        // accesos rápidos, no el listado completo de módulos — sin este header, un usuario
        // que solo mira este panel puede confundirlo con "todos los módulos" y reportar que
        // Expo/Engram/Entorno/etc. "no aparecen" cuando en realidad nunca estuvieron acá por
        // diseño (la lista completa de 13 vive en el tab Módulos de la app).
        panel.addView(TextView(ctx).apply {
            text = "ACCESOS RÁPIDOS (ver todos en Kairos)"
            textSize = 10f
            setTextColor(Color.parseColor("#8888AA"))
            setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
        })

        // Fila provisoria mientras se resuelve el estado real en segundo plano — ver abajo.
        val loadingRow = TextView(ctx).apply {
            text = "Cargando estado…"
            textSize = 11f
            setTextColor(Color.parseColor("#8888AA"))
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }
        panel.addView(loadingRow)

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
            return
        }

        // ModuleController.isRunning() es bloqueante (hasta 5s por módulo) — hasta ~20s de
        // ANR en el peor caso si se llama acá directo, en el hilo principal (callback
        // ACTION_UP del touch listener de la burbuja). Se resuelve en background y se
        // postea el resultado al panel ya mostrado.
        Thread {
            val statuses = quickModules.map { (id, label) ->
                val running = try { ModuleController.isRunning(id) } catch (_: Exception) { false }
                Triple(id, label, running)
            }
            handler.post {
                if (panelView !== panel) return@post
                panel.removeView(loadingRow)
                for ((id, label, running) in statuses) {
                    panel.addView(TextView(ctx).apply {
                        text = "${if (running) "●" else "○"} $label"
                        setTextColor(if (running) Color.parseColor("#22C55E") else Color.LTGRAY)
                        setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                        setOnClickListener {
                            if (running) {
                                ModuleController.stopModule(id, ctx.applicationContext) { _ -> }
                                Toast.makeText(ctx, "Deteniendo $label…", Toast.LENGTH_SHORT).show()
                            } else {
                                ModuleController.startModule(id, ctx.applicationContext) { _, _ -> }
                                Toast.makeText(ctx, "Iniciando $label…", Toast.LENGTH_SHORT).show()
                            }
                            removePanel()
                        }
                    })
                }
            }
        }.start()
    }

    private fun removePanel() {
        panelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        panelView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        removePanel()
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
