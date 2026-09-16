package com.termux.app.util

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.IntRange

/**
 * Resuelve un color de tema (?attr/kairos*, ver themes_kairos.xml) en el momento — reemplaza
 * `ContextCompat.getColor(ctx, R.color.kairos_x)` (fijo, un solo tema posible) en código Kotlin
 * que setea colores programáticamente (selector de temas Oscuro/Señal/Claro agregado 2026-08-22).
 * El XML de layouts usa ?attr/kairos* directo — esto es el equivalente
 * para las clases que arman colores a mano (ModuleListAdapter, GradientDrawable de íconos, etc).
 */
fun Context.kairosThemeColor(@AttrRes attrRes: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attrRes, typedValue, true)
    return typedValue.data
}

/**
 * Igual que [kairosThemeColor] pero con un alfa fijo aplicado — reemplaza el patrón repetido
 * `Color.parseColor("#26EF4444")`/`Color.parseColor("#1A22C55E")` (hex fijo del tema Oscuro,
 * roto en Señal/Claro porque `kairosRed`/`kairosGreen` tienen valores distintos por tema, ver
 * `colors_kairos_senal.xml`/`colors_kairos_claro.xml`) que apareció duplicado en
 * `BaseModuleFragment.kt`, `BottomSheetInstalacion.kt`, `ModuleListAdapter.kt`,
 * `HomelabFragment.kt` y `OllamaFragment.kt` — auditoría de temas 2026-09-15. Usado para
 * fondos "tenues" (badges, chips activos, tarjetas seleccionadas) derivados de un color sólido
 * del tema ya migrado, en vez de repetir `Color.argb(alpha, Color.red(c), Color.green(c),
 * Color.blue(c))` en cada call-site.
 */
fun Context.kairosThemeColorAlpha(@AttrRes attrRes: Int, @IntRange(from = 0, to = 255) alpha: Int): Int {
    val base = kairosThemeColor(attrRes)
    return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
}
