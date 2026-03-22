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

package com.tom.rv2ide.actions

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tom.rv2ide.R
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.projects.internal.ProjectManagerImpl
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.services.builder.gradleDistributionParams
import com.tom.rv2ide.utils.DialogUtils
import com.tom.rv2ide.utils.ILogger
import com.tom.rv2ide.utils.flashError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** @see openApplicationModuleChooser */
fun openApplicationModuleChooser(
    data: ActionData,
    callback: (AndroidModule) -> Unit,
) = openApplicationModuleChooser(data.requireContext(), callback)

/**
 * Shows a dialog to let the user choose between Android application modules in case the project has
 * multiple subproject with `com.android.application` plugin. If the project contains only a single
 * application module, it is selected by default and the dialog is not shown to the user.
 *
 * @param
 */
fun openApplicationModuleChooser(
    context: Context,
    callback: (AndroidModule) -> Unit,
) {
  val applications = currentApplicationModules()

  if (applications.isEmpty()) {
    val lifecycleOwner = context as? LifecycleOwner
    val manager = IProjectManager.getInstance() as? ProjectManagerImpl
    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
    if (
        lifecycleOwner != null &&
            manager != null &&
            buildService != null &&
            buildService.isToolingServerStarted() &&
            !buildService.isBuildInProgress
    ) {
      lifecycleOwner.lifecycleScope.launch {
        val refreshedApplications =
            withContext(Dispatchers.IO) {
              try {
                manager.refreshProjectModel(buildService, gradleDistributionParams)
                currentApplicationModules()
              } catch (error: Throwable) {
                ILogger.ROOT.warn(
                    "Failed to refresh project model before resolving application modules.",
                    error,
                )
                emptyList()
              }
            }

        if (refreshedApplications.isEmpty()) {
          flashError(R.string.msg_launch_failure_no_app_module)
          ILogger.ROOT.error("Cannot run application. No application modules found in project.")
          return@launch
        }

        showApplicationModuleChooser(context, refreshedApplications, callback)
      }
      return
    }

    flashError(R.string.msg_launch_failure_no_app_module)
    ILogger.ROOT.error("Cannot run application. No application modules found in project.")
    return
  }

  showApplicationModuleChooser(context, applications, callback)
}

private fun currentApplicationModules(): List<AndroidModule> {
  return IProjectManager.getInstance()
      .getWorkspace()
      ?.androidProjects()
      ?.filter(AndroidModule::isApplication)
      ?.toList()
      .orEmpty()
}

private inline fun showApplicationModuleChooser(
    context: Context,
    applications: List<AndroidModule>,
    crossinline callback: (AndroidModule) -> Unit,
) {
  if (applications.size == 1) {
    callback(applications.first())
    return
  }

  val builder =
      DialogUtils.newSingleChoiceDialog(
          context,
          context.getString(R.string.title_choose_application),
          applications.map { it.path }.toTypedArray(),
          0,
      ) { selection ->
        val app = applications[selection]
        ILogger.ROOT.info("Selected application: '{}'", app.path)
        callback(app)
      }

  builder.show()
}
