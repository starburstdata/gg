package com.jjuicy.intellij.ui.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.jjuicy.intellij.actions.chooseRemote
import com.jjuicy.intellij.actions.performMutation
import com.jjuicy.intellij.data.*
import java.awt.Cursor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.SwingUtilities
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
        rowHeight = GGGraphPainter.BASE_ROW_HEIGHT
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tableHeader.isVisible = false
        setShowGrid(false)
        intercellSpacing = JBUI.size(0, 0)
        border = BorderFactory.createEmptyBorder()
        setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS)
    }

    private val scrollPane = JBScrollPane(table)
    private var currentRevset = ""
    private lateinit var revsetField: JTextField

    // bookmark drag state
    private var dragRef: StoreRef.LocalBookmark? = null
    private var dragSourceRow: Int = -1

    init {
        add(buildHeaderPanel(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // recompute wrapping when table width changes
        var lastKnownWidth = 0
        table.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                val newWidth = table.width
                if (newWidth != lastKnownWidth && newWidth > 0 && tableModel.rowCount > 0) {
                    lastKnownWidth = newWidth
                    recomputeRowHeights()
                }
            }
        })

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

        // Double-click to edit description; right-click for context menu; drag chips to move bookmarks
        val mouseHandler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) {
                        table.selectionModel.setSelectionInterval(row, row)
                        editDescription()
                    }
                }
            }
            override fun mousePressed(e: MouseEvent) {
                val hit = localBookmarkAtPoint(e.point)
                if (hit != null && !e.isPopupTrigger) {
                    dragRef = hit.second
                    dragSourceRow = hit.first
                    table.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    return
                }
                if (e.isPopupTrigger) showContextMenu(e, hit?.second)
            }
            override fun mouseReleased(e: MouseEvent) {
                val ref = dragRef
                if (ref != null) {
                    val targetRow = table.rowAtPoint(e.point)
                    if (targetRow >= 0 && targetRow != dragSourceRow) {
                        val targetHeader = tableModel.getEnhancedRow(targetRow).row.revision
                        runMutation("Move bookmark ${ref.bookmark_name}") {
                            GGRepository.getInstance(project).moveBookmark(ref, targetHeader.id)
                        }
                    }
                    dragRef = null
                    dragSourceRow = -1
                    table.cursor = Cursor.getDefaultCursor()
                    return
                }
                if (e.isPopupTrigger) showContextMenu(e, localBookmarkAtPoint(e.point)?.second)
            }
            override fun mouseDragged(e: MouseEvent) {
                if (dragRef == null) return
                val targetRow = table.rowAtPoint(e.point)
                if (targetRow >= 0 && targetRow != dragSourceRow) {
                    table.selectionModel.setSelectionInterval(targetRow, targetRow)
                }
            }
        }
        table.addMouseListener(mouseHandler)
        table.addMouseMotionListener(mouseHandler)

        // F2 to edit description of the selected revision
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_F2) {
                    editDescription()
                    e.consume()
                }
            }
        })
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

    /** Load (or reload) the log. Pass a non-null [revset] to also update the revset field.
     *  Pass [reselectId] to restore the previous selection after new data arrives; otherwise
     *  the first row is selected. */
    fun loadLog(revset: String? = null, reselectId: com.jjuicy.intellij.data.RevId? = null) {
        if (revset != null) {
            currentRevset = revset
            revsetField.text = revset
        }
        if (currentRevset.isBlank()) return
        tableModel.markLoading()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repo = GGRepository.getInstance(project)
                val page = repo.loadLog(currentRevset)
                ApplicationManager.getApplication().invokeLater {
                    tableModel.setPage(page)
                    recomputeRowHeights()
                    if (reselectId != null) {
                        reselectRevision(reselectId)
                    } else if (tableModel.rowCount > 0) {
                        table.selectionModel.setSelectionInterval(0, 0)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load log", e)
                ApplicationManager.getApplication().invokeLater {
                    tableModel.appendPage(LogPage(emptyList(), false))
                    recomputeRowHeights()
                }
            }
        }
    }

    /**
     * Compute row heights based on word-wrapped description text,
     * then apply them to both the model and the JBTable.
     */
    private fun recomputeRowHeights() {
        val tableWidth = table.width
        val fm = table.getFontMetrics(table.font)
        val lineHeight = fm.height

        for (i in 0 until tableModel.rowCount) {
            val row = tableModel.getEnhancedRow(i)
            val header = row.row.revision
            val graphWidth = GGGraphPainter.graphWidth(row.row.location.col)
            val availableWidth = if (tableWidth > 0) tableWidth - graphWidth - 12 else 400

            val descText = header.description.text.ifBlank { "(no description)" }
            val changeIdText = header.id.change.prefix + header.id.change.rest + "  "
            val changeIdWidth = fm.stringWidth(changeIdText)
            // rough estimate of bookmark chip width
            val bookmarkWidth = header.refs.sumOf { ref ->
                val name = when (ref) {
                    is StoreRef.LocalBookmark -> ref.bookmark_name
                    is StoreRef.RemoteBookmark -> "${ref.bookmark_name}@${ref.remote_name}"
                    is StoreRef.Tag -> ref.tag_name
                }
                fm.stringWidth(" $name ") + 10
            }
            val firstLineAvail = maxOf(50, availableWidth - changeIdWidth - bookmarkWidth)
            val fullLineAvail = maxOf(50, availableWidth)

            val wrappedLines = GGGraphCellRenderer.wrapText(descText, fm, firstLineAvail, fullLineAvail)
            val height = GGLogTableModel.BASE_ROW_HEIGHT + maxOf(0, wrappedLines.size - 1) * lineHeight

            tableModel.updateRowHeight(i, height)
            table.setRowHeight(i, height)
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

    // --- Context menu, double-click, and drag actions ---

    private fun localBookmarkAtPoint(p: Point): Pair<Int, StoreRef.LocalBookmark>? {
        val row = table.rowAtPoint(p)
        if (row < 0) return null
        val cellRect = table.getCellRect(row, 0, false)
        val cellPoint = Point(p.x - cellRect.x, p.y - cellRect.y)
        val cellComp = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0)
        cellComp.setSize(cellRect.width, cellRect.height)
        forceLayout(cellComp)
        val hit = SwingUtilities.getDeepestComponentAt(cellComp, cellPoint.x, cellPoint.y)
        val ref = (hit as? JComponent)?.getClientProperty("gg.ref") as? StoreRef.LocalBookmark
        return if (ref != null) Pair(row, ref) else null
    }

    private fun forceLayout(c: java.awt.Component) {
        if (c is java.awt.Container) {
            c.doLayout()
            for (child in c.components) forceLayout(child)
        }
    }

    private fun editDescription() {
        val row = table.selectedRow
        if (row < 0 || row >= tableModel.rowCount) return
        val header = tableModel.getEnhancedRow(row).row.revision
        if (header.is_immutable) return

        val textArea = JBTextArea(header.description.text).apply {
            lineWrap = true
            wrapStyleWord = true
            rows = maxOf(3, header.description.lines.size + 1)
            columns = 50
        }
        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(400, 120)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, textArea)
            .setTitle("Edit Description (Ctrl+Enter to save)")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(false)
            .setCancelOnOtherWindowOpen(false)
            .createPopup()

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if ((e.isControlDown || e.isMetaDown) && e.keyCode == KeyEvent.VK_ENTER) {
                    val newDesc = textArea.text
                    popup.cancel()
                    runMutation("Describe revision") {
                        GGRepository.getInstance(project).describeRevision(header.id, newDesc)
                    }
                    e.consume()
                }
            }
        })

        val cellRect = table.getCellRect(row, 0, true)
        val relPoint = RelativePoint(table, Point(cellRect.x, cellRect.y + cellRect.height))
        popup.show(relPoint)
    }

    private fun editRevision(header: RevHeader) {
        if (header.is_working_copy || header.is_immutable) return
        runMutation("Edit revision") {
            GGRepository.getInstance(project).checkoutRevision(header.id)
        }
    }

    private fun showContextMenu(e: MouseEvent, clickedBookmark: StoreRef.LocalBookmark? = null) {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        table.selectionModel.setSelectionInterval(row, row)
        val header = tableModel.getEnhancedRow(row).row.revision

        val menu = JPopupMenu()

        menu.add(JMenuItem("New Child").apply {
            addActionListener {
                runMutation("New child") {
                    GGRepository.getInstance(project).createRevision(header.id)
                }
            }
        })

        menu.add(JMenuItem("Edit (Make Working Copy)").apply {
            isEnabled = !header.is_working_copy && !header.is_immutable
            addActionListener { editRevision(header) }
        })

        menu.add(JMenuItem("Edit Description…").apply {
            isEnabled = !header.is_immutable
            addActionListener { editDescription() }
        })

        menu.add(JMenuItem("Squash into Parent").apply {
            isEnabled = !header.is_immutable && header.parent_ids.size == 1
            addActionListener {
                runMutation("Squash into parent") {
                    GGRepository.getInstance(project).squashIntoParent(header.id)
                }
            }
        })

        menu.addSeparator()

        menu.add(JMenuItem("Create Bookmark…").apply {
            addActionListener {
                val name = Messages.showInputDialog(
                    project, "Bookmark name:", "Create Bookmark", null
                ) ?: return@addActionListener
                runMutation("Create bookmark") {
                    GGRepository.getInstance(project).createBookmark(name, header.id)
                }
            }
        })

        val visibleBookmarks = (0 until tableModel.rowCount)
            .flatMap { tableModel.getEnhancedRow(it).row.revision.refs }
            .filterIsInstance<StoreRef.LocalBookmark>()
            .distinctBy { it.bookmark_name }
        menu.add(JMenuItem("Move Bookmark Here…").apply {
            isEnabled = visibleBookmarks.isNotEmpty()
            addActionListener {
                val names = visibleBookmarks.map { it.bookmark_name }.toTypedArray()
                val choice = Messages.showEditableChooseDialog(
                    "Select bookmark to move:", "Move Bookmark Here", null, names, names[0], null
                ) ?: return@addActionListener
                val ref = visibleBookmarks.find { it.bookmark_name == choice }
                    ?: StoreRef.LocalBookmark(choice, false, false, emptyList(), 0, 0)
                runMutation("Move bookmark") {
                    GGRepository.getInstance(project).moveBookmark(ref, header.id)
                }
            }
        })

        if (clickedBookmark != null) {
            menu.add(JMenuItem("Push Bookmark '${clickedBookmark.bookmark_name}'…").apply {
                addActionListener {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val remotes = GGRepository.getInstance(project).loadRemotes()
                        val remote = chooseRemote(project, remotes, "Push Bookmark")
                            ?: return@executeOnPooledThread
                        performMutation(project, "jj git push") {
                            GGRepository.getInstance(project).gitPush(remote, clickedBookmark.bookmark_name)
                        }
                    }
                }
            })
        }

        menu.addSeparator()

        menu.add(JMenuItem("Abandon").apply {
            isEnabled = !header.is_immutable && !header.is_working_copy
            addActionListener {
                runMutation("Abandon revision") {
                    GGRepository.getInstance(project).abandonRevisions(header.id)
                }
            }
        })

        menu.show(table, e.x, e.y)
    }

    private fun runMutation(description: String, block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                block()
                ApplicationManager.getApplication().invokeLater {
                    project.messageBus.syncPublisher(GG_LOG_CHANGED).onLogChanged()
                }
            } catch (e: com.jjuicy.intellij.jj.JJException) {
                LOG.warn("$description failed: ${e.message}")
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, e.message ?: "Unknown error", "jjuicy")
                }
            } catch (e: Exception) {
                LOG.warn("$description failed", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, e.message ?: "Unknown error", "jjuicy")
                }
            }
        }
    }
}
