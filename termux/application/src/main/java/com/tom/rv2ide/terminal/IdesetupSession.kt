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

package com.tom.rv2ide.terminal

import android.content.Context
import com.termux.shared.file.FileUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession
import java.io.File
import org.slf4j.LoggerFactory

/**
 * [TermuxSession] implementation that is used to run the `idesetup` script during automatic
 * installation.
 *
 * @author Akash Yadav
 */
class IdesetupSession
private constructor(
    terminalSession: TerminalSession,
    executionCommand: ExecutionCommand,
    termuxSessionClient: TermuxSessionClient?,
    setStdoutOnExit: Boolean,
    private val tempFiles: List<File>,
) : TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit) {

  companion object {

        private val log = LoggerFactory.getLogger(IdesetupSession::class.java)

        @JvmStatic
        internal fun wrap(session: TermuxSession?, tempFiles: List<File>): IdesetupSession? {
            return session?.let { IdesetupSession(it, tempFiles) }
        }

        @JvmStatic
        internal fun createLaunchArtifacts(
            context: Context,
            postSetupCommand: String?
        ): IdesetupLaunchArtifacts? {
          return IdesetupLaunchScriptFactory.create(context, postSetupCommand)
        }
    }

  private constructor(
      src: TermuxSession,
      tempFiles: List<File>,
  ) : this(
      src.terminalSession,
      src.executionCommand,
      src.termuxSessionClient,
      src.isSetStdoutOnExit,
      tempFiles,
  )

  override fun finish() {
    super.finish()
    tempFiles.distinctBy { it.absolutePath }.forEach { file ->
      val error = FileUtils.deleteFile("idesetupScript", file.absolutePath, true)
      if (error != null) {
        log.error(error.errorLogString)
      }
    }
  }
}
