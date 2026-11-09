package com.termux.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Voz real del Agente Kairos — pedido explícito del usuario (2026-10-30):
 * "mejorar y agregar el movimiento de boca, ojos a la cara que ya tenemos" + "parpadear y tener
 * sonido" (parpadeo ya existía, ver AgentAvatarView.kt). Esto cierra el "futuro" que dejaba
 * anotado `docs/agente/DECISIONES_AGENTE_2026-10-10.md` punto 2 ("Voz: futuro [...] hoy la app
 * no tiene TextToSpeech").
 *
 * **`android.speech.tts.TextToSpeech` nativo, sin librerías nuevas** — ninguna dependencia
 * externa, motor TTS del propio sistema Android (voz instalada por el usuario/fabricante).
 *
 * **Por qué no "boca sincronizada con audio" vía un Visualizer**: Android no expone una forma
 * pública confiable de engancharse al `AudioTrack` interno que usa `TextToSpeech` para hablar en
 * vivo (no hay `getAudioSessionId()` público en la API de TTS). La vía real, documentada y
 * estable: `TextToSpeech.synthesizeToFile()` renderiza el habla a un archivo WAV ANTES de sonar;
 * esta clase lee ese WAV a mano y lo reproduce ELLA MISMA con un `AudioTrack` propio en modo
 * STREAM, calculando el volumen RMS real de cada bloque de muestras justo antes de escribirlo —
 * así la boca se abre/cierra según el audio que de verdad está sonando en ese instante, no una
 * animación de relleno con un patrón fijo (lo que `AgentAvatarView.setSpeaking()` seguía usando
 * de respaldo, ver más abajo).
 */
object AgentVoice {

    private var tts: TextToSpeech? = null
    private var ready = false
    @Volatile private var playbackThread: Thread? = null

    /** Arranca el motor TTS una sola vez; no bloquea — [onReady] avisa cuando ya se puede hablar. */
    fun init(context: Context, onReady: (Boolean) -> Unit = {}) {
        if (tts != null) { onReady(ready); return }
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale.getDefault()
            onReady(ready)
        }
    }

    fun isAvailable(): Boolean = ready

    /**
     * Sintetiza [text] a un archivo temporal, lo reproduce con amplitud real en vivo
     * (cada actualización llega por [onAmplitude], 0f..1f) y avisa por [onDone] al terminar
     * (también si falla — nunca deja a quien llama esperando para siempre). Todo el trabajo
     * pesado (síntesis + reproducción) corre fuera del hilo principal.
     */
    fun speak(context: Context, text: String, onAmplitude: (Float) -> Unit, onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !ready) {
            onDone()
            return
        }
        val outFile = File(context.cacheDir, "agent_voice_${UUID.randomUUID()}.wav")
        val utteranceId = outFile.name
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                playAndMeasure(outFile, onAmplitude, onDone)
            }
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onError(utteranceId: String?) {
                // API vieja de UtteranceProgressListener -- Android la sigue llamando en algunos
                // dispositivos en vez de la versión con errorCode; hay que sobreescribirla igual.
                outFile.delete()
                onDone()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                outFile.delete()
                onDone()
            }
        })
        val result = engine.synthesizeToFile(text, Bundle(), outFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            outFile.delete()
            onDone()
        }
    }

    /** Corta cualquier reproducción en curso — ej. si se cierra el avatar a mitad de una frase. */
    fun stop() {
        tts?.stop()
        playbackThread?.interrupt()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    // ── WAV real -> AudioTrack + amplitud RMS por bloque ────────────────────────────────────

    private const val CHUNK_SAMPLES = 512 // ~11-23ms por bloque según sample rate -- granularidad suave para la boca

    private fun playAndMeasure(wavFile: File, onAmplitude: (Float) -> Unit, onDone: () -> Unit) {
        val thread = Thread {
            try {
                val format = readWavFormat(wavFile) ?: run { onDone(); return@Thread }
                val channelConfig = if (format.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val minBuf = AudioTrack.getMinBufferSize(format.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) { onDone(); return@Thread }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(format.sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                RandomAccessFile(wavFile, "r").use { raf ->
                    raf.seek(format.dataOffset.toLong())
                    val bytesPerSample = 2 * format.channels
                    val chunkBytes = CHUNK_SAMPLES * bytesPerSample
                    val buf = ByteArray(chunkBytes)
                    track.play()
                    while (!Thread.currentThread().isInterrupted) {
                        val read = raf.read(buf)
                        if (read <= 0) break
                        onAmplitude(rmsAmplitude(buf, read))
                        track.write(buf, 0, read)
                    }
                }
                track.stop()
                track.release()
            } catch (_: Exception) {
                // Best-effort: si algo falla a mitad de camino, simplemente se corta el habla —
                // nunca debe tirar abajo el overlay del avatar.
            } finally {
                onAmplitude(0f)
                wavFile.delete()
                onDone()
            }
        }
        playbackThread = thread
        thread.start()
    }

    /** RMS normalizado 0f..1f de un bloque PCM 16-bit little-endian (todos los canales juntos
     *  alcanza para "qué tan fuerte suena ahora", no hace falta separarlos por canal). */
    private fun rmsAmplitude(buf: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSquares = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sumSquares += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sumSquares / count)
        // 32768 = pico teórico de PCM16; se recorta a 1f por las dudas (headroom real suele ser menor).
        return min(1f, (rms / 32768.0).toFloat())
    }

    private class WavFormat(val sampleRate: Int, val channels: Int, val dataOffset: Int)

    /** Parser mínimo de cabecera WAV (RIFF/WAVE, chunk "fmt " + "data") — lo que
     *  TextToSpeech.synthesizeToFile() produce de verdad; no pretende soportar WAV arbitrario. */
    private fun readWavFormat(file: File): WavFormat? {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            if (raf.read(header) != 12) return null
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null

            var sampleRate = 0
            var channels = 0
            var dataOffset = -1
            val chunkHeader = ByteArray(8)
            while (raf.read(chunkHeader) == 8) {
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = leInt(chunkHeader, 4)
                val chunkStart = raf.filePointer
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(min(chunkSize, 16))
                        raf.read(fmt)
                        channels = leShort(fmt, 2)
                        sampleRate = leInt(fmt, 4)
                    }
                    "data" -> {
                        dataOffset = chunkStart.toInt()
                        return WavFormat(sampleRate.takeIf { it > 0 } ?: 22050, channels.takeIf { it > 0 } ?: 1, dataOffset)
                    }
                }
                raf.seek(chunkStart + chunkSize + (chunkSize % 2)) // los chunks RIFF se alinean a 2 bytes
            }
            return null
        }
    }

    private fun leShort(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
