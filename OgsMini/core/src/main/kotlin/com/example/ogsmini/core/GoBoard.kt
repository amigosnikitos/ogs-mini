package com.example.ogsmini.core

enum class Stone {
    BLACK, WHITE;

    val other: Stone get() = if (this == BLACK) WHITE else BLACK
}

enum class PlayCheck { OK, OFF_BOARD, OCCUPIED, KO, SUICIDE }

/**
 * Доска го: захваты, простое ко, группы и дыхания.
 *
 * Координаты как в OGS: x — столбец слева направо, y — строка сверху вниз, (0,0) — левый верхний угол.
 */
class GoBoard(val width: Int, val height: Int = width) {

    private val cells = IntArray(width * height) // 0 — пусто, 1 — чёрный, 2 — белый

    /** captures[0] — сколько камней сняли чёрные, captures[1] — сколько сняли белые. */
    val captures = IntArray(2)

    /** Точка, на которую сейчас нельзя ставить из-за ко (или -1). */
    var koPoint: Int = -1
        private set

    /** Индекс последнего поставленного камня (или -1: ходов не было / был пас). */
    var lastMove: Int = -1
        private set

    fun index(x: Int, y: Int): Int = y * width + x
    fun inside(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun get(x: Int, y: Int): Stone? = decode(cells[index(x, y)])
    fun at(i: Int): Stone? = decode(cells[i])

    fun setStone(x: Int, y: Int, color: Stone?) {
        if (inside(x, y)) cells[index(x, y)] = encode(color)
    }

    /** Копия содержимого доски: 0 — пусто, 1 — чёрный, 2 — белый. */
    fun snapshot(): IntArray = cells.copyOf()

    fun copy(): GoBoard {
        val b = GoBoard(width, height)
        System.arraycopy(cells, 0, b.cells, 0, cells.size)
        b.captures[0] = captures[0]
        b.captures[1] = captures[1]
        b.koPoint = koPoint
        b.lastMove = lastMove
        return b
    }

    private fun neighbors(i: Int): IntArray {
        val x = i % width
        val y = i / width
        val out = IntArray(4)
        var n = 0
        if (x > 0) out[n++] = i - 1
        if (x < width - 1) out[n++] = i + 1
        if (y > 0) out[n++] = i - width
        if (y < height - 1) out[n++] = i + width
        return if (n == 4) out else out.copyOf(n)
    }

    /** Связная группа (камни одного цвета либо пустая область), содержащая клетку (x, y). */
    fun group(x: Int, y: Int): List<Int> {
        val start = index(x, y)
        val color = cells[start]
        val seen = BooleanArray(cells.size)
        val stack = ArrayList<Int>()
        val result = ArrayList<Int>()
        stack.add(start)
        seen[start] = true
        while (stack.isNotEmpty()) {
            val i = stack.removeAt(stack.size - 1)
            result.add(i)
            for (n in neighbors(i)) {
                if (!seen[n] && cells[n] == color) {
                    seen[n] = true
                    stack.add(n)
                }
            }
        }
        return result
    }

    fun liberties(group: List<Int>): Int {
        val libs = HashSet<Int>()
        for (i in group) for (n in neighbors(i)) if (cells[n] == 0) libs.add(n)
        return libs.size
    }

    /**
     * Ставит камень без проверок ко/самоубийства — для воспроизведения ходов, которые уже принял сервер.
     * Возвращает число снятых камней. Координаты вне доски (например, -1,-1) считаются пасом.
     */
    fun play(x: Int, y: Int, color: Stone): Int {
        if (!inside(x, y)) {
            pass()
            return 0
        }
        val i = index(x, y)
        if (cells[i] != 0) return 0
        val me = encode(color)
        val opp = 3 - me
        cells[i] = me

        var captured = 0
        var lastCaptured = -1
        for (n in neighbors(i)) {
            if (cells[n] == opp) {
                val g = group(n % width, n / width)
                if (liberties(g) == 0) {
                    for (p in g) cells[p] = 0
                    captured += g.size
                    lastCaptured = g[0]
                }
            }
        }
        captures[me - 1] += captured

        val own = group(x, y)
        val ownLibs = liberties(own)
        if (ownLibs == 0) {
            // самоубийство — возможно только по особым правилам; группа снимается
            for (p in own) cells[p] = 0
            captures[opp - 1] += own.size
            koPoint = -1
        } else {
            koPoint = if (captured == 1 && own.size == 1 && ownLibs == 1) lastCaptured else -1
        }
        lastMove = i
        return captured
    }

    fun pass() {
        koPoint = -1
        lastMove = -1
    }

    /** Можно ли сейчас поставить камень цвета [color] в (x, y) — для подсказок в интерфейсе (сервер всё равно проверит). */
    fun check(x: Int, y: Int, color: Stone): PlayCheck {
        if (!inside(x, y)) return PlayCheck.OFF_BOARD
        val i = index(x, y)
        if (cells[i] != 0) return PlayCheck.OCCUPIED
        if (i == koPoint) return PlayCheck.KO
        val sim = copy()
        sim.play(x, y, color)
        return if (sim.cells[i] == 0) PlayCheck.SUICIDE else PlayCheck.OK
    }

    override fun toString(): String = buildString {
        for (y in 0 until height) {
            for (x in 0 until width) append(when (cells[index(x, y)]) { 1 -> 'X'; 2 -> 'O'; else -> '.' })
            append('\n')
        }
    }

    private companion object {
        fun encode(s: Stone?): Int = when (s) { Stone.BLACK -> 1; Stone.WHITE -> 2; null -> 0 }
        fun decode(v: Int): Stone? = when (v) { 1 -> Stone.BLACK; 2 -> Stone.WHITE; else -> null }
    }
}
