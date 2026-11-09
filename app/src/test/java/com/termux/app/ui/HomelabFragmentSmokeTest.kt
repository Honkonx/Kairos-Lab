package com.termux.app.ui

import com.termux.app.testutil.FragmentSmokeTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test real: ¿la pantalla Homelab (pestañas Principal/Gateway/Servicios/Automatizaciones)
 * infla y arranca sin crashear? Reemplaza a `MonitorFragmentSmokeTest` (2026-10-30,
 *) — MonitorFragment.kt se eliminó, su contenido se adaptó dentro de Homelab.
 * Ver `.claude/skills/android-kairos-testing.md`.
 */
@RunWith(RobolectricTestRunner::class)
class HomelabFragmentSmokeTest {

    @Test
    fun opensWithoutCrashing() {
        val fragment = HomelabFragment()
        FragmentSmokeTest.launch(fragment)
        assertNotNull(fragment.view)
    }
}
