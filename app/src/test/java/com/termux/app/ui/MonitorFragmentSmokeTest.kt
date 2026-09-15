package com.termux.app.ui

import com.termux.app.testutil.FragmentSmokeTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test real: ¿la pantalla Monitor (tab "monitor", fusión de Sistema+Procesos, ver KDoc de
 * `MonitorFragment`) infla y arranca sin crashear? Ver `.claude/skills/android-kairos-testing.md`.
 */
@RunWith(RobolectricTestRunner::class)
class MonitorFragmentSmokeTest {

    @Test
    fun opensWithoutCrashing() {
        val fragment = MonitorFragment()
        FragmentSmokeTest.launch(fragment)
        assertNotNull(fragment.view)
    }
}
