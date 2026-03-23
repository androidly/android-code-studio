/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.tom.rv2ide.artificial.agents

import android.content.Context
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

interface AIAgent {
    val providerId: String
    val providerName: String
    
    fun initialize(apiKey: String, context: Context)
    fun reinitializeWithNewModel(apiKey: String, context: Context)
    fun setContext(context: Context)
    fun setProjectData(projectTreeResult: ProjectTreeResult)
    fun setConversationSessionId(sessionId: String) {}
    fun clearConversation()
    
    suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String>

    suspend fun generateCodeStreaming(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
        listener: AIAgentStreamListener
    ): Result<String> {
        val result = generateCode(prompt, context, language, projectStructure)
        result.getOrNull()?.takeIf { it.isNotEmpty() }?.let(listener::onTextDelta)
        result.getOrNull()?.let(listener::onCompleted)
        return result
    }

    fun supportsNativeToolCalls(): Boolean = false

    suspend fun generateStructuredTurn(
        request: AIAgentStructuredTurnRequest,
        listener: AIAgentStreamListener?
    ): Result<AIAgentStructuredTurnResponse> {
        return Result.failure(
            UnsupportedOperationException("Structured turns are not supported by $providerName")
        )
    }
    
    fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean)
    fun undoLastModification(): Boolean
    fun getModificationHistory(): List<ModificationAttempt>
    
    fun resetAttemptCount()
    fun incrementAttemptCount()
    fun getCurrentAttemptCount(): Int
    fun canRetry(): Boolean
    
    fun writeFile(filePath: String, content: String): FileWriteResult
    fun isInitialized(): Boolean
}

data class ModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)

fun MutableList<ModificationAttempt>.addBoundedModificationAttempt(
    attempt: ModificationAttempt,
    maxEntries: Int = 16,
    maxRetainedChars: Int = 1_500_000
) {
    add(attempt)
    while (size > maxEntries) {
        removeAt(0)
    }
    while (size > 1 && sumOf(ModificationAttempt::estimatedRetainedChars) > maxRetainedChars) {
        removeAt(0)
    }
}

private fun ModificationAttempt.estimatedRetainedChars(): Int {
    return filePath.length +
        (previousContent?.length ?: 0) +
        newContent.length +
        64
}

interface AIAgentStreamListener {
    fun onTextDelta(delta: String)

    fun onCompleted(fullResponse: String) {}

    fun onToolCallStarted(toolCall: AIToolCall) {}

    fun onToolCallOutput(toolCall: AIToolCall, chunk: String) {}

    fun onToolCallCompleted(result: AIToolExecutionResult) {}
}
