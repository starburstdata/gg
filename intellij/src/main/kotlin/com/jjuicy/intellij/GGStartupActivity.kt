package com.jjuicy.intellij

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LOG = logger<GGStartupActivity>()

/**
 * Runs when a project opens. If a .jj/ workspace is present, opens the tool window.
 *
 * No backend process is started — the plugin calls `jj` CLI directly on demand.
 */
class GGStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!GGWorkspaceDetector.hasJjWorkspace(project)) return

        LOG.info("Jujutsu workspace detected for project ${project.name}")
        withContext(Dispatchers.EDT) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("jjuicy")
            if (toolWindow != null && !toolWindow.isVisible) {
                toolWindow.show()
            }
        }
    }
}
