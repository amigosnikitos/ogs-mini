package com.example.ogsmini.core

/** Кодирование координат в формате OGS: две буквы a..z (как в SGF), пас — "..". */
object Coord {
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
    const val PASS = ".."

    fun encode(x: Int, y: Int): String =
        if (x < 0 || y < 0 || x >= LETTERS.length || y >= LETTERS.length) PASS else "${LETTERS[x]}${LETTERS[y]}"

    /** Разбирает пару символов начиная с позиции [at]. ".." даёт (-1, -1). При ошибке — null. */
    fun decode(s: String, at: Int = 0): Pair<Int, Int>? {
        if (at < 0 || s.length < at + 2) return null
        val a = s[at]
        val b = s[at + 1]
        if (a == '.' && b == '.') return Pair(-1, -1)
        val x = LETTERS.indexOf(a)
        val y = LETTERS.indexOf(b)
        return if (x < 0 || y < 0) null else Pair(x, y)
    }

    private const val BOARD_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ" // как на доске: буквы I нет

    /** Название точки как на доске: столбец буквой (без I), строка считается снизу. Пас — «Пас». */
    fun label(x: Int, y: Int, height: Int): String {
        if (x < 0 || y < 0) return "Пас"
        val col = if (x < BOARD_COLUMNS.length) BOARD_COLUMNS[x].toString() else "?"
        return "$col${height - y}"
    }

    /** Строка вида "aabbcc" → список точек. Нераспознанные пары пропускаются. */
    fun decodeAll(s: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 1 < s.length) {
            val p = decode(s, i)
            if (p != null) out.add(p)
            i += 2
        }
        return out
    }
}
