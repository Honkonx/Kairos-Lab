package com.termux.app.ui.studio.palette

import androidx.annotation.DrawableRes

/**
 * Un comando registrado en la paleta de comandos (Ctrl+P) — envoltorio liviano sobre una acción
 * que ya existe en otro lado de la app (típicamente un método privado de `MainActivity`, expuesto
 * acá vía [CommandHost] en [CommandRegistry]). A diferencia del patrón de referencia auditado en
 * `referencia/ides/Xed-Editor-main` (`com.rk.commands.Command`, con `ActionContext`,
 * `childCommands`, `isEnabled`/`isSupported` dinámicos, remapeo de teclas por usuario), esta
 * versión es intencionalmente mínima para esta ronda: sin submenús, sin habilitación condicional,
 * sin persistencia de keybinds custom — solo id + título + ícono opcional + acción. Ver
 * `docs/ide/IDE_EXTERNO.md` Sección 7 para el detalle de la auditoría.
 *
 * @param id identificador estable del comando (no se muestra en UI, útil para tests/logs futuros).
 * @param title título visible en la lista de la paleta, y lo único contra lo que se filtra.
 * @param iconRes ícono opcional mostrado a la izquierda del título; `null` no dibuja ícono.
 * @param action acción a ejecutar al tocar el ítem — la paleta la invoca y se cierra.
 */
data class Command(
    val id: String,
    val title: String,
    @DrawableRes val iconRes: Int? = null,
    val action: () -> Unit
)
