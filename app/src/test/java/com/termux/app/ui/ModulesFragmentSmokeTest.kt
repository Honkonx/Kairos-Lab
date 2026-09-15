package com.termux.app.ui

import com.termux.app.testutil.FragmentSmokeTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test real: ¿la pantalla Módulos (tab "modules", pantalla de arranque por defecto de
 * TermuxActivity — ver `mCurrentFragment = mModulesFragment` en `initializeAppUi()`) infla y
 * arranca sin crashear? Ver `.claude/skills/android-kairos-testing.md`.
 */
@RunWith(RobolectricTestRunner::class)
class ModulesFragmentSmokeTest {

    @Test
    fun opensWithoutCrashing() {
        val fragment = ModulesFragment()
        FragmentSmokeTest.launch(fragment)
        assertNotNull(fragment.view)
    }
}
