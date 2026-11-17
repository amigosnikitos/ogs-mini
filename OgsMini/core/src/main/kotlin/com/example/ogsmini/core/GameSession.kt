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
    /**
     * Чей сейчас ход. Считается только по списку ходов, начальному игроку и форе — как в движке официального
     * клиента OGS. Сообщения о часах (`current_player`) на это не влияют: сервер может прислать их раньше
     * самого хода, и тогда камень лёг бы чужим цветом.
     */
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

    /**
     * Дерево ходов: основная линия (ходы партии) и варианты, которые строит пользователь. Создаётся вместе с
     * данными партии и дальше идёт за ними: каждый ход сервера попадает и в доску, и в дерево.
     */
    var tree: GameTree? = null
        private set

    private var lastX = -2
    private var lastY = -2
    private var handicap = 0
    private var freeHandicap = false

    val loaded: Boolean get() = data != null

    fun applyGameData(d: GameData) {
        data = d
        handicap = d.handicap
        freeHandicap = d.freeHandicap
        val b = GoBoard(d.width, d.height)
        val black = Coord.decodeAll(d.initialBlack)
        val white = Coord.decodeAll(d.initialWhite)
        for ((x, y) in black) b.setStone(x, y, Stone.BLACK)
        for ((x, y) in white) b.setStone(x, y, Stone.WHITE)
        var color = d.initialPlayer
        val played = ArrayList<TreeMove>(d.moves.size)
        for ((i, m) in d.moves.withIndex()) {
            b.play(m.x, m.y, color)
            played.add(TreeMove(color, m.x, m.y))
            color = nextToPlay(color, i)
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

        // Та же партия (повторная загрузка, отмена хода) — дерево и варианты сохраняются, меняется только хвост основной линии.
        val setup = TreeSetup(d.width, d.height, black, white, d.initialPlayer, d.handicap, d.freeHandicap)
        val existing = tree
        if (existing != null && existing.setup == setup) {
            existing.syncTrunk(played)
        } else {
            tree = GameTree(setup).also { it.syncTrunk(played) }
        }
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
        val mover = toPlay
        board.play(x, y, mover)
        tree?.appendTrunk(TreeMove(mover, x, y))
        toPlay = nextToPlay(mover, moveCount)
        moveCount++
        lastX = x
        lastY = y
        return MoveResult.APPLIED
    }

    fun applyClock(c: ClockInfo, localNowMs: Long) {
        clock = c
        c.nowMs?.let { clockOffsetMs = it - localNowMs }
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

    /**
     * Кто ходит после хода игрока [mover], если до этого хода было сыграно [movesBefore] ходов.
     * Обычно очередь переходит к сопернику. Исключение — фора, которую чёрные расставляют сами
     * (`free_handicap_placement`): они делают подряд [handicap] ходов. Это то же правило, что в движке
     * официального клиента OGS.
     */
    private fun nextToPlay(mover: Stone, movesBefore: Int): Stone =
        TurnRules.next(mover, movesBefore, handicap, freeHandicap)

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
