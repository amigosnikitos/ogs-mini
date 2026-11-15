package com.example.ogsmini.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.floor

/** Входящее сообщение realtime-API: событие `["имя", данные]` или ответ `[id, данные]`. */
sealed class Incoming {
    data class Event(val name: String, val data: JsonElement) : Incoming()
    data class Response(val id: Long, val data: JsonElement, val error: JsonElement?) : Incoming()
}

// ---------- безопасные чтения из JSON ----------

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

// ---------- модели ----------

data class PlayerInfo(val id: Long, val name: String, val rank: Double?)

data class PlayerClock(val thinking: Double?, val periods: Int?, val periodTime: Double?)

data class ClockInfo(
    val currentPlayer: Long?,
    val lastMoveMs: Long?,
    val nowMs: Long?,
    val black: PlayerClock?,
    val white: PlayerClock?,
)

data class MoveRec(val x: Int, val y: Int) {
    val isPass: Boolean get() = x < 0 || y < 0
}

data class GameData(
    val gameId: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val initialPlayer: Stone,
    val moves: List<MoveRec>,
    val phase: String,
    val black: PlayerInfo?,
    val white: PlayerInfo?,
    val clock: ClockInfo?,
    val outcome: String?,
    val winnerId: Long?,
    val komi: Double?,
    val handicap: Int,
    val rules: String?,
    val initialBlack: String,
    val initialWhite: String,
    val removed: String,
)

data class MoveEvent(val moveNumber: Int?, val x: Int, val y: Int)

data class RemovedEvent(val removed: Boolean, val stones: String, val allRemoved: String?)

data class UiConfig(val userId: Long, val username: String, val jwt: String)

data class Tokens(val access: String, val refresh: String?, val expiresInSec: Long?)

data class GameSummary(
    val id: Long,
    val name: String,
    val black: String,
    val white: String,
    val width: Int,
    val height: Int,
    val myTurn: Boolean?,
)

object Wire {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    // ---------- разбор входящих ----------

    fun parse(text: String): Incoming? {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return null
        }
        val arr = root as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        val head = arr[0] as? JsonPrimitive ?: return null
        val data: JsonElement = if (arr.size > 1) arr[1] else JsonNull
        if (head.isString) return Incoming.Event(head.content, data)
        val id = head.longOrNull ?: return null
        return Incoming.Response(id, data, if (arr.size > 2) arr[2] else null)
    }

    fun parseJson(text: String): JsonElement? =
        try { json.parseToJsonElement(text) } catch (e: Exception) { null }

    fun parseGameData(el: JsonElement): GameData? {
        val o = el as? JsonObject ?: return null
        val id = o.long("game_id") ?: o.long("id") ?: return null
        val w = o.int("width") ?: 19
        val h = o.int("height") ?: w
        val players = o.obj("players")
        val black = parsePlayer(players?.get("black"))
            ?: o.long("black_player_id")?.let { PlayerInfo(it, "#$it", null) }
        val white = parsePlayer(players?.get("white"))
            ?: o.long("white_player_id")?.let { PlayerInfo(it, "#$it", null) }
        val init = o.obj("initial_state")
        val moves = ArrayList<MoveRec>()
        (o["moves"] as? JsonArray)?.forEach { m -> parseMoveRec(m)?.let { moves.add(it) } }
        return GameData(
            gameId = id,
            name = o.str("game_name") ?: o.str("name") ?: "Партия #$id",
            width = w,
            height = h,
            initialPlayer = if (o.str("initial_player") == "white") Stone.WHITE else Stone.BLACK,
            moves = moves,
            phase = o.str("phase") ?: "play",
            black = black,
            white = white,
            clock = parseClock(o["clock"]),
            outcome = o.str("outcome"),
            winnerId = o.long("winner"),
            komi = o.dbl("komi"),
            handicap = o.int("handicap") ?: 0,
            rules = o.str("rules"),
            initialBlack = init?.str("black") ?: "",
            initialWhite = init?.str("white") ?: "",
            removed = o.str("removed") ?: "",
        )
    }

    private fun parsePlayer(el: JsonElement?): PlayerInfo? {
        val o = el as? JsonObject ?: return null
        val id = o.long("id") ?: return null
        val name = o.str("username") ?: o.str("name") ?: "#$id"
        return PlayerInfo(id, name, o.dbl("ranking") ?: o.dbl("rank"))
    }

    private fun parseMoveRec(el: JsonElement): MoveRec? = when (el) {
        is JsonArray -> {
            val x = (el.getOrNull(0) as? JsonPrimitive)?.intOrNull
            val y = (el.getOrNull(1) as? JsonPrimitive)?.intOrNull
            if (x != null && y != null) MoveRec(x, y) else null
        }
        is JsonObject -> {
            val x = el.int("x")
            val y = el.int("y")
            if (x != null && y != null) MoveRec(x, y) else null
        }
        is JsonPrimitive -> if (el.isString) Coord.decode(el.content)?.let { MoveRec(it.first, it.second) } else null
    }

    fun parseClock(el: JsonElement?): ClockInfo? {
        val o = el as? JsonObject ?: return null
        return ClockInfo(
            currentPlayer = o.long("current_player"),
            lastMoveMs = o.long("last_move"),
            nowMs = o.long("now"),
            black = parsePlayerClock(o["black_time"]),
            white = parsePlayerClock(o["white_time"]),
        )
    }

    // Для простых контролей времени сервер присылает число (момент истечения) — его не показываем.
    private fun parsePlayerClock(el: JsonElement?): PlayerClock? {
        val o = el as? JsonObject ?: return null
        return PlayerClock(o.dbl("thinking_time"), o.int("periods"), o.dbl("period_time"))
    }

    fun parseMoveEvent(el: JsonElement): MoveEvent? {
        val o = el as? JsonObject ?: return null
        val n = o.int("move_number")
        return when (val mv = o["move"]) {
            is JsonArray -> {
                val x = (mv.getOrNull(0) as? JsonPrimitive)?.intOrNull
                val y = (mv.getOrNull(1) as? JsonPrimitive)?.intOrNull
                if (x != null && y != null) MoveEvent(n, x, y) else null
            }
            is JsonPrimitive -> if (mv.isString) Coord.decode(mv.content)?.let { MoveEvent(n, it.first, it.second) } else null
            else -> null
        }
    }

    fun parseRemovedEvent(el: JsonElement): RemovedEvent? {
        val o = el as? JsonObject ?: return null
        return RemovedEvent(o.bool("removed") ?: true, o.str("stones") ?: "", o.str("all_removed"))
    }

    fun parsePhase(el: JsonElement): String? = (el as? JsonPrimitive)?.takeIf { it.isString }?.content

    // ---------- REST ----------

    fun parseConfig(root: JsonElement): UiConfig? {
        val o = root as? JsonObject ?: return null
        val user = o.obj("user") ?: return null
        val id = user.long("id") ?: return null
        val jwt = o.str("user_jwt") ?: return null
        return UiConfig(id, user.str("username") ?: "#$id", jwt)
    }

    fun parseTokens(root: JsonElement): Tokens? {
        val o = root as? JsonObject ?: return null
        val access = o.str("access_token") ?: return null
        return Tokens(access, o.str("refresh_token"), o.long("expires_in"))
    }

    /** Список активных партий из `/api/v1/ui/overview`. */
    fun parseOverview(root: JsonElement, myId: Long): List<GameSummary> {
        val games = (root as? JsonObject)?.get("active_games") as? JsonArray ?: return emptyList()
        return games.mapNotNull { g ->
            val o = g as? JsonObject ?: return@mapNotNull null
            val inner = o.obj("json")
            val id = o.long("id") ?: inner?.long("game_id") ?: return@mapNotNull null
            val players = inner?.obj("players")
            val black = o.obj("black")?.str("username") ?: parsePlayer(players?.get("black"))?.name ?: "?"
            val white = o.obj("white")?.str("username") ?: parsePlayer(players?.get("white"))?.name ?: "?"
            val current = inner?.obj("clock")?.long("current_player")
            GameSummary(
                id = id,
                name = o.str("name") ?: inner?.str("game_name") ?: "",
                black = black,
                white = white,
                width = o.int("width") ?: inner?.int("width") ?: 19,
                height = o.int("height") ?: inner?.int("height") ?: o.int("width") ?: 19,
                myTurn = current?.let { it == myId },
            )
        }
    }

    /** Человекочитаемый текст ошибки из тела ответа REST/OAuth. */
    fun describeError(body: String, code: Int): String {
        val o = parseJson(body) as? JsonObject
        val msg = o?.str("error_description") ?: o?.str("detail") ?: o?.str("error")
        if (msg != null) return msg
        val tail = body.trim().take(120)
        return if (tail.isEmpty()) "HTTP $code" else "HTTP $code: $tail"
    }

    // ---------- формат рангов ----------

    fun rankLabel(rank: Double?): String {
        if (rank == null) return ""
        val r = floor(rank).toInt()
        return if (r < 30) "${30 - r}k" else "${r - 29}d"
    }

    // ---------- исходящие сообщения ----------

    private fun frame(command: String, data: JsonElement): String =
        buildJsonArray { add(command); add(data) }.toString()

    fun authenticate(jwt: String, deviceId: String, clientVersion: String): String =
        frame("authenticate", buildJsonObject {
            put("jwt", jwt)
            put("device_id", deviceId)
            put("client", "ogs-mini-android")
            put("client_version", clientVersion)
        })

    fun ping(nowMs: Long): String =
        frame("net/ping", buildJsonObject { put("client", nowMs); put("drift", 0); put("latency", 0) })

    fun gameConnect(gameId: Long): String =
        frame("game/connect", buildJsonObject { put("game_id", gameId); put("chat", false) })

    fun gameDisconnect(gameId: Long): String =
        frame("game/disconnect", buildJsonObject { put("game_id", gameId) })

    fun move(gameId: Long, x: Int, y: Int): String =
        frame("game/move", buildJsonObject { put("game_id", gameId); put("move", Coord.encode(x, y)) })

    fun resign(gameId: Long): String =
        frame("game/resign", buildJsonObject { put("game_id", gameId) })

    fun removedSet(gameId: Long, removed: Boolean, stones: String): String =
        frame("game/removed_stones/set", buildJsonObject {
            put("game_id", gameId); put("removed", removed); put("stones", stones)
        })

    fun removedAccept(gameId: Long, stones: String): String =
        frame("game/removed_stones/accept", buildJsonObject {
            put("game_id", gameId); put("stones", stones); put("strict_seki_mode", false)
        })
}
