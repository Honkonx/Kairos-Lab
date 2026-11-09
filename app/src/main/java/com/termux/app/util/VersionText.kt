package com.termux.app.util

/**
 * Deja del texto de versión que los scripts guardan en el registry solo "los dígitos", para que la lista de
 * módulos ponga su propia "v" (regla del proyecto: la versión se muestra siempre como v + números).
 *
 * Los scripts guardan a veces la primera línea cruda de `<binario> --version`: `llama-server` imprime
 * "version: 0.6.0-dev (build 1, commit 6184e92)" (se veía "vversion: 0.6.0-dev…") y `gitea --version` imprime
 * "Gitea version 1.26.1 built with GNU Make 4.4.1, go1.25 : bindata, sqlite…" (una línea larguísima en la
 * tarjeta). Se toma el primer número con puntos y, si lo trae, su sufijo de pre-lanzamiento ("-dev", "-rc1").
 * Si no hay ningún número con puntos (p. ej. "latest", "0.x", "vim-9") se conserva el texto, recortado.
 */
object VersionText {
    private val DOTTED = Regex("""\d+(?:\.\d+)+(?:-[0-9A-Za-z.]+)?""")
    private val LABEL = Regex("""^\s*(?:version|versión)\s*[:=]?\s*""", RegexOption.IGNORE_CASE)
    private val LEADING_V = Regex("""^[vV](?=\d)""")
    private const val MAX_FALLBACK = 20

    fun clean(raw: String): String {
        DOTTED.find(raw)?.let { return it.value }
        val noLabel = raw.replace(LABEL, "").trim().replace(LEADING_V, "")
        return if (noLabel.length <= MAX_FALLBACK) noLabel else noLabel.take(MAX_FALLBACK).trimEnd() + "…"
    }
}
