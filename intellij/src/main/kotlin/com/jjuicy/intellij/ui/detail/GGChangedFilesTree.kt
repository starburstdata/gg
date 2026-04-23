package com.jjuicy.intellij.ui.detail

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.jjuicy.intellij.data.ChangeKind
import com.jjuicy.intellij.data.RevChange
import com.jjuicy.intellij.data.RevId
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Flat list of changed files for the selected revision(s).
 *
 * Double-click or Enter opens the IntelliJ native diff viewer.
 */
class GGChangedFilesTree(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<RevChange>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ChangeRenderer()
    }

    private var currentRevId: RevId? = null

    init {
        add(JBScrollPane(list), BorderLayout.CENTER)

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelectedDiff()
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelectedDiff()
            }
        })
    }

    fun setChanges(revId: RevId, changes: List<RevChange>, conflicts: List<RevChange>) {
        currentRevId = revId
        listModel.clear()
        changes.forEach { listModel.addElement(it) }
        // conflicts that don't already appear in changes
        val changePaths = changes.map { it.path.repo_path }.toSet()
        conflicts.filter { it.path.repo_path !in changePaths }.forEach { listModel.addElement(it) }
    }

    fun clear() {
        currentRevId = null
        listModel.clear()
    }

    private fun openSelectedDiff() {
        val change = list.selectedValue ?: return
        val id = currentRevId ?: return
        GGDiffHelper.openDiff(project, id, change.path)
    }

    private class ChangeRenderer : ColoredListCellRenderer<RevChange>() {
        override fun customizeCellRenderer(
            list: javax.swing.JList<out RevChange>,
            value: RevChange?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            icon = when (value.kind) {
                ChangeKind.Added -> AllIcons.General.Add
                ChangeKind.Deleted -> AllIcons.General.Remove
                ChangeKind.Modified -> AllIcons.Actions.Edit
                ChangeKind.None -> AllIcons.FileTypes.Unknown
            }
            val textAttr = when {
                value.has_conflict -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.RED)
                value.kind == ChangeKind.Added -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x00_80_00, 0x57_A6_4A))
                value.kind == ChangeKind.Deleted -> SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.RED)
                else -> SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES
            }
            append(value.path.relative_path.value, textAttr)
            if (value.has_conflict) append("  ⚠ conflict", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.RED))
        }
    }
}
