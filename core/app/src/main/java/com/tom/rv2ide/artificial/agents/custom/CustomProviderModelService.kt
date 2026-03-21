package com.tom.rv2ide.artificial.agents.custom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CustomProviderModelService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAvailableModels(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val modelsEndpoint = CustomProviderConfig.modelsEndpoint(baseUrl)
            if (modelsEndpoint.isBlank()) {
                throw IllegalArgumentException("Base URL is required")
            }

            val request = Request.Builder()
                .url(modelsEndpoint)
                .get()
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = parseErrorMessage(responseBody)
                    throw IllegalStateException("Fetch models failed (${response.code}): $message")
                }

                val models = parseModelsResponse(responseBody)
                if (models.isEmpty()) {
                    throw IllegalStateException("No models returned by ${CustomProviderConfig.normalizedBaseUrl(baseUrl)}")
                }
                models
            }
        }

    fun parseModelsResponse(responseBody: String): List<String> {
        val json = JSONObject(responseBody)
        val models = LinkedHashSet<String>()

        collectModelIds(json.optJSONArray("data"), models)
        collectModelIds(json.optJSONArray("models"), models)

        val groupedData = json.optJSONObject("data")
        if (groupedData != null) {
            val keys = groupedData.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                collectModelIds(groupedData.optJSONArray(key), models)
            }
        }

        return models.toList()
    }

    private fun collectModelIds(items: JSONArray?, target: MutableSet<String>) {
        if (items == null) {
            return
        }

        for (index in 0 until items.length()) {
            val item = items.opt(index)
            val modelId = when (item) {
                is JSONObject -> item.optString("id")
                    .ifBlank { item.optString("name") }
                    .removePrefix("models/")
                    .trim()
                is String -> item.trim()
                else -> ""
            }

            if (modelId.isNotBlank()) {
                target.add(modelId)
            }
        }
    }

    private fun parseErrorMessage(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val error = json.optJSONObject("error")
            error?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: responseBody
        } catch (_: Exception) {
            responseBody
        }.take(300)
    }
}
