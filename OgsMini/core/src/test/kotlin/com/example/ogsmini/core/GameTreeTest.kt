package com.example.ogsmini.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дерево ходов: просмотр истории партии и варианты, как на сайте OGS.
 * Основная линия — ходы партии, которые присылает сервер; варианты строит пользователь у себя.
 */
class GameTreeTest {

    /** Дерево с ходами партии по очереди (чёрные, белые, …) в точках [pts]. Курсор — на последнем ходе. */
    private fun gameTree(vararg pts: Pair<Int, Int>, setup: TreeSetup = TreeSetup(9)): GameTree {
        val t = GameTree(setup)
        var color = setup.initialPlayer
        for ((i, p) in pts.withIndex()) {
            t.appendTrunk(TreeMove(color, p.first, p.second))
            color = TurnRules.next(color, i, setup.handicap, setup.freeHandicap)
        }
        return t
    }

    private fun stones(t: GameTree, node: MoveNode = t.cursor): Int =
        t.boardAt(node).snapshot().count { it != 0 }

    // ------------------------------------------------------------------ основная линия и листание

    @Test
    fun mainLineGrowsAndTheViewFollowsItWhenLive() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4)
        assertEquals(3, t.trunkLength)
        assertTrue(t.live)
        assertSame(t.trunkEnd, t.cursor)
        assertEquals(listOf(null, Stone.BLACK, Stone.WHITE, Stone.BLACK), t.line().map { it.move?.color })
        assertEquals(listOf(0, 1, 2, 3), t.line().map { it.number })
    }

    @Test
    fun browsingHistoryShowsEarlierPositions() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4, 5 to 5)
        t.first()
        assertEquals(0, t.cursor.number)
        assertEquals(0, stones(t))
        assertFalse(t.live)
        assertFalse(t.prev()) // раньше начала не уйти
        assertTrue(t.next())
        assertTrue(t.next())
        assertEquals(2, t.cursor.number)
        assertEquals(2, stones(t))
        t.last()
        assertEquals(4, t.cursor.number)
        assertTrue(t.live)
        assertFalse(t.next()) // дальше последнего хода не уйти
        assertTrue(t.prev())
        assertEquals(3, t.cursor.number)
        assertFalse(t.live)
        t.goLive()
        assertEquals(4, t.cursor.number)
        assertTrue(t.live)
    }

    @Test
    fun newMovesDoNotDragTheViewAwayFromHistory() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4)
        t.prev()
        t.prev() // смотрим ход 1
        t.appendTrunk(TreeMove(Stone.WHITE, 5, 5))
        assertEquals(1, t.cursor.number)
        assertEquals(4, t.trunkLength)
        assertFalse(t.live)

        t.last()
        t.appendTrunk(TreeMove(Stone.BLACK, 1, 1)) // а если стоим на последнем ходе — идём за партией
        assertEquals(5, t.cursor.number)
        assertTrue(t.live)
    }

    @Test
    fun sliderSeekClampsToTheLine() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4)
        t.seek(1)
        assertEquals(1, t.cursor.number)
        t.seek(99)
        assertEquals(3, t.cursor.number)
        t.seek(-4)
        assertEquals(0, t.cursor.number)
        assertEquals(3, t.lineLength())
    }

    @Test
    fun boardAtReplaysPositionsIncludingCaptures() {
        // чёрные (0,1), белые (0,0), чёрные (1,0) — угловой белый камень снят
        val t = gameTree(0 to 1, 0 to 0, 1 to 0)
        val end = t.boardAt(t.trunkEnd)
        assertNull(end.get(0, 0))
        assertEquals(1, end.captures[0])
        assertEquals(2, stones(t, t.trunkEnd))

        t.prev()
        assertEquals(Stone.WHITE, t.boardAt().get(0, 0))
        assertEquals(0, t.boardAt().captures[0])
    }

    @Test
    fun startingPositionStonesAreOnEveryBoard() {
        val setup = TreeSetup(9, black = listOf(3 to 3, 5 to 5), initialPlayer = Stone.WHITE)
        val t = gameTree(4 to 4, setup = setup)
        assertEquals(Stone.WHITE, t.line()[1].move!!.color) // ходят белые
        assertEquals(2, stones(t, t.root))
        assertEquals(3, stones(t, t.trunkEnd))
    }

    // ------------------------------------------------------------------ анализ: варианты

    @Test
    fun analysisStoneStartsABranchWithTheRightColor() {
        val t = gameTree(2 to 2, 6 to 6)
        t.seek(1)
        t.enterAnalysis()
        assertEquals(PlayCheck.OK, t.play(4, 4))
        val v = t.cursor
        assertFalse(v.trunk)
        assertEquals(2, v.number)
        assertEquals(Stone.WHITE, v.move!!.color)
        assertEquals(2, stones(t))
        assertEquals(2, t.trunkLength) // партия не тронута
        assertTrue(t.hasVariations())

        assertEquals(PlayCheck.OK, t.play(4, 5))
        assertEquals(Stone.BLACK, t.cursor.move!!.color)
        assertEquals(3, t.cursor.number)
        assertEquals(3, stones(t))

        t.goLive()
        assertEquals(2, t.cursor.number)
        assertEquals(Stone.WHITE, t.boardAt().get(6, 6))
        assertNull(t.boardAt().get(4, 4)) // вариантных камней на доске партии нет
    }

    @Test
    fun stonesCannotBePlacedOutsideAnalysis() {
        val t = gameTree(2 to 2)
        t.seek(0)
        assertEquals(PlayCheck.OFF_BOARD, t.play(4, 4))
        assertFalse(t.pass())
        assertFalse(t.hasVariations())
        assertEquals(0, t.cursor.number)
    }

    @Test
    fun playingAnExistingContinuationJustMovesThere() {
        val t = gameTree(2 to 2, 6 to 6)
        t.seek(1)
        t.enterAnalysis()
        assertEquals(PlayCheck.OK, t.play(6, 6)) // такой ход в партии уже есть
        assertTrue(t.cursor.trunk)
        assertSame(t.trunkEnd, t.cursor)
        assertFalse(t.hasVariations())
    }

    @Test
    fun theSameVariationMoveIsNotDuplicated() {
        val t = gameTree(2 to 2)
        t.enterAnalysis()
        t.play(4, 4)
        val first = t.cursor
        t.prev()
        assertEquals(PlayCheck.OK, t.play(4, 4))
        assertSame(first, t.cursor)
        assertEquals(1, t.cursor.parent!!.children.size)
    }

    @Test
    fun analysisRespectsOccupiedKoAndSuicide() {
        // ко: белые (1,1) берут камень (2,1), чёрным сразу вернуть нельзя
        val ko = GameTree(
            TreeSetup(
                5,
                black = listOf(1 to 0, 0 to 1, 2 to 1, 1 to 2),
                white = listOf(2 to 0, 3 to 1, 2 to 2),
                initialPlayer = Stone.WHITE,
            ),
        )
        ko.enterAnalysis()
        assertEquals(PlayCheck.OCCUPIED, ko.play(2, 1))
        assertEquals(PlayCheck.OK, ko.play(1, 1))
        assertEquals(1, ko.boardAt().captures[1])
        assertEquals(PlayCheck.KO, ko.play(2, 1))
        assertEquals(1, ko.cursor.number) // ничего не добавилось
        assertEquals(PlayCheck.OK, ko.play(4, 4)) // а ход в другое место — можно
        assertEquals(PlayCheck.OK, ko.play(4, 3))
        assertEquals(PlayCheck.OK, ko.play(2, 1)) // через ход ко уже можно брать обратно

        // самоубийство в углу
        val s = GameTree(TreeSetup(5, black = listOf(1 to 0, 0 to 1), initialPlayer = Stone.WHITE))
        s.enterAnalysis()
        assertEquals(PlayCheck.SUICIDE, s.play(0, 0))
        assertEquals(0, s.cursor.number)
        assertFalse(s.hasVariations())
    }

    @Test
    fun passInAnalysisIsABranchToo() {
        val t = gameTree(2 to 2)
        t.enterAnalysis()
        assertTrue(t.pass())
        val p = t.cursor
        assertTrue(p.move!!.isPass)
        assertEquals(Stone.WHITE, p.move!!.color)
        assertEquals(1, stones(t)) // пас доску не меняет
        assertEquals(Stone.BLACK, t.colorToPlay(p))

        t.prev()
        assertTrue(t.pass()) // второй пас — тот же узел
        assertSame(p, t.cursor)
        assertEquals(1, t.cursor.parent!!.children.size)
    }

    @Test
    fun freeHandicapLetsBlackPlaceAllStonesInAnalysisToo() {
        val t = GameTree(TreeSetup(9, handicap = 3, freeHandicap = true))
        assertEquals(Stone.BLACK, t.colorToPlay(t.root))
        t.enterAnalysis()
        val colors = ArrayList<Stone>()
        for ((x, y) in listOf(0 to 0, 1 to 1, 2 to 2, 3 to 3)) {
            assertEquals(PlayCheck.OK, t.play(x, y))
            colors.add(t.cursor.move!!.color)
        }
        assertEquals(listOf(Stone.BLACK, Stone.BLACK, Stone.BLACK, Stone.WHITE), colors)
        assertEquals(Stone.BLACK, t.colorToPlay(t.cursor))
    }

    @Test
    fun whiteStartsWhenTheSetupSaysSo() {
        val t = GameTree(TreeSetup(9, black = listOf(3 to 3, 5 to 5), initialPlayer = Stone.WHITE))
        assertEquals(Stone.WHITE, t.colorToPlay(t.root))
        t.enterAnalysis()
        t.play(4, 4)
        assertEquals(Stone.WHITE, t.cursor.move!!.color)
        assertEquals(Stone.BLACK, t.colorToPlay(t.cursor))
    }

    // ------------------------------------------------------------------ как переходят «вперёд» и между вариантами

    @Test
    fun nextPrefersTheGameLineOverVariations() {
        val t = gameTree(2 to 2, 6 to 6)
        t.seek(1)
        t.enterAnalysis()
        t.play(4, 4)
        t.prev() // запомнился вариант, но основная линия важнее
        assertTrue(t.next())
        assertSame(t.trunkEnd, t.cursor)
    }

    @Test
    fun insideAVariationNextGoesToTheBranchWeCameBackFrom() {
        val t = gameTree(2 to 2)
        t.enterAnalysis()
        t.play(6, 6) // вариант A
        val a = t.cursor
        t.play(1, 1) // A → B1
        val b1 = t.cursor
        t.prev()
        t.play(2, 1) // A → B2
        val b2 = t.cursor
        t.prev()
        assertSame(a, t.cursor)
        assertTrue(t.next())
        assertSame(b2, t.cursor) // не первый вариант, а тот, из которого вернулись
        assertTrue(t.moveSibling(-1))
        assertSame(b1, t.cursor) // соседний вариант того же хода
        t.prev()
        assertTrue(t.next())
        assertSame(b1, t.cursor) // выбор запомнился
    }

    @Test
    fun siblingVariationsCanBeSwitched() {
        val t = gameTree(2 to 2, 6 to 6)
        t.seek(1)
        t.enterAnalysis()
        t.play(4, 4)
        val a = t.cursor
        t.prev()
        t.play(5, 5)
        val b = t.cursor
        assertEquals(3, t.siblings().size)
        assertSame(t.trunkEnd, t.siblings()[0]) // ход партии — первый
        assertTrue(t.moveSibling(-1))
        assertSame(a, t.cursor)
        assertTrue(t.moveSibling(-1))
        assertSame(t.trunkEnd, t.cursor)
        assertFalse(t.moveSibling(-1))
        assertTrue(t.moveSibling(2))
        assertSame(b, t.cursor)
        assertFalse(t.moveSibling(1))

        t.exitAnalysis()
        assertFalse(t.moveSibling(1)) // вне анализа между вариантами не ходят
    }

    @Test
    fun branchChoicesAppearOnlyInAnalysisWithSeveralContinuations() {
        val t = gameTree(2 to 2, 6 to 6)
        t.seek(1)
        assertTrue(t.branchChoices().isEmpty()) // не анализ
        t.enterAnalysis()
        assertTrue(t.branchChoices().isEmpty()) // продолжение одно
        t.play(4, 4)
        t.prev()
        val choices = t.branchChoices()
        assertEquals(2, choices.size)
        assertTrue(choices[0].trunk)
        assertFalse(choices[1].trunk)
        assertEquals(listOf(2, 2), choices.map { it.number })
        assertNotNull(t.find(choices[1].id))
        t.jumpTo(t.find(choices[1].id)!!)
        assertEquals(Stone.WHITE, t.cursor.move!!.color)
        assertFalse(t.cursor.trunk)
    }

    @Test
    fun lineFollowsTheCurrentVariation() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4)
        t.seek(1)
        t.enterAnalysis()
        t.play(5, 5)
        t.play(5, 6)
        assertEquals(listOf(0, 1, 2, 3), t.line().map { it.number })
        assertEquals(listOf(true, true, false, false), t.line().map { it.trunk })
        assertEquals(3, t.lineLength())

        t.seek(1) // вернулись к ходу 1: дальше линия идёт уже по партии
        assertEquals(listOf(0, 1, 2, 3), t.line().map { it.number })
        assertTrue(t.line().all { it.trunk })
    }

    // ------------------------------------------------------------------ выход из анализа, переходы, удаление

    @Test
    fun leavingAnalysisReturnsToTheGameLineAndKeepsVariations() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4)
        assertTrue(t.live)
        t.enterAnalysis()
        assertFalse(t.live)
        t.exitAnalysis()
        assertTrue(t.live)

        t.seek(1)
        t.enterAnalysis()
        t.play(5, 5)
        t.play(5, 6)
        t.exitAnalysis()
        assertFalse(t.analysis)
        assertEquals(1, t.cursor.number)
        assertTrue(t.cursor.trunk)
        assertTrue(t.hasVariations())

        t.goLive()
        assertEquals(3, t.cursor.number)
        assertTrue(t.live)
    }

    @Test
    fun jumpingToAVariationTurnsAnalysisOn() {
        val t = gameTree(2 to 2, 6 to 6)
        t.seek(1)
        t.enterAnalysis()
        t.play(4, 4)
        val v = t.cursor
        t.exitAnalysis()
        assertFalse(t.analysis)

        t.jumpTo(v)
        assertTrue(t.analysis)
        assertSame(v, t.cursor)
        t.jumpTo(t.trunkEnd)
        assertTrue(t.analysis) // переход на ход партии режим не выключает
        assertSame(t.trunkEnd, t.cursor)
    }

    @Test
    fun onlyVariationsCanBeDeleted() {
        val t = gameTree(2 to 2, 6 to 6)
        t.enterAnalysis()
        assertFalse(t.canDeleteBranch)
        assertFalse(t.deleteBranch()) // ход партии не удалить

        t.seek(1)
        t.play(4, 4)
        val v1 = t.cursor
        t.play(4, 5) // продолжение варианта
        assertTrue(t.canDeleteBranch)
        t.prev() // курсор на v1
        assertTrue(t.deleteBranch()) // уходит вместе с продолжением
        assertEquals(1, t.cursor.number)
        assertTrue(t.cursor.trunk)
        assertFalse(t.hasVariations())
        assertNull(t.find(v1.id))
        assertEquals(2, t.trunkLength)
    }

    @Test
    fun deletingTheRootIsImpossible() {
        val t = gameTree(2 to 2)
        t.enterAnalysis()
        t.first()
        assertFalse(t.canDeleteBranch)
        assertFalse(t.deleteBranch())
    }

    // ------------------------------------------------------------------ ходы сервера и варианты

    @Test
    fun serverMoveThatMatchesAVariationPromotesIt() {
        val t = gameTree(2 to 2)
        t.enterAnalysis()
        t.play(6, 6) // вариант: белые
        val variation = t.cursor
        t.play(6, 7) // его продолжение: чёрные
        val deeper = t.cursor

        t.appendTrunk(TreeMove(Stone.WHITE, 6, 6)) // сервер сыграл ровно это
        assertSame(variation, t.trunkEnd)
        assertTrue(variation.trunk)
        assertEquals(2, t.trunkLength)
        assertEquals(1, variation.parent!!.children.size) // без дубликата
        assertSame(deeper, t.cursor) // курсор в анализе остался где был
        assertFalse(deeper.trunk)

        t.appendTrunk(TreeMove(Stone.BLACK, 6, 7)) // и следующий ход — тоже по варианту
        assertSame(deeper, t.trunkEnd)
        assertTrue(deeper.trunk)
        assertFalse(t.hasVariations())
    }

    @Test
    fun syncReplacesOnlyTheTailAndKeepsVariations() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4, 5 to 5)
        t.seek(2)
        t.enterAnalysis()
        t.play(1, 1) // вариант от хода 2
        val variation = t.cursor
        t.goLive()

        // сервер откатил два хода и сыграл другой
        val moves = listOf(
            TreeMove(Stone.BLACK, 2, 2),
            TreeMove(Stone.WHITE, 6, 6),
            TreeMove(Stone.BLACK, 7, 7),
        )
        t.syncTrunk(moves)
        assertEquals(3, t.trunkLength)
        assertTrue(t.live)
        assertSame(t.trunkEnd, t.cursor)
        assertEquals(Stone.BLACK, t.trunkEnd.move!!.color)
        assertEquals(7, t.trunkEnd.move!!.x)
        assertTrue(variation.parent!!.children.any { it === variation })

        // а теперь пришёл ход, совпадающий с вариантом
        t.syncTrunk(listOf(moves[0], moves[1], TreeMove(Stone.BLACK, 1, 1)))
        assertSame(variation, t.trunkEnd)
        assertTrue(variation.trunk)
        assertEquals(1, variation.parent!!.children.size)
    }

    @Test
    fun syncWithTheSameMovesChangesNothing() {
        val t = gameTree(2 to 2, 6 to 6)
        val v = t.version
        t.syncTrunk(listOf(TreeMove(Stone.BLACK, 2, 2), TreeMove(Stone.WHITE, 6, 6)))
        assertEquals(v, t.version)
        assertEquals(2, t.trunkLength)
    }

    @Test
    fun syncWithMoreMovesAppendsAndFollowsWhenLive() {
        val t = gameTree(2 to 2, 6 to 6)
        t.syncTrunk(
            listOf(
                TreeMove(Stone.BLACK, 2, 2), TreeMove(Stone.WHITE, 6, 6),
                TreeMove(Stone.BLACK, 4, 4), TreeMove(Stone.WHITE, 5, 5),
            ),
        )
        assertEquals(4, t.trunkLength)
        assertTrue(t.live)
    }

    @Test
    fun syncDoesNotLeaveTheCursorOnARemovedMove() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4, 5 to 5)
        t.seek(3)
        t.syncTrunk(listOf(TreeMove(Stone.BLACK, 2, 2), TreeMove(Stone.WHITE, 6, 6)))
        assertEquals(2, t.trunkLength)
        assertEquals(2, t.cursor.number)
        assertTrue(t.cursor.trunk)

        val u = gameTree(2 to 2, 6 to 6, 4 to 4, 5 to 5)
        u.seek(1) // смотрели ход 1 — отмена хвоста его не касается
        u.syncTrunk(listOf(TreeMove(Stone.BLACK, 2, 2), TreeMove(Stone.WHITE, 6, 6)))
        assertEquals(1, u.cursor.number)
        assertFalse(u.live)
    }

    // ------------------------------------------------------------------ список ходов

    @Test
    fun outlineListsVariationsRightAfterTheirBranchPoint() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4, 5 to 5)
        t.seek(2)
        t.enterAnalysis()
        t.play(1, 1) // 3'
        t.play(1, 2) // 4'
        val rows = t.outline()
        assertEquals(listOf(1, 2, 3, 4, 3, 4), rows.map { it.number })
        assertEquals(listOf(0, 0, 1, 1, 0, 0), rows.map { it.indent })
        assertEquals(listOf(true, true, false, false, true, true), rows.map { it.trunk })
        assertEquals(1, rows.count { it.current })
        assertTrue(rows[3].current)
    }

    @Test
    fun outlineOfAPlainGameIsTheMoveList() {
        val t = gameTree(2 to 2, 6 to 6, 4 to 4)
        val rows = t.outline()
        assertEquals(listOf(1, 2, 3), rows.map { it.number })
        assertTrue(rows.all { it.indent == 0 && it.trunk })
        assertTrue(rows.last().current)
        assertEquals(listOf(Stone.BLACK, Stone.WHITE, Stone.BLACK), rows.map { it.move!!.color })
    }

    // ------------------------------------------------------------------ правила очерёдности и подписи точек

    @Test
    fun turnRulesMatchTheSite() {
        assertEquals(Stone.WHITE, TurnRules.next(Stone.BLACK, 0, 0, false))
        assertEquals(Stone.BLACK, TurnRules.next(Stone.WHITE, 5, 0, false))
        assertEquals(Stone.BLACK, TurnRules.next(Stone.WHITE, 0, 4, false)) // фора камнями на доске — обычная очерёдность
        // свободная фора в 4 камня: чёрные ставят четыре подряд
        assertEquals(Stone.BLACK, TurnRules.next(Stone.BLACK, 0, 4, true))
        assertEquals(Stone.BLACK, TurnRules.next(Stone.BLACK, 2, 4, true))
        assertEquals(Stone.WHITE, TurnRules.next(Stone.BLACK, 3, 4, true))
        assertEquals(Stone.BLACK, TurnRules.next(Stone.WHITE, 4, 4, true))
    }

    @Test
    fun pointLabelsSkipTheLetterI() {
        assertEquals("A9", Coord.label(0, 0, 9))
        assertEquals("J1", Coord.label(8, 8, 9))
        assertEquals("T19", Coord.label(18, 0, 19))
        assertEquals("Пас", Coord.label(-1, -1, 19))
    }
}
