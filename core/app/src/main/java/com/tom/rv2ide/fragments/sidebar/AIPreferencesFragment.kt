package com.tom.rv2ide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.custom.CustomProviderConfig
import com.tom.rv2ide.artificial.agents.external.CodexCliConfig
import com.tom.rv2ide.artificial.agents.external.CodexTermuxBridge
import com.tom.rv2ide.artificial.agents.external.ExternalEngineConfig
import com.tom.rv2ide.artificial.dialogs.CodexCliConfigDialog
import com.tom.rv2ide.artificial.dialogs.CustomProviderConfigDialog
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import com.tom.rv2ide.managers.CodeCompletionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tom.rv2ide.artificial.dialogs.LocalLLMConfigDialog

class AIPreferencesFragment(
    private val aiAgent: AIAgentManager,
    private val agents: Agents,
    private val codeCompletionManager: CodeCompletionManager?
) : Fragment() {

    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var customProfileDropdown: AutoCompleteTextView
    private lateinit var autoSwitchToggle: MaterialSwitch
    private lateinit var toolExecutionToggle: MaterialSwitch
    private lateinit var codeCompletionToggle: MaterialSwitch
    private lateinit var currentProviderText: MaterialTextView
    private lateinit var currentModelText: MaterialTextView
    private lateinit var customSection: LinearLayout
    private lateinit var externalSection: LinearLayout
    private lateinit var externalEngineSummaryText: MaterialTextView
    private lateinit var codexConfigSummaryText: MaterialTextView
    private lateinit var installCodexButton: MaterialButton
    private lateinit var configureCodexButton: MaterialButton
    private lateinit var configureExternalEngineButton: MaterialButton
    private lateinit var addCustomProfileButton: MaterialButton
    private lateinit var editCustomProfileButton: MaterialButton
    private lateinit var deleteCustomProfileButton: MaterialButton
    
    private val providerSwitchDialog by lazy { ProviderSwitchDialog(requireContext()) }
    private val permissionManager by lazy { AIPermissionManager(requireContext()) }
    
    private var completionStateMonitorJob: Job? = null
    private var isCompletionEnabled = true
    private var customProfileIds: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupProviderDropdown()
        setupModelDropdown()
        setupCustomProfileSection()
        setupExternalEngineSection()
        setupToggles()
        updateCurrentStatus()
        updateCustomSectionVisibility()
        updateExternalSection()
        startCompletionStateMonitoring()
    }

    override fun onResume() {
        super.onResume()
        startCompletionStateMonitoring()
        updateCurrentStatus()
        updateProviderDropdownSelection()
        updateCustomProfileDropdown()
        updateModelDropdown()
        updateCustomSectionVisibility()
        updateExternalSection()
        syncCodeCompletionToggle()
    }
    
    override fun onPause() {
        super.onPause()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        providerDropdown = view.findViewById(R.id.providerDropdown)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        customProfileDropdown = view.findViewById(R.id.customProfileDropdown)
        autoSwitchToggle = view.findViewById(R.id.autoSwitchToggle)
        toolExecutionToggle = view.findViewById(R.id.toolExecutionToggle)
        codeCompletionToggle = view.findViewById(R.id.codeCompletionToggle)
        currentProviderText = view.findViewById(R.id.currentProviderText)
        currentModelText = view.findViewById(R.id.currentModelText)
        customSection = view.findViewById(R.id.customSection)
        externalSection = view.findViewById(R.id.externalSection)
        externalEngineSummaryText = view.findViewById(R.id.externalEngineSummaryText)
        codexConfigSummaryText = view.findViewById(R.id.codexConfigSummaryText)
        installCodexButton = view.findViewById(R.id.installCodexButton)
        configureCodexButton = view.findViewById(R.id.configureCodexButton)
        configureExternalEngineButton = view.findViewById(R.id.configureExternalEngineButton)
        addCustomProfileButton = view.findViewById(R.id.addCustomProfileButton)
        editCustomProfileButton = view.findViewById(R.id.editCustomProfileButton)
        deleteCustomProfileButton = view.findViewById(R.id.deleteCustomProfileButton)
    }

    private fun setupProviderDropdown() {
        val allProviderIds = listOf("gemini", "openai", "claude", "deepseek", "grok", "localllm", "external", "custom")
        val providerNames = allProviderIds.map(::providerDisplayName)
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, providerNames)
        providerDropdown.setAdapter(adapter)
        
        updateProviderDropdownSelection()
        
        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedProviderId = allProviderIds[position]
            val selectedProviderName = providerNames[position]
            
            if (selectedProviderId == "localllm") {
                updateProviderDropdownSelection()
                showLocalLLMConfigDialog(selectedProviderName)
            } else if (selectedProviderId == "external") {
                val settings = CodexTermuxBridge.ensureManagedPreset(context = requireContext())
                handleProviderChange("external", selectedProviderName, settings.resolvedDisplayLabel())
            } else if (selectedProviderId == "custom") {
                val activeProfile = CustomProviderConfig.getActiveProfile()
                when {
                    activeProfile == null -> {
                        updateProviderDropdownSelection()
                        showCustomProviderConfigDialog(createNew = true)
                    }
                    !activeProfile.isValid -> {
                        updateProviderDropdownSelection()
                        showCustomProviderConfigDialog(profileId = activeProfile.id)
                    }
                    else -> handleProviderChange("custom", selectedProviderName, activeProfile.modelId)
                }
            } else {
                handleProviderChange(selectedProviderId, selectedProviderName)
            }
        }
    }
    
    private fun showLocalLLMConfigDialog(providerName: String) {
        val dialog = LocalLLMConfigDialog { _, _ ->
            handleProviderChange("localllm", providerName)
        }
        dialog.show(parentFragmentManager, "LocalLLMConfigDialog")
    }

    private fun showCustomProviderConfigDialog(
        profileId: String? = null,
        createNew: Boolean = false
    ) {
        val dialog = CustomProviderConfigDialog(
            profileId = profileId,
            createNew = createNew
        ) { savedProfile ->
            updateCustomProfileDropdown()
            updateCustomSectionVisibility()
            updateModelDropdown()
            handleProviderChange("custom", providerDisplayName("custom"), savedProfile.modelId)
        }
        dialog.show(parentFragmentManager, "CustomProviderConfigDialog")
    }

    private fun showCodexConfigDialog() {
        val dialog = CodexCliConfigDialog { _ ->
            CodexTermuxBridge.ensureManagedPreset(context = requireContext())
            updateExternalSection()
            updateModelDropdown()
            updateCurrentStatus()
            showSnackbar("Codex CLI settings saved")
        }
        dialog.show(parentFragmentManager, "CodexCliConfigDialog")
    }
    
    private fun updateProviderDropdownSelection() {
        val currentProviderId = agents.getProvider()
        val currentProviderName = providerDisplayName(currentProviderId)
        providerDropdown.setText(currentProviderName, false)
    }
    
    private fun updateCurrentStatus() {
        val currentProvider = agents.getProvider()
        val currentModel = agents.getAgent()
        
        android.util.Log.d("AIPreferences", "Current provider: $currentProvider, model: $currentModel")
        
        val providerDisplay = when (currentProvider) {
            "custom" -> {
                val activeProfileName = CustomProviderConfig.getActiveProfile()?.name
                listOfNotNull(providerDisplayName(currentProvider), activeProfileName?.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
            }
            "external" -> {
                listOfNotNull(
                    providerDisplayName(currentProvider),
                    ExternalEngineConfig.getModelLabel().takeIf { ExternalEngineConfig.hasValidConfig() && it.isNotBlank() }
                ).joinToString(" · ")
            }
            else -> providerDisplayName(currentProvider)
        }

        currentProviderText.text = providerDisplay
        currentModelText.text = currentModel
    }

    private fun setupModelDropdown() {
        updateModelDropdown()
        
        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val currentProvider = agents.getProvider()
            val models = agents.getModelsForProvider(currentProvider)
            
            if (position < models.size) {
                val selectedModel = models[position]
                handleModelChange(selectedModel)
            }
        }
    }

    private fun updateModelDropdown() {
        val currentProvider = agents.getProvider()
        val models = agents.getModelsForProvider(currentProvider)
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, models.toList())
        modelDropdown.setAdapter(adapter)
        
        val currentModel = agents.getAgent()
        if (currentProvider == "custom" && currentModel.isNotBlank()) {
            modelDropdown.setText(currentModel, false)
        } else if (currentModel in models) {
            modelDropdown.setText(currentModel, false)
        } else if (models.isNotEmpty()) {
            modelDropdown.setText(models[0], false)
        } else {
            modelDropdown.setText("", false)
        }
    }

    private fun setupCustomProfileSection() {
        updateCustomProfileDropdown()

        customProfileDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedProfileId = customProfileIds.getOrNull(position) ?: return@setOnItemClickListener
            CustomProviderConfig.setActiveProfile(selectedProfileId)
            updateCustomProfileDropdown()
            updateModelDropdown()
            updateCurrentStatus()

            if (agents.getProvider() == "custom") {
                handleProviderChange("custom", providerDisplayName("custom"), CustomProviderConfig.getModelId())
            } else {
                showSnackbar("Active custom profile: ${customProfileDropdown.text}")
            }
        }

        addCustomProfileButton.setOnClickListener {
            showCustomProviderConfigDialog(createNew = true)
        }

        editCustomProfileButton.setOnClickListener {
            val activeProfileId = CustomProviderConfig.getActiveProfileId()
            if (activeProfileId.isBlank()) {
                showSnackbar("Add a custom profile first")
                return@setOnClickListener
            }
            showCustomProviderConfigDialog(profileId = activeProfileId)
        }

        deleteCustomProfileButton.setOnClickListener {
            val activeProfile = CustomProviderConfig.getActiveProfile()
            if (activeProfile == null) {
                showSnackbar("No custom profile to delete")
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Custom Profile")
                .setMessage("Delete '${activeProfile.name}'?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    val deleted = CustomProviderConfig.deleteProfile(activeProfile.id)
                    if (!deleted) {
                        showSnackbar("Failed to delete custom profile")
                        return@setPositiveButton
                    }

                    updateCustomProfileDropdown()
                    updateModelDropdown()
                    updateCurrentStatus()
                    updateCustomSectionVisibility()

                    val newActiveProfile = CustomProviderConfig.getActiveProfile()
                    when {
                        agents.getProvider() == "custom" && newActiveProfile?.isValid == true -> {
                            handleProviderChange("custom", providerDisplayName("custom"), newActiveProfile.modelId)
                        }
                        agents.getProvider() == "custom" -> {
                            updateProviderDropdownSelection()
                            showSnackbar("Custom profile deleted. Add or select another profile to keep using Custom Provider.")
                        }
                        else -> {
                            showSnackbar("Deleted ${activeProfile.name}")
                        }
                    }
                }
                .show()
        }
    }

    private fun setupExternalEngineSection() {
        installCodexButton.setOnClickListener {
            val settings = CodexTermuxBridge.installAndConfigure(
                context = requireContext(),
                selectProvider = true
            )
            updateExternalSection()
            updateProviderDropdownSelection()
            updateModelDropdown()
            updateCurrentStatus()
            showSnackbar("Codex CLI installer opened and Codex preset applied")
            if (agents.getProvider() == "external") {
                handleProviderChange("external", providerDisplayName("external"), settings.resolvedDisplayLabel())
            }
        }
        configureExternalEngineButton.setOnClickListener {
            val settings = CodexTermuxBridge.ensureManagedPreset(context = requireContext())
            updateExternalSection()
            updateModelDropdown()
            if (agents.getProvider() == "external") {
                handleProviderChange("external", providerDisplayName("external"), settings.resolvedDisplayLabel())
            } else {
                updateCurrentStatus()
                showSnackbar("Codex bridge preset applied")
            }
        }
        configureCodexButton.setOnClickListener {
            showCodexConfigDialog()
        }
    }

    private fun updateCustomProfileDropdown() {
        val profiles = CustomProviderConfig.getProfiles()
        customProfileIds = profiles.map { it.id }
        val profileNames = profiles.map { profile ->
            if (profile.id == CustomProviderConfig.getActiveProfileId()) {
                "${profile.name} (Active)"
            } else {
                profile.name
            }
        }

        customProfileDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, profileNames)
        )
        val activeProfile = CustomProviderConfig.getActiveProfile()
        customProfileDropdown.setText(activeProfile?.name.orEmpty(), false)

        val hasProfiles = profiles.isNotEmpty()
        customProfileDropdown.isEnabled = hasProfiles
        editCustomProfileButton.isEnabled = hasProfiles
        deleteCustomProfileButton.isEnabled = hasProfiles
    }

    private fun updateCustomSectionVisibility() {
        customSection.visibility = View.VISIBLE
    }

    private fun updateExternalSection() {
        externalSection.visibility = View.VISIBLE
        val codexStatus = CodexTermuxBridge.status()
        val codexConfig = CodexCliConfig.getSettings()
        externalEngineSummaryText.text = if (ExternalEngineConfig.hasValidConfig()) {
            "${codexStatus.summaryText()} · managed session bridge active"
        } else {
            codexStatus.summaryText()
        }
        codexConfigSummaryText.text = if (codexConfig.isValid) {
            "Codex config: ${codexConfig.summaryText()}"
        } else {
            "Codex config: not set. Configure provider ID, base URL, key, and model for the Termux bridge."
        }
        installCodexButton.text = if (codexStatus.installed) "Reinstall Codex CLI" else "Install Codex CLI"
    }

    private fun providerDisplayName(providerId: String): String {
        return when (providerId) {
            "gemini" -> "Google Gemini"
            "openai" -> "OpenAI"
            "claude" -> "Anthropic Claude"
            "deepseek" -> "DeepSeek"
            "grok" -> "xAI Grok"
            "localllm" -> "Local LLM"
            "external" -> "Codex CLI"
            "custom" -> "Custom Provider"
            else -> providerId.uppercase()
        }
    }

    private fun setupToggles() {
        autoSwitchToggle.isChecked = providerSwitchDialog.isAutoSwitchEnabled()
        autoSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
            providerSwitchDialog.setAutoSwitch(isChecked)
            val message = if (isChecked) {
                "Auto-switch enabled"
            } else {
                "Auto-switch disabled"
            }
            showSnackbar(message)
        }

        toolExecutionToggle.isChecked = permissionManager.isToolExecutionEnabled()
        toolExecutionToggle.setOnCheckedChangeListener { _, isChecked ->
            permissionManager.setToolExecutionEnabled(isChecked)
            val message = if (isChecked) {
                "AI tool execution enabled"
            } else {
                "AI tool execution disabled"
            }
            showSnackbar(message)
        }
        
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        val effectiveSavedState = if (agents.getProvider() == "external") false else savedState
        isCompletionEnabled = effectiveSavedState
        codeCompletionToggle.isChecked = effectiveSavedState
        codeCompletionToggle.isEnabled = agents.getProvider() != "external"
        
        codeCompletionToggle.setOnCheckedChangeListener { _, isChecked ->
            if (agents.getProvider() == "external" && isChecked) {
                codeCompletionToggle.isChecked = false
                showSnackbar("Code completion is unavailable for Codex CLI")
                return@setOnCheckedChangeListener
            }
            android.util.Log.d("AIPreferences", "Toggle changed to: $isChecked")
            
            isCompletionEnabled = isChecked
            
            requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("code_completion_enabled", isChecked)
                .apply()
            
            lifecycleScope.launch {
                applyCompletionStateChange(isChecked)
            }
            
            val message = if (isChecked) {
                "✅ Code completion enabled"
            } else {
                "❌ Code completion disabled"
            }
            showSnackbar(message)
        }
    }
    
    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        
        completionStateMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(100)
                
                val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                
                if (savedState != isCompletionEnabled) {
                    android.util.Log.d("AIPreferences", "State mismatch detected: saved=$savedState, current=$isCompletionEnabled")
                    isCompletionEnabled = savedState
                    
                    if (codeCompletionToggle.isChecked != savedState) {
                        codeCompletionToggle.isChecked = savedState
                    }
                    
                    applyCompletionStateChange(savedState)
                }
            }
        }
    }
    
    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }
    
    private suspend fun applyCompletionStateChange(enabled: Boolean) {
        android.util.Log.d("AIPreferences", "Applying completion state change: $enabled")
        
        if (enabled) {
            codeCompletionManager?.reattachToCurrentEditor()
            android.util.Log.d("AIPreferences", "Re-enabled code completion")
        } else {
            codeCompletionManager?.cleanup()
            android.util.Log.d("AIPreferences", "Disabled code completion")
        }
    }

    private fun syncCodeCompletionToggle() {
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)

        val effectiveSavedState = if (agents.getProvider() == "external") false else savedState

        android.util.Log.d("AIPreferences", "Syncing toggle: saved=$effectiveSavedState")

        isCompletionEnabled = effectiveSavedState
        codeCompletionToggle.isChecked = effectiveSavedState
        codeCompletionToggle.isEnabled = agents.getProvider() != "external"
    }

    private fun handleProviderChange(
        providerId: String,
        providerName: String,
        preferredModel: String? = null
    ) {
        android.util.Log.d("AIPreferences", "Switching to provider: $providerId")
        
        val availableModels = agents.getModelsForProvider(providerId)
        android.util.Log.d("AIPreferences", "Available models for $providerId: ${availableModels.joinToString()}")

        val previousProvider = agents.getProvider()
        val previousModel = agents.getAgent()
        val targetModel = preferredModel?.takeIf { it.isNotBlank() } ?: availableModels.firstOrNull()

        agents.setProvider(providerId)
        targetModel?.let {
            agents.setAgent(it)
            android.util.Log.d("AIPreferences", "Set target model: $it")
        }
        
        updateModelDropdown()
        updateCustomProfileDropdown()
        updateCustomSectionVisibility()
        updateExternalSection()
        
        if (aiAgent.setProvider(providerId)) {
            updateProviderDropdownSelection()
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()
            updateExternalSection()
            codeCompletionToggle.isEnabled = providerId != "external"
            if (providerId == "external" && isCompletionEnabled) {
                requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("code_completion_enabled", false)
                    .apply()
                isCompletionEnabled = false
                codeCompletionToggle.isChecked = false
                lifecycleScope.launch {
                    applyCompletionStateChange(false)
                }
            }
             
            lifecycleScope.launch {
                if (isCompletionEnabled && providerId != "external") {
                    delay(500)
                    codeCompletionManager?.reattachToCurrentEditor()
                    android.util.Log.d("AIPreferences", "Reattached completion after provider change")
                }
            }
            
            showSnackbar("Switched to $providerName")
        } else {
            agents.setProvider(previousProvider)
            if (previousModel.isNotBlank()) {
                agents.setAgent(previousModel)
            }
            updateProviderDropdownSelection()
            updateModelDropdown()
            updateCustomProfileDropdown()
            updateCustomSectionVisibility()
            updateExternalSection()
            updateCurrentStatus()
            showSnackbar("⚠️ No valid API key for $providerName")
        }
    }

    private fun handleModelChange(modelName: String) {
        android.util.Log.d("AIPreferences", "Switching to model: $modelName")
        agents.setAgent(modelName)
        aiAgent.reinitializeWithSelectedModel()
        updateCustomProfileDropdown()
        updateCurrentStatus()
        
        lifecycleScope.launch {
            if (isCompletionEnabled) {
                delay(500)
                codeCompletionManager?.reattachToCurrentEditor()
                android.util.Log.d("AIPreferences", "Reattached completion after model change")
            }
        }
        
        showSnackbar("Model switched to: $modelName")
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroyView() {
        stopCompletionStateMonitoring()
        super.onDestroyView()
    }
}
