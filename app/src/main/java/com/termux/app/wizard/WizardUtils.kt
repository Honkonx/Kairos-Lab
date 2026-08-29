package com.termux.app.wizard

import androidx.fragment.app.Fragment

/**
 * Conversión dp->px compartida por las pantallas del wizard — reemplaza 7 copias locales
 * casi idénticas (`WizardWelcomeFragment`, `WizardPermissionsFragment` x2,
 * `WizardBatteryFragment`, `WizardCheckFragment`, `WizardInstallFragment`,
 * `WizardPhantomProcessFragment`). Ver AUDITORIA_CODIGO_2026-08-13.md §3.7.
 */
fun Fragment.dp(d: Int): Int = (d * resources.displayMetrics.density).toInt()
