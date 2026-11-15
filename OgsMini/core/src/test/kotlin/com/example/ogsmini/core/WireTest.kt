package com.example.ogsmini.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    private val gamedata = """
        ["game/777/gamedata", {
          "game_id": 777, "game_name": "Friendly", "width": 9, "height": 9,
          "initial_player": "black", "phase": "play", "komi": 6.5, "handicap": 0, "rules": "japanese",
          "players": {
            "black": {"id": 1, "username": "alice", "ranking": 25.3},
            "white": {"id": 2, "username": "bob", "rank": 31}
          },
          "moves": [[2,2,1000],[6,6,900],[-1,-1,100]],
          "clock": {"current_player": 2, "last_move": 1700000000000,
                    "black_time": {"thinking_time": 120},
                    "white_time": {"thinking_time": 100, "periods": 5, "period_time": 30}}
        }]
    """.trimIndent()

    private fun loadSession(): GameSession {
        val ev = Wire.parse(gamedata) as Incoming.Event
        val d = Wire.parseGameData(ev.data)!!
        return GameSession(d.gameId).also { it.applyGameData(d) }
    }

    @Test
    fun parsesEventsAndResponses() {
        val ev = Wire.parse(gamedata)
        assertTrue(ev is Incoming.Event)
        assertEquals("game/777/gamedata", (ev as Incoming.Event).name)
        val resp = Wire.parse("""[1, {"results": []}]""")
        assertTrue(resp is Incoming.Response)
        assertEquals(1L, (resp as Incoming.Response).id)
        assertNull(Wire.parse("not json"))
        assertNull(Wire.parse("{}"))
    }

    @Test
    fun gameDataBuildsSession() {
        val s = loadSession()
        val d = s.data!!
        assertEquals("Friendly", d.name)
        assertEquals("alice", d.black!!.name)
        assertEquals("bob", d.white!!.name)
        assertEquals(3, s.moveCount)
        assertEquals(Stone.BLACK, s.board.get(2, 2))
        assertEquals(Stone.WHITE, s.board.get(6, 6))
        assertEquals(Stone.WHITE, s.toPlay) // после паса чёрных ходят белые, часы тоже говорят: игрок 2
        assertEquals(Stone.BLACK, s.myColor(1))
        assertEquals(Stone.WHITE, s.myColor(2))
        assertNull(s.myColor(99))
        assertTrue(s.isMyTurn(2))
        assertFalse(s.isMyTurn(1))
    }

    @Test
    fun moveEventsAreAppliedIgnoredAndDesyncDetected() {
        val s = loadSession()
        val ev = Wire.parseMoveEvent(
            (Wire.parse("""["game/777/move", {"game_id":777,"move_number":4,"move":[4,4,200]}]""") as Incoming.Event).data
        )!!
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(ev.moveNumber, ev.x, ev.y))
        assertEquals(Stone.WHITE, s.board.get(4, 4))
        assertEquals(Stone.BLACK, s.toPlay)
        assertEquals(4, s.moveCount)
        // дубликат
        assertEquals(GameSession.MoveResult.IGNORED, s.applyMove(4, 4, 4))
        // пропущенный ход
        assertEquals(GameSession.MoveResult.DESYNC, s.applyMove(9, 1, 1))
        // ход строкой и пас
        val str = Wire.parseMoveEvent(
            (Wire.parse("""["game/777/move", {"move_number":5,"move":"dd"}]""") as Incoming.Event).data
        )!!
        assertEquals(3, str.x)
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(str.moveNumber, str.x, str.y))
        val pass = Wire.parseMoveEvent(
            (Wire.parse("""["game/777/move", {"move_number":6,"move":[-1,-1,5]}]""") as Incoming.Event).data
        )!!
        assertEquals(-1, pass.x)
        assertEquals(GameSession.MoveResult.APPLIED, s.applyMove(pass.moveNumber, pass.x, pass.y))
        assertEquals(6, s.moveCount)
    }

    @Test
    fun clockAndRemaining() {
        val s = loadSession()
        val last = 1_700_000_000_000L
        assertEquals(90.0, s.remainingSeconds(Stone.WHITE, last + 10_000)!!, 0.001)
        assertEquals(120.0, s.remainingSeconds(Stone.BLACK, last + 10_000)!!, 0.001)
        val c = Wire.parseClock(
            (Wire.parse("""["game/777/clock", {"current_player":1,"last_move":$last,"now":${last + 500},
               "black_time":{"thinking_time":119},"white_time":{"thinking_time":99}}]""") as Incoming.Event).data
        )!!
        s.applyClock(c, last + 400)
        // очередь считается по ходам, а не по часам: после трёх ходов по-прежнему ходят белые
        assertEquals(Stone.WHITE, s.toPlay)
        assertEquals(100L, s.clockOffsetMs)
        // время белых по-прежнему идёт: 99 с минус (500 мс на устройстве + сдвиг часов 100 мс)
        assertEquals(98.4, s.remainingSeconds(Stone.WHITE, last + 500)!!, 0.001)
    }

    @Test
    fun stoneRemoval() {
        val s = loadSession()
        s.applyPhase("stone removal")
        val ev = Wire.parseRemovedEvent(
            (Wire.parse("""["game/777/removed_stones", {"removed":true,"stones":"cc","all_removed":"cc"}]""") as Incoming.Event).data
        )!!
        s.applyRemoved(ev)
        assertTrue(s.isRemoved(2, 2))
        assertEquals("cc", s.removedRaw)
        s.applyRemoved(RemovedEvent(false, "cc", null))
        assertFalse(s.isRemoved(2, 2))
        s.applyRemoved(RemovedEvent(true, "ddcc", null))
        assertEquals("ccdd", s.removedRaw) // собирается в порядке строк доски
        s.applyRemoved(RemovedEvent(false, "ccdd", null))
        assertEquals(listOf(s.board.index(2, 2)), s.removalGroup(2, 2))
        assertEquals(emptyList<Int>(), s.removalGroup(0, 0))
        s.applyPhase("finished")
        assertTrue(s.removed.isEmpty())
    }

    @Test
    fun overviewConfigTokensAndRanks() {
        val root = Wire.parseJson(
            """{"active_games":[
                {"id":55,"name":"A","black":{"id":1,"username":"alice"},"white":{"id":2,"username":"bob"},
                 "width":19,"height":19,"json":{"clock":{"current_player":1}}},
                {"id":56,"json":{"players":{"black":{"id":3,"username":"carl"},"white":{"id":1,"username":"alice"}},
                 "width":9,"height":9,"clock":{"current_player":3}}}]}"""
        )!!
        val list = Wire.parseOverview(root, 1)
        assertEquals(2, list.size)
        assertEquals(true, list[0].myTurn)
        assertEquals("carl", list[1].black)
        assertEquals(9, list[1].width)
        assertEquals(false, list[1].myTurn)

        val cfg = Wire.parseConfig(Wire.parseJson("""{"user":{"id":5,"username":"zed"},"user_jwt":"abc.def"}""")!!)
        assertNotNull(cfg)
        assertEquals(5L, cfg!!.userId)
        assertEquals("abc.def", cfg.jwt)

        val t = Wire.parseTokens(Wire.parseJson("""{"access_token":"A","refresh_token":"R","expires_in":2592000}""")!!)!!
        assertEquals("A", t.access)
        assertEquals(2592000L, t.expiresInSec)

        assertEquals("5k", Wire.rankLabel(25.3))
        assertEquals("2d", Wire.rankLabel(31.0))
        assertEquals("", Wire.rankLabel(null))
    }

    @Test
    fun outgoingFrames() {
        assertEquals("""["game/move",{"game_id":7,"move":"dd"}]""", Wire.move(7, 3, 3))
        assertEquals("""["game/move",{"game_id":7,"move":".."}]""", Wire.move(7, -1, -1))
        assertEquals("""["game/connect",{"game_id":7,"chat":false}]""", Wire.gameConnect(7))
        assertEquals("""["game/resign",{"game_id":7}]""", Wire.resign(7))
        assertEquals("""["game/removed_stones/set",{"game_id":7,"removed":true,"stones":"aabb"}]""", Wire.removedSet(7, true, "aabb"))
        assertTrue(Wire.authenticate("J", "dev", "0.1").startsWith("""["authenticate",{"jwt":"J","""))
    }
}
