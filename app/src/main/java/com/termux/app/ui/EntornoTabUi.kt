package com.termux.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.util.DistroIcons
import com.termux.app.util.kairosThemeColor

/**
 * Helpers de UI puramente presentacionales para las pestañas de EntornoFragment (Mini PC) —
 * extraídos en el refactor de separación por pestaña (EntornoFragment.kt pasó de ~2600 líneas
 * a un cascarón + EntornoNativoTab/EntornoX11Tab/EntornoDistrosTab/EntornoVncTab/
 * EntornoSistemaTab). Reorganización pura de código ya existente — ningún comportamiento
 * cambia, ver KDoc original de cada función (git history de EntornoFragment.kt) para el
 * razonamiento de diseño completo.
 *
 * Sin estado propio y sin depender de Fragment — reciben siempre un Context explícito, así
 * las 5 clases de pestaña (que NO heredan de Fragment) pueden llamarlas directo sin necesitar
 * acceso a miembros protegidos de BaseModuleFragment.
 */

/** Replica EXACTA de BaseModuleFragment.dp() ((d * density).toInt()) — las clases de pestaña
 *  no heredan de Fragment y no pueden llamar a ese método protegido; mismo resultado numérico. */
internal fun dp(ctx: Context, d: Int): Int = (d * ctx.resources.displayMetrics.density).toInt()

/** Una tile del grid: ícono + texto corto + acción, con badge verde opcional si `running` da true (evaluado en el momento de renderizar, no cacheado). */
internal data class TileAction(
    val label: String,
    val iconRes: Int,
    val running: () -> Boolean = { false },
    val onClick: () -> Unit
)

/** Subtítulo chico dentro de una card (ej. "📋 Instalado" en EntornoFragment, "Servidor X11" en EntornoX11Tab) — distinto de sectionLabel() (esa es entre cards/pestañas, letterSpacing más ancho). */
internal fun inventorySubLabel(ctx: Context, text: String): TextView {
    return TextView(ctx).apply {
        this.text = text
        textSize = 10f
        setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 4))
    }
}

/** Mismo estilo del título que usa addCard() internamente (BaseModuleFragment.kt) pero sin envolver un MaterialCardView vacío — subtítulo "Lanzar"/"Mantenimiento" dentro de cada pestaña. */
internal fun sectionLabel(ctx: Context, parent: LinearLayout, text: String) {
    parent.addView(TextView(ctx).apply {
        this.text = text
        textSize = 10f
        setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.12f
        setPadding(dp(ctx, 4), dp(ctx, 16), dp(ctx, 4), dp(ctx, 8))
    })
}

/** Grid de 3 columnas — usado por el render() de cada pestaña, uno por subsección ("Lanzar"/"Mantenimiento"). */
internal fun tileGrid(ctx: Context, parent: LinearLayout, actions: List<TileAction>) {
    val grid = GridLayout(ctx).apply {
        columnCount = 3
        setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 6))
    }
    actions.forEachIndexed { i, action ->
        val params = GridLayout.LayoutParams().apply {
            width = 0
            height = dp(ctx, 92)
            columnSpec = GridLayout.spec(i % 3, 1f)
            rowSpec = GridLayout.spec(i / 3)
            setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        grid.addView(actionTile(ctx, action), params)
    }
    parent.addView(grid)
}

/** Tile individual: MaterialCardView con ícono (tintado por tema) + texto centrado + punto verde de "corriendo" en la esquina — mismo estilo de card que distroTile(), generalizado para cualquier acción. */
internal fun actionTile(ctx: Context, action: TileAction): View {
    val card = MaterialCardView(ctx).apply {
        radius = dp(ctx, 12).toFloat()
        cardElevation = 0f
        strokeWidth = dp(ctx, 1)
        strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
        setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
        setOnClickListener { action.onClick() }
    }
    val frame = FrameLayout(ctx)
    val inner = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 6), dp(ctx, 8), dp(ctx, 6), dp(ctx, 6))
    }
    inner.addView(ImageView(ctx).apply {
        setImageResource(action.iconRes)
        imageTintList = ColorStateList.valueOf(ctx.kairosThemeColor(R.attr.kairosText))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26))
    })
    inner.addView(TextView(ctx).apply {
        text = action.label
        textSize = 10.5f
        gravity = Gravity.CENTER
        maxLines = 3
        setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        setPadding(0, dp(ctx, 6), 0, 0)
    })
    frame.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    if (action.running()) {
        frame.addView(View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(ctx, 6), dp(ctx, 6), 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            }
        })
    }
    card.addView(frame)
    return card
}

/**
 * Ícono de identidad de distro (2026-08-27) — compartido por las filas de inventario/sesiones
 * de EntornoFragment (distroInventoryRow()/distroSessionRow()) y por distroTile() en
 * EntornoDistrosTab. Dos variantes, elegidas por `DistroIcons.useLightBadge()`:
 * - Ubuntu/Kali/fallback: círculo del color de marca (DistroIcons.colorHex) + ícono tintado
 *   blanco encima — su marca oficial real ES blanca sobre fondo de color.
 * - Debian/Alpine/Arch/Fedora/Manjaro/openSUSE/RockyLinux/Void: círculo claro (blanco fijo,
 *   igual en los 3 temas de Kairos) + ícono SIN tint, porque el propio drawable ya trae el hex
 *   real de la marca en sus paths.
 */
internal fun distroIconView(ctx: Context, name: String, sizeDp: Int): ImageView {
    val lightBadge = DistroIcons.useLightBadge(name)
    return ImageView(ctx).apply {
        setImageResource(DistroIcons.iconRes(name))
        imageTintList = if (lightBadge) null else ColorStateList.valueOf(android.graphics.Color.WHITE)
        val pad = dp(ctx, sizeDp / 5)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (lightBadge) {
                    android.graphics.Color.WHITE
                } else {
                    try {
                        android.graphics.Color.parseColor(DistroIcons.colorHex(name))
                    } catch (_: Exception) {
                        ctx.kairosThemeColor(R.attr.kairosBg3)
                    }
                }
            )
        }
    }
}
