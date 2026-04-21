package com.jjuicy.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

private val LOG = logger<GGToolWindowFactory>()

/**
 * Creates the jjuicy sidebar tool window content.
 *
 * The tool window is only applicable when the project contains a Jujutsu
 * workspace (.jj/ directory).
 */
class GGToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean {
        return GGWorkspaceDetector.hasJjWorkspace(project)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        LOG.info("Creating jjuicy tool window for project ${project.name}")

        val processManager = GGProcessManager.getInstance(project)

        // create a disposable scoped to this tool window
        val panelDisposable = Disposer.newDisposable("jjuicy-browser-panel")
        Disposer.register(toolWindow.disposable, panelDisposable)

        val panel = GGBrowserPanel(processManager, panelDisposable)

        val content = ContentFactory.getInstance()
            .createContent(panel, null, false)
        content.setDisposer(Disposable {
            panel.dispose()
            Disposer.dispose(panelDisposable)
        })
        toolWindow.contentManager.addContent(content)
    }
}
