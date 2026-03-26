package com.tom.rv2ide.artificial.agents.custom

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal object CustomProviderHttpRequestFactory {
    fun buildJsonPostRequest(
        endpoint: String,
        apiType: CustomProviderApiType,
        apiKey: String,
        acceptHeader: String,
        requestJson: JSONObject
    ): Request {
        return Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", acceptHeader)
            .apply {
                when (apiType) {
                    CustomProviderApiType.CLAUDE_MESSAGES -> {
                        header("Authorization", "Bearer $apiKey")
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }

                    else -> {
                        header("Authorization", "Bearer $apiKey")
                    }
                }
            }
            .build()
    }
}
