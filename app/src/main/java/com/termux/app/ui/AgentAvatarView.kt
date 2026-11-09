package com.termux.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import com.termux.app.util.AgentAvatarKind
import com.termux.app.util.AgentAvatarPose
import kotlin.random.Random

/**
 * "Cara mínima" del Agente Kairos — dos ojos y una boca, dibujados a mano con Canvas (sin
 * Compose, sin archivos de imagen). Diseño tomado del boceto aprobado por el usuario
 * (ui_mockups/agente/mascotas.html, figura "B · Cara mínima": fondo redondeado oscuro, dos ojos
 * rectangulares redondeados, boca rectangular redondeada que se ensancha al "hablar", anillo
 * exterior que aparece mientras habla). Ver docs/agente/DECISIONES_AGENTE_2026-10-10.md — el
 * usuario decidió que el avatar empieza como esta figura simple, flotando en una esquina,
 * discreta casi siempre.
 *
 * Colores fijos (no `?attr/kairos*`): esta vista vive en un overlay de Service
 * (TYPE_APPLICATION_OVERLAY), sin el tema de ninguna Activity aplicado — mismo criterio ya
 * usado por FloatingWidgetService.kt para su burbuja "K" (Color.parseColor directo).
 *
 * **Avatares con imagen real** (2026-10-30, pedido explícito del usuario: "poner para escoger si
 * desea a kari la chica, kairo el chico, o kairos la cara") — cuando [kind] no es
 * [AgentAvatarKind.CARA], en vez de dibujar ojos/boca a mano se usan 3 imágenes reales
 * (`assets/agent/<kari|kairo>/neutral.png`/`mouth_open.png`/`blink.png`, generadas por el usuario
 * a partir de `ver/avatar_femenino|masculino/`) con un crossfade de alfa entre neutral/boca
 * abierta según [mouthOpenFraction] y un cambio discreto a `blink.png` durante el parpadeo — los
 * MISMOS campos de estado que ya maneja [blink]/[setSpeaking]/[setMouthAmplitude], ningún
 * duplicado de lógica de animación, solo cambia qué dibuja [onDraw].
 */
class AgentAvatarView(context: Context, private val kind: AgentAvatarKind = AgentAvatarKind.CARA) : View(context) {

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var bmpNeutral: Bitmap? = null
    private var bmpMouthOpen: Bitmap? = null
    private var bmpBlink: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Pose actual — ver AgentAvatarPose. Cambia con [setPose]; [loadAvatarBitmaps] siempre
     *  intenta la pose pedida primero y cae a DE_PIE si esa pose no tiene set de imágenes
     *  todavía (arte pendiente, ver ver/avatar_femenino/README.md). */
    private var pose = AgentAvatarPose.DE_PIE
    /** Alfa de crossfade entre la pose anterior y la nueva mientras [poseCrossfadeAnimator] corre. */
    private var posePrevNeutral: Bitmap? = null
    private var poseCrossfadeAlpha = 255
    private var poseCrossfadeAnimator: ValueAnimator? = null
    /** Token incremental: descarta el resultado de una carga vieja si [setPose] se llamó de
     *  nuevo antes de que la anterior terminara (evita que una carga lenta pise una más reciente). */
    private var loadToken = 0

    init {
        if (kind != AgentAvatarKind.CARA) loadAvatarBitmaps(pose)
    }

    /** Cambia de pose con un crossfade suave. No hace nada si ya está en esa pose, o si
     *  [kind] es CARA (la cara mínima dibujada a mano no tiene poses de cuerpo). */
    fun setPose(newPose: AgentAvatarPose) {
        if (kind == AgentAvatarKind.CARA || newPose == pose) return
        pose = newPose
        posePrevNeutral = bmpNeutral
        loadAvatarBitmaps(newPose)
    }

    /** Decodifica los 3 PNG de [requestedPose] en un hilo propio — BitmapFactory sobre assets no
     *  debe correr en el hilo principal del overlay. Si el set de ESA pose no existe todavía
     *  (arte pendiente), cae a DE_PIE; si incluso DE_PIE falla, [onDraw] cae a la "cara mínima"
     *  dibujada a mano — nunca una vista en blanco. */
    private fun loadAvatarBitmaps(requestedPose: AgentAvatarPose) {
        val dir = kind.assetDir ?: return
        val myToken = ++loadToken
        Thread {
            val am = context.assets
            fun tryLoad(poseDirPrefix: String): Triple<Bitmap, Bitmap?, Bitmap?>? = try {
                val base = if (poseDirPrefix.isEmpty()) "agent/$dir" else "agent/$dir/$poseDirPrefix"
                val neutral = am.open("$base/neutral.png").use { BitmapFactory.decodeStream(it) }
                val mouthOpen = runCatching { am.open("$base/mouth_open.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
                val blink = runCatching { am.open("$base/blink.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
                Triple(neutral, mouthOpen, blink)
            } catch (_: Exception) {
                null
            }
            val result = tryLoad(requestedPose.dirPrefix) ?: tryLoad(AgentAvatarPose.DE_PIE.dirPrefix)
            if (result == null) return@Thread
            handler.post {
                if (myToken != loadToken) return@post // una carga más nueva ya está en curso
                bmpNeutral = result.first; bmpMouthOpen = result.second; bmpBlink = result.third
                startPoseCrossfade()
            }
        }.start()
    }

    private fun startPoseCrossfade() {
        poseCrossfadeAnimator?.cancel()
        if (posePrevNeutral == null) { invalidate(); return }
        poseCrossfadeAnimator = ValueAnimator.ofInt(0, 255).apply {
            duration = 420
            addUpdateListener {
                poseCrossfadeAlpha = it.animatedValue as Int
                invalidate()
                if (poseCrossfadeAlpha >= 255) posePrevNeutral = null
            }
            start()
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#161616") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C2C2C"); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8E8E8") }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3B82F6") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6"); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }

    /** 1f = ojos bien abiertos, ~0.08f = parpadeo. Anima con [blinkAnimator]. */
    private var eyeOpenFraction = 1f
    /** 1f = boca cerrada (reposo), hasta ~2.6f = boca abierta "hablando". */
    private var mouthOpenFraction = 1f
    /** Alfa del anillo exterior (solo visible mientras habla). */
    private var ringAlpha = 0

    private var blinkAnimator: ValueAnimator? = null
    private var speakAnimator: ValueAnimator? = null
    private var ringAnimator: ValueAnimator? = null
    private var speaking = false
    private var attached = false

    private val scheduleNextBlink = object : Runnable {
        override fun run() {
            if (!attached) return
            if (!speaking) blink()
            // Parpadeo "vivo" pero poco frecuente: cada 2 a 6 segundos, nunca mecánico.
            handler.postDelayed(this, (2000 + Random.nextInt(4000)).toLong())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        handler.postDelayed(scheduleNextBlink, (1200 + Random.nextInt(2000)).toLong())
        startBreathing()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
        handler.removeCallbacksAndMessages(null)
        blinkAnimator?.cancel()
        speakAnimator?.cancel()
        ringAnimator?.cancel()
        breathAnimator?.cancel()
        poseCrossfadeAnimator?.cancel()
    }

    private fun blink() {
        blinkAnimator?.cancel()
        blinkAnimator = ValueAnimator.ofFloat(1f, 0.08f, 1f).apply {
            duration = 220
            interpolator = LinearInterpolator()
            addUpdateListener { eyeOpenFraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /**
     * Activa/desactiva la animación de "hablando": boca en movimiento + anillo exterior visible.
     * Llamado por AgentAvatarService mientras el globo de texto está en pantalla. Este es el
     * camino de RESPALDO (patrón fijo, sin audio real) — se usa cuando no hay voz real sonando
     * (TTS apagado en preferencias, o [AgentVoice] no disponible en el dispositivo). Mientras hay
     * voz real, [setMouthAmplitude] maneja la boca en vivo y este método solo prende/apaga el
     * anillo (ver AgentAvatarService.speak()).
     */
    fun setSpeaking(isSpeaking: Boolean, driveMouth: Boolean = true) {
        speaking = isSpeaking
        ringAnimator?.cancel()
        if (isSpeaking) {
            if (driveMouth) {
                speakAnimator?.cancel()
                speakAnimator = ValueAnimator.ofFloat(1f, 2.6f, 1f, 2.1f, 1f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { mouthOpenFraction = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            ringAnimator = ValueAnimator.ofInt(0, 160).apply {
                duration = 250
                addUpdateListener { ringAlpha = it.animatedValue as Int; invalidate() }
                start()
            }
        } else {
            speakAnimator?.cancel()
            mouthOpenFraction = 1f
            ringAnimator = ValueAnimator.ofInt(ringAlpha, 0).apply {
                duration = 250
                addUpdateListener { ringAlpha = it.animatedValue as Int; invalidate() }
                start()
            }
            invalidate()
        }
    }

    /**
     * Boca sincronizada con audio REAL (ver AgentVoice.kt — amplitud RMS calculada del WAV de
     * TextToSpeech justo antes de reproducir cada bloque). [level] 0f..1f: 0 = silencio (boca
     * casi cerrada), 1 = pico de volumen. Reemplaza al [speakAnimator] de patrón fijo mientras
     * dura el habla real — llamar con `driveMouth=false` en [setSpeaking] para no pisarse con
     * este método.
     */
    fun setMouthAmplitude(level: Float) {
        speakAnimator?.cancel()
        val clamped = level.coerceIn(0f, 1f)
        // Mismo rango que ya usaba el patrón fijo (0.4f reposo .. ~3f pico) para que el dibujo
        // (onDraw) no necesite ningún cambio — solo cambia QUIÉN decide el valor.
        mouthOpenFraction = 0.4f + clamped * 2.6f
        invalidate()
    }

    /** "Respira" — escala/traslada sutilmente TODO el dibujo (cara mínima o imagen real, ambas)
     *  para dar sensación de vida sin necesitar arte nuevo ni joints reales. Técnica real y
     *  liviana ya documentada en apps de avatar 2D simples (ver investigación en
     *  docs/agente/ANIMACION_2D_INVESTIGACION_2026-11-02.md) — un ciclo seno lento, nunca
     *  mecánico porque el período varía un poco entre ciclos (igual que el parpadeo). */
    private var breathPhase = 0f
    private var breathAnimator: ValueAnimator? = null

    private fun startBreathing() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = (3400 + Random.nextInt(800)).toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { breathPhase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        if (ringAlpha > 0) {
            ringPaint.alpha = ringAlpha
            canvas.drawCircle(cx, cy, minOf(w, h) / 2f - borderPaint.strokeWidth, ringPaint)
        }

        val breathSave = canvas.save()
        val breathScale = 1f + 0.012f * kotlin.math.sin(breathPhase)
        val breathDy = h * 0.006f * kotlin.math.sin(breathPhase)
        canvas.scale(breathScale, breathScale, cx, cy)
        canvas.translate(0f, breathDy)

        val bodyMargin = minOf(w, h) * 0.08f
        val bodyRadius = minOf(w, h) * 0.26f
        val body = RectF(bodyMargin, bodyMargin, w - bodyMargin, h - bodyMargin)
        canvas.drawRoundRect(body, bodyRadius, bodyRadius, bgPaint)

        val neutral = bmpNeutral
        if (kind != AgentAvatarKind.CARA && neutral != null) {
            drawAvatarBitmap(canvas, body, bodyRadius, neutral)
        } else {
            drawMinimalFace(canvas, w, h, cx, cy)
        }
        canvas.drawRoundRect(body, bodyRadius, bodyRadius, borderPaint)
        canvas.restoreToCount(breathSave)
    }

    /** Recorta al mismo redondeado que [body] y dibuja, con crossfade: blink.png mientras dura el
     *  parpadeo ([eyeOpenFraction] bajo), si no un alfa-blend entre neutral/mouth_open según
     *  [mouthOpenFraction] normalizado. */
    private fun drawAvatarBitmap(canvas: Canvas, body: RectF, bodyRadius: Float, neutral: Bitmap) {
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(body, bodyRadius, bodyRadius, Path.Direction.CW) }
        canvas.clipPath(clip)

        // Pose previa debajo, a toda opacidad, mientras la nueva pose hace crossfade encima
        // (startPoseCrossfade) -- sin esto se vería un parpadeo brusco al cambiar de pose.
        posePrevNeutral?.let { prev ->
            bitmapPaint.alpha = 255
            canvas.drawBitmap(prev, null, body, bitmapPaint)
        }

        val blinking = eyeOpenFraction < 0.3f
        val blink = bmpBlink
        val baseAlpha = if (posePrevNeutral != null) poseCrossfadeAlpha else 255
        if (blinking && blink != null) {
            bitmapPaint.alpha = baseAlpha
            canvas.drawBitmap(blink, null, body, bitmapPaint)
        } else {
            val mouthOpen = bmpMouthOpen
            // mouthOpenFraction va de 0.4f (reposo) a ~3f (pico) -- normalizado 0f..1f para alfa.
            val talkAlpha = ((mouthOpenFraction - 0.4f) / (3f - 0.4f)).coerceIn(0f, 1f)
            bitmapPaint.alpha = baseAlpha
            canvas.drawBitmap(neutral, null, body, bitmapPaint)
            if (mouthOpen != null && talkAlpha > 0.02f) {
                bitmapPaint.alpha = ((talkAlpha * baseAlpha.toFloat() / 255f) * 255).toInt()
                canvas.drawBitmap(mouthOpen, null, body, bitmapPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    /** La "cara mínima" original (ojos/boca dibujados a mano) — [AgentAvatarKind.CARA], o el
     *  respaldo real si las imágenes de [kind] no cargaron todavía/fallaron. */
    private fun drawMinimalFace(canvas: Canvas, w: Float, h: Float, cx: Float, cy: Float) {
        // Ojos: dos rectángulos redondeados, altura modulada por eyeOpenFraction (parpadeo).
        val eyeW = w * 0.09f
        val eyeH = h * 0.16f * eyeOpenFraction.coerceIn(0.06f, 1f)
        val eyeY = cy - h * 0.06f
        val eyeGap = w * 0.17f
        drawRoundedRect(canvas, cx - eyeGap - eyeW / 2f, eyeY - eyeH / 2f, eyeW, eyeH, eyePaint)
        drawRoundedRect(canvas, cx + eyeGap - eyeW / 2f, eyeY - eyeH / 2f, eyeW, eyeH, eyePaint)

        // Boca: ancho fijo, alto modulado por mouthOpenFraction (quieta en reposo, se mueve al hablar).
        val mouthW = w * 0.16f
        val mouthH = h * 0.055f * mouthOpenFraction.coerceIn(0.4f, 3f)
        val mouthY = cy + h * 0.17f
        drawRoundedRect(canvas, cx - mouthW / 2f, mouthY - mouthH / 2f, mouthW, mouthH, mouthPaint)
    }

    private fun drawRoundedRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, paint: Paint) {
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
    }
}
