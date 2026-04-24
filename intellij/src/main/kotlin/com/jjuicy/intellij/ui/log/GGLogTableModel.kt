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
        // Force a complete repaint — the clear+insert sequence above doesn't
        // fire a deletion event, so stale cells can survive as ghost artifacts.
        fireTableDataChanged()
    }

    /** Append an additional page (for infinite scroll / paginated loading). */
    fun appendPage(logPage: com.jjuicy.intellij.data.LogPage) {
        hasMore = logPage.has_more
        loading = false
        addPage(logPage.rows)
    }

    fun markLoading() {
        loading = true
        fireTableDataChanged()
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
