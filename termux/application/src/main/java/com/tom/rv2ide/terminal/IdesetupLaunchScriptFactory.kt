package com.tom.rv2ide.terminal

import android.content.Context
import com.termux.shared.file.FileUtils
import com.tom.rv2ide.app.configuration.IDEBuildConfigProvider
import com.tom.rv2ide.managers.ToolsManager
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.slf4j.LoggerFactory

internal data class IdesetupLaunchArtifacts(
    val executable: File,
    val tempFiles: List<File>
)

internal object IdesetupLaunchScriptFactory {

    private val log = LoggerFactory.getLogger(IdesetupLaunchScriptFactory::class.java)
    private const val IDESETUP_BINARY_NAME = "idesetup"

    fun create(
        context: Context,
        postSetupCommand: String?
    ): IdesetupLaunchArtifacts? {
        val launchDir = createLaunchDirectory(context) ?: return null
        val binary = createBinaryScript(context, launchDir) ?: return null
        val queuedCommand = postSetupCommand?.trim().orEmpty()
        val wrapper = createWrapperScript(
            launchDir = launchDir,
            binary = binary,
            postSetupCommand = queuedCommand
        ) ?: return null
        return IdesetupLaunchArtifacts(
            executable = wrapper,
            tempFiles = listOf(wrapper, binary, launchDir)
        )
    }

    private fun createLaunchDirectory(context: Context): File? {
        val tempDir = File(context.filesDir, "temp").apply { mkdirs() }
        val launchDir = File(tempDir, "idesetup_${UUID.randomUUID().toString().replace('-', '_')}")
        if (launchDir.exists() || launchDir.mkdirs()) {
            return launchDir
        }
        log.error("Failed to create idesetup launch directory: {}", launchDir.absolutePath)
        return null
    }

    private fun createBinaryScript(context: Context, launchDir: File): File? {
        val script = File(launchDir, IDESETUP_BINARY_NAME)
        if (!writeIdesetupBinary(context, script)) {
            return null
        }
        FileUtils.setFilePermissions("idesetupBinary", script.absolutePath, "rwx")
        return script
    }

    private fun createWrapperScript(
        launchDir: File,
        binary: File,
        postSetupCommand: String
    ): File? {
        val wrapper = File(launchDir, "idesetup_wrapper.sh")
        val shellPath = Environment.BASH_SHELL.takeIf { it.exists() }?.absolutePath ?: "/system/bin/sh"
        val scriptContent = buildString {
            appendLine("#!$shellPath")
            appendLine("set +e")
            appendLine("export PATH=${shellQuote(launchDir.absolutePath)}:\"${'$'}PATH\"")
            appendLine("idesetup_binary=${shellQuote(binary.absolutePath)}")
            appendLine("\"${'$'}idesetup_binary\" \"${'$'}@\"")
            if (postSetupCommand.isBlank()) {
                appendLine("exit \"${'$'}?\"")
                return@buildString
            }
            appendLine("setup_status=${'$'}?")
            appendLine("if [ \"${'$'}setup_status\" -ne 0 ]; then")
            appendLine("  echo")
            appendLine("  echo \"[AndroidCodeStudio] IDE setup finished with exit code ${'$'}setup_status.\"")
            appendLine("  exit \"${'$'}setup_status\"")
            appendLine("fi")
            appendLine("echo")
            appendLine("echo \"[AndroidCodeStudio] IDE setup finished. Starting queued Codex CLI installation...\"")
            appendLine(postSetupCommand)
            appendLine("post_status=${'$'}?")
            appendLine("echo")
            appendLine("echo \"[AndroidCodeStudio] Queued Codex CLI installation finished with exit code ${'$'}post_status.\"")
            appendLine("exit \"${'$'}post_status\"")
        }
        return try {
            FileOutputStream(wrapper).use { outputStream ->
                outputStream.write(scriptContent.toByteArray())
            }
            FileUtils.setFilePermissions("idesetupWrapper", wrapper.absolutePath, "rwx")
            wrapper
        } catch (error: Exception) {
            log.error("Failed to write idesetup wrapper script: {}", error.message, error)
            null
        }
    }

    private fun writeIdesetupBinary(
        context: Context,
        target: File
    ): Boolean {
        return try {
            val folderName = when (IDEBuildConfigProvider.getInstance().cpuArch) {
                com.tom.rv2ide.app.configuration.CpuArch.AARCH64 -> "arm64"
                com.tom.rv2ide.app.configuration.CpuArch.ARM -> "arm"
                com.tom.rv2ide.app.configuration.CpuArch.X86_64 -> "x86_64"
                com.tom.rv2ide.app.configuration.CpuArch.X86 -> "x86"
            }
            context.assets.open(ToolsManager.getCommonAsset("${folderName}/idesetup")).use { inputStream ->
                FileOutputStream(target).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (error: Exception) {
            log.error("Failed to write idesetup binary: {}", error.message, error)
            false
        }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
