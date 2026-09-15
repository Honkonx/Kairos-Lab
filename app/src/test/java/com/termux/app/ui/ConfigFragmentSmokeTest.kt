package com.termux.app.ui

import com.termux.app.testutil.FragmentSmokeTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test real: ¿la pantalla Ajustes (tab "settings" de TermuxActivity) infla y arranca sin
 * crashear? Ver `.claude/skills/android-kairos-testing.md` — no valida lógica de negocio, solo
 * que `onCreateView()`/`onViewCreated()` no lancen ninguna excepción, el tipo de bug real que
 * motivó esta ronda (ver `StudioFragmentSmokeTest`).
 */
@RunWith(RobolectricTestRunner::class)
class ConfigFragmentSmokeTest {

    @Test
    fun opensWithoutCrashing() {
        val fragment = ConfigFragment()
        FragmentSmokeTest.launch(fragment)
        assertNotNull(fragment.view)
    }
}
