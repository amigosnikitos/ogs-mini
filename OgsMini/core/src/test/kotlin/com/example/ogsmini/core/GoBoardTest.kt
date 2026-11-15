package com.example.ogsmini.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBoardTest {

    private fun board(w: Int, vararg rows: String): GoBoard {
        val b = GoBoard(w, rows.size)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                when (ch) {
                    'X' -> b.setStone(x, y, Stone.BLACK)
                    'O' -> b.setStone(x, y, Stone.WHITE)
                }
            }
        }
        return b
    }

    @Test
    fun cornerStoneIsCaptured() {
        val b = GoBoard(9)
        b.play(0, 0, Stone.WHITE)
        assertEquals(0, b.play(1, 0, Stone.BLACK))
        assertEquals(1, b.play(0, 1, Stone.BLACK))
        assertNull(b.get(0, 0))
        assertEquals(1, b.captures[0])
    }

    @Test
    fun groupWithTwoStonesIsCapturedTogether() {
        // белая группа (1,0)-(1,1): после чёрного (2,0) её единственное дыхание — (2,1)
        val b = board(5, "XOX..", "XO...", ".X...")
        assertEquals(2, b.play(2, 1, Stone.BLACK))
        assertNull(b.get(1, 0))
        assertNull(b.get(1, 1))
        assertEquals(2, b.captures[0])
    }

    @Test
    fun libertiesOfGroup() {
        val b = board(5, "XX...", ".....")
        val g = b.group(0, 0)
        assertEquals(2, g.size)
        assertEquals(3, b.liberties(g)) // (2,0), (0,1), (1,1)
    }

    @Test
    fun koIsDetectedAndClearedByAnotherMove() {
        val b = board(
            5,
            ".XO..",
            "XO.O.",
            ".XO..",
            ".....",
            ".....",
        )
        assertEquals(1, b.play(2, 1, Stone.BLACK))
        assertNull(b.get(1, 1))
        assertEquals(PlayCheck.KO, b.check(1, 1, Stone.WHITE))
        b.play(4, 4, Stone.WHITE)
        assertEquals(PlayCheck.OK, b.check(1, 1, Stone.WHITE))
    }

    @Test
    fun suicideIsDetected() {
        val b = board(3, ".X.", "X.X", ".X.")
        assertEquals(PlayCheck.SUICIDE, b.check(1, 1, Stone.WHITE))
        assertEquals(PlayCheck.OK, b.check(1, 1, Stone.BLACK))
    }

    @Test
    fun capturingMoveWithoutLibertiesIsNotSuicide() {
        // белый ставит в (0,1): чёрный (0,0) теряет последнее дыхание, камень белого остаётся на доске
        val b = board(3, "XO.", "...", "...")
        assertEquals(PlayCheck.OK, b.check(0, 1, Stone.WHITE))
        assertEquals(1, b.play(0, 1, Stone.WHITE))
        assertNull(b.get(0, 0))
        assertEquals(Stone.WHITE, b.get(0, 1))
    }

    @Test
    fun occupiedAndOffBoard() {
        val b = GoBoard(9)
        b.play(3, 3, Stone.BLACK)
        assertEquals(PlayCheck.OCCUPIED, b.check(3, 3, Stone.WHITE))
        assertEquals(PlayCheck.OFF_BOARD, b.check(9, 0, Stone.WHITE))
    }

    @Test
    fun rectangularBoardAndPass() {
        val b = GoBoard(5, 3)
        b.play(4, 2, Stone.BLACK)
        assertEquals(Stone.BLACK, b.get(4, 2))
        b.play(-1, -1, Stone.WHITE) // пас
        assertEquals(-1, b.lastMove)
    }

    @Test
    fun coordCodec() {
        assertEquals("dd", Coord.encode(3, 3))
        assertEquals("..", Coord.encode(-1, -1))
        assertEquals(Pair(9, 9), Coord.decode("jj"))
        assertEquals(Pair(-1, -1), Coord.decode(".."))
        assertNull(Coord.decode("!!"))
        assertEquals(3, Coord.decodeAll("aabbcc").size)
        assertEquals(Pair(18, 0), Coord.decode("sa"))
    }

    @Test
    fun pkceMatchesRfc7636Vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor(verifier))
        assertEquals(64, Pkce.randomToken().length)
        val url = Pkce.authorizeUrl("cid", "gobo://callback", "chal", "st")
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("redirect_uri=gobo%3A%2F%2Fcallback"))
        assertFalse(url.contains(" "))
    }
}
