package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Perfiles de recursos" reusables para QEMU (RAM + arquitectura + resolución guardados bajo un
 * nombre, para no re-tipear lo mismo cada vez que se arranca una VM distinta) — hallazgo real de
 * `docs/arquitectura/INVESTIGACION_MODULOS_NUEVOS_Y_PENDIENTES_REFERENCIA_2026-11-05.md`: "Perfiles
 * de recursos reusables ('Planes' de froxlor / límites de Coolify) -- aplicar primero a QEMU
 * (único mecanismo de Kairos con límites de kernel reales hoy)".
 *
 * Mismo patrón de registry que [AutomationManager]/[HomelabManager] (una clave con un array JSON
 * compacto en `~/.android_server_registry`, protegido por [RegistryLock]) — nada sensible acá
 * (ningún secreto), así que no hace falta el cifrado de [AutomationSecrets].
 */
object QemuProfiles {

    data class Profile(
        val name: String,
        val ramMb: Int,
        val guestArch: String,
        val resolution: String
    )

    private val HOME = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val REGISTRY_LOCK_FILE get() = ManagerNativeUtils.registryLockFile
    private fun registryFile() = File(HOME, ".android_server_registry")
    private const val PROFILES_KEY = "qemu.resource_profiles"

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

    private fun serialize(entries: List<Profile>): String {
        val arr = JSONArray()
        entries.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name); put("ramMb", p.ramMb); put("guestArch", p.guestArch); put("resolution", p.resolution)
            })
        }
        return arr.toString()
    }

    // Hallazgo real de revisión automática ("command_injection"): guestArch/resolution terminan
    // interpolados tal cual dentro de un string de shell en QemuFragment.bootVmConsole()/
    // bootVmVnc() ("'$vmGuestArch'" entre comillas simples) -- antes solo podían venir de chips
    // fijos de la UI (valores hardcodeados seguros), pero un perfil guardado los lee de un JSON
    // en el registry sin pasar por esos chips. Validar ACÁ (al parsear, no solo al aplicar) para
    // que un perfil corrupto/manipulado nunca llegue a existir como [Profile] válido en primer
    // lugar -- un valor fuera de la allowlist descarta el perfil ENTERO en vez de sanearlo en
    // silencio (más seguro: un perfil roto se nota como "desapareció", no como "se aplicó mal").
    private val VALID_ARCH = setOf("aarch64", "x86_64")
    private val VALID_RESOLUTION = Regex("^$|^\\d{2,5}x\\d{2,5}$")
    private const val MIN_RAM_MB = 64
    private const val MAX_RAM_MB = 16384

    private fun parse(raw: String?): List<Profile> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                val ramMb = o.optInt("ramMb", 512).takeIf { it in MIN_RAM_MB..MAX_RAM_MB } ?: return@mapNotNull null
                val guestArch = o.optString("guestArch", "aarch64").takeIf { it in VALID_ARCH } ?: return@mapNotNull null
                val resolution = o.optString("resolution", "").takeIf { VALID_RESOLUTION.matches(it) } ?: return@mapNotNull null
                Profile(name, ramMb, guestArch, resolution)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun listProfiles(): List<Profile> = parse(registryValues()[PROFILES_KEY]).sortedBy { it.name.lowercase() }

    /** Guarda o reemplaza (mismo nombre = sobreescribe, sin duplicar) un perfil. Devuelve
     *  false (no guarda nada) si [profile] no pasa la misma allowlist que [parse] -- defensa en
     *  ambos extremos, no solo al leer. El caller real (QemuFragment) siempre arma el profile
     *  desde los chips ya validados de la UI, así que esto no debería fallar nunca en la
     *  práctica, pero cierra el control por completo en vez de confiar solo en el lado lectura. */
    fun saveProfile(profile: Profile): Boolean {
        if (profile.ramMb !in MIN_RAM_MB..MAX_RAM_MB) return false
        if (profile.guestArch !in VALID_ARCH) return false
        if (!VALID_RESOLUTION.matches(profile.resolution)) return false
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[PROFILES_KEY]).filterNot { it.name == profile.name }
                current[PROFILES_KEY] = serialize(list + profile)
            }
        }
        return true
    }

    fun deleteProfile(name: String) {
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                current[PROFILES_KEY] = serialize(parse(current[PROFILES_KEY]).filterNot { it.name == name })
            }
        }
    }
}
