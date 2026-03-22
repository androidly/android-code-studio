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
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.custom.CustomProviderConfig
import com.tom.rv2ide.artificial.agents.custom.CustomProviderProfile
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

private data class ProviderOption(val id: String, val label: String)

private val providerOptions =
    listOf(
        ProviderOption("gemini", "Google Gemini"),
        ProviderOption("openai", "OpenAI"),
        ProviderOption("claude", "Anthropic Claude"),
        ProviderOption("deepseek", "DeepSeek"),
        ProviderOption("grok", "xAI Grok"),
        ProviderOption("localllm", "Local LLM"),
        ProviderOption("custom", "Custom Provider"),
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
      title = "Auto-switch Provider"
      summary = "Switch to another configured provider on rate-limit, quota, or invalid-key errors"
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
      title = "AI Tool Execution"
      summary = "Allow builds, focused reads/writes, safe terminal commands, and Termux package actions"
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
      title = "AI Provider"
      summary = buildSummary(context)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val currentProvider = Agents(context).getProvider()
    val selectedIndex = providerOptions.indexOfFirst { it.id == currentProvider }
    MaterialAlertDialogBuilder(context)
        .setTitle("Select AI Provider")
        .setSingleChoiceItems(providerOptions.map { it.label }.toTypedArray(), selectedIndex) { dialog, which ->
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
          } else {
            applyProviderSelection(context, option.id)
            onChanged?.invoke()
            refresh()
          }
        }
        .setNegativeButton("Cancel", null)
        .show()
    return true
  }

  private fun buildSummary(context: Context): String {
    val providerId = Agents(context).getProvider()
    val activeProfile = CustomProviderConfig.getActiveProfile()
    return if (providerId == "custom" && activeProfile != null) {
      "Current: ${providerDisplayName(providerId)} • ${activeProfile.name}"
    } else {
      "Current: ${providerDisplayName(providerId)}"
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
      title = "AI Model"
      summary = buildSummary(context)
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val agents = Agents(context)
    val providerId = agents.getProvider()
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
      showToast(context, "No models configured for ${providerDisplayName(providerId)}")
      return true
    }

    val currentModel = agents.getAgent()
    val selectedIndex = models.indexOfFirst { it == currentModel }
    MaterialAlertDialogBuilder(context)
        .setTitle("Select AI Model")
        .setSingleChoiceItems(models.toTypedArray(), selectedIndex) { dialog, which ->
          dialog.dismiss()
          agents.setAgent(models[which])
          onChanged?.invoke()
          refresh()
        }
        .setNegativeButton("Cancel", null)
        .show()
    return true
  }

  private fun buildSummary(context: Context): String {
    return Agents(context).getAgent().takeIf { it.isNotBlank() }?.let { "Current: $it" }
        ?: "No model selected"
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
      title = "Active Custom Profile"
      summary = buildSummary()
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
        .setTitle("Select Active Custom Profile")
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
        .setNegativeButton("Cancel", null)
        .show()
    return true
  }

  private fun buildSummary(): String {
    val activeProfile = CustomProviderConfig.getActiveProfile()
    return if (activeProfile == null) {
      "No custom profile configured"
    } else {
      listOfNotNull(
              activeProfile.name.takeIf { it.isNotBlank() },
              activeProfile.apiType.displayName,
              activeProfile.modelId.takeIf { it.isNotBlank() },
          )
          .joinToString(" • ")
    }
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary = buildSummary()
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled && CustomProviderConfig.hasProfiles()
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
      title = "Add Custom Profile"
      summary = if (CustomProviderConfig.hasProfiles()) "Create another reusable custom provider profile" else "Create your first custom provider profile"
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
    pref.summary = if (CustomProviderConfig.hasProfiles()) "Create another reusable custom provider profile" else "Create your first custom provider profile"
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
      title = "Edit Active Custom Profile"
      summary = CustomProviderConfig.getActiveProfile()?.let { "Edit ${it.name}" } ?: "No custom profile to edit"
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
    pref.summary = CustomProviderConfig.getActiveProfile()?.let { "Edit ${it.name}" } ?: "No custom profile to edit"
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
      title = "Delete Active Custom Profile"
      summary = CustomProviderConfig.getActiveProfile()?.let { "Delete ${it.name}" } ?: "No custom profile to delete"
      isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val activeProfile = CustomProviderConfig.getActiveProfile() ?: return true
    MaterialAlertDialogBuilder(context)
        .setTitle("Delete Custom Profile")
        .setMessage("Delete '${activeProfile.name}'?")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete") { _, _ ->
          if (CustomProviderConfig.deleteProfile(activeProfile.id)) {
            CustomProviderConfig.getActiveProfile()?.takeIf { Agents(context).getProvider() == "custom" }?.let {
              applyProviderSelection(context, "custom", it.modelId)
            }
            onChanged?.invoke()
            refresh()
          } else {
            showToast(context, "Failed to delete custom profile")
          }
        }
        .show()
    return true
  }

  override fun refresh() {
    val pref = preference ?: return
    pref.summary = CustomProviderConfig.getActiveProfile()?.let { "Delete ${it.name}" } ?: "No custom profile to delete"
    pref.isEnabled = prefManager.getBoolean("ai_agent_enabled", false) && CustomProviderConfig.hasProfiles()
  }

  override fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled && CustomProviderConfig.hasProfiles()
  }
}

private fun providerDisplayName(providerId: String): String {
  return providerOptions.firstOrNull { it.id == providerId }?.label ?: providerId.uppercase()
}

private fun applyProviderSelection(context: Context, providerId: String, preferredModel: String? = null) {
  val agents = Agents(context)
  agents.setProvider(providerId)
  val targetModel =
      if (providerId == "custom") {
        preferredModel?.takeIf { it.isNotBlank() }
            ?: CustomProviderConfig.getModelId().ifBlank { CustomProviderConfig.getAvailableModels().firstOrNull().orEmpty() }
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
    showToast(context, "Unable to open the custom provider editor from this screen")
    return
  }
  CustomProviderConfigDialog(profileId = profileId, createNew = createNew, onSave = onSave)
      .show(activity.supportFragmentManager, "CustomProviderConfigDialog")
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
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_grok_api_key", ""))
    editText.hint = "Enter your xAI Grok API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("Grok API Key")
            .setMessage("Enter your xAI Grok API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_grok_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_grok_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
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
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_gemini_api_key", ""))
    editText.hint = "Enter your Google Gemini API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("Gemini API Key")
            .setMessage("Enter your Google Gemini API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_gemini_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_gemini_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
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
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_deepseek_api_key", ""))
    editText.hint = "Enter your Deepseek API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("Deepseek API Key")
            .setMessage("Enter your Deepseek API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_deepseek_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_deepseek_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
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
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_openai_api_key", ""))
    editText.hint = "Enter your OpenAI API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("OpenAI API Key")
            .setMessage("Enter your OpenAI API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_openai_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_openai_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
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
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_anthropic_api_key", ""))
    editText.hint = "Enter your Anthropic API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("Anthropic API Key")
            .setMessage("Enter your Anthropic API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_anthropic_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_anthropic_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
  }
}
