package com.termux.app.ui.studio.lsp

/**
 * Catálogo MVP de language servers soportados por el autocompletado LSP de Estudio (ver
 * [StudioLspController] y `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §LSP).
 *
 * Solo 2 lenguajes en esta ronda — los que más se editan en Kairos: los scripts .sh de `modulos`
 * (Bash) y los módulos Python del propio proyecto. Ambos instalables vía gestores de paquete que
 * Kairos ya soporta como módulos propios (`nodejs.sh`/`python.sh`), así que no se agrega ninguna
 * dependencia nueva de infraestructura — solo un paquete npm/pip encima de un runtime que el
 * usuario probablemente ya tiene si está editando ese tipo de archivo.
 *
 * Kotlin/Java (el lenguaje de la propia Kairos) quedó deliberadamente FUERA de este MVP: no hay
 * un language server de Kotlin/Java liviano e instalable vía `pkg`/npm/pip en Termux sin JDK
 * completo + Gradle project model (kotlin-language-server necesita compilar el proyecto entero
 * para dar completado útil) — validar eso es un esfuerzo propio, no cabe en esta ronda.
 */
enum class LspLanguageServer(
    /** Id corto — también el nombre que la librería `editor-lsp` usa como `serverName` al
     * registrar el [io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.LanguageServerDefinition]. */
    val id: String,
    val displayName: String,
    /** Extensiones de archivo (sin punto, minúscula) que este server cubre. */
    val extensions: Set<String>,
    /** Nombre del binario a resolver por PATH una vez instalado (ver
     * [com.termux.app.util.isTermuxBinaryAvailable]) — el mismo nombre es el comando real. */
    val binaryName: String,
    /** Comando de arranque completo, pasado tal cual a `bash -c "..."` (ver
     * [TermuxLspProcessConnection]) — modo stdio por default en ambos servers de este catálogo. */
    val startCommand: String,
    /** Comando de instalación silenciosa (npm/pip), corrido vía `bash -c "..."` con el mismo
     * entorno de Termux que el resto de la app ([com.termux.app.util.applyTermuxEnv]). */
    val installCommand: String,
    /** Binario(s) prerequisito que deben existir ANTES de poder instalar/correr este server (el
     * runtime del lenguaje del propio server, no del archivo que se está editando). */
    val runtimeBinaries: List<String>,
    /** Módulo de Kairos que instala ese runtime — para el mensaje al usuario cuando falta. */
    val runtimeModuleHint: String,
) {
    BASH(
        id = "bash-language-server",
        displayName = "Bash Language Server",
        extensions = setOf("sh", "bash"),
        binaryName = "bash-language-server",
        startCommand = "bash-language-server start",
        installCommand = "npm install -g bash-language-server",
        runtimeBinaries = listOf("node", "npm"),
        runtimeModuleHint = "Node.js (módulo \"nodejs\")",
    ),
    PYTHON(
        id = "pylsp",
        displayName = "Python LSP Server",
        extensions = setOf("py", "pyw"),
        binaryName = "pylsp",
        startCommand = "pylsp",
        // --user: instala en $HOME/.local/bin, ya incluido en el PATH de
        // com.termux.app.util.applyTermuxEnv() (ver comentario ahí sobre el bug real de Claude
        // Code con el mismo patrón de instalación).
        installCommand = "pip install --user python-lsp-server",
        runtimeBinaries = listOf("python3", "pip"),
        runtimeModuleHint = "Python (módulo \"python\")",
    );

    companion object {
        fun forExtension(extension: String): LspLanguageServer? {
            val ext = extension.lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}
