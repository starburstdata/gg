package com.jjuicy.intellij.ui.log

import com.jjuicy.intellij.data.LogLine
import com.jjuicy.intellij.data.LogRow
import com.jjuicy.intellij.data.RevHeader
import javax.swing.table.AbstractTableModel

/**
 * A [LogLine] augmented with the child and parent [RevHeader]s so the renderer
 * can paint the line without looking up neighbouring rows.
 *
 * This mirrors the `EnhancedLine` type from GraphLog.svelte.
 */
data class EnhancedLogLine(
    val line: LogLine,
    val key: Int,
    var child: RevHeader,   // var because ToIntersection/ToMissing lines set it later
    var parent: RevHeader,  // var because parent may be resolved on a subsequent page
)

/**
 * An augmented row storing the original data plus the pre-computed list of
 * lines that pass through (or start at) this row.
 *
 * Mirrors EnhancedRow from GraphLog.svelte.
 */
data class EnhancedLogRow(
    val row: LogRow,
    val passingLines: MutableList<EnhancedLogLine> = mutableListOf(),
)

/**
 * AbstractTableModel for the commit log.
 *
 * Stores [EnhancedLogRow]s and exposes helpers for incremental page loading.
 * The [addPage] method ports `addPageToGraph()` from LogPane.svelte.
 */
class GGLogTableModel : AbstractTableModel() {

    companion object {
        const val BASE_ROW_HEIGHT = 30
        const val DESC_LINE_HEIGHT = 14
    }

    private val rows = mutableListOf<EnhancedLogRow>()
    var hasMore: Boolean = false
    var loading: Boolean = false
        private set

    // Lines whose parent hasn't been seen yet (ToIntersection/ToMissing carry-overs)
    private val passNextRow = mutableListOf<EnhancedLogLine>()
    private var lineKey = 0

    /** Maximum graph column index across all rows (drives graph pane width). */
    var maxGraphColumn: Int = 0
        private set

    /** Per-row pixel heights, computed from description line counts. */
    var rowHeights: IntArray = IntArray(0)
        private set

    /** Cumulative Y offsets: rowOffsets[i] = sum of rowHeights[0..i-1]. */
    var rowOffsets: IntArray = IntArray(0)
        private set

    fun getRowHeight(index: Int): Int =
        if (index in rowHeights.indices) rowHeights[index] else BASE_ROW_HEIGHT

    fun getRowOffset(index: Int): Int =
        if (index in rowOffsets.indices) rowOffsets[index] else index * BASE_ROW_HEIGHT

    /** Update a single row's height (e.g. after word-wrapping) and recompute offsets. */
    fun updateRowHeight(index: Int, height: Int) {
        if (index !in rowHeights.indices) return
        if (rowHeights[index] == height) return
        rowHeights[index] = height
        recomputeOffsets()
    }

    private fun recomputeOffsets() {
        var cumulative = 0
        for (i in rowHeights.indices) {
            rowOffsets[i] = cumulative
            cumulative += rowHeights[i]
        }
    }

    // --- Data access ---

    fun getEnhancedRow(index: Int): EnhancedLogRow = rows[index]

    fun indexOf(commitHex: String): Int =
        rows.indexOfFirst { it.row.revision.id.commit.hex == commitHex }

    fun indexOfChange(changeHex: String): Int =
        rows.indexOfFirst { it.row.revision.id.change.hex == changeHex }

    // --- Mutations ---

    /** Replace all existing rows with a new page. */
    fun setPage(logPage: com.jjuicy.intellij.data.LogPage) {
        rows.clear()
        passNextRow.clear()
        lineKey = 0
        maxGraphColumn = 0
        hasMore = logPage.has_more
        loading = false
        addPage(logPage.rows)
        computeRowMetrics()
        // Force a complete repaint — the clear+insert sequence above doesn't
        // fire a deletion event, so stale cells can survive as ghost artifacts.
        fireTableDataChanged()
    }

    /** Append an additional page (for infinite scroll / paginated loading). */
    fun appendPage(logPage: com.jjuicy.intellij.data.LogPage) {
        hasMore = logPage.has_more
        loading = false
        addPage(logPage.rows)
        computeRowMetrics()
    }

    fun markLoading() {
        loading = true
        fireTableDataChanged()
    }

    private fun computeRowMetrics() {
        val heights = IntArray(rows.size)
        val offsets = IntArray(rows.size)
        var cumulative = 0
        for (i in rows.indices) {
            val descLineCount = rows[i].row.revision.description.lines.size
            heights[i] = BASE_ROW_HEIGHT + maxOf(0, descLineCount - 1) * DESC_LINE_HEIGHT
            offsets[i] = cumulative
            cumulative += heights[i]
        }
        rowHeights = heights
        rowOffsets = offsets
    }

    /** Ported from addPageToGraph() in LogPane.svelte */
    private fun addPage(newRows: List<LogRow>) {
        val insertStart = rows.size

        for (logRow in newRows) {
            val enhanced = EnhancedLogRow(logRow)

            // Resolve parent of lines carried over from the previous row
            for (carried in passNextRow) {
                carried.parent = logRow.revision
            }
            enhanced.passingLines.addAll(passNextRow)
            passNextRow.clear()

            for (line in logRow.lines) {
                val enhLine = EnhancedLogLine(
                    line = line,
                    key = lineKey++,
                    child = logRow.revision,   // placeholder; may be overwritten below
                    parent = logRow.revision,  // placeholder
                )

                when (line) {
                    is LogLine.ToIntersection, is LogLine.ToMissing -> {
                        // These lines begin at this row and end at the next row.
                        enhLine.child = logRow.revision
                        enhanced.passingLines.add(enhLine)
                        passNextRow.add(enhLine)
                    }
                    else -> {
                        // FromNode / ToNode end at this row; source is an earlier row.
                        enhLine.parent = logRow.revision
                        val sourceRowNum = line.source.row
                        val sourceIdx = rows.indexOfFirst { it.row.location.row == sourceRowNum }
                        if (sourceIdx >= 0) {
                            enhLine.child = rows[sourceIdx].row.revision
                            // Add this line to every row from sourceIdx up to (but not including) the current row
                            for (i in sourceIdx until rows.size) {
                                if (rows[i].row.location.row < line.target.row) {
                                    rows[i].passingLines.add(enhLine)
                                }
                            }
                        } else {
                            // source row not yet in the graph (first page edge case) — use self
                            enhLine.child = logRow.revision
                        }
                        enhanced.passingLines.add(enhLine)
                    }
                }
            }

            rows.add(enhanced)

            // Track widest column
            val col = logRow.location.col
            if (col > maxGraphColumn) maxGraphColumn = col
        }

        if (rows.size > insertStart) {
            fireTableRowsInserted(insertStart, rows.size - 1)
        }
    }

    // --- AbstractTableModel ---

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = 1
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex]
    override fun getColumnClass(columnIndex: Int): Class<*> = EnhancedLogRow::class.java
}
