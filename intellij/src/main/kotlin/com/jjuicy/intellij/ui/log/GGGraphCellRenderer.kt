package com.jjuicy.intellij.ui.log

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.jjuicy.intellij.data.StoreRef
import java.awt.*
import javax.swing.*
import javax.swing.table.TableCellRenderer

/**
 * TableCellRenderer for the commit log.
 *
 * Each cell is split into a left graph canvas (painted via [GGGraphPainter]) and
 * a right text area showing the commit description, bookmarks, and author.
 */
class GGGraphCellRenderer(private val model: GGLogTableModel) : TableCellRenderer {

    private val cell = CellPanel()

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val enhanced = value as? EnhancedLogRow ?: return cell
        cell.prepare(enhanced, row, isSelected, model.maxGraphColumn)
        // Renderer is a rubber stamp — Swing won't auto-layout dynamic children
        // (bookmarkPanel chips change per row). Force a full validate pass now so
        // chips have non-zero bounds before the cell is painted.
        cell.validate()
        return cell
    }

    /** Single panel that owns all sub-components and does custom painting. */
    private inner class CellPanel : JPanel(BorderLayout()) {

        private val graphPanel = GraphPanel()
        private val textPanel = TextPanel()

        init {
            isOpaque = true
            add(graphPanel, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
            border = JBUI.Borders.empty(1, 0)
        }

        fun prepare(enhanced: EnhancedLogRow, rowIndex: Int, selected: Boolean, maxCol: Int) {
            val bg = if (selected) UIManager.getColor("Table.selectionBackground")
            else UIManager.getColor("Table.background")
            background = bg
            graphPanel.background = bg
            textPanel.background = bg

            graphPanel.enhanced = enhanced
            graphPanel.rowIndex = rowIndex
            val gw = GGGraphPainter.graphWidth(maxCol)
            graphPanel.preferredSize = Dimension(gw, GGGraphPainter.ROW_HEIGHT)

            textPanel.update(enhanced, selected)
        }
    }

    /** Paints the graph lines and node using [GGGraphPainter]. */
    private class GraphPanel : JPanel() {
        var enhanced: EnhancedLogRow? = null
        var rowIndex: Int = 0

        init { isOpaque = true }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val enh = enhanced ?: return
            val g2 = g as Graphics2D
            GGGraphPainter.paintLines(g2, enh.passingLines, rowIndex, width)
            GGGraphPainter.paintNode(g2, enh, rowIndex)
        }
    }

    /** Right-side text area: description + bookmarks + author. */
    private class TextPanel : JPanel() {

        private val descLabel = SimpleColoredComponent()
        private val metaLabel = SimpleColoredComponent()
        private val bookmarkPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))

        init {
            isOpaque = true
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            bookmarkPanel.isOpaque = false
            add(buildTopRow())
            add(metaLabel)
            border = JBUI.Borders.emptyLeft(4)
        }

        private fun buildTopRow(): JPanel {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
            row.isOpaque = false
            row.add(descLabel)
            row.add(bookmarkPanel)
            return row
        }

        fun update(enhanced: EnhancedLogRow, selected: Boolean) {
            val header = enhanced.row.revision
            val hiddenForks = enhanced.row.hidden_forks

            descLabel.clear()
            val descText = header.description.firstLine.ifBlank { "(no description)" }
            val descAttr = when {
                selected -> SimpleTextAttributes.SELECTED_SIMPLE_CELL_ATTRIBUTES
                header.is_working_copy -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
                header.is_immutable -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY)
                else -> SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES
            }
            descLabel.append(descText, descAttr)
            if (header.has_conflict) {
                descLabel.append("  ⚠", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.RED))
            }

            bookmarkPanel.removeAll()
            for (ref in header.refs) {
                bookmarkPanel.add(makeChip(ref, selected))
            }
            for (fork in hiddenForks) {
                bookmarkPanel.add(makeHiddenForkChip(fork, selected))
            }

            metaLabel.clear()
            val ts = formatTimestamp(header.author.timestamp)
            metaLabel.append(
                "${header.author.name}  $ts",
                SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.GRAY)
            )
        }

        private fun makeChip(ref: StoreRef, selected: Boolean): JLabel {
            val (text, color) = when (ref) {
                is StoreRef.LocalBookmark -> {
                    val suffix = if (!ref.is_synced) "*" else ""
                    Pair("${ref.bookmark_name}$suffix", if (ref.has_conflict) JBColor.RED else JBColor(0x3D_9A_56, 0x57_A6_4A))
                }
                is StoreRef.RemoteBookmark -> {
                    Pair("${ref.bookmark_name}@${ref.remote_name}", JBColor(0xC0_40_40, 0xFF_6B_6B))
                }
                is StoreRef.Tag -> Pair(ref.tag_name, JBColor(0x8C_6D_3F, 0xF9_E2_AF))
            }
            val lbl = JLabel(" $text ")
            lbl.font = lbl.font.deriveFont(lbl.font.size2D * 0.85f)
            lbl.foreground = if (selected) UIManager.getColor("Table.selectionForeground") else color
            lbl.border = BorderFactory.createLineBorder(
                if (selected) UIManager.getColor("Table.selectionForeground")!! else color,
                1
            )
            // tag local bookmarks so drag-and-drop hit testing can identify them
            if (ref is StoreRef.LocalBookmark) lbl.putClientProperty("gg.ref", ref)
            return lbl
        }

        private fun makeHiddenForkChip(name: String, selected: Boolean): JLabel {
            val color = JBColor(Color(0x88_88_88), Color(0x88_88_88))
            val lbl = JLabel(" ↙$name ")
            lbl.font = lbl.font.deriveFont(lbl.font.size2D * 0.85f)
            lbl.foreground = color
            lbl.border = BorderFactory.createLineBorder(color, 1)
            return lbl
        }

        private fun formatTimestamp(ts: String): String {
            // The backend serializes as RFC3339 (e.g. "2024-01-15T10:30:00+00:00")
            return try {
                val odt = java.time.OffsetDateTime.parse(ts)
                val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                odt.format(fmt)
            } catch (_: Exception) {
                ts.take(16)
            }
        }
    }
}
