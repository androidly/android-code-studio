package com.tom.rv2ide.fragments.assistant

import android.os.SystemClock
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface AIAssistantStreamingTurnHost {
    fun appendTimelineItem(item: AIAssistantTimelineItem)
    fun updateTimelineItem(
        itemId: Long,
        transformer: (AIAssistantTimelineItem) -> AIAssistantTimelineItem
    )
    fun moveTimelineItemToEnd(itemId: Long)
    fun markTimelineItemDirty(itemId: Long)
    fun timelineItemsSnapshot(): List<AIAssistantTimelineItem>
    fun publishState()
    fun publishStateThrottled(persistTimeline: Boolean = true)
    fun isExecutionActive(): Boolean
}

internal class AIAssistantStreamingTurnController(
    private val scope: CoroutineScope,
    private val host: AIAssistantStreamingTurnHost
) {
    private var activeWorkingTickerJob: Job? = null
    private var activeStreamItemId: Long? = null
    private var activeStreamStartedAtMs: Long = 0L
    private var activeProgressMessage: String? = null
    private val pendingTools = ArrayDeque<PendingToolItem>()
    private val toolOutputBuffers = mutableMapOf<Long, StringBuilder>()
    private val activeStreamResponseBuffer = StringBuilder()
    private var activeStreamResponseDirty: Boolean = false

    fun startAssistantStream(initialPlaceholder: String = DEFAULT_PLACEHOLDER) {
        val normalizedPlaceholder = initialPlaceholder.trim().ifBlank { DEFAULT_PLACEHOLDER }
        activeProgressMessage = normalizedPlaceholder
        if (activeStreamItemId != null) {
            refreshActiveAssistantStreamItem()
            host.publishState()
            return
        }

        activeStreamStartedAtMs = SystemClock.elapsedRealtime()
        activeStreamItemId = nextAIAssistantTimelineItemId()
        activeStreamResponseBuffer.setLength(0)
        activeStreamResponseDirty = false
        host.appendTimelineItem(
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
        host.publishState()
    }

    fun ensureAssistantStreamStarted() {
        startAssistantStream(activeProgressMessage ?: DEFAULT_PLACEHOLDER)
    }

    fun updateAssistantWorkingStatus(message: String) {
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
        host.publishStateThrottled(persistTimeline = false)
    }

    fun appendAssistantDelta(delta: String) {
        if (delta.isBlank()) {
            return
        }
        if (activeStreamItemId == null) {
            ensureAssistantStreamStarted()
        }
        activeStreamResponseBuffer.append(delta)
        trimAssistantResponseBuffer(activeStreamResponseBuffer)
        activeStreamResponseDirty = true
        host.publishStateThrottled(persistTimeline = false)
    }

    fun replaceAssistantResponse(fullResponse: String) {
        if (fullResponse.isBlank()) {
            return
        }
        if (activeStreamItemId == null) {
            ensureAssistantStreamStarted()
        }
        activeStreamResponseBuffer.setLength(0)
        activeStreamResponseBuffer.append(trimAssistantResponseText(fullResponse))
        activeStreamResponseDirty = true
        host.publishStateThrottled(persistTimeline = false)
    }

    fun completeAssistantStream(finalResponse: String?, fallbackText: String?) {
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
            host.appendTimelineItem(
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
            host.publishState()
            return
        }

        host.updateTimelineItem(itemId) { existing ->
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
        host.moveTimelineItemToEnd(itemId)
        activeStreamItemId = null
        activeStreamStartedAtMs = 0L
        activeStreamResponseBuffer.setLength(0)
        activeStreamResponseDirty = false
        host.publishState()
    }

    fun moveActiveStreamToEnd() {
        activeStreamItemId?.let(host::moveTimelineItemToEnd)
    }

    fun onToolCallStarted(toolCall: AIToolCall) {
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
        host.publishState()
    }

    fun onToolCallOutput(toolCall: AIToolCall, chunk: String) {
        val pendingTool = findPendingTool(toolTimelineKey(toolCall)) ?: return
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
        host.publishStateThrottled(persistTimeline = false)
    }

    fun onToolCallCompleted(result: AIToolExecutionResult) {
        val pendingTool = if (pendingTools.isEmpty()) null else pendingTools.removeFirst()
        val itemId = pendingTool?.itemId ?: nextAIAssistantTimelineItemId()
        val existingItem = findStreamToolAttachment(itemId)
        val bufferText = pendingTool?.let { toolOutputBuffers[it.itemId]?.toString()?.trim() }.orEmpty()
        val finalOutput = result.output
            .takeIf { it.isNotBlank() }
            ?.trim()
            ?.takeLast(MAX_TOOL_OUTPUT_PREVIEW_CHARS)
            ?: bufferText.takeIf { it.isNotBlank() }?.takeLast(MAX_TOOL_OUTPUT_PREVIEW_CHARS)
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
        host.publishState()
    }

    fun markPendingToolsCancelled() {
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

    fun clearCompletedToolTracking() {
        pendingTools.clear()
        toolOutputBuffers.clear()
    }

    fun resetRunTracking() {
        stopActiveWorkingTicker()
        activeStreamItemId = null
        activeStreamStartedAtMs = 0L
        activeProgressMessage = null
        activeStreamResponseBuffer.setLength(0)
        activeStreamResponseDirty = false
        clearCompletedToolTracking()
    }

    fun flushActiveAssistantResponseIfNeeded(markDirtyForPersistence: Boolean) {
        if (!activeStreamResponseDirty) {
            return
        }
        val itemId = activeStreamItemId ?: return
        val streamItem = host.timelineItemsSnapshot()
            .firstOrNull { it.id == itemId } as? AIAssistantStreamingResponseItem
            ?: return
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
        host.updateTimelineItem(itemId) { updatedItem }
        if (markDirtyForPersistence) {
            host.markTimelineItemDirty(updatedItem.id)
        }
        activeStreamResponseDirty = false
    }

    fun trimAssistantResponseText(text: String): String {
        val normalized = text.trim()
        if (normalized.length <= MAX_ASSISTANT_RESPONSE_CHARS) {
            return normalized
        }
        val retainedTailChars = (MAX_ASSISTANT_RESPONSE_CHARS - ASSISTANT_RESPONSE_TRUNCATED_NOTICE.length)
            .coerceAtLeast(4_096)
        return ASSISTANT_RESPONSE_TRUNCATED_NOTICE + normalized.takeLast(retainedTailChars).trimStart()
    }

    fun cancelledToolStageTrail(sawOutput: Boolean): String {
        return buildToolStageTrail(
            stage = AIAssistantToolStage.CANCELLED,
            sawOutput = sawOutput
        )
    }

    fun dispose() {
        stopActiveWorkingTicker()
    }

    private fun refreshActiveAssistantStreamItem() {
        val itemId = activeStreamItemId ?: return
        host.updateTimelineItem(itemId) { existing ->
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
        activeWorkingTickerJob = scope.launch {
            while (activeStreamItemId != null && host.isExecutionActive()) {
                delay(1000L)
                if (activeStreamItemId == null || !host.isExecutionActive()) {
                    break
                }
                refreshActiveAssistantStreamItem()
                host.publishStateThrottled(persistTimeline = false)
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
        return formatCompactElapsed(
            (SystemClock.elapsedRealtime() - activeStreamStartedAtMs).coerceAtLeast(0L)
        )
    }

    private fun replaceOrAppendStreamToolAttachment(item: AIAssistantToolItem) {
        ensureStreamItemForAttachment()
        val streamItemId = activeStreamItemId
            ?: host.timelineItemsSnapshot().lastOrNull { it is AIAssistantStreamingResponseItem }?.id
            ?: return
        host.updateTimelineItem(streamItemId) { existing ->
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
        host.moveTimelineItemToEnd(streamItemId)
    }

    private fun updateStreamToolAttachment(
        attachmentId: Long,
        transformer: (AIAssistantToolItem) -> AIAssistantToolItem
    ) {
        val streamItemId = findStreamItemIdForAttachment(attachmentId) ?: return
        host.updateTimelineItem(streamItemId) { existing ->
            val streamItem = existing as? AIAssistantStreamingResponseItem ?: return@updateTimelineItem existing
            val updatedAttachments = streamItem.attachments.toMutableList()
            val index = updatedAttachments.indexOfFirst { it.id == attachmentId }
            if (index < 0) {
                return@updateTimelineItem existing
            }
            updatedAttachments[index] = transformer(updatedAttachments[index])
            streamItem.copy(attachments = updatedAttachments)
        }
        host.moveTimelineItemToEnd(streamItemId)
    }

    private fun findStreamItemIdForAttachment(attachmentId: Long): Long? {
        activeStreamItemId?.let { streamItemId ->
            val activeStreamItem = host.timelineItemsSnapshot()
                .firstOrNull { it.id == streamItemId } as? AIAssistantStreamingResponseItem
            if (activeStreamItem?.attachments?.any { it.id == attachmentId } == true) {
                return streamItemId
            }
        }
        return host.timelineItemsSnapshot()
            .asReversed()
            .firstOrNull { item ->
                val streamItem = item as? AIAssistantStreamingResponseItem
                streamItem?.attachments?.any { it.id == attachmentId } == true
            }
            ?.id
    }

    private fun findStreamToolAttachment(attachmentId: Long): AIAssistantToolItem? {
        val streamItemId = findStreamItemIdForAttachment(attachmentId) ?: return null
        val streamItem = host.timelineItemsSnapshot()
            .firstOrNull { it.id == streamItemId } as? AIAssistantStreamingResponseItem
        return streamItem?.attachments?.firstOrNull { it.id == attachmentId }
    }

    private fun ensureStreamItemForAttachment() {
        if (activeStreamItemId == null) {
            startAssistantStream(activeProgressMessage ?: DEFAULT_PLACEHOLDER)
        }
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

    private data class PendingToolItem(
        val key: String,
        val itemId: Long,
        val startedAtMs: Long,
        var sawOutput: Boolean = false,
        var outputChunks: Int = 0
    )

    private companion object {
        private const val DEFAULT_PLACEHOLDER = "Preparing assistant run"
        private const val MAX_VISIBLE_STREAM_RESPONSE_CHARS = 16_384
        private const val MAX_ASSISTANT_RESPONSE_CHARS = 96_000
        private const val MAX_TOOL_OUTPUT_PREVIEW_CHARS = 1400
        private const val ASSISTANT_RESPONSE_TRUNCATED_NOTICE =
            "[Earlier assistant output truncated to keep the session stable]\n\n"
    }
}
