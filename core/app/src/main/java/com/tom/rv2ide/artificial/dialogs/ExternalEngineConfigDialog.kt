package com.tom.rv2ide.artificial.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.external.CodexTermuxBridge
import com.tom.rv2ide.artificial.agents.external.ExternalEngineConfig
import com.tom.rv2ide.artificial.agents.external.ExternalEngineSettings
import com.tom.rv2ide.artificial.agents.external.ExternalWorkingDirectoryMode

class ExternalEngineConfigDialog(
    private val onSave: (ExternalEngineSettings) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var displayNameLayout: TextInputLayout
    private lateinit var commandTemplateLayout: TextInputLayout
    private lateinit var workingDirectoryLayout: TextInputLayout
    private lateinit var displayNameInput: TextInputEditText
    private lateinit var commandTemplateInput: TextInputEditText
    private lateinit var workingDirectoryInput: MaterialAutoCompleteTextView
    private lateinit var promptViaStdinSwitch: MaterialSwitch
    private lateinit var helperText: MaterialTextView
    private lateinit var installCodexButton: Button
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_external_engine_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupDropdown()
        loadSavedConfig()
        setupActions()
    }

    private fun bindViews(view: View) {
        displayNameLayout = view.findViewById(R.id.externalDisplayNameLayout)
        commandTemplateLayout = view.findViewById(R.id.externalCommandTemplateLayout)
        workingDirectoryLayout = view.findViewById(R.id.externalWorkingDirectoryLayout)
        displayNameInput = view.findViewById(R.id.externalDisplayNameInput)
        commandTemplateInput = view.findViewById(R.id.externalCommandTemplateInput)
        workingDirectoryInput = view.findViewById(R.id.externalWorkingDirectoryInput)
        promptViaStdinSwitch = view.findViewById(R.id.externalPromptViaStdinSwitch)
        helperText = view.findViewById(R.id.externalHelperText)
        installCodexButton = view.findViewById(R.id.externalInstallCodexButton)
        saveButton = view.findViewById(R.id.externalSaveButton)
        cancelButton = view.findViewById(R.id.externalCancelButton)
    }

    private fun setupDropdown() {
        workingDirectoryInput.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                ExternalWorkingDirectoryMode.displayNames()
            )
        )
        workingDirectoryInput.setOnClickListener { workingDirectoryInput.showDropDown() }
    }

    private fun loadSavedConfig() {
        val settings = ExternalEngineConfig.getSettings()
            .takeIf(ExternalEngineSettings::isValid)
            ?: CodexTermuxBridge.recommendedSettings()
        populateInputs(settings)
        updateHelperText()
    }

    private fun setupActions() {
        displayNameInput.doAfterTextChanged { updateHelperText() }
        commandTemplateInput.doAfterTextChanged { updateHelperText() }
        installCodexButton.setOnClickListener { installCodexPreset() }
        saveButton.setOnClickListener { saveConfig() }
        cancelButton.setOnClickListener { dismiss() }
    }

    private fun updateHelperText() {
        val previewName = displayNameInput.text?.toString().orEmpty().trim()
        val previewTemplate = commandTemplateInput.text?.toString().orEmpty().trim()
        val effectiveLabel = CodexTermuxBridge.recommendedSettings().copy(
            displayName = previewName,
            commandTemplate = previewTemplate
        ).resolvedDisplayLabel()
        helperText.text = buildString {
            appendLine(CodexTermuxBridge.status().summaryText())
            appendLine("Shell-safe placeholders: {project_root} {working_directory} {session_file} {prompt_file} {prompt}")
            appendLine("Codex preset runs codex exec --json, stores thread.started.thread_id, and resumes with codex exec resume <thread_id> -.")
            appendLine("Prefer {prompt_file} or stdin for long prompts. Do not add extra quotes around placeholders.")
            append("Current label: ")
            append(effectiveLabel)
        }
    }

    private fun installCodexPreset() {
        val savedSettings = CodexTermuxBridge.installAndConfigure(
            context = requireContext(),
            selectProvider = true
        )
        populateInputs(savedSettings)
        onSave(savedSettings)
        dismiss()
    }

    private fun saveConfig() {
        clearErrors()

        val displayName = displayNameInput.text?.toString().orEmpty().trim()
        val commandTemplate = commandTemplateInput.text?.toString().orEmpty().trim()
        val workingDirectoryMode = ExternalWorkingDirectoryMode.fromDisplayName(
            workingDirectoryInput.text?.toString()
        )
        val passPromptViaStdin = promptViaStdinSwitch.isChecked

        var hasError = false
        if (commandTemplate.isBlank()) {
            commandTemplateLayout.error = "Command template is required"
            hasError = true
        }
        if (workingDirectoryInput.text.isNullOrBlank()) {
            workingDirectoryLayout.error = "Working directory is required"
            hasError = true
        }
        if (hasError) {
            return
        }

        val savedSettings = ExternalEngineConfig.save(
            displayName = displayName,
            commandTemplate = commandTemplate,
            workingDirectoryMode = workingDirectoryMode,
            passPromptViaStdin = passPromptViaStdin
        )
        onSave(savedSettings)
        dismiss()
    }

    private fun clearErrors() {
        displayNameLayout.error = null
        commandTemplateLayout.error = null
        workingDirectoryLayout.error = null
    }

    private fun populateInputs(settings: ExternalEngineSettings) {
        displayNameInput.setText(settings.displayName)
        commandTemplateInput.setText(settings.commandTemplate)
        workingDirectoryInput.setText(settings.workingDirectoryMode.displayName, false)
        promptViaStdinSwitch.isChecked = settings.passPromptViaStdin
    }
}
