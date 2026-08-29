package com.termux.app.ui

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.card.MaterialCardView
import com.termux.R
import com.termux.app.util.EntornoNative
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.TERMUX_UDOCKER_PATH
import org.json.JSONObject
import java.io.File
import com.termux.app.util.kairosThemeColor

/**
 * udocker — pantalla propia del módulo (antes caía en GenericModuleFragment, sin más acción
 * que "Abrir en terminal"). Pedido explícito del usuario: sacarle provecho real a udocker
 * como runtime de contenedores de propósito general (no solo la pieza interna que ya usa
 * n8n.sh) — instalar distros reales de Docker Hub (alpine/ubuntu/debian u otra imagen a
 * mano), listar/gestionar los contenedores ya creados, y ver/eliminar las imágenes
 * descargadas.
 *
 * Comandos reales de udocker confirmados antes de usarlos acá (indigo-dc/udocker, ver
 * docs/modulos/UDOCKER.md §8 para la fuente interna + búsqueda de la doc oficial del
 * proyecto real para "rmi", que no aparecía documentado en ese archivo):
 *   - udocker pull <repo/imagen:tag>              → baja la imagen desde Docker Hub
 *   - udocker create --name=<nombre> <imagen>     → crea el contenedor a partir de la imagen
 *   - udocker run <contenedor> [comando]          → corre/entra al contenedor
 *   - udocker setup --execmode=P2 <contenedor>    → fuerza el modo PRoot puro (mismo criterio
 *     que ya usa modulos/udocker.sh y EntornoNative.udockerExecCommand())
 *   - udocker ps -a                                → lista contenedores (usado por
 *     EntornoNative.udockerContainersList()/udockerContainers(), reusado acá tal cual)
 *   - udocker images                               → lista imágenes descargadas
 *   - udocker rm <contenedor>                      → elimina un contenedor
 *   - udocker rmi <repo/imagen:tag>                → elimina una imagen descargada
 *
 * Ampliados 2026-08-19 (3ra ronda de la auditoría, ver docs/arquitectura/
 * AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_OFICIAL_2026-08-19.md sección "Actualización 2") —
 * confirmados contra indigo-dc/udocker doc/user_manual.md:
 *   - udocker inspect <contenedor|imagen>          → metadata JSON (solo lectura)
 *   - udocker export -o <tar> <contenedor>         → exporta el árbol de dirs a .tar
 *   - udocker save -o <tar> <repo/imagen:tag>      → guarda la imagen (con capas) a .tar
 *   - udocker import <tar> <repo/imagen:tag>       → importa un .tar como imagen nueva
 * export/save/import pasan por Storage Access Framework (mismo patrón que ChatFragment/
 * CactusFragment) porque udocker escribe/lee del filesystem real, no de un content:// Uri.
 *
 * No reimplementa nada de EntornoNative.kt (solo se lee/reusa, nunca se modifica desde acá
 * — es compartida con EntornoFragment) — udockerAvailable()/udockerContainers()/
 * udockerContainersList()/udockerExecCommand() se llaman directo. Lo que EntornoNative NO
 * expone (pull/create/rm de contenedor/images/rmi) se corre acá mismo vía
 * ManagerNativeUtils.runExec(), el mismo helper de bajo nivel que ya usa EntornoNative
 * internamente — no hace falta un wrapper nuevo para eso.
 *
 * Migración a strings.xml (2026-08-28, soporte español/inglés) — a diferencia de
 * PackagesFragment, acá todos los strings de usuario viven dentro de funciones miembro (nunca
 * en un `val` con evaluación inmediata), así que `getString()`/`requireContext()` funcionan
 * directo sin necesidad de `by lazy {}`.
 */
class UdockerFragment : BaseModuleFragment() {
    override fun getModuleId() = "udocker"
    override fun getModuleName() = getString(R.string.udocker_module_name)

    private var containersContainer: LinearLayout? = null
    private var imagesContainer: LinearLayout? = null

    // ── Exportar/guardar/importar (auditoría 2026-08-19, 3ra ronda) ─────────────
    // "udocker export"/"udocker save" escriben un .tar real a un path del filesystem — el
    // contenedor de la app (sandbox de Termux, $HOME) no es visible desde fuera sin pasar por
    // Storage Access Framework, mismo patrón que ya usa el resto de Kairos para sacar/meter
    // archivos del sandbox (ver ChatFragment.mPickImageLauncher / CactusFragment.
    // mPickImportFileLauncher, citado en el KDoc de esos fragments). El .tar se genera primero
    // en un archivo temporal dentro del sandbox (vía udocker) y de ahí se copia al Uri elegido
    // por el usuario — udocker no sabe escribir directo a un content:// Uri.
    private var pendingExportContainer: String? = null
    private var pendingSaveImage: String? = null

    private val mExportContainerLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri: Uri? ->
            val container = pendingExportContainer
            pendingExportContainer = null
            if (uri != null && container != null) exportContainerTo(container, uri)
        }

    private val mSaveImageLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri: Uri? ->
            val image = pendingSaveImage
            pendingSaveImage = null
            if (uri != null && image != null) saveImageTo(image, uri)
        }

    private val mImportImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { promptImportImageName(it) }
        }

    override fun buildContent() {
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) {
                installModuleInBackground(null) { ok ->
                    if (ok) {
                        toast(getString(R.string.udocker_toast_installed))
                        refreshView()
                    } else {
                        toast(getString(R.string.udocker_toast_install_failed))
                    }
                }
            }
            return
        }

        addCard(getString(R.string.udocker_card_containers)) {
            containersContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            addView(containersContainer)
        }
        refreshContainers()

        actionButton(getString(R.string.udocker_action_install_distro)) { promptInstallDistro() }
        actionButton(getString(R.string.udocker_action_terminal_container)) { promptTerminalInContainer() }
        actionButton(getString(R.string.udocker_action_import_image)) { mImportImageLauncher.launch("*/*") }

        addCard(getString(R.string.udocker_card_images)) {
            imagesContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            addView(imagesContainer)
        }
        refreshImages()

        // Gap real (auditoría de consistencia de menús 2026-08-19): este módulo no tenía
        // NINGÚN botón de Actualizar/Desinstalar desde su propia pantalla — GenericModuleFragment
        // lo da gratis a cualquier módulo sin pantalla propia. Ver BaseModuleFragment.
        // addMaintenanceCard().
        addMaintenanceCard()
    }

    private fun refreshView() {
        container.removeAllViews()
        buildContent()
    }

    // ── Contenedores ─────────────────────────────────────────────

    /** Reusa EntornoNative.udockerContainersList() (misma función que ya usa la card "📋 Instalado" de Entorno) — no se reimplementa el parseo de "udocker ps -a". */
    private fun refreshContainers() {
        Thread {
            val json = EntornoNative.udockerContainersList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { renderContainers(json) }
        }.start()
    }

    private fun renderContainers(json: JSONObject) {
        val listContainer = containersContainer ?: return
        listContainer.removeAllViews()
        val containers = json.optJSONArray("containers")
        if (!json.optBoolean("ok", false) || containers == null || containers.length() == 0) {
            val message = if (json.optBoolean("ok", false)) getString(R.string.udocker_no_containers) else getString(R.string.udocker_unavailable)
            listContainer.addView(emptyRow(message))
            return
        }
        for (i in 0 until containers.length()) {
            val entry = containers.optJSONObject(i) ?: continue
            listContainer.addView(containerRow(entry))
        }
    }

    private fun containerRow(entry: JSONObject): View {
        val name = entry.optString("name")
        val image = entry.optString("image", "desconocida")
        val running = entry.optString("status") == "corriendo"
        val icon = if (running) "●" else "○"
        val color = requireContext().kairosThemeColor(if (running) R.attr.kairosGreen else R.attr.kairosText2)
        val row = infoRow("$icon $name", image, color)
        row.setOnClickListener { promptContainerActions(name) }
        return row
    }

    private fun promptContainerActions(name: String) {
        val options = arrayOf(
            getString(R.string.udocker_option_open_terminal),
            getString(R.string.udocker_option_inspect),
            getString(R.string.udocker_option_export),
            getString(R.string.udocker_option_delete_container)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptRunOptions(name) { flags ->
                        launchTerminalCommand(buildUdockerRunCommand(name, flags), "udocker/$name")
                    }
                    1 -> inspectContainer(name)
                    2 -> {
                        pendingExportContainer = name
                        mExportContainerLauncher.launch("$name.tar")
                    }
                    3 -> confirmRemoveContainer(name)
                }
            }
            .setNegativeButton(getString(R.string.udocker_button_cancel), null)
            .show()
    }

    /** `udocker inspect <contenedor>` — imprime metadata JSON real del contenedor (imagen base,
     * variables de entorno, comando por defecto, etc., ver indigo-dc/udocker user_manual.md,
     * sección "inspect"). Solo lectura — no muta nada. */
    private fun inspectContainer(name: String) {
        Thread {
            val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "inspect", name), 20)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { showTextDialog(getString(R.string.udocker_inspect_title, name), if (rc == 0) out else (err.ifEmpty { out }).ifEmpty { getString(R.string.udocker_no_output) }) }
        }.start()
    }

    /**
     * `udocker export -o <tar> <contenedor>` — exporta el árbol de directorios del contenedor a
     * un .tar (indigo-dc/udocker user_manual.md, sección "export"). udocker escribe directo a un
     * path del filesystem, no sabe de content:// Uris — se genera primero en un archivo temporal
     * dentro del sandbox de Termux y de ahí se copia al Uri que eligió el usuario (SAF,
     * mExportContainerLauncher).
     */
    private fun exportContainerTo(name: String, destUri: Uri) {
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.udocker_export_progress_title, name), getString(R.string.udocker_generating_tar))
        Thread {
            val tmp = File(requireContext().cacheDir, "udocker_export_$name.tar")
            val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "export", "-o", tmp.absolutePath, name), 300)
            if (rc != 0 || !tmp.exists()) {
                tmp.delete()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { progress.failure(getString(R.string.udocker_export_failed, name), (out.ifEmpty { err }).takeLast(400)) }
                return@Thread
            }
            val copyError = copyFileToUri(tmp, destUri)
            tmp.delete()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (copyError == null) progress.success(getString(R.string.udocker_export_success, name)) else progress.failure(getString(R.string.udocker_tar_generated_not_saved), copyError)
            }
        }.start()
    }

    private fun copyFileToUri(source: File, destUri: Uri): String? = try {
        requireContext().contentResolver.openOutputStream(destUri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw java.io.IOException(getString(R.string.udocker_error_open_destination))
        null
    } catch (e: Exception) {
        e.message ?: getString(R.string.udocker_error_unknown)
    }

    private fun showTextDialog(title: String, body: String) {
        val ctx = requireContext()
        val text = TextView(ctx).apply {
            text = body
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(text) })
            .setPositiveButton(getString(R.string.udocker_button_close), null)
            .show()
    }

    private fun confirmRemoveContainer(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.udocker_confirm_delete_container_title, name))
            .setMessage(getString(R.string.udocker_confirm_delete_container_message))
            .setPositiveButton(getString(R.string.udocker_button_delete)) { _, _ -> removeContainer(name) }
            .setNegativeButton(getString(R.string.udocker_button_cancel), null)
            .show()
    }

    private fun removeContainer(name: String) {
        Thread {
            val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "rm", name), 20)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                toast(if (rc == 0) getString(R.string.udocker_toast_container_deleted, name) else getString(R.string.udocker_toast_error, (out.ifEmpty { err }).takeLast(200)))
                refreshContainers()
            }
        }.start()
    }

    /**
     * Diálogo opcional de "udocker run -v/-e" antes de abrir la terminal — hallazgo de
     * docs/arquitectura/AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_OFICIAL_2026-08-19.md
     * ("Flags/opciones oficiales NO expuestas" → udocker): hasta ahora no había forma de
     * montar un directorio host ni pasar variables de entorno sin escribir el comando de
     * udocker a mano en una terminal cruda. Sintaxis real confirmada contra la doc oficial
     * (indigo-dc/udocker, user_manual.md — "udocker run -v=/tmp myfed", "udocker run -v /tmp
     * -v /proc" para múltiples montajes, "--env=\"VAR=VAL\"" para variables): flags -v/-e
     * repetibles, formato "-v host:contenedor" y "-e CLAVE=VALOR". No toca
     * EntornoNative.udockerExecCommand() (compartida con EntornoFragment, ver comentario de
     * cabecera de este archivo) — el comando final se arma acá mismo en
     * buildUdockerRunCommand(). Ambos campos son opcionales — dejarlos vacíos abre el
     * contenedor exactamente igual que antes (sin flags extra).
     */
    private fun promptRunOptions(name: String, onConfirm: (extraFlags: List<String>) -> Unit) {
        val ctx = requireContext()
        val volumesEdit = EditText(ctx).apply {
            hint = getString(R.string.udocker_hint_volumes)
            minLines = 2
        }
        val envsEdit = EditText(ctx).apply {
            hint = getString(R.string.udocker_hint_envs)
            minLines = 2
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(TextView(ctx).apply {
                text = getString(R.string.udocker_run_options_hint)
                textSize = 12f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, 0, 0, dp(8))
            })
            addView(volumesEdit)
            addView(envsEdit)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.udocker_open_container_title, name))
            .setView(layout)
            .setPositiveButton(getString(R.string.udocker_button_open_terminal)) { _, _ ->
                val flags = mutableListOf<String>()
                volumesEdit.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    if (!line.contains(':')) {
                        toast(getString(R.string.udocker_invalid_mount, line))
                        return@forEach
                    }
                    flags += "-v"
                    flags += line
                }
                envsEdit.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    if (!line.contains('=')) {
                        toast(getString(R.string.udocker_invalid_env, line))
                        return@forEach
                    }
                    flags += "-e"
                    flags += line
                }
                onConfirm(flags)
            }
            .setNegativeButton(getString(R.string.udocker_button_cancel), null)
            .show()
    }

    /** Arma "udocker run" con los -v/-e opcionales de promptRunOptions() insertados antes del nombre del contenedor — mismo patrón de fallback bash→sh que EntornoNative.udockerExecCommand(), pero con flags dinámicos (por eso no se reusa esa función acá, ver comentario de promptRunOptions()). Cada valor de -v/-e se entrecomilla para sobrevivir espacios/caracteres especiales al pasar por el shell de la sesión de terminal (launchTerminalCommand() tipea el comando entero como una línea de shell). */
    private fun buildUdockerRunCommand(containerName: String, extraFlags: List<String>, shell: String = "bash"): String {
        val flagsStr = if (extraFlags.isEmpty()) "" else extraFlags.joinToString(" ") { quoteShellArg(it) } + " "
        return "udocker run --interactive --tty $flagsStr$containerName $shell || udocker run --interactive --tty $flagsStr$containerName sh"
    }

    private fun quoteShellArg(arg: String): String =
        if (arg == "-v" || arg == "-e") arg else "'" + arg.replace("'", "'\\''") + "'"

    /** Mismo patrón que EntornoFragment.promptUdockerTerminal() — picker de contenedores + sesión interactiva vía launchTerminalCommand(). */
    private fun promptTerminalInContainer() {
        Thread {
            val json = EntornoNative.udockerContainers()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val containers = json.optJSONArray("containers")
                if (!json.optBoolean("ok") || containers == null || containers.length() == 0) {
                    toast(getString(R.string.udocker_no_containers_install_distro))
                    return@runOnUiThread
                }
                val names = Array(containers.length()) { containers.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.udocker_terminal_container_title))
                    .setItems(names) { _, which ->
                        val chosen = names[which]
                        promptRunOptions(chosen) { flags ->
                            launchTerminalCommand(buildUdockerRunCommand(chosen, flags), "udocker/$chosen")
                        }
                    }
                    .setNegativeButton(getString(R.string.udocker_button_cancel), null)
                    .show()
            }
        }.start()
    }

    // ── Instalar distro (udocker pull + udocker create) ─────────

    private data class DistroImage(val label: String, val image: String, val defaultName: String)

    /** Imágenes reales de Docker Hub — no son un formato propio de udocker, son las mismas alpine/ubuntu/debian que correrían con Docker real (udocker no tiene registry propio). "Otra imagen…" cubre cualquier otra imagen de Docker Hub a mano. */
    private fun promptInstallDistro() {
        val distros = arrayOf(
            DistroImage(getString(R.string.udocker_distro_alpine), "alpine:latest", "alpine"),
            DistroImage(getString(R.string.udocker_distro_ubuntu), "ubuntu:latest", "ubuntu"),
            DistroImage(getString(R.string.udocker_distro_debian), "debian:latest", "debian")
        )
        val ctx = requireContext()
        val grid = GridLayout(ctx).apply {
            columnCount = 2
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.udocker_install_distro_title))
            .setView(grid)
            .setNegativeButton(getString(R.string.udocker_other_image_button)) { _, _ -> promptCustomImage() }
            .create()

        distros.forEachIndexed { i, d ->
            val tile = distroTile(ctx, d.label) {
                dialog.dismiss()
                promptContainerName(d.image, d.defaultName)
            }
            val params = GridLayout.LayoutParams().apply {
                width = dp(130)
                height = dp(80)
                columnSpec = GridLayout.spec(i % 2)
                rowSpec = GridLayout.spec(i / 2)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(tile, params)
        }
        dialog.show()
    }

    /** Mismo patrón visual que EntornoFragment.distroTile() (tiles 2 columnas, adoptado de Linbox-WinEmu) — reimplementado acá en vez de importado porque distroTile() es privado de EntornoFragment. */
    private fun distroTile(ctx: Context, label: String, onClick: () -> Unit): View {
        val card = MaterialCardView(ctx).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            setOnClickListener { onClick() }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        inner.addView(TextView(ctx).apply {
            text = "📦"
            textSize = 22f
            gravity = Gravity.CENTER
        })
        inner.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        card.addView(inner)
        return card
    }

    private fun promptCustomImage() {
        val edit = EditText(requireContext()).apply { hint = getString(R.string.udocker_hint_custom_image) }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.udocker_custom_image_title))
            .setView(edit)
            .setPositiveButton(getString(R.string.udocker_button_next)) { _, _ ->
                val image = edit.text.toString().trim()
                if (image.isEmpty()) {
                    toast(getString(R.string.udocker_toast_empty_image))
                } else {
                    val defaultName = image.substringBefore(':').substringAfterLast('/')
                    promptContainerName(image, defaultName)
                }
            }
            .setNegativeButton(getString(R.string.udocker_button_cancel), null)
            .show()
    }

    private fun promptContainerName(image: String, defaultName: String) {
        val edit = EditText(requireContext()).apply {
            setText(defaultName)
            hint = getString(R.string.udocker_hint_container_name)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.udocker_container_name_for_title, image))
            .setView(edit)
            .setPositiveButton(getString(R.string.udocker_button_install)) { _, _ ->
                val name = edit.text.toString().trim().ifEmpty { defaultName }
                installDistro(image, name)
            }
            .setNegativeButton(getString(R.string.udocker_button_cancel), null)
            .show()
    }

    /** udocker pull (puede tardar varios minutos, imagen completa) + udocker create --name= + setup P2 (mismo modo forzado por modulos/udocker.sh) — mismo patrón ProgressDialogController que EntornoFragment.installDistroAppWithProgress(). */
    private fun installDistro(image: String, name: String) {
        val appContext = requireContext().applicationContext
        val progress = ProgressDialogController(requireContext())
        // allowBackground=true: udocker pull descarga la imagen completa, hasta 600s de timeout
        // — mismo tratamiento que la descarga de imágenes QEMU/modelos GGUF (docs/humano247.md).
        progress.show(getString(R.string.udocker_installing_title, image), getString(R.string.udocker_downloading_image), allowBackground = true)
        Thread {
            val (pullRc, pullOut, pullErr) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "pull", image), 600)
            if (pullRc != 0) {
                if (progress.isBackgrounded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, "udocker", "install_failed", (pullOut.ifEmpty { pullErr }).takeLast(400)
                    )
                }
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { progress.failure(getString(R.string.udocker_download_failed, image), (pullOut.ifEmpty { pullErr }).takeLast(400)) }
                return@Thread
            }
            if (isAdded) requireActivity().runOnUiThread { progress.update(getString(R.string.udocker_creating_container, name)) }

            val (createRc, createOut, createErr) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "create", "--name=$name", image), 60)
            if (createRc != 0) {
                if (progress.isBackgrounded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, "udocker", "install_failed", (createOut.ifEmpty { createErr }).takeLast(400)
                    )
                }
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { progress.failure(getString(R.string.udocker_create_container_failed), (createOut.ifEmpty { createErr }).takeLast(400)) }
                return@Thread
            }
            ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "setup", "--execmode=P2", name), 20)

            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, "udocker", "install_done", appContext.getString(R.string.udocker_notify_container_ready, name)
                )
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                progress.success(getString(R.string.udocker_container_ready, name))
                refreshContainers()
                refreshImages()
            }
        }.start()
    }

    // ── Imágenes descargadas ─────────────────────────────────────

    private fun refreshImages() {
        Thread {
            val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "images"), 15)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { renderImages(rc, out, err) }
        }.start()
    }

    private fun renderImages(rc: Int, out: String, err: String) {
        val listContainer = imagesContainer ?: return
        listContainer.removeAllViews()
        if (rc != 0) {
            listContainer.addView(emptyRow(if (err.contains("not found", ignoreCase = true)) getString(R.string.udocker_unavailable) else getString(R.string.udocker_cannot_read_images)))
            return
        }
        val images = parseImagesOutput(out)
        if (images.isEmpty()) {
            listContainer.addView(emptyRow(getString(R.string.udocker_no_images)))
            return
        }
        images.forEach { image ->
            val row = infoRow("📦 $image", getString(R.string.udocker_options_label), requireContext().kairosThemeColor(R.attr.kairosBlue))
            row.setOnClickListener { promptImageActions(image) }
            listContainer.addView(row)
        }
    }

    private fun promptImageActions(image: String) {
        val options = arrayOf(
            getString(R.string.udocker_option_inspect),
            getString(R.string.udocker_option_save),
            getString(R.string.udocker_option_delete_image)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(image)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> inspectImage(image)
                    1 -> {
                        pendingSaveImage = image
                        val fileName = image.replace('/', '_').replace(':', '_') + ".tar"
                        mSaveImageLauncher.launch(fileName)
                    }
                    2 -> confirmRemoveImage(image)
                }
            }
            .setNegativeButton(getString(R.string.udocker_button_cancel), null)
            .show()
    }

    /** `udocker inspect <repo/imagen:tag>` — mismo comando que en contenedores, funciona igual
     * sobre una referencia de imagen (indigo-dc/udocker user_manual.md). Solo lectura. */
    private fun inspectImage(image: String) {
        Thread {
            val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "inspect", image), 20)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { showTextDialog(getString(R.string.udocker_inspect_title, image), if (rc == 0) out else (err.ifEmpty { out }).ifEmpty { getString(R.string.udocker_no_output) }) }
        }.start()
    }

    /** `udocker save -o <tar> <repo/imagen:tag>` — guarda la imagen (con sus capas) en formato
     * compatible con Docker (indigo-dc/udocker user_manual.md, sección "save"). Mismo patrón de
     * archivo temporal → copia a Uri elegido que exportContainerTo(). */
    private fun saveImageTo(image: String, destUri: Uri) {
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.udocker_saving_title, image), getString(R.string.udocker_generating_tar))
        Thread {
            val safeName = image.replace('/', '_').replace(':', '_')
            val tmp = File(requireContext().cacheDir, "udocker_save_$safeName.tar")
            val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "save", "-o", tmp.absolutePath, image), 300)
            if (rc != 0 || !tmp.exists()) {
                tmp.delete()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { progress.failure(getString(R.string.udocker_save_failed, image), (out.ifEmpty { err }).takeLast(400)) }
                return@Thread
            }
            val copyError = copyFileToUri(tmp, destUri)
            tmp.delete()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (copyError == null) progress.success(getString(R.string.udocker_image_saved, image)) else progress.failure(getString(R.string.udocker_tar_generated_not_saved), copyError)
            }
        }.start()
    }

    /**
     * `udocker import <tar> <repo/imagen:tag>` — importa un .tar (exportado por Docker o por
     * udocker) como una imagen nueva del repositorio local (indigo-dc/udocker user_manual.md,
     * sección "import"). El Uri elegido por SAF se copia primero a un archivo temporal del
     * sandbox (udocker tampoco sabe leer un content:// Uri directo) y de ahí se borra al
     * terminar, éxito o error.
     */
    private fun promptImportImageName(sourceUri: Uri) {
        val edit = EditText(requireContext()).apply { hint = getString(R.string.udocker_hint_image_name) }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.udocker_import_image_title))
            .setView(edit)
            .setPositiveButton(getString(R.string.udocker_button_import)) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isEmpty()) toast(getString(R.string.udocker_toast_empty_name)) else importImageFrom(sourceUri, name)
            }
            .setNegativeButton(getString(R.string.udocker_button_cancel), null)
            .show()
    }

    private fun importImageFrom(sourceUri: Uri, imageName: String) {
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.udocker_importing_title, imageName), getString(R.string.udocker_copying_file))
        Thread {
            val safeName = imageName.replace('/', '_').replace(':', '_')
            val tmp = File(requireContext().cacheDir, "udocker_import_$safeName.tar")
            val copyError = try {
                requireContext().contentResolver.openInputStream(sourceUri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw java.io.IOException(getString(R.string.udocker_error_open_chosen_file))
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.udocker_error_unknown)
            }
            if (copyError != null) {
                tmp.delete()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { progress.failure(getString(R.string.udocker_cannot_read_tar), copyError) }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { progress.update(getString(R.string.udocker_importing_to_udocker)) }
            val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "import", tmp.absolutePath, imageName), 300)
            tmp.delete()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (rc == 0) {
                    progress.success(getString(R.string.udocker_image_imported, imageName))
                    refreshImages()
                } else {
                    progress.failure(getString(R.string.udocker_import_failed, imageName), (out.ifEmpty { err }).takeLast(400))
                }
            }
        }.start()
    }

    /** "udocker images" imprime una cabecera ("REPOSITORY"/similar) + una fila por imagen — se descarta cualquier línea de cabecera por prefijo (mismo criterio defensivo que EntornoNative.udockerContainersList() con el header de "udocker ps -a") y se toma el primer token de cada línea como referencia de imagen. */
    private fun parseImagesOutput(out: String): List<String> {
        return out.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("REPOSITORY", ignoreCase = true) && !it.startsWith("Warning", ignoreCase = true) }
            .map { it.substringBefore(' ').trim() }
            .filter { it.isNotEmpty() }
    }

    private fun confirmRemoveImage(image: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.udocker_confirm_delete_image_title, image))
            .setMessage(getString(R.string.udocker_confirm_delete_image_message))
            .setPositiveButton(getString(R.string.udocker_button_delete)) { _, _ -> removeImage(image) }
            .setNegativeButton(getString(R.string.udocker_button_cancel), null)
            .show()
    }

    private fun removeImage(image: String) {
        Thread {
            val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "rmi", image), 20)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                toast(if (rc == 0) getString(R.string.udocker_toast_image_deleted, image) else getString(R.string.udocker_toast_error, (out.ifEmpty { err }).takeLast(200)))
                refreshImages()
            }
        }.start()
    }

    private fun emptyRow(text: String): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
    }
}
