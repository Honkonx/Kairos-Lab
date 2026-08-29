package com.termux.app.wizard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.KairosBootstrap
import com.termux.app.TermuxInstaller
import com.termux.app.util.RootfsInstaller
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.WizardDebugLog
import com.termux.app.util.applyTermuxEnv
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import com.termux.app.util.kairosThemeColor

/** Pantalla 4 del wizard (antes pantalla 2, ver docs/humano54.md — el rediseño 2026-08-04
 * sumó procesos fantasma y batería como pantallas propias antes de esta) — bootstrap de
 * Termux + rootfs (opcional) + kairos.sh, con progreso en vivo. Es la pantalla de mayor
 * riesgo del wizard (toca el flujo real de primer arranque) — se migró acá con la MISMA
 * lógica que ya tenía WizardActivity.java antes del pasaje a ViewPager2/Fragment, cambiando
 * solo el contexto (Activity->Fragment) y agregando los guards isAdded/view!=null ya
 * establecidos en el resto de la app para callbacks async que pueden llegar después de que
 * la vista se destruya. */
class WizardInstallFragment : Fragment() {

    private val STEP_LABELS = arrayOf(
        "Verificando permisos", "Actualizando Termux", "Instalando paquetes core",
        "Instalando compiladores", "Instalando glibc", "Instalando multimedia",
        "Actualizando pip", "Instalando npm globales", "Configurando tema",
        "Creando estructura", "Finalizando"
    )

    private var stepsContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var retryButton: Button? = null
    private var stepRows: Array<View?> = arrayOfNulls(0)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var started = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_wizard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stepsContainer = view.findViewById(R.id.steps_container)
        statusText = view.findViewById(R.id.status_text)
        progressBar = view.findViewById(R.id.progress_bar)
        retryButton = view.findViewById(R.id.button_start)

        buildStepList()

        progressBar?.visibility = View.VISIBLE
        progressBar?.isIndeterminate = false
        progressBar?.max = STEP_LABELS.size
        progressBar?.progress = 0

        statusText?.text = "Preparando entorno..."

        retryButton?.visibility = View.GONE
        retryButton?.text = "Reintentar"
        retryButton?.setOnClickListener { onRetryClicked() }

        // beginInstall() NO se dispara acá a propósito (bug real reportado, ver
        // docs/humano/humano56.md: "la ventana de rootfs no encontrado sale antes de tiempo,
        // en una pantalla antes" + "Cannot run program bash"). ViewPager2 necesita crear la
        // View/Fragment de la página SIGUIENTE antes de que termine la animación de scroll
        // entre páginas — onViewCreated() de esta pantalla puede correr mientras la pantalla
        // 3 (Batería) todavía es la que ve el usuario. FragmentStateAdapter sí garantiza que
        // el ciclo de vida de una página no-actual queda topeado por debajo de RESUMED
        // (STARTED como mucho) — por eso el disparo real se movió a onResume(), que solo
        // llega cuando esta página de verdad pasó a ser la actual.
    }

    override fun onResume() {
        super.onResume()
        if (!started) {
            started = true
            WizardDebugLog.log("WizardInstallFragment", "onResume() — arranca beginInstall()")
            beginInstall()
        }
    }

    private fun beginInstall() {
        WizardDebugLog.log("WizardInstallFragment", "beginInstall(): llamando setupBootstrapIfNeeded()")
        TermuxInstaller.setupBootstrapIfNeeded(requireActivity()) {
            WizardDebugLog.log("WizardInstallFragment", "beginInstall(): callback de setupBootstrapIfNeeded() llegó")
            if (!isAdded) return@setupBootstrapIfNeeded
            handler.post { if (isAdded) statusText?.text = "Finalizando bootstrap de Termux..." }
            Thread {
                WizardDebugLog.log("WizardInstallFragment", "ensureBootstrapSecondStage(): arranca")
                ensureBootstrapSecondStage()
                WizardDebugLog.log("WizardInstallFragment", "ensureBootstrapSecondStage(): terminó")
                if (!isAdded) return@Thread
                handler.post { if (isAdded) statusText?.text = "Extrayendo archivos..." }
                KairosBootstrap.extractAssetsSync(requireContext())
                WizardDebugLog.log("WizardInstallFragment", "extractAssetsSync() terminó")
                handler.post {
                    if (!isAdded) return@post
                    statusText?.text = "Preparando rootfs..."
                    installRootfsThenContinue()
                }
            }.start()
        }
    }

    private fun ensureBootstrapSecondStage() = ensureBootstrapSecondStageStatic()

    companion object {
        /**
         * Dispara el bootstrap crudo de Termux (bash/pkg/apt, sin rootfs ni kairos.sh) sin
         * esperar a esta pantalla — WizardPhantomProcessFragment (página 2, ANTES de esta)
         * necesita `pkg`/`bash`/`adb` ya extraídos para poder instalar nmap/android-tools;
         * bug real reportado (ver docs/humano/humano56.md): "no descarga los paquetes" porque
         * el bootstrap todavía no existía cuando esa pantalla intentaba usarlo. Llamado desde
         * `WizardPermissionsFragment` al avanzar. Idempotente —
         * `TermuxInstaller.setupBootstrapIfNeeded()` ya no hace nada si `$PREFIX` existe, así
         * que volver a llamarlo acá en `beginInstall()` es gratis, no reinstala nada 2 veces.
         */
        @JvmStatic
        fun ensureTermuxBootstrapReady(activity: androidx.fragment.app.FragmentActivity) {
            WizardDebugLog.log("WizardInstallFragment", "ensureTermuxBootstrapReady(): llamando setupBootstrapIfNeeded() desde Permisos")
            TermuxInstaller.setupBootstrapIfNeeded(activity) {
                WizardDebugLog.log("WizardInstallFragment", "ensureTermuxBootstrapReady(): callback llegó, arranca second stage")
                Thread { ensureBootstrapSecondStageStatic() }.start()
            }
        }

        /**
         * El "second stage" del bootstrap de Termux (postinst de busybox/coreutils/npm/
         * openssh/proot-distro/python-pip/termux-exec/etc.) normalmente se dispara vía un
         * script en $PREFIX/etc/profile.d/, que SOLO corre en shells de login — la primera
         * vez que el usuario abre la terminal. ModuleController instala/arranca módulos con
         * ProcessBuilder("bash", script), una shell NO interactiva y NO de login — nunca lo
         * dispara. Forzamos una shell de login desechable acá para garantizar que corra
         * siempre, sin depender de que el usuario abra la terminal.
         */
        private fun ensureBootstrapSecondStageStatic() {
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-l", "-c", "true")
                pb.applyTermuxEnv()
                // applyTermuxEnv() no setea TERM/LANG (no los necesita el resto de call
                // sites) — este shell de login sí los precisa para que el postinst de
                // busybox/coreutils/etc. corra igual que en una terminal interactiva real.
                pb.environment()["TERM"] = "xterm-256color"
                pb.environment()["LANG"] = "en_US.UTF-8"
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.readBytes()
                process.waitFor()
            } catch (_: Exception) {
                // No bloqueante — si falla, el fallback normal de Termux se dispara igual
                // la primera vez que el usuario abra la terminal manualmente.
            }
        }
    }

    /** Rootfs embebido (opcional, ver RootfsInstaller.kt) — si está disponible, deja los
     * paquetes core/build/multimedia de kairos.sh ya listos (checkpoints pre-marcados) y
     * esos pasos se saltan solos. Si falla, no bloquea nada — kairos.sh sigue con
     * pkg install normal, exactamente igual que sin esta clase.
     *
     * El texto que ve el usuario distingue las 2 situaciones reales (pedido explícito,
     * ver docs/humano54.md): "Extrayendo rootfs" si está embebido en el APK (sin red,
     * RootfsInstaller.isEmbedded()), "Descargando e instalando rootfs" si no está embebido
     * pero se puede bajar de la Release — en vez de mostrar tal cual los mensajes internos
     * de RootfsInstaller ("Copiando rootfs embebido…", "Extrayendo paquetes… X%", etc.), que
     * cambian de fase varias veces y no eran los 2 textos fijos que se pidieron. Se conserva
     * el % cuando RootfsInstaller lo informa. */
    private fun installRootfsThenContinue() {
        if (!isAdded) return
        // progressBar pasa a determinado (0-100) apenas RootfsInstaller reporta el primer %
        // real — antes quedaba indeterminado toda la instalación, sin dar ninguna pista de
        // avance durante "Instalando paquetes" (fase silenciosa de 5+ min, ver docs/humano268.md).
        progressBar?.isIndeterminate = true
        progressBar?.max = 100
        val embedded = RootfsInstaller.isEmbedded(requireContext())
        WizardDebugLog.log("WizardInstallFragment", "installRootfsThenContinue(): embedded=$embedded")
        RootfsInstaller.install(
            requireContext(),
            { progress ->
                WizardDebugLog.log("RootfsInstaller.progress", progress)
                handler.post {
                    if (!isAdded) return@post
                    // Se muestra el mensaje real de RootfsInstaller (fase + % si lo trae) en
                    // vez de una etiqueta fija genérica — el usuario pidió saber "por donde
                    // va" (docs/humano268.md), no solo un texto estático de "Extrayendo rootfs".
                    statusText?.text = progress
                    val percent = Regex("(\\d+)%").find(progress)?.groupValues?.get(1)?.toIntOrNull()
                    if (percent != null) {
                        progressBar?.isIndeterminate = false
                        progressBar?.progress = percent
                    } else {
                        progressBar?.isIndeterminate = true
                    }
                }
            },
            { success, message ->
                WizardDebugLog.log("WizardInstallFragment", "RootfsInstaller.install() terminó: success=$success msg=$message")
                handler.post {
                    android.util.Log.i("WizardInstallFragment", "RootfsInstaller: success=$success msg=$message")
                    if (!isAdded) return@post
                    progressBar?.isIndeterminate = false
                    if (success) {
                        statusText?.text = "Configurando KairosApp"
                        runKairosSetup()
                    } else {
                        // Pedido explícito del usuario (docs/humano/humano16.md): si el rootfs no
                        // está disponible ni embebido ni por descarga, la caída al wizard
                        // clásico (pkg install por paquete) NO puede ser automática/silenciosa
                        // — antes esto pasaba directo a runKairosSetup() sin avisar nada.
                        askUseClassicWizard(message)
                    }
                }
            }
        )
    }

    /**
     * Bug real reportado (2026-08-01, ver docs/humano/humano42.md): "apenas no detecta el rootfs
     * lo dice" — el usuario lo interpretó como un problema de timing de UI (el diálogo
     * aparece "demasiado rápido"). Investigado a fondo: NO es un bug de timing —
     * `WizardPermissionsFragment` ya gatea el botón "Continuar" correctamente
     * (`maybeEnableContinue()`, deshabilitado hasta que storage+notificaciones estén
     * resueltos) y esta pantalla solo se abre desde ese click (índice de página 2 en el
     * layout original de 4 pantallas; índice 4 desde el rediseño de docs/humano54.md), así
     * que `WizardInstallFragment`/`beginInstall()` genuinamente arrancan recién ahí, no antes.
     *
     * La causa real: `RootfsInstaller.kt` documenta explícitamente que el repo público de
     * destino `Honkonx/kairos-lab` (ver docs/humano267.md — ningún link de descarga apunta
     * al privado kairos-dev) todavía no existe — la variante liviana del build
     * (`build-app.yml`, sin rootfs embebido en assets/) SIEMPRE va a fallar con 404 al
     * intentar descargarlo en runtime hasta que se cree el repo y se publique la Release.
     * Como ese 404 llega rápido (no hay reintentos ni espera larga), el diálogo "Rootfs no
     * disponible" aparece casi al instante — coincide exacto con "apenas" — pero es el
     * comportamiento ESPERADO mientras kairos-lab no exista, no una falla. El fix real no es
     * de timing sino de mensaje: diferenciar "esta build no incluye el rootfs, es normal"
     * (repo aún no creado, caso conocido) de un error genuino (red caída, checksum inválido,
     * etc.), para que no lea como "algo está roto" cuando en realidad la instalación clásica
     * de siempre sigue funcionando igual.
     */
    private fun askUseClassicWizard(reason: String) {
        if (!isAdded) return
        WizardDebugLog.log("WizardInstallFragment", "askUseClassicWizard(): $reason")
        val isKnownRepoNotYetPublicCase = reason.contains("repositorio kairos-lab todavía no existe")
        val title = if (isKnownRepoNotYetPublicCase) "Instalación rápida no disponible en esta build" else "Rootfs no disponible"
        val message = if (isKnownRepoNotYetPublicCase) {
            "Esta build no incluye el rootfs pre-armado (variante liviana, sin paquetes " +
                "embebidos) — es normal, no un error. La instalación clásica (paquete por " +
                "paquete) hace exactamente lo mismo, solo un poco más lenta.\n\n" +
                "¿Continuar con la instalación clásica?"
        } else {
            "No se pudo obtener el rootfs pre-armado (ni embebido en el APK ni " +
                "descargándolo de GitHub).\n\nDetalle: $reason\n\n" +
                "¿Continuar con la instalación clásica (paquete por paquete, más " +
                "lenta pero no depende del rootfs)?"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Aceptar") { _, _ ->
                if (!isAdded) return@setPositiveButton
                statusText?.text = "Configurando KairosApp"
                runKairosSetup()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                if (!isAdded) return@setNegativeButton
                showError("Instalación cancelada — rootfs no disponible ($reason)")
            }
            .show()
    }

    private fun buildStepList() {
        val container = stepsContainer ?: return
        val n = STEP_LABELS.size
        stepRows = arrayOfNulls(n)
        for (i in 0 until n) {
            val ctx = requireContext()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val num = TextView(ctx).apply {
                text = (i + 1).toString()
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }
            row.addView(num)
            val label = TextView(ctx).apply {
                text = STEP_LABELS[i]
                textSize = 14f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            container.addView(row)
            stepRows[i] = row
        }
    }

    private fun updateStep(current: Int, total: Int, description: String) {
        handler.post {
            if (!isAdded) return@post
            progressBar?.progress = current
            for (i in stepRows.indices) {
                if (i >= total) break
                val row = stepRows[i] as? LinearLayout ?: continue
                val num = row.getChildAt(0) as? TextView ?: continue
                val label = row.getChildAt(1) as? TextView ?: continue
                val ctx = requireContext()
                when {
                    i < current -> {
                        num.text = "✓"
                        setCircleBg(num, "#22C55E")
                        num.setTextColor(Color.WHITE)
                        label.setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                    }
                    i == current -> {
                        num.text = (i + 1).toString()
                        setCircleBg(num, "#3B82F6")
                        num.setTextColor(Color.WHITE)
                        label.setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                        label.text = description.ifEmpty { STEP_LABELS[i] }
                    }
                    else -> {
                        num.text = (i + 1).toString()
                        setCircleBg(num, "#333333")
                        num.setTextColor(Color.parseColor("#555555"))
                        label.setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                    }
                }
            }
        }
    }

    private fun setCircleBg(tv: TextView, color: String) {
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.setColor(Color.parseColor(color))
        tv.background = gd
    }

    /**
     * `applyTermuxEnv()` en vez del bloque de env vars a mano que tenía esto antes (bug real
     * bajo sospecha, ver docs/humano/humano61.md: "sigue el mismo error del rootfs y el
     * wizard"): el bloque manual replicaba HOME/PREFIX/PATH/LD_LIBRARY_PATH pero se olvidaba
     * `SHELL` — hueco documentado en ProcessBuilderExt.kt (cualquier proceso hijo que
     * kairos.sh dispare y que resuelva su shell a partir de `$SHELL`, como tmux o npm, puede
     * caer a un fallback que no existe en Termux si falta). También usaba
     * `System.getenv("HOME")` (el HOME del proceso Android de la app, no el de Termux) en vez
     * de la constante canónica — mismo criterio que usa el resto de la app.
     *
     * `TERMUX_BASH_PATH` (ruta absoluta) en vez de `"bash"` (nombre relativo) — el error
     * "Cannot run program bash" seguía apareciendo pese al fix de arriba (ver
     * docs/humano/humano62.md), y en la misma ronda apareció el mismo error con `apt` en
     * `RootfsInstaller.installDebs()`, que YA usaba `applyTermuxEnv()`. Eso apunta a que la
     * resolución de PATH vía nombre relativo no es 100% confiable justo después de que el
     * bootstrap termina de extraerse en este dispositivo — la ruta absoluta es más robusta
     * sea cual sea la causa exacta. `WizardDebugLog` deja registro de cada paso para
     * confirmar en la próxima corrida si esto lo resuelve del todo.
     */
    private fun runKairosSetup() {
        if (running) return
        running = true

        Thread {
            val home = TermuxConstants.TERMUX_HOME_DIR_PATH
            val script = File("$home/scripts/kairos.sh")
            WizardDebugLog.log("WizardInstallFragment", "runKairosSetup(): script=${script.absolutePath} existe=${script.exists()}")
            if (!script.exists()) {
                handler.post { if (isAdded) showError("Script kairos.sh no encontrado") }
                return@Thread
            }
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, script.absolutePath, "--silent")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                WizardDebugLog.log("WizardInstallFragment", "runKairosSetup(): proceso arrancado")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val fLine = line ?: continue
                    WizardDebugLog.log("kairos.sh", fLine)
                    handler.post {
                        if (!isAdded) return@post
                        when {
                            fLine.startsWith("[STEP]") -> {
                                try {
                                    val rest = fLine.substring(6).trim()
                                    val parts = rest.split(" ", limit = 2)
                                    val stepInfo = parts[0].split("/")
                                    val current = stepInfo[0].toInt()
                                    val total = stepInfo[1].toInt()
                                    val desc = if (parts.size > 1) parts[1] else ""
                                    updateStep(current, total, desc)
                                } catch (e: Exception) {
                                    android.util.Log.w("WizardInstallFragment", "Línea [STEP] malformada: $fLine")
                                }
                            }
                            fLine.startsWith("[ERROR]") -> showError(fLine.substring(7).trim())
                        }
                    }
                }
                val exitCode = process.waitFor()
                WizardDebugLog.log("WizardInstallFragment", "runKairosSetup(): terminó con exit code $exitCode")
                if (exitCode == 0) {
                    // 2026-08-11 (humano97 punto 1): el wizard instala de una vez los módulos
                    // base del stack (remote=SSH+cloudflared, llamaserver=IA Local, entorno=
                    // proot-distro/X11/GPU) encadenando sus instaladores propios tras kairos.sh.
                    // Cada script escribe su <id>.installed en el registry real (mismo contrato
                    // que desde la Tienda) — "registrar de una vez" sin duplicar lógica.
                    //
                    // Bug real reportado (2026-08-22, ver docs/humano/humano200.md): este paso corre
                    // en silencio total (sin updateStep, ver KDoc de runWizardModules()) — con
                    // las 11 casillas visibles ya en verde, la pantalla queda estática varios
                    // minutos (entorno.sh solo puede tardar ~3 min, incluyendo el bug de mirror
                    // ya conocido) sin ninguna señal de que sigue trabajando — indistinguible de
                    // "se colgó". Fix: indicador genérico (sin nombrar los 3 módulos, respeta el
                    // pedido original de humano97 R2 de no mostrarlos como pasos propios) con
                    // progreso indeterminado mientras corre.
                    handler.post {
                        if (!isAdded) return@post
                        statusText?.text = "Ajustando módulos base… (puede tardar unos minutos)"
                        progressBar?.isIndeterminate = true
                    }
                    runWizardModules()
                    handler.post {
                        running = false
                        if (!isAdded) return@post
                        progressBar?.isIndeterminate = false
                        onSetupComplete()
                    }
                } else {
                    handler.post {
                        running = false
                        if (!isAdded) return@post
                        showError("Error durante la configuración (código $exitCode)")
                    }
                }
            } catch (e: Exception) {
                WizardDebugLog.logException("WizardInstallFragment", e)
                handler.post {
                    running = false
                    if (isAdded) showError(e.message ?: "Error desconocido")
                }
            }
        }.start()
    }

    /**
     * 2026-08-11 (humano97 punto 1): tras kairos.sh, el wizard instala de una vez los módulos
     * base del stack encadenando SUS instaladores propios (los mismos que usa la Tienda):
     *   - ssh.sh        → remote (SSH + cloudflared)
     *   - llamaserver.sh → IA Local (llama.cpp)
     *   - entorno.sh    → proot-distro/X11/GPU
     * Cada script escribe su <id>.installed en el registry real (mismo contrato que desde la
     * Tienda) — "registrar de una vez" sin duplicar lógica. NO FATAL: si un módulo falla (red,
     * cloudflared sin acceso, etc.) el wizard continúa; el usuario puede reintentarlo desde la
     * Tienda. Se corre en el MISMO thread de runKairosSetup() (secuencial tras kairos.sh).
     *
     * 2026-08-11 (humano97 R2, feedback usuario): el wizard NO debe mostrar estos pasos —
     * "debe quedar como estaba antes". Los pasos visibles siguen siendo solo
     * bootstrap/rootfs/kairos.sh; los instaladores corren en silencio (sin updateStep, sin
     * tocar el progreso), solo se loguean a WizardDebugLog.
     */
    private fun runWizardModules() {
        val home = TermuxConstants.TERMUX_HOME_DIR_PATH
        val installDir = File(home, "scripts/install")
        val wizardModules = listOf(
            Triple("ssh", "ssh.sh", "Remote (SSH + Cloudflared)"),
            Triple("llamaserver", "llamaserver.sh", "IA Local (llama.cpp)"),
            Triple("entorno", "entorno.sh", "Entorno (proot-distro/X11)")
        )
        for ((id, scriptName, label) in wizardModules) {
            if (!isAdded) return
            val script = File(installDir, scriptName)
            if (!script.exists()) {
                WizardDebugLog.log("WizardInstallFragment", "runWizardModules(): $scriptName no existe, se omite")
                continue
            }
            WizardDebugLog.log("WizardInstallFragment", "runWizardModules(): instalando $scriptName --silent")
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, script.absolutePath, "--silent")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.bufferedReader().forEachLine { line ->
                    WizardDebugLog.log(id, line)
                }
                val exitCode = process.waitFor()
                WizardDebugLog.log("WizardInstallFragment", "runWizardModules(): $scriptName terminó con exit code $exitCode")
            } catch (e: Exception) {
                WizardDebugLog.logException("WizardInstallFragment", e)
            }
        }
    }

    private fun showError(message: String) {
        running = false
        if (!isAdded) return
        progressBar?.visibility = View.GONE
        statusText?.text = "✕ $message"
        statusText?.setTextColor(requireContext().kairosThemeColor(R.attr.kairosRed))
        retryButton?.visibility = View.VISIBLE
        retryButton?.text = "Reintentar"
    }

    private fun onSetupComplete() {
        if (!isAdded) return
        progressBar?.progress = progressBar?.max ?: 0
        statusText?.text = "✓ Configuración completa"
        statusText?.setTextColor(requireContext().kairosThemeColor(R.attr.kairosGreen))
        handler.postDelayed({
            if (isAdded) (activity as? WizardActivity)?.goToPage(5)
        }, 1200)
    }

    private fun onRetryClicked() {
        if (!isAdded) return
        statusText?.text = "Reintentando..."
        statusText?.setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        retryButton?.visibility = View.GONE

        for (i in stepRows.indices) {
            val row = stepRows[i] as? LinearLayout ?: continue
            val num = row.getChildAt(0) as? TextView
            val label = row.getChildAt(1) as? TextView
            num?.text = (i + 1).toString()
            num?.let { setCircleBg(it, "#333333") }
            num?.setTextColor(Color.parseColor("#555555"))
            label?.text = STEP_LABELS[i]
            label?.setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
        }

        Thread {
            ensureBootstrapSecondStage()
            if (!isAdded) return@Thread
            KairosBootstrap.extractAssetsSync(requireContext())
            handler.post {
                if (!isAdded) return@post
                installRootfsThenContinue()
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
