package com.tom.rv2ide.artificial.agents.external

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tom.rv2ide.activities.TerminalActivity
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.custom.CustomProviderApiType
import com.tom.rv2ide.artificial.agents.custom.CustomProviderConfig
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.termux.shared.termux.repository.TermuxPackageRepository
import com.tom.rv2ide.utils.Environment
import java.io.File
import org.json.JSONObject

data class CodexTermuxStatus(
    val installed: Boolean,
    val executablePath: String?,
    val configured: Boolean,
    val configuredForCodex: Boolean,
    val launcherNeedsRepair: Boolean
) {
    val ready: Boolean
        get() = installed && configuredForCodex && !launcherNeedsRepair

    fun runtimeIssue(): String? {
        if (!configuredForCodex) {
            return "Codex bridge preset is missing. Apply the Codex preset first."
        }
        return when {
            !installed -> "Codex CLI is not installed in Termux yet. Tap Install Codex to set it up."
            launcherNeedsRepair -> "Codex CLI launcher needs repair. Tap Fix Codex to rebuild the Termux wrapper."
            else -> null
        }
    }

    fun summaryText(): String {
        return when {
            launcherNeedsRepair ->
                "Codex CLI installed${executablePath?.let { " • $it" }.orEmpty()} • launcher needs repair, rerun installer"
            installed && configuredForCodex ->
                "Codex CLI installed${executablePath?.let { " • $it" }.orEmpty()} • preset ready"
            installed ->
                "Codex CLI installed${executablePath?.let { " • $it" }.orEmpty()} • apply Codex preset"
            configuredForCodex ->
                "Codex preset saved • install Codex CLI in Termux to run it"
            configured ->
                "Codex bridge preset missing • apply Codex preset"
            else ->
                "Codex CLI not installed yet"
        }
    }
}

object CodexTermuxBridge {

    private const val PACKAGE_NAME = "@mmmbuto/codex-cli-termux"
    private const val EXECUTABLE_NAME = "codex"
    private const val CODEX_ENV_FILE_NAME = "android-code-studio.env"
    private const val MANAGED_CONFIG_MARKER = "# Managed by Android Code Studio"
    private const val CUSTOM_PROVIDER_ID = "android_code_studio_custom"
    private const val CUSTOM_PROVIDER_ENV_KEY = "ACS_CODEX_PROVIDER_API_KEY"
    private const val CODEX_OPENAI_ENV_KEY = "OPENAI_API_KEY"
    private val BROKEN_ENV_SHEBANGS = setOf(
        "#!/usr/bin/env node",
        "#!/usr/bin/env sh"
    )
    private const val NPM_STUB_MARKER = "CODEX_MANAGED_BY_NPM"
    private const val GLOBAL_CODEX_STUB_MARKER = "const binaryPath = join(__dirname, 'codex')"
    private const val GLOBAL_CODEX_EXEC_STUB_MARKER =
        "const execBinaryPath = join(__dirname, 'codex-exec')"

    data class CodexLaunchConfiguration(
        val shellSetup: String,
        val cliConfigFlags: String,
        val cliConfigArgs: List<String>,
        val environmentVariables: Map<String, String>
    )

    private val executableCandidates: List<File>
        get() = listOf(
            File(Environment.BIN_DIR, EXECUTABLE_NAME),
            File(Environment.PREFIX, "bin/$EXECUTABLE_NAME")
        )

    fun isManagedCodexTemplate(commandTemplate: String): Boolean {
        val normalizedTemplate = commandTemplate.trim()
        return normalizedTemplate.contains("codex exec") &&
            normalizedTemplate.contains("--json") &&
            normalizedTemplate.contains("{codex_resume_args}")
    }

    fun looksLikeCodexCommand(commandTemplate: String): Boolean {
        val normalizedTemplate = commandTemplate.trim()
        return Regex("""(^|\s)codex(\s|$)""").containsMatchIn(normalizedTemplate)
    }

    fun repairCodexSettingsIfNeeded(settings: ExternalEngineSettings): ExternalEngineSettings? {
        val normalizedDisplayName = settings.displayName.trim()
        val looksLikeCodexProfile = looksLikeCodexCommand(settings.commandTemplate) ||
            (settings.commandTemplate.isBlank() && normalizedDisplayName.contains("codex", ignoreCase = true))
        if (!looksLikeCodexProfile) {
            return null
        }
        val normalizedSettings = recommendedSettings().copy(
            displayName = normalizedDisplayName.ifBlank { "Codex CLI" },
            workingDirectoryMode = settings.workingDirectoryMode,
            passPromptViaStdin = false
        )
        return if (settings == normalizedSettings) {
            null
        } else {
            normalizedSettings
        }
    }

    fun recommendedSettings(): ExternalEngineSettings {
        return ExternalEngineSettings(
            displayName = "Codex CLI",
            commandTemplate = buildString {
                appendLine("{codex_shell_setup}")
                append("codex exec --json {codex_config_flags} --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check {codex_resume_args}")
            },
            workingDirectoryMode = ExternalWorkingDirectoryMode.PROJECT_ROOT,
            passPromptViaStdin = false
        )
    }

    fun status(): CodexTermuxStatus {
        val settings = ExternalEngineConfig.getSettings()
        val executable = executableCandidates.firstOrNull { it.isFile && it.canExecute() }
        val launcherNeedsRepair = executable?.needsCodexLauncherRepair() == true
        val configuredForCodex = isManagedCodexTemplate(settings.commandTemplate)
        return CodexTermuxStatus(
            installed = executable != null,
            executablePath = executable?.absolutePath,
            configured = settings.isValid,
            configuredForCodex = configuredForCodex,
            launcherNeedsRepair = launcherNeedsRepair
        )
    }

    fun applyPreset(selectProvider: Boolean = false, context: Context? = null): ExternalEngineSettings {
        val preset = recommendedSettings()
        val savedSettings = ExternalEngineConfig.save(
            displayName = preset.displayName,
            commandTemplate = preset.commandTemplate,
            workingDirectoryMode = preset.workingDirectoryMode,
            passPromptViaStdin = preset.passPromptViaStdin
        )
        if (selectProvider && context != null) {
            val agents = Agents(context)
            agents.setProvider("external")
            agents.setAgent(CodexCliConfig.getModelId())
        }
        context?.let(::prepareLaunchConfiguration)
        return savedSettings
    }

    fun ensureManagedPreset(context: Context? = null, selectProvider: Boolean = false): ExternalEngineSettings {
        val currentSettings = ExternalEngineConfig.getSettings()
        val managedSettings = if (isManagedCodexTemplate(currentSettings.commandTemplate)) {
            currentSettings
        } else {
            applyPreset(selectProvider = false, context = null)
        }
        if (selectProvider && context != null) {
            val agents = Agents(context)
            agents.setProvider("external")
            agents.setAgent(CodexCliConfig.getModelId())
        }
        context?.let(::prepareLaunchConfiguration)
        return managedSettings
    }

    fun installAndConfigure(
        context: Context,
        selectProvider: Boolean = false
    ): ExternalEngineSettings {
        val savedSettings = applyPreset(selectProvider = selectProvider, context = context)
        launchInstaller(context)
        return savedSettings
    }

    fun launchInstaller(context: Context) {
        val installIntent = Intent(context, TerminalActivity::class.java).apply {
            putExtra(
                TerminalActivity.EXTRA_SCRIPTED_SESSION_COMMAND,
                buildInstallerCommand(context)
            )
            putExtra(
                TerminalActivity.EXTRA_SCRIPTED_SESSION_NAME,
                "Install Codex CLI"
            )
            putExtra(
                TerminalActivity.EXTRA_SCRIPTED_SESSION_WORKDIR,
                Environment.HOME.absolutePath
            )
            putExtra(
                TerminalActivity.EXTRA_SCRIPTED_SESSION_KEEP_OPEN,
                true
            )
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(installIntent)
    }

    fun buildInstallerCommand(context: Context): String {
        return buildInstallCommand(context)
    }

    private fun buildInstallCommand(context: Context): String {
        return CodexTermuxInstallerScriptSupport.buildInstallCommand(
            context = context,
            packageName = PACKAGE_NAME,
            npmStubMarker = NPM_STUB_MARKER,
            globalCodexStubMarker = GLOBAL_CODEX_STUB_MARKER,
            globalCodexExecStubMarker = GLOBAL_CODEX_EXEC_STUB_MARKER,
            shellQuote = ::shellQuote
        )
    }

    fun prepareLaunchConfiguration(context: Context? = null): CodexLaunchConfiguration {
        val codexHome = File(Environment.HOME, ".codex").apply { mkdirs() }
        val envFile = File(codexHome, CODEX_ENV_FILE_NAME)
        val configFile = File(codexHome, "config.toml")
        val authFile = File(codexHome, "auth.json")
        val export = resolveExportSource(context)
        val envFileContent = export.environmentVariables
            .takeIf { it.isNotEmpty() }
            ?.let { environmentVariables ->
                buildString {
                    appendLine(MANAGED_CONFIG_MARKER)
                    environmentVariables.forEach { (key, value) ->
                        appendLine("export $key=${shellQuote(value)}")
                    }
                }
            }

        when {
            envFileContent != null -> envFile.writeText(envFileContent)
            envFile.exists() -> envFile.delete()
        }

        when {
            export.configTomlContent != null && (!configFile.exists() || configFile.isManagedCodexConfig()) -> {
                configFile.writeText(export.configTomlContent)
            }
            export.configTomlContent == null && configFile.isManagedCodexConfig() -> {
                configFile.delete()
            }
        }

        if (export.authJsonContent != null) {
            authFile.writeText(export.authJsonContent)
        }

        return CodexLaunchConfiguration(
            shellSetup = buildString {
                appendLine("export CODEX_HOME=${shellQuote(codexHome.absolutePath)}")
                append("if [ -f ${shellQuote(envFile.absolutePath)} ]; then . ${shellQuote(envFile.absolutePath)}; fi")
            },
            cliConfigFlags = export.cliConfigFlags,
            cliConfigArgs = export.cliConfigArgs,
            environmentVariables = buildMap {
                put("CODEX_HOME", codexHome.absolutePath)
                putAll(export.environmentVariables)
            }
        )
    }

    private fun File.hasBrokenCodexLauncherTree(): Boolean {
        if (!isFile) {
            return false
        }
        val filesToInspect = linkedSetOf(absoluteFile)
        val resolvedFile = runCatching { canonicalFile }.getOrNull()
        if (resolvedFile != null) {
            filesToInspect += resolvedFile
            resolvedFile.parentFile?.let { parent ->
                filesToInspect += File(parent, "codex")
                filesToInspect += File(parent, "codex-exec")
            }
        }
        return filesToInspect.any { candidate ->
            candidate.isFile && candidate.readFirstLineSafely() in BROKEN_ENV_SHEBANGS
        }
    }

    private fun File.needsCodexLauncherRepair(): Boolean {
        return hasBrokenCodexLauncherTree() || isRecursiveGlobalCodexStub()
    }

    private fun File.isRecursiveGlobalCodexStub(): Boolean {
        if (!isFile || parentFile?.absolutePath != Environment.BIN_DIR.absolutePath) {
            return false
        }
        val requiredMarker = when (name) {
            EXECUTABLE_NAME -> GLOBAL_CODEX_STUB_MARKER
            "codex-exec" -> GLOBAL_CODEX_EXEC_STUB_MARKER
            else -> return false
        }
        return try {
            bufferedReader().use { reader ->
                val content = buildString {
                    repeat(80) {
                        val line = reader.readLine() ?: return@use false
                        appendLine(line)
                    }
                }
                content.contains(requiredMarker) && content.contains(NPM_STUB_MARKER)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun File.readFirstLineSafely(): String? {
        return try {
            inputStream().bufferedReader().use { it.readLine() }
        } catch (_: Exception) {
            null
        }
    }

    private data class CodexExportSource(
        val cliConfigFlags: String = "",
        val cliConfigArgs: List<String> = emptyList(),
        val configTomlContent: String? = null,
        val environmentVariables: Map<String, String> = emptyMap(),
        val authJsonContent: String? = null
    )

    private fun resolveExportSource(context: Context?): CodexExportSource {
        val codexSettings = CodexCliConfig.getSettings()
        if (codexSettings.isValid) {
            return buildCodexExportSource(codexSettings)
        }

        val customProfile = CustomProviderConfig.getActiveProfile()
        if (customProfile?.isValid == true && customProfile.apiType == CustomProviderApiType.OPENAI_RESPONSES) {
            val versionedBaseUrl = versionedBaseUrl(customProfile.normalizedBaseUrl)
            val cliConfigArgs = listOf(
                "--config", "model_provider=${tomlString(CUSTOM_PROVIDER_ID)}",
                "--config", "model=${tomlString(customProfile.modelId)}",
                "--config", "model_providers.$CUSTOM_PROVIDER_ID=${buildCustomProviderInlineToml(versionedBaseUrl)}"
            )
            val cliFlags = cliConfigArgs.joinToString(" ") { shellQuote(it) }
            val configToml = buildString {
                appendLine(MANAGED_CONFIG_MARKER)
                appendLine("model_provider = ${tomlString(CUSTOM_PROVIDER_ID)}")
                appendLine("model = ${tomlString(customProfile.modelId)}")
                appendLine()
                appendLine("[model_providers.$CUSTOM_PROVIDER_ID]")
                appendLine("name = ${tomlString("Android Code Studio Custom")}")
                appendLine("base_url = ${tomlString(versionedBaseUrl)}")
                appendLine("env_key = ${tomlString(CUSTOM_PROVIDER_ENV_KEY)}")
                appendLine("wire_api = ${tomlString("responses")}")
            }
            return CodexExportSource(
                cliConfigFlags = cliFlags,
                cliConfigArgs = cliConfigArgs,
                configTomlContent = configToml,
                environmentVariables = mapOf(CUSTOM_PROVIDER_ENV_KEY to customProfile.apiKey)
            )
        }

        val openAiKey = ApiKey.getOpenAIApiKey().trim()
        if (openAiKey.isNotBlank()) {
            val preferredModel = context
                ?.let(::Agents)
                ?.takeIf { it.getProvider() == "openai" }
                ?.getAgent()
                .orEmpty()
                .trim()
            val cliConfigArgs = buildList {
                add("--config")
                add("model_provider=${tomlString("openai")}")
                if (preferredModel.isNotBlank()) {
                    add("--config")
                    add("model=${tomlString(preferredModel)}")
                }
            }
            val cliFlags = cliConfigArgs.joinToString(" ") { shellQuote(it) }
            val configToml = buildString {
                appendLine(MANAGED_CONFIG_MARKER)
                appendLine("model_provider = ${tomlString("openai")}")
                if (preferredModel.isNotBlank()) {
                    appendLine("model = ${tomlString(preferredModel)}")
                }
            }
            val authJson = JSONObject()
                .put("auth_mode", "apikey")
                .put("OPENAI_API_KEY", openAiKey)
                .put("tokens", JSONObject.NULL)
                .put("last_refresh", JSONObject.NULL)
                .toString(2)
            return CodexExportSource(
                cliConfigFlags = cliFlags,
                cliConfigArgs = cliConfigArgs,
                configTomlContent = configToml,
                environmentVariables = mapOf(CODEX_OPENAI_ENV_KEY to openAiKey),
                authJsonContent = authJson
            )
        }

        return CodexExportSource()
    }

    private fun buildCodexExportSource(settings: CodexCliSettings): CodexExportSource {
        val inlineProviderToml = buildCodexProviderInlineToml(settings)
        val cliConfigArgs = buildList {
            add("--config")
            add("model_provider=${tomlString(settings.normalizedProviderId)}")
            add("--config")
            add("model=${tomlString(settings.model.trim())}")
            add("--config")
            add("review_model=${tomlString(settings.resolvedReviewModel)}")
            add("--config")
            add("model_reasoning_effort=${tomlString(settings.reasoningEffort.value)}")
            add("--config")
            add("model_context_window=${settings.contextWindow}")
            add("--config")
            add("model_auto_compact_token_limit=${settings.autoCompactTokenLimit}")
            add("--config")
            add("model_providers.${settings.normalizedProviderId}=$inlineProviderToml")
        }
        val cliFlags = cliConfigArgs.joinToString(" ") { shellQuote(it) }

        val configToml = buildString {
            appendLine(MANAGED_CONFIG_MARKER)
            appendLine("model_provider = ${tomlString(settings.normalizedProviderId)}")
            appendLine("model = ${tomlString(settings.model.trim())}")
            appendLine("review_model = ${tomlString(settings.resolvedReviewModel)}")
            appendLine("model_reasoning_effort = ${tomlString(settings.reasoningEffort.value)}")
            appendLine("model_context_window = ${settings.contextWindow}")
            appendLine("model_auto_compact_token_limit = ${settings.autoCompactTokenLimit}")
            appendLine()
            appendLine("[model_providers.${settings.normalizedProviderId}]")
            appendLine("name = ${tomlString(settings.resolvedProviderName)}")
            settings.normalizedBaseUrl.takeIf { it.isNotBlank() }?.let { baseUrl ->
                appendLine("base_url = ${tomlString(baseUrl)}")
            }
            if (settings.usesAuthJson) {
                appendLine("requires_openai_auth = true")
            } else {
                appendLine("env_key = ${tomlString(CODEX_OPENAI_ENV_KEY)}")
            }
            appendLine("wire_api = ${tomlString("responses")}")
        }

        val authJson = if (settings.usesAuthJson) {
            JSONObject()
                .put("auth_mode", "apikey")
                .put(CODEX_OPENAI_ENV_KEY, settings.apiKey.trim())
                .put("tokens", JSONObject.NULL)
                .put("last_refresh", JSONObject.NULL)
                .toString(2)
        } else {
            null
        }

        return CodexExportSource(
            cliConfigFlags = cliFlags,
            cliConfigArgs = cliConfigArgs,
            configTomlContent = configToml,
            environmentVariables = if (settings.usesAuthJson) {
                emptyMap()
            } else {
                mapOf(CODEX_OPENAI_ENV_KEY to settings.apiKey.trim())
            },
            authJsonContent = authJson
        )
    }

    private fun versionedBaseUrl(normalizedBaseUrl: String): String {
        val baseUrl = normalizedBaseUrl.trim().removeSuffix("/")
        if (baseUrl.isBlank()) {
            return ""
        }
        return if (baseUrl.endsWith("/v1")) {
            baseUrl
        } else {
            "$baseUrl/v1"
        }
    }

    private fun buildCustomProviderInlineToml(versionedBaseUrl: String): String {
        return buildString {
            append("{ ")
            append("name = ${tomlString("Android Code Studio Custom")}, ")
            append("base_url = ${tomlString(versionedBaseUrl)}, ")
            append("env_key = ${tomlString(CUSTOM_PROVIDER_ENV_KEY)}, ")
            append("wire_api = ${tomlString("responses")} ")
            append("}")
        }
    }

    private fun buildCodexProviderInlineToml(settings: CodexCliSettings): String {
        return buildString {
            append("{ ")
            append("name = ${tomlString(settings.resolvedProviderName)}, ")
            settings.normalizedBaseUrl.takeIf { it.isNotBlank() }?.let { baseUrl ->
                append("base_url = ${tomlString(baseUrl)}, ")
            }
            if (settings.usesAuthJson) {
                append("requires_openai_auth = true, ")
            } else {
                append("env_key = ${tomlString(CODEX_OPENAI_ENV_KEY)}, ")
            }
            append("wire_api = ${tomlString("responses")} ")
            append("}")
        }
    }

    private fun tomlString(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\""
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun File.isManagedCodexConfig(): Boolean {
        return try {
            isFile && bufferedReader().use { reader ->
                reader.readLine()?.contains(MANAGED_CONFIG_MARKER) == true
            }
        } catch (_: Exception) {
            false
        }
    }
}
