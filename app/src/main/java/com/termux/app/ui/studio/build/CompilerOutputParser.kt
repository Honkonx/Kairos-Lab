package com.termux.app.ui.studio.build

/** Severidad real de un diagnóstico de compilación — mapea directo a lo que Gradle/kotlinc/javac
 * imprimen, sin inventar niveles intermedios. */
enum class BuildSeverity { ERROR, WARNING, INFO }

/**
 * Un diagnóstico de build ya estructurado — reemplaza el texto crudo que hoy ve el usuario en
 * `BuildLogActivity` por algo con severidad/archivo/línea/columna reales.
 *
 * [file]/[line]/[column] son `null` cuando la línea es un error/aviso real pero SIN ubicación
 * (ej. el resumen final de Gradle "FAILURE: Build failed...") — no se inventa una ubicación
 * falsa solo para llenar el campo.
 */
data class BuildDiagnostic(
    val severity: BuildSeverity,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val raw: String,
)

/**
 * Parsea línea por línea la salida REAL de `./gradlew assembleDebug --console=plain` de Kairos —
 * NO es una copia del `CompilerOutputParser` de CodeAssist (`referencia/ides/CodeAssist-main/`,
 * citado en `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §4 punto 3): ese parser
 * asume formato GNU (`path:line:col: error: msg`) + bloques `ecj`, pensado para aapt2/javac/ecj
 * invocados directo. El build real de Kairos pasa por Gradle, y el compilador de Kotlin (la
 * mayoría del código de la app) NO usa ese formato — usa el suyo propio, confirmado en este mismo
 * proyecto por la salida real de `tools/build-local.ps1` (ver `docs/humano206.md`/`docs/humano207.md`
 * para ejemplos reales vistos en esta sesión):
 *
 *   e: file:///C:/Users/.../EntornoNative.kt:996:27 Unresolved reference 'home'.
 *   w: file:///C:/Users/.../Foo.kt:12:5 'x' is never used.
 *
 * javac (los pocos `.java` heredados de termux-app) SÍ sigue el formato GNU clásico:
 *
 *   /ruta/Foo.java:12: error: cannot find symbol
 *
 * Se soportan ambos formatos reales, más un fallback para líneas de fallo de Gradle sin ubicación
 * (`FAILURE: Build failed...`, `* What went wrong:`) que igual se quieren destacar como error.
 */
object CompilerOutputParser {

    // "e: file:///C:/Users/.../Foo.kt:996:27 mensaje" (o "w:" para warning) — formato real de
    // kotlinc invocado por Gradle en este proyecto, NO el "(line, col)" de versiones viejas.
    private val KOTLINC = Regex("""^([ew]):\s*file://+(.+?):(\d+):(\d+)\s+(.*)$""")

    // "/ruta/Foo.java:12: error: mensaje" (columna opcional) — formato GNU clásico de javac.
    private val JAVAC = Regex("""^(.+?\.java):(\d+):\s*(error|warning|note):\s*(.*)$""", RegexOption.IGNORE_CASE)

    // Fallo de Gradle sin ubicación de archivo — igual se quiere marcar como error real.
    private val GRADLE_FAILURE = Regex("""^(FAILURE:|\*\s*What went wrong:|.*BUILD FAILED.*)""")

    /** Parsea una línea — devuelve `null` si es ruido (progreso de tareas, texto informativo) que
     * no aporta como diagnóstico estructurado (sigue viéndose igual en el log crudo, esto solo
     * decide qué se resalta/cuenta aparte). */
    fun parseLine(line: String): BuildDiagnostic? {
        KOTLINC.matchEntire(line)?.let { m ->
            val (sev, file, lineNo, col, msg) = m.destructured
            return BuildDiagnostic(
                severity = if (sev == "e") BuildSeverity.ERROR else BuildSeverity.WARNING,
                message = msg.trim(),
                file = file,
                line = lineNo.toIntOrNull(),
                column = col.toIntOrNull(),
                raw = line,
            )
        }
        JAVAC.matchEntire(line)?.let { m ->
            val (file, lineNo, sev, msg) = m.destructured
            return BuildDiagnostic(
                severity = severityOf(sev),
                message = msg.trim(),
                file = file,
                line = lineNo.toIntOrNull(),
                raw = line,
            )
        }
        if (GRADLE_FAILURE.containsMatchIn(line)) {
            return BuildDiagnostic(severity = BuildSeverity.ERROR, message = line.trim(), raw = line)
        }
        return null
    }

    private fun severityOf(s: String): BuildSeverity = when (s.lowercase()) {
        "error" -> BuildSeverity.ERROR
        "warning" -> BuildSeverity.WARNING
        else -> BuildSeverity.INFO
    }
}
