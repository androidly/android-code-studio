package com.tom.rv2ide.artificial.agents.custom

internal data class CustomProviderResponsesContinuationState(
    var lastRequestSignature: String? = null,
    var lastResponseId: String? = null,
    var lastConversationKeys: List<String> = emptyList()
)

internal data class CustomProviderResponsesRequestPlan(
    val turns: List<CustomProviderTurn>,
    val validateToolCallPairing: Boolean,
    val previousResponseId: String?,
    val requestSignature: String
)

internal object CustomProviderResponsesContinuationSupport {
    fun clear(state: CustomProviderResponsesContinuationState) {
        state.lastRequestSignature = null
        state.lastResponseId = null
        state.lastConversationKeys = emptyList()
    }

    fun restoreConversationKeys(
        state: CustomProviderResponsesContinuationState,
        currentConversation: List<CustomProviderTurn>
    ) {
        state.lastConversationKeys = if (state.lastResponseId != null) {
            currentConversation.map(CustomProviderTurn::incrementalKey)
        } else {
            emptyList()
        }
    }

    fun rememberSuccessfulResponse(
        state: CustomProviderResponsesContinuationState,
        responseId: String?,
        currentConversation: List<CustomProviderTurn>,
        selectedModel: String,
        toolExecutionEnabled: Boolean,
        systemPrompt: String,
        apiType: CustomProviderApiType,
        responsesContinuationEnabledForSession: Boolean
    ) {
        if (
            !CustomProviderCompatibilitySupport.shouldUseResponsesContinuation(
                requestMode = ProviderRequestMode.Default,
                apiType = apiType,
                responsesContinuationEnabledForSession = responsesContinuationEnabledForSession
            )
        ) {
            return
        }

        val normalizedResponseId = responseId?.trim().orEmpty()
        if (normalizedResponseId.isBlank()) {
            clear(state)
            return
        }

        state.lastRequestSignature = buildRequestSignature(
            selectedModel = selectedModel,
            toolExecutionEnabled = toolExecutionEnabled,
            systemPrompt = systemPrompt
        )
        state.lastResponseId = normalizedResponseId
        state.lastConversationKeys = currentConversation.map(CustomProviderTurn::incrementalKey)
    }

    fun buildRequestPlan(
        state: CustomProviderResponsesContinuationState,
        currentConversation: List<CustomProviderTurn>,
        selectedModel: String,
        toolExecutionEnabled: Boolean,
        systemPrompt: String,
        requestMode: ProviderRequestMode,
        apiType: CustomProviderApiType,
        responsesContinuationEnabledForSession: Boolean
    ): CustomProviderResponsesRequestPlan {
        val currentConversationKeys = currentConversation.map(CustomProviderTurn::incrementalKey)
        val requestSignature = buildRequestSignature(
            selectedModel = selectedModel,
            toolExecutionEnabled = toolExecutionEnabled,
            systemPrompt = systemPrompt
        )

        val previousResponseId = state.lastResponseId?.takeIf {
            CustomProviderCompatibilitySupport.shouldUseResponsesContinuation(
                requestMode = requestMode,
                apiType = apiType,
                responsesContinuationEnabledForSession = responsesContinuationEnabledForSession
            ) && requestSignature == state.lastRequestSignature
        }

        val incrementalTurns = previousResponseId
            ?.takeIf {
                state.lastConversationKeys.isNotEmpty() &&
                    currentConversationKeys.size > state.lastConversationKeys.size &&
                    currentConversationKeys.subList(0, state.lastConversationKeys.size) == state.lastConversationKeys
            }
            ?.let { currentConversation.drop(state.lastConversationKeys.size) }

        return CustomProviderResponsesRequestPlan(
            turns = incrementalTurns ?: currentConversation,
            validateToolCallPairing = incrementalTurns == null,
            previousResponseId = previousResponseId?.takeIf { incrementalTurns != null },
            requestSignature = requestSignature
        )
    }

    private fun buildRequestSignature(
        selectedModel: String,
        toolExecutionEnabled: Boolean,
        systemPrompt: String
    ): String {
        return buildString {
            append(selectedModel)
            append('|')
            append(toolExecutionEnabled)
            append('|')
            append(systemPrompt)
        }
    }
}
