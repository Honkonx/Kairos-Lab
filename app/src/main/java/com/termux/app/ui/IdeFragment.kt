package com.termux.app.ui

import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.TERMUX_PREFIX_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.showProjectsMenu

/**
 * Pantalla dedicada para el módulo "ide" (Neovim + NvChad + Copilot + CodeCompanion —
 * modulos/ide.sh, puerta de entrada `nvim`). Antes caía en GenericModuleFragment (solo
 * "Abrir en terminal" + "Gestionar proyectos", sin abrir nvim ya posicionado en un proyecto ni
 * ningún diagnóstico). Wireada en ModuleDetailNavigator.kt como "ide" -> IdeFragment().
 *
 * Comandos confirmados (no inventados — ver comentarios de cada botón):
 *  - `nvim` a secas: puerta de entrada real, confirmada en la cabecera de modulos/ide.sh
 *    ("CLI RESULTANTE: nvim → abre el IDE (NvChad)").
 *  - `nvim --headless -c "checkhealth" -c "qa!"`: :checkhealth es interactivo por defecto
 *    (abre un buffer flotante) — en modo --headless Neovim no tiene UI, así que el output de
 *    los comandos pasados con -c va a stderr en vez de imprimirse en un buffer visible
 *    (comportamiento documentado y confirmado, ver neovim/neovim#27084 "Command line usage
 *    does not print into the standard output"). Por eso se redirige stderr a un archivo
 *    temporal en vez de intentar mostrar un buffer que nunca se renderiza sin UI.
 *  - `nvim --headless "+Lazy! sync" +qa`: exactamente el mismo comando que ya usa
 *    modulos/ide.sh (PASO 2, línea "Sincronizando plugins") para instalar/actualizar los
 *    plugins de NvChad la primera vez — reusar la sintaxis real del propio script en vez de
 *    adivinar una nueva.
 */
class IdeFragment : BaseModuleFragment() {
    override fun getModuleId() = "ide"
    override fun getModuleName() = getString(R.string.ide_module_name)

    override fun buildContent() {
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) {
                installModuleInBackground(null) { ok ->
                    if (ok) {
                        toast(getString(R.string.ide_toast_installed))
                        container.removeAllViews()
                        buildContent()
                    } else {
                        toast(getString(R.string.ide_toast_install_failed))
                    }
                }
            }
            return
        }

        addCard(getString(R.string.ide_card_estado)) {
            addView(infoRow(getString(R.string.ide_row_editor_label), getString(R.string.ide_row_editor_value)))
            addView(infoRow(getString(R.string.ide_row_framework_label), getString(R.string.ide_row_framework_value)))
            addView(infoRow(getString(R.string.ide_row_ia_label), getString(R.string.ide_row_ia_value)))
        }

        // "nvim" a secas: confirmado en la cabecera de modulos/ide.sh ("CLI RESULTANTE:
        // nvim → abre el IDE (NvChad)"). Sin proyecto elegido, abre en el directorio actual
        // (home) — mismo comportamiento de cualquier editor de terminal.
        // Mockup aprobado por el usuario 2026-08-26 (ver
        // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md), extendido a "todos los CLI" tras
        // corrección explícita del usuario — "Abrir proyecto en nvim" de abajo solo lista
        // proyectos YA importados a ~/proyectos, esto permite elegir cualquier carpeta del
        // almacenamiento sin pasar por ese paso.
        actionButton(getString(R.string.ide_btn_abrir_aqui), GHOST) {
            promptOpenLocation(
                onDefault = { launchTerminalCommand("nvim") },
                onChooseFolder = { path -> launchTerminalCommand("cd '$path' && nvim .") }
            )
        }

        // Abre un proyecto elegido/creado con el mismo patrón symlink/copiar que el resto de
        // los CLIs (showProjectsMenu, ver util/ProjectActions.kt) y arranca nvim DENTRO de esa
        // carpeta — mismo mecanismo "cd '$path' && <cli>" que usa HermesFragment/OpenClawFragment.
        actionButton(getString(R.string.ide_btn_abrir_proyecto), GHOST) {
            showProjectsMenu(
                onToast = { toast(it) },
                onLaunchInProject = { path -> launchTerminalCommand("cd '$path' && nvim .") }
            )
        }

        actionButton(getString(R.string.ide_btn_gestionar_proyectos), GHOST) {
            showProjectsMenu(onToast = { toast(it) })
        }

        // Diagnóstico headless — ver docstring de la clase para la justificación completa del
        // comando. Corre en background (puede tardar unos segundos) y muestra el resultado en
        // un diálogo con scroll en vez de abrir la terminal, porque --headless nunca dibuja
        // nada en pantalla.
        actionButton(getString(R.string.ide_btn_checkhealth), GHOST) {
            runCheckhealth()
        }

        // "nvim --headless \"+Lazy! sync\" +qa": mismo comando literal que modulos/ide.sh usa
        // en su propia instalación (PASO 2) para bajar/actualizar los plugins de NvChad —
        // reinvocarlo a mano sincroniza plugins nuevos/actualizados del lockfile de NvChad sin
        // tener que reinstalar todo el módulo.
        actionButton(getString(R.string.ide_btn_lazy_sync), GHOST) {
            toast(getString(R.string.ide_toast_sync_plugins))
            launchTerminalCommand("nvim --headless \"+Lazy! sync\" +qa")
        }

        actionButton(getString(R.string.ide_btn_reinstalar), GHOST) { reinstall() }
    }

    private fun runCheckhealth() {
        if (!isAdded) return
        toast(getString(R.string.ide_toast_checkhealth))
        val outputFile = "$TERMUX_PREFIX_PATH/tmp/kairos_ide_checkhealth.txt"
        Thread {
            val report = try {
                val pb = ProcessBuilder(
                    TERMUX_BASH_PATH, "-c",
                    "nvim --headless -c 'checkhealth' -c 'qa!' > '$outputFile' 2>&1"
                )
                pb.applyTermuxEnv()
                val process = pb.start()
                process.waitFor()
                val file = java.io.File(outputFile)
                if (file.exists()) file.readText() else getString(R.string.ide_checkhealth_sin_reporte)
            } catch (e: Exception) {
                getString(R.string.ide_checkhealth_error, e.message ?: "")
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                showCheckhealthResult(report)
            }
        }.start()
    }

    private fun showCheckhealthResult(report: String) {
        val ctx = requireContext()
        val textView = TextView(ctx).apply {
            text = report.ifBlank { getString(R.string.ide_checkhealth_sin_salida) }
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(ctx).apply { addView(textView) }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.ide_dialog_checkhealth_title))
            .setView(scroll)
            .setPositiveButton(getString(R.string.ide_dialog_cerrar), null)
            .show()
    }

    private fun reinstall() {
        toast(getString(R.string.ide_toast_reinstalando))
        reinstallModuleService { ok ->
            toast(if (ok) getString(R.string.ide_toast_actualizado) else getString(R.string.ide_toast_fallo))
        }
    }
}
