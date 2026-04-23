package com.jjuicy.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jjuicy.intellij.GGProcessManager
import com.jjuicy.intellij.data.GGRepository
import com.jjuicy.intellij.data.GG_LOG_CHANGED
import com.jjuicy.intellij.data.RevId
import com.jjuicy.intellij.data.RevSet
import com.jjuicy.intellij.ui.detail.GGDetailPanel
import com.jjuicy.intellij.ui.log.GGLogPanel
import com.jjuicy.intellij.ui.log.GG_REVISION_SELECTED
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

private val LOG = logger<GGMainPanel>()

/**
 * Root panel for the jjuicy tool window.
 *
 * Shows a loading state while the backend is starting, then switches to a
 * horizontal split: [GGLogPanel] on the left, [GGDetailPanel] on the right.
 */
class GGMainPanel(
    private val project: Project,
    private val processManager: GGProcessManager,
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()), GGProcessManager.ProcessListener {

    private val logPanel = GGLogPanel(project)
    private val detailPanel = GGDetailPanel(project)
    private var lastSelectedId: RevId? = null
    private var splitter: JBSplitter? = null

    init {
        showLoadingState()
        processManager.addListener(this)

        // refresh on SSE events from backend
        project.messageBus.connect(parentDisposable).subscribe(GG_LOG_CHANGED, object : GGRepository.LogListener {
            override fun onLogChanged() {
                val id = lastSelectedId
                logPanel.loadLog()  // keeps current revset
                if (id != null) logPanel.reselectRevision(id)
            }
        })

        // detail updates when selection changes
        project.messageBus.connect(parentDisposable).subscribe(GG_REVISION_SELECTED, object : GGLogPanel.SelectionListener {
            override fun onRevisionSelected(set: RevSet) {
                lastSelectedId = set.from
                detailPanel.showRevision(set)
            }
        })
    }

    override fun onReady(url: String) {
        ApplicationManager.getApplication().invokeLater {
            LOG.info("gg web ready at $url, initialising native UI")
            val repo = GGRepository.getInstance(project)
            repo.initialize(url)
            showContent()
            // query_workspace must be called first — it triggers OpenWorkspace on the backend
            // worker, transitioning it from WorkerSession to WorkspaceSession so that
            // subsequent QueryLog requests are handled correctly.
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val config = repo.loadWorkspace()
                    val initialRevset = when (config) {
                        is com.jjuicy.intellij.data.RepoConfig.Workspace -> config.latest_query
                        else -> null
                    }
                    ApplicationManager.getApplication().invokeLater {
                        logPanel.loadLog(initialRevset)
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to load workspace config", e)
                    ApplicationManager.getApplication().invokeLater {
                        logPanel.loadLog(null)
                    }
                }
            }
        }
    }

    override fun onCrashed() {
        ApplicationManager.getApplication().invokeLater {
            showMessage("The jjuicy backend crashed.\nUse the notification to restart it.")
        }
    }

    fun dispose() {
        processManager.removeListener(this)
    }

    // --- Layout helpers ---

    private fun showLoadingState() {
        removeAll()
        add(label("Starting jjuicy…"), BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun showMessage(text: String) {
        removeAll()
        add(label(text), BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun showContent() {
        removeAll()
        val toolbar = buildToolbar()
        val s = JBSplitter(false, 0.40f).apply {
            firstComponent = logPanel
            secondComponent = detailPanel
            dividerWidth = 3
        }
        splitter = s
        add(toolbar.component, BorderLayout.NORTH)
        add(s, BorderLayout.CENTER)
        revalidate(); repaint()

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                updateSplitterOrientation()
            }
        })
        updateSplitterOrientation()
    }

    private fun updateSplitterOrientation() {
        val s = splitter ?: return
        val isVertical = width < 500
        if (s.isVertical != isVertical) {
            s.orientation = isVertical
            s.proportion = if (isVertical) 0.50f else 0.40f
        }
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(ActionManager.getInstance().getAction("GG.Refresh") ?: PlaceholderAction("Refresh"))
            add(ActionManager.getInstance().getAction("GG.Undo") ?: PlaceholderAction("Undo"))
            addSeparator()
            add(ActionManager.getInstance().getAction("GG.Push") ?: PlaceholderAction("Push"))
            add(ActionManager.getInstance().getAction("GG.Fetch") ?: PlaceholderAction("Fetch"))
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = this
        return toolbar
    }

    private fun label(text: String): JLabel = JBLabel(
        "<html>${text.replace("\n", "<br>")}</html>",
        SwingConstants.CENTER
    ).apply {
        font = font.deriveFont(Font.PLAIN, 13f)
        border = JBUI.Borders.empty(16)
    }

    /** Placeholder action shown when an action ID is not yet registered. */
    private class PlaceholderAction(name: String) : AnAction(name) {
        override fun actionPerformed(e: AnActionEvent) {}
    }
}
