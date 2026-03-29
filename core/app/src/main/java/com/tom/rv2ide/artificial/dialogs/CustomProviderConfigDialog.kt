package com.tom.rv2ide.artificial.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.custom.CustomProviderApiType
import com.tom.rv2ide.artificial.agents.custom.CustomProviderConfig
import com.tom.rv2ide.artificial.agents.custom.CustomProviderProfile
import com.tom.rv2ide.artificial.agents.custom.CustomProviderModelService
import kotlinx.coroutines.launch

class CustomProviderConfigDialog(
    private val profileId: String? = null,
    private val createNew: Boolean = false,
    private val onSave: (CustomProviderProfile) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var profileNameLayout: TextInputLayout
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var apiKeyLayout: TextInputLayout
    private lateinit var apiTypeLayout: TextInputLayout
    private lateinit var modelLayout: TextInputLayout

    private lateinit var profileNameInput: TextInputEditText
    private lateinit var baseUrlInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var apiTypeInput: MaterialAutoCompleteTextView
    private lateinit var modelInput: MaterialAutoCompleteTextView

    private lateinit var statusText: MaterialTextView
    private lateinit var fetchModelsButton: Button
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    private val loadedModels = mutableListOf<String>()
    private var loadedProfile: CustomProviderProfile? = null
    private var autoFetchAttempted = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_custom_provider_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupApiTypeDropdown()
        loadSavedConfig()
        setupActions()
    }

    private fun bindViews(view: View) {
        profileNameLayout = view.findViewById(R.id.profileNameLayout)
        baseUrlLayout = view.findViewById(R.id.baseUrlLayout)
        apiKeyLayout = view.findViewById(R.id.apiKeyLayout)
        apiTypeLayout = view.findViewById(R.id.apiTypeLayout)
        modelLayout = view.findViewById(R.id.modelLayout)

        profileNameInput = view.findViewById(R.id.profileNameInput)
        baseUrlInput = view.findViewById(R.id.baseUrlInput)
        apiKeyInput = view.findViewById(R.id.apiKeyInput)
        apiTypeInput = view.findViewById(R.id.apiTypeInput)
        modelInput = view.findViewById(R.id.modelInput)

        statusText = view.findViewById(R.id.statusText)
        fetchModelsButton = view.findViewById(R.id.fetchModelsButton)
        saveButton = view.findViewById(R.id.saveButton)
        cancelButton = view.findViewById(R.id.cancelButton)
    }

    private fun setupApiTypeDropdown() {
        val apiTypes = CustomProviderApiType.entries.map(::apiTypeLabel)
        apiTypeInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, apiTypes)
        )
        apiTypeInput.setOnClickListener { apiTypeInput.showDropDown() }
    }

    private fun loadSavedConfig() {
        loadedProfile = if (createNew) {
            null
        } else {
            CustomProviderConfig.getProfile(profileId) ?: CustomProviderConfig.getActiveProfile()
        }
        loadedModels.clear()
        loadedModels.addAll(loadedProfile?.cachedModels.orEmpty())

        profileNameInput.setText(loadedProfile?.name.orEmpty())
        baseUrlInput.setText(loadedProfile?.baseUrl.orEmpty())
        apiKeyInput.setText(loadedProfile?.apiKey.orEmpty())
        apiTypeInput.setText(
            apiTypeLabel(loadedProfile?.apiType ?: CustomProviderApiType.OPENAI_CHAT),
            false
        )
        modelInput.setText(loadedProfile?.modelId.orEmpty(), false)

        val suggestions = if (loadedModels.isNotEmpty()) {
            buildSuggestedModels(loadedProfile?.modelId.orEmpty())
        } else {
            CustomProviderConfig.getAvailableModels()
        }
        updateModelSuggestions(suggestions)
        statusText.text = if (createNew) {
            getString(R.string.ai_assistant_custom_provider_create_status)
        } else {
            getString(
                R.string.ai_assistant_custom_provider_edit_status,
                loadedProfile?.name ?: getString(R.string.ai_assistant_provider_custom)
            )
        }
        autoFetchAttempted = false
        maybeAutoFetchModels()
    }

    private fun setupActions() {
        fetchModelsButton.setOnClickListener {
            clearErrors()
            val baseUrl = baseUrlInput.text?.toString().orEmpty().trim()
            val apiKey = apiKeyInput.text?.toString().orEmpty().trim()

            var hasError = false
            if (baseUrl.isBlank()) {
                baseUrlLayout.error = getString(R.string.ai_assistant_base_url_required)
                hasError = true
            }
            if (apiKey.isBlank()) {
                apiKeyLayout.error = getString(R.string.ai_assistant_api_key_required)
                hasError = true
            }
            if (hasError) {
                return@setOnClickListener
            }

            fetchModels(baseUrl, apiKey)
        }

        saveButton.setOnClickListener {
            saveConfig()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        modelInput.setOnClickListener {
            if (loadedModels.isNotEmpty()) {
                modelInput.showDropDown()
                return@setOnClickListener
            }
            if (!maybeAutoFetchModels(force = true)) {
                modelInput.showDropDown()
            }
        }
    }

    private fun maybeAutoFetchModels(force: Boolean = false): Boolean {
        if (!force && autoFetchAttempted) {
            return false
        }

        val baseUrl = baseUrlInput.text?.toString().orEmpty().trim()
        val apiKey = apiKeyInput.text?.toString().orEmpty().trim()
        if (baseUrl.isBlank() || apiKey.isBlank() || loadedModels.isNotEmpty()) {
            return false
        }

        autoFetchAttempted = true
        fetchModels(baseUrl, apiKey)
        return true
    }

    private fun fetchModels(baseUrl: String, apiKey: String) {
        setLoading(true)
        statusText.text =
            getString(
                R.string.ai_assistant_fetching_models_from,
                CustomProviderConfig.modelsEndpoint(baseUrl)
            )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val models = CustomProviderModelService.fetchAvailableModels(baseUrl, apiKey)
                loadedModels.clear()
                loadedModels.addAll(models)
                updateModelSuggestions(models)

                if (modelInput.text.isNullOrBlank() && models.isNotEmpty()) {
                    modelInput.setText(models.first(), false)
                }

                statusText.text = getString(R.string.ai_assistant_fetched_models_count, models.size)
            } catch (error: Exception) {
                statusText.text = error.message ?: getString(R.string.ai_assistant_fetch_models_failed)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun saveConfig() {
        clearErrors()

        val profileName = profileNameInput.text?.toString().orEmpty().trim()
        val baseUrl = baseUrlInput.text?.toString().orEmpty().trim()
        val apiKey = apiKeyInput.text?.toString().orEmpty().trim()
        val modelId = modelInput.text?.toString().orEmpty().trim()
        val apiType = apiTypeFromLabel(apiTypeInput.text?.toString())

        var hasError = false
        if (profileName.isBlank()) {
            profileNameLayout.error = getString(R.string.ai_assistant_profile_name_required)
            hasError = true
        }
        if (baseUrl.isBlank()) {
            baseUrlLayout.error = getString(R.string.ai_assistant_base_url_required)
            hasError = true
        }
        if (apiKey.isBlank()) {
            apiKeyLayout.error = getString(R.string.ai_assistant_api_key_required)
            hasError = true
        }
        if (modelId.isBlank()) {
            modelLayout.error = getString(R.string.ai_assistant_model_id_required)
            hasError = true
        }
        if (apiTypeInput.text.isNullOrBlank()) {
            apiTypeLayout.error = getString(R.string.ai_assistant_api_type_required)
            hasError = true
        }

        if (hasError) {
            return
        }

        val modelsToPersist = if (loadedModels.isNotEmpty()) {
            buildSuggestedModels(modelId)
        } else {
            null
        }

        val savedProfile = CustomProviderConfig.saveProfile(
            profileId = loadedProfile?.id ?: profileId,
            name = profileName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId,
            apiType = apiType,
            cachedModels = modelsToPersist,
            makeActive = true
        )

        onSave(savedProfile)
        dismiss()
    }

    private fun updateModelSuggestions(models: List<String>) {
        modelInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, models)
        )
    }

    private fun apiTypeLabel(apiType: CustomProviderApiType): String {
        return when (apiType) {
            CustomProviderApiType.OPENAI_CHAT ->
                getString(R.string.ai_assistant_custom_provider_api_type_openai_chat)
            CustomProviderApiType.OPENAI_RESPONSES ->
                getString(R.string.ai_assistant_custom_provider_api_type_openai_responses)
            CustomProviderApiType.CLAUDE_MESSAGES ->
                getString(R.string.ai_assistant_custom_provider_api_type_claude_messages)
        }
    }

    private fun apiTypeFromLabel(label: String?): CustomProviderApiType {
        return when (label) {
            getString(R.string.ai_assistant_custom_provider_api_type_openai_chat) ->
                CustomProviderApiType.OPENAI_CHAT
            getString(R.string.ai_assistant_custom_provider_api_type_openai_responses) ->
                CustomProviderApiType.OPENAI_RESPONSES
            getString(R.string.ai_assistant_custom_provider_api_type_claude_messages) ->
                CustomProviderApiType.CLAUDE_MESSAGES
            else -> CustomProviderApiType.fromDisplayName(label)
        }
    }

    private fun buildSuggestedModels(selectedModel: String): List<String> {
        val ordered = LinkedHashSet<String>()
        selectedModel.trim().takeIf { it.isNotBlank() }?.let(ordered::add)
        loadedModels.map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(ordered::add)
        return ordered.toList()
    }

    private fun clearErrors() {
        profileNameLayout.error = null
        baseUrlLayout.error = null
        apiKeyLayout.error = null
        apiTypeLayout.error = null
        modelLayout.error = null
    }

    private fun setLoading(loading: Boolean) {
        fetchModelsButton.isEnabled = !loading
        saveButton.isEnabled = !loading
    }
}
