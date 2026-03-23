package com.tom.rv2ide.fragments.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class AIAssistantTimelineStore(context: Context) {
    private val databaseHelper = AIAssistantTimelineDatabaseHelper(context.applicationContext)
    private val legacyRootDirectory = File(context.filesDir, "ai-assistant-timelines")

    fun loadLatest(
        sessionKey: String,
        limit: Int
    ): PersistedAIAssistantTimelineWindow? {
        migrateLegacyIfNeeded(sessionKey)
        val database = databaseHelper.readableDatabase
        val sessionExists = database.sessionExists(sessionKey)
        if (!sessionExists) {
            return null
        }

        val totalCount = database.queryTimelineCount(sessionKey)
        return PersistedAIAssistantTimelineWindow(
            promptDraft = database.queryPromptDraft(sessionKey).orEmpty(),
            totalCount = totalCount,
            nextOrderIndex = database.queryNextOrderIndex(sessionKey),
            entries = database.queryStructuredTimelineEntries(
                sessionKey = sessionKey,
                beforeOrderIndexExclusive = null,
                limit = limit
            )
        )
    }

    fun loadOlder(
        sessionKey: String,
        beforeOrderIndexExclusive: Long,
        limit: Int
    ): List<PersistedAIAssistantTimelineEntry> {
        migrateLegacyIfNeeded(sessionKey)
        return databaseHelper.readableDatabase.queryStructuredTimelineEntries(
            sessionKey = sessionKey,
            beforeOrderIndexExclusive = beforeOrderIndexExclusive,
            limit = limit
        )
    }

    fun saveChanges(
        sessionKey: String,
        promptDraft: String,
        persistPromptDraft: Boolean,
        entries: List<PersistedAIAssistantTimelineEntry>
    ): Boolean {
        if (!persistPromptDraft && entries.isEmpty()) {
            return true
        }

        return runCatching {
            val database = databaseHelper.writableDatabase
            database.beginTransaction()
            try {
                if (persistPromptDraft) {
                    database.upsertPromptDraft(sessionKey, promptDraft)
                }
                entries.forEach { entry ->
                    database.upsertTimelineEntry(sessionKey, entry)
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }.isSuccess
    }

    fun clear(sessionKey: String) {
        runCatching {
            val database = databaseHelper.writableDatabase
            database.beginTransaction()
            try {
                database.delete(
                    AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGE_PARTS,
                    "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ?",
                    arrayOf(sessionKey)
                )
                database.delete(
                    AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGES,
                    "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ?",
                    arrayOf(sessionKey)
                )
                database.delete(
                    AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_SESSIONS,
                    "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ?",
                    arrayOf(sessionKey)
                )
                if (database.hasTable(AIAssistantTimelineDatabaseHelper.LEGACY_TABLE_TIMELINE_ITEMS)) {
                    database.execSQL(
                        "DELETE FROM ${AIAssistantTimelineDatabaseHelper.LEGACY_TABLE_TIMELINE_ITEMS} WHERE ${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ?",
                        arrayOf(sessionKey)
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            legacyFileForSessionKey(sessionKey).takeIf(File::exists)?.delete()
        }
    }

    private fun migrateLegacyIfNeeded(sessionKey: String) {
        val database = databaseHelper.writableDatabase
        if (database.sessionExists(sessionKey)) {
            legacyFileForSessionKey(sessionKey).takeIf(File::exists)?.delete()
            return
        }

        val legacyTimeline = loadLegacyTimeline(sessionKey) ?: return
        val migratedEntries = legacyTimeline.timelineItems.mapIndexed { index, item ->
            PersistedAIAssistantTimelineEntry(
                orderIndex = index.toLong(),
                item = item
            )
        }
        if (
            saveChanges(
                sessionKey = sessionKey,
                promptDraft = legacyTimeline.promptDraft,
                persistPromptDraft = true,
                entries = migratedEntries
            )
        ) {
            legacyFileForSessionKey(sessionKey).takeIf(File::exists)?.delete()
        }
    }

    private fun loadLegacyTimeline(sessionKey: String): PersistedAIAssistantTimeline? {
        val file = legacyFileForSessionKey(sessionKey)
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

    private fun legacyFileForSessionKey(sessionKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionKey.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(legacyRootDirectory, "$digest.json")
    }
}

internal data class PersistedAIAssistantTimeline(
    val promptDraft: String,
    val timelineItems: List<AIAssistantTimelineItem>
)

internal data class PersistedAIAssistantTimelineWindow(
    val promptDraft: String,
    val totalCount: Int,
    val nextOrderIndex: Long,
    val entries: List<PersistedAIAssistantTimelineEntry>
)

internal data class PersistedAIAssistantTimelineEntry(
    val orderIndex: Long,
    val item: AIAssistantTimelineItem
)

internal data class PersistedAIAssistantMessage(
    val messageId: Long,
    val orderIndex: Long,
    val type: String,
    val metadata: JSONObject,
    val parts: List<PersistedAIAssistantMessagePart>
)

internal data class PersistedAIAssistantMessagePart(
    val partIndex: Int,
    val type: String,
    val payload: JSONObject
)

private class AIAssistantTimelineDatabaseHelper(
    context: Context
) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.createStructuredTimelineTables()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.createStructuredTimelineTables()
            db.migrateLegacyTimelineItemsToStructuredMessages()
        }
    }

    companion object {
        private const val DATABASE_NAME = "ai-assistant-timeline.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_TIMELINE_SESSIONS = "timeline_sessions"
        const val TABLE_TIMELINE_MESSAGES = "timeline_messages"
        const val TABLE_TIMELINE_MESSAGE_PARTS = "timeline_message_parts"
        const val LEGACY_TABLE_TIMELINE_ITEMS = "timeline_items"
        const val COLUMN_SESSION_KEY = "session_key"
        const val COLUMN_PROMPT_DRAFT = "prompt_draft"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_MESSAGE_ID = "message_id"
        const val COLUMN_ORDER_INDEX = "order_index"
        const val COLUMN_MESSAGE_TYPE = "message_type"
        const val COLUMN_MESSAGE_METADATA = "message_metadata"
        const val COLUMN_PART_INDEX = "part_index"
        const val COLUMN_PART_TYPE = "part_type"
        const val COLUMN_PART_PAYLOAD = "part_payload"
        const val LEGACY_COLUMN_ITEM_ID = "item_id"
        const val LEGACY_COLUMN_ITEM_TYPE = "item_type"
        const val LEGACY_COLUMN_ITEM_PAYLOAD = "item_payload"
        const val INDEX_TIMELINE_MESSAGES_SESSION_ORDER = "idx_timeline_messages_session_order"
        const val INDEX_TIMELINE_MESSAGE_PARTS_SESSION_MESSAGE = "idx_timeline_message_parts_session_message"
        const val LEGACY_INDEX_TIMELINE_ITEMS_SESSION_ORDER = "idx_timeline_items_session_order"
    }
}

private fun SQLiteDatabase.sessionExists(sessionKey: String): Boolean {
    if (queryPromptDraft(sessionKey) != null) {
        return true
    }
    return queryTimelineCount(sessionKey) > 0
}

private fun SQLiteDatabase.hasTable(tableName: String): Boolean {
    return query(
        "sqlite_master",
        arrayOf("name"),
        "type = ? AND name = ?",
        arrayOf("table", tableName),
        null,
        null,
        null,
        "1"
    ).use { cursor -> cursor.moveToFirst() }
}

private fun SQLiteDatabase.createStructuredTimelineTables() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS ${AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_SESSIONS} (
            ${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} TEXT PRIMARY KEY NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_PROMPT_DRAFT} TEXT NOT NULL DEFAULT '',
            ${AIAssistantTimelineDatabaseHelper.COLUMN_UPDATED_AT} INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent()
    )
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS ${AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGES} (
            ${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} TEXT NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID} INTEGER NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX} INTEGER NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_TYPE} TEXT NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_METADATA} TEXT NOT NULL,
            PRIMARY KEY (${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY}, ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID})
        )
        """.trimIndent()
    )
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS ${AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGE_PARTS} (
            ${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} TEXT NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID} INTEGER NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_PART_INDEX} INTEGER NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_PART_TYPE} TEXT NOT NULL,
            ${AIAssistantTimelineDatabaseHelper.COLUMN_PART_PAYLOAD} TEXT NOT NULL,
            PRIMARY KEY (
                ${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY},
                ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID},
                ${AIAssistantTimelineDatabaseHelper.COLUMN_PART_INDEX}
            )
        )
        """.trimIndent()
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS ${AIAssistantTimelineDatabaseHelper.INDEX_TIMELINE_MESSAGES_SESSION_ORDER}
        ON ${AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGES} (
            ${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY},
            ${AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX}
        )
        """.trimIndent()
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS ${AIAssistantTimelineDatabaseHelper.INDEX_TIMELINE_MESSAGE_PARTS_SESSION_MESSAGE}
        ON ${AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGE_PARTS} (
            ${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY},
            ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID},
            ${AIAssistantTimelineDatabaseHelper.COLUMN_PART_INDEX}
        )
        """.trimIndent()
    )
}

private fun SQLiteDatabase.migrateLegacyTimelineItemsToStructuredMessages() {
    val hasLegacyItemsTable = hasTable(AIAssistantTimelineDatabaseHelper.LEGACY_TABLE_TIMELINE_ITEMS)
    if (!hasLegacyItemsTable) {
        return
    }

    beginTransaction()
    try {
        query(
            AIAssistantTimelineDatabaseHelper.LEGACY_TABLE_TIMELINE_ITEMS,
            arrayOf(
                AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY,
                AIAssistantTimelineDatabaseHelper.LEGACY_COLUMN_ITEM_ID,
                AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX,
                AIAssistantTimelineDatabaseHelper.LEGACY_COLUMN_ITEM_PAYLOAD
            ),
            null,
            null,
            null,
            null,
            "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} ASC, ${AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX} ASC"
        ).use { cursor ->
            val sessionKeyColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY)
            val itemIdColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.LEGACY_COLUMN_ITEM_ID)
            val orderColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX)
            val payloadColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.LEGACY_COLUMN_ITEM_PAYLOAD)

            while (cursor.moveToNext()) {
                val sessionKey = cursor.getString(sessionKeyColumn).orEmpty()
                val itemId = cursor.getLong(itemIdColumn)
                val orderIndex = cursor.getLong(orderColumn)
                val payload = cursor.getString(payloadColumn).orEmpty()
                val item = runCatching { JSONObject(payload).toTimelineItemOrNull() }.getOrNull() ?: continue
                val structuredMessage = item.toPersistedMessage(orderIndex = orderIndex, messageId = itemId)
                upsertStructuredMessage(sessionKey, structuredMessage)
            }
        }

        execSQL("DROP INDEX IF EXISTS ${AIAssistantTimelineDatabaseHelper.LEGACY_INDEX_TIMELINE_ITEMS_SESSION_ORDER}")
        execSQL("DROP TABLE IF EXISTS ${AIAssistantTimelineDatabaseHelper.LEGACY_TABLE_TIMELINE_ITEMS}")
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun SQLiteDatabase.queryPromptDraft(sessionKey: String): String? {
    query(
        AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_SESSIONS,
        arrayOf(AIAssistantTimelineDatabaseHelper.COLUMN_PROMPT_DRAFT),
        "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ?",
        arrayOf(sessionKey),
        null,
        null,
        null,
        "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            return null
        }
        return cursor.getString(
            cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_PROMPT_DRAFT)
        )
    }
}

private fun SQLiteDatabase.queryTimelineCount(sessionKey: String): Int {
    query(
        AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGES,
        arrayOf("COUNT(*) AS count"),
        "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ?",
        arrayOf(sessionKey),
        null,
        null,
        null
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            return 0
        }
        return cursor.getInt(cursor.getColumnIndexOrThrow("count"))
    }
}

private fun SQLiteDatabase.queryNextOrderIndex(sessionKey: String): Long {
    query(
        AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGES,
        arrayOf("COALESCE(MAX(${AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX}) + 1, 0) AS next_order"),
        "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ?",
        arrayOf(sessionKey),
        null,
        null,
        null,
        "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            return 0L
        }
        return cursor.getLong(cursor.getColumnIndexOrThrow("next_order"))
    }
}

private fun SQLiteDatabase.queryStructuredTimelineEntries(
    sessionKey: String,
    beforeOrderIndexExclusive: Long?,
    limit: Int
): List<PersistedAIAssistantTimelineEntry> {
    val selection = buildString {
        append("${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ?")
        if (beforeOrderIndexExclusive != null) {
            append(" AND ${AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX} < ?")
        }
    }
    val selectionArgs = buildList {
        add(sessionKey)
        beforeOrderIndexExclusive?.let { add(it.toString()) }
    }.toTypedArray()

    val messageRows = mutableListOf<PersistedAIAssistantMessage>()
    query(
        AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGES,
        arrayOf(
            AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID,
            AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX,
            AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_TYPE,
            AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_METADATA
        ),
        selection,
        selectionArgs,
        null,
        null,
        "${AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX} DESC",
        limit.coerceAtLeast(1).toString()
    ).use { cursor ->
        val messageIdColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID)
        val orderColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX)
        val typeColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_TYPE)
        val metadataColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_METADATA)
        while (cursor.moveToNext()) {
            messageRows += PersistedAIAssistantMessage(
                messageId = cursor.getLong(messageIdColumn),
                orderIndex = cursor.getLong(orderColumn),
                type = cursor.getString(typeColumn).orEmpty(),
                metadata = runCatching { JSONObject(cursor.getString(metadataColumn).orEmpty()) }
                    .getOrDefault(JSONObject()),
                parts = emptyList()
            )
        }
    }
    if (messageRows.isEmpty()) {
        return emptyList()
    }

    val messageIds = messageRows.map(PersistedAIAssistantMessage::messageId)
    val partsByMessageId = queryMessageParts(
        sessionKey = sessionKey,
        messageIds = messageIds
    )
    return messageRows
        .asReversed()
        .mapNotNull { message ->
            message.copy(parts = partsByMessageId[message.messageId].orEmpty())
                .toTimelineEntryOrNull()
        }
}

private fun SQLiteDatabase.upsertPromptDraft(sessionKey: String, promptDraft: String) {
    insertWithOnConflict(
        AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_SESSIONS,
        null,
        ContentValues().apply {
            put(AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY, sessionKey)
            put(AIAssistantTimelineDatabaseHelper.COLUMN_PROMPT_DRAFT, promptDraft)
            put(AIAssistantTimelineDatabaseHelper.COLUMN_UPDATED_AT, System.currentTimeMillis())
        },
        SQLiteDatabase.CONFLICT_REPLACE
    )
}

private fun SQLiteDatabase.queryMessageParts(
    sessionKey: String,
    messageIds: List<Long>
): Map<Long, List<PersistedAIAssistantMessagePart>> {
    if (messageIds.isEmpty()) {
        return emptyMap()
    }
    val placeholders = List(messageIds.size) { "?" }.joinToString(", ")
    val selectionArgs = buildList {
        add(sessionKey)
        messageIds.forEach { add(it.toString()) }
    }.toTypedArray()
    val partsByMessageId = linkedMapOf<Long, MutableList<PersistedAIAssistantMessagePart>>()
    query(
        AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGE_PARTS,
        arrayOf(
            AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID,
            AIAssistantTimelineDatabaseHelper.COLUMN_PART_INDEX,
            AIAssistantTimelineDatabaseHelper.COLUMN_PART_TYPE,
            AIAssistantTimelineDatabaseHelper.COLUMN_PART_PAYLOAD
        ),
        "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ? AND ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID} IN ($placeholders)",
        selectionArgs,
        null,
        null,
        "${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID} ASC, ${AIAssistantTimelineDatabaseHelper.COLUMN_PART_INDEX} ASC"
    ).use { cursor ->
        val messageIdColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID)
        val partIndexColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_PART_INDEX)
        val partTypeColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_PART_TYPE)
        val partPayloadColumn = cursor.getColumnIndexOrThrow(AIAssistantTimelineDatabaseHelper.COLUMN_PART_PAYLOAD)
        while (cursor.moveToNext()) {
            val messageId = cursor.getLong(messageIdColumn)
            partsByMessageId.getOrPut(messageId) { mutableListOf() } += PersistedAIAssistantMessagePart(
                partIndex = cursor.getInt(partIndexColumn),
                type = cursor.getString(partTypeColumn).orEmpty(),
                payload = runCatching { JSONObject(cursor.getString(partPayloadColumn).orEmpty()) }
                    .getOrDefault(JSONObject())
            )
        }
    }
    return partsByMessageId
}

private fun SQLiteDatabase.upsertTimelineEntry(
    sessionKey: String,
    entry: PersistedAIAssistantTimelineEntry
) {
    upsertStructuredMessage(
        sessionKey = sessionKey,
        message = entry.toPersistedMessage()
    )
}

private fun SQLiteDatabase.upsertStructuredMessage(
    sessionKey: String,
    message: PersistedAIAssistantMessage
) {
    insertWithOnConflict(
        AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGES,
        null,
        ContentValues().apply {
            put(AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY, sessionKey)
            put(AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID, message.messageId)
            put(AIAssistantTimelineDatabaseHelper.COLUMN_ORDER_INDEX, message.orderIndex)
            put(AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_TYPE, message.type)
            put(AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_METADATA, message.metadata.toString())
        },
        SQLiteDatabase.CONFLICT_REPLACE
    )
    delete(
        AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGE_PARTS,
        "${AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY} = ? AND ${AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID} = ?",
        arrayOf(sessionKey, message.messageId.toString())
    )
    message.parts.forEach { part ->
        insertWithOnConflict(
            AIAssistantTimelineDatabaseHelper.TABLE_TIMELINE_MESSAGE_PARTS,
            null,
            ContentValues().apply {
                put(AIAssistantTimelineDatabaseHelper.COLUMN_SESSION_KEY, sessionKey)
                put(AIAssistantTimelineDatabaseHelper.COLUMN_MESSAGE_ID, message.messageId)
                put(AIAssistantTimelineDatabaseHelper.COLUMN_PART_INDEX, part.partIndex)
                put(AIAssistantTimelineDatabaseHelper.COLUMN_PART_TYPE, part.type)
                put(AIAssistantTimelineDatabaseHelper.COLUMN_PART_PAYLOAD, part.payload.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}

private fun PersistedAIAssistantTimelineEntry.toPersistedMessage(): PersistedAIAssistantMessage {
    return item.toPersistedMessage(
        orderIndex = orderIndex,
        messageId = item.id
    )
}

private fun AIAssistantTimelineItem.toPersistedMessage(
    orderIndex: Long,
    messageId: Long = id
): PersistedAIAssistantMessage {
    return when (this) {
        is AIAssistantHistoryDividerItem -> PersistedAIAssistantMessage(
            messageId = messageId,
            orderIndex = orderIndex,
            type = "history-divider",
            metadata = JSONObject(),
            parts = emptyList()
        )
        is AIAssistantWelcomeItem -> PersistedAIAssistantMessage(
            messageId = messageId,
            orderIndex = orderIndex,
            type = "welcome",
            metadata = JSONObject(),
            parts = listOf(
                PersistedAIAssistantMessagePart(
                    partIndex = 0,
                    type = "title",
                    payload = JSONObject().put("text", title)
                ),
                PersistedAIAssistantMessagePart(
                    partIndex = 1,
                    type = "body",
                    payload = JSONObject().put("text", body)
                )
            )
        )
        is AIAssistantUserItem -> PersistedAIAssistantMessage(
            messageId = messageId,
            orderIndex = orderIndex,
            type = "user",
            metadata = JSONObject(),
            parts = listOf(
                PersistedAIAssistantMessagePart(
                    partIndex = 0,
                    type = "prompt",
                    payload = JSONObject().put("text", prompt)
                )
            )
        )
        is AIAssistantResponseItem -> PersistedAIAssistantMessage(
            messageId = messageId,
            orderIndex = orderIndex,
            type = "response",
            metadata = JSONObject(),
            parts = listOf(
                PersistedAIAssistantMessagePart(
                    partIndex = 0,
                    type = "response",
                    payload = JSONObject().put("text", response)
                )
            )
        )
        is AIAssistantStreamingResponseItem -> PersistedAIAssistantMessage(
            messageId = messageId,
            orderIndex = orderIndex,
            type = "stream",
            metadata = JSONObject().apply {
                put("header", header)
                put("placeholder", placeholder.orEmpty())
                put("status", status.orEmpty())
                put("isWorking", isWorking)
                put("isStreaming", isStreaming)
            },
            parts = buildList {
                add(
                    PersistedAIAssistantMessagePart(
                        partIndex = 0,
                        type = "response",
                        payload = JSONObject().put("text", response)
                    )
                )
                attachments.forEachIndexed { index, attachment ->
                    add(
                        PersistedAIAssistantMessagePart(
                            partIndex = index + 1,
                            type = "tool",
                            payload = attachment.toJson()
                        )
                    )
                }
            }
        )
        is AIAssistantStatusItem -> PersistedAIAssistantMessage(
            messageId = messageId,
            orderIndex = orderIndex,
            type = "status",
            metadata = JSONObject().put("tone", tone.name),
            parts = buildList {
                add(
                    PersistedAIAssistantMessagePart(
                        partIndex = 0,
                        type = "title",
                        payload = JSONObject().put("text", title)
                    )
                )
                body?.let { text ->
                    add(
                        PersistedAIAssistantMessagePart(
                            partIndex = 1,
                            type = "body",
                            payload = JSONObject().put("text", text)
                        )
                    )
                }
            }
        )
        is AIAssistantDiffItem -> PersistedAIAssistantMessage(
            messageId = messageId,
            orderIndex = orderIndex,
            type = "diff",
            metadata = JSONObject().apply {
                put("filePath", filePath)
                put("changeLabel", changeLabel)
            },
            parts = listOf(
                PersistedAIAssistantMessagePart(
                    partIndex = 0,
                    type = "preview",
                    payload = preview.toJson()
                )
            )
        )
    }
}

private fun PersistedAIAssistantMessage.toTimelineEntryOrNull(): PersistedAIAssistantTimelineEntry? {
    val item = toTimelineItemOrNull() ?: return null
    observeTimelineIds(item)
    return PersistedAIAssistantTimelineEntry(
        orderIndex = orderIndex,
        item = item
    )
}

private fun PersistedAIAssistantMessage.toTimelineItemOrNull(): AIAssistantTimelineItem? {
    return when (type) {
        "history-divider" -> null
        "welcome" -> AIAssistantWelcomeItem(
            title = partText("title"),
            body = partText("body"),
            id = messageId
        )
        "user" -> AIAssistantUserItem(
            prompt = partText("prompt"),
            id = messageId
        )
        "response" -> AIAssistantResponseItem(
            response = partText("response"),
            id = messageId
        )
        "stream" -> AIAssistantStreamingResponseItem(
            header = metadata.optString("header", "Working"),
            placeholder = metadata.optString("placeholder").ifBlank { null },
            response = partText("response"),
            status = metadata.optString("status").ifBlank { null },
            attachments = parts
                .filter { it.type == "tool" }
                .mapNotNull { it.payload.toToolItemOrNull() },
            isWorking = metadata.optBoolean("isWorking", true),
            isStreaming = metadata.optBoolean("isStreaming", true),
            id = messageId
        )
        "status" -> AIAssistantStatusItem(
            title = partText("title"),
            body = partTextOrNull("body"),
            tone = runCatching { AIAssistantTone.valueOf(metadata.optString("tone")) }
                .getOrDefault(AIAssistantTone.NEUTRAL),
            id = messageId
        )
        "diff" -> {
            val filePath = metadata.optString("filePath").trim()
            if (filePath.isBlank()) {
                null
            } else {
                AIAssistantDiffItem(
                    filePath = filePath,
                    changeLabel = metadata.optString("changeLabel"),
                    preview = parts.firstOrNull { it.type == "preview" }
                        ?.payload
                        ?.toDiffPreview()
                        ?: AIAssistantDiffPreview(0, 0, emptyList(), emptyList()),
                    id = messageId
                )
            }
        }
        else -> null
    }
}

private fun PersistedAIAssistantMessage.partText(partType: String): String {
    return partTextOrNull(partType).orEmpty()
}

private fun PersistedAIAssistantMessage.partTextOrNull(partType: String): String? {
    return parts.firstOrNull { it.type == partType }
        ?.payload
        ?.optString("text")
        ?.ifBlank { null }
}

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

private fun AIAssistantTimelineItem.persistenceType(): String {
    return when (this) {
        is AIAssistantHistoryDividerItem -> "history-divider"
        is AIAssistantWelcomeItem -> "welcome"
        is AIAssistantUserItem -> "user"
        is AIAssistantResponseItem -> "response"
        is AIAssistantStreamingResponseItem -> "stream"
        is AIAssistantStatusItem -> "status"
        is AIAssistantDiffItem -> "diff"
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

private fun AIAssistantDiffPreview.toJson(): JSONObject {
    return JSONObject().apply {
        put("addedCount", addedCount)
        put("removedCount", removedCount)
        put("addedLines", JSONArray(addedLines))
        put("removedLines", JSONArray(removedLines))
    }
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
