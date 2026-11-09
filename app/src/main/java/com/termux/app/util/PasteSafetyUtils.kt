package com.termux.app.util

import android.app.AlertDialog
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import com.termux.R

/**
 * Revisión de seguridad para paste manual del usuario en la terminal — puerto del mecanismo de
 * `ttyx_` (fork activo de Tilix, `source/gx/ttyx/terminal/clipboard.d:87-120` `isPasteUnsafe()`
 * + `advpaste.d` `AdvancedPasteDialog`), ver `docs/referencias/terminal/REFERENCIA_TTYX.md`.
 *
 * Distinto de `.claude/rules/kairos-secrets-never-revealed.md` (que protege secretos que la
 * APP guarda) — esto protege lo que el usuario pega A MANO desde el portapapeles de Android
 * (tokens copiados por error, comandos peligrosos copiados de foros/docs de módulos de IA).
 *
 * Igual que en ttyx_, la revisión solo se dispara para paste MULTILÍNEA — un paste de una sola
 * línea (el caso común: pegar un token, una URL, un comando corto) se ejecuta directo sin
 * fricción, evitando el error de "un aviso por cada tecla" que ttyx_ documenta explícitamente
 * evitar para el caso de una sola línea. Dentro del diálogo, la advertencia escala si el texto
 * matchea alguno de los patrones de riesgo conocidos (privilegios, destructivo, RCE por pipe).
 */
object PasteSafetyUtils {

    /** Patrones de riesgo real, agrupados igual que `isPasteUnsafe()` de ttyx_. Case-insensitive. */
    private val PRIVILEGE_ESCALATION = listOf(
        Regex("""\bsudo\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsu\s+-""", RegexOption.IGNORE_CASE),
        Regex("""\bdoas\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpkexec\b""", RegexOption.IGNORE_CASE)
    )

    private val DESTRUCTIVE = listOf(
        Regex("""\brm\s+(-\w*\s+)*-\w*r\w*f\w*""", RegexOption.IGNORE_CASE), // rm -rf, rm -fr, rm -Rf...
        Regex("""\bmkfs\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdd\s+if="""),
        Regex("""\bchmod\s+(-R\s+)?777\b"""),
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&?\s*\}\s*;\s*:""") // fork bomb :(){ :|:& };:
    )

    private val REMOTE_CODE_EXECUTION = listOf(
        Regex("""(curl|wget)\b[^|\n]*\|\s*(sudo\s+)?(bash|sh|zsh)\b""", RegexOption.IGNORE_CASE),
        Regex("""\|\s*(sudo\s+)?(bash|sh|zsh)\s*$""", RegexOption.MULTILINE),
        Regex("""\beval\b""", RegexOption.IGNORE_CASE)
    )

    /**
     * true si el texto tiene más de una línea real (ignora un único salto de línea final, que
     * el portapapeles de Android agrega rutinariamente al copiar una sola línea de output) —
     * mismo gate que ttyx_ usa antes de mostrar cualquier diálogo de revisión.
     */
    @JvmStatic
    fun isMultilinePaste(text: String): Boolean {
        return text.trimEnd('\n', '\r').contains('\n')
    }

    /** Etiquetas ES de los patrones peligrosos detectados en `text`, vacío si ninguno matchea. */
    @JvmStatic
    fun findDangerousPatternLabels(text: String): List<String> {
        val labels = mutableListOf<String>()
        if (PRIVILEGE_ESCALATION.any { it.containsMatchIn(text) }) labels += "sudo/su/doas (escalada de privilegios)"
        if (DESTRUCTIVE.any { it.containsMatchIn(text) }) labels += "rm -rf / mkfs / dd / chmod 777 / fork bomb (destructivo)"
        if (REMOTE_CODE_EXECUTION.any { it.containsMatchIn(text) }) labels += "curl|bash / wget|sh / eval (ejecución remota)"
        return labels
    }

    /**
     * Punto de entrada único llamado desde `TermuxTerminalViewClient.doPaste()` y
     * `TermuxTerminalSessionActivityClient.onPasteTextFromClipboard()` (ambos código propio de
     * Kairos, NO `terminal-view`/`terminal-emulator` protegidos — ver hallazgo de paste por
     * mouse en el comentario de `TerminalView.java` citado en la auditoría, ese camino no pasa
     * por acá porque requeriría tocar código protegido).
     *
     * Si el paste es de una sola línea, `onConfirm` corre directo sin diálogo. Si es
     * multilínea, siempre se muestra un diálogo de revisión con preview antes de ejecutar —
     * la advertencia se resalta en rojo si se detectó algún patrón peligroso.
     */
    @JvmStatic
    fun reviewAndPaste(context: Context, text: String, onConfirm: Runnable) {
        if (!isMultilinePaste(text)) {
            onConfirm.run()
            return
        }

        val dangerLabels = findDangerousPatternLabels(text)
        val lineCount = text.trimEnd('\n', '\r').count { it == '\n' } + 1

        val preview = TextView(context).apply {
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            this.text = buildPreview(text, dangerLabels.isNotEmpty())
        }
        val scroll = ScrollView(context).apply {
            addView(preview)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 700
            )
        }

        val title = if (dangerLabels.isNotEmpty())
            context.getString(R.string.terminal_paste_review_title_danger)
        else
            context.getString(R.string.terminal_paste_review_title)

        val message = if (dangerLabels.isNotEmpty())
            context.getString(R.string.terminal_paste_review_message_danger, lineCount, dangerLabels.joinToString(", "))
        else
            context.getString(R.string.terminal_paste_review_message, lineCount)

        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(scroll)
            .setNegativeButton(R.string.terminal_paste_review_cancel, null)
            .setPositiveButton(
                if (dangerLabels.isNotEmpty()) R.string.terminal_paste_review_confirm_danger
                else R.string.terminal_paste_review_confirm
            ) { _, _ -> onConfirm.run() }

        builder.show()
    }

    /** Preview truncado (evita un TextView gigante con pastes de miles de líneas) + resaltado simple. */
    private fun buildPreview(text: String, highlightDanger: Boolean): CharSequence {
        val maxChars = 4000
        val truncated = text.length > maxChars
        val shown = if (truncated) text.substring(0, maxChars) else text
        val builder = SpannableStringBuilder(shown)
        if (truncated) builder.append("\n\n[...truncado, ${text.length - maxChars} caracteres más...]")

        if (highlightDanger) {
            val allPatterns = PRIVILEGE_ESCALATION + DESTRUCTIVE + REMOTE_CODE_EXECUTION
            for (pattern in allPatterns) {
                for (match in pattern.findAll(shown)) {
                    builder.setSpan(
                        ForegroundColorSpan(0xFFFF5252.toInt()),
                        match.range.first, match.range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        match.range.first, match.range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        return builder
    }
}
