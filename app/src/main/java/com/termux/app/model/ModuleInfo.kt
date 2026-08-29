package com.termux.app.model

data class ModuleInfo(
    val id: String,
    val name: String,
    val icon: String,
    val iconBg: String,
    val port: String,
    // Nombre del script de instalación real dentro de scripts/install/ (ej. "ollama.sh",
    // o "ssh.sh" para el módulo "remote"). Fuente de verdad única desde la auditoría
    // 2026-08-19 (ver docs/arquitectura/AUDITORIA_EXTENSIBILIDAD_MODULOS_2026-08-19.md
    // sección 2.d) — antes ModuleController.installScriptFile() calculaba este nombre a
    // mano con la convención "$moduleId.sh" + un caso especial hardcodeado para "remote",
    // duplicando esta misma info que modules.json ya trae en el campo "script".
    val script: String = "",
    val description: String = "",
    val size: String = "",
    val type: String = "",
    val requiresProot: Boolean = false,
    val hasVariants: Boolean = false,
    val estimate: String = "",
    val hasSwitch: Boolean = true,
    val tmuxSession: String = "",
    val webviewUrl: String = "",
    val terminalCommand: String = "",
    // Campos del sistema de plugins (Fase B, 2026-08-10 — ver
    // docs/arquitectura/PLAN.md "Sistema de plugins / Tienda de módulos"):
    // arch = bionic | glibc | proot | distro (con qué libc/entorno corre el binario).
    // category = texto libre, NO un enum cerrado — solo se usa como badge/búsqueda
    //   (PluginListAdapter.badge(), PluginsFragment búsqueda por substring), nunca se
    //   valida ni se filtra contra una lista fija de valores. Confirmado contra
    //   modules.json real (auditoría 2026-08-19): valores en uso hoy son
    //   ai | dev | db | lang | seguridad | system | tools — la lista original de este
    //   comentario (ai/dev/db/editor/lang/npm/shell/ui/auto) nunca fue una validación
    //   real, quedó desactualizada apenas se agregaron módulos con categorías nuevas.
    // catalogVersion = versión CONOCIDA del catálogo (la instalada vive en el registry,
    //   <id>.version) — la usa la Tienda para mostrar "actualización disponible".
    // installMethods = métodos de instalación soportados (["glibc","proot-glibc","proot-distro"],
    //   patrón freebuff de core-termux). requires = paquetes base que necesita.
    // recommended = true → aparece en la sección "Recomendados" arriba de la Tienda.
    // downloads = contador de instalaciones/descargas para ordenar recomendados por
    //   popularidad (fuente del catálogo, no un tracking de la app).
    val arch: String = "",
    val category: String = "",
    val catalogVersion: String = "",
    val installMethods: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val recommended: Boolean = false,
    val downloads: Int = 0,
    // internal = true → módulo NO se muestra en la Tienda (PluginsFragment) ni en la
    // pantalla Módulos (ModulesFragment) como entrada propia — vive consolidado dentro de
    // otro módulo con switches internos (ej. los 16 lenguajes/paquetes npm consolidados en
    // "languages"/"packages", ver docs/modulos/LENGUAJES.md, ronda 2026-08-18). El script
    // real sigue existiendo tal cual en modulos/<id>.sh — solo cambia CÓMO se presenta, no
    // cómo se instala. Distinto de "hidden" (ProjectsManager.hiddenModuleIds(), estado del
    // usuario en runtime para ocultar un módulo YA instalado del home) — a propósito, para
    // no confundir "el catálogo no lo lista" con "el usuario lo desactivó".
    val internal: Boolean = false,
    // hideFromCatalog = true → módulo NO aparece listado en la Tienda (PluginsFragment) ni
    // en la pantalla Módulos (ModulesFragment), pero SIGUE siendo un módulo real con su
    // propio catálogo/entrada en modules.json — a diferencia de `internal` (arriba), que
    // consolida un módulo DENTRO de otro (languages/packages). Pensado para "entorno"
    // (Mini PC): sigue siendo navegable directo (ModuleDetailNavigator, accesos desde otras
    // pantallas) pero no debe listarse como una card más del catálogo (2026-08-26, ver
    // docs/mini-pc/MINIPC_TAB_2026-08-25.md). Default false: no rompe los ~57 módulos que no
    // traen este campo en modules.json todavía.
    val hideFromCatalog: Boolean = false,
    // Nombre (sin extensión) de un drawable real embebido en res/drawable o
    // res/drawable-nodpi (ej. "ic_module_python") para los módulos con logo oficial ya
    // confirmado (ver docs/arquitectura/AUDITORIA_ICONOS_MODULOS_2026-08-19.md). Cuando es
    // null, el módulo sigue usando el ícono/fallback que ya tenía (ModuleIcons.forModule())
    // sin ningún cambio de comportamiento — no confundir con `icon` (glyph/emoji legado).
    val iconAsset: String? = null
) {
    enum class Status {
        NOT_INSTALLED,
        INSTALLED_STOPPED,
        RUNNING,
        INSTALLING,
        ERROR
    }
}
