package com.tom.rv2ide.fragments.assistant

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class AIAssistantChatSession(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val summary: String? = null,
    val messageCount: Int = 0
)

internal data class AIAssistantSessionDeletionResult(
    val deletedSessionIds: List<String>,
    val activeSession: AIAssistantChatSession?
)

internal class AIAssistantSessionRegistry(context: Context) {

    private val registryFile = File(context.filesDir, "ai_assistant_sessions.json")
    private val lock = Any()

    fun getOrCreateActive(projectRoot: String): AIAssistantChatSession {
        synchronized(lock) {
            val registry = loadRegistry()
            val projectState = registry.projects.getOrPut(normalizeProjectRoot(projectRoot)) {
                MutableProjectSessionState()
            }
            projectState.findActive()?.let { activeSession ->
                return activeSession.toImmutable()
            }

            val now = System.currentTimeMillis()
            val createdSession = projectState.createSession(
                title = "Session ${projectState.sessions.size + 1}",
                timestamp = now
            )
            saveRegistry(registry)
            return createdSession.toImmutable()
        }
    }

    fun createSession(projectRoot: String, titleHint: String?): AIAssistantChatSession {
        synchronized(lock) {
            val registry = loadRegistry()
            val projectState = registry.projects.getOrPut(normalizeProjectRoot(projectRoot)) {
                MutableProjectSessionState()
            }
            val now = System.currentTimeMillis()
            val createdSession = projectState.createSession(
                title = titleHint
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: "Session ${projectState.sessions.size + 1}",
                timestamp = now
            )
            saveRegistry(registry)
            return createdSession.toImmutable()
        }
    }

    fun listSessions(projectRoot: String): List<AIAssistantChatSession> {
        synchronized(lock) {
            val projectState = loadRegistry().projects[normalizeProjectRoot(projectRoot)] ?: return emptyList()
            return projectState.sessions
                .sortedWith(
                    compareByDescending<MutableAIAssistantChatSession> { it.updatedAtMillis }
                        .thenByDescending { it.createdAtMillis }
                )
                .map(MutableAIAssistantChatSession::toImmutable)
        }
    }

    fun switchToSession(projectRoot: String, sessionId: String): AIAssistantChatSession? {
        synchronized(lock) {
            val registry = loadRegistry()
            val projectState = registry.projects[normalizeProjectRoot(projectRoot)] ?: return null
            val now = System.currentTimeMillis()
            val targetSession = projectState.sessions.firstOrNull { it.id == sessionId } ?: return null
            targetSession.updatedAtMillis = now
            projectState.activeSessionId = sessionId
            saveRegistry(registry)
            return targetSession.toImmutable()
        }
    }

    fun touchSession(projectRoot: String, sessionId: String) {
        synchronized(lock) {
            val registry = loadRegistry()
            val projectState = registry.projects[normalizeProjectRoot(projectRoot)] ?: return
            val targetSession = projectState.sessions.firstOrNull { it.id == sessionId } ?: return
            targetSession.updatedAtMillis = System.currentTimeMillis()
            saveRegistry(registry)
        }
    }

    fun recordSessionTurn(
        projectRoot: String,
        sessionId: String,
        latestSummary: String? = null,
        messageCountDelta: Int = 0
    ) {
        synchronized(lock) {
            val registry = loadRegistry()
            val projectState = registry.projects[normalizeProjectRoot(projectRoot)] ?: return
            val targetSession = projectState.sessions.firstOrNull { it.id == sessionId } ?: return
            targetSession.updatedAtMillis = System.currentTimeMillis()
            latestSummary
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { targetSession.summary = it.take(MAX_SESSION_SUMMARY_CHARS) }
            if (messageCountDelta != 0) {
                targetSession.messageCount = (targetSession.messageCount + messageCountDelta).coerceAtLeast(0)
            }
            saveRegistry(registry)
        }
    }

    fun deleteSessions(
        projectRoot: String,
        sessionIds: Collection<String>
    ): AIAssistantSessionDeletionResult {
        synchronized(lock) {
            val normalizedSessionIds = sessionIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            if (normalizedSessionIds.isEmpty()) {
                return AIAssistantSessionDeletionResult(
                    deletedSessionIds = emptyList(),
                    activeSession = loadRegistry()
                        .projects[normalizeProjectRoot(projectRoot)]
                        ?.findActive()
                        ?.toImmutable()
                )
            }

            val registry = loadRegistry()
            val projectState = registry.projects[normalizeProjectRoot(projectRoot)]
                ?: return AIAssistantSessionDeletionResult(emptyList(), null)
            val deletedSessions = projectState.sessions
                .filter { it.id in normalizedSessionIds }
                .map(MutableAIAssistantChatSession::toImmutable)
            if (deletedSessions.isEmpty()) {
                return AIAssistantSessionDeletionResult(
                    deletedSessionIds = emptyList(),
                    activeSession = projectState.findActive()?.toImmutable()
                )
            }

            projectState.sessions.removeAll { session -> session.id in normalizedSessionIds }
            val nextActiveSession = when {
                projectState.sessions.isEmpty() -> {
                    val now = System.currentTimeMillis()
                    projectState.createSession(
                        title = "Session 1",
                        timestamp = now
                    )
                }
                projectState.activeSessionId in normalizedSessionIds -> {
                    projectState.sessions.maxWithOrNull(
                        compareBy<MutableAIAssistantChatSession> { it.updatedAtMillis }
                            .thenBy { it.createdAtMillis }
                    )?.also { nextSession ->
                        projectState.activeSessionId = nextSession.id
                    }
                }
                else -> projectState.findActive()
            }

            saveRegistry(registry)
            return AIAssistantSessionDeletionResult(
                deletedSessionIds = deletedSessions.map(AIAssistantChatSession::id),
                activeSession = nextActiveSession?.toImmutable()
            )
        }
    }

    private fun normalizeProjectRoot(projectRoot: String): String {
        return projectRoot.trim().ifBlank { "__global__" }
    }

    private fun loadRegistry(): MutableAIAssistantSessionRegistryState {
        if (!registryFile.exists()) {
            return MutableAIAssistantSessionRegistryState()
        }

        return runCatching {
            val rootJson = JSONObject(registryFile.readText())
            val projectsJson = rootJson.optJSONObject("projects") ?: JSONObject()
            val projects = linkedMapOf<String, MutableProjectSessionState>()
            val keys = projectsJson.keys()
            while (keys.hasNext()) {
                val projectKey = keys.next()
                val projectJson = projectsJson.optJSONObject(projectKey) ?: continue
                val activeSessionId = projectJson.optString("activeSessionId").trim().ifBlank { null }
                val sessions = projectJson.optJSONArray("sessions").toMutableSessions()
                projects[projectKey] = MutableProjectSessionState(
                    activeSessionId = activeSessionId,
                    sessions = sessions
                )
            }
            MutableAIAssistantSessionRegistryState(projects)
        }.getOrDefault(MutableAIAssistantSessionRegistryState())
    }

    private fun saveRegistry(registry: MutableAIAssistantSessionRegistryState) {
        runCatching {
            registryFile.parentFile?.mkdirs()
            val projectsJson = JSONObject()
            registry.projects.forEach { (projectKey, projectState) ->
                projectsJson.put(
                    projectKey,
                    JSONObject().apply {
                        put("activeSessionId", projectState.activeSessionId.orEmpty())
                        put(
                            "sessions",
                            JSONArray().apply {
                                projectState.sessions.forEach { session ->
                                    put(
                                        JSONObject().apply {
                                            put("id", session.id)
                                            put("title", session.title)
                                            put("createdAtMillis", session.createdAtMillis)
                                            put("updatedAtMillis", session.updatedAtMillis)
                                            put("summary", session.summary.orEmpty())
                                            put("messageCount", session.messageCount)
                                        }
                                    )
                                }
                            }
                        )
                    }
                )
            }
            registryFile.writeText(
                JSONObject()
                    .put("projects", projectsJson)
                    .toString()
            )
        }
    }
}

private data class MutableAIAssistantSessionRegistryState(
    val projects: LinkedHashMap<String, MutableProjectSessionState> = linkedMapOf()
)

private data class MutableProjectSessionState(
    var activeSessionId: String? = null,
    val sessions: MutableList<MutableAIAssistantChatSession> = mutableListOf()
) {
    fun findActive(): MutableAIAssistantChatSession? {
        return sessions.firstOrNull { it.id == activeSessionId }
    }

    fun createSession(title: String, timestamp: Long): MutableAIAssistantChatSession {
        val createdSession = MutableAIAssistantChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAtMillis = timestamp,
            updatedAtMillis = timestamp
        )
        sessions += createdSession
        activeSessionId = createdSession.id
        return createdSession
    }
}

private data class MutableAIAssistantChatSession(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    var updatedAtMillis: Long,
    var summary: String? = null,
    var messageCount: Int = 0
) {
    fun toImmutable(): AIAssistantChatSession {
        return AIAssistantChatSession(
            id = id,
            title = title,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            summary = summary,
            messageCount = messageCount
        )
    }
}

private fun JSONArray?.toMutableSessions(): MutableList<MutableAIAssistantChatSession> {
    if (this == null) {
        return mutableListOf()
    }

    return buildList {
        for (index in 0 until length()) {
            val sessionJson = optJSONObject(index) ?: continue
            val id = sessionJson.optString("id").trim()
            val title = sessionJson.optString("title").trim()
            if (id.isBlank() || title.isBlank()) {
                continue
            }
            add(
                MutableAIAssistantChatSession(
                    id = id,
                    title = title,
                    createdAtMillis = sessionJson.optLong("createdAtMillis", 0L),
                    updatedAtMillis = sessionJson.optLong(
                        "updatedAtMillis",
                        sessionJson.optLong("createdAtMillis", 0L)
                    ),
                    summary = sessionJson.optString("summary").trim().ifBlank { null },
                    messageCount = sessionJson.optInt("messageCount", 0).coerceAtLeast(0)
                )
            )
        }
    }.toMutableList()
}

private const val MAX_SESSION_SUMMARY_CHARS = 280
