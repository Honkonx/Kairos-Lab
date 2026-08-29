package com.termux.app.ui.studio.tabs

import android.net.Uri

/**
 * Estado en memoria de un archivo abierto en una pestaña. `content` se sincroniza con el
 * editor solo al cambiar de pestaña o guardar (ver [EditorTabsController]) — Kairos IDE usa un
 * único [io.github.rosemoe.sora.widget.CodeEditor] compartido entre pestañas (no una instancia
 * por archivo, que sería mucho más pesado), así que el contenido "inactivo" vive acá.
 */
data class OpenFile(
    val uri: Uri,
    val name: String,
    var content: String,
    var isDirty: Boolean = false
)
