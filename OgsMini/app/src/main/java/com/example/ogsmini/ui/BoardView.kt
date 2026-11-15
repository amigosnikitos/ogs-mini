package com.example.ogsmini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.example.ogsmini.GameUi
import com.example.ogsmini.core.Stone
import kotlin.math.roundToInt

private val Wood = Color(0xFFDCB35C)
private val GridColor = Color(0xFF3A2A10)
private val BlackStone = Color(0xFF111111)
private val WhiteStone = Color(0xFFF7F7F2)
private val WhiteEdge = Color(0xFF555555)

private fun starPoints(w: Int, h: Int): List<Pair<Int, Int>> {
    if (w != h) return emptyList()
    val axis = when (w) {
        19 -> listOf(3, 9, 15)
        13 -> listOf(3, 6, 9)
        9 -> listOf(2, 4, 6)
        else -> emptyList()
    }
    return axis.flatMap { x -> axis.map { y -> Pair(x, y) } }
}

private fun DrawScope.drawStone(center: Offset, radius: Float, white: Boolean, alpha: Float = 1f) {
    drawCircle(if (white) WhiteStone else BlackStone, radius, center, alpha)
    if (white) drawCircle(WhiteEdge, radius, center, alpha, Stroke(width = radius * 0.08f))
}

/** Доска: поле с отступом в одну клетку вокруг линий, камни, последний ход, пробный камень, мёртвые камни. */
@Composable
fun GoBoardView(ui: GameUi, onTap: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val w = ui.width
    val h = ui.height
    Canvas(
        modifier = modifier
            .aspectRatio((w + 1f) / (h + 1f))
            .pointerInput(w, h) {
                detectTapGestures { offset ->
                    val cell = size.width / (w + 1f)
                    val x = ((offset.x - cell) / cell).roundToInt()
                    val y = ((offset.y - cell) / cell).roundToInt()
                    if (x in 0 until w && y in 0 until h) onTap(x, y)
                }
            }
    ) {
        val cell = size.width / (w + 1f)
        fun at(x: Int, y: Int) = Offset(cell * (x + 1), cell * (y + 1))

        drawRect(Wood)
        for (i in 0 until w) drawLine(GridColor, at(i, 0), at(i, h - 1), strokeWidth = 1.5f)
        for (j in 0 until h) drawLine(GridColor, at(0, j), at(w - 1, j), strokeWidth = 1.5f)
        for ((sx, sy) in starPoints(w, h)) drawCircle(GridColor, cell * 0.09f, at(sx, sy))

        val radius = cell * 0.47f
        if (ui.cells.size == w * h) {
            for (i in ui.cells.indices) {
                val v = ui.cells[i]
                if (v == 0) continue
                val dead = i in ui.removed
                drawStone(at(i % w, i / w), radius, white = v == 2, alpha = if (dead) 0.35f else 1f)
            }
            if (ui.lastMove in ui.cells.indices && ui.cells[ui.lastMove] != 0) {
                val white = ui.cells[ui.lastMove] == 2
                drawCircle(
                    if (white) BlackStone else WhiteStone,
                    cell * 0.2f,
                    at(ui.lastMove % w, ui.lastMove / w),
                    style = Stroke(width = cell * 0.08f),
                )
            }
        }
        if (ui.pending in 0 until w * h) {
            val c = at(ui.pending % w, ui.pending / w)
            drawStone(c, radius, white = ui.toPlay == Stone.WHITE, alpha = 0.6f)
            drawCircle(Color(0xFFD32F2F), radius * 0.35f, c, style = Stroke(width = cell * 0.08f))
        }
    }
}
