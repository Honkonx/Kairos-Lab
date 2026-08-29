package com.termux.app.util

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes

/**
 * Resuelve un color de tema (?attr/kairos*, ver themes_kairos.xml) en el momento — reemplaza
 * `ContextCompat.getColor(ctx, R.color.kairos_x)` (fijo, un solo tema posible) en código Kotlin
 * que setea colores programáticamente (2026-08-22, ver docs/humano/humano190.md, selector de temas
 * Oscuro/Señal/Claro). El XML de layouts usa ?attr/kairos* directo — esto es el equivalente
 * para las clases que arman colores a mano (ModuleListAdapter, GradientDrawable de íconos, etc).
 */
fun Context.kairosThemeColor(@AttrRes attrRes: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attrRes, typedValue, true)
    return typedValue.data
}
