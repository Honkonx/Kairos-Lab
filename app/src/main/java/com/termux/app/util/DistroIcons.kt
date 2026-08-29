package com.termux.app.util

import com.termux.R

/**
 * Registro de ícono de identidad + color de marca por distro de `EntornoNative.KNOWN_DISTROS`
 * (2026-08-27, ver docs/humano259.md — pedido explícito del usuario: reemplazar el emoji
 * genérico 🐧 fijo que se mostraba para CUALQUIER distro instalada por un logo real por distro,
 * mismo criterio ya usado para íconos de módulo — `ic_module_*` + `iconBg`, ver
 * `ModuleRowRenderer.bindModuleIcon()`). Distinto de `ModuleIcons.kt` (íconos de MÓDULO/CLI,
 * no de distro Linux) — no se fusionan porque las listas de nombres no se superponen y la
 * necesidad es puramente de identidad visual dentro de EntornoFragment (inventario + picker de
 * instalación), no del catálogo de módulos.
 *
 * Colores tomados de la paleta de marca oficial de cada proyecto (aproximados, no un pedido de
 * asset con derechos de autor) — mismo criterio de "interpretación vectorial simple" pedido
 * por el usuario para los propios drawables `ic_distro_*.xml`.
 */
object DistroIcons {
    private data class Entry(val iconRes: Int, val colorHex: String)

    private val REGISTRY: Map<String, Entry> = mapOf(
        "ubuntu" to Entry(R.drawable.ic_distro_ubuntu, "#E95420"),
        "debian" to Entry(R.drawable.ic_distro_debian, "#A81D33"),
        "alpine" to Entry(R.drawable.ic_distro_alpine, "#0D597F"),
        "archlinux" to Entry(R.drawable.ic_distro_archlinux, "#1793D1"),
        "fedora" to Entry(R.drawable.ic_distro_fedora, "#3C6EB4"),
        "void" to Entry(R.drawable.ic_distro_void, "#295C4C"),
        "kali" to Entry(R.drawable.ic_distro_kali, "#367BF0"),
        "manjaro" to Entry(R.drawable.ic_distro_manjaro, "#35BF5C"),
        "rockylinux" to Entry(R.drawable.ic_distro_rockylinux, "#6DA34D"),
        "opensuse-tumbleweed" to Entry(R.drawable.ic_distro_opensuse, "#73BA25")
    )

    // Fallback para cualquier nombre no listado arriba (ej. un alias nuevo agregado a
    // KNOWN_DISTROS sin actualizar este registro todavía) — pingüino genérico de buena
    // calidad ya usado como ícono de módulo de "Entorno" (ic_module_entorno), nunca queda sin
    // ícono (pedido explícito del usuario: "si alguna distro no tiene un logo [...] usá un
    // ícono de pingüino genérico [...] no dejes ninguna sin nada").
    private val FALLBACK = Entry(R.drawable.ic_module_entorno, "#3A3A3A")

    fun iconRes(distroName: String): Int = (REGISTRY[distroName] ?: FALLBACK).iconRes

    fun colorHex(distroName: String): String = (REGISTRY[distroName] ?: FALLBACK).colorHex
}
