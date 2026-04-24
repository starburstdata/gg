package com.jjuicy.intellij.ui.detail

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jjuicy.intellij.data.*
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*

private val LOG = logger<GGDetailPanel>()
private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Revision detail pane: author, status, changed files.
 */
class GGDetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    // --- UI components ---

    private val noSelectionLabel = JBLabel("Select a revision to view details", SwingConstants.CENTER).apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.ITALIC)
    }

    private val authorLabel = JBLabel()
    private val statusLabel = JBLabel()

    private val changedFilesPanel = GGChangedFilesTree(project)

    private val contentPanel = JPanel(BorderLayout())

    // Current state
    private var currentRevId: RevId? = null
    private var currentIsImmutable = true
    private var currentIsWorkingCopy = false

    init {
        add(noSelectionLabel, BorderLayout.CENTER)

        // build content panel (shown when a revision is selected)
        contentPanel.add(buildMetaPanel(), BorderLayout.NORTH)
        contentPanel.add(changedFilesPanel, BorderLayout.CENTER)
        contentPanel.border = JBUI.Borders.empty(4)
    }

    fun showRevision(set: RevSet) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = GGRepository.getInstance(project).loadRevisions(set)
                ApplicationManager.getApplication().invokeLater {
                    applyResult(result)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load revision detail", e)
            }
        }
    }

    fun clear() {
        currentRevId = null
        changedFilesPanel.clear()
        removeAll()
        add(noSelectionLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun applyResult(result: RevsResult) {
        when (result) {
            is RevsResult.NotFound -> clear()
            is RevsResult.Detail -> {
                val header = result.headers.firstOrNull() ?: run { clear(); return }
                currentRevId = header.id
                currentIsImmutable = header.is_immutable
                currentIsWorkingCopy = header.is_working_copy

                authorLabel.text = buildAuthorText(header)
                statusLabel.text = buildStatusText(header)

                changedFilesPanel.setChanges(header.id, result.changes, emptyList())

                if (contentPanel.parent == null) {
                    removeAll()
                    add(contentPanel, BorderLayout.CENTER)
                }
                revalidate()
                repaint()
            }
        }
    }

    private fun buildMetaPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.emptyBottom(4)
        val gc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0; gridy = 0
        }

        panel.add(authorLabel, gc)
        gc.gridy++; panel.add(statusLabel, gc)

        return panel
    }

    private fun buildAuthorText(h: RevHeader): String {
        val ts = try {
            OffsetDateTime.parse(h.author.timestamp).format(TS_FMT)
        } catch (_: Exception) { h.author.timestamp.take(19) }
        return "${h.author.name} <${h.author.email}>  $ts"
    }

    private fun buildStatusText(h: RevHeader): String {
        val parts = mutableListOf<String>()
        if (h.is_working_copy) parts += "working copy"
        if (h.is_immutable) parts += "immutable"
        if (h.has_conflict) parts += "⚠ conflict"
        return parts.joinToString("  ·  ")
    }

}
