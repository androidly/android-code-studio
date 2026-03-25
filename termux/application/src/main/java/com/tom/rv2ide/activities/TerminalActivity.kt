/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.activities

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.tom.rv2ide.terminal.IdeTerminalSessionClient
import com.tom.rv2ide.terminal.IdesetupSession
import com.tom.rv2ide.terminal.ShellCommandSession
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.flashError
import org.slf4j.LoggerFactory

/** @author Akash Yadav */
class TerminalActivity : TermuxActivity() {

  override val navigationBarColor: Int
    get() = ContextCompat.getColor(this, android.R.color.black)

  override val statusBarColor: Int
    get() = ContextCompat.getColor(this, android.R.color.black)

  private var canAddNewSessions = true
    set(value) {
      field = value
      findViewById<View>(R.id.new_session_button)?.isEnabled = value
    }

  companion object {

    private val log = LoggerFactory.getLogger(TerminalActivity::class.java)
    private const val KEY_TERMINAL_CAN_ADD_SESSIONS = "ide.terminal.sessions.canAddSessions"

    const val EXTRA_ONBOARDING_RUN_IDESETUP = "ide.onboarding.terminal.runIdesetup"
    const val EXTRA_ONBOARDING_RUN_IDESETUP_ARGS = "ide.onboarding.terminal.runIdesetup.args"
    const val EXTRA_ONBOARDING_POST_SETUP_COMMAND = "ide.onboarding.terminal.postSetupCommand"
    const val EXTRA_SCRIPTED_SESSION_COMMAND = "ide.terminal.scripted.command"
    const val EXTRA_SCRIPTED_SESSION_NAME = "ide.terminal.scripted.name"
    const val EXTRA_SCRIPTED_SESSION_WORKDIR = "ide.terminal.scripted.workdir"
    const val EXTRA_SCRIPTED_SESSION_KEEP_OPEN = "ide.terminal.scripted.keepOpen"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightNavigationBars = false
    controller.isAppearanceLightStatusBars = false
    super.onCreate(savedInstanceState)
    canAddNewSessions = savedInstanceState?.getBoolean(KEY_TERMINAL_CAN_ADD_SESSIONS, true) ?: true
  }

  override fun onCreateTerminalSessionClient(): TermuxTerminalSessionActivityClient {
    return IdeTerminalSessionClient(this)
  }

  override fun onSaveInstanceState(savedInstanceState: Bundle) {
    super.onSaveInstanceState(savedInstanceState)
    savedInstanceState.putBoolean(KEY_TERMINAL_CAN_ADD_SESSIONS, canAddNewSessions)
  }

  override fun onServiceConnected(componentName: ComponentName?, service: IBinder?) {
    super.onServiceConnected(componentName, service)
    Environment.mkdirIfNotExits(Environment.TMP_DIR)
  }

  override fun onCreateNewSession(
      isFailsafe: Boolean,
      sessionName: String?,
      workingDirectory: String?,
  ) {
    if (canAddNewSessions) {
      super.onCreateNewSession(isFailsafe, sessionName, workingDirectory)
    } else {
      flashError(R.string.msg_terminal_new_sessions_disabled)
    }
  }

  override fun setupTermuxSessionOnServiceConnected(
      intent: Intent?,
      workingDir: String?,
      sessionName: String?,
      existingSession: TermuxSession?,
      launchFailsafe: Boolean,
  ) {
    if (intent != null) {
      val runIdesetup = intent.getBooleanExtra(EXTRA_ONBOARDING_RUN_IDESETUP, false)
      val runIdesetupArgs = intent.getStringArrayExtra(EXTRA_ONBOARDING_RUN_IDESETUP_ARGS)
      val postSetupCommand = intent.getStringExtra(EXTRA_ONBOARDING_POST_SETUP_COMMAND)
      if (runIdesetup && !runIdesetupArgs.isNullOrEmpty()) {
        addIdesetupSession(runIdesetupArgs, postSetupCommand)
        return
      }

      val scriptedCommand = intent.getStringExtra(EXTRA_SCRIPTED_SESSION_COMMAND)
      if (!scriptedCommand.isNullOrBlank()) {
        addScriptedCommandSession(
            command = scriptedCommand,
            sessionName = intent.getStringExtra(EXTRA_SCRIPTED_SESSION_NAME),
            workingDirectory = intent.getStringExtra(EXTRA_SCRIPTED_SESSION_WORKDIR),
            keepShellOpen = intent.getBooleanExtra(EXTRA_SCRIPTED_SESSION_KEEP_OPEN, false),
        )
        return
      }
    }

    super.setupTermuxSessionOnServiceConnected(
        intent,
        workingDir,
        sessionName,
        existingSession,
        launchFailsafe,
    )
  }

  private fun addIdesetupSession(
      args: Array<String>,
      postSetupCommand: String?
  ) {
    val launchArtifacts =
        IdesetupSession.createLaunchArtifacts(this, postSetupCommand)
            ?: run {
              log.error("Failed to add idesetup session. Cannot create script.")
              flashError(R.string.msg_cannot_create_terminal_session)
              return
            }

    Log.d("IdeSetupConfig", "buildIdeSetupArguments: ${args.joinToString(separator = " ")}")

    val session =
        IdesetupSession.wrap(
            termuxService.createTermuxSession(
                /* executablePath = */ launchArtifacts.executable.absolutePath,
                /* arguments = */ args,
                /* stdin = */ null,
                /* workingDirectory = */ Environment.HOME.absolutePath,
                /* isFailSafe = */ false,
                /* sessionName = */ "IDE setup",
            ),
            launchArtifacts.tempFiles,
        )

    session
        ?: run {
          flashError(R.string.msg_cannot_create_terminal_session)
          return
        }

    termuxTerminalSessionClient.setCurrentSession(session.terminalSession)
  }

  private fun addScriptedCommandSession(
      command: String,
      sessionName: String?,
      workingDirectory: String?,
      keepShellOpen: Boolean,
  ) {
    val script =
        ShellCommandSession.createScript(this, command, keepShellOpen)
            ?: run {
              log.error("Failed to add scripted terminal session. Cannot create script.")
              flashError(R.string.msg_cannot_create_terminal_session)
              return
            }

    val session =
        ShellCommandSession.wrap(
            termuxService.createTermuxSession(
                /* executablePath = */ script.absolutePath,
                /* arguments = */ emptyArray(),
                /* stdin = */ null,
                /* workingDirectory = */ workingDirectory ?: Environment.HOME.absolutePath,
                /* isFailSafe = */ false,
                /* sessionName = */ sessionName ?: "Command",
            ),
            script,
        )

    session
        ?: run {
          flashError(R.string.msg_cannot_create_terminal_session)
          return
        }

    termuxTerminalSessionClient.setCurrentSession(session.terminalSession)
  }
}
