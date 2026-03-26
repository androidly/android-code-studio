package com.tom.rv2ide.artificial.agents.external

import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object ExternalEngineCodexItemMapper {
    private val assistantTextItemTypes = setOf("agent_message", "message")

    fun isAssistantTextItem(itemType: String): Boolean {
        return itemType in assistantTextItemTypes
    }

    fun extractAssistantText(item: JSONObject): String {
        val directText = item.optString("text").trim()
        if (directText.isNotBlank()) {
            return directText
        }
        return listOf("content", "summary")
            .firstNotNullOfOrNull { field ->
                extractTextArray(item.optJSONArray(field)).takeIf { it.isNotBlank() }
            }
            .orEmpty()
    }

    fun buildToolCall(
        item: JSONObject,
        itemId: String,
        workingDirectory: File
    ): AIToolCall {
        val type = resolveItemType(item)
        val arguments = linkedMapOf<String, String>()
        val fieldLines = mutableListOf<Pair<String, String>>()

        fun addField(key: String, value: String?) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) {
                return
            }
            arguments[key.lowercase()] = normalized
            fieldLines += key to normalized
        }

        addField("output_mode", "replace")
        when (type) {
            "command_execution" -> {
                addField("command", item.optString("command"))
                addField("workdir", workingDirectory.absolutePath)
            }
            "file_change" -> {
                addField("summary", buildFileChangeSummary(item))
            }
            "web_search" -> {
                addField("query", item.optString("query"))
                addField("summary", buildWebSearchSummary(item))
            }
            "mcp_tool_call", "mcp_tool" -> {
                addField("server", item.optString("server"))
                addField("tool", item.optString("tool"))
                addField("summary", buildMcpSummary(item))
                addField("arguments_json", compactJson(item.opt("arguments")))
            }
            "todo_list" -> {
                addField("summary", buildTodoSummary(item.optJSONArray("items")))
            }
            "reasoning" -> {
                val text = extractAssistantText(item)
                addField("text", text)
                addField("summary", summariseText(text))
            }
            "function_call" -> {
                addField("name", item.optString("name"))
                addField("summary", item.optString("name"))
                addField("arguments_json", item.optString("arguments"))
            }
            "function_call_output" -> {
                addField("summary", "Function output")
                addField("output", item.optString("output"))
            }
            "error" -> {
                addField("message", item.optString("message"))
                addField("summary", summariseText(item.optString("message")))
            }
            else -> {
                addField("summary", buildGenericSummary(type, item))
            }
        }

        return AIToolCall(
            callId = itemId,
            name = type,
            arguments = arguments,
            rawBlock = buildRawBlock(type, fieldLines)
        )
    }

    fun buildSnapshotOutput(item: JSONObject): String {
        return when (resolveItemType(item)) {
            "command_execution" -> item.optString("aggregated_output").trim()
            "file_change" -> buildFileChangeOutput(item)
            "mcp_tool_call", "mcp_tool" -> buildMcpResultOutput(item)
            "todo_list" -> formatTodoItems(item.optJSONArray("items"))
            "function_call", "function_call_output" -> item.optString("output").trim()
            "error" -> item.optString("message").trim()
            else -> ""
        }
    }

    fun buildResult(
        item: JSONObject,
        toolCall: AIToolCall,
        workingDirectory: File
    ): AIToolExecutionResult {
        return when (toolCall.name.lowercase()) {
            "command_execution" -> buildCommandResult(item, toolCall, workingDirectory)
            "file_change" -> buildFileChangeResult(item)
            "web_search" -> buildWebSearchResult(item)
            "mcp_tool_call", "mcp_tool" -> buildMcpToolCallResult(item, toolCall)
            "todo_list" -> buildTodoListResult(item)
            "reasoning" -> buildReasoningResult(item)
            "function_call" -> buildFunctionCallResult(item, toolCall)
            "function_call_output" -> buildFunctionOutputResult(item)
            "error" -> buildItemErrorResult(item)
            else -> buildGenericResult(item, toolCall)
        }
    }

    fun resolveItemType(item: JSONObject): String {
        return item.optString("type")
            .trim()
            .ifBlank { "unknown_item" }
    }

    private fun buildCommandResult(
        item: JSONObject,
        toolCall: AIToolCall,
        workingDirectory: File
    ): AIToolExecutionResult {
        val status = item.optString("status").trim()
        val output = item.optString("aggregated_output").trim()
        val exitCode = item.optIntOrNull("exit_code")
        val success = status == "completed" && (exitCode == null || exitCode == 0)
        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = success,
            summary = when {
                !success && status.isNotBlank() -> status.replace('_', ' ')
                success -> "completed"
                else -> "finished"
            },
            output = output,
            executedCommand = toolCall.argument("command"),
            workingDirectory = toolCall.argument("workdir") ?: workingDirectory.absolutePath,
            exitCode = exitCode,
            blockedBySafetyPolicy = output.contains("[Command blocked by safety policy]")
        )
    }

    private fun buildFileChangeResult(item: JSONObject): AIToolExecutionResult {
        val changes = item.optJSONArray("changes")
        val changeCount = changes?.length() ?: 0
        val status = item.optString("status").trim()
        val success = status == "completed"
        return AIToolExecutionResult(
            toolName = "file_change",
            success = success,
            summary = when {
                status.isNotBlank() -> status.replace('_', ' ')
                changeCount <= 0 && success -> "completed"
                changeCount <= 0 -> "failed"
                success -> "$changeCount changes"
                else -> "failed"
            },
            output = buildFileChangeOutput(item)
        )
    }

    private fun buildWebSearchResult(item: JSONObject): AIToolExecutionResult {
        val summary = buildWebSearchSummary(item)
        return AIToolExecutionResult(
            toolName = "web_search",
            success = true,
            summary = summary.ifBlank { "searched the web" },
            output = ""
        )
    }

    private fun buildMcpToolCallResult(
        item: JSONObject,
        toolCall: AIToolCall
    ): AIToolExecutionResult {
        val status = item.optString("status").trim()
        val errorMessage = item.optJSONObject("error")
            ?.optString("message")
            ?.trim()
            .orEmpty()
        val success = status == "completed" && errorMessage.isBlank()
        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = success,
            summary = toolCall.argument("summary").orEmpty()
                .ifBlank { if (success) "completed" else "failed" },
            output = buildMcpResultOutput(item)
        )
    }

    private fun buildTodoListResult(item: JSONObject): AIToolExecutionResult {
        val todoItems = item.optJSONArray("items")
        return AIToolExecutionResult(
            toolName = "todo_list",
            success = true,
            summary = buildTodoSummary(todoItems).ifBlank { "Plan updated" },
            output = formatTodoItems(todoItems)
        )
    }

    private fun buildReasoningResult(item: JSONObject): AIToolExecutionResult {
        val text = extractAssistantText(item)
        return AIToolExecutionResult(
            toolName = "reasoning",
            success = true,
            summary = summariseText(text).ifBlank { "Reasoning" },
            output = ""
        )
    }

    private fun buildFunctionCallResult(
        item: JSONObject,
        toolCall: AIToolCall
    ): AIToolExecutionResult {
        val status = item.optString("status").trim()
        val output = item.optString("output").trim()
        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = status != "failed",
            summary = toolCall.argument("name")
                ?: toolCall.argument("summary")
                ?: if (status == "failed") "failed" else "completed",
            output = output
        )
    }

    private fun buildFunctionOutputResult(item: JSONObject): AIToolExecutionResult {
        return AIToolExecutionResult(
            toolName = "function_call_output",
            success = true,
            summary = "Function output",
            output = item.optString("output").trim()
        )
    }

    private fun buildItemErrorResult(item: JSONObject): AIToolExecutionResult {
        val message = item.optString("message").trim()
        return AIToolExecutionResult(
            toolName = "error",
            success = false,
            summary = summariseText(message).ifBlank { "failed" },
            output = message
        )
    }

    private fun buildGenericResult(
        item: JSONObject,
        toolCall: AIToolCall
    ): AIToolExecutionResult {
        val status = item.optString("status").trim()
        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = status != "failed" && resolveItemType(item) != "error",
            summary = buildGenericSummary(toolCall.name, item),
            output = buildGenericOutput(item)
        )
    }

    private fun buildFileChangeOutput(item: JSONObject): String {
        val changes = item.optJSONArray("changes")
        return buildString {
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
    }

    private fun buildMcpResultOutput(item: JSONObject): String {
        val errorMessage = item.optJSONObject("error")
            ?.optString("message")
            ?.trim()
            .orEmpty()
        if (errorMessage.isNotBlank()) {
            return errorMessage
        }
        return compactJson(item.opt("result"))
    }

    private fun buildWebSearchSummary(item: JSONObject): String {
        return item.optString("query")
            .trim()
            .ifBlank { "Searching the web" }
    }

    private fun buildFileChangeSummary(item: JSONObject): String {
        val changes = item.optJSONArray("changes")
        val changeCount = changes?.length() ?: 0
        return when {
            changeCount <= 0 -> item.optString("status").trim().ifBlank { "File changes" }
            changeCount == 1 -> "1 file changed"
            else -> "$changeCount files changed"
        }
    }

    private fun buildMcpSummary(item: JSONObject): String {
        return listOfNotNull(
            item.optString("server").trim().takeIf { it.isNotBlank() },
            item.optString("tool").trim().takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }

    private fun buildTodoSummary(items: JSONArray?): String {
        if (items == null || items.length() <= 0) {
            return "Plan updated"
        }
        val total = items.length()
        var completed = 0
        var firstPending: String? = null
        for (index in 0 until total) {
            val todo = items.optJSONObject(index) ?: continue
            if (todo.optBoolean("completed")) {
                completed += 1
            } else if (firstPending == null) {
                firstPending = todo.optString("text").trim().takeIf { it.isNotBlank() }
            }
        }
        return listOfNotNull(
            "Plan $completed/$total",
            firstPending?.let(::summariseText)
        ).joinToString(" · ")
    }

    private fun formatTodoItems(items: JSONArray?): String {
        if (items == null || items.length() <= 0) {
            return ""
        }
        return buildString {
            for (index in 0 until items.length()) {
                val todo = items.optJSONObject(index) ?: continue
                val text = todo.optString("text").trim()
                if (text.isBlank()) {
                    continue
                }
                append(if (todo.optBoolean("completed")) "[x] " else "[ ] ")
                append(text)
                appendLine()
            }
        }.trim()
    }

    private fun buildGenericSummary(
        itemType: String,
        item: JSONObject
    ): String {
        return when {
            item.optString("summary").trim().isNotBlank() -> item.optString("summary").trim()
            item.optString("name").trim().isNotBlank() -> item.optString("name").trim()
            item.optString("tool").trim().isNotBlank() -> item.optString("tool").trim()
            item.optString("query").trim().isNotBlank() -> item.optString("query").trim()
            item.optString("message").trim().isNotBlank() -> summariseText(item.optString("message"))
            else -> itemType.replace('_', ' ')
        }
    }

    private fun buildGenericOutput(item: JSONObject): String {
        return listOf(
            item.optString("output").trim(),
            item.optString("text").trim(),
            item.optString("query").trim(),
            compactJson(item.opt("arguments"))
        ).firstOrNull { it.isNotBlank() }
            ?: compactJson(item)
    }

    private fun extractTextArray(array: JSONArray?): String {
        if (array == null || array.length() <= 0) {
            return ""
        }
        return buildString {
            for (index in 0 until array.length()) {
                val element = array.optJSONObject(index) ?: continue
                val text = element.optString("text").trim()
                if (text.isBlank()) {
                    continue
                }
                if (isNotEmpty()) {
                    append('\n')
                }
                append(text)
            }
        }.trim()
    }

    private fun buildRawBlock(
        toolName: String,
        fieldLines: List<Pair<String, String>>
    ): String {
        return buildString {
            appendLine("TOOL_CALL: $toolName")
            fieldLines.forEachIndexed { index, (key, value) ->
                append(key)
                append(": ")
                append(value)
                if (index != fieldLines.lastIndex) {
                    appendLine()
                }
            }
        }.trim()
    }

    private fun compactJson(
        value: Any?,
        maxChars: Int = 1200
    ): String {
        val normalized = when (value) {
            null,
            JSONObject.NULL -> ""
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> value.toString()
        }.trim()
        if (normalized.length <= maxChars) {
            return normalized
        }
        return normalized.take(maxChars).trimEnd() + "…"
    }

    private fun summariseText(
        text: String,
        maxChars: Int = 140
    ): String {
        val normalized = text.replace('\n', ' ').trim()
        if (normalized.length <= maxChars) {
            return normalized
        }
        return normalized.take(maxChars).trimEnd() + "…"
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return if (has(key) && !isNull(key)) {
            optInt(key)
        } else {
            null
        }
    }
}
