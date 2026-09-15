package com.termux.app.ui.studio.palette

import androidx.annotation.DrawableRes

/**
 * Un comando registrado en la paleta de comandos (Ctrl+P) — envoltorio liviano sobre una acción
 * que ya existe en otro lado de la app (típicamente un método privado de `MainActivity`, expuesto
 * acá vía [CommandHost] en [CommandRegistry]). A diferencia del patrón de referencia auditado en
 * `referencia/ides/Xed-Editor-main` (`com.rk.commands.Command`, con `ActionContext`,
 * `childCommands`, `isEnabled`/`isSupported` dinámicos, remapeo de teclas por usuario), esta
 * versión sigue siendo intencionalmente mínima: sin submenús, sin persistencia de keybinds
 * custom — solo id + título + ícono opcional + acción. Ver `docs/ide/IDE_EXTERNO.md` Sección 7
 * para el detalle de la auditoría original.
 *
 * [isEnabled] SÍ se agregó (ronda 2026-09-01, auditoría ampliada de `referencia/ides/`
 * — ver `docs/referencias/ides/AUDITORIA_CATEGORIA_IDES.md`): gap real encontrado comparando
 * contra `referencia/ides/android-code-studio-dev` (`core/actions/.../ActionsRegistry.kt`,
 * acciones que se habilitan/deshabilitan según contexto real — hay un archivo abierto, hay un
 * proyecto, hay un repo git) — antes CADA comando de la paleta aparecía siempre habilitado
 * aunque tocarlo fuera a mostrar un Toast de error (ej. "Panel Git" sin ningún proyecto abierto).
 * Sin submenús ni `ActionContext` genérico como el original — solo un booleano calculado por
 * [CommandRegistry.buildCommands] a partir de 3 señales de estado ya expuestas por
 * [CommandHost] (`hasOpenFile`/`hasOpenProject`/`hasGitRepo`), evita el mismo choque contra
 * varios comandos que hoy ya fallan con un Toast — no una acción de "arreglar cada Toast" por
 * separado.
 *
 * @param id identificador estable del comando (no se muestra en UI, útil para tests/logs futuros).
 * @param title título visible en la lista de la paleta, y lo único contra lo que se filtra.
 * @param iconRes ícono opcional mostrado a la izquierda del título; `null` no dibuja ícono.
 * @param isEnabled si es `false`, el ítem se muestra atenuado y tocarlo no hace nada — sigue
 *   visible (no se oculta) para que el usuario entienda que la acción existe pero no aplica ahora.
 * @param action acción a ejecutar al tocar el ítem — la paleta la invoca y se cierra.
 */
data class Command(
    val id: String,
    val title: String,
    @DrawableRes val iconRes: Int? = null,
    val isEnabled: Boolean = true,
    val action: () -> Unit
)
