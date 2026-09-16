package com.termux.app

import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.termux.R
import com.termux.app.data.ModuleCatalog
import com.termux.app.util.QuickAccessPrefs

/**
 * Quick Settings Tile — togglea UN módulo elegido por el usuario (Ajustes → "Accesos rápidos")
 * sin abrir la app. Ver KDoc de [QuickAccessPrefs] para el contexto completo (investigación de
 * la ronda 2026-09-15 sobre `termux/termux-widget`) y la limitación v1 (un solo tile fijo, no N
 * tiles dinámicos por módulo — Android asocia 1 tile = 1 `TileService` declarado estáticamente
 * en el manifest).
 *
 * `qsTile`/`updateTile()` son seguros de llamar desde cualquier hilo según la documentación
 * oficial de `TileService` — igual se posta a main con [mainHandler] para no depender de esa
 * garantía y quedar consistente con el resto del proyecto (`ModuleController.startModule()`/
 * `stopModule()` entregan su callback en un `Thread` de background, nunca en main — ver
 * `.claude/rules/kotlin-kairos-android-patterns.md`).
 */
class QuickModuleTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val moduleId = QuickAccessPrefs.getTileModule(applicationContext) ?: return
        val running = ModuleController.isRunning(moduleId)
        // Estado "no disponible" mientras el toggle real corre en background — puede tardar
        // varios segundos (arranque de Ollama, n8n, etc.), el tile no tiene forma de mostrar
        // progreso intermedio, solo el estado final (mismo trade-off ya documentado en
        // BaseModuleFragment.startModuleServiceWithPolling()).
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            updateTile()
        }
        if (running) {
            ModuleController.stopModule(moduleId, applicationContext) { _ -> postRefresh() }
        } else {
            ModuleController.startModule(moduleId, applicationContext) { _, _ -> postRefresh() }
        }
    }

    private fun postRefresh() {
        mainHandler.post { refreshTile() }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val moduleId = QuickAccessPrefs.getTileModule(applicationContext)
        if (moduleId == null) {
            tile.label = getString(R.string.qs_tile_no_module_label)
            tile.state = Tile.STATE_INACTIVE
            tile.icon = Icon.createWithResource(this, R.drawable.ic_shortcut_module)
            tile.updateTile()
            return
        }
        val module = ModuleCatalog.load(applicationContext).firstOrNull { it.id == moduleId }
        tile.label = module?.name ?: moduleId
        tile.state = if (ModuleController.isRunning(moduleId)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shortcut_module)
        tile.updateTile()
    }
}
