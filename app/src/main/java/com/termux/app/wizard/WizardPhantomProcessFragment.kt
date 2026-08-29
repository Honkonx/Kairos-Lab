package com.termux.app.wizard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.TermuxInstaller
import com.termux.app.util.PhantomProcessKillerHelper
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.WizardDebugLog
import com.termux.app.util.isTermuxBinaryAvailable
import com.termux.app.util.kairosThemeColor

/**
 * Pantalla 2 del wizard — quitar el límite de procesos fantasma (Android 12+), con
 * pantalla propia en vez de escondido en el tab Monitor (pedido explícito del usuario, ver
 * docs/humano53.md/humano54.md). Reordena los 3 métodos que ya existían en
 * PhantomProcessKillerHelper.kt/MonitorFragment.kt: auto-detección beta PRIMERO (recomendada,
 * menos datos manuales), guiado con código+puerto segundo (fallback si falla la beta),
 * tutorial 100% manual tercero. La lógica real vive en PhantomProcessKillerHelper — este
 * archivo solo arma la pantalla y los diálogos de los 3 pasos, mismo patrón que ya usaba
 * MonitorFragment. "Continuar" no bloquea — se puede saltar este paso igual que Batería.
 */
class WizardPhantomProcessFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Pantalla 3 del wizard (contando desde 1, como la nombra el usuario) — punto donde
        // arranca el log persistente pedido explícitamente, ver docs/humano/humano62.md.
        WizardDebugLog.resetForNewRun()
        WizardDebugLog.log("WizardPhantomProcessFragment", "onCreateView()")
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(TextView(ctx).apply {
            text = "Quitar límite de procesos"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })

        root.addView(TextView(ctx).apply {
            text = "Android puede matar procesos en segundo plano (Ollama, n8n, OpenClaw) para " +
                "ahorrar batería. Para evitarlo hay que desactivar un límite en Opciones de " +
                "desarrollador. Elegí un método:"
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(14); it.bottomMargin = dp(20)
            }
        })

        root.addView(optionCard(
            title = "Auto-detectar y desactivar",
            desc = "Instala lo necesario y hace el resto internamente en Termux — no tenés que tocar puertos a mano.",
            buttonText = "Configurar automáticamente",
            recommended = true
        ) { startAutoDetectFix() })

        root.addView(optionCard(
            title = "Con código y puerto manual",
            desc = "Si el método automático falla, este pide 2 datos de Opciones de desarrollador.",
            buttonText = "Configurar manualmente",
            recommended = false
        ) { startGuidedFix() })

        root.addView(optionCard(
            title = "Desactivar yo mismo",
            desc = "Te decimos exactamente qué opción buscar y te la abrimos directo.",
            buttonText = "Ver tutorial",
            recommended = false
        ) { showManualTutorialDialog() })

        root.addView(Button(ctx).apply {
            text = "Continuar"
            isAllCaps = false
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setOnClickListener {
                WizardDebugLog.log("WizardPhantomProcessFragment", "Continuar -> pantalla 4 (Batería)")
                (activity as? WizardActivity)?.goToPage(3)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(8)
        })

        return scroll
    }

    private fun optionCard(title: String, desc: String, buttonText: String, recommended: Boolean, onClick: () -> Unit): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(ctx.kairosThemeColor(R.attr.kairosBg2))
                cornerRadius = dp(12).toFloat()
                if (recommended) setStroke(dp(1), ctx.kairosThemeColor(R.attr.kairosGreen))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(12)
            }
        }
        if (recommended) {
            card.addView(TextView(ctx).apply {
                text = "RECOMENDADA"
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            })
        }
        card.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = if (recommended) dp(4) else 0
            }
        })
        card.addView(TextView(ctx).apply {
            text = desc
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(4); it.bottomMargin = dp(10)
            }
        })
        card.addView(Button(ctx).apply {
            text = buttonText
            isAllCaps = false
            if (recommended) {
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                setTextColor(Color.BLACK)
            } else {
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            }
            setOnClickListener { onClick() }
        })
        return card
    }

    private fun ensureDeveloperOptionsThen(onReady: () -> Unit) {
        PhantomProcessKillerHelper.ensureDeveloperOptionsThen(requireActivity(), onReady)
    }

    /**
     * Los 2 métodos automáticos corren `pkg install`/`adb` reales, que necesitan el bootstrap
     * crudo de Termux ya extraído (bash/pkg/apt) — esta pantalla es la 2, ANTES de la 4
     * (instalación completa), así que ese bootstrap recién se dispara en segundo plano al
     * salir de Permisos (`WizardInstallFragment.ensureTermuxBootstrapReady()`, ver
     * docs/humano/humano56.md).
     *
     * Bug real reportado dos rondas seguidas (ver docs/humano/humano58.md y humano59.md): la
     * v1 de este guard avisaba una sola vez con un Toast; la v2 (sondeo ciego, 20 intentos de
     * 1s) seguía agotándose sin éxito en un dispositivo real — el problema de fondo era medir
     * una señal PROXY (`isTermuxBinaryAvailable`) con un timeout inventado, en vez de
     * engancharse a la señal REAL de "el bootstrap ya terminó". Ahora se engancha directo al
     * callback de `TermuxInstaller.setupBootstrapIfNeeded()` (la misma función que ya dispara
     * `WizardInstallFragment`, con el guard de concurrencia de humano58.md) — si el bootstrap
     * ya está listo (caso normal, ya lo disparó Permisos) el callback llega casi al instante;
     * si es la primera vez de verdad y todavía no existe, corre la extracción real y el
     * callback llega EXACTAMENTE cuando termina, sin importar cuánto tarde — sin timeout
     * arbitrario que pueda quedarse corto. El único costo: en ese caso raro (primera vez
     * genuina) puede aparecer brevemente el diálogo nativo de progreso de Termux — aceptado a
     * propósito por el usuario en humano59.md ("dejalo para cuando toquemos el bootstrap en
     * serio").
     */
    private fun ensureTermuxReadyThen(onReady: () -> Unit) {
        if (isTermuxBinaryAvailable("pkg")) {
            WizardDebugLog.log("WizardPhantomProcessFragment", "ensureTermuxReadyThen: pkg ya disponible, sigue directo")
            onReady()
            return
        }
        WizardDebugLog.log("WizardPhantomProcessFragment", "ensureTermuxReadyThen: pkg no disponible todavía, esperando callback de setupBootstrapIfNeeded()")
        TermuxInstaller.setupBootstrapIfNeeded(requireActivity()) {
            WizardDebugLog.log("WizardPhantomProcessFragment", "ensureTermuxReadyThen: callback de setupBootstrapIfNeeded() llegó")
            if (isAdded) onReady()
        }
    }

    // ── (a) recomendada — beta / auto-detección de puerto con nmap ──────────

    private fun startAutoDetectFix() = ensureTermuxReadyThen {
        WizardDebugLog.log("WizardPhantomProcessFragment", "startAutoDetectFix()")
        val ctx = requireContext()
        if (PhantomProcessKillerHelper.isNmapAvailable()) {
            WizardDebugLog.log("WizardPhantomProcessFragment", "nmap ya disponible, salta instalación")
            ensureDeveloperOptionsThen { showAutoDetectStep1() }
            return@ensureTermuxReadyThen
        }
        val progress = ProgressDialogController(ctx)
        progress.show("Preparando auto-detección", "Espere, instalando paquete necesario…")
        Thread {
            val ok = PhantomProcessKillerHelper.installNmap { line ->
                WizardDebugLog.log("installNmap", line)
                if (!isAdded) return@installNmap
                requireActivity().runOnUiThread { if (isAdded) progress.update(line) }
            }
            WizardDebugLog.log("WizardPhantomProcessFragment", "installNmap() devolvió ok=$ok")
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    progress.dismiss()
                    ensureDeveloperOptionsThen { showAutoDetectStep1() }
                } else {
                    progress.failure(
                        "No se pudo instalar nmap",
                        "Probá \"Configurar manualmente\" en su lugar — no necesita nmap."
                    )
                }
            }
        }.start()
    }

    private fun showAutoDetectStep1() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("Depuración inalámbrica")
            .setMessage(
                "Vamos a usar la Depuración inalámbrica de Android. Acá solo vas a tener que " +
                    "escribir 2 datos (el puerto de conexión lo detecta Kairos solo).\n\n" +
                    "Tocá \"Abrir Opciones de desarrollador\", entrá a \"Depuración " +
                    "inalámbrica\" y activala. Cuando la tengas activa, volvé a Kairos y " +
                    "tocá \"Continuar\"."
            )
            .setNeutralButton("Abrir Opciones de desarrollador") { _, _ ->
                PhantomProcessKillerHelper.openDeveloperOptions(requireActivity())
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ -> showAutoDetectStep2() }
            .show()
    }

    private fun showAutoDetectStep2() {
        showPairingDataDialog(
            description = "Dentro de \"Depuración inalámbrica\", tocá \"Vincular dispositivo con " +
                "código de emparejamiento\" — vas a ver el puerto y el código de 6 " +
                "dígitos. No hace falta el puerto de conexión esta vez.",
            includeConnectPort = false
        ) { pairingPort, code, _ -> runAutoDetectFixFlow(pairingPort, code) }
    }

    private fun runAutoDetectFixFlow(pairingPort: String, code: String) {
        val ctx = requireContext()
        val progress = ProgressDialogController(ctx)
        progress.show("Detectando puerto y aplicando fix", "Procesando…")
        val log = StringBuilder()
        WizardDebugLog.log("WizardPhantomProcessFragment", "runAutoDetectFixFlow() arrancó")
        PhantomProcessKillerHelper.runAutoDetectFix(
            pairingPort, code,
            onOutput = { line ->
                log.append(line).append('\n')
                WizardDebugLog.log("runAutoDetectFix", line)
                if (!isAdded) return@runAutoDetectFix
                // A propósito no se muestra el detalle línea por línea acá (pedido explícito
                // del usuario: "que no salga nada en la interfaz, solo procesando") — el log
                // completo sigue disponible en "Ver detalles" al terminar (éxito o error), y
                // ahora también en el archivo de WizardDebugLog.
                requireActivity().runOnUiThread { if (isAdded) progress.update("Procesando…") }
            },
            onDone = { success ->
                WizardDebugLog.log("WizardPhantomProcessFragment", "runAutoDetectFixFlow() terminó, success=$success")
                if (!isAdded) return@runAutoDetectFix
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (success) {
                        progress.success("Listo — límite de procesos desactivado", log.toString())
                    } else {
                        progress.dismiss()
                        showAutoDetectFailure(log.toString())
                    }
                }
            }
        )
    }

    private fun showAutoDetectFailure(log: String) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("No se pudo detectar el puerto")
            .setMessage(
                "Probá el método manual con código y puerto — te va a pedir el mismo código " +
                    "de recién.\n\nDetalle:\n${log.takeLast(400)}"
            )
            .setPositiveButton("Ir al método manual") { _, _ -> startGuidedFix() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ── (b) manual con código y puerto ───────────────────────────────────────

    private fun startGuidedFix() = ensureTermuxReadyThen {
        WizardDebugLog.log("WizardPhantomProcessFragment", "startGuidedFix()")
        ensureDeveloperOptionsThen { showGuidedStep1() }
    }

    private fun showGuidedStep1() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("Depuración inalámbrica")
            .setMessage(
                "En el siguiente paso vas a tener que escribir 3 datos que SOLO Android " +
                    "muestra en su propia pantalla — no hay forma de leerlos automáticamente.\n\n" +
                    "Tocá \"Abrir Opciones de desarrollador\", entrá a \"Depuración " +
                    "inalámbrica\" y activala. Cuando la tengas activa, volvé a Kairos y " +
                    "tocá \"Continuar\"."
            )
            .setNeutralButton("Abrir Opciones de desarrollador") { _, _ ->
                PhantomProcessKillerHelper.openDeveloperOptions(requireActivity())
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ -> showGuidedStep2() }
            .show()
    }

    private fun showGuidedStep2() {
        showPairingDataDialog(
            description = "Son 3 números distintos que Android muestra en 2 lugares — no es un " +
                "invento de Kairos, así funciona la Depuración inalámbrica: al tocar " +
                "\"Vincular dispositivo con código de emparejamiento\" vas a ver el " +
                "puerto de emparejamiento y un código de 6 dígitos; el puerto de " +
                "conexión es OTRO número, aparte, que está arriba de la pantalla " +
                "principal de Depuración inalámbrica, junto a la IP.",
            includeConnectPort = true
        ) { pairingPort, code, connectPort -> runGuidedFix(pairingPort, code, connectPort ?: "") }
    }

    /**
     * Helper compartido entre `showAutoDetectStep2()` (2 campos: puerto+código) y
     * `showGuidedStep2()` (3 campos: puerto+código+puerto de conexión) — mismos EditText,
     * misma regex de validación, mismo Toast de error, solo cambia si pide el 3er campo y
     * qué hacer al confirmar. Ver AUDITORIA_CODIGO_2026-08-13.md §3.8.
     */
    private fun showPairingDataDialog(
        description: String,
        includeConnectPort: Boolean,
        onConfirm: (pairingPort: String, code: String, connectPort: String?) -> Unit
    ) {
        val ctx = requireContext()
        val pairingPortInput = EditText(ctx).apply {
            hint = "Puerto de emparejamiento (ej. 39555)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val codeInput = EditText(ctx).apply {
            hint = "Código de 6 dígitos"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val connectPortInput = if (includeConnectPort) {
            EditText(ctx).apply {
                hint = "Puerto de conexión (ej. 35541)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            }
        } else null
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(TextView(ctx).apply {
                text = description
                textSize = 12f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, 0, 0, dp(12))
            })
            addView(pairingPortInput)
            addView(codeInput)
            connectPortInput?.let { addView(it) }
        }
        val fieldCount = if (includeConnectPort) 3 else 2
        AlertDialog.Builder(ctx)
            .setTitle("Datos de emparejamiento")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar") { _, _ ->
                val pairingPort = pairingPortInput.text.toString().trim()
                val code = codeInput.text.toString().trim()
                val connectPort = connectPortInput?.text?.toString()?.trim()
                val digitsOnly = Regex("^\\d+$")
                val valid = digitsOnly.matches(pairingPort) && digitsOnly.matches(code) &&
                    (!includeConnectPort || (connectPort != null && digitsOnly.matches(connectPort)))
                if (!valid) {
                    Toast.makeText(ctx, "Completá los $fieldCount campos solo con números", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(pairingPort, code, connectPort)
            }
            .show()
    }

    private fun runGuidedFix(pairingPort: String, code: String, connectPort: String) {
        val ctx = requireContext()
        val progress = ProgressDialogController(ctx)
        progress.show("Aplicando fix", "Ejecutando comandos ADB…")
        val log = StringBuilder()
        WizardDebugLog.log("WizardPhantomProcessFragment", "runGuidedFix() arrancó")
        PhantomProcessKillerHelper.runFix(
            pairingPort, code, connectPort,
            onOutput = { line ->
                log.append(line).append('\n')
                WizardDebugLog.log("runFix", line)
                if (!isAdded) return@runFix
                requireActivity().runOnUiThread { if (isAdded) progress.update(line) }
            },
            onDone = { success ->
                WizardDebugLog.log("WizardPhantomProcessFragment", "runGuidedFix() terminó, success=$success")
                if (!isAdded) return@runFix
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (success) {
                        progress.success("Listo — límite de procesos desactivado", log.toString())
                    } else {
                        progress.failure("No se pudo confirmar el fix — revisá el detalle", log.toString())
                    }
                }
            }
        )
    }

    // ── (c) desactivar el toggle nativo, o ADB como respaldo ─────────────────
    // Delega en PhantomProcessKillerHelper.showManualTutorialDialog() (compartido con
    // MonitorFragment, ver docs/humano/humano56.md) — prioriza el toggle nativo "Desactivar
    // restricciones de procesos secundarios" (un solo toque, sin ADB, confirmado presente en
    // el dispositivo del usuario) sobre los comandos ADB, que quedan como respaldo.

    private fun showManualTutorialDialog() {
        PhantomProcessKillerHelper.showManualTutorialDialog(requireActivity())
    }
}
