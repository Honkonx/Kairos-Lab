package com.termux.app.wizard

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.util.MemoryMonitor
import com.termux.app.util.kairosThemeColor
import kotlin.math.roundToInt

/** Pantalla 0 del wizard — bienvenida + resumen. Ver WizardActivity.kt (host) para el
 * ViewPager2 que aloja las 4 pantallas y docs/bootstrap/ROOTFS_EMBEBIDO.md para el diseño general. */
class WizardWelcomeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(TextView(ctx).apply {
            text = "Bienvenido a Kairos"
            textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })

        root.addView(TextView(ctx).apply {
            text = "Kairos convierte tu teléfono en un servidor de IA local: Ollama, n8n, " +
                "OpenClaw, Claude Code, OpenCode y más — todo corriendo sobre Termux, sin salir " +
                "de esta app.\n\nAntes de arrancar necesitamos dos permisos (almacenamiento y " +
                "notificaciones) y vamos a preparar el entorno base — puede tardar unos minutos " +
                "la primera vez."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(20); it.bottomMargin = dp(12)
            }
        })

        // Repropósito del mecanismo de RAM total ya usado en ModelsFragment/QemuFragment/
        // MemoryMonitor (auditoría 2026-09-01) — el wizard de primer arranque nunca informaba
        // qué podía esperar el usuario de SU dispositivo puntual (mismos 4 pasos para un
        // teléfono de 2GB que uno de 16GB). Solo informativo, no bloquea nada — el instalador
        // sigue igual sin importar la RAM detectada.
        root.addView(TextView(ctx).apply {
            text = deviceRamHint(ctx)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp(28)
            }
        })

        // Pedido explícito del usuario (2026-08-25): Kairos maneja cosas delicadas (credenciales
        // de CLIs, tokens de túnel, ejecución de comandos reales) — un descargo de
        // responsabilidad genérico ("no me hago responsable de sus datos ni de lo que hagan con
        // el APK"), sin enumerar casos puntuales. Se persiste en SharedPreferences ("kairos_prefs",
        // mismo archivo que ya usa ConfigFragment) para no volver a pedirlo si el usuario ya
        // pasó por este wizard antes.
        val prefs = ctx.getSharedPreferences("kairos_prefs", 0)
        val startButton = Button(ctx).apply {
            text = "Comenzar"
            isAllCaps = false
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            setTextColor(Color.BLACK)
            isEnabled = prefs.getBoolean(PREF_TOS_ACCEPTED, false)
            alpha = if (isEnabled) 1f else 0.5f
            setOnClickListener { (activity as? WizardActivity)?.goToPage(1) }
        }

        val tosRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp(16)
            }
        }
        val tosCheckbox = CheckBox(ctx).apply {
            isChecked = prefs.getBoolean(PREF_TOS_ACCEPTED, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(PREF_TOS_ACCEPTED, checked).apply()
                startButton.isEnabled = checked
                startButton.alpha = if (checked) 1f else 0.5f
            }
        }
        tosRow.addView(tosCheckbox)
        tosRow.addView(TextView(ctx).apply {
            text = "Acepto los "
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
        })
        tosRow.addView(TextView(ctx).apply {
            text = "Términos y Condiciones"
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { showTermsDialog() }
        })
        root.addView(tosRow)

        root.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return scroll
    }

    /** Mismos rangos que ya usa ModelsFragment.estimatedRamGb()/exceedsRam (informal, no un
     *  umbral científico) — solo para dar una expectativa realista de entrada, nunca bloquea
     *  el wizard ni cambia el flujo de instalación. */
    private fun deviceRamHint(ctx: android.content.Context): String {
        val ramGb = MemoryMonitor.totalGb(ctx)
        val ramRounded = (ramGb * 10).roundToInt() / 10.0
        val nivel = when {
            ramGb < 4.0 -> "modelos livianos (hasta ~3B) van a andar mejor — los grandes pueden ir lentos o no entrar en memoria"
            ramGb < 8.0 -> "buen margen para modelos medianos (7B-8B cuantizados)"
            else -> "margen amplio, incluso para modelos grandes (13B+)"
        }
        return "Tu dispositivo tiene ~${ramRounded}GB de RAM — $nivel. Esto es solo orientativo, se puede ajustar todo después."
    }

    private fun showTermsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Términos y Condiciones")
            .setMessage(TERMS_TEXT)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    companion object {
        private const val PREF_TOS_ACCEPTED = "tos_accepted"

        // Texto genérico a propósito (pedido explícito del usuario: "nada especifico pero cosas
        // aclaradas... simplemente decir que no me hago responsable de sus datos y lo que hagan
        // con el apk") — sin enumerar casos puntuales (credenciales, tokens, etc.), un descargo
        // de responsabilidad general en lenguaje formal.
        private const val TERMS_TEXT = "Kairos es un software provisto \"tal cual\" (\"as is\"), sin garantías de ningún tipo, " +
            "expresas o implícitas, incluyendo — sin limitarse a ellas — garantías de idoneidad para un propósito " +
            "particular, disponibilidad ininterrumpida o ausencia de errores.\n\n" +
            "El uso de esta aplicación y de cualquier módulo, herramienta o servicio que instale o ejecute a través " +
            "de ella es responsabilidad exclusiva del usuario. El desarrollador no se hace responsable por la " +
            "pérdida, exposición o mal uso de datos del usuario, ni por las consecuencias derivadas del uso que el " +
            "usuario le dé a la aplicación o a lo que ejecute a través de ella, incluyendo eventuales daños al " +
            "dispositivo, pérdida de información, o infracciones a leyes o normativas aplicables en la jurisdicción " +
            "del usuario.\n\n" +
            "Al continuar, el usuario declara conocer y aceptar estos términos, y asume toda responsabilidad por el " +
            "uso que realice de la aplicación."
    }
}
