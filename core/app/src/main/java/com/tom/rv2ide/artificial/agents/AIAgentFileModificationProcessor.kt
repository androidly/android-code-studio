package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.parser.SnippetParser
import java.io.File
import kotlinx.coroutines.delay

internal class AIAgentFileModificationProcessor(
    private val writeFile: (String, String) -> FileWriteResult,
    private val recordModification: (String, String?, String, Boolean) -> Unit
) {
    private val snippetParser = SnippetParser()

    suspend fun process(
        response: String,
        callback: AIAgentManager.AIAgentCallback
    ): List<BaseFileModification> {
        if (!response.contains("FILE_TO_MODIFY:")) {
            return emptyList()
        }

        val modifications = mutableListOf<BaseFileModification>()
        val lines = response.lines()
        var currentFile: String? = null
        val contentBuilder = StringBuilder()
        var inContent = false

        suspend fun flushPendingFile() {
            val filePath = currentFile ?: return
            if (contentBuilder.isEmpty()) {
                return
            }

            val fileName = File(filePath).name
            callback.onFileModifying(filePath, fileName)

            val rawContent = contentBuilder.toString().trim()
            val cleanedContent = snippetParser.cleanFileContent(rawContent)
            val previousContent = AIAgentModificationSupport.readCurrentFileContent(filePath)
            val writeResult = writeFile(filePath, cleanedContent)
            val success = writeResult is FileWriteResult.Success

            recordModification(filePath, previousContent, cleanedContent, success)
            callback.onFileModified(filePath, fileName, success)
            delay(300)

            modifications += BaseFileModification(
                filePath = filePath,
                content = cleanedContent,
                writeResult = writeResult,
                previousContent = previousContent
            )
        }

        for (line in lines) {
            if (line.startsWith("FILE_TO_MODIFY:")) {
                flushPendingFile()
                currentFile = line.substringAfter("FILE_TO_MODIFY:").trim()
                contentBuilder.clear()
                inContent = true
            } else if (inContent) {
                contentBuilder.append(line).append("\n")
            }
        }

        flushPendingFile()
        return modifications
    }
}
