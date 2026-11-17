package com.example.ogsmini.core

/** Ход в дереве партии: чей камень и куда. Пас — координаты (-1, -1). */
data class TreeMove(val color: Stone, val x: Int, val y: Int) {
    val isPass: Boolean get() = x < 0 || y < 0
}

/** Очерёдность ходов — одно и то же правило для самой партии и для анализа. */
object TurnRules {
    /**
     * Кто ходит после хода игрока [mover], если до этого хода было сыграно [movesBefore] ходов.
     * Обычно очередь переходит к сопернику. Исключение — фора, которую чёрные расставляют сами
     * (`free_handicap_placement`): они делают подряд [handicap] ходов. Это то же правило, что в движке
     * официального клиента OGS.
     */
    fun next(mover: Stone, movesBefore: Int, handicap: Int, freeHandicap: Boolean): Stone {
        val handicapMovesLeft = if (freeHandicap) maxOf(0, handicap - movesBefore) else 0
        return if (handicapMovesLeft < 2) mover.other else mover
    }
}

/** Всё, что определяет начальную позицию и очерёдность. Если это изменилось, дерево строится заново. */
data class TreeSetup(
    val width: Int,
    val height: Int = width,
    val black: List<Pair<Int, Int>> = emptyList(),
    val white: List<Pair<Int, Int>> = emptyList(),
    val initialPlayer: Stone = Stone.BLACK,
    val handicap: Int = 0,
    val freeHandicap: Boolean = false,
)

/**
 * Узел дерева: позиция после хода [move]. Корень (move == null) — начальная позиция с форой.
 * Как и в дереве ходов сайта OGS, у узла основной линии есть [trunkNext] — следующий ход самой партии,
 * а все остальные продолжения — ветки-варианты.
 */
class MoveNode internal constructor(
    val id: Int,
    val parent: MoveNode?,
    val move: TreeMove?,
    /** Сколько ходов сыграно в этой позиции. */
    val number: Int,
    trunk: Boolean,
) {
    /** Узел лежит на основной линии (это ход, который был сыгран в партии). */
    var trunk: Boolean = trunk
        internal set
    var trunkNext: MoveNode? = null
        internal set
    internal val branches = ArrayList<MoveNode>()

    /** Какое продолжение выбрано последним: в него ведёт «вперёд», когда выбор не очевиден. */
    internal var hint: MoveNode? = null

    /** Все продолжения: сначала ход из самой партии (если он есть), затем варианты в порядке создания. */
    val children: List<MoveNode>
        get() {
            val t = trunkNext ?: return branches.toList()
            val out = ArrayList<MoveNode>(branches.size + 1)
            out.add(t)
            out.addAll(branches)
            return out
        }
}

/** Строка списка ходов или вариант продолжения — то, что нужно интерфейсу, без доступа к самим узлам. */
data class NodeInfo(
    val id: Int,
    val number: Int,
    val move: TreeMove?,
    val trunk: Boolean,
    /** Глубина вложенности вариантов: 0 — основная линия, 1 — вариант, 2 — вариант в варианте… */
    val indent: Int = 0,
    val current: Boolean = false,
)

/**
 * Дерево ходов партии: основная линия (то, что сыграно на самом деле, её присылает сервер) и ветки-варианты,
 * которые пользователь строит у себя в режиме анализа. Варианты никуда не отправляются.
 *
 * Правила повторяют дерево ходов сайта OGS:
 *  * «вперёд» всегда идёт по основной линии, пока она есть; в варианте — в ветку, из которой вернулись, иначе в первую;
 *  * поставить в анализе камень туда, где продолжение уже есть, значит перейти в него, а не создавать копию;
 *  * когда сервер присылает ход, совпадающий с вариантом, вариант становится основной линией;
 *  * основную линию удалить нельзя, ветку — можно.
 *
 * Вне режима анализа курсор всегда на основной линии (это просто листание истории партии).
 */
class GameTree(val setup: TreeSetup) {

    private var nextId = 1

    val root = MoveNode(0, null, null, 0, true)

    /** Последний сыгранный ход основной линии (текущая позиция партии). */
    var trunkEnd: MoveNode = root
        private set

    /** Показанная позиция. */
    var cursor: MoveNode = root
        private set

    var analysis: Boolean = false
        private set

    /** Растёт при каждом изменении структуры дерева — для кэшей в интерфейсе. */
    var version: Int = 0
        private set

    val width: Int get() = setup.width
    val height: Int get() = setup.height

    /** Сколько ходов сыграно в партии. */
    val trunkLength: Int get() = trunkEnd.number

    /** Показана текущая позиция партии (не история и не анализ). */
    val live: Boolean get() = !analysis && cursor === trunkEnd

    // ------------------------------------------------------------------ основная линия

    /** Новый ход из партии. Если курсор стоял на последнем ходу (и не анализ), он идёт за партией. */
    fun appendTrunk(move: TreeMove) {
        val follow = !analysis && cursor === trunkEnd
        addTrunkNode(move)
        if (follow) cursor = trunkEnd
        version++
    }

    /**
     * Приводит основную линию к списку ходов [moves], который прислал сервер. Общее начало остаётся как есть
     * (вместе с вариантами), хвост заменяется. Так же обрабатывается повторная загрузка партии и отмена хода.
     */
    fun syncTrunk(moves: List<TreeMove>) {
        val follow = !analysis && cursor === trunkEnd
        val nodes = ArrayList<MoveNode>()
        var n: MoveNode? = root
        while (n != null) {
            nodes.add(n)
            n = n.trunkNext
        }
        var common = 0
        while (common < moves.size && common + 1 < nodes.size && nodes[common + 1].move == moves[common]) common++
        if (common == moves.size && common + 1 == nodes.size) return // уже совпадает

        val anchor = nodes[common]
        val cut = anchor.trunkNext
        val cursorCut = cut != null && isWithin(cursor, cut)
        anchor.trunkNext = null
        trunkEnd = anchor
        for (i in common until moves.size) addTrunkNode(moves[i])
        if (follow) cursor = trunkEnd else if (cursorCut) cursor = anchor
        version++
    }

    private fun addTrunkNode(move: TreeMove) {
        val end = trunkEnd
        val promoted = end.branches.indexOfFirst { it.move == move }
        val node = if (promoted >= 0) {
            // такой вариант уже был: он становится ходом партии, вместе со всем, что под ним
            end.branches.removeAt(promoted).also { it.trunk = true }
        } else {
            MoveNode(nextId++, end, move, end.number + 1, true)
        }
        end.trunkNext = node
        trunkEnd = node
    }

    private fun isWithin(node: MoveNode, subtreeRoot: MoveNode): Boolean {
        var n: MoveNode? = node
        while (n != null) {
            if (n === subtreeRoot) return true
            n = n.parent
        }
        return false
    }

    // ------------------------------------------------------------------ листание

    /** Куда ведёт «вперёд» из [node]. Вне анализа — только по основной линии. */
    private fun step(node: MoveNode): MoveNode? {
        node.trunkNext?.let { return it }
        if (!analysis) return null
        val h = node.hint
        if (h != null && node.branches.any { it === h }) return h
        return node.branches.firstOrNull()
    }

    /** Есть ход назад от показанной позиции. */
    val hasPrev: Boolean get() = cursor.parent != null

    /** Есть ход вперёд от показанной позиции (вне анализа — только по партии). */
    val hasNext: Boolean get() = step(cursor) != null

    fun first() {
        cursor = root
    }

    fun prev(): Boolean {
        val p = cursor.parent ?: return false
        p.hint = cursor
        cursor = p
        return true
    }

    fun next(): Boolean {
        val n = step(cursor) ?: return false
        cursor = n
        return true
    }

    /** В конец показанной линии: до последнего хода основной линии или до конца варианта. */
    fun last() {
        var n = cursor
        while (true) n = step(n) ?: break
        cursor = n
    }

    /** К текущей позиции партии; режим анализа выключается (построенные варианты остаются). */
    fun goLive() {
        analysis = false
        cursor = trunkEnd
    }

    /**
     * Выключить анализ, оставшись на месте. Если курсор стоял в варианте, он переходит на ближайший ход
     * партии, от которого вариант ответвлялся. Построенные варианты остаются в дереве.
     */
    fun exitAnalysis() {
        analysis = false
        var n = cursor
        while (!n.trunk) n = n.parent ?: break
        cursor = n
    }

    /** Показанная линия: от начала партии через курсор и дальше до конца. */
    fun line(): List<MoveNode> {
        val out = ArrayList<MoveNode>()
        var n: MoveNode? = cursor
        while (n != null) {
            out.add(n)
            n = n.parent
        }
        out.reverse()
        var m = step(cursor)
        while (m != null) {
            out.add(m)
            m = step(m)
        }
        return out
    }

    /** Сколько ходов в показанной линии. */
    fun lineLength(): Int = line().size - 1

    /** Перейти к ходу номер [index] показанной линии (для ползунка). */
    fun seek(index: Int) {
        val l = line()
        val target = l[index.coerceIn(0, l.lastIndex)]
        markPath(target)
        cursor = target
    }

    /** Перейти к узлу [node] (например, из списка ходов). Узел вне основной линии включает анализ. */
    fun jumpTo(node: MoveNode) {
        if (!node.trunk) analysis = true
        markPath(node)
        cursor = node
    }

    private fun markPath(node: MoveNode) {
        var c = node
        while (true) {
            val p = c.parent ?: break
            p.hint = c
            c = p
        }
    }

    /** Все продолжения показанной позиции. */
    fun siblings(): List<MoveNode> = cursor.parent?.children ?: listOf(cursor)

    /** Переключиться на соседний вариант того же хода (в рамках одного родителя). */
    fun moveSibling(delta: Int): Boolean {
        if (!analysis) return false
        val p = cursor.parent ?: return false
        val list = p.children
        val i = list.indexOfFirst { it === cursor }
        val j = i + delta
        if (i < 0 || j !in list.indices) return false
        p.hint = list[j]
        cursor = list[j]
        return true
    }

    fun find(id: Int): MoveNode? {
        val stack = ArrayList<MoveNode>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.lastIndex)
            if (n.id == id) return n
            stack.addAll(n.children)
        }
        return null
    }

    // ------------------------------------------------------------------ анализ

    fun enterAnalysis() {
        analysis = true
    }

    /**
     * Поставить камень в режиме анализа. Если такое продолжение уже есть, просто переходит в него.
     * Возвращает результат проверки правил; не OK — ничего не изменилось.
     * Вне анализа ставить нельзя (OFF_BOARD).
     */
    fun play(x: Int, y: Int): PlayCheck {
        if (!analysis) return PlayCheck.OFF_BOARD
        val node = cursor
        val existing = node.children.firstOrNull { val m = it.move; m != null && m.x == x && m.y == y }
        if (existing != null) {
            node.hint = existing
            cursor = existing
            return PlayCheck.OK
        }
        val color = colorToPlay(node)
        val check = boardAt(node).check(x, y, color)
        if (check != PlayCheck.OK) return check
        addBranch(node, TreeMove(color, x, y))
        return PlayCheck.OK
    }

    /** Пас в режиме анализа. */
    fun pass(): Boolean {
        if (!analysis) return false
        val node = cursor
        val existing = node.children.firstOrNull { it.move?.isPass == true }
        if (existing != null) {
            node.hint = existing
            cursor = existing
            return true
        }
        addBranch(node, TreeMove(colorToPlay(node), -1, -1))
        return true
    }

    private fun addBranch(parent: MoveNode, move: TreeMove) {
        val n = MoveNode(nextId++, parent, move, parent.number + 1, false)
        parent.branches.add(n)
        parent.hint = n
        cursor = n
        version++
    }

    val canDeleteBranch: Boolean get() = analysis && !cursor.trunk && cursor.parent != null

    /** Удалить ветку, на которой стоит курсор (вместе с продолжениями). Ходы партии не удаляются. */
    fun deleteBranch(): Boolean {
        if (!canDeleteBranch) return false
        val n = cursor
        val p = n.parent ?: return false
        p.branches.removeAll { it === n }
        if (p.hint === n) p.hint = null
        cursor = p
        version++
        return true
    }

    // ------------------------------------------------------------------ вопросы

    /** Чей ход в позиции [node]. */
    fun colorToPlay(node: MoveNode = cursor): Stone {
        val m = node.move ?: return setup.initialPlayer
        return TurnRules.next(m.color, node.number - 1, setup.handicap, setup.freeHandicap)
    }

    private var cacheId = -1
    private var cacheVersion = -1
    private var cacheBoard: GoBoard? = null

    /**
     * Доска в позиции [node]: начальные камни и все ходы от начала партии. Результат не изменять —
     * для последней запрошенной позиции он хранится в кэше.
     */
    fun boardAt(node: MoveNode = cursor): GoBoard {
        val cached = cacheBoard
        if (cached != null && node.id == cacheId && version == cacheVersion) return cached
        val b = GoBoard(setup.width, setup.height)
        for ((x, y) in setup.black) b.setStone(x, y, Stone.BLACK)
        for ((x, y) in setup.white) b.setStone(x, y, Stone.WHITE)
        val path = ArrayList<MoveNode>()
        var n: MoveNode? = node
        while (n != null && n.move != null) {
            path.add(n)
            n = n.parent
        }
        for (i in path.indices.reversed()) {
            val m = path[i].move ?: continue
            b.play(m.x, m.y, m.color)
        }
        cacheId = node.id
        cacheVersion = version
        cacheBoard = b
        return b
    }

    fun info(node: MoveNode, indent: Int = 0): NodeInfo =
        NodeInfo(node.id, node.number, node.move, node.trunk, indent, node === cursor)

    /** Варианты продолжения показанной позиции — только если их несколько. */
    fun branchChoices(): List<NodeInfo> {
        val c = cursor.children
        return if (analysis && c.size >= 2) c.map { info(it) } else emptyList()
    }

    /** Есть ли в дереве хотя бы один вариант (ветка вне основной линии). */
    fun hasVariations(): Boolean {
        val stack = ArrayList<MoveNode>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.lastIndex)
            if (n.branches.isNotEmpty()) return true
            n.trunkNext?.let { stack.add(it) }
        }
        return false
    }

    /**
     * Список ходов с вариантами, как структура вложенности: основная линия с отступом 0; варианты идут сразу
     * после хода, от которого ответвились, с отступом больше, а основная линия продолжается после них.
     */
    fun outline(maxRows: Int = 5000): List<NodeInfo> {
        val rows = ArrayList<NodeInfo>()
        val stack = ArrayList<Pair<MoveNode, Int>>()
        pushChildren(root, 0, stack)
        while (stack.isNotEmpty() && rows.size < maxRows) {
            val (n, indent) = stack.removeAt(stack.lastIndex)
            rows.add(info(n, indent))
            pushChildren(n, indent, stack)
        }
        return rows
    }

    // Порядок обхода: сначала альтернативы (на уровень глубже), затем основное продолжение.
    // Стек работает с конца, поэтому кладём в обратном порядке.
    private fun pushChildren(n: MoveNode, indent: Int, stack: MutableList<Pair<MoveNode, Int>>) {
        val c = n.children
        if (c.isEmpty()) return
        stack.add(c[0] to indent)
        for (i in c.lastIndex downTo 1) stack.add(c[i] to indent + 1)
    }

}
