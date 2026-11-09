package com.termux.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.util.GatewayAdminClient
import com.termux.app.util.GatewayLevels
import com.termux.app.util.GatewayManager
import com.termux.app.util.GatewayPolicy
import com.termux.app.util.GatewayToken
import com.termux.app.util.kairosThemeColor

/**
 * Pantalla de permisos del gateway (Homelab): nivel máximo, casilla de responsabilidad y tokens. Habla con
 * las rutas de administración del gateway, por [GatewayAdminClient]. Los tokens se muestran **una sola vez**, al crearlos (el gateway solo guarda su
 * hash, regla `kairos-secrets-never-revealed.md`); la lista posterior solo trae nombre y nivel.
 */
object GatewayPermissionsDialog {

    private val LEVELS = listOf(
        Triple(GatewayLevels.READ, R.string.gateway_level_read, R.string.gateway_level_read_desc),
        Triple(GatewayLevels.ASK, R.string.gateway_level_ask, R.string.gateway_level_ask_desc),
        Triple(GatewayLevels.SEMI, R.string.gateway_level_semi, R.string.gateway_level_semi_desc),
        Triple(GatewayLevels.FULL, R.string.gateway_level_full, R.string.gateway_level_full_desc),
    )

    fun show(activity: Activity) {
        Thread {
            val client = GatewayManager.adminClient()
            val policy = client.getPolicy()
            val tokens = if (policy != null) client.listTokens() else emptyList()
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (policy == null) {
                    Toast.makeText(activity, R.string.gateway_perm_unreachable, Toast.LENGTH_SHORT).show()
                } else {
                    build(activity, client, policy, tokens)
                }
            }
        }.start()
    }

    private fun dp(ctx: Context, d: Int) = (d * ctx.resources.displayMetrics.density).toInt()

    private fun build(activity: Activity, client: GatewayAdminClient, policy: GatewayPolicy, tokens: List<GatewayToken>) {
        val ctx = activity
        val text2 = ctx.kairosThemeColor(R.attr.kairosText2)
        val text3 = ctx.kairosThemeColor(R.attr.kairosText3)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        column.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.gateway_perm_max)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(text3)
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        })

        val group = RadioGroup(ctx)
        LEVELS.forEachIndexed { index, (level, nameRes, descRes) ->
            group.addView(RadioButton(ctx).apply {
                id = View.generateViewId()
                tag = level
                text = "${ctx.getString(nameRes)} — ${ctx.getString(descRes)}"
                textSize = 13f
                setTextColor(text2)
                isChecked = level == policy.max
            }, index)
        }
        column.addView(group)

        val responsible = CheckBox(ctx).apply {
            text = ctx.getString(R.string.gateway_perm_responsible)
            textSize = 12.5f
            setTextColor(text2)
            isChecked = policy.responsible
        }
        column.addView(responsible)

        column.addView(sectionLabel(ctx, R.string.gateway_tokens_title))
        val tokensBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(tokensBox)
        lateinit var dialog: AlertDialog

        fun renderTokens(list: List<GatewayToken>) {
            tokensBox.removeAllViews()
            if (list.isEmpty()) {
                tokensBox.addView(TextView(ctx).apply { text = ctx.getString(R.string.gateway_tokens_empty); textSize = 12f; setTextColor(text3) })
            }
            list.forEach { token ->
                tokensBox.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
                    addView(TextView(ctx).apply {
                        text = "${token.name} · ${token.level}"
                        textSize = 13f
                        setTextColor(text2)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    })
                    addView(TextView(ctx).apply {
                        text = ctx.getString(R.string.gateway_token_revoke)
                        textSize = 12f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(ctx.kairosThemeColor(R.attr.kairosRed))
                        setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                        setOnClickListener {
                            Thread {
                                client.revokeToken(token.id)
                                val fresh = client.listTokens()
                                activity.runOnUiThread { if (dialog.isShowing) renderTokens(fresh) }
                            }.start()
                        }
                    })
                })
            }
        }
        renderTokens(tokens)

        column.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.gateway_token_new)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            setOnClickListener { askNewToken(activity, client) { fresh -> if (dialog.isShowing) renderTokens(fresh) } }
        })

        dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.gateway_perm_title)
            .setView(ScrollView(ctx).apply { addView(column, MATCH_PARENT, WRAP_CONTENT) })
            .setPositiveButton(R.string.gateway_save, null)
            .setNegativeButton(R.string.gateway_close, null)
            .create()
        dialog.show()
        // Se engancha después de show() para poder validar sin que el diálogo se cierre solo.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val checked = group.findViewById<RadioButton>(group.checkedRadioButtonId)
            val level = checked?.tag as? String ?: policy.max
            if (GatewayLevels.needsResponsibility(level) && !responsible.isChecked) {
                Toast.makeText(ctx, R.string.gateway_perm_needs_responsible, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Thread {
                val ok = client.setPolicy(GatewayPolicy(level, responsible.isChecked))
                activity.runOnUiThread {
                    Toast.makeText(ctx, if (ok) R.string.gateway_perm_saved else R.string.gateway_perm_save_failed, Toast.LENGTH_SHORT).show()
                    if (ok) dialog.dismiss()
                }
            }.start()
        }
    }

    private fun sectionLabel(ctx: Context, res: Int) = TextView(ctx).apply {
        text = ctx.getString(res)
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
    }

    private fun askNewToken(activity: Activity, client: GatewayAdminClient, onChanged: (List<GatewayToken>) -> Unit) {
        val input = EditText(activity).apply { hint = activity.getString(R.string.gateway_token_name_hint); setSingleLine() }
        val names = LEVELS.map { activity.getString(it.second) }.toTypedArray()
        var chosen = 0
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
            addView(input, MATCH_PARENT, WRAP_CONTENT)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.gateway_token_new)
            .setView(box)
            .setSingleChoiceItems(names, 0) { _, which -> chosen = which }
            .setPositiveButton(R.string.gateway_token_new) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "token" }
                Thread {
                    val created = client.createToken(name, LEVELS[chosen].first)
                    val fresh = client.listTokens()
                    activity.runOnUiThread {
                        onChanged(fresh)
                        created?.let { showTokenOnce(activity, it.token) }
                    }
                }.start()
            }
            .setNegativeButton(R.string.gateway_close, null)
            .show()
    }

    /** El único momento en que el valor del token es visible. */
    private fun showTokenOnce(activity: Activity, token: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.gateway_token_created_title)
            .setMessage(activity.getString(R.string.gateway_token_created_msg) + "\n\n" + token)
            .setPositiveButton(R.string.gateway_token_copy) { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("kairos-gateway-token", token))
                Toast.makeText(activity, R.string.gateway_token_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.gateway_close, null)
            .show()
    }
}
