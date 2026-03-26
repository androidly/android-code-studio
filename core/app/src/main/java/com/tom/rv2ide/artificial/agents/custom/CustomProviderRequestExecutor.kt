package com.tom.rv2ide.artificial.agents.custom

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class CustomProviderRequestExecutor(
    private val httpClient: OkHttpClient,
    private val apiTypeProvider: () -> CustomProviderApiType,
    private val applyLearningOutcome: (CustomProviderCompatibilityLearningOutcome) -> Unit
) {
    fun <T> execute(
        buildRequest: (ProviderRequestMode) -> Request,
        parseSuccessfulResponse: (Response) -> T
    ): T {
        var deferredFailure: Pair<Int, String>? = null

        for (requestMode in CustomProviderCompatibilitySupport.requestModesFor(apiTypeProvider())) {
            val response = httpClient.newCall(buildRequest(requestMode)).execute()
            try {
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    applyLearning(rawMessage = responseBody, requestMode = requestMode)
                    if (
                        CustomProviderCompatibilitySupport.shouldRetryWithCompatibilityFallback(
                            responseCode = response.code,
                            requestMode = requestMode,
                            apiType = apiTypeProvider()
                        )
                    ) {
                        deferredFailure = response.code to responseBody
                        continue
                    }
                    CustomProviderApiResponseSupport.throwMappedApiError(
                        responseCode = response.code,
                        responseBody = responseBody,
                        apiType = apiTypeProvider()
                    )
                }

                try {
                    return parseSuccessfulResponse(response)
                } catch (error: Exception) {
                    applyLearning(rawMessage = error.message.orEmpty(), requestMode = requestMode)
                    if (
                        CustomProviderCompatibilitySupport.shouldRetryWithCompatibilityFallback(
                            error = error,
                            requestMode = requestMode,
                            apiType = apiTypeProvider()
                        )
                    ) {
                        deferredFailure = 400 to (error.message ?: error.toString())
                        continue
                    }
                    throw error
                }
            } finally {
                response.close()
            }
        }

        deferredFailure?.let { (responseCode, responseBody) ->
            CustomProviderApiResponseSupport.throwMappedApiError(
                responseCode = responseCode,
                responseBody = responseBody,
                apiType = apiTypeProvider()
            )
        }
        throw IllegalStateException("Custom provider request failed without a usable response")
    }

    private fun applyLearning(
        rawMessage: String,
        requestMode: ProviderRequestMode
    ) {
        applyLearningOutcome(
            CustomProviderCompatibilitySupport.applyCompatibilityLearning(
                rawMessage = rawMessage,
                requestMode = requestMode,
                apiType = apiTypeProvider()
            )
        )
    }
}
