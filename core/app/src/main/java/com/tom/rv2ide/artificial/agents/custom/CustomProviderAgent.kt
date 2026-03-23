package com.tom.rv2ide.artificial.agents.custom

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AIAgentStreamListener
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.agents.NativeToolCallingAgent
import com.tom.rv2ide.artificial.agents.NativeToolResult
import com.tom.rv2ide.artificial.agents.NativeToolTurnResponse
import com.tom.rv2ide.artificial.agents.PersistentConversationAgent
import com.tom.rv2ide.artificial.agents.addBoundedModificationAttempt
import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.tools.AIToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

class CustomProviderAgent : AIAgent, NativeToolCallingAgent, PersistentConversationAgent {

    private var apiKey: String? = null
    private var baseUrl: String = ""
    private var selectedModel: String = ""
    private var apiType: CustomProviderApiType = CustomProviderApiType.OPENAI_CHAT

    private val writingRules = WritingRules.Instructions()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private var projectTreeResult: ProjectTreeResult? = null
    private var fileWriter: AIFileWriter? = null
    private var agents: Agents? = null
    private val conversationHistory = mutableListOf<CustomConversationMessage>()
    private val committedToolConversation = mutableListOf<CustomProviderTurn>()
    private val activeToolTurn = mutableListOf<CustomProviderTurn>()
    private val condensedNativeConversation = mutableListOf<String>()
    private val modificationHistory = mutableListOf<ModificationAttempt>()
    private var currentAttemptCount = 0
    private var toolCallSequence = 0
    private var lastNativeResponsesRequestSignature: String? = null
    private var lastNativeResponsesResponseId: String? = null
    private var lastNativeResponsesConversationKeys: List<String> = emptyList()
    private var strictNativeToolSchemasEnabled = true
    private var responsesContinuationEnabledForSession = true
    private var initializationFingerprint: String? = null
    private val maxRetryAttempts = 3
    private val defaultContextWindowTokens = 400000
    private val defaultInputWindowTokens = 272000
    private val defaultReservedCompactionTokens = 20000
    private val defaultMaxGenerationTokens = 128000
    private val minCommittedNativeTurnsToKeep = 8
    private val nativeCompressionBatchSize = 8
    private val maxCondensedNativeEntries = 10

    override val providerId = "custom"
    override val providerName = "Custom Provider"

    companion object {
        fun registerAgent() {
            AIAgentRegistry.register("custom", object : AIAgentRegistry.AgentFactory {
                override fun create(context: Context): AIAgent {
                    return CustomProviderAgent()
                }

                override fun hasValidApiKey(): Boolean {
                    return CustomProviderConfig.hasValidConfig()
                }

                override fun getApiKey(): String? {
                    return CustomProviderConfig.getApiKey().takeIf { it.isNotBlank() }
                }
            })
        }
    }

    override fun initialize(apiKey: String, context: Context) {
        this.apiKey = apiKey
        agents = Agents(context)

        val settings = CustomProviderConfig.getSettings()
        baseUrl = settings.normalizedBaseUrl
        apiType = settings.apiType

        val savedModel = if (agents?.getProvider() == providerId) {
            agents?.getAgent().orEmpty()
        } else {
            ""
        }

        selectedModel = savedModel.ifBlank { settings.modelId }

        if (baseUrl.isBlank() || this.apiKey.isNullOrBlank() || selectedModel.isBlank()) {
            throw IllegalStateException("Custom provider is not fully configured")
        }

        val nextFingerprint = buildInitializationFingerprint(
            apiKey = this.apiKey.orEmpty(),
            normalizedBaseUrl = baseUrl,
            modelId = selectedModel,
            nextApiType = apiType
        )
        if (initializationFingerprint == null) {
            resetProviderCompatibilityState()
        }
        if (initializationFingerprint != null && initializationFingerprint != nextFingerprint) {
            clearConversation()
        }
        initializationFingerprint = nextFingerprint
    }

    override fun reinitializeWithNewModel(apiKey: String, context: Context) {
        initialize(apiKey, context)
    }

    override fun setContext(context: Context) {
        fileWriter = AIFileWriter(context)
    }

    override fun setProjectData(projectTreeResult: ProjectTreeResult) {
        this.projectTreeResult = projectTreeResult
    }

    override fun clearConversation() {
        conversationHistory.clear()
        committedToolConversation.clear()
        activeToolTurn.clear()
        condensedNativeConversation.clear()
        modificationHistory.clear()
        currentAttemptCount = 0
        toolCallSequence = 0
        resetProviderCompatibilityState()
        resetNativeResponsesContinuationState()
    }

    override fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean) {
        modificationHistory.addBoundedModificationAttempt(
            ModificationAttempt(
                timestamp = System.currentTimeMillis(),
                filePath = filePath,
                previousContent = oldContent,
                newContent = newContent,
                attemptNumber = currentAttemptCount,
                success = success
            )
        )
    }

    override fun undoLastModification(): Boolean {
        if (modificationHistory.isEmpty()) {
            return false
        }

        val lastModification = modificationHistory.lastOrNull { it.success } ?: return false
        if (lastModification.previousContent != null) {
            val result = writeFile(lastModification.filePath, lastModification.previousContent)
            if (result is FileWriteResult.Success) {
                modificationHistory.removeAt(modificationHistory.lastIndexOf(lastModification))
                return true
            }
            return false
        }

        return try {
            File(lastModification.filePath).delete()
            modificationHistory.removeAt(modificationHistory.lastIndexOf(lastModification))
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getModificationHistory(): List<ModificationAttempt> {
        return modificationHistory.toList()
    }

    override fun resetAttemptCount() {
        currentAttemptCount = 0
    }

    override fun incrementAttemptCount() {
        currentAttemptCount++
    }

    override fun getCurrentAttemptCount(): Int = currentAttemptCount

    override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

    override suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(generateResponse(prompt, context, null))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun generateCodeStreaming(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
        listener: AIAgentStreamListener
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(generateResponse(prompt, context, listener))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override fun writeFile(filePath: String, content: String): FileWriteResult {
        val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
        return writer.writeFile(filePath, content, createBackup = true)
    }

    override fun isInitialized(): Boolean {
        return !apiKey.isNullOrBlank() && baseUrl.isNotBlank() && selectedModel.isNotBlank()
    }

    override fun persistentConversationFingerprint(): String {
        return initializationFingerprint.orEmpty()
    }

    override fun exportPersistentConversationState(): String {
        val persistedRequestSignature = lastNativeResponsesRequestSignature
            ?.takeIf { activeToolTurn.isEmpty() }
        val persistedResponseId = lastNativeResponsesResponseId
            ?.takeIf { activeToolTurn.isEmpty() }
        return JSONObject().apply {
            put(
                "conversationHistory",
                JSONArray().apply {
                    conversationHistory.forEach { message ->
                        put(
                            JSONObject().apply {
                                put("role", message.role)
                                put("content", message.content)
                            }
                        )
                    }
                }
            )
            put(
                "committedToolConversation",
                JSONArray().apply {
                    committedToolConversation.forEach { turn ->
                        put(turn.toJson())
                    }
                }
            )
            put(
                "condensedNativeConversation",
                JSONArray().apply {
                    condensedNativeConversation.forEach { summaryEntry ->
                        put(summaryEntry)
                    }
                }
            )
            put("toolCallSequence", toolCallSequence)
            put("lastNativeResponsesRequestSignature", persistedRequestSignature.orEmpty())
            put("lastNativeResponsesResponseId", persistedResponseId.orEmpty())
        }.toString()
    }

    override fun importPersistentConversationState(serializedState: String) {
        clearConversation()
        val state = JSONObject(serializedState)

        state.optJSONArray("conversationHistory")?.let { items ->
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val role = item.optString("role").trim()
                val content = item.optString("content").trim()
                if (role.isBlank() || content.isBlank()) {
                    continue
                }
                conversationHistory += CustomConversationMessage(role = role, content = content)
            }
        }

        state.optJSONArray("committedToolConversation")?.let { items ->
            for (index in 0 until items.length()) {
                items.optJSONObject(index)
                    ?.toCustomProviderTurnOrNull()
                    ?.let(committedToolConversation::add)
            }
        }

        state.optJSONArray("condensedNativeConversation")?.let { items ->
            for (index in 0 until items.length()) {
                items.optString(index)
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(condensedNativeConversation::add)
            }
        }

        toolCallSequence = state.optInt("toolCallSequence", 0)
        lastNativeResponsesRequestSignature = state.optString("lastNativeResponsesRequestSignature")
            .takeIf { it.isNotBlank() }
        lastNativeResponsesResponseId = state.optString("lastNativeResponsesResponseId")
            .takeIf { it.isNotBlank() }
        lastNativeResponsesConversationKeys = if (lastNativeResponsesResponseId != null) {
            currentNativeConversation().map(CustomProviderTurn::incrementalKey)
        } else {
            emptyList()
        }
    }

    override suspend fun startToolSessionTurn(
        prompt: String,
        toolExecutionEnabled: Boolean,
        listener: AIAgentStreamListener
    ): Result<NativeToolTurnResponse> = withContext(Dispatchers.IO) {
        try {
            activeToolTurn.clear()
            activeToolTurn += CustomProviderTurn.User(buildNativeUserTurn(prompt))
            autoCompactNativeConversationIfNeeded(toolExecutionEnabled)
            val response = callProviderNative(toolExecutionEnabled, listener)
            activeToolTurn += CustomProviderTurn.Assistant(
                text = response.assistantText,
                toolCalls = response.toolCalls
            )
            rememberNativeResponsesContinuationState(
                toolExecutionEnabled = toolExecutionEnabled,
                responseId = response.responseId
            )
            if (response.toolCalls.isEmpty()) {
                commitActiveToolTurn(toolExecutionEnabled)
            }
            Result.success(response.toExternalResponse())
        } catch (error: Exception) {
            abortActiveToolSessionTurn()
            Result.failure(error)
        }
    }

    override suspend fun continueToolSessionTurn(
        toolResults: List<NativeToolResult>,
        toolExecutionEnabled: Boolean,
        listener: AIAgentStreamListener
    ): Result<NativeToolTurnResponse> = withContext(Dispatchers.IO) {
        try {
            if (activeToolTurn.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("No active tool session turn to continue")
                )
            }

            activeToolTurn += toolResults.map { toolResult ->
                CustomProviderTurn.ToolResult(
                    toolCallId = toolResult.toolCallId,
                    toolName = toolResult.toolName,
                    output = toolResult.output,
                    isError = toolResult.isError
                )
            }

            autoCompactNativeConversationIfNeeded(toolExecutionEnabled)
            val response = callProviderNative(toolExecutionEnabled, listener)
            activeToolTurn += CustomProviderTurn.Assistant(
                text = response.assistantText,
                toolCalls = response.toolCalls
            )
            rememberNativeResponsesContinuationState(
                toolExecutionEnabled = toolExecutionEnabled,
                responseId = response.responseId
            )
            if (response.toolCalls.isEmpty()) {
                commitActiveToolTurn(toolExecutionEnabled)
            }
            Result.success(response.toExternalResponse())
        } catch (error: Exception) {
            abortActiveToolSessionTurn()
            Result.failure(error)
        }
    }

    override fun abortActiveToolSessionTurn() {
        if (activeToolTurn.isNotEmpty()) {
            resetNativeResponsesContinuationState()
        }
        activeToolTurn.clear()
    }

    private fun generateResponse(
        prompt: String,
        context: String?,
        listener: AIAgentStreamListener?
    ): String {
        if (!isInitialized()) {
            throw IllegalStateException("Custom provider not initialized")
        }

        val fullPrompt = buildPrompt(prompt, context)
        val responseText = callProvider(fullPrompt, listener)
        if (responseText.isBlank()) {
            throw Exception("Empty response from custom provider")
        }

        conversationHistory.add(CustomConversationMessage("user", prompt))
        conversationHistory.add(CustomConversationMessage("assistant", responseText))
        trimConversationHistory()
        listener?.onCompleted(responseText)
        return responseText
    }

    private fun buildPrompt(prompt: String, context: String?): String {
        val needsCorrection = isUserRequestingCorrection(prompt)

        return buildString {
            append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
            if (projectTreeResult != null) {
                append(projectTreeResult!!.tree)
                append("\n\n")
                append("CRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths like '/storage/emulated/0/project' or 'com.example.yourproject'.\n")
                append("CRITICAL: Look at the actual paths above and use those EXACT paths.\n\n")
            }

            append("IMPORTANT: File contents are not preloaded. Use project search and focused file-range reads to inspect only the relevant 50-200 lines before editing.\n\n")

            if (context != null) {
                append("=== ADDITIONAL CONTEXT ===\n")
                append(context)
                append("\n\n")
            }

            if (conversationHistory.isNotEmpty()) {
                append("=== CONVERSATION HISTORY ===\n")
                conversationHistory.forEach { message ->
                    append("${message.role.uppercase()}: ${message.content}\n\n")
                }
            }

            if (needsCorrection && modificationHistory.isNotEmpty()) {
                append("=== CORRECTION REQUIRED ===\n")
                append("The user indicated the previous modification was WRONG.\n")
                append("Previous failed attempts:\n")
                modificationHistory.takeLast(3).forEach { attempt ->
                    append("Attempt ${attempt.attemptNumber}: ${attempt.filePath}\n")
                    append("Result: ${if (attempt.success) "Applied but user rejected" else "Failed"}\n\n")
                }
                append("You MUST try a DIFFERENT approach. Do NOT repeat the same solution.\n")
                append("Analyze what went wrong and provide a better solution.\n\n")
            }

            if (currentAttemptCount > 0) {
                append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n")
                append("This is retry attempt number $currentAttemptCount.\n")
                append("Previous attempts did not satisfy the user.\n")
                append("Think carefully and provide a different solution.\n\n")
            }

            append("=== USER REQUEST ===\n")
            append(prompt)
        }
    }

    private fun buildNativeUserTurn(prompt: String): String {
        val needsCorrection = isUserRequestingCorrection(prompt)

        return buildString {
            if (needsCorrection && modificationHistory.isNotEmpty()) {
                appendLine("CORRECTION REQUIRED")
                appendLine("The previous implementation did not satisfy the user.")
                modificationHistory.takeLast(3).forEach { attempt ->
                    appendLine(
                        "- Attempt ${attempt.attemptNumber}: ${attempt.filePath} (${if (attempt.success) "applied but rejected" else "failed"})"
                    )
                }
                appendLine("Use a different approach.")
                appendLine()
            }

            if (currentAttemptCount > 0) {
                appendLine("RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts")
                appendLine("Do not repeat the same solution.")
                appendLine()
            }

            append(prompt.trim())
        }.trim()
    }

    private fun buildNativeSystemPrompt(toolExecutionEnabled: Boolean): String {
        return buildString {
            appendLine("You are ACS AI Agent inside AndroidCodeStudio.")
            appendLine("Operate like a coding CLI agent with persistent multi-turn memory.")
            appendLine("All prior user requests, assistant outputs, tool calls, and tool results in this session remain relevant unless the user changes direction.")
            appendLine("Continue the existing task unless the user clearly changes direction.")
            appendLine("Runtime environment is Android with a Termux-style shell and common CLI tools such as rg, grep, sed, head, tail, cat, find, git, pkg, apt, curl, wget, stat, tree, du, df, ps, cp, mkdir, and touch.")
            appendLine("Do not assume the project tree is preloaded into the prompt. Use tools to discover paths and inspect focused ranges.")
            appendLine("Prefer search and focused reads before editing. Read only the relevant 50-200 lines when possible.")
            appendLine("Prefer precise range edits instead of rewriting whole files.")
            appendLine("Use exact project paths returned by the tools. Do not invent paths.")
            appendLine("When build validation is useful, run a build instead of guessing, but if the same build or command fails twice without any file edits, stop and explain the blocker instead of looping.")
            appendLine("Keep answers concise when no code or tool action is needed.")
            if (toolExecutionEnabled) {
                appendLine("Use native tool calls whenever tools are needed.")
                appendLine("Do not emit raw TOOL_CALL text blocks.")
                appendLine("Do not emit FILE_TO_MODIFY blocks while native tools are available.")
                appendLine("Use run_terminal_command only for a single safe command without chaining, pipes, or redirects.")
                appendLine("Never use 'cd ... && ...'. Put the directory in WORKDIR and keep COMMAND to a single command.")
                appendLine("Use non-interactive flags for package installation and other commands when available.")
                appendLine("If a command is blocked by safety policy or not in the allowlist, do not repeat it verbatim. Switch to another supported command or a different tool.")
                appendLine("Prefer build_project for gradle/gradlew builds. Direct gradlew terminal commands may be rerouted as builds.")
            } else {
                appendLine("Tool execution is disabled. Do not request tools or invent tool results.")
            }

            projectTreeResult?.tree
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { rootPath ->
                    appendLine()
                    appendLine("Project root: $rootPath")
                }
        }.trim()
    }

    private fun currentNativeConversation(): List<CustomProviderTurn> {
        return buildList {
            buildCondensedConversationTurn()?.let(::add)
            addAll(committedToolConversation)
            addAll(activeToolTurn)
        }
    }

    private fun buildCondensedConversationTurn(): CustomProviderTurn? {
        if (condensedNativeConversation.isEmpty()) {
            return null
        }

        return CustomProviderTurn.User(
            buildString {
                appendLine("SESSION SUMMARY FROM EARLIER TURNS")
                appendLine("Treat this summary as authoritative context that replaces older compacted turns.")
                condensedNativeConversation.forEach { summaryEntry ->
                    append("- ")
                    appendLine(summaryEntry)
                }
            }.trim()
        )
    }

    private fun resetNativeResponsesContinuationState() {
        lastNativeResponsesRequestSignature = null
        lastNativeResponsesResponseId = null
        lastNativeResponsesConversationKeys = emptyList()
    }

    private fun resetProviderCompatibilityState() {
        strictNativeToolSchemasEnabled = shouldEnableStrictToolSchemasByDefault()
        responsesContinuationEnabledForSession = shouldEnableResponsesContinuationByDefault()
    }

    private fun rememberNativeResponsesContinuationState(
        toolExecutionEnabled: Boolean,
        responseId: String?
    ) {
        if (!shouldUseResponsesContinuation(ProviderRequestMode.Default)) {
            return
        }
        val normalizedResponseId = responseId?.trim().orEmpty()
        if (normalizedResponseId.isBlank()) {
            resetNativeResponsesContinuationState()
            return
        }

        lastNativeResponsesRequestSignature = buildNativeResponsesRequestSignature(toolExecutionEnabled)
        lastNativeResponsesResponseId = normalizedResponseId
        lastNativeResponsesConversationKeys = currentNativeConversation().map(CustomProviderTurn::incrementalKey)
    }

    private fun commitActiveToolTurn(toolExecutionEnabled: Boolean) {
        if (activeToolTurn.isEmpty()) {
            return
        }
        committedToolConversation += activeToolTurn
        activeToolTurn.clear()
        autoCompactNativeConversationIfNeeded(toolExecutionEnabled)
    }

    private fun nextToolCallId(): String {
        toolCallSequence += 1
        return "acs_call_$toolCallSequence"
    }

    private fun callProvider(
        fullPrompt: String,
        listener: AIAgentStreamListener? = null
    ): String {
        val key = apiKey ?: throw IllegalStateException("API key missing")
        val streaming = listener != null
        var deferredFailure: Pair<Int, String>? = null

        for (requestMode in requestModesForCurrentProvider()) {
            val requestJson = when (apiType) {
                CustomProviderApiType.OPENAI_CHAT -> buildChatRequest(fullPrompt, streaming, requestMode)
                CustomProviderApiType.OPENAI_RESPONSES -> buildResponsesRequest(fullPrompt, streaming, requestMode)
                CustomProviderApiType.CLAUDE_MESSAGES -> buildClaudeRequest(fullPrompt, streaming)
            }

            val request = Request.Builder()
                .url(getEndpoint())
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept", if (streaming) "text/event-stream" else "application/json")
                .apply {
                    when (apiType) {
                        CustomProviderApiType.CLAUDE_MESSAGES -> {
                            header("Authorization", "Bearer $key")
                            header("x-api-key", key)
                            header("anthropic-version", "2023-06-01")
                        }
                        else -> {
                            header("Authorization", "Bearer $key")
                        }
                    }
                }
                .build()

            val response = httpClient.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    applyCompatibilityLearning(responseBody, requestMode)
                    if (shouldRetryWithCompatibilityFallback(response.code, requestMode)) {
                        deferredFailure = response.code to responseBody
                        continue
                    }
                    throwMappedApiError(response.code, responseBody)
                }

                val responseBody = response.body ?: throw Exception("Empty response body from custom provider")
                val contentType = response.header("Content-Type")
                try {
                    return if (streaming) {
                        if (isEventStream(contentType)) {
                            parseStreamingResponse(responseBody, listener)
                        } else {
                            parseNonStreamingResponseBody(responseBody.string(), listener)
                        }
                    } else {
                        parseNonStreamingResponseBody(responseBody.string(), null)
                    }
                } catch (error: Exception) {
                    applyCompatibilityLearning(error.message.orEmpty(), requestMode)
                    if (shouldRetryWithCompatibilityFallback(error, requestMode)) {
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
            throwMappedApiError(responseCode, responseBody)
        }
        throw IllegalStateException("Custom provider request failed without a usable response")
    }

    private fun parseNonStreamingResponseBody(
        rawBody: String,
        listener: AIAgentStreamListener?
    ): String {
        val jsonResponse = JSONObject(rawBody)
        val parsed = when (apiType) {
            CustomProviderApiType.OPENAI_CHAT -> parseChatResponse(jsonResponse)
            CustomProviderApiType.OPENAI_RESPONSES -> parseResponsesResponse(jsonResponse)
            CustomProviderApiType.CLAUDE_MESSAGES -> parseClaudeResponse(jsonResponse)
        }
        if (!parsed.isNullOrBlank() && listener != null) {
            listener.onTextDelta(parsed)
        }
        return parsed
    }

    private fun getEndpoint(): String {
        return when (apiType) {
            CustomProviderApiType.OPENAI_CHAT -> CustomProviderConfig.chatCompletionsEndpoint(baseUrl)
            CustomProviderApiType.OPENAI_RESPONSES -> CustomProviderConfig.responsesEndpoint(baseUrl)
            CustomProviderApiType.CLAUDE_MESSAGES -> CustomProviderConfig.messagesEndpoint(baseUrl)
        }
    }

    private fun callProviderNative(
        toolExecutionEnabled: Boolean,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val key = apiKey ?: throw IllegalStateException("API key missing")
        var deferredFailure: Pair<Int, String>? = null

        for (requestMode in requestModesForCurrentProvider()) {
            val requestJson = when (apiType) {
                CustomProviderApiType.OPENAI_CHAT -> buildNativeChatRequest(toolExecutionEnabled, requestMode)
                CustomProviderApiType.OPENAI_RESPONSES -> buildNativeResponsesRequest(toolExecutionEnabled, requestMode)
                CustomProviderApiType.CLAUDE_MESSAGES -> buildNativeClaudeRequest(toolExecutionEnabled)
            }

            val request = Request.Builder()
                .url(getEndpoint())
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .apply {
                    when (apiType) {
                        CustomProviderApiType.CLAUDE_MESSAGES -> {
                            header("Authorization", "Bearer $key")
                            header("x-api-key", key)
                            header("anthropic-version", "2023-06-01")
                        }

                        else -> {
                            header("Authorization", "Bearer $key")
                        }
                    }
                }
                .build()

            val response = httpClient.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    applyCompatibilityLearning(responseBody, requestMode)
                    if (shouldRetryWithCompatibilityFallback(response.code, requestMode)) {
                        deferredFailure = response.code to responseBody
                        continue
                    }
                    throwMappedApiError(response.code, responseBody)
                }

                val responseBody = response.body
                    ?: throw Exception("Empty response body from custom provider")
                try {
                    return if (isEventStream(response.header("Content-Type"))) {
                        parseNativeStreamingResponse(responseBody, listener)
                    } else {
                        parseNativeJsonResponse(responseBody.string(), listener)
                    }
                } catch (error: Exception) {
                    applyCompatibilityLearning(error.message.orEmpty(), requestMode)
                    if (shouldRetryWithCompatibilityFallback(error, requestMode)) {
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
            throwMappedApiError(responseCode, responseBody)
        }
        throw IllegalStateException("Custom provider request failed without a usable response")
    }

    private fun buildNativeChatRequest(
        toolExecutionEnabled: Boolean,
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONObject {
        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", buildNativeSystemPrompt(toolExecutionEnabled))
                }
            )
            currentNativeConversation().forEach { turn ->
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
            if (toolExecutionEnabled) {
                put("tools", buildOpenAIChatTools(requestMode))
                put("tool_choice", "auto")
            }
        }
    }

    private fun buildNativeResponsesRequest(
        toolExecutionEnabled: Boolean,
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONObject {
        val currentConversation = currentNativeConversation()
        val currentConversationKeys = currentConversation.map(CustomProviderTurn::incrementalKey)
        val requestSignature = buildNativeResponsesRequestSignature(toolExecutionEnabled)
        val previousResponseId = lastNativeResponsesResponseId
            ?.takeIf {
                shouldUseResponsesContinuation(requestMode) &&
                    requestSignature == lastNativeResponsesRequestSignature
            }
        val incrementalTurns = previousResponseId
            ?.takeIf {
                lastNativeResponsesConversationKeys.isNotEmpty() &&
                    currentConversationKeys.size > lastNativeResponsesConversationKeys.size &&
                    currentConversationKeys
                        .subList(0, lastNativeResponsesConversationKeys.size) == lastNativeResponsesConversationKeys
            }
            ?.let { currentConversation.drop(lastNativeResponsesConversationKeys.size) }
        val input = buildNativeResponsesInput(
            turns = incrementalTurns ?: currentConversation,
            validateToolCallPairing = incrementalTurns == null
        )

        return JSONObject().apply {
            put("model", selectedModel)
            put("instructions", buildNativeSystemPrompt(toolExecutionEnabled))
            put("input", input)
            put("stream", true)
            if (!requestMode.compatibilityFallback) {
                put("max_output_tokens", defaultMaxGenerationTokens)
            }
            previousResponseId
                ?.takeIf { incrementalTurns != null }
                ?.let { put("previous_response_id", it) }
            if (toolExecutionEnabled) {
                put("tools", buildOpenAIResponsesTools(requestMode))
                put("tool_choice", "auto")
            }
        }
    }

    private fun buildNativeResponsesInput(
        turns: List<CustomProviderTurn>,
        validateToolCallPairing: Boolean = true
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

    private fun buildNativeResponsesRequestSignature(toolExecutionEnabled: Boolean): String {
        return buildString {
            append(selectedModel)
            append('|')
            append(toolExecutionEnabled)
            append('|')
            append(buildNativeSystemPrompt(toolExecutionEnabled))
        }
    }

    private fun buildNativeClaudeRequest(toolExecutionEnabled: Boolean): JSONObject {
        val messages = JSONArray().apply {
            currentNativeConversation().forEach { turn ->
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
            put("system", buildNativeSystemPrompt(toolExecutionEnabled))
            put("messages", messages)
            put("max_tokens", defaultMaxGenerationTokens)
            put("temperature", 0.2)
            put("stream", true)
            if (toolExecutionEnabled) {
                put("tools", buildClaudeTools())
            }
        }
    }

    private fun buildOpenAIChatTools(
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONArray {
        return JSONArray().apply {
            val strictToolSchemas = shouldUseStrictToolSchemas(requestMode)
            nativeToolDefinitions().forEach { definition ->
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", definition.name)
                                put("description", definition.description)
                                put("parameters", buildOpenAICompatibleToolSchema(definition.inputSchema, strictToolSchemas))
                                if (strictToolSchemas) {
                                    put("strict", true)
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    private fun buildOpenAIResponsesTools(
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONArray {
        return JSONArray().apply {
            val strictToolSchemas = shouldUseStrictToolSchemas(requestMode)
            nativeToolDefinitions().forEach { definition ->
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put("name", definition.name)
                        put("description", definition.description)
                        put("parameters", buildOpenAICompatibleToolSchema(definition.inputSchema, strictToolSchemas))
                        if (strictToolSchemas) {
                            put("strict", true)
                        }
                    }
                )
            }
        }
    }

    private fun buildClaudeTools(): JSONArray {
        return JSONArray().apply {
            nativeToolDefinitions().forEach { definition ->
                put(
                    JSONObject().apply {
                        put("name", definition.name)
                        put("description", definition.description)
                        put("input_schema", definition.inputSchema)
                    }
                )
            }
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

    private fun buildChatRequest(
        fullPrompt: String,
        streaming: Boolean,
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONObject {
        val messages = JSONArray()
        messages.put(
            JSONObject().apply {
                put("role", "system")
                put("content", writingRules.useThis())
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

    private fun buildResponsesRequest(
        fullPrompt: String,
        streaming: Boolean,
        requestMode: ProviderRequestMode = ProviderRequestMode.Default
    ): JSONObject {
        return JSONObject().apply {
            put("model", selectedModel)
            put("instructions", writingRules.useThis())
            put("input", fullPrompt)
            put("stream", streaming)
            if (!requestMode.compatibilityFallback) {
                put("max_output_tokens", defaultMaxGenerationTokens)
            }
        }
    }

    private fun buildClaudeRequest(fullPrompt: String, streaming: Boolean): JSONObject {
        val messages = JSONArray()
        messages.put(
            JSONObject().apply {
                put("role", "user")
                put("content", fullPrompt)
            }
        )

        return JSONObject().apply {
            put("model", selectedModel)
            put("system", writingRules.useThis())
            put("messages", messages)
            put("max_tokens", defaultMaxGenerationTokens)
            put("temperature", 0.7)
            put("stream", streaming)
        }
    }

    private fun parseStreamingResponse(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener?
    ): String {
        val streamListener = listener ?: throw IllegalArgumentException("Streaming listener required")
        val accumulated = StringBuilder()
        var currentEvent: String? = null
        val dataLines = mutableListOf<String>()

        responseBody.source().use { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> currentEvent = line.substringAfter("event:").trim()
                    line.startsWith("data:") -> dataLines += line.substringAfter("data:").trimStart()
                    line.isBlank() -> {
                        handleStreamingEvent(currentEvent, dataLines, accumulated, streamListener)
                        currentEvent = null
                        dataLines.clear()
                    }
                }
            }
        }

        if (dataLines.isNotEmpty()) {
            handleStreamingEvent(currentEvent, dataLines, accumulated, streamListener)
        }

        return accumulated.toString().trim()
    }

    private fun handleStreamingEvent(
        eventName: String?,
        dataLines: List<String>,
        accumulated: StringBuilder,
        listener: AIAgentStreamListener
    ) {
        if (dataLines.isEmpty()) {
            return
        }

        val payload = dataLines.joinToString("\n")
        if (payload == "[DONE]") {
            return
        }

        val json = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return
        }

        extractEmbeddedStreamError(eventName, json)?.let { errorMessage ->
            throw IllegalStateException(errorMessage)
        }

        val textDelta = when (apiType) {
            CustomProviderApiType.OPENAI_CHAT -> extractOpenAIChatStreamText(json)
            CustomProviderApiType.OPENAI_RESPONSES -> extractOpenAIResponsesStreamText(eventName, json, accumulated.isEmpty())
            CustomProviderApiType.CLAUDE_MESSAGES -> extractClaudeStreamText(eventName, json, accumulated.isEmpty())
        }

        if (textDelta.isNotEmpty()) {
            accumulated.append(textDelta)
            listener.onTextDelta(textDelta)
        }
    }

    private fun extractOpenAIChatStreamText(json: JSONObject): String {
        val choices = json.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) {
            return ""
        }

        val choice = choices.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta")
        return extractStreamingFragment(delta?.opt("content"))
            .ifEmpty { choice.optString("text") }
    }

    private fun extractOpenAIResponsesStreamText(
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
                parseResponsesResponse(response).takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        val deltaObject = json.optJSONObject("delta")
        val nestedText = extractStreamingFragment(deltaObject)
        if (nestedText.isNotEmpty()) {
            return nestedText
        }

        return json.optString("delta")
    }

    private fun extractClaudeStreamText(
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

    private fun extractStreamingFragment(value: Any?): String {
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

    private fun parseChatResponse(jsonResponse: JSONObject): String {
        val choices = jsonResponse.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.optJSONObject(0)?.optJSONObject("message")
            val content = extractText(message?.opt("content"))
            if (content.isNotBlank()) {
                return content
            }
        }
        throw Exception("No response content from OpenAI Chat compatible API")
    }

    private fun parseResponsesResponse(jsonResponse: JSONObject): String {
        jsonResponse.optString("output_text")
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        val collected = mutableListOf<String>()
        val outputs = jsonResponse.optJSONArray("output")
        if (outputs != null) {
            for (index in 0 until outputs.length()) {
                val item = outputs.optJSONObject(index) ?: continue
                val directText = extractText(item.opt("text"))
                if (directText.isNotBlank()) {
                    collected.add(directText)
                }

                val contentItems = item.optJSONArray("content")
                if (contentItems != null) {
                    val nestedText = extractText(contentItems)
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

    private fun parseClaudeResponse(jsonResponse: JSONObject): String {
        val content = extractText(jsonResponse.optJSONArray("content"))
        if (content.isNotBlank()) {
            return content
        }
        throw Exception("No response content from Claude Messages compatible API")
    }

    private fun parseNativeStreamingResponse(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        return when (apiType) {
            CustomProviderApiType.OPENAI_CHAT -> parseNativeOpenAIChatStream(responseBody, listener)
            CustomProviderApiType.OPENAI_RESPONSES -> parseNativeOpenAIResponsesStream(responseBody, listener)
            CustomProviderApiType.CLAUDE_MESSAGES -> parseNativeClaudeStream(responseBody, listener)
        }
    }

    private fun parseNativeOpenAIChatStream(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val textBuffer = StringBuilder()
        val toolCallAccumulators = linkedMapOf<Int, ToolCallStreamAccumulator>()

        collectSseEvents(responseBody) { _, payload ->
            if (payload == "[DONE]") {
                return@collectSseEvents
            }

            val json = payload.toJsonObjectOrNull() ?: return@collectSseEvents
            extractEmbeddedStreamError(null, json)?.let { errorMessage ->
                throw IllegalStateException(errorMessage)
            }
            val choices = json.optJSONArray("choices") ?: return@collectSseEvents
            val choice = choices.optJSONObject(0) ?: return@collectSseEvents
            val delta = choice.optJSONObject("delta")
            val contentDelta = extractStreamingFragment(delta?.opt("content"))
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
                    function?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { nameFragment ->
                            accumulator.name = if (accumulator.name.isBlank()) {
                                nameFragment
                            } else {
                                accumulator.name + nameFragment
                            }
                        }
                    function?.optString("arguments")?.takeIf { it.isNotEmpty() }?.let {
                        accumulator.argumentsBuilder.append(it)
                    }
                }
            }

            choice.optJSONObject("message")?.let { message ->
                val directText = extractText(message.opt("content"))
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
            toolCalls = toolCallAccumulators
                .toSortedMap()
                .values
                .mapNotNull(ToolCallStreamAccumulator::toRecordedToolCall)
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
            if (payload == "[DONE]") {
                return@collectSseEvents
            }

            val json = payload.toJsonObjectOrNull() ?: return@collectSseEvents
            val eventType = json.optString("type").ifBlank { eventName.orEmpty() }
            when {
                eventType.contains("error", ignoreCase = true) ||
                    eventType.contains("response.failed", ignoreCase = true) ||
                    eventType.contains("response.incomplete", ignoreCase = true) -> {
                    throw IllegalStateException(
                        extractEmbeddedStreamError(eventType, json)
                            ?: "Custom provider responses stream failed"
                    )
                }

                eventType.contains("response.created", ignoreCase = true) -> {
                    responseId = json.optJSONObject("response")
                        ?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                        ?: responseId
                }

                eventType.contains("output_text.delta", ignoreCase = true) -> {
                    val delta = json.optString("delta")
                    if (delta.isNotEmpty()) {
                        textBuffer.append(delta)
                        listener.onTextDelta(delta)
                    }
                }

                eventType.contains("output_text.done", ignoreCase = true) -> {
                    val text = json.optString("text").ifBlank { json.optString("delta") }
                    if (textBuffer.isEmpty() && text.isNotBlank()) {
                        textBuffer.append(text)
                        listener.onTextDelta(text)
                    }
                }

                eventType.contains("function_call_arguments.delta", ignoreCase = true) -> {
                    val callId = json.optString("call_id").trim()
                    val itemId = json.optString("item_id").trim()
                    val accumulator = resolveResponsesToolAccumulator(
                        toolCallAccumulators = toolCallAccumulators,
                        callId = callId,
                        itemId = itemId,
                        fallbackId = callId
                            .ifBlank { itemId }
                            .ifBlank { "response_call_${toolCallAccumulators.size + 1}" }
                    )
                    accumulator.adoptResponsesIdentifiers(callId = callId, itemId = itemId)
                    json.optString("name").takeIf { it.isNotBlank() }?.let { accumulator.name = it }
                    json.optString("delta").takeIf { it.isNotEmpty() }?.let {
                        accumulator.argumentsBuilder.append(it)
                    }
                }

                eventType.contains("function_call_arguments.done", ignoreCase = true) -> {
                    val callId = json.optString("call_id").trim()
                    val itemId = json.optString("item_id").trim()
                    val accumulator = resolveResponsesToolAccumulator(
                        toolCallAccumulators = toolCallAccumulators,
                        callId = callId,
                        itemId = itemId,
                        fallbackId = callId
                            .ifBlank { itemId }
                            .ifBlank { "response_call_${toolCallAccumulators.size + 1}" }
                    )
                    accumulator.adoptResponsesIdentifiers(callId = callId, itemId = itemId)
                    accumulator.name = json.optString("name").ifBlank { accumulator.name }
                    json.optString("arguments")
                        .takeIf { it.isNotBlank() }
                        ?.let { accumulator.initialArgumentsJson = it }
                }

                eventType.contains("output_item.added", ignoreCase = true) ||
                    eventType.contains("output_item.done", ignoreCase = true) -> {
                    val item = json.optJSONObject("item") ?: json.optJSONObject("output_item")
                    mergeResponsesOutputItem(
                        item = item,
                        textBuffer = textBuffer,
                        listener = listener,
                        allowTextFallback = textBuffer.isEmpty(),
                        toolCallAccumulators = toolCallAccumulators
                    )
                }

                eventType.contains("response.completed", ignoreCase = true) -> {
                    completedResponse = json.optJSONObject("response")
                    responseId = completedResponse
                        ?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                        ?: responseId
                }
            }
        }

        completedResponse?.let { response ->
            val outputText = response.optString("output_text")
            if (textBuffer.isEmpty() && outputText.isNotBlank()) {
                textBuffer.append(outputText)
            }

            val outputItems = response.optJSONArray("output")
            if (outputItems != null) {
                for (index in 0 until outputItems.length()) {
                    mergeResponsesOutputItem(
                        item = outputItems.optJSONObject(index),
                        textBuffer = textBuffer,
                        listener = null,
                        allowTextFallback = textBuffer.isEmpty(),
                        toolCallAccumulators = toolCallAccumulators
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

    private fun ToolCallStreamAccumulator.adoptResponsesIdentifiers(
        callId: String,
        itemId: String
    ) {
        if (callId.isNotBlank()) {
            id = callId
        }
        if (itemId.isNotBlank()) {
            responseItemId = itemId
        }
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
            "message" -> {
                if (!allowTextFallback) {
                    return
                }
                val fallbackText = extractText(item.opt("content"))
                if (fallbackText.isNotBlank() && textBuffer.isEmpty()) {
                    textBuffer.append(fallbackText)
                    listener?.onTextDelta(fallbackText)
                }
            }

            "function_call" -> {
                val callId = item.optString("call_id").trim()
                val itemId = item.optString("id").trim()
                val accumulator = resolveResponsesToolAccumulator(
                    toolCallAccumulators = toolCallAccumulators,
                    callId = callId,
                    itemId = itemId,
                    fallbackId = callId
                        .ifBlank { itemId }
                        .ifBlank { "response_call_${toolCallAccumulators.size + 1}" }
                )
                accumulator.adoptResponsesIdentifiers(callId = callId, itemId = itemId)
                accumulator.name = item.optString("name").ifBlank { accumulator.name }
                item.optString("arguments")
                    .takeIf { it.isNotBlank() }
                    ?.let { accumulator.initialArgumentsJson = it }
            }
        }
    }

    private fun parseNativeClaudeStream(
        responseBody: ResponseBody,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val textBuffer = StringBuilder()
        val contentBlocks = linkedMapOf<Int, ClaudeContentBlockAccumulator>()

        collectSseEvents(responseBody) { eventName, payload ->
            if (payload == "[DONE]") {
                return@collectSseEvents
            }

            val json = payload.toJsonObjectOrNull() ?: return@collectSseEvents
            val type = json.optString("type").ifBlank { eventName.orEmpty() }
            when {
                type.contains("error", ignoreCase = true) -> {
                    throw IllegalStateException(
                        extractEmbeddedStreamError(type, json)
                            ?: "Custom provider Claude stream failed"
                    )
                }

                type.contains("content_block_start", ignoreCase = true) -> {
                    val index = json.optInt("index", contentBlocks.size)
                    val contentBlock = json.optJSONObject("content_block") ?: return@collectSseEvents
                    val blockType = contentBlock.optString("type")
                    val accumulator = ClaudeContentBlockAccumulator(type = blockType)
                    when (blockType) {
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
                            accumulator.initialInputJson = contentBlock.opt("input")
                                ?.takeIf { it != JSONObject.NULL }
                                ?.toString()
                        }
                    }
                    contentBlocks[index] = accumulator
                }

                type.contains("content_block_delta", ignoreCase = true) -> {
                    val index = json.optInt("index", -1)
                    val accumulator = contentBlocks[index] ?: return@collectSseEvents
                    val delta = json.optJSONObject("delta") ?: return@collectSseEvents
                    val deltaType = delta.optString("type")
                    when {
                        deltaType.contains("text", ignoreCase = true) -> {
                            val textDelta = delta.optString("text")
                            if (textDelta.isNotEmpty()) {
                                accumulator.textBuilder.append(textDelta)
                                textBuffer.append(textDelta)
                                listener.onTextDelta(textDelta)
                            }
                        }

                        deltaType.contains("input_json", ignoreCase = true) -> {
                            delta.optString("partial_json")
                                .takeIf { it.isNotEmpty() }
                                ?.let(accumulator.inputJsonBuilder::append)
                        }
                    }
                }

                type.contains("message_stop", ignoreCase = true) -> {
                    if (textBuffer.isEmpty()) {
                        json.optJSONObject("message")
                            ?.optJSONArray("content")
                            ?.let(::extractStreamingFragment)
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

    private fun parseNativeJsonResponse(
        rawBody: String,
        listener: AIAgentStreamListener
    ): NativeProviderResponse {
        val jsonResponse = JSONObject(rawBody)
        val parsed = when (apiType) {
            CustomProviderApiType.OPENAI_CHAT -> parseNativeOpenAIChatJson(jsonResponse)
            CustomProviderApiType.OPENAI_RESPONSES -> parseNativeOpenAIResponsesJson(jsonResponse)
            CustomProviderApiType.CLAUDE_MESSAGES -> parseNativeClaudeJson(jsonResponse)
        }
        if (parsed.assistantText.isNotBlank()) {
            listener.onTextDelta(parsed.assistantText)
        }
        return parsed
    }

    private fun parseNativeOpenAIChatJson(jsonResponse: JSONObject): NativeProviderResponse {
        val choice = jsonResponse.optJSONArray("choices")
            ?.optJSONObject(0)
            ?: return NativeProviderResponse(assistantText = "", toolCalls = emptyList())
        val message = choice.optJSONObject("message")
        return NativeProviderResponse(
            assistantText = extractText(message?.opt("content")),
            toolCalls = buildRecordedChatToolCalls(message?.optJSONArray("tool_calls"))
        )
    }

    private fun parseNativeOpenAIResponsesJson(jsonResponse: JSONObject): NativeProviderResponse {
        return NativeProviderResponse(
            assistantText = runCatching { parseResponsesResponse(jsonResponse) }.getOrDefault(""),
            toolCalls = buildRecordedResponsesToolCalls(jsonResponse),
            responseId = jsonResponse.optString("id").takeIf { it.isNotBlank() }
        )
    }

    private fun parseNativeClaudeJson(jsonResponse: JSONObject): NativeProviderResponse {
        val toolCalls = mutableListOf<RecordedToolCall>()
        val content = jsonResponse.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") != "tool_use") {
                    continue
                }
                toolCalls += RecordedToolCall(
                    id = block.optString("id").ifBlank { nextToolCallId() },
                    name = block.optString("name"),
                    argumentsJson = normalizeJsonObjectString(
                        block.opt("input")?.toString().orEmpty().ifBlank { "{}" }
                    )
                )
            }
        }
        return NativeProviderResponse(
            assistantText = runCatching { parseClaudeResponse(jsonResponse) }.getOrDefault(""),
            toolCalls = toolCalls
        )
    }

    private fun buildRecordedChatToolCalls(toolCalls: JSONArray?): List<RecordedToolCall> {
        if (toolCalls == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until toolCalls.length()) {
                val toolJson = toolCalls.optJSONObject(index) ?: continue
                val function = toolJson.optJSONObject("function")
                val name = function?.optString("name").orEmpty()
                if (name.isBlank()) {
                    continue
                }
                add(
                    RecordedToolCall(
                        id = toolJson.optString("id").ifBlank { nextToolCallId() },
                        name = name,
                        argumentsJson = normalizeJsonObjectString(
                            function?.optString("arguments").orEmpty().ifBlank { "{}" }
                        )
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
                if (item.optString("type") != "function_call") {
                    continue
                }
                val name = item.optString("name")
                if (name.isBlank()) {
                    continue
                }
                add(
                    RecordedToolCall(
                        id = item.optString("call_id")
                            .ifBlank { item.optString("id") }
                            .ifBlank { nextToolCallId() },
                        name = name,
                        argumentsJson = normalizeJsonObjectString(
                            item.optString("arguments").ifBlank { "{}" }
                        ),
                        responseItemId = item.optString("id").trim().ifBlank { null }
                    )
                )
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
                        if (dataLines.isNotEmpty()) {
                            handler(currentEvent, dataLines.joinToString("\n"))
                        }
                        currentEvent = null
                        dataLines.clear()
                    }
                }
            }
        }

        if (dataLines.isNotEmpty()) {
            handler(currentEvent, dataLines.joinToString("\n"))
        }
    }

    private fun requestModesForCurrentProvider(): List<ProviderRequestMode> {
        return when (apiType) {
            CustomProviderApiType.OPENAI_CHAT,
            CustomProviderApiType.OPENAI_RESPONSES -> listOf(
                ProviderRequestMode.Default,
                ProviderRequestMode.CompatibilityFallback
            )
            CustomProviderApiType.CLAUDE_MESSAGES -> listOf(ProviderRequestMode.Default)
        }
    }

    private fun shouldRetryWithCompatibilityFallback(
        responseCode: Int,
        requestMode: ProviderRequestMode
    ): Boolean {
        return responseCode == 400 &&
            !requestMode.compatibilityFallback &&
            (apiType == CustomProviderApiType.OPENAI_CHAT || apiType == CustomProviderApiType.OPENAI_RESPONSES)
    }

    private fun shouldRetryWithCompatibilityFallback(
        error: Throwable,
        requestMode: ProviderRequestMode
    ): Boolean {
        if (requestMode.compatibilityFallback) {
            return false
        }
        if (apiType != CustomProviderApiType.OPENAI_CHAT &&
            apiType != CustomProviderApiType.OPENAI_RESPONSES
        ) {
            return false
        }

        val message = error.message.orEmpty()
        if (message.isBlank()) {
            return false
        }

        return message.contains("status_code=400", ignoreCase = true) ||
            message.contains("status code 400", ignoreCase = true) ||
            message.contains("invalid schema", ignoreCase = true) ||
            message.contains("no tool call found", ignoreCase = true) ||
            message.contains("function_call_output", ignoreCase = true) ||
            message.contains("previous_response_id", ignoreCase = true)
    }

    private fun shouldUseResponsesContinuation(requestMode: ProviderRequestMode): Boolean {
        return apiType == CustomProviderApiType.OPENAI_RESPONSES &&
            !requestMode.compatibilityFallback &&
            responsesContinuationEnabledForSession
    }

    private fun shouldUseStrictToolSchemas(requestMode: ProviderRequestMode): Boolean {
        return !requestMode.compatibilityFallback && strictNativeToolSchemasEnabled
    }

    private fun shouldEnableResponsesContinuationByDefault(): Boolean {
        return isOfficialOpenAIHost()
    }

    private fun shouldEnableStrictToolSchemasByDefault(): Boolean {
        return isOfficialOpenAIHost()
    }

    private fun isOfficialOpenAIHost(): Boolean {
        val host = runCatching { URI(baseUrl).host.orEmpty().lowercase() }.getOrDefault("")
        if (host.isBlank()) {
            return false
        }
        return host == "api.openai.com" || host.endsWith(".openai.azure.com")
    }

    private fun applyCompatibilityLearning(
        rawMessage: String,
        requestMode: ProviderRequestMode
    ) {
        if (requestMode.compatibilityFallback) {
            return
        }
        val message = rawMessage.trim()
        if (message.isBlank()) {
            return
        }
        if (message.contains("invalid schema", ignoreCase = true) ||
            message.contains("schema for function", ignoreCase = true) ||
            message.contains("'strict'", ignoreCase = true)
        ) {
            strictNativeToolSchemasEnabled = false
        }
        if (apiType == CustomProviderApiType.OPENAI_RESPONSES &&
            (message.contains("no tool call found", ignoreCase = true) ||
                message.contains("function_call_output", ignoreCase = true) ||
                message.contains("previous_response_id", ignoreCase = true))
        ) {
            responsesContinuationEnabledForSession = false
            resetNativeResponsesContinuationState()
        }
    }

    private fun buildOpenAICompatibleToolSchema(
        schema: JSONObject,
        strictMode: Boolean
    ): JSONObject {
        return JSONObject(schema.toString()).also { sanitizeOpenAICompatibleToolSchema(it, strictMode) }
    }

    private fun sanitizeOpenAICompatibleToolSchema(
        schema: JSONObject,
        strictMode: Boolean
    ) {
        val normalizedType = normalizeSchemaType(schema.opt("type"), strictMode)
            ?: inferSchemaType(schema)
        schema.put("type", normalizedType)

        if (normalizedType == "object") {
            val properties = schema.optJSONObject("properties") ?: JSONObject().also {
                schema.put("properties", it)
            }
            val propertyNames = mutableListOf<String>()
            val iterator = properties.keys()
            while (iterator.hasNext()) {
                val propertyName = iterator.next()
                propertyNames += propertyName
                when (val child = properties.opt(propertyName)) {
                    is JSONObject -> sanitizeOpenAICompatibleToolSchema(child, strictMode)
                    else -> properties.put(propertyName, JSONObject().put("type", "string"))
                }
            }
            schema.put("additionalProperties", false)
            schema.put("required", JSONArray(propertyNames))
        } else if (normalizedType == "array") {
            val items = schema.opt("items")
            if (items is JSONObject) {
                sanitizeOpenAICompatibleToolSchema(items, strictMode)
            } else {
                schema.put("items", JSONObject().put("type", "string"))
            }
        }
    }

    private fun normalizeSchemaType(
        typeValue: Any?,
        strictMode: Boolean
    ): String? {
        return when (typeValue) {
            is String -> typeValue
            is JSONArray -> {
                var firstNonNull: String? = null
                for (index in 0 until typeValue.length()) {
                    val candidate = typeValue.optString(index).trim()
                    if (candidate.isBlank()) {
                        continue
                    }
                    if (candidate == "null" && !strictMode) {
                        continue
                    }
                    if (candidate != "null") {
                        firstNonNull = candidate
                        if (!strictMode) {
                            break
                        }
                    }
                }
                firstNonNull ?: typeValue.optString(0).trim().ifBlank { null }
            }
            else -> null
        }
    }

    private fun inferSchemaType(schema: JSONObject): String {
        return when {
            schema.has("properties") || schema.has("required") || schema.has("additionalProperties") -> "object"
            schema.has("items") -> "array"
            schema.has("minimum") || schema.has("maximum") -> "integer"
            else -> "string"
        }
    }

    private fun isEventStream(contentType: String?): Boolean {
        return contentType?.contains("text/event-stream", ignoreCase = true) == true
    }

    private fun extractEmbeddedStreamError(eventName: String?, json: JSONObject): String? {
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

    private fun nativeToolDefinitions(): List<NativeToolDefinition> {
        return listOf(
            NativeToolDefinition(
                name = "find_files",
                description = "Find project files by path or file-name pattern before reading any content.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "pattern" to stringSchema("Glob-like file or path pattern such as *MainActivity*.kt."),
                        "max_results" to integerSchema(
                            description = "Maximum number of paths to return.",
                            nullable = true,
                            minimum = 1,
                            maximum = 120
                        )
                    ),
                    required = listOf("pattern", "max_results")
                )
            ),
            NativeToolDefinition(
                name = "search_project",
                description = "Search project text content to locate the relevant symbol, error, or string before reading a file range.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "pattern" to stringSchema("Literal text or regex pattern to search for."),
                        "file_glob" to stringSchema(
                            description = "Optional file glob such as *.kt or **/build.gradle.",
                            nullable = true
                        ),
                        "case_sensitive" to booleanSchema(
                            description = "Whether the match should be case sensitive.",
                            nullable = true
                        ),
                        "regex" to booleanSchema(
                            description = "Treat PATTERN as a regular expression.",
                            nullable = true
                        ),
                        "max_results" to integerSchema(
                            description = "Maximum number of matches to return.",
                            nullable = true,
                            minimum = 1,
                            maximum = 100
                        )
                    ),
                    required = listOf("pattern", "file_glob", "case_sensitive", "regex", "max_results")
                )
            ),
            NativeToolDefinition(
                name = "read_file_range",
                description = "Read only a focused line range from a file. Prefer 50-200 relevant lines instead of the whole file.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "file" to stringSchema("Relative file path from the project root."),
                        "start_line" to integerSchema(
                            description = "1-based start line.",
                            nullable = true,
                            minimum = 1
                        ),
                        "end_line" to integerSchema(
                            description = "1-based end line.",
                            nullable = true,
                            minimum = 1
                        )
                    ),
                    required = listOf("file", "start_line", "end_line")
                )
            ),
            NativeToolDefinition(
                name = "replace_file_range",
                description = "Apply a focused edit by replacing or inserting a line range in a file.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "file" to stringSchema("Relative file path from the project root."),
                        "start_line" to integerSchema("1-based start line for replacement or insertion.", minimum = 1),
                        "end_line" to integerSchema("1-based end line. Use START_LINE-1 to insert.", minimum = 0),
                        "content" to stringSchema("Exact replacement text for the requested range.")
                    ),
                    required = listOf("file", "start_line", "end_line", "content")
                )
            ),
            NativeToolDefinition(
                name = "build_project",
                description = "Run Gradle wrapper tasks to sync, compile, or build the project.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "tasks" to stringSchema("Space-separated Gradle tasks such as :core:app:assembleDebug."),
                        "args" to stringSchema(
                            description = "Optional extra Gradle flags such as --stacktrace --info.",
                            nullable = true
                        )
                    ),
                    required = listOf("tasks", "args")
                )
            ),
            NativeToolDefinition(
                name = "run_terminal_command",
                description = "Run one safe non-interactive Termux command without pipes, redirects, or chaining. Common supported commands include pkg, apt, git, rg, grep, sed, head, tail, cat, ls, find, curl, wget, stat, tree, du, df, ps, cp, mkdir, and touch. Gradle wrapper commands are better handled by build_project and may be rerouted there.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "command" to stringSchema("Single command to execute, for example pkg install ripgrep -y."),
                        "workdir" to stringSchema(
                            description = "Optional working directory hint such as PROJECT_ROOT or HOME.",
                            nullable = true
                        )
                    ),
                    required = listOf("command", "workdir")
                )
            )
        )
    }

    private fun objectSchema(
        properties: LinkedHashMap<String, JSONObject>,
        required: List<String> = emptyList()
    ): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject(properties as Map<*, *>))
            put("additionalProperties", false)
            put("required", JSONArray(required))
        }
    }

    private fun stringSchema(
        description: String,
        nullable: Boolean = false
    ): JSONObject {
        return JSONObject().apply {
            put("type", schemaType("string", nullable))
            put("description", description)
        }
    }

    private fun integerSchema(
        description: String,
        nullable: Boolean = false,
        minimum: Int? = null,
        maximum: Int? = null
    ): JSONObject {
        return JSONObject().apply {
            put("type", schemaType("integer", nullable))
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }
    }

    private fun booleanSchema(
        description: String,
        nullable: Boolean = false
    ): JSONObject {
        return JSONObject().apply {
            put("type", schemaType("boolean", nullable))
            put("description", description)
        }
    }

    private fun schemaType(
        primaryType: String,
        nullable: Boolean
    ): Any {
        if (!nullable) {
            return primaryType
        }
        return JSONArray()
            .put(primaryType)
            .put("null")
    }

    private fun extractText(value: Any?): String {
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

    private fun extractTextFromObject(item: JSONObject): String {
        return item.optString("text")
            .ifBlank { item.optString("output_text") }
            .ifBlank {
                val nestedText = item.optJSONObject("message")?.opt("content")
                extractText(nestedText)
            }
            .trim()
    }

    private fun throwMappedApiError(responseCode: Int, responseBody: String): Nothing {
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

    private fun readRelevantFiles(): Map<String, String> {
        val filesContent = mutableMapOf<String, String>()
        val tree = projectTreeResult?.tree ?: return filesContent

        tree.lines()
            .filter { it.isNotBlank() }
            .forEach { filePath ->
                val trimmedPath = filePath.trim()
                val file = File(trimmedPath)

                if (file.isFile &&
                    (trimmedPath.endsWith(".kt") ||
                        trimmedPath.endsWith(".java") ||
                        trimmedPath.endsWith(".xml") ||
                        trimmedPath.endsWith(".gradle") ||
                        trimmedPath.endsWith(".gradle.kts")) &&
                    !trimmedPath.contains("/build/") &&
                    !trimmedPath.contains("/.gradle/")
                ) {
                    try {
                        filesContent[trimmedPath] = file.readText()
                    } catch (_: Exception) {
                    }
                }
            }

        return filesContent
    }

    private fun isUserRequestingCorrection(message: String): Boolean {
        val correctionKeywords = listOf(
            "wrong", "not what", "mistake", "error", "incorrect",
            "that's not", "not right", "fix", "undo", "revert",
            "different", "try again", "not working"
        )
        return correctionKeywords.any { message.lowercase().contains(it) }
    }

    private fun trimConversationHistory() {
        if (conversationHistory.size > 20) {
            conversationHistory.removeAt(0)
            conversationHistory.removeAt(0)
        }
    }

    private fun autoCompactNativeConversationIfNeeded(toolExecutionEnabled: Boolean) {
        var compacted = false
        val usableBudget = nativeUsableInputBudgetTokens()
        while (estimateNativeConversationTokens(toolExecutionEnabled) > usableBudget &&
            committedToolConversation.size > minCommittedNativeTurnsToKeep
        ) {
            val removableTurnCount = committedToolConversation.size - minCommittedNativeTurnsToKeep
            val batchSize = minOf(nativeCompressionBatchSize, removableTurnCount)
            val summarized = summarizeNativeTurns(committedToolConversation.take(batchSize))
            committedToolConversation.subList(0, batchSize).clear()
            compacted = true
            if (summarized.isNotBlank()) {
                condensedNativeConversation += summarized
                while (condensedNativeConversation.size > maxCondensedNativeEntries) {
                    condensedNativeConversation.removeAt(0)
                }
            }
        }
        while (estimateNativeConversationTokens(toolExecutionEnabled) > usableBudget &&
            committedToolConversation.isNotEmpty()
        ) {
            val batchSize = minOf(nativeCompressionBatchSize, committedToolConversation.size)
            val summarized = summarizeNativeTurns(committedToolConversation.take(batchSize))
            committedToolConversation.subList(0, batchSize).clear()
            compacted = true
            if (summarized.isNotBlank()) {
                condensedNativeConversation += summarized
                while (condensedNativeConversation.size > maxCondensedNativeEntries) {
                    condensedNativeConversation.removeAt(0)
                }
            }
        }
        if (compacted) {
            resetNativeResponsesContinuationState()
        }
    }

    private fun nativeUsableInputBudgetTokens(): Int {
        val hardInputBudget = maxOf(
            1,
            minOf(defaultInputWindowTokens, defaultContextWindowTokens - defaultMaxGenerationTokens)
        )
        return maxOf(1, hardInputBudget - minOf(defaultReservedCompactionTokens, defaultMaxGenerationTokens))
    }

    private fun estimateNativeConversationTokens(toolExecutionEnabled: Boolean): Int {
        var total = 64
        total += estimateTextTokens(buildNativeSystemPrompt(toolExecutionEnabled))
        buildCondensedConversationTurn()?.let { total += estimateTurnTokens(it) }
        total += committedToolConversation.sumOf(::estimateTurnTokens)
        total += activeToolTurn.sumOf(::estimateTurnTokens)
        return total
    }

    private fun estimateTurnTokens(turn: CustomProviderTurn): Int {
        return when (turn) {
            is CustomProviderTurn.User -> estimateTextTokens(turn.text) + 12
            is CustomProviderTurn.Assistant -> {
                estimateTextTokens(turn.text) +
                    turn.toolCalls.sumOf { toolCall ->
                        estimateTextTokens(toolCall.name) +
                            estimateTextTokens(toolCall.argumentsJson) +
                            estimateTextTokens(toolCall.id) +
                            estimateTextTokens(toolCall.responseItemId.orEmpty()) +
                            18
                    } +
                    18
            }
            is CustomProviderTurn.ToolResult ->
                estimateTextTokens(turn.toolCallId) +
                    estimateTextTokens(turn.toolName) +
                    estimateTextTokens(turn.output) +
                    18
        }
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isBlank()) {
            return 0
        }
        val asciiChars = text.count { it.code in 0..127 }
        val nonAsciiChars = text.length - asciiChars
        val newlineCount = text.count { it == '\n' }
        val structuralChars = text.count { char ->
            char == '{' || char == '}' || char == '[' || char == ']' ||
                char == ':' || char == ',' || char == '"' || char == '`'
        }
        val asciiTokens = (asciiChars + 3) / 4
        val nonAsciiTokens = nonAsciiChars
        return asciiTokens + nonAsciiTokens + newlineCount + (structuralChars / 8) + 6
    }

    private fun summarizeNativeTurns(turns: List<CustomProviderTurn>): String {
        if (turns.isEmpty()) {
            return ""
        }
        return turns.joinToString(" || ") { turn ->
            when (turn) {
                is CustomProviderTurn.User ->
                    "user requested: ${turn.text.replace(Regex("\\s+"), " ").take(160)}"
                is CustomProviderTurn.Assistant -> {
                    val textSummary = turn.text.replace(Regex("\\s+"), " ").take(160)
                    val toolSummary = turn.toolCalls.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
                    buildString {
                        if (textSummary.isNotBlank()) {
                            append("assistant responded: $textSummary")
                        }
                        if (toolSummary != null) {
                            if (isNotEmpty()) {
                                append(" | ")
                            }
                            append("assistant used tools: $toolSummary")
                        }
                    }.ifBlank { "assistant used tools without extra text" }
                }
                is CustomProviderTurn.ToolResult -> {
                    val summaryLine = Regex("(?m)^SUMMARY:\\s*(.+)$")
                        .find(turn.output)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        .orEmpty()
                    buildString {
                        append("tool ")
                        append(turn.toolName)
                        append(if (turn.isError) " returned error: " else " returned: ")
                        append(summaryLine.ifBlank { turn.output.replace(Regex("\\s+"), " ").take(140) })
                    }
                }
            }
        }.take(1000)
    }

    private fun buildInitializationFingerprint(
        apiKey: String,
        normalizedBaseUrl: String,
        modelId: String,
        nextApiType: CustomProviderApiType
    ): String {
        return buildString {
            append(nextApiType.value)
            append('|')
            append(normalizedBaseUrl.trim())
            append('|')
            append(modelId.trim())
            append('|')
            append(apiKey.trim().hashCode())
        }
    }

    private fun normalizeJsonObjectString(rawJson: String): String {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) {
            return "{}"
        }

        return trimmed.toJsonObjectOrNull()?.toString() ?: trimmed
    }

    private fun parseToolArguments(rawArgumentsJson: String): Map<String, String> {
        val json = normalizeJsonObjectString(rawArgumentsJson).toJsonObjectOrNull() ?: return emptyMap()
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

    private fun buildRawToolBlock(name: String, arguments: Map<String, String>): String {
        return buildString {
            appendLine("TOOL_CALL: $name")
            arguments.forEach { (key, value) ->
                if (value.contains('\n')) {
                    appendLine("${key.uppercase()}:")
                    appendLine(value)
                } else {
                    appendLine("${key.uppercase()}: $value")
                }
            }
        }.trim()
    }
}

private data class CustomConversationMessage(
    val role: String,
    val content: String
)

private sealed interface CustomProviderTurn {
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

private fun CustomProviderTurn.incrementalKey(): String {
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

private fun CustomProviderTurn.toJson(): JSONObject {
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

private fun JSONObject.toCustomProviderTurnOrNull(): CustomProviderTurn? {
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

private data class RecordedToolCall(
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

    fun toExternalToolCall(parseArguments: (String) -> Map<String, String>): AIToolCall {
        val arguments = parseArguments(argumentsJson)
        return AIToolCall(
            callId = id,
            name = name,
            arguments = arguments,
            rawBlock = buildString {
                appendLine("TOOL_CALL: $name")
                if (arguments.isEmpty()) {
                    append(argumentsJson)
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
            }.trim(),
            rawArgumentsJson = argumentsJson
        )
    }
}

private fun JSONObject.toRecordedToolCallOrNull(): RecordedToolCall? {
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

private data class NativeProviderResponse(
    val assistantText: String,
    val toolCalls: List<RecordedToolCall>,
    val responseId: String? = null
) {
    fun toExternalResponse(): NativeToolTurnResponse {
        return NativeToolTurnResponse(
            assistantText = assistantText,
            toolCalls = toolCalls.map { toolCall ->
                toolCall.toExternalToolCall { argumentsJson ->
                    val json = normalizeJsonObjectStringStatic(argumentsJson).toJsonObjectOrNull()
                        ?: return@toExternalToolCall emptyMap()
                    val parsed = linkedMapOf<String, String>()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        parsed[key.lowercase()] = when (val value = json.opt(key)) {
                            null,
                            JSONObject.NULL -> ""
                            is JSONObject,
                            is JSONArray -> value.toString()
                            else -> value.toString()
                        }
                    }
                    parsed
                }
            }
        )
    }
}

private data class ToolCallStreamAccumulator(
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

private data class ClaudeContentBlockAccumulator(
    val type: String,
    var id: String = "",
    var name: String = "",
    var initialInputJson: String? = null,
    val textBuilder: StringBuilder = StringBuilder(),
    val inputJsonBuilder: StringBuilder = StringBuilder()
)

private data class NativeToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

private data class ProviderRequestMode(
    val compatibilityFallback: Boolean = false
) {
    companion object {
        val Default = ProviderRequestMode(compatibilityFallback = false)
        val CompatibilityFallback = ProviderRequestMode(compatibilityFallback = true)
    }
}

private fun String.toJsonObjectOrNull(): JSONObject? {
    return try {
        JSONObject(this)
    } catch (_: Exception) {
        null
    }
}

private fun normalizeJsonObjectStringStatic(rawJson: String): String {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) {
        return "{}"
    }
    return trimmed.toJsonObjectOrNull()?.toString() ?: trimmed
}
