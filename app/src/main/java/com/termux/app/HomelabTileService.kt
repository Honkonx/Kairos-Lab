package com.termux.app

import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.termux.R
import com.termux.app.util.HomelabServer

/**
 * Quick Settings Tile — enciende/apaga el panel web de Homelab ([HomelabServer]) sin abrir la
 * app. Ítem #6 de `docs/referencias/homelab/AUDITORIA_homelab_2026-10-09.md` (inspirado en
 * `SshdTileService` de Sshd4a-dev, GPL-3 — solo se adoptó la IDEA de un tile, código propio desde
 * cero, ver `.claude/rules/kairos-port-or-build-own.md`). Mismo patrón que
 * [QuickModuleTileService] (postear a main con [mainHandler] en vez de depender de la garantía
 * documentada de que `qsTile`/`updateTile()` son seguros desde cualquier hilo).
 */
class HomelabTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            updateTile()
        }
        val appContext = applicationContext
        if (HomelabServer.isRunning) {
            Thread {
                HomelabServer.stop()
                mainHandler.post { refreshTile() }
            }.start()
        } else {
            Thread {
                HomelabServer.start(context = appContext)
                mainHandler.post { refreshTile() }
            }.start()
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.homelab_tile_label)
        tile.state = if (HomelabServer.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_homelab)
        tile.updateTile()
    }
}
