package com.tom.rv2ide.fragments.assistant

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.ModificationData
import com.tom.rv2ide.activities.ReviewChangesActivity
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.custom.CustomProviderConfig
import com.tom.rv2ide.artificial.agents.custom.CustomProviderProfile
import com.tom.rv2ide.artificial.agents.external.CodexCliConfig
import com.tom.rv2ide.artificial.agents.external.CodexTermuxBridge
import com.tom.rv2ide.artificial.dialogs.CodexCliConfigDialog
import com.tom.rv2ide.artificial.dialogs.CustomProviderConfigDialog
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import java.io.File
import kotlinx.coroutines.launch

class AIAssistantConsoleFragment : Fragment() {

    private var _providerText: MaterialTextView? = null
    private val providerText: MaterialTextView
        get() = requireNotNull(_providerText)
    private var _modelText: MaterialTextView? = null
    private val modelText: MaterialTextView
        get() = requireNotNull(_modelText)
    private var _sessionText: TextView? = null
    private val sessionText: TextView
        get() = requireNotNull(_sessionText)
    private var _timelineRecyclerView: RecyclerView? = null
    private val timelineRecyclerView: RecyclerView
        get() = requireNotNull(_timelineRecyclerView)
    private var _promptInput: TextInputEditText? = null
    private val promptInput: TextInputEditText
        get() = requireNotNull(_promptInput)
    private var _engineButton: MaterialButton? = null
    private val engineButton: MaterialButton
        get() = requireNotNull(_engineButton)
    private var _slashButton: MaterialButton? = null
    private val slashButton: MaterialButton
        get() = requireNotNull(_slashButton)
    private var _sendButton: MaterialButton? = null
    private val sendButton: MaterialButton
        get() = requireNotNull(_sendButton)
    private var _stopButton: MaterialButton? = null
    private val stopButton: MaterialButton
        get() = requireNotNull(_stopButton)
    private var _reviewButton: MaterialButton? = null
    private val reviewButton: MaterialButton
        get() = requireNotNull(_reviewButton)
    private var _clearButton: MaterialButton? = null
    private val clearButton: MaterialButton
        get() = requireNotNull(_clearButton)
    private var _jumpToBottomButton: MaterialButton? = null
    private val jumpToBottomButton: MaterialButton
        get() = requireNotNull(_jumpToBottomButton)

    private val consoleViewModel: AIAssistantConsoleViewModel by activityViewModels()
    private val timelineAdapter by lazy {
        AIAssistantTimelineAdapter(
            onDiffClicked = ::openDiffFile,
            onSessionSwitchRequested = ::handleSessionSwitchRequest,
            onSessionDeleteRequested = ::handleSessionDeleteRequest
        )
    }
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
        _timelineRecyclerView?.apply {
            stopScroll()
            clearOnScrollListeners()
            recycledViewPool.clear()
            itemAnimator = null
            adapter = null
        }
        timelineAdapter.replaceAll(emptyList())
        AIAssistantRichTextRenderer.clearCaches(cancelJobs = true)
        _providerText = null
        _modelText = null
        _sessionText = null
        _timelineRecyclerView = null
        _promptInput = null
        _engineButton = null
        _slashButton = null
        _sendButton = null
        _stopButton = null
        _reviewButton = null
        _clearButton = null
        _jumpToBottomButton = null
        super.onDestroyView()
    }

    private fun bindViews(root: View) {
        _providerText = root.findViewById(R.id.providerText)
        _modelText = root.findViewById(R.id.modelText)
        _sessionText = root.findViewById(R.id.sessionText)
        _timelineRecyclerView = root.findViewById(R.id.timelineRecyclerView)
        _promptInput = root.findViewById(R.id.promptInput)
        _engineButton = root.findViewById(R.id.engineButton)
        _slashButton = root.findViewById(R.id.slashButton)
        _sendButton = root.findViewById(R.id.sendButton)
        _stopButton = root.findViewById(R.id.stopButton)
        _reviewButton = root.findViewById(R.id.reviewButton)
        _clearButton = root.findViewById(R.id.clearButton)
        _jumpToBottomButton = root.findViewById(R.id.jumpToBottomButton)
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
            submitPrompt(interruptActiveRun = false)
        }

        sendButton.setOnLongClickListener {
            submitPrompt(interruptActiveRun = true)
            true
        }

        engineButton.setOnClickListener {
            when (Agents(requireContext()).getProvider()) {
                "external" -> openCodexConfig(selectAfterSave = false)
                "custom" -> openCustomProviderConfig(selectAfterSave = false)
                else -> showProviderSwitcher()
            }
        }

        slashButton.setOnClickListener {
            showSlashCommandSheet()
        }

        providerText.setOnClickListener {
            showProviderSwitcher()
        }

        stopButton.setOnClickListener {
            val clearedQueuedPrompts = consoleViewModel.stopExecution()
            if (clearedQueuedPrompts > 0) {
                showSnackbar("Stopped the current run and cleared $clearedQueuedPrompts queued messages")
            }
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
        val providerId = Agents(requireContext()).getProvider()
        providerText.text = when {
            providerId == "custom" -> providerDisplayName(providerId)
            state.providerLabel.isNotBlank() -> state.providerLabel
            else -> providerDisplayName(providerId)
        }
        modelText.text = state.modelLabel.ifBlank { getString(R.string.ai_assistant_preferences_not_set) }
        sessionText.isVisible = state.sessionLabel.isNotBlank()
        sessionText.text = if (state.sessionLabel.isBlank()) {
            ""
        } else {
            buildString {
                append("Session: ${state.sessionLabel}")
                if (state.queuedPromptCount > 0) {
                    append("  •  queued ${state.queuedPromptCount}")
                }
            }
        }
        engineButton.text = getString(R.string.ai_assistant_console_configure)
        sendButton.isEnabled = true
        stopButton.isEnabled = state.isRunning
        stopButton.text = if (state.isRunning && state.queuedPromptCount > 0) {
            "Stop (${state.queuedPromptCount})"
        } else {
            "Stop"
        }
        promptInput.isEnabled = true
        sendButton.contentDescription = if (state.isRunning) {
            "Queue request. Long press to interrupt the active run."
        } else {
            "Send request"
        }
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

        val previousItems = timelineAdapter.getItemsSnapshot()
        if (previousItems != state.timelineItems) {
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

    private fun showProviderSwitcher() {
        val providerIds = listOf("external", "custom")
        val providerNames = providerIds.map(::providerDisplayName).toTypedArray()
        val selectedProviderId = Agents(requireContext()).getProvider()
        val selectedIndex = providerIds.indexOf(selectedProviderId).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_assistant_select_provider_title)
            .setSingleChoiceItems(providerNames, selectedIndex) { dialog, which ->
                dialog.dismiss()
                when (providerIds[which]) {
                    "external" -> switchToCodexProvider()
                    "custom" -> switchToCustomProvider()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun switchToCodexProvider() {
        if (!CodexCliConfig.hasValidConfig()) {
            openCodexConfig(selectAfterSave = true)
            return
        }

        CodexTermuxBridge.ensureManagedPreset(context = requireContext())
        val applied = consoleViewModel.applyAgentSelection(
            providerId = "external",
            preferredModel = CodexCliConfig.getModelId()
        )
        if (applied) {
            showSnackbar(getString(R.string.ai_assistant_switched_to_provider, providerDisplayName("external")))
        } else {
            showSnackbar(
                getString(
                    R.string.ai_assistant_no_valid_api_key,
                    providerDisplayName("external")
                )
            )
        }
    }

    private fun switchToCustomProvider() {
        val activeProfile = CustomProviderConfig.getActiveProfile()
        when {
            activeProfile == null -> openCustomProviderConfig(createNew = true, selectAfterSave = true)
            !activeProfile.isValid -> openCustomProviderConfig(profileId = activeProfile.id, selectAfterSave = true)
            else -> applyCustomProviderSelection(activeProfile)
        }
    }

    private fun openCodexConfig(selectAfterSave: Boolean) {
        CodexCliConfigDialog {
            CodexTermuxBridge.ensureManagedPreset(context = requireContext())
            val applied = consoleViewModel.applyAgentSelection(
                providerId = "external",
                preferredModel = CodexCliConfig.getModelId()
            )
            if (applied && selectAfterSave) {
                showSnackbar(
                    getString(
                        R.string.ai_assistant_switched_to_provider,
                        providerDisplayName("external")
                    )
                )
            } else {
                showSnackbar(getString(R.string.ai_assistant_codex_settings_saved))
            }
        }.show(parentFragmentManager, "CodexCliConfigDialog")
    }

    private fun openCustomProviderConfig(
        profileId: String? = CustomProviderConfig.getActiveProfileId().takeIf { it.isNotBlank() },
        createNew: Boolean = false,
        selectAfterSave: Boolean
    ) {
        CustomProviderConfigDialog(
            profileId = profileId,
            createNew = createNew
        ) { savedProfile ->
            applyCustomProviderSelection(savedProfile, selectAfterSave)
        }.show(parentFragmentManager, "CustomProviderConfigDialog")
    }

    private fun applyCustomProviderSelection(
        profile: CustomProviderProfile,
        selectAfterSave: Boolean = true
    ) {
        val applied = consoleViewModel.applyAgentSelection(
            providerId = "custom",
            preferredModel = profile.modelId
        )
        if (applied && selectAfterSave) {
            showSnackbar(
                getString(
                    R.string.ai_assistant_custom_profile_active,
                    profile.name
                )
            )
        } else if (applied) {
            showSnackbar(getString(R.string.ai_assistant_custom_profile_active, profile.name))
        } else {
            showSnackbar(
                getString(
                    R.string.ai_assistant_no_valid_api_key,
                    providerDisplayName("custom")
                )
            )
        }
    }

    private fun providerDisplayName(providerId: String): String {
        return when (providerId) {
            "external" -> getString(R.string.ai_assistant_provider_codex_cli)
            "custom" -> getString(R.string.ai_assistant_provider_custom)
            "gemini" -> getString(R.string.ai_assistant_provider_gemini)
            "openai" -> getString(R.string.ai_assistant_provider_openai)
            "claude" -> getString(R.string.ai_assistant_provider_claude)
            "deepseek" -> getString(R.string.ai_assistant_provider_deepseek)
            "grok" -> getString(R.string.ai_assistant_provider_grok)
            "localllm" -> getString(R.string.ai_assistant_provider_local_llm)
            else -> providerId.uppercase()
        }
    }

    private fun handleSlashCommand(commandText: String): Boolean {
        val normalized = commandText.trim()
        val command = normalized.substringBefore(' ').lowercase()
        val argument = normalized.substringAfter(' ', missingDelimiterValue = "").trim()
        return when (command) {
            "/new" -> {
                forceScrollOnNextTimelineUpdate = true
                val createdLabel = consoleViewModel.createNewSession(argument.ifBlank { null })
                if (createdLabel == null) {
                    showSnackbar("Unable to create a new session")
                } else {
                    showSnackbar("Started $createdLabel")
                }
                true
            }
            "/list", "/sessions" -> {
                forceScrollOnNextTimelineUpdate = true
                consoleViewModel.showSessionList()
                true
            }
            "/switch" -> {
                if (argument.isBlank()) {
                    forceScrollOnNextTimelineUpdate = true
                    consoleViewModel.showSessionList()
                    showSnackbar("Use /switch <number> or pick it from the / menu")
                } else {
                    forceScrollOnNextTimelineUpdate = true
                    val switchedLabel = consoleViewModel.switchSession(argument)
                    if (switchedLabel == null) {
                        consoleViewModel.showSessionList(query = argument)
                        showSnackbar("Session not found: $argument")
                    } else {
                        showSnackbar("Switched to $switchedLabel")
                    }
                }
                true
            }
            "/clear" -> {
                consoleViewModel.clearTimeline()
                showSnackbar("Assistant timeline cleared")
                true
            }
            "/history" -> {
                forceScrollOnNextTimelineUpdate = true
                consoleViewModel.showHistory(argument.toIntOrNull() ?: 10)
                true
            }
            "/search" -> {
                if (argument.isBlank()) {
                    showSnackbar("Use /search <keyword>")
                } else {
                    forceScrollOnNextTimelineUpdate = true
                    consoleViewModel.showSessionList(query = argument)
                }
                true
            }
            "/delete" -> {
                forceScrollOnNextTimelineUpdate = true
                if (argument.isBlank()) {
                    consoleViewModel.showSessionList(emphasizeDelete = true)
                } else {
                    val result = consoleViewModel.deleteSessions(argument)
                    showSnackbar(result.message)
                }
                true
            }
            "/status" -> {
                forceScrollOnNextTimelineUpdate = true
                consoleViewModel.showStatus()
                true
            }
            "/compress" -> {
                forceScrollOnNextTimelineUpdate = true
                consoleViewModel.requestConversationCompression()
                true
            }
            "/memory" -> {
                forceScrollOnNextTimelineUpdate = true
                consoleViewModel.showMemoryCommand(argument)
                true
            }
            "/stop" -> {
                val clearedQueuedPrompts = consoleViewModel.stopExecution()
                if (clearedQueuedPrompts > 0) {
                    showSnackbar("Stopped the current run and cleared $clearedQueuedPrompts queued messages")
                }
                true
            }
            "/review" -> {
                openReview()
                true
            }
            "/help" -> {
                forceScrollOnNextTimelineUpdate = true
                consoleViewModel.showCommandsHelp()
                true
            }
            else -> false
        }
    }

    private fun handleSessionSwitchRequest(target: String) {
        forceScrollOnNextTimelineUpdate = true
        val switchedLabel = consoleViewModel.switchSession(target)
        if (switchedLabel == null) {
            consoleViewModel.showSessionList(query = target)
            showSnackbar("Session not found: $target")
        } else {
            showSnackbar("Switched to $switchedLabel")
        }
    }

    private fun handleSessionDeleteRequest(target: String) {
        forceScrollOnNextTimelineUpdate = true
        val result = consoleViewModel.deleteSessions(target)
        showSnackbar(result.message)
    }

    private fun scrollToBottom(
        force: Boolean = false,
        smooth: Boolean = false
    ) {
        val recyclerView = _timelineRecyclerView ?: return
        recyclerView.post {
            val count = timelineAdapter.itemCount
            if (count <= 0) {
                forceScrollOnNextTimelineUpdate = false
                return@post
            }
            if (!force && !isAtBottom()) {
                forceScrollOnNextTimelineUpdate = false
                return@post
            }
            recyclerView.stopScroll()
            if (smooth) {
                recyclerView.smoothScrollToPosition(count - 1)
            } else {
                recyclerView.scrollBy(0, recyclerView.computeVerticalScrollRange())
                if (recyclerView.canScrollVertically(1)) {
                    recyclerView.scrollToPosition(count - 1)
                    recyclerView.scrollBy(0, recyclerView.computeVerticalScrollRange())
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
        val jumpButton = _jumpToBottomButton ?: return
        if (_timelineRecyclerView == null) {
            return
        }
        val atBottom = isAtBottom()
        if (atBottom && unreadTimelineCount > 0) {
            unreadTimelineCount = 0
        }
        val shouldShow = !atBottom || unreadTimelineCount > 0
        jumpButton.isVisible = shouldShow
        if (!shouldShow) {
            return
        }
        jumpButton.text = if (unreadTimelineCount > 0) {
            "${if (unreadTimelineCount >= 99) "99+" else unreadTimelineCount} new"
        } else {
            "Latest"
        }
    }

    private fun maybeLoadOlderHistory(scrollDeltaY: Int) {
        if (_timelineRecyclerView == null) {
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

        val recyclerView = _timelineRecyclerView ?: return true
        recyclerView.post {
            layoutManager.scrollToPositionWithOffset(anchorPosition, anchor.topOffset)
            userAtBottom = isAtBottom()
            updateJumpToBottomButton()
        }
        return true
    }

    private fun isAtBottom(): Boolean {
        val recyclerView = _timelineRecyclerView ?: return true
        if (!recyclerView.isAttachedToWindow) {
            return true
        }
        if (timelineAdapter.itemCount <= 0) {
            return true
        }
        return !recyclerView.canScrollVertically(1)
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

        val reviewPayload = ArrayList(modifications)

        startActivity(
            Intent(requireContext(), ReviewChangesActivity::class.java)
                .putParcelableArrayListExtra("modifications", reviewPayload)
        )
    }

    private fun refreshCurrentEditorIfNeeded(filePath: String) {
        val activity = activity as? EditorHandlerActivity ?: return
        val targetFile = File(filePath).absoluteFile
        val openedEditor = activity.getEditorForFile(targetFile) ?: return
        val openedFile = openedEditor.file ?: return
        if (openedFile.absolutePath != targetFile.absolutePath) {
            return
        }

        openedEditor.reloadFromDiskIfUnmodified()
    }

    private fun showSnackbar(message: String) {
        val anchorView = activity?.findViewById<View>(android.R.id.content) ?: view ?: return
        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showSlashCommandSheet() {
        AIAssistantCommandSheetDialog { command ->
            when (command.behavior) {
                AIAssistantSlashCommandBehavior.EXECUTE -> {
                    if (handleSlashCommand(command.commandText)) {
                        clearPromptComposer()
                    }
                }
                AIAssistantSlashCommandBehavior.INSERT -> {
                    insertCommandTemplate(command.commandText)
                }
            }
        }.show(parentFragmentManager, "AIAssistantCommandSheetDialog")
    }

    private fun insertCommandTemplate(commandText: String) {
        promptInput.setText(commandText)
        promptInput.setSelection(commandText.length)
        promptInput.requestFocus()
        consoleViewModel.updatePromptDraft(commandText)
    }

    private fun clearPromptComposer() {
        promptInput.setText("")
        consoleViewModel.updatePromptDraft("")
    }

    private fun submitPrompt(interruptActiveRun: Boolean) {
        val prompt = promptInput.text?.toString().orEmpty().trim()
        if (prompt.isBlank()) {
            showSnackbar("Enter a request or use /help")
            return
        }

        if (handleSlashCommand(prompt)) {
            clearPromptComposer()
            return
        }

        forceScrollOnNextTimelineUpdate = true
        val result = if (interruptActiveRun) {
            consoleViewModel.interruptAndExecutePrompt(prompt)
        } else {
            consoleViewModel.executePrompt(prompt)
        }
        when (result) {
            AIAssistantPromptDispatchResult.STARTED -> Unit
            AIAssistantPromptDispatchResult.QUEUED ->
                showSnackbar("Queued behind the active run. Long press send to interrupt instead.")
            AIAssistantPromptDispatchResult.INTERRUPTED ->
                showSnackbar("Interrupted the active run. Your message moved to the front of the queue.")
            AIAssistantPromptDispatchResult.QUEUE_FULL ->
                showSnackbar("The queue is full. Stop the current run or wait for it to finish.")
            AIAssistantPromptDispatchResult.REJECTED_EMPTY ->
                showSnackbar("Enter a request or use /help")
        }
    }

    private data class TimelinePrependAnchor(
        val itemId: Long,
        val topOffset: Int
    )
}
