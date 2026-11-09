package com.termux.app.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.termux.app.ModuleController
import com.termux.app.data.ModuleCatalog
import java.io.BufferedReader
import java.io.FileReader
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Lector de métricas de sistema REUSABLE — extraído del mismo /proc-reading que ya usa
 * `MonitorFragment.kt` (RAM/almacenamiento/CPU/red) para que el panel de "Homelab"
 * (`HomelabFragment`, panel nativo) y `HomelabServer` (panel web) compartan una única fuente de
 * verdad en vez de reimplementar los mismos reads dos veces (DRY, ver
 * `.claude/rules/clean-code-principles.md`). `MonitorFragment` NO se migró a usar este objeto en
 * esta ronda — su lógica propia sigue intacta con su historial de bugs ya documentado ahí; el
 * alcance de esta ronda (rediseño Homelab, 2026-09-16) es solo Homelab, no un refactor de
 * Monitor.
 *
 * A diferencia de `MonitorFragment`, este objeto es 100% independiente de un Fragment/ciclo de
 * vida — son lecturas puntuales sin estado de polling propio; el caller decide la cadencia.
 */
object SystemMetrics {

    data class RamInfo(val usedMb: Long, val totalMb: Long, val percent: Int)
    data class StorageInfo(val usedGb: Double, val totalGb: Double, val percent: Int, val hasPermission: Boolean)
    data class CpuSample(val totalJiffies: Long, val idleJiffies: Long)
    data class NetworkInfo(val type: String, val internetOk: Boolean)
    data class ModuleRunState(val id: String, val name: String, val running: Boolean)

    fun readRam(): RamInfo? {
        return try {
            var totalKb = 0L
            var availableKb = 0L
            BufferedReader(FileReader("/proc/meminfo")).use { reader ->
                reader.forEachLine { line ->
                    when {
                        line.startsWith("MemTotal:") -> totalKb = parseKb(line)
                        line.startsWith("MemAvailable:") -> availableKb = parseKb(line)
                    }
                }
            }
            if (totalKb <= 0) return null
            val usedKb = totalKb - availableKb
            RamInfo(usedKb / 1024, totalKb / 1024, ((usedKb.toFloat() / totalKb) * 100).toInt())
        } catch (_: Exception) {
            null
        }
    }

    private fun parseKb(line: String): Long = line.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L

    fun readStorage(): StorageInfo {
        if (!PermissionManager.hasStorage()) return StorageInfo(0.0, 0.0, 0, false)
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = total - free
            val pct = if (total > 0) (used * 100 / total).toInt() else 0
            StorageInfo(used / (1024.0 * 1024 * 1024), total / (1024.0 * 1024 * 1024), pct, true)
        } catch (_: Exception) {
            StorageInfo(0.0, 0.0, 0, true)
        }
    }

    /** Una sola lectura instantánea de `/proc/stat` — el caller guarda la muestra anterior y
     *  calcula el delta entre 2 llamadas con [cpuPercent] (mismo patrón que
     *  `MonitorFragment.readCpuInfo()`: no existe "% de CPU ahora mismo" de una sola lectura,
     *  los contadores son acumulados desde el boot). */
    fun sampleCpu(): CpuSample? {
        return try {
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                val line = reader.readLine() ?: return null
                if (!line.startsWith("cpu ")) return null
                val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (fields.size < 4) return null
                val idle = fields[3] + (fields.getOrNull(4) ?: 0L)
                CpuSample(fields.sum(), idle)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun cpuPercent(prev: CpuSample?, current: CpuSample?): Int? {
        if (prev == null || current == null) return null
        val totalDelta = current.totalJiffies - prev.totalJiffies
        val idleDelta = current.idleJiffies - prev.idleJiffies
        if (totalDelta <= 0) return null
        return (((totalDelta - idleDelta).toFloat() / totalDelta) * 100).toInt().coerceIn(0, 100)
    }

    /** Temperatura de batería vía el sticky broadcast `ACTION_BATTERY_CHANGED` — mismo
     *  mecanismo que ya usa `AutomationBatteryReceiver.kt` para el nivel de batería;
     *  `EXTRA_TEMPERATURE` viene en décimas de grado Celsius. Proxy razonable de "temperatura
     *  del dispositivo" sin depender de `/sys/class/thermal` (rutas no estandarizadas por
     *  fabricante, ilegibles sin root en la mayoría de los dispositivos). */
    fun batteryTemperatureC(context: Context): Float? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (tenths < 0) null else tenths / 10f
        } catch (_: Exception) {
            null
        }
    }

    fun batteryLevel(context: Context): Int? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) null else (level * 100 / scale)
        } catch (_: Exception) {
            null
        }
    }

    fun networkInfo(context: Context): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return NetworkInfo("sin_conexion", false)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos_moviles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "otro"
        }
        return NetworkInfo(type, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    /** IP local real — mismo mecanismo que `NubeServer.localIpAddress()`/
     *  `RemoteManager.getLocalIp()`, duplicado acá a propósito (función de 6 líneas — no vale la
     *  pena una dependencia cruzada entre `util/NubeServer.kt` y `util/SystemMetrics.kt` solo
     *  por esto). Toma la PRIMERA de [localIpAddresses] -- un dispositivo con WiFi + hotspot/VPN
     *  simultáneos tiene más de una, pero la mayoría de los callers (un solo campo de texto en
     *  la UI) solo necesitan "una dirección razonable", no la lista completa. */
    fun localIpAddress(): String? = localIpAddresses().firstOrNull()

    /** TODAS las IPv4 locales no-loopback (hallazgo real: antes solo se exponía la primera,
     *  descartando en silencio cualquier otra interfaz activa -- ej. WiFi + hotspot a la vez).
     *  Usado por [com.termux.app.util.HomelabServer] para exponer la lista completa, además del
     *  campo singular de siempre. */
    fun localIpAddresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .mapNotNull { it.hostAddress }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Estado corriendo/detenido de TODOS los módulos con switch real (`hasSwitch:true`,
     *  `!internal`) — misma fuente de verdad que `ModuleController.isRunning()`/`ModuleCatalog`,
     *  sin lista hardcodeada (a diferencia de `MonitorFragment.processModules()`, que hardcodea
     *  un subconjunto fijo — acá se quiere el panel COMPLETO de módulos, pedido explícito del
     *  usuario: "módulos corriendo"). */
    fun moduleStates(context: Context): List<ModuleRunState> {
        return try {
            ModuleCatalog.load(context)
                .filter { it.hasSwitch && !it.internal }
                .sortedBy { it.name.lowercase() }
                .map { m ->
                    val running = try { ModuleController.isRunning(m.id) } catch (_: Exception) { false }
                    ModuleRunState(m.id, m.name, running)
                }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
