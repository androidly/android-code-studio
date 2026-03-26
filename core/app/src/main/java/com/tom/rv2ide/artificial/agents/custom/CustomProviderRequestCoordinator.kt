package com.tom.rv2ide.artificial.agents.custom

import com.tom.rv2ide.artificial.agents.AIAgentStreamListener
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

internal class CustomProviderRequestCoordinator(
    private val requestExecutor: CustomProviderRequestExecutor,
    private val nativeResponseParser: CustomProviderNativeResponseParser,
    private val apiTypeProvider: () -> CustomProviderApiType,
    private val apiKeyProvider: () -> String?,
    private val baseUrlProvider: () -> String,
    private val selectedModelProvider: () -> String,
    private val textSystemPromptProvider: () -> String,
    private val strictNativeToolSchemasEnabledProvider: () -> Boolean,
    private val responsesContinuationStateProvider: () -> CustomProviderResponsesContinuationState,
    private val responsesContinuationEnabledForSessionProvider: () -> Boolean,
    private val defaultMaxGenerationTokens: Int,
    private val nativeSystemPromptProvider: (Boolean) -> String,
    private val nativeConversationProvider: () -> List<CustomProviderTurn>
) {

    fun callProvider(
        fullPrompt: String,
        listener: AIAgentStreamListener? = null
    ): String {
        val key = apiKeyProvider() ?: throw IllegalStateException("API key missing")
        val streaming = listener != null
        return requestExecutor.execute(
            buildRequest = { requestMode ->
                val requestJson = when (apiTypeProvider()) {
                    CustomProviderApiType.OPENAI_CHAT -> buildChatRequest(fullPrompt, streaming, requestMode)
                    CustomProviderApiType.OPENAI_RESPONSES -> buildResponsesRequest(fullPrompt, streaming, requestMode)
                    CustomProviderApiType.CLAUDE_MESSAGES -> buildClaudeRequest(fullPrompt, streaming)
                }
                CustomProviderHttpRequestFactory.buildJsonPostRequest(
                    endpoint = endpoint(),
                    apiType = apiTypeProvider(),
                    apiKey = key,
                    acceptHeader = if (streaming) "text/event-stream" else "application/json",
                    requestJson = requestJson
                )
            },
            parseSuccessfulResponse = { response ->
                val responseBody = response.body ?: throw Exception("Empty response body from custom provider")
                if (streaming && CustomProviderApiResponseSupport.isEventStream(response.header("Content-Type"))) {
                    parseStreamingResponse(responseBody, listener)
                } else {
                    parseNonStreamingResponseBody(responseBody.string(), listener.takeIf { streaming })
                }
            }
        )
    }

    fun callProviderNative(
        toolExecutionEnabled: Boolean,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val key = apiKeyProvider() ?: throw IllegalStateException("API key missing")
        return requestExecutor.execute(
            buildRequest = { requestMode ->
                val requestJson = when (apiTypeProvider()) {
                    CustomProviderApiType.OPENAI_CHAT -> buildNativeChatRequest(toolExecutionEnabled, requestMode)
                    CustomProviderApiType.OPENAI_RESPONSES -> buildNativeResponsesRequest(toolExecutionEnabled, requestMode)
                    CustomProviderApiType.CLAUDE_MESSAGES -> buildNativeClaudeRequest(toolExecutionEnabled)
                }
                CustomProviderHttpRequestFactory.buildJsonPostRequest(
                    endpoint = endpoint(),
                    apiType = apiTypeProvider(),
                    apiKey = key,
                    acceptHeader = "text/event-stream",
                    requestJson = requestJson
                )
            },
            parseSuccessfulResponse = { response ->
                val responseBody = response.body
                    ?: throw Exception("Empty response body from custom provider")
                if (CustomProviderApiResponseSupport.isEventStream(response.header("Content-Type"))) {
                    nativeResponseParser.parseNativeStreamingResponse(responseBody, listener)
                } else {
                    nativeResponseParser.parseNativeJsonResponse(responseBody.string(), listener)
                }
            }
        )
    }

    private fun parseNonStreamingResponseBody(
        rawBody: String,
        listener: AIAgentStreamListener?
    ): String {
        val jsonResponse = JSONObject(rawBody)
        val parsed = when (apiTypeProvider()) {
            CustomProviderApiType.OPENAI_CHAT -> CustomProviderResponseTextSupport.parseChatResponse(jsonResponse)
            CustomProviderApiType.OPENAI_RESPONSES -> CustomProviderResponseTextSupport.parseResponsesResponse(jsonResponse)
            CustomProviderApiType.CLAUDE_MESSAGES -> CustomProviderResponseTextSupport.parseClaudeResponse(jsonResponse)
        }
        if (!parsed.isNullOrBlank() && listener != null) {
            listener.onTextDelta(parsed)
        }
        return parsed
    }

    private fun endpoint(): String {
        return when (apiTypeProvider()) {
            CustomProviderApiType.OPENAI_CHAT -> CustomProviderConfig.chatCompletionsEndpoint(baseUrlProvider())
            CustomProviderApiType.OPENAI_RESPONSES -> CustomProviderConfig.responsesEndpoint(baseUrlProvider())
            CustomProviderApiType.CLAUDE_MESSAGES -> CustomProviderConfig.messagesEndpoint(baseUrlProvider())
        }
    }

    private fun buildNativeChatRequest(
        toolExecutionEnabled: Boolean,
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONObject {
        return CustomProviderRequestPayloadSupport.buildNativeChatRequestPayload(
            selectedModel = selectedModelProvider(),
            systemPrompt = nativeSystemPromptProvider(toolExecutionEnabled),
            conversation = nativeConversationProvider(),
            defaultMaxGenerationTokens = defaultMaxGenerationTokens,
            requestMode = requestMode,
            tools = if (toolExecutionEnabled) buildOpenAIChatTools(requestMode) else null
        )
    }

    private fun buildNativeResponsesRequest(
        toolExecutionEnabled: Boolean,
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONObject {
        val currentConversation = nativeConversationProvider()
        val systemPrompt = nativeSystemPromptProvider(toolExecutionEnabled)
        val requestPlan = CustomProviderResponsesContinuationSupport.buildRequestPlan(
            state = responsesContinuationStateProvider(),
            currentConversation = currentConversation,
            selectedModel = selectedModelProvider(),
            toolExecutionEnabled = toolExecutionEnabled,
            systemPrompt = systemPrompt,
            requestMode = requestMode,
            apiType = apiTypeProvider(),
            responsesContinuationEnabledForSession = responsesContinuationEnabledForSessionProvider()
        )
        return CustomProviderRequestPayloadSupport.buildNativeResponsesRequestPayload(
            selectedModel = selectedModelProvider(),
            systemPrompt = systemPrompt,
            turns = requestPlan.turns,
            validateToolCallPairing = requestPlan.validateToolCallPairing,
            defaultMaxGenerationTokens = defaultMaxGenerationTokens,
            requestMode = requestMode,
            previousResponseId = requestPlan.previousResponseId,
            tools = if (toolExecutionEnabled) buildOpenAIResponsesTools(requestMode) else null
        )
    }

    private fun buildNativeClaudeRequest(toolExecutionEnabled: Boolean): JSONObject {
        return CustomProviderRequestPayloadSupport.buildNativeClaudeRequestPayload(
            selectedModel = selectedModelProvider(),
            systemPrompt = nativeSystemPromptProvider(toolExecutionEnabled),
            conversation = nativeConversationProvider(),
            defaultMaxGenerationTokens = defaultMaxGenerationTokens,
            tools = if (toolExecutionEnabled) buildClaudeTools() else null
        )
    }

    private fun buildOpenAIChatTools(
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONArray {
        return CustomProviderNativeToolCatalog.buildOpenAIChatTools(
            strictToolSchemas = CustomProviderCompatibilitySupport.shouldUseStrictToolSchemas(
                requestMode = requestMode,
                strictNativeToolSchemasEnabled = strictNativeToolSchemasEnabledProvider()
            )
        )
    }

    private fun buildOpenAIResponsesTools(
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONArray {
        return CustomProviderNativeToolCatalog.buildOpenAIResponsesTools(
            strictToolSchemas = CustomProviderCompatibilitySupport.shouldUseStrictToolSchemas(
                requestMode = requestMode,
                strictNativeToolSchemasEnabled = strictNativeToolSchemasEnabledProvider()
            )
        )
    }

    private fun buildClaudeTools(): JSONArray {
        return CustomProviderNativeToolCatalog.buildClaudeTools()
    }

    private fun buildChatRequest(
        fullPrompt: String,
        streaming: Boolean,
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONObject {
        return CustomProviderRequestPayloadSupport.buildChatRequestPayload(
            selectedModel = selectedModelProvider(),
            systemPrompt = textSystemPromptProvider(),
            fullPrompt = fullPrompt,
            streaming = streaming,
            defaultMaxGenerationTokens = defaultMaxGenerationTokens,
            requestMode = requestMode
        )
    }

    private fun buildResponsesRequest(
        fullPrompt: String,
        streaming: Boolean,
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONObject {
        return CustomProviderRequestPayloadSupport.buildResponsesRequestPayload(
            selectedModel = selectedModelProvider(),
            systemPrompt = textSystemPromptProvider(),
            fullPrompt = fullPrompt,
            streaming = streaming,
            defaultMaxGenerationTokens = defaultMaxGenerationTokens,
            requestMode = requestMode
        )
    }

    private fun buildClaudeRequest(fullPrompt: String, streaming: Boolean): JSONObject {
        return CustomProviderRequestPayloadSupport.buildClaudeRequestPayload(
            selectedModel = selectedModelProvider(),
            systemPrompt = textSystemPromptProvider(),
            fullPrompt = fullPrompt,
            streaming = streaming,
            defaultMaxGenerationTokens = defaultMaxGenerationTokens
        )
    }

    private fun parseStreamingResponse(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener?
    ): String {
        val streamListener = listener ?: throw IllegalArgumentException("Streaming listener required")
        val accumulated = StringBuilder()
        var currentEvent: String? = null
        val dataLines = mutableListOf<String>()

        fun flushEvent() {
            if (dataLines.isEmpty()) {
                return
            }
            val eventName = currentEvent
            val payload = dataLines.joinToString("\n")
            currentEvent = null
            dataLines.clear()
            if (payload == "[DONE]") {
                return
            }

            val json = try {
                JSONObject(payload)
            } catch (_: Exception) {
                return
            }

            CustomProviderApiResponseSupport.extractEmbeddedStreamError(eventName, json)?.let { errorMessage ->
                throw IllegalStateException(errorMessage)
            }

            val textDelta = CustomProviderResponseTextSupport.extractStreamingTextDelta(
                apiType = apiTypeProvider(),
                eventName = eventName,
                json = json,
                allowCompletedFallback = accumulated.isEmpty()
            )
            if (textDelta.isNotEmpty()) {
                accumulated.append(textDelta)
                streamListener.onTextDelta(textDelta)
            }
        }

        responseBody.source().use { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> currentEvent = line.substringAfter("event:").trim()
                    line.startsWith("data:") -> dataLines += line.substringAfter("data:").trimStart()
                    line.isBlank() -> flushEvent()
                }
            }
        }

        flushEvent()
        return accumulated.toString().trim()
    }
}
