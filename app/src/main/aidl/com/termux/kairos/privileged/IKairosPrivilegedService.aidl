package com.termux.kairos.privileged;

/**
 * Alcance B (docs/arquitectura/PLAN_ADB_USB_GATEWAY_2026-10-31.md, sección "diseño técnico
 * real"): servidor privilegiado propio tipo Shizuku — ejecuta comandos con la identidad con la
 * que arrancó este proceso (shell vía ADB, o root si el dispositivo lo tiene real), nunca más
 * que eso. Interfaz mínima a propósito: no hay forma de pedir nada más estructurado que "correr
 * este comando" — cualquier capacidad adicional que un cliente necesite se construye corriendo
 * el comando adecuado con [newProcess], no agregando un método nuevo por cada caso.
 *
 * oneway NO se usa a propósito en ninguno de los 3 métodos: el cliente necesita la respuesta
 * real (uid, file descriptors, o si está autorizado) antes de seguir, nunca "dispara y olvida".
 *
 * [callerPackage] en [isPackageAuthorized]/[newProcess] es solo lo que el cliente DICE tener —
 * nunca la fuente real de identidad (hallazgo de revisión automática, 2026-10-31: "authorization
 * bypass — caller-asserted identity", corregido). El servidor siempre lo verifica contra
 * `Binder.getCallingUid()` (puesto por el kernel, no falsificable) antes de confiar en él — no
 * hace falta un parámetro `callerUid` separado, por eso no está acá.
 */
interface IKairosPrivilegedService {

    /** Identidad real con la que corre este servidor — 2000 (shell) o 0 (root). El cliente la usa
     *  para saber qué puede esperar de [newProcess] antes de intentar nada. */
    int getServerUid();

    /** true si [callerPackage] (verificado contra el uid real que llama, ver KDoc de la
     *  interfaz) ya fue autorizado por el usuario desde la UI de Kairos. No ejecuta nada — solo
     *  consulta el registro de consentimiento. */
    boolean isPackageAuthorized(String callerPackage);

    /**
     * Ejecuta [cmd] con la identidad de este servidor y devuelve los 3 file descriptors
     * (stdin, stdout, stderr) del proceso — el cliente lee/escribe directo sobre ellos, mismo
     * patrón real que `IRemoteProcess` de Shizuku (confirmado en su código fuente real,
     * `referencia/ver/Shizuku-API`). Lanza `SecurityException` si [callerPackage] no está
     * autorizado — el cliente debe llamar [isPackageAuthorized] primero, o manejar esa
     * excepción como "todavía no tengo permiso".
     */
    ParcelFileDescriptor[] newProcess(String callerPackage, in String[] cmd, in String[] env, String workDir);
}
