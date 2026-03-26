package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import kotlinx.coroutines.delay

internal class AIAgentRequestRetrySupport(
    private val callback: AIAgentManager.AIAgentCallback,
    private val currentProviderName: () -> String,
    private val currentAttemptCount: () -> Int,
    private val canRetry: () -> Boolean,
    private val incrementAttemptCount: () -> Unit,
    private val resetAttemptCount: () -> Unit,
    private val switchProvider: (String) -> Boolean,
    private val findAlternativeProvider: () -> String?,
    private val isAutoSwitchEnabled: () -> Boolean,
    private val isNonRetryableToolError: (Throwable) -> Boolean,
    private val formatErrorMessage: (Throwable) -> String,
    private val waitFor: suspend (Long) -> Unit = { delay(it) }
) {

    var isCompleted: Boolean = false
        private set

    var providerSwitched: Boolean = false
        private set

    fun canContinue(): Boolean {
        return !isCompleted && canRetry()
    }

    fun markCompleted() {
        isCompleted = true
    }

    suspend fun beforeAttempt() {
        val attemptNumber = currentAttemptCount()
        if (attemptNumber <= 0 || providerSwitched) {
            return
        }

        callback.onRetry(attemptNumber, "Thinking differently...")
        waitFor(1000)
    }

    suspend fun scheduleProcessingRetry(message: String) {
        callback.onProcessing(message)
        incrementAttemptCount()
        waitFor(1500)
    }

    suspend fun handleResolutionFailure(error: Throwable) {
        if (isProviderSwitchableError(error) && !providerSwitched) {
            handleProviderSwitchFailure(error)
            return
        }

        if (canRetry() && !providerSwitched && !isNonRetryableToolError(error)) {
            callback.onRetry(
                currentAttemptCount(),
                "Error: ${error.message?.take(50) ?: "Unknown error"}. Retrying..."
            )
            incrementAttemptCount()
            waitFor(1500)
            return
        }

        callback.onError(formatErrorMessage(error))
        isCompleted = true
    }

    suspend fun handleExecutionException(error: Exception) {
        if (canRetry() && !isNonRetryableToolError(error)) {
            callback.onRetry(
                currentAttemptCount(),
                "Exception: ${error.message?.take(50) ?: "Unknown"}. Trying again..."
            )
            incrementAttemptCount()
            waitFor(1500)
            return
        }

        callback.onError(formatErrorMessage(error))
        isCompleted = true
    }

    private suspend fun handleProviderSwitchFailure(error: Throwable) {
        if (!isAutoSwitchEnabled()) {
            callback.onError("PROVIDER_SWITCH_REQUIRED::${formatErrorMessage(error)}")
            isCompleted = true
            return
        }

        val alternativeProvider = findAlternativeProvider()
        if (alternativeProvider == null) {
            callback.onError("${formatErrorMessage(error)}\n\n❌ No alternative providers available.")
            isCompleted = true
            return
        }

        callback.onProcessing("⚠️ ${currentProviderName()}: ${error.message ?: "Unknown error"}")
        callback.onProcessing("🔄 Auto-switching to another provider...")
        waitFor(1500)

        if (switchProvider(alternativeProvider)) {
            providerSwitched = true
            resetAttemptCount()
            callback.onProcessing("✅ Switched to ${currentProviderName()}")
            return
        }

        callback.onError("${formatErrorMessage(error)}\n\n❌ Failed to switch providers.")
        isCompleted = true
    }

    private fun isProviderSwitchableError(error: Throwable): Boolean {
        return error is RateLimitException ||
            error is QuotaExceededException ||
            error is InsufficientBalanceException ||
            error is InvalidApiKeyException
    }
}
