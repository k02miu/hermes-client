package io.github.k02miu.hermesmobile.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import io.github.k02miu.hermesmobile.ui.theme.HermesColors
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * A GFM table whose columns are sized by their content, the way a browser lays out an auto table:
 * every column gets its longest line when that fits, otherwise a readable floor (its whole text when
 * short, else [READABLE]) plus a share of the remaining width in proportion to how much it wants.
 * Columns are never squeezed below that floor: a table that cannot fit scrolls sideways instead.
 */
@Composable
fun AutoWidthTable(content: String, node: ASTNode, style: TextStyle) {
    val settings = annotatorSettings()
    val rows = remember(content, node, style) {
        node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }.map { row ->
            row.children.filter { it.type == GFMTokenTypes.CELL }.map { content.buildMarkdownAnnotatedString(it, style, settings) }
        }
    }
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.size }
    val line = HermesColors.Line
    val headerFill = HermesColors.Surface
    val geometry = remember { TableGeometry() }
    val shape = RoundedCornerShape(8.dp)
    BoxWithConstraints {
        val available = constraints.maxWidth
        Layout(
            content = {
                rows.forEachIndexed { r, cells ->
                    repeat(columns) { c ->
                        Text(
                            cells.getOrNull(c) ?: AnnotatedString(""),
                            style = style.copy(color = HermesColors.Text, fontWeight = if (r == 0) FontWeight.SemiBold else style.fontWeight),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .clip(shape)
                .border(1.dp, line, shape)
                .drawBehind {
                    if (geometry.rowBottoms.isEmpty()) return@drawBehind
                    drawRect(headerFill, size = Size(size.width, geometry.rowBottoms[0].toFloat()))
                    geometry.rowBottoms.dropLast(1).forEach { y ->
                        drawLine(line, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), strokeWidth = 1.dp.toPx())
                    }
                },
        ) { measurables, _ ->
            val minCol = 40.dp.roundToPx()
            val maxCol = 320.dp.roundToPx()
            val readable = READABLE.roundToPx()
            val wantMin = IntArray(columns) { minCol }
            val wantMax = IntArray(columns) { minCol }
            measurables.forEachIndexed { i, m ->
                val c = i % columns
                wantMax[c] = maxOf(wantMax[c], minOf(maxCol, m.maxIntrinsicWidth(Constraints.Infinity)))
                wantMin[c] = maxOf(wantMin[c], minOf(maxCol, m.minIntrinsicWidth(Constraints.Infinity)))
            }
            for (c in 0 until columns) wantMin[c] = minOf(maxOf(wantMin[c], readable), wantMax[c])
            val sumMin = wantMin.sum()
            val sumMax = wantMax.sum()
            val widths = when {
                sumMax <= available -> wantMax
                sumMin >= available -> wantMin
                else -> IntArray(columns) { c ->
                    wantMin[c] + ((wantMax[c] - wantMin[c]).toLong() * (available - sumMin) / (sumMax - sumMin)).toInt()
                }
            }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % columns])) }
            val rowHeights = placeables.chunked(columns).map { row -> row.maxOf { it.height } }
            geometry.rowBottoms = rowHeights.runningReduce { acc, h -> acc + h }
            layout(widths.sum(), rowHeights.sum()) {
                var y = 0
                placeables.chunked(columns).forEachIndexed { r, row ->
                    var x = 0
                    row.forEachIndexed { c, p ->
                        p.placeRelative(x, y)
                        x += widths[c]
                    }
                    y += rowHeights[r]
                }
            }
        }
    }
}

/** The narrowest a column of long text gets before the table scrolls instead: about ten characters. */
private val READABLE = 140.dp

/** Row edges from the last layout pass, for the header fill and the row rules drawn behind the cells. */
private class TableGeometry {
    var rowBottoms: List<Int> = emptyList()
}
