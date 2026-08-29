package com.termux.app.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Entrada de voz vía `android.speech.SpeechRecognizer` real (API nativa de Android, on-device
 * en muchos dispositivos modernos) — portado de `referencia/termux/NewTermux-main/.../
 * SpeechInputManager.java` (131 líneas, cero dependencias externas), ver
 * docs/referencias/REFERENCIA_NEWTERMUX.md ("re-auditoría con lente de piezas", 2026-08-01). Responde a la
 * idea "STT en el chat" que había quedado documentada sin una referencia concreta en
 * docs/referencias/REFERENCIA_WHISPERCODE.md (WhisperKit es iOS-only, este es el equivalente Android real).
 *
 * Mismo patrón de callback que el original, adaptado a una interfaz Kotlin idiomática — usado
 * por ChatFragment.kt para llenar el input de texto con lo dictado (nunca envía el mensaje
 * automático, el usuario revisa/edita antes de tocar enviar).
 */
class SpeechInputManager(private val context: Context) {

    interface SpeechCallback {
        fun onResult(text: String)
        fun onError(error: String)
        fun onListeningStarted()
        fun onListeningStopped()
    }

    companion object {
        private const val TAG = "SpeechInputManager"

        @JvmStatic
        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: SpeechCallback? = null
    private var listening = false

    fun setCallback(cb: SpeechCallback) {
        callback = cb
    }

    fun startListening() {
        if (listening) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback?.onError("Reconocimiento de voz no disponible en este dispositivo")
            return
        }

        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                callback?.onListeningStarted()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                listening = false
                callback?.onListeningStopped()
            }

            override fun onError(error: Int) {
                listening = false
                val msg = speechErrorToString(error)
                Log.e(TAG, "Error de reconocimiento: $msg")
                callback?.onError(msg)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrEmpty()) {
                    Log.d(TAG, "Resultado: $text")
                    callback?.onResult(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Decí tu mensaje…")
        }
        sr.startListening(intent)
    }

    fun stopListening() {
        if (listening) {
            recognizer?.stopListening()
            listening = false
        }
    }

    fun isListening(): Boolean = listening

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun speechErrorToString(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Error grabando audio"
        SpeechRecognizer.ERROR_CLIENT -> "Error del cliente de reconocimiento"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso de micrófono"
        SpeechRecognizer.ERROR_NETWORK -> "Error de red"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red agotado"
        SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció ningún texto"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado"
        SpeechRecognizer.ERROR_SERVER -> "Error del servidor de reconocimiento"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se detectó voz"
        else -> "Error desconocido ($error)"
    }
}
