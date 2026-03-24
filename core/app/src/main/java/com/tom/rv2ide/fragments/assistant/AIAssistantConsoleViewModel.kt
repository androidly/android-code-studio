package com.tom.rv2ide.fragments.assistant

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom.rv2ide.activities.ModificationData
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.external.CodexCliConfig
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AIAssistantConsoleViewModel(application: Application) : AndroidViewModel(application) {
    val aiAgent: AIAgentManager = AIAgentManager(application)
    private val timelineStore = AIAssistantTimelineStore(application)
    private val sessionRegistry = AIAssistantSessionRegistry(application)

    private val _uiState = MutableStateFlow(AIAssistantConsoleUiState())
    val uiState: StateFlow<AIAssistantConsoleUiState> = _uiState.asStateFlow()

    private val timelineItems = mutableListOf<AIAssistantTimelineItem>()
    private val timelineOrderIndexes = mutableMapOf<Long, Long>()
    private val dirtyTimelineItemIds = linkedSetOf<Long>()
    private var executionJob: Job? = null
    private var activeWorkingTickerJob: Job? = null
    private var lastModifications: List<ModificationData> = emptyList()
    private var projectRootPath: String = ""
    private var promptDraft: String = ""
    private var promptDraftDirty: Boolean = false
    private var activeStreamItemId: Long? = null
    private var activeStreamStartedAtMs: Long = 0L
    private var activeProgressMessage: String? = null
    private var activeAssistantSession: AIAssistantChatSession? = null
    private var activeTimelineSessionKey: String? = null
    private var totalPersistedTimelineCount: Int = 0
    private var nextTimelineOrderIndex: Long = 0L
    private val pendingTools = ArrayDeque<PendingToolItem>()
    private val toolOutputBuffers = mutableMapOf<Long, StringBuilder>()
    private val activeStreamResponseBuffer = StringBuilder()
    private var activeStreamResponseDirty: Boolean = false
    private var fileRefreshHandler: ((String) -> Unit)? = null
    private var timelinePersistJob: Job? = null
    private var throttledStatePublishJob: Job? = null
    private var throttledStateNeedsPersistence: Boolean = false
    private var nextExecutionToken: Long = 1L
    private var activeExecutionToken: Long? = null
    private val pendingPromptQueue = ArrayDeque<QueuedPrompt>()

    init {
        publishState()
    }

    fun bindProject(projectRoot: String) {
        if (projectRoot.isBlank()) {
            return
        }
        if (projectRoot == projectRootPath) {
            ensureActiveAssistantSession()
            syncTimelineSession()
            publishState()
            return
        }
        projectRootPath = projectRoot
        val session = sessionRegistry.getOrCreateActive(projectRoot)
        activeAssistantSession = session
        aiAgent.setConversationSessionId(session.id)
        aiAgent.setProjectRoot(projectRoot)
        syncTimelineSession(force = true)
        publishState()
    }

    fun setFileRefreshHandler(handler: ((String) -> Unit)?) {
        fileRefreshHandler = handler
    }

    fun lastModificationsSnapshot(): List<ModificationData> {
        return lastModifications.toList()
    }

    fun updatePromptDraft(draft: String) {
        if (promptDraft == draft) {
            return
        }
        promptDraft = draft
        promptDraftDirty = true
        publishState()
    }

    fun refreshAgentPresentation() {
        ensureActiveAssistantSession()
        aiAgent.syncSelectedProviderAndModel()
        publishState(persistTimeline = false)
    }

    fun ensureWelcome() {
        if (timelineItems.isEmpty()) {
            appendTimelineItem(
                AIAssistantWelcomeItem(
                    title = "AI Assistant",
                    body = "Persistent Codex-style session with focused reads, tool execution, streaming output, and reviewable diffs. Try /new, /list, /switch 2, /review, /stop, or ask it to build and patch a specific file range."
                )
            )
        }
        publishState()
    }

    fun showCommandsHelp() {
        appendTimelineItem(
            AIAssistantStatusItem(
                title = "Commands",
                body = buildString {
                    appendLine("/new [name]  /list  /switch <n|id|name>")
                    appendLine("/history [n]  /search <keyword>  /delete <n|1,3-5>")
                    appendLine("/status  /compress  /memory [add|global ...]")
                    append("/help  /review  /stop  /clear")
                },
                tone = AIAssistantTone.NEUTRAL
            )
        )
        publishState()
    }

    fun createNewSession(nameHint: String? = null): String? {
        if (projectRootPath.isBlank()) {
            return null
        }
        val session = sessionRegistry.createSession(projectRootPath, nameHint)
        activateAssistantSession(session)
        return session.title
    }

    fun switchSession(target: String): String? {
        if (projectRootPath.isBlank()) {
            return null
        }
        val resolved = resolveAssistantSessionTarget(target) ?: return null
        val activated = sessionRegistry.switchToSession(projectRootPath, resolved.id) ?: return null
        activateAssistantSession(activated)
        return activated.title
    }

    fun showSessionList(
        query: String? = null,
        emphasizeDelete: Boolean = false
    ) {
        val normalizedQuery = query?.trim().orEmpty()
        val sessions = listAvailableSessions()
        val filteredSessions = if (normalizedQuery.isBlank()) {
            sessions
        } else {
            filterSessions(sessions, normalizedQuery)
        }

        if (filteredSessions.isEmpty()) {
            appendTimelineItem(
                AIAssistantStatusItem(
                    title = if (normalizedQuery.isBlank()) "Sessions" else "Search",
                    body = if (normalizedQuery.isBlank()) {
                        "No saved sessions yet. Use /new to start a fresh chat."
                    } else {
                        "No sessions matched \"$normalizedQuery\"."
                    },
                    tone = AIAssistantTone.NEUTRAL
                )
            )
        } else {
            appendTimelineItem(
                AIAssistantSessionBrowserItem(
                    title = if (normalizedQuery.isBlank()) {
                        "Sessions · ${filteredSessions.size}"
                    } else {
                        "Search · ${filteredSessions.size} hit" +
                            if (filteredSessions.size == 1) "" else "s"
                    },
                    subtitle = buildString {
                        if (normalizedQuery.isNotBlank()) {
                            append("Keyword: ")
                            append(normalizedQuery)
                        } else {
                            append("Current session is pinned. ")
                            append(
                                if (emphasizeDelete) {
                                    "Use Delete to remove old chats."
                                } else {
                                    "Switch or delete directly from the card."
                                }
                            )
                        }
                    },
                    sessions = filteredSessions.mapIndexed { index, session ->
                        session.toBrowserEntry(order = index + 1)
                    }
                )
            )
        }
        publishState()
    }

    fun showHistory(limit: Int = 10) {
        val normalizedLimit = limit.coerceIn(1, 50)
        val sessionKey = activeTimelineSessionKey ?: currentTimelineSessionKey()
        if (sessionKey == null) {
            appendTimelineItem(
                AIAssistantStatusItem(
                    title = "History",
                    body = "No active session is loaded yet.",
                    tone = AIAssistantTone.WARNING
                )
            )
            publishState()
            return
        }

        flushTimelinePersistence(sessionKey)
        val recentEntries = timelineStore
            .loadLatest(sessionKey, maxOf(normalizedLimit * 8, 48))
            ?.entries
            .orEmpty()
            .map(PersistedAIAssistantTimelineEntry::item)
            .toConversationHistoryLines()
            .takeLast(normalizedLimit)

        appendTimelineItem(
            AIAssistantStatusItem(
                title = "History",
                body = if (recentEntries.isEmpty()) {
                    "No user/assistant messages have been saved in this session yet."
                } else {
                    recentEntries.joinToString(separator = "\n\n")
                },
                tone = AIAssistantTone.NEUTRAL
            )
        )
        publishState()
    }

    fun showStatus() {
        val sessionCount = listAvailableSessions().size
        val codexSummary = runCatching { CodexCliConfig.getSettings() }
            .getOrNull()
            ?.takeIf { aiAgent.getCurrentProviderId() == "external" && it.isValid }
            ?.summaryText()
        appendTimelineItem(
            AIAssistantStatusItem(
                title = "Status",
                body = buildString {
                    appendLine("Provider: ${aiAgent.getCurrentProviderName()}")
                    appendLine("Model: ${aiAgent.getCurrentModelName()}")
                    appendLine("Session: ${activeAssistantSession?.title.orEmpty().ifBlank { "None" }}")
                    appendLine("Queued: ${pendingPromptQueue.size}")
                    appendLine("Running: ${if (executionJob?.isActive == true) "yes" else "no"}")
                    appendLine("Timeline: ${timelineItems.size}/${totalPersistedTimelineCount}")
                    appendLine("Saved sessions: $sessionCount")
                    projectRootPath.takeIf { it.isNotBlank() }?.let { root ->
                        appendLine("Project: $root")
                    }
                    codexSummary?.let { summary ->
                        append("Codex: $summary")
                    }
                }.trim(),
                tone = AIAssistantTone.NEUTRAL
            )
        )
        publishState()
    }

    fun requestConversationCompression() {
        val providerId = aiAgent.getCurrentProviderId()
        val message = when (providerId) {
            "custom" -> "The custom provider already auto-compacts old native turns when the context budget is exceeded."
            "external" -> {
                val settings = runCatching { CodexCliConfig.getSettings() }.getOrNull()
                if (settings?.isValid == true) {
                    "Managed Codex already has auto-compact configured at ${settings.autoCompactTokenLimit} tokens in ~/.codex/config.toml. A manual /compact bridge action is not exposed yet."
                } else {
                    "Codex auto-compact is not configured yet. Open Codex settings first."
                }
            }
            else -> "This provider does not expose a native manual compact command in the current bridge. Use /new if you need a clean session."
        }
        appendTimelineItem(
            AIAssistantStatusItem(
                title = "Compress",
                body = message,
                tone = AIAssistantTone.NEUTRAL
            )
        )
        publishState()
    }

    fun showMemoryCommand(rawArguments: String) {
        val arguments = rawArguments.trim()
        val projectMemoryFile = File(projectRootPath.ifBlank { "." }, "AGENTS.md")
        val globalMemoryFile = File(File(Environment.HOME, ".codex"), "AGENTS.md")
        val tokens = arguments.split(Regex("\\s+")).filter(String::isNotBlank)

        when {
            tokens.isEmpty() || tokens.first().equals("show", ignoreCase = true) -> {
                appendTimelineItem(showMemoryFileItem(projectMemoryFile, isGlobal = false))
            }
            tokens.first().equals("help", ignoreCase = true) -> {
                appendTimelineItem(
                    AIAssistantStatusItem(
                        title = "Memory",
                        body = buildString {
                            appendLine("/memory")
                            appendLine("/memory add <text>")
                            appendLine("/memory global")
                            append("/memory global add <text>")
                        },
                        tone = AIAssistantTone.NEUTRAL
                    )
                )
            }
            tokens.first().equals("global", ignoreCase = true) && tokens.size == 1 -> {
                appendTimelineItem(showMemoryFileItem(globalMemoryFile, isGlobal = true))
            }
            tokens.first().equals("global", ignoreCase = true) &&
                (tokens.getOrNull(1)?.equals("add", ignoreCase = true) == true) -> {
                appendTimelineItem(
                    appendMemoryFileItem(
                        file = globalMemoryFile,
                        text = tokens.drop(2).joinToString(" ")
                    )
                )
            }
            tokens.first().equals("add", ignoreCase = true) -> {
                appendTimelineItem(
                    appendMemoryFileItem(
                        file = projectMemoryFile,
                        text = tokens.drop(1).joinToString(" ")
                    )
                )
            }
            else -> {
                appendTimelineItem(
                    AIAssistantStatusItem(
                        title = "Memory",
                        body = "Usage: /memory [add|global|global add|help]",
                        tone = AIAssistantTone.WARNING
                    )
                )
            }
        }
        publishState()
    }

    fun deleteSessions(target: String): AIAssistantSessionDeleteResult {
        if (projectRootPath.isBlank()) {
            return AIAssistantSessionDeleteResult(
                deletedCount = 0,
                activeSessionLabel = null,
                message = "No project session is active yet."
            )
        }

        val targets = resolveAssistantSessionTargets(target)
        if (targets.isEmpty()) {
            return AIAssistantSessionDeleteResult(
                deletedCount = 0,
                activeSessionLabel = activeAssistantSession?.title,
                message = "No session matched: $target"
            )
        }

        val wasActiveDeleted = activeAssistantSession?.id?.let { activeId ->
            targets.any { session -> session.id == activeId }
        } == true
        val deletion = sessionRegistry.deleteSessions(
            projectRoot = projectRootPath,
            sessionIds = targets.map(AIAssistantChatSession::id)
        )
        deletion.deletedSessionIds.forEach(timelineStore::clearConversationSession)

        if (wasActiveDeleted) {
            deletion.activeSession?.let(::activateAssistantSession)
        } else {
            publishState()
        }
        showSessionList(emphasizeDelete = true)
        return AIAssistantSessionDeleteResult(
            deletedCount = deletion.deletedSessionIds.size,
            activeSessionLabel = deletion.activeSession?.title ?: activeAssistantSession?.title,
            message = buildString {
                append("Deleted ${deletion.deletedSessionIds.size} session")
                if (deletion.deletedSessionIds.size != 1) {
                    append('s')
                }
                if (wasActiveDeleted) {
                    deletion.activeSession?.title?.let { activeTitle ->
                        append(" and switched to ")
                        append(activeTitle)
                    }
                }
            }
        )
    }

    fun loadOlderHistory(): Boolean {
        val sessionKey = activeTimelineSessionKey ?: currentTimelineSessionKey() ?: return false
        if (hiddenTimelineItemCount() <= 0) {
            return false
        }
        val beforeOrderIndex = earliestLoadedOrderIndex() ?: return false
        val olderEntries = timelineStore.loadOlder(
            sessionKey = sessionKey,
            beforeOrderIndexExclusive = beforeOrderIndex,
            limit = TIMELINE_HISTORY_PAGE_SIZE
        )
        if (olderEntries.isEmpty()) {
            return false
        }
        prependLoadedTimelineEntries(olderEntries)
        publishState()
        return true
    }

    fun collapseHistoryToLatest(): Boolean {
        val latestWindowSize = latestLoadedTimelineWindowSize()
        if (timelineItems.size <= latestWindowSize) {
            return false
        }
        collapseLoadedTimelineToLatestWindow()
        publishState(persistTimeline = false)
        return true
    }

    fun clearTimeline() {
        cancelExecution(
            manualStop = false,
            showStoppedStatus = false,
            clearQueuedPrompts = true,
            continueWithQueuedPrompts = false
        )
        lastModifications = emptyList()
        aiAgent.clearConversation()
        promptDraft = ""
        promptDraftDirty = false
        resetRunTracking()
        clearLoadedTimeline()
        totalPersistedTimelineCount = 0
        nextTimelineOrderIndex = 0L
        activeTimelineSessionKey?.let(timelineStore::clear)
        ensureWelcome()
        publishState()
    }

    fun stopExecution(): Int {
        return cancelExecution(
            manualStop = true,
            showStoppedStatus = true,
            clearQueuedPrompts = true,
            continueWithQueuedPrompts = false
        )
    }

    fun executePrompt(prompt: String): AIAssistantPromptDispatchResult {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isBlank()) {
            return AIAssistantPromptDispatchResult.REJECTED_EMPTY
        }
        return if (executionJob?.isActive == true) {
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
        if (executionJob?.isActive != true) {
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

    private fun startPromptExecution(
        prompt: String,
        appendUserItem: Boolean
    ) {
        touchActiveAssistantSession()
        val executionToken = beginExecutionToken()

        promptDraft = ""
        promptDraftDirty = true
        lastModifications = emptyList()
        resetRunTracking()
        if (appendUserItem) {
            appendTimelineItem(AIAssistantUserItem(prompt))
            recordActiveSessionUserPrompt(prompt)
        }
        startAssistantStream("Preparing assistant run")
        publishState()

        executionJob = viewModelScope.launch {
            try {
                aiAgent.executeRequest(prompt, object : AIAgentManager.AIAgentCallback {
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
                            lastModifications = modifications.map(::toReviewableModification)
                            appendDiffItems(modifications)
                            val fallbackText = response.takeIf {
                                it.isNotBlank() && !it.contains("FILE_TO_MODIFY:")
                            } ?: buildModificationSummaryText(summary)
                            completeAssistantStream(response, fallbackText)
                            recordActiveSessionAssistantReply()
                            clearCompletedToolTracking()
                            onExecutionFinished(executionToken)
                        }
                    }

                    override fun onTextResponse(
                        response: String,
                        summary: AIAgentManager.ModificationSummary
                    ) {
                        postExecutionUpdate(executionToken) {
                            completeAssistantStream(response, response)
                            recordActiveSessionAssistantReply()
                            onExecutionFinished(executionToken)
                        }
                    }

                    override fun onError(message: String) {
                        postExecutionUpdate(executionToken) {
                            completeAssistantStream(null, "Run failed before the assistant produced a final reply.")
                            markPendingToolsCancelled()
                            appendTimelineItem(
                                AIAssistantStatusItem(
                                    title = "Error",
                                    body = message,
                                    tone = AIAssistantTone.ERROR
                                )
                            )
                            clearCompletedToolTracking()
                            onExecutionFinished(executionToken)
                        }
                    }

                    override fun onRetry(attemptNumber: Int, message: String) {
                        postExecutionUpdate(executionToken) {
                            updateAssistantWorkingStatus("Retry #$attemptNumber")
                            appendTimelineItem(
                                AIAssistantStatusItem(
                                    title = "Retry #$attemptNumber",
                                    body = message,
                                    tone = AIAssistantTone.WARNING
                                )
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
                            publishStateThrottled(persistTimeline = false)
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
                    onExecutionFinished(executionToken)
                }
            } catch (error: Exception) {
                postExecutionUpdate(executionToken) {
                    completeAssistantStream(null, "Unexpected exception interrupted the run.")
                    markPendingToolsCancelled()
                    appendTimelineItem(
                        AIAssistantStatusItem(
                            title = "Exception",
                            body = error.message ?: "Unexpected error",
                            tone = AIAssistantTone.ERROR
                        )
                    )
                    clearCompletedToolTracking()
                    onExecutionFinished(executionToken)
                }
            }
        }
    }

    override fun onCleared() {
        flushTimelinePersistence()
        cancelPendingThrottledStatePublish()
        executionJob?.cancel()
        executionJob = null
        stopActiveWorkingTicker()
        timelinePersistJob?.cancel()
        timelinePersistJob = null
        fileRefreshHandler = null
        super.onCleared()
    }

    private fun publishState(persistTimeline: Boolean = true) {
        cancelPendingThrottledStatePublish()
        publishStateNow(persistTimeline)
    }

    private fun publishStateNow(persistTimeline: Boolean) {
        syncTimelineSession()
        flushActiveAssistantResponseIfNeeded(markDirtyForPersistence = persistTimeline)
        val visibleTimelineItems = buildVisibleTimelineItems()
        val hiddenHistoryCount = hiddenTimelineItemCount()
        _uiState.value = AIAssistantConsoleUiState(
            providerLabel = aiAgent.getCurrentProviderName(),
            modelLabel = aiAgent.getCurrentModelName(),
            sessionLabel = activeAssistantSession?.title.orEmpty(),
            promptDraft = promptDraft,
            isRunning = executionJob?.isActive == true,
            queuedPromptCount = pendingPromptQueue.size,
            hasReviewableChanges = lastModifications.isNotEmpty(),
            canLoadMoreHistory = hiddenHistoryCount > 0,
            hiddenHistoryCount = hiddenHistoryCount,
            totalTimelineCount = totalPersistedTimelineCount,
            timelineItems = visibleTimelineItems
        )
        if (persistTimeline) {
            scheduleTimelinePersistence()
        }
    }

    private fun publishStateThrottled(persistTimeline: Boolean = true) {
        throttledStateNeedsPersistence = throttledStateNeedsPersistence || persistTimeline
        if (throttledStatePublishJob?.isActive == true) {
            return
        }
        throttledStatePublishJob = viewModelScope.launch {
            delay(THROTTLED_STATE_PUBLISH_INTERVAL_MS)
            val needsPersistence = throttledStateNeedsPersistence
            throttledStateNeedsPersistence = false
            throttledStatePublishJob = null
            publishStateNow(persistTimeline = needsPersistence)
        }
    }

    private fun cancelPendingThrottledStatePublish() {
        throttledStatePublishJob?.cancel()
        throttledStatePublishJob = null
        throttledStateNeedsPersistence = false
    }

    private fun cancelExecution(
        manualStop: Boolean,
        showStoppedStatus: Boolean,
        clearQueuedPrompts: Boolean,
        continueWithQueuedPrompts: Boolean
    ): Int {
        activeExecutionToken = null
        executionJob?.cancel()
        executionJob = null
        if (manualStop) {
            completeAssistantStream(null, "Manual stop requested")
        }
        markPendingToolsCancelled()
        clearCompletedToolTracking()
        if (manualStop && showStoppedStatus) {
            appendTimelineItem(
                AIAssistantStatusItem(
                    title = "Stopped",
                    body = "Manual stop requested",
                    tone = AIAssistantTone.WARNING
                )
            )
        }
        val clearedQueuedPromptCount = if (clearQueuedPrompts) {
            clearPendingPromptQueue()
        } else {
            0
        }
        if (!continueWithQueuedPrompts || !startNextQueuedPromptIfIdle()) {
            publishState()
        }
        return clearedQueuedPromptCount
    }

    private fun enqueuePrompt(
        prompt: String,
        addToFront: Boolean = false
    ): Boolean {
        if (pendingPromptQueue.size >= MAX_QUEUED_PROMPTS) {
            return false
        }
        touchActiveAssistantSession()
        promptDraft = ""
        promptDraftDirty = true
        appendTimelineItem(AIAssistantUserItem(prompt))
        recordActiveSessionUserPrompt(prompt)
        val queuedPrompt = QueuedPrompt(
            prompt = prompt,
            userMessageAlreadyAppended = true
        )
        if (addToFront) {
            pendingPromptQueue.addFirst(queuedPrompt)
        } else {
            pendingPromptQueue.addLast(queuedPrompt)
        }
        publishState()
        return true
    }

    private fun startNextQueuedPromptIfIdle(): Boolean {
        if (executionJob?.isActive == true) {
            return false
        }
        val nextPrompt = pendingPromptQueue.pollFirst() ?: return false
        startPromptExecution(
            prompt = nextPrompt.prompt,
            appendUserItem = !nextPrompt.userMessageAlreadyAppended
        )
        return true
    }

    private fun clearPendingPromptQueue(): Int {
        val clearedCount = pendingPromptQueue.size
        pendingPromptQueue.clear()
        return clearedCount
    }

    private fun onExecutionFinished(executionToken: Long) {
        finishExecutionToken(executionToken)
        executionJob = null
        if (!startNextQueuedPromptIfIdle()) {
            publishState()
        }
    }

    private fun appendDiffItems(modifications: List<AIAgentManager.ModificationResult>) {
        modifications.forEach { modification ->
            val preview = AIAssistantDiffPreview.fromContents(
                previousContent = modification.previousContent,
                newContent = modification.content
            )
            appendTimelineItem(
                AIAssistantDiffItem(
                    filePath = modification.filePath,
                    changeLabel = if (modification.isNewFile) "Created" else "Updated",
                    preview = preview
                )
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
        activeStreamResponseBuffer.setLength(0)
        activeStreamResponseDirty = false
        appendTimelineItem(
            AIAssistantStreamingResponseItem(
                header = "Working",
                placeholder = normalizedPlaceholder,
                response = "",
                status = buildWorkingElapsedLabel(),
                isWorking = true,
                isStreaming = true,
                id = activeStreamItemId ?: nextAIAssistantTimelineItemId()
            )
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
        publishStateThrottled(persistTimeline = false)
    }

    private fun appendAssistantDelta(delta: String) {
        if (delta.isBlank()) {
            return
        }
        if (activeStreamItemId == null) {
            startAssistantStream(activeProgressMessage ?: "Preparing assistant run")
        }
        activeStreamResponseBuffer.append(delta)
        trimAssistantResponseBuffer(activeStreamResponseBuffer)
        activeStreamResponseDirty = true
        publishStateThrottled(persistTimeline = false)
    }

    private fun replaceAssistantResponse(fullResponse: String) {
        if (fullResponse.isBlank()) {
            return
        }
        if (activeStreamItemId == null) {
            startAssistantStream(activeProgressMessage ?: "Preparing assistant run")
        }
        activeStreamResponseBuffer.setLength(0)
        activeStreamResponseBuffer.append(trimAssistantResponseText(fullResponse))
        activeStreamResponseDirty = true
        publishStateThrottled(persistTimeline = false)
    }

    private fun completeAssistantStream(finalResponse: String?, fallbackText: String?) {
        stopActiveWorkingTicker()
        val bufferedResponse = activeStreamResponseBuffer.toString()
        val visibleFinalText = finalResponse
            ?.takeIf { it.isNotBlank() && !it.contains("FILE_TO_MODIFY:") && !it.contains("TOOL_CALL:") }
            ?.trim()
            ?.let(::trimAssistantResponseText)
        val finalText = visibleFinalText ?: fallbackText?.trim().orEmpty().let(::trimAssistantResponseText)
        val itemId = activeStreamItemId
        activeProgressMessage = null

        if (itemId == null) {
            if (finalText.isBlank()) {
                activeStreamResponseBuffer.setLength(0)
                activeStreamResponseDirty = false
                return
            }
            appendTimelineItem(
                AIAssistantStreamingResponseItem(
                    header = "Assistant",
                    placeholder = null,
                    response = finalText,
                    status = null,
                    isWorking = false,
                    isStreaming = false
                )
            )
            activeStreamResponseBuffer.setLength(0)
            activeStreamResponseDirty = false
            publishState()
            return
        }

        updateTimelineItem(itemId) { existing ->
            val streamItem = existing as? AIAssistantStreamingResponseItem ?: return@updateTimelineItem existing
            val nextText = when {
                finalText.isNotBlank() -> finalText
                bufferedResponse.isNotBlank() -> bufferedResponse
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
        activeStreamResponseBuffer.setLength(0)
        activeStreamResponseDirty = false
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
                publishStateThrottled(persistTimeline = false)
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
        val updatedItem = transformer(timelineItems[index])
        if (updatedItem == timelineItems[index]) {
            return
        }
        timelineItems[index] = updatedItem
        markTimelineItemDirty(updatedItem.id)
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
        timelineOrderIndexes[itemId] = nextTimelineOrderIndex++
        markTimelineItemDirty(itemId)
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

        val hiddenCount = hiddenTimelineItemCount()
        val visibleItems = timelineItems.toList()
        if (hiddenCount <= 0) {
            return visibleItems
        }

        return buildList(visibleItems.size + 1) {
            add(
                AIAssistantHistoryDividerItem(
                    hiddenCount = hiddenCount,
                    visibleCount = visibleItems.size,
                    totalCount = totalPersistedTimelineCount
                )
            )
            addAll(visibleItems)
        }
    }

    private fun hiddenTimelineItemCount(): Int {
        return (totalPersistedTimelineCount - timelineItems.size).coerceAtLeast(0)
    }

    private fun appendTimelineItem(item: AIAssistantTimelineItem) {
        timelineItems += item
        timelineOrderIndexes[item.id] = nextTimelineOrderIndex++
        totalPersistedTimelineCount += 1
        markTimelineItemDirty(item.id)
    }

    private fun prependLoadedTimelineEntries(entries: List<PersistedAIAssistantTimelineEntry>) {
        if (entries.isEmpty()) {
            return
        }
        val normalizedItems = entries.mapNotNull(::normalizeRestoredTimelineEntry)
        if (normalizedItems.isEmpty()) {
            return
        }
        timelineItems.addAll(0, normalizedItems)
    }

    private fun clearLoadedTimeline() {
        timelineItems.clear()
        timelineOrderIndexes.clear()
        dirtyTimelineItemIds.clear()
    }

    private fun markTimelineItemDirty(itemId: Long) {
        if (timelineOrderIndexes.containsKey(itemId)) {
            dirtyTimelineItemIds += itemId
        }
    }

    private fun earliestLoadedOrderIndex(): Long? {
        val earliestItemId = timelineItems.firstOrNull()?.id ?: return null
        return timelineOrderIndexes[earliestItemId]
    }

    private fun latestLoadedTimelineWindowSize(): Int {
        return minOf(totalPersistedTimelineCount, INITIAL_VISIBLE_TIMELINE_ITEMS)
    }

    private fun collapseLoadedTimelineToLatestWindow() {
        val latestWindowSize = latestLoadedTimelineWindowSize()
        if (timelineItems.size <= latestWindowSize) {
            return
        }
        removeLoadedItemsFromStart(timelineItems.size - latestWindowSize)
    }

    private fun removeLoadedItemsFromStart(maxCount: Int) {
        if (maxCount <= 0) {
            return
        }
        repeat(maxCount) {
            val firstItem = timelineItems.firstOrNull() ?: return
            if (dirtyTimelineItemIds.contains(firstItem.id)) {
                return
            }
            timelineItems.removeAt(0)
            timelineOrderIndexes.remove(firstItem.id)
        }
    }

    private fun replaceLoadedTimelineWindow(
        restored: PersistedAIAssistantTimelineWindow?
    ) {
        clearLoadedTimeline()
        promptDraft = restored?.promptDraft.orEmpty()
        promptDraftDirty = false
        totalPersistedTimelineCount = restored?.totalCount ?: 0
        nextTimelineOrderIndex = restored?.nextOrderIndex ?: 0L
        restored?.entries
            ?.mapNotNull(::normalizeRestoredTimelineEntry)
            ?.let(timelineItems::addAll)
    }

    private fun normalizeRestoredTimelineEntry(
        entry: PersistedAIAssistantTimelineEntry
    ): AIAssistantTimelineItem? {
        val normalizedItem = normalizeTimelineItemForPersistence(entry.item)
        timelineOrderIndexes[normalizedItem.id] = entry.orderIndex
        nextTimelineOrderIndex = maxOf(nextTimelineOrderIndex, entry.orderIndex + 1L)
        if (normalizedItem != entry.item) {
            dirtyTimelineItemIds += normalizedItem.id
        }
        return normalizedItem
    }

    private fun buildDirtyTimelineEntries(
        dirtyItemIds: Set<Long>
    ): List<PersistedAIAssistantTimelineEntry> {
        return timelineItems.mapNotNull { item ->
            if (item.id !in dirtyItemIds) {
                return@mapNotNull null
            }
            val orderIndex = timelineOrderIndexes[item.id] ?: return@mapNotNull null
            PersistedAIAssistantTimelineEntry(
                orderIndex = orderIndex,
                item = normalizeTimelineItemForPersistence(item)
            )
        }
    }

    private fun captureTimelinePersistenceSnapshot(
        expectedSessionKey: String
    ): TimelinePersistenceSnapshot? {
        val activeSessionKey = activeTimelineSessionKey ?: return null
        if (activeSessionKey != expectedSessionKey) {
            return null
        }
        if (!promptDraftDirty && dirtyTimelineItemIds.isEmpty()) {
            return null
        }

        val dirtyItemIds = dirtyTimelineItemIds.toSet()
        val snapshot = TimelinePersistenceSnapshot(
            sessionKey = activeSessionKey,
            promptDraft = promptDraft,
            persistPromptDraft = promptDraftDirty,
            dirtyItemIds = dirtyItemIds,
            entries = buildDirtyTimelineEntries(dirtyItemIds)
        )
        promptDraftDirty = false
        dirtyTimelineItemIds.removeAll(dirtyItemIds)
        return snapshot
    }

    private fun restoreTimelinePersistenceSnapshot(snapshot: TimelinePersistenceSnapshot) {
        if (activeTimelineSessionKey != snapshot.sessionKey) {
            return
        }
        if (snapshot.persistPromptDraft) {
            promptDraftDirty = true
        }
        dirtyTimelineItemIds += snapshot.dirtyItemIds
    }

    private fun trimLoadedTimelineWindowIfNeeded(): Boolean {
        var trimmed = false
        if (timelineItems.size > MAX_IN_MEMORY_TIMELINE_ITEMS) {
            val beforeSize = timelineItems.size
            removeLoadedItemsFromStart(timelineItems.size - MAX_IN_MEMORY_TIMELINE_ITEMS)
            trimmed = timelineItems.size != beforeSize
        }
        while (timelineRetainedCharCount() > MAX_IN_MEMORY_TIMELINE_CHARS) {
            val firstItem = timelineItems.firstOrNull() ?: break
            if (dirtyTimelineItemIds.contains(firstItem.id)) {
                break
            }
            timelineItems.removeAt(0)
            timelineOrderIndexes.remove(firstItem.id)
            trimmed = true
        }
        return trimmed
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
        activeStreamResponseBuffer.setLength(0)
        activeStreamResponseDirty = false
        clearCompletedToolTracking()
    }

    private fun syncTimelineSession(force: Boolean = false) {
        val sessionKey = currentTimelineSessionKey() ?: return
        if (!force && activeTimelineSessionKey == sessionKey) {
            return
        }

        activeTimelineSessionKey?.let { existingSessionKey ->
            if (force || existingSessionKey != sessionKey) {
                flushTimelinePersistence(existingSessionKey)
            }
        }

        timelinePersistJob?.cancel()
        timelinePersistJob = null
        resetRunTracking()
        lastModifications = emptyList()
        clearPendingPromptQueue()

        activeTimelineSessionKey = sessionKey
        replaceLoadedTimelineWindow(
            timelineStore.loadLatest(
                sessionKey = sessionKey,
                limit = INITIAL_VISIBLE_TIMELINE_ITEMS
            )
        )
    }

    private fun currentTimelineSessionKey(): String? {
        ensureActiveAssistantSession()
        return aiAgent.getCurrentSessionStorageKey()
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun ensureActiveAssistantSession(): AIAssistantChatSession? {
        if (projectRootPath.isBlank()) {
            return null
        }
        val resolvedSession = activeAssistantSession ?: sessionRegistry.getOrCreateActive(projectRootPath)
        activeAssistantSession = resolvedSession
        aiAgent.setConversationSessionId(resolvedSession.id)
        return resolvedSession
    }

    private fun activateAssistantSession(session: AIAssistantChatSession) {
        cancelExecution(
            manualStop = false,
            showStoppedStatus = false,
            clearQueuedPrompts = true,
            continueWithQueuedPrompts = false
        )
        activeAssistantSession = session
        promptDraft = ""
        promptDraftDirty = false
        aiAgent.setConversationSessionId(session.id)
        syncTimelineSession(force = true)
        if (timelineItems.isEmpty()) {
            ensureWelcome()
        } else {
            publishState()
        }
    }

    private fun listAvailableSessions(): List<AIAssistantChatSession> {
        if (projectRootPath.isBlank()) {
            return emptyList()
        }
        return sessionRegistry.listSessions(projectRootPath)
    }

    private fun filterSessions(
        sessions: List<AIAssistantChatSession>,
        query: String
    ): List<AIAssistantChatSession> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return sessions
        }
        return sessions.filter { session ->
            session.id.startsWith(normalizedQuery, ignoreCase = true) ||
                session.title.contains(normalizedQuery, ignoreCase = true) ||
                session.summary?.contains(normalizedQuery, ignoreCase = true) == true
        }
    }

    private fun resolveAssistantSessionTarget(target: String): AIAssistantChatSession? {
        val normalizedTarget = target.trim()
        if (normalizedTarget.isBlank()) {
            return null
        }
        val sessions = listAvailableSessions()
        normalizedTarget.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { index -> sessions.getOrNull(index - 1) }
            ?.let { return it }

        return sessions.firstOrNull { session ->
            session.id.startsWith(normalizedTarget, ignoreCase = true) ||
                session.title.equals(normalizedTarget, ignoreCase = true)
        } ?: sessions.firstOrNull { session ->
            session.title.contains(normalizedTarget, ignoreCase = true) ||
                session.summary?.contains(normalizedTarget, ignoreCase = true) == true
        }
    }

    private fun resolveAssistantSessionTargets(target: String): List<AIAssistantChatSession> {
        val sessions = listAvailableSessions()
        if (sessions.isEmpty()) {
            return emptyList()
        }

        val resolved = linkedMapOf<String, AIAssistantChatSession>()
        target.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { token ->
                val rangeParts = token.split('-', limit = 2)
                if (rangeParts.size == 2) {
                    val start = rangeParts[0].trim().toIntOrNull()
                    val end = rangeParts[1].trim().toIntOrNull()
                    if (start != null && end != null) {
                        val lower = minOf(start, end)
                        val upper = maxOf(start, end)
                        for (index in lower..upper) {
                            sessions.getOrNull(index - 1)?.let { session ->
                                resolved.putIfAbsent(session.id, session)
                            }
                        }
                        return@forEach
                    }
                }
                resolveAssistantSessionTarget(token)?.let { session ->
                    resolved.putIfAbsent(session.id, session)
                }
            }
        return resolved.values.toList()
    }

    private fun touchActiveAssistantSession() {
        val projectRoot = projectRootPath.takeIf { it.isNotBlank() } ?: return
        val sessionId = activeAssistantSession?.id ?: return
        sessionRegistry.touchSession(projectRoot, sessionId)
    }

    private fun recordActiveSessionUserPrompt(prompt: String) {
        val projectRoot = projectRootPath.takeIf { it.isNotBlank() } ?: return
        val sessionId = activeAssistantSession?.id ?: return
        val summary = prompt
            .trim()
            .replace(Regex("\\s+"), " ")
            .takeIf(String::isNotBlank)
        sessionRegistry.recordSessionTurn(
            projectRoot = projectRoot,
            sessionId = sessionId,
            latestSummary = summary,
            messageCountDelta = 1
        )
    }

    private fun recordActiveSessionAssistantReply() {
        val projectRoot = projectRootPath.takeIf { it.isNotBlank() } ?: return
        val sessionId = activeAssistantSession?.id ?: return
        sessionRegistry.recordSessionTurn(
            projectRoot = projectRoot,
            sessionId = sessionId,
            messageCountDelta = 1
        )
    }

    private fun formatSessionTimestamp(timestampMillis: Long): String {
        if (timestampMillis <= 0L) {
            return "updated recently"
        }
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(timestampMillis))
    }

    private fun AIAssistantChatSession.toBrowserEntry(order: Int): AIAssistantSessionBrowserEntry {
        val summaryText = summary?.trim()?.ifBlank { null }
        val sessionMeta = buildString {
            if (id == activeAssistantSession?.id) {
                append("Current")
                append(" · ")
            }
            append(
                if (messageCount == 1) {
                    "1 msg"
                } else {
                    "${messageCount.coerceAtLeast(0)} msgs"
                }
            )
            append(" · ")
            append(formatSessionTimestamp(updatedAtMillis))
        }
        return AIAssistantSessionBrowserEntry(
            sessionId = id,
            order = order,
            title = title,
            summary = summaryText,
            meta = sessionMeta,
            isActive = id == activeAssistantSession?.id
        )
    }

    private fun List<AIAssistantTimelineItem>.toConversationHistoryLines(): List<String> {
        return mapNotNull { item ->
            when (item) {
                is AIAssistantUserItem -> "User\n${item.prompt.trim()}"
                is AIAssistantResponseItem -> item.response.trim()
                    .takeIf(String::isNotBlank)
                    ?.let { response -> "Assistant\n$response" }
                is AIAssistantStreamingResponseItem -> item.response.trim()
                    .takeIf(String::isNotBlank)
                    ?.let { response -> "Assistant\n$response" }
                else -> null
            }
        }
    }

    private fun showMemoryFileItem(file: File, isGlobal: Boolean): AIAssistantStatusItem {
        val label = if (isGlobal) "Global memory" else "Project memory"
        if (!file.exists()) {
            return AIAssistantStatusItem(
                title = "Memory",
                body = "$label file is empty.\nPath: ${file.absolutePath}",
                tone = AIAssistantTone.NEUTRAL
            )
        }

        val content = runCatching { file.readText() }.getOrNull().orEmpty().trim()
        return AIAssistantStatusItem(
            title = "Memory",
            body = if (content.isBlank()) {
                "$label file is empty.\nPath: ${file.absolutePath}"
            } else {
                buildString {
                    appendLine("$label · ${file.absolutePath}")
                    appendLine()
                    append(
                        if (content.length > MAX_MEMORY_FILE_PREVIEW_CHARS) {
                            content.take(MAX_MEMORY_FILE_PREVIEW_CHARS) + "\n\n... (truncated)"
                        } else {
                            content
                        }
                    )
                }.trim()
            },
            tone = AIAssistantTone.NEUTRAL
        )
    }

    private fun appendMemoryFileItem(file: File, text: String): AIAssistantStatusItem {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return AIAssistantStatusItem(
                title = "Memory",
                body = "Usage: /memory add <text>",
                tone = AIAssistantTone.WARNING
            )
        }

        return runCatching {
            file.parentFile?.mkdirs()
            val existing = if (file.exists()) file.readText() else ""
            val separator = when {
                existing.isBlank() -> ""
                existing.endsWith('\n') -> ""
                else -> "\n"
            }
            file.writeText(existing + separator + "- $normalizedText\n")
            AIAssistantStatusItem(
                title = "Memory",
                body = "Saved to ${file.absolutePath}",
                tone = AIAssistantTone.SUCCESS
            )
        }.getOrElse { error ->
            AIAssistantStatusItem(
                title = "Memory",
                body = error.message ?: "Unable to update the memory file.",
                tone = AIAssistantTone.ERROR
            )
        }
    }

    private fun scheduleTimelinePersistence() {
        val sessionKey = activeTimelineSessionKey ?: currentTimelineSessionKey() ?: return
        if (!promptDraftDirty && dirtyTimelineItemIds.isEmpty()) {
            return
        }
        timelinePersistJob?.cancel()
        timelinePersistJob = viewModelScope.launch {
            delay(180L)
            val snapshot = captureTimelinePersistenceSnapshot(sessionKey)
            if (snapshot == null) {
                timelinePersistJob = null
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                timelineStore.saveChanges(
                    sessionKey = snapshot.sessionKey,
                    promptDraft = snapshot.promptDraft,
                    persistPromptDraft = snapshot.persistPromptDraft,
                    entries = snapshot.entries
                )
            }
            if (saved) {
                if (trimLoadedTimelineWindowIfNeeded()) {
                    publishStateNow(persistTimeline = false)
                }
            } else {
                restoreTimelinePersistenceSnapshot(snapshot)
            }
            timelinePersistJob = null
        }
    }

    private fun flushTimelinePersistence(sessionKey: String? = activeTimelineSessionKey) {
        val resolvedSessionKey = sessionKey ?: return
        timelinePersistJob?.cancel()
        timelinePersistJob = null
        flushActiveAssistantResponseIfNeeded(markDirtyForPersistence = true)
        val snapshot = captureTimelinePersistenceSnapshot(resolvedSessionKey) ?: return
        val saved = timelineStore.saveChanges(
            sessionKey = snapshot.sessionKey,
            promptDraft = snapshot.promptDraft,
            persistPromptDraft = snapshot.persistPromptDraft,
            entries = snapshot.entries
        )
        if (saved) {
            if (trimLoadedTimelineWindowIfNeeded()) {
                publishStateNow(persistTimeline = false)
            }
        } else {
            restoreTimelinePersistenceSnapshot(snapshot)
        }
    }

    private data class TimelinePersistenceSnapshot(
        val sessionKey: String,
        val promptDraft: String,
        val persistPromptDraft: Boolean,
        val dirtyItemIds: Set<Long>,
        val entries: List<PersistedAIAssistantTimelineEntry>
    )

    private fun normalizeTimelineItemForPersistence(
        item: AIAssistantTimelineItem
    ): AIAssistantTimelineItem {
        if (item is AIAssistantStreamingResponseItem) {
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
                placeholder = item.placeholder?.let(::trimSmallTimelineText),
                response = trimAssistantResponseText(item.response),
                status = null,
                attachments = normalizedAttachments,
                isWorking = false,
                isStreaming = false
            )
        }

        return when (item) {
            is AIAssistantUserItem -> item.copy(prompt = trimSmallTimelineText(item.prompt))
            is AIAssistantResponseItem -> item.copy(response = trimAssistantResponseText(item.response))
            is AIAssistantWelcomeItem -> item.copy(body = trimSmallTimelineText(item.body))
            is AIAssistantStatusItem -> item.copy(body = item.body?.let(::trimSmallTimelineText))
            is AIAssistantSessionBrowserItem -> item.copy(
                subtitle = item.subtitle?.let(::trimSmallTimelineText),
                sessions = item.sessions.map { session ->
                    session.copy(
                        title = trimSmallTimelineText(session.title),
                        summary = session.summary?.let(::trimSmallTimelineText),
                        meta = trimSmallTimelineText(session.meta)
                    )
                }
            )
            else -> item
        }
    }

    private fun flushActiveAssistantResponseIfNeeded(markDirtyForPersistence: Boolean) {
        if (!activeStreamResponseDirty) {
            return
        }
        val itemId = activeStreamItemId ?: return
        val index = timelineItems.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return
        }
        val streamItem = timelineItems[index] as? AIAssistantStreamingResponseItem ?: return
        val fullResponseText = activeStreamResponseBuffer.toString()
        val responseText = if (markDirtyForPersistence) {
            fullResponseText
        } else {
            buildVisibleStreamingResponse(fullResponseText)
        }
        val updatedItem = streamItem.copy(
            header = "Working",
            response = responseText,
            status = buildWorkingElapsedLabel(),
            isWorking = true,
            isStreaming = true
        )
        if (updatedItem == streamItem) {
            activeStreamResponseDirty = false
            return
        }
        timelineItems[index] = updatedItem
        if (markDirtyForPersistence) {
            markTimelineItemDirty(updatedItem.id)
        }
        activeStreamResponseDirty = false
    }

    private fun buildVisibleStreamingResponse(fullResponse: String): String {
        if (fullResponse.length <= MAX_VISIBLE_STREAM_RESPONSE_CHARS) {
            return fullResponse
        }
        if (fullResponse.startsWith(ASSISTANT_RESPONSE_TRUNCATED_NOTICE)) {
            val retainedTailChars = (MAX_VISIBLE_STREAM_RESPONSE_CHARS - ASSISTANT_RESPONSE_TRUNCATED_NOTICE.length)
                .coerceAtLeast(1)
            return ASSISTANT_RESPONSE_TRUNCATED_NOTICE + fullResponse.takeLast(retainedTailChars)
        }
        val visibleTail = fullResponse.takeLast((MAX_VISIBLE_STREAM_RESPONSE_CHARS - 2).coerceAtLeast(1))
        return "…\n$visibleTail"
    }

    private fun timelineRetainedCharCount(): Int {
        return timelineItems.sumOf(::estimatedRetainedChars)
    }

    private fun estimatedRetainedChars(item: AIAssistantTimelineItem): Int {
        return when (item) {
            is AIAssistantWelcomeItem -> item.title.length + item.body.length
            is AIAssistantUserItem -> item.prompt.length
            is AIAssistantResponseItem -> item.response.length
            is AIAssistantStreamingResponseItem -> {
                item.header.length +
                    item.placeholder.orEmpty().length +
                    item.response.length +
                    item.status.orEmpty().length +
                    item.attachments.sumOf(::estimatedRetainedChars)
            }
            is AIAssistantStatusItem -> item.title.length + item.body.orEmpty().length
            is AIAssistantSessionBrowserItem -> {
                item.title.length +
                    item.subtitle.orEmpty().length +
                    item.sessions.sumOf { session ->
                        session.sessionId.length +
                            session.title.length +
                            session.summary.orEmpty().length +
                            session.meta.length
                    }
            }
            is AIAssistantHistoryDividerItem -> 0
            is AIAssistantDiffItem -> {
                item.filePath.length +
                    item.changeLabel.length +
                    item.preview.addedLines.sumOf(String::length) +
                    item.preview.removedLines.sumOf(String::length)
            }
        }
    }

    private fun estimatedRetainedChars(item: AIAssistantToolItem): Int {
        return item.title.length +
            item.summary.length +
            item.stageTrail.length +
            item.command.orEmpty().length +
            item.workingDirectory.orEmpty().length +
            item.outputPreview.orEmpty().length
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

    private data class QueuedPrompt(
        val prompt: String,
        val userMessageAlreadyAppended: Boolean
    )

    private data class PendingToolItem(
        val key: String,
        val itemId: Long,
        val startedAtMs: Long,
        var sawOutput: Boolean = false,
        var outputChunks: Int = 0
    )

    private fun toReviewableModification(
        modification: AIAgentManager.ModificationResult
    ): ModificationData {
        return ModificationData(
            filePath = modification.filePath,
            content = modification.content,
            isNewFile = modification.isNewFile
        )
    }

    private fun trimAssistantResponseText(text: String): String {
        val normalized = text.trim()
        if (normalized.length <= MAX_ASSISTANT_RESPONSE_CHARS) {
            return normalized
        }
        val retainedTailChars = (MAX_ASSISTANT_RESPONSE_CHARS - ASSISTANT_RESPONSE_TRUNCATED_NOTICE.length)
            .coerceAtLeast(4_096)
        return ASSISTANT_RESPONSE_TRUNCATED_NOTICE + normalized.takeLast(retainedTailChars).trimStart()
    }

    private fun trimSmallTimelineText(text: String): String {
        val normalized = text.trim()
        if (normalized.length <= MAX_SMALL_TIMELINE_TEXT_CHARS) {
            return normalized
        }
        val retainedTailChars = (MAX_SMALL_TIMELINE_TEXT_CHARS - TIMELINE_TEXT_TRUNCATED_NOTICE.length)
            .coerceAtLeast(2_048)
        return TIMELINE_TEXT_TRUNCATED_NOTICE + normalized.takeLast(retainedTailChars).trimStart()
    }

    private fun trimAssistantResponseBuffer(buffer: StringBuilder): Boolean {
        if (buffer.length <= MAX_ASSISTANT_RESPONSE_CHARS) {
            return false
        }
        val retainedTailChars = (MAX_ASSISTANT_RESPONSE_CHARS - ASSISTANT_RESPONSE_TRUNCATED_NOTICE.length)
            .coerceAtLeast(4_096)
        val retainedTail = buffer.toString().takeLast(retainedTailChars).trimStart()
        buffer.setLength(0)
        buffer.append(ASSISTANT_RESPONSE_TRUNCATED_NOTICE)
        buffer.append(retainedTail)
        return true
    }

    companion object {
        private const val INITIAL_VISIBLE_TIMELINE_ITEMS = 48
        private const val TIMELINE_HISTORY_PAGE_SIZE = 32
        private const val THROTTLED_STATE_PUBLISH_INTERVAL_MS = 120L
        private const val MAX_QUEUED_PROMPTS = 8
        private const val MAX_IN_MEMORY_TIMELINE_ITEMS = 192
        private const val MAX_IN_MEMORY_TIMELINE_CHARS = 240_000
        private const val MAX_VISIBLE_STREAM_RESPONSE_CHARS = 16_384
        private const val MAX_ASSISTANT_RESPONSE_CHARS = 96_000
        private const val MAX_SMALL_TIMELINE_TEXT_CHARS = 24_000
        private const val MAX_MEMORY_FILE_PREVIEW_CHARS = 12_000
        private const val ASSISTANT_RESPONSE_TRUNCATED_NOTICE =
            "[Earlier assistant output truncated to keep the session stable]\n\n"
        private const val TIMELINE_TEXT_TRUNCATED_NOTICE =
            "[Earlier content truncated to keep the session stable]\n\n"
    }
}

data class AIAssistantConsoleUiState(
    val providerLabel: String = "Unknown",
    val modelLabel: String = "",
    val sessionLabel: String = "",
    val promptDraft: String = "",
    val isRunning: Boolean = false,
    val queuedPromptCount: Int = 0,
    val hasReviewableChanges: Boolean = false,
    val canLoadMoreHistory: Boolean = false,
    val hiddenHistoryCount: Int = 0,
    val totalTimelineCount: Int = 0,
    val timelineItems: List<AIAssistantTimelineItem> = emptyList()
)

data class AIAssistantSessionDeleteResult(
    val deletedCount: Int,
    val activeSessionLabel: String?,
    val message: String
)

enum class AIAssistantPromptDispatchResult {
    STARTED,
    QUEUED,
    INTERRUPTED,
    QUEUE_FULL,
    REJECTED_EMPTY
}
