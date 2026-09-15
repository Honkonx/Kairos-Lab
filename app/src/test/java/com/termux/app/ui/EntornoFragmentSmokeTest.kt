package com.termux.app.ui

import com.termux.app.testutil.FragmentSmokeTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test real: ¿la pantalla Mini PC (tab "entorno"/"minipc", ver `nav_minipc` en
 * `TermuxActivity`) infla y arranca sin crashear? Ver `.claude/skills/android-kairos-testing.md`.
 *
 * NOTA (ver resumen de esta ronda): al momento de escribir este test, `EntornoFragment.kt` tiene
 * 3 archivos hermanos (`EntornoNativoTab.kt`/`EntornoVncTab.kt`/`EntornoX11Tab.kt`) sin commitear
 * que rompen la compilación del módulo `:app` completo (acceden a miembros `private` de
 * `EntornoFragment` desde otra clase — error de visibilidad de Kotlin, no relacionado a esta
 * ronda de testing). No se tocó ese código de producción; ver el resumen final para el detalle
 * completo — este test queda escrito y listo, pero no se pudo confirmar en verde hasta que esa
 * ronda en curso (probablemente otro agente) termine/corrija esos 3 archivos.
 */
@RunWith(RobolectricTestRunner::class)
class EntornoFragmentSmokeTest {

    @Test
    fun opensWithoutCrashing() {
        val fragment = EntornoFragment()
        FragmentSmokeTest.launch(fragment)
        assertNotNull(fragment.view)
    }
}
