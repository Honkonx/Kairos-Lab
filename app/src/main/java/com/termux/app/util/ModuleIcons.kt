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
        "engram" to R.drawable.ic_module_engram
    )

    // Bug real confirmado en dispositivo (auditoría ADB 2026-08-22, ver docs/humano/humano187.md,
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
