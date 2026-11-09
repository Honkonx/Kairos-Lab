package com.termux.app.util

import android.app.AlertDialog
import androidx.fragment.app.Fragment
import java.io.File

/**
 * Diálogo de 2 opciones para elegir dónde abrir una TUI/comando de un CLI — "Carpeta por
 * defecto (~)" o "Elegir carpeta…" (mockup aprobado por el usuario 2026-08-26, ver
 * docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md). "Elegir carpeta…" reusa el mismo
 * navegador de carpetas de [pickStorageFolder] (el que ya usa Symlink/Copiar más abajo en
 * este archivo) — arranca desde Termux home o desde almacenamiento interno, y NO crea ningún
 * symlink/copia: solo devuelve la ruta absoluta elegida vía [onChooseFolder], el caller decide
 * qué hacer con ella (típicamente "cd '<ruta>' && <comando>").
 */
fun Fragment.promptOpenLocation(onDefault: () -> Unit, onChooseFolder: (String) -> Unit) {
    AlertDialog.Builder(requireContext())
        .setTitle("¿Dónde abrir?")
        .setItems(arrayOf("Carpeta por defecto (~)", "Elegir carpeta…")) { _, which ->
            when (which) {
                0 -> onDefault()
                1 -> pickOpenLocationSource(onChooseFolder)
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

private fun Fragment.pickOpenLocationSource(onChooseFolder: (String) -> Unit) {
    val options = arrayOf("Desde Termux (~)", "Desde almacenamiento interno")
    AlertDialog.Builder(requireContext())
        .setTitle("Elegir carpeta — origen")
        .setItems(options) { _, which ->
            val start = if (which == 0) ManagerNativeUtils.home else ProjectsManager.EXTERNAL_STORAGE_ROOT
            pickStorageFolder({}, start, "Elegir carpeta") { path -> onChooseFolder(path) }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

/**
 * Acciones de gestión de proyectos para CLIs, compartidas por TODOS los fragments de
 * módulos — evita duplicar el diálogo de ~120 líneas que ClaudeFragment/CodexFragment/
 * OpenCodeFragment/AntigravityFragment/OpenClawFragment repiten (ver CodexFragment.kt:136).
 *
 * El menú trabaja sobre la carpeta compartida `~/proyectos` (ProjectsManager.PROJECTS_DIR),
 * la misma que usa kairos_manager.py y los CLIs con fragment dedicado. Por eso un proyecto
 * symlinkeado desde un CLI se ve en los demás: el registry de origen (`project_origin_*`)
 * es común.
 *
 * Uso en un fragment cualquiera:
 *   showProjectsMenu(onToast = { toast(it) }, onLaunchInProject = { path -> launchTerminalCommand("cd '$path' && cli") })
 *
 * [baseDir]/[regPrefix] (2026-08-13): por defecto operan sobre la carpeta compartida
 * `~/proyectos` (ProjectsManager.PROJECTS_DIR/PROJECT_ORIGIN_PREFIX) — OpenClawFragment los
 * pasa explícitos porque OpenClaw usa su propio workspace ($HOME/.openclaw/workspace, prefijo
 * de registry "openclaw_workspace") en vez de la carpeta compartida.
 *
 * Semántica semi-universal (ver AGENTS.md): cualquier módulo CLI nuevo del catálogo que caiga
 * en GenericModuleFragment recibe este menú sin escribir una sola línea extra.
 *
 * Symlink vs Copiar (2026-08-14, pedido explícito del usuario — ver docs/humano/): son DOS
 * caminos distintos y ahora ambos navegan CUALQUIER carpeta del almacenamiento (no solo
 * Download), no solo Download en sí:
 *  - Symlink = puntero (Os.symlink real, sin copiar bytes) — el contenido sigue viviendo en
 *    su ubicación original; además de Download/almacenamiento, ahora también se puede
 *    symlinkear un proyecto que YA está en `~/proyectos` hacia el baseDir actual (útil para
 *    reusar un proyecto ya importado/symlinkeado en un CLI con workspace propio, ej. OpenClaw).
 *  - Copiar = duplica bytes reales (File.copyRecursively) desde cualquier carpeta del
 *    almacenamiento hacia `baseDir` — a diferencia del symlink, admite "Traer cambios del
 *    original" (pull manual bajo demanda, ProjectsManager.projectsSyncFromOrigin) para
 *    refrescar la copia con lo último del original. "Enviar cambios al original (todos)"
 *    (ProjectsManager.projectsSyncAll) ya existía y hace el camino inverso (copia→original,
 *    útil para empujar ediciones hechas dentro de Termux hacia afuera) — se conserva tal
 *    cual, ambas direcciones conviven bajo botones separados.
 */
fun Fragment.showProjectsMenu(
    onToast: (String) -> Unit,
    onLaunchInProject: ((String) -> Unit)? = null,
    baseDir: File = ProjectsManager.PROJECTS_DIR,
    regPrefix: String = ProjectsManager.PROJECT_ORIGIN_PREFIX
) {
    val options = if (onLaunchInProject != null) {
        arrayOf(
            "📁 Abrir en proyecto",
            "🔗 Symlink (acceso directo)",
            "📋 Copiar carpeta a Termux",
            "⬇ Traer cambios del original",
            "🗑 Eliminar proyecto",
            "⬆ Enviar cambios al original (todos)",
            "❓ ¿Symlink o copiar?"
        )
    } else {
        arrayOf(
            "🔗 Symlink (acceso directo)",
            "📋 Copiar carpeta a Termux",
            "⬇ Traer cambios del original",
            "🗑 Eliminar proyecto",
            "⬆ Enviar cambios al original (todos)",
            "❓ ¿Symlink o copiar?"
        )
    }
    AlertDialog.Builder(requireContext())
        .setTitle("Gestionar proyectos")
        .setItems(options) { _, which ->
            val base = if (onLaunchInProject != null) 1 else 0
            when (which) {
                0 -> if (onLaunchInProject != null) pickProjectToOpen(onToast, onLaunchInProject, baseDir, regPrefix) else pickSymlinkSource(onToast, baseDir)
                base -> pickSymlinkSource(onToast, baseDir)
                base + 1 -> pickImportSource(onToast, baseDir, regPrefix)
                base + 2 -> pickProjectToSyncFromOrigin(onToast, baseDir, regPrefix)
                base + 3 -> pickProjectToDelete(onToast, baseDir, regPrefix)
                base + 4 -> syncAllProjects(onToast, baseDir, regPrefix)
                base + 5 -> showSymlinkVsCopyHelp()
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

private fun Fragment.pickProjectToOpen(onToast: (String) -> Unit, onLaunchInProject: (String) -> Unit, baseDir: File, regPrefix: String) {
    runProjectsAction({ ProjectsManager.projectsList(baseDir, regPrefix) }) { json ->
        if (!json.optBoolean("ok", false)) {
            onToast("Error: ${json.optString("error", "desconocido")}")
            return@runProjectsAction
        }
        val projects = json.optJSONArray("projects")
        if (projects == null || projects.length() == 0) {
            onToast("No hay proyectos en ~/proyectos — usa \"Gestionar proyectos\" para agregar uno")
            return@runProjectsAction
        }
        val names = (0 until projects.length()).map { projects.getJSONObject(it).optString("name") }
        val paths = (0 until projects.length()).map { projects.getJSONObject(it).optString("path") }
        AlertDialog.Builder(requireContext())
            .setTitle("Abrir proyecto")
            .setItems(names.toTypedArray()) { _, which ->
                onLaunchInProject(paths[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

// ════════════════════════════════════════════════════════════
//  Symlink — elegir origen (Download / almacenamiento / proyecto ya en ~/proyectos)
// ════════════════════════════════════════════════════════════

private fun Fragment.pickSymlinkSource(onToast: (String) -> Unit, baseDir: File) {
    val options = arrayOf("Desde Descargas", "Desde almacenamiento interno", "Desde un proyecto de ~/proyectos")
    AlertDialog.Builder(requireContext())
        .setTitle("Symlink — elegí el origen")
        .setItems(options) { _, which ->
            when (which) {
                0 -> pickStorageFolder(onToast, ProjectsManager.DOWNLOAD_DIR_PATH, "Symlink desde Descargas") { path ->
                    createSymlinkFrom(onToast, path, baseDir)
                }
                1 -> pickStorageFolder(onToast, ProjectsManager.EXTERNAL_STORAGE_ROOT, "Symlink desde almacenamiento") { path ->
                    createSymlinkFrom(onToast, path, baseDir)
                }
                2 -> pickHomeProjectToSymlink(onToast, baseDir)
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

private fun Fragment.createSymlinkFrom(onToast: (String) -> Unit, source: String, baseDir: File) {
    runProjectsAction({ ProjectsManager.projectsCreateSymlink(source, baseDir) }) { res ->
        onToast(
            if (res.optBoolean("ok", false)) res.optString("message", "Symlink creado")
            else "Error: ${res.optString("error", "desconocido")}"
        )
    }
}

// Symlink desde un proyecto que YA está en la carpeta compartida ~/proyectos hacia el
// baseDir actual — pedido explícito 2026-08-14: antes solo se podía symlinkear desde
// Download, no reusar un proyecto ya traído a home. Principal caso de uso real: un CLI con
// workspace propio (ej. OpenClaw, baseDir != PROJECTS_DIR) que quiere apuntar a un proyecto
// que ya vive en ~/proyectos sin volver a importarlo/symlinkearlo desde cero.
private fun Fragment.pickHomeProjectToSymlink(onToast: (String) -> Unit, baseDir: File) {
    if (baseDir.canonicalFileOrSelf() == ProjectsManager.PROJECTS_DIR.canonicalFileOrSelf()) {
        onToast("Ya estás en ~/proyectos — elegí \"Desde Descargas\" o \"Desde almacenamiento interno\"")
        return
    }
    runProjectsAction({ ProjectsManager.projectsList(ProjectsManager.PROJECTS_DIR, ProjectsManager.PROJECT_ORIGIN_PREFIX) }) { json ->
        if (!json.optBoolean("ok", false)) {
            onToast("Error: ${json.optString("error", "desconocido")}")
            return@runProjectsAction
        }
        val projects = json.optJSONArray("projects")
        if (projects == null || projects.length() == 0) {
            onToast("No hay proyectos en ~/proyectos todavía")
            return@runProjectsAction
        }
        val names = (0 until projects.length()).map { projects.getJSONObject(it).optString("name") }
        val realPaths = (0 until projects.length()).map { projects.getJSONObject(it).optString("real_path") }
        AlertDialog.Builder(requireContext())
            .setTitle("Symlink desde ~/proyectos")
            .setItems(names.toTypedArray()) { _, which ->
                createSymlinkFrom(onToast, realPaths[which], baseDir)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

private fun File.canonicalFileOrSelf(): File = try { canonicalFile } catch (_: Exception) { absoluteFile }

// ════════════════════════════════════════════════════════════
//  Navegador de carpetas del almacenamiento — reutilizado por symlink y copiar. Lista
//  AlertDialog en vez de una vista custom (mismo estilo que el resto del archivo);
//  MANAGE_EXTERNAL_STORAGE ya está concedido (ver FileManagerFragment.kt), así que
//  ProjectsManager.projectsStorageDirs(path) puede listar cualquier carpeta, no solo Download.
// ════════════════════════════════════════════════════════════

// Bug real (confirmado con captura de dispositivo 2026-08-16): AlertDialog.Builder NO
// soporta setMessage() + setItems() al mismo tiempo — el mensaje se renderiza pero la
// lista de ítems ("✓ Usar esta carpeta", ".. (subir)", subcarpetas) nunca aparece, dejando
// solo el título + la ruta como texto plano + el botón "Cancelar" (exactamente lo que se
// veía en la captura). pickStorageFolder() es compartida por Symlink (pickSymlinkSource) y
// Copiar (pickImportSource), así que este único bug rompía ambos flujos a la vez. Fix: la
// ruta actual pasa al título (segunda línea) en vez de setMessage(), dejando setItems()
// como el único content view del diálogo.
private fun Fragment.pickStorageFolder(
    onToast: (String) -> Unit,
    path: String,
    title: String,
    onPicked: (String) -> Unit
) {
    runProjectsAction({ ProjectsManager.projectsStorageDirs(path) }) { json ->
        if (!json.optBoolean("ok", false)) {
            onToast("Error: ${json.optString("error", "desconocido")}")
            return@runProjectsAction
        }
        val dirs = json.optJSONArray("dirs")
        val count = dirs?.length() ?: 0
        val names = (0 until count).map { dirs!!.getJSONObject(it).optString("name") }
        val paths = (0 until count).map { dirs!!.getJSONObject(it).optString("path") }
        val canGoUp = path != ProjectsManager.EXTERNAL_STORAGE_ROOT && File(path).parent != null
        val items = mutableListOf("✓ Usar esta carpeta")
        if (canGoUp) items.add(".. (subir)")
        items.addAll(names.map { "📁 $it" })
        AlertDialog.Builder(requireContext())
            .setTitle("$title\n$path")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> onPicked(path)
                    canGoUp && which == 1 -> pickStorageFolder(onToast, File(path).parent ?: path, title, onPicked)
                    else -> {
                        val idx = which - (if (canGoUp) 2 else 1)
                        pickStorageFolder(onToast, paths[idx], title, onPicked)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

// ════════════════════════════════════════════════════════════
//  Copiar — elegir origen (Download / almacenamiento), duplica bytes reales
// ════════════════════════════════════════════════════════════

private fun Fragment.pickImportSource(onToast: (String) -> Unit, baseDir: File, regPrefix: String) {
    val options = arrayOf("Desde Descargas", "Desde almacenamiento interno")
    AlertDialog.Builder(requireContext())
        .setTitle("Copiar carpeta — elegí el origen")
        .setItems(options) { _, which ->
            val start = if (which == 0) ProjectsManager.DOWNLOAD_DIR_PATH else ProjectsManager.EXTERNAL_STORAGE_ROOT
            pickStorageFolder(onToast, start, "Copiar carpeta a Termux") { path ->
                runProjectsAction({ ProjectsManager.projectsImport(path, baseDir, regPrefix) }) { res ->
                    onToast(
                        if (res.optBoolean("ok", false)) res.optString("message", "Importado")
                        else "Error: ${res.optString("error", "desconocido")}"
                    )
                }
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

// ════════════════════════════════════════════════════════════
//  Sincronizar copia ← original (pull manual bajo demanda, un proyecto a la vez)
// ════════════════════════════════════════════════════════════

private fun Fragment.pickProjectToSyncFromOrigin(onToast: (String) -> Unit, baseDir: File, regPrefix: String) {
    runProjectsAction({ ProjectsManager.projectsList(baseDir, regPrefix) }) { json ->
        if (!json.optBoolean("ok", false)) {
            onToast("Error: ${json.optString("error", "desconocido")}")
            return@runProjectsAction
        }
        val projects = json.optJSONArray("projects")
        val count = projects?.length() ?: 0
        val copies = (0 until count).map { projects!!.getJSONObject(it) }.filter { it.optString("origin").isNotBlank() }
        if (copies.isEmpty()) {
            onToast("No hay copias vinculadas a un original — usa \"Copiar carpeta a Termux\" primero")
            return@runProjectsAction
        }
        val names = copies.map { it.optString("name") }
        AlertDialog.Builder(requireContext())
            .setTitle("Traer cambios del original")
            .setItems(names.toTypedArray()) { _, which ->
                runProjectsAction({ ProjectsManager.projectsSyncFromOrigin(names[which], baseDir, regPrefix) }) { res ->
                    onToast(
                        if (res.optBoolean("ok", false)) res.optString("message", "Sincronizado")
                        else "Error: ${res.optString("error", "desconocido")}"
                    )
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

private fun Fragment.pickProjectToDelete(onToast: (String) -> Unit, baseDir: File, regPrefix: String) {
    runProjectsAction({ ProjectsManager.projectsList(baseDir, regPrefix) }) { json ->
        if (!json.optBoolean("ok", false)) {
            onToast("Error: ${json.optString("error", "desconocido")}")
            return@runProjectsAction
        }
        val projects = json.optJSONArray("projects")
        if (projects == null || projects.length() == 0) {
            onToast("No hay proyectos para eliminar")
            return@runProjectsAction
        }
        val names = (0 until projects.length()).map { projects.getJSONObject(it).optString("name") }
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar proyecto")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                AlertDialog.Builder(requireContext())
                    .setMessage("¿Eliminar \"$name\"? Esto borra el symlink o la carpeta importada.")
                    .setPositiveButton("Eliminar") { _, _ ->
                        runProjectsAction({ ProjectsManager.projectsDelete(name, baseDir, regPrefix) }) { res ->
                            onToast(
                                if (res.optBoolean("ok", false)) res.optString("message", "Eliminado")
                                else "Error: ${res.optString("error", "desconocido")}"
                            )
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

private fun Fragment.syncAllProjects(onToast: (String) -> Unit, baseDir: File, regPrefix: String) {
    onToast("Sincronizando proyectos…")
    runProjectsAction({ ProjectsManager.projectsSyncAll(baseDir, regPrefix) }) { json ->
        if (json.optBoolean("ok", false)) {
            onToast("${json.optInt("synced", 0)} sincronizados, ${json.optInt("failed", 0)} fallidos")
        } else {
            onToast("Error: ${json.optString("error", "desconocido")}")
        }
    }
}

// Diálogo de ayuda en lenguaje simple (pedido explícito 2026-08-14) — el usuario no es
// programador, así que evita jerga técnica ("puntero", "bytes") salvo la palabra clave
// mínima necesaria para diferenciar las dos opciones.
private fun Fragment.showSymlinkVsCopyHelp() {
    AlertDialog.Builder(requireContext())
        .setTitle("¿Symlink o copiar?")
        .setMessage(
            "🔗 Symlink (acceso directo)\n" +
                "La carpeta se queda donde ya estaba (por ejemplo en Descargas). Kairos solo crea un acceso directo hacia ella — no ocupa espacio extra y cualquier cambio se ve al instante en los dos lados.\n\n" +
                "📋 Copiar\n" +
                "Kairos duplica todos los archivos dentro de Termux. Ocupa espacio extra y los cambios NO se copian solos — tenés que tocar \"Traer cambios del original\" cada vez que quieras actualizar la copia.\n\n" +
                "Usá Symlink si querés trabajar siempre sobre el archivo original. Usá Copiar si preferís tener una versión aparte dentro de Termux, y actualizarla solo cuando vos lo pidas."
        )
        .setPositiveButton("Entendido", null)
        .show()
}
