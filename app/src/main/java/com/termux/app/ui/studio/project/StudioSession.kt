package com.termux.app.ui.studio.project

import android.net.Uri

/**
 * Un proyecto abierto en Estudio — soporte multi-proyecto (ver
 * `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §2, implementado 2026-08-26). Antes
 * `StudioFragment` sostenía un único proyecto vía 2 campos sueltos
 * (`currentProjectPath`/`currentProjectTreeUri`); ahora esos campos siguen existiendo como
 * espejo del proyecto ACTIVO (el resto del código de `StudioFragment` — build, git, búsqueda —
 * los sigue usando tal cual), pero la lista de sesiones vive acá.
 *
 * [openTabs]/[activeTabUri] son una FOTO del estado de pestañas al momento de cambiar a otra
 * sesión (ver `StudioFragment.captureActiveSessionTabState`) — cuando la sesión vuelve a estar
 * activa, se releen desde disco vía el URI SAF (mismo criterio que ya usaba
 * [SessionStateManager] para la sesión única: nunca se guarda contenido de archivo en memoria
 * "de fondo", solo identidad).
 */
data class StudioSession(
    val id: String,
    var treeUri: Uri,
    var projectPath: String?,
    var displayName: String,
    var openTabs: MutableList<SessionStateManager.TabState> = mutableListOf(),
    var activeTabUri: Uri? = null
)
