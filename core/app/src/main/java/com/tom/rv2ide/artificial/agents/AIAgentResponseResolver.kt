package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolCallParser
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult

internal interface AIAgentResponseResolverHost {
    val currentAgent: AIAgent?
    val toolExecutor: com.tom.rv2ide.artificial.tools.AIToolExecutor
    val permissionManager: AIPermissionManager

    fun currentNativeToolAgent(): NativeToolCallingAgent?
    fun buildSessionContext(): String
    fun recordSessionTurn(role: AgentSessionRole, content: String)
    fun summarizeToolResult(toolResult: AIToolExecutionResult): String
}

internal class AIAgentResponseResolver(
    private val host: AIAgentResponseResolverHost
) {

    suspend fun resolveAgentResponse(
        userRequest: String,
        callback: AIAgentManager.AIAgentCallback
    ): Result<ResolvedAgentResponse> {
        host.currentNativeToolAgent()?.let { nativeAgent ->
            return resolveNativeToolAgentResponse(
                nativeAgent = nativeAgent,
                userRequest = userRequest,
                callback = callback
            )
        }

        val toolResults = mutableListOf<AIToolExecutionResult>()
        val toolModifications = mutableListOf<BaseFileModification>()
        val toolLoopTracker = AIToolLoopGuard.Tracker()
        var successfulFileChangeCount = 0

        while (true) {
            val context = AIAgentToolPresentationSupport.buildToolContext(
                toolExecutionEnabled = host.permissionManager.isToolExecutionEnabled(),
                sessionContext = host.buildSessionContext(),
                toolResults = toolResults
            )
            val previewController = createAssistantPreviewController(callback)

            val result = host.currentAgent?.generateCodeStreaming(
                prompt = userRequest,
                context = context,
                language = "kotlin",
                projectStructure = null,
                listener = object : AIAgentStreamListener {
                    override fun onTextDelta(delta: String) {
                        previewController.onTextDelta(delta)
                    }

                    override fun onToolCallStarted(toolCall: AIToolCall) {
                        callback.onToolCallStarted(toolCall)
                    }

                    override fun onToolCallOutput(toolCall: AIToolCall, chunk: String) {
                        callback.onToolCallOutput(toolCall, chunk)
                    }

                    override fun onToolCallCompleted(result: AIToolExecutionResult) {
                        callback.onToolCallCompleted(result)
                    }
                }
            ) ?: Result.failure(Exception("No agent initialized"))

            result.exceptionOrNull()?.let { return Result.failure(it) }
            val response = result.getOrNull() ?: return Result.failure(Exception("Empty AI response"))
            previewController.finish(response)

            val toolCalls = AIToolCallParser.parseToolCalls(response)
            if (toolCalls.isEmpty()) {
                return Result.success(
                    ResolvedAgentResponse(
                        response = response,
                        toolModifications = toolModifications.toList(),
                        toolResults = toolResults.toList()
                    )
                )
            }

            if (!host.permissionManager.isToolExecutionEnabled()) {
                return Result.failure(
                    ToolExecutionDisabledException(
                        "AI requested a tool, but AI Tool Execution is disabled. Enable it in AI preferences first."
                    )
                )
            }

            if (response.contains("FILE_TO_MODIFY:")) {
                return Result.failure(
                    ToolProtocolException(
                        "The model mixed TOOL_CALL and FILE_TO_MODIFY in one response. It must finish tool requests before sending edits."
                    )
                )
            }

            toolLoopTracker.registerToolCalls(toolCalls)?.let { loopMessage ->
                return Result.success(
                    finalizeToolRunWithCurrentContext(
                        userRequest = userRequest,
                        callback = callback,
                        toolResults = toolResults,
                        toolModifications = toolModifications,
                        loopReason = loopMessage
                    )
                )
            }
            callback.onProcessing("Executing ${toolCalls.size} tool request(s)...")

            toolCalls.forEach { toolCall ->
                callback.onToolCallStarted(toolCall)
                callback.onProcessing(AIAgentToolPresentationSupport.formatToolStartMessage(toolCall))
                val toolResult = host.toolExecutor.execute(toolCall) { chunk ->
                    callback.onToolCallOutput(toolCall, chunk)
                }
                toolLoopTracker.registerToolResult(
                    toolResult = toolResult,
                    successfulFileChangeCount = successfulFileChangeCount
                )?.let { loopMessage ->
                    return Result.success(
                        finalizeToolRunWithCurrentContext(
                            userRequest = userRequest,
                            callback = callback,
                            toolResults = toolResults + toolResult,
                            toolModifications = toolModifications,
                            loopReason = loopMessage
                        )
                    )
                }
                toolResults += toolResult
                host.recordSessionTurn(AgentSessionRole.TOOL, host.summarizeToolResult(toolResult))
                toolResult.fileChange?.let { fileChange ->
                    val fileName = java.io.File(fileChange.filePath).name
                    callback.onFileModifying(fileChange.filePath, fileName)
                    val writeSuccessful = fileChange.writeResult is FileWriteResult.Success
                    callback.onFileModified(fileChange.filePath, fileName, writeSuccessful)
                    if (writeSuccessful) {
                        successfulFileChangeCount += 1
                    }
                    toolModifications += BaseFileModification(
                        filePath = fileChange.filePath,
                        content = fileChange.newContent,
                        writeResult = fileChange.writeResult,
                        previousContent = fileChange.previousContent
                    )
                }
                callback.onToolCallCompleted(toolResult)
                callback.onProcessing(AIAgentToolPresentationSupport.formatToolEndMessage(toolResult))
            }
        }
    }

    private suspend fun resolveNativeToolAgentResponse(
        nativeAgent: NativeToolCallingAgent,
        userRequest: String,
        callback: AIAgentManager.AIAgentCallback
    ): Result<ResolvedAgentResponse> {
        val toolResults = mutableListOf<AIToolExecutionResult>()
        val toolModifications = mutableListOf<BaseFileModification>()
        val toolLoopTracker = AIToolLoopGuard.Tracker()
        var successfulFileChangeCount = 0
        var pendingToolResults = emptyList<NativeToolResult>()

        var round = 0
        try {
            while (true) {
                val previewController = createNativeAssistantPreviewController(callback)

                val result = if (round == 0) {
                    nativeAgent.startToolSessionTurn(
                        prompt = userRequest,
                        toolExecutionEnabled = host.permissionManager.isToolExecutionEnabled(),
                        listener = object : AIAgentStreamListener {
                            override fun onTextDelta(delta: String) {
                                previewController.onTextDelta(delta)
                            }
                        }
                    )
                } else {
                    nativeAgent.continueToolSessionTurn(
                        toolResults = pendingToolResults,
                        toolExecutionEnabled = host.permissionManager.isToolExecutionEnabled(),
                        listener = object : AIAgentStreamListener {
                            override fun onTextDelta(delta: String) {
                                previewController.onTextDelta(delta)
                            }
                        }
                    )
                }

                result.exceptionOrNull()?.let {
                    nativeAgent.abortActiveToolSessionTurn()
                    return Result.failure(it)
                }

                val response = result.getOrNull()
                    ?: run {
                        nativeAgent.abortActiveToolSessionTurn()
                        return Result.failure(Exception("Empty AI response"))
                    }
                previewController.finish(response.assistantText)

                val toolCalls = response.toolCalls
                if (toolCalls.isEmpty()) {
                    return Result.success(
                        ResolvedAgentResponse(
                            response = response.assistantText,
                            toolModifications = toolModifications.toList(),
                            toolResults = toolResults.toList()
                        )
                    )
                }

                if (!host.permissionManager.isToolExecutionEnabled()) {
                    nativeAgent.abortActiveToolSessionTurn()
                    return Result.failure(
                        ToolExecutionDisabledException(
                            "AI requested a tool, but AI Tool Execution is disabled. Enable it in AI preferences first."
                        )
                    )
                }

                toolLoopTracker.registerToolCalls(toolCalls)?.let { loopMessage ->
                    nativeAgent.abortActiveToolSessionTurn()
                    return Result.success(
                        finalizeToolRunWithCurrentContext(
                            userRequest = userRequest,
                            callback = callback,
                            toolResults = toolResults,
                            toolModifications = toolModifications,
                            loopReason = loopMessage
                        )
                    )
                }
                callback.onProcessing("Executing ${toolCalls.size} tool request(s)...")

                val roundToolResults = mutableListOf<NativeToolResult>()
                toolCalls.forEachIndexed { index, toolCall ->
                    callback.onToolCallStarted(toolCall)
                    callback.onProcessing(AIAgentToolPresentationSupport.formatToolStartMessage(toolCall))
                    val toolResult = host.toolExecutor.execute(toolCall) { chunk ->
                        callback.onToolCallOutput(toolCall, chunk)
                    }
                    toolLoopTracker.registerToolResult(
                        toolResult = toolResult,
                        successfulFileChangeCount = successfulFileChangeCount
                    )?.let { loopMessage ->
                        nativeAgent.abortActiveToolSessionTurn()
                        return Result.success(
                            finalizeToolRunWithCurrentContext(
                                userRequest = userRequest,
                                callback = callback,
                                toolResults = toolResults + toolResult,
                                toolModifications = toolModifications,
                                loopReason = loopMessage
                            )
                        )
                    }
                    toolResults += toolResult
                    host.recordSessionTurn(AgentSessionRole.TOOL, host.summarizeToolResult(toolResult))
                    toolResult.fileChange?.let { fileChange ->
                        val fileName = java.io.File(fileChange.filePath).name
                        callback.onFileModifying(fileChange.filePath, fileName)
                        val writeSuccessful = fileChange.writeResult is FileWriteResult.Success
                        callback.onFileModified(fileChange.filePath, fileName, writeSuccessful)
                        if (writeSuccessful) {
                            successfulFileChangeCount += 1
                        }
                        toolModifications += BaseFileModification(
                            filePath = fileChange.filePath,
                            content = fileChange.newContent,
                            writeResult = fileChange.writeResult,
                            previousContent = fileChange.previousContent
                        )
                    }
                    callback.onToolCallCompleted(toolResult)
                    callback.onProcessing(AIAgentToolPresentationSupport.formatToolEndMessage(toolResult))
                    roundToolResults += NativeToolResult(
                        toolCallId = toolCall.callId ?: "native_call_${round + 1}_${index + 1}",
                        toolName = toolCall.name,
                        output = toolResult.toToolResultPayload(),
                        isError = !toolResult.success
                    )
                }
                pendingToolResults = roundToolResults.toList()
                round += 1
            }
        } catch (error: Exception) {
            nativeAgent.abortActiveToolSessionTurn()
            return Result.failure(error)
        }
    }

    private suspend fun finalizeToolRunWithCurrentContext(
        userRequest: String,
        callback: AIAgentManager.AIAgentCallback,
        toolResults: List<AIToolExecutionResult>,
        toolModifications: List<BaseFileModification>,
        loopReason: String
    ): ResolvedAgentResponse {
        callback.onProcessing("Stopping repeated tool calls and producing a final answer from the current context...")

        val forcedResponse = attemptForcedFinalAnswer(
            userRequest = userRequest,
            callback = callback,
            toolResults = toolResults,
            toolModifications = toolModifications,
            loopReason = loopReason
        )?.trim()

        val finalResponse = forcedResponse
            ?.takeIf { it.isNotBlank() && !AIAgentToolPresentationSupport.containsProtocolBlocks(it) }
            ?: AIAgentToolPresentationSupport.buildForcedFinalFallback(
                toolResults = toolResults,
                toolModifications = toolModifications,
                loopReason = loopReason
            )

        return ResolvedAgentResponse(
            response = finalResponse,
            toolModifications = toolModifications.toList(),
            toolResults = toolResults.toList()
        )
    }

    private suspend fun attemptForcedFinalAnswer(
        userRequest: String,
        callback: AIAgentManager.AIAgentCallback,
        toolResults: List<AIToolExecutionResult>,
        toolModifications: List<BaseFileModification>,
        loopReason: String
    ): String? {
        val previewController = createAssistantPreviewController(callback)

        val result = host.currentAgent?.generateCodeStreaming(
            prompt = AIAgentToolPresentationSupport.buildForcedFinalizationPrompt(userRequest, loopReason),
            context = AIAgentToolPresentationSupport.buildForcedFinalizationContext(
                sessionContext = host.buildSessionContext(),
                toolResults = toolResults,
                toolModifications = toolModifications,
                loopReason = loopReason
            ),
            language = "kotlin",
            projectStructure = null,
            listener = object : AIAgentStreamListener {
                override fun onTextDelta(delta: String) {
                    previewController.onTextDelta(delta)
                }
            }
        ) ?: return null

        val response = result.getOrNull()?.trim().orEmpty()
        if (response.isBlank()) {
            return null
        }
        previewController.finish(response)

        return response
    }
}
