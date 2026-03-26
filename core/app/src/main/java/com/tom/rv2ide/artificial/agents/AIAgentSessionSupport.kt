package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import java.io.File

internal const val AI_AGENT_SESSION_TURN_LIMIT = 12
internal const val AI_AGENT_SESSION_TEXT_CHAR_LIMIT = 1200

internal class AIAgentSessionSupport(
    private val sessionPersistenceStore: AIAgentSessionStore,
    private val projectRootProvider: () -> File?,
    private val conversationSessionIdProvider: () -> String,
    private val providerSessionIdentityProvider: () -> String,
    private val persistentConversationAgentProvider: () -> PersistentConversationAgent?,
    private val logTag: String,
    private val maxSessionTurns: Int = AI_AGENT_SESSION_TURN_LIMIT,
    private val maxSessionTextChars: Int = AI_AGENT_SESSION_TEXT_CHAR_LIMIT
) {

    companion object {
        private val sessionStore = LinkedHashMap<String, MutableList<AgentSessionTurn>>()
    }

    private var restoredPersistentSessionKey: String? = null

    fun currentSessionKey(): String {
        val projectKey = projectRootProvider()?.absolutePath ?: "__global__"
        return "$projectKey|session:${conversationSessionIdProvider()}|${providerSessionIdentityProvider()}"
    }

    fun resetRestoredState() {
        restoredPersistentSessionKey = null
    }

    fun clearCurrentSession() {
        val sessionKey = currentSessionKey()
        currentSessionTurns().clear()
        sessionPersistenceStore.clear(sessionKey)
        restoredPersistentSessionKey = sessionKey
    }

    fun recordSessionTurn(
        role: AgentSessionRole,
        content: String
    ) {
        val normalized = content.trim()
        if (normalized.isBlank()) {
            return
        }

        val sessionTurns = currentSessionTurns()
        sessionTurns += AgentSessionTurn(
            role = role,
            content = normalized.take(maxSessionTextChars)
        )
        while (sessionTurns.size > maxSessionTurns) {
            sessionTurns.removeAt(0)
        }
        persistCurrentSessionState()
    }

    fun buildSessionContext(): String {
        val turns = currentSessionTurns().takeLast(maxSessionTurns)
        if (turns.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("=== SESSION MEMORY ===")
            appendLine("Continue the same coding task unless the user clearly changes direction.")
            turns.forEachIndexed { index, turn ->
                append(index + 1)
                append(". ")
                append(turn.role.label)
                append(": ")
                appendLine(turn.content)
            }
        }.trim()
    }

    fun restoreCurrentSessionState(force: Boolean = false) {
        val sessionKey = currentSessionKey()
        if (!force && restoredPersistentSessionKey == sessionKey) {
            return
        }

        val persisted = sessionPersistenceStore.load(sessionKey)
        val restoredTurns = when {
            persisted != null -> persisted.sessionTurns.mapNotNull(::restoreAgentSessionTurn)
            sessionStore.containsKey(sessionKey) -> sessionStore[sessionKey].orEmpty()
            else -> emptyList()
        }
        sessionStore[sessionKey] = restoredTurns.toMutableList()

        persistentConversationAgentProvider()?.let { agent ->
            val serializedState = persisted?.agentState?.trim().orEmpty()
            if (serializedState.isNotBlank()) {
                runCatching {
                    agent.importPersistentConversationState(serializedState)
                }.onFailure { error ->
                    android.util.Log.w(
                        logTag,
                        "Failed to restore persistent conversation state for $sessionKey",
                        error
                    )
                    agent.clearConversation()
                    sessionStore[sessionKey] = mutableListOf()
                    sessionPersistenceStore.clear(sessionKey)
                }
            } else if (force || restoredPersistentSessionKey != sessionKey) {
                agent.clearConversation()
            }
        }

        restoredPersistentSessionKey = sessionKey
    }

    fun persistCurrentSessionState() {
        val sessionKey = currentSessionKey()
        val turns = currentSessionTurns().map { turn ->
            PersistedAgentSessionTurn(
                role = turn.role.label,
                content = turn.content
            )
        }
        val agentState = persistentConversationAgentProvider()
            ?.exportPersistentConversationState()
            ?.takeIf { it.isNotBlank() }
        sessionPersistenceStore.save(
            sessionKey = sessionKey,
            sessionTurns = turns,
            agentState = agentState
        )
        restoredPersistentSessionKey = sessionKey
    }

    fun summarizeAssistantResponse(
        response: String,
        summary: AIAgentManager.ModificationSummary?
    ): String {
        if (response.contains("FILE_TO_MODIFY:")) {
            return buildString {
                append("Prepared file changes")
                summary?.let {
                    append(": ${it.successfulFiles}/${it.totalFiles} files applied")
                    if (it.failedFiles > 0) {
                        append(", ${it.failedFiles} failed")
                    }
                }
            }
        }

        return response.trim().take(maxSessionTextChars)
    }

    fun summarizeToolResult(toolResult: AIToolExecutionResult): String {
        return buildString {
            append(toolResult.toolName)
            append(": ")
            append(toolResult.summary)
            toolResult.exitCode?.let { append(" (exit=$it)") }
            toolResult.executedCommand?.takeIf { it.isNotBlank() }?.let {
                append(" | cmd=")
                append(it.take(240))
            }
        }.take(maxSessionTextChars)
    }

    fun summarizeModificationResults(results: List<AIAgentManager.ModificationResult>): String {
        if (results.isEmpty()) {
            return ""
        }

        val fileSummary = results.joinToString(", ") { result ->
            val action = if (result.isNewFile) "created" else "updated"
            "${File(result.filePath).name} ($action)"
        }
        return "Changed files: $fileSummary".take(maxSessionTextChars)
    }

    private fun currentSessionTurns(): MutableList<AgentSessionTurn> {
        val key = currentSessionKey()
        return sessionStore.getOrPut(key) { mutableListOf() }
    }
}

internal enum class AgentSessionRole(val label: String) {
    USER("USER"),
    ASSISTANT("ASSISTANT"),
    TOOL("TOOL"),
    CHANGE("CHANGE");

    companion object {
        fun fromLabel(label: String): AgentSessionRole? {
            return values().firstOrNull { it.label == label }
        }
    }
}

internal data class AgentSessionTurn(
    val role: AgentSessionRole,
    val content: String
)

internal fun restoreAgentSessionTurn(turn: PersistedAgentSessionTurn): AgentSessionTurn? {
    val role = AgentSessionRole.fromLabel(turn.role) ?: return null
    return AgentSessionTurn(
        role = role,
        content = turn.content
    )
}
