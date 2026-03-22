package com.tom.rv2ide.fragments.assistant

import java.io.File
import java.util.concurrent.atomic.AtomicLong

private val timelineIdCounter = AtomicLong(1)

fun nextAIAssistantTimelineItemId(): Long = timelineIdCounter.getAndIncrement()

fun observeAIAssistantTimelineItemId(id: Long) {
    while (true) {
        val current = timelineIdCounter.get()
        if (id < current) {
            return
        }
        if (timelineIdCounter.compareAndSet(current, id + 1)) {
            return
        }
    }
}

sealed class AIAssistantTimelineItem(
    open val id: Long = nextAIAssistantTimelineItemId()
)

data class AIAssistantWelcomeItem(
    val title: String,
    val body: String,
    override val id: Long = nextAIAssistantTimelineItemId()
) : AIAssistantTimelineItem(id)

data class AIAssistantUserItem(
    val prompt: String,
    override val id: Long = nextAIAssistantTimelineItemId()
) : AIAssistantTimelineItem(id)

data class AIAssistantResponseItem(
    val response: String,
    override val id: Long = nextAIAssistantTimelineItemId()
) : AIAssistantTimelineItem(id)

data class AIAssistantStreamingResponseItem(
    val header: String = "Working",
    val placeholder: String? = null,
    val response: String = "",
    val status: String? = null,
    val attachments: List<AIAssistantToolItem> = emptyList(),
    val isWorking: Boolean = true,
    val isStreaming: Boolean = true,
    override val id: Long = nextAIAssistantTimelineItemId()
) : AIAssistantTimelineItem(id)

enum class AIAssistantTone {
    NEUTRAL,
    RUNNING,
    SUCCESS,
    WARNING,
    ERROR
}

data class AIAssistantStatusItem(
    val title: String,
    val body: String? = null,
    val tone: AIAssistantTone = AIAssistantTone.NEUTRAL,
    override val id: Long = nextAIAssistantTimelineItemId()
) : AIAssistantTimelineItem(id)

data class AIAssistantHistoryDividerItem(
    val hiddenCount: Int,
    val visibleCount: Int,
    val totalCount: Int,
    override val id: Long = -1L
) : AIAssistantTimelineItem(id)

enum class AIAssistantToolStage(val label: String) {
    PLANNED("Queued"),
    RUNNING("Running"),
    STREAMING("Streaming"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Stopped")
}

data class AIAssistantToolItem(
    val title: String,
    val summary: String,
    val stage: AIAssistantToolStage = AIAssistantToolStage.PLANNED,
    val stageTrail: String = AIAssistantToolStage.PLANNED.label,
    val command: String? = null,
    val workingDirectory: String? = null,
    val outputPreview: String? = null,
    val id: Long = nextAIAssistantTimelineItemId()
) {
    val isLive: Boolean
        get() = stage == AIAssistantToolStage.RUNNING || stage == AIAssistantToolStage.STREAMING
}

data class AIAssistantDiffPreview(
    val addedCount: Int,
    val removedCount: Int,
    val addedLines: List<String>,
    val removedLines: List<String>
) {
    companion object {
        private const val MAX_PREVIEW_LINES = 8

        fun fromContents(previousContent: String?, newContent: String): AIAssistantDiffPreview {
            val newLines = newContent.lines()
            if (previousContent == null) {
                return AIAssistantDiffPreview(
                    addedCount = newLines.size,
                    removedCount = 0,
                    addedLines = newLines.take(MAX_PREVIEW_LINES),
                    removedLines = emptyList()
                )
            }

            val oldLines = previousContent.lines()
            if (oldLines == newLines) {
                return AIAssistantDiffPreview(0, 0, emptyList(), emptyList())
            }

            var prefix = 0
            val prefixLimit = minOf(oldLines.size, newLines.size)
            while (prefix < prefixLimit && oldLines[prefix] == newLines[prefix]) {
                prefix++
            }

            var oldSuffix = oldLines.lastIndex
            var newSuffix = newLines.lastIndex
            while (oldSuffix >= prefix &&
                newSuffix >= prefix &&
                oldLines[oldSuffix] == newLines[newSuffix]
            ) {
                oldSuffix--
                newSuffix--
            }

            val removedLines = if (oldSuffix >= prefix) {
                oldLines.subList(prefix, oldSuffix + 1)
            } else {
                emptyList()
            }
            val addedLines = if (newSuffix >= prefix) {
                newLines.subList(prefix, newSuffix + 1)
            } else {
                emptyList()
            }

            return AIAssistantDiffPreview(
                addedCount = addedLines.size,
                removedCount = removedLines.size,
                addedLines = addedLines.take(MAX_PREVIEW_LINES),
                removedLines = removedLines.take(MAX_PREVIEW_LINES)
            )
        }
    }
}

data class AIAssistantDiffItem(
    val filePath: String,
    val changeLabel: String,
    val preview: AIAssistantDiffPreview,
    override val id: Long = nextAIAssistantTimelineItemId()
) : AIAssistantTimelineItem(id) {
    val fileName: String
        get() = File(filePath).name
}
