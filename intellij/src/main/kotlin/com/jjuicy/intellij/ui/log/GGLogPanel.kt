package com.jjuicy.intellij.ui.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.jjuicy.intellij.actions.performMutation
import com.jjuicy.intellij.data.*
import java.awt.Cursor
import java.awt.BorderLayout
import java.awt.Point
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

    // bookmark drag state
    private var dragRef: StoreRef.LocalBookmark? = null
    private var dragSourceRow: Int = -1

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

        // Double-click to edit; right-click for context menu; drag chips to move bookmarks
        val mouseHandler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) editRevision(tableModel.getEnhancedRow(row).row.revision)
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
                            GGRepository.getInstance(project).mutate("move_ref", MoveRef(ref = ref, to_id = targetHeader.id))
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

    // --- Context menu, double-click, and drag actions ---

    private fun localBookmarkAtPoint(p: Point): Pair<Int, StoreRef.LocalBookmark>? {
        val row = table.rowAtPoint(p)
        if (row < 0) return null
        val cellRect = table.getCellRect(row, 0, false)
        val cellPoint = Point(p.x - cellRect.x, p.y - cellRect.y)
        // prepareRenderer returns a shared stamp component whose children have no bounds set
        // unless we force a layout pass matching the cell's actual dimensions.
        val cellComp = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0)
        cellComp.setSize(cellRect.width, cellRect.height)
        forceLayout(cellComp)
        val hit = SwingUtilities.getDeepestComponentAt(cellComp, cellPoint.x, cellPoint.y)
        val ref = (hit as? JLabel)?.getClientProperty("gg.ref") as? StoreRef.LocalBookmark
        return if (ref != null) Pair(row, ref) else null
    }

    /** Recursively calls doLayout() — needed for off-screen renderer components where validate() is a no-op. */
    private fun forceLayout(c: java.awt.Component) {
        if (c is java.awt.Container) {
            c.doLayout()
            for (child in c.components) forceLayout(child)
        }
    }

    private fun editRevision(header: RevHeader) {
        if (header.is_working_copy || header.is_immutable) return
        runMutation("Edit revision") {
            GGRepository.getInstance(project).mutate("checkout_revision", CheckoutRevision(id = header.id))
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
                    GGRepository.getInstance(project).mutate(
                        "create_revision", CreateRevision(set = RevSet(header.id, header.id))
                    )
                }
            }
        })

        menu.add(JMenuItem("Edit (Make Working Copy)").apply {
            isEnabled = !header.is_working_copy && !header.is_immutable
            addActionListener { editRevision(header) }
        })

        menu.add(JMenuItem("Squash into Parent").apply {
            isEnabled = !header.is_immutable && header.parent_ids.size == 1
            addActionListener {
                val parentId = header.parent_ids.first()
                runMutation("Squash into parent") {
                    GGRepository.getInstance(project).mutate(
                        "move_changes",
                        MoveChanges(from = RevSet(header.id, header.id), to_id = parentId)
                    )
                }
            }
        })

        menu.addSeparator()

        menu.add(JMenuItem("Create Bookmark…").apply {
            addActionListener {
                val name = Messages.showInputDialog(
                    project, "Bookmark name:", "Create Bookmark", null
                ) ?: return@addActionListener
                val ref = StoreRef.LocalBookmark(name, false, false, emptyList(), 0, 0)
                runMutation("Create bookmark") {
                    GGRepository.getInstance(project).mutate("create_ref", CreateRef(id = header.id, ref = ref))
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
                    GGRepository.getInstance(project).mutate("move_ref", MoveRef(ref = ref, to_id = header.id))
                }
            }
        })

        if (clickedBookmark != null) {
            menu.add(JMenuItem("Push Bookmark '${clickedBookmark.bookmark_name}'…").apply {
                addActionListener {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val remotes = GGRepository.getInstance(project).loadRemotes()
                            val remote = when {
                                remotes.isEmpty() -> {
                                    var name: String? = null
                                    ApplicationManager.getApplication().invokeAndWait {
                                        name = Messages.showInputDialog(project, "Remote name:", "Push Bookmark", null)
                                    }
                                    name ?: return@executeOnPooledThread
                                }
                                remotes.size == 1 -> remotes[0]
                                else -> {
                                    var chosen: String? = null
                                    ApplicationManager.getApplication().invokeAndWait {
                                        chosen = Messages.showEditableChooseDialog(
                                            "Select remote to push to:", "Push Bookmark",
                                            null, remotes.toTypedArray(), remotes[0], null
                                        )
                                    }
                                    chosen ?: return@executeOnPooledThread
                                }
                            }
                            performMutation(project, "jj git push", "git_push") {
                                GitPush(refspec = GitRefspec.RemoteBookmark(
                                    remote_name = remote,
                                    bookmark_ref = clickedBookmark,
                                ))
                            }
                        } catch (e: Exception) {
                            LOG.warn("Push bookmark failed", e)
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(project, e.message ?: "Unknown error", "jjuicy")
                            }
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
                    GGRepository.getInstance(project).mutate(
                        "abandon_revisions", AbandonRevisions(set = RevSet(header.id, header.id))
                    )
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
            } catch (e: Exception) {
                LOG.warn("$description failed", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, e.message ?: "Unknown error", "jjuicy")
                }
            }
        }
    }
}
