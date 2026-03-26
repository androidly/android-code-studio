/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.preferences

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.custom.CustomProviderApiType
import com.tom.rv2ide.artificial.agents.custom.CustomProviderConfig
import com.tom.rv2ide.artificial.agents.custom.CustomProviderProfile
import com.tom.rv2ide.artificial.agents.external.CodexCliConfig
import com.tom.rv2ide.artificial.agents.external.CodexCliSettings
import com.tom.rv2ide.artificial.agents.external.CodexTermuxBridge
import com.tom.rv2ide.artificial.agents.external.ExternalEngineConfig
import com.tom.rv2ide.artificial.dialogs.CodexCliConfigDialog
import com.tom.rv2ide.artificial.dialogs.CustomProviderConfigDialog
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import com.tom.rv2ide.preferences.internal.prefManager
import com.tom.rv2ide.resources.R.string
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/** * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
@Parcelize
class AIAgentPreferencesScreen(
    override val key: String = "idepref_ai_agent",
    override val title: Int = string.ai_agent_title,
    override val summary: Int? = string.ai_agent_description,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(AIAgentConfig())
  }
}

private interface ManagedAiPreference {
  fun refresh()
  fun setEnabled(enabled: Boolean)
}

private data class ProviderOption(val id: String, @StringRes val labelRes: Int)

private val providerOptions =
    listOf(
        ProviderOption("gemini", R.string.ai_assistant_provider_gemini),
        ProviderOption("openai", R.string.ai_assistant_provider_openai),
        ProviderOption("claude", R.string.ai_assistant_provider_claude),
        ProviderOption("deepseek", R.string.ai_assistant_provider_deepseek),
        ProviderOption("grok", R.string.ai_assistant_provider_grok),
        ProviderOption("localllm", R.string.ai_assistant_provider_local_llm),
        ProviderOption("external", R.string.ai_assistant_provider_codex_cli),
        ProviderOption("custom", R.string.ai_assistant_provider_custom),
    )

@Parcelize
private class AIAgentConfig(
    override val key: String = "idepref_ai_agent_config",
    override val title: Int = string.ai_agent_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  @IgnoredOnParcel private var providerPref: ProviderSelectionPreference? = null
  @IgnoredOnParcel private var modelPref: ModelSelectionPreference? = null
  @IgnoredOnParcel private var customProfilePref: CustomProfilePreference? = null
  @IgnoredOnParcel private var externalEnginePref: ExternalEnginePreference? = null
  @IgnoredOnParcel private var codexCliPref: CodexCliPreference? = null
  @IgnoredOnParcel private var installCodexPref: InstallCodexTermuxPreference? = null
  @IgnoredOnParcel private var addCustomProfilePref: AddCustomProfilePreference? = null
  @IgnoredOnParcel private var editCustomProfilePref: EditCustomProfilePreference? = null
  @IgnoredOnParcel private var deleteCustomProfilePref: DeleteCustomProfilePreference? = null
  @IgnoredOnParcel private var autoSwitchPref: AutoSwitchPreference? = null
  @IgnoredOnParcel private var toolExecutionPref: ToolExecutionPreference? = null
  @IgnoredOnParcel private var geminiApiKeyPref: GeminiApiKey? = null
  @IgnoredOnParcel private var deepseekApiKeyPref: DeepseekApiKey? = null
  @IgnoredOnParcel private var openAIApiKeyPref: OpenAIApiKey? = null
  @IgnoredOnParcel private var anthropicApiKeyPref: AnthropicApiKey? = null
  @IgnoredOnParcel private var grokApiKeyPref: GrokApiKey? = null

  init {
    val aiAgentEnabled = AIAgentEnabled { isEnabled -> updatePreferencesState(isEnabled) }

    providerPref = ProviderSelectionPreference { refreshManagedPreferences() }
    modelPref = ModelSelectionPreference { refreshManagedPreferences() }
    customProfilePref = CustomProfilePreference { refreshManagedPreferences() }
    externalEnginePref = ExternalEnginePreference { refreshManagedPreferences() }
    codexCliPref = CodexCliPreference { refreshManagedPreferences() }
    installCodexPref = InstallCodexTermuxPreference { refreshManagedPreferences() }
    addCustomProfilePref = AddCustomProfilePreference { refreshManagedPreferences() }
    editCustomProfilePref = EditCustomProfilePreference { refreshManagedPreferences() }
    deleteCustomProfilePref = DeleteCustomProfilePreference { refreshManagedPreferences() }
    autoSwitchPref = AutoSwitchPreference()
    toolExecutionPref = ToolExecutionPreference()
    geminiApiKeyPref = GeminiApiKey()
    deepseekApiKeyPref = DeepseekApiKey()
    openAIApiKeyPref = OpenAIApiKey()
    anthropicApiKeyPref = AnthropicApiKey()
    grokApiKeyPref = GrokApiKey()

    addPreference(aiAgentEnabled)
    addPreference(autoSwitchPref!!)
    addPreference(toolExecutionPref!!)
    addPreference(providerPref!!)
    addPreference(modelPref!!)
    addPreference(customProfilePref!!)
    addPreference(externalEnginePref!!)
    addPreference(codexCliPref!!)
    addPreference(installCodexPref!!)
    addPreference(addCustomProfilePref!!)
    addPreference(editCustomProfilePref!!)
    addPreference(deleteCustomProfilePref!!)
    addPreference(geminiApiKeyPref!!)
    addPreference(deepseekApiKeyPref!!)
    addPreference(openAIApiKeyPref!!)
    addPreference(anthropicApiKeyPref!!)
    addPreference(grokApiKeyPref!!)
  }

  private fun refreshManagedPreferences() {
    managedPreferences().forEach { it.refresh() }
    updatePreferencesState(prefManager.getBoolean("ai_agent_enabled", false))
  }

  private fun updatePreferencesState(isEnabled: Boolean) {
    managedPreferences().forEach { it.setEnabled(isEnabled) }
    updateApiKeyPreferencesState(isEnabled)
  }

  private fun managedPreferences(): List<ManagedAiPreference> {
    return listOfNotNull(
        providerPref,
        modelPref,
        customProfilePref,
        externalEnginePref,
        codexCliPref,
        installCodexPref,
        addCustomProfilePref,
        editCustomProfilePref,
        deleteCustomProfilePref,
        autoSwitchPref,
        toolExecutionPref,
    )
  }

  private fun updateApiKeyPreferencesState(isEnabled: Boolean) {
    geminiApiKeyPref?.setEnabled(isEnabled)
    deepseekApiKeyPref?.setEnabled(isEnabled)
    openAIApiKeyPref?.setEnabled(isEnabled)
    anthropicApiKeyPref?.setEnabled(isEnabled)
    grokApiKeyPref?.setEnabled(isEnabled)
  }
}


@Parcelize
private class AIAgentEnabled(
    override val key: String = "ai_agent_enabled",
    override val title: Int = R.string.ai_agent_enable,
    @IgnoredOnParcel private val onStateChanged: ((Boolean) -> Unit)? = null,
) :
    SwitchPreference(
        setValue = { isEnabled ->
          prefManager.putBoolean("ai_agent_enabled", isEnabled)
          onStateChanged?.invoke(isEnabled)
        },
        getValue = { prefManager.getBoolean("ai_agent_enabled", false) },
    ) {

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "ai_agent_enabled"
      title = context.getString(R.string.ai_agent_enable)
      summary = context.getString(R.string.ai_agent_enable_summary)
    }
  }
}

@Parcelize
private class AutoSwitchPreference(
    override val key: String = "ai_agent_auto_switch",
    override val title: Int = string.ai_agent_title,
) : SwitchPreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: androidx.preference.SwitchPreference? = null

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context) as androidx.preference.SwitchPreference
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_auto_switch_title)
      summary = context.getString(R.string.ai_assistant_auto_switch_summary)
      isChecked = ProviderSwitchDialog(context).isAutoSwitchEnabled()
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
    ProviderSwitchDialog(preference.context).setAutoSwitch(newValue as? Boolean ?: false)
    return true
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.isChecked = ProviderSwitchDialog(pref.context).isAutoSwitchEnabled()
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class ToolExecutionPreference(
    override val key: String = "ai_agent_tool_execution",
    override val title: Int = string.ai_agent_title,
) : SwitchPreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: androidx.preference.SwitchPreference? = null

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context) as androidx.preference.SwitchPreference
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_tool_execution_title)
      summary = context.getString(R.string.ai_assistant_tool_execution_summary)
      isChecked = AIPermissionManager(context).isToolExecutionEnabled()
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
    AIPermissionManager(preference.context).setToolExecutionEnabled(newValue as? Boolean ?: false)
    return true
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.isChecked = AIPermissionManager(pref.context).isToolExecutionEnabled()
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class ProviderSelectionPreference(
    override val key: String = "ai_agent_provider_selector",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_provider_title)
      summary = buildSummary(context)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val currentProvider = Agents(context).getProvider()
    val selectedIndex = providerOptions.indexOfFirst { it.id == currentProvider }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_assistant_select_provider_title)
        .setSingleChoiceItems(
            providerOptions.map { context.getString(it.labelRes) }.toTypedArray(),
            selectedIndex,
        ) { dialog, which ->
          dialog.dismiss()
          val option = providerOptions[which]
          if (option.id == "custom") {
            val activeProfile = CustomProviderConfig.getActiveProfile()
            when {
              activeProfile == null ->
                  showCustomProviderDialog(context, createNew = true) { savedProfile ->
                    applyProviderSelection(context, "custom", savedProfile.modelId)
                    onChanged?.invoke()
                    refresh()
                  }
              !activeProfile.isValid ->
                  showCustomProviderDialog(context, profileId = activeProfile.id) { savedProfile ->
                    applyProviderSelection(context, "custom", savedProfile.modelId)
                    onChanged?.invoke()
                    refresh()
                  }
              else -> {
                applyProviderSelection(context, "custom", activeProfile.modelId)
                onChanged?.invoke()
                refresh()
              }
            }
          } else if (option.id == "external") {
            CodexTermuxBridge.ensureManagedPreset(context = context)
            applyProviderSelection(context, "external", preferredExternalModel())
            onChanged?.invoke()
            refresh()
          } else {
            applyProviderSelection(context, option.id)
            onChanged?.invoke()
            refresh()
          }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
    return true
  }

  private fun buildSummary(context: Context): String {
    val providerId = Agents(context).getProvider()
    val activeProfile = CustomProviderConfig.getActiveProfile()
    return when {
      providerId == "custom" && activeProfile != null ->
        context.getString(
            R.string.ai_assistant_current_value_with_detail,
            providerDisplayName(context, providerId),
            activeProfile.name,
        )
      providerId == "external" && ExternalEngineConfig.hasValidConfig() ->
        context.getString(
            R.string.ai_assistant_current_value_with_detail,
            providerDisplayName(context, providerId),
            preferredExternalModel(),
        )
      else -> context.getString(R.string.ai_assistant_current_value, providerDisplayName(context, providerId))
    }
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary = buildSummary(pref.context)
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class ModelSelectionPreference(
    override val key: String = "ai_agent_model_selector",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_model_title)
      summary = buildSummary(context)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val agents = Agents(context)
    val providerId = agents.getProvider()
    if (providerId == "external" && !ExternalEngineConfig.hasValidConfig()) {
      CodexTermuxBridge.ensureManagedPreset(context = context)
      applyProviderSelection(context, "external", preferredExternalModel())
      onChanged?.invoke()
      refresh()
      return true
    }
    if (providerId == "custom") {
      val activeProfile = CustomProviderConfig.getActiveProfile()
      if (activeProfile == null) {
        showCustomProviderDialog(context, createNew = true) { onChanged?.invoke(); refresh() }
        return true
      }
      if (agents.getModelsForProvider(providerId).isEmpty()) {
        showCustomProviderDialog(context, profileId = activeProfile.id) { savedProfile ->
          applyProviderSelection(context, "custom", savedProfile.modelId)
          onChanged?.invoke()
          refresh()
        }
        return true
      }
    }

    val models = agents.getModelsForProvider(providerId).filter { it.isNotBlank() }
    if (models.isEmpty()) {
      showToast(
          context,
          context.getString(
              R.string.ai_assistant_no_models_configured,
              providerDisplayName(context, providerId),
          ),
      )
      return true
    }

    val currentModel = agents.getAgent()
    val selectedIndex = models.indexOfFirst { it == currentModel }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_assistant_select_model_title)
        .setSingleChoiceItems(models.toTypedArray(), selectedIndex) { dialog, which ->
          dialog.dismiss()
          agents.setAgent(models[which])
          onChanged?.invoke()
          refresh()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
    return true
  }

  private fun buildSummary(context: Context): String {
    return Agents(context)
        .getAgent()
        .takeIf { it.isNotBlank() }
        ?.let { context.getString(R.string.ai_assistant_current_value, it) }
        ?: context.getString(R.string.ai_assistant_no_model_selected)
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary = buildSummary(pref.context)
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class CustomProfilePreference(
    override val key: String = "ai_agent_custom_profile_selector",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_active_custom_profile_title)
      summary = buildSummary(context)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val profiles = CustomProviderConfig.getProfiles()
    if (profiles.isEmpty()) {
      showCustomProviderDialog(context, createNew = true) { onChanged?.invoke(); refresh() }
      return true
    }

    val activeId = CustomProviderConfig.getActiveProfileId()
    val selectedIndex = profiles.indexOfFirst { it.id == activeId }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_assistant_select_active_custom_profile_title)
        .setSingleChoiceItems(profiles.map { it.name }.toTypedArray(), selectedIndex) { dialog, which ->
          dialog.dismiss()
          val selectedProfile = profiles[which]
          CustomProviderConfig.setActiveProfile(selectedProfile.id)
          if (Agents(context).getProvider() == "custom") {
            applyProviderSelection(context, "custom", selectedProfile.modelId)
          }
          onChanged?.invoke()
          refresh()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
    return true
  }

  private fun buildSummary(context: Context): String {
    val activeProfile = CustomProviderConfig.getActiveProfile()
    return if (activeProfile == null) {
      context.getString(R.string.ai_assistant_no_custom_profile_configured)
    } else {
      listOfNotNull(
              activeProfile.name.takeIf { it.isNotBlank() },
              apiTypeDisplayName(context, activeProfile.apiType),
              activeProfile.modelId.takeIf { it.isNotBlank() },
          )
          .joinToString(" • ")
    }
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary = buildSummary(pref.context)
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled && CustomProviderConfig.hasProfiles()
  }
}

@Parcelize
private class ExternalEnginePreference(
    override val key: String = "ai_agent_external_engine_config",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_codex_bridge_title)
      summary = buildSummary(context)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    CodexTermuxBridge.ensureManagedPreset(context = preference.context)
    if (Agents(preference.context).getProvider() == "external") {
      applyProviderSelection(preference.context, "external", preferredExternalModel())
    }
    onChanged?.invoke()
    refresh()
    showToast(preference.context, preference.context.getString(R.string.ai_assistant_codex_preset_applied))
    return true
  }

  private fun buildSummary(context: Context): String {
    val codexStatus = CodexTermuxBridge.status()
    if (!ExternalEngineConfig.hasValidConfig()) {
      return codexStatus.summaryText()
    }
    return listOf(
        codexStatus.summaryText(),
        context.getString(R.string.ai_assistant_codex_bridge_active),
    ).joinToString(" • ")
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary = buildSummary(pref.context)
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class CodexCliPreference(
    override val key: String = "ai_agent_external_engine_codex_config",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_codex_cli_config_title)
      summary = buildSummary(context)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    showCodexCliDialog(preference.context) {
      onChanged?.invoke()
      refresh()
    }
    return true
  }

  private fun buildSummary(context: Context): String {
    val settings = CodexCliConfig.getSettings()
    return if (settings.isValid) {
      settings.summaryText()
    } else {
      context.getString(R.string.ai_assistant_codex_cli_config_summary)
    }
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary = buildSummary(pref.context)
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class InstallCodexTermuxPreference(
    override val key: String = "ai_agent_external_engine_install_codex",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_install_codex_cli_title)
      summary = CodexTermuxBridge.status().summaryText()
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    CodexTermuxBridge.installAndConfigure(
        context = preference.context,
        selectProvider = true
    )
    onChanged?.invoke()
    refresh()
    showToast(preference.context, preference.context.getString(R.string.ai_assistant_install_codex_cli_opened))
    return true
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary = CodexTermuxBridge.status().summaryText()
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class AddCustomProfilePreference(
    override val key: String = "ai_agent_custom_profile_add",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_add_custom_profile_title)
      summary =
          context.getString(
              if (CustomProviderConfig.hasProfiles()) {
                R.string.ai_assistant_add_custom_profile_summary_more
              } else {
                R.string.ai_assistant_add_custom_profile_summary_first
              }
          )
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    showCustomProviderDialog(context, createNew = true) { savedProfile ->
      if (Agents(context).getProvider() == "custom") {
        applyProviderSelection(context, "custom", savedProfile.modelId)
      }
      onChanged?.invoke()
      refresh()
    }
    return true
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary =
        pref.context.getString(
            if (CustomProviderConfig.hasProfiles()) {
              R.string.ai_assistant_add_custom_profile_summary_more
            } else {
              R.string.ai_assistant_add_custom_profile_summary_first
            }
        )
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class EditCustomProfilePreference(
    override val key: String = "ai_agent_custom_profile_edit",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_edit_custom_profile_title)
      summary =
          CustomProviderConfig.getActiveProfile()?.let {
            context.getString(R.string.ai_assistant_edit_custom_profile_summary, it.name)
          } ?: context.getString(R.string.ai_assistant_no_custom_profile_to_edit)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val activeProfile = CustomProviderConfig.getActiveProfile() ?: return true
    showCustomProviderDialog(context, profileId = activeProfile.id) { savedProfile ->
      if (Agents(context).getProvider() == "custom") {
        applyProviderSelection(context, "custom", savedProfile.modelId)
      }
      onChanged?.invoke()
      refresh()
    }
    return true
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary =
        CustomProviderConfig.getActiveProfile()?.let {
          pref.context.getString(R.string.ai_assistant_edit_custom_profile_summary, it.name)
        } ?: pref.context.getString(R.string.ai_assistant_no_custom_profile_to_edit)
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled && CustomProviderConfig.hasProfiles()
  }
}

@Parcelize
private class DeleteCustomProfilePreference(
    override val key: String = "ai_agent_custom_profile_delete",
    override val title: Int = string.ai_agent_title,
    @IgnoredOnParcel private val onChanged: (() -> Unit)? = null,
) : BasePreference(), ManagedAiPreference {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context)
  }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    preference = pref
    return pref.apply {
      title = context.getString(R.string.ai_assistant_delete_custom_profile_title)
      summary =
          CustomProviderConfig.getActiveProfile()?.let {
            context.getString(R.string.ai_assistant_delete_custom_profile_summary, it.name)
          } ?: context.getString(R.string.ai_assistant_no_custom_profile_to_delete)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val activeProfile = CustomProviderConfig.getActiveProfile() ?: return true
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_assistant_delete_custom_profile_dialog_title)
        .setMessage(context.getString(R.string.ai_assistant_delete_custom_profile_dialog_message, activeProfile.name))
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.delete) { _, _ ->
          if (CustomProviderConfig.deleteProfile(activeProfile.id)) {
            CustomProviderConfig.getActiveProfile()?.takeIf { Agents(context).getProvider() == "custom" }?.let {
              applyProviderSelection(context, "custom", it.modelId)
            }
            onChanged?.invoke()
            refresh()
          } else {
            showToast(context, context.getString(R.string.ai_assistant_delete_custom_profile_failed))
          }
        }
        .show()
    return true
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary =
        CustomProviderConfig.getActiveProfile()?.let {
          pref.context.getString(R.string.ai_assistant_delete_custom_profile_summary, it.name)
        } ?: pref.context.getString(R.string.ai_assistant_no_custom_profile_to_delete)
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled && CustomProviderConfig.hasProfiles()
  }
}

private fun providerDisplayName(context: Context, providerId: String): String {
  return providerOptions.firstOrNull { it.id == providerId }?.let { context.getString(it.labelRes) }
      ?: providerId.uppercase()
}

private fun apiTypeDisplayName(context: Context, apiType: CustomProviderApiType): String {
  return when (apiType) {
    CustomProviderApiType.OPENAI_CHAT ->
        context.getString(R.string.ai_assistant_custom_provider_api_type_openai_chat)
    CustomProviderApiType.OPENAI_RESPONSES ->
        context.getString(R.string.ai_assistant_custom_provider_api_type_openai_responses)
    CustomProviderApiType.CLAUDE_MESSAGES ->
        context.getString(R.string.ai_assistant_custom_provider_api_type_claude_messages)
  }
}

private fun applyProviderSelection(context: Context, providerId: String, preferredModel: String? = null) {
  val agents = Agents(context)
  agents.setProvider(providerId)
  val targetModel =
      if (providerId == "custom") {
        preferredModel?.takeIf { it.isNotBlank() }
            ?: CustomProviderConfig.getModelId().ifBlank { CustomProviderConfig.getAvailableModels().firstOrNull().orEmpty() }
      } else if (providerId == "external") {
        preferredModel?.takeIf { it.isNotBlank() }
            ?: preferredExternalModel()
      } else {
        preferredModel?.takeIf { agents.isValidModelForProvider(it, providerId) }
            ?: agents.getModelsForProvider(providerId).firstOrNull().orEmpty()
      }
  if (targetModel.isNotBlank()) {
    agents.setAgent(targetModel)
  }
}

private fun showCustomProviderDialog(
    context: Context,
    profileId: String? = null,
    createNew: Boolean = false,
    onSave: (CustomProviderProfile) -> Unit,
) {
  val activity = context.findFragmentActivity()
  if (activity == null) {
    showToast(context, context.getString(R.string.ai_assistant_open_custom_provider_editor_failed))
    return
  }
  CustomProviderConfigDialog(profileId = profileId, createNew = createNew, onSave = onSave)
      .show(activity.supportFragmentManager, "CustomProviderConfigDialog")
}

private fun showCodexCliDialog(
    context: Context,
    onSave: (CodexCliSettings) -> Unit,
) {
  val activity = context.findFragmentActivity()
  if (activity == null) {
    showToast(context, context.getString(R.string.ai_assistant_open_codex_editor_failed))
    return
  }
  CodexCliConfigDialog(onSave = onSave)
      .show(activity.supportFragmentManager, "CodexCliConfigDialog")
}

private fun preferredExternalModel(): String {
  return CodexCliConfig.getModelId().ifBlank { ExternalEngineConfig.getModelLabel() }
}

private fun showToast(context: Context, message: String) {
  Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? {
  return when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
  }
}

private fun showApiKeyDialog(
    context: Context,
    preferenceKey: String,
    @StringRes hintRes: Int,
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    onSaved: (String) -> Unit,
) {
  val editText =
      android.widget.EditText(context).apply {
        setText(prefManager.getString(preferenceKey, ""))
        hint = context.getString(hintRes)
      }

  MaterialAlertDialogBuilder(context)
      .setTitle(titleRes)
      .setMessage(messageRes)
      .setView(editText)
      .setPositiveButton(R.string.save) { _, _ ->
        val apiKey = editText.text.toString().trim()
        prefManager.putString(preferenceKey, apiKey)
        onSaved(apiKey)
      }
      .setNegativeButton(R.string.cancel, null)
      .show()
}

private fun apiKeySummary(context: Context, preferenceKey: String): String {
  val apiKey = prefManager.getString(preferenceKey, "")
  return if (apiKey.isBlank()) {
    context.getString(R.string.ai_assistant_api_key_click_to_set)
  } else {
    context.getString(R.string.ai_assistant_api_key_masked_summary, apiKey.take(8))
  }
}


@Parcelize
private class GrokApiKey(
    override val key: String = "ai_agent_grok_api_key",
    override val title: Int = R.string.ai_agent_grok_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_grok_api_key"
          title = context.getString(R.string.ai_agent_grok_api_key)
          summary = apiKeySummary(context, key)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    showApiKeyDialog(
        context = context,
        preferenceKey = key,
        hintRes = R.string.ai_assistant_api_key_hint_grok,
        titleRes = R.string.ai_assistant_api_key_dialog_title_grok,
        messageRes = R.string.ai_assistant_api_key_dialog_message_grok,
    ) {
      preference.summary = apiKeySummary(context, key)
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class GeminiApiKey(
    override val key: String = "ai_agent_gemini_api_key",
    override val title: Int = R.string.ai_agent_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_gemini_api_key"
          title = context.getString(R.string.ai_agent_api_key)
          summary = apiKeySummary(context, key)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    showApiKeyDialog(
        context = context,
        preferenceKey = key,
        hintRes = R.string.ai_assistant_api_key_hint_gemini,
        titleRes = R.string.ai_assistant_api_key_dialog_title_gemini,
        messageRes = R.string.ai_assistant_api_key_dialog_message_gemini,
    ) {
      preference.summary = apiKeySummary(context, key)
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class DeepseekApiKey(
    override val key: String = "ai_agent_deepseek_api_key",
    override val title: Int = R.string.ai_agent_deepseek_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_deepseek_api_key"
          title = context.getString(R.string.ai_agent_deepseek_api_key)
          summary = apiKeySummary(context, key)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    showApiKeyDialog(
        context = context,
        preferenceKey = key,
        hintRes = R.string.ai_assistant_api_key_hint_deepseek,
        titleRes = R.string.ai_assistant_api_key_dialog_title_deepseek,
        messageRes = R.string.ai_assistant_api_key_dialog_message_deepseek,
    ) {
      preference.summary = apiKeySummary(context, key)
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class OpenAIApiKey(
    override val key: String = "ai_agent_openai_api_key",
    override val title: Int = R.string.ai_agent_openai_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_openai_api_key"
          title = context.getString(R.string.ai_agent_openai_api_key)
          summary = apiKeySummary(context, key)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    showApiKeyDialog(
        context = context,
        preferenceKey = key,
        hintRes = R.string.ai_assistant_api_key_hint_openai,
        titleRes = R.string.ai_assistant_api_key_dialog_title_openai,
        messageRes = R.string.ai_assistant_api_key_dialog_message_openai,
    ) {
      preference.summary = apiKeySummary(context, key)
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class AnthropicApiKey(
    override val key: String = "ai_agent_anthropic_api_key",
    override val title: Int = R.string.ai_agent_anthropic_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_anthropic_api_key"
          title = context.getString(R.string.ai_agent_anthropic_api_key)
          summary = apiKeySummary(context, key)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    showApiKeyDialog(
        context = context,
        preferenceKey = key,
        hintRes = R.string.ai_assistant_api_key_hint_anthropic,
        titleRes = R.string.ai_assistant_api_key_dialog_title_anthropic,
        messageRes = R.string.ai_assistant_api_key_dialog_message_anthropic,
    ) {
      preference.summary = apiKeySummary(context, key)
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}
