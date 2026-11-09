package com.termux.app.util

import com.termux.R

/**
 * module.id -> drawable vectorial real. Reemplaza los glyphs Unicode que se
 * usaban antes como ícono (ej. "⬡"/"∞" en modules.json) — usados tanto en la
 * lista de módulos (ModuleListAdapter) como en la hoja de instalación
 * (BottomSheetInstalacion).
 */
object ModuleIcons {
    private val ICONS = mapOf(
        "ollama" to R.drawable.ic_module_ollama,
        "n8n" to R.drawable.ic_module_n8n,
        "python" to R.drawable.ic_module_python,
        "claude" to R.drawable.ic_module_claude,
        "codex" to R.drawable.ic_module_codex,
        "antigravity" to R.drawable.ic_module_antigravity,
        "openclaw" to R.drawable.ic_module_openclaw,
        "opencode" to R.drawable.ic_module_opencode,
        "hermes" to R.drawable.ic_module_hermes,
        "remote" to R.drawable.ic_module_remote,
        "expo" to R.drawable.ic_module_expo,
        "entorno" to R.drawable.ic_module_entorno,
        "engram" to R.drawable.ic_module_engram,
        // Glifos propios generados por tools/gen_icons_modulos.py (2026-10-08, pedido del usuario):
        "git" to R.drawable.ic_module_git,
        "gh" to R.drawable.ic_module_gh,
        "freebuff" to R.drawable.ic_module_freebuff,
        "codebuff" to R.drawable.ic_module_codebuff,
        "mimocode" to R.drawable.ic_module_mimocode,
        "stacks" to R.drawable.ic_module_stacks,
        "llamaserver" to R.drawable.ic_module_llamaserver,
        "ide" to R.drawable.ic_module_ide,
        "kilo" to R.drawable.ic_module_kilo,
        "cursor" to R.drawable.ic_module_cursor,
        "verificar" to R.drawable.ic_module_verificar,
        "ruby" to R.drawable.ic_module_ruby,
        "kotlin" to R.drawable.ic_module_kotlin,
        "clang" to R.drawable.ic_module_clang,
        "livesrv" to R.drawable.ic_module_livesrv,
        "localtunnel" to R.drawable.ic_module_localtunnel,
        "markserv" to R.drawable.ic_module_markserv,
        "psqlformat" to R.drawable.ic_module_psqlformat,
        "ncu" to R.drawable.ic_module_ncu,
        "languages" to R.drawable.ic_module_languages,
        "packages" to R.drawable.ic_module_packages,
        "codegraph" to R.drawable.ic_module_codegraph,
        "gitea" to R.drawable.ic_module_gitea,
        "media-downloader" to R.drawable.ic_module_media_downloader,
        "copilotcli" to R.drawable.ic_module_copilotcli,
        "minimaxcli" to R.drawable.ic_module_minimaxcli,
        "qwencode" to R.drawable.ic_module_qwencode,
        "ciberseguridad" to R.drawable.ic_module_ciberseguridad,
        "db" to R.drawable.ic_module_db,
        "udocker" to R.drawable.ic_module_udocker,
        "docker" to R.drawable.ic_module_docker,
        "qemu" to R.drawable.ic_module_qemu,
        "cactus" to R.drawable.ic_module_cactus,
        "apk" to R.drawable.ic_module_apk,
        "kimi" to R.drawable.ic_module_kimi,
        "hf" to R.drawable.ic_module_hf,
        "repo" to R.drawable.ic_module_repo,
        "nodejs" to R.drawable.ic_module_nodejs,
        "perl" to R.drawable.ic_module_perl,
        "php" to R.drawable.ic_module_php,
        "rust" to R.drawable.ic_module_rust,
        "golang" to R.drawable.ic_module_golang,
        "typescript" to R.drawable.ic_module_typescript,
        "nestjs" to R.drawable.ic_module_nestjs,
        "prettier" to R.drawable.ic_module_prettier,
        "vercel" to R.drawable.ic_module_vercel,
        "ngrok" to R.drawable.ic_module_ngrok,
        "pi" to R.drawable.ic_module_pi,
        "ohmypi" to R.drawable.ic_module_ohmypi,
    )

    // Bug real confirmado en dispositivo (auditoría ADB 2026-08-22,,
    // "muchos módulos tienen el logo de Ollama"): 25/57 módulos (44%) no tienen `iconAsset` en
    // modules.json ni entrada acá en ICONS — caían en el logo ESPECÍFICO de Ollama como
    // fallback, mostrando la marca de un módulo no relacionado (Freebuff, Entornos de Prueba,
    // Verificación, etc.). Fallback cambiado a un ícono genérico neutral de "módulo" en vez de
    // reusar la marca de otro módulo real.
    fun forModule(moduleId: String): Int = ICONS[moduleId] ?: R.drawable.ic_module_generic

    // Módulos cuyo `iconAsset` (ver ModuleInfo.kt) apunta a un PNG/JPG de marca real en
    // drawable-nodpi (logo multicolor, ej. Hugging Face naranja, Kimi) en vez de un
    // VectorDrawable de un solo trazo blanco (ver docs/arquitectura/
    // AUDITORIA_ICONOS_MODULOS_2026-08-19.md). Los adapters usan este set para NO aplicarles
    // el tint blanco fijo que sí aplican los VectorDrawable de un solo color — tintar de
    // blanco un logo a color lo reduce a una silueta plana y rompe la marca.
    val RASTER_MODULE_IDS = setOf(
        "codex", "opencode", "cactus", "kimi", "qwencode", "minimaxcli",
        "copilotcli", "hf", "ohmypi", "udocker"
    )
}
