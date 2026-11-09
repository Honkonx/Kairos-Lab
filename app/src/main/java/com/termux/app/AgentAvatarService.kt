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
import android.widget.TextView
import android.widget.Toast
import com.termux.R
import com.termux.app.ui.AgentAvatarView
import com.termux.app.util.AgentAvatarPhrases
import com.termux.app.util.AgentAvatarPose
import com.termux.app.util.AgentAvatarPrefs
import com.termux.app.util.AgentAvatarWatcher
import com.termux.app.util.AgentVoice
import kotlin.math.abs

/**
 * Overlay separado del widget flotante de módulos (FloatingWidgetService.kt, que sigue existiendo
 * tal cual — este Service es solo para el Agente). Dibuja la "cara mínima" del Agente Kairos
 * flotando en una esquina casi todo el tiempo y, de vez en cuando, dice algo breve en un globo de
 * texto (ver docs/agente/DECISIONES_AGENTE_2026-10-10.md: figura 2D simple, voz a futuro). Tocar
 * el avatar (sin arrastrarlo) abre la pantalla de chat del Agente (KairosAgentFragment) encima de
 * lo que el usuario esté haciendo, vía TermuxActivity.EXTRA_OPEN_AGENT.
 *
 * Activado/desactivado desde KairosAgentFragment ("Avatar flotante"), mismo patrón de
 * permiso/SharedPreferences que ConfigFragment.kt ya usa para FloatingWidgetService.
 *
 * Voz real (2026-10-30, pedido explícito del usuario): si `AgentAvatarPrefs.isVoiceEnabled()`
 * está prendido (apagado por defecto), lo que dice el avatar también se escucha de verdad vía
 * [AgentVoice] (TextToSpeech nativo) — la boca se anima con la amplitud REAL del audio en vez
 * del patrón fijo de respaldo. Ver KDoc de AgentVoice.kt para el porqué del mecanismo.
 */
class AgentAvatarService : Service() {

    private lateinit var windowManager: WindowManager
    private var avatarContainer: View? = null
    private var avatarView: AgentAvatarView? = null
    private var avatarParams: WindowManager.LayoutParams? = null
    private var bubbleView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val sayRunnable = Runnable { saySomething() }
    private val hideBubbleRunnable = Runnable { hideBubble() }
    private val poseRotateRunnable = Runnable { rotatePose() }
    private var currentPose = AgentAvatarPose.DE_PIE

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!hasOverlayPermission()) {
            Toast.makeText(this, getString(R.string.agent_avatar_need_overlay), Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        addAvatar()
        if (AgentAvatarPrefs.isVoiceEnabled(this)) AgentVoice.init(this)
        scheduleNextSpeech()
        scheduleNextPoseRotate()
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun addAvatar() {
        val density = resources.displayMetrics.density
        val size = (64 * density).toInt()
        val view = AgentAvatarView(this, AgentAvatarPrefs.kind(this))

        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val metrics = resources.displayMetrics
        val margin = (12 * density).toInt()
        val savedX = AgentAvatarPrefs.savedX(this)
        val savedY = AgentAvatarPrefs.savedY(this)
        params.x = if (savedX != AgentAvatarPrefs.UNSET) savedX else metrics.widthPixels - size - margin
        params.y = if (savedY != AgentAvatarPrefs.UNSET) savedY else (metrics.heightPixels * 0.55f).toInt()

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
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
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    moveBubbleNearAvatar()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        AgentAvatarPrefs.savePosition(this, params.x, params.y)
                    } else {
                        openAgent()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
            avatarContainer = view
            avatarView = view
            avatarParams = params
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.agent_avatar_overlay_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /** Trae Kairos al frente y abre el chat del Agente — ver TermuxActivity.handleOpenAgentIntent(). */
    private fun openAgent() {
        hideBubble()
        val intent = Intent(this, TermuxActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(TermuxActivity.EXTRA_OPEN_AGENT, true)
        }
        startActivity(intent)
    }

    // ── Rotación de pose de cuerpo (2026-11-02) ─────────────────────────────
    // Mientras no hay arte real de sentada/acostada (ver ver/avatar_femenino/README.md),
    // AgentAvatarView.setPose() cae automáticamente a DE_PIE -- este timer queda listo y no
    // rompe nada hoy, pero empieza a rotar de verdad apenas existan los PNG de esas poses.

    private fun scheduleNextPoseRotate() {
        handler.removeCallbacks(poseRotateRunnable)
        handler.postDelayed(poseRotateRunnable, (45_000 + (Math.random() * 45_000)).toLong())
    }

    private fun rotatePose() {
        // No cambiar de pose a mitad de una frase/burbuja -- se nota raro cambiar el cuerpo
        // mientras "está hablando".
        if (bubbleView == null) {
            currentPose = AgentAvatarPose.randomOtherThan(currentPose)
            avatarView?.setPose(currentPose)
        }
        scheduleNextPoseRotate()
    }

    // ── "Dice algo" de vez en cuando ────────────────────────────────────────

    private fun scheduleNextSpeech() {
        handler.removeCallbacks(sayRunnable)
        val delay = AgentAvatarPhrases.nextIntervalMs(
            AgentAvatarPhrases.DEFAULT_INTERVAL_MS, AgentAvatarPhrases.DEFAULT_JITTER_MS, Math.random()
        )
        handler.postDelayed(sayRunnable, delay)
    }

    // Chequeo real (GatewayManager.isHealthy() hace una llamada HTTP bloqueante, batteryLevel()
    // es barata pero se corre junto por simplicidad) en un hilo propio — nunca en el hilo
    // principal, que es donde corre este Handler. Si AgentAvatarWatcher.check() encuentra algo
    // notable (gateway caído, batería baja), se dice ESO en vez de la frase decorativa al azar.
    private fun saySomething() {
        Thread {
            val notable = runCatching { AgentAvatarWatcher.check(applicationContext) }.getOrNull()
            handler.post { speak(notable) }
        }.start()
    }

    private fun speak(notable: String?) {
        val text = notable ?: run {
            val phrases = resources.getStringArray(R.array.agent_avatar_phrases).toList()
            AgentAvatarPhrases.pickRandom(phrases, Math.random())
        }
        if (text != null && avatarParams != null) {
            showBubble(text)
            val useVoice = AgentAvatarPrefs.isVoiceEnabled(this) && AgentVoice.isAvailable()
            // driveMouth=false con voz real: la boca la maneja setMouthAmplitude() en vivo (ver
            // onAmplitude abajo), no el patrón fijo — evita que los dos se pisen.
            avatarView?.setSpeaking(true, driveMouth = !useVoice)
            if (useVoice) {
                AgentVoice.speak(
                    context = this,
                    text = text,
                    onAmplitude = { level -> handler.post { avatarView?.setMouthAmplitude(level) } },
                    onDone = { handler.post { hideBubble() } },
                )
                // Con voz real, la burbuja se esconde cuando el audio termina (onDone de arriba),
                // no por el temporizador fijo — se cancela el hideBubbleRunnable de respaldo.
                handler.removeCallbacks(hideBubbleRunnable)
            } else {
                handler.removeCallbacks(hideBubbleRunnable)
                handler.postDelayed(hideBubbleRunnable, AgentAvatarPhrases.BUBBLE_VISIBLE_MS)
            }
        }
        scheduleNextSpeech()
    }

    private fun showBubble(text: String) {
        hideBubble()
        val density = resources.displayMetrics.density
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            maxWidth = (160 * density).toInt()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        positionBubble(params, density)
        try {
            windowManager.addView(bubble, params)
            bubbleView = bubble
        } catch (_: Exception) {
            bubbleView = null
        }
    }

    /** Burbuja arriba del avatar; si no entra arriba (avatar ya pegado al techo), la pone abajo. */
    private fun positionBubble(params: WindowManager.LayoutParams, density: Float) {
        val avatarP = avatarParams ?: return
        val size = (64 * density).toInt()
        params.x = avatarP.x
        val aboveY = avatarP.y - (54 * density).toInt()
        params.y = if (aboveY > 0) aboveY else avatarP.y + size + (6 * density).toInt()
    }

    private fun moveBubbleNearAvatar() {
        val bubble = bubbleView ?: return
        val density = resources.displayMetrics.density
        val params = (bubble.layoutParams as? WindowManager.LayoutParams) ?: return
        positionBubble(params, density)
        try { windowManager.updateViewLayout(bubble, params) } catch (_: Exception) {}
    }

    private fun hideBubble() {
        handler.removeCallbacks(hideBubbleRunnable)
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
        avatarView?.setSpeaking(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        AgentVoice.shutdown()
        avatarContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        avatarContainer = null
        avatarView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
