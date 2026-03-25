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
            agents.setAgent(savedSettings.resolvedDisplayLabel())
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
            agents.setAgent(managedSettings.resolvedDisplayLabel())
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
        val expectedMainRepo = TermuxPackageRepository.getEffectiveMainRepo(context)
        val expectedMainSources = TermuxPackageRepository.getExpectedMainSources(context).trimEnd()
        val npmRegistry = TermuxPackageRepository.getEffectiveNpmRegistry(context)
        val expectedDebLines = expectedMainSources
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("deb ") }
            .joinToString("\n")
        return buildString {
            appendLine("prefix_dir=\"${'$'}{PREFIX:-${Environment.PREFIX.absolutePath}}\"")
            appendLine("node_path=\"${'$'}prefix_dir/bin/node\"")
            appendLine("sources_dir=\"${'$'}prefix_dir/etc/apt\"")
            appendLine("sources_list=\"${'$'}sources_dir/sources.list\"")
            appendLine("package_root=\"${'$'}prefix_dir/lib/node_modules/$PACKAGE_NAME\"")
            appendLine("package_bin_dir=\"${'$'}package_root/bin\"")
            appendLine("package_codex_js=\"${'$'}package_bin_dir/codex.js\"")
            appendLine("package_codex_exec_js=\"${'$'}package_bin_dir/codex-exec.js\"")
            appendLine("package_codex_bin=\"${'$'}package_bin_dir/codex\"")
            appendLine("package_codex_exec_bin=\"${'$'}package_bin_dir/codex-exec\"")
            appendLine("codex_path=\"${'$'}prefix_dir/bin/codex\"")
            appendLine("codex_exec_path=\"${'$'}prefix_dir/bin/codex-exec\"")
            appendLine("expected_main_repo=${shellQuote(expectedMainRepo)}")
            appendLine("expected_main_sources=${shellQuote(expectedMainSources)}")
            appendLine("expected_deb_lines=${shellQuote(expectedDebLines)}")
            appendLine("npm_registry=${shellQuote(npmRegistry)}")
            appendLine("file_contains() {")
            appendLine("  candidate_path=\"${'$'}1\"")
            appendLine("  expected_text=\"${'$'}2\"")
            appendLine("  [ -f \"${'$'}candidate_path\" ] || return 1")
            appendLine("  grep -Fq \"${'$'}expected_text\" \"${'$'}candidate_path\" 2>/dev/null")
            appendLine("}")
            appendLine("repair_launcher_shebang() {")
            appendLine("  launcher_path=\"${'$'}1\"")
            appendLine("  if [ -z \"${'$'}launcher_path\" ] || [ ! -f \"${'$'}launcher_path\" ]; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  first_line=$(head -n 1 \"${'$'}launcher_path\" 2>/dev/null || true)")
            appendLine("  case \"${'$'}first_line\" in")
            appendLine("    '#!/usr/bin/env node')")
            appendLine("      echo \"Repairing Node.js shebang in ${'$'}launcher_path\"")
            appendLine("      sed -i \"1s|^#!/usr/bin/env node$|#!${'$'}prefix_dir/bin/node|\" \"${'$'}launcher_path\" || return ${'$'}?")
            appendLine("      chmod 700 \"${'$'}launcher_path\" || return ${'$'}?")
            appendLine("      ;;")
            appendLine("    '#!/usr/bin/env sh')")
            appendLine("      echo \"Repairing shell shebang in ${'$'}launcher_path\"")
            appendLine("      sed -i \"1s|^#!/usr/bin/env sh$|#!/system/bin/sh|\" \"${'$'}launcher_path\" || return ${'$'}?")
            appendLine("      chmod 700 \"${'$'}launcher_path\" || return ${'$'}?")
            appendLine("      ;;")
            appendLine("  esac")
            appendLine("}")
            appendLine("launcher_has_broken_shebang() {")
            appendLine("  launcher_path=\"${'$'}1\"")
            appendLine("  if [ -z \"${'$'}launcher_path\" ] || [ ! -f \"${'$'}launcher_path\" ]; then")
            appendLine("    return 1")
            appendLine("  fi")
            appendLine("  first_line=$(head -n 1 \"${'$'}launcher_path\" 2>/dev/null || true)")
            appendLine("  [ \"${'$'}first_line\" = '#!/usr/bin/env node' ] || [ \"${'$'}first_line\" = '#!/usr/bin/env sh' ]")
            appendLine("}")
            appendLine("repair_launcher_tree() {")
            appendLine("  launcher_path=\"${'$'}1\"")
            appendLine("  if [ -z \"${'$'}launcher_path\" ]; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  repair_launcher_shebang \"${'$'}launcher_path\" || return ${'$'}?")
            appendLine("  resolved_path=$(readlink -f \"${'$'}launcher_path\" 2>/dev/null || true)")
            appendLine("  if [ -n \"${'$'}resolved_path\" ] && [ -f \"${'$'}resolved_path\" ]; then")
            appendLine("    repair_launcher_shebang \"${'$'}resolved_path\" || return ${'$'}?")
            appendLine("    resolved_dir=$(dirname \"${'$'}resolved_path\")")
            appendLine("    repair_launcher_shebang \"${'$'}resolved_dir/codex\" || return ${'$'}?")
            appendLine("    repair_launcher_shebang \"${'$'}resolved_dir/codex-exec\" || return ${'$'}?")
            appendLine("  fi")
            appendLine("}")
            appendLine("launcher_tree_has_broken_shebang() {")
            appendLine("  launcher_path=\"${'$'}1\"")
            appendLine("  if launcher_has_broken_shebang \"${'$'}launcher_path\"; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  resolved_path=$(readlink -f \"${'$'}launcher_path\" 2>/dev/null || true)")
            appendLine("  if [ -z \"${'$'}resolved_path\" ] || [ ! -f \"${'$'}resolved_path\" ]; then")
            appendLine("    return 1")
            appendLine("  fi")
            appendLine("  if launcher_has_broken_shebang \"${'$'}resolved_path\"; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  resolved_dir=$(dirname \"${'$'}resolved_path\")")
            appendLine("  if launcher_has_broken_shebang \"${'$'}resolved_dir/codex\"; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  launcher_has_broken_shebang \"${'$'}resolved_dir/codex-exec\"")
            appendLine("}")
            appendLine("launcher_is_recursive_npm_stub() {")
            appendLine("  launcher_path=\"${'$'}1\"")
            appendLine("  if [ -z \"${'$'}launcher_path\" ] || [ ! -f \"${'$'}launcher_path\" ]; then")
            appendLine("    return 1")
            appendLine("  fi")
            appendLine("  case \"$(basename \"${'$'}launcher_path\")\" in")
            appendLine("    codex)")
            appendLine("      file_contains \"${'$'}launcher_path\" \"$GLOBAL_CODEX_STUB_MARKER\" && file_contains \"${'$'}launcher_path\" \"$NPM_STUB_MARKER\"")
            appendLine("      ;;")
            appendLine("    codex-exec)")
            appendLine("      file_contains \"${'$'}launcher_path\" \"$GLOBAL_CODEX_EXEC_STUB_MARKER\" && file_contains \"${'$'}launcher_path\" \"$NPM_STUB_MARKER\"")
            appendLine("      ;;")
            appendLine("    *)")
            appendLine("      return 1")
            appendLine("      ;;")
            appendLine("  esac")
            appendLine("}")
            appendLine("launcher_needs_repair() {")
            appendLine("  launcher_path=\"${'$'}1\"")
            appendLine("  launcher_has_broken_shebang \"${'$'}launcher_path\" && return 0")
            appendLine("  launcher_is_recursive_npm_stub \"${'$'}launcher_path\"")
            appendLine("}")
            appendLine("write_global_launcher_wrapper() {")
            appendLine("  launcher_name=\"${'$'}1\"")
            appendLine("  entry_path=\"${'$'}2\"")
            appendLine("  target_path=\"${'$'}prefix_dir/bin/${'$'}launcher_name\"")
            appendLine("  if [ ! -f \"${'$'}entry_path\" ]; then")
            appendLine("    echo \"Missing Codex entry point: ${'$'}entry_path\"")
            appendLine("    return 1")
            appendLine("  fi")
            appendLine("  if [ -L \"${'$'}target_path\" ] || [ -f \"${'$'}target_path\" ]; then")
            appendLine("    rm -f \"${'$'}target_path\" || return ${'$'}?")
            appendLine("  fi")
            appendLine("  printf '%s\\n' \\")
            appendLine("    '#!/system/bin/sh' \\")
            appendLine("    'set -eu' \\")
            appendLine("    \"prefix_dir=\\\"${'$'}{PREFIX:-${Environment.PREFIX.absolutePath}}\\\"\" \\")
            appendLine("    \"entry_path=\\\"${'$'}entry_path\\\"\" \\")
            appendLine("    'export PREFIX=\"${'$'}prefix_dir\"' \\")
            appendLine("    'export PATH=\"${'$'}prefix_dir/bin:/system/bin:/system/xbin:${'$'}{PATH:-}\"' \\")
            appendLine("    'exec \"${'$'}prefix_dir/bin/node\" \"${'$'}entry_path\" \"${'$'}@\"' > \"${'$'}target_path\" || return ${'$'}?")
            appendLine("  chmod 700 \"${'$'}target_path\" || return ${'$'}?")
            appendLine("}")
            appendLine("repair_global_codex_launchers() {")
            appendLine("  repair_launcher_tree \"${'$'}package_codex_js\" || return ${'$'}?")
            appendLine("  repair_launcher_tree \"${'$'}package_codex_exec_js\" || return ${'$'}?")
            appendLine("  repair_launcher_tree \"${'$'}package_codex_bin\" || return ${'$'}?")
            appendLine("  repair_launcher_tree \"${'$'}package_codex_exec_bin\" || return ${'$'}?")
            appendLine("  write_global_launcher_wrapper 'codex' \"${'$'}package_codex_js\" || return ${'$'}?")
            appendLine("  write_global_launcher_wrapper 'codex-exec' \"${'$'}package_codex_exec_js\" || return ${'$'}?")
            appendLine("}")
            appendLine("repair_termux_sources() {")
            appendLine("  mkdir -p \"${'$'}sources_dir\" || return ${'$'}?")
            appendLine("  current_deb_lines=''")
            appendLine("  if [ -f \"${'$'}sources_list\" ]; then")
            appendLine("    current_deb_lines=$(grep -E '^[[:space:]]*deb ' \"${'$'}sources_list\" 2>/dev/null | sed 's/[[:space:]]*$//' || true)")
            appendLine("  fi")
            appendLine("  if [ -n \"${'$'}current_deb_lines\" ] && [ \"${'$'}current_deb_lines\" = \"${'$'}expected_deb_lines\" ]; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  echo \"Repairing Termux apt sources to ${'$'}expected_main_repo\"")
            appendLine("  printf '%s\\n' \"${'$'}expected_main_sources\" > \"${'$'}sources_list\" || return ${'$'}?")
            appendLine("  rm -rf \"${'$'}prefix_dir/var/lib/apt/lists\"/* 2>/dev/null || true")
            appendLine("}")
            appendLine("install_status=0")
            appendLine("repair_termux_sources || install_status=${'$'}?")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ]; then")
            appendLine("  pkg update || install_status=${'$'}?")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ]; then")
            appendLine("  pkg upgrade -y || install_status=${'$'}?")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && ! command -v node >/dev/null 2>&1; then")
            appendLine("  if ! pkg install nodejs -y; then")
            appendLine("    echo 'nodejs is unavailable in this repo, trying nodejs-lts instead.'")
            appendLine("    pkg install nodejs-lts -y || install_status=${'$'}?")
            appendLine("  fi")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && ! command -v node >/dev/null 2>&1; then")
            appendLine("  if ! pkg install nodejs-lts -y; then")
            appendLine("    echo 'nodejs-lts is unavailable in this repo after trying nodejs.'")
            appendLine("    install_status=1")
            appendLine("  fi")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && ! command -v npm >/dev/null 2>&1; then")
            appendLine("  pkg install npm -y || install_status=${'$'}?")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && ! command -v npm >/dev/null 2>&1; then")
            appendLine("  echo 'npm is still unavailable after installing Node.js and npm.'")
            appendLine("  install_status=1")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ]; then")
            appendLine("  echo \"Using npm registry: ${'$'}npm_registry\"")
            appendLine("  npm install -g --registry=\"${'$'}npm_registry\" --fetch-retries=4 --fetch-retry-factor=2 --fetch-retry-mintimeout=20000 --fetch-retry-maxtimeout=120000 --fetch-timeout=120000 $PACKAGE_NAME || install_status=${'$'}?")
            appendLine("fi")
            appendLine("hash -r")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && [ ! -x \"${'$'}node_path\" ]; then")
            appendLine("  echo \"Node.js runtime is unavailable at ${'$'}node_path\"")
            appendLine("  install_status=1")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && [ ! -f \"${'$'}package_codex_js\" ]; then")
            appendLine("  echo 'codex.js was not found after npm install.'")
            appendLine("  install_status=1")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && [ ! -f \"${'$'}package_codex_exec_js\" ]; then")
            appendLine("  echo 'codex-exec.js was not found after npm install.'")
            appendLine("  install_status=1")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ]; then")
            appendLine("  repair_global_codex_launchers || install_status=${'$'}?")
            appendLine("  hash -r")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && [ ! -x \"${'$'}codex_path\" ]; then")
            appendLine("  echo 'codex launcher wrapper was not created in the global bin directory.'")
            appendLine("  install_status=1")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && launcher_needs_repair \"${'$'}codex_path\"; then")
            appendLine("  echo 'Codex CLI launcher wrapper is still unsafe after repair.'")
            appendLine("  echo \"Launcher: ${'$'}codex_path\"")
            appendLine("  if [ -n \"${'$'}codex_exec_path\" ]; then")
            appendLine("    echo \"Companion: ${'$'}codex_exec_path\"")
            appendLine("  fi")
            appendLine("  if [ -f \"${'$'}codex_path\" ]; then")
            appendLine("    echo 'First line:'")
            appendLine("    head -n 1 \"${'$'}codex_path\" || true")
            appendLine("  fi")
            appendLine("  install_status=1")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && [ ! -x \"${'$'}codex_exec_path\" ]; then")
            appendLine("  echo 'codex-exec launcher wrapper was not created in the global bin directory.'")
            appendLine("  install_status=1")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ] && launcher_needs_repair \"${'$'}codex_exec_path\"; then")
            appendLine("  echo 'codex-exec launcher wrapper is still unsafe after repair.'")
            appendLine("  echo \"Companion: ${'$'}codex_exec_path\"")
            appendLine("  install_status=1")
            appendLine("fi")
            appendLine("if [ \"${'$'}install_status\" -eq 0 ]; then")
            appendLine("  command -v codex || true")
            appendLine("  command -v codex-exec || true")
            appendLine("  echo \"Node runtime: ${'$'}node_path\"")
            appendLine("  echo 'Launcher shebang:'")
            appendLine("  head -n 1 \"${'$'}codex_path\" || true")
            appendLine("  echo 'Package codex.js shebang:'")
            appendLine("  head -n 1 \"${'$'}package_codex_js\" || true")
            appendLine("  echo 'Bundled codex shebang:'")
            appendLine("  head -n 1 \"${'$'}package_codex_bin\" || true")
            appendLine("  if [ -n \"${'$'}codex_exec_path\" ]; then")
            appendLine("    echo 'Exec shebang:'")
            appendLine("    head -n 1 \"${'$'}codex_exec_path\" || true")
            appendLine("    echo 'Package codex-exec.js shebang:'")
            appendLine("    head -n 1 \"${'$'}package_codex_exec_js\" || true")
            appendLine("    echo 'Bundled codex-exec shebang:'")
            appendLine("    head -n 1 \"${'$'}package_codex_exec_bin\" || true")
            appendLine("  fi")
            appendLine("  echo")
            appendLine("  echo 'Codex CLI for Termux is installed.'")
            appendLine("  echo 'The installer only performed static wrapper repair and did not auto-run Codex.'")
            appendLine("  echo 'Next: run codex login or codex --help manually in this terminal when you are ready.'")
            appendLine("  echo 'If browser auth fails on Termux, try: codex login --device-auth'")
            appendLine("  echo 'You can also export OPENAI_API_KEY and related provider env vars instead of browser login.'")
            appendLine("else")
            appendLine("  echo")
            appendLine("  echo 'Codex CLI installation failed.'")
            appendLine("  echo 'Check the package output above and make sure Node.js/npm are available in this Termux environment.'")
            appendLine("  echo \"Last npm registry: ${'$'}npm_registry\"")
            appendLine("  echo 'If npm failed with ECONNRESET or timeout, switch the NPM registry in Settings -> Termux -> Package repositories and retry.'")
            appendLine("  echo 'After Node.js works, rerun this installer or run: npm install -g --registry=\"$npmRegistry\" $PACKAGE_NAME'")
            appendLine("  install_status=1")
            appendLine("fi")
            append("test \"${'$'}install_status\" -eq 0")
        }
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
