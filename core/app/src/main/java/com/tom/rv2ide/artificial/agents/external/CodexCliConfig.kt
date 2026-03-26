package com.tom.rv2ide.artificial.agents.external

import com.tom.rv2ide.artificial.agents.custom.CustomProviderApiType
import com.tom.rv2ide.artificial.agents.custom.CustomProviderConfig
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.preferences.internal.prefManager

enum class CodexCliAuthMode(
    val value: String,
    val displayName: String
) {
    ENV_KEY("env_key", "Env Key (Recommended)"),
    OPENAI_AUTH("openai_auth", "auth.json / OpenAI Auth");

    companion object {
        fun fromValue(value: String?): CodexCliAuthMode {
            return values().firstOrNull { it.value == value } ?: ENV_KEY
        }

        fun displayNames(): Array<String> {
            return values().map { it.displayName }.toTypedArray()
        }

        fun fromDisplayName(displayName: String?): CodexCliAuthMode {
            return values().firstOrNull { it.displayName == displayName } ?: ENV_KEY
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
            return values().firstOrNull { it.value == value } ?: XHIGH
        }

        fun displayNames(): Array<String> {
            return values().map { it.displayName }.toTypedArray()
        }

        fun fromDisplayName(displayName: String?): CodexCliReasoningEffort {
            return values().firstOrNull { it.displayName == displayName } ?: XHIGH
        }
    }
}

data class CodexCliSettings(
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reviewModel: String,
    val reasoningEffort: CodexCliReasoningEffort,
    val authMode: CodexCliAuthMode,
    val contextWindow: Long,
    val autoCompactTokenLimit: Long
) {
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

    val isValid: Boolean
        get() = CodexCliConfig.isValidProviderId(normalizedProviderId) &&
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
        ).filter { it.isNotBlank() }.joinToString(" • ")
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

    private const val DEFAULT_PROVIDER_ID = "OpenAI"
    private const val DEFAULT_PROVIDER_NAME = "OpenAI"
    private const val DEFAULT_MODEL = "gpt-5.4"
    private const val DEFAULT_CONTEXT_WINDOW = 1_000_000L
    private const val DEFAULT_AUTO_COMPACT_LIMIT = 900_000L

    private val providerIdRegex = Regex("^[A-Za-z0-9_-]+$")

    fun getSettings(): CodexCliSettings {
        val inferred = inferredDefaults()
        return CodexCliSettings(
            providerId = prefManager.getString(KEY_PROVIDER_ID, inferred.providerId).orEmpty().trim(),
            providerName = prefManager.getString(KEY_PROVIDER_NAME, inferred.providerName).orEmpty().trim(),
            baseUrl = prefManager.getString(KEY_BASE_URL, inferred.baseUrl).orEmpty().trim(),
            apiKey = prefManager.getString(KEY_API_KEY, inferred.apiKey).orEmpty().trim(),
            model = prefManager.getString(KEY_MODEL, inferred.model).orEmpty().trim(),
            reviewModel = prefManager.getString(KEY_REVIEW_MODEL, inferred.reviewModel).orEmpty().trim(),
            reasoningEffort = CodexCliReasoningEffort.fromValue(
                prefManager.getString(KEY_REASONING_EFFORT, inferred.reasoningEffort.value)
            ),
            authMode = CodexCliAuthMode.fromValue(
                prefManager.getString(KEY_AUTH_MODE, inferred.authMode.value)
            ),
            contextWindow = prefManager.getLong(KEY_CONTEXT_WINDOW, inferred.contextWindow).coerceAtLeast(1L),
            autoCompactTokenLimit = prefManager
                .getLong(KEY_AUTO_COMPACT_LIMIT, inferred.autoCompactTokenLimit)
                .coerceAtLeast(1L)
        )
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
        prefManager.putString(KEY_PROVIDER_ID, providerId.trim())
        prefManager.putString(KEY_PROVIDER_NAME, providerName.trim())
        prefManager.putString(KEY_BASE_URL, baseUrl.trim())
        prefManager.putString(KEY_API_KEY, apiKey.trim())
        prefManager.putString(KEY_MODEL, model.trim())
        prefManager.putString(KEY_REVIEW_MODEL, reviewModel.trim())
        prefManager.putString(KEY_REASONING_EFFORT, reasoningEffort.value)
        prefManager.putString(KEY_AUTH_MODE, authMode.value)
        prefManager.putLong(KEY_CONTEXT_WINDOW, contextWindow.coerceAtLeast(1L))
        prefManager.putLong(KEY_AUTO_COMPACT_LIMIT, autoCompactTokenLimit.coerceAtLeast(1L))
        return getSettings()
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
            providerId = DEFAULT_PROVIDER_ID,
            providerName = DEFAULT_PROVIDER_NAME,
            baseUrl = customProfile?.baseUrl.orEmpty(),
            apiKey = apiKey,
            model = model,
            reviewModel = model,
            reasoningEffort = CodexCliReasoningEffort.XHIGH,
            authMode = CodexCliAuthMode.ENV_KEY,
            contextWindow = DEFAULT_CONTEXT_WINDOW,
            autoCompactTokenLimit = DEFAULT_AUTO_COMPACT_LIMIT
        )
    }
}
