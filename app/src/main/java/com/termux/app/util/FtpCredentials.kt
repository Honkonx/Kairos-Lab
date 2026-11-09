package com.termux.app.util

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Usuario/contraseña del servidor FTP embebido ([FtpServer]). A diferencia de un secreto interno del propio
 * gateway ([GatewayFiles] — admin/app, que nadie fuera del proceso necesita conocer), esta contraseña SÍ la tiene
 * que escribir la persona en un cliente FTP externo (FileZilla, el explorador de archivos de otro teléfono/PC) —
 * por eso la elige ella misma al crearla/reemplazarla, en vez de que Kairos la genere. Lo que nunca cambia (ver
 * `.claude/rules/kairos-secrets-never-revealed.md`) es que, una vez guardada, no se vuelve a MOSTRAR: solo se
 * guarda su hash (SHA-256 con sal propia), nunca el valor en texto plano — ni siquiera en disco. "Reemplazar"
 * siempre arranca con los campos vacíos (ver `FtpFragment`/panel), nunca precargado con el valor anterior, que de
 * todas formas no se podría recuperar porque no se guarda.
 */
class FtpCredentials(private val dir: File) {
    init {
        dir.mkdirs()
        restrict(dir)
    }

    private val userFile = File(dir, "ftp_user")
    private val saltFile = File(dir, "ftp_pass_salt")
    private val hashFile = File(dir, "ftp_pass_hash")

    val configured: Boolean get() = userFile.isFile && saltFile.isFile && hashFile.isFile

    /** El nombre de usuario SÍ se puede mostrar (no es un secreto, solo un identificador) — a diferencia de la
     *  contraseña, que nunca tiene un getter. */
    val username: String?
        get() = userFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Valida y guarda credenciales nuevas, reemplazando las anteriores si existían. */
    fun set(username: String, password: String): Boolean {
        if (!USERNAME_PATTERN.matches(username)) return false
        if (password.length < MIN_PASSWORD_LEN || password.length > MAX_PASSWORD_LEN) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        userFile.writeText(username); restrict(userFile)
        saltFile.writeBytes(salt); restrict(saltFile)
        hashFile.writeText(hash(password, salt)); restrict(hashFile)
        return true
    }

    /** Compara sin revelar nunca el valor guardado — [MessageDigest.isEqual] para no filtrar el resultado por
     *  tiempo de comparación (mismo cuidado que [GatewayBridgeCore.handle] con el secreto del puente). */
    fun matches(username: String, password: String): Boolean {
        val expectedUser = this.username ?: return false
        if (username != expectedUser) return false
        val salt = saltFile.takeIf { it.isFile }?.readBytes() ?: return false
        val expectedHash = hashFile.takeIf { it.isFile }?.readText()?.trim() ?: return false
        return MessageDigest.isEqual(expectedHash.toByteArray(Charsets.UTF_8), hash(password, salt).toByteArray(Charsets.UTF_8))
    }

    fun clear() {
        userFile.delete(); saltFile.delete(); hashFile.delete()
    }

    /**
     * Revisión de seguridad real: un SHA-256 directo (aunque con sal) se calcula en microsegundos — un atacante
     * que consiga `ftp_pass_hash`/`ftp_pass_salt` (p. ej. si el dispositivo tiene root, o un backup mal protegido)
     * podría probar miles de millones de contraseñas por segundo. PBKDF2WithHmacSHA256 con 210 000 iteraciones
     * (recomendación OWASP 2023) hace cada intento miles de veces más lento, sin agregar ninguna librería nueva
     * (`javax.crypto` es parte del JDK/Android estándar).
     */
    private fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return key.encoded.joinToString("") { "%02x".format(it) }
    }

    private fun restrict(f: File) {
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true)
        if (f.isDirectory) f.setExecutable(true, true)
    }

    companion object {
        val USERNAME_PATTERN = Regex("[a-zA-Z0-9_.-]{1,32}")
        const val MIN_PASSWORD_LEN = 10
        const val MAX_PASSWORD_LEN = 128
        private const val SALT_BYTES = 16
        private const val PBKDF2_ITERATIONS = 210_000
        private const val PBKDF2_KEY_LENGTH_BITS = 256
    }
}
