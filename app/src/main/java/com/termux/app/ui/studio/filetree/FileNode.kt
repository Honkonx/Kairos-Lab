package com.termux.app.ui.studio.filetree

import androidx.documentfile.provider.DocumentFile

/**
 * Nodo del árbol de archivos del sidebar. `depth` es el nivel de anidamiento (0 = hijo directo
 * de la carpeta raíz abierta) — se usa para la indentación visual en [FileTreeAdapter].
 * `children`/`childrenLoaded` cachean el listado ya resuelto vía SAF para no volver a golpear
 * el ContentProvider cada vez que se colapsa/expande la misma carpeta.
 *
 * `parent` es el nodo carpeta que contiene a este (null si es hijo directo de la raíz del
 * proyecto abierto, que no tiene FileNode propio) — se usa para saber qué subárbol recargar
 * después de crear/renombrar/eliminar sin recargar el árbol completo (ver
 * [FileTreeAdapter.refreshNode] y `MainActivity.refreshParentOf`).
 */
class FileNode(
    val document: DocumentFile,
    val depth: Int,
    val parent: FileNode? = null
) {
    var isExpanded: Boolean = false
    var childrenLoaded: Boolean = false

    /** `true` mientras hay un [FileTreeLoader.loadChildren] en vuelo para este nodo — evita que
     * un doble tap sobre la misma carpeta (antes de que la primera carga SAF vuelva) dispare una
     * segunda carga concurrente, que insertaría los hijos duplicados en [FileTreeAdapter] cuando
     * ambos callbacks resuelven (ver [FileTreeAdapter.toggleExpansion]). */
    var isLoading: Boolean = false

    val children: MutableList<FileNode> = mutableListOf()

    val name: String get() = document.name ?: "?"
    val isDirectory: Boolean get() = document.isDirectory
}
