package com.tom.rv2ide.artificial.agents.custom

import com.tom.rv2ide.artificial.agents.NativeToolTurnResponse
import com.tom.rv2ide.artificial.tools.AIToolCall
import org.json.JSONArray
import org.json.JSONObject

internal data class CustomConversationMessage(
    val role: String,
    val content: String
)

internal sealed interface CustomProviderTurn {
    data class User(val text: String) : CustomProviderTurn
    data class Assistant(
        val text: String,
        val toolCalls: List<RecordedToolCall> = emptyList()
    ) : CustomProviderTurn
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val output: String,
        val isError: Boolean
    ) : CustomProviderTurn
}

internal fun CustomProviderTurn.incrementalKey(): String {
    return when (this) {
        is CustomProviderTurn.User -> "user:${text.length}:${text.hashCode()}"
        is CustomProviderTurn.Assistant -> buildString {
            append("assistant:")
            append(text.length)
            append(':')
            append(text.hashCode())
            append(':')
            append(
                toolCalls.joinToString(separator = "|") { toolCall ->
                    buildString {
                        append(toolCall.id)
                        append(':')
                        append(toolCall.responseItemId.orEmpty())
                        append(':')
                        append(toolCall.name)
                        append(':')
                        append(toolCall.argumentsJson.length)
                        append(':')
                        append(toolCall.argumentsJson.hashCode())
                    }
                }
            )
        }
        is CustomProviderTurn.ToolResult ->
            "tool:$toolCallId:$toolName:$isError:${output.length}:${output.hashCode()}"
    }
}

internal fun CustomProviderTurn.toJson(): JSONObject {
    return when (this) {
        is CustomProviderTurn.User -> JSONObject().apply {
            put("type", "user")
            put("text", text)
        }

        is CustomProviderTurn.Assistant -> JSONObject().apply {
            put("type", "assistant")
            put("text", text)
            put(
                "toolCalls",
                JSONArray().apply {
                    toolCalls.forEach { toolCall ->
                        put(toolCall.toJson())
                    }
                }
            )
        }

        is CustomProviderTurn.ToolResult -> JSONObject().apply {
            put("type", "tool_result")
            put("toolCallId", toolCallId)
            put("toolName", toolName)
            put("output", output)
            put("isError", isError)
        }
    }
}

internal fun JSONObject.toCustomProviderTurnOrNull(): CustomProviderTurn? {
    return when (optString("type")) {
        "user" -> optString("text")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(CustomProviderTurn::User)

        "assistant" -> {
            val text = optString("text")
            val toolCalls = optJSONArray("toolCalls")
                ?.let { items ->
                    buildList {
                        for (index in 0 until items.length()) {
                            items.optJSONObject(index)
                                ?.toRecordedToolCallOrNull()
                                ?.let(::add)
                        }
                    }
                }
                .orEmpty()
            if (text.isBlank() && toolCalls.isEmpty()) {
                null
            } else {
                CustomProviderTurn.Assistant(
                    text = text,
                    toolCalls = toolCalls
                )
            }
        }

        "tool_result" -> {
            val toolCallId = optString("toolCallId").trim()
            val toolName = optString("toolName").trim()
            val output = optString("output")
            if (toolCallId.isBlank() || toolName.isBlank()) {
                null
            } else {
                CustomProviderTurn.ToolResult(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    output = output,
                    isError = optBoolean("isError", false)
                )
            }
        }

        else -> null
    }
}

internal data class RecordedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val responseItemId: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("argumentsJson", argumentsJson)
            put("responseItemId", responseItemId.orEmpty())
        }
    }

    fun toExternalToolCall(): AIToolCall {
        val arguments = parseArgumentsFromJson(argumentsJson)
        return AIToolCall(
            callId = id,
            name = name,
            arguments = arguments,
            rawBlock = buildRawToolBlock(name, arguments, argumentsJson),
            rawArgumentsJson = argumentsJson
        )
    }
}

internal fun JSONObject.toRecordedToolCallOrNull(): RecordedToolCall? {
    val id = optString("id").trim()
    val name = optString("name").trim()
    if (id.isBlank() || name.isBlank()) {
        return null
    }
    return RecordedToolCall(
        id = id,
        name = name,
        argumentsJson = normalizeJsonObjectStringStatic(optString("argumentsJson")),
        responseItemId = optString("responseItemId").trim().ifBlank { null }
    )
}

internal data class NativeProviderResponse(
    val assistantText: String,
    val toolCalls: List<RecordedToolCall>,
    val responseId: String? = null
) {
    fun toExternalResponse(): NativeToolTurnResponse {
        return NativeToolTurnResponse(
            assistantText = assistantText,
            toolCalls = toolCalls.map(RecordedToolCall::toExternalToolCall)
        )
    }
}

internal data class ToolCallStreamAccumulator(
    var id: String,
    var name: String = "",
    var initialArgumentsJson: String = "",
    val argumentsBuilder: StringBuilder = StringBuilder(),
    var responseItemId: String? = null
) {
    fun toRecordedToolCall(): RecordedToolCall? {
        if (name.isBlank()) {
            return null
        }
        val argumentsJson = argumentsBuilder.toString()
            .takeIf { it.isNotBlank() }
            ?: initialArgumentsJson.ifBlank { "{}" }
        return RecordedToolCall(
            id = id,
            name = name,
            argumentsJson = normalizeJsonObjectStringStatic(argumentsJson),
            responseItemId = responseItemId?.trim()?.ifBlank { null }
        )
    }
}

internal data class ClaudeContentBlockAccumulator(
    val type: String,
    var id: String = "",
    var name: String = "",
    var initialInputJson: String? = null,
    val textBuilder: StringBuilder = StringBuilder(),
    val inputJsonBuilder: StringBuilder = StringBuilder()
)

internal data class ProviderRequestMode(
    val compatibilityFallback: Boolean = false
) {
    companion object {
        val Default = ProviderRequestMode(compatibilityFallback = false)
        val CompatibilityFallback = ProviderRequestMode(compatibilityFallback = true)
    }
}

internal fun String.toJsonObjectOrNull(): JSONObject? {
    return try {
        JSONObject(this)
    } catch (_: Exception) {
        null
    }
}

internal fun normalizeJsonObjectStringStatic(rawJson: String): String {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) {
        return "{}"
    }
    return trimmed.toJsonObjectOrNull()?.toString() ?: trimmed
}

private fun parseArgumentsFromJson(rawArgumentsJson: String): Map<String, String> {
    val json = normalizeJsonObjectStringStatic(rawArgumentsJson).toJsonObjectOrNull() ?: return emptyMap()
    val parsed = linkedMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        parsed[key.lowercase()] = stringifyToolArgument(json.opt(key))
    }
    return parsed
}

private fun stringifyToolArgument(value: Any?): String {
    return when (value) {
        null,
        JSONObject.NULL -> ""
        is JSONObject,
        is JSONArray -> value.toString()
        else -> value.toString()
    }
}

private fun buildRawToolBlock(
    name: String,
    arguments: Map<String, String>,
    rawArgumentsJson: String
): String {
    return buildString {
        appendLine("TOOL_CALL: $name")
        if (arguments.isEmpty()) {
            append(rawArgumentsJson)
        } else {
            arguments.forEach { (key, value) ->
                if (value.contains('\n')) {
                    appendLine("${key.uppercase()}:")
                    appendLine(value)
                } else {
                    appendLine("${key.uppercase()}: $value")
                }
            }
        }
    }.trim()
}
