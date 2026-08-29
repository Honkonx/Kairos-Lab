package com.termux.app.ui.studio.search

import android.net.Uri

/**
 * Una coincidencia de "Buscar en proyecto": un archivo + número de línea (1-based) + el texto
 * de esa línea (recortado como contexto, ver [ProjectSearchEngine]). [relativePath] es relativo
 * a la raíz del proyecto abierto — se muestra en la lista para distinguir archivos con el mismo
 * nombre en carpetas distintas.
 */
data class ProjectSearchResult(
    val uri: Uri,
    val fileName: String,
    val relativePath: String,
    val lineNumber: Int,
    val lineText: String
)
