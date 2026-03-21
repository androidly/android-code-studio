package com.tom.rv2ide.artificial.agents.custom

import com.tom.rv2ide.preferences.internal.prefManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class CustomProviderApiType(
    val value: String,
    val displayName: String
) {
    OPENAI_CHAT("openai_chat", "OpenAI Chat"),
    OPENAI_RESPONSES("openai_responses", "OpenAI Responses"),
    CLAUDE_MESSAGES("claude_messages", "Claude Messages");

    companion object {
        fun fromValue(value: String?): CustomProviderApiType {
            return values().firstOrNull { it.value == value } ?: OPENAI_CHAT
        }

        fun fromDisplayName(displayName: String?): CustomProviderApiType {
            return values().firstOrNull { it.displayName == displayName } ?: OPENAI_CHAT
        }

        fun displayNames(): Array<String> {
            return values().map { it.displayName }.toTypedArray()
        }
    }
}

data class CustomProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val apiType: CustomProviderApiType,
    val cachedModels: List<String> = emptyList(),
    val enabled: Boolean = true
) {
    val normalizedBaseUrl: String
        get() = CustomProviderConfig.normalizedBaseUrl(baseUrl)

    val isValid: Boolean
        get() = enabled &&
            normalizedBaseUrl.isNotBlank() &&
            apiKey.isNotBlank() &&
            modelId.isNotBlank()
}

data class CustomProviderSettings(
    val profileId: String,
    val profileName: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val apiType: CustomProviderApiType,
    val cachedModels: List<String>
) {
    val normalizedBaseUrl: String
        get() = CustomProviderConfig.normalizedBaseUrl(baseUrl)
}

object CustomProviderConfig {

    private const val KEY_BASE_URL = "ai_custom_provider_base_url"
    private const val KEY_API_KEY = "ai_custom_provider_api_key"
    private const val KEY_MODEL_ID = "ai_custom_provider_model_id"
    private const val KEY_API_TYPE = "ai_custom_provider_api_type"
    private const val KEY_MODELS_JSON = "ai_custom_provider_models_json"
    private const val KEY_PROFILES_JSON = "ai_custom_provider_profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "ai_custom_provider_active_profile_id"
    private const val DEFAULT_PROFILE_NAME = "Default Custom Provider"

    fun getSettings(): CustomProviderSettings {
        val profile = getActiveProfile()
        return CustomProviderSettings(
            profileId = profile?.id.orEmpty(),
            profileName = profile?.name.orEmpty(),
            baseUrl = profile?.baseUrl.orEmpty(),
            apiKey = profile?.apiKey.orEmpty(),
            modelId = profile?.modelId.orEmpty(),
            apiType = profile?.apiType ?: CustomProviderApiType.OPENAI_CHAT,
            cachedModels = profile?.cachedModels.orEmpty()
        )
    }

    fun getProfiles(): List<CustomProviderProfile> {
        ensureMigrated()
        val rawJson = prefManager.getString(KEY_PROFILES_JSON, "[]").orEmpty()
        return try {
            val profiles = mutableListOf<CustomProviderProfile>()
            val array = JSONArray(rawJson)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
                val name = item.optString("name").trim().ifBlank { "Custom Provider ${index + 1}" }
                profiles += CustomProviderProfile(
                    id = id,
                    name = name,
                    baseUrl = item.optString("baseUrl").trim(),
                    apiKey = item.optString("apiKey").trim(),
                    modelId = item.optString("modelId").trim(),
                    apiType = CustomProviderApiType.fromValue(item.optString("apiType")),
                    cachedModels = parseModelArray(item.optJSONArray("cachedModels")),
                    enabled = item.optBoolean("enabled", true)
                )
            }
            profiles
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getProfile(profileId: String?): CustomProviderProfile? {
        if (profileId.isNullOrBlank()) {
            return null
        }
        return getProfiles().firstOrNull { it.id == profileId }
    }

    fun getActiveProfileId(): String {
        ensureMigrated()
        val activeId = prefManager.getString(KEY_ACTIVE_PROFILE_ID, "")?.trim().orEmpty()
        if (activeId.isNotBlank()) {
            return activeId
        }
        return getProfiles().firstOrNull()?.id.orEmpty()
    }

    fun getActiveProfile(): CustomProviderProfile? {
        val profiles = getProfiles()
        val activeId = getActiveProfileId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }

    fun setActiveProfile(profileId: String) {
        val profiles = getProfiles()
        if (profiles.none { it.id == profileId }) {
            return
        }
        prefManager.putString(KEY_ACTIVE_PROFILE_ID, profileId)
    }

    fun saveProfile(
        profileId: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        apiType: CustomProviderApiType,
        cachedModels: List<String>? = null,
        makeActive: Boolean = true
    ): CustomProviderProfile {
        val profiles = getProfiles().toMutableList()
        val normalizedModels = normalizeModels(
            models = cachedModels ?: getProfile(profileId)?.cachedModels.orEmpty(),
            preferredModel = modelId
        )
        val resolvedId = profileId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val profile = CustomProviderProfile(
            id = resolvedId,
            name = name.trim().ifBlank { DEFAULT_PROFILE_NAME },
            baseUrl = normalizedBaseUrl(baseUrl),
            apiKey = apiKey.trim(),
            modelId = modelId.trim(),
            apiType = apiType,
            cachedModels = normalizedModels
        )
        val existingIndex = profiles.indexOfFirst { it.id == resolvedId }
        if (existingIndex >= 0) {
            profiles[existingIndex] = profile
        } else {
            profiles += profile
        }
        persistProfiles(profiles, if (makeActive) resolvedId else getActiveProfileId())
        return profile
    }

    fun deleteProfile(profileId: String): Boolean {
        val profiles = getProfiles().toMutableList()
        val removed = profiles.removeAll { it.id == profileId }
        if (!removed) {
            return false
        }
        val fallbackActiveId = when {
            profiles.isEmpty() -> ""
            getActiveProfileId() == profileId -> profiles.first().id
            else -> getActiveProfileId()
        }
        persistProfiles(profiles, fallbackActiveId)
        return true
    }

    fun getBaseUrl(): String {
        return getActiveProfile()?.baseUrl.orEmpty()
    }

    fun getApiKey(): String {
        return getActiveProfile()?.apiKey.orEmpty()
    }

    fun getModelId(): String {
        return getActiveProfile()?.modelId.orEmpty()
    }

    fun getApiType(): CustomProviderApiType {
        return getActiveProfile()?.apiType ?: CustomProviderApiType.OPENAI_CHAT
    }

    fun save(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        apiType: CustomProviderApiType,
        cachedModels: List<String>? = null
    ) {
        val activeProfile = getActiveProfile()
        saveProfile(
            profileId = activeProfile?.id,
            name = activeProfile?.name ?: DEFAULT_PROFILE_NAME,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId,
            apiType = apiType,
            cachedModels = cachedModels,
            makeActive = true
        )
    }

    fun saveCachedModels(models: List<String>) {
        val activeProfile = getActiveProfile() ?: return
        saveProfile(
            profileId = activeProfile.id,
            name = activeProfile.name,
            baseUrl = activeProfile.baseUrl,
            apiKey = activeProfile.apiKey,
            modelId = activeProfile.modelId,
            apiType = activeProfile.apiType,
            cachedModels = models,
            makeActive = true
        )
    }

    fun saveModelForActiveProfile(modelId: String) {
        val activeProfile = getActiveProfile() ?: return
        saveProfile(
            profileId = activeProfile.id,
            name = activeProfile.name,
            baseUrl = activeProfile.baseUrl,
            apiKey = activeProfile.apiKey,
            modelId = modelId,
            apiType = activeProfile.apiType,
            cachedModels = activeProfile.cachedModels,
            makeActive = true
        )
    }

    fun clearCachedModels() {
        saveCachedModels(emptyList())
    }

    fun getCachedModels(): List<String> {
        return getActiveProfile()?.cachedModels.orEmpty()
    }

    fun getAvailableModels(): List<String> {
        val models = LinkedHashSet<String>()
        getModelId().takeIf { it.isNotBlank() }?.let(models::add)
        getCachedModels().forEach(models::add)
        return models.toList()
    }

    fun hasProfiles(): Boolean {
        return getProfiles().isNotEmpty()
    }

    fun hasAnyValidProfile(): Boolean {
        return getProfiles().any { it.isValid }
    }

    fun hasValidConfig(): Boolean {
        return getActiveProfile()?.isValid == true
    }

    fun normalizedBaseUrl(rawBaseUrl: String = getBaseUrl()): String {
        var normalized = rawBaseUrl.trim()
        if (normalized.isBlank()) {
            return ""
        }

        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }

        normalized = normalized.removeSuffix("/")

        val endpointSuffixes = listOf(
            "/chat/completions",
            "/responses",
            "/messages",
            "/models"
        )
        endpointSuffixes.firstOrNull { normalized.endsWith(it) }?.let { suffix ->
            normalized = normalized.removeSuffix(suffix)
        }

        return normalized.removeSuffix("/")
    }

    fun chatCompletionsEndpoint(rawBaseUrl: String = getBaseUrl()): String {
        return buildVersionedEndpoint(rawBaseUrl, "chat/completions")
    }

    fun responsesEndpoint(rawBaseUrl: String = getBaseUrl()): String {
        return buildVersionedEndpoint(rawBaseUrl, "responses")
    }

    fun messagesEndpoint(rawBaseUrl: String = getBaseUrl()): String {
        return buildVersionedEndpoint(rawBaseUrl, "messages")
    }

    fun modelsEndpoint(rawBaseUrl: String = getBaseUrl()): String {
        return buildVersionedEndpoint(rawBaseUrl, "models")
    }

    private fun persistProfiles(profiles: List<CustomProviderProfile>, activeProfileId: String) {
        val jsonArray = JSONArray()
        profiles.forEach { profile ->
            jsonArray.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name.trim())
                    put("baseUrl", normalizedBaseUrl(profile.baseUrl))
                    put("apiKey", profile.apiKey.trim())
                    put("modelId", profile.modelId.trim())
                    put("apiType", profile.apiType.value)
                    put("enabled", profile.enabled)
                    put("cachedModels", JSONArray(normalizeModels(profile.cachedModels, profile.modelId)))
                }
            )
        }
        prefManager.putString(KEY_PROFILES_JSON, jsonArray.toString())
        prefManager.putString(KEY_ACTIVE_PROFILE_ID, activeProfileId)
    }

    private fun parseModelArray(items: JSONArray?): List<String> {
        if (items == null) {
            return emptyList()
        }
        val models = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val modelId = items.optString(index).trim()
            if (modelId.isNotBlank()) {
                models += modelId
            }
        }
        return normalizeModels(models)
    }

    private fun normalizeModels(models: List<String>, preferredModel: String? = null): List<String> {
        val ordered = LinkedHashSet<String>()
        preferredModel?.trim()?.takeIf { it.isNotBlank() }?.let(ordered::add)
        models.map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(ordered::add)
        return ordered.toList()
    }

    private fun ensureMigrated() {
        val storedProfiles = prefManager.getString(KEY_PROFILES_JSON, null)
        if (storedProfiles != null) {
            return
        }

        val legacyBaseUrl = prefManager.getString(KEY_BASE_URL, "")?.trim().orEmpty()
        val legacyApiKey = prefManager.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val legacyModelId = prefManager.getString(KEY_MODEL_ID, "")?.trim().orEmpty()
        val legacyApiType = CustomProviderApiType.fromValue(prefManager.getString(KEY_API_TYPE, null))
        val legacyModels = try {
            parseModelArray(JSONArray(prefManager.getString(KEY_MODELS_JSON, "[]").orEmpty()))
        } catch (_: Exception) {
            emptyList()
        }

        if (
            legacyBaseUrl.isBlank() &&
            legacyApiKey.isBlank() &&
            legacyModelId.isBlank() &&
            legacyModels.isEmpty()
        ) {
            prefManager.putString(KEY_PROFILES_JSON, "[]")
            prefManager.putString(KEY_ACTIVE_PROFILE_ID, "")
            return
        }

        val migratedProfile = CustomProviderProfile(
            id = "default",
            name = DEFAULT_PROFILE_NAME,
            baseUrl = normalizedBaseUrl(legacyBaseUrl),
            apiKey = legacyApiKey,
            modelId = legacyModelId,
            apiType = legacyApiType,
            cachedModels = normalizeModels(legacyModels, legacyModelId)
        )
        persistProfiles(listOf(migratedProfile), migratedProfile.id)
    }

    private fun buildVersionedEndpoint(rawBaseUrl: String, pathAfterV1: String): String {
        val normalized = normalizedBaseUrl(rawBaseUrl)
        if (normalized.isBlank()) {
            return ""
        }

        return if (normalized.endsWith("/v1")) {
            "$normalized/$pathAfterV1"
        } else {
            "$normalized/v1/$pathAfterV1"
        }
    }
}
