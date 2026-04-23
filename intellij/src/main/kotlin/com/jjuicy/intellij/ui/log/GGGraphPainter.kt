package com.jjuicy.intellij.ui.log

import com.intellij.ui.JBColor
import com.jjuicy.intellij.data.LogLine
import java.awt.*
import java.awt.geom.GeneralPath
import java.awt.geom.Ellipse2D

/**
 * Stateless graph renderer.
 *
 * Ports the geometry from GraphLine.svelte and GraphNode.svelte to Graphics2D.
 * All coordinates are in the full-graph absolute coordinate system where:
 *   - column x = col * COLUMN_WIDTH + HALF_COL
 *   - row "child y" = row * ROW_HEIGHT + 21
 *   - row "parent y" = row * ROW_HEIGHT + 9
 *   - row centre y = row * ROW_HEIGHT + 15
 *
 * The caller should translate/clip the Graphics2D context to the appropriate
 * cell before invoking these methods.
 */
object GGGraphPainter {

    const val COLUMN_WIDTH = 18
    const val ROW_HEIGHT = 30
    const val HALF_COL = 9      // COLUMN_WIDTH / 2
    const val NODE_RADIUS = 5
    private const val ARC_RADIUS = 6f

    // Colours matching the Svelte CSS variable palette (Catppuccin-blue ≈ #89b4fa)
    private val LINE_COLOR = JBColor(Color(0x4E_9A_D3), Color(0x89_B4_FA))
    private val LINE_COLOR_INDIRECT = JBColor(Color(0x6C_9A_B8), Color(0x6C_8E_AA))
    private val NODE_COLOR = JBColor(Color(0x4E_9A_D3), Color(0x89_B4_FA))
    private val WC_COLOR = JBColor(Color(0x57_A6_4A), Color(0x57_A6_4A))
    private val CONFLICT_COLOR = JBColor(Color(0xCC_44_44), Color(0xE7_71_72))
    private val IMMUTABLE_COLOR = JBColor(Color(0x3E_7D_C8), Color(0x74_C7_EC))

    private val SOLID_STROKE = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    private val DASHED_STROKE = BasicStroke(
        1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
        floatArrayOf(2f, 3f), 0f
    )

    /**
     * Paint all [EnhancedLogLine]s that pass through [rowIndex] onto [g].
     *
     * [g]'s origin should already be at (0, 0) of the cell (i.e. the top-left
     * of the 30 px row). We translate internally by -rowIndex * ROW_HEIGHT to
     * obtain absolute graph coordinates, then set a clip for this row.
     */
    fun paintLines(g: Graphics2D, lines: List<EnhancedLogLine>, rowIndex: Int, cellWidth: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // clip to the 30px cell height
            g2.setClip(0, 0, cellWidth, ROW_HEIGHT)
            // shift so absolute graph y=0 corresponds to the top of the entire graph
            g2.translate(0, -rowIndex * ROW_HEIGHT)

            for (enhLine in lines) {
                paintLine(g2, enhLine)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun paintLine(g: Graphics2D, enhLine: EnhancedLogLine) {
        val line = enhLine.line
        val path = buildPath(line)

        g.stroke = if (line.indirect) DASHED_STROKE else SOLID_STROKE
        g.color = if (line.indirect) LINE_COLOR_INDIRECT else LINE_COLOR
        g.draw(path)
    }

    /** Build the full GeneralPath for a line in absolute graph coordinates. */
    private fun buildPath(line: LogLine): GeneralPath {
        val c1 = line.source.col
        val c2 = line.target.col
        val r1 = line.source.row
        val r2 = line.target.row

        val childY = r1 * ROW_HEIGHT + 21f
        val parentY = r2 * ROW_HEIGHT + 9f

        return when {
            line is LogLine.ToIntersection && c1 != c2 -> {
                // merge bend: child → horizontal → merge column
                mergeColumnPath(c1, c2, childY, parentY)
            }
            line is LogLine.FromNode && line.via != null && line.via != c1 -> {
                // rescue sweep with three segments
                rescuePath(c1, c2, line.via, r1, r2, childY, parentY)
            }
            c1 == c2 -> {
                // straight vertical
                straightPath(c1, childY, parentY)
            }
            else -> {
                // two-segment curved line (the common case)
                curvedPath(c1, c2, r1, r2, childY, parentY, line is LogLine.FromNode)
            }
        }
    }

    private fun straightPath(col: Int, childY: Float, parentY: Float): GeneralPath {
        val x = col * COLUMN_WIDTH + HALF_COL.toFloat()
        return GeneralPath().also {
            it.moveTo(x, childY)
            it.lineTo(x, parentY)
        }
    }

    private fun curvedPath(
        c1: Int, c2: Int,
        r1: Int, r2: Int,
        childY: Float, parentY: Float,
        isFromNode: Boolean,
    ): GeneralPath {
        val childX = c1 * COLUMN_WIDTH + HALF_COL.toFloat()
        val parentX = c2 * COLUMN_WIDTH + HALF_COL.toFloat()
        // rescue FromNodes (right-to-left only) bend near the target row boundary
        val midY = if (isFromNode && c1 > c2) r2 * ROW_HEIGHT.toFloat() else childY + 9f
        val radius = if (c2 > c1) ARC_RADIUS else -ARC_RADIUS
        val sweep = c2 > c1 // true = clockwise

        return GeneralPath().also { p ->
            p.moveTo(childX, childY)
            p.lineTo(childX, midY - ARC_RADIUS)
            arcTo(p, childX, midY - ARC_RADIUS, childX + radius, midY, sweep)
            p.lineTo(parentX - radius, midY)
            arcTo(p, parentX - radius, midY, parentX, midY + ARC_RADIUS, !sweep)
            p.lineTo(parentX, parentY)
        }
    }

    private fun mergeColumnPath(c1: Int, c2: Int, childY: Float, parentY: Float): GeneralPath {
        val childX = c1 * COLUMN_WIDTH + HALF_COL.toFloat()
        val mergeX = c2 * COLUMN_WIDTH + HALF_COL.toFloat()
        val midY = if (c2 > c1) childY + 9f else parentY - 9f
        val radius = if (c2 > c1) ARC_RADIUS else -ARC_RADIUS
        val sweep = c2 > c1

        return GeneralPath().also { p ->
            p.moveTo(childX, childY)
            p.lineTo(childX, midY - ARC_RADIUS)
            arcTo(p, childX, midY - ARC_RADIUS, childX + radius, midY, sweep)
            p.lineTo(mergeX - radius, midY)
            arcTo(p, mergeX - radius, midY, mergeX, midY + ARC_RADIUS, !sweep)
            p.lineTo(mergeX, parentY)
        }
    }

    private fun rescuePath(
        c1: Int, c2: Int, via: Int,
        r1: Int, r2: Int,
        childY: Float, parentY: Float,
    ): GeneralPath {
        val childX = c1 * COLUMN_WIDTH + HALF_COL.toFloat()
        val viaX = via * COLUMN_WIDTH + HALF_COL.toFloat()
        val parentX = c2 * COLUMN_WIDTH + HALF_COL.toFloat()
        val topMidY = childY + 9f
        val botMidY = r2 * ROW_HEIGHT.toFloat()
        val topRadius = if (viaX > childX) ARC_RADIUS else -ARC_RADIUS
        val topSweep = viaX > childX
        val botRadius = if (parentX > viaX) ARC_RADIUS else -ARC_RADIUS
        val botSweep = parentX > viaX

        return GeneralPath().also { p ->
            p.moveTo(childX, childY)
            p.lineTo(childX, topMidY - ARC_RADIUS)
            arcTo(p, childX, topMidY - ARC_RADIUS, childX + topRadius, topMidY, topSweep)
            p.lineTo(viaX - topRadius, topMidY)
            arcTo(p, viaX - topRadius, topMidY, viaX, topMidY + ARC_RADIUS, !topSweep)
            p.lineTo(viaX, botMidY - ARC_RADIUS)
            arcTo(p, viaX, botMidY - ARC_RADIUS, viaX + botRadius, botMidY, botSweep)
            p.lineTo(parentX - botRadius, botMidY)
            arcTo(p, parentX - botRadius, botMidY, parentX, botMidY + ARC_RADIUS, !botSweep)
            p.lineTo(parentX, parentY)
        }
    }

    /**
     * Approximate an SVG arc (A6,6) with a quadratic bezier curve.
     * This produces a visually equivalent 90-degree rounded corner.
     */
    private fun arcTo(p: GeneralPath, x1: Float, y1: Float, x2: Float, y2: Float, clockwise: Boolean) {
        // control point is the corner of the right angle formed by the two endpoints
        val cx = if (y1 == y2 || clockwise) x2 else x1
        val cy = if (y1 == y2 || clockwise) y1 else y2
        p.quadTo(cx, cy, x2, y2)
    }

    /**
     * Paint the commit node circle at [row]'s column, using absolute coordinates.
     * The caller must apply the same -rowIndex * ROW_HEIGHT translation as for lines.
     */
    fun paintNode(g: Graphics2D, row: EnhancedLogRow, rowIndex: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(0, -rowIndex * ROW_HEIGHT)

            val col = row.row.location.col
            val cx = col * COLUMN_WIDTH + HALF_COL.toFloat()
            val cy = row.row.location.row * ROW_HEIGHT + 15f  // centre of row

            val header = row.row.revision
            val diameter = NODE_RADIUS * 2f
            val shape = Ellipse2D.Float(cx - NODE_RADIUS, cy - NODE_RADIUS, diameter, diameter)

            when {
                header.has_conflict -> {
                    g2.color = CONFLICT_COLOR
                    g2.fill(shape)
                }
                header.is_working_copy -> {
                    g2.color = WC_COLOR
                    g2.fill(shape)
                }
                header.is_immutable -> {
                    g2.color = IMMUTABLE_COLOR
                    g2.fill(shape)
                    g2.color = IMMUTABLE_COLOR.darker()
                    g2.stroke = BasicStroke(1f)
                    g2.draw(shape)
                }
                else -> {
                    // hollow circle
                    g2.color = NODE_COLOR
                    g2.stroke = BasicStroke(1.5f)
                    g2.draw(shape)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    /** Width required to display a graph with [maxColumn] columns. */
    fun graphWidth(maxColumn: Int): Int = (maxColumn + 2) * COLUMN_WIDTH
}
