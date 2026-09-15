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
     * valor guardado en [com.termux.app.ui.studio.settings.EditorPrefs.editorTheme].
     *
     * "dracula" agregado 2026-08-31 (auditoría `referencia/ides/android-code-studio-dev`, ver
     * `docs/referencias/ides/REFERENCIA_ANDROID_CODE_STUDIO.md`): ese fork de AndroidIDE trae 8
     * schemes de sora-editor (formato propio, no TextMate) — la paleta real de "Dracula"
     * (`editor/impl/.../schemes/dracula-dark/default-dark.json`, colores hexadecimales) se
     * adaptó a mano al formato TextMate JSON que ya usa Kairos (`dracula.json`, mismo esquema de
     * scopes que `kairos-ink.json`) — no es una copia del archivo GPLv3 de ACS, solo referencia
     * de paleta (Dracula es un theme público conocido, draculatheme.com).
     *
     * 10 temas más agregados 2026-09-01 (auditoría `referencia/terminal/stdusk`, ver
     * `docs/referencias/terminal/REFERENCIA_STDUSK.md`): `stdusk/assets/schemes/` trae ~200
     * esquemas de color pero en formato Xresources plano (`*.foreground`/`*.background`/16
     * colores ANSI `*.color0`..`*.color15`) — **no** trae datos por scope de sintaxis
     * (keyword/string/comment/tipo/etc.), que es lo que un theme TextMate real necesita. Una
     * conversión mecánica 1:1 de 16 colores ANSI a scopes de sintaxis da resultados de baja
     * calidad (ej. mapear "color1" a keyword sin más contexto ignora que cada theme real tiene
     * convenciones propias de qué scope usa qué acento). En vez de eso, para estos 10 (Nord,
     * Gruvbox Dark/Light, Solarized Dark/Light, Monokai, One Dark, Atom One Light, Ayu Dark/Light
     * — todos presentes en el catálogo de `stdusk` con esos nombres o variantes cercanas:
     * "Atom"→One Dark, "AtomOneLight", "ayu"/"ayu_light", "Monokai Soda"→Monokai) se tomó
     * background/foreground/cursor real del archivo de `stdusk` (coincide con el theme público)
     * y se completaron los scopes de sintaxis con la paleta oficial/ampliamente publicada de cada
     * theme (Nord: nordtheme.com; Gruvbox: morhetz/gruvbox; Solarized: ethanschoonover.com/solarized;
     * Monokai: paleta clásica; One Dark/Atom One Light: atom-one-*-vscode; Ayu: ayu-theme) — mismo
     * criterio que ya se usó para "dracula" arriba, no una copia de ningún archivo de terceros. */
    val AVAILABLE_THEMES = listOf(
        "kairos-ink", "kairos-paper", "kairos-contrast", "dracula",
        "nord", "gruvbox-dark", "gruvbox-light", "solarized-dark", "solarized-light",
        "monokai", "one-dark", "atom-one-light", "ayu-dark", "ayu-light"
    )
    const val DEFAULT_THEME = "kairos-ink"

    /**
     * Extensión de archivo (sin punto, minúscula) -> scope TextMate del grammar bundleado.
     *
     * `css`/`html`/`htm`/`yaml`/`yml` agregados 2026-08-31 (auditoría fresca de
     * `referencia/ides/CodeAssist-main`/`Xed-Editor-main`, ver `docs/referencias/ides/`): los
     * grammars `textmate/css/` y `textmate/html/` ya estaban bundleados en
     * `app/src/main/assets/textmate/` y registrados en `textmate/languages.json`
     * (`scopeName: "source.css"` / `"text.html.basic"`) desde 2026-08-01, pero esta tabla nunca
     * los mapeaba — un `.css`/`.html`/`.yaml` abierto en Estudio caía silenciosamente a texto
     * plano ([EmptyLanguage]) pese a tener el grammar real disponible en el APK. `yaml`/`yml`
     * tenía el mismo gap pese a que el editor de archivos simple ([com.termux.app.ui.EditorFragment],
     * ahora migrado a usar esta misma tabla) ya los mapeaba antes.
     */
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
        "markdown" to "text.html.markdown",
        "css" to "source.css",
        "html" to "text.html.basic",
        "htm" to "text.html.basic",
        "yaml" to "source.yaml",
        "yml" to "source.yaml"
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
