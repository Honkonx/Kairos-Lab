package com.termux.app.util

import android.app.ActivityManager
import android.content.Context

/**
 * Chequeo de RAM disponible del dispositivo antes de cargar un modelo GGUF/Ollama pesado —
 * hallazgo de auditoría de la categoría ia de referencia/ (2026-08-31). Kairos ya tenía un chequeo de RAM
 * TOTAL vs. tamaño estimado del modelo antes de descargarlo (ModelsFragment.totalRamGb(),
 * "puede no entrar en RAM"), pero nada que mire cuánta RAM está LIBRE en el momento real de
 * cargar/usar el modelo — un dispositivo con RAM total suficiente puede igual estar bajo de
 * memoria disponible en ese instante (otras apps, otros módulos de Kairos corriendo), y el
 * OOM-killer de Android mata el proceso sin aviso previo (mismo síntoma ya reportado antes
 * para el caso de RAM total insuficiente).
 *
 * Envoltorio fino sobre ActivityManager.getMemoryInfo() — no reimplementa nada, solo centraliza
 * el cálculo de porcentaje usado para que no se repita en cada Fragment que lo necesite.
 */
object MemoryMonitor {

    private const val WARNING_THRESHOLD = 0.85
    private const val CRITICAL_THRESHOLD = 0.95

    fun getMemoryInfo(context: Context): ActivityManager.MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo
    }

    /** % de RAM total actualmente en uso (0.0–1.0), calculado desde availMem/totalMem. */
    fun usedFraction(context: Context): Double {
        val memInfo = getMemoryInfo(context)
        if (memInfo.totalMem <= 0L) return 0.0
        val usedMem = memInfo.totalMem - memInfo.availMem
        return usedMem.toDouble() / memInfo.totalMem.toDouble()
    }

    /** RAM libre real, en GB — para mostrar en un diálogo de advertencia. */
    fun availableGb(context: Context): Double =
        getMemoryInfo(context).availMem / (1024.0 * 1024.0 * 1024.0)

    /** RAM TOTAL del dispositivo, en GB — mismo cálculo que ModelsFragment.totalRamGb() (no
     *  refactorizado ahí para no tocar lógica ya probada sin pedido explícito), reusado acá
     *  para el wizard de primer arranque (auditoría 2026-09-01, repropósito del mecanismo de
     *  RAM ya usado en Monitor/QEMU/ModelsFragment — ver WizardWelcomeFragment). */
    fun totalGb(context: Context): Double =
        getMemoryInfo(context).totalMem / (1024.0 * 1024.0 * 1024.0)

    /** Más del 85% de RAM en uso — aviso breve (Toast/Snackbar), no bloquea nada. */
    fun isMemoryWarning(context: Context): Boolean = usedFraction(context) > WARNING_THRESHOLD

    /** Más del 95% de RAM en uso — riesgo real de OOM-kill inminente, mostrar diálogo real. */
    fun isMemoryCritical(context: Context): Boolean = usedFraction(context) > CRITICAL_THRESHOLD
}
