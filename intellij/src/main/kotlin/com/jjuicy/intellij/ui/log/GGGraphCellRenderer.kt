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

    companion object {
        /** Word-wrap [text] respecting explicit newlines and pixel widths. */
        fun wrapText(text: String, fm: FontMetrics, firstLineMax: Int, fullMax: Int): List<String> {
            val result = mutableListOf<String>()
            for (paragraph in text.lines()) {
                if (paragraph.isEmpty()) {
                    result.add("")
                    continue
                }
                val maxWidth = if (result.isEmpty()) firstLineMax else fullMax
                if (fm.stringWidth(paragraph) <= maxWidth) {
                    result.add(paragraph)
                    continue
                }
                wrapParagraph(paragraph, fm, if (result.isEmpty()) firstLineMax else fullMax, fullMax, result)
            }
            return result.ifEmpty { listOf("") }
        }

        private fun wrapParagraph(
            text: String,
            fm: FontMetrics,
            firstSegmentMax: Int,
            otherSegmentMax: Int,
            result: MutableList<String>,
        ) {
            val words = text.split(" ")
            var current = StringBuilder()
            var isFirst = result.isEmpty()
            for (word in words) {
                val maxW = if (isFirst) firstSegmentMax else otherSegmentMax
                val test = if (current.isEmpty()) word else "$current $word"
                if (fm.stringWidth(test) > maxW && current.isNotEmpty()) {
                    result.add(current.toString())
                    current = StringBuilder(word)
                    isFirst = false
                } else {
                    current = StringBuilder(test)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
        }
    }

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

        val cellRect = table.getCellRect(row, column, false)
        cell.setSize(cellRect.width, cellRect.height)

        // compute word wrapping for display (heights are set externally by GGLogPanel)
        val graphWidth = GGGraphPainter.graphWidth(enhanced.row.location.col)
        val textWidth = cellRect.width - graphWidth - 12
        cell.updateWrapping(textWidth)

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
            graphPanel.rowHeights = model.rowHeights
            graphPanel.rowOffsets = model.rowOffsets
            val gw = GGGraphPainter.graphWidth(enhanced.row.location.col)
            val rh = model.getRowHeight(rowIndex)
            graphPanel.preferredSize = Dimension(gw, rh)

            textPanel.update(enhanced, selected)
        }

        fun updateWrapping(textWidth: Int) {
            textPanel.updateWrapping(textWidth)
        }
    }

    /** Paints the graph lines and node using [GGGraphPainter]. */
    private class GraphPanel : JPanel() {
        var enhanced: EnhancedLogRow? = null
        var rowIndex: Int = 0
        var rowHeights: IntArray = IntArray(0)
        var rowOffsets: IntArray = IntArray(0)

        init { isOpaque = true }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val enh = enhanced ?: return
            val g2 = g as Graphics2D
            GGGraphPainter.paintLines(g2, enh.passingLines, rowIndex, width, rowHeights, rowOffsets)
            GGGraphPainter.paintNode(g2, enh, rowIndex, rowOffsets)
        }
    }

    /** Right-side text area: description + bookmarks + author. */
    private class TextPanel : JPanel() {

        private val changeIdPrefixColor = JBColor(Color(0xCA_5C_2C), Color(0xD1_75_5B))
        private val changeIdRestColor = JBColor(Color(0x80_80_80), Color(0x79_79_79))

        private val descLabel = SimpleColoredComponent()
        private val metaLabel = SimpleColoredComponent()
        private val bookmarkPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        private val topRow = buildTopRow()
        private val extraDescLabels = mutableListOf<SimpleColoredComponent>()

        // stored during update(), used during updateWrapping()
        private var storedDescLines: List<String> = emptyList()
        private var storedDescAttr: SimpleTextAttributes = SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES
        private var storedHasConflict = false

        init {
            isOpaque = true
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            bookmarkPanel.isOpaque = false
            add(topRow)
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

        /**
         * Set up change ID, bookmarks, and metadata. Description text is stored
         * but not rendered yet — [updateWrapping] handles that once the width is known.
         */
        fun update(enhanced: EnhancedLogRow, selected: Boolean) {
            val header = enhanced.row.revision
            val hiddenForks = enhanced.row.hidden_forks

            // change ID only (description appended later in updateWrapping)
            descLabel.clear()
            val changeId = header.id.change
            if (!selected) {
                descLabel.append(changeId.prefix, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, changeIdPrefixColor))
                descLabel.append(changeId.rest, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, changeIdRestColor))
            } else {
                descLabel.append(changeId.prefix + changeId.rest, SimpleTextAttributes.SELECTED_SIMPLE_CELL_ATTRIBUTES)
            }
            descLabel.append("  ", SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES)

            storedDescLines = header.description.lines
            storedDescAttr = when {
                selected -> SimpleTextAttributes.SELECTED_SIMPLE_CELL_ATTRIBUTES
                header.is_working_copy -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
                header.is_immutable -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY)
                else -> SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES
            }
            storedHasConflict = header.has_conflict

            bookmarkPanel.removeAll()
            for (ref in header.refs) {
                bookmarkPanel.add(makeChip(ref, selected))
            }
            for (fork in hiddenForks) {
                bookmarkPanel.add(makeHiddenForkChip(fork, selected))
            }

            // clear previous extra lines
            for (label in extraDescLabels) remove(label)
            extraDescLabels.clear()

            metaLabel.clear()
            val ts = formatTimestamp(header.author.timestamp)
            metaLabel.append(
                "${header.author.name}  $ts",
                SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.GRAY)
            )
        }

        /**
         * Compute word wrapping for the description and add continuation lines.
         * Heights are set externally — this is purely for display.
         */
        fun updateWrapping(availableWidth: Int) {
            // clear previous extra lines (may have been set by a prior wrapping pass)
            for (label in extraDescLabels) remove(label)
            extraDescLabels.clear()

            val fm = getFontMetrics(font)

            // width consumed by the change ID prefix already in descLabel
            val changeIdWidth = descLabel.preferredSize.width
            val bookmarkWidth = if (bookmarkPanel.componentCount > 0) bookmarkPanel.preferredSize.width + 8 else 0
            val firstLineAvail = maxOf(50, availableWidth - changeIdWidth - bookmarkWidth)
            val fullLineAvail = maxOf(50, availableWidth)

            val descText = storedDescLines.joinToString("\n").ifBlank { "(no description)" }
            val allWrappedLines = wrapText(descText, fm, firstLineAvail, fullLineAvail)

            // first wrapped line goes into descLabel alongside change ID
            descLabel.append(allWrappedLines.first(), storedDescAttr)
            if (storedHasConflict) {
                descLabel.append("  ⚠", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.RED))
            }

            // continuation lines
            if (allWrappedLines.size > 1) {
                val insertIdx = getComponentIndex(metaLabel)
                for (i in 1 until allWrappedLines.size) {
                    val lineLabel = SimpleColoredComponent()
                    lineLabel.append(allWrappedLines[i], storedDescAttr)
                    extraDescLabels.add(lineLabel)
                    add(lineLabel, insertIdx + (i - 1))
                }
            }
        }

        private fun getComponentIndex(comp: java.awt.Component): Int {
            for (i in 0 until componentCount) {
                if (getComponent(i) === comp) return i
            }
            return componentCount
        }

        private fun makeChip(ref: StoreRef, selected: Boolean): SimpleColoredComponent {
            val (text, color, bgColor) = when (ref) {
                is StoreRef.LocalBookmark -> {
                    val suffix = if (!ref.is_synced) "*" else ""
                    val (color, bgColor) = if (ref.has_conflict) {
                        JBColor.RED to JBColor(0xFF_DD_DD, 0x4A_1A_1A)
                    } else if (!ref.is_synced) {
                        // yellow/amber: moved locally but not pushed yet
                        JBColor(0x8B_6A_00, 0xF9_E2_AF) to JBColor(0xFF_F3_CC, 0x3B_30_10)
                    } else {
                        JBColor(0x1A_6B_2E, 0x57_A6_4A) to JBColor(0xD5_F0_D5, 0x1C_3B_1C)
                    }
                    Triple("${ref.bookmark_name}$suffix", color, bgColor)
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
