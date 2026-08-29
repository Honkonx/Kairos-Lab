package com.termux.app.ui.studio.lsp

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.termux.app.ui.studio.editor.SyntaxHighlighter
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.isTermuxBinaryAvailable
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
// ServerConnectProvider es una interfaz ANIDADA dentro de CustomLanguageServerDefinition
// (confirmado desempaquetando editor-lsp-0.24.4.aar: CustomLanguageServerDefinition$
// ServerConnectProvider.class), no un tipo de nivel superior en el paquete — de ahí el
// nombre calificado abajo en vez de un import propio.
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition.ServerConnectProvider
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.hover.hover
import io.github.rosemoe.sora.lsp.utils.asLspPosition
import io.github.rosemoe.sora.lsp.utils.createTextDocumentIdentifier
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.io.File
import java.net.URI

private const val LOG_TAG = "KairosLsp"

/** Timeout de un request `textDocument/definition` — más corto que [RENAME_TIMEOUT_MS] porque es
 * una acción de navegación (el usuario espera respuesta inmediata; si tarda más que esto, algo
 * anda mal con el server, no vale la pena seguir esperando bloqueando el flujo). */
private const val DEFINITION_TIMEOUT_MS = 8_000L

/** Timeout de un request `textDocument/rename` — más largo que [DEFINITION_TIMEOUT_MS] porque
 * `pylsp`/rope puede tardar más en resolver todas las referencias de un símbolo en un proyecto
 * grande (no medido contra un proyecto real de miles de líneas en esta ronda). */
private const val RENAME_TIMEOUT_MS = 15_000L

/**
 * Autocompletado real vía LSP para Estudio (MVP — ver
 * `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §LSP y `docs/ide/IDE_INTEGRADO.md`). Usa la
 * librería oficial `io.github.rosemoe:editor-lsp` (mismo BOM 0.24.4 que `editor`/
 * `language-textmate`, ya declarado en `app/build.gradle`) — NO una implementación propia del
 * protocolo JSON-RPC. Patrón de conexión (un [LspProject] por proyecto, un [LspEditor] por
 * archivo, [TermuxLspProcessConnection] como transporte) adaptado de
 * `referencia/ides/Xed-Editor-main` (`core/main/src/main/java/com/rk/lsp/LspConnector.kt`,
 * GPLv3 — solo se leyó el patrón de invocación de la librería, sin copiar código Kotlin de ese
 * proyecto).
 *
 * ## Alcance real de este MVP (honesto, ver también el catálogo en [LspLanguageServer])
 * - Solo Bash/shell y Python tienen autocompletado LSP — el resto de extensiones se queda con el
 *   resaltado TextMate plano de siempre (sin error, sin UI extra: simplemente no hace nada).
 * - Solo funciona si el proyecto abierto resuelve a una ruta de filesystem real
 *   (`StudioFragment.currentProjectPath` — almacenamiento primario; mismo límite que ya acepta
 *   Build/Git) — el language server necesita leer archivos reales de disco, no puede leer un
 *   `content://` URI de SAF directamente.
 * - Editor único compartido entre pestañas (ver
 *   `com.termux.app.ui.studio.tabs.EditorTabsController`): al cambiar de pestaña, el [LspEditor]
 *   de la pestaña anterior queda conectado en segundo plano (mismo proceso de servidor — no se
 *   recrea, es liviano) pero deja de estar atado al [CodeEditor] visible hasta volver a esa
 *   pestaña — no hay diagnósticos "en vivo" de una pestaña en background.
 * - Hover / ir a definición / renombrar SÍ están cableados a la UI (ronda 2026-08-26, ver
 *   `StudioFragment.showLspContextMenu` y [requestHoverAt]/[requestDefinition]/[requestRename]
 *   abajo) — menú contextual de long-press en el editor, único punto de entrada táctil real
 *   (confirmado leyendo el código fuente de sora-editor upstream: el hover/context-menu
 *   automáticos de la librería solo funcionan con mouse externo — `isInMouseMode`/
 *   `onContextClick` — nunca con touch; el gancho táctil real es `LongPressEvent`, que la
 *   librería no cablea a nada por sí sola). Formatting queda deliberadamente fuera de esta ronda
 *   (no pedido, riesgo de sobrescribir el archivo entero sin preview). Autocompletado +
 *   diagnósticos (subrayado de errores) SÍ vienen gratis de la librería al conectar, sin UI
 *   adicional propia.
 * - Instalación del server: silenciosa la primera vez que se abre un archivo del lenguaje
 *   soportado en la sesión (un Toast + `npm install -g`/`pip install --user` en background,
 *   mismo criterio "un aviso, sin diálogo de confirmación" que ya usa el resto de instalaciones
 *   silenciosas de Kairos) — si el runtime (Node/npm o Python/pip) no está instalado, se avisa
 *   una vez por sesión y no se reintenta en cada archivo/tecla siguiente.
 */
class StudioLspController(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [LspProject] por proyecto — clave = ruta real de filesystem de la raíz del proyecto. */
    private val projects = HashMap<String, LspProject>()

    /** Servers cuya instalación ya se avisó/intentó en esta sesión de Estudio — evita repetir el
     * Toast + intento de instalación en cada archivo/tecla si la instalación falló o si el
     * runtime requerido no está disponible. */
    private val installAttempted = HashSet<LspLanguageServer>()

    /** [LspEditor] del archivo activo — el único que puede recibir "ir a definición"/hover/rename
     * disparados desde el menú contextual de long-press (ver [requestDefinition], [requestHoverAt],
     * [requestRename] abajo). Se actualiza en cada [connectFile] exitoso; puede quedar apuntando a
     * un editor ya no visible si cambia de pestaña sin haber vuelto a pasar por acá — mismo límite
     * ya documentado arriba de "un solo [CodeEditor] compartido entre pestañas". */
    private var activeEditor: LspEditor? = null

    /**
     * Punto de entrada único — llamado desde `StudioFragment` cada vez que cambia el archivo
     * activo (pestaña nueva o cambio de pestaña, ver `EditorTabsController.onActiveFileChanged`).
     * No-op silencioso si el proyecto no resuelve a una ruta real, o si la extensión no tiene
     * language server soportado (el archivo se queda con el resaltado TextMate plano de siempre).
     */
    fun onActiveFileChanged(
        codeEditor: CodeEditor,
        projectRealPath: String?,
        fileRealPath: String?,
        fileName: String
    ) {
        if (projectRealPath == null || fileRealPath == null) return
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isEmpty()) return
        val server = LspLanguageServer.forExtension(extension) ?: return

        scope.launch {
            try {
                connectFile(codeEditor, projectRealPath, fileRealPath, fileName, extension, server)
            } catch (error: Exception) {
                Log.w(LOG_TAG, "LSP connect failed for $fileRealPath (${server.id})", error)
            }
        }
    }

    private suspend fun connectFile(
        codeEditor: CodeEditor,
        projectRealPath: String,
        fileRealPath: String,
        fileName: String,
        extension: String,
        server: LspLanguageServer
    ) {
        if (!ensureServerAvailable(server)) return

        val project = projects.getOrPut(projectRealPath) { LspProject(projectRealPath) }
        ensureServerDefinitionRegistered(project, extension, server)

        val lspEditor = project.getOrCreateEditor(fileRealPath)
        val wrapperLanguage = withContext(Dispatchers.Main) {
            SyntaxHighlighter.createLanguageFor(fileName, codeEditor.context)
        }
        lspEditor.wrapperLanguage = wrapperLanguage
        withContext(Dispatchers.Main) {
            lspEditor.editor = codeEditor
        }

        if (!lspEditor.isConnected) {
            lspEditor.connectWithTimeout()
        }
        activeEditor = lspEditor
    }

    private fun ensureServerDefinitionRegistered(
        project: LspProject,
        extension: String,
        server: LspLanguageServer
    ) {
        if (project.getServerDefinition(extension, server.id) != null) return
        val definition = CustomLanguageServerDefinition(
            ext = extension,
            serverConnectProvider = ServerConnectProvider { TermuxLspProcessConnection(server.startCommand) },
            name = server.id,
            extensionsOverride = server.extensions.toList()
        )
        try {
            project.addServerDefinition(definition)
        } catch (error: Exception) {
            Log.w(LOG_TAG, "No se pudo registrar ${server.id} para ${project.projectUri}", error)
        }
    }

    /** `true` si el binario ya está disponible o se pudo instalar ahora; `false` si falta el
     * runtime prerequisito o la instalación falló (en ambos casos ya se avisó al usuario una vez
     * por [server] en esta sesión, ver [installAttempted]). */
    private suspend fun ensureServerAvailable(server: LspLanguageServer): Boolean {
        if (isTermuxBinaryAvailable(server.binaryName)) return true
        if (server in installAttempted) return false
        installAttempted += server

        val missingRuntime = server.runtimeBinaries.firstOrNull { !isTermuxBinaryAvailable(it) }
        if (missingRuntime != null) {
            showToast("Autocompletado de ${server.displayName} necesita ${server.runtimeModuleHint} instalado primero.")
            return false
        }

        showToast("Instalando ${server.displayName} (autocompletado)…")
        val installed = runInstall(server)
        if (!installed) {
            showToast("No se pudo instalar ${server.displayName} — autocompletado no disponible para este archivo.")
        }
        return installed
    }

    private fun runInstall(server: LspLanguageServer): Boolean {
        return try {
            val processBuilder = ProcessBuilder(TERMUX_BASH_PATH, "-c", server.installCommand)
            processBuilder.applyTermuxEnv()
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                Log.i(LOG_TAG, "[install ${server.id}] $line")
            }
            val exitCode = process.waitFor()
            exitCode == 0 && isTermuxBinaryAvailable(server.binaryName)
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Install failed for ${server.id}", error)
            false
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    // ── Hover / ir a definición / renombrar (menú contextual de long-press, ver
    // StudioFragment.showLspContextMenu) — cablea las 3 acciones reales que la librería
    // `editor-lsp` soporta pero NO wirea sola a una UI táctil (ver KDoc de la clase arriba: el
    // hover automático de la librería solo dispara con `isInMouseMode`, un mouse externo — nunca
    // con touch/long-press, confirmado leyendo `LspEditorHoverEvent.kt` de sora-editor upstream;
    // "ir a definición"/"renombrar" no tienen NINGÚN wiring de UI en la librería, solo los
    // métodos crudos del protocolo vía `LspEditor.requestManager`). ──────────────────────────

    /** Dispara el hover LSP para [position] en el archivo activo — reusa el `HoverWindow` propio
     * de la librería (aparece anclado a la posición de cursor/selección ACTUAL del editor, no a
     * las coordenadas de pantalla del toque, ver `HoverWindow.updateWindowPosition()` upstream)
     * — por eso el caller (`StudioFragment`) mueve la selección a [position] con
     * `codeEditor.setSelection(...)` antes de llamar acá, para que la ventana aparezca donde el
     * usuario tocó. No-op silencioso si no hay editor activo conectado — mismo criterio de
     * "no hace nada, no rompe nada" que el resto del MVP de autocompletado. */
    fun requestHoverAt(position: CharPosition) {
        val editor = activeEditor ?: return
        if (!editor.isConnected) return
        scope.launch {
            try {
                editor.eventManager.emitAsync(EventType.hover, position)
            } catch (error: Exception) {
                Log.w(LOG_TAG, "hover request failed", error)
            }
        }
    }

    /** Ubicación real de destino de un "ir a definición" — ruta de FILESYSTEM (no URI SAF, mismo
     * contrato que [com.termux.app.ui.studio.build.BuildLogActivity] ya usa para diagnósticos
     * clickeables) + línea/columna 1-based (mismo convenio que
     * `StudioFragment.openFileAtLine`/`openDiagnosticFile`). */
    data class DefinitionLocation(val realPath: String, val line: Int, val column: Int)

    /** Pide "ir a definición" para [position] en el archivo activo. [onResult] se invoca en el
     * hilo principal con la ubicación resuelta, o `null` si no hay definición, la ruta cae fuera
     * de lo que se puede resolver a un `File` real, o el request falló/hizo timeout. [onUnsupported]
     * se invoca en el hilo principal si el language server conectado no anuncia soporte para esta
     * capacidad (`requestManager.definition()` devuelve `null` — contrato real de
     * `DefaultRequestManager`, no una excepción) — ej. `bash-language-server` no la soporta para
     * variables sueltas, a diferencia de `pylsp`. */
    fun requestDefinition(
        position: CharPosition,
        onResult: (DefinitionLocation?) -> Unit,
        onUnsupported: () -> Unit
    ) {
        val editor = activeEditor
        if (editor == null || !editor.isConnected) {
            scope.launch { withContext(Dispatchers.Main) { onUnsupported() } }
            return
        }
        scope.launch {
            try {
                val params = DefinitionParams(editor.uri.createTextDocumentIdentifier(), position.asLspPosition())
                val future = editor.requestManager.definition(params)
                if (future == null) {
                    withContext(Dispatchers.Main) { onUnsupported() }
                    return@launch
                }
                val result = withTimeout(DEFINITION_TIMEOUT_MS) { future.await() }
                val location = firstLocation(result)?.let { (uri, range) ->
                    uriToRealPath(uri)?.let { path -> DefinitionLocation(path, range.start.line + 1, range.start.character + 1) }
                }
                withContext(Dispatchers.Main) { onResult(location) }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "definition request failed", error)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    private fun firstLocation(result: Either<List<Location>, List<LocationLink>>?): Pair<String, Range>? {
        if (result == null) return null
        return if (result.isLeft) {
            result.left?.firstOrNull()?.let { it.uri to it.range }
        } else {
            result.right?.firstOrNull()?.let { it.targetUri to (it.targetSelectionRange ?: it.targetRange) }
        }
    }

    private fun uriToRealPath(uriString: String): String? = try {
        File(URI(uriString)).absolutePath
    } catch (error: Exception) {
        Log.w(LOG_TAG, "No se pudo resolver la URI de definición: $uriString", error)
        null
    }

    /** Pide un "renombrar símbolo" (LSP `textDocument/rename`) para [position] en el archivo
     * activo, con el nombre nuevo [newName]. [onResult] recibe en el hilo principal el
     * [WorkspaceEdit] crudo devuelto por el servidor (puede tener `changes` vacío/null si el
     * servidor no propuso nada) — la resolución a URIs SAF reales, el preview y la escritura
     * quedan del lado de `StudioFragment` (ver `StudioFragment.applyLspRename`), porque ese es el
     * único lugar que ya tiene los helpers de lectura/escritura SAF
     * (`resolveUriFromRealPath`/`readTextFromUri`/`writeTextToUri`) — este controller se mantiene
     * agnóstico de UI/SAF a propósito (ver KDoc de la clase). [onUnsupported] igual que en
     * [requestDefinition] — `renameProvider` no anunciado por el servidor (ej.
     * `bash-language-server`, que no tiene renombrado). */
    fun requestRename(
        position: CharPosition,
        newName: String,
        onResult: (WorkspaceEdit?) -> Unit,
        onUnsupported: () -> Unit
    ) {
        val editor = activeEditor
        if (editor == null || !editor.isConnected) {
            scope.launch { withContext(Dispatchers.Main) { onUnsupported() } }
            return
        }
        scope.launch {
            try {
                val params = RenameParams(editor.uri.createTextDocumentIdentifier(), position.asLspPosition(), newName)
                val future = editor.requestManager.rename(params)
                if (future == null) {
                    withContext(Dispatchers.Main) { onUnsupported() }
                    return@launch
                }
                val edit = withTimeout(RENAME_TIMEOUT_MS) { future.await() }
                withContext(Dispatchers.Main) { onResult(edit) }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "rename request failed", error)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    /** Cierra todos los proyectos/procesos de language server abiertos en esta sesión de Estudio
     * — llamar desde `StudioFragment.onDestroyView()`. Reinicia el estado por completo: la
     * próxima vez que se abra Estudio y un archivo soportado, los servers se reconectan desde
     * cero (mismo criterio que el resto del estado en memoria de `StudioFragment`, que tampoco
     * sobrevive a la destrucción de la vista — ver `SessionStateManager` para lo que sí persiste
     * a disco). */
    fun destroy() {
        val projectsSnapshot = projects.values.toList()
        projects.clear()
        installAttempted.clear()
        activeEditor = null
        Thread {
            projectsSnapshot.forEach { project ->
                runCatching { project.dispose() }
                    .onFailure { Log.w(LOG_TAG, "dispose failed for ${project.projectUri}", it) }
            }
        }.start()
        scope.cancel()
    }
}

/** Convierte un [WorkspaceEdit] crudo (respuesta de `textDocument/rename`) al mapa
 * `uri (file://…) -> ediciones` real a aplicar — la mayoría de los servers (incluido `pylsp`)
 * usan el campo `changes` (más simple, un `Map<String, List<TextEdit>>` directo); el spec de LSP
 * también permite `documentChanges` (una lista que puede mezclar ediciones de texto con
 * operaciones de archivo — crear/renombrar/borrar, que este MVP no soporta y descarta en
 * silencio, ver `Either.isLeft`) para servers más nuevos — se intenta `changes` primero y se cae
 * a `documentChanges` solo si el primero viene vacío/null, nunca se combinan ambos (evita
 * duplicar ediciones si un server mandara los dos por algún motivo). Vive acá (no en
 * `StudioFragment`) porque es manipulación pura de tipos `lsp4j`, sin ninguna dependencia de
 * Android/SAF — [applyTextEditsToContent] abajo es la otra mitad pura de este mismo criterio. */
fun extractWorkspaceEditChanges(edit: WorkspaceEdit): Map<String, List<TextEdit>> {
    val changes = edit.changes
    if (!changes.isNullOrEmpty()) return changes

    val documentChanges = edit.documentChanges ?: return emptyMap()
    val result = LinkedHashMap<String, MutableList<TextEdit>>()
    documentChanges.forEach { either ->
        if (either.isLeft) {
            val textDocumentEdit = either.left
            // TextDocumentEdit.getEdits() en lsp4j 0.24.0 (versión real pinneada acá, confirmado
            // con javap sobre el .jar real) devuelve List<TextEdit> directo — NO
            // List<Either<TextEdit, SnippetTextEdit>>, esa firma es de una versión de lsp4j más
            // nueva que la que usa este proyecto. Sin Either que desenvolver acá.
            result.getOrPut(textDocumentEdit.textDocument.uri) { mutableListOf() }.addAll(textDocumentEdit.edits)
        }
        // Either.right (ResourceOperation: crear/renombrar/borrar archivo) — fuera de alcance de
        // este MVP, se ignora en silencio (mismo criterio que el resto del alcance honesto de
        // esta clase, ver KDoc arriba).
    }
    return result
}

/** Aplica [edits] (rangos LSP, 0-based UTF-16) sobre [original] y devuelve el texto resultante.
 * Recorre los edits de MAYOR a MENOR offset de inicio para que aplicar uno no invalide los
 * offsets de los que faltan por aplicar (técnica estándar para aplicar múltiples `TextEdit` de
 * LSP sobre un mismo documento — asume rangos no solapados, contrato real del protocolo LSP para
 * una respuesta de rename). [positionToOffset] recorre el texto línea por línea porque
 * `Position.line`/`.character` son 0-based mecanismos de LSP, no offsets directos de `String`. */
fun applyTextEditsToContent(original: String, edits: List<TextEdit>): String {
    val sorted = edits.sortedByDescending { positionToOffset(original, it.range.start.line, it.range.start.character) }
    var result = original
    for (edit in sorted) {
        val start = positionToOffset(result, edit.range.start.line, edit.range.start.character)
        val end = positionToOffset(result, edit.range.end.line, edit.range.end.character)
        if (start < 0 || end < start || end > result.length) continue
        result = result.substring(0, start) + edit.newText + result.substring(end)
    }
    return result
}

private fun positionToOffset(text: String, line: Int, character: Int): Int {
    var currentLine = 0
    var index = 0
    while (currentLine < line && index < text.length) {
        if (text[index] == '\n') currentLine++
        index++
    }
    return (index + character).coerceIn(0, text.length)
}
