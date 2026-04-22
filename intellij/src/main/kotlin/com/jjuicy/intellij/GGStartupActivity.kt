package com.jjuicy.intellij

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LOG = logger<GGStartupActivity>()

/**
 * Runs when a project opens. Checks for a .jj/ workspace and starts the
 * backend process if found.
 *
 * Also registers a VFS listener to detect .jj/ creation after project open
 * (e.g., when the user runs `jj init` in the terminal).
 */
class GGStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (GGWorkspaceDetector.hasJjWorkspace(project)) {
            LOG.info("Jujutsu workspace detected for project ${project.name}, starting gg web")
            activatePlugin(project)
        } else {
            LOG.info("No .jj/ workspace found for project ${project.name}")
        }

        // watch for .jj/ creation
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val hasNewJjDir = events.any { event ->
                        event is VFileCreateEvent &&
                            event.isDirectory &&
                            event.childName == ".jj"
                    }
                    if (hasNewJjDir && !GGProcessManager.getInstance(project).isReady) {
                        LOG.info(".jj/ directory created in project ${project.name}, starting gg web")
                        // process start is fine on any thread; tool window show needs EDT
                        GGProcessManager.getInstance(project).start()
                    }
                }
            }
        )
    }

    private suspend fun activatePlugin(project: Project) {
        // process start is safe on any thread
        GGProcessManager.getInstance(project).start()

        // tool window operations must run on the EDT
        withContext(Dispatchers.EDT) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("jjuicy")
            if (toolWindow != null && !toolWindow.isVisible) {
                toolWindow.show()
            }
        }
    }
}
