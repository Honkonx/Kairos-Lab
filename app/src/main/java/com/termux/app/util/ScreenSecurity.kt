package com.termux.app.util

import android.view.WindowManager
import androidx.fragment.app.Fragment

/**
 * FLAG_SECURE por pantalla, no global (hallazgo real de auditoría de referencia, 2026-09-15:
 * AnARCHIS12/homelab, un dashboard homelab competidor directo, usa FLAG_SECURE + bloqueo
 * biométrico opcional como pilar de seguridad en pantallas con credenciales — ver
 * docs/referencias/. Kairos ya cumple la regla "un secreto nunca se vuelve a mostrar" en
 * Remote/Homelab (ver .claude/rules/kairos-secrets-never-revealed.md), pero no bloqueaba
 * capturas de pantalla ni grabación mientras un token/clave estaba visible siendo tipeado).
 *
 * FLAG_SECURE es un flag de `Window` (la Activity completa — Kairos usa una sola Activity
 * (TermuxActivity) con Fragments, ver CLAUDE.md § Architecture), no de View/Fragment. Por eso
 * se activa/desactiva de forma DINÁMICA en onResume()/onPause() de cada Fragment sensible en
 * vez de dejarlo fijo para toda la app: aplicarlo global rompería screenshots/grabación de
 * pantalla (usadas activamente para QA — ver .claude/skills/kairos-apk-qa/SKILL.md y
 * .claude/skills/kairos-adb-debug/SKILL.md) en TODAS las pantallas, no solo las que manejan
 * credenciales — mala UX/DX para una restricción que solo hace falta en un puñado de pantallas.
 *
 * onPause() de cada Fragment sensible SIEMPRE debe volver a llamar con secure=false — dos
 * Fragments sensibles nunca están resumed al mismo tiempo en una sola Activity (las
 * transacciones de FragmentManager son secuenciales), así que no hay riesgo de que el clear()
 * de un Fragment pise el set() de otro que siga visible.
 */
fun Fragment.setScreenSecure(secure: Boolean) {
    val window = activity?.window ?: return
    if (secure) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
