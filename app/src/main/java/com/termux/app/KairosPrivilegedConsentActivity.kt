package com.termux.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.util.KairosPrivilegedConsentStore

/**
 * Diálogo de consentimiento del Alcance B — una app cliente que quiere el Binder privilegiado
 * (`IKairosPrivilegedService`) la abre con `startActivityForResult()` (el resultado en sí se
 * ignora; lo que importa es que SOLO ese método de arranque hace que Android rellene
 * [getCallingPackage] con el paquete real de quien la lanzó). Fail-closed: no autoriza nada por
 * su cuenta, solo registra lo que la persona decide tocando Permitir/Denegar (ver KDoc de
 * [KairosPrivilegedConsentStore]).
 *
 * Hallazgo real de revisión automática (2026-10-31, "consent spoofing / confused deputy"): la
 * primera versión leía QUÉ PAQUETE AUTORIZAR de un extra del propio Intent (`client_package`) —
 * cualquier app podía abrir esto pidiendo autorizar a OTRA app distinta de quien realmente la
 * lanzó, sin que el usuario tuviera forma de notarlo. Ahora la identidad sale de
 * [getCallingPackage] (puesto por el sistema, no falsificable desde `putExtra`) — si no viene
 * seteado (porque alguien la abrió con `startActivity()` plano en vez de
 * `startActivityForResult()`), se rechaza directo, nunca se adivina ni se confía en un extra.
 *
 * Primer incremento sin cliente real todavía (ver KDoc de `KairosPrivilegedServerMain`) — esta
 * Activity es el lado real de Kairos de ese handshake, lista para cuando exista una app que la
 * invoque.
 */
class KairosPrivilegedConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clientPackage = callingPackage?.trim()
        if (clientPackage.isNullOrBlank()) {
            finish()
            return
        }
        val appLabel = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(clientPackage, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            clientPackage
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.privileged_consent_title))
            .setMessage(getString(R.string.privileged_consent_message, appLabel, clientPackage))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.privileged_consent_deny)) { _, _ ->
                KairosPrivilegedConsentStore.revoke(clientPackage)
                finish()
            }
            .setNeutralButton(getString(R.string.privileged_consent_allow_once)) { _, _ ->
                KairosPrivilegedConsentStore.authorizeOnce(this, clientPackage)
                finish()
            }
            .setPositiveButton(getString(R.string.privileged_consent_allow)) { _, _ ->
                KairosPrivilegedConsentStore.authorize(this, clientPackage)
                finish()
            }
            .show()
    }

    // Anti-tapjacking (mismo criterio que el resto de diálogos de consentimiento de permisos,
    // ver .claude/rules/kairos-secrets-never-revealed.md) — esta decisión otorga privilegio
    // elevado, no debería poder tocarse "a ciegas" por una superposición de otra app. Activity
    // standalone (no un Fragment de TermuxActivity) — FLAG_SECURE se aplica directo sobre su
    // propia Window, no vía la extensión Fragment.setScreenSecure().
    override fun onResume() {
        super.onResume()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
