package com.termux.app.ui

import com.termux.app.testutil.FragmentSmokeTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test real: ¿el explorador de Archivos (tab "files") infla y arranca sin crashear? Ver
 * `.claude/skills/android-kairos-testing.md`.
 */
@RunWith(RobolectricTestRunner::class)
class FileManagerFragmentSmokeTest {

    @Test
    fun opensWithoutCrashing() {
        val fragment = FileManagerFragment()
        FragmentSmokeTest.launch(fragment)
        assertNotNull(fragment.view)
    }
}
