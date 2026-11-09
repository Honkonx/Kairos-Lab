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

// ── Filas de lista con descripción (rediseño de Mini PC aprobado por el usuario 2026-10-08,
// mensaje 6: "Sistema — audio y GPU con interruptor y descripción, [...]
// Pantalla — X11 y VNC juntos, en lista con descripción"). Reemplazan a los tiles de 92dp sin
// contexto en las pestañas Pantalla y Sistema. Mismo estilo de card que actionTile().

/** Una fila: ícono + título + descripción opcional + punto verde si `running` + flecha. */
internal data class RowAction(
    val title: String,
    val description: String? = null,
    val iconRes: Int,
    val running: () -> Boolean = { false },
    val onClick: () -> Unit
)

/** Card con las [rows] una debajo de otra, separadas por una línea fina. */
internal fun rowList(ctx: Context, parent: LinearLayout, rows: List<View>) {
    val card = MaterialCardView(ctx).apply {
        radius = dp(ctx, 12).toFloat()
        cardElevation = 0f
        strokeWidth = dp(ctx, 1)
        strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
        setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 8))
        }
    }
    val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    rows.forEachIndexed { i, row ->
        if (i > 0) {
            list.addView(View(ctx).apply {
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosDivider))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
            })
        }
        list.addView(row)
    }
    card.addView(list)
    parent.addView(card)
}

private fun rowShell(ctx: Context, iconRes: Int, title: String, description: String?, trailing: View?): LinearLayout {
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 56)
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        addView(ImageView(ctx).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(ctx.kairosThemeColor(R.attr.kairosText))
            // Ícono decorativo: TalkBack ya lee el título y la descripción de la fila.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)).apply { marginEnd = dp(ctx, 14) }
        })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                // Los títulos de los tiles viejos traen "\n" ("Instalar\ndistro"): en una fila de ancho
                // completo partían el título en dos líneas con la descripción mucho más ancha.
                text = title.replace("\n", " ")
                textSize = 13.5f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
            if (!description.isNullOrBlank()) {
                addView(TextView(ctx).apply {
                    text = description
                    textSize = 11f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
        })
        trailing?.let { addView(it) }
    }
}

/** Fila que navega/ejecuta: flecha "›" a la derecha y punto verde si está activa. */
internal fun actionRow(ctx: Context, row: RowAction): View {
    val trailing = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (row.running()) {
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9)).apply { marginEnd = dp(ctx, 10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                }
            })
        }
        addView(TextView(ctx).apply {
            text = "›"
            textSize = 20f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })
    }
    return rowShell(ctx, row.iconRes, row.title, row.description, trailing).apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { row.onClick() }
    }
}

/**
 * Fila con interruptor (ej. PulseAudio). `checked` es el estado real al momento de dibujar; el
 * listener se agrega DESPUÉS de fijarlo para que dibujar la fila nunca dispare la acción.
 */
internal fun switchRow(
    ctx: Context,
    iconRes: Int,
    title: String,
    description: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
): View {
    val toggle = androidx.appcompat.widget.SwitchCompat(ctx).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, isOn -> onToggle(isOn) }
    }
    return rowShell(ctx, iconRes, title, description, toggle).apply {
        isClickable = true
        setOnClickListener { toggle.toggle() }
    }
}
