package com.termux.app.ui.studio.editor

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import com.termux.R
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher

/**
 * Barra de búsqueda/reemplazo del archivo activo. Envuelve la API nativa de búsqueda de
 * sora-editor (`CodeEditor.getSearcher()` -> [EditorSearcher]) en vez de reimplementar
 * búsqueda de texto a mano — API confirmada real (no de memoria) decompilando
 * `editor-0.24.4.aar` del caché de Gradle local con Python (mismo método que la ronda
 * anterior usó para `subscribeEvent`/`SelectionChangeEvent`, ver `MainActivity.kt`):
 *
 * ```
 * io.github.rosemoe.sora.widget.EditorSearcher
 *   search(String pattern, EditorSearcher.SearchOptions options)
 *   gotoNext(): boolean / gotoPrevious(): boolean
 *   stopSearch()
 *   hasQuery(): boolean
 *   getCurrentMatchedPositionIndex(): int / getMatchedPositionCount(): int
 *   replaceCurrentMatch(String replacement) / replaceAll(String replacement)
 *   EditorSearcher.SearchOptions(int type, boolean caseInsensitive)
 *     type: SearchOptions.TYPE_NORMAL | TYPE_WHOLE_WORD | TYPE_REGULAR_EXPRESSION
 * ```
 *
 * LIMITACIÓN CONOCIDA: `search()` corre el matching en un hilo aparte
 * (`EditorSearcher.SearchRunnable`) y no expone un callback público de "terminó" — el contador
 * de coincidencias se refresca con un pequeño delay tras cada `search()` ([SEARCH_RESULT_DELAY_MS])
 * en vez de sincronizarse con precisión perfecta. En archivos muy grandes el contador puede
 * quedar desactualizado por una fracción de segundo; no se probó en un archivo de miles de
 * líneas en dispositivo real.
 */
class EditorSearchController(
    private val codeEditor: CodeEditor,
    barRoot: View,
    private val onClosed: () -> Unit = {}
) {

    companion object {
        private const val SEARCH_RESULT_DELAY_MS = 180L
    }

    private val queryInput: EditText = barRoot.findViewById(R.id.search_query_input)
    private val counterLabel: TextView = barRoot.findViewById(R.id.search_match_counter)
    private val replaceRow: View = barRoot.findViewById(R.id.search_replace_row)
    private val replacementInput: EditText = barRoot.findViewById(R.id.search_replacement_input)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingCounterRefresh: Runnable? = null

    val rootView: View = barRoot

    init {
        barRoot.findViewById<ImageButton>(R.id.search_prev_button).setOnClickListener {
            codeEditor.searcher.gotoPrevious()
            refreshCounter()
        }
        barRoot.findViewById<ImageButton>(R.id.search_next_button).setOnClickListener {
            codeEditor.searcher.gotoNext()
            refreshCounter()
        }
        barRoot.findViewById<ImageButton>(R.id.search_toggle_replace_button).setOnClickListener {
            replaceRow.visibility = if (replaceRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        barRoot.findViewById<ImageButton>(R.id.search_close_button).setOnClickListener { hide() }

        barRoot.findViewById<View>(R.id.search_replace_button).setOnClickListener { replaceCurrent() }
        barRoot.findViewById<View>(R.id.search_replace_all_button).setOnClickListener { replaceAll() }

        queryInput.addTextChangedListener { text -> runSearch(text?.toString().orEmpty()) }
        queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                codeEditor.searcher.gotoNext()
                refreshCounter()
                true
            } else {
                false
            }
        }
    }

    fun show() {
        rootView.visibility = View.VISIBLE
        queryInput.requestFocus()
        val currentQuery = queryInput.text?.toString().orEmpty()
        if (currentQuery.isNotEmpty()) runSearch(currentQuery)
    }

    fun hide() {
        cancelPendingCounterRefresh()
        codeEditor.searcher.stopSearch()
        rootView.visibility = View.GONE
        onClosed()
    }

    fun isVisible(): Boolean = rootView.visibility == View.VISIBLE

    private fun runSearch(pattern: String) {
        if (pattern.isEmpty()) {
            codeEditor.searcher.stopSearch()
            updateCounterLabel(0, 0)
            return
        }
        val options = EditorSearcher.SearchOptions(
            EditorSearcher.SearchOptions.TYPE_NORMAL,
            /* caseInsensitive = */ true
        )
        codeEditor.searcher.search(pattern, options)
        refreshCounter()
    }

    /** Ver nota de limitación en el KDoc de la clase — no hay callback nativo de "búsqueda
     * terminada", así que se agenda un refresco corto en vez de leer el contador al toque. */
    private fun refreshCounter() {
        cancelPendingCounterRefresh()
        val runnable = Runnable {
            val searcher = codeEditor.searcher
            if (searcher.hasQuery()) {
                updateCounterLabel(searcher.getCurrentMatchedPositionIndex() + 1, searcher.getMatchedPositionCount())
            } else {
                updateCounterLabel(0, 0)
            }
        }
        pendingCounterRefresh = runnable
        mainHandler.postDelayed(runnable, SEARCH_RESULT_DELAY_MS)
    }

    private fun cancelPendingCounterRefresh() {
        pendingCounterRefresh?.let { mainHandler.removeCallbacks(it) }
        pendingCounterRefresh = null
    }

    private fun updateCounterLabel(current: Int, total: Int) {
        counterLabel.text = if (total <= 0) {
            counterLabel.context.getString(R.string.search_no_matches)
        } else {
            counterLabel.context.getString(R.string.search_match_counter_format, current, total)
        }
    }

    private fun requireQuery(): Boolean {
        if (!codeEditor.searcher.hasQuery()) {
            Toast.makeText(rootView.context, R.string.search_no_query_toast, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun replaceCurrent() {
        if (!requireQuery()) return
        codeEditor.searcher.replaceCurrentMatch(replacementInput.text?.toString().orEmpty())
        refreshCounter()
    }

    private fun replaceAll() {
        if (!requireQuery()) return
        val matchCountBeforeReplace = codeEditor.searcher.matchedPositionCountSnapshot()
        codeEditor.searcher.replaceAll(replacementInput.text?.toString().orEmpty())
        Toast.makeText(
            rootView.context,
            rootView.context.getString(R.string.search_replaced_all_toast, matchCountBeforeReplace),
            Toast.LENGTH_SHORT
        ).show()
        mainHandler.postDelayed({ updateCounterLabel(0, 0) }, SEARCH_RESULT_DELAY_MS)
    }
}

/** `getMatchedPositionCount()` sigue siendo válido hasta que `replaceAll` limpia los
 * resultados — se lee antes de reemplazar solo para mostrarle al usuario cuántos reemplazos
 * hizo, ya que `replaceAll(String)` no devuelve un conteo. */
private fun EditorSearcher.matchedPositionCountSnapshot(): Int =
    if (hasQuery()) getMatchedPositionCount() else 0
