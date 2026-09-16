package com.termux.app.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.data.ModuleCatalog

/**
 * Accesos rápidos fuera de la app (2026-09-15) — investigación de esta ronda (Quick Settings
 * Tile + App Shortcuts) confirmó que el propio ecosistema Termux ya valida esta demanda:
 * `termux/termux-widget` es un plugin OFICIAL dedicado solo a lanzar scripts desde el launcher.
 * Kairos no tenía ningún acceso rápido fuera de la app para prender/apagar un módulo favorito —
 * esto cierra ese gap con 2 mecanismos distintos, mismo storage compartido:
 *
 *  1. **App Shortcuts** (long-press del ícono del launcher) — hasta [MAX_FAVORITES] módulos
 *     marcados como favoritos desde su propia pantalla de detalle (ver
 *     `BaseModuleFragment.addFavoriteStar()`), re-publicados como shortcuts DINÁMICOS
 *     (`ShortcutManagerCompat`) cada vez que cambia el set. v1: cada shortcut abre directo la
 *     pantalla de detalle del módulo (ver `TermuxActivity.handleShortcutIntent()`) — no lo
 *     togglea desde el shortcut mismo, pedido explícito de la ronda 2026-09-15 ("no hace falta
 *     que el shortcut mismo togglee el módulo, alcanza con navegación directa en esta v1").
 *  2. **Quick Settings Tile** (`QuickModuleTileService`) — UN módulo fijo, elegido en
 *     Ajustes (`ConfigFragment` → sección "Accesos rápidos"), que el tile togglea con un tap
 *     desde el panel de ajustes rápidos sin abrir la app. v1: un solo tile configurable, no N
 *     tiles dinámicos — Android asocia 1 tile = 1 `TileService` declarado estáticamente en el
 *     manifest, no hay forma de registrar tiles dinámicos por módulo sin una clase nueva por
 *     cada uno; documentado como limitación v1, no una omisión silenciosa.
 *
 * Storage: SharedPreferences "kairos_prefs" (el mismo archivo que ya usa TODO el resto de
 * `ConfigFragment` — pref_auto_start, pref_floating_widget, etc.) — no el registry de texto
 * plano de `ManagerNativeUtils`/`ProjectsManager` (ese es para estado que un script bash
 * necesita leer; esto es puramente UI de Kairos, ningún script lo consume).
 */
object QuickAccessPrefs {

    private const val PREFS_NAME = "kairos_prefs"
    private const val KEY_FAVORITES = "pref_favorite_modules"
    private const val KEY_TILE_MODULE = "pref_qs_tile_module"

    /** Límite duro pedido explícitamente por el usuario: hasta 4 módulos favoritos. */
    const val MAX_FAVORITES = 4

    enum class FavoriteToggleResult { ADDED, REMOVED, LIMIT_REACHED }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Ids de los módulos favoritos, en el orden en que se marcaron. */
    fun getFavorites(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_FAVORITES, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun isFavorite(context: Context, moduleId: String): Boolean =
        moduleId in getFavorites(context)

    /**
     * Agrega/quita [moduleId] de favoritos y re-publica los shortcuts dinámicos del launcher.
     * Devuelve [FavoriteToggleResult.LIMIT_REACHED] sin aplicar el cambio si ya hay
     * [MAX_FAVORITES] y se intentaba agregar uno más — el caller (star del header, fila de
     * ConfigFragment) decide cómo avisarlo (toast con `getString()`, que esta clase no puede
     * resolver por no ser un Fragment).
     */
    fun toggleFavorite(context: Context, moduleId: String): FavoriteToggleResult {
        val current = getFavorites(context).toMutableList()
        val result: FavoriteToggleResult
        if (moduleId in current) {
            current.remove(moduleId)
            result = FavoriteToggleResult.REMOVED
        } else {
            if (current.size >= MAX_FAVORITES) return FavoriteToggleResult.LIMIT_REACHED
            current.add(moduleId)
            result = FavoriteToggleResult.ADDED
        }
        prefs(context).edit().putString(KEY_FAVORITES, current.joinToString(",")).apply()
        syncShortcuts(context)
        return result
    }

    /** Módulo fijo controlado por el Quick Settings Tile, o null si el usuario no eligió ninguno. */
    fun getTileModule(context: Context): String? =
        prefs(context).getString(KEY_TILE_MODULE, null)?.takeIf { it.isNotBlank() }

    fun setTileModule(context: Context, moduleId: String?) {
        prefs(context).edit().apply {
            if (moduleId.isNullOrBlank()) remove(KEY_TILE_MODULE) else putString(KEY_TILE_MODULE, moduleId)
        }.apply()
        // El propio TileService relee getTileModule() en cada onStartListening() — Android llama
        // a eso solo con que el panel de ajustes rápidos vuelva a mostrarse, no hace falta forzar
        // un refresh acá.
    }

    /**
     * Re-publica TODOS los shortcuts dinámicos desde cero a partir de [getFavorites] —
     * `setDynamicShortcuts()` reemplaza el set completo, no hace falta remover a mano. Llamar
     * después de cualquier `toggleFavorite()` y también una vez al arrancar la app
     * (`TermuxActivity.onCreate()`) para cubrir el caso de un módulo que se desinstaló/dejó de
     * existir en el catálogo mientras la app estaba cerrada — módulos ausentes del catálogo se
     * descartan en silencio (`mapNotNull`), no rompen el resto de los shortcuts.
     */
    @JvmStatic
    fun syncShortcuts(context: Context) {
        val appContext = context.applicationContext
        val catalog = ModuleCatalog.load(appContext)
        val shortcuts = getFavorites(appContext).mapNotNull { id ->
            val module = catalog.firstOrNull { it.id == id } ?: return@mapNotNull null
            ShortcutInfoCompat.Builder(appContext, "module_$id")
                .setShortLabel(module.name)
                .setLongLabel(module.name)
                .setIcon(IconCompat.createWithResource(appContext, R.drawable.ic_shortcut_module))
                .setIntent(
                    Intent(appContext, TermuxActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(TermuxActivity.EXTRA_SHORTCUT_MODULE_ID, id)
                )
                .build()
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts)
        } catch (_: Exception) {
            // Best-effort: un launcher sin soporte de shortcuts dinámicos (raro) simplemente no
            // los muestra — no vale la pena crashear la app por esto.
        }
    }
}
