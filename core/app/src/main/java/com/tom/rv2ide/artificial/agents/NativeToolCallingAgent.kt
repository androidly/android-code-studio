package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.tools.AIToolCall

interface NativeToolCallingAgent {
    suspend fun startToolSessionTurn(
        prompt: String,
        toolExecutionEnabled: Boolean,
        listener: AIAgentStreamListener
    ): Result<NativeToolTurnResponse>

    suspend fun continueToolSessionTurn(
        toolResults: List<NativeToolResult>,
        toolExecutionEnabled: Boolean,
        listener: AIAgentStreamListener
    ): Result<NativeToolTurnResponse>

    fun abortActiveToolSessionTurn() {}
}

data class NativeToolTurnResponse(
    val assistantText: String,
    val toolCalls: List<AIToolCall>
)

data class NativeToolResult(
    val toolCallId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean
)
