package com.termux.app.ui.studio.palette

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.R

/**
 * Diálogo de la paleta de comandos (Ctrl+P) — campo de filtro + lista filtrable en vivo (ver
 * `dialog_command_palette.xml`). Elegido `AlertDialog` sobre una `Activity` propia porque el
 * resto de diálogos "de acción rápida" de la app (nuevo archivo, renombrar, proyectos recientes,
 * preguntar a la IA — ver `MainActivity.showNewEntryDialog`/`showRecentProjectsDialog`/
 * `showAskAiDialog`) ya usan `AlertDialog.Builder` con una vista custom; no hacía falta el peso
 * de una Activity nueva (ni su entrada en `AndroidManifest.xml`) para una paleta que no navega a
 * ningún lado — solo filtra y ejecuta.
 *
 * Uso: `CommandPaletteDialog.show(activity, CommandRegistry.buildCommands(activity))`.
 */
object CommandPaletteDialog {

    fun show(context: Context, commands: List<Command>) {
        val view = LayoutInflater.from(context).inflate(R.layout.studio_dialog_command_palette, null)
        val filterInput = view.findViewById<EditText>(R.id.command_palette_filter_input)
        val recycler = view.findViewById<RecyclerView>(R.id.command_palette_recycler)
        val emptyLabel = view.findViewById<TextView>(R.id.command_palette_empty_label)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        val adapter = CommandPaletteAdapter(commands) { command ->
            dialog.dismiss()
            command.action()
        }
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = adapter

        fun updateEmptyState() {
            emptyLabel.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE
        }
        updateEmptyState()

        filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.setFilter(s?.toString().orEmpty())
                updateEmptyState()
            }
        })

        dialog.show()
        filterInput.requestFocus()
    }
}
