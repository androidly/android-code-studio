package com.tom.rv2ide.fragments.assistant

import com.tom.rv2ide.artificial.agents.external.CodexCliSettings

internal data class AIAssistantStatusSnapshot(
    val providerName: String,
    val modelName: String,
    val activeSessionTitle: String?,
    val queuedPromptCount: Int,
    val isRunning: Boolean,
    val visibleTimelineCount: Int,
    val totalTimelineCount: Int,
    val savedSessionCount: Int,
    val projectRootPath: String?,
    val codexSettings: CodexCliSettings?
)

internal object AIAssistantStatusCommandSupport {
    fun buildStatusItem(snapshot: AIAssistantStatusSnapshot): AIAssistantStatusItem {
        return AIAssistantStatusItem(
            title = "Status",
            body = buildString {
                appendLine("Provider: ${snapshot.providerName}")
                appendLine("Model: ${snapshot.modelName}")
                appendLine("Session: ${snapshot.activeSessionTitle.orEmpty().ifBlank { "None" }}")
                appendLine("Queued: ${snapshot.queuedPromptCount}")
                appendLine("Running: ${if (snapshot.isRunning) "yes" else "no"}")
                appendLine("Timeline: ${snapshot.visibleTimelineCount}/${snapshot.totalTimelineCount}")
                appendLine("Saved sessions: ${snapshot.savedSessionCount}")
                snapshot.projectRootPath
                    ?.takeIf(String::isNotBlank)
                    ?.let { root -> appendLine("Project: $root") }
                snapshot.codexSettings
                    ?.takeIf(CodexCliSettings::isValid)
                    ?.let { settings -> append("Codex: ${settings.summaryText()}") }
            }.trim(),
            tone = AIAssistantTone.NEUTRAL
        )
    }

    fun buildCompressionItem(
        providerId: String,
        codexSettings: CodexCliSettings?
    ): AIAssistantStatusItem {
        val message = when (providerId) {
            "custom" -> "The custom provider already auto-compacts old native turns when the context budget is exceeded."
            "external" -> {
                if (codexSettings?.isValid == true) {
                    "Managed Codex already has auto-compact configured at ${codexSettings.autoCompactTokenLimit} tokens in ~/.codex/config.toml. A manual /compact bridge action is not exposed yet."
                } else {
                    "Codex auto-compact is not configured yet. Open Codex settings first."
                }
            }
            else -> "This provider does not expose a native manual compact command in the current bridge. Use /new if you need a clean session."
        }
        return AIAssistantStatusItem(
            title = "Compress",
            body = message,
            tone = AIAssistantTone.NEUTRAL
        )
    }
}
