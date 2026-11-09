package com.termux.app.util

import android.content.Context
import com.termux.app.ui.studio.project.SessionStateManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Primer paso real de paridad del gateway con el IDE nativo de Kairos — **Estudio**
 * (`app/src/main/java/com/termux/app/ui/studio/`), nunca el módulo de terminal "ide" (nvim +
 * NvChad). Confirmado por auditoría (``): el gateway tenía 111 acciones y CERO
 * de IDE, pese a que Estudio ya tiene `GitBridge`/`FileTreeLoader`/`ProjectSearchEngine`/
 * `CompilerOutputParser` funcionando — el gap real era solo falta de wiring, no falta de
 * funcionalidad en Estudio.
 *
 * **Alcance real de esta ronda**: solo lectura de metadatos (qué proyecto(s) tiene Estudio
 * abiertos/recordados), vía [SessionStateManager] — el mismo mecanismo que ya usa `StudioFragment`
 * para restaurar sesiones al reabrir la app. Deliberadamente NO incluye `ide.tree`/`ide.read`/
 * `ide.write` todavía: Estudio trabaja sobre `DocumentFile`/URIs SAF (Storage Access Framework),
 * no siempre resuelve a una ruta de filesystem real (`StudioSession.projectPath` puede ser `null`
 * si el proyecto vive fuera de almacenamiento primario) — leer/escribir contenido de archivo
 * necesita decidir si se hace vía esa ruta real (cuando existe) o vía el URI SAF con
 * `ContentResolver` (más lento, pero siempre funciona), y confirmar en un dispositivo real que el
 * proceso del gateway (mismo UID que la app, pero proceso hijo separado) puede abrir esos URIs —
 * ninguna de las dos cosas se puede verificar sin dispositivo para el caso SAF puro, así que
 * `ide.write` (y leer/listar un proyecto SIN `projectPath` real) queda pendiente.
 *
 * **Ampliado la misma ronda (``)**: `ide.tree`/`ide.read` SÍ se agregan, pero
 * acotados al caso seguro — un proyecto cuyo `projectPath` ya resolvió a una ruta real de
 * filesystem (`StudioFragment.resolveRealPathFromTreeUri`, el caso común: proyecto en
 * almacenamiento interno). Reusan [HomeFiles] (mismo mecanismo con el que ya cuentan `fs.*`/
 * `storage.*` contra path-traversal y nombres de secretos vedados), apuntado a la raíz del
 * proyecto en vez de a `$HOME` — ningún código nuevo de acceso a archivos, solo un root distinto.
 * Si `projectPath` es `null` (proyecto fuera de almacenamiento primario, solo accesible por URI
 * SAF), devuelven un error explícito en vez de fallar en silencio o inventar un camino SAF sin
 * poder probarlo.
 *
 * **`ide.write` agregado (misma ronda, tras pedido explícito de continuar con Shizuku/gateway)**:
 * mismo acotamiento que `tree`/`read` (solo proyectos con `projectPath` real) y mismo mecanismo
 * (`HomeFiles.write`, límite de tamaño y protección de ruta ya existentes). Escribe directo al
 * archivo en disco — si Estudio tiene esa misma pestaña abierta con cambios sin guardar, el
 * editor no se refresca solo (no hay ningún mecanismo de notificación entre el gateway y un
 * Fragment de UI); es una limitación real, no un bug, documentada para no sorprender en el uso.
 */
interface GatewayIdeOps {
    fun projectInfo(): JSONObject
    fun tree(projectId: String, path: String): JSONObject
    fun read(projectId: String, path: String): JSONObject
    fun write(projectId: String, path: String, content: String): JSONObject
}

class AppIdeOps(private val context: Context) : GatewayIdeOps {
    override fun projectInfo(): JSONObject {
        val (sessions, activeIndex) = SessionStateManager(context).loadSessions()
        val projects = JSONArray(sessions.mapIndexed { index, session ->
            JSONObject()
                .put("id", session.id)
                .put("display_name", session.displayName)
                .put("tree_uri", session.treeUri.toString())
                .put("project_path", session.projectPath ?: JSONObject.NULL)
                .put("open_tabs", session.openTabs.size)
                .put("active", index == activeIndex)
        })
        return JSONObject().put("ok", true).put("projects", projects)
    }

    private fun realPathOf(projectId: String): Result<String> {
        val session = SessionStateManager(context).loadSessions().first.firstOrNull { it.id == projectId }
            ?: return Result.failure(IllegalArgumentException("proyecto no encontrado: $projectId"))
        return session.projectPath?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("este proyecto no tiene una ruta real de archivos (vive solo en un URI SAF) — todavía no soportado"))
    }

    override fun tree(projectId: String, path: String): JSONObject {
        val root = realPathOf(projectId).getOrElse { return JSONObject().put("ok", false).put("error", it.message) }
        return HomeFiles(File(root)).list(path).fold(
            onSuccess = { entries -> JSONObject().put("ok", true).put("entries", JSONArray(entries.map {
                JSONObject().put("name", it.name).put("dir", it.dir).put("size", it.size)
            })) },
            onFailure = { JSONObject().put("ok", false).put("error", it.message) },
        )
    }

    override fun read(projectId: String, path: String): JSONObject {
        val root = realPathOf(projectId).getOrElse { return JSONObject().put("ok", false).put("error", it.message) }
        return HomeFiles(File(root)).read(path).fold(
            onSuccess = { content -> JSONObject().put("ok", true).put("content", content) },
            onFailure = { JSONObject().put("ok", false).put("error", it.message) },
        )
    }

    override fun write(projectId: String, path: String, content: String): JSONObject {
        val root = realPathOf(projectId).getOrElse { return JSONObject().put("ok", false).put("error", it.message) }
        return HomeFiles(File(root)).write(path, content).fold(
            onSuccess = { JSONObject().put("ok", true) },
            onFailure = { JSONObject().put("ok", false).put("error", it.message) },
        )
    }
}
