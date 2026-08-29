package com.termux.app.ui.studio.filetree

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.termux.R

/**
 * Adapter plano (no anidado) del árbol de archivos: mantiene una sola lista visible
 * ([visibleNodes]) que crece/encoge al expandir o colapsar carpetas — mismo patrón que usan la
 * mayoría de file trees de RecyclerView (evita RecyclerView-dentro-de-RecyclerView). La carga
 * real de hijos vía SAF la hace quien llama ([onExpandRequested]), porque `DocumentFile.listFiles()`
 * es I/O bloqueante y este adapter no debe saber de threads.
 *
 * [onContextMenuRequested] se dispara con long-press en la fila o tap en el ícono "⋮" — quien
 * llama (`MainActivity`) es responsable de mostrar el menú real y de ejecutar la operación de
 * archivo (SAF no vive acá, ver [refreshNode]).
 *
 * Filtro de búsqueda ([setFilter]): mientras hay texto de filtro activo, el árbol se muestra
 * "aplanado" (solo nodos ya cargados cuyo nombre matchea) y expand/collapse queda deshabilitado
 * para no desincronizar posiciones reales (`visibleNodes`) vs. posiciones mostradas — tocar una
 * carpeta filtrada limpia el filtro en vez de expandir, tocar un archivo lo abre igual.
 */
class FileTreeAdapter(
    private val onFileClick: (FileNode) -> Unit,
    private val onExpandRequested: (FileNode) -> Unit,
    private val onContextMenuRequested: (FileNode, View) -> Unit = { _, _ -> },
    private val onFilteredDirectoryClicked: (() -> Unit)? = null
) : RecyclerView.Adapter<FileTreeAdapter.ViewHolder>() {

    private val visibleNodes = mutableListOf<FileNode>()
    private var filterQuery: String = ""

    fun setRootChildren(nodes: List<FileNode>) {
        visibleNodes.clear()
        visibleNodes.addAll(nodes)
        notifyDataSetChanged()
    }

    fun clear() {
        visibleNodes.clear()
        notifyDataSetChanged()
    }

    /** Filtra por nombre (case-insensitive) sobre los nodos ya cargados/visibles — no recorre
     * subcarpetas todavía no expandidas. Pasar cadena vacía restaura el árbol completo. */
    fun setFilter(query: String) {
        filterQuery = query.trim()
        notifyDataSetChanged()
    }

    private fun currentList(): List<FileNode> =
        if (filterQuery.isEmpty()) {
            visibleNodes
        } else {
            visibleNodes.filter { it.name.contains(filterQuery, ignoreCase = true) }
        }

    /** Llamado por quien maneja [onExpandRequested] cuando terminó de listar los hijos. */
    fun onChildrenLoaded(node: FileNode, children: List<FileNode>) {
        node.isLoading = false
        val position = visibleNodes.indexOf(node)
        if (position == -1) return
        node.children.clear()
        node.children.addAll(children)
        node.childrenLoaded = true
        node.isExpanded = true
        visibleNodes.addAll(position + 1, children)
        notifyItemChanged(position)
        notifyItemRangeInserted(position + 1, children.size)
    }

    /**
     * Reemplaza los hijos cacheados de [node] con [newChildren] recién releídos vía SAF —
     * usado después de crear/renombrar/eliminar dentro de este nodo, para refrescar solo el
     * subárbol afectado en vez de recargar todo el árbol desde la raíz. Si [node] no está
     * visible actualmente (carpeta colapsada en un ancestro) solo actualiza el caché, sin tocar
     * la lista visible.
     */
    fun refreshNode(node: FileNode, newChildren: List<FileNode>) {
        val position = visibleNodes.indexOf(node)
        if (position == -1) {
            node.children.clear()
            node.children.addAll(newChildren)
            node.childrenLoaded = true
            return
        }
        if (node.isExpanded) {
            val removedCount = countVisibleDescendants(node)
            repeat(removedCount) { visibleNodes.removeAt(position + 1) }
        }
        node.children.clear()
        node.children.addAll(newChildren)
        node.childrenLoaded = true
        node.isExpanded = true
        visibleNodes.addAll(position + 1, newChildren)
        notifyDataSetChanged()
    }

    private fun toggleExpansion(node: FileNode, position: Int) {
        when {
            node.isExpanded -> collapse(node, position)
            node.childrenLoaded -> expandFromCache(node, position)
            node.isLoading -> Unit // ya hay una carga SAF en vuelo para este nodo, no disparar otra
            else -> {
                node.isLoading = true
                onExpandRequested(node)
            }
        }
    }

    private fun expandFromCache(node: FileNode, position: Int) {
        node.isExpanded = true
        visibleNodes.addAll(position + 1, node.children)
        notifyItemChanged(position)
        notifyItemRangeInserted(position + 1, node.children.size)
    }

    private fun collapse(node: FileNode, position: Int) {
        node.isExpanded = false
        val removedCount = countVisibleDescendants(node)
        if (removedCount > 0) {
            repeat(removedCount) { visibleNodes.removeAt(position + 1) }
            notifyItemRangeRemoved(position + 1, removedCount)
        }
        notifyItemChanged(position)
    }

    private fun countVisibleDescendants(node: FileNode): Int {
        var count = 0
        for (child in node.children) {
            if (visibleNodes.contains(child)) {
                count += 1 + countVisibleDescendants(child)
            }
        }
        return count
    }

    override fun getItemCount(): Int = currentList().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.studio_item_file_tree, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = currentList()[position]
        holder.bind(node)
        holder.itemView.setOnClickListener { handleClick(node) }
        holder.itemView.setOnLongClickListener {
            onContextMenuRequested(node, holder.itemView)
            true
        }
        holder.menuButton.setOnClickListener { onContextMenuRequested(node, holder.menuButton) }
    }

    private fun handleClick(node: FileNode) {
        if (filterQuery.isNotEmpty()) {
            if (node.isDirectory) {
                onFilteredDirectoryClicked?.invoke()
                setFilter("")
            } else {
                onFileClick(node)
            }
            return
        }
        val position = visibleNodes.indexOf(node)
        if (position == -1) return
        if (node.isDirectory) {
            toggleExpansion(node, position)
        } else {
            onFileClick(node)
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chevron: ImageView = itemView.findViewById(R.id.filetree_chevron)
        private val icon: ImageView = itemView.findViewById(R.id.filetree_icon)
        private val nameLabel: TextView = itemView.findViewById(R.id.filetree_name)
        val menuButton: ImageView = itemView.findViewById(R.id.filetree_menu)

        private val baseIndentPx = (8 * itemView.resources.displayMetrics.density).toInt()
        private val depthIndentPx = (16 * itemView.resources.displayMetrics.density).toInt()

        fun bind(node: FileNode) {
            itemView.setPaddingRelative(
                baseIndentPx + node.depth * depthIndentPx,
                itemView.paddingTop,
                itemView.paddingEnd,
                itemView.paddingBottom
            )

            chevron.visibility = if (node.isDirectory) View.VISIBLE else View.INVISIBLE
            chevron.rotation = if (node.isExpanded) 90f else 0f

            val spec = FileIconProvider.iconFor(node.name, node.isDirectory, node.isExpanded)
            icon.setImageResource(spec.drawableRes)
            icon.setColorFilter(ContextCompat.getColor(itemView.context, spec.colorRes))

            nameLabel.text = node.name
        }
    }
}
