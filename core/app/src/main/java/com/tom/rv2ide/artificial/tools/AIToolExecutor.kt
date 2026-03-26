package com.tom.rv2ide.artificial.tools

import android.content.Context
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.projects.internal.ProjectManagerImpl
import com.tom.rv2ide.services.builder.gradleDistributionParams
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AIToolExecutor(
    private val context: Context,
    private val projectRootProvider: () -> File?,
    private val writeFile: (String, String) -> FileWriteResult,
    private val recordModification: (String, String?, String, Boolean) -> Unit
) {
    private var projectModelRefreshSuggested = false

    companion object {
        private const val MAX_OUTPUT_CHARS = 12000
        private const val COMMAND_TIMEOUT_SECONDS = 300L
        private const val BUILD_TIMEOUT_SECONDS = 900L
        private const val MAX_TEXT_FILE_BYTES = 1_000_000L
        private const val MAX_FIND_FILES_RESULTS = 120
        private const val MAX_SEARCH_RESULTS = 100
        private const val DEFAULT_FIND_FILES_RESULTS = 40
        private const val DEFAULT_SEARCH_RESULTS = 40
        private const val DEFAULT_READ_RANGE_SIZE = 120
        private const val MAX_READ_RANGE_SIZE = 240
        private const val MAX_REPLACE_LINES = 240
        private val IGNORED_DIRECTORY_NAMES = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".cxx",
            "build",
            "bin",
            "out",
            "node_modules"
        )
        private val ALLOWED_TERMINAL_PREFIXES = setOf(
            "pkg",
            "apt",
            "dpkg",
            "ls",
            "pwd",
            "find",
            "grep",
            "sed",
            "cat",
            "echo",
            "git",
            "rg",
            "cp",
            "mkdir",
            "touch",
            "realpath",
            "readlink",
            "stat",
            "file",
            "tree",
            "du",
            "df",
            "ps",
            "top",
            "curl",
            "wget",
            "tar",
            "unzip",
            "zip",
            "jar",
            "basename",
            "dirname",
            "hostname",
            "id",
            "test",
            "java",
            "javac",
            "kotlinc",
            "python",
            "python3",
            "pip",
            "pip3",
            "clang",
            "clang++",
            "cmake",
            "make",
            "ndk-build",
            "whoami",
            "uname",
            "env",
            "printenv",
            "which",
            "whereis",
            "termux-info",
            "head",
            "tail",
            "awk",
            "cut",
            "sort",
            "uniq",
            "wc",
            "nl"
        )
        private val GRADLE_TERMINAL_TOKENS = setOf(
            "gradle",
            "gradlew",
            "gradlew.bat"
        )
        private val FORBIDDEN_COMMAND_PARTS = listOf(
            "&&",
            "||",
            ";",
            "|",
            ">",
            "<",
            "`",
            "$(",
            "\n",
            "\r"
        )
        private val FORBIDDEN_FIRST_TOKENS = setOf(
            "rm",
            "mv",
            "dd",
            "mkfs",
            "mount",
            "umount",
            "su",
            "sudo",
            "reboot",
            "shutdown",
            "poweroff",
            "chown",
            "chmod",
            "am",
            "pm"
        )
    }

    suspend fun execute(
        toolCall: AIToolCall,
        onOutputLine: ((String) -> Unit)? = null
    ): AIToolExecutionResult = withContext(Dispatchers.IO) {
        when (toolCall.name.trim().lowercase()) {
            "build_project" -> executeBuildProject(toolCall, onOutputLine)
            "run_terminal_command" -> executeTerminalCommand(toolCall, onOutputLine)
            "find_files" -> executeFindFiles(toolCall)
            "search_project" -> executeSearchProject(toolCall)
            "read_file_range" -> executeReadFileRange(toolCall)
            "replace_file_range" -> executeReplaceFileRange(toolCall)
            else -> AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Unknown tool '${toolCall.name}'",
                output = "Supported tools: build_project, run_terminal_command, find_files, search_project, read_file_range, replace_file_range"
            )
        }
    }

    private fun executeFindFiles(toolCall: AIToolCall): AIToolExecutionResult {
        val projectRoot = projectRootProvider()
            ?: return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Project root is not loaded",
                output = "Load a project before requesting file search tools."
            )

        val pattern = toolCall.argument("pattern").orEmpty().trim()
        if (pattern.isBlank()) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "No file pattern provided",
                output = "Use PATTERN: MainActivity.kt or PATTERN: *Activity*.kt"
            )
        }

        val maxResults = parseBoundedInt(
            rawValue = toolCall.argument("max_results"),
            defaultValue = DEFAULT_FIND_FILES_RESULTS,
            minValue = 1,
            maxValue = MAX_FIND_FILES_RESULTS
        )
        val matcher = buildPathPatternMatcher(pattern)
        val matches = mutableListOf<String>()
        val iterator = projectRoot.walkTopDown()
            .onEnter { directory -> !shouldSkipDirectory(directory, projectRoot) }
            .iterator()

        while (iterator.hasNext() && matches.size < maxResults) {
            val file = iterator.next()
            if (!file.isFile) {
                continue
            }

            val relativePath = toRelativeProjectPath(file, projectRoot)
            if (matcher(relativePath) || matcher(file.name)) {
                matches += relativePath
            }
        }

        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = true,
            summary = if (matches.isEmpty()) {
                "No files matched '$pattern'"
            } else {
                "Found ${matches.size} file(s) matching '$pattern'"
            },
            output = if (matches.isEmpty()) {
                "No matching files found under ${projectRoot.absolutePath}"
            } else {
                matches.joinToString("\n")
            },
            workingDirectory = projectRoot.absolutePath
        )
    }

    private fun executeSearchProject(toolCall: AIToolCall): AIToolExecutionResult {
        val projectRoot = projectRootProvider()
            ?: return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Project root is not loaded",
                output = "Load a project before requesting search tools."
            )

        val pattern = toolCall.argument("pattern").orEmpty().trim()
        if (pattern.isBlank()) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "No search pattern provided",
                output = "Use PATTERN: symbol_name or PATTERN: class Foo"
            )
        }

        val isRegex = toolCall.argument("regex").toBooleanStrictOrFalse()
        val caseSensitive = toolCall.argument("case_sensitive").toBooleanStrictOrFalse()
        val fileGlob = toolCall.argument("file_glob").orEmpty().trim()
        val fileMatcher = if (fileGlob.isBlank()) null else buildPathPatternMatcher(fileGlob)
        val maxResults = parseBoundedInt(
            rawValue = toolCall.argument("max_results"),
            defaultValue = DEFAULT_SEARCH_RESULTS,
            minValue = 1,
            maxValue = MAX_SEARCH_RESULTS
        )

        val regex = if (isRegex) {
            try {
                Regex(
                    pattern,
                    if (caseSensitive) {
                        emptySet()
                    } else {
                        setOf(RegexOption.IGNORE_CASE)
                    }
                )
            } catch (error: Exception) {
                return AIToolExecutionResult(
                    toolName = toolCall.name,
                    success = false,
                    summary = "Invalid regular expression",
                    output = error.message ?: "Could not compile PATTERN as a regex."
                )
            }
        } else {
            null
        }

        val matches = mutableListOf<String>()
        val iterator = projectRoot.walkTopDown()
            .onEnter { directory -> !shouldSkipDirectory(directory, projectRoot) }
            .iterator()

        while (iterator.hasNext() && matches.size < maxResults) {
            val file = iterator.next()
            if (!file.isFile || !isSearchableTextFile(file)) {
                continue
            }

            val relativePath = toRelativeProjectPath(file, projectRoot)
            if (fileMatcher != null && !fileMatcher(relativePath)) {
                continue
            }

            try {
                file.bufferedReader().useLines { lines ->
                    var lineNumber = 0
                    for (line in lines) {
                        lineNumber++
                        val isMatch = if (regex != null) {
                            regex.containsMatchIn(line)
                        } else if (caseSensitive) {
                            line.contains(pattern)
                        } else {
                            line.contains(pattern, ignoreCase = true)
                        }

                        if (isMatch) {
                            matches += "${relativePath}:$lineNumber: ${truncateLine(line)}"
                            if (matches.size >= maxResults) {
                                return@useLines
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = true,
            summary = if (matches.isEmpty()) {
                "No matches found for '$pattern'"
            } else {
                "Found ${matches.size} match(es) for '$pattern'"
            },
            output = if (matches.isEmpty()) {
                "No content matches found under ${projectRoot.absolutePath}"
            } else {
                matches.joinToString("\n")
            },
            workingDirectory = projectRoot.absolutePath
        )
    }

    private fun executeReadFileRange(toolCall: AIToolCall): AIToolExecutionResult {
        val file = resolveProjectFile(toolCall.argument("file"), allowMissing = false)
            ?: return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Invalid file path",
                output = "Use FILE: relative/path/from/project/root"
            )

        val normalized = file.readText().replace("\r\n", "\n")
        val lines = if (normalized.isEmpty()) emptyList() else normalized.split("\n")
        val totalLines = lines.size
        if (totalLines == 0) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = true,
                summary = "File is empty",
                output = "FILE: ${file.absolutePath}\nTOTAL_LINES: 0"
            )
        }

        val startLine = parseBoundedInt(
            rawValue = toolCall.argument("start_line"),
            defaultValue = 1,
            minValue = 1,
            maxValue = totalLines
        )
        val requestedEnd = parseBoundedInt(
            rawValue = toolCall.argument("end_line"),
            defaultValue = (startLine + DEFAULT_READ_RANGE_SIZE - 1).coerceAtMost(totalLines),
            minValue = startLine,
            maxValue = totalLines
        )
        val endLine = requestedEnd.coerceAtMost(startLine + MAX_READ_RANGE_SIZE - 1)

        val width = endLine.toString().length
        val snippet = (startLine..endLine).joinToString("\n") { lineNumber ->
            "${lineNumber.toString().padStart(width)} | ${lines[lineNumber - 1]}"
        }

        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = true,
            summary = "Read lines $startLine-$endLine from ${file.name}",
            output = buildString {
                appendLine("FILE: ${file.absolutePath}")
                appendLine("TOTAL_LINES: $totalLines")
                appendLine("RANGE: $startLine-$endLine")
                appendLine("CONTENT:")
                append(snippet)
            }
        )
    }

    private fun executeReplaceFileRange(toolCall: AIToolCall): AIToolExecutionResult {
        val file = resolveProjectFile(toolCall.argument("file"), allowMissing = true)
            ?: return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Invalid file path",
                output = "Use FILE: relative/path/from/project/root"
            )

        val rawContent = toolCall.arguments["content"]
        if (rawContent == null) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "No replacement content provided",
                output = "Use CONTENT: followed by the exact replacement block."
            )
        }

        val previousContent = file.takeIf { it.exists() }?.readText()?.replace("\r\n", "\n")
        val originalLines = when {
            previousContent == null -> emptyList()
            previousContent.isEmpty() -> emptyList()
            else -> previousContent.split("\n")
        }
        val originalLineCount = originalLines.size
        val startLine = parseBoundedInt(
            rawValue = toolCall.argument("start_line"),
            defaultValue = 1,
            minValue = 1,
            maxValue = if (file.exists()) originalLineCount + 1 else 1
        )
        val endLine = parseBoundedInt(
            rawValue = toolCall.argument("end_line"),
            defaultValue = startLine,
            minValue = 0,
            maxValue = if (file.exists()) originalLineCount else 0
        )

        if (endLine < startLine - 1) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Invalid line range",
                output = "END_LINE must be START_LINE - 1 for insertion, or >= START_LINE for replacement."
            )
        }

        if (!file.exists() && (startLine != 1 || endLine != 0)) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Cannot replace lines in a missing file",
                output = "For a new file, use START_LINE: 1 and END_LINE: 0."
            )
        }

        if (file.exists()) {
            if (startLine > originalLineCount + 1) {
                return AIToolExecutionResult(
                    toolName = toolCall.name,
                    success = false,
                    summary = "Start line is outside the file",
                    output = "The file has $originalLineCount lines."
                )
            }
            if (endLine > originalLineCount) {
                return AIToolExecutionResult(
                    toolName = toolCall.name,
                    success = false,
                    summary = "End line is outside the file",
                    output = "The file has $originalLineCount lines."
                )
            }
        }

        val normalizedReplacement = normalizeReplacementContent(rawContent)
        val replacementLines = if (normalizedReplacement.isEmpty()) {
            emptyList()
        } else {
            normalizedReplacement.split("\n")
        }
        if (replacementLines.size > MAX_REPLACE_LINES) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Replacement block is too large",
                output = "Replace at most $MAX_REPLACE_LINES lines at a time."
            )
        }

        val startIndex = startLine - 1
        val endExclusive = if (endLine < startLine) startIndex else endLine
        val mergedLines = buildList {
            addAll(originalLines.take(startIndex))
            addAll(replacementLines)
            if (file.exists()) {
                addAll(originalLines.drop(endExclusive))
            }
        }
        val newContent = mergedLines.joinToString("\n")
        val writeResult = writeFile(file.absolutePath, newContent)
        val success = writeResult is FileWriteResult.Success
        recordModification(file.absolutePath, previousContent, newContent, success)
        if (success) {
            markProjectModelDirtyIfNeeded(file)
        }

        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = success,
            summary = if (success) {
                "Updated ${file.name} lines $startLine-$endLine"
            } else {
                "Failed to update ${file.name}"
            },
            output = when (writeResult) {
                is FileWriteResult.Success -> {
                    val finalEndLine = if (replacementLines.isEmpty()) {
                        (startLine - 1).coerceAtLeast(0)
                    } else {
                        startLine + replacementLines.size - 1
                    }
                    buildString {
                        appendLine("FILE: ${file.absolutePath}")
                        appendLine("REPLACED_RANGE: $startLine-$endLine")
                        appendLine("NEW_RANGE: $startLine-$finalEndLine")
                        appendLine("NEW_TOTAL_LINES: ${if (newContent.isEmpty()) 0 else newContent.split("\n").size}")
                    }.trim()
                }
                is FileWriteResult.PermissionDenied -> writeResult.reason
                is FileWriteResult.Error -> writeResult.message
            },
            workingDirectory = file.parentFile?.absolutePath,
            fileChange = AIToolFileChange(
                filePath = file.absolutePath,
                previousContent = previousContent,
                newContent = newContent,
                writeResult = writeResult
            )
        )
    }

    private suspend fun executeBuildProject(
        toolCall: AIToolCall,
        onOutputLine: ((String) -> Unit)?
    ): AIToolExecutionResult {
        val projectRoot = projectRootProvider()
            ?: return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Project root is not loaded",
                output = "Load a project before requesting build tools."
            )

        val tasks = parseBuildTasks(toolCall.argument("tasks"))
        if (tasks.isEmpty()) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "No Gradle tasks provided",
                output = "Use TASKS: :app:assembleDebug or similar."
            )
        }

        val invalidTask = tasks.firstOrNull { !it.matches(Regex("[A-Za-z0-9:_./-]+")) }
        if (invalidTask != null) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Unsafe Gradle task '$invalidTask'",
                output = "Gradle tasks may only contain letters, digits, ':', '_', '.', '/', and '-'."
            )
        }

        val args = parseGradleArgs(toolCall.argument("args"))
        val wrapper = when {
            File(projectRoot, "gradlew").exists() -> "./gradlew"
            File(projectRoot, "gradlew.bat").exists() -> "./gradlew"
            else -> null
        } ?: return AIToolExecutionResult(
            toolName = toolCall.name,
            success = false,
            summary = "Gradle wrapper not found",
            output = "Expected gradlew in ${projectRoot.absolutePath}"
        )

        val command = buildString {
            append("sh ")
            append(wrapper)
            tasks.forEach {
                append(' ')
                append(it)
            }
            if (args.none { it == "--console=plain" }) {
                append(" --console=plain")
            }
            args.forEach {
                append(' ')
                append(it)
            }
        }

        val execution = runCommand(command, projectRoot, BUILD_TIMEOUT_SECONDS, onOutputLine)
        val refreshResult = if (
            execution.exitCode == 0 &&
            shouldRefreshIdeProjectModelAfterBuild(tasks)
        ) {
                refreshIdeProjectModel(onOutputLine)
            } else {
                null
            }
        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = execution.exitCode == 0,
            summary = when {
                execution.exitCode != 0 -> "Gradle build failed with exit code ${execution.exitCode}"
                refreshResult?.success == false ->
                    "Gradle build completed successfully, but IDE project model refresh failed"
                refreshResult?.success == true ->
                    "Gradle build completed successfully and IDE project model was refreshed"
                else -> "Gradle build completed successfully"
            },
            output = buildString {
                if (execution.output.isNotBlank()) {
                    append(execution.output.trimEnd())
                }
                refreshResult?.let { refresh ->
                    if (isNotEmpty()) {
                        appendLine()
                        appendLine()
                    }
                    appendLine("IDE_MODEL_REFRESH: ${if (refresh.success) "SUCCESS" else "FAILED"}")
                    append(refresh.message)
                }
            }.trim(),
            executedCommand = command,
            workingDirectory = projectRoot.absolutePath,
            exitCode = execution.exitCode
        )
    }

    private suspend fun executeTerminalCommand(
        toolCall: AIToolCall,
        onOutputLine: ((String) -> Unit)?
    ): AIToolExecutionResult {
        val rawCommand = toolCall.argument("command").orEmpty()
        if (rawCommand.isBlank()) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "No terminal command provided",
                output = "Use COMMAND: pkg install ripgrep -y or another supported command."
            )
        }

        val preparedCommand = prepareTerminalCommand(toolCall, rawCommand)
        val command = preparedCommand.command

        parseGradleTerminalCommand(toolCall, command, preparedCommand.workdirHint)?.let { gradleToolCall ->
            val buildResult = executeBuildProject(gradleToolCall, onOutputLine)
            return buildResult.copy(
                summary = if (buildResult.success) {
                    buildResult.summary.replaceFirst("Gradle build", "Gradle command")
                } else {
                    buildResult.summary
                },
                output = buildString {
                    appendLine("REROUTED_FROM_TERMINAL_COMMAND: $rawCommand")
                    preparedCommand.rewriteNote?.let { note ->
                        appendLine("HOST_REWRITE: $note")
                    }
                    if (buildResult.output.isNotBlank()) {
                        append(buildResult.output)
                    }
                }.trim(),
                executedCommand = command
            )
        }

        val safetyError = validateTerminalCommand(command)
        if (safetyError != null) {
            return AIToolExecutionResult(
                toolName = toolCall.name,
                success = false,
                summary = "Command blocked by safety policy",
                output = buildBlockedCommandGuidance(command, safetyError),
                executedCommand = command,
                blockedBySafetyPolicy = true
            )
        }

        val workingDirectory = resolveWorkingDirectory(preparedCommand.workdirHint)
        val execution = runCommand(command, workingDirectory, COMMAND_TIMEOUT_SECONDS, onOutputLine)
        return AIToolExecutionResult(
            toolName = toolCall.name,
            success = execution.exitCode == 0,
            summary = if (execution.exitCode == 0) {
                "Command completed successfully"
            } else {
                "Command failed with exit code ${execution.exitCode}"
            },
            output = buildString {
                preparedCommand.rewriteNote?.let { note ->
                    appendLine("HOST_REWRITE: $note")
                }
                if (execution.output.isNotBlank()) {
                    append(execution.output)
                }
            }.trim(),
            executedCommand = command,
            workingDirectory = workingDirectory.absolutePath,
            exitCode = execution.exitCode
        )
    }

    private suspend fun refreshIdeProjectModel(
        onOutputLine: ((String) -> Unit)?
    ): ProjectModelRefreshResult {
        val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
            ?: return ProjectModelRefreshResult(
                success = false,
                message = "BuildService unavailable. IDE project model was not refreshed."
            )
        if (!buildService.isToolingServerStarted()) {
            return ProjectModelRefreshResult(
                success = false,
                message = "Tooling server unavailable. IDE project model was not refreshed."
            )
        }

        val manager = ProjectManagerImpl.getInstance()
        return try {
            onOutputLine?.invoke("[ide] Refreshing project model...")
            val result = manager.refreshProjectModel(buildService, gradleDistributionParams)
            if (result == null) {
                ProjectModelRefreshResult(
                    success = false,
                    message = "Project initialization returned no result."
                )
            } else if (!result.isSuccessful) {
                ProjectModelRefreshResult(
                    success = false,
                    message = "Tooling sync failed: ${result.failure ?: "unknown failure"}"
                )
            } else {
                projectModelRefreshSuggested = false
                ProjectModelRefreshResult(
                    success = true,
                    message = "Tooling sync succeeded and workspace/module state was rebuilt."
                )
            }
        } catch (error: Throwable) {
            ProjectModelRefreshResult(
                success = false,
                message = error.message ?: "Unknown refresh error"
            )
        }
    }

    private data class ProjectModelRefreshResult(
        val success: Boolean,
        val message: String
    )

    private fun shouldRefreshIdeProjectModelAfterBuild(tasks: List<String>): Boolean {
        return projectModelRefreshSuggested || tasks.any(::isExplicitModelRefreshTask)
    }

    private fun isExplicitModelRefreshTask(task: String): Boolean {
        val normalized = task.substringAfterLast(':').trim().lowercase()
        return normalized.contains("sync") ||
            normalized.contains("preparekotlinbuildscriptmodel") ||
            normalized == "projects" ||
            normalized == "components"
    }

    private fun markProjectModelDirtyIfNeeded(file: File) {
        if (isProjectModelAffectingFile(file)) {
            projectModelRefreshSuggested = true
        }
    }

    private fun isProjectModelAffectingFile(file: File): Boolean {
        val normalizedPath = file.path.replace('\\', '/').lowercase()
        val normalizedName = file.name.lowercase()
        return normalizedName == "settings.gradle" ||
            normalizedName == "settings.gradle.kts" ||
            normalizedName == "build.gradle" ||
            normalizedName == "build.gradle.kts" ||
            normalizedName == "gradle.properties" ||
            normalizedName == "gradle-wrapper.properties" ||
            normalizedPath.endsWith("/gradle/libs.versions.toml") ||
            normalizedPath.contains("/buildsrc/")
    }

    private fun prepareTerminalCommand(
        toolCall: AIToolCall,
        rawCommand: String
    ): PreparedTerminalCommand {
        var command = unwrapShellWrappedCommand(rawCommand)
        var workdirHint = toolCall.argument("workdir")
        val rewriteNotes = mutableListOf<String>()

        extractLeadingCdCommand(command)?.let { extracted ->
            command = extracted.command
            workdirHint = extracted.workdir
            rewriteNotes += "Converted leading cd into WORKDIR=${extracted.workdir}"
        }

        return PreparedTerminalCommand(
            command = command,
            workdirHint = workdirHint,
            rewriteNote = rewriteNotes.joinToString(" | ").takeIf { it.isNotBlank() }
        )
    }

    private fun validateTerminalCommand(command: String): String? {
        val normalized = command.trim()
        if (normalized.isBlank()) {
            return "Command is blank."
        }

        FORBIDDEN_COMMAND_PARTS.firstOrNull { normalized.contains(it) }?.let { operator ->
            return "Shell operator '$operator' is not allowed. Use a single command without pipes, redirects, or chaining."
        }

        val firstToken = normalized.substringBefore(' ').trim()
        if (firstToken in FORBIDDEN_FIRST_TOKENS) {
            return "Command '$firstToken' is not allowed."
        }

        if (firstToken !in ALLOWED_TERMINAL_PREFIXES) {
            return "Command '$firstToken' is not in the allowlist. Supported prefixes: ${ALLOWED_TERMINAL_PREFIXES.sorted().joinToString(", ")}"
        }

        return null
    }

    private fun buildBlockedCommandGuidance(command: String, reason: String): String {
        val normalized = command.trim()
        val firstToken = normalized.substringBefore(' ').trim()

        val suggestions = buildList {
            if (looksLikeGradleCommand(normalized)) {
                add("Use build_project for Gradle wrapper builds, or issue a direct ./gradlew command and let it be routed as a build.")
            }
            if (normalized.startsWith("cd ", ignoreCase = true)) {
                add("Do not chain 'cd ... && ...'. Put the target directory in WORKDIR and keep COMMAND to a single command.")
            }
            if (firstToken in setOf("cat", "sed", "grep", "rg", "find")) {
                add("Prefer find_files, search_project, and read_file_range for focused project inspection instead of shelling out repeatedly.")
            }
            add("Do not repeat the same blocked command. Switch to another supported command or a different tool.")
        }

        return buildString {
            appendLine(reason)
            appendLine()
            appendLine("Alternatives:")
            suggestions.forEach { suggestion ->
                appendLine("- $suggestion")
            }
        }.trim()
    }

    private fun unwrapShellWrappedCommand(command: String): String {
        val trimmed = command.trim()
        val match = Regex("""^(?:/system/bin/)?(?:bash|sh)\s+-l?c\s+(['"])(.*)\1$""")
            .matchEntire(trimmed)
            ?: return trimmed
        return match.groupValues[2].trim().ifBlank { trimmed }
    }

    private fun extractLeadingCdCommand(command: String): CdCommandRewrite? {
        val trimmed = command.trim()
        val match = Regex("""^cd\s+((?:'[^']*'|"[^"]*"|[^\s;&|]+))\s*(?:&&|;)\s*(.+)$""")
            .matchEntire(trimmed)
            ?: return null
        val rawDirectory = match.groupValues[1].trim()
        val remainder = match.groupValues[2].trim()
        if (remainder.isBlank()) {
            return null
        }

        return CdCommandRewrite(
            workdir = unquoteShellToken(rawDirectory),
            command = remainder
        )
    }

    private fun unquoteShellToken(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2) {
            if ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
                (trimmed.startsWith('\'') && trimmed.endsWith('\''))
            ) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }

    private fun parseGradleTerminalCommand(
        toolCall: AIToolCall,
        command: String,
        workdirOverride: String? = null
    ): AIToolCall? {
        val tokens = tokenizeCommand(command)
        if (tokens.isEmpty()) {
            return null
        }

        val executableIndex = when {
            isGradleExecutableToken(tokens.first()) -> 0
            tokens.size >= 2 &&
                tokens.first() in setOf("sh", "bash") &&
                isGradleExecutableToken(tokens[1]) -> 1
            else -> return null
        }

        val trailingTokens = tokens.drop(executableIndex + 1)
        val tasks = trailingTokens.filterNot(::isGradleArgumentToken)
        if (tasks.isEmpty()) {
            return null
        }
        val args = trailingTokens.filter(::isGradleArgumentToken)

        return AIToolCall(
            callId = toolCall.callId,
            name = "build_project",
            arguments = buildMap {
                put("tasks", tasks.joinToString(" "))
                put("args", args.joinToString(" "))
                workdirOverride
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("workdir", it) }
                    ?: toolCall.argument("workdir")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("workdir", it) }
            },
            rawBlock = toolCall.rawBlock,
            rawArgumentsJson = toolCall.rawArgumentsJson
        )
    }

    private fun tokenizeCommand(command: String): List<String> {
        return command.trim()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun isGradleExecutableToken(token: String): Boolean {
        val normalized = token.trim().substringAfterLast('/').substringAfterLast('\\')
        return normalized in GRADLE_TERMINAL_TOKENS
    }

    private fun looksLikeGradleCommand(command: String): Boolean {
        val tokens = tokenizeCommand(command)
        if (tokens.isEmpty()) {
            return false
        }
        return isGradleExecutableToken(tokens.first()) ||
            (tokens.size >= 2 &&
                tokens.first() in setOf("sh", "bash") &&
                isGradleExecutableToken(tokens[1]))
    }

    private fun isGradleArgumentToken(token: String): Boolean {
        return token.matches(Regex("--[A-Za-z0-9_.-]+(=.*)?")) ||
            token.matches(Regex("-[A-Za-z]+")) ||
            token.matches(Regex("-P[A-Za-z0-9_.-]+=.+")) ||
            token.matches(Regex("-D[A-Za-z0-9_.-]+=.+"))
    }

    private fun normalizeReplacementContent(rawContent: String): String {
        val normalized = rawContent.replace("\r\n", "\n")
        val lines = normalized.lines()
        val firstContentIndex = lines.indexOfFirst { it.isNotBlank() }
        val lastContentIndex = lines.indexOfLast { it.isNotBlank() }

        if (firstContentIndex >= 0 && lastContentIndex > firstContentIndex) {
            val firstLine = lines[firstContentIndex].trim()
            val lastLine = lines[lastContentIndex].trim()
            if (firstLine.startsWith("```") && lastLine == "```") {
                return lines.subList(firstContentIndex + 1, lastContentIndex).joinToString("\n")
            }
        }

        return normalized.trimEnd('\n', '\r')
    }

    private fun isSearchableTextFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() > MAX_TEXT_FILE_BYTES) {
            return false
        }

        return try {
            file.inputStream().use { input ->
                val probe = ByteArray(1024)
                val bytesRead = input.read(probe)
                if (bytesRead <= 0) {
                    return true
                }

                for (index in 0 until bytesRead) {
                    if (probe[index].toInt() == 0) {
                        return false
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildPathPatternMatcher(pattern: String): (String) -> Boolean {
        val normalizedPattern = pattern.trim().replace('\\', '/')
        val hasWildcards = normalizedPattern.contains('*') || normalizedPattern.contains('?')
        if (hasWildcards) {
            val compiledRegex = wildcardToRegex(normalizedPattern)
            return { value: String -> compiledRegex.matches(value.replace('\\', '/')) }
        }

        return { value: String -> value.replace('\\', '/').contains(normalizedPattern, ignoreCase = true) }
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val builder = StringBuilder("^")
        pattern.forEach { character ->
            when (character) {
                '*' -> builder.append(".*")
                '?' -> builder.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    builder.append('\\').append(character)
                }
                else -> builder.append(character)
            }
        }
        builder.append('$')
        return Regex(builder.toString(), setOf(RegexOption.IGNORE_CASE))
    }

    private fun truncateLine(line: String, maxChars: Int = 240): String {
        val singleLine = line.trim()
        return if (singleLine.length <= maxChars) {
            singleLine
        } else {
            singleLine.take(maxChars - 3) + "..."
        }
    }

    private fun resolveProjectFile(rawPath: String?, allowMissing: Boolean): File? {
        val projectRoot = projectRootProvider()?.canonicalFile ?: return null
        val path = rawPath?.trim().orEmpty()
        if (path.isBlank()) {
            return null
        }

        val candidate = if (File(path).isAbsolute) {
            File(path)
        } else {
            File(projectRoot, path)
        }
        val canonical = try {
            candidate.canonicalFile
        } catch (_: Exception) {
            return null
        }

        val rootPath = projectRoot.absolutePath
        val candidatePath = canonical.absolutePath
        val withinProject = candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
        if (!withinProject) {
            return null
        }

        if (!allowMissing && !canonical.exists()) {
            return null
        }
        if (canonical.exists() && !canonical.isFile) {
            return null
        }

        return canonical
    }

    private fun shouldSkipDirectory(directory: File, projectRoot: File): Boolean {
        if (directory.absolutePath == projectRoot.absolutePath) {
            return false
        }
        return directory.name in IGNORED_DIRECTORY_NAMES
    }

    private fun toRelativeProjectPath(file: File, projectRoot: File): String {
        return file.relativeTo(projectRoot).path.replace(File.separatorChar, '/')
    }

    private fun parseBoundedInt(
        rawValue: String?,
        defaultValue: Int,
        minValue: Int,
        maxValue: Int
    ): Int {
        val parsed = rawValue?.trim()?.toIntOrNull() ?: return defaultValue.coerceIn(minValue, maxValue)
        return parsed.coerceIn(minValue, maxValue)
    }

    private fun parseBuildTasks(rawTasks: String?): List<String> {
        return rawTasks.orEmpty()
            .split(',', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseGradleArgs(rawArgs: String?): List<String> {
        return rawArgs.orEmpty()
            .split(' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter(::isGradleArgumentToken)
    }

    private fun resolveWorkingDirectory(rawWorkdir: String?): File {
        val value = rawWorkdir?.trim().orEmpty()
        val projectRoot = projectRootProvider()
        return when {
            value.equals("PROJECT_ROOT", ignoreCase = true) && projectRoot != null -> projectRoot
            value.equals("HOME", ignoreCase = true) -> Environment.HOME
            value.equals("PREFIX", ignoreCase = true) -> Environment.PREFIX
            value.isNotBlank() && File(value).exists() -> File(value)
            value.isNotBlank() && projectRoot != null -> {
                val projectRelative = File(projectRoot, value)
                if (projectRelative.exists()) {
                    projectRelative
                } else {
                    val homeRelative = File(Environment.HOME, value)
                    if (homeRelative.exists()) {
                        homeRelative
                    } else {
                        projectRoot
                    }
                }
            }
            projectRoot != null -> projectRoot
            else -> Environment.HOME
        }
    }

    private fun runCommand(
        command: String,
        workingDirectory: File,
        timeoutSeconds: Long,
        onOutputLine: ((String) -> Unit)?
    ): CommandExecution {
        val shell = when {
            Environment.BASH_SHELL.exists() -> Environment.BASH_SHELL.absolutePath
            else -> "/system/bin/sh"
        }
        val shellArgs = if (shell.endsWith("bash")) {
            listOf(shell, "-lc", command)
        } else {
            listOf(shell, "-c", command)
        }

        val processBuilder = ProcessBuilder(shellArgs)
        processBuilder.directory(workingDirectory)
        processBuilder.redirectErrorStream(true)

        val environment = processBuilder.environment()
        environment.putAll(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false))

        val customEnvironment = HashMap<String, String>()
        Environment.putEnvironment(customEnvironment, false)
        environment.putAll(customEnvironment)
        ensurePathEnvironment(environment)

        val process = processBuilder.start()
        val outputBuffer = RollingOutputBuffer(MAX_OUTPUT_CHARS)
        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    outputBuffer.appendLine(line)
                    onOutputLine?.invoke(line)
                }
            }
        }
        readerThread.start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            val timeoutLine = "[TIMEOUT] Command exceeded ${timeoutSeconds}s and was terminated."
            outputBuffer.appendLine(timeoutLine)
            onOutputLine?.invoke(timeoutLine)
        }

        readerThread.join(2_000)

        return CommandExecution(
            exitCode = if (completed) process.exitValue() else -1,
            output = outputBuffer.build()
        )
    }

    private fun ensurePathEnvironment(environment: MutableMap<String, String>) {
        val currentPath = environment["PATH"].orEmpty()
        val requiredEntries = listOf(
            Environment.BIN_DIR.absolutePath,
            File(Environment.PREFIX, "bin").absolutePath
        )

        val merged = linkedSetOf<String>()
        requiredEntries.forEach { entry ->
            if (entry.isNotBlank()) {
                merged.add(entry)
            }
        }

        currentPath.split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(merged::add)

        environment["PATH"] = merged.joinToString(":")
        environment["TMPDIR"] = Environment.TMP_DIR.absolutePath
    }
}

private fun String?.toBooleanStrictOrFalse(): Boolean {
    return this?.trim()?.equals("true", ignoreCase = true) == true
}

private data class CommandExecution(
    val exitCode: Int,
    val output: String
)

private data class PreparedTerminalCommand(
    val command: String,
    val workdirHint: String?,
    val rewriteNote: String? = null
)

private data class CdCommandRewrite(
    val workdir: String,
    val command: String
)

private class RollingOutputBuffer(
    private val maxChars: Int
) {
    private val lines = ArrayDeque<String>()
    private var totalChars = 0

    fun appendLine(line: String) {
        val normalized = if (line.length > maxChars) {
            line.takeLast(maxChars)
        } else {
            line
        }

        lines.addLast(normalized)
        totalChars += normalized.length + 1

        while (totalChars > maxChars && lines.isNotEmpty()) {
            val removed = lines.removeFirst()
            totalChars -= removed.length + 1
        }
    }

    fun build(): String {
        return lines.joinToString("\n").trim()
    }
}
