package com.tom.rv2ide.fragments.assistant

import com.tom.rv2ide.artificial.agents.AIAgentManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal interface AIAssistantPromptExecutionHost {
    fun touchActiveSession()
    fun clearPromptDraftForDispatch()
    fun clearReviewableChanges()
    fun appendAndRecordUserPrompt(prompt: String)
    fun recordAssistantReply()
    fun applyModificationResults(modifications: List<AIAgentManager.ModificationResult>)
    fun buildModificationSummaryText(summary: AIAgentManager.ModificationSummary): String
    fun appendStatusItem(
        title: String,
        body: String? = null,
        tone: AIAssistantTone = AIAssistantTone.NEUTRAL
    )
    fun refreshFileIfNeeded(filePath: String)
    fun publishState()
}

internal class AIAssistantPromptExecutionController(
    private val scope: CoroutineScope,
    private val aiAgent: AIAgentManager,
    private val streamingTurnController: AIAssistantStreamingTurnController,
    private val host: AIAssistantPromptExecutionHost
) {
    private var executionJob: Job? = null
    private var nextExecutionToken: Long = 1L
    private var activeExecutionToken: Long? = null
    private val pendingPromptQueue = ArrayDeque<QueuedPrompt>()

    fun isRunning(): Boolean = executionJob?.isActive == true

    fun isExecutionActiveForStreaming(): Boolean {
        return executionJob == null || executionJob?.isActive == true
    }

    fun queuedPromptCount(): Int = pendingPromptQueue.size

    fun executePrompt(prompt: String): AIAssistantPromptDispatchResult {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isBlank()) {
            return AIAssistantPromptDispatchResult.REJECTED_EMPTY
        }
        return if (isRunning()) {
            if (enqueuePrompt(normalizedPrompt)) {
                AIAssistantPromptDispatchResult.QUEUED
            } else {
                AIAssistantPromptDispatchResult.QUEUE_FULL
            }
        } else {
            startPromptExecution(normalizedPrompt, appendUserItem = true)
            AIAssistantPromptDispatchResult.STARTED
        }
    }

    fun interruptAndExecutePrompt(prompt: String): AIAssistantPromptDispatchResult {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isBlank()) {
            return AIAssistantPromptDispatchResult.REJECTED_EMPTY
        }
        if (!isRunning()) {
            return executePrompt(normalizedPrompt)
        }
        if (!enqueuePrompt(normalizedPrompt, addToFront = true)) {
            return AIAssistantPromptDispatchResult.QUEUE_FULL
        }
        cancelExecution(
            manualStop = true,
            showStoppedStatus = true,
            clearQueuedPrompts = false,
            continueWithQueuedPrompts = true
        )
        return AIAssistantPromptDispatchResult.INTERRUPTED
    }

    fun stopExecution(): Int {
        return cancelExecution(
            manualStop = true,
            showStoppedStatus = true,
            clearQueuedPrompts = true,
            continueWithQueuedPrompts = false
        )
    }

    fun cancelExecution(
        manualStop: Boolean,
        showStoppedStatus: Boolean,
        clearQueuedPrompts: Boolean,
        continueWithQueuedPrompts: Boolean
    ): Int {
        activeExecutionToken = null
        executionJob?.cancel()
        executionJob = null
        if (manualStop) {
            streamingTurnController.completeAssistantStream(null, "Manual stop requested")
        }
        streamingTurnController.markPendingToolsCancelled()
        streamingTurnController.clearCompletedToolTracking()
        if (manualStop && showStoppedStatus) {
            host.appendStatusItem(
                title = "Stopped",
                body = "Manual stop requested",
                tone = AIAssistantTone.WARNING
            )
        }
        val clearedQueuedPromptCount = if (clearQueuedPrompts) {
            clearPendingPromptQueue()
        } else {
            0
        }
        if (!continueWithQueuedPrompts || !startNextQueuedPromptIfIdle()) {
            host.publishState()
        }
        return clearedQueuedPromptCount
    }

    fun clearPendingPromptQueue(): Int {
        val clearedCount = pendingPromptQueue.size
        pendingPromptQueue.clear()
        return clearedCount
    }

    fun dispose() {
        activeExecutionToken = null
        executionJob?.cancel()
        executionJob = null
        pendingPromptQueue.clear()
    }

    private fun enqueuePrompt(
        prompt: String,
        addToFront: Boolean = false
    ): Boolean {
        if (pendingPromptQueue.size >= MAX_QUEUED_PROMPTS) {
            return false
        }
        host.touchActiveSession()
        host.clearPromptDraftForDispatch()
        host.appendAndRecordUserPrompt(prompt)
        val queuedPrompt = QueuedPrompt(
            prompt = prompt,
            userMessageAlreadyAppended = true
        )
        if (addToFront) {
            pendingPromptQueue.addFirst(queuedPrompt)
        } else {
            pendingPromptQueue.addLast(queuedPrompt)
        }
        host.publishState()
        return true
    }

    private fun startNextQueuedPromptIfIdle(): Boolean {
        if (isRunning()) {
            return false
        }
        val nextPrompt = pendingPromptQueue.removeFirstOrNull() ?: return false
        startPromptExecution(
            prompt = nextPrompt.prompt,
            appendUserItem = !nextPrompt.userMessageAlreadyAppended
        )
        return true
    }

    private fun startPromptExecution(
        prompt: String,
        appendUserItem: Boolean
    ) {
        host.touchActiveSession()
        val executionToken = beginExecutionToken()

        host.clearPromptDraftForDispatch()
        host.clearReviewableChanges()
        streamingTurnController.resetRunTracking()
        if (appendUserItem) {
            host.appendAndRecordUserPrompt(prompt)
        }
        streamingTurnController.startAssistantStream("Preparing assistant run")
        host.publishState()

        executionJob = scope.launch {
            try {
                aiAgent.executeRequest(prompt, buildAgentCallback(executionToken))
            } catch (_: CancellationException) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.completeAssistantStream(
                        null,
                        "Assistant run was cancelled"
                    )
                    streamingTurnController.markPendingToolsCancelled()
                    streamingTurnController.clearCompletedToolTracking()
                    onExecutionFinished(executionToken)
                }
            } catch (error: Exception) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.completeAssistantStream(
                        null,
                        "Unexpected exception interrupted the run."
                    )
                    streamingTurnController.markPendingToolsCancelled()
                    host.appendStatusItem(
                        title = "Exception",
                        body = error.message ?: "Unexpected error",
                        tone = AIAssistantTone.ERROR
                    )
                    streamingTurnController.clearCompletedToolTracking()
                    onExecutionFinished(executionToken)
                }
            }
        }
    }

    private fun buildAgentCallback(executionToken: Long): AIAgentManager.AIAgentCallback {
        return object : AIAgentManager.AIAgentCallback {
            override fun onProcessing(message: String) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.updateAssistantWorkingStatus(message)
                }
            }

            override fun onFileModifying(filePath: String, fileName: String) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.updateAssistantWorkingStatus("Editing $fileName")
                }
            }

            override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.updateAssistantWorkingStatus(
                        if (success) "Updated $fileName" else "Failed to update $fileName"
                    )
                    if (success) {
                        host.refreshFileIfNeeded(filePath)
                    }
                }
            }

            override fun onAssistantTextStarted() {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.ensureAssistantStreamStarted()
                }
            }

            override fun onAssistantTextDelta(delta: String) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.appendAssistantDelta(delta)
                }
            }

            override fun onAssistantTextFinished(fullResponse: String) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.replaceAssistantResponse(fullResponse)
                }
            }

            override fun onSuccess(
                response: String,
                modifications: List<AIAgentManager.ModificationResult>,
                summary: AIAgentManager.ModificationSummary
            ) {
                postExecutionUpdate(executionToken) {
                    host.applyModificationResults(modifications)
                    val fallbackText = response.takeIf {
                        it.isNotBlank() && !it.contains("FILE_TO_MODIFY:")
                    } ?: host.buildModificationSummaryText(summary)
                    streamingTurnController.completeAssistantStream(response, fallbackText)
                    host.recordAssistantReply()
                    streamingTurnController.clearCompletedToolTracking()
                    onExecutionFinished(executionToken)
                }
            }

            override fun onTextResponse(
                response: String,
                summary: AIAgentManager.ModificationSummary
            ) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.completeAssistantStream(response, response)
                    host.recordAssistantReply()
                    onExecutionFinished(executionToken)
                }
            }

            override fun onError(message: String) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.completeAssistantStream(
                        null,
                        "Run failed before the assistant produced a final reply."
                    )
                    streamingTurnController.markPendingToolsCancelled()
                    host.appendStatusItem(
                        title = "Error",
                        body = message,
                        tone = AIAssistantTone.ERROR
                    )
                    streamingTurnController.clearCompletedToolTracking()
                    onExecutionFinished(executionToken)
                }
            }

            override fun onRetry(attemptNumber: Int, message: String) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.updateAssistantWorkingStatus("Retry #$attemptNumber")
                    host.appendStatusItem(
                        title = "Retry #$attemptNumber",
                        body = message,
                        tone = AIAssistantTone.WARNING
                    )
                    streamingTurnController.moveActiveStreamToEnd()
                    host.publishState()
                }
            }

            override fun onToolCallStarted(toolCall: com.tom.rv2ide.artificial.tools.AIToolCall) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.onToolCallStarted(toolCall)
                }
            }

            override fun onToolCallOutput(
                toolCall: com.tom.rv2ide.artificial.tools.AIToolCall,
                chunk: String
            ) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.onToolCallOutput(toolCall, chunk)
                }
            }

            override fun onToolCallCompleted(result: com.tom.rv2ide.artificial.tools.AIToolExecutionResult) {
                postExecutionUpdate(executionToken) {
                    streamingTurnController.onToolCallCompleted(result)
                }
            }
        }
    }

    private fun postExecutionUpdate(executionToken: Long, block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            if (activeExecutionToken != executionToken) {
                return@launch
            }
            block()
        }
    }

    private fun beginExecutionToken(): Long {
        val executionToken = nextExecutionToken++
        activeExecutionToken = executionToken
        return executionToken
    }

    private fun finishExecutionToken(executionToken: Long) {
        if (activeExecutionToken == executionToken) {
            activeExecutionToken = null
        }
    }

    private fun onExecutionFinished(executionToken: Long) {
        finishExecutionToken(executionToken)
        executionJob = null
        if (!startNextQueuedPromptIfIdle()) {
            host.publishState()
        }
    }

    private data class QueuedPrompt(
        val prompt: String,
        val userMessageAlreadyAppended: Boolean
    )

    private companion object {
        private const val MAX_QUEUED_PROMPTS = 8
    }
}
