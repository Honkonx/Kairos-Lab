package com.termux.app.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.termux.R
import com.termux.app.model.ModuleInfo

/**
 * Dispatch compartido de navegación al detalle de un módulo (Fase B, 2026-08-10).
 *
 * Antes vivía como método privado de ModulesFragment (navigateToModuleDetail) y la Tienda de
 * plugins (PluginsFragment) lo necesitaba también — se extrajo acá para que AMBOS consuman el
 * mismo mapeo id → fragment. Los 14 módulos con UI específica real conservan su fragment
 * dedicado; cualquier módulo nuevo del catálogo cae en GenericModuleFragment (Fase A), sin
 * requerir una clase Kotlin nueva.
 */
object ModuleDetailNavigator {

    fun navigate(fragmentManager: FragmentManager, module: ModuleInfo) {
        val fragment = when (module.id) {
            "ollama" -> OllamaFragment()
            "claude" -> ClaudeFragment()
            "codex" -> CodexFragment()
            "antigravity" -> AntigravityFragment()
            "opencode" -> OpenCodeFragment()
            "openclaw" -> OpenClawFragment()
            "hermes" -> HermesFragment()
            "python" -> PythonFragment()
            "n8n" -> N8nFragment()
            "remote" -> RemoteFragment()
            "expo" -> ExpoFragment()
            "entorno" -> EntornoFragment()
            "engram" -> EngramFragment()
            "llamaserver" -> LlamaServerFragment()
            "db" -> DbFragment()
            "stacks" -> StacksFragment()
            "ciberseguridad" -> CiberseguridadFragment()
            "udocker" -> UdockerFragment()
            "qemu" -> QemuFragment()
            "cactus" -> CactusFragment()
            "repo" -> RepoFragment()
            "docker" -> DockerFragment()
            "ide" -> IdeFragment()
            "verificar" -> VerificarFragment()
            "apk" -> ApkFragment()
            "languages" -> LanguagesFragment()
            "packages" -> PackagesFragment()
            "freebuff" -> CliToolFragment.newInstance(module.id, module.name)
            "codebuff" -> CliToolFragment.newInstance(module.id, module.name)
            "copilotcli" -> CliToolFragment.newInstance(module.id, module.name)
            "minimaxcli" -> CliToolFragment.newInstance(module.id, module.name)
            "mimocode" -> CliToolFragment.newInstance(module.id, module.name)
            "mistralvibe" -> CliToolFragment.newInstance(module.id, module.name)
            "qwencode" -> CliToolFragment.newInstance(module.id, module.name)
            "kimi" -> CliToolFragment.newInstance(module.id, module.name)
            "kilo" -> CliToolFragment.newInstance(module.id, module.name)
            "cursor" -> CliToolFragment.newInstance(module.id, module.name)
            "hf" -> CliToolFragment.newInstance(module.id, module.name)
            "pi" -> CliToolFragment.newInstance(module.id, module.name)
            "codegraph" -> CliToolFragment.newInstance(module.id, module.name)
            "ohmypi" -> CliToolFragment.newInstance(module.id, module.name)
            else -> GenericModuleFragment.newInstance(module)
        }
        fragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}
