package com.example.ogsmini.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чей сейчас ход и каким цветом ложится новый камень.
 * Правило то же, что в движке официального клиента OGS (goban): цвет считается только по списку ходов,
 * начальному игроку и форе. Сообщения о часах на это влиять не должны.
 */
class GameSessionTest {

    /** Игрок 1 — чёрные (alice), игрок 2 — белые (bob). */
    private fun gamedata(
        moves: String = "",
        currentPlayer: Int = 1,
        initialPlayer: String = "black",
        handicap: Int = 0,
        free: Boolean = false,
        initialBlack: String = "",
    ) = """
        {"game_id": 5, "game_name": "t", "width": 9, "height": 9, "phase": "play",
         "initial_player": "$initialPlayer", "handicap": $handicap, "free_handicap_placement": $free,
         "initial_state": {"black": "$initialBlack", "white": ""},
         "players": {"black": {"id": 1, "username": "alice"}, "white": {"id": 2, "username": "bob"}},
         "moves": [$moves],
         "clock": {"current_player": $currentPlayer, "last_move": 1700000000000}}
    """.trimIndent()

    private fun session(json: String): GameSession {
        val d = Wire.parseGameData(Wire.parseJson(json)!!)!!
        return GameSession(d.gameId).also { it.applyGameData(d) }
    }

    private fun clock(currentPlayer: Int): ClockInfo =
        Wire.parseClock(Wire.parseJson("""{"current_player": $currentPlayer, "last_move": 1700000000500}"""))!!

    @Test
    fun clockArrivingBeforeTheMoveDoesNotChangeTheColor() {
        // Ошибка из жизни: чёрный ходит, сервер присылает часы (уже с новым current_player) раньше самого хода —
        // и камень ложился белым, а очередь оставалась у чёрных.
        val s = session(gamedata())
        assertEquals(Stone.BLACK, s.toPlay)
        assertTrue(s.isMyTurn(1))

        s.applyClock(clock(2), 0)
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(1, 2, 2))

        assertEquals(Stone.BLACK, s.board.get(2, 2))
        assertEquals(Stone.WHITE, s.toPlay)
        assertFalse(s.isMyTurn(1))
        assertTrue(s.isMyTurn(2))
    }

    @Test
    fun clockArrivingAfterTheMoveKeepsTheTurn() {
        val s = session(gamedata())
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(1, 2, 2))
        s.applyClock(clock(2), 0)
        assertEquals(Stone.BLACK, s.board.get(2, 2))
        assertEquals(Stone.WHITE, s.toPlay)

        // и ответный ход белых, снова с «часами вперёд»
        s.applyClock(clock(1), 0)
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(2, 6, 6))
        assertEquals(Stone.WHITE, s.board.get(6, 6))
        assertEquals(Stone.BLACK, s.toPlay)
    }

    @Test
    fun staleOrUnexpectedClockNeverOverridesTheMoveList() {
        val s = session(gamedata(moves = "[2,2,1],[6,6,1],[3,3,1]", currentPlayer = 2))
        assertEquals(Stone.WHITE, s.toPlay) // три хода сыграно — ходят белые
        s.applyClock(clock(1), 0)
        assertEquals(Stone.WHITE, s.toPlay)
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(4, 5, 5))
        assertEquals(Stone.WHITE, s.board.get(5, 5))
    }

    @Test
    fun snapshotColorsFollowTheMoveListEvenIfTheClockDisagrees() {
        val s = session(gamedata(moves = "[2,2,1]", currentPlayer = 1))
        assertEquals(Stone.WHITE, s.toPlay)
        assertEquals(Stone.BLACK, s.board.get(2, 2))
    }

    @Test
    fun passesKeepTheColorsAlternating() {
        val s = session(gamedata(moves = "[0,0,1],[-1,-1,1],[1,1,1]", currentPlayer = 2))
        assertEquals(Stone.BLACK, s.board.get(0, 0))
        assertEquals(Stone.BLACK, s.board.get(1, 1)) // пас белых, потом снова чёрные
        assertEquals(Stone.WHITE, s.toPlay)
    }

    @Test
    fun duplicateMoveEventIsIgnoredAndDoesNotFlipTheTurn() {
        val s = session(gamedata())
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(1, 2, 2))
        assertEquals(GameSession.MoveResult.IGNORED, s.applyMove(1, 2, 2))
        assertEquals(Stone.WHITE, s.toPlay)
        assertEquals(1, s.moveCount)
    }

    @Test
    fun freeHandicapBlackPlacesAllStonesThenWhiteMoves() {
        val s = session(gamedata(handicap = 3, free = true))
        assertEquals(Stone.BLACK, s.toPlay)
        s.applyMove(1, 0, 0)
        assertEquals(Stone.BLACK, s.toPlay) // осталось ещё два камня форы
        s.applyMove(2, 1, 1)
        assertEquals(Stone.BLACK, s.toPlay)
        s.applyMove(3, 2, 2)
        assertEquals(Stone.WHITE, s.toPlay) // фора расставлена
        s.applyMove(4, 4, 4)
        assertEquals(Stone.WHITE, s.board.get(4, 4))
        assertEquals(Stone.BLACK, s.toPlay)
        assertEquals(Stone.BLACK, s.board.get(0, 0))
        assertEquals(Stone.BLACK, s.board.get(1, 1))
        assertEquals(Stone.BLACK, s.board.get(2, 2))
    }

    @Test
    fun freeHandicapIsReplayedCorrectlyFromAGameSnapshot() {
        val s = session(gamedata(moves = "[0,0,1],[1,1,1],[2,2,1],[4,4,1]", currentPlayer = 1, handicap = 3, free = true))
        assertEquals(Stone.BLACK, s.board.get(2, 2))
        assertEquals(Stone.WHITE, s.board.get(4, 4))
        assertEquals(Stone.BLACK, s.toPlay)

        val placing = session(gamedata(moves = "[0,0,1],[1,1,1]", handicap = 3, free = true))
        assertEquals(Stone.BLACK, placing.toPlay) // ещё не все камни форы стоят
    }

    @Test
    fun oneStoneOrTwoStoneFreeHandicapEdgeCases() {
        val one = session(gamedata(handicap = 1, free = true))
        one.applyMove(1, 0, 0)
        assertEquals(Stone.WHITE, one.toPlay) // «фора в 1 камень» — обычная очерёдность

        val two = session(gamedata(handicap = 2, free = true))
        two.applyMove(1, 0, 0)
        assertEquals(Stone.BLACK, two.toPlay)
        two.applyMove(2, 1, 1)
        assertEquals(Stone.WHITE, two.toPlay)
    }

    @Test
    fun fixedHandicapWhiteMovesFirst() {
        val s = session(gamedata(currentPlayer = 2, initialPlayer = "white", handicap = 2, initialBlack = "ddgg"))
        assertEquals(Stone.BLACK, s.board.get(3, 3))
        assertEquals(Stone.BLACK, s.board.get(6, 6))
        assertEquals(Stone.WHITE, s.toPlay)
        assertTrue(s.isMyTurn(2))

        s.applyClock(clock(1), 0)
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(1, 4, 4))
        assertEquals(Stone.WHITE, s.board.get(4, 4))
        assertEquals(Stone.BLACK, s.toPlay)
    }
}
