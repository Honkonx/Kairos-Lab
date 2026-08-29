package com.termux.app.wizard

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.util.RootfsPackageChecker
import com.termux.app.util.kairosThemeColor

/** Pantalla 5 (última, opcional) del wizard — comprobar paquetes instalados/
 * actualizaciones antes de entrar a la app. Misma acción que el botón de Ajustes
 * (ConfigFragment) — RootfsPackageChecker.kt, 100% Kotlin, sin invocar python3 (pedido
 * explícito del usuario, ver docs/humano/humano12.md). Solo muestra una rueda + texto de
 * estado corto, nunca el detalle línea por línea — mismo criterio de instalación
 * silenciosa del resto de la app. */
class WizardCheckFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())

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
            text = "Comprobar paquetes"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })

        root.addView(TextView(ctx).apply {
            text = "Opcional: revisamos si falta algún paquete base o si hay actualizaciones " +
                "disponibles, e instalamos/actualizamos lo que haga falta. Podés hacer esto " +
                "más adelante también desde Ajustes."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(16); it.bottomMargin = dp(32)
            }
        })

        val spinner = ProgressBar(ctx).apply { visibility = View.GONE }
        root.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = dp(16)
        })

        val statusText = TextView(ctx).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
        }
        root.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = dp(24)
        })

        val checkBtn = Button(ctx).apply {
            text = "Comprobar y actualizar"
            isAllCaps = false
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            setTextColor(Color.BLACK)
        }
        root.addView(checkBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val skipBtn = Button(ctx).apply {
            text = "Omitir"
            isAllCaps = false
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            setOnClickListener { (activity as? WizardActivity)?.finishWizard() }
        }
        root.addView(skipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(12)
        })

        checkBtn.setOnClickListener {
            checkBtn.isEnabled = false
            skipBtn.isEnabled = false
            spinner.visibility = View.VISIBLE
            statusText.text = "Comprobando paquetes…"
            runCheck(statusText)
        }

        return scroll
    }

    private fun runCheck(statusText: TextView) {
        Thread {
            val ctx = context ?: return@Thread
            val message = try {
                val result = RootfsPackageChecker.sync(ctx) { progress ->
                    handler.post { if (isAdded) statusText.text = progress }
                }
                result.message
            } catch (e: Exception) {
                "No se pudo comprobar — ${e.message ?: "error desconocido"}"
            }
            handler.post {
                if (!isAdded) return@post
                statusText.text = message
                handler.postDelayed({ (activity as? WizardActivity)?.finishWizard() }, 900)
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
