package com.termux.app.wizard

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.util.kairosThemeColor

/** Pantalla 1 del wizard — permisos de almacenamiento y notificaciones, EN ESE ORDEN
 * (pedido explícito del usuario, ver docs/humano/humano10.md). El botón "Continuar" queda
 * deshabilitado hasta que ambos quedan resueltos (concedido o explícitamente rechazado
 * para notificaciones — almacenamiento sí es obligatorio, Kairos lo necesita de verdad). */
class WizardPermissionsFragment : Fragment() {

    private var storageStatusText: TextView? = null
    private var notifStatusText: TextView? = null
    private var continueButton: Button? = null
    private var notifResolved = false
    // Se pone true la primera vez que el usuario toca "Conceder" en Notificaciones y lo
    // mandamos a Ajustes (ver requestNotifPermission) — onResume() lo usa para saber si ya
    // tuvo oportunidad de decidir y puede desbloquear "Continuar" aunque siga denegado
    // (notificaciones es opcional, a diferencia de almacenamiento).
    private var notifSettingsOpened = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(TextView(ctx).apply {
            text = "Permisos necesarios"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })

        val storageRow = buildPermissionRow(ctx, "Almacenamiento",
            "Necesario para que los módulos guarden modelos, proyectos y datos.")
        storageStatusText = storageRow.getChildAt(1) as TextView
        (storageRow.getChildAt(2) as Button).setOnClickListener { requestStoragePermission() }
        root.addView(storageRow)

        val notifRow = buildPermissionRow(ctx, "Notificaciones",
            "Avisa si un módulo se cae o se detiene solo mientras la app está en segundo plano.")
        notifStatusText = notifRow.getChildAt(1) as TextView
        (notifRow.getChildAt(2) as Button).setOnClickListener { requestNotifPermission() }
        root.addView(notifRow)

        continueButton = Button(ctx).apply {
            text = "Continuar"
            isAllCaps = false
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            setTextColor(Color.BLACK)
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                // Dispara el bootstrap crudo de Termux (bash/pkg/adb) en segundo plano ANTES
                // de entrar a la pantalla de procesos fantasma — esa pantalla corre comandos
                // pkg/adb reales y bug real reportado (ver docs/humano/humano56.md) mostró que
                // fallaban porque el bootstrap todavía no existía a esa altura del wizard.
                // Idempotente, no interfiere con el bootstrap completo de la pantalla 4 — desde
                // docs/humano/humano58.md, TermuxInstaller.setupBootstrapIfNeeded() además
                // encola cualquier llamada concurrente en vez de correr 2 extracciones en
                // paralelo (esa doble llamada, sin el guard, corrompía $PREFIX y era la causa
                // real de "Cannot run program bash" más adelante en la pantalla 4).
                WizardInstallFragment.ensureTermuxBootstrapReady(requireActivity())
                (activity as? WizardActivity)?.goToPage(2)
            }
        }
        root.addView(continueButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(32)
        })

        return scroll
    }

    override fun onResume() {
        super.onResume()
        // Refleja el estado real cada vez que la pantalla vuelve a quedar visible (ej.
        // el usuario vuelve de Ajustes del sistema tras conceder el permiso).
        updateStorageRow(Environment.isExternalStorageManager())
        if (!notifResolved) {
            val alreadyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (alreadyGranted) {
                notifResolved = true
                updateNotifRow(true)
            } else if (notifSettingsOpened) {
                // Volvió de Ajustes de notificaciones sin conceder — igual lo damos por
                // resuelto (opcional) para no bloquear "Continuar" para siempre.
                notifResolved = true
                updateNotifRow(false)
            }
        }
        maybeEnableContinue()
    }

    private fun buildPermissionRow(ctx: android.content.Context, title: String, description: String): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(ctx.kairosThemeColor(R.attr.kairosBg2))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(16)
            }
        }
        row.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        row.addView(TextView(ctx).apply {
            text = description
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(4); it.bottomMargin = dp(10)
            }
        })
        row.addView(Button(ctx).apply {
            text = "Conceder"
            isAllCaps = false
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        return row
    }

    private fun requestStoragePermission() {
        if (Environment.isExternalStorageManager()) {
            updateStorageRow(true)
            maybeEnableContinue()
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.parse("package:${requireContext().packageName}")
        startActivity(intent)
        pollStoragePermission()
    }

    private fun pollStoragePermission() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isAdded) return
                if (Environment.isExternalStorageManager()) {
                    updateStorageRow(true)
                    maybeEnableContinue()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notifResolved = true
            updateNotifRow(true)
            maybeEnableContinue()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifResolved = true
            updateNotifRow(true)
            maybeEnableContinue()
            return
        }
        // BUG REAL confirmado por ADB en dispositivo (Android 16/API 36, ver docs/humano/humanoXXX.md):
        // gradle.properties tiene targetSdkVersion=28 (< 33/Tiramisu) — con targetSdk por debajo
        // de 33, Android NUNCA muestra el diálogo runtime de POST_NOTIFICATIONS al llamar
        // requestPermissions()/ActivityResultContracts.RequestPermission(); simplemente resuelve
        // al instante con el estado de concesión actual (sin diálogo, sin interacción real del
        // usuario) — confirmado con pm reset-permissions + logcat: cero actividad de
        // PermissionController, el launcher devolvía "denegado" en el mismo frame del tap.
        // Bumpear targetSdkVersion es un cambio de arquitectura aparte (afecta scoped storage,
        // exported activities, foreground service types, etc. — no se toca acá sin permiso
        // explícito). La única vía real de conceder el permiso en este targetSdk es Ajustes,
        // igual que ya hace el permiso de almacenamiento arriba.
        notifSettingsOpened = true
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        }
        startActivity(intent)
    }

    private fun updateStorageRow(granted: Boolean) {
        if (!isAdded) return
        storageStatusText?.text = if (granted) "✓ Concedido" else "Necesario para que los módulos guarden modelos, proyectos y datos."
        storageStatusText?.setTextColor(requireContext().kairosThemeColor(if (granted) R.attr.kairosGreen else R.attr.kairosText2))
    }

    private fun updateNotifRow(granted: Boolean) {
        if (!isAdded) return
        notifStatusText?.text = if (granted) "✓ Concedido" else "✗ Rechazado — se puede activar después en Ajustes"
        notifStatusText?.setTextColor(requireContext().kairosThemeColor(if (granted) R.attr.kairosGreen else R.attr.kairosAmber))
    }

    private fun maybeEnableContinue() {
        if (!isAdded) return
        val storageOk = Environment.isExternalStorageManager()
        if (storageOk && notifResolved) {
            continueButton?.isEnabled = true
            continueButton?.alpha = 1f
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
