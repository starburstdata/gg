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
        // (bookmarkPanel chips change per row). Use the actual cell rect so FlowLayout
        // doesn't wrap chips to a second line when table.width hasn't settled yet.
        val cellRect = table.getCellRect(row, column, false)
        cell.setSize(cellRect.width, cellRect.height)
        forceLayout(cell)
        return cell
    }

    private fun forceLayout(c: Component) {
        if (c is Container) {
            c.doLayout()
            for (child in c.components) forceLayout(child)
        }
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
            val gw = GGGraphPainter.graphWidth(minOf(maxCol, GGGraphPainter.MAX_VISIBLE_COLUMNS))
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

        private val changeIdPrefixColor = JBColor(Color(0xCA_5C_2C), Color(0xD1_75_5B))
        private val changeIdRestColor = JBColor(Color(0x80_80_80), Color(0x79_79_79))

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
            val row = JPanel(BorderLayout(4, 0))
            row.isOpaque = false
            row.add(descLabel, BorderLayout.WEST)
            row.add(bookmarkPanel, BorderLayout.CENTER)
            return row
        }

        fun update(enhanced: EnhancedLogRow, selected: Boolean) {
            val header = enhanced.row.revision
            val hiddenForks = enhanced.row.hidden_forks

            descLabel.clear()
            val changeId = header.id.change
            if (!selected) {
                descLabel.append(changeId.prefix, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, changeIdPrefixColor))
                descLabel.append(changeId.rest, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, changeIdRestColor))
            } else {
                descLabel.append(changeId.prefix + changeId.rest, SimpleTextAttributes.SELECTED_SIMPLE_CELL_ATTRIBUTES)
            }
            descLabel.append("  ", SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES)
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

        private fun makeChip(ref: StoreRef, selected: Boolean): SimpleColoredComponent {
            val (text, color, bgColor) = when (ref) {
                is StoreRef.LocalBookmark -> {
                    val suffix = if (!ref.is_synced) "*" else ""
                    Triple(
                        "${ref.bookmark_name}$suffix",
                        if (ref.has_conflict) JBColor.RED else JBColor(0x1A_6B_2E, 0x57_A6_4A),
                        if (ref.has_conflict) JBColor(0xFF_DD_DD, 0x4A_1A_1A) else JBColor(0xD5_F0_D5, 0x1C_3B_1C),
                    )
                }
                is StoreRef.RemoteBookmark -> Triple(
                    "${ref.bookmark_name}@${ref.remote_name}",
                    JBColor(0xA0_20_20, 0xFF_8A_8A),
                    JBColor(0xFF_E0_E0, 0x4A_1A_1A),
                )
                is StoreRef.Tag -> Triple(
                    ref.tag_name,
                    JBColor(0x6B_4E_1A, 0xF9_E2_AF),
                    JBColor(0xFD_F3_D0, 0x3B_30_10),
                )
            }
            val chip = SimpleColoredComponent()
            chip.append(" $text ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
            chip.border = BorderFactory.createLineBorder(color, 1)
            chip.isOpaque = true
            chip.background = bgColor
            // tag local bookmarks so drag-and-drop hit testing can identify them
            if (ref is StoreRef.LocalBookmark) chip.putClientProperty("gg.ref", ref)
            return chip
        }

        private fun makeHiddenForkChip(name: String, selected: Boolean): SimpleColoredComponent {
            val color = JBColor(Color(0x60_60_60), Color(0x99_99_99))
            val chip = SimpleColoredComponent()
            chip.append(" ↙$name ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
            chip.border = BorderFactory.createLineBorder(color, 1)
            chip.isOpaque = true
            chip.background = JBColor(Color(0xE8_E8_E8), Color(0x30_30_30))
            return chip
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
