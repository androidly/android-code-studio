package com.tom.rv2ide.artificial.agents.custom

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.rules.WritingRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class CustomProviderAgent : AIAgent {

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
    private val modificationHistory = mutableListOf<ModificationAttempt>()
    private var currentAttemptCount = 0
    private val maxRetryAttempts = 3

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
        modificationHistory.clear()
        currentAttemptCount = 0
    }

    override fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean) {
        modificationHistory.add(
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
            if (!isInitialized()) {
                return@withContext Result.failure(IllegalStateException("Custom provider not initialized"))
            }

            val fullPrompt = buildPrompt(prompt, context)
            val responseText = callProvider(fullPrompt)

            if (responseText.isBlank()) {
                return@withContext Result.failure(Exception("Empty response from custom provider"))
            }

            conversationHistory.add(CustomConversationMessage("user", prompt))
            conversationHistory.add(CustomConversationMessage("assistant", responseText))
            trimConversationHistory()

            Result.success(responseText)
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

    private fun callProvider(fullPrompt: String): String {
        val key = apiKey ?: throw IllegalStateException("API key missing")
        val requestJson = when (apiType) {
            CustomProviderApiType.OPENAI_CHAT -> buildChatRequest(fullPrompt)
            CustomProviderApiType.OPENAI_RESPONSES -> buildResponsesRequest(fullPrompt)
            CustomProviderApiType.CLAUDE_MESSAGES -> buildClaudeRequest(fullPrompt)
        }

        val request = Request.Builder()
            .url(getEndpoint())
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
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

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throwMappedApiError(response.code, responseBody)
            }

            val jsonResponse = JSONObject(responseBody)
            return when (apiType) {
                CustomProviderApiType.OPENAI_CHAT -> parseChatResponse(jsonResponse)
                CustomProviderApiType.OPENAI_RESPONSES -> parseResponsesResponse(jsonResponse)
                CustomProviderApiType.CLAUDE_MESSAGES -> parseClaudeResponse(jsonResponse)
            }
        }
    }

    private fun getEndpoint(): String {
        return when (apiType) {
            CustomProviderApiType.OPENAI_CHAT -> CustomProviderConfig.chatCompletionsEndpoint(baseUrl)
            CustomProviderApiType.OPENAI_RESPONSES -> CustomProviderConfig.responsesEndpoint(baseUrl)
            CustomProviderApiType.CLAUDE_MESSAGES -> CustomProviderConfig.messagesEndpoint(baseUrl)
        }
    }

    private fun buildChatRequest(fullPrompt: String): JSONObject {
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
            put("temperature", 0.7)
            put("max_tokens", 4096)
        }
    }

    private fun buildResponsesRequest(fullPrompt: String): JSONObject {
        return JSONObject().apply {
            put("model", selectedModel)
            put("instructions", writingRules.useThis())
            put("input", fullPrompt)
            put("temperature", 0.7)
            put("max_output_tokens", 4096)
        }
    }

    private fun buildClaudeRequest(fullPrompt: String): JSONObject {
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
            put("max_tokens", 4096)
            put("temperature", 0.7)
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
}

private data class CustomConversationMessage(
    val role: String,
    val content: String
)
