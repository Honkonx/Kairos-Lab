package com.termux.app.ui

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.R
import java.io.BufferedReader
import java.io.FileReader
import com.termux.app.util.kairosThemeColor

class LogsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var searchInput: EditText
    private var logPath: String = ""
    private var allLines = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_logs, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logPath = arguments?.getString("log_path") ?: ""

        view.findViewById<TextView>(R.id.back_btn).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btn_clear_logs).setOnClickListener {
            allLines.clear()
            refreshAdapter("")
        }

        searchInput = view.findViewById(R.id.search_logs)
        recycler = view.findViewById(R.id.logs_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                refreshAdapter(s?.toString() ?: "")
            }
        })

        loadLogs()
    }

    // Bug real (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §1.12): la lectura del log corría directa en el hilo de UI. Se lee en un Thread de
    // fondo hacia una lista local (allLines solo se toca desde el hilo de UI, evitando
    // condiciones de carrera con refreshAdapter()/el filtro de búsqueda).
    private fun loadLogs() {
        if (logPath.isEmpty()) {
            allLines.clear()
            allLines.add("(Sin archivo de log)")
            refreshAdapter("")
            return
        }
        val path = logPath
        Thread {
            val lines = mutableListOf<String>()
            try {
                BufferedReader(FileReader(path)).use { reader ->
                    reader.forEachLine { lines.add(it) }
                }
            } catch (_: Exception) {
                lines.add("(No se pudo leer: $path)")
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                allLines.clear()
                allLines.addAll(lines)
                refreshAdapter("")
                recycler.scrollToPosition(allLines.size - 1)
            }
        }.start()
    }

    private fun refreshAdapter(filter: String) {
        val filtered = if (filter.isEmpty()) allLines
        else allLines.filter { it.contains(filter, ignoreCase = true) }

        recycler.adapter = object : RecyclerView.Adapter<LogsFragment.VH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
                LayoutInflater.from(p.context).inflate(android.R.layout.simple_list_item_1, p, false)
            )
            override fun onBindViewHolder(h: VH, i: Int) {
                val line = filtered[i]
                h.tv.text = colorizeLine(line)
                h.tv.textSize = 11f
                h.tv.setTypeface(android.graphics.Typeface.MONOSPACE)
                h.tv.setPadding(dp(4), dp(3), dp(4), dp(3))
            }
            override fun getItemCount() = filtered.size
        }
    }

    private fun colorizeLine(line: String): SpannableString {
        val ss = SpannableString(line)
        val color = when {
            line.contains("[OK]") || line.contains("[INFO]") ->
                requireContext().kairosThemeColor(R.attr.kairosBlue)
            line.contains("[WARN]") ->
                requireContext().kairosThemeColor(R.attr.kairosAmber)
            line.contains("[ERROR]") ->
                requireContext().kairosThemeColor(R.attr.kairosRed)
            line.contains("✓") || line.contains("success") ->
                requireContext().kairosThemeColor(R.attr.kairosGreen)
            else -> requireContext().kairosThemeColor(R.attr.kairosText)
        }
        ss.setSpan(ForegroundColorSpan(color), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tv: TextView = itemView.findViewById(android.R.id.text1)
    }

    companion object {
        fun newInstance(logPath: String): LogsFragment {
            return LogsFragment().apply {
                arguments = Bundle().apply { putString("log_path", logPath) }
            }
        }
    }

    private fun dp(d: Int) = (d * resources.displayMetrics.density).toInt()
}
