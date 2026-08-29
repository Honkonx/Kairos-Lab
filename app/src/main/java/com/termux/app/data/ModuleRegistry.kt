package com.termux.app.data

import android.content.Context
import com.termux.app.model.ModuleInfo
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class ModuleRegistry(private val context: Context) {

    private val registryMap: MutableMap<String, String> = mutableMapOf()

    fun load(): ModuleRegistry {
        registryMap.clear()
        // El registry real lo escriben los scripts bash en $HOME/.android_server_registry
        // (Termux real, TERMUX_HOME_DIR_PATH) — NO en context.filesDir (sandbox interno
        // de la app, un directorio distinto donde nunca se escribe nada).
        val file = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".android_server_registry")
        if (!file.exists()) return this
        try {
            BufferedReader(FileReader(file)).use { reader ->
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val eq = trimmed.indexOf('=')
                        if (eq > 0) {
                            val key = trimmed.substring(0, eq).trim()
                            val value = trimmed.substring(eq + 1).trim()
                            registryMap[key] = value
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return this
    }

    fun get(key: String): String? = registryMap[key]

    fun getModules(): Map<String, String> = registryMap.toMap()

    fun isProotInstalled(): Boolean {
        val file = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin/proot")
        return file.exists()
    }
}
