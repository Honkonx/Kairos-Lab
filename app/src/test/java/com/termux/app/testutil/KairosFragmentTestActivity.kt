package com.termux.app.testutil

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.termux.R

/**
 * Host Activity mínimo para smoke tests Robolectric de Fragments de nivel superior.
 *
 * A propósito NO es `TermuxActivity` — esa clase liga el ciclo de vida completo del motor
 * (bind a `TermuxService`, wizard de primer arranque, terminal overlay) que un smoke test de
 * "¿esta pantalla infla sin crashear?" no necesita y que sería muchísimo más costoso simular
 * fielmente en Robolectric. En cambio aplica el MISMO tema real que usa `TermuxActivity` en
 * producción (`KairosThemePrefs`, default `Theme.Kairos.Oscuro`) — sin esto, cualquier
 * `?attr/kairos*`/`?attr/studio*` sin resolver en un layout inflado durante el test pasaría
 * desapercibido (sería el mismo gap que dejó pasar el bug real de `StudioFragment`, ver
 * `StudioFragmentSmokeTest`).
 */
class KairosFragmentTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Kairos_Oscuro)
        super.onCreate(savedInstanceState)
    }
}
