package com.termux.app.ui.studio

import android.net.Uri
import android.widget.LinearLayout
import com.termux.R
import com.termux.app.testutil.FragmentSmokeTest
import com.termux.app.ui.studio.project.SessionStateManager
import com.termux.app.ui.studio.project.StudioSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Repro real del crash-loop corregido en `a2635b8` ("crash real en loop al retroceder con 2+
 * sesiones abiertas — contexto de tema incorrecto"): `StudioFragment.updateSessionChipsUi()`
 * infla `studio_item_project_chip.xml` (usa `?attr/studioBg`, atributo del tema PROPIO de
 * Estudio) solo cuando hay 2+ sesiones de proyecto restauradas — con 0/1 sesión el bloque entero
 * hace early-return sin tocar ese layout (ver [StudioFragmentSmokeTest] para el caso base).
 *
 * El bug real: antes del fix, ese inflado usaba `LayoutInflater.from(requireContext())` — el
 * Context CRUDO del Fragment (tema del apk, `Theme.Kairos.*`, que nunca define `?attr/studioBg`,
 * son atributos totalmente separados) en vez de `binding.root.context` (el
 * `ContextThemeWrapper` con `Theme.Studio.*` que `onCreateView()` ya aplica para el resto de la
 * vista). Resolver un `?attr/` no definido en el tema activo revienta el inflado — confirmado en
 * dispositivo real como crash-loop (Android relanza la Activity, cae en el mismo punto).
 *
 * Se seedean 2 sesiones directamente vía la API pública real de [SessionStateManager]
 * (`saveSessions`) — el mismo mecanismo que usa `StudioFragment.onStop()`/`persistCurrentSession()`
 * en producción — para que `restoreSessionIfAny()` (llamado desde `onViewCreated()`) encuentre
 * 2+ sesiones guardadas y dispare `updateSessionChipsUi()` con la lista poblada, exactamente el
 * mismo camino que el dispositivo real recorría al reabrir Estudio con 2+ proyectos abiertos.
 *
 * Verificado manualmente (revirtiendo el fix a `LayoutInflater.from(requireContext())` en una
 * copia de trabajo, ver resumen de la ronda): este test FALLA con el bug reintroducido y PASA
 * con el fix real aplicado — no es un test que "corre sin hacer nada útil".
 */
@RunWith(RobolectricTestRunner::class)
class StudioFragmentMultiSessionSmokeTest {

    @Test
    fun opensWithoutCrashingWithTwoRestoredSessions() {
        val context = RuntimeEnvironment.getApplication()
        val sessions = listOf(
            StudioSession(
                id = "project-a",
                treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AProjectA"),
                projectPath = null,
                displayName = "Proyecto A"
            ),
            StudioSession(
                id = "project-b",
                treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AProjectB"),
                projectPath = null,
                displayName = "Proyecto B"
            )
        )
        SessionStateManager(context).saveSessions(sessions, activeIndex = 0)

        val fragment = StudioFragment()
        FragmentSmokeTest.launch(fragment)

        assertNotNull(fragment.view)
        // Confirma que updateSessionChipsUi() realmente corrió y pobló los 2 chips — no solo que
        // el Fragment no crasheó (podría no crashear por otra razón si restoreSessionIfAny()
        // hubiera fallado silenciosamente antes de llegar a poblar la lista de sesiones).
        val chipsContainer = fragment.requireView().findViewById<LinearLayout>(R.id.project_session_chips)
        assertEquals(2, chipsContainer.childCount)
    }
}
