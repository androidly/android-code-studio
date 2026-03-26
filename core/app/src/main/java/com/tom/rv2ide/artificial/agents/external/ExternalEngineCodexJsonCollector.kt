package com.tom.rv2ide.artificial.agents.external

import com.tom.rv2ide.artificial.agents.AIAgentStreamListener
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
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
    private val activeToolCalls = mutableMapOf<String, AIToolCall>()
    private var terminalErrorMessage: String? = null

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
            "item.started" -> handleItemStarted(event.optJSONObject("item"))
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

    private fun handleItemStarted(item: JSONObject?) {
        val resolvedItem = item ?: return
        if (resolvedItem.optString("type").trim() != "command_execution") {
            return
        }
        val itemId = resolvedItem.optString("id").trim().ifBlank { return }
        val command = resolvedItem.optString("command").trim().ifBlank { return }
        val toolCall = buildSyntheticCommandToolCall(itemId, command, workingDirectory)
        activeToolCalls[itemId] = toolCall
        listener.onToolCallStarted(toolCall)
    }

    private fun handleItemCompleted(item: JSONObject?) {
        val resolvedItem = item ?: return
        when (resolvedItem.optString("type").trim()) {
            "agent_message" -> {
                val message = resolvedItem.optString("text").trim()
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
            "command_execution" -> {
                val itemId = resolvedItem.optString("id").trim()
                val toolCall = activeToolCalls.remove(itemId)
                    ?: buildSyntheticCommandToolCall(
                        itemId = itemId.ifBlank { "command" },
                        command = resolvedItem.optString("command").trim(),
                        workingDirectory = workingDirectory
                    )
                resolvedItem.optString("aggregated_output")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { listener.onToolCallOutput(toolCall, it) }
                listener.onToolCallCompleted(
                    buildSyntheticCommandResult(
                        item = resolvedItem,
                        toolCall = toolCall,
                        workingDirectory = workingDirectory
                    )
                )
            }
            "file_change" -> {
                listener.onToolCallCompleted(buildSyntheticFileChangeResult(resolvedItem))
            }
            "error" -> {
                terminalErrorMessage = resolvedItem.optString("message")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: terminalErrorMessage
            }
        }
    }

    private fun buildSyntheticCommandToolCall(
        itemId: String,
        command: String,
        workingDirectory: File
    ): AIToolCall {
        val normalizedCommand = command.trim()
        return AIToolCall(
            callId = itemId,
            name = "run_terminal_command",
            arguments = mapOf(
                "command" to normalizedCommand,
                "workdir" to workingDirectory.absolutePath
            ),
            rawBlock = buildString {
                appendLine("TOOL_CALL: run_terminal_command")
                appendLine("command: $normalizedCommand")
                append("workdir: ${workingDirectory.absolutePath}")
            }
        )
    }

    private fun buildSyntheticCommandResult(
        item: JSONObject,
        toolCall: AIToolCall,
        workingDirectory: File
    ): AIToolExecutionResult {
        val status = item.optString("status").trim()
        val output = item.optString("aggregated_output").trim()
        val exitCode = item.optIntOrNull("exit_code")
        val success = status == "completed" && (exitCode == null || exitCode == 0)
        val summary = buildString {
            append(
                when {
                    success -> "Command completed"
                    status.isNotBlank() -> "Command ${status.replace('_', ' ')}"
                    else -> "Command finished"
                }
            )
            exitCode?.let { append(" (exit=$it)") }
        }
        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = success,
            summary = summary,
            output = output,
            executedCommand = toolCall.argument("command"),
            workingDirectory = toolCall.argument("workdir") ?: workingDirectory.absolutePath,
            exitCode = exitCode
        )
    }

    private fun buildSyntheticFileChangeResult(item: JSONObject): AIToolExecutionResult {
        val changes = item.optJSONArray("changes")
        val changeCount = changes?.length() ?: 0
        val status = item.optString("status").trim()
        val success = status == "completed"
        val output = buildString {
            if (changes == null) {
                return@buildString
            }
            for (index in 0 until changes.length()) {
                val change = changes.optJSONObject(index) ?: continue
                val kind = change.optString("kind").trim().ifBlank { "update" }
                val path = change.optString("path").trim()
                append(kind.replace('_', ' '))
                if (path.isNotBlank()) {
                    append(": ")
                    append(path)
                }
                appendLine()
            }
        }.trim()
        val summary = when {
            changeCount <= 0 && success -> "Applied file changes"
            changeCount <= 0 -> "File changes failed"
            success -> "Applied $changeCount file changes"
            else -> "Failed to apply $changeCount file changes"
        }
        return AIToolExecutionResult(
            toolName = "apply_patch",
            success = success,
            summary = summary,
            output = output
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return if (has(key) && !isNull(key)) {
            optInt(key)
        } else {
            null
        }
    }
}
