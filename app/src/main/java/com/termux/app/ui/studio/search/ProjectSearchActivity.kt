package com.termux.app.ui.studio.search

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.termux.R
import com.termux.databinding.StudioActivityProjectSearchBinding

/**
 * Pantalla "Buscar en proyecto": recorre TODO el árbol del proyecto abierto (no solo el archivo
 * activo, ver [com.termux.app.ui.studio.editor.EditorSearchController] para eso) buscando un término, y
 * muestra los resultados como lista navegable (archivo + línea + contexto). Tocar un resultado
 * cierra esta pantalla devolviendo el URI del archivo + número de línea a [MainActivity][com.termux.app.ui.studio.MainActivity],
 * que abre el archivo en una pestaña y posiciona el cursor ahí.
 *
 * Recibe la carpeta raíz del proyecto como un URI de árbol SAF ya persistido (mismo
 * `content://.../tree/...` que ya usa el sidebar) — no como ruta real de filesystem, porque el
 * recorrido acá usa `DocumentFile`/`ContentResolver` igual que el resto del editor, no Termux.
 */
class ProjectSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_TREE_URI = "project_tree_uri"
        const val EXTRA_RESULT_FILE_URI = "result_file_uri"
        const val EXTRA_RESULT_LINE_NUMBER = "result_line_number"
    }

    private lateinit var binding: StudioActivityProjectSearchBinding
    private lateinit var resultsAdapter: ProjectSearchAdapter

    /** Incrementado en cada búsqueda nueva — el hilo de fondo de una búsqueda anterior lo revisa
     * para saber que ya no es la búsqueda vigente y debe abortar en vez de seguir publicando
     * resultados viejos sobre la lista nueva. */
    @Volatile
    private var searchGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = StudioActivityProjectSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        resultsAdapter = ProjectSearchAdapter(onResultClick = ::finishWithResult)
        binding.projectSearchResultsRecycler.layoutManager = LinearLayoutManager(this)
        binding.projectSearchResultsRecycler.adapter = resultsAdapter

        binding.projectSearchRunButton.setOnClickListener { runSearch() }
        binding.projectSearchQueryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }

        if (projectTreeUri() == null) {
            binding.projectSearchStatusLabel.text = getString(R.string.project_search_no_project)
            binding.projectSearchRunButton.isEnabled = false
            binding.projectSearchQueryInput.isEnabled = false
        }
    }

    private fun projectTreeUri(): Uri? = intent.getParcelableExtra(EXTRA_PROJECT_TREE_URI)

    private fun runSearch() {
        val treeUri = projectTreeUri() ?: return
        val query = binding.projectSearchQueryInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return

        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null) {
            binding.projectSearchStatusLabel.text = getString(R.string.project_search_no_project)
            return
        }

        val thisGeneration = ++searchGeneration
        resultsAdapter.clear()
        binding.projectSearchStatusLabel.text = getString(R.string.project_search_searching)

        Thread {
            var matchedFiles = 0
            var lastFileUri: Uri? = null
            val totalMatches = ProjectSearchEngine.searchBlocking(
                root = root,
                query = query,
                openInputStream = { uri -> contentResolver.openInputStream(uri) },
                isCancelled = { thisGeneration != searchGeneration }
            ) { result ->
                if (result.uri != lastFileUri) {
                    matchedFiles++
                    lastFileUri = result.uri
                }
                runOnUiThread {
                    if (thisGeneration == searchGeneration) resultsAdapter.addResult(result)
                }
            }
            runOnUiThread {
                if (thisGeneration == searchGeneration) onSearchFinished(totalMatches, matchedFiles)
            }
        }.start()
    }

    private fun onSearchFinished(totalMatches: Int, matchedFiles: Int) {
        binding.projectSearchStatusLabel.text = if (totalMatches == 0) {
            getString(R.string.project_search_no_results)
        } else {
            getString(R.string.project_search_results_count, totalMatches, matchedFiles)
        }
    }

    private fun finishWithResult(result: ProjectSearchResult) {
        val data = Intent().apply {
            putExtra(EXTRA_RESULT_FILE_URI, result.uri)
            putExtra(EXTRA_RESULT_LINE_NUMBER, result.lineNumber)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Invalida cualquier búsqueda de fondo todavía corriendo para que deje de tocar la
        // Activity (y el adapter) después de destruida.
        searchGeneration++
    }
}
