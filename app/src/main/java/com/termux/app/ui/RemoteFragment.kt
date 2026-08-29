package com.termux.app.ui

import android.graphics.Color
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.VERTICAL
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.ModuleController
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.RemoteManager
import com.termux.app.util.friendlyProcessErrorMessage
import com.termux.app.util.kairosThemeColor

class RemoteFragment : BaseModuleFragment() {
    override fun getModuleId() = "remote"
    override fun getModuleName() = getString(R.string.remote_module_name)

    // Holds the latest remote info — antes venía del JSON de kairos_manager.py, ahora de
    // RemoteManager.info() (migrado 2026-07-31, ver comentario de runRemoteAction()).
    private var remoteInfo: RemoteManager.RemoteInfo? = null

    // Estado "corriendo" de sshd calculado en el hilo de fondo durante el polling
    // (ver auditoría 2026-08-13: antes se chequeaba en updateInfoRows() en el hilo de UI).
    private var remoteRunning: Boolean = false

    // Filas de la card INFO — guardadas como campos para poder actualizarlas
    // después de cada poll (antes se creaban una vez con placeholder y nunca
    // se volvían a tocar, así que la card jamás mostraba datos reales).
    private var sshRow: View? = null
    private var ipRow: View? = null
    private var userRow: View? = null
    private var connRow: View? = null
    private var tunnelRow: View? = null
    // Switches reales (2026-08-22, ver docs/humano/humano193.md) — reemplazan los pares de botones
    // Iniciar/Detener de SSH y del túnel Cloudflare. El comentario viejo de línea ~77
    // ("BaseModuleFragment provides only buttons") ya no aplica — ver
    // BaseModuleFragment.switchRow(). Sincronizados en updateInfoRows() con el estado real
    // del polling (remoteRunning / remoteInfo?.cfRunning), nunca con un estado local propio.
    private var sshSwitch: SwitchRow? = null
    private var tunnelSwitch: SwitchRow? = null

    // Campo donde se pega la IP detectada por el scan de red (no existía — se agregó junto
    // con el botón "Buscar servidor en la red" para que la detección tenga un destino
    // visible; no modifica el flujo SSH/Cloudflare existente).
    private var serverIpInput: android.widget.EditText? = null

    // ── Panel de seguridad SSH (pedido explícito 2026-08-19, ver
    //    docs/ssh/AUDITORIA_PANEL_SEGURIDAD_SSH_2026-08-19.md) ──────────────────
    // Estado leído en vivo de sshd_config (RemoteManager.sshSecurityConfig(), nunca
    // cacheado más allá de esta variable de UI) — se refresca después de cada acción que
    // lo modifica, nunca se asume que el valor mostrado sigue vigente sin releer.
    private var securityConfig: RemoteManager.SshSecurityConfig? = null
    private var secPortRow: View? = null
    private var secPermitRootRow: View? = null
    private var secOwnKeyRow: View? = null
    private var secAuthorizedKeysRow: View? = null

    // Switch real "Requerir clave SSH siempre" (pedido explícito 2026-08-19, ver
    // actualización de docs/ssh/AUDITORIA_PANEL_SEGURIDAD_SSH_2026-08-19.md) —
    // reemplaza el botón "Alternar auth por contraseña" de la ronda anterior por un
    // SwitchCompat visible cuyo estado siempre refleja PasswordAuthentication real de
    // sshd_config (nunca un estado local que pueda desincronizarse, ver refreshSecurityPanel()
    // y BaseModuleFragment/LlamaServerFragment.paramSwitch() para el mismo patrón de estilo).
    // `updatingPasswordAuthSwitch` evita que el listener dispare una acción cuando el switch
    // se actualiza programáticamente desde refreshSecurityPanel()/resetPasswordAuthSwitch().
    private var passwordAuthSwitch: SwitchCompat? = null
    private var updatingPasswordAuthSwitch = false

    // ── Pestañas (reorganización 2026-08-26, pedido explícito del usuario — mockup
    //    aprobado, ver docs/ssh/CLIENTE_SSH_RECEPTOR.md) ──────────────────────────────────
    // Mismo patrón que EntornoFragment.buildTabsSection()/renderTab() (TabLayout programático
    // + reconstrucción de un content container), EXCEPTO que acá el contenido ya se armaba
    // entero con addCard()/actionButton()/switchRow() de BaseModuleFragment — esos helpers
    // siempre agregan a `container` (el ScrollView único del Fragment), no a un parent
    // arbitrario. En vez de reescribir todo ese código para que reciba un parent (alto
    // riesgo de romper algo en una pantalla con mucha lógica de estado real: polling,
    // switches, seguridad SSH), se usa un enfoque de visibilidad: cada pestaña se construye
    // igual que antes (agregando directo a `container`), y section() registra qué vistas de
    // `container` pertenecen a qué pestaña para poder mostrar/ocultar por índice después.
    // Terminología corregida 2026-08-27 (ver docs/humano256.md — bug real confirmado: la app
    // tenía estos dos roles exactamente invertidos respecto al modelo del usuario). Definición
    // real del usuario: "Emisor" = Kairos ACTIVA ssh y da la IP/clave para que ALGUIEN MÁS lo
    // controle (rol de servidor); "Receptor" = NOSOTROS ponemos la IP/clave para controlar
    // OTROS dispositivos/VPS (rol de cliente). Antes estaba al revés (Receptor=servidor,
    // Emisor=cliente) — se renombraron constantes/funciones/comentarios, la lógica de cada
    // pestaña no cambió, solo qué nombre le corresponde a cuál.
    private val TAB_EMISOR = 0
    private val TAB_SEGURIDAD = 1
    private val TAB_CLOUDFLARE = 2
    private val TAB_RED = 3
    private val TAB_RECEPTOR = 4
    private val sectionViews: Array<MutableList<View>> = Array(5) { mutableListOf() }
    private var activeTabIndex = TAB_EMISOR

    /** Corre [block] (que agrega vistas a `container` vía addCard/actionButton/switchRow/etc.)
     *  y registra las vistas nuevas como pertenecientes a la pestaña [tabIndex]. */
    private fun section(tabIndex: Int, block: () -> Unit) {
        val before = container.childCount
        block()
        val after = container.childCount
        for (i in before until after) sectionViews[tabIndex].add(container.getChildAt(i))
    }

    private fun buildTabLayout() {
        val ctx = requireContext()
        val tabLayout = TabLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(4))
            }
            tabMode = TabLayout.MODE_SCROLLABLE
            setSelectedTabIndicatorColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            setTabTextColors(ctx.kairosThemeColor(R.attr.kairosText3), ctx.kairosThemeColor(R.attr.kairosText))
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
        }
        listOf(
            getString(R.string.remote_tab_emisor),
            getString(R.string.remote_tab_seguridad),
            getString(R.string.remote_tab_cloudflare),
            getString(R.string.remote_tab_red),
            getString(R.string.remote_tab_receptor)
        ).forEach {
            tabLayout.addTab(tabLayout.newTab().setText(it))
        }
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

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        buildTabLayout()
        section(TAB_EMISOR) { buildEmisorTab() }
        section(TAB_SEGURIDAD) { buildSeguridadTab() }
        section(TAB_CLOUDFLARE) { buildCloudflareTab() }
        section(TAB_RED) { buildRedTab() }
        section(TAB_RECEPTOR) { buildReceptorTab() }
        renderActiveTab(activeTabIndex)
        refreshSecurityPanel()
        // Start polling to keep the INFO card up‑to‑date
        startPolling()
    }

    // Pestaña "Emisor" — Kairos SIENDO CONTROLADO (todo lo que ya existía en la card INFO
    // + switch SSH + los 5 botones de conexión/claves/contraseña/conexiones/fingerprint).
    private fun buildEmisorTab() {
        // Top card with basic connection info – will be refreshed periodically
        addCard(getString(R.string.remote_card_info)) {
            sshRow = infoRow(getString(R.string.remote_label_ssh), "—").also { addView(it) }
            ipRow = infoRow(getString(R.string.remote_label_ip), "—").also { addView(it) }
            userRow = infoRow(getString(R.string.remote_label_usuario), "—").also { addView(it) }
            connRow = infoRow(getString(R.string.remote_label_conexiones), "—").also { addView(it) }
        }
        // Iniciar/detener SSH ahora pasa por ModuleController (startModuleService/
        // stopModuleService de BaseModuleFragment) en vez de RemoteManager.sshStart()/
        // sshStop() — esas dos funciones corrían exactamente el mismo script
        // ($HOME/scripts/remote/ssh_start.sh / ssh_stop.sh, ver ModuleController.
        // getModuleStartScript()/getModuleStopInfo() para "remote") pero por un camino
        // paralelo sin waitForPortOpen()/ModuleEventBridge.notifySessionEvent() — dos
        // sistemas leyendo/escribiendo el mismo estado de formas distintas, el mismo
        // patrón de bug que ya causó problemas reales en este proyecto (ver
        // .claude/rules/kotlin-kairos-android-patterns.md). El resto de acciones de
        // RemoteManager (info, add-key, password, connections, fingerprint, panel de
        // seguridad) no son lifecycle de módulo — no tienen equivalente en
        // ModuleController y se quedan como están.
        sshSwitch = switchRow(getString(R.string.remote_label_ssh), initialOn = remoteRunning) { on ->
            if (on) {
                startModuleService { ok, output ->
                    if (!ok) sshSwitch?.setSwitchState(false)
                    val msg = if (ok) getString(R.string.remote_msg_ssh_iniciado) else getString(R.string.remote_error_fmt, output.ifEmpty { getString(R.string.remote_msg_no_se_pudo_iniciar) })
                    Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
                    runRemoteAction("info")
                }
            } else {
                stopModuleService { ok ->
                    if (!ok) sshSwitch?.setSwitchState(true)
                    val msg = if (ok) getString(R.string.remote_msg_ssh_detenido) else getString(R.string.remote_msg_error_no_se_pudo_detener)
                    Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
                    runRemoteAction("info")
                }
            }
        }
        container.addView(sshSwitch!!.root)
        actionButton(getString(R.string.remote_btn_info_conexion), GHOST) { runRemoteAction("info", silent = false) }
        actionButton(getString(R.string.remote_btn_agregar_clave_publica), GHOST) { promptAndRun("ssh-add-key") }
        actionButton(getString(R.string.remote_btn_cambiar_contrasena), GHOST) { promptAndRun("ssh-password") }
        // submenu_remote() de termux-ai-stack tiene la opción [5] "Conexiones activas" —
        // cmd_remote ya expone "ssh-connections" (lista de sesiones sshd + PID del daemon)
        // pero ningún Fragment la llamaba todavía. A diferencia del resto de botones de
        // este archivo (que solo muestran un Snackbar), el resultado acá es una lista de
        // largo variable, así que se muestra en un AlertDialog en vez de un Snackbar.
        actionButton(getString(R.string.remote_btn_conexiones_activas), GHOST) { showSshConnections() }
        // El menú original (submenu_remote, opción [3] "Info conexión") solo imprime el
        // comando de conexión en la terminal — acá ya se calculaba en RemoteManager.info()
        // (connectCmd/scpCmd) pero nunca se mostraba ni se podía copiar desde la app; el
        // usuario tenía que transcribirlo a mano desde el diálogo "Info de conexión".
        actionButton(getString(R.string.remote_btn_copiar_comando_ssh), GHOST) { copyConnectCommand() }
        // No existía en ningún menú (ni el original ni este Fragment): la huella de la
        // clave del servidor que el cliente SSH del usuario le va a mostrar ("authenticity
        // of host ... can't be established, are you sure?") en el primer connect — sin
        // forma de verificarla, el usuario solo puede aceptarla a ciegas. Las claves ya las
        // genera `ssh-keygen -A` en el PASO 3 de ssh.sh; esto solo las lee, no genera nada.
        actionButton(getString(R.string.remote_btn_fingerprint), GHOST) { showHostKeyFingerprints() }
        // Bug real (auditoria 2026-08-05, ver docs/humano65.md/humano66.md): ningun modulo sin
        // CLI dedicada (Python/Ollama/n8n/Expo/Remote) tenia forma de actualizar desde la app.
        // Consolidado 2026-08-19 (auditoría de consistencia de menús) en la card MANTENIMIENTO
        // compartida (ver BaseModuleFragment.addMaintenanceCard()) — antes este botón vivía
        // suelto acá Y el Fragment nunca ofrecía "Desinstalar" desde su propia pantalla (a
        // diferencia de GenericModuleFragment, que lo da gratis a cualquier módulo sin
        // pantalla propia).
        addMaintenanceCard()
    }

    // Pestaña "Seguridad" — todo lo que ya existía en la card SEGURIDAD SSH (puerto,
    // requerir-clave, login root, claves autorizadas, clave propia del dispositivo).
    private fun buildSeguridadTab() {
        // Panel de seguridad SSH — pedido explícito del usuario tras la ronda de exposición
        // SSH vía túnel (docs/ssh/AUDITORIA_MEJORA_SSH_VPS_2026-08-19.md): exponer
        // sshd a internet sin ningún control visible de auth/usuario en la UI es un riesgo
        // real. Estado leído en vivo (nunca cacheado) — ver refreshSecurityPanel().
        addCard(getString(R.string.remote_card_seguridad_ssh)) {
            secPortRow = infoRow(getString(R.string.remote_label_puerto), "—").also { addView(it) }
            addView(buildPasswordAuthSwitchRow())
            addView(android.widget.TextView(requireContext()).apply {
                text = getString(R.string.remote_dialog_msg_password_vps_note)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), 0, dp(14), dp(10))
            })
            secPermitRootRow = infoRow(getString(R.string.remote_label_login_root), "—").also { addView(it) }
            secAuthorizedKeysRow = infoRow(getString(R.string.remote_label_claves_autorizadas), "—").also { addView(it) }
            secOwnKeyRow = infoRow(getString(R.string.remote_label_clave_propia_dispositivo), "—").also { addView(it) }
        }
        actionButton(getString(R.string.remote_btn_cambiar_puerto), GHOST) { promptChangePort() }
        actionButton(getString(R.string.remote_btn_alternar_login_root), DANGER) { toggleSshPermitRootLogin() }
        actionButton(getString(R.string.remote_btn_generar_clave_propia_conectar), GHOST) { generateOwnKeypair() }
        actionButton(getString(R.string.remote_btn_copiar_clave_publica_propia), GHOST) { copyOwnPublicKey() }
    }

    // Pestaña "Cloudflare" — todo lo que ya existía en la card CLOUDFLARED.
    private fun buildCloudflareTab() {
        addCard(getString(R.string.remote_card_cloudflared)) {
            tunnelRow = infoRow(getString(R.string.remote_label_tunnel), "—").also { addView(it) }
        }
        tunnelSwitch = switchRow(getString(R.string.remote_switch_tunnel_cloudflare), initialOn = remoteInfo?.cfRunning ?: false) { on ->
            runRemoteAction(if (on) "cf-start" else "cf-stop", silent = false)
        }
        container.addView(tunnelSwitch!!.root)
        actionButton(getString(R.string.remote_btn_configurar_token_cf), GHOST) { promptAndRun("cf-set-token") }
        actionButton(getString(R.string.remote_btn_como_conectarse_cf_ssh), GHOST) { runRemoteAction("cf-info", silent = false) }
    }

    // Pestaña "Red" — todo lo que ya existía en la card SERVIDOR EN LA RED (campo IP +
    // escaneo LAN).
    private fun buildRedTab() {
        // Descubrimiento de servidores en la LAN (pedido 2026-08-14): el módulo Remote se
        // conecta a servidores OpenCode/agentes por IP y hasta ahora el usuario la escribía
        // a mano. Este campo + el botón de abajo escanean la red local buscando el puerto
        // RemoteManager.REMOTE_SCAN_PORT y pegan la IP encontrada acá.
        addCard(getString(R.string.remote_card_servidor_red)) {
            serverIpInput = android.widget.EditText(requireContext()).apply {
                hint = getString(R.string.remote_hint_ip_servidor)
                setSingleLine(true)
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                setHintTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }.also { addView(it) }
        }
        actionButton(getString(R.string.remote_btn_buscar_servidor_red), GHOST) { startSubnetScan() }
    }

    // ── Pestaña "Receptor" — Kairos como CLIENTE SSH, conectándose a OTROS servidores ────────
    // Orden explícita del usuario (ronda 2026-08-26): "ssh es para controlar y ser
    // controlado" — todo lo de arriba es Kairos SIENDO controlado; esta pestaña es la
    // dirección opuesta. MVP real: agregar conexión (host/puerto/usuario/auth) → verla en la
    // lista → conectar abre una sesión de terminal real con `ssh` (launchTerminalCommand(),
    // nunca reimplementado a mano) → borrar. "Monitoreo" barato: un probe TCP al host:puerto
    // antes de conectar, que actualiza "última vez confirmado" (ver
    // RemoteManager.probeAndTouchClientConnection()).
    private var receptorIpRow: View? = null
    private var receptorOwnKeyRow: View? = null
    private var clientListContainer: LinearLayout? = null
    // Card "Claves privadas guardadas" — pedido explícito del owner del proyecto: el
    // Receptor tiene que poder pegar/importar una clave PRIVADA de un tercero (no solo usar la
    // clave propia del dispositivo) y elegir si esa clave se guarda persistentemente o se usa
    // solo para la conexión actual. Ver RemoteManager sección "Claves privadas importadas"
    // para la garantía de seguridad dura (nunca se vuelve a mostrar una clave guardada).
    private var importedKeysContainer: LinearLayout? = null

    private fun buildReceptorTab() {
        val ctx = requireContext()
        addCard(getString(R.string.remote_card_este_dispositivo)) {
            receptorIpRow = infoRow(getString(R.string.remote_label_mi_ip_local), remoteInfo?.ip ?: "—").also { addView(it) }
            receptorOwnKeyRow = infoRow(getString(R.string.remote_label_mi_clave_propia), "—").also { addView(it) }
        }
        actionButton(getString(R.string.remote_btn_generar_clave_propia), GHOST) { generateOwnKeypair() }
        actionButton(getString(R.string.remote_btn_copiar_clave_publica), GHOST) { copyOwnPublicKey() }

        // Claves privadas IMPORTADAS de terceros — distintas de la clave propia de arriba
        // (esa la genera Kairos para IDENTIFICARSE ante otros servidores; estas son claves que
        // YA existen en otro lado y el usuario pega acá para poder usarlas). Una vez guardada,
        // ninguna fila de esta lista ofrece "ver" — solo alias + fingerprint + Reemplazar/Borrar
        // (ver buildImportedKeyRow()).
        addCard(getString(R.string.remote_card_claves_importadas)) {
            importedKeysContainer = LinearLayout(ctx).apply { orientation = VERTICAL }.also { addView(it) }
        }
        actionButton(getString(R.string.remote_btn_importar_clave_privada), GHOST) { promptImportPrivateKey() }
        actionButton(getString(R.string.remote_btn_conectar_clave_sesion), GHOST) { promptEphemeralConnect() }
        renderImportedKeys()

        addCard(getString(R.string.remote_card_servidores_guardados)) {
            clientListContainer = LinearLayout(ctx).apply { orientation = VERTICAL }.also { addView(it) }
        }
        actionButton(getString(R.string.remote_btn_agregar_conexion), GHOST) { promptAddClientConnection() }
        renderClientConnections()
    }


    private fun updateInfoRows() {
        val info = remoteInfo ?: return
        // "corriendo?" viene de ModuleController.isRunning("remote") (mismo mecanismo
        // pgrep sshd que ya usa el switch de la lista de modulos), no del booleano que
        // RemoteManager calcula por su cuenta, para que ambos no puedan divergir.
        // El chequeo se resuelve en el hilo de fondo (runRemoteAction), no acá, para no
        // bloquear el hilo de UI durante el polling cada 5s (ver auditoría 2026-08-13).
        val running = remoteRunning
        setRowValue(sshRow, if (running) getString(R.string.remote_status_corriendo_8022) else getString(R.string.remote_status_detenido))
        setRowValue(ipRow, info.ip)
        setRowValue(userRow, info.user)
        setRowValue(connRow, info.connections.toString())
        setRowValue(tunnelRow, if (info.cfRunning) getString(R.string.remote_status_activo) else if (info.cfHasToken) getString(R.string.remote_status_detenido) else getString(R.string.remote_status_sin_token))
        sshSwitch?.setSwitchState(running)
        tunnelSwitch?.setSwitchState(info.cfRunning)
        // La pestaña Receptor muestra la misma IP local (info.ip ya viene del mismo poll de
        // RemoteManager.info(), no se vuelve a resolver).
        setRowValue(receptorIpRow, info.ip)
    }

    private fun setRowValue(row: View?, value: String) {
        val group = row as? LinearLayout ?: return
        (group.getChildAt(1) as? android.widget.TextView)?.text = value
    }

    // ---------------------------------------------------------------------
    // Helper: corre una acción de RemoteManager en background thread.
    // silent=true (default, usado por el polling periodico) no muestra el
    // Snackbar "OK" cada 5s; silent=false lo muestra para acciones del usuario.
    // Migrado 2026-07-31 de "python3 kairos_manager.py remote <accion>" a Kotlin puro
    // (RemoteManager) — evidencia real de dispositivo (capturas) mostraba el tab Remote
    // roto exactamente por el bug sistémico "Cannot run program python3" (ver
    // ProcesosFragment.kt, migrado por el mismo motivo en la ronda anterior). El
    // contrato de datos hacia la UI (updateInfoRows/showSshConnections) no cambió.
    // ---------------------------------------------------------------------
    private fun runRemoteAction(action: String, vararg extraArgs: String, silent: Boolean = true) {
        Thread {
            try {
                val (ok, error) = dispatchRemoteAction(action, extraArgs)
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (action == "info" && ok) updateInfoRows()
                    if (!silent || !ok) {
                        val msg = if (ok) getString(R.string.remote_btn_ok) else getString(R.string.remote_error_fmt, error.ifEmpty { getString(R.string.remote_error_unknown) })
                        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    Snackbar.make(requireView(), friendlyProcessErrorMessage(e, getString(R.string.remote_ctx_ssh_cloudflared)), Snackbar.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // Traduce cada acción del contrato viejo (nombre de acción + args de texto libre) a
    // la llamada tipada correspondiente de RemoteManager. Devuelve (ok, error) — igual
    // de genérico que el (ok, error) que ya leía runRemoteAction() del JSON de Python.
    private fun dispatchRemoteAction(action: String, extraArgs: Array<out String>): Pair<Boolean, String> {
        val value = extraArgs.firstOrNull().orEmpty()
        return when (action) {
            "info" -> {
                remoteInfo = RemoteManager.info()
                remoteRunning = try {
                    ModuleController.isRunning("remote")
                } catch (_: Exception) {
                    false
                }
                true to ""
            }
            "ssh-add-key" -> RemoteManager.sshAddKey(value).let { it.ok to it.error }
            "ssh-password" -> RemoteManager.sshSetPassword(value).let { it.ok to it.error }
            "cf-start" -> RemoteManager.cfStart().let { it.ok to it.error }
            "cf-stop" -> RemoteManager.cfStop().let { it.ok to it.error }
            "cf-set-token" -> RemoteManager.cfSetToken(value).let { it.ok to it.error }
            "cf-info" -> {
                RemoteManager.cfInfo()
                true to ""
            }
            else -> false to getString(R.string.remote_msg_accion_desconocida, action)
        }
    }

    private fun showSshConnections() {
        Thread {
            try {
                val result = RemoteManager.sshConnections()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    val body = StringBuilder()
                    body.append(if (result.daemonRunning) getString(R.string.remote_msg_daemon_activo) else getString(R.string.remote_msg_daemon_detenido))
                    if (result.connections.isEmpty()) {
                        body.append(getString(R.string.remote_msg_sin_conexiones_activas))
                    } else {
                        result.connections.forEach { body.append(it).append("\n") }
                    }
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.remote_dialog_title_conexiones_ssh_activas))
                        .setMessage(body.toString())
                        .setPositiveButton(getString(R.string.remote_btn_cerrar), null)
                        .show()
                }
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    Snackbar.make(requireView(), friendlyProcessErrorMessage(e, getString(R.string.remote_ctx_ssh)), Snackbar.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // Copia el comando "ssh -p 8022 user@ip" al portapapeles. Si todavía no llegó ningún
    // poll (remoteInfo null, apenas se abrió la pantalla), pide un refresh primero en vez
    // de copiar un comando vacío/desactualizado.
    private fun copyConnectCommand() {
        val info = remoteInfo
        if (info == null) {
            toast(getString(R.string.remote_msg_esperando_datos))
            return
        }
        copyToClipboard(info.connectCmd)
        toast(getString(R.string.remote_msg_comando_copiado, info.connectCmd))
    }

    // Lee (sin generar) la huella de cada clave de host SSH ya creada por la instalación,
    // para que el usuario pueda compararla contra el warning de "authenticity of host"
    // que le muestra su cliente SSH antes de aceptarlo ciegamente.
    private fun showHostKeyFingerprints() {
        Thread {
            val fingerprints = try {
                RemoteManager.sshHostKeyFingerprints()
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    Snackbar.make(requireView(), friendlyProcessErrorMessage(e, getString(R.string.remote_ctx_ssh)), Snackbar.LENGTH_LONG).show()
                }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val body = if (fingerprints.isEmpty()) {
                    getString(R.string.remote_msg_no_claves_host)
                } else {
                    fingerprints.joinToString("\n\n")
                }
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.remote_dialog_title_huella_servidor))
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.remote_btn_cerrar), null)
                    .show()
            }
        }.start()
    }

    // Lee sshd_config real en un hilo de fondo y actualiza las 5 filas de la card "SEGURIDAD
    // SSH" — se llama una vez al construir la pantalla y de nuevo después de cada acción que
    // cambia la config (nunca se asume que el valor mostrado sigue vigente sin releer, ver
    // docstring de RemoteManager.SshSecurityConfig).
    private fun refreshSecurityPanel() {
        Thread {
            val config = try {
                RemoteManager.sshSecurityConfig()
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    Snackbar.make(requireView(), friendlyProcessErrorMessage(e, getString(R.string.remote_ctx_seguridad_ssh)), Snackbar.LENGTH_LONG).show()
                }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                securityConfig = config
                setRowValue(secPortRow, config.port.toString())
                val passRed = requireContext().kairosThemeColor(R.attr.kairosRed)
                val greenColor = requireContext().kairosThemeColor(R.attr.kairosGreen)
                // El switch está "activado" cuando se REQUIERE clave siempre, es decir cuando
                // PasswordAuthentication está deshabilitada — inverso de config.passwordAuthEnabled.
                // updatingPasswordAuthSwitch evita que este set programático dispare el listener.
                updatingPasswordAuthSwitch = true
                passwordAuthSwitch?.isChecked = !config.passwordAuthEnabled
                updatingPasswordAuthSwitch = false
                (secPermitRootRow as? LinearLayout)?.let { row ->
                    (row.getChildAt(1) as? android.widget.TextView)?.apply {
                        text = if (config.permitRootLogin) getString(R.string.remote_status_permitido) else getString(R.string.remote_status_bloqueado)
                        setTextColor(if (config.permitRootLogin) passRed else greenColor)
                    }
                }
                setRowValue(secAuthorizedKeysRow, if (config.hasAuthorizedKeys) getString(R.string.remote_status_si_al_menos_una) else getString(R.string.remote_status_ninguna))
                setRowValue(secOwnKeyRow, if (config.hasOwnKeypair) getString(R.string.remote_status_ya_generada) else getString(R.string.remote_status_no_generada))
                // La pestaña Receptor muestra la misma clave propia (RemoteManager.sshSecurityConfig
                // es la única fuente real, ver docstring de securityConfig) — se actualiza acá
                // también para no leer sshd_config/ssh-keygen dos veces por el mismo dato.
                setRowValue(receptorOwnKeyRow, if (config.hasOwnKeypair) getString(R.string.remote_status_ya_generada) else getString(R.string.remote_status_no_generada))
            }
        }.start()
    }

    private fun promptChangePort() {
        val ctx = requireContext()
        val edit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.remote_hint_puerto_range)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(securityConfig?.port?.toString() ?: "")
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.remote_dialog_title_cambiar_puerto_ssh))
            .setMessage(getString(R.string.remote_dialog_msg_cambiar_puerto))
            .setView(edit)
            .setPositiveButton(getString(R.string.remote_btn_guardar)) { _, _ ->
                val port = edit.text.toString().trim().toIntOrNull()
                if (port == null) {
                    toast(getString(R.string.remote_msg_puerto_invalido))
                    return@setPositiveButton
                }
                runSecurityAction { RemoteManager.sshSetPort(port) }
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    // Fila del switch "Requerir clave SSH siempre" — mismo estilo (thumb/track tintados) que
    // LlamaServerFragment.paramSwitch()/OllamaConfigFragment.lanSwitch. El listener delega en
    // onPasswordAuthSwitchToggled(); ver docstring de passwordAuthSwitch más arriba para el
    // guard updatingPasswordAuthSwitch.
    private fun buildPasswordAuthSwitchRow(): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(2))
        }
        row.addView(android.widget.TextView(ctx).apply {
            text = getString(R.string.remote_label_requerir_clave_ssh)
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val switch = SwitchCompat(ctx).apply {
            thumbTintList = ContextCompat.getColorStateList(ctx, R.color.switch_thumb_color)
            trackTintList = ContextCompat.getColorStateList(ctx, R.color.switch_track_color)
            setOnCheckedChangeListener { _, isChecked ->
                if (updatingPasswordAuthSwitch) return@setOnCheckedChangeListener
                onPasswordAuthSwitchToggled(isChecked)
            }
        }
        passwordAuthSwitch = switch
        row.addView(switch)
        return row
    }

    // requireKeyOnly=true → activar el switch → deshabilitar PasswordAuthentication.
    // Guardrail en 2 capas para no dejar al usuario sin acceso por accidente:
    //  1. Acá en la UI, ANTES de mostrar el diálogo de confirmación — si no hay ninguna clave
    //     en authorized_keys, se bloquea el toggle de inmediato con un Toast claro y el switch
    //     vuelve a su posición real (nunca queda "activado" visualmente sin que el cambio haya
    //     aplicado).
    //  2. RemoteManager.sshSetPasswordAuth(false) server-side (ver docstring ahí) — rechaza la
    //     operación igual aunque este chequeo de UI se saltee por algún camino no previsto.
    // requireKeyOnly=false → desactivar el switch → volver a permitir contraseña, sin
    // restricción ni confirmación (pedido explícito del usuario).
    private fun onPasswordAuthSwitchToggled(requireKeyOnly: Boolean) {
        if (!requireKeyOnly) {
            runSecurityAction { RemoteManager.sshSetPasswordAuth(true) }
            return
        }
        if (securityConfig?.hasAuthorizedKeys != true) {
            toast(getString(R.string.remote_msg_agrega_clave_primero))
            resetPasswordAuthSwitch()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remote_dialog_title_requerir_clave_siempre))
            .setMessage(getString(R.string.remote_dialog_msg_requerir_clave))
            .setPositiveButton(getString(R.string.remote_btn_requerir_clave)) { _, _ ->
                runSecurityAction { RemoteManager.sshSetPasswordAuth(false) }
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar)) { _, _ -> resetPasswordAuthSwitch() }
            .setOnCancelListener { resetPasswordAuthSwitch() }
            .show()
    }

    // Vuelve el switch a lo que sshd_config realmente dice ahora mismo — se usa cuando el
    // usuario cancela el diálogo o cuando se bloquea el toggle por falta de authorized_keys,
    // para que el switch nunca quede mostrando un estado que no aplicó de verdad.
    private fun resetPasswordAuthSwitch() {
        val enabled = securityConfig?.passwordAuthEnabled ?: true
        updatingPasswordAuthSwitch = true
        passwordAuthSwitch?.isChecked = !enabled
        updatingPasswordAuthSwitch = false
    }

    // Default seguro: PermitRootLogin no (ver ssh.sh PASO 3). Activarlo muestra una
    // advertencia explícita antes de tocar nada — nunca se activa sin que el usuario
    // confirme a propósito.
    private fun toggleSshPermitRootLogin() {
        val current = securityConfig?.permitRootLogin ?: false
        val next = !current
        if (next) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.remote_dialog_title_permitir_login_root))
                .setMessage(getString(R.string.remote_dialog_msg_permitir_login_root))
                .setPositiveButton(getString(R.string.remote_btn_permitir_de_todos_modos)) { _, _ ->
                    runSecurityAction { RemoteManager.sshSetPermitRootLogin(true) }
                }
                .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
                .show()
        } else {
            runSecurityAction { RemoteManager.sshSetPermitRootLogin(false) }
        }
    }

    private fun generateOwnKeypair() {
        toast(getString(R.string.remote_msg_generando_clave_propia))
        Thread {
            val result = try {
                RemoteManager.sshGenerateOwnKeypair()
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    Snackbar.make(requireView(), friendlyProcessErrorMessage(e, getString(R.string.remote_ctx_ssh)), Snackbar.LENGTH_LONG).show()
                }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Snackbar.make(requireView(), if (result.ok) result.message else getString(R.string.remote_error_fmt, result.error), Snackbar.LENGTH_SHORT).show()
                if (result.ok) refreshSecurityPanel()
            }
        }.start()
    }

    private fun copyOwnPublicKey() {
        val key = securityConfig?.ownPublicKey
        if (key.isNullOrBlank()) {
            toast(getString(R.string.remote_msg_no_clave_propia_generar_primero))
            return
        }
        copyToClipboard(key)
        toast(getString(R.string.remote_msg_clave_publica_copiada))
    }

    // Corre una acción de RemoteManager que modifica sshd_config, refresca el panel y
    // muestra el resultado — mismo Thread+guard de Fragment-adjunto que runRemoteAction(),
    // separado porque estas acciones devuelven ActionResult tipado directo (no pasan por
    // dispatchRemoteAction) y siempre deben refrescar el panel de seguridad al terminar.
    private fun runSecurityAction(action: () -> RemoteManager.ActionResult) {
        Thread {
            val result = try {
                action()
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    Snackbar.make(requireView(), friendlyProcessErrorMessage(e, getString(R.string.remote_ctx_seguridad_ssh)), Snackbar.LENGTH_LONG).show()
                }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Snackbar.make(requireView(), if (result.ok) result.message else getString(R.string.remote_error_fmt, result.error), Snackbar.LENGTH_LONG).show()
                if (result.ok) refreshSecurityPanel()
            }
        }.start()
    }

    // Prompt the user for a single string argument (e.g., key or password) and run the action.
    private fun promptAndRun(action: String) {
        // For brevity we use a simple dialog with an EditText.
        val ctx = requireContext()
        val edit = android.widget.EditText(ctx)
        edit.hint = getString(R.string.remote_hint_valor)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(action)
            .setView(edit)
            .setPositiveButton(getString(R.string.remote_btn_ok)) { _, _ ->
                val value = edit.text.toString()
                if (value.isNotBlank()) runRemoteAction(action, value, silent = false)
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    // Escaneo de la LAN en busca de servidores OpenCode/agentes escuchando en
    // RemoteManager.REMOTE_SCAN_PORT. TODO el scan corre en un Thread de fondo (nunca red en
    // el main thread — ver PRECAUCIÓN); cada servidor encontrado se anuncia en vivo con un
    // Toast y al final se ofrece elegir en un AlertDialog (radio list) que pega la IP en el
    // campo serverIpInput. Sin WiFi o sin resultados se avisa con Toast, no con red.
    private fun startSubnetScan() {
        val ctx = requireContext()
        Thread {
            if (!RemoteManager.isOnWifi(ctx)) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { toast(getString(R.string.remote_msg_conectate_wifi)) }
                return@Thread
            }
            val found = try {
                RemoteManager.scanSubnet(ctx) { server ->
                    if (!isAdded) return@scanSubnet
                    requireActivity().runOnUiThread { toast(getString(R.string.remote_msg_encontrado, server.host)) }
                }
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    Snackbar.make(requireView(), friendlyProcessErrorMessage(e, getString(R.string.remote_ctx_scan_red)), Snackbar.LENGTH_LONG).show()
                }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (found.isEmpty()) {
                    toast(getString(R.string.remote_msg_no_servidores_encontrados))
                } else {
                    showServerPicker(found)
                }
            }
        }.start()
    }

    // Radio list con los servidores encontrados por el scan; elegir uno pega su IP en el
    // campo serverIpInput y la copia al portapapeles para poder pegarla donde se necesite.
    private fun showServerPicker(servers: List<RemoteManager.RemoteServer>) {
        val names = servers.map { it.host }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remote_dialog_title_servidores_encontrados, servers.size))
            .setSingleChoiceItems(names, 0) { dialog, which ->
                val server = servers[which]
                serverIpInput?.setText(server.host)
                copyToClipboard(server.host)
                toast(getString(R.string.remote_msg_ip_copiada, server.host))
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("RemoteServer", text))
    }

    // ── Pestaña "Receptor" — helpers del cliente SSH ─────────────────────────────────────

    /** Reconstruye la lista de "SERVIDORES GUARDADOS" leyendo el registry en un hilo de
     *  fondo — mismo patrón de lectura que refreshSecurityPanel() (nunca cacheado en un
     *  campo que pueda desincronizarse; RemoteManager.listClientConnections() relee el
     *  registry en cada llamada). */
    private fun renderClientConnections() {
        Thread {
            val connections = try {
                RemoteManager.listClientConnections()
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    Snackbar.make(requireView(), friendlyProcessErrorMessage(e, getString(R.string.remote_ctx_conexiones_guardadas)), Snackbar.LENGTH_LONG).show()
                }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val list = clientListContainer ?: return@runOnUiThread
                list.removeAllViews()
                val ctx = requireContext()
                if (connections.isEmpty()) {
                    list.addView(android.widget.TextView(ctx).apply {
                        text = getString(R.string.remote_msg_sin_conexiones_guardadas)
                        textSize = 12f
                        setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                        setPadding(dp(14), dp(10), dp(14), dp(12))
                    })
                } else {
                    connections.forEach { conn -> list.addView(buildClientConnectionRow(conn)) }
                }
            }
        }.start()
    }

    /** Fila de una conexión guardada: alias + host:puerto + pill de "última vez confirmado"
     *  + botones Conectar/Borrar. `pill()`/`createActionButton()` (BaseModuleFragment) no
     *  agregan a `container` por sí solos — a diferencia de `actionButton()`/`addCard()`,
     *  devuelven el View para poder anidarlo acá dentro de la fila propia. */
    private fun buildClientConnectionRow(conn: RemoteManager.SshClientConnection): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(android.widget.TextView(ctx).apply {
            text = "${conn.alias}\n${conn.user}@${conn.host}:${conn.port}"
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val lastSeenLabel = if (conn.lastConnectedAt <= 0) {
            getString(R.string.remote_status_sin_confirmar)
        } else {
            val minutesAgo = (System.currentTimeMillis() - conn.lastConnectedAt) / 60000
            if (minutesAgo < 1) getString(R.string.remote_status_confirmado_recien) else getString(R.string.remote_status_confirmado_hace_min, minutesAgo.toInt())
        }
        header.addView(pill(lastSeenLabel, conn.lastConnectedAt > 0))
        row.addView(header)

        val buttonsRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        buttonsRow.addView(createActionButton(getString(R.string.remote_btn_conectar), GHOST) { connectToClient(conn) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        buttonsRow.addView(createActionButton(getString(R.string.remote_btn_borrar), DANGER) { confirmDeleteClientConnection(conn) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(buttonsRow)
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBorder))
        })
        return row
    }

    /** Diálogo "Agregar conexión" — host, puerto (editable, default 8022 mismo puerto que
     *  este dispositivo usa por consistencia visual, pero el usuario lo puede cambiar a
     *  cualquier valor 1-65535 incluido el 22 real — pedido explícito: "el tipo de puerto si
     *  no quieren el 22"), usuario, y método de auth: clave propia del dispositivo (default,
     *  como antes), una clave IMPORTADA y guardada (elegida por radio de la lista real —
     *  RemoteManager.listImportedKeys(), nunca hardcodeada), o ninguna (contraseña interactiva).
     *  La lista de claves importadas se lee en un hilo de fondo ANTES de mostrar el diálogo
     *  (mismo patrón que renderClientConnections/renderImportedKeys — nunca red/IO en el hilo
     *  de UI), así el diálogo ya nace con las opciones reales. */
    private fun promptAddClientConnection() {
        Thread {
            val importedKeys = try { RemoteManager.listImportedKeys() } catch (_: Exception) { emptyList() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) showAddClientConnectionDialog(importedKeys)
            }
        }.start()
    }

    private fun showAddClientConnectionDialog(importedKeys: List<RemoteManager.ImportedSshKey>) {
        val ctx = requireContext()
        val view = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        val aliasEdit = android.widget.EditText(ctx).apply { hint = getString(R.string.remote_hint_nombre_opcional) }
        val hostEdit = android.widget.EditText(ctx).apply { hint = getString(R.string.remote_hint_host_o_ip) }
        val portEdit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.remote_hint_puerto)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("22")
        }
        val userEdit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.remote_hint_usuario)
            setText("root")
        }
        listOf(aliasEdit, hostEdit, portEdit, userEdit).forEach { view.addView(it) }

        view.addView(android.widget.TextView(ctx).apply {
            text = getString(R.string.remote_label_metodo_auth)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(10), 0, dp(2))
        })
        val authGroup = android.widget.RadioGroup(ctx).apply { orientation = VERTICAL }
        val ownKeyRadio = android.widget.RadioButton(ctx).apply {
            text = getString(R.string.remote_check_usar_clave_propia)
            isChecked = true
        }
        authGroup.addView(ownKeyRadio)
        // Un RadioButton por clave importada — id de cada RadioButton = índice en la lista
        // (View.generateViewId() para no colisionar con otros ids del layout).
        val importedRadios = importedKeys.map { key ->
            android.widget.RadioButton(ctx).apply {
                id = View.generateViewId()
                text = getString(R.string.remote_check_usar_clave_importada, key.alias, key.fingerprint.take(40))
            }.also { authGroup.addView(it) }
        }
        val passwordRadio = android.widget.RadioButton(ctx).apply {
            text = getString(R.string.remote_check_sin_clave_contrasena)
        }
        authGroup.addView(passwordRadio)
        view.addView(authGroup)
        if (importedKeys.isEmpty()) {
            view.addView(android.widget.TextView(ctx).apply {
                text = getString(R.string.remote_msg_sin_claves_importadas_hint)
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, dp(4), 0, dp(4))
            })
        }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.remote_dialog_title_agregar_conexion_ssh))
            .setView(view)
            .setPositiveButton(getString(R.string.remote_btn_guardar)) { _, _ ->
                val port = portEdit.text.toString().trim().toIntOrNull()
                if (port == null) {
                    toast(getString(R.string.remote_msg_puerto_invalido))
                    return@setPositiveButton
                }
                val chosenImportedKey = importedKeys.zip(importedRadios).firstOrNull { (_, radio) -> radio.isChecked }?.first
                val useOwnKey = ownKeyRadio.isChecked
                val result = RemoteManager.addClientConnection(
                    aliasEdit.text.toString().trim(),
                    hostEdit.text.toString().trim(),
                    port,
                    userEdit.text.toString().trim(),
                    useOwnKey,
                    chosenImportedKey?.id
                )
                if (result.ok) {
                    renderClientConnections()
                } else {
                    toast(getString(R.string.remote_error_fmt, result.error))
                }
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    // ── Claves privadas importadas — UI (Receptor) ────────────────────────────────────────

    /** Reconstruye la lista "CLAVES PRIVADAS IMPORTADAS" — mismo patrón de lectura en background
     *  que renderClientConnections() (nunca cacheado). Solo metadata pública (alias +
     *  fingerprint), nunca contenido — ver garantía de seguridad en RemoteManager. */
    private fun renderImportedKeys() {
        Thread {
            val keys = try { RemoteManager.listImportedKeys() } catch (_: Exception) { emptyList() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val list = importedKeysContainer ?: return@runOnUiThread
                list.removeAllViews()
                val ctx = requireContext()
                if (keys.isEmpty()) {
                    list.addView(android.widget.TextView(ctx).apply {
                        text = getString(R.string.remote_msg_sin_claves_importadas)
                        textSize = 12f
                        setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                        setPadding(dp(14), dp(10), dp(14), dp(12))
                    })
                } else {
                    keys.forEach { key -> list.addView(buildImportedKeyRow(key)) }
                }
            }
        }.start()
    }

    /** Fila de una clave importada guardada: alias + fingerprint (información pública derivada,
     *  no la clave en sí) + botones Reemplazar/Borrar. A PROPÓSITO no hay botón "Ver" — ver
     *  garantía de seguridad dura en RemoteManager, sección "Claves privadas importadas". */
    private fun buildImportedKeyRow(key: RemoteManager.ImportedSshKey): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        row.addView(android.widget.TextView(ctx).apply {
            text = "${key.alias}\n${key.fingerprint}"
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        val buttonsRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        buttonsRow.addView(createActionButton(getString(R.string.remote_btn_reemplazar), GHOST) { promptReplaceImportedKey(key) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        buttonsRow.addView(createActionButton(getString(R.string.remote_btn_borrar), DANGER) { confirmDeleteImportedKey(key) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(buttonsRow)
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBorder))
        })
        return row
    }

    /** Diálogo "Importar clave privada" — SIEMPRE guarda persistentemente (permisos 600 bajo
     *  ~/.ssh/imported/, ver RemoteManager.importPrivateKey()). El botón separado
     *  "Conectar con clave pegada (no guardar)" (promptEphemeralConnect()) es la vía explícita
     *  para el caso "solo esta sesión" — dos botones distintos en vez de un radio dentro del
     *  mismo diálogo, para que la decisión guardar-vs-no-guardar sea inequívoca por el botón
     *  que el usuario toca, no un estado que se pueda dejar sin querer en la posición
     *  equivocada. El campo de texto nunca se pre-rellena con una clave existente — este
     *  diálogo solo IMPORTA claves nuevas, nunca las muestra. */
    private fun promptImportPrivateKey() {
        val ctx = requireContext()
        val view = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        val aliasEdit = android.widget.EditText(ctx).apply { hint = getString(R.string.remote_hint_nombre_opcional) }
        val keyEdit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.remote_hint_clave_privada_pegar)
            minLines = 4
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        view.addView(aliasEdit)
        view.addView(keyEdit)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.remote_dialog_title_importar_clave_privada))
            .setMessage(getString(R.string.remote_dialog_msg_importar_clave_privada))
            .setView(view)
            .setPositiveButton(getString(R.string.remote_btn_guardar)) { _, _ ->
                val alias = aliasEdit.text.toString().trim()
                val keyText = keyEdit.text.toString()
                Thread {
                    val result = RemoteManager.importPrivateKey(alias, keyText)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (result.ok) {
                            toast(getString(R.string.remote_msg_clave_importada))
                            renderImportedKeys()
                        } else {
                            toast(getString(R.string.remote_error_fmt, result.error))
                        }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    /** Reemplazar el CONTENIDO de una clave ya guardada — mismo campo vacío que importar (nunca
     *  pre-rellenado con la clave vieja, ver garantía de seguridad). */
    private fun promptReplaceImportedKey(key: RemoteManager.ImportedSshKey) {
        val ctx = requireContext()
        val keyEdit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.remote_hint_clave_privada_pegar)
            minLines = 4
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.remote_dialog_title_reemplazar_clave, key.alias))
            .setView(keyEdit)
            .setPositiveButton(getString(R.string.remote_btn_guardar)) { _, _ ->
                val keyText = keyEdit.text.toString()
                Thread {
                    val result = RemoteManager.replaceImportedKey(key.id, keyText)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (result.ok) {
                            toast(getString(R.string.remote_msg_clave_reemplazada))
                            renderImportedKeys()
                        } else {
                            toast(getString(R.string.remote_error_fmt, result.error))
                        }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    private fun confirmDeleteImportedKey(key: RemoteManager.ImportedSshKey) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remote_dialog_title_borrar_clave_importada))
            .setMessage(getString(R.string.remote_dialog_msg_borrar_clave_importada, key.alias))
            .setPositiveButton(getString(R.string.remote_btn_borrar_plain)) { _, _ ->
                RemoteManager.deleteImportedKey(key.id)
                renderImportedKeys()
                renderClientConnections()
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    /** "Conectar con clave pegada (no guardar)" — flujo separado y explícito para el caso
     *  "solo esta sesión": pide host/puerto/usuario + la clave, y abre la terminal directo con
     *  RemoteManager.buildEphemeralConnectCommand() (archivo transitorio, se autoborra — ver
     *  docstring en RemoteManager). Esta conexión NO se agrega a "SERVIDORES GUARDADOS" ni la
     *  clave a "CLAVES PRIVADAS IMPORTADAS" — es de un solo uso, a propósito. */
    private fun promptEphemeralConnect() {
        val ctx = requireContext()
        val view = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        val hostEdit = android.widget.EditText(ctx).apply { hint = getString(R.string.remote_hint_host_o_ip) }
        val portEdit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.remote_hint_puerto)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("22")
        }
        val userEdit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.remote_hint_usuario)
            setText("root")
        }
        val keyEdit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.remote_hint_clave_privada_pegar)
            minLines = 4
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        listOf(hostEdit, portEdit, userEdit, keyEdit).forEach { view.addView(it) }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.remote_dialog_title_conectar_clave_sesion))
            .setMessage(getString(R.string.remote_dialog_msg_conectar_clave_sesion))
            .setView(view)
            .setPositiveButton(getString(R.string.remote_btn_conectar)) { _, _ ->
                val port = portEdit.text.toString().trim().toIntOrNull()
                if (port == null) {
                    toast(getString(R.string.remote_msg_puerto_invalido))
                    return@setPositiveButton
                }
                val host = hostEdit.text.toString().trim()
                val user = userEdit.text.toString().trim()
                val keyText = keyEdit.text.toString()
                Thread {
                    val (cmd, result) = RemoteManager.buildEphemeralConnectCommand(user, host, port, keyText)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (result.ok) {
                            launchTerminalCommand(cmd, sessionName = host)
                        } else {
                            toast(getString(R.string.remote_error_fmt, result.error))
                        }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    private fun confirmDeleteClientConnection(conn: RemoteManager.SshClientConnection) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remote_dialog_title_borrar_conexion))
            .setMessage(getString(R.string.remote_dialog_msg_borrar_conexion, conn.alias, conn.user, conn.host, conn.port))
            .setPositiveButton(getString(R.string.remote_btn_borrar_plain)) { _, _ ->
                RemoteManager.deleteClientConnection(conn.id)
                renderClientConnections()
            }
            .setNegativeButton(getString(R.string.remote_btn_cancelar), null)
            .show()
    }

    /**
     * Sonda TCP rápida al host:puerto guardado (RemoteManager.probeAndTouchClientConnection,
     * corre en background — NUNCA red en el hilo de UI) y, responda o no, abre la sesión de
     * terminal real con el comando `ssh` armado por RemoteManager.buildClientConnectCommand()
     * — reusa BaseModuleFragment.launchTerminalCommand(), nunca reimplementa el manejo de
     * terminal. Si el probe falla se avisa con un Snackbar pero se conecta igual: un firewall
     * que bloquea el probe puede seguir dejando pasar SSH real, así que no bloquea el intento.
     */
    private fun connectToClient(conn: RemoteManager.SshClientConnection) {
        toast(getString(R.string.remote_msg_conectando_a, conn.alias))
        Thread {
            val reachable = try {
                RemoteManager.probeAndTouchClientConnection(conn)
            } catch (_: Exception) {
                false
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (!reachable) {
                    Snackbar.make(requireView(), getString(R.string.remote_msg_probe_no_respondio), Snackbar.LENGTH_SHORT).show()
                }
                launchTerminalCommand(RemoteManager.buildClientConnectCommand(conn), sessionName = conn.alias)
                if (reachable) renderClientConnections()
            }
        }.start()
    }

    // Periodically fetch remote info and update the INFO card.
    // Auditoría 2026-07-27: antes handler/poll eran variables locales de este método,
    // sin forma de cancelarlas — el loop seguía re-programándose cada 5s para siempre,
    // incluso después de salir de esta pantalla (fuga real de batería/CPU/procesos en
    // background), a diferencia de ModulesFragment que sí cancela su propio poll.
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            runRemoteAction("info")
            pollHandler.postDelayed(this, 5000)
        }
    }

    private fun startPolling() {
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    override fun onDestroyView() {
        pollHandler.removeCallbacks(pollRunnable)
        super.onDestroyView()
    }
}

