package com.tom.rv2ide.artificial.agents.custom

import org.json.JSONArray
import org.json.JSONObject

internal object CustomProviderRequestPayloadSupport {
    fun buildNativeChatRequestPayload(
        selectedModel: String,
        systemPrompt: String,
        conversation: List<CustomProviderTurn>,
        defaultMaxGenerationTokens: Int,
        requestMode: ProviderRequestMode,
        tools: JSONArray?
    ): JSONObject {
        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )
            conversation.forEach { turn ->
                when (turn) {
                    is CustomProviderTurn.User -> {
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", turn.text)
                            }
                        )
                    }

                    is CustomProviderTurn.Assistant -> {
                        put(
                            JSONObject().apply {
                                put("role", "assistant")
                                if (turn.text.isNotBlank()) {
                                    put("content", turn.text)
                                }
                                if (turn.toolCalls.isNotEmpty()) {
                                    put("tool_calls", buildOpenAIChatToolCalls(turn.toolCalls))
                                }
                            }
                        )
                    }

                    is CustomProviderTurn.ToolResult -> {
                        put(
                            JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", turn.toolCallId)
                                put("content", turn.output)
                            }
                        )
                    }
                }
            }
        }

        return JSONObject().apply {
            put("model", selectedModel)
            put("messages", messages)
            put("stream", true)
            if (!requestMode.compatibilityFallback) {
                put("max_tokens", defaultMaxGenerationTokens)
            }
            tools?.let {
                put("tools", it)
                put("tool_choice", "auto")
            }
        }
    }

    fun buildNativeResponsesRequestPayload(
        selectedModel: String,
        systemPrompt: String,
        turns: List<CustomProviderTurn>,
        validateToolCallPairing: Boolean,
        defaultMaxGenerationTokens: Int,
        requestMode: ProviderRequestMode,
        previousResponseId: String?,
        tools: JSONArray?
    ): JSONObject {
        val input = buildNativeResponsesInput(
            turns = turns,
            validateToolCallPairing = validateToolCallPairing
        )

        return JSONObject().apply {
            put("model", selectedModel)
            put("instructions", systemPrompt)
            put("input", input)
            put("stream", true)
            if (!requestMode.compatibilityFallback) {
                put("max_output_tokens", defaultMaxGenerationTokens)
            }
            previousResponseId?.let { put("previous_response_id", it) }
            tools?.let {
                put("tools", it)
                put("tool_choice", "auto")
            }
        }
    }

    fun buildNativeClaudeRequestPayload(
        selectedModel: String,
        systemPrompt: String,
        conversation: List<CustomProviderTurn>,
        defaultMaxGenerationTokens: Int,
        tools: JSONArray?
    ): JSONObject {
        val messages = JSONArray().apply {
            conversation.forEach { turn ->
                when (turn) {
                    is CustomProviderTurn.User -> {
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject().apply {
                                            put("type", "text")
                                            put("text", turn.text)
                                        }
                                    )
                                )
                            }
                        )
                    }

                    is CustomProviderTurn.Assistant -> {
                        val content = JSONArray()
                        if (turn.text.isNotBlank()) {
                            content.put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", turn.text)
                                }
                            )
                        }
                        turn.toolCalls.forEach { toolCall ->
                            content.put(
                                JSONObject().apply {
                                    put("type", "tool_use")
                                    put("id", toolCall.id)
                                    put("name", toolCall.name)
                                    put(
                                        "input",
                                        toolCall.argumentsJson.toJsonObjectOrNull() ?: JSONObject()
                                    )
                                }
                            )
                        }
                        put(
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", content)
                            }
                        )
                    }

                    is CustomProviderTurn.ToolResult -> {
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject().apply {
                                            put("type", "tool_result")
                                            put("tool_use_id", turn.toolCallId)
                                            put("content", turn.output)
                                            put("is_error", turn.isError)
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        return JSONObject().apply {
            put("model", selectedModel)
            put("system", systemPrompt)
            put("messages", messages)
            put("max_tokens", defaultMaxGenerationTokens)
            put("temperature", 0.2)
            put("stream", true)
            tools?.let { put("tools", it) }
        }
    }

    fun buildChatRequestPayload(
        selectedModel: String,
        systemPrompt: String,
        fullPrompt: String,
        streaming: Boolean,
        defaultMaxGenerationTokens: Int,
        requestMode: ProviderRequestMode
    ): JSONObject {
        val messages = JSONArray()
        messages.put(
            JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
        )
        messages.put(
            JSONObject().apply {
                put("role", "user")
                put("content", fullPrompt)
            }
        )

        return JSONObject().apply {
            put("model", selectedModel)
            put("messages", messages)
            put("stream", streaming)
            if (!requestMode.compatibilityFallback) {
                put("max_tokens", defaultMaxGenerationTokens)
            }
        }
    }

    fun buildResponsesRequestPayload(
        selectedModel: String,
        systemPrompt: String,
        fullPrompt: String,
        streaming: Boolean,
        defaultMaxGenerationTokens: Int,
        requestMode: ProviderRequestMode
    ): JSONObject {
        return JSONObject().apply {
            put("model", selectedModel)
            put("instructions", systemPrompt)
            put("input", fullPrompt)
            put("stream", streaming)
            if (!requestMode.compatibilityFallback) {
                put("max_output_tokens", defaultMaxGenerationTokens)
            }
        }
    }

    fun buildClaudeRequestPayload(
        selectedModel: String,
        systemPrompt: String,
        fullPrompt: String,
        streaming: Boolean,
        defaultMaxGenerationTokens: Int
    ): JSONObject {
        val messages = JSONArray()
        messages.put(
            JSONObject().apply {
                put("role", "user")
                put("content", fullPrompt)
            }
        )

        return JSONObject().apply {
            put("model", selectedModel)
            put("system", systemPrompt)
            put("messages", messages)
            put("max_tokens", defaultMaxGenerationTokens)
            put("temperature", 0.7)
            put("stream", streaming)
        }
    }

    private fun buildOpenAIChatToolCalls(toolCalls: List<RecordedToolCall>): JSONArray {
        return JSONArray().apply {
            toolCalls.forEach { toolCall ->
                put(
                    JSONObject().apply {
                        put("id", toolCall.id)
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", toolCall.name)
                                put("arguments", toolCall.argumentsJson)
                            }
                        )
                    }
                )
            }
        }
    }

    private fun buildNativeResponsesInput(
        turns: List<CustomProviderTurn>,
        validateToolCallPairing: Boolean
    ): JSONArray {
        return JSONArray().apply {
            val knownToolCallIds = linkedSetOf<String>()
            turns.forEach { turn ->
                when (turn) {
                    is CustomProviderTurn.User -> {
                        put(
                            JSONObject().apply {
                                put("type", "message")
                                put("role", "user")
                                put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", turn.text)
                                        }
                                    )
                                )
                            }
                        )
                    }

                    is CustomProviderTurn.Assistant -> {
                        if (turn.text.isNotBlank()) {
                            put(
                                JSONObject().apply {
                                    put("type", "message")
                                    put("role", "assistant")
                                    put(
                                        "content",
                                        JSONArray().put(
                                            JSONObject().apply {
                                                put("type", "output_text")
                                                put("text", turn.text)
                                            }
                                        )
                                    )
                                }
                            )
                        }
                        turn.toolCalls.forEach { toolCall ->
                            if (toolCall.id.isBlank()) {
                                return@forEach
                            }
                            knownToolCallIds += toolCall.id
                            put(
                                JSONObject().apply {
                                    toolCall.responseItemId
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { put("id", it) }
                                    put("type", "function_call")
                                    put("call_id", toolCall.id)
                                    put("name", toolCall.name)
                                    put("arguments", toolCall.argumentsJson)
                                    put("status", "completed")
                                }
                            )
                        }
                    }

                    is CustomProviderTurn.ToolResult -> {
                        if (turn.toolCallId.isBlank()) {
                            return@forEach
                        }
                        if (validateToolCallPairing && turn.toolCallId !in knownToolCallIds) {
                            return@forEach
                        }
                        put(
                            JSONObject().apply {
                                put("type", "function_call_output")
                                put("call_id", turn.toolCallId)
                                put("output", turn.output)
                            }
                        )
                    }
                }
            }
        }
    }
}
