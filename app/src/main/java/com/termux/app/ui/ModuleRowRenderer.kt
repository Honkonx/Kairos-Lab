package com.termux.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.util.TypedValue
import com.termux.R
import com.termux.app.model.ModuleInfo
import com.termux.app.model.ModuleInfo.Status
import com.termux.app.util.ModuleIcons
import com.termux.app.util.kairosThemeColor

// Piezas de renderizado compartidas entre ModuleListAdapter (pantalla Módulos) y
// PluginListAdapter (Tienda de plugins) — extraídas para que ambas filas de módulo se
// mantengan visualmente simétricas por construcción, en vez de mantener a mano dos copias
// del mismo cálculo (pedido explícito del usuario, ver docs/humano/humano115.md; hallazgo
// previo en docs/viejo/AUDITORIA_CODIGO_2026-08-13.md §3.6, antes diferido a
// propósito por el riesgo de tocar UI sin poder verla renderizada).

/** dp -> px como Float, para `GradientDrawable.cornerRadius` y similares. */
fun dpFloat(value: Int): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
        android.content.res.Resources.getSystem().displayMetrics
    )
}

/**
 * Fondo redondeado del ícono de una fila de módulo/plugin: el color declarado en
 * modules.json (`module.iconBg`, ej. "#3B82F6"), o `kairos_bg3` si el string no es un color
 * válido (`Color.parseColor` lanza `IllegalArgumentException`). Antes PluginListAdapter caía,
 * en ese mismo caso de error, en un fallback distinto e inconsistente con ModuleListAdapter
 * (el drawable `ic_module_ollama` como fondo del ícono, en vez de un fondo liso) — unificado
 * acá al criterio que ya usaba ModuleListAdapter.
 */
fun moduleIconBackground(ctx: Context, iconBg: String, cornerRadiusDp: Int = 10): Drawable {
    val color = try {
        Color.parseColor(iconBg)
    } catch (_: Exception) {
        ctx.kairosThemeColor(R.attr.kairosBg3)
    }
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpFloat(cornerRadiusDp)
    }
}

/**
 * Ícono + fondo de una fila de módulo/plugin, compartido entre ModuleListAdapter y
 * PluginListAdapter (ronda 2026-08-19, ver
 * docs/arquitectura/AUDITORIA_ICONOS_MODULOS_2026-08-19.md).
 *
 * `module.iconAsset` (solo presente en los 33 módulos con logo oficial confirmado, ver
 * ModuleInfo.kt) apunta por nombre a un drawable real embebido en res/drawable o
 * res/drawable-nodpi (ej. "ic_module_python") — se resuelve con `getIdentifier` porque el
 * nombre viaja como dato del catálogo (modules.json), no como referencia de compilación.
 * Si el lookup falla (nombre mal escrito, drawable no embebido) cae al mismo fallback que ya
 * usaban los 24 módulos sin `iconAsset`: `ModuleIcons.forModule(module.id)`.
 *
 * Los logos multicolor reales (PNG/JPG, ver `ModuleIcons.RASTER_MODULE_IDS`) NO llevan el
 * tint blanco fijo que el layout original aplicaba a todos los íconos por igual — ese tint
 * fue diseñado para los VectorDrawable de un solo trazo (blanco sobre fondo de color) y
 * convertiría un logo de marca a color en una silueta plana.
 */
fun bindModuleIcon(icon: ImageView, module: ModuleInfo) {
    val ctx = icon.context
    val assetName = module.iconAsset
    val resolvedFromAsset = assetName?.let { name ->
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName).takeIf { it != 0 }
    }
    val resId = resolvedFromAsset ?: ModuleIcons.forModule(module.id)
    icon.setImageResource(resId)

    val usesRealAsset = resolvedFromAsset != null
    val isRasterLogo = usesRealAsset && module.id in ModuleIcons.RASTER_MODULE_IDS
    icon.imageTintList = if (isRasterLogo) null else ColorStateList.valueOf(Color.WHITE)

    icon.background = moduleIconBackground(ctx, module.iconBg)
}

/**
 * Color de estado para el badge circular superpuesto sobre el ícono de una fila de
 * módulo/plugin (patrón Proxmox VE — ver docs/humano/humano194.md, investigación de paneles homelab:
 * un punto de color chico en una esquina fija escanea mejor en listas largas que recolorear
 * toda la tarjeta). Usa los mismos atributos `kairosStatus*` de attrs.xml, no un color nuevo.
 */
fun statusBadgeColor(ctx: Context, status: Status): Int = when (status) {
    Status.RUNNING -> ctx.kairosThemeColor(R.attr.kairosStatusRunning)
    Status.INSTALLED_STOPPED -> ctx.kairosThemeColor(R.attr.kairosStatusStopped)
    Status.INSTALLING -> ctx.kairosThemeColor(R.attr.kairosStatusInstalling)
    Status.ERROR -> ctx.kairosThemeColor(R.attr.kairosStatusError)
    Status.NOT_INSTALLED -> ctx.kairosThemeColor(R.attr.kairosStatusNotInstalled)
}

/**
 * Pinta el badge de estado (círculo chico, esquina inferior-derecha del ícono) — ver
 * `statusBadgeColor()`. El stroke usa `kairosBg2` (mismo fondo que la card contenedora) para
 * que el badge se vea "recortado" sobre el ícono en vez de pegado sin separación visual.
 * Indicador ADICIONAL al texto de estado ya existente (subtitle/statusLine) — no lo reemplaza,
 * la accesibilidad de texto sigue siendo la fuente principal de información.
 */
fun bindStatusBadge(badge: View, status: Status) {
    val ctx = badge.context
    badge.background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(statusBadgeColor(ctx, status))
        setStroke(dpFloat(2).toInt(), ctx.kairosThemeColor(R.attr.kairosBg2))
    }
}
