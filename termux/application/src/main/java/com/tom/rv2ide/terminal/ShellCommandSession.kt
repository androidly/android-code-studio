package com.tom.rv2ide.terminal

import android.content.Context
import com.termux.shared.file.FileUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.slf4j.LoggerFactory

class ShellCommandSession
private constructor(
    terminalSession: TerminalSession,
    executionCommand: ExecutionCommand,
    termuxSessionClient: TermuxSessionClient?,
    setStdoutOnExit: Boolean,
    private val script: File,
) : TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit) {

    companion object {
        private val log = LoggerFactory.getLogger(ShellCommandSession::class.java)

        @JvmStatic
        fun wrap(session: TermuxSession?, script: File): ShellCommandSession? {
            return session?.let { ShellCommandSession(it, script) }
        }

        @JvmStatic
        fun createScript(
            context: Context,
            command: String,
            keepShellOpen: Boolean
        ): File? {
            val tempDir = File(context.filesDir, "temp")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            val script = File(tempDir, "shell_command_${UUID.randomUUID().toString().replace('-', '_')}.sh")
            if (!writeCommandScript(script, command, keepShellOpen)) {
                return null
            }
            FileUtils.setFilePermissions("shellCommandScript", script.absolutePath, "rwx")
            return script
        }

        private fun writeCommandScript(
            script: File,
            command: String,
            keepShellOpen: Boolean
        ): Boolean {
            return try {
                val shellPath = Environment.BASH_SHELL.takeIf { it.exists() }?.absolutePath ?: "/system/bin/sh"
                val scriptContent = buildString {
                    appendLine("#!$shellPath")
                    appendLine("set +e")
                    appendLine(command.trim())
                    appendLine("status=$?")
                    appendLine("echo")
                    appendLine("echo \"[AndroidCodeStudio] Command finished with exit code ${'$'}status.\"")
                    if (keepShellOpen) {
                        appendLine("echo \"Interactive shell kept open below.\"")
                        appendLine("exec \"$shellPath\" -l")
                    } else {
                        appendLine("exit \"${'$'}status\"")
                    }
                }
                FileOutputStream(script).use { outputStream ->
                    outputStream.write(scriptContent.toByteArray())
                }
                true
            } catch (error: Exception) {
                log.error("Failed to write shell command script: {}", error.message, error)
                false
            }
        }
    }

    private constructor(
        src: TermuxSession,
        script: File,
    ) : this(
        src.terminalSession,
        src.executionCommand,
        src.termuxSessionClient,
        src.isSetStdoutOnExit,
        script,
    )

    override fun finish() {
        super.finish()
        val error = FileUtils.deleteFile("shellCommandScript", script.absolutePath, true)
        if (error != null) {
            log.error(error.errorLogString)
        }
    }
}
