package com.termux.app.util

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Límite de instalaciones de módulos simultáneas — pedido explícito del usuario: instalar más
 * de 4 módulos a la vez (manual o vía "descargar/instalar en segundo plano") tiende a hacer
 * fallar alguno (saturación de CPU/red/proot, o rate-limit de algún repo). Antes NO existía
 * ningún mecanismo de conteo/límite global: cada llamada a `ModuleController.installModule()`
 * lanzaba su propio `Thread`/`ProcessBuilder` de forma totalmente independiente, sin ningún
 * semáforo/contador compartido — confirmado por lectura completa de `ModuleController.kt` antes
 * de este cambio (ver `docs/arquitectura/COLA_INSTALACION_MODULOS.md`).
 *
 * Diseño: contador global + cola FIFO, sin `Context`/UI — vive en `util/` para que
 * `ModuleController` (un `object` sin `Context` propio) y cualquier Fragment puedan consultarlo
 * por igual. [submit] corre la tarea de inmediato si hay cupo, o la encola si ya hay
 * [MAX_CONCURRENT_INSTALLS] corriendo; [release] (llamado SIEMPRE al terminar una instalación,
 * éxito o error, vía `finally` en `ModuleController.installModule`) libera el cupo y arranca la
 * siguiente tarea encolada, si hay alguna.
 *
 * `synchronized` en vez de solo un `AtomicInteger`: "chequear cupo + reservarlo" y "liberar
 * cupo + arrancar el siguiente de la cola" son operaciones compuestas que necesitan ser
 * atómicas juntas — un `AtomicInteger` aislado no evita una carrera entre dos [submit]
 * simultáneos que ven "hay cupo" en el mismo instante, ni entre [release] y [submit]
 * disputándose el mismo cupo liberado.
 */
object InstallQueueManager {

    /** Pedido explícito del usuario: máximo 4 instalaciones reales corriendo a la vez. */
    const val MAX_CONCURRENT_INSTALLS = 4

    private val lock = Any()
    private var active = 0
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    /** Cuántas instalaciones están corriendo ahora mismo (no incluye las encoladas). */
    fun activeCount(): Int = synchronized(lock) { active }

    /** Cuántas instalaciones esperan turno en la cola. */
    fun queuedCount(): Int = queue.size

    /** true si el próximo [submit] entraría en cola en vez de arrancar de inmediato — usado
     * por la UI (switch "Instalación silenciosa (en segundo plano)") para deshabilitarse sola
     * cuando ya no hay cupo real. */
    fun isAtCapacity(): Boolean = synchronized(lock) { active >= MAX_CONCURRENT_INSTALLS }

    /**
     * Corre [task] de inmediato si hay cupo, o la encola si no. [onQueued] se invoca — en el
     * mismo hilo que llama a [submit], de forma síncrona — solo cuando la tarea entró en cola,
     * para que el caller pueda avisarle al usuario ("En cola — se instalará cuando termine otro
     * módulo") en vez de dejarlo pensando que no pasó nada.
     */
    fun submit(onQueued: () -> Unit = {}, task: () -> Unit) {
        val runNow = synchronized(lock) {
            if (active < MAX_CONCURRENT_INSTALLS) {
                active++
                true
            } else {
                queue.add(task)
                false
            }
        }
        if (runNow) task() else onQueued()
    }

    /**
     * Libera un cupo y arranca la siguiente tarea encolada (si hay alguna) — SIEMPRE debe
     * llamarse exactamente una vez por cada [task] que [submit] efectivamente arrancó (éxito o
     * error de esa instalación), o el contador queda inflado para siempre y la cola se traba.
     * decrementar + hacer poll() + re-incrementar quedan en el MISMO bloque synchronized para
     * que no se cuele un [submit] nuevo entre medio y le gane el cupo recién liberado a la
     * tarea que ya estaba esperando en la cola (FIFO real, no "quien llegue primero gana").
     */
    fun release() {
        var next: (() -> Unit)? = null
        synchronized(lock) {
            active--
            next = queue.poll()
            if (next != null) active++
        }
        next?.invoke()
    }
}
