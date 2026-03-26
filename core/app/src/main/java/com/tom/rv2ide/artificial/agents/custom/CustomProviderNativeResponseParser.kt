package com.tom.rv2ide.artificial.agents.custom

import com.tom.rv2ide.artificial.agents.AIAgentStreamListener
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

internal class CustomProviderNativeResponseParser(
    private val apiTypeProvider: () -> CustomProviderApiType,
    private val nextToolCallId: () -> String,
    private val normalizeJsonObjectString: (String) -> String
) {
    fun parseNativeStreamingResponse(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener
    ): NativeProviderResponse = when (apiTypeProvider()) {
        CustomProviderApiType.OPENAI_CHAT -> parseNativeOpenAIChatStream(responseBody, listener)
        CustomProviderApiType.OPENAI_RESPONSES -> parseNativeOpenAIResponsesStream(responseBody, listener)
        CustomProviderApiType.CLAUDE_MESSAGES -> parseNativeClaudeStream(responseBody, listener)
    }

    fun parseNativeJsonResponse(
        rawBody: String,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val jsonResponse = JSONObject(rawBody)
        val parsed = when (apiTypeProvider()) {
            CustomProviderApiType.OPENAI_CHAT -> parseNativeOpenAIChatJson(jsonResponse)
            CustomProviderApiType.OPENAI_RESPONSES -> parseNativeOpenAIResponsesJson(jsonResponse)
            CustomProviderApiType.CLAUDE_MESSAGES -> parseNativeClaudeJson(jsonResponse)
        }
        if (parsed.assistantText.isNotBlank()) {
            listener.onTextDelta(parsed.assistantText)
        }
        return parsed
    }

    private fun parseNativeOpenAIChatStream(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val textBuffer = StringBuilder()
        val toolCallAccumulators = linkedMapOf<Int, ToolCallStreamAccumulator>()
        collectSseEvents(responseBody) { _, payload ->
            if (payload == "[DONE]") return@collectSseEvents
            val json = payload.toJsonObjectOrNull() ?: return@collectSseEvents
            CustomProviderApiResponseSupport.extractEmbeddedStreamError(null, json)?.let {
                throw IllegalStateException(it)
            }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return@collectSseEvents
            val delta = choice.optJSONObject("delta")
            val contentDelta = CustomProviderResponseTextSupport.extractStreamingFragment(delta?.opt("content"))
            if (contentDelta.isNotEmpty()) {
                textBuffer.append(contentDelta)
                listener.onTextDelta(contentDelta)
            }
            val deltaToolCalls = delta?.optJSONArray("tool_calls")
            if (deltaToolCalls != null) {
                for (index in 0 until deltaToolCalls.length()) {
                    val toolJson = deltaToolCalls.optJSONObject(index) ?: continue
                    val toolIndex = toolJson.optInt("index", index)
                    val accumulator = toolCallAccumulators.getOrPut(toolIndex) {
                        ToolCallStreamAccumulator(id = nextToolCallId())
                    }
                    toolJson.optString("id").takeIf { it.isNotBlank() }?.let { accumulator.id = it }
                    val function = toolJson.optJSONObject("function")
                    function?.optString("name")?.takeIf { it.isNotBlank() }?.let { nameFragment ->
                        accumulator.name = if (accumulator.name.isBlank()) nameFragment else accumulator.name + nameFragment
                    }
                    function?.optString("arguments")?.takeIf { it.isNotEmpty() }?.let(accumulator.argumentsBuilder::append)
                }
            }
            choice.optJSONObject("message")?.let { message ->
                val directText = CustomProviderApiResponseSupport.extractText(message.opt("content"))
                if (textBuffer.isEmpty() && directText.isNotBlank()) {
                    textBuffer.append(directText)
                }
                val toolCalls = message.optJSONArray("tool_calls")
                if (toolCalls != null && toolCallAccumulators.isEmpty()) {
                    for (index in 0 until toolCalls.length()) {
                        val toolJson = toolCalls.optJSONObject(index) ?: continue
                        val function = toolJson.optJSONObject("function")
                        toolCallAccumulators[index] = ToolCallStreamAccumulator(
                            id = toolJson.optString("id").ifBlank { nextToolCallId() },
                            name = function?.optString("name").orEmpty(),
                            initialArgumentsJson = function?.optString("arguments").orEmpty()
                        )
                    }
                }
            }
        }
        return NativeProviderResponse(
            assistantText = textBuffer.toString().trim(),
            toolCalls = toolCallAccumulators.toSortedMap().values.mapNotNull(ToolCallStreamAccumulator::toRecordedToolCall)
        )
    }

    private fun parseNativeOpenAIResponsesStream(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val textBuffer = StringBuilder()
        val toolCallAccumulators = mutableListOf<ToolCallStreamAccumulator>()
        var completedResponse: JSONObject? = null
        var responseId: String? = null
        collectSseEvents(responseBody) { eventName, payload ->
            if (payload == "[DONE]") return@collectSseEvents
            val json = payload.toJsonObjectOrNull() ?: return@collectSseEvents
            val eventType = json.optString("type").ifBlank { eventName.orEmpty() }
            when {
                eventType.contains("error", true) ||
                    eventType.contains("response.failed", true) ||
                    eventType.contains("response.incomplete", true) -> {
                    throw IllegalStateException(
                        CustomProviderApiResponseSupport.extractEmbeddedStreamError(eventType, json)
                            ?: "Custom provider responses stream failed"
                    )
                }
                eventType.contains("response.created", true) -> {
                    responseId = json.optJSONObject("response")?.optString("id")?.takeIf { it.isNotBlank() } ?: responseId
                }
                eventType.contains("output_text.delta", true) -> {
                    val delta = json.optString("delta")
                    if (delta.isNotEmpty()) {
                        textBuffer.append(delta)
                        listener.onTextDelta(delta)
                    }
                }
                eventType.contains("output_text.done", true) -> {
                    val text = json.optString("text").ifBlank { json.optString("delta") }
                    if (textBuffer.isEmpty() && text.isNotBlank()) {
                        textBuffer.append(text)
                        listener.onTextDelta(text)
                    }
                }
                eventType.contains("function_call_arguments.delta", true) -> {
                    val callId = json.optString("call_id").trim()
                    val itemId = json.optString("item_id").trim()
                    val accumulator = resolveResponsesToolAccumulator(
                        toolCallAccumulators,
                        callId,
                        itemId,
                        callId.ifBlank { itemId }.ifBlank { "response_call_${toolCallAccumulators.size + 1}" }
                    )
                    accumulator.adoptResponsesIdentifiers(callId, itemId)
                    json.optString("name").takeIf { it.isNotBlank() }?.let { accumulator.name = it }
                    json.optString("delta").takeIf { it.isNotEmpty() }?.let(accumulator.argumentsBuilder::append)
                }
                eventType.contains("function_call_arguments.done", true) -> {
                    val callId = json.optString("call_id").trim()
                    val itemId = json.optString("item_id").trim()
                    val accumulator = resolveResponsesToolAccumulator(
                        toolCallAccumulators,
                        callId,
                        itemId,
                        callId.ifBlank { itemId }.ifBlank { "response_call_${toolCallAccumulators.size + 1}" }
                    )
                    accumulator.adoptResponsesIdentifiers(callId, itemId)
                    accumulator.name = json.optString("name").ifBlank { accumulator.name }
                    json.optString("arguments").takeIf { it.isNotBlank() }?.let { accumulator.initialArgumentsJson = it }
                }
                eventType.contains("output_item.added", true) || eventType.contains("output_item.done", true) -> {
                    val item = json.optJSONObject("item") ?: json.optJSONObject("output_item")
                    mergeResponsesOutputItem(item, textBuffer, listener, textBuffer.isEmpty(), toolCallAccumulators)
                }
                eventType.contains("response.completed", true) -> {
                    completedResponse = json.optJSONObject("response")
                    responseId = completedResponse?.optString("id")?.takeIf { it.isNotBlank() } ?: responseId
                }
            }
        }
        completedResponse?.let { response ->
            response.optString("output_text").takeIf { textBuffer.isEmpty() && it.isNotBlank() }?.let(textBuffer::append)
            response.optJSONArray("output")?.let { outputItems ->
                for (index in 0 until outputItems.length()) {
                    mergeResponsesOutputItem(
                        outputItems.optJSONObject(index),
                        textBuffer,
                        null,
                        textBuffer.isEmpty(),
                        toolCallAccumulators
                    )
                }
            }
        }
        return NativeProviderResponse(
            assistantText = textBuffer.toString().trim(),
            toolCalls = toolCallAccumulators.mapNotNull(ToolCallStreamAccumulator::toRecordedToolCall),
            responseId = responseId
        )
    }

    private fun parseNativeClaudeStream(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val textBuffer = StringBuilder()
        val contentBlocks = linkedMapOf<Int, ClaudeContentBlockAccumulator>()
        collectSseEvents(responseBody) { eventName, payload ->
            if (payload == "[DONE]") return@collectSseEvents
            val json = payload.toJsonObjectOrNull() ?: return@collectSseEvents
            val type = json.optString("type").ifBlank { eventName.orEmpty() }
            when {
                type.contains("error", true) -> {
                    throw IllegalStateException(
                        CustomProviderApiResponseSupport.extractEmbeddedStreamError(type, json)
                            ?: "Custom provider Claude stream failed"
                    )
                }
                type.contains("content_block_start", true) -> {
                    val index = json.optInt("index", contentBlocks.size)
                    val contentBlock = json.optJSONObject("content_block") ?: return@collectSseEvents
                    val accumulator = ClaudeContentBlockAccumulator(type = contentBlock.optString("type"))
                    when (accumulator.type) {
                        "text" -> {
                            val initialText = contentBlock.optString("text")
                            if (initialText.isNotEmpty()) {
                                accumulator.textBuilder.append(initialText)
                                textBuffer.append(initialText)
                                listener.onTextDelta(initialText)
                            }
                        }
                        "tool_use" -> {
                            accumulator.id = contentBlock.optString("id").ifBlank { nextToolCallId() }
                            accumulator.name = contentBlock.optString("name")
                            accumulator.initialInputJson = contentBlock.opt("input")?.takeIf { it != JSONObject.NULL }?.toString()
                        }
                    }
                    contentBlocks[index] = accumulator
                }
                type.contains("content_block_delta", true) -> {
                    val accumulator = contentBlocks[json.optInt("index", -1)] ?: return@collectSseEvents
                    val delta = json.optJSONObject("delta") ?: return@collectSseEvents
                    when {
                        delta.optString("type").contains("text", true) -> {
                            val textDelta = delta.optString("text")
                            if (textDelta.isNotEmpty()) {
                                accumulator.textBuilder.append(textDelta)
                                textBuffer.append(textDelta)
                                listener.onTextDelta(textDelta)
                            }
                        }
                        delta.optString("type").contains("input_json", true) -> {
                            delta.optString("partial_json").takeIf { it.isNotEmpty() }?.let(accumulator.inputJsonBuilder::append)
                        }
                    }
                }
                type.contains("message_stop", true) -> {
                    if (textBuffer.isEmpty()) {
                        json.optJSONObject("message")
                            ?.optJSONArray("content")
                            ?.let(CustomProviderResponseTextSupport::extractStreamingFragment)
                            ?.takeIf { it.isNotBlank() }
                            ?.let(textBuffer::append)
                    }
                }
            }
        }
        return NativeProviderResponse(
            assistantText = textBuffer.toString().trim(),
            toolCalls = contentBlocks.values.mapNotNull { block ->
                if (block.type != "tool_use") {
                    null
                } else {
                    val argumentsJson = block.inputJsonBuilder.toString()
                        .takeIf { it.isNotBlank() }
                        ?: block.initialInputJson.orEmpty().ifBlank { "{}" }
                    RecordedToolCall(
                        id = block.id.ifBlank { nextToolCallId() },
                        name = block.name,
                        argumentsJson = normalizeJsonObjectString(argumentsJson)
                    ).takeIf { it.name.isNotBlank() }
                }
            }
        )
    }

    private fun parseNativeOpenAIChatJson(jsonResponse: JSONObject): NativeProviderResponse {
        val message = jsonResponse.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return NativeProviderResponse(assistantText = "", toolCalls = emptyList())
        return NativeProviderResponse(
            assistantText = CustomProviderApiResponseSupport.extractText(message.opt("content")),
            toolCalls = buildRecordedChatToolCalls(message.optJSONArray("tool_calls"))
        )
    }

    private fun parseNativeOpenAIResponsesJson(jsonResponse: JSONObject): NativeProviderResponse {
        return NativeProviderResponse(
            assistantText = runCatching { CustomProviderResponseTextSupport.parseResponsesResponse(jsonResponse) }.getOrDefault(""),
            toolCalls = buildRecordedResponsesToolCalls(jsonResponse),
            responseId = jsonResponse.optString("id").takeIf { it.isNotBlank() }
        )
    }

    private fun parseNativeClaudeJson(jsonResponse: JSONObject): NativeProviderResponse {
        val toolCalls = mutableListOf<RecordedToolCall>()
        jsonResponse.optJSONArray("content")?.let { content ->
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") != "tool_use") continue
                toolCalls += RecordedToolCall(
                    id = block.optString("id").ifBlank { nextToolCallId() },
                    name = block.optString("name"),
                    argumentsJson = normalizeJsonObjectString(block.opt("input")?.toString().orEmpty().ifBlank { "{}" })
                )
            }
        }
        return NativeProviderResponse(
            assistantText = runCatching { CustomProviderResponseTextSupport.parseClaudeResponse(jsonResponse) }.getOrDefault(""),
            toolCalls = toolCalls
        )
    }

    private fun buildRecordedChatToolCalls(toolCalls: JSONArray?): List<RecordedToolCall> {
        if (toolCalls == null) return emptyList()
        return buildList {
            for (index in 0 until toolCalls.length()) {
                val toolJson = toolCalls.optJSONObject(index) ?: continue
                val function = toolJson.optJSONObject("function")
                val name = function?.optString("name").orEmpty()
                if (name.isBlank()) continue
                add(
                    RecordedToolCall(
                        id = toolJson.optString("id").ifBlank { nextToolCallId() },
                        name = name,
                        argumentsJson = normalizeJsonObjectString(function?.optString("arguments").orEmpty().ifBlank { "{}" })
                    )
                )
            }
        }
    }

    private fun buildRecordedResponsesToolCalls(jsonResponse: JSONObject): List<RecordedToolCall> {
        val outputItems = jsonResponse.optJSONArray("output") ?: return emptyList()
        return buildList {
            for (index in 0 until outputItems.length()) {
                val item = outputItems.optJSONObject(index) ?: continue
                if (item.optString("type") != "function_call") continue
                val name = item.optString("name")
                if (name.isBlank()) continue
                add(
                    RecordedToolCall(
                        id = item.optString("call_id").ifBlank { item.optString("id") }.ifBlank { nextToolCallId() },
                        name = name,
                        argumentsJson = normalizeJsonObjectString(item.optString("arguments").ifBlank { "{}" }),
                        responseItemId = item.optString("id").trim().ifBlank { null }
                    )
                )
            }
        }
    }

    private fun resolveResponsesToolAccumulator(
        toolCallAccumulators: MutableList<ToolCallStreamAccumulator>,
        callId: String,
        itemId: String,
        fallbackId: String
    ): ToolCallStreamAccumulator {
        return toolCallAccumulators.firstOrNull { accumulator ->
            (callId.isNotBlank() && accumulator.id == callId) ||
                (itemId.isNotBlank() && accumulator.responseItemId == itemId) ||
                (itemId.isNotBlank() && accumulator.id == itemId)
        } ?: ToolCallStreamAccumulator(id = fallbackId).also(toolCallAccumulators::add)
    }

    private fun mergeResponsesOutputItem(
        item: JSONObject?,
        textBuffer: StringBuilder,
        listener: AIAgentStreamListener?,
        allowTextFallback: Boolean,
        toolCallAccumulators: MutableList<ToolCallStreamAccumulator>
    ) {
        item ?: return
        when (item.optString("type")) {
            "message" -> if (allowTextFallback) {
                val fallbackText = CustomProviderApiResponseSupport.extractText(item.opt("content"))
                if (fallbackText.isNotBlank() && textBuffer.isEmpty()) {
                    textBuffer.append(fallbackText)
                    listener?.onTextDelta(fallbackText)
                }
            }
            "function_call" -> {
                val callId = item.optString("call_id").trim()
                val itemId = item.optString("id").trim()
                val accumulator = resolveResponsesToolAccumulator(
                    toolCallAccumulators,
                    callId,
                    itemId,
                    callId.ifBlank { itemId }.ifBlank { "response_call_${toolCallAccumulators.size + 1}" }
                )
                accumulator.adoptResponsesIdentifiers(callId, itemId)
                accumulator.name = item.optString("name").ifBlank { accumulator.name }
                item.optString("arguments").takeIf { it.isNotBlank() }?.let { accumulator.initialArgumentsJson = it }
            }
        }
    }

    private fun collectSseEvents(
        responseBody: ResponseBody,
        handler: (eventName: String?, payload: String) -> Unit
    ) {
        var currentEvent: String? = null
        val dataLines = mutableListOf<String>()
        responseBody.source().use { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> currentEvent = line.substringAfter("event:").trim()
                    line.startsWith("data:") -> dataLines += line.substringAfter("data:").trimStart()
                    line.isBlank() -> {
                        if (dataLines.isNotEmpty()) handler(currentEvent, dataLines.joinToString("\n"))
                        currentEvent = null
                        dataLines.clear()
                    }
                }
            }
        }
        if (dataLines.isNotEmpty()) handler(currentEvent, dataLines.joinToString("\n"))
    }
}

private fun ToolCallStreamAccumulator.adoptResponsesIdentifiers(callId: String, itemId: String) {
    if (callId.isNotBlank()) id = callId
    if (itemId.isNotBlank()) responseItemId = itemId
}
