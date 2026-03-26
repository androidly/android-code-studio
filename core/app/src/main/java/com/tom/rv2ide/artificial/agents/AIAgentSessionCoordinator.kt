package com.tom.rv2ide.artificial.agents

internal object AIAgentSessionCoordinator {
    fun buildProviderSessionIdentity(
        providerKey: String,
        persistentConversationFingerprint: String
    ): String {
        val fingerprint = persistentConversationFingerprint.trim()
        return if (fingerprint.isBlank()) {
            providerKey
        } else {
            "$providerKey|$fingerprint"
        }
    }
}
