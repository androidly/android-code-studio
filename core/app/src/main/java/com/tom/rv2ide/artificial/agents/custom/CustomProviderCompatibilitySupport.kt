package com.tom.rv2ide.artificial.agents.custom

import java.net.URI

internal data class CustomProviderCompatibilityLearningOutcome(
    val disableStrictToolSchemas: Boolean = false,
    val disableResponsesContinuation: Boolean = false
)

internal data class CustomProviderCompatibilityDefaults(
    val strictToolSchemasEnabled: Boolean,
    val responsesContinuationEnabled: Boolean
)

internal object CustomProviderCompatibilitySupport {
    fun requestModesFor(apiType: CustomProviderApiType): List<ProviderRequestMode> {
        return when (apiType) {
            CustomProviderApiType.OPENAI_CHAT,
            CustomProviderApiType.OPENAI_RESPONSES -> listOf(
                ProviderRequestMode.Default,
                ProviderRequestMode.CompatibilityFallback
            )
            CustomProviderApiType.CLAUDE_MESSAGES -> listOf(ProviderRequestMode.Default)
        }
    }

    fun shouldRetryWithCompatibilityFallback(
        responseCode: Int,
        requestMode: ProviderRequestMode,
        apiType: CustomProviderApiType
    ): Boolean {
        return responseCode == 400 &&
            !requestMode.compatibilityFallback &&
            (apiType == CustomProviderApiType.OPENAI_CHAT || apiType == CustomProviderApiType.OPENAI_RESPONSES)
    }

    fun shouldRetryWithCompatibilityFallback(
        error: Throwable,
        requestMode: ProviderRequestMode,
        apiType: CustomProviderApiType
    ): Boolean {
        if (requestMode.compatibilityFallback) {
            return false
        }
        if (apiType != CustomProviderApiType.OPENAI_CHAT &&
            apiType != CustomProviderApiType.OPENAI_RESPONSES
        ) {
            return false
        }

        val message = error.message.orEmpty()
        if (message.isBlank()) {
            return false
        }

        return message.contains("status_code=400", ignoreCase = true) ||
            message.contains("status code 400", ignoreCase = true) ||
            message.contains("invalid schema", ignoreCase = true) ||
            message.contains("no tool call found", ignoreCase = true) ||
            message.contains("function_call_output", ignoreCase = true) ||
            message.contains("previous_response_id", ignoreCase = true)
    }

    fun shouldUseResponsesContinuation(
        requestMode: ProviderRequestMode,
        apiType: CustomProviderApiType,
        responsesContinuationEnabledForSession: Boolean
    ): Boolean {
        return apiType == CustomProviderApiType.OPENAI_RESPONSES &&
            !requestMode.compatibilityFallback &&
            responsesContinuationEnabledForSession
    }

    fun shouldUseStrictToolSchemas(
        requestMode: ProviderRequestMode,
        strictNativeToolSchemasEnabled: Boolean
    ): Boolean {
        return !requestMode.compatibilityFallback && strictNativeToolSchemasEnabled
    }

    fun shouldEnableResponsesContinuationByDefault(baseUrl: String): Boolean {
        return isOfficialOpenAIHost(baseUrl)
    }

    fun shouldEnableStrictToolSchemasByDefault(baseUrl: String): Boolean {
        return isOfficialOpenAIHost(baseUrl)
    }

    fun defaultFlags(baseUrl: String): CustomProviderCompatibilityDefaults {
        return CustomProviderCompatibilityDefaults(
            strictToolSchemasEnabled = shouldEnableStrictToolSchemasByDefault(baseUrl),
            responsesContinuationEnabled = shouldEnableResponsesContinuationByDefault(baseUrl)
        )
    }

    fun applyCompatibilityLearning(
        rawMessage: String,
        requestMode: ProviderRequestMode,
        apiType: CustomProviderApiType
    ): CustomProviderCompatibilityLearningOutcome {
        if (requestMode.compatibilityFallback) {
            return CustomProviderCompatibilityLearningOutcome()
        }
        val message = rawMessage.trim()
        if (message.isBlank()) {
            return CustomProviderCompatibilityLearningOutcome()
        }

        val disableStrictToolSchemas =
            message.contains("invalid schema", ignoreCase = true) ||
                message.contains("schema for function", ignoreCase = true) ||
                message.contains("'strict'", ignoreCase = true)

        val disableResponsesContinuation =
            apiType == CustomProviderApiType.OPENAI_RESPONSES &&
                (message.contains("no tool call found", ignoreCase = true) ||
                    message.contains("function_call_output", ignoreCase = true) ||
                    message.contains("previous_response_id", ignoreCase = true))

        return CustomProviderCompatibilityLearningOutcome(
            disableStrictToolSchemas = disableStrictToolSchemas,
            disableResponsesContinuation = disableResponsesContinuation
        )
    }

    fun buildInitializationFingerprint(
        apiKey: String,
        normalizedBaseUrl: String,
        modelId: String,
        apiType: CustomProviderApiType
    ): String {
        return buildString {
            append(apiType.value)
            append('|')
            append(normalizedBaseUrl.trim())
            append('|')
            append(modelId.trim())
            append('|')
            append(apiKey.trim().hashCode())
        }
    }

    private fun isOfficialOpenAIHost(baseUrl: String): Boolean {
        val host = runCatching { URI(baseUrl).host.orEmpty().lowercase() }.getOrDefault("")
        if (host.isBlank()) {
            return false
        }
        return host == "api.openai.com" || host.endsWith(".openai.azure.com")
    }
}
