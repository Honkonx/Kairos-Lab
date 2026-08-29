package com.termux.app.ui.widget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView

/**
 * Selector de tema inline reusable — reemplaza el patrón `AlertDialog.setSingleChoiceItems()` +
 * botón "Aplicar" por un `PopupMenu` anclado que aplica al toque (pedido explícito del usuario,
 * ver docs/humano/humano202.md: "el boton de tema [...] deberia ser una casilla al tocar salir las
 * demas opciones y al tocar cambiar asi bonito estetico como las apk modernas"). Mismo patrón de
 * interacción que ya usa `BaseModuleFragment.dropdownRow()`, pero standalone — ni
 * `ConfigFragment` ni `StudioFragment` extienden esa clase base, así que este componente vive
 * afuera de ambas para que "un solo componente, dos instancias" (plan de rediseño de Estudio,
 * `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §3-4) sea real, no una promesa.
 */
object InlineThemePicker {

    /** Una opción de tema — desacoplado de `KairosThemePrefs.KairosTheme`/
     *  `StudioThemePrefs.StudioTheme` para que este archivo no dependa de ninguno de los 2. */
    data class Option(val id: String, val label: String)

    /** Abre el PopupMenu anclado a [anchor] — [currentId] se marca con un check visual. */
    fun show(anchor: View, options: List<Option>, currentId: String, onSelected: (Option) -> Unit) {
        val popup = PopupMenu(anchor.context, anchor)
        options.forEachIndexed { i, opt ->
            val title = if (opt.id == currentId) "✓ ${opt.label}" else "    ${opt.label}"
            popup.menu.add(0, i, i, title)
        }
        popup.setOnMenuItemClickListener { item ->
            val chosen = options[item.itemId]
            if (chosen.id != currentId) onSelected(chosen)
            true
        }
        popup.show()
    }

    /**
     * Fila clickable "label — tema actual" para pantallas armadas por código con `LinearLayout`
     * (mismo estilo que `ConfigFragment.addClickableRow()`/`BaseModuleFragment.dropdownRow()`).
     * Devuelve la `View` de la fila — quien la use puede agregarla a su contenedor directo.
     */
    fun row(
        context: Context,
        label: String,
        options: List<Option>,
        currentId: String,
        labelColor: Int,
        valueColor: Int,
        dp: (Int) -> Int,
        onSelected: (Option) -> Unit
    ): View {
        var selectedId = currentId
        val valueLabel = TextView(context).apply {
            text = options.first { it.id == selectedId }.label
            textSize = 13f
            setTextColor(valueColor)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(labelColor)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(valueLabel)
        }
        row.setOnClickListener {
            show(valueLabel, options, selectedId) { chosen ->
                selectedId = chosen.id
                valueLabel.text = chosen.label
                onSelected(chosen)
            }
        }
        return row
    }
}
