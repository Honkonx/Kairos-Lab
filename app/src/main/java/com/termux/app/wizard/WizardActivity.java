package com.termux.app.wizard;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.viewpager2.widget.ViewPager2;

import com.termux.app.KairosBootstrap;
import com.termux.app.TermuxActivity;

/**
 * Host del wizard de primer arranque — 6 pantallas en un ViewPager2 (bienvenida ->
 * permisos -> procesos fantasma -> batería -> instalación -> comprobar paquetes), cada una
 * su propio Fragment (Wizard*Fragment.kt). Patrón tomado de ver/MiceWine-Application-master/
 * (WelcomeActivity + ViewPager2 + FragmentStateAdapter), pedido explícito del usuario
 * — ver docs/humano/humano12.md. Rediseño 2026-08-04 (ver docs/humano53.md/humano54.md):
 * procesos fantasma y batería pasaron de ser popups dentro del paso de instalación a
 * pantallas propias, no bloqueantes. Esta clase queda deliberadamente delgada: la lógica
 * real de cada pantalla vive en su propio Fragment, esta Activity solo aloja el pager y
 * expone navegación entre páginas.
 *
 * Sin swipe (setUserInputEnabled(false), igual que MiceWine) — el usuario avanza con
 * los botones de cada pantalla, nunca deslizando, para no saltarse pasos obligatorios
 * (permisos) ni interrumpir una instalación en curso.
 */
public class WizardActivity extends AppCompatActivity {

    private ViewPager2 viewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Bug real confirmado en dispositivo (auditoría ADB 2026-08-22, ver docs/humano/humano199.md):
        // el manifest declara esta Activity con el tema base "Theme.TermuxApp.DayNight.
        // NoActionBar" (sin los atributos ?attr/kairos* del selector de temas introducido en
        // humano190), y sin este applyTheme() ANTES de super.onCreate() ese tema nunca se
        // reemplaza por uno de los 3 reales (Theme.Kairos.Oscuro/Senal/Claro) — cualquier color
        // resuelto vía ?attr/kairos* (XML) o kairosThemeColor() (Kotlin, ver
        // WizardWelcomeFragment/WizardPermissionsFragment/etc.) caía en el valor por defecto
        // (0/negro), dejando el wizard entero con texto casi invisible sobre fondo negro y la
        // barra de estado con el rojo del tema base sin tematizar. Mismo patrón ya usado en
        // TermuxActivity.setActivityTheme() — debe llamarse antes de super.onCreate().
        com.termux.app.util.KairosThemePrefs.INSTANCE.applyTheme(this);

        super.onCreate(savedInstanceState);

        if (isKairosReady()) {
            launchMainActivity();
            return;
        }

        viewPager = new ViewPager2(this);
        viewPager.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        viewPager.setUserInputEnabled(false);
        viewPager.setAdapter(new WizardPagerAdapter(this));
        setContentView(viewPager);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                int current = viewPager.getCurrentItem();
                // Página 4 (instalación) no se puede abandonar con "atrás" — hay un
                // proceso en curso (bootstrap/rootfs/kairos.sh). El resto sí retrocede.
                if (current > 0 && current != 4) {
                    viewPager.setCurrentItem(current - 1, true);
                }
            }
        });
    }

    /** Llamado por cada Fragment cuando termina su paso y hay que avanzar. */
    public void goToPage(int index) {
        if (viewPager != null) viewPager.setCurrentItem(index, true);
    }

    /** Llamado por WizardCheckFragment (botón "Omitir" o al terminar la comprobación). */
    public void finishWizard() {
        launchMainActivity();
    }

    public static boolean isKairosReady() {
        return KairosBootstrap.isReady();
    }

    private void launchMainActivity() {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
