package com.jjuicy.intellij.ui.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.jjuicy.intellij.data.GGRepository
import com.jjuicy.intellij.data.RevId
import com.jjuicy.intellij.data.RevSet
import com.intellij.util.messages.Topic
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.ListSelectionEvent

private val LOG = logger<GGLogPanel>()

/** Published when the user selects a row in the log table. */
val GG_REVISION_SELECTED: Topic<GGLogPanel.SelectionListener> =
    Topic.create("GG Revision Selected", GGLogPanel.SelectionListener::class.java)

/**
 * The commit graph panel.
 *
 * Shows a JBTable with a single column rendered by [GGGraphCellRenderer].
 * Selection changes broadcast on [GG_REVISION_SELECTED].
 */
class GGLogPanel(private val project: Project) : JPanel(BorderLayout()) {

    interface SelectionListener {
        fun onRevisionSelected(set: RevSet)
    }

    val tableModel = GGLogTableModel()
    val table = JBTable(tableModel).apply {
        setDefaultRenderer(EnhancedLogRow::class.java, GGGraphCellRenderer(tableModel))
        rowHeight = GGGraphPainter.ROW_HEIGHT
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tableHeader.isVisible = false
        setShowGrid(false)
        intercellSpacing = JBUI.size(0, 0)
        border = BorderFactory.createEmptyBorder()
        setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS)
    }

    private val scrollPane = JBScrollPane(table)
    private var currentRevset = ""
    private var isLoadingMore = false
    private lateinit var revsetField: JTextField

    init {
        add(buildHeaderPanel(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Selection → publish to message bus
        table.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
            if (!e.valueIsAdjusting) {
                val row = table.selectedRow
                if (row >= 0 && row < tableModel.rowCount) {
                    val enh = tableModel.getEnhancedRow(row)
                    val id = enh.row.revision.id
                    val set = RevSet(from = id, to = id)
                    project.messageBus.syncPublisher(GG_REVISION_SELECTED).onRevisionSelected(set)
                }
            }
        }

        // Load more rows when scrolled to bottom
        scrollPane.viewport.addChangeListener {
            if (!isLoadingMore && tableModel.hasMore && isScrolledToBottom()) {
                loadNextPage()
            }
        }
    }

    private fun buildHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(2, 4)
        revsetField = JTextField(currentRevset)
        revsetField.addActionListener {
            currentRevset = revsetField.text
            loadLog()
        }
        val label = JLabel("Revset: ")
        panel.add(label, BorderLayout.WEST)
        panel.add(revsetField, BorderLayout.CENTER)
        return panel
    }

    /** Load (or reload) the log. Pass a non-null [revset] to also update the displayed revset field. */
    fun loadLog(revset: String? = null) {
        if (revset != null) {
            currentRevset = revset
            revsetField.text = revset
        }
        if (currentRevset.isBlank()) return  // backend not ready yet
        tableModel.markLoading()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repo = GGRepository.getInstance(project)
                val page = repo.loadLog(currentRevset)
                ApplicationManager.getApplication().invokeLater {
                    tableModel.setPage(page)
                    // auto-select first row
                    if (tableModel.rowCount > 0) {
                        table.selectionModel.setSelectionInterval(0, 0)
                    }
                }
                // load remaining pages
                var current = page
                while (current.has_more) {
                    val next = repo.loadNextPage()
                    ApplicationManager.getApplication().invokeLater {
                        tableModel.appendPage(next)
                    }
                    current = next
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load log", e)
                ApplicationManager.getApplication().invokeLater {
                    tableModel.appendPage(com.jjuicy.intellij.data.LogPage(emptyList(), false))
                }
            }
        }
    }

    private fun loadNextPage() {
        if (isLoadingMore) return
        isLoadingMore = true

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val page = GGRepository.getInstance(project).loadNextPage()
                ApplicationManager.getApplication().invokeLater {
                    tableModel.appendPage(page)
                    isLoadingMore = false
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load next page", e)
                isLoadingMore = false
            }
        }
    }

    /** Reselect by commit ID or fall back to change ID after a reload. */
    fun reselectRevision(previousId: RevId) {
        var idx = tableModel.indexOf(previousId.commit.hex)
        if (idx < 0) idx = tableModel.indexOfChange(previousId.change.hex)
        if (idx >= 0) {
            table.selectionModel.setSelectionInterval(idx, idx)
            table.scrollRectToVisible(table.getCellRect(idx, 0, true))
        } else if (tableModel.rowCount > 0) {
            table.selectionModel.setSelectionInterval(0, 0)
        }
    }

    private fun isScrolledToBottom(): Boolean {
        val vp = scrollPane.viewport
        val viewRect = vp.viewRect
        val viewSize = vp.viewSize
        return viewRect.y + viewRect.height >= viewSize.height - GGGraphPainter.ROW_HEIGHT
    }
}
