package com.tom.rv2ide.fragments.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object AIAssistantSessionCommandSupport {

    fun buildSessionBrowserTimelineItem(
        sessions: List<AIAssistantChatSession>,
        query: String?,
        emphasizeDelete: Boolean,
        activeSessionId: String?
    ): AIAssistantTimelineItem {
        val normalizedQuery = query?.trim().orEmpty()
        val filteredSessions = filterSessions(sessions, normalizedQuery)
        if (filteredSessions.isEmpty()) {
            return AIAssistantStatusItem(
                title = if (normalizedQuery.isBlank()) "Sessions" else "Search",
                body = if (normalizedQuery.isBlank()) {
                    "No saved sessions yet. Use /new to start a fresh chat."
                } else {
                    "No sessions matched \"$normalizedQuery\"."
                },
                tone = AIAssistantTone.NEUTRAL
            )
        }

        return AIAssistantSessionBrowserItem(
            title = if (normalizedQuery.isBlank()) {
                "Sessions · ${filteredSessions.size}"
            } else {
                "Search · ${filteredSessions.size} hit" +
                    if (filteredSessions.size == 1) "" else "s"
            },
            subtitle = buildString {
                if (normalizedQuery.isNotBlank()) {
                    append("Keyword: ")
                    append(normalizedQuery)
                } else {
                    append("Current session is pinned. ")
                    append(
                        if (emphasizeDelete) {
                            "Use Delete to remove old chats."
                        } else {
                            "Switch or delete directly from the card."
                        }
                    )
                }
            },
            sessions = filteredSessions.mapIndexed { index, session ->
                session.toBrowserEntry(order = index + 1, activeSessionId = activeSessionId)
            }
        )
    }

    fun filterSessions(
        sessions: List<AIAssistantChatSession>,
        query: String
    ): List<AIAssistantChatSession> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return sessions
        }
        return sessions.filter { session ->
            session.id.startsWith(normalizedQuery, ignoreCase = true) ||
                session.title.contains(normalizedQuery, ignoreCase = true) ||
                session.summary?.contains(normalizedQuery, ignoreCase = true) == true
        }
    }

    fun resolveSessionTarget(
        sessions: List<AIAssistantChatSession>,
        target: String
    ): AIAssistantChatSession? {
        val normalizedTarget = target.trim()
        if (normalizedTarget.isBlank()) {
            return null
        }
        normalizedTarget.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { index -> sessions.getOrNull(index - 1) }
            ?.let { return it }

        return sessions.firstOrNull { session ->
            session.id.startsWith(normalizedTarget, ignoreCase = true) ||
                session.title.equals(normalizedTarget, ignoreCase = true)
        } ?: sessions.firstOrNull { session ->
            session.title.contains(normalizedTarget, ignoreCase = true) ||
                session.summary?.contains(normalizedTarget, ignoreCase = true) == true
        }
    }

    fun resolveSessionTargets(
        sessions: List<AIAssistantChatSession>,
        target: String
    ): List<AIAssistantChatSession> {
        if (sessions.isEmpty()) {
            return emptyList()
        }

        val resolved = linkedMapOf<String, AIAssistantChatSession>()
        target.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { token ->
                val rangeParts = token.split('-', limit = 2)
                if (rangeParts.size == 2) {
                    val start = rangeParts[0].trim().toIntOrNull()
                    val end = rangeParts[1].trim().toIntOrNull()
                    if (start != null && end != null) {
                        val lower = minOf(start, end)
                        val upper = maxOf(start, end)
                        for (index in lower..upper) {
                            sessions.getOrNull(index - 1)?.let { session ->
                                resolved.putIfAbsent(session.id, session)
                            }
                        }
                        return@forEach
                    }
                }
                resolveSessionTarget(sessions, token)?.let { session ->
                    resolved.putIfAbsent(session.id, session)
                }
            }
        return resolved.values.toList()
    }

    fun toConversationHistoryLines(items: List<AIAssistantTimelineItem>): List<String> {
        return items.mapNotNull { item ->
            when (item) {
                is AIAssistantUserItem -> "User\n${item.prompt.trim()}"
                is AIAssistantResponseItem -> item.response.trim()
                    .takeIf(String::isNotBlank)
                    ?.let { response -> "Assistant\n$response" }
                is AIAssistantStreamingResponseItem -> item.response.trim()
                    .takeIf(String::isNotBlank)
                    ?.let { response -> "Assistant\n$response" }
                else -> null
            }
        }
    }

    fun buildDeletionMessage(
        deletedCount: Int,
        nextActiveSessionTitle: String?
    ): String {
        return buildString {
            append("Deleted $deletedCount session")
            if (deletedCount != 1) {
                append('s')
            }
            nextActiveSessionTitle?.let { activeTitle ->
                append(" and switched to ")
                append(activeTitle)
            }
        }
    }

    private fun AIAssistantChatSession.toBrowserEntry(
        order: Int,
        activeSessionId: String?
    ): AIAssistantSessionBrowserEntry {
        val summaryText = summary?.trim()?.ifBlank { null }
        val sessionMeta = buildString {
            if (id == activeSessionId) {
                append("Current")
                append(" · ")
            }
            append(
                if (messageCount == 1) {
                    "1 msg"
                } else {
                    "${messageCount.coerceAtLeast(0)} msgs"
                }
            )
            append(" · ")
            append(formatSessionTimestamp(updatedAtMillis))
        }
        return AIAssistantSessionBrowserEntry(
            sessionId = id,
            order = order,
            title = title,
            summary = summaryText,
            meta = sessionMeta,
            isActive = id == activeSessionId
        )
    }

    private fun formatSessionTimestamp(timestampMillis: Long): String {
        if (timestampMillis <= 0L) {
            return "updated recently"
        }
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(timestampMillis))
    }
}
