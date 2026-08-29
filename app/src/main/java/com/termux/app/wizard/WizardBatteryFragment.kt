package com.termux.app.wizard

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.util.BatteryRestrictionHelper
import com.termux.app.util.WizardDebugLog
import com.termux.app.util.kairosThemeColor

/**
 * Pantalla 3 del wizard — optimización de batería, con pantalla propia (antes se disparaba
 * sola en medio de la instalación, sin botón para saltarla — bug real reportado por el
 * usuario: "no me deja continuar sin quitar la optimización de batería, y aunque la quite
 * sigue saliendo esa ventana", ver docs/humano53.md/humano54.md). La causa real era que
 * BatteryRestrictionHelper.requestDisableBatteryRestrictions() dispara 2 intents de sistema
 * seguidos (excepción estándar de Android + pantalla propia del fabricante) sin ningún gate
 * — acá se avisa eso de entrada y "Siguiente" queda SIEMPRE habilitado, se resuelva o no.
 */
class WizardBatteryFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        WizardDebugLog.log("WizardBatteryFragment", "onCreateView()")
        val ctx = requireContext()

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(TextView(ctx).apply {
            text = "Optimización de batería"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })

        root.addView(TextView(ctx).apply {
            text = "Algunos fabricantes (Samsung, Xiaomi, Huawei…) matan procesos en segundo " +
                "plano por su cuenta, encima del límite normal de Android. Te vamos a pedir la " +
                "excepción estándar y, si tu marca tiene una pantalla propia, esa también — " +
                "pueden abrirse 2 pantallas de sistema seguidas, es normal."
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(14); it.bottomMargin = dp(24)
            }
        })

        root.addView(Button(ctx).apply {
            text = "Quitar restricciones"
            isAllCaps = false
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            setTextColor(Color.BLACK)
            setOnClickListener {
                WizardDebugLog.log("WizardBatteryFragment", "Quitar restricciones tocado")
                try {
                    BatteryRestrictionHelper.requestDisableBatteryRestrictions(requireActivity())
                } catch (e: Exception) {
                    // Algunos fabricantes bloquean estos intents — no es crítico, se puede seguir igual.
                    WizardDebugLog.logException("WizardBatteryFragment", e)
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(ctx).apply {
            text = "Este paso es opcional — \"Siguiente\" avanza igual, se haya resuelto o no."
            textSize = 11.5f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(10); it.bottomMargin = dp(24)
            }
        })

        root.addView(Button(ctx).apply {
            text = "Siguiente"
            isAllCaps = false
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setOnClickListener {
                WizardDebugLog.log("WizardBatteryFragment", "Siguiente -> pantalla 5 (Instalación)")
                (activity as? WizardActivity)?.goToPage(4)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return scroll
    }
}
