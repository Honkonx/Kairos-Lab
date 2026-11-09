package com.termux.app.util

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.termux.kairos.privileged.IKairosPrivilegedService

/**
 * Implementación real del Binder del Alcance B (ver KDoc del .aidl y
 * `docs/arquitectura/PLAN_ADB_USB_GATEWAY_2026-10-31.md` § "diseño técnico real"). Corre DENTRO
 * del proceso privilegiado arrancado por [KairosPrivilegedServerMain] — nunca dentro del proceso
 * normal de la app (ese no tiene identidad shell/root, solo el proceso que `adb shell`/`su`
 * lanzó la tiene).
 *
 * [context] se usa solo para [KairosPrivilegedConsentStore] (leer PackageManager) — no para nada
 * que dependa de un Activity/ciclo de vida de UI, porque este proceso corre vía `app_process`
 * sin ninguno de esos.
 */
class KairosPrivilegedService(private val context: Context) : IKairosPrivilegedService.Stub() {

    override fun getServerUid(): Int = Process.myUid()

    /**
     * Hallazgo real de revisión automática (2026-10-31, "authorization bypass — caller-asserted
     * identity"): la primera versión confiaba en el parámetro [callerPackage] tal cual lo
     * mandaba el cliente — cualquier app podía mentir ahí y hacerse pasar por otra ya
     * autorizada. La identidad real NUNCA sale de un argumento: sale de
     * `Binder.getCallingUid()` (puesto por el kernel en cada transacción, no falsificable desde
     * el lado del cliente) resuelto contra `PackageManager.getPackagesForUid()` — el paquete
     * que el cliente DICE tener solo se acepta si de verdad aparece en esa lista real.
     */
    private fun verifiedCallerPackage(claimedPackage: String): String {
        val callingUid = Binder.getCallingUid()
        val packagesForUid = context.packageManager.getPackagesForUid(callingUid)
            ?: throw SecurityException("no se pudo resolver el paquete del uid $callingUid")
        if (claimedPackage !in packagesForUid) {
            throw SecurityException("el paquete declarado ($claimedPackage) no coincide con el uid real que llama ($callingUid)")
        }
        return claimedPackage
    }

    override fun isPackageAuthorized(callerPackage: String): Boolean =
        KairosPrivilegedConsentStore.isAuthorized(context, verifiedCallerPackage(callerPackage))

    override fun newProcess(
        callerPackage: String,
        cmd: Array<String>,
        env: Array<String>,
        workDir: String?
    ): Array<ParcelFileDescriptor> {
        val realPackage = verifiedCallerPackage(callerPackage)
        if (!KairosPrivilegedConsentStore.isAuthorized(context, realPackage)) {
            throw SecurityException("$realPackage no está autorizado — pedile a la persona que confirme desde Kairos (Config → Permisos)")
        }
        val pb = ProcessBuilder(*cmd)
        if (workDir != null && workDir.isNotBlank()) pb.directory(java.io.File(workDir))
        if (env.isNotEmpty()) {
            val envMap = pb.environment()
            for (entry in env) {
                val eq = entry.indexOf('=')
                if (eq > 0) envMap[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
        }
        pb.redirectErrorStream(false)
        // Hallazgo real de la auditoría de Shizuku (docs/referencias/pc-movil/REFERENCIA_SHIZUKU.md
        // punto 1, 2026-11-02): esta función devolvía solo 3 streams, sin ninguna forma de matar
        // el proceso si la app cliente muere — un proceso con identidad shell/root podía quedar
        // vivo huérfano. Shizuku real resuelve esto con linkToDeath sobre un token Binder; acá no
        // hace falta cambiar el AIDL — alcanza con vigilar el PID real del llamador
        // (Binder.getCallingPid(), puesto por el kernel, no falsificable) con el mismo patrón de
        // polling de /proc/<pid> que ya usa ForegroundProcessDetector.kt en este proyecto.
        val callerPid = Binder.getCallingPid()
        val process = pb.start()
        watchCallerAndKillOnDeath(callerPid, process)
        // ParcelFileDescriptor.dup() porque los streams de Process no son FileDescriptor
        // directos en todas las versiones — fromFd() exige un descriptor real, se obtiene vía
        // reflexión del campo interno si el cast directo no aplica. Camino simple primero: la
        // mayoría de las implementaciones de Process en Android SÍ exponen FileDescriptor real.
        val stdin = ParcelFileDescriptor.dup((process.outputStream as java.io.FileOutputStream).fd)
        val stdout = ParcelFileDescriptor.dup((process.inputStream as java.io.FileInputStream).fd)
        val stderr = ParcelFileDescriptor.dup((process.errorStream as java.io.FileInputStream).fd)
        return arrayOf(stdin, stdout, stderr)
    }

    /**
     * Mata [process] si el proceso llamador ([callerPid]) deja de existir antes de que [process]
     * termine solo. Sondeo simple (`/proc/<pid>` existe) cada 2s — igual de válido que un
     * `Binder.DeathRecipient` para este caso (el cliente mantiene el Binder vivo mientras su
     * proceso vive) y no requiere cambiar la interfaz AIDL pública. Hilo demonio: no debe
     * mantener la JVM viva si el proceso termina solo y nadie más referencia este hilo.
     */
    private fun watchCallerAndKillOnDeath(callerPid: Int, process: java.lang.Process) {
        val watcher = Thread {
            try {
                while (process.isAlive) {
                    if (!java.io.File("/proc/$callerPid").exists()) {
                        process.destroyForcibly()
                        return@Thread
                    }
                    Thread.sleep(2000)
                }
            } catch (_: InterruptedException) {
                // Watcher descartado por limpieza normal del proceso — nada que hacer.
            }
        }
        watcher.isDaemon = true
        watcher.start()
    }
}
