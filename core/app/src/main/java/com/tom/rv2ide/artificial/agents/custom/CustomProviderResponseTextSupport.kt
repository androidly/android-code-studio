package com.tom.rv2ide.artificial.agents.custom

import org.json.JSONArray
import org.json.JSONObject

internal object CustomProviderResponseTextSupport {
    fun extractStreamingFragment(value: Any?): String {
        return when (value) {
            is String -> value
            is JSONObject -> {
                value.optString("text")
                    .ifBlank { value.optString("output_text") }
                    .ifBlank { value.optString("content") }
                    .ifBlank {
                        value.optJSONObject("message")
                            ?.opt("content")
                            ?.let(::extractStreamingFragment)
                            .orEmpty()
                    }
            }

            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    append(extractStreamingFragment(value.opt(index)))
                }
            }

            else -> ""
        }
    }

    fun parseChatResponse(jsonResponse: JSONObject): String {
        val choices = jsonResponse.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.optJSONObject(0)?.optJSONObject("message")
            val content = CustomProviderApiResponseSupport.extractText(message?.opt("content"))
            if (content.isNotBlank()) {
                return content
            }
        }
        throw Exception("No response content from OpenAI Chat compatible API")
    }

    fun parseResponsesResponse(jsonResponse: JSONObject): String {
        jsonResponse.optString("output_text")
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        val collected = mutableListOf<String>()
        val outputs = jsonResponse.optJSONArray("output")
        if (outputs != null) {
            for (index in 0 until outputs.length()) {
                val item = outputs.optJSONObject(index) ?: continue
                val directText = CustomProviderApiResponseSupport.extractText(item.opt("text"))
                if (directText.isNotBlank()) {
                    collected.add(directText)
                }

                val contentItems = item.optJSONArray("content")
                if (contentItems != null) {
                    val nestedText = CustomProviderApiResponseSupport.extractText(contentItems)
                    if (nestedText.isNotBlank()) {
                        collected.add(nestedText)
                    }
                }
            }
        }

        if (collected.isNotEmpty()) {
            return collected.joinToString("\n").trim()
        }

        parseChatResponse(jsonResponse).takeIf { it.isNotBlank() }?.let { return it }
        throw Exception("No response content from OpenAI Responses compatible API")
    }

    fun parseClaudeResponse(jsonResponse: JSONObject): String {
        val content = CustomProviderApiResponseSupport.extractText(jsonResponse.optJSONArray("content"))
        if (content.isNotBlank()) {
            return content
        }
        throw Exception("No response content from Claude Messages compatible API")
    }

    fun extractStreamingTextDelta(
        apiType: CustomProviderApiType,
        eventName: String?,
        json: JSONObject,
        allowCompletedFallback: Boolean
    ): String {
        return when (apiType) {
            CustomProviderApiType.OPENAI_CHAT -> extractChatStreamText(json)
            CustomProviderApiType.OPENAI_RESPONSES -> extractResponsesStreamText(
                eventName = eventName,
                json = json,
                allowCompletedFallback = allowCompletedFallback
            )
            CustomProviderApiType.CLAUDE_MESSAGES -> extractClaudeStreamText(
                eventName = eventName,
                json = json,
                allowCompletedFallback = allowCompletedFallback
            )
        }
    }

    fun extractChatStreamText(json: JSONObject): String {
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta")
        return extractStreamingFragment(delta?.opt("content"))
            .ifEmpty { choice.optString("text") }
    }

    fun extractResponsesStreamText(
        eventName: String?,
        json: JSONObject,
        allowCompletedFallback: Boolean
    ): String {
        val eventType = json.optString("type").ifBlank { eventName.orEmpty() }
        if (eventType.contains("output_text.delta", ignoreCase = true)) {
            return json.optString("delta")
        }

        if (eventType.contains("response.completed", ignoreCase = true) && allowCompletedFallback) {
            json.optJSONObject("response")?.let { response ->
                response.optString("output_text")
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
                parseResponsesResponse(response)
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }

        val nestedText = extractStreamingFragment(json.optJSONObject("delta"))
        if (nestedText.isNotEmpty()) {
            return nestedText
        }

        return json.optString("delta")
    }

    fun extractClaudeStreamText(
        eventName: String?,
        json: JSONObject,
        allowCompletedFallback: Boolean
    ): String {
        val type = json.optString("type").ifBlank { eventName.orEmpty() }
        return when {
            type.contains("content_block_delta", ignoreCase = true) ->
                json.optJSONObject("delta")?.optString("text").orEmpty()

            type.contains("content_block_start", ignoreCase = true) ->
                json.optJSONObject("content_block")?.optString("text").orEmpty()

            type.contains("message_stop", ignoreCase = true) && allowCompletedFallback ->
                json.optJSONObject("message")
                    ?.optJSONArray("content")
                    ?.let(::extractStreamingFragment)
                    .orEmpty()

            else -> ""
        }
    }
}
