package com.termux.app.x11

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.termux.app.TermuxActivity
import com.termux.x11.LoriePreferences

/**
 * Subclase de com.termux.x11.LoriePreferences (módulo :x11-server — protegido, no se modifica)
 * usada SOLO para la pantalla standalone "Configuración de X11" (EntornoFragment.openX11Settings()
 * — fusión 2026-08-25, antes X11Fragment → btn_x11_settings), NO para el visor (ver
 * KairosX11MainActivity, que subclasea MainActivity en cambio).
 *
 * Por qué existe (bug real reportado 2026-08-18: "la mayoría de las opciones... no funcionan").
 * `LoriePreferences.termuxActivityListener` (protected, ver LoriePreferences.java) es un gancho
 * heredado del fork Linbox, pensado para que una TermuxActivity HOST lo asigne cuando embebe
 * `LoriePreferenceFragment` como panel propio. Kairos nunca tuvo ese host — abre LoriePreferences
 * como Activity standalone (comentario explícito en LoriePreferences.onCreate) — así que el
 * listener queda `null` para siempre y estos botones de la pantalla de preferencias quedan
 * como no-op silencioso:
 *   - "stop_desktop" → preferenceActivity.stopDesktop() → termuxActivityListener.stopDesktop(...)
 *   - "open_keyboard" → preferenceActivity.openSoftKeyBoar() → termuxActivityListener.openSoftwareKeyboard()
 *   - "open_progress_manager" → preferenceActivity.callProgressManager() → termuxActivityListener.showProcessManager()
 *   - "install_x11_server_bridge" (confirm) → preferenceActivity.installX11ServerBridge() → termuxActivityListener.reInstallX11StartScript(...)
 *
 * Las opciones de resolución/escala/touch/PIP/fullscreen/orientación/etc. NO pasan por este
 * listener — se aplican directo vía SharedPreferences + onPreferencesChanged()/broadcast
 * ACTION_PREFERENCES_CHANGED (confirmado leyendo MainActivity.java) y ya funcionan sin esta
 * subclase, siempre que el visor (MainActivity) esté vivo para recibir el broadcast.
 *
 * Esta subclase asigna termuxActivityListener con implementaciones reales donde tiene sentido
 * en el contexto de Kairos, y no-ops honestos donde no aplica (ej. reInstallX11StartScript —
 * Kairos no usa el modo "bridge por shell script" de termux-x11 original, el servidor corre
 * siempre embebido dentro del proceso :xserver, ver CmdEntryPoint). NO se toca el back-key
 * listener de MainActivity (ver KairosX11MainActivity.kt) — esta clase es una Activity distinta,
 * sin ese código.
 */
class KairosX11PreferencesActivity : LoriePreferences() {

    // Fix real (humano181, bug 7): "Open Controller" en la pantalla standalone
    // "Configuración de X11" crasheaba con NullPointerException — LoriePreferences.
    // showInputControlsDialog() (x11-server/.../LoriePreferences.java:822-885) lee
    // xServer.cursorLocker, inputControlsView e inputControlsManager sin chequeo de null;
    // esos 3 campos `protected` solo se inicializan en MainActivity.onCreate() (el visor),
    // nunca en esta Activity standalone (mismo patrón documentado arriba para
    // termuxActivityListener). El fix de humano174 (3 Activities sin declarar en el
    // manifest) no cubre este crash — es una causa distinta (NPE, no
    // ActivityNotFoundException) en el mismo diálogo. Se sobreescribe el método público
    // (no final) para no tocar el módulo x11-server protegido: si el visor no está vivo
    // (xServer null), se avisa en vez de dejar caer la excepción.
    override fun showInputControlsDialog() {
        if (xServer == null || inputControlsManager == null || inputControlsView == null || touchpadView == null) {
            Toast.makeText(
                this,
                "Abrí primero el escritorio X11 (visor) para configurar los controles de entrada.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        super.showInputControlsDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        termuxActivityListener = object : TermuxActivityListener {
            override fun onX11PreferenceSwitchChange(isOpen: Boolean) {
                // No hay slider/panel host en el standalone de Kairos — nada que hacer.
            }

            override fun releaseSlider(open: Boolean) {
                // Ídem — sin panel host, no-op.
            }

            override fun onChangeOrientation(landscape: Int) {
                // La orientación real la maneja MainActivity (el visor), no esta pantalla.
            }

            override fun reInstallX11StartScript(activity: android.app.Activity) {
                // "install_x11_server_bridge" del termux-x11 original reinstala un script de
                // arranque por shell. Kairos no usa ese modo — el servidor X corre siempre
                // embebido en el proceso :xserver (ver X11Service.kt/CmdEntryPoint) — no hay
                // script que reinstalar. Se avisa en vez de fingir que hizo algo.
                Toast.makeText(
                    activity,
                    "No aplica: en Kairos el servidor X11 corre embebido, sin script de arranque que reinstalar.",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun stopDesktop(activity: android.app.Activity) {
                // Mismo contrato que X11Fragment.stopX11()/MainActivity.stopX11Server():
                // broadcast ACTION_STOP (el visor lo escucha si está abierto) + parar el
                // servicio :xserver por nombre de componente explícito (X11Service vive en
                // :app, este módulo no puede depender de él — ver mismo patrón documentado
                // en MainActivity.stopX11Server()).
                activity.sendBroadcast(
                    Intent("com.termux.x11.ACTION_STOP").setPackage(activity.packageName)
                )
                activity.stopService(
                    Intent().setClassName(activity.packageName, "com.termux.app.X11Service")
                )
                Toast.makeText(activity, "Servidor X11 detenido", Toast.LENGTH_SHORT).show()
            }

            override fun openSoftwareKeyboard() {
                // Sin lorieView acá (esta pantalla no es el visor) — se muestra el IME sobre
                // la vista con foco actual de la propia pantalla de preferencias, que es lo
                // más razonable que se puede hacer sin una sesión X11 activa.
                val activity = this@KairosX11PreferencesActivity
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                val view = activity.currentFocus ?: activity.window?.decorView
                if (view != null) imm?.showSoftInput(view, 0)
            }

            override fun showProcessManager() {
                // Kairos no tiene un "process manager" propio del módulo X11 (ProcessInfo/
                // WinHandler es para juegos Windows vía Wine, no aplica al escritorio Linux
                // embebido) — se trae al frente la pantalla Monitor de Kairos, que sí lista
                // procesos reales del sistema.
                this@KairosX11PreferencesActivity.startActivity(
                    Intent(this@KairosX11PreferencesActivity, TermuxActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }

            override fun changePreference(key: String?) {
                // Solo informativo en termux-x11 original (log/journal) — no-op honesto.
            }

            override fun collectProcessorInfo(tag: String?): List<com.termux.x11.controller.winhandler.ProcessInfo> {
                // Ver comentario de showProcessManager(): no aplica al escritorio Linux embebido.
                return emptyList()
            }

            override fun setFloatBallMenu(enableFloatBallMenu: Boolean, enableGlobalFloatBallMenu: Boolean) {
                // El float ball menu es una preferencia del propio visor (MainActivity) — esta
                // pantalla standalone solo la persiste en SharedPreferences (ya lo hace el
                // framework de Preference); no hay UI de visor acá para actualizar en vivo.
            }
        }
    }
}
