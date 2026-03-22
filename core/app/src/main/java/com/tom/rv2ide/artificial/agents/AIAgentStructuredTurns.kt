package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.tools.AIToolCall

data class AIAgentStructuredTurnRequest(
    val userMessage: String? = null,
    val runtimeContext: String? = null,
    val allowTools: Boolean = true,
    val toolResults: List<AIAgentToolResultMessage> = emptyList()
)

data class AIAgentStructuredTurnResponse(
    val assistantText: String,
    val toolCalls: List<AIToolCall> = emptyList(),
    val usedNativeToolCalls: Boolean = false
)

data class AIAgentToolResultMessage(
    val toolCallId: String?,
    val toolName: String,
    val content: String,
    val isError: Boolean
)
