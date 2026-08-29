package com.termux.app.ui.studio.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.termux.R

/** Lista plana de [ProjectSearchResult] — sin agrupar por archivo a propósito (KISS): cada fila
 * ya muestra la ruta completa, agrupar visualmente por archivo es una mejora posible pero no
 * necesaria para que la búsqueda sea navegable. */
class ProjectSearchAdapter(
    private val onResultClick: (ProjectSearchResult) -> Unit
) : RecyclerView.Adapter<ProjectSearchAdapter.ViewHolder>() {

    private val results = mutableListOf<ProjectSearchResult>()

    fun submitResults(newResults: List<ProjectSearchResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }

    fun addResult(result: ProjectSearchResult) {
        results.add(result)
        notifyItemInserted(results.size - 1)
    }

    fun clear() {
        results.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = results.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.studio_item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        holder.bind(result)
        holder.itemView.setOnClickListener { onResultClick(result) }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val pathLabel: TextView = itemView.findViewById(R.id.search_result_path)
        private val lineLabel: TextView = itemView.findViewById(R.id.search_result_line)

        fun bind(result: ProjectSearchResult) {
            pathLabel.text = "${result.relativePath}:${result.lineNumber}"
            lineLabel.text = result.lineText
        }
    }
}
