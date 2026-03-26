package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.file.FileWriteResult
import java.io.File

internal object AIAgentModificationSupport {

    fun buildModificationResults(
        modifications: List<BaseFileModification>
    ): List<AIAgentManager.ModificationResult> {
        return modifications.map { modification ->
            val writeResult = modification.writeResult
            val success = writeResult is FileWriteResult.Success
            AIAgentManager.ModificationResult(
                filePath = modification.filePath,
                content = modification.content,
                previousContent = modification.previousContent,
                success = success,
                message = when (writeResult) {
                    is FileWriteResult.Success -> "Modified successfully"
                    is FileWriteResult.PermissionDenied -> writeResult.reason
                    is FileWriteResult.Error -> writeResult.message
                },
                isNewFile = modification.previousContent == null
            )
        }
    }

    fun createSummary(
        results: List<AIAgentManager.ModificationResult>
    ): AIAgentManager.ModificationSummary {
        val successful = results.count { it.success }
        val failed = results.count { !it.success }
        val newFiles = results.count { it.isNewFile }
        val modifiedFiles = results.count { !it.isNewFile }

        val fileDetails = results.map { result ->
            AIAgentManager.FileDetail(
                fileName = File(result.filePath).name,
                filePath = result.filePath,
                status = if (result.success) {
                    AIAgentManager.FileStatus.SUCCESS
                } else {
                    AIAgentManager.FileStatus.FAILED
                },
                changeType = if (result.isNewFile) {
                    AIAgentManager.ChangeType.CREATED
                } else {
                    AIAgentManager.ChangeType.MODIFIED
                }
            )
        }

        return AIAgentManager.ModificationSummary(
            totalFiles = results.size,
            successfulFiles = successful,
            failedFiles = failed,
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            fileDetails = fileDetails
        )
    }

    fun readCurrentFileContent(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (file.exists() && file.isFile) {
                file.readText()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
