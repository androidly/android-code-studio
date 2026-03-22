package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult

data class AINativeToolResult(
    val toolCall: AIToolCall,
    val executionResult: AIToolExecutionResult
)

data class AINativeTurnRequest(
    val userMessage: String? = null,
    val toolResults: List<AINativeToolResult> = emptyList()
)

data class AINativeTurnResponse(
    val assistantText: String = "",
    val toolCalls: List<AIToolCall> = emptyList()
)

interface AINativeToolAgent {
    suspend fun executeTurn(
        request: AINativeTurnRequest,
        listener: AIAgentStreamListener? = null
    ): Result<AINativeTurnResponse>
}
