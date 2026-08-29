package com.termux.app.ui

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.ManagerNativeUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import com.termux.app.util.kairosThemeColor

/**
 * Fragment dedicado de QEMU — investigación real hecha en modulos/qemu.sh (cabecera del
 * script): Android NO expone /dev/kvm a apps sin root, así que solo se exponen las dos
 * capacidades genuinamente reales en este entorno sin root:
 *
 *   1. qemu-user (qemu-x86_64/qemu-arm) — correr un binario ESTÁTICO de otra arquitectura
 *      directo, sin VM, sin binfmt_misc (que sí requiere root). Rápido y confiable, es el
 *      caso de uso más sólido de QEMU en Termux.
 *   2. qemu-system-x86_64-headless + qemu-utils — arrancar una VM headless (consola serie,
 *      -nographic) SIN aceleración KVM: TCG software puro, funcional pero lento (minutos de
 *      boot, no segundos). No es un reemplazo de una VM de escritorio con GPU.
 *
 * Se descartó a propósito cualquier UI de "VM gráfica" o selector de aceleración KVM/HAX —
 * ninguno de los dos existe sin root en este entorno, prometerlo sería UI aspiracional sin
 * respaldo real (mismo criterio de honestidad que qemu.sh y mimocode.sh).
 */
class QemuFragment : BaseModuleFragment() {
    override fun getModuleId() = "qemu"
    override fun getModuleName() = "QEMU"

    /** Entrada del catálogo de imágenes descargables — ver DOWNLOAD_CATALOG. */
    private data class CatalogImage(
        val nameResId: Int,
        val fileName: String,
        val sizeLabel: String,
        val descResId: Int,
        val url: String,
    )

    companion object {
        // Display :2 → puerto 5900+2 — Mini PC/Entorno ya usa :1 (5901), ver
        // VncViewerActivity/EntornoNative.vncStart(); QEMU usa el siguiente para no colisionar.
        private const val QEMU_VNC_PORT = 5902

        // hostfwd tcp::2222-:22 — ya lo arma run_vm.sh (modulos/qemu.sh, 3er argumento
        // ssh_port_host, default 2222) en TODOS los boots (console y vnc), no solo cuando el
        // usuario pide SSH explícitamente — la VM siempre puede recibir conexiones SSH del
        // host si el guest corre sshd. Esta constante evita repetir el literal "2222" en cada
        // llamada a run_vm.sh y en connectSsh().
        private const val QEMU_SSH_PORT = 2222

        // Bug real reportado por el usuario (captura de dispositivo, 2026-08-28): la VM cae
        // correctamente en "Booting from DVD/CD..." (sin disco duro) y llega al prompt de texto
        // ISOLINUX "boot:" — comportamiento CORRECTO de qemu/isolinux, no un error — pero la VM
        // queda "trabada" ahí porque nada le manda un Enter. VncViewerActivity.onKeyDown() ya
        // reenvía KEYCODE_ENTER como keysym X11 0xFF0D real al servidor VNC (confirmado leyendo
        // ese archivo antes de este fix) — el canal de teclado YA funciona, el gap real es que
        // el usuario no sabe que hace falta tocar la pantalla y apretar Enter. Se resuelve con
        // un diálogo de una sola vez (SharedPreferences, no un flag por sesión) antes de abrir
        // el visor VNC del propio QEMU — no se toca VncViewerActivity, el problema no era el
        // reenvío de teclado sino la falta de contexto para el usuario.
        private const val VNC_HINT_PREFS = "qemu_vnc_hint"
        private const val VNC_HINT_KEY_SHOWN = "boot_prompt_hint_shown"

        // Reemplaza el delay fijo viejo (4s) por un timeout de sondeo real — TCG software sin
        // KVM puede tardar bastante más que 4s en levantar el server VNC (ver
        // waitForQemuVncPort()/bootVmVnc()). 90s es generoso pero no infinito: si a los 90s
        // sigue sin responder, es más probable un fallo real (ISO inválida, etc.) que solo
        // lentitud — showVncBootTimeoutDialog() ofrece reintentar y muestra el log real.
        private const val QEMU_VNC_BOOT_TIMEOUT_MS = 90_000L

        // Catálogo curado de imágenes de SO x86_64 reales para bootear con
        // run_vm.sh (sin KVM — TCG software). URLs y tamaños confirmados con HEAD
        // real el 2026-08-18 (Content-Length), no inventados:
        //   - Alpine virt x86_64: dl-cdn.alpinelinux.org, 69206016 bytes (~66 MB)
        //   - Debian 12 cloud generic-amd64.qcow2: cloud.debian.org, 448069632 bytes (~427 MB)
        //   - Ubuntu 22.04 server cloudimg amd64: cloud-images.ubuntu.com, 734327808 bytes (~700 MB)
        private val DOWNLOAD_CATALOG = listOf(
            CatalogImage(
                nameResId = R.string.qemu_image_alpine_name,
                fileName = "alpine-virt-3.24.1-x86_64.iso",
                sizeLabel = "~66 MB",
                descResId = R.string.qemu_image_alpine_desc,
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-virt-3.24.1-x86_64.iso",
            ),
            CatalogImage(
                nameResId = R.string.qemu_image_debian_name,
                fileName = "debian-12-generic-amd64.qcow2",
                sizeLabel = "~427 MB",
                descResId = R.string.qemu_image_debian_desc,
                url = "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-amd64.qcow2",
            ),
            CatalogImage(
                nameResId = R.string.qemu_image_ubuntu_name,
                fileName = "ubuntu-22.04-server-cloudimg-amd64.img",
                sizeLabel = "~700 MB",
                descResId = R.string.qemu_image_ubuntu_desc,
                url = "https://cloud-images.ubuntu.com/releases/22.04/release/ubuntu-22.04-server-cloudimg-amd64.img",
            ),
        )

        /**
         * Normaliza el tamaño que escribe el usuario al formato REAL que acepta
         * `qemu-img create` (sufijo de una sola letra K/M/G/T, sin "b" final) —
         * bug real confirmado: el usuario escribió "1gb" (según el hint viejo del
         * campo) y qemu-img tiró "Invalid image size specified: '1gb'" porque el
         * formato real es "1G" o "1024M", nunca con "b" al final.
         *
         * Acepta "1gb"/"1GB"/"1g"/"1G" → "1G", "512mb"/"512M" → "512M", un número
         * solo (bytes) queda igual. Si no matchea nada reconocible, se devuelve el
         * texto tal cual escrito — mejor dejar que qemu-img dé su propio error real
         * que pretender "arreglar" algo que no se entiende.
         */
        internal fun normalizeQemuImgSize(raw: String): String {
            val trimmed = raw.trim()
            val match = Regex("^(\\d+(?:\\.\\d+)?)\\s*([KkMmGgTt])?[Bb]?$").matchEntire(trimmed)
                ?: return trimmed
            val (number, unit) = match.destructured
            return if (unit.isEmpty()) number else "$number${unit.uppercase()}"
        }
    }

    private val imagesDir: File get() = File(ManagerNativeUtils.home, "qemu_images")

    private var binaryPathInput: EditText? = null
    private var vmImagePathInput: EditText? = null
    private var vmRamInput: EditText? = null

    private var userModePill: LinearLayout? = null
    private var systemModePill: LinearLayout? = null

    // Bug real reportado por el usuario (2026-08-25, probando en su dispositivo): "QEMU dice
    // que no está instalado, pero sí puedo descargar imagen, pero no la abre" — modulos/qemu.sh
    // ya detecta correctamente si qemu-user/qemu-system quedaron realmente instalados (registry
    // qemu.user_mode/qemu.system_mode, confirmado en dispositivo real: AMBOS en "false" pese a
    // que el script sigue marcando qemu.installed=true al final — pkg install de
    // qemu-system-x86-64-headless falló silenciosamente, capturado por su propio `|| warn`).
    // El bug real de UI: bootVm()/runUserModeBinary() nunca chequeaban esto antes de abrir la
    // terminal — el usuario veía un toast "arrancando..." y una terminal con un error chico
    // que fácilmente se pasa por alto, sin ninguna explicación de qué pasó. Se guarda el
    // resultado real de refreshStatus() para bloquear con un diálogo claro en vez de abrir una
    // terminal condenada a fallar.
    private var userModeAvailable = false
    private var systemModeAvailable = false

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }

        addCard(getString(R.string.qemu_card_status_title)) {
            addView(statusRow(getString(R.string.qemu_status_user_mode)).also { userModePill = it })
            addView(statusRow(getString(R.string.qemu_status_system_mode)).also { systemModePill = it })
            addView(infoRow(getString(R.string.qemu_info_kvm_label), getString(R.string.qemu_info_kvm_value)))
        }
        refreshStatus()

        // Caso de uso #1 — correr un binario estático de otra arquitectura (sin VM, sin root).
        addCard(getString(R.string.qemu_card_run_binary_title)) {
            binaryPathInput = EditText(requireContext()).apply {
                hint = getString(R.string.qemu_binary_path_hint)
                setSingleLine(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }.also { addView(it) }
        }
        actionButton(getString(R.string.qemu_btn_run_x86_64), GHOST) { runUserModeBinary("x86_64") }
        actionButton(getString(R.string.qemu_btn_run_arm), GHOST) { runUserModeBinary("arm") }

        // Caso de uso #2 — imágenes de disco para la VM headless (qemu-img).
        addCard(getString(R.string.qemu_card_disk_images_title)) {
            addView(infoRow(getString(R.string.qemu_info_folder_label), imagesDir.path))
        }
        actionButton(getString(R.string.qemu_btn_create_image), GHOST) { promptCreateImage() }
        actionButton(getString(R.string.qemu_btn_list_images), GHOST) { listImages() }
        actionButton(getString(R.string.qemu_btn_image_ops), GHOST) { promptImageOperations() }

        // Catálogo de imágenes de SO reales para descargar — antes el usuario tenía que
        // conseguir su propio .iso/.qcow2 a mano. URLs confirmadas reales (ver DOWNLOAD_CATALOG
        // más abajo), guest x86_64 (arquitectura que soporta qemu-system-x86-64-headless).
        addCard(getString(R.string.qemu_card_download_images_title)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.qemu_download_images_desc)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(4))
            })
            for (entry in DOWNLOAD_CATALOG) {
                addView(downloadCatalogRow(entry))
            }
        }

        // Caso de uso #2 (continuación) — arrancar la VM headless, sin KVM.
        addCard(getString(R.string.qemu_card_vm_headless_title)) {
            vmImagePathInput = EditText(requireContext()).apply {
                hint = getString(R.string.qemu_vm_image_path_hint)
                setSingleLine(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }.also { addView(it) }
            vmRamInput = EditText(requireContext()).apply {
                hint = getString(R.string.qemu_vm_ram_hint)
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }.also { addView(it) }
        }
        actionButton(getString(R.string.qemu_btn_pick_image), GHOST) { pickImageIntoField() }
        actionButton(getString(R.string.qemu_btn_boot_vm), PRIMARY) { bootVm() }

        // SSH host→VM (docs/humano266.md — ajustar SSH/X11 para QEMU): run_vm.sh ya arma
        // hostfwd tcp::2222-:22 en TODOS los modos de boot (console y vnc, ver más arriba). No
        // se reusa RemoteManager/RemoteFragment (conexiones SSH guardadas con alias/clave
        // propia) porque esa UI está pensada para hosts persistentes con credenciales
        // conocidas — acá el usuario/clave del guest varía por imagen (root en Alpine,
        // debian/ubuntu en las cloud images, y ninguno es una "conexión guardada" real que
        // valga la pena persistir para una VM que se recrea a cada boot). Ver connectSsh().
        actionButton(getString(R.string.qemu_btn_connect_ssh), GHOST) { promptConnectSsh() }

        // Escape hatch para usuarios avanzados (kairos-product-philosophy.md: la terminal se
        // conserva a propósito para lo que la UI todavía no cubre — ej. la consola de monitor
        // de QEMU, flags custom de qemu-system-x86_64, o inspeccionar a mano los archivos de
        // imagenesDir/scripts). Mismo patrón que EntornoFragment.kt ("Iniciar sesión" en Mini
        // PC) — reusa launchTerminalCommand(), no un mecanismo nuevo.
        addCard(getString(R.string.qemu_card_terminal_title)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.qemu_terminal_desc)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(4))
            })
        }
        actionButton(getString(R.string.qemu_btn_open_terminal), GHOST) {
            launchTerminalCommand("cd '${imagesDir.path}' && bash", getString(R.string.qemu_session_name_terminal))
        }

        // Consolidado 2026-08-19 (auditoría de consistencia de menús): antes esta card solo
        // tenía "Actualizar" — sin "Desinstalar" desde la propia pantalla, a diferencia de
        // GenericModuleFragment (que lo da gratis a cualquier módulo sin pantalla propia). Ver
        // BaseModuleFragment.addMaintenanceCard().
        addMaintenanceCard()
    }

    private fun statusRow(label: String): LinearLayout {
        val row = infoRow(label, "—") as LinearLayout
        return row
    }

    private fun refreshStatus() {
        Thread {
            val userOk = ManagerNativeUtils.runShell("command -v qemu-x86_64 || command -v qemu-arm").first == 0
            val systemOk = ManagerNativeUtils.runShell("command -v qemu-system-x86_64").first == 0
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                userModeAvailable = userOk
                systemModeAvailable = systemOk
                setPillValue(userModePill, getString(if (userOk) R.string.qemu_status_installed else R.string.qemu_status_not_available))
                setPillValue(systemModePill, getString(if (systemOk) R.string.qemu_status_installed else R.string.qemu_status_not_available))
            }
        }.start()
    }

    // Diálogo compartido por bootVm()/runUserModeBinary() cuando el binario real no está
    // instalado — explica la causa real (falla silenciosa de pkg install, no un bug de "no
    // apreté el botón correcto") y ofrece reintentar la instalación en vez de dejar que el
    // usuario abra una terminal que va a fallar sin contexto.
    private fun showBinaryMissingDialog(what: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_binary_missing_title, what))
            .setMessage(getString(R.string.qemu_binary_missing_message))
            .setPositiveButton(getString(R.string.qemu_retry_install)) { _, _ ->
                toast(getString(R.string.qemu_toast_reinstalling))
                com.termux.app.ModuleController.installModule(getModuleId(), requireContext(), null, true, {}) { ok ->
                    if (!isAdded) return@installModule
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        toast(getString(if (ok) R.string.qemu_toast_reinstall_done else R.string.qemu_toast_reinstall_failed))
                        refreshStatus()
                    }
                }
            }
            .setNegativeButton(getString(R.string.qemu_close), null)
            .show()
    }

    private fun setPillValue(row: LinearLayout?, value: String) {
        (row?.valueTextView())?.text = value
    }

    private fun runUserModeBinary(arch: String) {
        if (!userModeAvailable) { showBinaryMissingDialog(getString(R.string.qemu_what_user_mode, arch)); return }
        val path = binaryPathInput?.text?.toString()?.trim().orEmpty()
        if (path.isEmpty()) { toast(getString(R.string.qemu_toast_binary_path_required)); return }
        launchTerminalCommand("qemu-$arch '$path'", sessionName = getString(R.string.qemu_session_name_arch, arch))
    }

    private fun promptCreateImage() {
        val ctx = requireContext()
        val nameInput = EditText(ctx).apply { hint = getString(R.string.qemu_create_image_name_hint) }
        val sizeInput = EditText(ctx).apply {
            hint = getString(R.string.qemu_create_image_size_hint)
            setPadding(0, dp(8), 0, 0)
        }
        val formLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(nameInput)
            addView(sizeInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.qemu_create_image_title))
            .setView(formLayout)
            .setPositiveButton(getString(R.string.qemu_create)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val size = sizeInput.text.toString().trim()
                if (name.isEmpty() || size.isEmpty()) { toast(getString(R.string.qemu_toast_name_size_required)); return@setPositiveButton }
                createImage(name, size)
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun createImage(name: String, size: String) {
        toast(getString(R.string.qemu_toast_creating_image))
        val normalizedSize = normalizeQemuImgSize(size)
        Thread {
            val format = if (name.endsWith(".qcow2")) "qcow2" else "raw"
            val target = File(imagesDir, name)
            val (code, _, err) = ManagerNativeUtils.runShell(
                "mkdir -p '${imagesDir.path}' && qemu-img create -f $format '${target.path}' $normalizedSize", 30
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (code == 0) getString(R.string.qemu_toast_image_created, target.name) else getString(R.string.qemu_error_prefix, err.ifEmpty { getString(R.string.qemu_error_could_not_create) }))
            }
        }.start()
    }

    private fun listImages(): List<File> {
        val files = imagesDir.listFiles { f ->
            f.isFile && (f.extension in listOf("qcow2", "img", "iso", "raw"))
        }?.toList()?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) {
            toast(getString(R.string.qemu_toast_no_images, imagesDir.path))
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.qemu_images_dialog_title, files.size))
                .setItems(files.map { "${it.name} (${ManagerNativeUtils.humanSize(it.length())})" }.toTypedArray()) { _, which ->
                    vmImagePathInput?.setText(files[which].path)
                }
                .setNegativeButton(getString(R.string.qemu_close), null)
                .show()
        }
        return files
    }

    private fun pickImageIntoField() {
        listImages()
    }

    /**
     * `qemu-img info/resize/convert` (auditoría 2026-08-19, 3ra ronda) — sintaxis confirmada
     * contra qemu.org/docs/master/tools/qemu-img.html:
     *   - `qemu-img info <file>`                              → metadata (formato, tamaño virtual/real, backing file)
     *   - `qemu-img resize <file> [+|-]<size>`                → agranda/achica el tamaño virtual (+5G, -1G, o absoluto "8G")
     *   - `qemu-img convert -f <fmt_in> -O <fmt_out> <in> <out>` → convierte entre formatos (ej. raw→qcow2)
     * Elige la imagen de la lista real (mismo picker que listImages()) y ofrece las 3 acciones.
     */
    private fun promptImageOperations() {
        val files = imagesDir.listFiles { f -> f.isFile && (f.extension in listOf("qcow2", "img", "iso", "raw")) }
            ?.toList()?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { toast(getString(R.string.qemu_toast_no_images, imagesDir.path)); return }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_choose_image_title))
            .setItems(files.map { "${it.name} (${ManagerNativeUtils.humanSize(it.length())})" }.toTypedArray()) { _, which ->
                promptImageOperationChoice(files[which])
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun promptImageOperationChoice(file: File) {
        val options = arrayOf(getString(R.string.qemu_option_info), getString(R.string.qemu_option_resize), getString(R.string.qemu_option_convert))
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runQemuImgInfo(file)
                    1 -> promptResizeImage(file)
                    2 -> promptConvertImage(file)
                }
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun runQemuImgInfo(file: File) {
        toast(getString(R.string.qemu_toast_reading_info, file.name))
        Thread {
            val (code, out, err) = ManagerNativeUtils.runShell("qemu-img info '${file.path}'", 15)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.qemu_info_dialog_title, file.name))
                    .setMessage(if (code == 0) out.trim().ifEmpty { getString(R.string.qemu_no_output) } else (err.ifEmpty { out }).ifEmpty { getString(R.string.qemu_unknown_error_message) })
                    .setPositiveButton(getString(R.string.qemu_close), null)
                    .show()
            }
        }.start()
    }

    /** `qemu-img resize` — el signo +/- es opcional (tamaño relativo al actual); sin signo es
     * tamaño absoluto. Acepta los mismos sufijos K/M/G/T que `create` — se reusa
     * normalizeQemuImgSize() para aceptar "5gb"/"5GB" igual que en promptCreateImage(). */
    private fun promptResizeImage(file: File) {
        val ctx = requireContext()
        val sizeInput = EditText(ctx).apply {
            hint = getString(R.string.qemu_resize_hint)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.qemu_resize_title, file.name))
            .setMessage(getString(R.string.qemu_resize_warning))
            .setView(sizeInput)
            .setPositiveButton(getString(R.string.qemu_resize_button)) { _, _ ->
                val raw = sizeInput.text.toString().trim()
                if (raw.isEmpty()) { toast(getString(R.string.qemu_toast_empty_size)); return@setPositiveButton }
                val sign = if (raw.startsWith("+") || raw.startsWith("-")) raw.take(1) else ""
                val magnitude = normalizeQemuImgSize(raw.removePrefix("+").removePrefix("-"))
                resizeImage(file, "$sign$magnitude")
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun resizeImage(file: File, size: String) {
        toast(getString(R.string.qemu_toast_resizing, file.name))
        Thread {
            val (code, _, err) = ManagerNativeUtils.runShell("qemu-img resize '${file.path}' $size", 30)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (code == 0) getString(R.string.qemu_toast_resize_done, file.name, size) else getString(R.string.qemu_error_prefix, err.ifBlank { getString(R.string.qemu_error_could_not_resize) }))
            }
        }.start()
    }

    /** `qemu-img convert -f <origen> -O <destino> <in> <out>` — nunca sobreescribe el archivo
     * fuente, siempre crea uno nuevo con el nombre + extensión del formato elegido. */
    private val QEMU_IMG_FORMATS = arrayOf("qcow2", "raw", "vdi", "vmdk", "vpc")

    private fun promptConvertImage(file: File) {
        val srcFormat = if (file.extension == "img") "raw" else file.extension.ifEmpty { "raw" }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_convert_title, file.name, srcFormat))
            .setItems(QEMU_IMG_FORMATS.map { it as CharSequence }.toTypedArray()) { _, which ->
                val targetFormat = QEMU_IMG_FORMATS[which]
                if (targetFormat == srcFormat) { toast(getString(R.string.qemu_toast_already_format, targetFormat)); return@setItems }
                convertImage(file, srcFormat, targetFormat)
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun convertImage(file: File, srcFormat: String, targetFormat: String) {
        val outFile = File(imagesDir, file.nameWithoutExtension + "." + targetFormat)
        toast(getString(R.string.qemu_toast_converting, targetFormat))
        Thread {
            val (code, _, err) = ManagerNativeUtils.runShell(
                "qemu-img convert -f $srcFormat -O $targetFormat '${file.path}' '${outFile.path}'", 120
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (code == 0) getString(R.string.qemu_toast_convert_done, outFile.name) else getString(R.string.qemu_error_prefix, err.ifBlank { getString(R.string.qemu_error_could_not_convert) }))
            }
        }.start()
    }

    /**
     * Selector de modo de visualización — pedido explícito del usuario tras
     * `docs/arquitectura/PROPUESTA_QEMU_DISPLAY_2026-08-26.md` (investigación previa, ya
     * aprobada): antes `bootVm()` solo tenía un camino (`-nographic`, consola serie forzada).
     * Ahora ofrece 2 modos reales:
     *   - Consola/SSH (default, comportamiento de siempre — sin cambios, `run_vm.sh` sigue
     *     aceptando 3 argumentos si algún caller viejo no pasa el 4to).
     *   - VNC — `run_vm.sh` arranca QEMU en background (sesión tmux detached, sin terminal
     *     visible — ver `bootVmVnc()`/`modulos/qemu.sh`) con `-vnc 127.0.0.1:2` (puerto 5902)
     *     en vez de `-nographic`; se sondea el puerto real (no un delay fijo, corregido
     *     2026-08-27 tras `docs/humano256.md`) y recién entonces se abre automáticamente
     *     `VncViewerActivity` (mismo visor RFB nativo que ya usa Mini PC/Entorno en el puerto
     *     5901 — `VncViewerActivity.EXTRA_PORT` es lo que permite reusarlo acá sin escribir un
     *     cliente VNC nuevo).
     * El "X11 nativo" (opción 3 de la propuesta) NO se implementa esta ronda — depende de un
     * hecho no confirmado (si el paquete `qemu-system-x86-64-headless` que instala `qemu.sh`
     * soporta `-display gtk/sdl`, probablemente no por ser la variante "headless") — ver la
     * propuesta para el detalle, no forzar código que probablemente no funcione.
     */

    private fun bootVm() {
        if (!systemModeAvailable) { showBinaryMissingDialog(getString(R.string.qemu_what_system_mode)); return }
        val path = vmImagePathInput?.text?.toString()?.trim().orEmpty()
        if (path.isEmpty()) { toast(getString(R.string.qemu_toast_pick_image_first)); return }
        val ram = vmRamInput?.text?.toString()?.trim().orEmpty().ifEmpty { "512" }

        val modes = arrayOf(
            getString(R.string.qemu_mode_console),
            getString(R.string.qemu_mode_vnc),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_display_mode_title))
            .setItems(modes) { _, which ->
                if (which == 1) bootVmVnc(path, ram) else bootVmConsole(path, ram)
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun bootVmConsole(path: String, ram: String) {
        toast(getString(R.string.qemu_toast_booting_console))
        launchTerminalCommand(
            "bash ${ManagerNativeUtils.home}/scripts/qemu/run_vm.sh '$path' $ram $QEMU_SSH_PORT console",
            sessionName = getString(R.string.qemu_session_name_vm)
        )
    }

    // Bug real confirmado por captura de pantalla del usuario (2026-08-27, ver
    // docs/humano256.md): esta función llamaba a launchTerminalCommand() — abría la terminal
    // adaptada CRUDA con el script corriendo como texto plano, exactamente lo que el selector
    // de modo (agregado el día anterior) debía evitar en el camino VNC. Además corría en
    // paralelo un segundo camino (abrir VncViewerActivity tras un delay fijo) — dos caminos a
    // la vez, ninguno exclusivo. Viola kairos-product-philosophy.md: el modo VNC es justamente
    // el que NO debería obligar a mirar una terminal.
    //
    // Fix real: correr run_vm.sh en background (silencioso, mismo patrón que
    // ModuleController.startModule — Thread + ProcessBuilder, sin abrir la terminal) — el
    // propio script ahora arranca QEMU en una sesión tmux detached en modo vnc (ver
    // modulos/qemu.sh) y vuelve al instante, así que este runShell no bloquea esperando a que
    // la VM termine. Después de eso, sondear el puerto real (ManagerNativeUtils.checkPort, no
    // un delay fijo) antes de abrir VncViewerActivity — un delay fijo de 4s no alcanza si el
    // boot TCG (sin KVM) tarda más, y sí desperdicia tiempo si el server ya está listo antes.
    private fun bootVmVnc(path: String, ram: String) {
        toast(getString(R.string.qemu_toast_booting_vnc))
        Thread {
            val (code, _, err) = ManagerNativeUtils.runShell(
                "bash ${ManagerNativeUtils.home}/scripts/qemu/run_vm.sh '$path' $ram $QEMU_SSH_PORT vnc", 20
            )
            if (!isAdded) return@Thread
            if (code != 0) {
                requireActivity().runOnUiThread {
                    if (isAdded) toast(getString(R.string.qemu_toast_boot_failed, err.ifBlank { getString(R.string.qemu_error_unknown_lower) }))
                }
                return@Thread
            }
            val portOpen = waitForQemuVncPort()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (portOpen) {
                    openVncViewerWithBootHint()
                } else {
                    showVncBootTimeoutDialog()
                }
            }
        }.start()
    }

    /** Abre VncViewerActivity — la primera vez (por dispositivo, SharedPreferences) muestra un
     * diálogo explicando que un prompt de texto tipo "boot:" (ISOLINUX, VM sin disco duro) es
     * comportamiento normal y solo necesita un Enter para continuar. Ver comentario de
     * VNC_HINT_PREFS más arriba para la evidencia real del bug de UX que esto resuelve. */
    private fun openVncViewerWithBootHint() {
        val intent = android.content.Intent(requireContext(), VncViewerActivity::class.java)
            .putExtra(VncViewerActivity.EXTRA_PORT, QEMU_VNC_PORT)
        val prefs = requireContext().getSharedPreferences(VNC_HINT_PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(VNC_HINT_KEY_SHOWN, false)) {
            startActivity(intent)
            return
        }
        prefs.edit().putBoolean(VNC_HINT_KEY_SHOWN, true).apply()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_vnc_boot_hint_title))
            .setMessage(getString(R.string.qemu_vnc_boot_hint_message))
            .setPositiveButton(getString(R.string.qemu_vnc_boot_hint_button)) { _, _ -> startActivity(intent) }
            .setCancelable(false)
            .show()
    }

    /** Sondea el puerto VNC real de QEMU (127.0.0.1:5902) en vez de un delay fijo — hasta
     * QEMU_VNC_BOOT_TIMEOUT_MS (TCG software sin KVM puede tardar bastante en levantar el
     * server, confirmado por el propio log de run_vm.sh: "el boot va a tardar"). */
    private fun waitForQemuVncPort(): Boolean {
        val deadline = System.currentTimeMillis() + QEMU_VNC_BOOT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ManagerNativeUtils.checkPort(QEMU_VNC_PORT)) return true
            Thread.sleep(2000)
        }
        return false
    }

    // Log real escrito por run_vm.sh (modo vnc) — ver comentario en modulos/qemu.sh: antes un
    // fallo de QEMU al arrancar era invisible (stderr silenciado, terminal ni se abría), ahora
    // se puede mostrar el motivo real (ISO inválida, RAM insuficiente, etc.) en vez de solo
    // "no conectó".
    private fun showVncBootTimeoutDialog() {
        Thread {
            val (_, out, _) = ManagerNativeUtils.runShell(
                "tail -n 20 '${ManagerNativeUtils.home}/kairos_logs/qemu_vm_vnc.log' 2>/dev/null", 5
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.qemu_vnc_timeout_title))
                    .setMessage(
                        getString(
                            R.string.qemu_vnc_timeout_message,
                            QEMU_VNC_PORT,
                            (QEMU_VNC_BOOT_TIMEOUT_MS / 1000).toInt(),
                            out.trim().ifEmpty { getString(R.string.qemu_vnc_log_empty) }
                        )
                    )
                    .setPositiveButton(getString(R.string.qemu_retry)) { _, _ -> bootVm() }
                    .setNegativeButton(getString(R.string.qemu_close), null)
                    .show()
            }
        }.start()
    }

    /**
     * Diálogo de usuario + arranque de connectSsh() — pedido explícito (docs/humano266.md,
     * "ajustar lo del vcn, ssh y x11"). El campo de usuario no tiene default fijo porque varía
     * mucho según la imagen del catálogo (root en Alpine, debian/ubuntu en las cloud images).
     */
    private fun promptConnectSsh() {
        val ctx = requireContext()
        val userInput = EditText(ctx).apply {
            hint = getString(R.string.qemu_ssh_username_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.qemu_ssh_dialog_title))
            .setMessage(getString(R.string.qemu_ssh_dialog_message, QEMU_SSH_PORT))
            .setView(userInput)
            .setPositiveButton(getString(R.string.qemu_ssh_connect_button)) { _, _ ->
                val user = userInput.text.toString().trim().ifEmpty { "root" }
                connectSsh(user)
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    /**
     * Sonda el puerto reenviado (127.0.0.1:QEMU_SSH_PORT) antes de abrir la terminal — si no
     * responde, es más probable que la VM siga bootenado (TCG sin KVM) o que el guest no tenga
     * sshd corriendo todavía, así que se avisa en vez de abrir una terminal condenada a
     * "Connection refused" sin contexto (mismo criterio que showBinaryMissingDialog()). El
     * comando real usa "-X" (X11 forwarding por app, no todo el escritorio — eso sigue siendo
     * VNC) y desactiva el chequeo de host key: la VM se recrea en cada boot con la misma
     * dirección 127.0.0.1:2222, así que el host key cambia legítimamente cada vez — sin esto
     * ssh rechazaría la conexión con "REMOTE HOST IDENTIFICATION HAS CHANGED" en el 2do boot.
     */
    private fun connectSsh(user: String) {
        Thread {
            val open = ManagerNativeUtils.checkPort(QEMU_SSH_PORT)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (open) {
                    launchSshTerminal(user)
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.qemu_ssh_port_closed_title, QEMU_SSH_PORT))
                        .setMessage(getString(R.string.qemu_ssh_port_closed_message, QEMU_SSH_PORT))
                        .setPositiveButton(getString(R.string.qemu_ssh_try_anyway)) { _, _ -> launchSshTerminal(user) }
                        .setNegativeButton(getString(R.string.qemu_cancel), null)
                        .show()
                }
            }
        }.start()
    }

    private fun launchSshTerminal(user: String) {
        launchTerminalCommand(
            "ssh -X -p $QEMU_SSH_PORT -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null '$user@localhost'",
            sessionName = getString(R.string.qemu_session_name_ssh)
        )
    }

    private fun downloadCatalogRow(entry: CatalogImage): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        row.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(requireContext()).apply {
                text = "${getString(entry.nameResId)} · ${entry.sizeLabel}"
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(requireContext()).apply {
                text = getString(entry.descResId)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            })
        })
        val alreadyDownloaded = File(imagesDir, entry.fileName).exists()
        row.addView(TextView(requireContext()).apply {
            text = getString(if (alreadyDownloaded) R.string.qemu_downloaded else R.string.qemu_download)
            textSize = 13f
            setTextColor(
                requireContext().kairosThemeColor(
                    if (alreadyDownloaded) R.attr.kairosGreen else R.attr.kairosBlue
                )
            )
            setPadding(dp(12), dp(6), dp(12), dp(6))
            if (!alreadyDownloaded) setOnClickListener { downloadDiskImage(entry) }
        })
        return row
    }

    /**
     * Descarga genérica con progreso real (%, velocidad, ETA) — mismo patrón que
     * LocalModelManager.downloadModel()/OllamaApiClient.pullModel(), sin la validación de
     * magic bytes GGUF (acá el archivo puede ser .iso o .qcow2, no .gguf). Corre en un
     * Thread propio, igual que el resto de las acciones largas de este Fragment.
     */
    private fun downloadDiskImage(entry: CatalogImage) {
        val target = File(imagesDir, entry.fileName)
        if (target.exists()) { toast(getString(R.string.qemu_toast_already_exists, entry.fileName)); return }
        val appContext = requireContext().applicationContext
        val progress = com.termux.app.util.ProgressDialogController(requireContext())
        // allowBackground=true (docs/humano247.md, pedido explícito del usuario): imágenes de
        // disco QEMU pueden pesar varios GB — antes el diálogo no-cancelable bloqueaba toda la
        // pantalla hasta terminar. Ahora se puede mandar a 2do plano y navegar libremente.
        progress.show(getString(R.string.qemu_download_progress_title, getString(entry.nameResId)), getString(R.string.qemu_connecting), allowBackground = true)

        Thread {
            imagesDir.mkdirs()
            val tmp = File(imagesDir, "${entry.fileName}.part")
            val error = try {
                downloadFileWithProgress(entry.url, tmp, appContext) { pct, message ->
                    runOnMainThread { progress.updateProgress(pct, message) }
                }
                if (!tmp.renameTo(target)) throw IOException(appContext.getString(R.string.qemu_could_not_save_file, entry.fileName))
                null
            } catch (e: Exception) {
                tmp.delete()
                e.message ?: appContext.getString(R.string.qemu_error_unknown_lower)
            }
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, appContext.getString(entry.nameResId),
                    if (error == null) "install_done" else "install_failed",
                    if (error == null) appContext.getString(R.string.qemu_notify_image_downloaded) else error
                )
            }
            runOnMainThread {
                if (error == null) {
                    progress.success(getString(R.string.qemu_toast_image_downloaded, entry.fileName))
                } else {
                    progress.failure(getString(R.string.qemu_download_error_title, entry.fileName), error)
                }
                listImages()
            }
        }.start()
    }

    private fun downloadFileWithProgress(url: String, tmp: File, ctx: android.content.Context, onProgress: (percent: Int, message: String) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
        }
        connection.connect()
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(ctx.getString(R.string.qemu_http_error, code))
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var downloaded = 0L
                    var readBytes: Int
                    var lastUpdate = System.currentTimeMillis()
                    var lastBytesAtUpdate = 0L
                    while (input.read(buffer).also { readBytes = it } >= 0) {
                        output.write(buffer, 0, readBytes)
                        downloaded += readBytes
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 500) {
                            val deltaSeconds = (now - lastUpdate) / 1000.0
                            val speedBps = (downloaded - lastBytesAtUpdate) / deltaSeconds
                            lastUpdate = now
                            lastBytesAtUpdate = downloaded
                            val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            onProgress(pct, formatDownloadProgress(downloaded, total, speedBps))
                        }
                    }
                }
            }
            if (total > 0 && tmp.length() < (total * 0.95).toLong()) {
                throw IOException(ctx.getString(R.string.qemu_download_cut_off, (tmp.length() / (1024 * 1024)).toInt(), (total / (1024 * 1024)).toInt()))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun formatDownloadProgress(downloaded: Long, total: Long, speedBps: Double): String {
        val pctInfo = if (total > 0) "${(downloaded * 100 / total).toInt()}% · " else ""
        val sizeInfo = "${ManagerNativeUtils.humanSize(downloaded)}/${if (total > 0) ManagerNativeUtils.humanSize(total) else "?"}"
        val speedInfo = if (speedBps > 0) " · ${ManagerNativeUtils.humanSize(speedBps.toLong())}/s" else ""
        return "$pctInfo$sizeInfo$speedInfo"
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (!isAdded) return
        activity?.runOnUiThread { if (isAdded) block() }
    }
}
