package com.termux.app.ui.studio.ai

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.termux.R
import com.termux.app.util.AiCompany
import com.termux.databinding.StudioActivityAiProviderSettingsBinding

/**
 * Pantalla de configuración del proveedor de IA del IDE — selector BYO (bring your own key),
 * proveedores unificados con el Agente Kairos vía `com.termux.app.util.AiCompany` (2026-10-31,
 * ver KDoc de esa clase) más la opción "local" (Ollama/llama-server en el propio dispositivo).
 *
 * Esta pantalla solo guarda la elección + la key — el request HTTP real a cada proveedor
 * (mismos endpoints/formatos que ChatFragment.kt en Kairos) vive en AiClient.kt, invocado
 * desde MainActivity.askAiAboutCode() ("Preguntar a la IA sobre este código").
 */
class AiProviderSettingsActivity : AppCompatActivity() {

    private lateinit var binding: StudioActivityAiProviderSettingsBinding
    private lateinit var prefs: AiProviderPrefs
    private val providers = AiCompany.entries.toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = StudioActivityAiProviderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        prefs = AiProviderPrefs(this)

        val labels = providers.map { it.label }
        binding.providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        val selected = prefs.getSelectedProvider()
        binding.providerSpinner.setSelection(providers.indexOf(selected))
        // Hallazgo real de esta ronda (2026-10-31): este campo ANTES precargaba la clave ya
        // guardada (prefs.getApiKey(selected)) — violaba
        // .claude/rules/kairos-secrets-never-revealed.md ("un secreto guardado nunca se vuelve a
        // mostrar"). El campo arranca SIEMPRE vacío ahora; el hint avisa si ya hay una guardada.
        updateApiKeyHint(selected)
        updateFieldsVisibility(selected)
        // Personalizado: la URL/modelo NO son secretos (no van en EncryptedSharedPreferences
        // por la misma razón que la key), así que sí se precargan — a diferencia de la API key,
        // no hay ninguna regla que prohíba mostrarlos de nuevo.
        binding.customBaseUrlInput.setText(prefs.getCustomBaseUrl())
        binding.customModelInput.setText(prefs.getCustomModel())

        binding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = providers[position]
                binding.apiKeyInput.setText("")
                updateApiKeyHint(provider)
                updateFieldsVisibility(provider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.saveButton.setOnClickListener { saveSelection() }
    }

    private fun updateApiKeyHint(provider: AiCompany) {
        val hasSaved = prefs.getApiKey(provider).isNotBlank()
        binding.apiKeyInput.hint = getString(
            if (hasSaved) R.string.ai_api_key_hint_saved else R.string.ai_api_key_hint
        )
    }

    private fun updateFieldsVisibility(provider: AiCompany) {
        // "Personalizado" también es BYO key (opcional, requiresApiKey=false) — el grupo de
        // clave se muestra igual para esa opción, solo que no es obligatoria.
        binding.apiKeyGroup.visibility =
            if (provider.requiresApiKey || provider == AiCompany.CUSTOM) View.VISIBLE else View.GONE
        binding.localWarningLabel.visibility = if (provider == AiCompany.LOCAL) View.VISIBLE else View.GONE
        binding.customEndpointGroup.visibility = if (provider == AiCompany.CUSTOM) View.VISIBLE else View.GONE
    }

    private fun saveSelection() {
        val provider = providers[binding.providerSpinner.selectedItemPosition]
        if (provider == AiCompany.CUSTOM) {
            val baseUrl = binding.customBaseUrlInput.text.toString().trim()
            val model = binding.customModelInput.text.toString().trim()
            if (baseUrl.isBlank() || model.isBlank()) {
                Toast.makeText(this, R.string.ai_custom_fields_required, Toast.LENGTH_SHORT).show()
                return
            }
            prefs.setCustomBaseUrl(baseUrl)
            prefs.setCustomModel(model)
        }
        prefs.setSelectedProvider(provider)
        if (provider.requiresApiKey || provider == AiCompany.CUSTOM) {
            // Campo vacío = conservar la clave que ya estaba guardada (mismo criterio que
            // KairosAgentFragment.resolveTelegramToken() — nunca se borra sin querer por dejar
            // el campo en blanco al solo cambiar de proveedor y volver).
            val typed = binding.apiKeyInput.text.toString().trim()
            if (typed.isNotBlank()) prefs.setApiKey(provider, typed)
        }
        Toast.makeText(this, R.string.ai_saved_confirmation, Toast.LENGTH_SHORT).show()
        finish()
    }
}
