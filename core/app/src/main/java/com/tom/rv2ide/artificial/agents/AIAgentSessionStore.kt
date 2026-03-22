package com.tom.rv2ide.artificial.agents

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class AIAgentSessionStore(context: Context) {
    private val rootDirectory = File(context.filesDir, "ai-agent-sessions")

    fun load(sessionKey: String): PersistedAgentSession? {
        val file = fileForSessionKey(sessionKey)
        if (!file.exists()) {
            return null
        }

        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optString("sessionKey") == sessionKey) {
                PersistedAgentSession(
                    sessionTurns = json.optJSONArray("sessionTurns").toPersistedTurns(),
                    agentState = json.optString("agentState").takeIf { it.isNotBlank() }
                )
            } else {
                null
            }
        }.getOrNull()
    }

    fun save(
        sessionKey: String,
        sessionTurns: List<PersistedAgentSessionTurn>,
        agentState: String?
    ) {
        runCatching {
            if (!rootDirectory.exists()) {
                rootDirectory.mkdirs()
            }
            val payload = JSONObject().apply {
                put("sessionKey", sessionKey)
                put(
                    "sessionTurns",
                    JSONArray().apply {
                        sessionTurns.forEach { turn ->
                            put(
                                JSONObject().apply {
                                    put("role", turn.role)
                                    put("content", turn.content)
                                }
                            )
                        }
                    }
                )
                put("agentState", agentState.orEmpty())
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

internal data class PersistedAgentSession(
    val sessionTurns: List<PersistedAgentSessionTurn>,
    val agentState: String?
)

internal data class PersistedAgentSessionTurn(
    val role: String,
    val content: String
)

private fun JSONArray?.toPersistedTurns(): List<PersistedAgentSessionTurn> {
    if (this == null) {
        return emptyList()
    }

    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val role = item.optString("role").trim()
            val content = item.optString("content").trim()
            if (role.isBlank() || content.isBlank()) {
                continue
            }
            add(
                PersistedAgentSessionTurn(
                    role = role,
                    content = content
                )
            )
        }
    }
}
