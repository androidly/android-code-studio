package com.tom.rv2ide.fragments.assistant

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.ModificationData
import com.tom.rv2ide.activities.ReviewChangesActivity
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import java.io.File
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
    private lateinit var jumpToBottomButton: MaterialButton

    private val consoleViewModel: AIAssistantConsoleViewModel by activityViewModels()
    private val timelineAdapter by lazy { AIAssistantTimelineAdapter(::openDiffFile) }
    private var applyingPromptState = false
    private var userAtBottom = true
    private var hasAutoScrolledInitialState = false
    private var forceScrollOnNextTimelineUpdate = false
    private var unreadTimelineCount = 0
    private var latestUiState = AIAssistantConsoleUiState()
    private var pendingHistoryPrependAnchor: TimelinePrependAnchor? = null
    private var suppressUnreadForNextTimelineUpdate = false

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
        consoleViewModel.bindProject(getProjectRoot().absolutePath.toString())
        consoleViewModel.setFileRefreshHandler(::refreshCurrentEditorIfNeeded)
        consoleViewModel.ensureWelcome()
        bindUiState()
    }

    override fun onDestroyView() {
        consoleViewModel.setFileRefreshHandler(null)
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
        jumpToBottomButton = root.findViewById(R.id.jumpToBottomButton)
    }

    private fun setupTimeline() {
        timelineRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = timelineAdapter
            itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                addDuration = 160
                changeDuration = 0
                moveDuration = 120
                removeDuration = 100
            }
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val wasAtBottom = userAtBottom
                    userAtBottom = isAtBottom()
                    if (userAtBottom && !wasAtBottom) {
                        clearUnreadTimelineIndicator()
                    } else {
                        updateJumpToBottomButton()
                    }
                    maybeLoadOlderHistory(dy)
                }
            })
        }
        updateJumpToBottomButton()
    }

    private fun setupActions() {
        promptInput.doAfterTextChanged { editable ->
            if (!applyingPromptState) {
                consoleViewModel.updatePromptDraft(editable?.toString().orEmpty())
            }
        }

        sendButton.setOnClickListener {
            val prompt = promptInput.text?.toString().orEmpty().trim()
            if (prompt.isBlank()) {
                showSnackbar("Enter a request or use /help")
                return@setOnClickListener
            }

            if (handleSlashCommand(prompt)) {
                return@setOnClickListener
            }

            forceScrollOnNextTimelineUpdate = true
            consoleViewModel.executePrompt(prompt)
        }

        stopButton.setOnClickListener {
            consoleViewModel.stopExecution()
        }

        reviewButton.setOnClickListener {
            openReview()
        }

        clearButton.setOnClickListener {
            consoleViewModel.clearTimeline()
            showSnackbar("Assistant timeline cleared")
        }

        jumpToBottomButton.setOnClickListener {
            clearUnreadTimelineIndicator()
            forceScrollOnNextTimelineUpdate = true
            if (!consoleViewModel.collapseHistoryToLatest()) {
                scrollToBottom(force = true, smooth = true)
            }
        }
    }

    private fun bindUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                consoleViewModel.uiState.collect(::renderState)
            }
        }
    }

    private fun renderState(state: AIAssistantConsoleUiState) {
        latestUiState = state
        providerText.text = state.providerLabel
        modelText.text = state.modelLabel
        sendButton.isEnabled = !state.isRunning
        stopButton.isEnabled = state.isRunning
        promptInput.isEnabled = !state.isRunning
        reviewButton.isEnabled = state.hasReviewableChanges

        val currentDraft = promptInput.text?.toString().orEmpty()
        if (currentDraft != state.promptDraft) {
            applyingPromptState = true
            promptInput.setText(state.promptDraft)
            promptInput.setSelection(state.promptDraft.length)
            applyingPromptState = false
        }

        if (state.timelineItems.isEmpty()) {
            hasAutoScrolledInitialState = false
            userAtBottom = true
            forceScrollOnNextTimelineUpdate = false
            unreadTimelineCount = 0
            pendingHistoryPrependAnchor = null
            suppressUnreadForNextTimelineUpdate = false
            updateJumpToBottomButton()
        }

        if (timelineAdapter.getItemsSnapshot() != state.timelineItems) {
            val previousItems = timelineAdapter.getItemsSnapshot()
            val shouldAutoScroll = pendingHistoryPrependAnchor == null &&
                shouldAutoScroll(previousItems, state.timelineItems)
            timelineAdapter.replaceAll(state.timelineItems) {
                if (restoreHistoryPrependAnchorIfNeeded(state)) {
                    return@replaceAll
                }
                if (shouldAutoScroll) {
                    scrollToBottom(
                        force = forceScrollOnNextTimelineUpdate || !hasAutoScrolledInitialState,
                        smooth = forceScrollOnNextTimelineUpdate
                    )
                    clearUnreadTimelineIndicator()
                } else if (suppressUnreadForNextTimelineUpdate) {
                    suppressUnreadForNextTimelineUpdate = false
                    updateJumpToBottomButton()
                } else {
                    registerUnreadTimelineActivity(previousItems, state.timelineItems)
                }
            }
        } else {
            updateJumpToBottomButton()
        }
    }

    private fun handleSlashCommand(commandText: String): Boolean {
        val normalized = commandText.trim()
        return when {
            normalized.equals("/clear", ignoreCase = true) -> {
                consoleViewModel.clearTimeline()
                showSnackbar("Assistant timeline cleared")
                true
            }
            normalized.equals("/stop", ignoreCase = true) -> {
                consoleViewModel.stopExecution()
                true
            }
            normalized.equals("/review", ignoreCase = true) -> {
                openReview()
                true
            }
            normalized.equals("/help", ignoreCase = true) -> {
                consoleViewModel.showCommandsHelp()
                true
            }
            else -> false
        }
    }

    private fun scrollToBottom(
        force: Boolean = false,
        smooth: Boolean = false
    ) {
        timelineRecyclerView.post {
            val count = timelineAdapter.itemCount
            if (count <= 0) {
                forceScrollOnNextTimelineUpdate = false
                return@post
            }
            if (!force && !isAtBottom()) {
                forceScrollOnNextTimelineUpdate = false
                return@post
            }
            timelineRecyclerView.stopScroll()
            if (smooth) {
                timelineRecyclerView.smoothScrollToPosition(count - 1)
            } else {
                timelineRecyclerView.scrollBy(0, timelineRecyclerView.computeVerticalScrollRange())
                if (timelineRecyclerView.canScrollVertically(1)) {
                    timelineRecyclerView.scrollToPosition(count - 1)
                    timelineRecyclerView.scrollBy(0, timelineRecyclerView.computeVerticalScrollRange())
                }
            }
            hasAutoScrolledInitialState = true
            userAtBottom = true
            forceScrollOnNextTimelineUpdate = false
            updateJumpToBottomButton()
        }
    }

    private fun shouldAutoScroll(
        oldItems: List<AIAssistantTimelineItem>,
        newItems: List<AIAssistantTimelineItem>
    ): Boolean {
        if (newItems.isEmpty()) {
            return false
        }
        if (forceScrollOnNextTimelineUpdate) {
            return true
        }
        if (oldItems.isEmpty()) {
            return true
        }
        val timelineChanged = oldItems.size != newItems.size || oldItems.lastOrNull() != newItems.lastOrNull()
        if (!timelineChanged) {
            return false
        }
        return forceScrollOnNextTimelineUpdate || userAtBottom || isAtBottom()
    }

    private fun registerUnreadTimelineActivity(
        oldItems: List<AIAssistantTimelineItem>,
        newItems: List<AIAssistantTimelineItem>
    ) {
        if (newItems.isEmpty() || isAtBottom()) {
            clearUnreadTimelineIndicator()
            return
        }
        val previousIds = oldItems.asSequence()
            .map(AIAssistantTimelineItem::id)
            .toHashSet()
        val addedCount = newItems.count { item -> item.id !in previousIds }
        unreadTimelineCount = when {
            addedCount > 0 -> (unreadTimelineCount + addedCount).coerceAtMost(99)
            unreadTimelineCount == 0 -> 1
            else -> unreadTimelineCount
        }
        updateJumpToBottomButton()
    }

    private fun clearUnreadTimelineIndicator() {
        unreadTimelineCount = 0
        updateJumpToBottomButton()
    }

    private fun updateJumpToBottomButton() {
        if (!this::jumpToBottomButton.isInitialized || !this::timelineRecyclerView.isInitialized) {
            return
        }
        val atBottom = isAtBottom()
        if (atBottom && unreadTimelineCount > 0) {
            unreadTimelineCount = 0
        }
        val shouldShow = !atBottom || unreadTimelineCount > 0
        jumpToBottomButton.isVisible = shouldShow
        if (!shouldShow) {
            return
        }
        jumpToBottomButton.text = if (unreadTimelineCount > 0) {
            "${if (unreadTimelineCount >= 99) "99+" else unreadTimelineCount} new"
        } else {
            "Latest"
        }
    }

    private fun maybeLoadOlderHistory(scrollDeltaY: Int) {
        if (!this::timelineRecyclerView.isInitialized) {
            return
        }
        if (scrollDeltaY >= 0) {
            return
        }
        if (pendingHistoryPrependAnchor != null || !latestUiState.canLoadMoreHistory) {
            return
        }
        if (timelineRecyclerView.canScrollVertically(-1)) {
            return
        }

        val layoutManager = timelineRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePosition == RecyclerView.NO_POSITION || firstVisiblePosition > 1) {
            return
        }

        val anchor = captureHistoryPrependAnchor(layoutManager) ?: return
        pendingHistoryPrependAnchor = anchor
        suppressUnreadForNextTimelineUpdate = true
        if (!consoleViewModel.loadOlderHistory()) {
            pendingHistoryPrependAnchor = null
            suppressUnreadForNextTimelineUpdate = false
        }
    }

    private fun captureHistoryPrependAnchor(
        layoutManager: LinearLayoutManager
    ): TimelinePrependAnchor? {
        var anchorPosition = layoutManager.findFirstVisibleItemPosition()
        if (anchorPosition == RecyclerView.NO_POSITION) {
            return null
        }

        while (anchorPosition < timelineAdapter.itemCount) {
            val item = timelineAdapter.getItemAt(anchorPosition)
            if (item !is AIAssistantHistoryDividerItem) {
                break
            }
            anchorPosition += 1
        }

        if (anchorPosition !in 0 until timelineAdapter.itemCount) {
            return null
        }

        val anchorItem = timelineAdapter.getItemAt(anchorPosition) ?: return null
        val anchorView = layoutManager.findViewByPosition(anchorPosition) ?: return null
        return TimelinePrependAnchor(
            itemId = anchorItem.id,
            topOffset = anchorView.top
        )
    }

    private fun restoreHistoryPrependAnchorIfNeeded(
        state: AIAssistantConsoleUiState
    ): Boolean {
        val anchor = pendingHistoryPrependAnchor ?: return false
        pendingHistoryPrependAnchor = null
        suppressUnreadForNextTimelineUpdate = false

        val layoutManager = timelineRecyclerView.layoutManager as? LinearLayoutManager ?: return false
        val anchorPosition = state.timelineItems.indexOfFirst { it.id == anchor.itemId }
        if (anchorPosition < 0) {
            updateJumpToBottomButton()
            return true
        }

        timelineRecyclerView.post {
            layoutManager.scrollToPositionWithOffset(anchorPosition, anchor.topOffset)
            userAtBottom = isAtBottom()
            updateJumpToBottomButton()
        }
        return true
    }

    private fun isAtBottom(): Boolean {
        if (!timelineRecyclerView.isAttachedToWindow) {
            return true
        }
        if (timelineAdapter.itemCount <= 0) {
            return true
        }
        return !timelineRecyclerView.canScrollVertically(1)
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
        val modifications = consoleViewModel.lastModificationsSnapshot()
        if (modifications.isEmpty()) {
            showSnackbar("No changes to review yet")
            return
        }

        val reviewPayload = ArrayList(
            modifications.map {
                ModificationData(
                    filePath = it.filePath,
                    content = it.content,
                    isNewFile = it.isNewFile
                )
            }
        )

        startActivity(
            Intent(requireContext(), ReviewChangesActivity::class.java)
                .putParcelableArrayListExtra("modifications", reviewPayload)
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

    private data class TimelinePrependAnchor(
        val itemId: Long,
        val topOffset: Int
    )
}
