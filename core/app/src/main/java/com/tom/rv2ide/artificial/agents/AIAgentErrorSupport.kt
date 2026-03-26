package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.agents.external.ExternalEngineConfigurationException
import com.tom.rv2ide.artificial.agents.external.ExternalEngineException
import com.tom.rv2ide.artificial.agents.external.ExternalEngineExecutionException

internal object AIAgentErrorSupport {

    fun formatErrorMessage(
        error: Throwable,
        providerName: String
    ): String {
        val errorMessage = error.message ?: "Unknown error occurred"
        val stackTrace = error.stackTraceToString().take(500)

        return when (error) {
            is ToolExecutionDisabledException ->
                "🧰 TOOL EXECUTION DISABLED\n\n${error.message}"
            is ToolProtocolException ->
                "⚠️ TOOL PROTOCOL ERROR\n\n${error.message}"
            is ToolCallLoopException ->
                "🔁 TOOL LOOP STOPPED\n\n${error.message}"
            is ExternalEngineConfigurationException ->
                "⚙️ EXTERNAL ENGINE CONFIG ERROR\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is ExternalEngineExecutionException ->
                "❌ EXTERNAL ENGINE ERROR\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is ExternalEngineException ->
                "❌ EXTERNAL ENGINE ERROR\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.RateLimitException ->
                "⚠️ RATE LIMIT EXCEEDED\n\nThe API rate limit has been exceeded.\nPlease wait a few minutes before trying again.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.QuotaExceededException ->
                "⚠️ QUOTA EXCEEDED\n\nYour API quota has been exhausted.\nPlease check your billing or upgrade your plan.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException ->
                "💳 INSUFFICIENT BALANCE\n\nYour account balance is too low to process this request.\nPlease add credits or upgrade your plan.\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException ->
                "❌ INVALID API KEY\n\nThe API key is invalid or expired.\nPlease update your API key in the configuration.\n\nDetails: $errorMessage"
            is java.net.UnknownHostException ->
                "🌐 NETWORK ERROR\n\nCould not connect to the API server.\nPlease check your internet connection.\n\nDetails: $errorMessage"
            is java.net.SocketTimeoutException ->
                "⏱️ TIMEOUT ERROR\n\nThe request took too long to complete.\nPlease try again.\n\nDetails: $errorMessage"
            is org.json.JSONException ->
                "📄 JSON PARSING ERROR\n\nFailed to parse API response.\nThe API may be experiencing issues.\n\nDetails: $errorMessage"
            else ->
                "❌ ERROR OCCURRED\n\nProvider: $providerName\nError Type: ${error.javaClass.simpleName}\n\nMessage: $errorMessage\n\nStack Trace (first 500 chars):\n$stackTrace"
        }
    }
}

internal class ToolCallLoopException(message: String) : IllegalStateException(message)

internal class ToolProtocolException(message: String) : IllegalStateException(message)

internal class ToolExecutionDisabledException(message: String) : IllegalStateException(message)
