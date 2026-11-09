package com.termux.app.util

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject

/**
 * `qemu.ram_available` — mismo chequeo real que `QemuFragment.availableRamMb()` (RAM LIBRE ahora
 * mismo, no el total del dispositivo — ver ese comentario sobre por qué). Arrancar la VM en sí
 * (2026-10-16,) NO necesita una acción nueva: ya es posible con
 * `session.open` genérico (`bash <HOME>/scripts/qemu/run_vm.sh '<imagen>' <ram_mb> 2222 console ''
 * <arch>`, el mismo comando real que `QemuFragment.bootVmConsole()` ya corre) — el gap real era
 * no tener cómo chequear la RAM disponible antes de pedirlo, eso es lo que esta acción cierra.
 */
interface GatewayQemuRamOps {
    fun available(): JSONObject
}

class AppQemuRamOps(private val context: Context) : GatewayQemuRamOps {
    override fun available(): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return JSONObject().put("ok", false).put("error", "ActivityManager no disponible")
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return JSONObject().put("ok", true).put("available_mb", info.availMem / (1024 * 1024))
    }
}
