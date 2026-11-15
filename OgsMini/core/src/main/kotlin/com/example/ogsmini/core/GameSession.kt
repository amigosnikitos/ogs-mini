package com.example.ogsmini.core

/**
 * Состояние одной открытой партии: доска, чья очередь, фаза, часы, отмеченные при подсчёте камни.
 * Чистая логика без сети и Android — поэтому проверяется обычными юнит-тестами.
 */
class GameSession(val gameId: Long) {

    enum class MoveResult { APPLIED, IGNORED, DESYNC }

    var data: GameData? = null
        private set
    var board: GoBoard = GoBoard(19)
        private set
    var moveCount: Int = 0
        private set
    var toPlay: Stone = Stone.BLACK
        private set
    var phase: String = "loading"
        private set
    var clock: ClockInfo? = null
        private set
    var outcome: String? = null
        private set

    /** Индексы камней, отмеченных мёртвыми в фазе подсчёта. */
    var removed: Set<Int> = emptySet()
        private set

    /** Строка «all_removed» в том виде, в каком её прислал сервер (для кнопки «Принять»). */
    var removedRaw: String = ""
        private set

    /** Разница «время сервера − время устройства» в мс, по полю now в событиях часов. */
    var clockOffsetMs: Long = 0
        private set

    private var lastX = -2
    private var lastY = -2

    val loaded: Boolean get() = data != null

    fun applyGameData(d: GameData) {
        data = d
        val b = GoBoard(d.width, d.height)
        for ((x, y) in Coord.decodeAll(d.initialBlack)) b.setStone(x, y, Stone.BLACK)
        for ((x, y) in Coord.decodeAll(d.initialWhite)) b.setStone(x, y, Stone.WHITE)
        var color = d.initialPlayer
        for (m in d.moves) {
            b.play(m.x, m.y, color)
            color = color.other
        }
        board = b
        moveCount = d.moves.size
        toPlay = color
        val last = d.moves.lastOrNull()
        lastX = last?.x ?: -2
        lastY = last?.y ?: -2
        phase = d.phase
        clock = d.clock
        outcome = d.outcome
        setRemoved(d.removed)
        syncTurnWithClock()
    }

    /**
     * Применяет ход из события `game/<id>/move`.
     * Номер хода в документации не расписан (с нуля или с единицы), поэтому проверка терпимая:
     * явные пропуски дают DESYNC, повтор уже применённого хода игнорируется.
     */
    fun applyMove(number: Int?, x: Int, y: Int): MoveResult {
        if (!loaded) return MoveResult.DESYNC
        if (number != null) {
            if (number > moveCount + 1) return MoveResult.DESYNC
            if (number < moveCount) return MoveResult.IGNORED
            if (number == moveCount && x == lastX && y == lastY) return MoveResult.IGNORED
        }
        board.play(x, y, toPlay)
        toPlay = toPlay.other
        moveCount++
        lastX = x
        lastY = y
        return MoveResult.APPLIED
    }

    fun applyClock(c: ClockInfo, localNowMs: Long) {
        clock = c
        c.nowMs?.let { clockOffsetMs = it - localNowMs }
        syncTurnWithClock()
    }

    fun applyPhase(p: String) {
        phase = p
        if (p != "stone removal") {
            removed = emptySet()
            removedRaw = ""
        }
    }

    fun applyRemoved(ev: RemovedEvent) {
        if (ev.allRemoved != null) {
            setRemoved(ev.allRemoved)
            return
        }
        val points = Coord.decodeAll(ev.stones)
            .filter { board.inside(it.first, it.second) }
            .map { board.index(it.first, it.second) }
        removed = if (ev.removed) removed + points else removed - points.toSet()
        removedRaw = removed.sorted().joinToString("") { Coord.encode(it % board.width, it / board.width) }
    }

    private fun setRemoved(raw: String) {
        removedRaw = raw
        removed = Coord.decodeAll(raw)
            .filter { board.inside(it.first, it.second) }
            .map { board.index(it.first, it.second) }
            .toSet()
    }

    private fun syncTurnWithClock() {
        val cp = clock?.currentPlayer ?: return
        val d = data ?: return
        if (cp == d.black?.id) toPlay = Stone.BLACK else if (cp == d.white?.id) toPlay = Stone.WHITE
    }

    // ---------- вопросы для интерфейса ----------

    fun myColor(userId: Long): Stone? {
        val d = data ?: return null
        return when (userId) {
            d.black?.id -> Stone.BLACK
            d.white?.id -> Stone.WHITE
            else -> null
        }
    }

    fun isMyTurn(userId: Long): Boolean = phase == "play" && myColor(userId) == toPlay

    /** Камни группы под пальцем (для отметки мёртвых). Пустая клетка — пустой список. */
    fun removalGroup(x: Int, y: Int): List<Int> {
        if (!board.inside(x, y) || board.get(x, y) == null) return emptyList()
        return board.group(x, y)
    }

    /** Группа сейчас отмечена мёртвой? */
    fun isRemoved(x: Int, y: Int): Boolean = board.inside(x, y) && board.index(x, y) in removed

    fun playerClock(color: Stone): PlayerClock? =
        clock?.let { if (color == Stone.BLACK) it.black else it.white }

    /** Оставшееся время игрока в секундах (только для часов с thinking_time), с учётом идущего хода. */
    fun remainingSeconds(color: Stone, localNowMs: Long): Double? {
        val c = clock ?: return null
        val pc = (if (color == Stone.BLACK) c.black else c.white) ?: return null
        val base = pc.thinking ?: return null
        if (phase != "play" || color != toPlay) return base
        val last = c.lastMoveMs ?: return base
        val elapsed = (localNowMs + clockOffsetMs - last) / 1000.0
        return (base - elapsed).coerceAtLeast(0.0)
    }
}
