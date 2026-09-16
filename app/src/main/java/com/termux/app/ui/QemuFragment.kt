package com.termux.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.qemu.QmpClient
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
 *
 * **Pregunta real del usuario (2026-09-03, ver
 * docs/arquitectura/PROPUESTA_QEMU_DISPLAY_2026-08-26.md sección "Resolución"): "inicié en VNC
 * sin haber instalado VNC, ¿tiene VNC dentro QEMU?"** — Sí. El modo VNC de este módulo NO usa
 * TigerVNC (el paquete que sí instala `modulos/entorno.sh` para Mini PC) ni ningún otro servidor
 * VNC de Termux. `qemu-system-x86_64` trae su PROPIO servidor VNC integrado en el binario
 * (`-vnc` es un flag estándar de QEMU, documentado en qemu.org/docs/master/system/invocation.html
 * — nada específico de Kairos) que expone el framebuffer gráfico del guest directo por RFB, sin
 * ninguna instalación aparte. Por eso "ya estaba" cuando el usuario nunca instaló VNC a mano:
 * `run_vm.sh` (`modulos/qemu.sh`) arranca QEMU con `-vnc unix:<ruta>` (socket unix, ver
 * [vncSocketPath] — v1.4.0, ya NO es un puerto TCP, ver hallazgo #3 de
 * docs/mini-pc/AUDITORIA_COMUNICACION_2026-09-08.md), y `bootVmVnc()` de acá abajo reutiliza
 * `VncViewerActivity` (el mismo visor RFB nativo de Kairos) solo como CLIENTE apuntando a ese
 * socket — es la misma pieza de UI, pero el servidor del otro lado es el de QEMU, no TigerVNC.
 *
 * **Control en caliente vía QMP (v1.4.0, hallazgo #2 de la misma auditoría):** `run_vm.sh`
 * también arranca QEMU con `-qmp unix:<ruta>,server,nowait` en AMBOS modos de boot (console y
 * vnc) — antes Kairos solo podía arrancar/matar el proceso QEMU entero. [QmpClient] (paquete
 * `com.termux.app.qemu`) implementa el protocolo real; acá se exponen apagado graceful
 * (`system_powerdown`), pausar/reanudar (`stop`/`cont`), captura de framebuffer (`screendump`)
 * y hot-swap de medios (`query-block` + `blockdev-change-medium`/`eject`) sin reiniciar la VM.
 *
 * **Reorganización por pestañas (2026-09-08, pedido explícito del usuario — "por categorias,
 * como en mini pc, ssh"):** mismo patrón que `RemoteFragment.kt` (TabLayout programático +
 * `section()`, no la variante de `EntornoFragment.kt` con clases de pestaña separadas) — este
 * Fragment ya construía todo su contenido con `addCard()`/`actionButton()` de
 * `BaseModuleFragment` (que agregan directo a `container`), exactamente la misma situación que
 * `RemoteFragment` resolvió con `section()`: en vez de reescribir toda la lógica de estado real
 * (descargas con progreso, threads, diálogos) para que reciba un `parent` explícito, cada
 * pestaña se sigue construyendo como antes y `section()` registra qué vistas nuevas de
 * `container` pertenecen a cada pestaña para mostrar/ocultar por índice después. 4 pestañas:
 * Binario (qemu-user), Imágenes (qemu-img + catálogo + importar custom), Máquina Virtual (RAM,
 * arquitectura del guest, resolución VNC, arrancar/parar/reconectar/SSH/instalar paquete),
 * Avanzado (terminal + mantenimiento). También agrega soporte real de guest **aarch64**
 * (mismo arch que el host, sin traducción cruzada de ISA — confirmado empíricamente 2026-09-08
 * bajando e inspeccionando el `.deb` real de `qemu-system-aarch64-headless`/`qemu-common` de
 * packages.termux.dev, no asumido por analogía — ver comentario largo en `modulos/qemu.sh`
 * `run_vm.sh`), antes solo se ofrecía x86_64 (cross-arch, más lento).
 */
class QemuFragment : BaseModuleFragment() {
    override fun getModuleId() = "qemu"
    override fun getModuleName() = "QEMU"

    /** Entrada del catálogo de imágenes descargables — ver DOWNLOAD_CATALOG. `guestArch` es
     *  puramente informativo hoy (no se usa para armar la descarga en sí, ni para
     *  auto-seleccionar el chip de arquitectura de la pestaña "Máquina Virtual" —
     *  deliberadamente NO implementado esta ronda: el chip vive en una vista ya construida una
     *  sola vez por buildContent()/section(), cambiar vmGuestArch desde otra pestaña sin
     *  reconstruir esa vista dejaría el chip visualmente desincronizado del valor real, un bug
     *  peor que no tener el atajo — ver KDoc de la clase). El usuario confirma el arch a mano
     *  con el chip antes de arrancar; el nombre/descripción de cada entrada (ej. "★
     *  recomendado") ya dejan claro cuál es cuál. */
    private data class CatalogImage(
        val nameResId: Int,
        val fileName: String,
        val sizeLabel: String,
        val descResId: Int,
        val url: String,
        val guestArch: String,
    )

    companion object {
        // Pestañas — mismo patrón de índices que RemoteFragment.TAB_EMISOR/etc.
        private const val TAB_BINARIO = 0
        private const val TAB_IMAGENES = 1
        private const val TAB_VM = 2
        private const val TAB_AVANZADO = 3
        // Socket unix del servidor VNC de QEMU — v1.4.0, reemplaza el viejo puerto TCP
        // 127.0.0.1:5902/display :2 (hallazgo #3, docs/mini-pc/AUDITORIA_COMUNICACION_2026-09-08.md:
        // "Allow connections only from localhost using localsocket without a password"). Ruta
        // relativa a $HOME — "$HOME/scripts/qemu" es QEMU_SCRIPTS en el propio modulos/qemu.sh.
        private const val QEMU_VNC_SOCKET_REL = "scripts/qemu/vncsocket"

        // Socket unix QMP (QEMU Machine Protocol — control en caliente real, ver QmpClient.kt) —
        // hallazgo #2 de la misma auditoría. run_vm.sh lo habilita en AMBOS modos de boot.
        private const val QEMU_QMP_SOCKET_REL = "scripts/qemu/qmpsocket"

        // hostfwd tcp::2222-:22 — ya lo arma run_vm.sh (modulos/qemu.sh, 3er argumento
        // ssh_port_host, default 2222) en TODOS los boots (console y vnc), no solo cuando el
        // usuario pide SSH explícitamente — la VM siempre puede recibir conexiones SSH del
        // host si el guest corre sshd. Esta constante evita repetir el literal "2222" en cada
        // llamada a run_vm.sh y en connectSsh().
        private const val QEMU_SSH_PORT = 2222

        // Sesión tmux real que arranca run_vm.sh en modo vnc (modulos/qemu.sh, PROTEGIDO — no
        // se toca ese script, solo se reusa el nombre literal de la sesión que ya crea). Mismo
        // patrón de "tmux kill-session -t <sesión>" que TunnelManager.kt ya usa para detener
        // túneles en background — no hace falta un mecanismo nuevo.
        private const val QEMU_VNC_TMUX_SESSION = "kairos_qemu_vnc"

        // Presets de RAM (auditoría referencia/herramientas/Podroid-main 2026-09-01,
        // DeviceResourcePolicy.kt — chips de selección rápida en vez de solo un campo de texto
        // libre). Kairos ya usa este mismo patrón de "fila de chips + campo custom" en
        // LlamaServerConfigFragment.kt/OllamaConfigFragment.kt para otros parámetros — acá se
        // replica para RAM de la VM, que hasta ahora solo tenía el EditText libre.
        private val QEMU_RAM_PRESETS_MB = listOf(512, 1024, 2048, 4096)

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

        // Catálogo curado de imágenes de SO reales para bootear con run_vm.sh (sin KVM — TCG
        // software). URLs y tamaños confirmados con HEAD real (Content-Length), no inventados:
        //   x86_64 (cross-arch, más lento — confirmado 2026-08-18):
        //   - Alpine virt x86_64: dl-cdn.alpinelinux.org, 69206016 bytes (~66 MB)
        //   - Debian 12 cloud generic-amd64.qcow2: cloud.debian.org, 448069632 bytes (~427 MB)
        //   - Ubuntu 22.04 server cloudimg amd64: cloud-images.ubuntu.com, 734327808 bytes (~700 MB)
        //   aarch64 (RECOMENDADO, mismo arch que el host — confirmado 2026-09-08, ver
        //   run_vm.sh/modulos/qemu.sh para el soporte real de guest_arch=aarch64):
        //   - Alpine virt aarch64: dl-cdn.alpinelinux.org, 92743680 bytes (~88 MB)
        //   - Debian 12 cloud generic-arm64.qcow2: cloud.debian.org, 433848320 bytes (~414 MB)
        //   - Ubuntu 22.04 server cloudimg arm64: cloud-images.ubuntu.com, 704513024 bytes (~672 MB)
        private val DOWNLOAD_CATALOG = listOf(
            CatalogImage(
                nameResId = R.string.qemu_image_alpine_aarch64_name,
                fileName = "alpine-virt-3.24.1-aarch64.iso",
                sizeLabel = "~88 MB",
                descResId = R.string.qemu_image_alpine_aarch64_desc,
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-virt-3.24.1-aarch64.iso",
                guestArch = "aarch64",
            ),
            CatalogImage(
                nameResId = R.string.qemu_image_debian_aarch64_name,
                fileName = "debian-12-generic-arm64.qcow2",
                sizeLabel = "~414 MB",
                descResId = R.string.qemu_image_debian_aarch64_desc,
                url = "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-arm64.qcow2",
                guestArch = "aarch64",
            ),
            CatalogImage(
                nameResId = R.string.qemu_image_ubuntu_aarch64_name,
                fileName = "ubuntu-22.04-server-cloudimg-arm64.img",
                sizeLabel = "~672 MB",
                descResId = R.string.qemu_image_ubuntu_aarch64_desc,
                url = "https://cloud-images.ubuntu.com/releases/22.04/release/ubuntu-22.04-server-cloudimg-arm64.img",
                guestArch = "aarch64",
            ),
            CatalogImage(
                nameResId = R.string.qemu_image_alpine_name,
                fileName = "alpine-virt-3.24.1-x86_64.iso",
                sizeLabel = "~66 MB",
                descResId = R.string.qemu_image_alpine_desc,
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-virt-3.24.1-x86_64.iso",
                guestArch = "x86_64",
            ),
            CatalogImage(
                nameResId = R.string.qemu_image_debian_name,
                fileName = "debian-12-generic-amd64.qcow2",
                sizeLabel = "~427 MB",
                descResId = R.string.qemu_image_debian_desc,
                url = "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-amd64.qcow2",
                guestArch = "x86_64",
            ),
            CatalogImage(
                nameResId = R.string.qemu_image_ubuntu_name,
                fileName = "ubuntu-22.04-server-cloudimg-amd64.img",
                sizeLabel = "~700 MB",
                descResId = R.string.qemu_image_ubuntu_desc,
                url = "https://cloud-images.ubuntu.com/releases/22.04/release/ubuntu-22.04-server-cloudimg-amd64.img",
                guestArch = "x86_64",
            ),
        )

        // "apt" primero (Debian/Ubuntu, los 2 catálogos con más presencia real de paquetes) —
        // ver promptInstallPackageInVm(). Índice del spinner mapea directo a la posición acá.
        private val QEMU_PKG_MANAGERS = arrayOf("apt (Debian/Ubuntu)", "apk (Alpine)")

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

    // Rutas reales de los sockets unix que arranca run_vm.sh (modulos/qemu.sh, v1.4.0) — ver
    // QEMU_VNC_SOCKET_REL/QEMU_QMP_SOCKET_REL. Kairos corre en el mismo proceso/UID que el
    // rootfs de Termux (ver CLAUDE.md), así que estos archivos son accesibles directo.
    private val vncSocketPath: String get() = "${ManagerNativeUtils.home}/$QEMU_VNC_SOCKET_REL"
    private val qmpSocketPath: String get() = "${ManagerNativeUtils.home}/$QEMU_QMP_SOCKET_REL"

    private var binaryPathInput: EditText? = null
    private var vmImagePathInput: EditText? = null
    private var vmRamInput: EditText? = null

    // Resolución del framebuffer VGA en modo VNC (pedido explícito del usuario 2026-09-03:
    // "mejoras el modulo qemu para poder configurar su vnc desde el propio modulo" — antes la
    // resolución dependía 100% de lo que el driver VGA del guest negociara, sin ningún control
    // desde la UI). "" = comportamiento de siempre (sin -g, ver run_vm.sh/modulos/qemu.sh).
    // Default 1280x720 — mismo estándar horizontal fijado esta ronda para X11/VNC de Mini PC
    // (ver docs/x11/INVESTIGACION_VNC_LIBRERIA_NATIVA_2026-09-03.md), consistencia entre los
    // 2 caminos VNC que Kairos ofrece.
    private var vmResolution: String = "1280x720"

    private var userModePill: LinearLayout? = null
    // Split real del viejo "systemModePill"/"systemModeAvailable" únicos (v1.3.0,
    // modulos/qemu.sh) — cada arquitectura de guest se instala/puede fallar por separado, ver
    // registry_install en modulos/qemu.sh (system_mode_aarch64/system_mode_x86_64).
    private var systemAarch64Pill: LinearLayout? = null
    private var systemX86Pill: LinearLayout? = null

    // Arquitectura del guest elegida en la pestaña "Máquina Virtual" — pedido explícito del
    // usuario ("ajustar lo de las imagenes que funcionan en android termux"): aarch64 es el
    // default porque es el camino genuinamente recomendado en este entorno (mismo arch que el
    // host, sin traducción cruzada de ISA — ver run_vm.sh/modulos/qemu.sh). Se pasa tal cual
    // como 6to argumento a run_vm.sh.
    private var vmGuestArch: String = "aarch64"

    // ── Pestañas (reorganización 2026-09-08, ver KDoc de la clase) — mismo patrón que
    // RemoteFragment.kt: section() registra qué vistas de `container` pertenecen a cada índice
    // para mostrar/ocultar después, en vez de reescribir addCard()/actionButton() para que
    // reciban un parent explícito. ──────────────────────────────────────────────────────────
    private val sectionViews: Array<MutableList<View>> = Array(4) { mutableListOf() }
    private var activeTabIndex = TAB_BINARIO

    private fun section(tabIndex: Int, block: () -> Unit) {
        val before = container.childCount
        block()
        val after = container.childCount
        for (i in before until after) sectionViews[tabIndex].add(container.getChildAt(i))
    }

    private fun buildTabLayout() {
        val ctx = requireContext()
        val tabLayout = TabLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, dp(4))
            }
            tabMode = TabLayout.MODE_SCROLLABLE
            setSelectedTabIndicatorColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            setTabTextColors(ctx.kairosThemeColor(R.attr.kairosText3), ctx.kairosThemeColor(R.attr.kairosText))
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
        }
        listOf(
            getString(R.string.qemu_tab_binario),
            getString(R.string.qemu_tab_imagenes),
            getString(R.string.qemu_tab_vm),
            getString(R.string.qemu_tab_avanzado),
        ).forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        container.addView(tabLayout)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { renderActiveTab(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun renderActiveTab(index: Int) {
        activeTabIndex = index
        sectionViews.forEachIndexed { tabIndex, views ->
            val visibility = if (tabIndex == index) View.VISIBLE else View.GONE
            views.forEach { it.visibility = visibility }
        }
    }

    // Importar imagen custom desde el almacenamiento del teléfono (pedido explícito del
    // usuario: "custom imagnes para selecionar del almacenamiento") — "*/*" porque los formatos
    // de disco QEMU (.qcow2/.iso/.img/.raw) no tienen un MIME registrado confiable en Android
    // (a diferencia de "image/*" que sí usa EntornoFragment.mPickWallpaperLauncher para fondos
    // de pantalla). Copia el content:// URI elegido a un archivo real dentro de imagesDir
    // (mismo motivo que EntornoFragment.copyUriToWallpaperFile(): ni qemu-img ni qemu-system
    // pueden abrir un content:// URI directo, necesitan una ruta de filesystem real).
    private val mPickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { importPickedImage(it) }
        }

    // Bug real reportado por el usuario (2026-08-25, probando en su dispositivo): "QEMU dice
    // que no está instalado, pero sí puedo descargar imagen, pero no la abre" — modulos/qemu.sh
    // ya detecta correctamente si qemu-user/qemu-system quedaron realmente instalados (registry
    // qemu.user_mode/qemu.system_mode_aarch64/qemu.system_mode_x86_64, confirmado en dispositivo
    // real: AMBOS system_mode en "false" pese a que el script sigue marcando qemu.installed=true
    // al final — pkg install de qemu-system-x86-64-headless falló silenciosamente, capturado por
    // su propio `|| warn`).
    // El bug real de UI: bootVm()/runUserModeBinary() nunca chequeaban esto antes de abrir la
    // terminal — el usuario veía un toast "arrancando..." y una terminal con un error chico
    // que fácilmente se pasa por alto, sin ninguna explicación de qué pasó. Se guarda el
    // resultado real de refreshStatus() para bloquear con un diálogo claro en vez de abrir una
    // terminal condenada a fallar.
    private var userModeAvailable = false
    private var systemAarch64Available = false
    private var systemX86Available = false

    /** true si el arch elegido en el chip de la pestaña VM tiene su qemu-system real instalado. */
    private fun selectedSystemModeAvailable(): Boolean =
        if (vmGuestArch == "aarch64") systemAarch64Available else systemX86Available

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }

        addCard(getString(R.string.qemu_card_status_title)) {
            addView(statusRow(getString(R.string.qemu_status_user_mode)).also { userModePill = it })
            addView(statusRow(getString(R.string.qemu_status_system_aarch64)).also { systemAarch64Pill = it })
            addView(statusRow(getString(R.string.qemu_status_system_x86_64)).also { systemX86Pill = it })
            addView(infoRow(getString(R.string.qemu_info_kvm_label), getString(R.string.qemu_info_kvm_value)))
        }
        refreshStatus()

        buildTabLayout()

        // Pestaña "Binario" — correr un binario estático de otra arquitectura (sin VM, sin root).
        section(TAB_BINARIO) {
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
        }

        // Pestaña "Imágenes" — imágenes de disco para la VM headless (qemu-img) + catálogo de
        // descarga + importar una imagen custom desde el almacenamiento del teléfono.
        section(TAB_IMAGENES) {
            addCard(getString(R.string.qemu_card_disk_images_title)) {
                addView(infoRow(getString(R.string.qemu_info_folder_label), imagesDir.path))
            }
            actionButton(getString(R.string.qemu_btn_create_image), GHOST) { promptCreateImage() }
            actionButton(getString(R.string.qemu_btn_list_images), GHOST) { listImages() }
            actionButton(getString(R.string.qemu_btn_image_ops), GHOST) { promptImageOperations() }
            actionButton(getString(R.string.qemu_btn_import_image), GHOST) { mPickImageLauncher.launch("*/*") }

            // Catálogo de imágenes de SO reales para descargar — antes el usuario tenía que
            // conseguir su propio .iso/.qcow2 a mano. URLs confirmadas reales (ver
            // DOWNLOAD_CATALOG más arriba) — aarch64 (recomendado, mismo arch que el host) Y
            // x86_64 (cross-arch, más lento) según qué guest_arch soporte run_vm.sh.
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
        }

        // Pestaña "Máquina Virtual" — arrancar/parar/reconectar la VM headless, sin KVM.
        section(TAB_VM) {
            addCard(getString(R.string.qemu_card_vm_headless_title)) {
                vmImagePathInput = EditText(requireContext()).apply {
                    hint = getString(R.string.qemu_vm_image_path_hint)
                    setSingleLine(true)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }.also { addView(it) }
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.qemu_label_guest_arch)
                    textSize = 11f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                    setPadding(dp(14), dp(4), dp(14), dp(0))
                })
                addView(guestArchChipRow())
                vmRamInput = EditText(requireContext()).apply {
                    hint = getString(R.string.qemu_vm_ram_hint)
                    setSingleLine(true)
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }.also { addView(it) }
                addView(ramPresetRow())
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.qemu_vnc_resolution_label)
                    textSize = 11f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                    setPadding(dp(14), dp(4), dp(14), dp(0))
                })
                addView(resolutionPresetRow())
            }
            actionButton(getString(R.string.qemu_btn_pick_image), GHOST) { pickImageIntoField() }
            actionButton(getString(R.string.qemu_btn_boot_vm), PRIMARY) { bootVm() }
            actionButton(getString(R.string.qemu_btn_reconnect_vnc), GHOST) { reconnectVncViewer() }
            actionButton(getString(R.string.qemu_btn_stop_vm), GHOST) { confirmStopVm() }

            // Control en caliente vía QMP (v1.4.0, hallazgo #2 de
            // docs/mini-pc/AUDITORIA_COMUNICACION_2026-09-08.md — ver QmpClient.kt y el KDoc de
            // la clase más arriba). Requiere una VM corriendo con el socket QMP habilitado
            // (run_vm.sh ya lo hace por defecto en AMBOS modos de boot desde esta versión) —
            // showQmpUnavailableDialog() avisa con un mensaje claro si no hay ninguna VM
            // corriendo, en vez de dejar que cada botón falle en silencio o con un timeout de
            // varios segundos por conexión.
            addCard(getString(R.string.qemu_card_qmp_title)) {
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.qemu_qmp_desc)
                    textSize = 12f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                    setPadding(dp(14), dp(8), dp(14), dp(4))
                })
            }
            actionButton(getString(R.string.qemu_btn_qmp_powerdown), GHOST) { qmpPowerdown() }
            actionButton(getString(R.string.qemu_btn_qmp_pause), GHOST) { qmpPause() }
            actionButton(getString(R.string.qemu_btn_qmp_resume), GHOST) { qmpResume() }
            actionButton(getString(R.string.qemu_btn_qmp_screenshot), GHOST) { qmpScreenshot() }
            actionButton(getString(R.string.qemu_btn_qmp_change_media), GHOST) { promptChangeMedia() }
            actionButton(getString(R.string.qemu_btn_qmp_eject_media), GHOST) { promptEjectMedia() }

            // SSH host→VM (ajustar SSH/X11 para QEMU): run_vm.sh ya arma
            // hostfwd tcp::2222-:22 en TODOS los modos de boot (console y vnc, ver más arriba). No
            // se reusa RemoteManager/RemoteFragment (conexiones SSH guardadas con alias/clave
            // propia) porque esa UI está pensada para hosts persistentes con credenciales
            // conocidas — acá el usuario/clave del guest varía por imagen (root en Alpine,
            // debian/ubuntu en las cloud images, y ninguno es una "conexión guardada" real que
            // valga la pena persistir para una VM que se recrea a cada boot). Ver connectSsh().
            actionButton(getString(R.string.qemu_btn_connect_ssh), GHOST) { promptConnectSsh() }

            // "Instalar programas dentro de las imágenes" (pedido explícito del usuario) — corre
            // el instalador real por SSH en una terminal VISIBLE (no silencioso): ni el usuario
            // por defecto ni la disponibilidad de sudo están garantizados según la imagen (Alpine
            // usa apk sin sudo por defecto; Debian/Ubuntu usan apt, con sudo solo si cloud-init
            // configuró un usuario no-root) — mismo criterio de honestidad que connectSsh(). Ver
            // promptInstallPackageInVm().
            actionButton(getString(R.string.qemu_btn_install_package), GHOST) { promptInstallPackageInVm() }
        }

        // Pestaña "Avanzado" — escape hatch para usuarios avanzados (kairos-product-philosophy.md:
        // la terminal se conserva a propósito para lo que la UI todavía no cubre — ej. la
        // consola de monitor de QEMU, flags custom de qemu-system-*, o inspeccionar a mano los
        // archivos de imagesDir/scripts) + mantenimiento (actualizar/desinstalar el módulo).
        section(TAB_AVANZADO) {
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

        renderActiveTab(activeTabIndex)
    }

    private fun statusRow(label: String): LinearLayout {
        val row = infoRow(label, "—") as LinearLayout
        return row
    }

    private fun refreshStatus() {
        Thread {
            val userOk = ManagerNativeUtils.runShell("command -v qemu-x86_64 || command -v qemu-arm").first == 0
            val aarch64Ok = ManagerNativeUtils.runShell("command -v qemu-system-aarch64").first == 0
            val x86Ok = ManagerNativeUtils.runShell("command -v qemu-system-x86_64").first == 0
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                userModeAvailable = userOk
                systemAarch64Available = aarch64Ok
                systemX86Available = x86Ok
                setPillValue(userModePill, getString(if (userOk) R.string.qemu_status_installed else R.string.qemu_status_not_available))
                setPillValue(systemAarch64Pill, getString(if (aarch64Ok) R.string.qemu_status_installed else R.string.qemu_status_not_available))
                setPillValue(systemX86Pill, getString(if (x86Ok) R.string.qemu_status_installed else R.string.qemu_status_not_available))
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
     * "Custom imagnes para selecionar del almacenamiento" (pedido explícito del usuario) — copia
     * el archivo elegido (content:// URI, ej. desde el Explorador de Archivos de Android) a un
     * archivo real dentro de imagesDir. Ni qemu-img ni qemu-system pueden abrir un content://
     * URI directo (mismo motivo que EntornoFragment.copyUriToWallpaperFile() para el fondo de
     * pantalla) — necesitan una ruta de filesystem real. Preserva el nombre real del archivo
     * (vía OpenableColumns.DISPLAY_NAME) para que la extensión (.iso/.qcow2/.img) se mantenga —
     * run_vm.sh decide el formato de disco por esa extensión.
     */
    private fun importPickedImage(uri: Uri) {
        toast(getString(R.string.qemu_toast_importing_image))
        val appContext = requireContext().applicationContext
        Thread {
            val name = resolveDisplayName(appContext, uri) ?: "imported_${System.currentTimeMillis()}.img"
            val dest = File(imagesDir, name)
            val ok = try {
                imagesDir.mkdirs()
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                dest.exists() && dest.length() > 0
            } catch (_: Exception) {
                false
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    toast(getString(R.string.qemu_toast_image_imported, dest.name))
                    vmImagePathInput?.setText(dest.path)
                } else {
                    toast(getString(R.string.qemu_error_could_not_import))
                }
            }
        }.start()
    }

    private fun resolveDisplayName(ctx: android.content.Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Fila de chips de RAM (512/1024/2048/4096 MB) — repropósito de
     * `DeviceResourcePolicy`/`VmRamChips` de Podroid (referencia/herramientas/Podroid-main):
     * ahí eligen el preset por defecto según la RAM total del dispositivo (~35%, tope al valor
     * más cercano hacia abajo). Acá se reusa la misma idea para resaltar el preset recomendado
     * en vez de solo tipearlo a mano — mismo patrón visual de "fila de chips" que ya usa
     * LlamaServerConfigFragment.kt (líneas de preset de turnos de contexto).
     */
    private fun ramPresetRow(): LinearLayout {
        val ctx = requireContext()
        val recommended = recommendedRamMb(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(0), dp(14), dp(10))
        }
        QEMU_RAM_PRESETS_MB.forEachIndexed { index, mb ->
            row.addView(TextView(ctx).apply {
                text = if (mb == recommended) getString(R.string.qemu_ram_preset_recommended, ramLabel(mb)) else ramLabel(mb)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(ctx.kairosThemeColor(if (mb == recommended) R.attr.kairosBlue else R.attr.kairosText))
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    if (index > 0) it.marginStart = dp(6)
                }
                setOnClickListener { vmRamInput?.setText(mb.toString()) }
            })
        }
        return row
    }

    private fun ramLabel(mb: Int): String = if (mb >= 1024) "${mb / 1024}GB" else "${mb}MB"

    /**
     * Fila de chips de arquitectura del guest (aarch64 recomendado / x86_64 cross-arch) — mismo
     * patrón visual que ramPresetRow()/resolutionPresetRow() (fila de chips seleccionables).
     * aarch64 arranca resaltado por default (vmGuestArch="aarch64") porque es el camino
     * genuinamente recomendado en este entorno (mismo arch que el host — ver investigación real
     * en modulos/qemu.sh).
     */
    private fun guestArchChipRow(): LinearLayout {
        val ctx = requireContext()
        val options = listOf("aarch64" to getString(R.string.qemu_arch_aarch64_chip), "x86_64" to getString(R.string.qemu_arch_x86_64_chip))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        lateinit var chips: List<TextView>
        fun refreshChipStyles() {
            chips.forEachIndexed { i, chip ->
                val selected = options[i].first == vmGuestArch
                chip.setTextColor(ctx.kairosThemeColor(if (selected) R.attr.kairosBlue else R.attr.kairosText))
            }
        }
        chips = options.mapIndexed { index, (value, label) ->
            TextView(ctx).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    if (index > 0) it.marginStart = dp(6)
                }
                setOnClickListener { vmGuestArch = value; refreshChipStyles() }
            }.also { row.addView(it) }
        }
        refreshChipStyles()
        return row
    }

    // "Auto" (vmResolution="") deja el comportamiento de siempre (sin -g, el guest negocia su
    // propia resolución) — solo aplica de verdad en modo VNC (bootVmVnc()), el modo consola
    // (-nographic) no tiene framebuffer gráfico así que un -g ahí no tendría efecto visible.
    private val QEMU_RESOLUTION_PRESETS = listOf("" to "Auto", "800x600" to "800x600", "1024x768" to "1024x768", "1280x720" to "1280x720", "1920x1080" to "1920x1080")

    private fun resolutionPresetRow(): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        lateinit var chips: List<TextView>
        fun refreshChipStyles() {
            chips.forEachIndexed { i, chip ->
                val selected = QEMU_RESOLUTION_PRESETS[i].first == vmResolution
                chip.setTextColor(ctx.kairosThemeColor(if (selected) R.attr.kairosBlue else R.attr.kairosText))
            }
        }
        chips = QEMU_RESOLUTION_PRESETS.mapIndexed { index, (value, label) ->
            TextView(ctx).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    if (index > 0) it.marginStart = dp(6)
                }
                setOnClickListener { vmResolution = value; refreshChipStyles() }
            }.also { row.addView(it) }
        }
        refreshChipStyles()
        return row
    }

    /** ~35% de la RAM total del dispositivo, redondeado hacia abajo al preset más cercano — misma
     * fórmula que `DeviceResourcePolicy.balancedRamMb()` de Podroid, sin copiar código (proyecto
     * GPLv2, ver docs/referencias/herramientas/REFERENCIA_PODROID.md), reimplementada acá. */
    private fun recommendedRamMb(ctx: android.content.Context): Int {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return QEMU_RAM_PRESETS_MB.first()
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        val target = (totalMb * 0.35).toInt()
        return QEMU_RAM_PRESETS_MB.filter { it <= target }.maxOrNull() ?: QEMU_RAM_PRESETS_MB.first()
    }

    /**
     * Detener la VM en modo VNC (auditoría referencia/herramientas/Podroid-main 2026-09-01,
     * HomeScreen.kt — Podroid muestra estado "Running"/uptime con un botón Detener; Kairos no
     * tenía forma de parar la VM desde la UI una vez arrancada en VNC — solo cerrando la app o
     * abriendo una terminal a mano). Mata la sesión tmux real (mismo nombre que crea
     * modulos/qemu.sh, PROTEGIDO — no se toca ese script) y, por las dudas, también el proceso
     * qemu-system-x86_64 directo (si quedó corriendo fuera de la sesión tmux, ej. modo consola).
     * No falla si no había nada corriendo (`2>/dev/null`, `|| true`).
     */
    private fun confirmStopVm() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_stop_vm_title))
            .setMessage(getString(R.string.qemu_stop_vm_message))
            .setPositiveButton(getString(R.string.qemu_stop_vm_button)) { _, _ -> stopVm() }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun stopVm() {
        Thread {
            ManagerNativeUtils.runShell(
                "tmux kill-session -t $QEMU_VNC_TMUX_SESSION 2>/dev/null; pkill -f qemu-system-x86_64 2>/dev/null; true", 10
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) toast(getString(R.string.qemu_toast_vm_stopped))
            }
        }.start()
    }

    // ── Control en caliente vía QMP (v1.4.0) ─────────────────────────────────────────────────
    // Ver KDoc de QmpClient.kt para el protocolo real y el alcance de comandos implementados.
    // Todas las acciones acá abajo siguen el mismo patrón: si no hay ningún socket QMP en disco
    // (ninguna VM arrancada todavía con la versión nueva de run_vm.sh), avisar con un diálogo
    // claro en vez de intentar conectar y esperar el timeout de 5s de QmpClient por cada tap.

    /** Diálogo compartido — no hay socket QMP en disco (ninguna VM corriendo, o corriendo con
     * una versión vieja de run_vm.sh sin `-qmp`). Distinto de un fallo de conexión real (socket
     * presente pero QEMU no responde, ej. crash) — ese caso lo cubre el toast de fallo genérico
     * de [runQmpAction]. */
    private fun showQmpUnavailableDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_qmp_unavailable_title))
            .setMessage(getString(R.string.qemu_qmp_unavailable_message))
            .setPositiveButton(getString(R.string.qemu_close), null)
            .show()
    }

    /** Corre una acción QMP puntual en background (conectar → ejecutar → cerrar) y muestra un
     * toast real de éxito/fallo — mismo patrón que el resto de las acciones de este Fragment
     * (Thread + guard `isAdded` + `runOnUiThread`). No mantiene la conexión abierta entre
     * acciones (ver KDoc de QmpClient sobre por qué: son taps puntuales de UI, no el hot path
     * continuo de mouse/teclado). */
    private fun runQmpAction(successMsgRes: Int, failureMsgRes: Int, action: (QmpClient) -> Boolean) {
        if (!QmpClient.socketExists(qmpSocketPath)) { showQmpUnavailableDialog(); return }
        Thread {
            val client = QmpClient(qmpSocketPath)
            val ok = try { client.connect() && action(client) } finally { client.close() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) toast(getString(if (ok) successMsgRes else failureMsgRes))
            }
        }.start()
    }

    /** Apagado graceful (ACPI powerdown real, `system_powerdown` QMP) — distinto de [stopVm]
     * (mata el proceso QEMU entero): le da al guest la chance de cerrar servicios/journal limpio
     * antes de terminar, como apretar el botón de power físico de una PC real. Con confirmación
     * porque, a diferencia de pausar/reanudar, es una acción que puede perder trabajo no
     * guardado dentro del guest si no responde al ACPI event (guests headless minimalistas como
     * Alpine sin `acpid` podrían ignorarlo — documentado en el propio mensaje del diálogo). */
    private fun qmpPowerdown() {
        if (!QmpClient.socketExists(qmpSocketPath)) { showQmpUnavailableDialog(); return }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_qmp_powerdown_title))
            .setMessage(getString(R.string.qemu_qmp_powerdown_message))
            .setPositiveButton(getString(R.string.qemu_qmp_powerdown_button)) { _, _ ->
                runQmpAction(R.string.qemu_toast_qmp_powerdown_sent, R.string.qemu_toast_qmp_failed) { it.systemPowerdown() }
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    /** Pausa la VM (`stop` QMP) — todas las vCPUs se detienen, el estado completo queda en RAM
     * (a diferencia de [stopVm]/[qmpPowerdown], nada se apaga ni se pierde). [qmpResume]
     * reanuda exactamente donde quedó. */
    private fun qmpPause() = runQmpAction(R.string.qemu_toast_qmp_paused, R.string.qemu_toast_qmp_failed) { it.stop() }

    /** Reanuda una VM pausada con [qmpPause] (`cont` QMP). */
    private fun qmpResume() = runQmpAction(R.string.qemu_toast_qmp_resumed, R.string.qemu_toast_qmp_failed) { it.cont() }

    /** Captura el framebuffer actual a un `.ppm` en [imagesDir] vía `screendump` QMP — directo
     * desde QEMU, sin decodificar el stream RFB ni necesitar el visor VNC abierto. Alcance
     * honesto de esta ronda: Android no decodifica `.ppm` nativamente, así que el resultado
     * queda como archivo en disco (ruta real en el toast) — una miniatura in-app real (hallazgo
     * #5 de la auditoría) queda documentada como próximo paso, no implementada acá. */
    private fun qmpScreenshot() {
        if (!QmpClient.socketExists(qmpSocketPath)) { showQmpUnavailableDialog(); return }
        Thread {
            imagesDir.mkdirs()
            val target = File(imagesDir, "qemu_screenshot_${System.currentTimeMillis()}.ppm")
            val client = QmpClient(qmpSocketPath)
            val ok = try { client.connect() && client.screendump(target.path) } finally { client.close() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (ok) getString(R.string.qemu_toast_qmp_screenshot_saved, target.name) else getString(R.string.qemu_toast_qmp_failed))
            }
        }.start()
    }

    /** Mismo formato que decide `IMG_FORMAT` en `run_vm.sh` (`modulos/qemu.sh`, PROTEGIDO — no
     * se toca ese script para esto, solo se replica su criterio acá): .iso siempre es raw
     * (ISO9660, no existe "format=iso" real para qemu-img/blockdev), .qcow2 es qcow2, cualquier
     * otra cosa (.img/.raw/sin extensión, ej. las cloud images del catálogo) es raw. */
    private fun qemuImgFormatFor(file: File): String = when (file.extension.lowercase()) {
        "iso" -> "raw"
        "qcow2" -> "qcow2"
        else -> "raw"
    }

    /**
     * Hot-swap real de medios — la funcionalidad de mayor valor del hallazgo original de la
     * auditoría (`blockdev-change-medium` QMP, cambiar el ISO/imagen de un drive SIN reiniciar
     * la VM). Flujo: 1) conectar QMP y listar los block devices reales de la VM en curso vía
     * `query-block` (sin asumir ningún id fijo tipo "hd0" — `modulos/qemu.sh` solo pone
     * `id=hd0` explícito en la rama aarch64, PROTEGIDO, no tocado para no arriesgar el boot
     * x86_64/q35 que ya funciona; consultar en vivo es lo que hace que esto funcione en ambas
     * arquitecturas sin depender de ese detalle interno del script), 2) el usuario elige cuál
     * device, 3) elige el archivo nuevo de la lista de imágenes ya existente en [imagesDir]
     * (mismo picker que [listImages]).
     */
    private fun promptChangeMedia() {
        if (!QmpClient.socketExists(qmpSocketPath)) { showQmpUnavailableDialog(); return }
        toast(getString(R.string.qemu_toast_qmp_listing_devices))
        queryBlockDevicesThenPick { device -> promptNewMediaFile(device) }
    }

    /** Expulsa el medio de un device SIN insertar uno nuevo (`eject` QMP) — mismo picker de
     * devices que [promptChangeMedia], sin el segundo paso de elegir archivo. */
    private fun promptEjectMedia() {
        if (!QmpClient.socketExists(qmpSocketPath)) { showQmpUnavailableDialog(); return }
        toast(getString(R.string.qemu_toast_qmp_listing_devices))
        queryBlockDevicesThenPick { device -> ejectMedia(device.device) }
    }

    /** Conecta QMP, lista los block devices reales (`query-block`) y deja elegir uno de un
     * diálogo — [onPicked] recibe el device elegido para que [promptChangeMedia]/
     * [promptEjectMedia] sigan cada uno su propio siguiente paso. */
    private fun queryBlockDevicesThenPick(onPicked: (QmpClient.BlockDevice) -> Unit) {
        Thread {
            val client = QmpClient(qmpSocketPath)
            val devices = try {
                if (client.connect()) client.queryBlockDevices() else emptyList()
            } finally {
                client.close()
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (devices.isEmpty()) {
                    toast(getString(R.string.qemu_toast_qmp_no_devices))
                    return@runOnUiThread
                }
                val labels = devices.map {
                    "${it.device} — ${it.filePath?.substringAfterLast('/') ?: getString(R.string.qemu_qmp_device_empty)}"
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.qemu_qmp_choose_device_title))
                    .setItems(labels.toTypedArray()) { _, which -> onPicked(devices[which]) }
                    .setNegativeButton(getString(R.string.qemu_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun promptNewMediaFile(device: QmpClient.BlockDevice) {
        val files = imagesDir.listFiles { f -> f.isFile && (f.extension in listOf("qcow2", "img", "iso", "raw")) }
            ?.toList()?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { toast(getString(R.string.qemu_toast_no_images, imagesDir.path)); return }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qemu_qmp_choose_new_media_title, device.device))
            .setItems(files.map { "${it.name} (${ManagerNativeUtils.humanSize(it.length())})" }.toTypedArray()) { _, which ->
                changeMedia(device.device, files[which])
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun changeMedia(device: String, file: File) {
        toast(getString(R.string.qemu_toast_qmp_changing_media, file.name))
        val format = qemuImgFormatFor(file)
        Thread {
            val client = QmpClient(qmpSocketPath)
            val ok = try { client.connect() && client.changeMedia(device, file.path, format) } finally { client.close() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) toast(if (ok) getString(R.string.qemu_toast_qmp_media_changed, file.name) else getString(R.string.qemu_toast_qmp_failed))
            }
        }.start()
    }

    private fun ejectMedia(device: String) =
        runQmpAction(R.string.qemu_toast_qmp_ejected, R.string.qemu_toast_qmp_failed) { it.ejectMedia(device) }

    /**
     * "Volver a entrar" a una VM que ya está corriendo en segundo plano — pedido explícito del
     * usuario ("en QEMU también el botón para cerrar por completo o volver a entrar"). Distinto
     * de [bootVmVnc]: NO vuelve a correr `run_vm.sh` (eso arrancaría una VM SEGUNDA en paralelo
     * si la sesión tmux `kairos_qemu_vnc` ya sigue viva) — solo sondea si el socket VNC real
     * ([vncSocketPath]) existe y, si sí, abre `VncViewerActivity` apuntando ahí directo. Cubre
     * el caso de que el usuario haya cerrado el visor (✕/menú "Cerrar") pero la VM siga
     * corriendo de fondo — antes la única forma de volver a verla era re-arrancarla desde cero.
     */
    private fun reconnectVncViewer() {
        toast(getString(R.string.qemu_toast_checking_vnc))
        Thread {
            val open = File(vncSocketPath).exists()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (open) {
                    openVncViewerWithBootHint()
                } else {
                    toast(getString(R.string.qemu_toast_no_vm_running))
                }
            }
        }.start()
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
     *     visible — ver `bootVmVnc()`/`modulos/qemu.sh`) con `-vnc unix:<ruta>` (socket unix,
     *     v1.4.0) en vez de `-nographic`; se sondea la existencia real del socket (no un delay
     *     fijo, corregido 2026-08-27) y recién entonces se abre
     *     automáticamente `VncViewerActivity` (mismo visor RFB nativo que ya usa Mini PC/Entorno
     *     — `VncViewerActivity.EXTRA_SOCKET_PATH` es lo que permite reusarlo acá sin escribir un
     *     cliente VNC nuevo).
     * El "X11 nativo" (opción 3 de la propuesta) NO se implementa esta ronda — depende de un
     * hecho no confirmado (si el paquete `qemu-system-x86-64-headless` que instala `qemu.sh`
     * soporta `-display gtk/sdl`, probablemente no por ser la variante "headless") — ver la
     * propuesta para el detalle, no forzar código que probablemente no funcione.
     */

    private fun bootVm() {
        if (!selectedSystemModeAvailable()) { showBinaryMissingDialog(getString(R.string.qemu_what_system_mode, vmGuestArch)); return }
        val path = vmImagePathInput?.text?.toString()?.trim().orEmpty()
        if (path.isEmpty()) { toast(getString(R.string.qemu_toast_pick_image_first)); return }
        val ram = vmRamInput?.text?.toString()?.trim().orEmpty().ifEmpty { "512" }

        // Guardia de RAM real (auditoría referencia/emuladores/Vectras-VM-Android 2026-09-08,
        // RamInfo.java `vectrasMemory()`: usa RAM LIBRE ahora mismo, no solo el total del
        // dispositivo, para decidir cuánta darle a la VM — Kairos ya tenía recommendedRamMb()
        // (35% del total, vía Podroid) para SUGERIR un preset, pero nada avisaba si el usuario
        // pedía más RAM de la que hay libre en este momento — Android puede matar el proceso de
        // Kairos por OOM si QEMU reserva más de lo que el sistema puede darle. No se BLOQUEA
        // (la memoria libre puede subir/bajar, y qemu-system no siempre reserva toda la RAM de
        // golpe) — se avisa con el número real y se deja decidir.
        val ramMb = ram.toIntOrNull() ?: 512
        val freeMb = availableRamMb()
        if (freeMb > 0 && ramMb > freeMb - 100) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.qemu_ram_warning_title))
                .setMessage(getString(R.string.qemu_ram_warning_message, ramMb, freeMb))
                .setPositiveButton(getString(R.string.qemu_ram_warning_continue)) { _, _ -> showDisplayModeDialog(path, ram) }
                .setNegativeButton(getString(R.string.qemu_cancel), null)
                .show()
            return
        }
        showDisplayModeDialog(path, ram)
    }

    /** RAM disponible AHORA MISMO (no el total del dispositivo) — ver comentario en bootVm(). -1 si `ActivityManager` no está disponible (nunca debería pasar, pero evita un NPE en vez de crashear). */
    private fun availableRamMb(): Int {
        val am = requireContext().getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return -1
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.availMem / (1024 * 1024)).toInt()
    }

    private fun showDisplayModeDialog(path: String, ram: String) {
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
        // Resolución vacía ('') para conservar la posición del 6to argumento (guest_arch) — modo
        // consola no usa -g de todas formas (ver run_vm.sh), pero el arch SÍ debe llegar en su
        // posición real. Comillas explícitas en '$vmGuestArch'/'' — un valor vacío SIN comillas
        // desaparecería del comando en vez de quedar como argumento posicional vacío, corriendo
        // guest_arch como si fuera el 5to argumento (resolución) por error.
        launchTerminalCommand(
            "bash ${ManagerNativeUtils.home}/scripts/qemu/run_vm.sh '$path' $ram $QEMU_SSH_PORT console '' '$vmGuestArch'",
            sessionName = getString(R.string.qemu_session_name_vm)
        )
    }

    // Bug real confirmado por captura de pantalla del usuario (2026-08-27): esta función
    // llamaba a launchTerminalCommand() — abría la terminal
    // adaptada CRUDA con el script corriendo como texto plano, exactamente lo que el selector
    // de modo (agregado el día anterior) debía evitar en el camino VNC. Además corría en
    // paralelo un segundo camino (abrir VncViewerActivity tras un delay fijo) — dos caminos a
    // la vez, ninguno exclusivo. Viola la filosofía de producto de Kairos: el modo VNC es
    // justamente el que NO debería obligar a mirar una terminal.
    //
    // Fix real: correr run_vm.sh en background (silencioso, mismo patrón que
    // ModuleController.startModule — Thread + ProcessBuilder, sin abrir la terminal) — el
    // propio script ahora arranca QEMU en una sesión tmux detached en modo vnc (ver
    // modulos/qemu.sh) y vuelve al instante, así que este runShell no bloquea esperando a que
    // la VM termine. Después de eso, sondear la existencia real del socket ([vncSocketPath], no
    // un delay fijo) antes de abrir VncViewerActivity — un delay fijo de 4s no alcanza si el
    // boot TCG (sin KVM) tarda más, y sí desperdicia tiempo si el server ya está listo antes.
    private fun bootVmVnc(path: String, ram: String) {
        toast(getString(R.string.qemu_toast_booting_vnc))
        Thread {
            // $vmResolution AHORA entre comillas (antes iba sin comillas al final del comando,
            // inofensivo porque era el último argumento — ahora que guest_arch va DESPUÉS como
            // 6to argumento, un valor vacío sin comillas desaparecería del comando entero en vez
            // de quedar como argumento posicional vacío, corriendo guest_arch en la posición 5).
            val (code, _, err) = ManagerNativeUtils.runShell(
                "bash ${ManagerNativeUtils.home}/scripts/qemu/run_vm.sh '$path' $ram $QEMU_SSH_PORT vnc '$vmResolution' '$vmGuestArch'", 20
            )
            if (!isAdded) return@Thread
            if (code != 0) {
                requireActivity().runOnUiThread {
                    if (isAdded) toast(getString(R.string.qemu_toast_boot_failed, err.ifBlank { getString(R.string.qemu_error_unknown_lower) }))
                }
                return@Thread
            }
            val socketReady = waitForQemuVncSocket()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (socketReady) {
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
            .putExtra(VncViewerActivity.EXTRA_SOCKET_PATH, vncSocketPath)
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

    /** Sondea la existencia real del socket VNC de QEMU ([vncSocketPath]) en vez de un delay
     * fijo — hasta QEMU_VNC_BOOT_TIMEOUT_MS (TCG software sin KVM puede tardar bastante en
     * levantar el server, confirmado por el propio log de run_vm.sh: "el boot va a tardar").
     * QEMU crea el archivo del socket al hacer bind()/listen() — su sola existencia es una señal
     * tan confiable como un puerto TCP respondiendo. */
    private fun waitForQemuVncSocket(): Boolean {
        val deadline = System.currentTimeMillis() + QEMU_VNC_BOOT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (File(vncSocketPath).exists()) return true
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
     * Diálogo de usuario + arranque de connectSsh() — pedido explícito del usuario
     * ("ajustar lo del vcn, ssh y x11"). El campo de usuario no tiene default fijo porque varía
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

    /**
     * "Instalar programas dentro de las imágenes" (pedido explícito del usuario). Alcance real
     * y honesto (empirical-verification-before-fix.md — no fabricar una post-condición sin
     * evidencia): Kairos no controla qué usuario/gestor de paquetes trae cada guest, así que
     * esto NO es un instalador silencioso de un clic — arma el comando real de instalación
     * (apt o apk) y lo corre por SSH en una terminal VISIBLE (mismo hostfwd que ya usa
     * connectSsh()), para que cualquier prompt de contraseña/confirmación del guest se vea y se
     * pueda responder ahí mismo. `sudo -n` (no interactivo) se intenta primero para el camino
     * apt — si no hay sudo configurado (ej. usuario ya es root sin necesitarlo), cae al comando
     * plano sin sudo.
     */
    private fun promptInstallPackageInVm() {
        val ctx = requireContext()
        val userInput = EditText(ctx).apply {
            hint = getString(R.string.qemu_ssh_username_hint)
            setSingleLine(true)
            setText("root")
        }
        val managerSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, QEMU_PKG_MANAGERS)
        }
        val pkgInput = EditText(ctx).apply {
            hint = getString(R.string.qemu_install_pkg_name_hint)
            setSingleLine(true)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
            addView(TextView(ctx).apply { text = getString(R.string.qemu_ssh_username_hint); textSize = 12f })
            addView(userInput)
            addView(TextView(ctx).apply { text = getString(R.string.qemu_install_pkg_manager_label); textSize = 12f; setPadding(0, dp(12), 0, 0) })
            addView(managerSpinner)
            addView(TextView(ctx).apply { text = getString(R.string.qemu_install_pkg_name_hint); textSize = 12f; setPadding(0, dp(12), 0, 0) })
            addView(pkgInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.qemu_install_pkg_title))
            .setMessage(getString(R.string.qemu_install_pkg_message))
            .setView(layout)
            .setPositiveButton(getString(R.string.qemu_install_pkg_button)) { _, _ ->
                val user = userInput.text.toString().trim().ifEmpty { "root" }
                val pkg = pkgInput.text.toString().trim()
                if (pkg.isEmpty()) { toast(getString(R.string.qemu_toast_pkg_name_required)); return@setPositiveButton }
                // Comillas simples alrededor de $pkg en el propio comando remoto (dentro de las
                // comillas dobles que arma installPackageInVm()) — suficiente para nombres de
                // paquete reales (sin espacios/comillas), consistente con el resto de este
                // Fragment (ej. createImage()/resizeImage() tampoco sanitizan más allá de eso).
                val installCmd = if (managerSpinner.selectedItemPosition == 1) {
                    "apk add --no-cache '$pkg'"
                } else {
                    "sudo -n apt-get update && sudo -n apt-get install -y '$pkg' || (apt-get update && apt-get install -y '$pkg')"
                }
                installPackageInVm(user, installCmd)
            }
            .setNegativeButton(getString(R.string.qemu_cancel), null)
            .show()
    }

    private fun installPackageInVm(user: String, installCmd: String) {
        launchTerminalCommand(
            "ssh -p $QEMU_SSH_PORT -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null '$user@localhost' \"$installCmd\"",
            sessionName = getString(R.string.qemu_session_name_install)
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
        // allowBackground=true (pedido explícito del usuario): imágenes de
        // disco QEMU pueden pesar varios GB — antes el diálogo no-cancelable bloqueaba toda la
        // pantalla hasta terminar. Ahora se puede mandar a 2do plano y navegar libremente.
        progress.show(getString(R.string.qemu_download_progress_title, getString(entry.nameResId)), getString(R.string.qemu_connecting), allowBackground = true)

        Thread {
            imagesDir.mkdirs()
            val tmp = File(imagesDir, "${entry.fileName}.part")
            val error = try {
                downloadFileWithProgress(entry.url, tmp, appContext) { pct, message ->
                    runOnMain { progress.updateProgress(pct, message) }
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
            runOnMain {
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

}
