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
import com.termux.app.util.kairosThemeColor

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
                it.topMargin = dp(20); it.bottomMargin = dp(40)
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
