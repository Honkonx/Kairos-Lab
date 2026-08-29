package com.termux.app.wizard

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/** 6 pantallas del wizard, en orden: bienvenida -> permisos -> procesos fantasma ->
 * batería -> instalación (bootstrap + rootfs opcional + kairos.sh) -> comprobar paquetes
 * (opcional). Ver docs/humano/humano10.md/humano11.md para el pedido explícito del orden
 * original (4 pantallas) y docs/humano53.md/humano54.md (ronda 2026-08-04) para el rediseño
 * que agregó procesos fantasma y batería como pantallas propias, sacándolas de dentro del
 * paso de instalación — ver docs/bootstrap/ROOTFS_EMBEBIDO.md para el diseño completo del
 * rootfs. Patrón ViewPager2+FragmentStateAdapter tomado de ver/MiceWine-Application-master/
 * (WelcomeActivity/AdapterWelcomeFragments). */
class WizardPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 6

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> WizardWelcomeFragment()
        1 -> WizardPermissionsFragment()
        2 -> WizardPhantomProcessFragment()
        3 -> WizardBatteryFragment()
        4 -> WizardInstallFragment()
        5 -> WizardCheckFragment()
        else -> throw IllegalArgumentException("Página de wizard inválida: $position")
    }
}
