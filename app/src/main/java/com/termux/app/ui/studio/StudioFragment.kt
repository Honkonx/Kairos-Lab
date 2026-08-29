package com.termux.app.ui.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.termux.R
import com.termux.app.ui.studio.ai.AiClient
import com.termux.app.ui.studio.ai.AiProviderPrefs
import com.termux.app.ui.studio.ai.AiProviderSettingsActivity
import com.termux.app.ui.studio.ai.AiResult
import com.termux.app.ui.studio.build.BuildLogActivity
import com.termux.databinding.StudioActivityMainBinding
import com.termux.app.ui.studio.editor.EditorSchemeSetup
import com.termux.app.ui.studio.editor.EditorSearchController
import com.termux.app.ui.studio.filetree.FileNode
import com.termux.app.ui.studio.filetree.FileTreeAdapter
import com.termux.app.ui.studio.filetree.FileTreeLoader
import com.termux.app.ui.studio.git.GitPanelActivity
import com.termux.app.ui.studio.keyboard.KeyboardShortcutAction
import com.termux.app.ui.studio.keyboard.KeyboardShortcutHandler
import com.termux.app.ui.studio.keyboard.VirtualKeyboardBar
import com.termux.app.ui.studio.lsp.LspLanguageServer
import com.termux.app.ui.studio.lsp.StudioLspController
import com.termux.app.ui.studio.lsp.applyTextEditsToContent
import com.termux.app.ui.studio.lsp.extractWorkspaceEditChanges
import com.termux.app.ui.studio.palette.CommandHost
import com.termux.app.ui.studio.palette.CommandPaletteDialog
import com.termux.app.ui.studio.palette.CommandRegistry
import com.termux.app.ui.studio.project.RecentProjectsManager
import com.termux.app.ui.studio.project.SessionStateManager
import com.termux.app.ui.studio.project.StudioSession
import com.termux.app.ui.studio.search.ProjectSearchActivity
import com.termux.app.ui.studio.settings.EditorPrefs
import com.termux.app.ui.studio.settings.EditorSettingsActivity
import com.termux.app.ui.studio.tabs.EditorTabsController
import com.termux.app.ui.studio.tabs.OpenFile
import com.termux.app.ui.studio.termux.TerminalActivity
import io.github.rosemoe.sora.event.LongPressEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.text.CharPosition
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URI

/**
 * Pantalla principal del IDE de Kairos ("Estudio") — layout real de IDE: sidebar de explorador
 * de archivos (drawer izquierdo, [FileTreeAdapter]), pestañas de archivos abiertos
 * ([EditorTabsController]) y barra de estado inferior (archivo activo + línea:columna del cursor).
 *
 * Antes era una `Activity` standalone (`MainActivity`, proyecto hermano, ver
 * docs/ide/IDE_EXTERNO.md); integrado como `Fragment` dentro de TermuxActivity (tab
 * "Más" → Estudio, ver docs/ide/IDE_INTEGRADO.md). El menú de la toolbar se infla
 * directo sobre el [MaterialToolbar][com.google.android.material.appbar.MaterialToolbar] del
 * layout (no hay ActionBar de Activity en un Fragment) y los atajos Ctrl+<tecla> se resuelven
 * por [handleKeyShortcut], que TermuxActivity reenvía desde su `dispatchKeyEvent`.
 */
class StudioFragment : Fragment(), CommandHost {

    private lateinit var binding: StudioActivityMainBinding
    private lateinit var tabsController: EditorTabsController
    private lateinit var fileTreeAdapter: FileTreeAdapter
    private lateinit var searchController: EditorSearchController

    private var currentFileUri: Uri? = null

    /** Ruta real de filesystem del proyecto abierto, o null si no se pudo resolver (o si
     * todavía no se abrió ninguna carpeta). Solo se usa para compilar (ver [openBuildScreen]) —
     * Termux corre en otro proceso/UID y no puede resolver árboles SAF de otra app, así que el
     * build necesita una ruta real, no el content:// URI que usa el editor para todo lo demás. */
    private var currentProjectPath: String? = null

    /** URI SAF de árbol de la carpeta de proyecto abierta, o null si no hay ninguna — a
     * diferencia de [currentProjectPath] (ruta real de filesystem, solo para build), esto es lo
     * que se persiste/restaura entre reinicios (ver [SessionStateManager]), porque un URI SAF es
     * lo único que sirve para volver a montar el árbol del sidebar vía `DocumentFile.fromTreeUri`. */
    private var currentProjectTreeUri: Uri? = null

    /** Si es `true`, la próxima carpeta elegida por [openFolderLauncher] viene del flujo "Nuevo
     * proyecto → plantilla Android" (ver [showNewProjectDialog]) y hay que generarle los
     * archivos mínimos después de abrirla. Se resetea apenas se consume. */
    private var pendingAndroidTemplate = false

    private lateinit var recentProjectsManager: RecentProjectsManager
    private lateinit var sessionStateManager: SessionStateManager

    /** Autocompletado real vía LSP (Bash/Python, ver [StudioLspController] para el alcance
     * honesto del MVP) — vive por instancia de vista del fragment, igual que [tabsController]:
     * se recrea en cada [onViewCreated] y se cierra en [onDestroyView]. */
    private lateinit var lspController: StudioLspController

    /** Sesiones de proyecto abiertas — multi-proyecto (ver
     * `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §2, implementado 2026-08-26).
     * [currentProjectPath]/[currentProjectTreeUri]/[rootFolder] arriba siguen siendo el espejo
     * de la sesión ACTIVA (`sessions[activeSessionIndex]`) — el resto del código (build, git,
     * búsqueda en proyecto) los sigue usando tal cual, sin enterarse de que ahora puede haber
     * más de un proyecto abierto. Límite de [MAX_SESSIONS] sesiones simultáneas — cada una
     * mantiene sora-editor + git state + árbol de archivos "pesados" en memoria si se dejara sin
     * límite (riesgo de memoria documentado en el plan, no validado con `dumpsys meminfo` en
     * esta ronda por no tener dispositivo conectado — a confirmar en la próxima ronda con
     * "honkon"). */
    private val sessions = mutableListOf<StudioSession>()
    private var activeSessionIndex = -1

    /** `DocumentFile` de la raíz del proyecto abierto — necesaria para crear archivos/carpetas
     * directamente en la raíz (no hay [FileNode] propio para ese nivel, ver [FileNode.parent])
     * y para recargar el árbol completo después de una operación cuyo padre es la raíz misma. */
    private var rootFolder: DocumentFile? = null

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { openFile(it) } }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            openProjectAsSession(it)
            if (pendingAndroidTemplate) {
                pendingAndroidTemplate = false
                generateAndroidTemplate(it)
            }
        }
    }

    /** Resultado de [ProjectSearchActivity]: el usuario tocó un resultado y hay que abrir ese
     * archivo con el cursor en la línea encontrada (ver [openFileAtLine]). */
    private val projectSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val fileUri: Uri = data.getParcelableExtra(ProjectSearchActivity.EXTRA_RESULT_FILE_URI) ?: return@registerForActivityResult
        val lineNumber = data.getIntExtra(ProjectSearchActivity.EXTRA_RESULT_LINE_NUMBER, 1)
        openFileAtLine(fileUri, lineNumber)
    }

    /** Resultado de [BuildLogActivity]: el usuario tocó una línea de error/warning con ubicación
     * real (diagnóstico clickeable, ver `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §4
     * punto 3, implementado 2026-08-26) — hay que abrir ese archivo con el cursor en la línea y
     * columna reportadas. A diferencia de [projectSearchLauncher], acá el diagnóstico trae una
     * ruta de FILESYSTEM real (la que ve el proceso de `gradlew` dentro de Termux), no un URI SAF
     * — [openDiagnosticFile] la resuelve contra [currentProjectTreeUri] (ver
     * [resolveUriFromRealPath]). */
    private val buildLogLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val filePath = data.getStringExtra(BuildLogActivity.EXTRA_RESULT_FILE_PATH) ?: return@registerForActivityResult
        val line = data.getIntExtra(BuildLogActivity.EXTRA_RESULT_LINE, 1)
        val column = data.getIntExtra(BuildLogActivity.EXTRA_RESULT_COLUMN, 1)
        openDiagnosticFile(filePath, line, column)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // ContextThemeWrapper con el tema de Estudio resuelto (StudioThemePrefs, INDEPENDIENTE
        // del tema del apk) — a diferencia de KairosThemePrefs (que necesita setTheme() +
        // recreate() de la Activity ENTERA), un Fragment puede re-temar su propia subrama de
        // vistas clonando el inflater con un Context envuelto — no hace falta recrear
        // TermuxActivity para que cambiar el tema de Estudio tome efecto (ver docs/humano/humano202.md).
        val themedContext = android.view.ContextThemeWrapper(
            requireContext(), com.termux.app.util.StudioThemePrefs.resolveStyleRes(requireContext())
        )
        binding = StudioActivityMainBinding.inflate(inflater.cloneInContext(themedContext), container, false)
        return binding.root
    }

    // Selector de tema de Estudio — mismo componente inline que ConfigFragment.addThemePickerRow()
    // usa para el tema del apk (ver com.termux.app.ui.widget.InlineThemePicker), anclado a la
    // toolbar porque un ítem de menú de overflow no tiene una View propia fácil de anclar.
    private fun showStudioThemePicker() {
        val ctx = requireContext()
        val themes = com.termux.app.util.StudioThemePrefs.StudioTheme.entries
        val current = com.termux.app.util.StudioThemePrefs.getSelectedTheme(ctx)
        val options = themes.map {
            com.termux.app.ui.widget.InlineThemePicker.Option(it.id, it.label)
        }
        com.termux.app.ui.widget.InlineThemePicker.show(binding.toolbar, options, current.id) { chosen ->
            val newTheme = com.termux.app.util.StudioThemePrefs.StudioTheme.fromId(chosen.id)
            com.termux.app.util.StudioThemePrefs.setSelectedTheme(ctx, newTheme)
            // Fase mínima (docs/humano/humano202.md): NO se re-infla la vista en caliente — hacerlo
            // reventaría el editor abierto (CodeEditor/tabs/git state se recrearían desde cero,
            // arriesgando perder cambios sin guardar). Se aplica la próxima vez que
            // onCreateView() corra (salir y volver a entrar a Estudio, o cualquier recreate()
            // de TermuxActivity por otra razón, ej. cambiar el tema del apk).
            Toast.makeText(ctx, "Tema de Estudio actualizado — se aplica al volver a entrar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Sin setSupportActionBar() (método de Activity) — el MaterialToolbar del layout hace de
        // barra del IDE: navegación (abre el drawer del sidebar) + menú propio inflado directo.
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.toolbar.inflateMenu(R.menu.studio_menu_main)
        binding.toolbar.setOnMenuItemClickListener { item -> handleMenuItem(item) }

        recentProjectsManager = RecentProjectsManager(requireContext())
        sessionStateManager = SessionStateManager(requireContext())
        lspController = StudioLspController(requireContext().applicationContext)

        EditorSchemeSetup.apply(binding.codeEditor)
        setUpFileTree()
        setUpTabsController()
        setUpCursorStatusTracking()
        setUpLspContextMenu()
        setUpSearchController()
        binding.virtualKeyboardBar.attachTo(binding.codeEditor)
        restoreSessionIfAny()
    }

    private fun setUpSearchController() {
        searchController = EditorSearchController(
            codeEditor = binding.codeEditor,
            barRoot = binding.searchBarInclude.root
        )
        // Bug real confirmado por ADB (2026-08-25, ver docs/humano225.md y siguientes): el
        // botón atrás de Android dejaba de navegar en TODA la app después de visitar el
        // módulo Estudio al menos una vez, sin importar a qué pantalla se navegara después
        // (confirmado en vivo: backstack de fragments acumulado hasta la entrada #26 sin que
        // ningún back press lo consumiera). Causa real: `addCallback(this)` usa el Lifecycle
        // del propio Fragment, que sigue en estado CREATED (no DESTROYED) mientras el
        // Fragment queda vivo en el back stack con la vista destruida — el callback queda
        // habilitado indefinidamente y, al ser el más reciente agregado al dispatcher,
        // intercepta cualquier back press posterior aunque Estudio ya no esté visible. Mismo
        // patrón correcto que ya usa ModuleWebViewFragment.kt: `viewLifecycleOwner` ata el
        // callback al ciclo de vida de la VISTA, que sí termina cuando el Fragment se
        // reemplaza/oculta.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (::searchController.isInitialized && searchController.isVisible()) {
                searchController.hide()
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    /** Reaplica las preferencias del editor cada vez que el fragment vuelve a primer plano —
     * cubre el caso de volver desde [EditorSettingsActivity] tras cambiar un valor, sin depender
     * de `startActivityForResult`/`ActivityResultContracts` para algo tan simple como releer
     * SharedPreferences. */
    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            EditorSchemeSetup.applyPrefs(binding.codeEditor, EditorPrefs(requireContext()))
        }
    }

    /** Se llama al salir de foco (pasar a background, girar pantalla que recree la Activity,
     * cerrar la app) — es el punto más confiable disponible sin un ciclo de vida más elaborado
     * (`onDestroy` no está garantizado en un kill del sistema, `onStop` sí se dispara siempre
     * antes de eso). Ver [SessionStateManager] para qué se persiste y qué limitaciones tiene. */
    override fun onStop() {
        super.onStop()
        persistCurrentSession()
        // Ver VirtualKeyboardBar.releaseAllModifiers — un Ctrl/Alt/Shift LOCKED no debería
        // sobrevivir a pasar a background (auditoría 2026-08-19, sección 2.2 de keyboard/).
        if (::binding.isInitialized) binding.virtualKeyboardBar.releaseAllModifiers()
    }

    /** Cierra los procesos de language server LSP abiertos en esta vista de Estudio (ver
     * [StudioLspController.destroy]) — `onDestroyView`, no `onDestroy`, porque es el punto real
     * en que se pierde el [io.github.rosemoe.sora.widget.CodeEditor] compartido (mismo criterio
     * de ciclo de vida que el resto de este fragment, que recrea prácticamente todo su estado de
     * vista en cada [onViewCreated] — ver KDoc de [sessions] sobre por qué la vista se destruye
     * al navegar a otro tab del BottomNavigationView). */
    override fun onDestroyView() {
        super.onDestroyView()
        if (::lspController.isInitialized) lspController.destroy()
    }

    private fun persistCurrentSession() {
        if (!::sessionStateManager.isInitialized) return
        captureActiveSessionTabState()
        sessionStateManager.saveSessions(sessions, activeSessionIndex)
    }

    /** Vuelca el estado actual de pestañas (URIs abiertas + cuál está activa) dentro del objeto
     * [StudioSession] activo — es una FOTO, no contenido de archivo (ver KDoc de
     * [StudioSession]). Se llama antes de cambiar/cerrar de sesión y antes de persistir a disco
     * ([onStop]), para que la sesión que se deja de ver no pierda su lista de pestañas. */
    private fun captureActiveSessionTabState() {
        val session = sessions.getOrNull(activeSessionIndex) ?: return
        if (!::tabsController.isInitialized) return
        tabsController.syncActiveFileDirtyState()
        session.openTabs = tabsController.openFilesList()
            .map { SessionStateManager.TabState(it.uri, it.name) }
            .toMutableList()
        session.activeTabUri = tabsController.activeFile()?.uri
    }

    /** Restaura, en orden, TODAS las sesiones de proyecto que estaban abiertas la última vez que
     * la app pasó a background (multi-proyecto, no solo la última) — sin que el usuario tenga
     * que volver a navegar el árbol ni reabrir cada carpeta a mano. Cada URI restaurado puede
     * fallar (permiso SAF revocado, archivo/carpeta borrada o movida) — se ignora en silencio
     * sesión por sesión / archivo por archivo en vez de abortar toda la restauración. */
    private fun restoreSessionIfAny() {
        val (savedSessions, savedActiveIndex) = sessionStateManager.loadSessions()
        if (savedSessions.isEmpty()) return
        sessions.addAll(savedSessions)
        activeSessionIndex = savedActiveIndex.coerceIn(0, sessions.size - 1)
        val session = sessions[activeSessionIndex]
        try {
            openFolder(session.treeUri, showToast = false)
            restoreTabsForSession(session)
        } catch (_: Exception) {
            // Permiso SAF probablemente revocado tras el reinicio — se sigue sin sidebar.
        }
        updateSessionChipsUi()
    }

    /** Reabre las pestañas guardadas de [session] (releyendo contenido desde el URI SAF, ver
     * [readTextFromUriSilently]) y selecciona la que estaba activa — usado tanto al restaurar la
     * sesión completa al iniciar la app como al volver a una sesión ya abierta con
     * [switchToSession]. */
    private fun restoreTabsForSession(session: StudioSession) {
        session.openTabs.forEach { tab ->
            val content = readTextFromUriSilently(tab.uri) ?: return@forEach
            tabsController.openOrSelect(tab.uri, tab.name, content)
        }
        session.activeTabUri?.let { tabsController.selectByUri(it) }
    }

    /** Igual que [readTextFromUri] pero sin mostrar un Toast si falla — usado durante la
     * restauración automática de sesión, donde un archivo individual no disponible (borrado,
     * movido, permiso revocado) no debería interrumpir con un mensaje de error por cada uno. */
    private fun readTextFromUriSilently(uri: Uri): String? = try {
        requireContext().contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }
    } catch (_: Exception) {
        null
    }

    private fun setUpFileTree() {
        fileTreeAdapter = FileTreeAdapter(
            onFileClick = { node -> openFileFromTree(node) },
            onExpandRequested = { node ->
                FileTreeLoader.loadChildren(node.document, node.depth + 1, node) { children ->
                    fileTreeAdapter.onChildrenLoaded(node, children)
                }
            },
            onContextMenuRequested = { node, anchor -> showFileContextMenu(node, anchor) },
            onFilteredDirectoryClicked = {
                binding.sidebarInclude.sidebarFilterInput.setText("")
            }
        )
        binding.sidebarInclude.fileTreeRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.sidebarInclude.fileTreeRecycler.adapter = fileTreeAdapter
        setUpFileTreeToolbar()
    }

    /** Botones "+" del header del sidebar (crear en la raíz del proyecto) + barra de filtro por
     * nombre — ver [R.layout.studio_layout_sidebar]. */
    private fun setUpFileTreeToolbar() {
        binding.sidebarInclude.sidebarNewFileRoot.setOnClickListener {
            showNewEntryDialogAtRoot(isDirectory = false)
        }
        binding.sidebarInclude.sidebarNewFolderRoot.setOnClickListener {
            showNewEntryDialogAtRoot(isDirectory = true)
        }
        binding.sidebarInclude.sidebarFilterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                fileTreeAdapter.setFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun setUpTabsController() {
        tabsController = EditorTabsController(
            tabLayout = binding.fileTabs,
            codeEditor = binding.codeEditor,
            emptyStateLabel = binding.emptyStateLabel,
            statusFileLabel = binding.statusFileLabel,
            onActiveFileChanged = { file ->
                currentFileUri = file?.uri
                notifyLspActiveFileChanged(file)
            }
        )
    }

    /** Conecta (o reconecta) el autocompletado LSP para el archivo recién activado — ver
     * [StudioLspController] para el alcance real (solo Bash/Python, solo con ruta de filesystem
     * real resuelta). No-op silencioso en cualquier otro caso: el archivo se queda con el
     * resaltado TextMate plano de siempre, sin autocompletado. */
    private fun notifyLspActiveFileChanged(file: OpenFile?) {
        if (!::binding.isInitialized || !::lspController.isInitialized) return
        val fileRealPath = file?.let { resolveRealFilePath(it.uri) }
        lspController.onActiveFileChanged(binding.codeEditor, currentProjectPath, fileRealPath, file?.name.orEmpty())
    }

    /** Ruta real de filesystem de un documento SAF individual (no un árbol), solo volumen
     * primario — misma limitación/mecanismo que [resolveRealPathFromTreeUri], pero para un
     * `DocumentsContract.getDocumentId` de un archivo suelto en vez de un árbol completo.
     * Devuelve `null` (a diferencia de [resolveDisplayPath], que cae al URI crudo) porque acá el
     * caller ([notifyLspActiveFileChanged]) necesita saber con certeza si hay o no una ruta real
     * usable por un proceso de Termux. */
    private fun resolveRealFilePath(uri: Uri): String? {
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2 || parts[0] != "primary") return null
        val root = Environment.getExternalStorageDirectory()
        val resolved = if (parts[1].isEmpty()) root else File(root, parts[1])
        return if (resolved.exists()) resolved.absolutePath else null
    }

    /** sora-editor no dispara un TextWatcher clásico — la posición de cursor se sigue vía su
     * propio bus de eventos (`CodeEditor.subscribeEvent`, confirmado contra las clases reales
     * del AAR `editor-0.24.4` de este build, no solo por memoria de la API). */
    private fun setUpCursorStatusTracking() {
        binding.codeEditor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            updateCursorStatusLabel()
        }
    }

    private fun updateCursorStatusLabel() {
        val cursor = binding.codeEditor.cursor
        val line = cursor.leftLine + 1
        val column = cursor.leftColumn + 1
        binding.statusCursorLabel.text = "$line:$column"
    }

    // ── Menú contextual LSP: hover / ir a definición / renombrar (ver StudioLspController) ────

    /** [LongPressEvent] es el único gancho táctil real que sora-editor ofrece para esto — el
     * hover/context-menu automáticos de la librería `editor-lsp` solo funcionan con mouse
     * externo (confirmado leyendo el código fuente upstream, ver KDoc de [StudioLspController]),
     * así que el menú de long-press acá es la UI propia de Kairos, no algo que la librería dé
     * gratis. Solo se muestra si la extensión del archivo activo tiene un language server en
     * [LspLanguageServer.forExtension] — en cualquier otro archivo (Kotlin, XML, etc.) el
     * long-press se comporta exactamente como antes (selección de palabra nativa de sora-editor,
     * sin menú extra). */
    private fun setUpLspContextMenu() {
        binding.codeEditor.subscribeEvent(LongPressEvent::class.java) { event, _ ->
            val extension = tabsController.activeFile()?.name?.substringAfterLast('.', "").orEmpty()
            if (LspLanguageServer.forExtension(extension) == null) return@subscribeEvent
            showLspContextMenu(event.charPosition)
        }
    }

    private fun showLspContextMenu(position: CharPosition) {
        val popup = PopupMenu(requireContext(), binding.codeEditor)
        popup.menu.add(0, 1, 0, R.string.lsp_menu_goto_definition)
        popup.menu.add(0, 2, 1, R.string.lsp_menu_hover)
        popup.menu.add(0, 3, 2, R.string.lsp_menu_rename)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { requestLspGoToDefinition(position); true }
                2 -> { requestLspHover(position); true }
                3 -> { showLspRenameDialog(position); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun requestLspGoToDefinition(position: CharPosition) {
        if (!::lspController.isInitialized) return
        binding.codeEditor.setSelection(position.line, position.column)
        lspController.requestDefinition(
            position,
            onResult = { location ->
                if (location == null) {
                    Toast.makeText(requireContext(), R.string.lsp_definition_not_found, Toast.LENGTH_SHORT).show()
                } else {
                    val uri = resolveUriFromRealPath(location.realPath)
                    if (uri == null) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.lsp_definition_outside_project, location.realPath),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        openFileAtLine(uri, location.line, location.column)
                    }
                }
            },
            onUnsupported = {
                Toast.makeText(requireContext(), R.string.lsp_definition_unsupported, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun requestLspHover(position: CharPosition) {
        if (!::lspController.isInitialized) return
        // Mueve el cursor a la posición tocada — HoverWindow.updateWindowPosition() (librería
        // editor-lsp) se posiciona respecto de la selección ACTUAL del editor, no de coordenadas
        // de pantalla del toque (ver KDoc de StudioLspController.requestHoverAt).
        binding.codeEditor.setSelection(position.line, position.column)
        lspController.requestHoverAt(position)
    }

    private fun showLspRenameDialog(position: CharPosition) {
        if (!::lspController.isInitialized) return
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.lsp_rename_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.lsp_rename_title)
            .setView(input)
            .setPositiveButton(R.string.lsp_rename_ok) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty()) requestLspRename(position, newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestLspRename(position: CharPosition, newName: String) {
        lspController.requestRename(
            position,
            newName,
            onResult = { edit -> if (edit != null) handleLspRenameResult(edit) else showLspRenameError() },
            onUnsupported = {
                Toast.makeText(requireContext(), R.string.lsp_rename_unsupported, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showLspRenameError() {
        Toast.makeText(requireContext(), R.string.lsp_rename_failed, Toast.LENGTH_LONG).show()
    }

    /** Arma el preview de "cuántos archivos, cuántos cambios" y pide confirmación ANTES de tocar
     * ningún archivo — [applyLspRenameEdits] es "todo o nada": si algún archivo del [edit] no se
     * puede resolver a un URI SAF real (fuera del árbol abierto, ej. una lib externa del sistema
     * fuera de `storage/emulated/0`), no se escribe NINGUNO, para no dejar un renombrado a medio
     * aplicar entre archivos. */
    private fun handleLspRenameResult(edit: WorkspaceEdit) {
        val changes = extractWorkspaceEditChanges(edit)
        if (changes.isEmpty()) {
            Toast.makeText(requireContext(), R.string.lsp_rename_no_changes, Toast.LENGTH_LONG).show()
            return
        }
        val totalEdits = changes.values.sumOf { it.size }
        val fileList = changes.entries.joinToString("\n") { (uri, edits) ->
            "• ${uriToDisplayName(uri)} (${edits.size})"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.lsp_rename_preview_title)
            .setMessage(getString(R.string.lsp_rename_preview_message, changes.size, totalEdits, fileList))
            .setPositiveButton(R.string.lsp_rename_apply) { _, _ -> applyLspRenameEdits(changes) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun uriToDisplayName(fileUriString: String): String = try {
        File(URI(fileUriString)).name
    } catch (_: Exception) {
        fileUriString
    }

    private fun applyLspRenameEdits(changes: Map<String, List<TextEdit>>) {
        // Resuelve TODOS los archivos a un URI SAF primero, antes de escribir ninguno (ver KDoc
        // de handleLspRenameResult sobre por qué es "todo o nada").
        data class ResolvedFile(val safUri: Uri, val displayName: String, val originalContent: String, val edits: List<TextEdit>)

        val resolved = mutableListOf<ResolvedFile>()
        for ((fileUriString, edits) in changes) {
            val realPath = try {
                File(URI(fileUriString)).absolutePath
            } catch (_: Exception) {
                null
            }
            val safUri = realPath?.let { resolveUriFromRealPath(it) }
            val content = safUri?.let { readTextFromUriSilently(it) }
            if (safUri == null || content == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.lsp_rename_partial, 0, changes.size, fileUriString),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            resolved.add(ResolvedFile(safUri, DocumentFile.fromSingleUri(requireContext(), safUri)?.name ?: fileUriString, content, edits))
        }

        var appliedCount = 0
        for (file in resolved) {
            try {
                val newContent = applyTextEditsToContent(file.originalContent, file.edits)
                writeTextToUri(file.safUri, newContent)
                tabsController.openOrSelect(file.safUri, file.displayName, newContent)
                appliedCount++
            } catch (error: Exception) {
                Toast.makeText(requireContext(), getString(R.string.lsp_rename_error, error.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
        if (appliedCount == resolved.size) {
            Toast.makeText(requireContext(), getString(R.string.lsp_rename_applied, appliedCount), Toast.LENGTH_LONG).show()
        }
    }

    /** Menú de la toolbar del IDE — el equivalente de `onOptionsItemSelected` de la Activity
     * original, ahora como listener del `MaterialToolbar` (en un Fragment no hay options menu de
     * Activity). Misma estructura: iconos visibles + overflow. */
    private fun handleMenuItem(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_file -> {
                openFileLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                true
            }
            R.id.action_open_folder -> {
                openFolderLauncher.launch(null)
                true
            }
            R.id.action_new_project -> {
                showNewProjectDialog()
                true
            }
            R.id.action_recent_projects -> {
                showRecentProjectsDialog()
                true
            }
            R.id.action_save_file -> {
                saveCurrentFile()
                true
            }
            R.id.action_search_in_file -> {
                searchController.show()
                true
            }
            R.id.action_search_in_project -> {
                openProjectSearch()
                true
            }
            R.id.action_ai_settings -> {
                startActivity(Intent(requireContext(), AiProviderSettingsActivity::class.java))
                true
            }
            R.id.action_editor_settings -> {
                startActivity(Intent(requireContext(), EditorSettingsActivity::class.java))
                true
            }
            R.id.action_studio_theme -> {
                showStudioThemePicker()
                true
            }
            R.id.action_terminal -> {
                startActivity(Intent(requireContext(), TerminalActivity::class.java))
                true
            }
            R.id.action_build_apk -> {
                openBuildScreen()
                true
            }
            R.id.action_git -> {
                openGitPanel()
                true
            }
            R.id.action_ask_ai -> {
                showAskAiDialog()
                true
            }
            R.id.action_command_palette -> {
                showCommandPalette()
                true
            }
            else -> false
        }
    }

    // ── Atajos de teclado físico/Bluetooth — ver com.termux.app.ui.studio.keyboard ───────────────

    /** Único punto de entrada para atajos Ctrl+<tecla> de teclado físico/Bluetooth. A diferencia
     * de la Activity original (que override `onKeyShortcut`), un Fragment no recibe ese hook —
     * TermuxActivity reenvía cada `KeyEvent` desde su `dispatchKeyEvent` (cuando este fragment
     * es el tab activo) a este método. La interpretación real vive en [KeyboardShortcutHandler]
     * (clase separada); acá solo se resuelve la acción y se delega al método [CommandHost]
     * equivalente (mismos métodos que ya usa [CommandPaletteDialog]/Ctrl+P). */
    fun handleKeyShortcut(event: KeyEvent): Boolean {
        if (!isAdded || !::tabsController.isInitialized || !::searchController.isInitialized) {
            return false
        }
        val action = KeyboardShortcutHandler.resolve(event) ?: return false
        return handleKeyboardShortcut(action)
    }

    private fun handleKeyboardShortcut(action: KeyboardShortcutAction): Boolean {
        when (action) {
            KeyboardShortcutAction.SAVE_FILE -> onSaveFile()
            KeyboardShortcutAction.SEARCH_IN_FILE -> onSearchInFile()
            KeyboardShortcutAction.SEARCH_IN_PROJECT -> onSearchInProject()
            KeyboardShortcutAction.CLOSE_TAB -> tabsController.closeActiveTab()
            KeyboardShortcutAction.NEXT_TAB -> tabsController.selectNextTab()
            KeyboardShortcutAction.PREVIOUS_TAB -> tabsController.selectPreviousTab()
            KeyboardShortcutAction.NEW_FILE -> onNewFile()
            KeyboardShortcutAction.OPEN_FILE -> onOpenFile()
            KeyboardShortcutAction.COMMAND_PALETTE -> showCommandPalette()
        }
        return true
    }

    private fun showCommandPalette() {
        CommandPaletteDialog.show(requireContext(), CommandRegistry.buildCommands(this))
    }

    override fun onSaveFile() = saveCurrentFile()
    override fun onOpenFile() { openFileLauncher.launch(arrayOf("text/*", "application/octet-stream")) }
    override fun onOpenFolder() { openFolderLauncher.launch(null) }
    override fun onNewFile() = showNewEntryDialogAtRoot(isDirectory = false)
    override fun onNewProject() = showNewProjectDialog()
    override fun onRecentProjects() = showRecentProjectsDialog()
    override fun onSearchInFile() = searchController.show()
    override fun onSearchInProject() = openProjectSearch()
    override fun onBuildApk() = openBuildScreen()
    override fun onTerminal() { startActivity(Intent(requireContext(), TerminalActivity::class.java)) }
    override fun onGitPanel() = openGitPanel()
    override fun onEditorSettings() { startActivity(Intent(requireContext(), EditorSettingsActivity::class.java)) }
    override fun onAiSettings() { startActivity(Intent(requireContext(), AiProviderSettingsActivity::class.java)) }
    override fun onAskAi() = showAskAiDialog()

    private fun openFile(uri: Uri) {
        val content = readTextFromUri(uri) ?: return
        // ACTION_OPEN_DOCUMENT (a diferencia de ACTION_OPEN_DOCUMENT_TREE) no persiste el
        // permiso automáticamente entre reinicios de la app — hay que pedirlo explícito para
        // que la restauración de sesión (restoreSessionIfAny) pueda reabrir este archivo suelto
        // más adelante. Puede fallar en proveedores que no soportan permisos persistibles
        // (poco común, pero no todos los ContentProvider lo garantizan) — no es fatal para abrir
        // el archivo ahora, solo afecta si sobrevive a un reinicio.
        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        currentFileUri = uri
        tabsController.openOrSelect(uri, uri.lastPathSegment ?: uri.toString(), content)
    }

    private fun openFileFromTree(node: FileNode) {
        val content = readTextFromUri(node.document.uri) ?: return
        currentFileUri = node.document.uri
        tabsController.openOrSelect(node.document.uri, node.name, content)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
        } catch (error: Exception) {
            Toast.makeText(requireContext(), "No se pudo abrir el archivo: ${error.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    /** [showToast]=false para restauración automática de sesión ([restoreSessionIfAny], que
     * corre en cada [onViewCreated] — es decir, cada vez que el fragment se recrea al volver a
     * la pestaña de Estudio desde otro módulo del BottomNavigationView) — es un re-abrir
     * silencioso de la MISMA carpeta que ya estaba abierta, no una acción nueva del usuario.
     * Bug real reportado 2026-08-20: "si tengo un proyecto en el ide y entro o salgo de un
     * modulo ... sale una notificacion de que esta abierta la carpeta en ide" — el Toast
     * "Carpeta abierta: ..." se disparaba en cada recreación del fragment, no solo al elegir
     * una carpeta a propósito (SAF picker / "Proyectos recientes"), que siguen pasando
     * [showToast]=true (valor por defecto). */
    private fun openFolder(treeUri: Uri, showToast: Boolean = true) {
        // "Abrir carpeta" es un punto de "empezar de cero" (limpia el árbol, las pestañas
        // recientes vía sesión, etc.) — un modificador sticky LOCKED de la carpeta anterior no
        // debería seguir pegado en el proyecto nuevo (auditoría 2026-08-19, sección 2.2).
        if (::binding.isInitialized) binding.virtualKeyboardBar.releaseAllModifiers()
        requireContext().contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val folder = DocumentFile.fromTreeUri(requireContext(), treeUri) ?: return
        currentProjectPath = resolveRealPathFromTreeUri(treeUri)
        currentProjectTreeUri = treeUri
        rootFolder = folder

        val displayName = folder.name ?: treeUri.toString()
        recentProjectsManager.recordOpened(treeUri, displayName)

        binding.sidebarInclude.sidebarProjectName.text = displayName
        binding.sidebarInclude.sidebarEmptyLabel.visibility = View.GONE
        binding.sidebarInclude.sidebarFilterInput.setText("")
        FileTreeLoader.loadChildren(folder, depth = 0) { children ->
            fileTreeAdapter.setRootChildren(children)
            binding.sidebarInclude.sidebarEmptyLabel.visibility =
                if (children.isEmpty()) View.VISIBLE else View.GONE
        }

        if (showToast) {
            Toast.makeText(
                requireContext(),
                "Carpeta abierta: $displayName",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── Multi-proyecto: sesiones abiertas (ver StudioSession, SessionStateManager) ─────────

    /** Punto de entrada real para "abrir" un proyecto desde cualquier flujo (menú "Abrir
     * carpeta", "Proyectos recientes", plantilla de proyecto nuevo) — a diferencia de
     * [openFolder] (que solo carga el árbol/sidebar de un URI ya conocido en la UI viva, sin
     * tocar la lista de sesiones), esto decide si [treeUri] ya es una sesión abierta (y solo
     * cambia a ella, ver [switchToSession]) o si hay que crear una sesión nueva. */
    private fun openProjectAsSession(treeUri: Uri, showToast: Boolean = true) {
        val existingIndex = sessions.indexOfFirst { it.treeUri == treeUri }
        if (existingIndex >= 0) {
            switchToSession(existingIndex)
            return
        }
        if (sessions.isNotEmpty()) {
            captureActiveSessionTabState()
            tabsController.saveAllDirtyTabs()
        }
        if (sessions.size >= MAX_SESSIONS) {
            // Sin UI de "elegir cuál cerrar" en esta fase (ver plan §2 punto 5, límite de
            // MAX_SESSIONS) — se cierra la sesión menos reciente (la primera de la lista) para
            // hacer lugar. Su estado de pestañas ya quedó guardado en el objeto StudioSession
            // (aunque se descarte de la lista en memoria), así que no hay pérdida real más allá
            // de la limitación ya documentada en SessionStateManager (contenido sin guardar).
            sessions.removeAt(0)
            if (activeSessionIndex > 0) activeSessionIndex--
        }
        tabsController.closeAllTabsSilently()
        val folder = DocumentFile.fromTreeUri(requireContext(), treeUri)
        val session = StudioSession(
            id = treeUri.toString(),
            treeUri = treeUri,
            projectPath = null,
            displayName = folder?.name ?: treeUri.toString()
        )
        sessions.add(session)
        activeSessionIndex = sessions.size - 1
        openFolder(treeUri, showToast)
        session.projectPath = currentProjectPath
        updateSessionChipsUi()
    }

    /** Cambia la sesión activa a la de índice [targetIndex] — guarda (auto-save, sin diálogo,
     * ver [EditorTabsController.saveAllDirtyTabs]) y congela el estado de pestañas de la sesión
     * que se deja, cierra sus pestañas en pantalla, y reabre el árbol + pestañas de la sesión
     * destino. No-op si ya es la sesión activa o el índice no existe. */
    private fun switchToSession(targetIndex: Int) {
        if (targetIndex == activeSessionIndex || targetIndex !in sessions.indices) return
        if (activeSessionIndex in sessions.indices) captureActiveSessionTabState()
        tabsController.saveAllDirtyTabs()
        tabsController.closeAllTabsSilently()
        activeSessionIndex = targetIndex
        val session = sessions[targetIndex]
        openFolder(session.treeUri, showToast = false)
        restoreTabsForSession(session)
        updateSessionChipsUi()
    }

    /** Cierra por completo la sesión de índice [index] (no solo sus pestañas — la saca de
     * [sessions]) — usado por el botón "✕" de un chip del selector de proyectos. Si era la
     * sesión activa, guarda sus pestañas dirty primero y deja el IDE en la sesión vecina (o
     * vacío si era la única). */
    private fun closeSession(index: Int) {
        if (index !in sessions.indices) return
        val wasActive = index == activeSessionIndex
        if (wasActive) {
            tabsController.saveAllDirtyTabs()
            tabsController.closeAllTabsSilently()
        }
        sessions.removeAt(index)
        when {
            sessions.isEmpty() -> {
                activeSessionIndex = -1
                currentProjectPath = null
                currentProjectTreeUri = null
                rootFolder = null
                binding.sidebarInclude.sidebarProjectName.text = ""
                binding.sidebarInclude.sidebarEmptyLabel.visibility = View.VISIBLE
                fileTreeAdapter.setRootChildren(emptyList())
            }
            wasActive -> {
                val newIndex = index.coerceAtMost(sessions.size - 1)
                activeSessionIndex = -1 // fuerza a switchToSession a no hacer early-return
                switchToSession(newIndex)
            }
            index < activeSessionIndex -> activeSessionIndex--
        }
        updateSessionChipsUi()
    }

    /** Puebla el selector de proyectos (`project_session_row`) — solo visible con 2+ sesiones
     * abiertas (con una sola sesión, el nombre del sidebar ya alcanza, ver
     * `studio_layout_sidebar.xml`). Reconstruye los chips desde cero cada vez — la cantidad es
     * chica (máximo [MAX_SESSIONS]), no vale la pena un adapter con diffing. */
    private fun updateSessionChipsUi() {
        if (!::binding.isInitialized) return
        binding.projectSessionChips.removeAllViews()
        if (sessions.size <= 1) {
            binding.projectSessionRow.visibility = View.GONE
            return
        }
        binding.projectSessionRow.visibility = View.VISIBLE
        sessions.forEachIndexed { index, session ->
            val chip = LayoutInflater.from(requireContext())
                .inflate(R.layout.studio_item_project_chip, binding.projectSessionChips, false)
            chip.findViewById<android.widget.TextView>(R.id.chip_project_name).text = session.displayName
            chip.alpha = if (index == activeSessionIndex) 1.0f else 0.55f
            chip.setOnClickListener { switchToSession(index) }
            chip.findViewById<View>(R.id.chip_close_button).setOnClickListener { closeSession(index) }
            binding.projectSessionChips.addView(chip)
        }
    }

    // ── Operaciones de archivo del árbol: crear, renombrar, eliminar, copiar ruta ──────────
    // Long-press en una fila o tap en su ícono "⋮" (ver FileTreeAdapter.onContextMenuRequested).

    /** Menú contextual de una fila del árbol. No se restringe "Nuevo archivo/carpeta" a nodos
     * directorio: sobre un archivo crea un hermano en el mismo directorio padre (patrón común
     * de IDE — click derecho en un archivo también permite crear al lado), igual que sobre una
     * carpeta crea dentro de ella. */
    private fun showFileContextMenu(node: FileNode, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.studio_menu_file_tree_context, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_tree_new_file -> { showNewEntryDialogNear(node, isDirectory = false); true }
                R.id.action_tree_new_folder -> { showNewEntryDialogNear(node, isDirectory = true); true }
                R.id.action_tree_rename -> { showRenameDialog(node); true }
                R.id.action_tree_delete -> { confirmDelete(node); true }
                R.id.action_tree_copy_path -> { copyPathToClipboard(node); true }
                else -> false
            }
        }
        popup.show()
    }

    /** Carpeta SAF donde crear un nuevo archivo/carpeta "cerca" de [node]: dentro de [node] si es
     * una carpeta, o en su directorio padre si es un archivo (hermano). `DocumentFile.parentFile`
     * es la vía general (funciona para cualquier documento de un árbol SAF, no solo los que ya
     * cacheamos como [FileNode]); [rootFolder] es el último fallback para hijos directos de la
     * raíz, donde `parentFile` en teoría ya debería resolver lo mismo. */
    private fun contextTargetDir(node: FileNode): DocumentFile? =
        if (node.isDirectory) node.document else (node.parent?.document ?: node.document.parentFile ?: rootFolder)

    /** [FileNode] cuyo subárbol hay que recargar tras crear algo "cerca" de [node] — null
     * significa "la raíz del proyecto" (recarga completa vía [refreshSubtree]). */
    private fun contextTargetDirNode(node: FileNode): FileNode? = if (node.isDirectory) node else node.parent

    private fun showNewEntryDialogNear(node: FileNode, isDirectory: Boolean) {
        val targetDir = contextTargetDir(node)
        if (targetDir == null) {
            Toast.makeText(requireContext(), R.string.filetree_error_no_project, Toast.LENGTH_SHORT).show()
            return
        }
        showNewEntryDialog(targetDir, contextTargetDirNode(node), isDirectory)
    }

    private fun showNewEntryDialogAtRoot(isDirectory: Boolean) {
        val root = rootFolder
        if (root == null) {
            Toast.makeText(requireContext(), R.string.filetree_error_no_project, Toast.LENGTH_SHORT).show()
            return
        }
        showNewEntryDialog(root, null, isDirectory)
    }

    private fun showNewEntryDialog(targetDir: DocumentFile, refreshNode: FileNode?, isDirectory: Boolean) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.filetree_name_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (isDirectory) R.string.filetree_new_folder_title else R.string.filetree_new_file_title)
            .setView(input)
            .setPositiveButton(R.string.filetree_create_ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) createEntry(targetDir, refreshNode, isDirectory, name)
            }
            .setNegativeButton(R.string.filetree_cancel, null)
            .show()
    }

    /** `"application/octet-stream"` para archivos nuevos: es el mime type más neutro para que
     * `DocumentsContract`/el proveedor SAF no le agregue/cambie la extensión del nombre pedido
     * (comportamiento real pero no garantizado por contrato público — algunos proveedores de
     * documentos no estándar podrían normalizar el nombre igual; a verificar en dispositivo
     * real si un nombre como "Main.kt" apareciera con una extensión distinta). */
    private fun createEntry(targetDir: DocumentFile, refreshNode: FileNode?, isDirectory: Boolean, name: String) {
        try {
            val created = if (isDirectory) {
                targetDir.createDirectory(name)
            } else {
                targetDir.createFile("application/octet-stream", name)
            }
            if (created == null) {
                Toast.makeText(requireContext(), getString(R.string.filetree_error_create, "SAF devolvió null"), Toast.LENGTH_LONG).show()
                return
            }
            refreshSubtree(refreshNode)
        } catch (error: Exception) {
            Toast.makeText(requireContext(), getString(R.string.filetree_error_create, error.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun showRenameDialog(node: FileNode) {
        val input = EditText(requireContext()).apply {
            setText(node.name)
            setSelection(node.name.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.filetree_rename_title)
            .setView(input)
            .setPositiveButton(R.string.filetree_rename_ok) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && newName != node.name) renameNode(node, newName)
            }
            .setNegativeButton(R.string.filetree_cancel, null)
            .show()
    }

    /** `TreeDocumentFile.renameTo` (implementación real de AndroidX) reasigna el `Uri` interno
     * del mismo objeto `DocumentFile` tras un rename exitoso — por eso [FileNode.document] sigue
     * siendo válido después de esta llamada sin reconstruirlo, y alcanza con comparar contra el
     * URI viejo capturado antes de llamar para saber si había una pestaña abierta apuntando ahí. */
    private fun renameNode(node: FileNode, newName: String) {
        val oldUri = node.document.uri
        try {
            val success = node.document.renameTo(newName)
            if (!success) {
                Toast.makeText(requireContext(), getString(R.string.filetree_error_rename, "SAF rechazó el rename"), Toast.LENGTH_LONG).show()
                return
            }
            if (currentFileUri == oldUri) {
                currentFileUri = node.document.uri
            }
            refreshParentOf(node)
        } catch (error: Exception) {
            Toast.makeText(requireContext(), getString(R.string.filetree_error_rename, error.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(node: FileNode) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.filetree_delete_title, node.name))
            .setMessage(getString(R.string.filetree_delete_message, node.name))
            .setPositiveButton(R.string.filetree_delete_ok) { _, _ -> deleteNode(node) }
            .setNegativeButton(R.string.filetree_cancel, null)
            .show()
    }

    private fun deleteNode(node: FileNode) {
        try {
            val success = node.document.delete()
            if (!success) {
                Toast.makeText(requireContext(), getString(R.string.filetree_error_delete, "SAF rechazó el borrado"), Toast.LENGTH_LONG).show()
                return
            }
            refreshParentOf(node)
        } catch (error: Exception) {
            Toast.makeText(requireContext(), getString(R.string.filetree_error_delete, error.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    /** Copia el `content://` URI del documento o, cuando se puede resolver (volumen primario,
     * mismo caso cubierto por [resolveRealPathFromTreeUri]), una ruta de filesystem legible —
     * mismo best-effort: en volúmenes secundarios o proveedores no estándar cae al URI SAF tal
     * cual, que sigue siendo información útil aunque no sea una ruta de shell usable. */
    private fun copyPathToClipboard(node: FileNode) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(node.name, resolveDisplayPath(node.document.uri)))
        Toast.makeText(requireContext(), R.string.filetree_path_copied, Toast.LENGTH_SHORT).show()
    }

    private fun resolveDisplayPath(uri: Uri): String {
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            null
        }
        val parts = documentId?.split(":", limit = 2)
        if (parts != null && parts.size == 2 && parts[0] == "primary") {
            val root = Environment.getExternalStorageDirectory()
            return if (parts[1].isEmpty()) root.absolutePath else File(root, parts[1]).absolutePath
        }
        return uri.toString()
    }

    /** Recarga solo el subárbol de [node] (o la raíz completa si es null) — usado tras crear
     * dentro de un directorio. Para renombrar/eliminar, ver [refreshParentOf] (recarga el padre,
     * porque el nodo afectado ya no existe con ese nombre/existencia). */
    private fun refreshSubtree(node: FileNode?) {
        if (node == null) {
            val root = rootFolder ?: return
            FileTreeLoader.loadChildren(root, depth = 0) { children ->
                fileTreeAdapter.setRootChildren(children)
                binding.sidebarInclude.sidebarEmptyLabel.visibility =
                    if (children.isEmpty()) View.VISIBLE else View.GONE
            }
            return
        }
        FileTreeLoader.loadChildren(node.document, node.depth + 1, node) { children ->
            fileTreeAdapter.refreshNode(node, children)
        }
    }

    private fun refreshParentOf(node: FileNode) = refreshSubtree(node.parent)

    // ── Proyectos recientes ─────────────────────────────────────────────────────────────

    private fun showRecentProjectsDialog() {
        val projects = recentProjectsManager.getRecentProjects()
        if (projects.isEmpty()) {
            Toast.makeText(requireContext(), R.string.recent_projects_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        val labels = projects.map { project ->
            "${project.name}\n${dateFormat.format(java.util.Date(project.lastAccessedMillis))}"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.recent_projects_title)
            .setItems(labels) { _, which -> openRecentProject(projects[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openRecentProject(project: RecentProjectsManager.RecentProject) {
        try {
            openProjectAsSession(project.uri)
        } catch (error: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.recent_projects_open_error, error.message ?: project.uri.toString()),
                Toast.LENGTH_LONG
            ).show()
            // El permiso SAF ya no es válido (revocado, o el volumen ya no está disponible) —
            // dejarlo en la lista solo confundiría con un item que siempre falla.
            recentProjectsManager.remove(project.uri)
        }
    }

    // ── Nuevo proyecto ──────────────────────────────────────────────────────────────────

    private fun showNewProjectDialog() {
        val options = arrayOf(
            getString(R.string.new_project_empty_folder),
            getString(R.string.new_project_android_template)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.new_project_title)
            .setItems(options) { _, which ->
                pendingAndroidTemplate = which == 1
                openFolderLauncher.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Plantilla mínima "Proyecto Android vacío" — no un módulo Gradle completo compilable
     * (faltaría el wrapper, `settings.gradle`, res/, etc.), solo lo justo para arrancar a mano
     * un proyecto nuevo sin escribir los dos archivos más repetitivos desde cero. Un wizard de
     * plantillas más completo queda pendiente (ver MEJORAS_PENDIENTES.md). */
    private fun generateAndroidTemplate(treeUri: Uri) {
        val folder = DocumentFile.fromTreeUri(requireContext(), treeUri) ?: return
        try {
            createFileIfMissing(folder, "AndroidManifest.xml", "text/xml", MINIMAL_ANDROID_MANIFEST)
            createFileIfMissing(folder, "build.gradle", "text/x-groovy", MINIMAL_BUILD_GRADLE)
            FileTreeLoader.loadChildren(folder, depth = 0) { children ->
                fileTreeAdapter.setRootChildren(children)
                binding.sidebarInclude.sidebarEmptyLabel.visibility =
                    if (children.isEmpty()) View.VISIBLE else View.GONE
            }
            Toast.makeText(requireContext(), R.string.new_project_template_created, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.new_project_template_error, error.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun createFileIfMissing(folder: DocumentFile, fileName: String, mimeType: String, content: String) {
        if (folder.findFile(fileName) != null) return
        val file = folder.createFile(mimeType, fileName) ?: return
        writeTextToUri(file.uri, content)
    }

    /**
     * Best-effort: resuelve la ruta real de filesystem de un árbol SAF abierto en el volumen de
     * almacenamiento primario (`content://com.android.externalstorage.documents/tree/primary:...`),
     * que es el caso común en la enorme mayoría de dispositivos. No hay API pública garantizada
     * para esto — Android por diseño no promete rutas reales para URIs SAF — así que en
     * volúmenes secundarios (SD externa, USB) o proveedores no estándar esto devuelve null y el
     * build se bloquea con [R.string.build_error_no_real_path] en vez de fallar en silencio.
     */
    private fun resolveRealPathFromTreeUri(treeUri: Uri): String? {
        val documentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (error: Exception) {
            return null
        }
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2 || parts[0] != "primary") return null
        val relativePath = parts[1]
        val root = Environment.getExternalStorageDirectory()
        val resolved = if (relativePath.isEmpty()) root else File(root, relativePath)
        return if (resolved.exists()) resolved.absolutePath else null
    }

    private fun openBuildScreen() {
        val path = currentProjectPath
        if (path == null) {
            Toast.makeText(requireContext(), getString(R.string.build_error_no_real_path), Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(requireContext(), BuildLogActivity::class.java)
        intent.putExtra(BuildLogActivity.EXTRA_PROJECT_PATH, path)
        buildLogLauncher.launch(intent)
    }

    /** Resuelve el resultado de un diagnóstico de build clickeado ([buildLogLauncher]) — [filePath]
     * es una ruta de FILESYSTEM real (la que ve el proceso `gradlew` corriendo dentro de Termux
     * vía [com.termux.app.ui.studio.termux.TermuxBridge]), no un URI SAF, así que hay que
     * resolverla primero contra el árbol abierto ([resolveUriFromRealPath]) antes de poder
     * abrirla con [openFileAtLine]. */
    private fun openDiagnosticFile(filePath: String, line: Int, column: Int) {
        val uri = resolveUriFromRealPath(filePath)
        if (uri == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.build_diagnostic_file_not_found, filePath),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        openFileAtLine(uri, line, column)
    }

    /** Inverso de [resolveRealPathFromTreeUri] — dada una ruta de filesystem real bajo el
     * almacenamiento primario, reconstruye el URI SAF `content://` equivalente contra el árbol
     * del proyecto abierto. Mismo alcance/limitación que la función que invierte (solo volumen
     * primario, `content://com.android.externalstorage.documents/tree/primary:...`) — en
     * volúmenes secundarios o si [filePath] cae fuera del proyecto abierto, devuelve `null` y el
     * caller ([openDiagnosticFile]) avisa en vez de fallar en silencio. */
    private fun resolveUriFromRealPath(filePath: String): Uri? {
        val treeUri = currentProjectTreeUri ?: return null
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val normalized = filePath.trimEnd('/')
        if (!normalized.startsWith(root)) return null
        val relative = normalized.removePrefix(root).trimStart('/')
        val documentId = if (relative.isEmpty()) "primary:" else "primary:$relative"
        return try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        } catch (_: Exception) {
            null
        }
    }

    private fun openGitPanel() {
        val path = currentProjectPath
        if (path == null) {
            Toast.makeText(requireContext(), getString(R.string.build_error_no_real_path), Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(requireContext(), GitPanelActivity::class.java)
        intent.putExtra(GitPanelActivity.EXTRA_PROJECT_PATH, path)
        startActivity(intent)
    }

    // ── Búsqueda en todo el proyecto (ver com.termux.app.ui.studio.search.ProjectSearchActivity) ────

    private fun openProjectSearch() {
        val treeUri = currentProjectTreeUri
        if (treeUri == null) {
            Toast.makeText(requireContext(), R.string.project_search_no_project, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(requireContext(), ProjectSearchActivity::class.java)
        intent.putExtra(ProjectSearchActivity.EXTRA_PROJECT_TREE_URI, treeUri)
        projectSearchLauncher.launch(intent)
    }

    /** Abre [uri] (creando o reusando su pestaña, igual que [openFile]) y posiciona el cursor en
     * [lineNumber] (1-based, como lo reporta [com.termux.app.ui.studio.search.ProjectSearchEngine]). Se
     * agenda con `post` porque [EditorTabsController.openOrSelect] puede disparar el cambio de
     * pestaña de forma asincrónica al seleccionar el `TabLayout.Tab` recién creado — sin el
     * `post`, `setSelection` podría correr antes de que el editor termine de recibir el texto
     * nuevo. No probado contra un archivo de miles de líneas en dispositivo real. */
    private fun openFileAtLine(uri: Uri, lineNumber: Int, column: Int = 1) {
        val content = readTextFromUri(uri) ?: return
        val displayName = DocumentFile.fromSingleUri(requireContext(), uri)?.name ?: uri.lastPathSegment ?: uri.toString()
        currentFileUri = uri
        tabsController.openOrSelect(uri, displayName, content)
        binding.codeEditor.post {
            val targetLine = (lineNumber - 1).coerceIn(0, (binding.codeEditor.text.lineCount - 1).coerceAtLeast(0))
            // La columna reportada por un diagnóstico de build (ver CompilerOutputParser) puede
            // no ser válida si el editor recibió un contenido distinto al que vio el compilador
            // (archivo editado después del build) — setSelection con una columna fuera de rango
            // puede lanzar IndexOutOfBoundsException en sora-editor, así que se intenta con la
            // columna real primero y se cae a la columna 0 de la línea si falla.
            try {
                binding.codeEditor.setSelection(targetLine, (column - 1).coerceAtLeast(0))
            } catch (_: Exception) {
                binding.codeEditor.setSelection(targetLine, 0)
            }
            binding.codeEditor.ensureSelectionVisible()
        }
    }

    private fun saveCurrentFile() {
        val uri = currentFileUri ?: return
        tabsController.captureActiveEditorText()
        try {
            writeTextToUri(uri, binding.codeEditor.text.toString())
            tabsController.markActiveFileSaved()
        } catch (error: Exception) {
            Toast.makeText(requireContext(), "No se pudo guardar: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeTextToUri(uri: Uri, content: String) {
        requireContext().contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            OutputStreamWriter(stream).use { it.write(content) }
        }
    }

    // ── Preguntar a la IA sobre el código (ver com.termux.app.ui.studio.ai.AiClient) ─────────────

    /** Cuántos caracteres del archivo/selección se mandan como contexto — igual que un
     *  código fuente grande completo puede tardar minutos o superar límites de tokens de
     *  proveedores cloud, se recorta con el mismo criterio pragmático que ChatFragment.kt
     *  usa para el detalle de errores HTTP (take(N)). */
    private val maxCodeContextChars = 12_000

    private fun showAskAiDialog() {
        val questionInput = EditText(requireContext()).apply {
            hint = getString(R.string.ai_ask_hint)
            minLines = 2
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ai_ask_title)
            .setView(questionInput)
            .setPositiveButton(R.string.ai_ask_send) { _, _ ->
                val question = questionInput.text?.toString()?.trim().orEmpty()
                if (question.isNotBlank()) askAiAboutCode(question)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** Selección actual del editor si hay una, o el contenido completo del archivo abierto. */
    private fun selectedOrFullEditorText(): String {
        val editor = binding.codeEditor
        return try {
            val cursor = editor.cursor
            if (cursor.isSelected) {
                editor.text.subSequence(cursor.left, cursor.right).toString()
            } else {
                editor.text.toString()
            }
        } catch (_: Exception) {
            editor.text.toString()
        }
    }

    private fun askAiAboutCode(question: String) {
        val code = selectedOrFullEditorText()
        if (code.isBlank()) {
            Toast.makeText(requireContext(), R.string.ai_no_code, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), R.string.ai_asking, Toast.LENGTH_SHORT).show()
        val prefs = AiProviderPrefs(requireContext())
        val prompt = buildString {
            append("Código:\n```\n")
            append(code.take(maxCodeContextChars))
            append("\n```\n\nPregunta sobre este código: ")
            append(question)
        }
        Thread {
            val result = AiClient.askWithPrefs(prefs, prompt)
            requireActivity().runOnUiThread { showAiResult(result) }
        }.start()
    }

    private fun showAiResult(result: AiResult) {
        if (!isAdded) return
        when (result) {
            is AiResult.Success -> AlertDialog.Builder(requireContext())
                .setTitle(R.string.ai_result_title)
                .setMessage(result.text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            is AiResult.Error -> AlertDialog.Builder(requireContext())
                .setTitle(R.string.ai_error_title)
                .setMessage(result.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    companion object {
        /** Máximo de proyectos abiertos simultáneamente (ver
         * `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §2 punto 5) — cada sesión mantiene
         * su propio árbol de archivos + pestañas; no validado con `dumpsys meminfo` en
         * dispositivo real todavía, 3 es el límite ya propuesto en el plan, no una medición. */
        private const val MAX_SESSIONS = 3

        // Plantilla mínima para "Nuevo proyecto → Proyecto Android vacío" (ver
        // generateAndroidTemplate). Intencionalmente incompleta — falta gradle/wrapper,
        // settings.gradle, res/ — solo ahorra los dos archivos más repetitivos al arrancar un
        // proyecto nuevo a mano.
        private const val MINIMAL_ANDROID_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
"""

        private const val MINIMAL_BUILD_GRADLE = """plugins {
    id 'com.android.application'
}

android {
    namespace 'com.example.app'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.app"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
}
"""
    }
}