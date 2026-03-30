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
import com.tom.rv2ide.artificial.agents.custom.CustomProviderModelService
import com.tom.rv2ide.artificial.agents.external.CodexCliAuthMode
import com.tom.rv2ide.artificial.agents.external.CodexCliConfig
import com.tom.rv2ide.artificial.agents.external.CodexCliProfile
import com.tom.rv2ide.artificial.agents.external.CodexCliReasoningEffort
import com.tom.rv2ide.artificial.agents.external.CodexCliSettings
import kotlinx.coroutines.launch

class CodexCliConfigDialog(
    private val profileId: String? = null,
    private val createNew: Boolean = false,
    private val makeActiveOnSave: Boolean = true,
    private val onSave: (CodexCliSettings) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var profileNameLayout: TextInputLayout
    private lateinit var providerIdLayout: TextInputLayout
    private lateinit var providerNameLayout: TextInputLayout
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var apiKeyLayout: TextInputLayout
    private lateinit var authModeLayout: TextInputLayout
    private lateinit var modelLayout: TextInputLayout
    private lateinit var reviewModelLayout: TextInputLayout
    private lateinit var reasoningLayout: TextInputLayout
    private lateinit var contextWindowLayout: TextInputLayout
    private lateinit var autoCompactLayout: TextInputLayout

    private lateinit var profileNameInput: TextInputEditText
    private lateinit var providerIdInput: TextInputEditText
    private lateinit var providerNameInput: TextInputEditText
    private lateinit var baseUrlInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var authModeInput: MaterialAutoCompleteTextView
    private lateinit var modelInput: MaterialAutoCompleteTextView
    private lateinit var reviewModelInput: TextInputEditText
    private lateinit var reasoningInput: MaterialAutoCompleteTextView
    private lateinit var contextWindowInput: TextInputEditText
    private lateinit var autoCompactInput: TextInputEditText

    private lateinit var statusText: MaterialTextView
    private lateinit var fetchModelsButton: Button
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    private val loadedModels = mutableListOf<String>()
    private var loadedProfile: CodexCliProfile? = null
    private var autoFetchAttempted = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_codex_cli_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupDropdowns()
        loadSavedConfig()
        setupActions()
    }

    private fun bindViews(view: View) {
        profileNameLayout = view.findViewById(R.id.codexProfileNameLayout)
        providerIdLayout = view.findViewById(R.id.codexProviderIdLayout)
        providerNameLayout = view.findViewById(R.id.codexProviderNameLayout)
        baseUrlLayout = view.findViewById(R.id.codexBaseUrlLayout)
        apiKeyLayout = view.findViewById(R.id.codexApiKeyLayout)
        authModeLayout = view.findViewById(R.id.codexAuthModeLayout)
        modelLayout = view.findViewById(R.id.codexModelLayout)
        reviewModelLayout = view.findViewById(R.id.codexReviewModelLayout)
        reasoningLayout = view.findViewById(R.id.codexReasoningLayout)
        contextWindowLayout = view.findViewById(R.id.codexContextWindowLayout)
        autoCompactLayout = view.findViewById(R.id.codexAutoCompactLayout)

        profileNameInput = view.findViewById(R.id.codexProfileNameInput)
        providerIdInput = view.findViewById(R.id.codexProviderIdInput)
        providerNameInput = view.findViewById(R.id.codexProviderNameInput)
        baseUrlInput = view.findViewById(R.id.codexBaseUrlInput)
        apiKeyInput = view.findViewById(R.id.codexApiKeyInput)
        authModeInput = view.findViewById(R.id.codexAuthModeInput)
        modelInput = view.findViewById(R.id.codexModelInput)
        reviewModelInput = view.findViewById(R.id.codexReviewModelInput)
        reasoningInput = view.findViewById(R.id.codexReasoningInput)
        contextWindowInput = view.findViewById(R.id.codexContextWindowInput)
        autoCompactInput = view.findViewById(R.id.codexAutoCompactInput)

        statusText = view.findViewById(R.id.codexStatusText)
        fetchModelsButton = view.findViewById(R.id.codexFetchModelsButton)
        saveButton = view.findViewById(R.id.codexSaveButton)
        cancelButton = view.findViewById(R.id.codexCancelButton)
    }

    private fun setupDropdowns() {
        authModeInput.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                CodexCliAuthMode.entries.map(::authModeLabel)
            )
        )
        authModeInput.setOnClickListener { authModeInput.showDropDown() }

        reasoningInput.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                CodexCliReasoningEffort.displayNames()
            )
        )
        reasoningInput.setOnClickListener { reasoningInput.showDropDown() }
    }

    private fun loadSavedConfig() {
        loadedProfile = if (createNew) {
            null
        } else {
            CodexCliConfig.getProfile(profileId) ?: CodexCliConfig.getActiveProfile()
        }
        val settings = loadedProfile?.toSettings() ?: CodexCliConfig.getSettings()
        loadedModels.clear()
        loadedModels.addAll(loadedProfile?.cachedModels.orEmpty())

        profileNameInput.setText(loadedProfile?.name.orEmpty())
        providerIdInput.setText(settings.providerId)
        providerNameInput.setText(settings.providerName)
        baseUrlInput.setText(settings.baseUrl)
        apiKeyInput.setText(settings.apiKey)
        authModeInput.setText(authModeLabel(settings.authMode), false)
        modelInput.setText(settings.model, false)
        reviewModelInput.setText(settings.reviewModel)
        reasoningInput.setText(settings.reasoningEffort.displayName, false)
        contextWindowInput.setText(settings.contextWindow.toString())
        autoCompactInput.setText(settings.autoCompactTokenLimit.toString())

        updateModelSuggestions(
            if (loadedModels.isNotEmpty()) {
                buildSuggestedModels(settings.model, settings.resolvedReviewModel)
            } else {
                settings.availableModels
            }
        )
        statusText.text = if (createNew || loadedProfile == null) {
            getString(R.string.ai_assistant_codex_profile_create_status)
        } else {
            getString(
                R.string.ai_assistant_codex_profile_edit_status,
                loadedProfile?.name ?: settings.resolvedProfileName
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
                baseUrlLayout.error = getString(R.string.ai_assistant_fetch_models_base_url_required)
                hasError = true
            }
            if (apiKey.isBlank()) {
                apiKeyLayout.error = getString(R.string.ai_assistant_fetch_models_api_key_required)
                hasError = true
            }
            if (hasError) {
                return@setOnClickListener
            }

            fetchModels(baseUrl, apiKey)
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

        saveButton.setOnClickListener { saveConfig() }
        cancelButton.setOnClickListener { dismiss() }
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
                "${CodexCliConfig.versionedBaseUrl(baseUrl).removeSuffix("/v1")}/v1/models"
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
                if (reviewModelInput.text.isNullOrBlank() && models.isNotEmpty()) {
                    reviewModelInput.setText(models.first())
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
        val providerId = providerIdInput.text?.toString().orEmpty().trim()
        val providerName = providerNameInput.text?.toString().orEmpty().trim()
        val baseUrl = baseUrlInput.text?.toString().orEmpty().trim()
        val apiKey = apiKeyInput.text?.toString().orEmpty().trim()
        val model = modelInput.text?.toString().orEmpty().trim()
        val reviewModel = reviewModelInput.text?.toString().orEmpty().trim().ifBlank { model }
        val authMode = authModeFromLabel(authModeInput.text?.toString())
        val reasoningEffort = CodexCliReasoningEffort.fromDisplayName(reasoningInput.text?.toString())
        val contextWindow = contextWindowInput.text?.toString().orEmpty().trim().toLongOrNull()
        val autoCompact = autoCompactInput.text?.toString().orEmpty().trim().toLongOrNull()

        var hasError = false
        if (profileName.isBlank()) {
            profileNameLayout.error = getString(R.string.ai_assistant_profile_name_required)
            hasError = true
        }
        if (!CodexCliConfig.isValidProviderId(providerId)) {
            providerIdLayout.error = getString(R.string.ai_assistant_codex_provider_id_validation)
            hasError = true
        }
        if (apiKey.isBlank()) {
            apiKeyLayout.error = getString(R.string.ai_assistant_api_key_required)
            hasError = true
        }
        if (model.isBlank()) {
            modelLayout.error = getString(R.string.ai_assistant_model_required)
            hasError = true
        }
        if (authModeInput.text.isNullOrBlank()) {
            authModeLayout.error = getString(R.string.ai_assistant_auth_mode_required)
            hasError = true
        }
        if (reasoningInput.text.isNullOrBlank()) {
            reasoningLayout.error = getString(R.string.ai_assistant_reasoning_effort_required)
            hasError = true
        }
        if (contextWindow == null || contextWindow <= 0L) {
            contextWindowLayout.error = getString(R.string.ai_assistant_context_window_positive)
            hasError = true
        }
        if (autoCompact == null || autoCompact <= 0L) {
            autoCompactLayout.error = getString(R.string.ai_assistant_token_limit_positive)
            hasError = true
        }
        if (hasError) {
            return
        }

        val savedProfile = CodexCliConfig.saveProfile(
            profileId = loadedProfile?.id ?: profileId,
            profileName = profileName,
            providerId = providerId,
            providerName = providerName.ifBlank { providerId },
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            reviewModel = reviewModel,
            reasoningEffort = reasoningEffort,
            authMode = authMode,
            contextWindow = contextWindow ?: return,
            autoCompactTokenLimit = autoCompact ?: return,
            cachedModels = buildSuggestedModels(model, reviewModel),
            makeActive = makeActiveOnSave
        )
        onSave(savedProfile.toSettings())
        dismiss()
    }

    private fun updateModelSuggestions(models: List<String>) {
        modelInput.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                buildSuggestedModels(models = models)
            )
        )
    }

    private fun buildSuggestedModels(
        primaryModel: String? = null,
        reviewModel: String? = null,
        models: List<String> = loadedModels
    ): List<String> {
        val ordered = LinkedHashSet<String>()
        primaryModel?.trim()?.takeIf { it.isNotBlank() }?.let(ordered::add)
        reviewModel?.trim()?.takeIf { it.isNotBlank() }?.let(ordered::add)
        models.map(String::trim)
            .filter(String::isNotBlank)
            .forEach(ordered::add)
        return ordered.toList()
    }

    private fun authModeLabel(mode: CodexCliAuthMode): String {
        return when (mode) {
            CodexCliAuthMode.ENV_KEY -> getString(R.string.ai_assistant_codex_auth_mode_env_key)
            CodexCliAuthMode.OPENAI_AUTH -> getString(R.string.ai_assistant_codex_auth_mode_openai_auth)
        }
    }

    private fun authModeFromLabel(label: String?): CodexCliAuthMode {
        return when (label) {
            getString(R.string.ai_assistant_codex_auth_mode_env_key) -> CodexCliAuthMode.ENV_KEY
            getString(R.string.ai_assistant_codex_auth_mode_openai_auth) -> CodexCliAuthMode.OPENAI_AUTH
            else -> CodexCliAuthMode.fromDisplayName(label)
        }
    }

    private fun clearErrors() {
        profileNameLayout.error = null
        providerIdLayout.error = null
        providerNameLayout.error = null
        baseUrlLayout.error = null
        apiKeyLayout.error = null
        authModeLayout.error = null
        modelLayout.error = null
        reviewModelLayout.error = null
        reasoningLayout.error = null
        contextWindowLayout.error = null
        autoCompactLayout.error = null
    }

    private fun setLoading(loading: Boolean) {
        fetchModelsButton.isEnabled = !loading
        saveButton.isEnabled = !loading
        cancelButton.isEnabled = !loading
    }
}
