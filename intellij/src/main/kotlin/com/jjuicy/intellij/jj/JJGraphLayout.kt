package com.jjuicy.intellij.jj

import com.jjuicy.intellij.data.LogCoordinates
import com.jjuicy.intellij.data.LogLine
import com.jjuicy.intellij.data.LogRow
import com.jjuicy.intellij.data.RevHeader

/**
 * Computes graph layout for a flat list of [RevHeader]s.
 *
 * Port of the Rust `QuerySession::get_page` stem algorithm (src/worker/queries.rs).
 * Input must be in topological display order (newest/youngest first, same order
 * that `jj log` emits without --reversed).
 *
 * Each column slot in [stems] holds a pending downward edge whose [Stem.target]
 * is the commit hex expected in that column. Null slots are empty gaps that the
 * allocator can reuse. When a commit appears, any stem targeting it is closed and
 * a [LogLine.ToNode] is emitted; then new stems are opened for the commit's parents.
 */
object JJGraphLayout {

    private data class Stem(
        val source: LogCoordinates,
        val target: String,       // commit hex
        val indirect: Boolean = false,
        val rescued: Boolean = false,
    )

    fun computeRows(headers: List<RevHeader>): List<LogRow> {
        val stems = mutableListOf<Stem?>()
        val rows = mutableListOf<LogRow>()
        val ownWidths = mutableListOf<Int>()
        var rowNum = 0

        for (header in headers) {
            val commitHex = header.id.commit.hex
            val lines = mutableListOf<LogLine>()

            // Step 1 — find a column: prefer a stem targeting this commit, then a
            //          free slot, then a new column on the right.
            val nodeCol = findColumn(stems, commitHex)

            // Step 2 — terminate the stem targeting this commit; emit ToNode.
            if (nodeCol < stems.size && stems[nodeCol] != null) {
                val slot = stems[nodeCol]!!
                stems[nodeCol] = null
                val rescueAlreadyCovers =
                    slot.rescued && slot.source.col == nodeCol && slot.source.row + 1 == rowNum
                if (!rescueAlreadyCovers) {
                    lines.add(LogLine.ToNode(
                        source = slot.source,
                        target = LogCoordinates(nodeCol, rowNum),
                        indirect = slot.indirect,
                    ))
                }
            } else {
                while (stems.size <= nodeCol) stems.add(null)
            }

            // Step 3 — assign each parent edge to a column.
            var parentCount = 0
            var singleParentMerge: Pair<Int, Int>? = null  // (existingSlot, lineIdx)

            for (parentId in header.parent_ids) {
                val parentHex = parentId.hex
                parentCount++

                val existingSlot = stems.indexOfFirst { it?.target == parentHex }
                if (existingSlot >= 0) {
                    // parent already has a stem — merge into it (ToIntersection)
                    val lineIdx = lines.size
                    lines.add(LogLine.ToIntersection(
                        source = LogCoordinates(nodeCol, rowNum),
                        target = LogCoordinates(existingSlot, rowNum + 1),
                        indirect = false,
                    ))
                    if (parentCount == 1) singleParentMerge = Pair(existingSlot, lineIdx)
                    else singleParentMerge = null
                    continue
                }

                // No existing stem — allocate a new one.
                val col = when {
                    nodeCol < stems.size && stems[nodeCol] == null -> nodeCol
                    else -> {
                        val free = stems.indexOfFirst { it == null }
                        if (free >= 0) free else { stems.add(null); stems.size - 1 }
                    }
                }
                while (stems.size <= col) stems.add(null)
                stems[col] = Stem(
                    source = LogCoordinates(nodeCol, rowNum),
                    target = parentHex,
                )
                singleParentMerge = null
            }

            // Step 4 — column rescue (sapling-style): single-parent commit whose
            //          parent's stem is to the right gets the stem swapped back.
            val (slot, lineIdx) = singleParentMerge ?: Pair(-1, -1)
            if (parentCount == 1 && slot > nodeCol) {
                val stemRef = stems.getOrNull(slot)
                if (stemRef != null && stemRef.source.row < rowNum) {
                    val stem = stemRef
                    stems[slot] = null

                    // redirect the ToIntersection we just emitted to nodeCol
                    val toI = lines[lineIdx] as LogLine.ToIntersection
                    lines[lineIdx] = toI.copy(target = LogCoordinates(nodeCol, toI.target.row))

                    // emit the compaction sweep as FromNode
                    lines.add(LogLine.FromNode(
                        source = stem.source,
                        target = LogCoordinates(nodeCol, rowNum + 1),
                        indirect = stem.indirect,
                        via = slot,
                    ))
                    while (stems.size <= nodeCol) stems.add(null)
                    stems[nodeCol] = Stem(
                        source = LogCoordinates(nodeCol, rowNum),
                        target = stem.target,
                        indirect = stem.indirect,
                        rescued = true,
                    )
                }
            }

            val ownWidth = stems.size

            // Step 5 — trim trailing empty slots.
            while (stems.isNotEmpty() && stems.last() == null) stems.removeAt(stems.size - 1)

            rows.add(LogRow(
                revision = header,
                location = LogCoordinates(nodeCol, rowNum),
                padding = 0,  // filled in below
                lines = lines,
                hidden_forks = emptyList(),
            ))
            ownWidths.add(ownWidth)
            rowNum++
        }

        // Neighbour-aware padding: widen each row's effective column count to match
        // the rightmost column used by any line spanning through that row — mirrors
        // the post-loop pass in the Rust backend.
        val rowByNum = rows.mapIndexed { i, r -> r.location.row to i }.toMap()
        val effective = ownWidths.toMutableList()

        for (row in rows) {
            for (line in row.lines) {
                val (src, tgt, via) = when (line) {
                    is LogLine.FromNode -> Triple(line.source, line.target, line.via)
                    is LogLine.ToNode -> Triple(line.source, line.target, null)
                    is LogLine.ToIntersection -> Triple(line.source, line.target, null)
                    is LogLine.ToMissing -> Triple(line.source, line.target, null)
                }
                val width = maxOf(src.col, tgt.col, via ?: 0) + 1
                val lo = minOf(src.row, tgt.row)
                val hi = maxOf(src.row, tgt.row)
                for (rn in lo..hi) {
                    val idx = rowByNum[rn] ?: continue
                    if (effective[idx] < width) effective[idx] = width
                }
            }
        }

        return rows.mapIndexed { i, row ->
            row.copy(padding = maxOf(0, effective[i] - row.location.col - 1))
        }
    }

    private fun findColumn(stems: List<Stem?>, commitHex: String): Int {
        val existing = stems.indexOfFirst { it?.target == commitHex }
        if (existing >= 0) return existing
        val free = stems.indexOfFirst { it == null }
        if (free >= 0) return free
        return stems.size
    }
}
