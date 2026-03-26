package com.tom.rv2ide.fragments.assistant

import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.external.CodexCliConfig
import com.tom.rv2ide.utils.Environment
import java.io.File

internal interface AIAssistantSessionCommandHost {
    val aiAgent: AIAgentManager
    val timelineStore: AIAssistantTimelineStore
    val sessionRegistry: AIAssistantSessionRegistry

    fun projectRootPath(): String
    fun activeAssistantSession(): AIAssistantChatSession?
    fun activeTimelineSessionKey(): String?
    fun currentTimelineSessionKey(): String?
    fun totalPersistedTimelineCount(): Int
    fun pendingPromptCount(): Int
    fun isExecutionRunning(): Boolean
    fun timelineItemsSnapshot(): List<AIAssistantTimelineItem>
    fun appendTimelineItem(item: AIAssistantTimelineItem)
    fun publishState()
    fun activateAssistantSession(session: AIAssistantChatSession)
    fun flushTimelinePersistence(sessionKey: String?)
}

internal class AIAssistantSessionCommandController(
    private val host: AIAssistantSessionCommandHost
) {

    fun createNewSession(nameHint: String? = null): String? {
        val projectRoot = host.projectRootPath()
        if (projectRoot.isBlank()) {
            return null
        }
        val session = host.sessionRegistry.createSession(projectRoot, nameHint)
        host.activateAssistantSession(session)
        return session.title
    }

    fun switchSession(target: String): String? {
        val projectRoot = host.projectRootPath()
        if (projectRoot.isBlank()) {
            return null
        }
        val resolved = AIAssistantSessionCommandSupport.resolveSessionTarget(
            sessions = listAvailableSessions(),
            target = target
        ) ?: return null
        val activated = host.sessionRegistry.switchToSession(projectRoot, resolved.id) ?: return null
        host.activateAssistantSession(activated)
        return activated.title
    }

    fun showSessionList(
        query: String? = null,
        emphasizeDelete: Boolean = false
    ) {
        host.appendTimelineItem(
            AIAssistantSessionCommandSupport.buildSessionBrowserTimelineItem(
                sessions = listAvailableSessions(),
                query = query,
                emphasizeDelete = emphasizeDelete,
                activeSessionId = host.activeAssistantSession()?.id
            )
        )
        host.publishState()
    }

    fun showHistory(limit: Int = 10) {
        val normalizedLimit = limit.coerceIn(1, 50)
        val sessionKey = host.activeTimelineSessionKey() ?: host.currentTimelineSessionKey()
        if (sessionKey == null) {
            host.appendTimelineItem(
                AIAssistantStatusItem(
                    title = "History",
                    body = "No active session is loaded yet.",
                    tone = AIAssistantTone.WARNING
                )
            )
            host.publishState()
            return
        }

        host.flushTimelinePersistence(sessionKey)
        val recentEntries = host.timelineStore
            .loadLatest(sessionKey, maxOf(normalizedLimit * 8, 48))
            ?.entries
            .orEmpty()
            .map(PersistedAIAssistantTimelineEntry::item)
            .let(AIAssistantSessionCommandSupport::toConversationHistoryLines)
            .takeLast(normalizedLimit)

        host.appendTimelineItem(
            AIAssistantStatusItem(
                title = "History",
                body = if (recentEntries.isEmpty()) {
                    "No user/assistant messages have been saved in this session yet."
                } else {
                    recentEntries.joinToString(separator = "\n\n")
                },
                tone = AIAssistantTone.NEUTRAL
            )
        )
        host.publishState()
    }

    fun showStatus() {
        host.appendTimelineItem(
            AIAssistantStatusCommandSupport.buildStatusItem(
                AIAssistantStatusSnapshot(
                    providerName = host.aiAgent.getCurrentProviderName(),
                    modelName = host.aiAgent.getCurrentModelName(),
                    activeSessionTitle = host.activeAssistantSession()?.title,
                    queuedPromptCount = host.pendingPromptCount(),
                    isRunning = host.isExecutionRunning(),
                    visibleTimelineCount = host.timelineItemsSnapshot().size,
                    totalTimelineCount = host.totalPersistedTimelineCount(),
                    savedSessionCount = listAvailableSessions().size,
                    projectRootPath = host.projectRootPath().takeIf(String::isNotBlank),
                    codexSettings = runCatching { CodexCliConfig.getSettings() }.getOrNull()
                        ?.takeIf { host.aiAgent.getCurrentProviderId() == "external" }
                )
            )
        )
        host.publishState()
    }

    fun requestConversationCompression() {
        host.appendTimelineItem(
            AIAssistantStatusCommandSupport.buildCompressionItem(
                providerId = host.aiAgent.getCurrentProviderId(),
                codexSettings = runCatching { CodexCliConfig.getSettings() }.getOrNull()
                    ?.takeIf { host.aiAgent.getCurrentProviderId() == "external" }
            )
        )
        host.publishState()
    }

    fun showMemoryCommand(rawArguments: String) {
        val projectMemoryFile = File(host.projectRootPath().ifBlank { "." }, "AGENTS.md")
        val globalMemoryFile = File(File(Environment.HOME, ".codex"), "AGENTS.md")
        val item = when (val command = AIAssistantMemoryCommandSupport.parse(rawArguments)) {
            AIAssistantMemoryCommand.ShowProject -> {
                AIAssistantMemoryCommandSupport.buildShowFileItem(projectMemoryFile, isGlobal = false)
            }
            AIAssistantMemoryCommand.ShowGlobal -> {
                AIAssistantMemoryCommandSupport.buildShowFileItem(globalMemoryFile, isGlobal = true)
            }
            AIAssistantMemoryCommand.Help -> {
                AIAssistantMemoryCommandSupport.buildHelpItem()
            }
            is AIAssistantMemoryCommand.AddProject -> {
                AIAssistantMemoryCommandSupport.buildAppendFileItem(projectMemoryFile, command.text)
            }
            is AIAssistantMemoryCommand.AddGlobal -> {
                AIAssistantMemoryCommandSupport.buildAppendFileItem(globalMemoryFile, command.text)
            }
            AIAssistantMemoryCommand.Invalid -> {
                AIAssistantMemoryCommandSupport.buildUsageItem()
            }
        }
        host.appendTimelineItem(item)
        host.publishState()
    }

    fun deleteSessions(target: String): AIAssistantSessionDeleteResult {
        val projectRoot = host.projectRootPath()
        if (projectRoot.isBlank()) {
            return AIAssistantSessionDeleteResult(
                deletedCount = 0,
                activeSessionLabel = null,
                message = "No project session is active yet."
            )
        }

        val targets = AIAssistantSessionCommandSupport.resolveSessionTargets(
            sessions = listAvailableSessions(),
            target = target
        )
        if (targets.isEmpty()) {
            return AIAssistantSessionDeleteResult(
                deletedCount = 0,
                activeSessionLabel = host.activeAssistantSession()?.title,
                message = "No session matched: $target"
            )
        }

        val wasActiveDeleted = host.activeAssistantSession()?.id?.let { activeId ->
            targets.any { session -> session.id == activeId }
        } == true
        val deletion = host.sessionRegistry.deleteSessions(
            projectRoot = projectRoot,
            sessionIds = targets.map(AIAssistantChatSession::id)
        )
        deletion.deletedSessionIds.forEach(host.timelineStore::clearConversationSession)

        if (wasActiveDeleted) {
            deletion.activeSession?.let(host::activateAssistantSession)
        } else {
            host.publishState()
        }
        showSessionList(emphasizeDelete = true)
        return AIAssistantSessionDeleteResult(
            deletedCount = deletion.deletedSessionIds.size,
            activeSessionLabel = deletion.activeSession?.title ?: host.activeAssistantSession()?.title,
            message = AIAssistantSessionCommandSupport.buildDeletionMessage(
                deletedCount = deletion.deletedSessionIds.size,
                nextActiveSessionTitle = if (wasActiveDeleted) deletion.activeSession?.title else null
            )
        )
    }

    fun touchActiveSession() {
        val projectRoot = host.projectRootPath().takeIf { it.isNotBlank() } ?: return
        val sessionId = host.activeAssistantSession()?.id ?: return
        host.sessionRegistry.touchSession(projectRoot, sessionId)
    }

    fun recordUserPrompt(prompt: String) {
        val projectRoot = host.projectRootPath().takeIf { it.isNotBlank() } ?: return
        val sessionId = host.activeAssistantSession()?.id ?: return
        val summary = prompt
            .trim()
            .replace(Regex("\\s+"), " ")
            .takeIf(String::isNotBlank)
        host.sessionRegistry.recordSessionTurn(
            projectRoot = projectRoot,
            sessionId = sessionId,
            latestSummary = summary,
            messageCountDelta = 1
        )
    }

    fun recordAssistantReply() {
        val projectRoot = host.projectRootPath().takeIf { it.isNotBlank() } ?: return
        val sessionId = host.activeAssistantSession()?.id ?: return
        host.sessionRegistry.recordSessionTurn(
            projectRoot = projectRoot,
            sessionId = sessionId,
            messageCountDelta = 1
        )
    }

    private fun listAvailableSessions(): List<AIAssistantChatSession> {
        val projectRoot = host.projectRootPath()
        if (projectRoot.isBlank()) {
            return emptyList()
        }
        return host.sessionRegistry.listSessions(projectRoot)
    }

}
