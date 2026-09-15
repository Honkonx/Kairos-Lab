package com.termux.app.ui.studio.palette

import com.termux.R

/**
 * Todas las acciones reales de `MainActivity` que tiene sentido exponer como comando de la
 * paleta (Ctrl+P) — un método por acción, implementado por `MainActivity` delegando a su propio
 * handler privado existente (mismo código que ya corre desde `menu_main.xml`/`onOptionsItemSelected`,
 * ver `MainActivity.onOptionsItemSelected`). No se inventó ningún comando nuevo: cada método de
 * acá tiene una entrada equivalente ya real en el menú principal.
 */
interface CommandHost {
    fun onSaveFile()
    fun onOpenFile()
    fun onOpenFolder()
    fun onNewFile()
    fun onNewProject()
    fun onRecentProjects()
    fun onSearchInFile()
    fun onSearchInProject()
    fun onBuildApk()
    fun onTerminal()
    fun onGitPanel()
    fun onEditorSettings()
    fun onAiSettings()
    fun onAskAi()
    /** Selector rápido de tema de Estudio (independiente del tema del apk, ver
     * `StudioThemePrefs.kt`) — faltaba en la paleta pese a ya estar en el menú overflow desde
     * `docs/humano/humano202.md`; agregado en la reorganización del menú "⋮" del 2026-09-03. */
    fun onStudioTheme()

    /** Señales de estado para calcular [Command.isEnabled] — agregadas en la ronda 2026-09-01
     * (ver comentario de [Command]). Solo lo mínimo que ya existe en `StudioFragment` como
     * estado real, sin inventar un `ActionContext` genérico nuevo. */
    fun hasOpenFile(): Boolean
    fun hasOpenProject(): Boolean
    fun hasGitRepo(): Boolean
}

/**
 * Registro simple de comandos: arma la lista fija que muestra [CommandPaletteDialog], atada a un
 * [CommandHost] concreto (en la práctica, siempre `MainActivity`). No hay estado propio ni
 * remapeo de teclas de usuario en esta ronda — ver [Command] para el detalle de qué se dejó
 * afuera a propósito respecto del patrón de referencia.
 */
object CommandRegistry {

    fun buildCommands(host: CommandHost): List<Command> {
        val hasOpenFile = host.hasOpenFile()
        val hasOpenProject = host.hasOpenProject()
        val hasGitRepo = host.hasGitRepo()

        return listOf(
            Command("save_file", "Guardar archivo", R.drawable.studio_ic_save, isEnabled = hasOpenFile) { host.onSaveFile() },
            Command("open_file", "Abrir archivo", R.drawable.studio_ic_file_generic) { host.onOpenFile() },
            Command("open_folder", "Abrir carpeta", R.drawable.studio_ic_folder) { host.onOpenFolder() },
            Command("new_file", "Nuevo archivo", R.drawable.studio_ic_add, isEnabled = hasOpenProject) { host.onNewFile() },
            Command("new_project", "Nuevo proyecto", R.drawable.studio_ic_add) { host.onNewProject() },
            Command("recent_projects", "Proyectos recientes", R.drawable.studio_ic_folder_open) { host.onRecentProjects() },
            Command("search_in_file", "Buscar en archivo", R.drawable.studio_ic_search, isEnabled = hasOpenFile) { host.onSearchInFile() },
            Command("search_in_project", "Buscar en proyecto", R.drawable.studio_ic_search, isEnabled = hasOpenProject) { host.onSearchInProject() },
            Command("build_apk", "Build APK", R.drawable.studio_ic_build, isEnabled = hasOpenProject) { host.onBuildApk() },
            Command("terminal", "Terminal", R.drawable.studio_ic_terminal) { host.onTerminal() },
            Command("git_panel", "Panel Git", R.drawable.studio_ic_more_vert, isEnabled = hasOpenProject && hasGitRepo) { host.onGitPanel() },
            Command("editor_settings", "Configuración del editor", R.drawable.studio_ic_more_vert) { host.onEditorSettings() },
            Command("ai_settings", "Configuración de IA", R.drawable.studio_ic_more_vert) { host.onAiSettings() },
            Command("ask_ai", "Preguntar a la IA sobre este código", R.drawable.studio_ic_more_vert, isEnabled = hasOpenFile) { host.onAskAi() },
            Command("studio_theme", "Tema de Estudio", R.drawable.studio_ic_more_vert) { host.onStudioTheme() }
        )
    }

    /** Filtro case-insensitive por título — misma idea que `FileTreeAdapter.setFilter`, sin
     * duplicar su código porque ese filtra [com.termux.app.ui.studio.filetree.FileNode], no [Command]. */
    fun filter(commands: List<Command>, query: String): List<Command> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return commands
        return commands.filter { it.title.contains(trimmed, ignoreCase = true) }
    }
}
