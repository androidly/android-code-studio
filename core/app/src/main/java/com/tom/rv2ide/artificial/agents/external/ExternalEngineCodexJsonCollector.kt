package com.tom.rv2ide.artificial.agents.external

import com.tom.rv2ide.artificial.agents.AIAgentStreamListener
import com.tom.rv2ide.artificial.tools.AIToolCall
import java.io.File
import org.json.JSONObject

internal class ExternalEngineCodexJsonCollector(
    private val sessionFile: File,
    private val workingDirectory: File,
    private val listener: AIAgentStreamListener,
    private val readStoredThreadId: (File) -> String?,
    private val writeStoredThreadId: (File, String) -> Unit
) {
    private val assistantMessages = mutableListOf<String>()
    private val trackedItems = mutableMapOf<String, TrackedCodexItem>()
    private var terminalErrorMessage: String? = null
    private var syntheticItemCounter = 0L

    fun consume(line: String) {
        val normalizedLine = line.trim()
        if (normalizedLine.isBlank()) {
            return
        }
        val event = runCatching { JSONObject(normalizedLine) }.getOrNull() ?: return
        when (event.optString("type").trim()) {
            "thread.started" -> {
                val threadId = event.optString("thread_id").trim()
                if (threadId.isNotBlank()) {
                    writeStoredThreadId(sessionFile, threadId)
                }
            }
            "turn.started" -> resetTurnState()
            "item.started" -> handleItemStarted(event.optJSONObject("item"))
            "item.updated" -> handleItemUpdated(event.optJSONObject("item"))
            "item.completed" -> handleItemCompleted(event.optJSONObject("item"))
            "turn.failed" -> {
                terminalErrorMessage = event.optJSONObject("error")
                    ?.optString("message")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: terminalErrorMessage
            }
            "error" -> {
                terminalErrorMessage = event.optString("message")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: terminalErrorMessage
            }
        }
    }

    fun finish(
        capturedOutput: String,
        exitCode: Int
    ): String {
        val response = assistantMessages.joinToString(separator = "\n\n").trim()
        if (response.isNotBlank()) {
            listener.onCompleted(response)
            return response
        }

        terminalErrorMessage?.takeIf { it.isNotBlank() }?.let { errorMessage ->
            throw ExternalEngineExecutionException(errorMessage)
        }

        val fallbackOutput = capturedOutput
            .lineSequence()
            .map(String::trimEnd)
            .filter { it.isNotBlank() && !it.trimStart().startsWith("{") }
            .joinToString("\n")
            .trim()
        return when {
            fallbackOutput.isNotBlank() && exitCode == 0 -> fallbackOutput
            fallbackOutput.isNotBlank() ->
                throw ExternalEngineExecutionException(
                    "$fallbackOutput\n\n[external-engine] Process exited with code $exitCode."
                )
            else ->
                throw ExternalEngineExecutionException(
                    "[external-engine] Process exited with code $exitCode."
                )
        }
    }

    private fun resetTurnState() {
        assistantMessages.clear()
        trackedItems.clear()
    }

    private fun handleItemStarted(item: JSONObject?) {
        val resolvedItem = item ?: return
        val itemType = ExternalEngineCodexItemMapper.resolveItemType(resolvedItem)
        if (ExternalEngineCodexItemMapper.isAssistantTextItem(itemType)) {
            return
        }
        val trackedItem = ensureTrackedItem(resolvedItem) ?: return
        emitSnapshotOutputIfChanged(trackedItem, resolvedItem)
    }

    private fun handleItemUpdated(item: JSONObject?) {
        val resolvedItem = item ?: return
        val itemType = ExternalEngineCodexItemMapper.resolveItemType(resolvedItem)
        if (ExternalEngineCodexItemMapper.isAssistantTextItem(itemType)) {
            return
        }
        val trackedItem = ensureTrackedItem(resolvedItem) ?: return
        emitSnapshotOutputIfChanged(trackedItem, resolvedItem)
    }

    private fun handleItemCompleted(item: JSONObject?) {
        val resolvedItem = item ?: return
        val itemType = ExternalEngineCodexItemMapper.resolveItemType(resolvedItem)
        if (ExternalEngineCodexItemMapper.isAssistantTextItem(itemType)) {
            emitAssistantMessage(resolvedItem)
            return
        }
        val trackedItem = trackedItems.remove(resolveItemId(resolvedItem))
        val toolCall = trackedItem?.toolCall
            ?: ExternalEngineCodexItemMapper.buildToolCall(
                item = resolvedItem,
                itemId = resolveItemId(resolvedItem),
                workingDirectory = workingDirectory
            ).also(listener::onToolCallStarted)
        emitSnapshotOutputIfChanged(
            trackedItem ?: TrackedCodexItem(
                itemId = resolveItemId(resolvedItem),
                toolCall = toolCall
            ),
            resolvedItem
        )
        listener.onToolCallCompleted(
            ExternalEngineCodexItemMapper.buildResult(
                item = resolvedItem,
                toolCall = toolCall,
                workingDirectory = workingDirectory
            )
        )
    }

    private fun emitAssistantMessage(item: JSONObject) {
        val message = ExternalEngineCodexItemMapper.extractAssistantText(item).trim()
        if (message.isBlank()) {
            return
        }
        val delta = if (assistantMessages.isEmpty()) {
            message
        } else {
            "\n\n$message"
        }
        assistantMessages += message
        listener.onTextDelta(delta)
    }

    private fun ensureTrackedItem(item: JSONObject): TrackedCodexItem? {
        val itemId = resolveItemId(item)
        trackedItems[itemId]?.let { return it }
        val toolCall = ExternalEngineCodexItemMapper.buildToolCall(
            item = item,
            itemId = itemId,
            workingDirectory = workingDirectory
        )
        return TrackedCodexItem(
            itemId = itemId,
            toolCall = toolCall
        ).also { trackedItem ->
            trackedItems[itemId] = trackedItem
            listener.onToolCallStarted(toolCall)
        }
    }

    private fun emitSnapshotOutputIfChanged(
        trackedItem: TrackedCodexItem,
        item: JSONObject
    ) {
        val snapshot = ExternalEngineCodexItemMapper.buildSnapshotOutput(item).trim()
        if (snapshot.isBlank() || snapshot == trackedItem.lastSnapshotOutput) {
            return
        }
        trackedItem.lastSnapshotOutput = snapshot
        listener.onToolCallOutput(trackedItem.toolCall, snapshot)
    }

    private fun resolveItemId(item: JSONObject): String {
        return item.optString("id")
            .trim()
            .ifBlank {
                val type = ExternalEngineCodexItemMapper.resolveItemType(item)
                syntheticItemCounter += 1
                "$type-$syntheticItemCounter"
            }
    }

    private data class TrackedCodexItem(
        val itemId: String,
        val toolCall: AIToolCall,
        var lastSnapshotOutput: String? = null
    )
}
