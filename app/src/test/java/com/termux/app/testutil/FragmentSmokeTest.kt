package com.termux.app.testutil

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Helper compartido por los smoke tests de Fragments de nivel superior (ver
 * `.claude/skills/android-kairos-testing.md` — no existía ningún precedente de test de Fragment
 * en el repo antes de esta ronda, así que este es el primero).
 *
 * No hay `androidx.fragment:fragment-testing`/`FragmentScenario` declarado como dependencia de
 * test (solo `junit`+`robolectric`, ver `app/build.gradle`) — en vez de agregar una dependencia
 * nueva para esto, se arma el mismo mecanismo a mano con `ActivityController`, que Robolectric
 * ya soporta con las dependencias existentes (`androidx.appcompat`, transitiva de
 * `termux-shared`, trae `androidx.fragment`).
 *
 * El orden de ciclo de vida replica a propósito cómo `TermuxActivity.initializeAppUi()` agrega
 * sus Fragments reales: `add()` + `commitNow()` ANTES de que la Activity llegue a RESUMED (en
 * producción los 10 fragments de nivel superior se agregan dentro de `onCreate()`, no después) —
 * así el Fragment pasa por `onCreateView()`/`onViewCreated()`/`onResume()` en el mismo orden
 * relativo que en el dispositivo real, no aislado.
 */
object FragmentSmokeTest {

    /**
     * Crea un host Activity real (mismo tema que usa TermuxActivity en producción, ver
     * [KairosFragmentTestActivity]), agrega [fragment] a `android.R.id.content` (contenedor que
     * toda Activity ya tiene, sin necesitar un layout de test propio) y lleva el ciclo de vida
     * hasta RESUMED — el punto en el que un crash de inflado/`onViewCreated` ya se manifestó.
     *
     * Devuelve el [ActivityController] (no solo la Activity) para que un test puntual pueda,
     * si lo necesita, seguir empujando el ciclo de vida (`pause()`, `destroy()`, recrear la
     * vista) — ver `StudioFragmentSmokeTest` para un caso real que lo necesita.
     */
    fun launch(fragment: Fragment): ActivityController<KairosFragmentTestActivity> {
        val controller = Robolectric.buildActivity(KairosFragmentTestActivity::class.java).create()
        val activity: FragmentActivity = controller.get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        controller.start().resume()
        return controller
    }
}
