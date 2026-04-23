package com.jjuicy.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.jjuicy.intellij.ui.GGMainPanel

private val LOG = logger<GGToolWindowFactory>()

/**
 * Creates the jjuicy sidebar tool window with a completely native Swing UI.
 *
 * The tool window is only shown for projects that contain a Jujutsu workspace.
 */
class GGToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        GGWorkspaceDetector.hasJjWorkspace(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        LOG.info("Creating jjuicy native tool window for project ${project.name}")

        val panelDisposable = Disposer.newDisposable("jjuicy-main-panel")
        Disposer.register(toolWindow.disposable, panelDisposable)

        val panel = GGMainPanel(project, panelDisposable)

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(Disposable {
            panel.dispose()
            Disposer.dispose(panelDisposable)
        })
        toolWindow.contentManager.addContent(content)
    }
}
