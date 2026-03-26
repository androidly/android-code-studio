package com.tom.rv2ide.fragments.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface AIAssistantTimelinePersistenceHost {
    val timelineStore: AIAssistantTimelineStore
    val streamingTurnController: AIAssistantStreamingTurnController
    val timelineItems: MutableList<AIAssistantTimelineItem>
    val timelineOrderIndexes: MutableMap<Long, Long>
    val dirtyTimelineItemIds: LinkedHashSet<Long>
    var promptDraft: String
    var promptDraftDirty: Boolean
    var totalPersistedTimelineCount: Int
    var nextTimelineOrderIndex: Long
    var activeTimelineSessionKey: String?

    fun currentTimelineSessionKey(): String?
    fun markTimelineItemDirty(itemId: Long)
    fun publishStateNowWithoutPersistence()
}

internal class AIAssistantTimelinePersistenceController(
    private val scope: CoroutineScope,
    private val host: AIAssistantTimelinePersistenceHost
) {
    private var timelinePersistJob: Job? = null

    fun cancelScheduledPersistence() {
        timelinePersistJob?.cancel()
        timelinePersistJob = null
    }

    fun dispose() {
        cancelScheduledPersistence()
    }

    fun scheduleTimelinePersistence() {
        val sessionKey = host.activeTimelineSessionKey ?: host.currentTimelineSessionKey() ?: return
        if (!host.promptDraftDirty && host.dirtyTimelineItemIds.isEmpty()) {
            return
        }
        cancelScheduledPersistence()
        timelinePersistJob = scope.launch {
            delay(180L)
            val snapshot = captureTimelinePersistenceSnapshot(sessionKey)
            if (snapshot == null) {
                timelinePersistJob = null
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                host.timelineStore.saveChanges(
                    sessionKey = snapshot.sessionKey,
                    promptDraft = snapshot.promptDraft,
                    persistPromptDraft = snapshot.persistPromptDraft,
                    entries = snapshot.entries
                )
            }
            if (saved) {
                if (trimLoadedTimelineWindowIfNeeded()) {
                    host.publishStateNowWithoutPersistence()
                }
            } else {
                restoreTimelinePersistenceSnapshot(snapshot)
            }
            timelinePersistJob = null
        }
    }

    fun flushTimelinePersistence(sessionKey: String? = host.activeTimelineSessionKey) {
        val resolvedSessionKey = sessionKey ?: return
        cancelScheduledPersistence()
        host.streamingTurnController.flushActiveAssistantResponseIfNeeded(markDirtyForPersistence = true)
        val snapshot = captureTimelinePersistenceSnapshot(resolvedSessionKey) ?: return
        val saved = host.timelineStore.saveChanges(
            sessionKey = snapshot.sessionKey,
            promptDraft = snapshot.promptDraft,
            persistPromptDraft = snapshot.persistPromptDraft,
            entries = snapshot.entries
        )
        if (saved) {
            if (trimLoadedTimelineWindowIfNeeded()) {
                host.publishStateNowWithoutPersistence()
            }
        } else {
            restoreTimelinePersistenceSnapshot(snapshot)
        }
    }

    fun normalizeTimelineItemForPersistence(
        item: AIAssistantTimelineItem
    ): AIAssistantTimelineItem {
        if (item is AIAssistantStreamingResponseItem) {
            val normalizedAttachments = item.attachments.map { attachment ->
                if (!attachment.isLive) {
                    attachment
                } else {
                    attachment.copy(
                        stage = AIAssistantToolStage.CANCELLED,
                        stageTrail = host.streamingTurnController.cancelledToolStageTrail(
                            sawOutput = !attachment.outputPreview.isNullOrBlank()
                        )
                    )
                }
            }

            return item.copy(
                header = if (item.response.isNotBlank()) "Assistant" else item.header.ifBlank { "Assistant" },
                placeholder = item.placeholder?.let(::trimSmallTimelineText),
                response = host.streamingTurnController.trimAssistantResponseText(item.response),
                status = null,
                attachments = normalizedAttachments,
                isWorking = false,
                isStreaming = false
            )
        }

        return when (item) {
            is AIAssistantUserItem -> item.copy(prompt = trimSmallTimelineText(item.prompt))
            is AIAssistantResponseItem -> item.copy(
                response = host.streamingTurnController.trimAssistantResponseText(item.response)
            )
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

    private fun buildDirtyTimelineEntries(
        dirtyItemIds: Set<Long>
    ): List<PersistedAIAssistantTimelineEntry> {
        return host.timelineItems.mapNotNull { item ->
            if (item.id !in dirtyItemIds) {
                return@mapNotNull null
            }
            val orderIndex = host.timelineOrderIndexes[item.id] ?: return@mapNotNull null
            PersistedAIAssistantTimelineEntry(
                orderIndex = orderIndex,
                item = normalizeTimelineItemForPersistence(item)
            )
        }
    }

    private fun captureTimelinePersistenceSnapshot(
        expectedSessionKey: String
    ): TimelinePersistenceSnapshot? {
        val activeSessionKey = host.activeTimelineSessionKey ?: return null
        if (activeSessionKey != expectedSessionKey) {
            return null
        }
        if (!host.promptDraftDirty && host.dirtyTimelineItemIds.isEmpty()) {
            return null
        }

        val dirtyItemIds = host.dirtyTimelineItemIds.toSet()
        val snapshot = TimelinePersistenceSnapshot(
            sessionKey = activeSessionKey,
            promptDraft = host.promptDraft,
            persistPromptDraft = host.promptDraftDirty,
            dirtyItemIds = dirtyItemIds,
            entries = buildDirtyTimelineEntries(dirtyItemIds)
        )
        host.promptDraftDirty = false
        host.dirtyTimelineItemIds.removeAll(dirtyItemIds)
        return snapshot
    }

    private fun restoreTimelinePersistenceSnapshot(snapshot: TimelinePersistenceSnapshot) {
        if (host.activeTimelineSessionKey != snapshot.sessionKey) {
            return
        }
        if (snapshot.persistPromptDraft) {
            host.promptDraftDirty = true
        }
        host.dirtyTimelineItemIds += snapshot.dirtyItemIds
    }

    private fun trimLoadedTimelineWindowIfNeeded(): Boolean {
        var trimmed = false
        if (host.timelineItems.size > MAX_IN_MEMORY_TIMELINE_ITEMS) {
            val beforeSize = host.timelineItems.size
            removeLoadedItemsFromStart(host.timelineItems.size - MAX_IN_MEMORY_TIMELINE_ITEMS)
            trimmed = host.timelineItems.size != beforeSize
        }
        while (timelineRetainedCharCount() > MAX_IN_MEMORY_TIMELINE_CHARS) {
            val firstItem = host.timelineItems.firstOrNull() ?: break
            if (host.dirtyTimelineItemIds.contains(firstItem.id)) {
                break
            }
            host.timelineItems.removeAt(0)
            host.timelineOrderIndexes.remove(firstItem.id)
            trimmed = true
        }
        return trimmed
    }

    private fun removeLoadedItemsFromStart(maxCount: Int) {
        if (maxCount <= 0) {
            return
        }
        repeat(maxCount) {
            val firstItem = host.timelineItems.firstOrNull() ?: return
            if (host.dirtyTimelineItemIds.contains(firstItem.id)) {
                return
            }
            host.timelineItems.removeAt(0)
            host.timelineOrderIndexes.remove(firstItem.id)
        }
    }

    private fun timelineRetainedCharCount(): Int {
        return host.timelineItems.sumOf(::estimatedRetainedChars)
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

    private fun trimSmallTimelineText(text: String): String {
        val normalized = text.trim()
        if (normalized.length <= MAX_SMALL_TIMELINE_TEXT_CHARS) {
            return normalized
        }
        val retainedTailChars = (MAX_SMALL_TIMELINE_TEXT_CHARS - TIMELINE_TEXT_TRUNCATED_NOTICE.length)
            .coerceAtLeast(2_048)
        return TIMELINE_TEXT_TRUNCATED_NOTICE + normalized.takeLast(retainedTailChars).trimStart()
    }

    private data class TimelinePersistenceSnapshot(
        val sessionKey: String,
        val promptDraft: String,
        val persistPromptDraft: Boolean,
        val dirtyItemIds: Set<Long>,
        val entries: List<PersistedAIAssistantTimelineEntry>
    )

    private companion object {
        private const val MAX_IN_MEMORY_TIMELINE_ITEMS = 192
        private const val MAX_IN_MEMORY_TIMELINE_CHARS = 240_000
        private const val MAX_SMALL_TIMELINE_TEXT_CHARS = 24_000
        private const val TIMELINE_TEXT_TRUNCATED_NOTICE =
            "[Earlier content truncated to keep the session stable]\n\n"
    }
}
