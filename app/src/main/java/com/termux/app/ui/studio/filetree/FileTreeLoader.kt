package com.termux.app.ui.studio.filetree

import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile

/**
 * `DocumentFile.listFiles()` es I/O bloqueante sobre el ContentProvider de SAF — esta clase lo
 * corre en un hilo aparte y entrega el resultado ya ordenado (carpetas primero, alfabético
 * dentro de cada grupo) de vuelta en el hilo principal. Sin dependencia de coroutines a
 * propósito: ide-app hoy no trae kotlinx-coroutines y esta ronda no es la que decide agregarla.
 */
object FileTreeLoader {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun loadChildren(
        parent: DocumentFile,
        depth: Int,
        parentNode: FileNode? = null,
        onLoaded: (List<FileNode>) -> Unit
    ) {
        Thread {
            val children = parent.listFiles()
                .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
                .map { FileNode(it, depth, parentNode) }
            mainHandler.post { onLoaded(children) }
        }.start()
    }

    /** Carpetas que ningún recorrido recursivo debería bajar a mirar — build output, VCS interno,
     * dependencias descargadas. Usado por [walkFilesBlocking] (búsqueda en todo el proyecto);
     * el sidebar interactivo ([loadChildren]) no filtra nada porque ahí el usuario decide qué
     * expandir. */
    private val recursiveWalkIgnoredDirNames = setOf(
        ".git", ".gradle", ".idea", "build", "node_modules", "bin", "obj"
    )

    /**
     * Recorre TODO el subárbol de [root] (recursivo, todas las profundidades) y llama a [onFile]
     * por cada archivo (no carpeta) encontrado, con su ruta relativa a [root] separada por "/".
     * A diferencia de [loadChildren] (un nivel por vez, pensado para expandir el sidebar
     * interactivamente) esto trae el árbol completo de una — lo usa la búsqueda en todo el
     * proyecto ([com.termux.app.ui.studio.search.ProjectSearchActivity]), que necesita mirar cada archivo,
     * no solo el nivel visible.
     *
     * Bloqueante — `DocumentFile.listFiles()` es I/O sobre el ContentProvider de SAF en cada
     * llamada recursiva. Quien invoca es responsable de correrlo en un hilo aparte y de volver
     * al hilo principal para tocar UI.
     */
    fun walkFilesBlocking(root: DocumentFile, relativePath: String = "", onFile: (DocumentFile, String) -> Unit) {
        val children = root.listFiles()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory && name in recursiveWalkIgnoredDirNames) continue
            val childPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
            if (child.isDirectory) {
                walkFilesBlocking(child, childPath, onFile)
            } else {
                onFile(child, childPath)
            }
        }
    }
}
