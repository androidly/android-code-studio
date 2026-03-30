package com.tom.rv2ide.artificial.agents.external

import com.tom.rv2ide.artificial.agents.custom.CustomProviderApiType
import com.tom.rv2ide.artificial.agents.custom.CustomProviderConfig
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.preferences.internal.prefManager
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private fun buildCodexSuggestedModels(
    primaryModel: String? = null,
    reviewModel: String? = null,
    models: List<String> = emptyList()
): List<String> {
    val ordered = LinkedHashSet<String>()
    primaryModel?.trim()?.takeIf { it.isNotBlank() }?.let(ordered::add)
    reviewModel?.trim()?.takeIf { it.isNotBlank() }?.let(ordered::add)
    models.map(String::trim)
        .filter(String::isNotBlank)
        .forEach(ordered::add)
    return ordered.toList()
}

enum class CodexCliAuthMode(
    val value: String,
    val displayName: String
) {
    ENV_KEY("env_key", "Env Key (Recommended)"),
    OPENAI_AUTH("openai_auth", "auth.json / OpenAI Auth");

    companion object {
        fun fromValue(value: String?): CodexCliAuthMode {
            return entries.firstOrNull { it.value == value } ?: ENV_KEY
        }

        fun displayNames(): Array<String> {
            return entries.map { it.displayName }.toTypedArray()
        }

        fun fromDisplayName(displayName: String?): CodexCliAuthMode {
            return entries.firstOrNull { it.displayName == displayName } ?: ENV_KEY
        }
    }
}

enum class CodexCliReasoningEffort(
    val value: String,
    val displayName: String
) {
    NONE("none", "none"),
    MINIMAL("minimal", "minimal"),
    LOW("low", "low"),
    MEDIUM("medium", "medium"),
    HIGH("high", "high"),
    XHIGH("xhigh", "xhigh");

    companion object {
        fun fromValue(value: String?): CodexCliReasoningEffort {
            return entries.firstOrNull { it.value == value } ?: XHIGH
        }

        fun displayNames(): Array<String> {
            return entries.map { it.displayName }.toTypedArray()
        }

        fun fromDisplayName(displayName: String?): CodexCliReasoningEffort {
            return entries.firstOrNull { it.displayName == displayName } ?: XHIGH
        }
    }
}

data class CodexCliProfile(
    val id: String,
    val name: String,
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reviewModel: String,
    val reasoningEffort: CodexCliReasoningEffort,
    val authMode: CodexCliAuthMode,
    val contextWindow: Long,
    val autoCompactTokenLimit: Long,
    val cachedModels: List<String> = emptyList(),
    val enabled: Boolean = true
) {
    fun toSettings(inferred: Boolean = false): CodexCliSettings {
        return CodexCliSettings(
            profileId = id,
            profileName = name,
            providerId = providerId,
            providerName = providerName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            reviewModel = reviewModel,
            reasoningEffort = reasoningEffort,
            authMode = authMode,
            contextWindow = contextWindow,
            autoCompactTokenLimit = autoCompactTokenLimit,
            cachedModels = cachedModels,
            enabled = enabled,
            inferred = inferred
        )
    }
}

data class CodexCliSettings(
    val profileId: String = "",
    val profileName: String = "",
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reviewModel: String,
    val reasoningEffort: CodexCliReasoningEffort,
    val authMode: CodexCliAuthMode,
    val contextWindow: Long,
    val autoCompactTokenLimit: Long,
    val cachedModels: List<String> = emptyList(),
    val enabled: Boolean = true,
    val inferred: Boolean = false
) {
    val normalizedProfileName: String
        get() = profileName.trim()

    val resolvedProfileName: String
        get() = normalizedProfileName.ifBlank {
            resolvedProviderName.ifBlank { normalizedProviderId }
        }

    val normalizedProviderId: String
        get() = providerId.trim()

    val resolvedProviderName: String
        get() = providerName.trim().ifBlank { normalizedProviderId }

    val normalizedBaseUrl: String
        get() = CodexCliConfig.versionedBaseUrl(baseUrl)

    val resolvedReviewModel: String
        get() = reviewModel.trim().ifBlank { model.trim() }

    val usesAuthJson: Boolean
        get() = authMode == CodexCliAuthMode.OPENAI_AUTH

    val availableModels: List<String>
        get() = buildCodexSuggestedModels(
            primaryModel = model,
            reviewModel = resolvedReviewModel,
            models = cachedModels
        )

    val isValid: Boolean
        get() = enabled &&
            CodexCliConfig.isValidProviderId(normalizedProviderId) &&
            apiKey.isNotBlank() &&
            model.trim().isNotBlank()

    fun summaryText(): String {
        val providerSummary = buildString {
            append(resolvedProviderName)
            if (normalizedBaseUrl.isNotBlank()) {
                append(" @ ")
                append(normalizedBaseUrl)
            }
        }
        return listOf(
            providerSummary,
            model.trim(),
            reasoningEffort.value,
            if (usesAuthJson) "auth.json" else "env key"
        ).filter(String::isNotBlank).joinToString(" • ")
    }
}

object CodexCliConfig {

    private const val KEY_PROVIDER_ID = "ai_codex_cli_provider_id"
    private const val KEY_PROVIDER_NAME = "ai_codex_cli_provider_name"
    private const val KEY_BASE_URL = "ai_codex_cli_base_url"
    private const val KEY_API_KEY = "ai_codex_cli_api_key"
    private const val KEY_MODEL = "ai_codex_cli_model"
    private const val KEY_REVIEW_MODEL = "ai_codex_cli_review_model"
    private const val KEY_REASONING_EFFORT = "ai_codex_cli_reasoning_effort"
    private const val KEY_AUTH_MODE = "ai_codex_cli_auth_mode"
    private const val KEY_CONTEXT_WINDOW = "ai_codex_cli_context_window"
    private const val KEY_AUTO_COMPACT_LIMIT = "ai_codex_cli_auto_compact_limit"
    private const val KEY_MODELS_JSON = "ai_codex_cli_models_json"
    private const val KEY_PROFILES_JSON = "ai_codex_cli_profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "ai_codex_cli_active_profile_id"

    private const val DEFAULT_PROVIDER_ID = "OpenAI"
    private const val DEFAULT_PROVIDER_NAME = "OpenAI"
    private const val DEFAULT_PROFILE_NAME = "Default Codex Profile"
    private const val DEFAULT_MODEL = "gpt-5.4"
    private const val DEFAULT_CONTEXT_WINDOW = 1_000_000L
    private const val DEFAULT_AUTO_COMPACT_LIMIT = 900_000L

    private val providerIdRegex = Regex("^[A-Za-z0-9_-]+$")

    fun getSettings(): CodexCliSettings {
        val activeProfile = getActiveProfile()
        return activeProfile?.toSettings() ?: inferredDefaults()
    }

    fun getProfiles(): List<CodexCliProfile> {
        ensureMigrated()
        val rawJson = prefManager.getString(KEY_PROFILES_JSON, "[]").orEmpty()
        return try {
            val profiles = mutableListOf<CodexCliProfile>()
            val array = JSONArray(rawJson)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
                val providerId = item.optString("providerId").trim().ifBlank { DEFAULT_PROVIDER_ID }
                val model = item.optString("model").trim()
                val reviewModel = item.optString("reviewModel").trim()
                profiles += CodexCliProfile(
                    id = id,
                    name = item.optString("name").trim().ifBlank { "Codex Profile ${index + 1}" },
                    providerId = providerId,
                    providerName = item.optString("providerName").trim().ifBlank { providerId },
                    baseUrl = item.optString("baseUrl").trim(),
                    apiKey = item.optString("apiKey").trim(),
                    model = model,
                    reviewModel = reviewModel.ifBlank { model },
                    reasoningEffort = CodexCliReasoningEffort.fromValue(item.optString("reasoningEffort")),
                    authMode = CodexCliAuthMode.fromValue(item.optString("authMode")),
                    contextWindow = item.optLong("contextWindow", DEFAULT_CONTEXT_WINDOW).coerceAtLeast(1L),
                    autoCompactTokenLimit = item.optLong(
                        "autoCompactTokenLimit",
                        DEFAULT_AUTO_COMPACT_LIMIT
                    ).coerceAtLeast(1L),
                    cachedModels = parseModelArray(item.optJSONArray("cachedModels")),
                    enabled = item.optBoolean("enabled", true)
                )
            }
            profiles
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getProfile(profileId: String?): CodexCliProfile? {
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

    fun getActiveProfile(): CodexCliProfile? {
        val profiles = getProfiles()
        val activeId = getActiveProfileId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }

    fun setActiveProfile(profileId: String) {
        if (getProfiles().none { it.id == profileId }) {
            return
        }
        prefManager.putString(KEY_ACTIVE_PROFILE_ID, profileId)
    }

    fun saveProfile(
        profileId: String? = null,
        profileName: String,
        providerId: String,
        providerName: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        reviewModel: String,
        reasoningEffort: CodexCliReasoningEffort,
        authMode: CodexCliAuthMode,
        contextWindow: Long,
        autoCompactTokenLimit: Long,
        cachedModels: List<String>? = null,
        makeActive: Boolean = true
    ): CodexCliProfile {
        val profiles = getProfiles().toMutableList()
        val existingProfile = getProfile(profileId)
        val resolvedId = profileId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val resolvedModel = model.trim()
        val resolvedReviewModel = reviewModel.trim().ifBlank { resolvedModel }
        val profile = CodexCliProfile(
            id = resolvedId,
            name = profileName.trim().ifBlank { DEFAULT_PROFILE_NAME },
            providerId = providerId.trim().ifBlank { DEFAULT_PROVIDER_ID },
            providerName = providerName.trim().ifBlank {
                providerId.trim().ifBlank { DEFAULT_PROVIDER_NAME }
            },
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = resolvedModel,
            reviewModel = resolvedReviewModel,
            reasoningEffort = reasoningEffort,
            authMode = authMode,
            contextWindow = contextWindow.coerceAtLeast(1L),
            autoCompactTokenLimit = autoCompactTokenLimit.coerceAtLeast(1L),
            cachedModels = buildCodexSuggestedModels(
                primaryModel = resolvedModel,
                reviewModel = resolvedReviewModel,
                models = cachedModels ?: existingProfile?.cachedModels.orEmpty()
            )
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

    fun save(
        providerId: String,
        providerName: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        reviewModel: String,
        reasoningEffort: CodexCliReasoningEffort,
        authMode: CodexCliAuthMode,
        contextWindow: Long,
        autoCompactTokenLimit: Long
    ): CodexCliSettings {
        val activeProfile = getActiveProfile()
        return saveProfile(
            profileId = activeProfile?.id,
            profileName = activeProfile?.name ?: DEFAULT_PROFILE_NAME,
            providerId = providerId,
            providerName = providerName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            reviewModel = reviewModel,
            reasoningEffort = reasoningEffort,
            authMode = authMode,
            contextWindow = contextWindow,
            autoCompactTokenLimit = autoCompactTokenLimit,
            makeActive = true
        ).toSettings()
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

    fun saveCachedModels(models: List<String>) {
        val activeProfile = getActiveProfile() ?: return
        saveProfile(
            profileId = activeProfile.id,
            profileName = activeProfile.name,
            providerId = activeProfile.providerId,
            providerName = activeProfile.providerName,
            baseUrl = activeProfile.baseUrl,
            apiKey = activeProfile.apiKey,
            model = activeProfile.model,
            reviewModel = activeProfile.reviewModel,
            reasoningEffort = activeProfile.reasoningEffort,
            authMode = activeProfile.authMode,
            contextWindow = activeProfile.contextWindow,
            autoCompactTokenLimit = activeProfile.autoCompactTokenLimit,
            cachedModels = models,
            makeActive = true
        )
    }

    fun saveModelForActiveProfile(modelId: String) {
        val activeProfile = getActiveProfile() ?: return
        val resolvedModel = modelId.trim()
        if (resolvedModel.isBlank()) {
            return
        }
        val resolvedReviewModel = activeProfile.reviewModel
            .takeIf { it.isNotBlank() && it != activeProfile.model }
            ?: resolvedModel
        saveProfile(
            profileId = activeProfile.id,
            profileName = activeProfile.name,
            providerId = activeProfile.providerId,
            providerName = activeProfile.providerName,
            baseUrl = activeProfile.baseUrl,
            apiKey = activeProfile.apiKey,
            model = resolvedModel,
            reviewModel = resolvedReviewModel,
            reasoningEffort = activeProfile.reasoningEffort,
            authMode = activeProfile.authMode,
            contextWindow = activeProfile.contextWindow,
            autoCompactTokenLimit = activeProfile.autoCompactTokenLimit,
            cachedModels = activeProfile.cachedModels,
            makeActive = true
        )
    }

    fun getAvailableModels(): List<String> {
        return getSettings().availableModels
    }

    fun hasProfiles(): Boolean {
        return getProfiles().isNotEmpty()
    }

    fun hasAnyValidProfile(): Boolean {
        return getProfiles().any { it.toSettings().isValid }
    }

    fun hasValidConfig(): Boolean {
        return getSettings().isValid
    }

    fun getModelId(): String {
        return getSettings().model.trim()
    }

    fun isValidProviderId(value: String): Boolean {
        return value.isNotBlank() && providerIdRegex.matches(value)
    }

    fun versionedBaseUrl(rawBaseUrl: String): String {
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

        return if (normalized.endsWith("/v1")) {
            normalized
        } else {
            "$normalized/v1"
        }
    }

    private fun persistProfiles(profiles: List<CodexCliProfile>, activeProfileId: String) {
        val jsonArray = JSONArray()
        profiles.forEach { profile ->
            jsonArray.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name.trim())
                    put("providerId", profile.providerId.trim())
                    put("providerName", profile.providerName.trim())
                    put("baseUrl", profile.baseUrl.trim())
                    put("apiKey", profile.apiKey.trim())
                    put("model", profile.model.trim())
                    put("reviewModel", profile.reviewModel.trim())
                    put("reasoningEffort", profile.reasoningEffort.value)
                    put("authMode", profile.authMode.value)
                    put("contextWindow", profile.contextWindow.coerceAtLeast(1L))
                    put(
                        "autoCompactTokenLimit",
                        profile.autoCompactTokenLimit.coerceAtLeast(1L)
                    )
                    put("enabled", profile.enabled)
                    put(
                        "cachedModels",
                        JSONArray(
                            buildCodexSuggestedModels(
                                primaryModel = profile.model,
                                reviewModel = profile.reviewModel,
                                models = profile.cachedModels
                            )
                        )
                    )
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
        return buildCodexSuggestedModels(models = models)
    }

    private fun ensureMigrated() {
        val storedProfiles = prefManager.getString(KEY_PROFILES_JSON, null)
        if (storedProfiles != null) {
            return
        }

        val legacyProviderId = prefManager.getString(KEY_PROVIDER_ID, null)?.trim().orEmpty()
        val legacyProviderName = prefManager.getString(KEY_PROVIDER_NAME, null)?.trim().orEmpty()
        val legacyBaseUrl = prefManager.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        val legacyApiKey = prefManager.getString(KEY_API_KEY, null)?.trim().orEmpty()
        val legacyModel = prefManager.getString(KEY_MODEL, null)?.trim().orEmpty()
        val legacyReviewModel = prefManager.getString(KEY_REVIEW_MODEL, null)?.trim().orEmpty()
        val legacyModels = try {
            parseModelArray(JSONArray(prefManager.getString(KEY_MODELS_JSON, "[]").orEmpty()))
        } catch (_: Exception) {
            emptyList()
        }

        val hasLegacyData = listOf(
            legacyProviderId,
            legacyProviderName,
            legacyBaseUrl,
            legacyApiKey,
            legacyModel,
            legacyReviewModel
        ).any(String::isNotBlank) || legacyModels.isNotEmpty()

        if (!hasLegacyData) {
            prefManager.putString(KEY_PROFILES_JSON, "[]")
            prefManager.putString(KEY_ACTIVE_PROFILE_ID, "")
            return
        }

        val migratedProfile = CodexCliProfile(
            id = "default",
            name = DEFAULT_PROFILE_NAME,
            providerId = legacyProviderId.ifBlank { DEFAULT_PROVIDER_ID },
            providerName = legacyProviderName.ifBlank {
                legacyProviderId.ifBlank { DEFAULT_PROVIDER_NAME }
            },
            baseUrl = legacyBaseUrl,
            apiKey = legacyApiKey,
            model = legacyModel,
            reviewModel = legacyReviewModel.ifBlank { legacyModel },
            reasoningEffort = CodexCliReasoningEffort.fromValue(
                prefManager.getString(KEY_REASONING_EFFORT, null)
            ),
            authMode = CodexCliAuthMode.fromValue(prefManager.getString(KEY_AUTH_MODE, null)),
            contextWindow = prefManager
                .getLong(KEY_CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW)
                .coerceAtLeast(1L),
            autoCompactTokenLimit = prefManager
                .getLong(KEY_AUTO_COMPACT_LIMIT, DEFAULT_AUTO_COMPACT_LIMIT)
                .coerceAtLeast(1L),
            cachedModels = buildCodexSuggestedModels(
                primaryModel = legacyModel,
                reviewModel = legacyReviewModel.ifBlank { legacyModel },
                models = legacyModels
            )
        )
        persistProfiles(listOf(migratedProfile), migratedProfile.id)
    }

    private fun inferredDefaults(): CodexCliSettings {
        val customProfile = CustomProviderConfig.getActiveProfile()
            ?.takeIf { it.isValid && it.apiType == CustomProviderApiType.OPENAI_RESPONSES }
        val apiKey = customProfile?.apiKey?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: ApiKey.getOpenAIApiKey().trim()
        val model = customProfile?.modelId?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

        return CodexCliSettings(
            profileId = "",
            profileName = "",
            providerId = DEFAULT_PROVIDER_ID,
            providerName = DEFAULT_PROVIDER_NAME,
            baseUrl = customProfile?.baseUrl.orEmpty(),
            apiKey = apiKey,
            model = model,
            reviewModel = model,
            reasoningEffort = CodexCliReasoningEffort.XHIGH,
            authMode = CodexCliAuthMode.ENV_KEY,
            contextWindow = DEFAULT_CONTEXT_WINDOW,
            autoCompactTokenLimit = DEFAULT_AUTO_COMPACT_LIMIT,
            cachedModels = buildCodexSuggestedModels(primaryModel = model, reviewModel = model),
            inferred = true
        )
    }
}
