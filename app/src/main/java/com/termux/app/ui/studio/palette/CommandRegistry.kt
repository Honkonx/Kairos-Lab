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
}

/**
 * Registro simple de comandos: arma la lista fija que muestra [CommandPaletteDialog], atada a un
 * [CommandHost] concreto (en la práctica, siempre `MainActivity`). No hay estado propio ni
 * remapeo de teclas de usuario en esta ronda — ver [Command] para el detalle de qué se dejó
 * afuera a propósito respecto del patrón de referencia.
 */
object CommandRegistry {

    fun buildCommands(host: CommandHost): List<Command> = listOf(
        Command("save_file", "Guardar archivo", R.drawable.studio_ic_save) { host.onSaveFile() },
        Command("open_file", "Abrir archivo", R.drawable.studio_ic_file_generic) { host.onOpenFile() },
        Command("open_folder", "Abrir carpeta", R.drawable.studio_ic_folder) { host.onOpenFolder() },
        Command("new_file", "Nuevo archivo", R.drawable.studio_ic_add) { host.onNewFile() },
        Command("new_project", "Nuevo proyecto", R.drawable.studio_ic_add) { host.onNewProject() },
        Command("recent_projects", "Proyectos recientes", R.drawable.studio_ic_folder_open) { host.onRecentProjects() },
        Command("search_in_file", "Buscar en archivo", R.drawable.studio_ic_search) { host.onSearchInFile() },
        Command("search_in_project", "Buscar en proyecto", R.drawable.studio_ic_search) { host.onSearchInProject() },
        Command("build_apk", "Build APK", R.drawable.studio_ic_build) { host.onBuildApk() },
        Command("terminal", "Terminal", R.drawable.studio_ic_terminal) { host.onTerminal() },
        Command("git_panel", "Panel Git", R.drawable.studio_ic_more_vert) { host.onGitPanel() },
        Command("editor_settings", "Configuración del editor", R.drawable.studio_ic_more_vert) { host.onEditorSettings() },
        Command("ai_settings", "Configuración de IA", R.drawable.studio_ic_more_vert) { host.onAiSettings() },
        Command("ask_ai", "Preguntar a la IA sobre este código", R.drawable.studio_ic_more_vert) { host.onAskAi() }
    )

    /** Filtro case-insensitive por título — misma idea que `FileTreeAdapter.setFilter`, sin
     * duplicar su código porque ese filtra [com.termux.app.ui.studio.filetree.FileNode], no [Command]. */
    fun filter(commands: List<Command>, query: String): List<Command> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return commands
        return commands.filter { it.title.contains(trimmed, ignoreCase = true) }
    }
}
