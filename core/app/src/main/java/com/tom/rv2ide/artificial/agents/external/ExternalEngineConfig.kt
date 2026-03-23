package com.tom.rv2ide.artificial.agents.external

import com.tom.rv2ide.preferences.internal.prefManager
import java.security.MessageDigest

enum class ExternalWorkingDirectoryMode(
    val value: String,
    val displayName: String
) {
    PROJECT_ROOT("project_root", "Project Root"),
    HOME("home", "Termux Home"),
    PREFIX("prefix", "Termux Prefix");

    companion object {
        fun fromValue(value: String?): ExternalWorkingDirectoryMode {
            return values().firstOrNull { it.value == value } ?: PROJECT_ROOT
        }

        fun displayNames(): Array<String> {
            return values().map { it.displayName }.toTypedArray()
        }

        fun fromDisplayName(displayName: String?): ExternalWorkingDirectoryMode {
            return values().firstOrNull { it.displayName == displayName } ?: PROJECT_ROOT
        }
    }
}

data class ExternalEngineSettings(
    val displayName: String,
    val commandTemplate: String,
    val workingDirectoryMode: ExternalWorkingDirectoryMode,
    val passPromptViaStdin: Boolean
) {
    val isValid: Boolean
        get() = commandTemplate.isNotBlank()

    fun resolvedDisplayLabel(): String {
        val explicit = displayName.trim()
        if (explicit.isNotBlank()) {
            return explicit
        }

        val commandToken = commandTemplate
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .substringBefore(' ')
            .substringBefore('\t')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()

        return commandToken.ifBlank { "External CLI" }
    }

    fun fingerprint(): String {
        val payload = buildString {
            appendLine(displayName.trim())
            appendLine(commandTemplate.trim())
            appendLine(workingDirectoryMode.value)
            append(passPromptViaStdin)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(16)
    }
}

object ExternalEngineConfig {

    private const val KEY_DISPLAY_NAME = "ai_external_engine_display_name"
    private const val KEY_COMMAND_TEMPLATE = "ai_external_engine_command_template"
    private const val KEY_WORKDIR_MODE = "ai_external_engine_workdir_mode"
    private const val KEY_PASS_PROMPT_STDIN = "ai_external_engine_pass_prompt_stdin"

    fun getSettings(): ExternalEngineSettings {
        val rawSettings = readSettings()
        val repairedSettings = CodexTermuxBridge.repairCodexSettingsIfNeeded(rawSettings)
        return if (repairedSettings != null) {
            save(
                displayName = repairedSettings.displayName,
                commandTemplate = repairedSettings.commandTemplate,
                workingDirectoryMode = repairedSettings.workingDirectoryMode,
                passPromptViaStdin = repairedSettings.passPromptViaStdin
            )
        } else {
            rawSettings
        }
    }

    private fun readSettings(): ExternalEngineSettings {
        return ExternalEngineSettings(
            displayName = prefManager.getString(KEY_DISPLAY_NAME, "").orEmpty().trim(),
            commandTemplate = prefManager.getString(KEY_COMMAND_TEMPLATE, "").orEmpty().trim(),
            workingDirectoryMode = ExternalWorkingDirectoryMode.fromValue(
                prefManager.getString(KEY_WORKDIR_MODE, ExternalWorkingDirectoryMode.PROJECT_ROOT.value)
            ),
            passPromptViaStdin = prefManager.getBoolean(KEY_PASS_PROMPT_STDIN, false)
        )
    }

    fun save(
        displayName: String,
        commandTemplate: String,
        workingDirectoryMode: ExternalWorkingDirectoryMode,
        passPromptViaStdin: Boolean
    ): ExternalEngineSettings {
        prefManager.putString(KEY_DISPLAY_NAME, displayName.trim())
        prefManager.putString(KEY_COMMAND_TEMPLATE, commandTemplate.trim())
        prefManager.putString(KEY_WORKDIR_MODE, workingDirectoryMode.value)
        prefManager.putBoolean(KEY_PASS_PROMPT_STDIN, passPromptViaStdin)
        return readSettings()
    }

    fun hasValidConfig(): Boolean {
        return getSettings().isValid
    }

    fun getCommandTemplate(): String {
        return getSettings().commandTemplate
    }

    fun getModelLabel(): String {
        return getSettings().resolvedDisplayLabel()
    }

    fun fingerprint(): String {
        return getSettings().fingerprint()
    }
}
