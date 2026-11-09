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
 *
 * **Bug real encontrado y corregido 2026-11-02** (reporte del usuario
 * "sigue sin dejar tirar capture"): el patrón de arriba (solo onResume/onPause) ASUME que
 * salir de la pantalla dispara onPause() de verdad — pero TermuxActivity cambia de tab con
 * `FragmentTransaction.hide()/show()` (ver TermuxActivity.java), que NO dispara onPause()/
 * onResume() (comportamiento documentado de Android, distinto a una transacción con
 * back-stack). Visitar UNA sola vez cualquier pantalla con `setScreenSecure(true)` en
 * onResume() dejaba FLAG_SECURE prendido para TODA la app (es un flag de Window, no de
 * Fragment) hasta que la Activity entera pasara a background — bloqueando capturas/grabación
 * indefinidamente en cualquier otra pantalla, no solo en la sensible.
 *
 * **Patrón correcto, obligatorio para toda pantalla nueva que use setScreenSecure()**: además
 * de onResume()/onPause(), agregar `override fun onHiddenChanged(hidden: Boolean)` con el
 * mismo par set/clear, guardado con `isResumed` en la rama de "mostrar" (hide()/show() SÍ
 * dispara onHiddenChanged(), a diferencia de onResume/onPause):
 * ```kotlin
 * override fun onHiddenChanged(hidden: Boolean) {
 *     super.onHiddenChanged(hidden)
 *     if (hidden) setScreenSecure(false) else if (isResumed) setScreenSecure(true)
 * }
 * ```
 * Ya aplicado en las 6 pantallas reales que usaban `setScreenSecure()` a esta fecha:
 * HomelabFragment, ConfigFragment, RemoteFragment, KairosAgentFragment, ChatFragment,
 * LlamaServerConfigFragment.
 */
fun Fragment.setScreenSecure(secure: Boolean) {
    val window = activity?.window ?: return
    if (secure) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
