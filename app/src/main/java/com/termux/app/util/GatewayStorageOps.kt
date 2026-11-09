package com.termux.app.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Un volumen de almacenamiento REAL del dispositivo — interno, microSD, o USB-OTG cuando Android lo monta como
 * almacenamiento público (ver [AppStorageOps] para por qué "cuando" no es "siempre"). Pedido del usuario
 * (``): ver el almacenamiento interno completo y también microSD/USB, no solo el HOME de Termux
 * (eso ya lo cubre `fs.*`/[HomeFiles]).
 *
 * [path] es la ruta real en el sistema de archivos si Android la expone de forma pública — ver [AppStorageOps]
 * para las limitaciones reales por versión de Android. [accessible] es una comprobación real (el directorio
 * existe y se puede leer con los permisos que la app tiene AHORA), nunca una suposición de que el permiso
 * "Acceso a todos los archivos" alcanza para cualquier volumen físico.
 */
data class StorageVolumeInfo(
    val id: String,
    val description: String,
    val primary: Boolean,
    val removable: Boolean,
    val state: String,
    val path: String?,
    val accessible: Boolean,
)

/** `storage.volumes` / `storage.list_dir` — ver [AppStorageOps] para la implementación real. */
interface GatewayStorageOps {
    fun volumes(): List<StorageVolumeInfo>

    /** Lista una carpeta DENTRO de un volumen ya reportado por [volumes] — nunca una ruta arbitraria del
     *  sistema de archivos. [relative] es relativa a la raíz del volumen (igual que [HomeFiles.list]). */
    fun listDir(volumeId: String, relative: String): Result<List<FileEntry>>
}

/**
 * Implementación real sobre [StorageManager] (API 24+, muy por debajo del minSdk=26 real de Kairos).
 *
 * **Limitación real investigada, no inventada**: la ruta de archivos de un volumen SECUNDARIO (microSD, USB-OTG
 * montado como almacenamiento) solo tiene una vía pública (`StorageVolume.getDirectory()`) desde API 30 — Android
 * la escondió deliberadamente al introducir scoped storage (API 29) y volvió a abrirla en API 30 solo para apps
 * con `MANAGE_EXTERNAL_STORAGE` (ver `PermissionManager.hasStorage()`, ya requerido para esto). En API 26-29 no
 * existe una forma pública (sin reflection sobre `getPath()`, que es `@hide`/no garantizado) de leer esa ruta —
 * el volumen aparece en [volumes] igual (nombre, estado), pero con `path=null` y `accessible=false`, en vez de
 * fingir una ruta que podría no ser correcta. El volumen PRIMARIO (almacenamiento interno) no depende de esto:
 * `Environment.getExternalStorageDirectory()` es pública desde siempre.
 *
 * Lectura de archivos dentro de un volumen ya reportado se resuelve reusando [HomeFiles] (mismo mecanismo de
 * `resolve()` contra path traversal y nombres de secretos) apuntado a la raíz de ESE volumen — nunca a rutas
 * fuera de él.
 */
class AppStorageOps(private val context: Context) : GatewayStorageOps {

    override fun volumes(): List<StorageVolumeInfo> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        return try {
            manager.storageVolumes.map(::toInfo)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun listDir(volumeId: String, relative: String): Result<List<FileEntry>> {
        val volume = volumes().firstOrNull { it.id == volumeId }
            ?: return Result.failure(IllegalArgumentException("volumen no encontrado"))
        val path = volume.path
        if (!volume.accessible || path == null) return Result.failure(IllegalArgumentException("volumen no accesible"))
        return HomeFiles(File(path)).list(relative)
    }

    private fun toInfo(volume: StorageVolume): StorageVolumeInfo {
        val dir = directoryOf(volume)
        val accessible = PermissionManager.hasStorage() && dir != null && dir.isDirectory && dir.canRead()
        val description = try { volume.getDescription(context) } catch (_: Exception) { null }
            ?: if (volume.isPrimary) "Almacenamiento interno" else "Almacenamiento extraíble"
        return StorageVolumeInfo(
            id = idOf(volume, dir),
            description = description,
            primary = volume.isPrimary,
            removable = volume.isRemovable,
            state = volume.state ?: "desconocido",
            path = dir?.path,
            accessible = accessible,
        )
    }

    private fun directoryOf(volume: StorageVolume): File? {
        if (volume.isPrimary) return Environment.getExternalStorageDirectory()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try { volume.directory } catch (_: Exception) { null }
    }

    private fun idOf(volume: StorageVolume, dir: File?): String {
        if (volume.isPrimary) return "primary"
        dir?.name?.takeIf { it.isNotBlank() }?.let { return it }
        (try { volume.uuid } catch (_: Exception) { null })?.let { return it }
        return "vol_${System.identityHashCode(volume)}"
    }
}
