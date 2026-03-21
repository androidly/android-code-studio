package com.tom.rv2ide.fragments.assistant

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.ModificationData
import com.tom.rv2ide.activities.ReviewChangesActivity
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import com.tom.rv2ide.fragments.assistant.AIAssistantDiffPreview.Companion.fromContents
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AIAssistantConsoleFragment : Fragment() {

    private lateinit var providerText: MaterialTextView
    private lateinit var modelText: MaterialTextView
    private lateinit var timelineRecyclerView: RecyclerView
    private lateinit var promptInput: TextInputEditText
    private lateinit var sendButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var reviewButton: MaterialButton
    private lateinit var clearButton: MaterialButton

    private val aiAgent by lazy { AIAgentManager(requireContext()) }
    private val timelineAdapter by lazy { AIAssistantTimelineAdapter(::openDiffFile) }

    private var executionJob: Job? = null
    private var lastModifications: List<AIAgentManager.ModificationResult> = emptyList()
    private var projectRootPath: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_assistant_console, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupTimeline()
        setupActions()
        loadProject()
        refreshProviderStatus()
        if (savedInstanceState == null) {
            showWelcome()
        }
        updateActionState(isRunning = false)
    }

    override fun onResume() {
        super.onResume()
        refreshProviderStatus()
    }

    override fun onDestroyView() {
        executionJob?.cancel()
        super.onDestroyView()
    }

    private fun bindViews(root: View) {
        providerText = root.findViewById(R.id.providerText)
        modelText = root.findViewById(R.id.modelText)
        timelineRecyclerView = root.findViewById(R.id.timelineRecyclerView)
        promptInput = root.findViewById(R.id.promptInput)
        sendButton = root.findViewById(R.id.sendButton)
        stopButton = root.findViewById(R.id.stopButton)
        reviewButton = root.findViewById(R.id.reviewButton)
        clearButton = root.findViewById(R.id.clearButton)
    }

    private fun setupTimeline() {
        timelineRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = false
            }
            adapter = timelineAdapter
            itemAnimator = null
        }
    }

    private fun setupActions() {
        sendButton.setOnClickListener {
            val prompt = promptInput.text?.toString().orEmpty().trim()
            if (prompt.isBlank()) {
                showSnackbar("Enter a request or use /help")
                return@setOnClickListener
            }

            promptInput.text?.clear()
            if (handleSlashCommand(prompt)) {
                return@setOnClickListener
            }

            executePrompt(prompt)
        }

        stopButton.setOnClickListener {
            cancelExecution(manualStop = true)
        }

        reviewButton.setOnClickListener {
            openReview()
        }

        clearButton.setOnClickListener {
            cancelExecution(manualStop = false)
            lastModifications = emptyList()
            aiAgent.clearConversation()
            timelineAdapter.clearAll()
            showWelcome()
            updateActionState(isRunning = false)
            showSnackbar("Assistant timeline cleared")
        }
    }

    private fun loadProject() {
        projectRootPath = getProjectRoot().absolutePath.toString()
        if (projectRootPath.isNotBlank()) {
            aiAgent.setProjectRoot(projectRootPath)
        }
    }

    private fun refreshProviderStatus() {
        providerText.text = aiAgent.getCurrentProviderName()
        modelText.text = aiAgent.getCurrentModelName()
    }

    private fun showWelcome() {
        timelineAdapter.append(
            AIAssistantWelcomeItem(
                title = "AI Assistant",
                body = "Use natural language or slash commands. Try /help, /review, /clear, or ask it to search files, edit ranges, and build."
            )
        )
        scrollToBottom()
    }

    private fun handleSlashCommand(commandText: String): Boolean {
        val normalized = commandText.trim()
        return when {
            normalized.equals("/clear", ignoreCase = true) -> {
                clearButton.performClick()
                true
            }
            normalized.equals("/stop", ignoreCase = true) -> {
                stopButton.performClick()
                true
            }
            normalized.equals("/review", ignoreCase = true) -> {
                openReview()
                true
            }
            normalized.equals("/help", ignoreCase = true) -> {
                timelineAdapter.append(
                    AIAssistantStatusItem(
                        title = "Commands",
                        body = "/help  /review  /stop  /clear",
                        tone = AIAssistantTone.NEUTRAL
                    )
                )
                scrollToBottom()
                true
            }
            else -> false
        }
    }

    private fun executePrompt(prompt: String) {
        executionJob?.cancel()
        lastModifications = emptyList()
        refreshProviderStatus()
        timelineAdapter.append(AIAssistantUserItem(prompt))
        timelineAdapter.append(
            AIAssistantStatusItem(
                title = "Queued",
                body = "Preparing assistant run",
                tone = AIAssistantTone.RUNNING
            )
        )
        scrollToBottom()
        updateActionState(isRunning = true)

        executionJob = lifecycleScope.launch {
            try {
                aiAgent.executeRequest(prompt, object : AIAgentManager.AIAgentCallback {
                    override fun onProcessing(message: String) {
                        appendOnMain(
                            AIAssistantStatusItem(
                                title = "Status",
                                body = message,
                                tone = AIAssistantTone.RUNNING
                            )
                        )
                    }

                    override fun onFileModifying(filePath: String, fileName: String) {
                        appendOnMain(
                            AIAssistantStatusItem(
                                title = "Editing $fileName",
                                body = filePath,
                                tone = AIAssistantTone.RUNNING
                            )
                        )
                    }

                    override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                        appendOnMain(
                            AIAssistantStatusItem(
                                title = if (success) "Updated $fileName" else "Failed to update $fileName",
                                body = filePath,
                                tone = if (success) AIAssistantTone.SUCCESS else AIAssistantTone.ERROR
                            )
                        )

                        if (success) {
                            refreshCurrentEditorIfNeeded(filePath)
                        }
                    }

                    override fun onSuccess(
                        response: String,
                        modifications: List<AIAgentManager.ModificationResult>,
                        summary: AIAgentManager.ModificationSummary
                    ) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            lastModifications = modifications
                            appendDiffItems(modifications)
                            val finalResponse = response.takeIf {
                                it.isNotBlank() && !it.contains("FILE_TO_MODIFY:")
                            } ?: buildString {
                                append("Applied ${summary.successfulFiles}/${summary.totalFiles} file changes")
                                if (summary.failedFiles > 0) {
                                    append(" (${summary.failedFiles} failed)")
                                }
                            }
                            timelineAdapter.append(AIAssistantResponseItem(finalResponse))
                            updateActionState(isRunning = false)
                            scrollToBottom()
                        }
                    }

                    override fun onTextResponse(response: String, summary: AIAgentManager.ModificationSummary) {
                        appendOnMain(AIAssistantResponseItem(response), finishRun = true)
                    }

                    override fun onError(message: String) {
                        appendOnMain(
                            AIAssistantStatusItem(
                                title = "Error",
                                body = message,
                                tone = AIAssistantTone.ERROR
                            ),
                            finishRun = true
                        )
                    }

                    override fun onRetry(attemptNumber: Int, message: String) {
                        appendOnMain(
                            AIAssistantStatusItem(
                                title = "Retry #$attemptNumber",
                                body = message,
                                tone = AIAssistantTone.WARNING
                            )
                        )
                    }

                    override fun onToolCallStarted(toolCall: AIToolCall) {
                        appendOnMain(
                            AIAssistantToolItem(
                                title = "Tool · ${toolCall.name}",
                                summary = toolCallSummary(toolCall),
                                workingDirectory = toolCall.argument("workdir"),
                                tone = AIAssistantTone.RUNNING
                            )
                        )
                    }

                    override fun onToolCallCompleted(result: AIToolExecutionResult) {
                        appendOnMain(
                            AIAssistantToolItem(
                                title = buildString {
                                    append("Tool ")
                                    append(if (result.success) "✓" else "✕")
                                    append(" · ")
                                    append(result.toolName)
                                },
                                summary = result.summary,
                                command = result.executedCommand,
                                workingDirectory = result.workingDirectory,
                                outputPreview = result.output.takeIf { it.isNotBlank() }?.trim()?.takeLast(800),
                                tone = if (result.success) AIAssistantTone.SUCCESS else AIAssistantTone.ERROR
                            )
                        )
                    }
                })
            } catch (_: CancellationException) {
                appendOnMain(
                    AIAssistantStatusItem(
                        title = "Stopped",
                        body = "Assistant run was cancelled",
                        tone = AIAssistantTone.WARNING
                    ),
                    finishRun = true
                )
            } catch (error: Exception) {
                appendOnMain(
                    AIAssistantStatusItem(
                        title = "Exception",
                        body = error.message ?: "Unexpected error",
                        tone = AIAssistantTone.ERROR
                    ),
                    finishRun = true
                )
            }
        }
    }

    private fun toolCallSummary(toolCall: AIToolCall): String {
        return when (toolCall.name.lowercase()) {
            "find_files", "search_project" -> toolCall.argument("pattern").orEmpty()
            "read_file_range", "replace_file_range" -> listOfNotNull(
                toolCall.argument("file"),
                toolCall.argument("start_line")?.let { start ->
                    val end = toolCall.argument("end_line") ?: "?"
                    "lines $start-$end"
                }
            ).joinToString(" · ")
            "build_project" -> toolCall.argument("tasks").orEmpty()
            "run_terminal_command" -> toolCall.argument("command").orEmpty()
            else -> toolCall.rawBlock
        }.ifBlank { toolCall.rawBlock }
    }

    private fun appendDiffItems(modifications: List<AIAgentManager.ModificationResult>) {
        val diffItems = modifications.map { modification ->
            val preview = fromContents(modification.previousContent, modification.content)
            AIAssistantDiffItem(
                filePath = modification.filePath,
                changeLabel = if (modification.isNewFile) "Created" else "Updated",
                preview = preview
            )
        }
        timelineAdapter.appendAll(diffItems)
    }

    private fun appendOnMain(
        item: AIAssistantTimelineItem,
        finishRun: Boolean = false
    ) {
        lifecycleScope.launch(Dispatchers.Main) {
            timelineAdapter.append(item)
            if (finishRun) {
                updateActionState(isRunning = false)
            }
            scrollToBottom()
        }
    }

    private fun updateActionState(isRunning: Boolean) {
        sendButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        promptInput.isEnabled = !isRunning
        reviewButton.isEnabled = lastModifications.isNotEmpty()
    }

    private fun cancelExecution(manualStop: Boolean) {
        executionJob?.cancel()
        executionJob = null
        updateActionState(isRunning = false)
        if (manualStop) {
            timelineAdapter.append(
                AIAssistantStatusItem(
                    title = "Stopped",
                    body = "Manual stop requested",
                    tone = AIAssistantTone.WARNING
                )
            )
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        timelineRecyclerView.post {
            val count = timelineAdapter.itemCount
            if (count > 0) {
                timelineRecyclerView.scrollToPosition(count - 1)
            }
        }
    }

    private fun openDiffFile(item: AIAssistantDiffItem) {
        val activity = activity as? EditorHandlerActivity ?: return
        val targetFile = File(item.filePath)
        if (!targetFile.exists()) {
            showSnackbar("File not found: ${item.fileName}")
            return
        }
        activity.openFile(targetFile)
        showSnackbar("Opened ${item.fileName}")
    }

    private fun openReview() {
        if (lastModifications.isEmpty()) {
            showSnackbar("No changes to review yet")
            return
        }

        val modifications = ArrayList(
            lastModifications.map {
                ModificationData(
                    filePath = it.filePath,
                    content = it.content,
                    isNewFile = it.isNewFile
                )
            }
        )

        startActivity(
            Intent(requireContext(), ReviewChangesActivity::class.java)
                .putParcelableArrayListExtra("modifications", modifications)
        )
    }

    private fun refreshCurrentEditorIfNeeded(filePath: String) {
        val activity = activity as? EditorHandlerActivity ?: return
        val currentEditor = activity.getCurrentEditor() ?: return
        val currentFile = currentEditor.file ?: return
        if (currentFile.absolutePath != filePath) {
            return
        }

        try {
            val newContent = File(filePath).readText()
            val editorText = currentEditor.editor?.text ?: return
            editorText.replace(0, editorText.length, newContent)
        } catch (_: Exception) {
        }
    }

    private fun showSnackbar(message: String) {
        val anchorView = activity?.findViewById<View>(android.R.id.content) ?: view ?: return
        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()
    }
}
