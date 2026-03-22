package com.tom.rv2ide.fragments.assistant

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AIAssistantConsoleViewModel(application: Application) : AndroidViewModel(application) {
    val aiAgent: AIAgentManager = AIAgentManager(application)
    private val timelineStore = AIAssistantTimelineStore(application)

    private val _uiState = MutableStateFlow(AIAssistantConsoleUiState())
    val uiState: StateFlow<AIAssistantConsoleUiState> = _uiState.asStateFlow()

    private val timelineItems = mutableListOf<AIAssistantTimelineItem>()
    private var executionJob: Job? = null
    private var activeWorkingTickerJob: Job? = null
    private var lastModifications: List<AIAgentManager.ModificationResult> = emptyList()
    private var projectRootPath: String = ""
    private var promptDraft: String = ""
    private var activeStreamItemId: Long? = null
    private var activeStreamStartedAtMs: Long = 0L
    private var activeProgressMessage: String? = null
    private var activeTimelineSessionKey: String? = null
    private val pendingTools = ArrayDeque<PendingToolItem>()
    private val toolOutputBuffers = mutableMapOf<Long, StringBuilder>()
    private var fileRefreshHandler: ((String) -> Unit)? = null
    private var timelinePersistJob: Job? = null
    private var visibleTimelineStartIndex: Int = 0
    private var nextExecutionToken: Long = 1L
    private var activeExecutionToken: Long? = null

    init {
        publishState()
    }

    fun bindProject(projectRoot: String) {
        if (projectRoot.isBlank() || projectRoot == projectRootPath) {
            syncTimelineSession()
            publishState()
            return
        }
        projectRootPath = projectRoot
        aiAgent.setProjectRoot(projectRoot)
        syncTimelineSession(force = true)
        publishState()
    }

    fun setFileRefreshHandler(handler: ((String) -> Unit)?) {
        fileRefreshHandler = handler
    }

    fun lastModificationsSnapshot(): List<AIAgentManager.ModificationResult> {
        return lastModifications.toList()
    }

    fun updatePromptDraft(draft: String) {
        if (promptDraft == draft) {
            return
        }
        promptDraft = draft
        publishState()
    }

    fun ensureWelcome() {
        if (timelineItems.isEmpty()) {
            timelineItems += AIAssistantWelcomeItem(
                title = "AI Assistant",
                body = "Persistent Codex-style session with focused reads, tool execution, streaming output, and reviewable diffs. Try /help, /review, /stop, or ask it to build and patch a specific file range."
            )
        }
        publishState()
    }

    fun showCommandsHelp() {
        timelineItems += AIAssistantStatusItem(
            title = "Commands",
            body = "/help  /review  /stop  /clear",
            tone = AIAssistantTone.NEUTRAL
        )
        publishState()
    }

    fun loadOlderHistory(): Boolean {
        val currentStartIndex = currentVisibleTimelineStartIndex()
        if (currentStartIndex <= 0) {
            return false
        }
        visibleTimelineStartIndex = (currentStartIndex - TIMELINE_HISTORY_PAGE_SIZE)
            .coerceAtLeast(0)
        publishState(persistTimeline = false)
        return true
    }

    fun collapseHistoryToLatest(): Boolean {
        val latestStartIndex = latestVisibleTimelineStartIndex()
        if (currentVisibleTimelineStartIndex() == latestStartIndex) {
            return false
        }
        visibleTimelineStartIndex = latestStartIndex
        publishState(persistTimeline = false)
        return true
    }

    fun clearTimeline() {
        cancelExecution(manualStop = false, showStoppedStatus = false)
        lastModifications = emptyList()
        aiAgent.clearConversation()
        promptDraft = ""
        resetRunTracking()
        resetVisibleTimelineWindow()
        timelineItems.clear()
        activeTimelineSessionKey?.let(timelineStore::clear)
        ensureWelcome()
        publishState()
    }

    fun stopExecution() {
        cancelExecution(manualStop = true, showStoppedStatus = true)
    }

    fun executePrompt(prompt: String) {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isBlank() || executionJob?.isActive == true) {
            return
        }
        val executionToken = beginExecutionToken()

        promptDraft = ""
        lastModifications = emptyList()
        resetRunTracking()
        timelineItems += AIAssistantUserItem(normalizedPrompt)
        startAssistantStream("Preparing assistant run")
        resetVisibleTimelineWindow()
        publishState()

        executionJob = viewModelScope.launch {
            try {
                aiAgent.executeRequest(normalizedPrompt, object : AIAgentManager.AIAgentCallback {
                    override fun onProcessing(message: String) {
                        postExecutionUpdate(executionToken) {
                            updateAssistantWorkingStatus(message)
                        }
                    }

                    override fun onFileModifying(filePath: String, fileName: String) {
                        postExecutionUpdate(executionToken) {
                            updateAssistantWorkingStatus("Editing $fileName")
                        }
                    }

                    override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                        postExecutionUpdate(executionToken) {
                            updateAssistantWorkingStatus(
                                if (success) "Updated $fileName" else "Failed to update $fileName"
                            )
                            if (success) {
                                fileRefreshHandler?.invoke(filePath)
                            }
                        }
                    }

                    override fun onAssistantTextStarted() {
                        postExecutionUpdate(executionToken) {
                            startAssistantStream(activeProgressMessage ?: "Preparing assistant run")
                        }
                    }

                    override fun onAssistantTextDelta(delta: String) {
                        postExecutionUpdate(executionToken) {
                            appendAssistantDelta(delta)
                        }
                    }

                    override fun onAssistantTextFinished(fullResponse: String) {
                        postExecutionUpdate(executionToken) {
                            replaceAssistantResponse(fullResponse)
                        }
                    }

                    override fun onSuccess(
                        response: String,
                        modifications: List<AIAgentManager.ModificationResult>,
                        summary: AIAgentManager.ModificationSummary
                    ) {
                        postExecutionUpdate(executionToken) {
                            lastModifications = modifications
                            appendDiffItems(modifications)
                            val fallbackText = response.takeIf {
                                it.isNotBlank() && !it.contains("FILE_TO_MODIFY:")
                            } ?: buildModificationSummaryText(summary)
                            completeAssistantStream(response, fallbackText)
                            clearCompletedToolTracking()
                            finishExecutionToken(executionToken)
                            executionJob = null
                            publishState()
                        }
                    }

                    override fun onTextResponse(
                        response: String,
                        summary: AIAgentManager.ModificationSummary
                    ) {
                        postExecutionUpdate(executionToken) {
                            completeAssistantStream(response, response)
                            finishExecutionToken(executionToken)
                            executionJob = null
                            publishState()
                        }
                    }

                    override fun onError(message: String) {
                        postExecutionUpdate(executionToken) {
                            completeAssistantStream(null, "Run failed before the assistant produced a final reply.")
                            markPendingToolsCancelled()
                            timelineItems += AIAssistantStatusItem(
                                title = "Error",
                                body = message,
                                tone = AIAssistantTone.ERROR
                            )
                            clearCompletedToolTracking()
                            finishExecutionToken(executionToken)
                            executionJob = null
                            publishState()
                        }
                    }

                    override fun onRetry(attemptNumber: Int, message: String) {
                        postExecutionUpdate(executionToken) {
                            updateAssistantWorkingStatus("Retry #$attemptNumber")
                            timelineItems += AIAssistantStatusItem(
                                title = "Retry #$attemptNumber",
                                body = message,
                                tone = AIAssistantTone.WARNING
                            )
                            activeStreamItemId?.let(::moveTimelineItemToEnd)
                            publishState()
                        }
                    }

                    override fun onToolCallStarted(toolCall: AIToolCall) {
                        postExecutionUpdate(executionToken) {
                            val itemId = nextAIAssistantTimelineItemId()
                            replaceOrAppendStreamToolAttachment(
                                AIAssistantToolItem(
                                    title = buildToolTitle(toolCall.name),
                                    summary = toolCallSummary(toolCall),
                                    stage = AIAssistantToolStage.RUNNING,
                                    stageTrail = buildToolStageTrail(
                                        stage = AIAssistantToolStage.RUNNING,
                                        sawOutput = false
                                    ),
                                    command = toolInvocationSummary(toolCall),
                                    workingDirectory = toolCall.argument("workdir"),
                                    id = itemId
                                )
                            )
                            pendingTools.addLast(
                                PendingToolItem(
                                    key = toolTimelineKey(toolCall),
                                    itemId = itemId,
                                    startedAtMs = SystemClock.elapsedRealtime()
                                )
                            )
                            toolOutputBuffers[itemId] = StringBuilder()
                            updateAssistantWorkingStatus("Tool: ${buildToolTitle(toolCall.name)}")
                            publishState()
                        }
                    }

                    override fun onToolCallOutput(toolCall: AIToolCall, chunk: String) {
                        postExecutionUpdate(executionToken) {
                            val pendingTool = findPendingTool(toolTimelineKey(toolCall)) ?: return@postExecutionUpdate
                            val itemId = pendingTool.itemId
                            val outputBuffer = toolOutputBuffers.getOrPut(itemId) { StringBuilder() }
                            if (chunk.isNotBlank()) {
                                pendingTool.sawOutput = true
                                pendingTool.outputChunks += 1
                                if (outputBuffer.isNotEmpty()) {
                                    outputBuffer.append('\n')
                                }
                                outputBuffer.append(chunk)
                            }
                            trimOutputBuffer(outputBuffer)
                            updateStreamToolAttachment(itemId) { toolItem ->
                                val stage = if (pendingTool.sawOutput) {
                                    AIAssistantToolStage.STREAMING
                                } else {
                                    AIAssistantToolStage.RUNNING
                                }
                                toolItem.copy(
                                    summary = toolCallSummary(toolCall),
                                    stage = stage,
                                    stageTrail = buildToolStageTrail(
                                        stage = stage,
                                        sawOutput = pendingTool.sawOutput
                                    ),
                                    command = toolInvocationSummary(toolCall) ?: toolItem.command,
                                    workingDirectory = toolCall.argument("workdir") ?: toolItem.workingDirectory,
                                    outputPreview = outputBuffer.toString().trim().ifBlank { null }
                                )
                            }
                            publishState()
                        }
                    }

                    override fun onToolCallCompleted(result: AIToolExecutionResult) {
                        postExecutionUpdate(executionToken) {
                            val pendingTool = if (pendingTools.isEmpty()) null else pendingTools.removeFirst()
                            val itemId = pendingTool?.itemId ?: nextAIAssistantTimelineItemId()
                            val existingItem = findStreamToolAttachment(itemId)
                            val bufferText = pendingTool?.let { toolOutputBuffers[it.itemId]?.toString()?.trim() }.orEmpty()
                            val finalOutput = result.output
                                .takeIf { it.isNotBlank() }
                                ?.trim()
                                ?.takeLast(1400)
                                ?: bufferText.takeIf { it.isNotBlank() }?.takeLast(1400)
                            val sawOutput = (pendingTool?.sawOutput == true) || !finalOutput.isNullOrBlank()
                            val stage = if (result.success) {
                                AIAssistantToolStage.COMPLETED
                            } else {
                                AIAssistantToolStage.FAILED
                            }
                            val updatedItem = AIAssistantToolItem(
                                title = buildToolTitle(result.toolName),
                                summary = buildToolCompletionSummary(result, pendingTool),
                                stage = stage,
                                stageTrail = buildToolStageTrail(stage = stage, sawOutput = sawOutput),
                                command = result.executedCommand ?: existingItem?.command,
                                workingDirectory = result.workingDirectory ?: existingItem?.workingDirectory,
                                outputPreview = finalOutput,
                                id = itemId
                            )
                            replaceOrAppendStreamToolAttachment(updatedItem)
                            pendingTool?.let { toolOutputBuffers.remove(it.itemId) }
                            updateAssistantWorkingStatus(
                                if (result.success) {
                                    "Tool completed: ${buildToolTitle(result.toolName)}"
                                } else {
                                    "Tool failed: ${buildToolTitle(result.toolName)}"
                                }
                            )
                            publishState()
                        }
                    }
                })
            } catch (_: CancellationException) {
                postExecutionUpdate(executionToken) {
                    completeAssistantStream(null, "Assistant run was cancelled")
                    markPendingToolsCancelled()
                    clearCompletedToolTracking()
                    finishExecutionToken(executionToken)
                    executionJob = null
                    publishState()
                }
            } catch (error: Exception) {
                postExecutionUpdate(executionToken) {
                    completeAssistantStream(null, "Unexpected exception interrupted the run.")
                    markPendingToolsCancelled()
                    timelineItems += AIAssistantStatusItem(
                        title = "Exception",
                        body = error.message ?: "Unexpected error",
                        tone = AIAssistantTone.ERROR
                    )
                    clearCompletedToolTracking()
                    finishExecutionToken(executionToken)
                    executionJob = null
                    publishState()
                }
            }
        }

        publishState()
    }

    override fun onCleared() {
        flushTimelinePersistence()
        executionJob?.cancel()
        executionJob = null
        stopActiveWorkingTicker()
        timelinePersistJob?.cancel()
        timelinePersistJob = null
        fileRefreshHandler = null
        super.onCleared()
    }

    private fun publishState(persistTimeline: Boolean = true) {
        syncTimelineSession()
        val visibleTimelineItems = buildVisibleTimelineItems()
        val hiddenHistoryCount = hiddenTimelineItemCount()
        _uiState.value = AIAssistantConsoleUiState(
            providerLabel = aiAgent.getCurrentProviderName(),
            modelLabel = aiAgent.getCurrentModelName(),
            promptDraft = promptDraft,
            isRunning = executionJob?.isActive == true,
            hasReviewableChanges = lastModifications.isNotEmpty(),
            canLoadMoreHistory = hiddenHistoryCount > 0,
            hiddenHistoryCount = hiddenHistoryCount,
            totalTimelineCount = timelineItems.size,
            timelineItems = visibleTimelineItems
        )
        if (persistTimeline) {
            scheduleTimelinePersistence()
        }
    }

    private fun cancelExecution(manualStop: Boolean, showStoppedStatus: Boolean) {
        activeExecutionToken = null
        executionJob?.cancel()
        executionJob = null
        if (manualStop) {
            completeAssistantStream(null, "Manual stop requested")
        }
        markPendingToolsCancelled()
        clearCompletedToolTracking()
        if (manualStop && showStoppedStatus) {
            timelineItems += AIAssistantStatusItem(
                title = "Stopped",
                body = "Manual stop requested",
                tone = AIAssistantTone.WARNING
            )
        }
        publishState()
    }

    private fun appendDiffItems(modifications: List<AIAgentManager.ModificationResult>) {
        timelineItems += modifications.map { modification ->
            val preview = AIAssistantDiffPreview.fromContents(
                previousContent = modification.previousContent,
                newContent = modification.content
            )
            AIAssistantDiffItem(
                filePath = modification.filePath,
                changeLabel = if (modification.isNewFile) "Created" else "Updated",
                preview = preview
            )
        }
    }

    private fun startAssistantStream(initialPlaceholder: String = "Preparing assistant run") {
        val normalizedPlaceholder = initialPlaceholder.trim().ifBlank { "Preparing assistant run" }
        activeProgressMessage = normalizedPlaceholder
        if (activeStreamItemId != null) {
            refreshActiveAssistantStreamItem()
            publishState()
            return
        }

        activeStreamStartedAtMs = SystemClock.elapsedRealtime()
        activeStreamItemId = nextAIAssistantTimelineItemId()
        timelineItems += AIAssistantStreamingResponseItem(
            header = "Working",
            placeholder = normalizedPlaceholder,
            response = "",
            status = buildWorkingElapsedLabel(),
            isWorking = true,
            isStreaming = true,
            id = activeStreamItemId ?: nextAIAssistantTimelineItemId()
        )
        ensureActiveWorkingTicker()
        publishState()
    }

    private fun updateAssistantWorkingStatus(message: String) {
        val normalized = message.trim()
        if (normalized.isBlank()) {
            return
        }
        activeProgressMessage = normalized
        if (activeStreamItemId == null) {
            startAssistantStream(normalized)
            return
        }
        refreshActiveAssistantStreamItem()
        publishState()
    }

    private fun appendAssistantDelta(delta: String) {
        if (delta.isBlank()) {
            return
        }
        if (activeStreamItemId == null) {
            startAssistantStream(activeProgressMessage ?: "Preparing assistant run")
        }
        val itemId = activeStreamItemId ?: return
        updateTimelineItem(itemId) { existing ->
            val streamItem = existing as? AIAssistantStreamingResponseItem ?: return@updateTimelineItem existing
            streamItem.copy(
                header = "Working",
                placeholder = streamItem.placeholder,
                response = streamItem.response + delta,
                status = buildWorkingElapsedLabel(),
                isWorking = true,
                isStreaming = true
            )
        }
        moveTimelineItemToEnd(itemId)
        publishState()
    }

    private fun replaceAssistantResponse(fullResponse: String) {
        if (fullResponse.isBlank()) {
            return
        }
        if (activeStreamItemId == null) {
            startAssistantStream(activeProgressMessage ?: "Preparing assistant run")
        }
        val itemId = activeStreamItemId ?: return
        updateTimelineItem(itemId) { existing ->
            val streamItem = existing as? AIAssistantStreamingResponseItem ?: return@updateTimelineItem existing
            streamItem.copy(
                header = "Working",
                response = fullResponse,
                status = buildWorkingElapsedLabel(),
                isWorking = true,
                isStreaming = true
            )
        }
        moveTimelineItemToEnd(itemId)
        publishState()
    }

    private fun completeAssistantStream(finalResponse: String?, fallbackText: String?) {
        stopActiveWorkingTicker()
        val visibleFinalText = finalResponse
            ?.takeIf { it.isNotBlank() && !it.contains("FILE_TO_MODIFY:") && !it.contains("TOOL_CALL:") }
            ?.trim()
        val finalText = visibleFinalText ?: fallbackText?.trim().orEmpty()
        val itemId = activeStreamItemId
        activeProgressMessage = null

        if (itemId == null) {
            if (finalText.isBlank()) {
                return
            }
            timelineItems += AIAssistantStreamingResponseItem(
                header = "Assistant",
                placeholder = null,
                response = finalText,
                status = null,
                isWorking = false,
                isStreaming = false
            )
            publishState()
            return
        }

        updateTimelineItem(itemId) { existing ->
            val streamItem = existing as? AIAssistantStreamingResponseItem ?: return@updateTimelineItem existing
            val nextText = when {
                finalText.isNotBlank() -> finalText
                else -> streamItem.response
            }
            streamItem.copy(
                header = "Assistant",
                placeholder = if (nextText.isBlank()) {
                    finalText.ifBlank { streamItem.placeholder.orEmpty() }.ifBlank { null }
                } else {
                    null
                },
                response = nextText,
                status = null,
                isWorking = false,
                isStreaming = false
            )
        }
        moveTimelineItemToEnd(itemId)
        activeStreamItemId = null
        activeStreamStartedAtMs = 0L
        publishState()
    }

    private fun refreshActiveAssistantStreamItem() {
        val itemId = activeStreamItemId ?: return
        updateTimelineItem(itemId) { existing ->
            val streamItem = existing as? AIAssistantStreamingResponseItem ?: return@updateTimelineItem existing
            val hasResponse = streamItem.response.isNotBlank()
            streamItem.copy(
                header = "Working",
                placeholder = if (hasResponse) {
                    streamItem.placeholder
                } else {
                    activeProgressMessage ?: streamItem.placeholder
                },
                status = buildWorkingElapsedLabel(),
                isWorking = true,
                isStreaming = true
            )
        }
        moveTimelineItemToEnd(itemId)
    }

    private fun ensureActiveWorkingTicker() {
        if (activeWorkingTickerJob?.isActive == true) {
            return
        }
        activeWorkingTickerJob = viewModelScope.launch {
            while (activeStreamItemId != null && (executionJob == null || executionJob?.isActive == true)) {
                delay(1000L)
                if (activeStreamItemId == null || (executionJob != null && executionJob?.isActive != true)) {
                    break
                }
                refreshActiveAssistantStreamItem()
                publishState()
            }
        }
    }

    private fun stopActiveWorkingTicker() {
        activeWorkingTickerJob?.cancel()
        activeWorkingTickerJob = null
    }

    private fun buildWorkingElapsedLabel(): String {
        if (activeStreamStartedAtMs <= 0L) {
            return "0s"
        }
        return formatCompactElapsed((SystemClock.elapsedRealtime() - activeStreamStartedAtMs).coerceAtLeast(0L))
    }

    private fun updateTimelineItem(
        itemId: Long,
        transformer: (AIAssistantTimelineItem) -> AIAssistantTimelineItem
    ) {
        val index = timelineItems.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return
        }
        timelineItems[index] = transformer(timelineItems[index])
    }

    private fun replaceOrAppendStreamToolAttachment(item: AIAssistantToolItem) {
        ensureStreamItemForAttachment()
        val streamItemId = activeStreamItemId
            ?: timelineItems.lastOrNull { it is AIAssistantStreamingResponseItem }?.id
            ?: return
        updateTimelineItem(streamItemId) { existing ->
            val streamItem = existing as? AIAssistantStreamingResponseItem ?: return@updateTimelineItem existing
            val updatedAttachments = streamItem.attachments.toMutableList()
            val index = updatedAttachments.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                updatedAttachments[index] = item
            } else {
                updatedAttachments += item
            }
            streamItem.copy(attachments = updatedAttachments)
        }
        moveTimelineItemToEnd(streamItemId)
    }

    private fun updateStreamToolAttachment(
        attachmentId: Long,
        transformer: (AIAssistantToolItem) -> AIAssistantToolItem
    ) {
        val streamItemId = findStreamItemIdForAttachment(attachmentId) ?: return
        updateTimelineItem(streamItemId) { existing ->
            val streamItem = existing as? AIAssistantStreamingResponseItem ?: return@updateTimelineItem existing
            val updatedAttachments = streamItem.attachments.toMutableList()
            val index = updatedAttachments.indexOfFirst { it.id == attachmentId }
            if (index < 0) {
                return@updateTimelineItem existing
            }
            updatedAttachments[index] = transformer(updatedAttachments[index])
            streamItem.copy(attachments = updatedAttachments)
        }
        moveTimelineItemToEnd(streamItemId)
    }

    private fun findStreamItemIdForAttachment(attachmentId: Long): Long? {
        activeStreamItemId?.let { streamItemId ->
            val activeStreamItem = timelineItems.firstOrNull { it.id == streamItemId } as? AIAssistantStreamingResponseItem
            if (activeStreamItem?.attachments?.any { it.id == attachmentId } == true) {
                return streamItemId
            }
        }
        return timelineItems
            .asReversed()
            .firstOrNull { item ->
                val streamItem = item as? AIAssistantStreamingResponseItem
                streamItem?.attachments?.any { it.id == attachmentId } == true
            }
            ?.id
    }

    private fun findStreamToolAttachment(attachmentId: Long): AIAssistantToolItem? {
        val streamItemId = findStreamItemIdForAttachment(attachmentId) ?: return null
        val streamItem = timelineItems.firstOrNull { it.id == streamItemId } as? AIAssistantStreamingResponseItem
        return streamItem?.attachments?.firstOrNull { it.id == attachmentId }
    }

    private fun ensureStreamItemForAttachment() {
        if (activeStreamItemId == null) {
            startAssistantStream(activeProgressMessage ?: "Preparing assistant run")
        }
    }

    private fun moveTimelineItemToEnd(itemId: Long) {
        val index = timelineItems.indexOfFirst { it.id == itemId }
        if (index < 0 || index == timelineItems.lastIndex) {
            return
        }
        val item = timelineItems.removeAt(index)
        timelineItems += item
    }

    private fun buildModificationSummaryText(summary: AIAgentManager.ModificationSummary): String {
        return if (summary.totalFiles == 0) {
            "Assistant completed the run."
        } else {
            buildString {
                append("Applied ${summary.successfulFiles}/${summary.totalFiles} file changes")
                if (summary.newFiles > 0) {
                    append("\nNew files: ${summary.newFiles}")
                }
                if (summary.modifiedFiles > 0) {
                    append("\nModified files: ${summary.modifiedFiles}")
                }
                if (summary.failedFiles > 0) {
                    append("\nFailed: ${summary.failedFiles}")
                }
            }
        }
    }

    private fun buildVisibleTimelineItems(): List<AIAssistantTimelineItem> {
        if (timelineItems.isEmpty()) {
            return emptyList()
        }

        val startIndex = currentVisibleTimelineStartIndex()
        val hiddenCount = startIndex.coerceAtLeast(0)
        val visibleItems = timelineItems.subList(startIndex, timelineItems.size).toList()
        if (hiddenCount <= 0) {
            return visibleItems
        }

        return buildList(visibleItems.size + 1) {
            add(
                AIAssistantHistoryDividerItem(
                    hiddenCount = hiddenCount,
                    visibleCount = visibleItems.size,
                    totalCount = timelineItems.size
                )
            )
            addAll(visibleItems)
        }
    }

    private fun visibleTimelineItemCount(): Int {
        if (timelineItems.isEmpty()) {
            return 0
        }
        return timelineItems.size - currentVisibleTimelineStartIndex()
    }

    private fun hiddenTimelineItemCount(): Int {
        return currentVisibleTimelineStartIndex()
    }

    private fun resetVisibleTimelineWindow() {
        visibleTimelineStartIndex = latestVisibleTimelineStartIndex()
    }

    private fun toolCallSummary(toolCall: AIToolCall): String {
        return when (toolCall.name.lowercase()) {
            "find_files", "search_project" -> toolCall.argument("pattern").orEmpty()
            "read_file_range", "replace_file_range" -> listOfNotNull(
                toolCall.argument("file"),
                toolCall.argument("start_line")?.let { start ->
                    val end = toolCall.argument("end_line") ?: "?"
                    "lines $start-$end"
                }
            ).joinToString(" · ")
            "build_project" -> toolCall.argument("tasks").orEmpty()
            "run_terminal_command" -> toolCall.argument("command").orEmpty()
            else -> toolCall.rawBlock
        }.ifBlank { toolCall.rawBlock }
    }

    private fun buildToolTitle(toolName: String): String {
        return when (toolName.lowercase()) {
            "find_files" -> "Find files"
            "search_project" -> "Search project"
            "read_file_range" -> "Read file range"
            "replace_file_range" -> "Patch file range"
            "build_project" -> "Build project"
            "run_terminal_command" -> "Run terminal command"
            else -> toolName.split('_').joinToString(" ") { token ->
                token.replaceFirstChar { char -> char.uppercase() }
            }
        }
    }

    private fun buildToolStageTrail(
        stage: AIAssistantToolStage,
        sawOutput: Boolean
    ): String {
        val stages = mutableListOf(AIAssistantToolStage.PLANNED.label)
        if (stage != AIAssistantToolStage.PLANNED) {
            stages += AIAssistantToolStage.RUNNING.label
        }
        if (sawOutput || stage == AIAssistantToolStage.STREAMING) {
            stages += AIAssistantToolStage.STREAMING.label
        }
        when (stage) {
            AIAssistantToolStage.COMPLETED,
            AIAssistantToolStage.FAILED,
            AIAssistantToolStage.CANCELLED -> stages += stage.label
            else -> Unit
        }
        return stages.distinct().joinToString(" -> ")
    }

    private fun toolInvocationSummary(toolCall: AIToolCall): String? {
        return when (toolCall.name.lowercase()) {
            "run_terminal_command" -> toolCall.argument("command")
            "build_project" -> toolCall.argument("tasks")
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun buildToolCompletionSummary(
        result: AIToolExecutionResult,
        pendingTool: PendingToolItem?
    ): String {
        val details = mutableListOf<String>()
        result.exitCode?.let { details += "exit $it" }
        pendingTool?.let { pending ->
            val elapsedMs = (SystemClock.elapsedRealtime() - pending.startedAtMs).coerceAtLeast(0L)
            if (elapsedMs > 0L) {
                details += formatDuration(elapsedMs)
            }
            if (pending.outputChunks > 0) {
                details += "${pending.outputChunks} chunk" + if (pending.outputChunks == 1) "" else "s"
            }
        }
        if (details.isEmpty()) {
            return result.summary
        }
        return "${result.summary} · ${details.joinToString(" · ")}"
    }

    private fun formatDuration(durationMs: Long): String {
        return if (durationMs < 1000L) {
            "${durationMs}ms"
        } else {
            val tenths = (durationMs % 1000L) / 100L
            "${durationMs / 1000L}.${tenths}s"
        }
    }

    private fun formatCompactElapsed(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        if (totalSeconds < 60L) {
            return "${totalSeconds}s"
        }
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        if (minutes < 60L) {
            return "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        }
        val hours = minutes / 60L
        val remainingMinutes = minutes % 60L
        return "${hours}h ${remainingMinutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
    }

    private fun toolTimelineKey(toolCall: AIToolCall): String {
        return buildString {
            append(toolCall.name.lowercase())
            append("::")
            append(toolCall.rawBlock.trim())
        }
    }

    private fun findPendingTool(toolKey: String): PendingToolItem? {
        return pendingTools.reversed().firstOrNull { it.key == toolKey }
    }

    private fun trimOutputBuffer(buffer: StringBuilder, maxChars: Int = 1400) {
        if (buffer.length <= maxChars) {
            return
        }
        val trimmed = buffer.toString().takeLast(maxChars)
        buffer.setLength(0)
        buffer.append(trimmed)
    }

    private fun markPendingToolsCancelled() {
        pendingTools.forEach { pendingTool ->
            val outputPreview = toolOutputBuffers[pendingTool.itemId]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            updateStreamToolAttachment(pendingTool.itemId) { toolItem ->
                toolItem.copy(
                    stage = AIAssistantToolStage.CANCELLED,
                    stageTrail = buildToolStageTrail(
                        stage = AIAssistantToolStage.CANCELLED,
                        sawOutput = pendingTool.sawOutput
                    ),
                    outputPreview = outputPreview ?: toolItem.outputPreview
                )
            }
        }
    }

    private fun clearCompletedToolTracking() {
        pendingTools.clear()
        toolOutputBuffers.clear()
    }

    private fun resetRunTracking() {
        stopActiveWorkingTicker()
        activeStreamItemId = null
        activeStreamStartedAtMs = 0L
        activeProgressMessage = null
        clearCompletedToolTracking()
    }

    private fun syncTimelineSession(force: Boolean = false) {
        val sessionKey = currentTimelineSessionKey() ?: return
        if (!force && activeTimelineSessionKey == sessionKey) {
            return
        }

        activeTimelineSessionKey
            ?.takeIf { it != sessionKey }
            ?.let(::flushTimelinePersistence)

        timelinePersistJob?.cancel()
        timelinePersistJob = null
        resetRunTracking()
        lastModifications = emptyList()

        val restored = timelineStore.load(sessionKey)
        activeTimelineSessionKey = sessionKey
        promptDraft = restored?.promptDraft.orEmpty()
        timelineItems.clear()
        timelineItems += normalizeRestoredTimelineItems(restored?.timelineItems.orEmpty())
        resetVisibleTimelineWindow()
    }

    private fun currentTimelineSessionKey(): String? {
        return aiAgent.getCurrentSessionStorageKey()
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun scheduleTimelinePersistence() {
        val sessionKey = activeTimelineSessionKey ?: currentTimelineSessionKey() ?: return
        val promptDraftSnapshot = promptDraft
        val timelineSnapshot = timelineItems.toList()
        timelinePersistJob?.cancel()
        timelinePersistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(180L)
            timelineStore.save(
                sessionKey = sessionKey,
                promptDraft = promptDraftSnapshot,
                timelineItems = timelineSnapshot.map(::normalizeTimelineItemForPersistence)
            )
        }
    }

    private fun flushTimelinePersistence(sessionKey: String? = activeTimelineSessionKey) {
        val resolvedSessionKey = sessionKey ?: return
        timelinePersistJob?.cancel()
        timelinePersistJob = null
        timelineStore.save(
            sessionKey = resolvedSessionKey,
            promptDraft = promptDraft,
            timelineItems = timelineItems.map(::normalizeTimelineItemForPersistence)
        )
    }

    private fun normalizeRestoredTimelineItems(
        items: List<AIAssistantTimelineItem>
    ): List<AIAssistantTimelineItem> {
        return items.map(::normalizeTimelineItemForPersistence)
    }

    private fun normalizeTimelineItemForPersistence(
        item: AIAssistantTimelineItem
    ): AIAssistantTimelineItem {
        if (item !is AIAssistantStreamingResponseItem) {
            return item
        }

        val normalizedAttachments = item.attachments.map { attachment ->
            if (!attachment.isLive) {
                attachment
            } else {
                attachment.copy(
                    stage = AIAssistantToolStage.CANCELLED,
                    stageTrail = buildToolStageTrail(
                        stage = AIAssistantToolStage.CANCELLED,
                        sawOutput = !attachment.outputPreview.isNullOrBlank()
                    )
                )
            }
        }

        return item.copy(
            header = if (item.response.isNotBlank()) "Assistant" else item.header.ifBlank { "Assistant" },
            status = null,
            attachments = normalizedAttachments,
            isWorking = false,
            isStreaming = false
        )
    }

    private fun postToMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            block()
        }
    }

    private fun postExecutionUpdate(executionToken: Long, block: () -> Unit) {
        postToMain {
            if (activeExecutionToken != executionToken) {
                return@postToMain
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

    private fun currentVisibleTimelineStartIndex(): Int {
        return visibleTimelineStartIndex.coerceIn(0, timelineItems.size)
    }

    private fun latestVisibleTimelineStartIndex(): Int {
        return (timelineItems.size - INITIAL_VISIBLE_TIMELINE_ITEMS).coerceAtLeast(0)
    }

    private data class PendingToolItem(
        val key: String,
        val itemId: Long,
        val startedAtMs: Long,
        var sawOutput: Boolean = false,
        var outputChunks: Int = 0
    )

    companion object {
        private const val INITIAL_VISIBLE_TIMELINE_ITEMS = 48
        private const val TIMELINE_HISTORY_PAGE_SIZE = 32
    }
}

data class AIAssistantConsoleUiState(
    val providerLabel: String = "Unknown",
    val modelLabel: String = "",
    val promptDraft: String = "",
    val isRunning: Boolean = false,
    val hasReviewableChanges: Boolean = false,
    val canLoadMoreHistory: Boolean = false,
    val hiddenHistoryCount: Int = 0,
    val totalTimelineCount: Int = 0,
    val timelineItems: List<AIAssistantTimelineItem> = emptyList()
)
