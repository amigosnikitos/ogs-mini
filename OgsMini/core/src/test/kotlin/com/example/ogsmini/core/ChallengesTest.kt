package com.example.ogsmini.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengesTest {

    private fun json(text: String) = Wire.parseJson(text)!!

    /** Сообщение о вызове в том виде, в каком его присылает сервер; [extra] дописывается в конец. */
    private fun challengeJson(
        id: Long,
        user: Long = 5,
        name: String = "alice",
        minRank: Int = 5,
        maxRank: Int = 36,
        ranked: Boolean = true,
        handicap: String = "0",
        width: Int = 19,
        rengo: Boolean = false,
        invite: Boolean = false,
        time: String = """"time_control": "byoyomi",
            "time_control_parameters": {"system":"byoyomi","speed":"live","main_time":600,"period_time":30,"periods":5,"pause_on_weekends":false}""",
    ): String = """
        {"challenge_id": $id, "user_id": $user, "username": "$name", "ranking": 24.5, "professional": false,
         "min_rank": $minRank, "max_rank": $maxRank, "game_id": ${id + 9000}, "name": "game $id",
         "ranked": $ranked, "handicap": $handicap, "komi": null, "komi_auto": "automatic", "rules": "japanese",
         "width": $width, "height": $width, "challenger_color": "automatic", "disable_analysis": false,
         $time, "time_per_move": 35, "rengo": $rengo, "invite_only": $invite}
    """.trimIndent()

    private fun graphOf(vararg messages: String): SeekGraph {
        val g = SeekGraph()
        g.apply(Seek.parse(json("[" + messages.joinToString(",") + "]")))
        return g
    }

    // ------------------------------------------------------------ время

    @Test
    fun shortDurationFormats() {
        assertEquals("0с", shortDuration(0))
        assertEquals("45с", shortDuration(45))
        assertEquals("1м 30с", shortDuration(90))
        assertEquals("1ч", shortDuration(3600))
        assertEquals("1ч 30м", shortDuration(5400))
        assertEquals("3д", shortDuration(3 * 86400))
        assertEquals("1д 12ч", shortDuration(129600))
        assertEquals("1нед 1д", shortDuration(8 * 86400))
    }

    @Test
    fun averageMoveTimeAndSpeedMatchTheSite() {
        val live = TimePresets.defaults("live", "byoyomi") // 10 мин + 5 × 30 с
        assertEquals(35, live.averageMoveSeconds(19, 19)) // 600/126.5 + 30
        assertEquals("live", live.classify(19, 19))
        assertEquals(51, live.averageMoveSeconds(9, 9)) // 600/28.5 + 30

        // 5 мин + 7 с на сайте называется рапидом, но по среднему времени на ход это «blitz»
        assertEquals(9, TimePresets.defaults("rapid", "fischer").averageMoveSeconds(19, 19))
        assertEquals("blitz", TimePresets.defaults("rapid", "fischer").classify(19, 19))

        assertEquals("blitz", TimePresets.defaults("blitz", "byoyomi").classify(19, 19))
        assertEquals("live", TimePresets.defaults("live", "simple").classify(19, 19))
        assertEquals("blitz", TimePresets.defaults("live", "absolute").classify(19, 19)) // 900 / 126.5 ≈ 7
        assertEquals("correspondence", TimePresets.defaults("correspondence", "fischer").classify(19, 19))
        assertEquals("correspondence", TimePresets.defaults("correspondence", "simple").classify(9, 9))
        assertEquals("correspondence", TimeControl("none", "correspondence").classify(19, 19))
    }

    @Test
    fun shortTextsLookLikeTheSite() {
        assertEquals("10м+5×30с", TimePresets.defaults("live", "byoyomi").short())
        assertEquals("5м+7с до 1ч", TimePresets.defaults("rapid", "fischer").short())
        assertEquals("10м+3м/10", TimePresets.defaults("live", "canadian").short())
        assertEquals("15с/ход", TimePresets.defaults("live", "simple").short())
        assertEquals("15м", TimePresets.defaults("live", "absolute").short())
        assertEquals("без времени", TimeControl("none", "correspondence").short())
    }

    @Test
    fun presetsAreConsistent() {
        for (speed in TimePresets.speeds) {
            for (system in TimePresets.systems) {
                val d = TimePresets.defaults(speed, system)
                assertEquals("$speed/$system", TimePresets.keys(system).toSet(), d.values.keys)
                assertEquals(speed == "correspondence", d.pauseOnWeekends)
                for (key in TimePresets.keys(system)) {
                    val options = TimePresets.options(speed, system, key)
                    val range = TimePresets.countRange(speed, key)
                    if (range != null) {
                        assertTrue("$speed/$system/$key is a counter", options.isEmpty())
                        assertTrue("$speed/$system/$key default in range", d.value(key) in range)
                    } else {
                        assertTrue("$speed/$system/$key has options", options.isNotEmpty())
                        assertTrue("$speed/$system/$key default is offered", d.value(key) in options)
                        assertEquals("$speed/$system/$key sorted", options.sorted().distinct(), options)
                    }
                }
            }
        }
        // на сайте значение по умолчанию для блица «простое» лежит вне списка 3–4 с; мы его добавляем
        assertEquals(listOf(3, 4, 5), TimePresets.options("blitz", "simple", "per_move"))
        assertEquals(0, TimePresets.options("live", "byoyomi", "main_time").first())
    }

    // ------------------------------------------------------------ список вызовов

    @Test
    fun parsesSeekGraphMessages() {
        val data = json(
            "[" + challengeJson(101, ranked = true, handicap = "null") + "," +
                """{"challenge_id": 102, "delete": true},""" +
                """{"challenge_id": 103, "game_started": true, "game_id": 9103, "creator": 5}]"""
        )
        val msgs = Seek.parse(data)
        assertEquals(3, msgs.size)

        val c = (msgs[0] as SeekMessage.Add).challenge
        assertEquals(101L, c.id)
        assertEquals(9101L, c.gameId)
        assertEquals("alice", c.username)
        assertEquals(24.5, c.rank, 0.0)
        assertEquals(5.0, c.minRank, 0.0)
        assertEquals(36.0, c.maxRank, 0.0)
        assertNull(c.handicap)
        assertNull(c.komi)
        assertEquals("byoyomi", c.time.system)
        assertEquals("live", c.time.speed)
        assertEquals(600, c.time.value("main_time"))
        assertEquals(5, c.time.value("periods"))
        assertTrue(c.live)
        assertEquals("19×19", ChallengeText.size(c))

        assertEquals(SeekMessage.Remove(102), msgs[1])
        assertEquals(SeekMessage.Started(103, 9103), msgs[2])
    }

    @Test
    fun correspondenceChallengeIsNotLive() {
        val c = (Seek.parse(
            json(
                "[" + challengeJson(
                    1,
                    time = """"time_control": "fischer", "time_control_parameters":
                        {"system":"fischer","speed":"correspondence","initial_time":259200,"time_increment":86400,"max_time":604800,"pause_on_weekends":true}""",
                ) + "]"
            )
        )[0] as SeekMessage.Add).challenge
        assertFalse(c.live)
        assertTrue(c.time.pauseOnWeekends)
        assertEquals("3д+1д до 1нед", c.time.short())
    }

    @Test
    fun customKomiIsKept() {
        val raw = challengeJson(1).replace(""""komi": null, "komi_auto": "automatic"""", """"komi": 0.5, "komi_auto": "custom"""")
        val c = (Seek.parse(json("[$raw]"))[0] as SeekMessage.Add).challenge
        assertEquals(0.5, c.komi!!, 0.0)
        assertEquals("0.5", ChallengeText.komi(c.komi))
        assertEquals("7", ChallengeText.komi(7.0))
        assertEquals("авто", ChallengeText.komi(null))
    }

    @Test
    fun ignoresBrokenMessages() {
        val msgs = Seek.parse(json("""[{"nothing": 1}, 5, {"challenge_id": 4}, {"challenge_id": 5, "user_id": 1}]"""))
        assertTrue(msgs.isEmpty())
        assertTrue(Seek.parse(json("{}")).isEmpty())
    }

    @Test
    fun rowsShowEligibilityAndOrder() {
        val g = graphOf(
            challengeJson(1, user = 5, name = "zed"),
            challengeJson(2, user = 7, name = "alice", minRank = 36), // слишком сильный соперник нужен
            challengeJson(3, user = 1, name = "me"),
            challengeJson(4, user = 8, name = "bob", rengo = true),
            challengeJson(5, user = 9, name = "carl", maxRank = 10), // для слабых
            challengeJson(6, user = 10, name = "dan", invite = true),
            challengeJson(7, user = 11, name = "adam", ranked = false),
        )
        assertEquals(1, g.rengoCount())

        val rows = g.rows(myId = 1, myRank = 20)
        assertEquals(listOf(3L, 7L, 1L, 2L, 5L, 6L), rows.map { it.c.id })

        assertTrue(rows[0].mine)
        assertFalse(rows[0].eligible)
        assertTrue(rows[1].eligible)
        assertTrue(rows[2].eligible)
        assertEquals("мин. ранг: 7d", rows[3].reason)
        assertEquals("макс. ранг: 20k", rows[4].reason)
        assertEquals("только по приглашению", rows[5].reason)
    }

    @Test
    fun removeAndStartedDropChallenges() {
        val g = graphOf(challengeJson(1), challengeJson(2), challengeJson(3))
        g.apply(Seek.parse(json("""[{"challenge_id": 1, "delete": true}, {"challenge_id": 2, "game_started": true, "game_id": 5}]""")))
        assertEquals(listOf(3L), g.rows(0, 20).map { it.c.id })
        assertNull(g[1])
        assertNotNull(g[3])
        g.clear()
        assertTrue(g.rows(0, 20).isEmpty())
    }

    @Test
    fun filterMatchesLikeTheSite() {
        val g = graphOf(
            challengeJson(1, user = 5, width = 19, ranked = true),
            challengeJson(2, user = 6, width = 13, ranked = false, handicap = "2"),
            challengeJson(3, user = 7, width = 9, ranked = true, minRank = 36), // недоступный
            challengeJson(4, user = 8, width = 7, ranked = false),
            challengeJson(5, user = 1, width = 9, ranked = true), // свой
        )
        val rows = g.rows(myId = 1, myRank = 20)
        fun ids(f: ChallengeFilter) = rows.filter { f.matches(it) }.map { it.c.id }.sorted()

        assertEquals(listOf(1L, 2L, 4L, 5L), ids(ChallengeFilter()))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(ChallengeFilter(showIneligible = true)))
        assertEquals(listOf(1L, 5L), ids(ChallengeFilter(showUnranked = false)))
        assertEquals(listOf(2L, 4L, 5L), ids(ChallengeFilter(showRanked = false)))
        assertEquals(listOf(1L, 2L, 5L), ids(ChallengeFilter(showOther = false)))
        assertEquals(listOf(1L, 4L, 5L), ids(ChallengeFilter(show13 = false)))
        assertEquals(listOf(1L, 4L, 5L), ids(ChallengeFilter(showHandicap = false)))
        // свои вызовы видны всегда
        assertEquals(listOf(5L), ids(ChallengeFilter(show19 = false, show13 = false, show9 = false, showOther = false)))
    }

    @Test
    fun filterBitsRoundTrip() {
        val f = ChallengeFilter(showIneligible = true, show13 = false, showHandicap = false)
        assertEquals(f, ChallengeFilter.fromBits(f.toBits()))
        assertEquals(ChallengeFilter(), ChallengeFilter.fromBits(ChallengeFilter().toBits()))
    }

    // ------------------------------------------------------------ создание вызова

    private fun JsonObject.game() = this["game"]!!.jsonObject

    @Test
    fun defaultRequestBodyMatchesTheSite() {
        val body = NewChallenge().requestBody()
        assertEquals(-1000, body["min_ranking"]!!.jsonPrimitive.int)
        assertEquals(1000, body["max_ranking"]!!.jsonPrimitive.int)
        assertEquals("automatic", body["challenger_color"]!!.jsonPrimitive.content)
        assertFalse(body["invite_only"]!!.jsonPrimitive.boolean)

        val g = body.game()
        assertEquals("Friendly Match", g["name"]!!.jsonPrimitive.content)
        assertEquals("japanese", g["rules"]!!.jsonPrimitive.content)
        assertTrue(g["ranked"]!!.jsonPrimitive.boolean)
        assertEquals(19, g["width"]!!.jsonPrimitive.int)
        assertEquals(19, g["height"]!!.jsonPrimitive.int)
        assertEquals(-1, g["handicap"]!!.jsonPrimitive.int)
        assertEquals("automatic", g["komi_auto"]!!.jsonPrimitive.content)
        assertFalse("komi" in g)
        assertFalse(g["disable_analysis"]!!.jsonPrimitive.boolean)
        assertTrue(g["initial_state"] is kotlinx.serialization.json.JsonNull)
        assertFalse(g["private"]!!.jsonPrimitive.boolean)
        assertFalse(g["rengo"]!!.jsonPrimitive.boolean)
        assertEquals("byoyomi", g["time_control"]!!.jsonPrimitive.content)

        val t = g["time_control_parameters"]!!.jsonObject
        assertEquals("byoyomi", t["system"]!!.jsonPrimitive.content)
        assertEquals("live", t["speed"]!!.jsonPrimitive.content)
        assertEquals(600, t["main_time"]!!.jsonPrimitive.int)
        assertEquals(30, t["period_time"]!!.jsonPrimitive.int)
        assertEquals(5, t["periods"]!!.jsonPrimitive.int)
        assertEquals("byoyomi", t["time_control"]!!.jsonPrimitive.content)
        assertFalse(t["pause_on_weekends"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun requestBodyHonoursOptions() {
        val f = NewChallenge(
            name = "  Мой вызов ",
            size = 9,
            rules = "chinese",
            ranked = false,
            handicap = 2,
            customKomi = 0.5,
            color = "white",
            disableAnalysis = true,
            time = TimePresets.defaults("correspondence", "fischer"),
            restrictRank = true,
            minRank = 12,
            maxRank = 30,
        )
        val body = f.requestBody()
        assertEquals(12, body["min_ranking"]!!.jsonPrimitive.int)
        assertEquals(30, body["max_ranking"]!!.jsonPrimitive.int)
        assertEquals("white", body["challenger_color"]!!.jsonPrimitive.content)
        val g = body.game()
        assertEquals("Мой вызов", g["name"]!!.jsonPrimitive.content)
        assertEquals("chinese", g["rules"]!!.jsonPrimitive.content)
        assertFalse(g["ranked"]!!.jsonPrimitive.boolean)
        assertEquals(9, g["width"]!!.jsonPrimitive.int)
        assertEquals(2, g["handicap"]!!.jsonPrimitive.int)
        assertEquals("custom", g["komi_auto"]!!.jsonPrimitive.content)
        assertEquals(0.5, g["komi"]!!.jsonPrimitive.double, 0.0)
        assertTrue(g["disable_analysis"]!!.jsonPrimitive.boolean)
        assertTrue(g["pause_on_weekends"]!!.jsonPrimitive.boolean)
        val t = g["time_control_parameters"]!!.jsonObject
        assertEquals("correspondence", t["speed"]!!.jsonPrimitive.content)
        assertEquals(3 * 86400, t["initial_time"]!!.jsonPrimitive.int)
        assertTrue(t["pause_on_weekends"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun normalizationKeepsCombinationsValid() {
        // рейтинговая партия: фора не больше 9, коми автоматическое
        val ranked = NewChallenge(ranked = true, handicap = 12, customKomi = 0.5).normalized()
        assertEquals(9, ranked.handicap)
        assertNull(ranked.customKomi)
        assertFalse("komi" in ranked.requestBody().game())

        // автоматическая фора — автоматическое коми
        assertNull(NewChallenge(ranked = false, handicap = -1, customKomi = 3.5).normalized().customKomi)
        // дружеская с явной форой — своё коми остаётся
        assertEquals(3.5, NewChallenge(ranked = false, handicap = 3, customKomi = 3.5).normalized().customKomi!!, 0.0)

        // Фишер: максимум времени не меньше начального
        val fischer = NewChallenge(time = TimePresets.defaults("live", "fischer").with("initial_time", 7200)).normalized()
        assertEquals(7200, fischer.time.value("max_time"))
    }

    @Test
    fun problemsAreReported() {
        assertNull(NewChallenge().problem())
        assertEquals("Минимальный ранг выше максимального", NewChallenge(restrictRank = true, minRank = 30, maxRank = 20).problem())
        assertEquals("Размер доски: 9, 13 или 19", NewChallenge(size = 5).problem())
        assertNotNull(NewChallenge(time = TimeControl("byoyomi", "live", mapOf("main_time" to 60))).problem())
    }

    @Test
    fun liveDependsOnTimeControl() {
        assertTrue(NewChallenge().live)
        assertFalse(NewChallenge(time = TimePresets.defaults("correspondence", "byoyomi")).live)
        assertTrue(NewChallenge(size = 9, time = TimePresets.defaults("blitz", "fischer")).live)
    }

    @Test
    fun summaryReadsWell() {
        assertEquals("19×19 · 10м+5×30с · рейтинговая · фора авто", NewChallenge().summary())
        assertEquals(
            "9×9 · 15с/ход · дружеская · без форы",
            NewChallenge(size = 9, ranked = false, handicap = 0, time = TimePresets.defaults("live", "simple")).summary(),
        )
    }

    @Test
    fun formSurvivesEncodeDecode() {
        val f = NewChallenge(
            name = "Привет",
            size = 13,
            rules = "aga",
            ranked = false,
            handicap = 3,
            customKomi = 0.5,
            color = "black",
            disableAnalysis = true,
            time = TimePresets.defaults("correspondence", "fischer").with("time_increment", 172800),
            restrictRank = true,
            minRank = 10,
            maxRank = 33,
        )
        assertEquals(f.normalized(), NewChallenge.decode(f.encode()))
        assertEquals(NewChallenge(), NewChallenge.decode(NewChallenge().encode()))
        assertNull(NewChallenge.decode(null))
        assertNull(NewChallenge.decode("не json"))
        // испорченные поля заменяются значениями по умолчанию
        val broken = NewChallenge.decode("""{"size": 5, "color": "pink", "time": {"system": "abc", "speed": "live"}}""")!!
        assertEquals(19, broken.size)
        assertEquals("automatic", broken.color)
        assertEquals(NewChallenge().time, broken.time)
    }

    @Test
    fun parsesCreatedChallenge() {
        assertEquals(CreatedChallenge(7, 99, "u-1"), ChallengeWire.parseCreated(json("""{"challenge": 7, "game": 99, "uuid": "u-1"}""")))
        assertEquals(CreatedChallenge(7, 99, null), ChallengeWire.parseCreated(json("""{"challenge": 7, "game": {"id": 99}}""")))
        assertNull(ChallengeWire.parseCreated(json("""{"game": 99}""")))
        assertNull(ChallengeWire.parseCreated(json("[]")))
    }

    @Test
    fun findsPlayerByName() {
        val page = json("""{"count": 2, "results": [{"id": 11, "username": "Bobby"}, {"id": 12, "username": "bob"}]}""")
        assertEquals(PlayerHit(12, "bob"), ChallengeWire.parsePlayerHit(page, "Bob"))
        assertNull(ChallengeWire.parsePlayerHit(page, "bo"))
        assertEquals(PlayerHit(5, "x"), ChallengeWire.parsePlayerHit(json("""[{"id": 5, "username": "x"}]"""), " x "))
        assertNull(ChallengeWire.parsePlayerHit(json("""{"results": []}"""), "x"))
    }

    @Test
    fun parsesRejectedOffer() {
        val r = ChallengeWire.parseOfferRejected(json("""{"type": "gameOfferRejected", "game_id": 55, "message": "Занят"}"""))!!
        assertEquals(55L, r.first)
        assertEquals("Занят", r.second)
        assertNull(ChallengeWire.parseOfferRejected(json("""{"type": "other", "game_id": 55}""")))
    }

    @Test
    fun outgoingFrames() {
        assertEquals("""["seek_graph/connect",{"channel":"global"}]""", ChallengeWire.seekConnect())
        assertEquals("""["seek_graph/disconnect",{"channel":"global"}]""", ChallengeWire.seekDisconnect())
        assertEquals("""["challenge/keepalive",{"challenge_id":7,"game_id":99}]""", ChallengeWire.keepalive(7, 99))
    }

    // ------------------------------------------------------------ ранги

    @Test
    fun ranksFollowTheSite() {
        assertEquals(24, Ranks.bounded(1500.0, null)) // ln(1500/525)·23.15 ≈ 24.3
        assertEquals(22, Ranks.bounded(null, 22.5))
        assertEquals(18, Ranks.bounded(null, null))
        assertEquals(Ranks.MIN, Ranks.bounded(50.0, null))
        assertEquals(Ranks.MAX, Ranks.bounded(7000.0, null))
        assertEquals(Ranks.MAX, Ranks.bounded(null, 1040.0))

        assertEquals("25k", Ranks.name(5))
        assertEquals("1k", Ranks.name(29))
        assertEquals("1d", Ranks.name(30))
        assertEquals("9d", Ranks.name(38))
        assertEquals("6k", Ranks.label(24.9))
        assertEquals("1d", Ranks.label(30.2))
        assertEquals("4p", Ranks.label(1040.0, pro = true))
        assertEquals("?", Ranks.label(-1000.0))
    }

    @Test
    fun configCarriesRank() {
        val withRating = Wire.parseConfig(
            json("""{"user_jwt": "j", "user": {"id": 3, "username": "me", "ranking": 22, "ratings": {"overall": {"rating": 1500.0}}}}""")
        )!!
        assertEquals(24, withRating.rank)
        val onlyRanking = Wire.parseConfig(json("""{"user_jwt": "j", "user": {"id": 3, "username": "me", "ranking": 22}}"""))!!
        assertEquals(22, onlyRanking.rank)
        assertEquals(18, Wire.parseConfig(json("""{"user_jwt": "j", "user": {"id": 3}}"""))!!.rank)
    }

    @Test
    fun errorTextKeepsLongBodiesOnRequest() {
        val body = "x".repeat(300)
        assertEquals("HTTP 400: " + "x".repeat(120), Wire.describeError(body, 400))
        assertEquals("HTTP 400: " + "x".repeat(300), Wire.describeError(body, 400, limit = 400))
        assertEquals("Нет прав", Wire.describeError("""{"detail": "Нет прав"}""", 403))
    }
}
