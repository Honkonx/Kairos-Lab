package com.termux.app.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.util.kairosThemeColor

/**
 * Grilla del menú "Más" — reemplaza el `PopupMenu` de lista vertical que usaba
 * `TermuxActivity.showMoreNavMenu()` (fusión 2026-08-25, ver
 * `docs/estructura/NAVEGACION_BOTTOM_SHEET_2026-08-25.md` y el mockup de referencia
 * `docs/mini-pc/MOCKUPS_NAVEGACION_2026-08-25.md`, canvas "Bottom Sheet"). El ítem "X11" salió
 * del listado en la misma ronda — sus acciones se fusionaron dentro de Mini PC
 * (`EntornoFragment`, ver `docs/mini-pc/MINIPC_TAB_2026-08-25.md`) — así que esta grilla arranca
 * con los 5 destinos restantes de `more_nav_menu.xml` (esa es la fuente real de los `R.id.nav_*`
 * — [MENU_ITEMS] abajo es solo la representación visual en grilla de esos mismos IDs, no un
 * catálogo independiente; si `more_nav_menu.xml` gana/pierde un ítem, esta lista se actualiza
 * a mano en la misma ronda).
 *
 * Primer `BottomSheetDialogFragment` del proyecto — antes no había ninguno (ver KDoc de
 * `BaseModuleFragment.showMcpPanel()`, que evaluó y descartó introducir uno porque su caso no
 * necesitaba gestos de swipe/grilla). Éste sí es el patrón real: una hoja deslizable con una
 * grilla de accesos, que un `AlertDialog`/`PopupMenu` de lista no puede dar.
 *
 * Fusión Sistema→Monitor (2026-08-26, ver docs/humano/humano225.md): el ítem "Sistema" salió
 * de la grilla — SystemFragment.kt se eliminó por completo, su contenido (RAM, almacenamiento,
 * info de dispositivo) se portó a MonitorFragment.kt como sección "DISPOSITIVO". Mismo criterio
 * que la salida de "X11" documentada arriba: si more_nav_menu.xml gana/pierde un ítem, esta
 * lista se actualiza a mano en la misma ronda.
 */
class MoreBottomSheetFragment : BottomSheetDialogFragment() {

    /**
     * Seteado por quien crea la instancia (`TermuxActivity.showMoreNavMenu()`) antes de
     * `show()` — mismo criterio que el resto de callbacks de un solo uso de la app: no
     * sobrevive a un config change (tampoco lo hacía el `PopupMenu` que reemplaza, ni el
     * `AlertDialog` con lambda que usa el resto del proyecto).
     */
    var onItemSelected: ((Int) -> Unit)? = null

    private data class MoreItem(val id: Int, val iconRes: Int, val label: String)

    private val menuItems by lazy {
        val ctx = requireContext()
        listOf(
            MoreItem(R.id.nav_monitor, R.drawable.ic_monitor, ctx.getString(R.string.more_sheet_monitor)),
            MoreItem(R.id.nav_files, R.drawable.ic_files, ctx.getString(R.string.more_sheet_files)),
            MoreItem(R.id.nav_tunnel, R.drawable.ic_tunnel, ctx.getString(R.string.more_sheet_tunnel)),
            MoreItem(R.id.nav_nube, R.drawable.ic_cloud, ctx.getString(R.string.more_sheet_nube)),
            MoreItem(R.id.nav_plugins, R.drawable.ic_store, ctx.getString(R.string.more_sheet_plugins)),
            MoreItem(R.id.nav_settings, R.drawable.ic_settings, ctx.getString(R.string.more_sheet_config))
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        root.addView(TextView(ctx).apply {
            text = getString(R.string.more_sheet_title)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            letterSpacing = 0.08f
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        val columns = 3
        val grid = GridLayout(ctx).apply { columnCount = columns }
        menuItems.forEachIndexed { i, item ->
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(92)
                columnSpec = GridLayout.spec(i % columns, 1f)
                rowSpec = GridLayout.spec(i / columns)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(buildTile(ctx, item), params)
        }
        root.addView(grid)
        return root
    }

    private fun buildTile(ctx: Context, item: MoreItem): View {
        val card = MaterialCardView(ctx).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            setOnClickListener {
                onItemSelected?.invoke(item.id)
                dismiss()
            }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        inner.addView(ImageView(ctx).apply {
            setImageResource(item.iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText))
        })
        inner.addView(TextView(ctx).apply {
            text = item.label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        card.addView(inner)
        return card
    }

    private fun dp(d: Int): Int = (d * resources.displayMetrics.density).toInt()
}
