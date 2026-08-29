package com.termux.app.ui.studio.palette

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.termux.R

/**
 * Adapter de la lista de comandos visible dentro de [CommandPaletteDialog]. La lista completa
 * ([allCommands]) es fija (viene de [CommandRegistry.buildCommands]); lo único que cambia en
 * vivo es [visibleCommands], recalculada con [CommandRegistry.filter] cada vez que cambia el
 * texto del campo de filtro.
 */
class CommandPaletteAdapter(
    private val allCommands: List<Command>,
    private val onCommandSelected: (Command) -> Unit
) : RecyclerView.Adapter<CommandPaletteAdapter.ViewHolder>() {

    private var visibleCommands: List<Command> = allCommands

    /** Reaplica el filtro sobre [allCommands] y refresca la lista mostrada. */
    fun setFilter(query: String) {
        visibleCommands = CommandRegistry.filter(allCommands, query)
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = visibleCommands.isEmpty()

    override fun getItemCount(): Int = visibleCommands.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.studio_item_command_palette, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val command = visibleCommands[position]
        holder.bind(command)
        holder.itemView.setOnClickListener { onCommandSelected(command) }
    }

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.command_palette_item_icon)
        private val title: TextView = itemView.findViewById(R.id.command_palette_item_title)

        fun bind(command: Command) {
            title.text = command.title
            if (command.iconRes != null) {
                icon.visibility = android.view.View.VISIBLE
                icon.setImageResource(command.iconRes)
            } else {
                icon.visibility = android.view.View.INVISIBLE
            }
        }
    }
}
