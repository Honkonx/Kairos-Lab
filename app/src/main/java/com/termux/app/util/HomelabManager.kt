package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Persistencia + probing de "Homelab" — pantalla nueva (ver `docs/modulos/HOMELAB.md`) que
 * convierte a Kairos en un panel/dashboard real para servicios self-hosted que el usuario ya
 * tiene corriendo en su red local (Docker/Portainer de un NAS, Pi-hole de una Raspberry Pi,
 * Proxmox de un servidor, etc.) — DISTINTO de los módulos que Kairos ya gestiona (Ollama, n8n),
 * que corren DENTRO del propio teléfono. Alcance de esta primera versión (pedido explícito del
 * usuario): solo la pantalla nativa dentro de la app — sin API profunda por plataforma (Docker
 * Engine API, Pi-hole API, Proxmox API), solo un probe HTTP genérico para saber si el servicio
 * responde. Ver `docs/arquitectura/FUTURO.md` sección "Homelab multi-superficie" para la visión
 * diferida (web externa, APK externa, SSH como acceso remoto al panel).
 *
 * Mismo patrón de registry que [RemoteManager] (una clave con un array JSON compacto en
 * `~/.android_server_registry`, protegido por [RegistryLock]) y de secreto-nunca-legible que
 * `RemoteManager` usa para claves SSH importadas: un secreto guardado nunca se vuelve a mostrar
 * en la UI una vez persistido — el token/contraseña de un servicio se
 * guarda en un archivo propio con permisos restringidos a nivel de aplicación — ninguna función
 * de este objeto expone su contenido a la UI, solo un booleano `hasSecret`. [secretFor] existe
 * únicamente para que un futuro cliente HTTP autenticado (fuera del alcance de esta ronda) lo
 * use al armar el request — HomelabFragment.kt no debe llamarlo para mostrar nada en pantalla.
 */
object HomelabManager {

    enum class ServiceType { DOCKER, PIHOLE, PROXMOX, HTTP, OTHER }

    data class HomelabService(
        val id: String,
        val name: String,
        val type: ServiceType,
        val url: String,
        val username: String = "",
        val hasSecret: Boolean = false
    )

    data class ActionResult(val ok: Boolean, val message: String = "", val error: String = "")

    private val HOME = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val REGISTRY_LOCK_FILE get() = ManagerNativeUtils.registryLockFile
    private fun registryFile() = File(HOME, ".android_server_registry")
    private const val SERVICES_KEY = "homelab.services"
    private val SECRETS_DIR = File(HOME, ".homelab_secrets")

    // Mismo mecanismo de lectura/escritura línea a línea (clave=valor) que RemoteManager/
    // TunnelManager — un único archivo de registry compartido por todos los managers de Kairos,
    // cada uno dueño de su(s) propia(s) clave(s) (ver comentario de RegistryLock.kt).
    private fun registryValues(): Map<String, String> {
        val file = registryFile()
        if (!file.exists()) return emptyMap()
        return try {
            file.readLines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) null
                else {
                    val eq = t.indexOf('=')
                    if (eq > 0) t.substring(0, eq).trim() to t.substring(eq + 1).trim() else null
                }
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun writeRegistryFileLocked(mutate: (MutableMap<String, String>) -> Unit) {
        val file = registryFile()
        val current = registryValues().toMutableMap()
        mutate(current)
        file.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    private fun serialize(entries: List<HomelabService>): String {
        val arr = JSONArray()
        entries.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("type", s.type.name)
                put("url", s.url)
                put("username", s.username)
                put("hasSecret", s.hasSecret)
            })
        }
        return arr.toString()
    }

    private fun parse(raw: String?): List<HomelabService> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HomelabService(
                    id = o.getString("id"),
                    name = o.optString("name").ifBlank { o.optString("url") },
                    type = try { ServiceType.valueOf(o.optString("type", "OTHER")) } catch (_: Exception) { ServiceType.OTHER },
                    url = o.getString("url"),
                    username = o.optString("username", ""),
                    hasSecret = o.optBoolean("hasSecret", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Servicios agregados por el usuario, ordenados por nombre para una lista estable. */
    fun listServices(): List<HomelabService> =
        parse(registryValues()[SERVICES_KEY]).sortedBy { it.name.lowercase() }

    /**
     * Antepone `http://` si el usuario no escribió un esquema — pedido explícito de "facilidad
     * de uso real" (el usuario típicamente escribe `192.168.1.50:9000`, no
     * `http://192.168.1.50:9000`).
     */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }

    fun addService(name: String, type: ServiceType, urlRaw: String, username: String, secret: String?): ActionResult {
        val url = normalizeUrl(urlRaw)
        if (url == "http://" || urlRaw.isBlank()) return ActionResult(false, error = "Falta la URL/IP del servicio")
        val effectiveName = name.ifBlank { url.removePrefix("http://").removePrefix("https://") }
        val id = "svc_" + System.currentTimeMillis()
        val hasSecret = !secret.isNullOrBlank()
        if (hasSecret) saveSecret(id, secret!!)
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[SERVICES_KEY])
                current[SERVICES_KEY] = serialize(
                    list + HomelabService(id, effectiveName, type, url, username, hasSecret)
                )
            }
        }
        return ActionResult(true, message = "Servicio agregado")
    }

    /** Edita nombre/URL/usuario de un servicio ya guardado — nunca toca el secreto (ver
     *  [replaceSecret]/[clearSecret] para eso, siempre por separado). */
    fun updateService(id: String, name: String, type: ServiceType, urlRaw: String, username: String): ActionResult {
        val url = normalizeUrl(urlRaw)
        if (urlRaw.isBlank()) return ActionResult(false, error = "Falta la URL/IP del servicio")
        var found = false
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[SERVICES_KEY])
                val updated = list.map {
                    if (it.id == id) {
                        found = true
                        it.copy(name = name.ifBlank { url }, type = type, url = url, username = username)
                    } else it
                }
                current[SERVICES_KEY] = serialize(updated)
            }
        }
        return if (found) ActionResult(true, message = "Servicio actualizado") else ActionResult(false, error = "Servicio no encontrado")
    }

    fun deleteService(id: String): ActionResult {
        try { secretFile(id).delete() } catch (_: Exception) {}
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[SERVICES_KEY])
                current[SERVICES_KEY] = serialize(list.filterNot { it.id == id })
            }
        }
        return ActionResult(true, message = "Servicio eliminado")
    }

    /** Reemplaza el token/contraseña de un servicio ya guardado — campo de reemplazo SIEMPRE
     *  arranca vacío en la UI, nunca pre-cargado con el valor anterior. */
    fun replaceSecret(id: String, secret: String): ActionResult {
        if (secret.isBlank()) return ActionResult(false, error = "El token/contraseña no puede estar vacío")
        saveSecret(id, secret)
        setHasSecretFlag(id, true)
        return ActionResult(true, message = "Token actualizado")
    }

    fun clearSecret(id: String): ActionResult {
        try { secretFile(id).delete() } catch (_: Exception) {}
        setHasSecretFlag(id, false)
        return ActionResult(true, message = "Token eliminado")
    }

    private fun setHasSecretFlag(id: String, hasSecret: Boolean) {
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[SERVICES_KEY])
                current[SERVICES_KEY] = serialize(
                    list.map { if (it.id == id) it.copy(hasSecret = hasSecret) else it }
                )
            }
        }
    }

    private fun secretFile(id: String) = File(SECRETS_DIR, id)

    // Permisos a nivel de Java (sin ProcessBuilder/chmod — esta pantalla es 100% Kotlin, no
    // depende de un shell): setReadable/setWritable(false, false) primero quita el permiso a
    // "todos", después (true, true) lo vuelve a dar solo al dueño — mismo resultado que
    // `chmod 600` sin invocar un proceso externo. Directorio propio (no ~/.ssh) porque un
    // token de servicio HTTP no tiene relación con las claves SSH de RemoteManager.
    private fun saveSecret(id: String, secret: String) {
        SECRETS_DIR.mkdirs()
        SECRETS_DIR.setReadable(false, false)
        SECRETS_DIR.setReadable(true, true)
        SECRETS_DIR.setExecutable(false, false)
        SECRETS_DIR.setExecutable(true, true)
        val file = secretFile(id)
        file.writeText(secret.trim())
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    /** Contenido real del secreto — SOLO para que un cliente HTTP interno lo use al armar un
     *  request autenticado. Ninguna pantalla de la app debe llamar esto para mostrar nada. */
    fun secretFor(id: String): String? =
        secretFile(id).takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    /**
     * Probe barato de disponibilidad — HEAD HTTP con timeout corto (no valida credenciales, ni
     * intenta ninguna integración profunda por API de cada plataforma, mismo alcance "MVP" que
     * [RemoteManager.probeClientReachable] usa para las conexiones SSH guardadas). Cualquier
     * código de respuesta HTTP (incluido 401/403 "requiere auth", o 405 si el servidor rechaza
     * HEAD) cuenta como "responde" — lo único que importa acá es si hay algo escuchando.
     */
    fun probe(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.instanceFollowRedirects = true
            conn.responseCode in 100..599
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
