package com.termux.app.ui

import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.data.ModuleRegistry
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.TunnelManager
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.showProjectsMenu
import com.termux.shared.termux.TermuxConstants
import java.io.File

class N8nFragment : BaseModuleFragment() {
    override fun getModuleId() = "n8n"
    override fun getModuleName() = getString(R.string.n8n_module_name)

    private lateinit var versionValue: TextView
    private lateinit var tunnelUrlValue: TextView
    private lateinit var statusPillSlot: LinearLayout
    private lateinit var networkRow: DropdownSwitchRow
    private val networkModes get() = listOf(getString(R.string.n8n_network_mode_local), getString(R.string.n8n_network_mode_tunnel))

    // Bug real (2026-08-06, ver docs/humano/humano83.md): esta card mostraba "proot" fijo,
    // sin leer el registry — con n8n instalado en udocker (la variante recomendada desde una
    // ronda anterior), la UI mentía sobre qué entorno se estaba usando de verdad.
    private fun n8nMode(): String =
        com.termux.app.data.ModuleRegistry(requireContext()).load().get("n8n.mode") ?: "proot"

    override fun buildContent() {
        // Instalación silenciosa en segundo plano (pedido 2026-08-13, ver humano101): si n8n
        // no está instalado, el fragment ofrece instalarlo internamente sin bloquear — el
        // usuario elige variante (udocker/proot-distro) y sigue navegando mientras se instala.
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) {
                showSilentInstallVariantDialog()
            }
            return
        }
        addCard(getString(R.string.n8n_card_estado)) {
            addView(infoRow(getString(R.string.n8n_label_entorno), n8nMode()))
            // Dropdown (modo de red) + switch bloqueado — pedido explícito del usuario
            // (2026-08-22, ver docs/humano/humano192.md/humano193.md, ejemplo textual que dio: "n8n
            // podría tener una casilla desplegable con modo localhost y modo cloudflare y el
            // switch dentro"). Reemplaza el botón de acción único que alternaba el modo
            // (docs/humano/humano116.md) + los botones separados "Iniciar n8n"/"Detener" de
            // abajo. El cambio de modo requiere reiniciar n8n para aplicar — antes esto era
            // solo un aviso en un toast ("aplica al próximo inicio"), ahora el propio switch
            // lo hace estructural: mientras n8n está corriendo (switch ON) no se puede tocar
            // el modo sin apagar primero.
            networkRow = dropdownSwitchRow(
                label = getString(R.string.n8n_module_name),
                options = networkModes,
                initialIndex = if (isLocalOnly()) 0 else 1,
                initialOn = false,
                onOptionChosen = { index -> setLocalOnly(index == 0) },
                onSwitchToggled = { on, _ -> onN8nSwitchToggled(on) }
            )
            addView(networkRow.root)
            val versionRow = valueRow(getString(R.string.n8n_label_version), getString(R.string.n8n_dash))
            versionValue = versionRow.second
            addView(versionRow.first)
            val tunnelRow = valueRow(getString(R.string.n8n_label_tunnel_url), getString(R.string.n8n_dash))
            tunnelUrlValue = tunnelRow.second
            addView(tunnelRow.first)
            statusPillSlot = LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.n8n_label_estado)
                    textSize = 13f
                    setTextColor(0xff8888aa.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
                })
                addView(pill(getString(R.string.n8n_status_stopped), false))
            }
            addView(statusPillSlot)
        }
        actionButton(getString(R.string.n8n_btn_open_web), GHOST) {
            openWebServer()
        }
        actionButton(getString(R.string.n8n_btn_view_tunnel_url), GHOST) {
            showTunnelUrlDialog()
        }
        // n8n expone una REST API pública real (GET /api/v1/workflows,
        // POST /workflows/{id}/activate|deactivate) con API Key propia — hasta esta ronda
        // Kairos solo la usaba para el healthcheck de arranque (curl /healthz en start.sh),
        // sin ninguna forma de ver o gestionar workflows sin abrir el webview completo. Ver
        // com.termux.app.util.N8nApiClient (patrón HTTP calcado de OllamaApiClient).
        actionButton(getString(R.string.n8n_btn_workflows), GHOST) {
            if (!com.termux.app.util.N8nApiClient.hasApiKey()) {
                toast(getString(R.string.n8n_toast_configure_api_key))
                showApiKeyDialog()
                return@actionButton
            }
            showWorkflowsList()
        }
        // GET /executions — confirmado real contra docs.n8n.io/connect/n8n-api/execution
        // (auditoría 2026-08-25), sin cubrir hasta ahora: ver si la última corrida de un
        // workflow falló, sin abrir el webview completo.
        actionButton(getString(R.string.n8n_btn_view_executions), GHOST) {
            if (!com.termux.app.util.N8nApiClient.hasApiKey()) {
                toast(getString(R.string.n8n_toast_configure_api_key))
                showApiKeyDialog()
                return@actionButton
            }
            showExecutionsList()
        }
        actionButton(getString(R.string.n8n_btn_api_key), GHOST) { showApiKeyDialog() }
        actionButton(getString(R.string.n8n_btn_view_logs), GHOST) {
            // Bug real (2026-08-06, ver docs/humano/humano83.md): mismo patrón ya corregido en
            // "Actualizar n8n" — hardcodeado a la ruta de la variante proot, sin importar cuál
            // se instaló de verdad.
            val script = if (n8nMode() == "udocker") "~/scripts/n8n-udocker/log.sh" else "~/scripts/n8n/n8n_log.sh"
            launchTerminalCommand("bash $script")
        }
        actionButton(getString(R.string.n8n_btn_backup_workflows), GHOST) {
            runBackupWorkflows()
        }
        // n8n no tenía ninguna forma de traer una carpeta de workflows/credenciales desde
        // afuera hacia ~/proyectos (ni symlink ni copia) — pedido explícito 2026-08-14: el
        // mismo menú compartido (symlink/copiar/sincronizar) que ya usan Claude/Codex/
        // OpenCode/Antigravity/OpenClaw/Hermes también debe cubrir n8n. Sin onLaunchInProject
        // porque n8n no abre una carpeta como CLI — es un servidor, no hay "cd && n8n".
        actionButton(getString(R.string.n8n_btn_manage_projects), GHOST) {
            showProjectsMenu(onToast = { toast(it) })
        }
        actionButton(getString(R.string.n8n_btn_update), GHOST) {
            // Bug real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): este botón
            // corría SIEMPRE "~/scripts/n8n/n8n_update.sh" (el script de la variante proot) sin
            // importar con qué variante se instaló n8n — si el usuario instaló la variante
            // udocker (que vive en "~/scripts/n8n-udocker/update.sh", una ruta distinta), el
            // botón intentaba correr un script que no existe. Se elige el script real según
            // "n8n.mode" del registry (escrito por n8n.sh al terminar la instalación).
            showUpdateN8nConfirm()
        }
        // submenu_n8n() en termux-ai-stack-dev/scripts/menu_proot.sh (opcion [9][t]) permite
        // fijar un token de Cloudflare Tunnel para tener una URL publica fija en vez de una
        // temporal que cambia cada reinicio, aca faltaba por completo, el usuario solo podia
        // ver la URL actual (arriba), no configurar el token que la hace fija.
        actionButton(getString(R.string.n8n_btn_cf_token), GHOST) {
            showCfTokenDialog()
        }
        // Opcion [d] de submenu_n8n() en termux-ai-stack-dev/scripts/menu_proot.sh, faltaba por
        // completo en la app (ver docs/humano/humano88.md) — permite fijar N8N_WEBHOOK_URL en
        // ~/.env_n8n para que los webhooks de n8n se construyan con un dominio propio en vez del
        // subdominio *.trycloudflare.com temporal. modulos/n8n.sh ya lo soportaba en modo proot;
        // se agrego soporte en modo udocker en esta misma ronda.
        actionButton(getString(R.string.n8n_btn_webhook_domain), GHOST) {
            showWebhookDomainDialog()
        }
        // Opcion [p] del mismo submenu, alterna ~/.n8n_protocol entre http/https (n8n en
        // modo proot arranca en un protocolo u otro leyendo ese archivo). Solo aplica al modo
        // proot, pero escribir el archivo es inofensivo si el usuario esta en udocker.
        actionButton(getString(R.string.n8n_btn_protocol), GHOST) {
            showProtocolDialog()
        }
        // Paridad con "[r] Reparar scripts de control" de la TUI original
        // (termux-ai-stack-dev/scripts/menu_proot.sh, _n8n_repair_scripts()) — faltaba por
        // completo en Kairos. Regenera SOLO start/stop/log/status/update/backup (según la
        // variante instalada, udocker o proot) vía "modulos/n8n.sh --repair-scripts", sin
        // tocar el contenedor/rootfs ni los datos/workflows del usuario. Útil si esos
        // scripts se corrompieron, se borraron a mano, o quedaron desactualizados tras un
        // update de la app pero la instalación de n8n en sí sigue sana.
        actionButton(getString(R.string.n8n_btn_repair_scripts), GHOST) {
            showRepairScriptsConfirm()
        }
        loadInfo()
    }

    private fun localOnlyFlagFile() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".n8n_local_only")

    private fun isLocalOnly(): Boolean = localOnlyFlagFile().exists()

    // Pedido 2026-08-13 (ver docs/humano/humano115.md): n8n arrancaba SIEMPRE el túnel
    // cloudflared si el binario estaba presente (que siempre lo está) — sin forma de usar
    // n8n solo por LAN/localhost:5678. El flag lo leen start.sh (udocker) y
    // start_servidor.sh (proot) de modulos/n8n.sh antes de levantar el túnel.
    private fun setLocalOnly(localOnly: Boolean) {
        Thread {
            val f = localOnlyFlagFile()
            if (localOnly) f.createNewFile() else f.delete()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (localOnly) getString(R.string.n8n_toast_local_mode) else getString(R.string.n8n_toast_tunnel_mode))
            }
        }.start()
    }

    // Reemplaza los botones separados "Iniciar n8n"/"Detener" (2026-08-22, ver
    // docs/humano/humano193.md) — el switch de la fila "n8n" (dropdown de modo de red) ahora
    // controla start/stop. Misma lógica de startModuleService/stopModuleService de siempre.
    private fun onN8nSwitchToggled(on: Boolean) {
        if (on) {
            toast(getString(R.string.n8n_toast_starting))
            startModuleService { ok, _ ->
                toast(if (ok) getString(R.string.n8n_toast_started) else getString(R.string.n8n_toast_start_failed))
                loadInfo()
            }
        } else {
            stopModuleService { ok ->
                toast(if (ok) getString(R.string.n8n_toast_stopped) else getString(R.string.n8n_toast_stop_failed))
                loadInfo()
            }
        }
    }

    // Pedido 2026-08-13 (ver humano101): instalación silenciosa en segundo plano — el usuario
    // elige la variante (udocker = nativo recomendado, proot-distro = Debian) y sigue haciendo
    // otras cosas mientras n8n se instala internamente (ModuleController.installModule, Thread).
    private fun showSilentInstallVariantDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.n8n_install_dialog_title))
            .setMessage(getString(R.string.n8n_install_dialog_message))
            .setItems(arrayOf(getString(R.string.n8n_install_variant_udocker), getString(R.string.n8n_install_variant_proot))) { _, which ->
                val variant = if (which == 0) "udocker" else "proot-distro"
                installModuleInBackground(variant) { ok ->
                    if (ok) {
                        toast(getString(R.string.n8n_toast_installed))
                        refreshView()
                    } else {
                        toast(getString(R.string.n8n_toast_install_failed))
                    }
                }
            }
            .setNegativeButton(getString(R.string.n8n_cancel), null)
            .show()
    }

    private fun refreshView() {
        container.removeAllViews()
        buildContent()
    }

    /**
     * Reemplazo directo de cmd_n8n's "info" (kairos_manager.py) — versión desde el registry,
     * URL de túnel desde ~/.last_cf_url y estado corriendo/detenido desde ModuleController
     * (vía isModuleRunning(), ya usado por todo el resto de la app). Todo lectura de
     * archivos simples + un `tmux has-session`, no hace falta pasar por python3 para esto.
     */
    private fun loadInfo() {
        Thread {
            val version = ModuleRegistry(requireContext()).load().get("n8n.version")
            val tunnelUrl = readFileTrimmed(".last_cf_url")
            val running = isModuleRunning()
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                versionValue.text = version?.ifBlank { getString(R.string.n8n_dash) } ?: getString(R.string.n8n_dash)
                tunnelUrlValue.text = tunnelUrl?.ifBlank { getString(R.string.n8n_dash) } ?: getString(R.string.n8n_dash)
                statusPillSlot.removeViewAt(statusPillSlot.childCount - 1)
                statusPillSlot.addView(pill(if (running) getString(R.string.n8n_status_running) else getString(R.string.n8n_status_stopped), running))
                networkRow.setSwitchState(running)
            }
        }.start()
    }

    /** Lee un archivo de texto de una línea en $HOME (ej. ~/.last_cf_url, ~/.cf_token) — null si no existe o está vacío. */
    private fun readFileTrimmed(name: String): String? {
        return try {
            val file = File(TermuxConstants.TERMUX_HOME_DIR_PATH, name)
            if (!file.exists()) return null
            file.readText().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun showCfTokenDialog() {
        val edit = EditText(requireContext()).apply {
            hint = getString(R.string.n8n_cf_token_hint)
        }
        // Reemplazo directo de cmd_n8n's "token-get" — antes el usuario abría este diálogo
        // sin ninguna pista de si ya tenía un token fijo cargado o no.
        val estadoActual = if (hasFixedCfToken()) getString(R.string.n8n_cf_token_status_fixed) else getString(R.string.n8n_cf_token_status_temp)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.n8n_cf_token_dialog_title))
            .setMessage(getString(R.string.n8n_cf_token_dialog_message, estadoActual))
            .setView(edit)
            .setPositiveButton(getString(R.string.n8n_btn_save)) { _, _ -> writeCfToken(edit.text.toString().trim()) }
            .setNegativeButton(getString(R.string.n8n_cancel), null)
            .show()
    }

    // Puente a Tunnel (ronda 2026-08-25, pedido explícito del usuario): n8n conserva sus
    // propios botones "Token Cloudflare"/"Configurar dominio webhook" en esta pantalla,
    // pero el valor real se guarda en TunnelManager con id "n8n" (mismo lugar que usa la
    // pestaña Tunnel) en vez de en archivos propios de n8n (~/.cf_token, y el dominio
    // dentro de ~/.env_n8n) — un solo sistema de guardado, sin perder el atajo cómodo
    // desde esta pantalla. ~/.env_n8n SIGUE existiendo como espejo real: n8n (el proceso,
    // no la UI) lee N8N_WEBHOOK_URL de ahí para arrancar con ese dominio — no es solo
    // cosmético, así que cada vez que se guarda el dominio acá también se reescribe ese
    // archivo. migrateLegacyCfConfigIfNeeded() mueve UNA VEZ lo que el usuario ya haya
    // configurado antes de este cambio (archivos legacy ~/.cf_token/~/.env_n8n) para no
    // perder configuración ya hecha.
    private fun migrateLegacyCfConfigIfNeeded() {
        val current = TunnelManager.getConfig("cloudflared", "n8n")
        var token = current.token
        var domain = current.domain
        var changed = false
        if (token.isBlank()) {
            val legacyTokenFile = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cf_token")
            if (legacyTokenFile.isFile && legacyTokenFile.length() > 0) {
                try {
                    val legacy = legacyTokenFile.readText().trim()
                    if (legacy.isNotEmpty()) { token = legacy; changed = true }
                } catch (_: Exception) { }
            }
        }
        if (domain.isBlank()) {
            val envFile = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".env_n8n")
            if (envFile.isFile) {
                try {
                    val legacyFull = envFile.readLines()
                        .firstOrNull { it.startsWith("N8N_WEBHOOK_URL=") }
                        ?.substringAfter("N8N_WEBHOOK_URL=")?.trim()?.ifEmpty { null }
                    val bare = legacyFull?.removePrefix("https://")?.removePrefix("http://")
                    if (!bare.isNullOrEmpty()) { domain = bare; changed = true }
                } catch (_: Exception) { }
            }
        }
        if (changed) TunnelManager.saveConfig("cloudflared", token, domain, "n8n")
    }

    private fun hasFixedCfToken(): Boolean {
        migrateLegacyCfConfigIfNeeded()
        return TunnelManager.getConfig("cloudflared", "n8n").token.isNotBlank()
    }

    private fun currentWebhookDomain(): String? {
        migrateLegacyCfConfigIfNeeded()
        return TunnelManager.getConfig("cloudflared", "n8n").domain.trim().ifEmpty { null }
    }

    private fun showWebhookDomainDialog() {
        val current = currentWebhookDomain()
        val edit = EditText(requireContext()).apply {
            hint = getString(R.string.n8n_webhook_domain_hint)
            if (current != null) setText(current.removePrefix("https://").removePrefix("http://"))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.n8n_webhook_domain_dialog_title))
            .setMessage(
                (if (current != null) getString(R.string.n8n_webhook_domain_current, current) else getString(R.string.n8n_webhook_domain_none)) +
                    getString(R.string.n8n_webhook_domain_help)
            )
            .setView(edit)
            .setPositiveButton(getString(R.string.n8n_btn_save)) { _, _ -> writeWebhookDomain(edit.text.toString().trim()) }
            .setNegativeButton(getString(R.string.n8n_cancel), null)
            .show()
    }

    // N8N_WEBHOOK_URL vive en ~/.env_n8n junto a otras posibles claves futuras (formato
    // KEY=VALUE por línea) — a diferencia de ~/.cf_token (un solo valor, un solo archivo), acá
    // hace falta preservar el resto del archivo y solo reemplazar/borrar esa clave puntual.
    //
    // Bug real reportado por el usuario (2026-08-25): el campo pedía escribir "https://" a mano
    // (hint viejo "https://n8n.tudominio.com") — n8n SÍ necesita la URL completa con esquema en
    // WEBHOOK_URL (confirmado en modulos/n8n.sh línea ~256, se pasa tal cual a
    // "--env=WEBHOOK_URL=..."), pero no hay razón para que el usuario tenga que escribirlo a
    // mano. Ahora el diálogo solo pide el dominio pelado y acá se le agrega "https://" — si el
    // usuario ya escribió un esquema (compatibilidad con quien ya tenía el hábito viejo), se
    // respeta tal cual en vez de duplicarlo.
    private fun writeWebhookDomain(domainInput: String) {
        // Dominio PELADO (sin esquema) es lo que se guarda en TunnelManager — coincide con
        // cómo el resto de Tunnel usa "domain" (ver TunnelManager.start()). ~/.env_n8n sigue
        // necesitando la URL completa con esquema para que n8n arranque bien (confirmado en
        // modulos/n8n.sh línea ~256) — se sigue escribiendo acá como espejo real, no cosmético.
        val bareDomain = domainInput.removePrefix("https://").removePrefix("http://").trim()
        val fullUrl = if (bareDomain.isEmpty()) "" else "https://$bareDomain"
        Thread {
            val existingToken = TunnelManager.getConfig("cloudflared", "n8n").token
            val savedOk = TunnelManager.saveConfig("cloudflared", existingToken, bareDomain, "n8n").ok
            val envOk = try {
                val file = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".env_n8n")
                val otherLines = if (file.isFile) {
                    file.readLines().filterNot { it.startsWith("N8N_WEBHOOK_URL=") }
                } else emptyList()
                val newLines = if (fullUrl.isEmpty()) otherLines else otherLines + "N8N_WEBHOOK_URL=$fullUrl"
                file.writeText(newLines.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
                true
            } catch (e: Exception) {
                false
            }
            // Bug real (auditoría terminal adaptada 2026-08-19): este Thread no tenía NINGÚN
            // guard de Fragment-adjunto — mismo patrón/mismo tipo de crash confirmado en
            // docs/humano/humano57.md (IllegalStateException si el usuario navega a otra
            // pantalla mientras el I/O de archivo corre en background).
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(
                    if (savedOk && envOk) {
                        if (fullUrl.isEmpty()) getString(R.string.n8n_toast_domain_deleted) else getString(R.string.n8n_toast_domain_saved)
                    } else {
                        getString(R.string.n8n_toast_domain_save_failed)
                    }
                )
            }
        }.start()
    }

    // Puente a Tunnel (ver migrateLegacyCfConfigIfNeeded() mas arriba) — el token ya NO se
    // escribe a ~/.cf_token via ProcessBuilder (ese camino tenia el bug real reportado
    // 2026-08-25, "error al agregar el token de cloudflare": usaba "bash" relativo en vez de
    // TERMUX_BASH_PATH, que siempre falla — ver mismo patron ya arreglado en
    // MonitorFragment.kt.runStorageSetup() (ex-SystemFragment.kt, fusionado 2026-08-26). Ahora es una escritura directa al registry via
    // TunnelManager, sin subproceso — mas simple y sin ese bug posible.
    //
    // Bug real confirmado 2026-08-26 (auditoría de Túnel, ver docs/estructura/TUNEL.md):
    // esta función guardaba el token SOLO en el registry y BORRABA ~/.cf_token como
    // "limpieza de legacy" — pero modulos/n8n.sh (ambas variantes, proot y udocker, ver
    // líneas ~305 y ~470) sigue leyendo el token de ARRANQUE real desde ~/.cf_token, no del
    // registry (el script bash no tiene forma de leer ~/.android_server_registry). El
    // resultado: el token quedaba "guardado" en la UI pero n8n.sh nunca lo veía y siempre
    // arrancaba con túnel anónimo. Fix real: TunnelManager.saveConfig()/clearConfig() ahora
    // escriben ~/.cf_token como espejo (mirrorBespokeFiles(), ver TunnelManager.kt) — esta
    // función ya no necesita tocar el archivo a mano, solo delegar.
    private fun writeCfToken(token: String) {
        Thread {
            val existingDomain = TunnelManager.getConfig("cloudflared", "n8n").domain
            val result = TunnelManager.saveConfig("cloudflared", token, existingDomain, "n8n")
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(
                    if (result.ok) {
                        if (token.isEmpty()) getString(R.string.n8n_toast_token_deleted) else getString(R.string.n8n_toast_token_saved)
                    } else {
                        getString(R.string.n8n_toast_token_save_failed, result.error)
                    }
                )
            }
        }.start()
    }

    private fun showProtocolDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.n8n_protocol_dialog_title))
            .setItems(arrayOf(getString(R.string.n8n_protocol_https), getString(R.string.n8n_protocol_http))) { _, which ->
                writeProtocol(if (which == 0) "https" else "http")
            }
            .setNegativeButton(getString(R.string.n8n_cancel), null)
            .show()
    }

    private fun writeProtocol(value: String) {
        Thread {
            val ok = try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "echo \"$1\" > ~/.n8n_protocol", "_", value)
                pb.applyTermuxEnv()
                pb.start().waitFor() == 0
            } catch (e: Exception) {
                false
            }
            // Mismo fix que writeWebhookDomain() — ver comentario ahí.
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (ok) getString(R.string.n8n_toast_protocol_changed, value) else getString(R.string.n8n_toast_protocol_failed))
            }
        }.start()
    }

    // Gap real reportado en auditoría 2026-08-27 (docs/humano273.md): abría la terminal solo
    // para leer un archivo (cat ~/.last_cf_url) — contradice la filosofía de producto de Kairos
    // (ver .claude/rules/kairos-product-philosophy.md), no hace falta terminal para esto.
    // Lectura directa del archivo, mismo patrón que localOnlyFlagFile()/webhookDomainFile() en
    // este mismo Fragment.
    private fun showTunnelUrlDialog() {
        val file = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".last_cf_url")
        val url = try {
            if (file.exists()) file.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.n8n_tunnel_url_dialog_title))
            .setMessage(if (url.isNotEmpty()) url else getString(R.string.n8n_tunnel_url_none))
            .setPositiveButton(getString(R.string.n8n_btn_close), null)
            .apply {
                if (url.isNotEmpty()) {
                    setNeutralButton(getString(R.string.n8n_btn_copy)) { _, _ ->
                        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("n8n tunnel URL", url))
                        toast(getString(R.string.n8n_toast_url_copied))
                    }
                }
            }
            .show()
    }

    // Gap real reportado en auditoría 2026-08-27 (docs/humano273.md): backup.sh/n8n_backup.sh
    // son acciones no-interactivas y deterministas (tar + un mensaje final "[OK] Backup: ...")
    // — no hace falta terminal, mismo patrón que runRepairScripts() de más abajo.
    private fun runBackupWorkflows() {
        toast(getString(R.string.n8n_toast_backup_progress))
        val script = if (n8nMode() == "udocker") "scripts/n8n-udocker/backup.sh" else "scripts/n8n/n8n_backup.sh"
        Thread {
            val output = try {
                val scriptFile = File(TermuxConstants.TERMUX_HOME_DIR_PATH, script).absolutePath
                val pb = ProcessBuilder(TERMUX_BASH_PATH, scriptFile)
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                val text = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) text else null
            } catch (e: Exception) {
                null
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val okLine = output?.lineSequence()?.firstOrNull { it.contains("[OK] Backup:") }
                toast(okLine?.removePrefix("[OK] Backup: ") ?: getString(R.string.n8n_toast_backup_failed))
            }
        }.start()
    }

    // Gap real reportado en auditoría 2026-08-27 (docs/humano273.md): n8n_update.sh/update.sh
    // son acciones no-interactivas (npm update / udocker pull+create) pero pueden tardar unos
    // minutos — se pide confirmación antes (como el resto de acciones potencialmente lentas de
    // este Fragment) y se corre en background con el mismo patrón de runRepairScripts().
    private fun showUpdateN8nConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.n8n_update_dialog_title))
            .setMessage(getString(R.string.n8n_update_dialog_message))
            .setPositiveButton(getString(R.string.n8n_btn_update_confirm)) { _, _ -> runUpdateN8n() }
            .setNegativeButton(getString(R.string.n8n_cancel), null)
            .show()
    }

    private fun runUpdateN8n() {
        toast(getString(R.string.n8n_toast_updating))
        val script = if (n8nMode() == "udocker") "scripts/n8n-udocker/update.sh" else "scripts/n8n/n8n_update.sh"
        Thread {
            val output = try {
                val scriptFile = File(TermuxConstants.TERMUX_HOME_DIR_PATH, script).absolutePath
                val pb = ProcessBuilder(TERMUX_BASH_PATH, scriptFile)
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                val text = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) text else null
            } catch (e: Exception) {
                null
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (output != null) {
                    toast(output.lineSequence().lastOrNull { it.isNotBlank() } ?: getString(R.string.n8n_toast_updated_fallback))
                    loadInfo()
                } else {
                    toast(getString(R.string.n8n_toast_update_failed))
                }
            }
        }.start()
    }

    // Equivalente a "[r] Reparar scripts de control" del menú TUI original
    // (termux-ai-stack-dev/scripts/menu_proot.sh, _n8n_repair_scripts()) — pedido explícito,
    // no implementado en una ronda anterior. Regenera SOLO los scripts de control
    // (start/stop/log/status/update/backup, según la variante udocker o proot ya instalada
    // — modulos/n8n.sh detecta el modo real desde el registry) sin tocar el contenedor
    // udocker, el rootfs Debian, ni los datos/workflows del usuario.
    private fun showRepairScriptsConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.n8n_repair_dialog_title))
            .setMessage(getString(R.string.n8n_repair_dialog_message))
            .setPositiveButton(getString(R.string.n8n_btn_repair_confirm)) { _, _ -> runRepairScripts() }
            .setNegativeButton(getString(R.string.n8n_cancel), null)
            .show()
    }

    // Corre "modulos/n8n.sh --repair-scripts --silent" directo (no via ModuleController.
    // installModule(), que solo soporta --variant/--force) — mismo patrón de ProcessBuilder
    // + applyTermuxEnv() que writeProtocol() en este mismo Fragment (writeCfToken() ya no
    // usa ProcessBuilder, ver comentario ahi).
    private fun runRepairScripts() {
        toast(getString(R.string.n8n_toast_repair_progress))
        Thread {
            val ok = try {
                val script = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/n8n.sh").absolutePath
                val pb = ProcessBuilder(TERMUX_BASH_PATH, script, "--repair-scripts", "--silent")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.bufferedReader().readText()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(
                    if (ok) getString(R.string.n8n_toast_repair_success)
                    else getString(R.string.n8n_toast_repair_failed)
                )
            }
        }.start()
    }

    // Bug real (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §1.2): isModuleRunning() llama ModuleController.isRunning(), que corre un
    // ProcessBuilder().waitFor(5, TimeUnit.SECONDS) — bloqueante. Antes se llamaba directo en
    // el hilo de UI (riesgo real de ANR de hasta 5s al tocar este botón). Mismo patrón que
    // loadInfo(): chequeo en un Thread de fondo, resultado posteado a runOnUiThread.
    // La API Key se genera manualmente en la UI de n8n (Settings → n8n API → Create an API
    // key) — no hay forma de emitirla por CLI/API sin haber iniciado sesión primero, así que
    // acá solo se pega/guarda el valor, igual que el patrón ya usado para el token de
    // Cloudflare (showCfTokenDialog()).
    private fun showApiKeyDialog() {
        val edit = EditText(requireContext()).apply {
            hint = getString(R.string.n8n_api_key_hint)
        }
        val estado = if (com.termux.app.util.N8nApiClient.hasApiKey()) getString(R.string.n8n_api_key_status_set) else getString(R.string.n8n_api_key_status_unset)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.n8n_api_key_dialog_title))
            .setMessage(getString(R.string.n8n_api_key_dialog_message, estado))
            .setView(edit)
            .setPositiveButton(getString(R.string.n8n_btn_save)) { _, _ ->
                com.termux.app.util.N8nApiClient.writeApiKey(edit.text.toString().trim())
                toast(getString(R.string.n8n_toast_api_key_updated))
            }
            .setNegativeButton(getString(R.string.n8n_cancel), null)
            .show()
    }

    private fun showWorkflowsList() {
        toast(getString(R.string.n8n_toast_loading_workflows))
        Thread {
            val workflows = try {
                com.termux.app.util.N8nApiClient.listWorkflows()
            } catch (e: Exception) {
                null.also {
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (isAdded) toast(getString(R.string.n8n_toast_error_generic, e.message ?: getString(R.string.n8n_error_default_connect)))
                    }
                }
            }
            if (!isAdded || workflows == null) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (workflows.isEmpty()) {
                    toast(getString(R.string.n8n_toast_no_workflows))
                    return@runOnUiThread
                }
                val labels = workflows.map { w -> "${if (w.active) "✅" else "⚪"} ${w.name}" }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.n8n_workflows_dialog_title, workflows.size))
                    .setItems(labels) { _, which -> showWorkflowActions(workflows[which]) }
                    .setNegativeButton(getString(R.string.n8n_btn_close), null)
                    .show()
            }
        }.start()
    }

    private fun showExecutionsList() {
        toast(getString(R.string.n8n_toast_loading_executions))
        Thread {
            val executions = try {
                com.termux.app.util.N8nApiClient.listExecutions()
            } catch (e: Exception) {
                null.also {
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (isAdded) toast(getString(R.string.n8n_toast_error_generic, e.message ?: getString(R.string.n8n_error_default_connect)))
                    }
                }
            }
            if (!isAdded || executions == null) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (executions.isEmpty()) {
                    toast(getString(R.string.n8n_toast_no_executions))
                    return@runOnUiThread
                }
                val labels = executions.map { e ->
                    val icon = when (e.status) {
                        "success" -> "✅"
                        "error", "crashed" -> "❌"
                        "running", "new" -> "🔄"
                        "waiting" -> "⏳"
                        "canceled" -> "⛔"
                        else -> "•"
                    }
                    "$icon ${e.status} — ${e.startedAt.ifBlank { getString(R.string.n8n_execution_no_date) }}"
                }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.n8n_executions_dialog_title, executions.size))
                    .setItems(labels, null)
                    .setNegativeButton(getString(R.string.n8n_btn_close), null)
                    .show()
            }
        }.start()
    }

    private fun showWorkflowActions(workflow: com.termux.app.util.N8nApiClient.WorkflowSummary) {
        val toggleLabel = if (workflow.active) getString(R.string.n8n_toggle_deactivate) else getString(R.string.n8n_toggle_activate)
        AlertDialog.Builder(requireContext())
            .setTitle(workflow.name)
            .setItems(arrayOf(toggleLabel)) { _, _ -> toggleWorkflow(workflow) }
            .setNegativeButton(getString(R.string.n8n_btn_close), null)
            .show()
    }

    private fun toggleWorkflow(workflow: com.termux.app.util.N8nApiClient.WorkflowSummary) {
        val newActive = !workflow.active
        Thread {
            val ok = try {
                com.termux.app.util.N8nApiClient.setActive(workflow.id, newActive)
                true
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (isAdded) toast(getString(R.string.n8n_toast_error_generic, e.message ?: getString(R.string.n8n_error_default_toggle)))
                }
                false
            }
            if (!isAdded || !ok) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (newActive) getString(R.string.n8n_toast_workflow_activated) else getString(R.string.n8n_toast_workflow_deactivated))
            }
        }.start()
    }

    private fun openWebServer() {
        Thread {
            val running = isModuleRunning()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (running) {
                    navigateTo(ModuleWebViewFragment.newInstance("http://localhost:5678", getString(R.string.n8n_module_name)))
                    return@runOnUiThread
                }
                toast(getString(R.string.n8n_toast_starting))
                // Bug real: startModuleService() YA entrega onDone en el hilo principal con el
                // Fragment confirmado adjunto (ver su KDoc en BaseModuleFragment.kt) — envolverlo
                // en un requireActivity().runOnUiThread{} extra acá encolaba un SEGUNDO post al
                // Looper principal sin volver a chequear isAdded, reabriendo la misma ventana de
                // "Fragment not attached" (docs/humano/humano57.md) que este helper existe para
                // cerrar: si el usuario navegaba fuera justo en ese instante, ese post extra
                // podía ejecutarse con el Fragment ya desadjunto.
                startModuleService { ok, _ ->
                    if (ok) {
                        navigateTo(ModuleWebViewFragment.newInstance("http://localhost:5678", getString(R.string.n8n_module_name)))
                    } else {
                        toast(getString(R.string.n8n_toast_start_failed))
                    }
                }
            }
        }.start()
    }
}
