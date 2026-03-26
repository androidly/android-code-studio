package com.tom.rv2ide.artificial.agents.custom

import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import org.json.JSONArray
import org.json.JSONObject

internal object CustomProviderApiResponseSupport {
    fun isEventStream(contentType: String?): Boolean {
        return contentType?.contains("text/event-stream", ignoreCase = true) == true
    }

    fun extractEmbeddedStreamError(eventName: String?, json: JSONObject): String? {
        val eventType = json.optString("type").ifBlank { eventName.orEmpty() }
        val errorObject = json.optJSONObject("error")
        val message = errorObject?.optString("message")
            .orEmpty()
            .ifBlank { json.optString("message") }
            .ifBlank {
                json.optJSONObject("response")
                    ?.optJSONObject("error")
                    ?.optString("message")
                    .orEmpty()
            }
            .trim()
        if (message.isBlank()) {
            return null
        }
        return if (eventType.isBlank()) {
            "Custom provider stream error: $message"
        } else {
            "Custom provider stream error [$eventType]: $message"
        }
    }

    fun extractText(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is JSONObject -> extractTextFromObject(value)
            is JSONArray -> {
                val fragments = mutableListOf<String>()
                for (index in 0 until value.length()) {
                    val item = value.opt(index)
                    val text = extractText(item)
                    if (text.isNotBlank()) {
                        fragments.add(text)
                    }
                }
                fragments.joinToString("\n").trim()
            }
            else -> ""
        }
    }

    fun throwMappedApiError(
        responseCode: Int,
        responseBody: String,
        apiType: CustomProviderApiType
    ): Nothing {
        val errorJson = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            JSONObject()
        }

        val errorObject = errorJson.optJSONObject("error") ?: errorJson
        val errorMessage = errorObject.optString("message")
            .ifBlank { errorJson.optString("message") }
            .ifBlank { responseBody.take(300) }
        val errorType = errorObject.optString("type")
        val errorCode = errorObject.optString("code")

        when {
            responseCode == 429 ||
                errorType.contains("rate_limit", true) ||
                errorCode.contains("rate_limit", true) -> {
                throw RateLimitException("Custom provider rate limit exceeded: $errorMessage")
            }

            responseCode == 402 ||
                errorMessage.contains("insufficient balance", true) ||
                errorMessage.contains("余额不足", true) -> {
                throw InsufficientBalanceException("Custom provider balance issue: $errorMessage")
            }

            errorType.contains("insufficient_quota", true) ||
                errorMessage.contains("quota", true) ||
                errorMessage.contains("billing", true) -> {
                throw QuotaExceededException("Custom provider quota exceeded: $errorMessage")
            }

            responseCode == 401 ||
                responseCode == 403 ||
                errorType.contains("authentication", true) ||
                errorCode.contains("invalid_api_key", true) ||
                errorMessage.contains("api key", true) -> {
                throw InvalidApiKeyException("Custom provider authentication failed: $errorMessage")
            }

            else -> throw Exception(
                "Custom provider API error ($responseCode, ${apiType.displayName}): $errorMessage"
            )
        }
    }

    private fun extractTextFromObject(item: JSONObject): String {
        return item.optString("text")
            .ifBlank { item.optString("output_text") }
            .ifBlank {
                val nestedText = item.optJSONObject("message")?.opt("content")
                extractText(nestedText)
            }
            .trim()
    }
}
