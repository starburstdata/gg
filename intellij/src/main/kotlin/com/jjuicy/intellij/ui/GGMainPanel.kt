package com.jjuicy.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jjuicy.intellij.data.GGRepository
import com.jjuicy.intellij.data.GG_LOG_CHANGED
import com.jjuicy.intellij.data.RevId
import com.jjuicy.intellij.data.RevSet
import com.jjuicy.intellij.ui.detail.GGDetailPanel
import com.jjuicy.intellij.ui.log.GGLogPanel
import com.jjuicy.intellij.ui.log.GG_REVISION_SELECTED
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

private val LOG = logger<GGMainPanel>()

/**
 * Root panel for the jjuicy tool window.
 *
 * Initialises immediately (no backend startup); shows an error label if jj
 * is not available. Splits into [GGLogPanel] and [GGDetailPanel].
 */
class GGMainPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()) {

    private val logPanel = GGLogPanel(project)
    private val detailPanel = GGDetailPanel(project)
    private var lastSelectedId: RevId? = null
    private var splitter: JBSplitter? = null

    init {
        val repo = GGRepository.getInstance(project)

        if (!repo.isReady) {
            showMessage("jj not found.\nInstall jj or set the path in Settings → Tools → jjuicy.")
        } else {
            showContent()
            repo.startWatching()

            // refresh on any detected repo change
            project.messageBus.connect(parentDisposable).subscribe(GG_LOG_CHANGED, object : GGRepository.LogListener {
                override fun onLogChanged() {
                    // pass lastSelectedId so reselection happens after the new data loads,
                    // not immediately against stale model data
                    logPanel.loadLog(reselectId = lastSelectedId)
                }
            })

            // detail updates when selection changes
            project.messageBus.connect(parentDisposable).subscribe(GG_REVISION_SELECTED, object : GGLogPanel.SelectionListener {
                override fun onRevisionSelected(set: RevSet) {
                    lastSelectedId = set.from
                    detailPanel.showRevision(set)
                }
            })

            // load initial log on a background thread
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val config = repo.loadWorkspace()
                    val initialRevset = when (config) {
                        is com.jjuicy.intellij.data.RepoConfig.Workspace -> config.latest_query
                        else -> "all()"
                    }
                    ApplicationManager.getApplication().invokeLater {
                        logPanel.loadLog(initialRevset)
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to load workspace config", e)
                    ApplicationManager.getApplication().invokeLater {
                        logPanel.loadLog("all()")
                    }
                }
            }
        }
    }

    fun dispose() {
        // parentDisposable handles subscriptions
    }

    // --- Layout helpers ---

    private fun showMessage(text: String) {
        removeAll()
        add(label(text), BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun showContent() {
        removeAll()
        val toolbar = buildToolbar()
        val s = JBSplitter(true, 0.50f).apply {
            firstComponent = logPanel
            secondComponent = detailPanel
            dividerWidth = 3
        }
        splitter = s
        add(toolbar.component, BorderLayout.NORTH)
        add(s, BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
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

    private class PlaceholderAction(name: String) : AnAction(name) {
        override fun actionPerformed(e: AnActionEvent) {}
    }
}
