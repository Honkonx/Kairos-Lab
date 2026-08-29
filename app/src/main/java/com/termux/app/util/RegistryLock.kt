package com.termux.app.util

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Locking real (java.nio FileLock + `synchronized` intra-proceso) para read-modify-write
 * sobre un archivo de registry compartido — extraído de
 * ProjectsManager.withRegistryLock() (consolidación 2026-08-13, ver auditoría de código:
 * TunnelManager.registryFile()/registryValues()/setRegistryValues()/removeRegistryValues()
 * y EntornoNative.updateRegistryValue() reimplementaban el mismo read-modify-write sobre
 * ~/.android_server_registry SIN ninguna protección de concurrencia).
 *
 * FileChannel.lock() de Java NO bloquea a otro hilo de la misma JVM que intente lockear la
 * misma región (lanza OverlappingFileLockException en vez de esperar, a diferencia de
 * flock() en POSIX) — de ahí el `synchronized` de proceso además del FileLock real, igual
 * que el mecanismo original de ProjectsManager. El monitor de `synchronized` se resuelve
 * por ruta absoluta del archivo de lock, así que managers distintos que apunten al MISMO
 * archivo de lock (ej. ManagerNativeUtils.registryLockFile, compartido por
 * ProjectsManager/TunnelManager/EntornoNative) quedan serializados entre sí, no solo
 * dentro de su propio manager.
 */
object RegistryLock {

    private val intraProcessMonitors = ConcurrentHashMap<String, Any>()

    private fun monitorFor(lockFile: File): Any =
        intraProcessMonitors.getOrPut(lockFile.absolutePath) { Any() }

    fun <T> withLock(lockFile: File, block: () -> T): T {
        synchronized(monitorFor(lockFile)) {
            lockFile.parentFile?.mkdirs()
            RandomAccessFile(lockFile, "rw").use { raf ->
                val fileLock = raf.channel.lock()
                try {
                    return block()
                } finally {
                    fileLock.release()
                }
            }
        }
    }
}
