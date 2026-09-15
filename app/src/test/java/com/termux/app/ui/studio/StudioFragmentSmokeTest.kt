package com.termux.app.ui.studio

import com.termux.app.testutil.FragmentSmokeTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test real: ¿la pantalla Estudio (tab "studio", el IDE integrado) infla y arranca sin
 * crashear, en el caso base de una sesión de proyecto vacía (instalación nueva, sin sesiones
 * restauradas)? Ver `.claude/skills/android-kairos-testing.md`.
 *
 * Este caso base NO reproduce el bug real corregido en `a2635b8` (ver
 * [StudioFragmentMultiSessionSmokeTest] para ese repro específico) — `updateSessionChipsUi()`
 * hace early-return con 0/1 sesiones (`sessions.size <= 1`), así que el chip de
 * `studio_item_project_chip.xml` (el que gatillaba el crash) nunca se infla en este caso. Se deja
 * como test separado igual porque cubre el resto de `onCreateView()`/`onViewCreated()` (el
 * `ContextThemeWrapper` de Estudio, `setUpFileTree()`, `setUpTabsController()`,
 * `setUpSearchController()`, etc.) — la superficie más grande de "¿esta pantalla infla sin
 * crashear?" que motivó esta ronda.
 */
@RunWith(RobolectricTestRunner::class)
class StudioFragmentSmokeTest {

    @Test
    fun opensWithoutCrashing() {
        val fragment = StudioFragment()
        FragmentSmokeTest.launch(fragment)
        assertNotNull(fragment.view)
    }
}
