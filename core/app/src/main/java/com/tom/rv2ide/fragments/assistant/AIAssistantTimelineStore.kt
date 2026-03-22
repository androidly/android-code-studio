package com.tom.rv2ide.fragments.assistant

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class AIAssistantTimelineStore(context: Context) {
    private val rootDirectory = File(context.filesDir, "ai-assistant-timelines")

    fun load(sessionKey: String): PersistedAIAssistantTimeline? {
        val file = fileForSessionKey(sessionKey)
        if (!file.exists()) {
            return null
        }

        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optString("sessionKey") != sessionKey) {
                return@runCatching null
            }
            PersistedAIAssistantTimeline(
                promptDraft = json.optString("promptDraft"),
                timelineItems = json.optJSONArray("timelineItems").toTimelineItems()
            )
        }.getOrNull()
    }

    fun save(
        sessionKey: String,
        promptDraft: String,
        timelineItems: List<AIAssistantTimelineItem>
    ) {
        runCatching {
            if (!rootDirectory.exists()) {
                rootDirectory.mkdirs()
            }
            val payload = JSONObject().apply {
                put("sessionKey", sessionKey)
                put("promptDraft", promptDraft)
                put(
                    "timelineItems",
                    JSONArray().apply {
                        timelineItems.forEach { item ->
                            put(item.toJson())
                        }
                    }
                )
            }
            fileForSessionKey(sessionKey).writeText(payload.toString())
        }
    }

    fun clear(sessionKey: String) {
        runCatching {
            val file = fileForSessionKey(sessionKey)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun fileForSessionKey(sessionKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionKey.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(rootDirectory, "$digest.json")
    }
}

internal data class PersistedAIAssistantTimeline(
    val promptDraft: String,
    val timelineItems: List<AIAssistantTimelineItem>
)

private fun JSONArray?.toTimelineItems(): List<AIAssistantTimelineItem> {
    if (this == null) {
        return emptyList()
    }

    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)
                ?.toTimelineItemOrNull()
                ?.let { item ->
                    observeTimelineIds(item)
                    add(item)
                }
        }
    }
}

private fun JSONObject.toTimelineItemOrNull(): AIAssistantTimelineItem? {
    return when (optString("type")) {
        "history-divider" -> null
        "welcome" -> AIAssistantWelcomeItem(
            title = optString("title"),
            body = optString("body"),
            id = optLong("id")
        )
        "user" -> AIAssistantUserItem(
            prompt = optString("prompt"),
            id = optLong("id")
        )
        "response" -> AIAssistantResponseItem(
            response = optString("response"),
            id = optLong("id")
        )
        "stream" -> AIAssistantStreamingResponseItem(
            header = optString("header", "Working"),
            placeholder = optString("placeholder").ifBlank { null },
            response = optString("response"),
            status = optString("status").ifBlank { null },
            attachments = optJSONArray("attachments").toToolItems(),
            isWorking = optBoolean("isWorking", true),
            isStreaming = optBoolean("isStreaming", true),
            id = optLong("id")
        )
        "status" -> AIAssistantStatusItem(
            title = optString("title"),
            body = optString("body").ifBlank { null },
            tone = runCatching { AIAssistantTone.valueOf(optString("tone")) }
                .getOrDefault(AIAssistantTone.NEUTRAL),
            id = optLong("id")
        )
        "diff" -> {
            val filePath = optString("filePath").trim()
            if (filePath.isBlank()) {
                null
            } else {
                AIAssistantDiffItem(
                    filePath = filePath,
                    changeLabel = optString("changeLabel"),
                    preview = optJSONObject("preview")?.toDiffPreview()
                        ?: AIAssistantDiffPreview(0, 0, emptyList(), emptyList()),
                    id = optLong("id")
                )
            }
        }
        else -> null
    }
}

private fun JSONArray?.toToolItems(): List<AIAssistantToolItem> {
    if (this == null) {
        return emptyList()
    }

    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index)?.toToolItemOrNull() ?: continue
            observeAIAssistantTimelineItemId(item.id)
            add(item)
        }
    }
}

private fun JSONObject.toToolItemOrNull(): AIAssistantToolItem? {
    val title = optString("title").trim()
    val summary = optString("summary").trim()
    if (title.isBlank() || summary.isBlank()) {
        return null
    }
    return AIAssistantToolItem(
        title = title,
        summary = summary,
        stage = runCatching { AIAssistantToolStage.valueOf(optString("stage")) }
            .getOrDefault(AIAssistantToolStage.PLANNED),
        stageTrail = optString("stageTrail"),
        command = optString("command").ifBlank { null },
        workingDirectory = optString("workingDirectory").ifBlank { null },
        outputPreview = optString("outputPreview").ifBlank { null },
        id = optLong("id")
    )
}

private fun JSONObject.toDiffPreview(): AIAssistantDiffPreview {
    return AIAssistantDiffPreview(
        addedCount = optInt("addedCount"),
        removedCount = optInt("removedCount"),
        addedLines = optJSONArray("addedLines").toStringList(),
        removedLines = optJSONArray("removedLines").toStringList()
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }
}

private fun observeTimelineIds(item: AIAssistantTimelineItem) {
    observeAIAssistantTimelineItemId(item.id)
    if (item is AIAssistantStreamingResponseItem) {
        item.attachments.forEach { attachment ->
            observeAIAssistantTimelineItemId(attachment.id)
        }
    }
}

private fun AIAssistantTimelineItem.toJson(): JSONObject {
    return when (this) {
        is AIAssistantHistoryDividerItem -> JSONObject().apply {
            put("type", "history-divider")
            put("hiddenCount", hiddenCount)
            put("visibleCount", visibleCount)
            put("totalCount", totalCount)
            put("id", id)
        }
        is AIAssistantWelcomeItem -> JSONObject().apply {
            put("type", "welcome")
            put("title", title)
            put("body", body)
            put("id", id)
        }
        is AIAssistantUserItem -> JSONObject().apply {
            put("type", "user")
            put("prompt", prompt)
            put("id", id)
        }
        is AIAssistantResponseItem -> JSONObject().apply {
            put("type", "response")
            put("response", response)
            put("id", id)
        }
        is AIAssistantStreamingResponseItem -> JSONObject().apply {
            put("type", "stream")
            put("header", header)
            put("placeholder", placeholder.orEmpty())
            put("response", response)
            put("status", status.orEmpty())
            put("isWorking", isWorking)
            put("isStreaming", isStreaming)
            put("id", id)
            put(
                "attachments",
                JSONArray().apply {
                    attachments.forEach { attachment ->
                        put(attachment.toJson())
                    }
                }
            )
        }
        is AIAssistantStatusItem -> JSONObject().apply {
            put("type", "status")
            put("title", title)
            put("body", body.orEmpty())
            put("tone", tone.name)
            put("id", id)
        }
        is AIAssistantDiffItem -> JSONObject().apply {
            put("type", "diff")
            put("filePath", filePath)
            put("changeLabel", changeLabel)
            put("id", id)
            put(
                "preview",
                JSONObject().apply {
                    put("addedCount", preview.addedCount)
                    put("removedCount", preview.removedCount)
                    put("addedLines", JSONArray(preview.addedLines))
                    put("removedLines", JSONArray(preview.removedLines))
                }
            )
        }
    }
}

private fun AIAssistantToolItem.toJson(): JSONObject {
    return JSONObject().apply {
        put("title", title)
        put("summary", summary)
        put("stage", stage.name)
        put("stageTrail", stageTrail)
        put("command", command.orEmpty())
        put("workingDirectory", workingDirectory.orEmpty())
        put("outputPreview", outputPreview.orEmpty())
        put("id", id)
    }
}
