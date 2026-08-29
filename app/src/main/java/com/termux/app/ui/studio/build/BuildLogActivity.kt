package com.termux.app.ui.studio.build

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.termux.R
import com.termux.app.ui.studio.termux.TermuxBridge
import com.termux.databinding.StudioActivityBuildLogBinding
import java.util.concurrent.TimeUnit

/**
 * Botón "🔨 Build APK" del IDE -- compila proyectos Android reales corriendo `./gradlew
 * assembleDebug` DENTRO de la propia sesión de Kairos/Termux, vía [TermuxBridge] (in-process,
 * sin `RUN_COMMAND`/permisos externos -- Estudio ya vive en el mismo proceso que Kairos, así que
 * no hace falta el puente de Intents que sí necesitaba la versión standalone de este IDE).
 *
 * Sigue dependiendo de que el usuario tenga `openjdk-17`/`gradle` instalados dentro de la sesión
 * de Termux de Kairos -- Estudio no trae su propio JDK embebido (inflaría el APK en cientos de
 * MB), reusa el toolchain que ya exista en el `$PREFIX` real, igual que el resto de módulos de
 * Kairos (Ollama/llama-server: detecta y delega, no empaqueta).
 */
class BuildLogActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_PATH = "project_path"

        // Resultado de tocar una línea de diagnóstico con ubicación real (ver
        // docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md §4 punto 3, implementado 2026-08-26) —
        // mismo patrón que ProjectSearchActivity.EXTRA_RESULT_*, StudioFragment.buildLogLauncher
        // los lee para abrir el archivo en el editor en la línea+columna exacta.
        const val EXTRA_RESULT_FILE_PATH = "result_file_path"
        const val EXTRA_RESULT_LINE = "result_line"
        const val EXTRA_RESULT_COLUMN = "result_column"

        private const val GRADLE_TASK = "assembleDebug"
        private const val ELAPSED_TIME_UPDATE_INTERVAL_MS = 1_000L
    }

    /** Un [BuildDiagnostic] por línea visual agregada a [binding.buildLogText] (o `null` si esa
     * línea es ruido sin estructura) — paralelo 1:1 a las líneas que [appendLogLine] va
     * agregando, se usa para resolver a qué diagnóstico corresponde un tap (ver
     * [diagnosticAtPosition]). Limitación conocida: si una línea larga hace wrap visual dentro
     * del TextView, el índice de "línea de layout" que reporta `Layout.getLineForVertical` deja
     * de coincidir 1:1 con esta lista (que es por línea LÓGICA de log) — aceptado para esta
     * ronda, el caso común (diagnósticos de compilador, generalmente cortos) no lo sufre. */
    private val lineDiagnostics = mutableListOf<BuildDiagnostic?>()

    /** Detecta un tap simple (no un drag de selección de texto) sobre [binding.buildLogText] y lo
     * resuelve contra [lineDiagnostics] — ver [attachDiagnosticClickHandling]. */
    private lateinit var logTapDetector: GestureDetector

    private lateinit var binding: StudioActivityBuildLogBinding
    private var projectPath: String? = null

    /** Estado real de "hay un build en curso" -- [TermuxBridge] no expone cancelación de un
     * proceso ya arrancado, así que "detenerlo y correr de nuevo" en la práctica significa: el
     * log de la corrida anterior queda descartado en pantalla y se dispara una nueva (el proceso
     * viejo puede seguir corriendo del lado de Termux hasta que termine solo). */
    private var isBuildRunning = false
    private var buildStartTimeMillis = 0L

    /** Checkbox "No preguntar de nuevo esta sesión" -- solo vive en memoria del proceso. */
    private var skipRunConflictConfirmation = false

    // Diagnósticos estructurados de esta corrida (2026-08-23, ver docs/humano207.md +
    // docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md §4 punto 3) -- CompilerOutputParser
    // clasifica cada línea que llega; acá solo se cuenta para el resumen final, el resaltado de
    // color se aplica línea por línea en appendLogLine().
    private var errorCount = 0
    private var warningCount = 0

    private val elapsedTimeHandler = Handler(Looper.getMainLooper())
    private val elapsedTimeUpdater = object : Runnable {
        override fun run() {
            updateElapsedTimeLabel()
            elapsedTimeHandler.postDelayed(this, ELAPSED_TIME_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = StudioActivityBuildLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)
        binding.buildLogText.text = getString(R.string.build_log_waiting)
        binding.rebuildButton.setOnClickListener { startBuild() }
        attachDiagnosticClickHandling()
    }

    /** Diagnósticos clickeables (ver KDoc de [lineDiagnostics]) — se usa un `GestureDetector`
     * propio en vez de `ClickableSpan`+`LinkMovementMethod` porque `buildLogText` tiene
     * `textIsSelectable="true"` (el log crudo sigue siendo copiable, pedido explícito de la
     * ronda original de este archivo) y ese modo de TextView intercepta el touch para su propio
     * manejo de selección antes de que un `ClickableSpan` normal llegue a disparar — un
     * `OnTouchListener` puesto con `setOnTouchListener` sí se evalúa primero: si el tap cae
     * sobre una línea con diagnóstico con archivo real, se consume (abre el archivo, no arranca
     * selección); si no, se deja pasar (`false`) para que la selección de texto normal siga
     * funcionando igual que antes. */
    private fun attachDiagnosticClickHandling() {
        logTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean = handleLogTap(e.x, e.y)
        })
        binding.buildLogText.setOnTouchListener { _, event -> logTapDetector.onTouchEvent(event) }
    }

    private fun handleLogTap(x: Float, y: Float): Boolean {
        val diagnostic = diagnosticAtPosition(x, y) ?: return false
        val file = diagnostic.file ?: return false
        val result = Intent().apply {
            putExtra(EXTRA_RESULT_FILE_PATH, file)
            putExtra(EXTRA_RESULT_LINE, diagnostic.line ?: 1)
            putExtra(EXTRA_RESULT_COLUMN, diagnostic.column ?: 1)
        }
        setResult(RESULT_OK, result)
        finish()
        return true
    }

    private fun diagnosticAtPosition(x: Float, y: Float): BuildDiagnostic? {
        val layout = binding.buildLogText.layout ?: return null
        val lineIndex = layout.getLineForVertical(y.toInt())
        return lineDiagnostics.getOrNull(lineIndex)
    }

    override fun onStart() {
        super.onStart()
        val path = projectPath
        if (path.isNullOrEmpty()) {
            showBanner(getString(R.string.build_error_no_project))
            binding.rebuildButton.isEnabled = false
        } else {
            hideBanner()
            binding.rebuildButton.isEnabled = true
            if (binding.buildStatusLabel.text.isNullOrEmpty() && !isBuildRunning) {
                startBuild()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        elapsedTimeHandler.removeCallbacks(elapsedTimeUpdater)
    }

    private fun showBanner(message: String) {
        binding.statusBanner.visibility = View.VISIBLE
        binding.statusBannerText.text = message
        binding.statusBannerAction.visibility = View.GONE
    }

    private fun hideBanner() {
        binding.statusBanner.visibility = View.GONE
    }

    private fun startBuild() {
        if (isBuildRunning && !skipRunConflictConfirmation) {
            showRunConflictDialog()
            return
        }
        executeBuild()
    }

    /** Diálogo real "ya hay un build corriendo" -- pregunta antes de disparar un build nuevo
     * sobre uno en curso, con checkbox para no volver a preguntar durante esta sesión. */
    private fun showRunConflictDialog() {
        val checkboxView = CheckBox(this).apply {
            setText(R.string.build_conflict_dont_ask_again)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.build_conflict_title)
            .setMessage(R.string.build_conflict_message)
            .setView(checkboxView)
            .setPositiveButton(R.string.build_conflict_confirm) { _, _ ->
                skipRunConflictConfirmation = checkboxView.isChecked
                executeBuild()
            }
            .setNegativeButton(R.string.build_conflict_cancel, null)
            .show()
    }

    private fun executeBuild() {
        val path = projectPath
        if (path.isNullOrEmpty()) {
            binding.buildStatusLabel.text = getString(R.string.build_error_no_project)
            return
        }

        val gradleCommand = "chmod +x ./gradlew 2>/dev/null; ./gradlew $GRADLE_TASK --console=plain"

        isBuildRunning = true
        buildStartTimeMillis = System.currentTimeMillis()
        errorCount = 0
        warningCount = 0
        lineDiagnostics.clear()
        binding.buildLogText.text = ""
        binding.rebuildButton.isEnabled = false
        updateElapsedTimeLabel()
        elapsedTimeHandler.removeCallbacks(elapsedTimeUpdater)
        elapsedTimeHandler.postDelayed(elapsedTimeUpdater, ELAPSED_TIME_UPDATE_INTERVAL_MS)

        TermuxBridge.runShellCommandStreaming(
            command = gradleCommand,
            workingDirectory = path,
            onLine = { line -> appendLogLine(line) },
            onDone = { exitCode -> onBuildFinished(exitCode) }
        )
    }

    /** El proceso de gradle no es cancelable (ver KDoc de [isBuildRunning]) — sigue corriendo en
     * su propio Thread aun si esta Activity ya terminó, y cada línea nueva llega igual por
     * [TermuxBridge.runShellCommandStreaming]. Sin este guard, `appendLogLine`/`onBuildFinished`
     * seguirían tocando `binding` de una Activity ya destruida (no crashea, pero es trabajo
     * inútil sobre vistas huérfanas) — mismo criterio que ya usa [GitPanelActivity]. */
    private fun appendLogLine(line: String) {
        if (isFinishing || isDestroyed) return
        val diagnostic = CompilerOutputParser.parseLine(line)
        if (diagnostic != null) {
            when (diagnostic.severity) {
                BuildSeverity.ERROR -> errorCount++
                BuildSeverity.WARNING -> warningCount++
                BuildSeverity.INFO -> {}
            }
        }
        lineDiagnostics.add(diagnostic)
        binding.buildLogText.append(spanForLine(line, diagnostic))
        binding.buildLogText.append("\n")
        binding.buildLogScroll.post {
            binding.buildLogScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Línea cruda de siempre, salvo que [CompilerOutputParser] la haya reconocido como un
     * diagnóstico real -- ahí se resalta en rojo/ámbar + negrita en vez de dejarla como texto
     * plano indistinguible del resto del log (pedido explícito del usuario: diagnósticos
     * estructurados, no texto crudo). No se reemplaza el texto ni se oculta nada -- el log crudo
     * completo sigue siendo seleccionable/copiable tal cual, solo cambia el color. */
    private fun spanForLine(line: String, diagnostic: BuildDiagnostic?): SpannableString {
        val span = SpannableString(line)
        if (diagnostic == null) return span
        val color = when (diagnostic.severity) {
            BuildSeverity.ERROR -> Color.parseColor("#EF4444")
            BuildSeverity.WARNING -> Color.parseColor("#F59E0B")
            BuildSeverity.INFO -> return span
        }
        span.setSpan(ForegroundColorSpan(color), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(StyleSpan(Typeface.BOLD), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Subrayado = "clickeable, tiene ubicación real" (ver attachDiagnosticClickHandling) —
        // señal visual de que tocar la línea salta al archivo, no solo cambia de color.
        if (diagnostic.file != null) {
            span.setSpan(
                android.text.style.UnderlineSpan(),
                0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return span
    }

    /** Cronómetro simple (texto plano en [binding.buildStatusLabel], sin `Chronometer` dedicado)
     * -- se actualiza cada segundo mientras [isBuildRunning] sea true. */
    private fun updateElapsedTimeLabel() {
        if (!isBuildRunning) return
        binding.buildStatusLabel.text = getString(
            R.string.build_status_running_elapsed,
            formatElapsedTime(elapsedSecondsSinceStart())
        )
    }

    private fun elapsedSecondsSinceStart(): Long =
        TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - buildStartTimeMillis)

    private fun formatElapsedTime(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun onBuildFinished(exitCode: Int) {
        isBuildRunning = false
        elapsedTimeHandler.removeCallbacks(elapsedTimeUpdater)
        if (isFinishing || isDestroyed) return
        val exitCodeText = getString(R.string.terminal_exit_code, exitCode)
        val diagnosticsSummary = buildString {
            if (errorCount > 0) append(" · ✗ $errorCount error${if (errorCount == 1) "" else "es"}")
            if (warningCount > 0) append(" · ⚠ $warningCount warning${if (warningCount == 1) "" else "s"}")
        }
        binding.buildStatusLabel.text = getString(
            R.string.build_status_done_elapsed,
            exitCodeText,
            formatElapsedTime(elapsedSecondsSinceStart())
        ) + diagnosticsSummary
        binding.rebuildButton.isEnabled = true
    }
}
