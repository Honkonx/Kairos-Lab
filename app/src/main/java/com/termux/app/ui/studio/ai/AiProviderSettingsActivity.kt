package com.termux.app.ui.studio.ai

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.termux.R
import com.termux.databinding.StudioActivityAiProviderSettingsBinding

/**
 * Pantalla de configuración del proveedor de IA del IDE — selector BYO (bring your own key)
 * con los mismos 5 proveedores cloud que ya tiene Kairos (ChatFragment.kt, CLOUD_PROVIDERS)
 * más la opción "local" (Ollama/llama-server en el propio dispositivo).
 *
 * Esta pantalla solo guarda la elección + la key — el request HTTP real a cada proveedor
 * (mismos endpoints/formatos que ChatFragment.kt en Kairos) vive en AiClient.kt, invocado
 * desde MainActivity.askAiAboutCode() ("Preguntar a la IA sobre este código").
 */
class AiProviderSettingsActivity : AppCompatActivity() {

    private lateinit var binding: StudioActivityAiProviderSettingsBinding
    private lateinit var prefs: AiProviderPrefs
    private val providers = AiProvider.entries.toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = StudioActivityAiProviderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        prefs = AiProviderPrefs(this)

        val labels = providers.map { getString(it.labelRes) }
        binding.providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        val selected = prefs.getSelectedProvider()
        binding.providerSpinner.setSelection(providers.indexOf(selected))
        binding.apiKeyInput.setText(prefs.getApiKey(selected))
        updateFieldsVisibility(selected)

        binding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = providers[position]
                binding.apiKeyInput.setText(prefs.getApiKey(provider))
                updateFieldsVisibility(provider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.saveButton.setOnClickListener { saveSelection() }
    }

    private fun updateFieldsVisibility(provider: AiProvider) {
        binding.apiKeyGroup.visibility = if (provider.requiresApiKey) View.VISIBLE else View.GONE
        binding.localWarningLabel.visibility = if (provider == AiProvider.LOCAL) View.VISIBLE else View.GONE
    }

    private fun saveSelection() {
        val provider = providers[binding.providerSpinner.selectedItemPosition]
        prefs.setSelectedProvider(provider)
        if (provider.requiresApiKey) {
            prefs.setApiKey(provider, binding.apiKeyInput.text.toString().trim())
        }
        Toast.makeText(this, R.string.ai_saved_confirmation, Toast.LENGTH_SHORT).show()
        finish()
    }
}
