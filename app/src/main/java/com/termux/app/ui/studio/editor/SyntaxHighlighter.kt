package com.termux.app.ui.studio.editor

import android.content.Context
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * Resaltado de sintaxis real por lenguaje, vía TextMate grammars (`io.github.rosemoe:language-textmate`,
 * mismo BOM 0.24.4 que ya usa [EditorSchemeSetup] para el editor base).
 *
 * API confirmada contra el AAR real del build (`language-textmate-0.24.4.aar` /
 * `editor-0.24.4.aar` del caché de Gradle), decompilado con un script Python (no había `javap`
 * disponible en esta PC) para leer las firmas reales del bytecode:
 * - `GrammarRegistry.getInstance().loadGrammars(String jsonPath)`
 * - `TextMateLanguage.create(String scopeName, boolean createIdentifiers): TextMateLanguage`
 * - `TextMateColorScheme.create(ThemeModel): TextMateColorScheme`
 * - `ThemeRegistry.getInstance().loadTheme(ThemeModel)`
 * - `ThemeRegistry.getInstance().setTheme(ThemeModel)` — marca un `ThemeModel` ya cargado como el
 *   "current" del registry (re-confirmado en esta ronda decompilando el mismo AAR: constant pool
 *   de `ThemeRegistry.class` tiene `setTheme` con firma `(Lio/github/.../model/ThemeModel;)V`,
 *   distinto del `setTheme(String)` de otros forks de sora-editor — acá es por instancia de modelo)
 * - `ThemeModel(IThemeSource)` (constructor de un solo argumento)
 * - `EmptyLanguage()` (constructor público sin argumentos, para archivos sin grammar bundleado)
 *
 * El patrón de llamada (orden de inicialización: registrar `AssetsFileResolver`, cargar
 * `languages.json`, cargar el theme, recién ahí crear el `TextMateColorScheme`) se contrastó
 * contra el uso real en `referencia/ides/Xed-Editor-main` (`LanguageManager.kt`, `Editor.kt`,
 * `ThemeManager.kt`, `XedColorScheme.kt` — GPLv3, solo se leyó el patrón de invocación de la
 * librería; ningún código Kotlin de ese proyecto fue copiado a este archivo).
 *
 * Grammars bundleados en `assets/textmate/` — ver `assets/textmate/README.md` para la fuente y
 * licencia exacta de cada uno (todos datos MIT/permisivos vía las extensiones oficiales de VS
 * Code, adaptados desde la carpeta de assets de Xed-Editor).
 */
object SyntaxHighlighter {

    private const val ASSETS_PREFIX = "textmate/"
    private const val LANGUAGES_MANIFEST = ASSETS_PREFIX + "languages.json"
    private const val THEME_DIR = ASSETS_PREFIX + "theme/"

    /** Nombre interno de theme -> archivo `.json` en `assets/textmate/theme/`. La clave es el
     * valor guardado en [com.termux.app.ui.studio.settings.EditorPrefs.editorTheme]. */
    val AVAILABLE_THEMES = listOf("kairos-ink", "kairos-paper", "kairos-contrast")
    const val DEFAULT_THEME = "kairos-ink"

    /** Extensión de archivo (sin punto, minúscula) -> scope TextMate del grammar bundleado. */
    private val EXTENSION_TO_SCOPE = mapOf(
        "kt" to "source.kotlin",
        "kts" to "source.kotlin", // cubre también build.gradle.kts (Gradle Kotlin DSL)
        "java" to "source.java",
        "py" to "source.python",
        "pyw" to "source.python",
        "js" to "source.js",
        "mjs" to "source.js",
        "cjs" to "source.js",
        "jsx" to "source.js.jsx",
        "ts" to "source.ts",
        "tsx" to "source.tsx",
        "xml" to "text.xml",
        "json" to "source.json",
        "gradle" to "source.groovy", // build.gradle clásico (Groovy DSL)
        "groovy" to "source.groovy",
        "sh" to "source.shell",
        "bash" to "source.shell",
        "zsh" to "source.shell",
        "md" to "text.html.markdown",
        "markdown" to "text.html.markdown"
    )

    private var grammarsLoaded = false
    private var currentThemeName = DEFAULT_THEME
    private var colorScheme: TextMateColorScheme? = null

    /** Cache de [ThemeModel] ya leídos de assets, por nombre de theme — evitar releer/reparsear
     * el JSON en cada cambio de theme si el usuario va y vuelve entre los mismos 2-3. */
    private val themeModelCache = HashMap<String, ThemeModel>()

    /** Registra el resolver de assets + carga grammars una única vez por proceso. Todo el trabajo
     * de I/O (leer JSONs de assets) es liviano (archivos de grammar, no modelos), así que se hace
     * síncrono en el hilo llamante — igual que [EditorSchemeSetup.apply], que ya se llama desde
     * `onCreate` sin hilo aparte. */
    @Synchronized
    private fun ensureGrammarsLoaded(context: Context) {
        if (grammarsLoaded) return

        val appContext = context.applicationContext
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(appContext.assets))
        GrammarRegistry.getInstance().loadGrammars(LANGUAGES_MANIFEST)

        grammarsLoaded = true
    }

    /** Carga (o reusa del cache) el [ThemeModel] de [themeName]. Si [themeName] no está en
     * [AVAILABLE_THEMES] cae a [DEFAULT_THEME] — nunca deja el editor sin theme por un valor de
     * preferencia corrupto o de una versión vieja de la app. */
    @Synchronized
    private fun loadThemeModel(context: Context, themeName: String): ThemeModel {
        val resolvedName = if (themeName in AVAILABLE_THEMES) themeName else DEFAULT_THEME
        themeModelCache[resolvedName]?.let { return it }

        val appContext = context.applicationContext
        // Bug real confirmado (crash en TODO arranque de la app, auditoría ADB 2026-08-21/22,
        // ver docs/humano/humano186.md): tm4e's `IThemeSource.guessFileFormat()` adivina el formato
        // del theme a partir de la EXTENSIÓN del nombre que se le pasa acá — pasar el nombre
        // pelado ("kairos-ink", sin ".json") lo hacía fallar con "Unsupported file type:
        // kairos-ink" pese a que el archivo real en assets sí tiene la extensión correcta.
        // Como DEFAULT_THEME = "kairos-ink", esto crasheaba StudioFragment (y por lo tanto
        // TermuxActivity.onStart(), que lo pre-inicializa) en cada arranque de la app.
        val themeFileName = "$resolvedName.json"
        val themeModel = appContext.assets.open(THEME_DIR + themeFileName).use { stream ->
            ThemeModel(IThemeSource.fromInputStream(stream, themeFileName, null))
        }
        ThemeRegistry.getInstance().loadTheme(themeModel)
        themeModelCache[resolvedName] = themeModel
        return themeModel
    }

    /**
     * Aplica el color scheme configurado (ver [setTheme]/[EditorPrefs.editorTheme]) y el grammar
     * TextMate correspondiente a [fileName] (detectado por extensión) sobre [editor]. Si la
     * extensión no tiene grammar bundleado, el editor queda en modo texto plano
     * ([EmptyLanguage]) — mismo comportamiento que antes de esta ronda, no rompe la apertura de
     * archivos sin resaltado conocido.
     */
    fun apply(editor: CodeEditor, fileName: String) {
        ensureGrammarsLoaded(editor.context)
        ensureColorScheme(editor.context)

        colorScheme?.let { editor.colorScheme = it }

        val scope = scopeForFileName(fileName)
        editor.setEditorLanguage(
            if (scope != null) TextMateLanguage.create(scope, true) else EmptyLanguage()
        )
    }

    /**
     * Crea una instancia NUEVA de [TextMateLanguage] para el grammar de [fileName] (por
     * extensión), sin asignarla a ningún editor — usada por
     * [com.termux.app.ui.studio.lsp.StudioLspController] como `wrapperLanguage` de un
     * `LspEditor` de la librería `editor-lsp`. Necesita ser una instancia propia, separada de la
     * que [apply] ya haya aplicado al [CodeEditor] compartido de Estudio — mismo comentario real
     * de `referencia/ides/Xed-Editor-main` sobre esta API: "created identifiers cannot be
     * modified retroactively" (`CodeEditorCompose.kt`, GPLv3 — solo se leyó el patrón de uso, sin
     * copiar código Kotlin de ese proyecto). Devuelve `null` si la extensión no tiene grammar
     * bundleado (mismo criterio que [apply] — el caller simplemente no conecta LSP para ese
     * archivo).
     */
    fun createLanguageFor(fileName: String, context: Context): TextMateLanguage? {
        ensureGrammarsLoaded(context)
        val scope = scopeForFileName(fileName) ?: return null
        return TextMateLanguage.create(scope, true)
    }

    @Synchronized
    private fun ensureColorScheme(context: Context) {
        if (colorScheme != null) return
        val themeModel = loadThemeModel(context, currentThemeName)
        ThemeRegistry.getInstance().setTheme(themeModel)
        colorScheme = TextMateColorScheme.create(themeModel)
    }

    /**
     * Cambia el theme activo en runtime, sin reiniciar la app: recarga el [ThemeRegistry] con
     * [themeName] y reaplica el `colorScheme` resultante sobre [editor] (el editor actualmente
     * visible — si hay múltiples tabs abiertos, cada uno reaplica el theme guardado la próxima
     * vez que [apply] corre sobre él, p. ej. al reabrir la pantalla). No hace falta recrear el
     * grammar del lenguaje — `editor.colorScheme` solo cambia colores, no el tokenizer.
     */
    @Synchronized
    fun setTheme(editor: CodeEditor, themeName: String) {
        val resolvedName = if (themeName in AVAILABLE_THEMES) themeName else DEFAULT_THEME
        ensureGrammarsLoaded(editor.context)

        val themeModel = loadThemeModel(editor.context, resolvedName)
        ThemeRegistry.getInstance().setTheme(themeModel)
        colorScheme = TextMateColorScheme.create(themeModel)
        currentThemeName = resolvedName

        colorScheme?.let { editor.colorScheme = it }
    }

    private fun scopeForFileName(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return EXTENSION_TO_SCOPE[extension]
    }
}
