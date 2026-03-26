package com.tom.rv2ide.fragments.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom.rv2ide.activities.ModificationData
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AIAssistantConsoleViewModel(application: Application) : AndroidViewModel(application) {
    val aiAgent: AIAgentManager = AIAgentManager(application)
    private val timelineStore = AIAssistantTimelineStore(application)
    private val sessionRegistry = AIAssistantSessionRegistry(application)
    private val sessionCommands: AIAssistantSessionCommandController by lazy {
        AIAssistantSessionCommandController(
            object : AIAssistantSessionCommandHost {
                override val aiAgent: AIAgentManager
                    get() = this@AIAssistantConsoleViewModel.aiAgent
                override val timelineStore: AIAssistantTimelineStore
                    get() = this@AIAssistantConsoleViewModel.timelineStore
                override val sessionRegistry: AIAssistantSessionRegistry
                    get() = this@AIAssistantConsoleViewModel.sessionRegistry

                override fun projectRootPath(): String = projectRootPath

                override fun activeAssistantSession(): AIAssistantChatSession? = activeAssistantSession

                override fun activeTimelineSessionKey(): String? = activeTimelineSessionKey

                override fun currentTimelineSessionKey(): String? =
                    this@AIAssistantConsoleViewModel.currentTimelineSessionKey()

                override fun totalPersistedTimelineCount(): Int = totalPersistedTimelineCount

                override fun pendingPromptCount(): Int = executionController.queuedPromptCount()

                override fun isExecutionRunning(): Boolean = executionController.isRunning()

                override fun timelineItemsSnapshot(): List<AIAssistantTimelineItem> = timelineItems.toList()

                override fun appendTimelineItem(item: AIAssistantTimelineItem) {
                    this@AIAssistantConsoleViewModel.appendTimelineItem(item)
                }

                override fun publishState() {
                    this@AIAssistantConsoleViewModel.publishState()
                }

                override fun activateAssistantSession(session: AIAssistantChatSession) {
                    this@AIAssistantConsoleViewModel.activateAssistantSession(session)
                }

                override fun flushTimelinePersistence(sessionKey: String?) {
                    timelinePersistenceController.flushTimelinePersistence(sessionKey)
                }
            }
        )
    }
    private val streamingTurnController: AIAssistantStreamingTurnController by lazy {
        AIAssistantStreamingTurnController(
            scope = viewModelScope,
            host = object : AIAssistantStreamingTurnHost {
                override fun appendTimelineItem(item: AIAssistantTimelineItem) {
                    this@AIAssistantConsoleViewModel.appendTimelineItem(item)
                }

                override fun updateTimelineItem(
                    itemId: Long,
                    transformer: (AIAssistantTimelineItem) -> AIAssistantTimelineItem
                ) {
                    this@AIAssistantConsoleViewModel.updateTimelineItem(itemId, transformer)
                }

                override fun moveTimelineItemToEnd(itemId: Long) {
                    this@AIAssistantConsoleViewModel.moveTimelineItemToEnd(itemId)
                }

                override fun markTimelineItemDirty(itemId: Long) {
                    this@AIAssistantConsoleViewModel.markTimelineItemDirty(itemId)
                }

                override fun timelineItemsSnapshot(): List<AIAssistantTimelineItem> {
                    return timelineItems.toList()
                }

                override fun publishState() {
                    this@AIAssistantConsoleViewModel.publishState()
                }

                override fun publishStateThrottled(
                    persistTimeline: Boolean,
                    intervalMs: Long
                ) {
                    this@AIAssistantConsoleViewModel.publishStateThrottled(
                        persistTimeline = persistTimeline,
                        intervalMs = intervalMs
                    )
                }

                override fun isExecutionActive(): Boolean {
                    return executionController.isExecutionActiveForStreaming()
                }
            }
        )
    }
    private val executionController: AIAssistantPromptExecutionController by lazy {
        AIAssistantPromptExecutionController(
            scope = viewModelScope,
            aiAgent = aiAgent,
            streamingTurnController = streamingTurnController,
            host = object : AIAssistantPromptExecutionHost {
                override fun touchActiveSession() {
                    sessionCommands.touchActiveSession()
                }

                override fun clearPromptDraftForDispatch() {
                    promptDraft = ""
                    promptDraftDirty = true
                }

                override fun clearReviewableChanges() {
                    lastModifications = emptyList()
                }

                override fun appendAndRecordUserPrompt(prompt: String) {
                    appendTimelineItem(AIAssistantUserItem(prompt))
                    sessionCommands.recordUserPrompt(prompt)
                }

                override fun recordAssistantReply() {
                    sessionCommands.recordAssistantReply()
                }

                override fun applyModificationResults(
                    modifications: List<AIAgentManager.ModificationResult>
                ) {
                    lastModifications = modifications.map(::toReviewableModification)
                    appendDiffItems(modifications)
                }

                override fun buildModificationSummaryText(
                    summary: AIAgentManager.ModificationSummary
                ): String {
                    return this@AIAssistantConsoleViewModel.buildModificationSummaryText(summary)
                }

                override fun appendStatusItem(
                    title: String,
                    body: String?,
                    tone: AIAssistantTone
                ) {
                    appendTimelineItem(
                        AIAssistantStatusItem(
                            title = title,
                            body = body,
                            tone = tone
                        )
                    )
                }

                override fun refreshFileIfNeeded(filePath: String) {
                    fileRefreshHandler?.invoke(filePath)
                }

                override fun publishState() {
                    this@AIAssistantConsoleViewModel.publishState()
                }
            }
        )
    }
    private val timelinePersistenceController: AIAssistantTimelinePersistenceController by lazy {
        AIAssistantTimelinePersistenceController(
            scope = viewModelScope,
            host = object : AIAssistantTimelinePersistenceHost {
                override val timelineStore: AIAssistantTimelineStore
                    get() = this@AIAssistantConsoleViewModel.timelineStore
                override val streamingTurnController: AIAssistantStreamingTurnController
                    get() = this@AIAssistantConsoleViewModel.streamingTurnController
                override val timelineItems: MutableList<AIAssistantTimelineItem>
                    get() = this@AIAssistantConsoleViewModel.timelineItems
                override val timelineOrderIndexes: MutableMap<Long, Long>
                    get() = this@AIAssistantConsoleViewModel.timelineOrderIndexes
                override val dirtyTimelineItemIds: LinkedHashSet<Long>
                    get() = this@AIAssistantConsoleViewModel.dirtyTimelineItemIds
                override var promptDraft: String
                    get() = this@AIAssistantConsoleViewModel.promptDraft
                    set(value) {
                        this@AIAssistantConsoleViewModel.promptDraft = value
                    }
                override var promptDraftDirty: Boolean
                    get() = this@AIAssistantConsoleViewModel.promptDraftDirty
                    set(value) {
                        this@AIAssistantConsoleViewModel.promptDraftDirty = value
                    }
                override var totalPersistedTimelineCount: Int
                    get() = this@AIAssistantConsoleViewModel.totalPersistedTimelineCount
                    set(value) {
                        this@AIAssistantConsoleViewModel.totalPersistedTimelineCount = value
                    }
                override var nextTimelineOrderIndex: Long
                    get() = this@AIAssistantConsoleViewModel.nextTimelineOrderIndex
                    set(value) {
                        this@AIAssistantConsoleViewModel.nextTimelineOrderIndex = value
                    }
                override var activeTimelineSessionKey: String?
                    get() = this@AIAssistantConsoleViewModel.activeTimelineSessionKey
                    set(value) {
                        this@AIAssistantConsoleViewModel.activeTimelineSessionKey = value
                    }

                override fun currentTimelineSessionKey(): String? {
                    return this@AIAssistantConsoleViewModel.currentTimelineSessionKey()
                }

                override fun markTimelineItemDirty(itemId: Long) {
                    this@AIAssistantConsoleViewModel.markTimelineItemDirty(itemId)
                }

                override fun publishStateNowWithoutPersistence() {
                    this@AIAssistantConsoleViewModel.publishStateNow(persistTimeline = false)
                }
            }
        )
    }

    private val _uiState = MutableStateFlow(AIAssistantConsoleUiState())
    val uiState: StateFlow<AIAssistantConsoleUiState> = _uiState.asStateFlow()

    private val timelineItems = mutableListOf<AIAssistantTimelineItem>()
    private val timelineOrderIndexes = mutableMapOf<Long, Long>()
    private val dirtyTimelineItemIds = linkedSetOf<Long>()
    private var lastModifications: List<ModificationData> = emptyList()
    private var projectRootPath: String = ""
    private var promptDraft: String = ""
    private var promptDraftDirty: Boolean = false
    private var activeAssistantSession: AIAssistantChatSession? = null
    private var activeTimelineSessionKey: String? = null
    private var totalPersistedTimelineCount: Int = 0
    private var nextTimelineOrderIndex: Long = 0L
    private var fileRefreshHandler: ((String) -> Unit)? = null
    private var throttledStatePublishJob: Job? = null
    private var throttledStateNeedsPersistence: Boolean = false

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

    fun applyAgentSelection(
        providerId: String,
        preferredModel: String? = null
    ): Boolean {
        ensureActiveAssistantSession()

        val agents = Agents(getApplication())
        val previousProvider = agents.getProvider()
        val previousModel = agents.getAgent()
        val targetModel = preferredModel?.takeIf { it.isNotBlank() }
            ?: agents.getModelsForProvider(providerId).firstOrNull()

        agents.setProvider(providerId)
        targetModel?.let(agents::setAgent)

        val applied = aiAgent.syncSelectedProviderAndModel()
        if (!applied) {
            agents.setProvider(previousProvider)
            if (previousModel.isNotBlank()) {
                agents.setAgent(previousModel)
            }
            aiAgent.syncSelectedProviderAndModel()
        }

        publishState(persistTimeline = false)
        return applied
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
        return sessionCommands.createNewSession(nameHint)
    }

    fun switchSession(target: String): String? {
        return sessionCommands.switchSession(target)
    }

    fun showSessionList(
        query: String? = null,
        emphasizeDelete: Boolean = false
    ) {
        sessionCommands.showSessionList(query, emphasizeDelete)
    }

    fun showHistory(limit: Int = 10) {
        sessionCommands.showHistory(limit)
    }

    fun showStatus() {
        sessionCommands.showStatus()
    }

    fun requestConversationCompression() {
        sessionCommands.requestConversationCompression()
    }

    fun showMemoryCommand(rawArguments: String) {
        sessionCommands.showMemoryCommand(rawArguments)
    }

    fun deleteSessions(target: String): AIAssistantSessionDeleteResult {
        return sessionCommands.deleteSessions(target)
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
        executionController.cancelExecution(
            manualStop = false,
            showStoppedStatus = false,
            clearQueuedPrompts = true,
            continueWithQueuedPrompts = false
        )
        lastModifications = emptyList()
        aiAgent.clearConversation()
        promptDraft = ""
        promptDraftDirty = false
        streamingTurnController.resetRunTracking()
        clearLoadedTimeline()
        totalPersistedTimelineCount = 0
        nextTimelineOrderIndex = 0L
        activeTimelineSessionKey?.let(timelineStore::clear)
        ensureWelcome()
        publishState()
    }

    fun stopExecution(): Int {
        return executionController.stopExecution()
    }

    fun executePrompt(prompt: String): AIAssistantPromptDispatchResult {
        return executionController.executePrompt(prompt)
    }

    fun interruptAndExecutePrompt(prompt: String): AIAssistantPromptDispatchResult {
        return executionController.interruptAndExecutePrompt(prompt)
    }

    override fun onCleared() {
        timelinePersistenceController.flushTimelinePersistence()
        cancelPendingThrottledStatePublish()
        executionController.dispose()
        streamingTurnController.dispose()
        timelinePersistenceController.dispose()
        fileRefreshHandler = null
        super.onCleared()
    }

    private fun publishState(persistTimeline: Boolean = true) {
        cancelPendingThrottledStatePublish()
        publishStateNow(persistTimeline)
    }

    private fun publishStateNow(persistTimeline: Boolean) {
        syncTimelineSession()
        streamingTurnController.flushActiveAssistantResponseIfNeeded(
            markDirtyForPersistence = persistTimeline
        )
        val visibleTimelineItems = buildVisibleTimelineItems()
        val hiddenHistoryCount = hiddenTimelineItemCount()
        _uiState.value = AIAssistantConsoleUiState(
            providerLabel = aiAgent.getCurrentProviderName(),
            modelLabel = aiAgent.getCurrentModelName(),
            sessionLabel = activeAssistantSession?.title.orEmpty(),
            promptDraft = promptDraft,
            isRunning = executionController.isRunning(),
            queuedPromptCount = executionController.queuedPromptCount(),
            hasReviewableChanges = lastModifications.isNotEmpty(),
            canLoadMoreHistory = hiddenHistoryCount > 0,
            hiddenHistoryCount = hiddenHistoryCount,
            totalTimelineCount = totalPersistedTimelineCount,
            timelineItems = visibleTimelineItems
        )
        if (persistTimeline) {
            timelinePersistenceController.scheduleTimelinePersistence()
        }
    }

    private fun publishStateThrottled(
        persistTimeline: Boolean = true,
        intervalMs: Long = THROTTLED_STATE_PUBLISH_INTERVAL_MS
    ) {
        throttledStateNeedsPersistence = throttledStateNeedsPersistence || persistTimeline
        if (throttledStatePublishJob?.isActive == true) {
            return
        }
        throttledStatePublishJob = viewModelScope.launch {
            delay(intervalMs)
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
        val normalizedItem = timelinePersistenceController.normalizeTimelineItemForPersistence(entry.item)
        timelineOrderIndexes[normalizedItem.id] = entry.orderIndex
        nextTimelineOrderIndex = maxOf(nextTimelineOrderIndex, entry.orderIndex + 1L)
        if (normalizedItem != entry.item) {
            dirtyTimelineItemIds += normalizedItem.id
        }
        return normalizedItem
    }

    private fun syncTimelineSession(force: Boolean = false) {
        val sessionKey = currentTimelineSessionKey() ?: return
        if (!force && activeTimelineSessionKey == sessionKey) {
            return
        }

        activeTimelineSessionKey?.let { existingSessionKey ->
            if (force || existingSessionKey != sessionKey) {
                timelinePersistenceController.flushTimelinePersistence(existingSessionKey)
            }
        }

        timelinePersistenceController.cancelScheduledPersistence()
        streamingTurnController.resetRunTracking()
        lastModifications = emptyList()
        executionController.clearPendingPromptQueue()

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
        executionController.cancelExecution(
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

    private fun toReviewableModification(
        modification: AIAgentManager.ModificationResult
    ): ModificationData {
        return ModificationData(
            filePath = modification.filePath,
            content = modification.content,
            isNewFile = modification.isNewFile
        )
    }

    companion object {
        private const val INITIAL_VISIBLE_TIMELINE_ITEMS = 48
        private const val TIMELINE_HISTORY_PAGE_SIZE = 32
        private const val THROTTLED_STATE_PUBLISH_INTERVAL_MS = 120L
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
