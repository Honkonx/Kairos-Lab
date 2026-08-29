package com.termux.app.x11

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import com.termux.x11.MainActivity

/**
 * Subclase de com.termux.x11.MainActivity (visor X11, módulo :x11-server — protegido, no se
 * modifica) para agregar UN botón flotante "Abrir teclado" sin tocar ningún archivo del módulo
 * protegido.
 *
 * Por qué existe (bug real reportado 2026-08-18, ver docs/humano/): al presionar atrás dentro
 * del visor, Termux:X11 real intercepta ese evento como parte del teclado extra/host activity
 * (mLorieKeyListener + termuxActivityListener) para abrir el teclado de Android — pero en Kairos
 * ese "host" (termuxActivityListener, un patrón heredado del fork Linbox donde una TermuxActivity
 * externa implementaba esa interfaz) nunca se asigna, porque el visor se lanza standalone. Wirear
 * termuxActivityListener completo es riesgoso: activa de golpe una rama de código del back-key
 * listener (releaseSlider + loriePreferenceFragment.updatePreferencesLayout()) que nunca se probó
 * en el modo standalone de Kairos y podría comportarse mal (loriePreferenceFragment existe pero
 * no está attachado a ningún FragmentManager visible en MainActivity).
 *
 * En vez de eso, se usa el mecanismo ya público y autocontenido que el propio MainActivity
 * expone — `switchSoftKeyboard(false)` (línea ~938 de MainActivity.java) — que solo llama a
 * `InputMethodManager.showSoftInput()` sobre la vista con foco; el bloque que toca
 * `loriePreferenceFragment`/`termuxActivityListener` ahí adentro está guardado detrás de
 * `if (null != termuxActivityListener)`, así que con el listener sin asignar (como ya está)
 * ese camino no se activa. Resultado: teclado real de Android, cero riesgo de regresión en el
 * back-button/exit-dialog que ya funciona.
 */
class KairosX11MainActivity : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addOpenKeyboardButton()
    }

    private fun addOpenKeyboardButton() {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return

        val button = Button(this).apply {
            text = "⌨"
            textSize = 18f
            setTextColor(Color.WHITE)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 0, 0, 0))
            }
            elevation = dpToPx(4f)
            contentDescription = "Abrir teclado"
            setOnClickListener { openAndroidKeyboard() }
        }

        val sizePx = dpToPx(40f).toInt()
        // Se coloca arriba del botón "⋮" (x11_float_menu_button: gravity top|end,
        // marginTop=24dp, height=40dp) — 24 + 40 + 8dp de separación = 72dp.
        val params = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dpToPx(72f).toInt()
            marginEnd = dpToPx(12f).toInt()
        }
        root.addView(button, params)
    }

    private fun openAndroidKeyboard() {
        // switchSoftKeyboard(false) es un método public heredado de MainActivity — hace
        // showSoftInput() sobre la vista con foco (o el lorieView si no hay foco). Ver
        // KDoc de la clase para por qué no se pasa por termuxActivityListener.
        try {
            switchSoftKeyboard(false)
        } catch (e: Exception) {
            // Fallback defensivo: forzar el IME directo sobre la vista raíz.
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(currentFocus ?: root(), 0)
        }
    }

    private fun root(): android.view.View = findViewById(android.R.id.content)

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
