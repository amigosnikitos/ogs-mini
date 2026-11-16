package com.example.ogsmini.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

// Вызовы (открытые партии): список как на сайте, создание, принятие.
// Форматы сообщений и запросов взяты из исходников сайта (online-go.com) и библиотеки goban.

// ---------------------------------------------------------------- время

private val DURATION_UNITS = listOf(604800 to "нед", 86400 to "д", 3600 to "ч", 60 to "м", 1 to "с")

/** Секунды → короткая запись, как на сайте: «1нед 2д 3ч 4м 5с». */
fun shortDuration(seconds: Int): String {
    var rest = seconds
    if (rest <= 0) return "0с"
    val parts = ArrayList<String>()
    for ((size, unit) in DURATION_UNITS) {
        val n = rest / size
        rest -= n * size
        if (n > 0) parts.add("$n$unit")
    }
    return parts.joinToString(" ")
}

/** Как на сайте: среднее время на ход не больше 3600 секунд — партия «быстрая», иначе заочная. */
fun speedOfMoveTime(tpm: Int): String = when {
    tpm == 0 || tpm > 3600 -> "correspondence"
    tpm < 10 -> "blitz"
    else -> "live"
}

/**
 * Контроль времени. [system]: fischer, byoyomi, canadian, simple, absolute или none.
 * [speed] — то, что выбрал создатель (blitz, rapid, live, correspondence); сервер ждёт его вместе с параметрами.
 */
data class TimeControl(
    val system: String,
    val speed: String,
    val values: Map<String, Int> = emptyMap(),
    val pauseOnWeekends: Boolean = false,
) {
    fun value(key: String): Int = values[key] ?: 0

    fun with(key: String, v: Int): TimeControl = copy(values = values + (key to v))

    /** Среднее время на ход в секундах для доски [width]×[height] (computeAverageMoveTime из goban). */
    fun averageMoveSeconds(width: Int, height: Int): Int {
        val moves = if (width > 0 && height > 0) Math.round(0.7 * width * height) / 2.0 else 90.0
        val t: Double = when (system) {
            "fischer" -> value("initial_time") / moves + value("time_increment")
            "byoyomi" -> value("main_time") / moves + value("period_time")
            "simple" -> value("per_move").toDouble()
            "canadian" ->
                value("main_time") / moves + value("period_time").toDouble() / maxOf(1, value("stones_per_period"))
            "absolute" -> value("total_time") / moves
            else -> 0.0
        }
        return floor(t + 0.5).toInt()
    }

    /** blitz, live или correspondence — как classifyGameSpeed на сайте. */
    fun classify(width: Int, height: Int): String = speedOfMoveTime(averageMoveSeconds(width, height))

    /** Короткая запись как в списке на сайте: «10м+5×30с», «5м+7с до 1ч», «30с/ход». */
    fun short(): String = when (system) {
        "simple" -> "${shortDuration(value("per_move"))}/ход"
        "fischer" ->
            "${shortDuration(value("initial_time"))}+${shortDuration(value("time_increment"))}" +
                " до ${shortDuration(value("max_time"))}"
        "byoyomi" -> "${shortDuration(value("main_time"))}+${value("periods")}×${shortDuration(value("period_time"))}"
        "canadian" ->
            "${shortDuration(value("main_time"))}+${shortDuration(value("period_time"))}/${value("stones_per_period")}"
        "absolute" -> shortDuration(value("total_time"))
        "none" -> "без времени"
        else -> "[$system]"
    }

    /** Объект `time_control_parameters` для запроса на создание вызова. */
    fun toRequestJson(): JsonObject = buildJsonObject {
        put("system", system)
        put("speed", speed)
        for ((k, v) in values) put(k, v)
        put("time_control", system)
        put("pause_on_weekends", pauseOnWeekends)
    }

    companion object {
        private val NUMERIC_KEYS = listOf(
            "initial_time", "time_increment", "max_time", "main_time", "period_time",
            "periods", "stones_per_period", "per_move", "total_time",
        )

        /** Из полей вызова: [system] — поле `time_control`, [params] — `time_control_parameters`. */
        fun fromJson(system: String?, params: JsonObject?): TimeControl {
            val sys = params?.str("system") ?: params?.str("time_control") ?: system ?: "none"
            val values = LinkedHashMap<String, Int>()
            if (params != null) {
                for (k in NUMERIC_KEYS) params.dbl(k)?.let { values[k] = it.roundToInt() }
            }
            return TimeControl(sys, params?.str("speed") ?: "", values, params?.bool("pause_on_weekends") ?: false)
        }
    }
}

/** Допустимые значения и значения по умолчанию для формы создания вызова (таблицы с сайта). */
object TimePresets {
    val speeds = listOf("blitz", "rapid", "live", "correspondence")
    val systems = listOf("fischer", "byoyomi", "canadian", "simple", "absolute")

    private val TIMES: List<Int> = listOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 70, 80, 90, 105, 120, 150, 180,
        210, 240, 270, 300, 360, 420, 480, 540, 600, 720, 900, 1200, 1500, 1800, 2100, 2400, 2700, 3000, 3300, 3600,
        4200, 4800, 5400, 6000, 6600, 7200, 8100, 9000, 9900, 10800, 12600, 14400, 16200, 18000, 19800, 21600,
        25200, 28800, 36000, 43200, 57600, 72000, 86400, 86400 + 43200, 86400 * 2, 86400 * 3, 86400 * 4, 86400 * 5,
        86400 * 6, 86400 * 7, 86400 * 8, 86400 * 9, 86400 * 10, 86400 * 11, 86400 * 12, 86400 * 13, 86400 * 14,
        86400 * 21, 86400 * 28,
    )

    private fun gen(min: Int, max: Int): List<Int> = TIMES.filter { it in min..max }
    private fun withZero(l: List<Int>): List<Int> = listOf(0) + l

    private val OPTIONS: Map<String, List<Int>> = buildMap {
        fun opt(speed: String, system: String, key: String, values: List<Int>) {
            this["$speed/$system/$key"] = values
        }
        opt("blitz", "fischer", "initial_time", gen(5, 180))
        opt("blitz", "fischer", "time_increment", gen(1, 5))
        opt("blitz", "fischer", "max_time", gen(5, 900))
        opt("blitz", "simple", "per_move", gen(3, 4))
        opt("blitz", "canadian", "main_time", withZero(gen(0, 60)))
        opt("blitz", "canadian", "period_time", gen(5, 30))
        opt("blitz", "byoyomi", "main_time", withZero(gen(0, 60)))
        opt("blitz", "byoyomi", "period_time", gen(1, 10))
        opt("blitz", "absolute", "total_time", gen(45, 180))

        opt("rapid", "fischer", "initial_time", gen(30, 300))
        opt("rapid", "fischer", "time_increment", gen(6, 10))
        opt("rapid", "fischer", "max_time", gen(30, 3600))
        opt("rapid", "simple", "per_move", gen(5, 10))
        opt("rapid", "canadian", "main_time", withZero(gen(30, 300)))
        opt("rapid", "canadian", "period_time", gen(20, 300))
        opt("rapid", "byoyomi", "main_time", withZero(gen(30, 300)))
        opt("rapid", "byoyomi", "period_time", gen(10, 30))
        opt("rapid", "absolute", "total_time", gen(180, 600))

        opt("live", "fischer", "initial_time", gen(30, 3600 * 4))
        opt("live", "fischer", "time_increment", gen(10, 1800))
        opt("live", "fischer", "max_time", gen(30, 3600 * 4))
        opt("live", "simple", "per_move", gen(10, 3600))
        opt("live", "canadian", "main_time", withZero(gen(30, 3600 * 4)))
        opt("live", "canadian", "period_time", gen(20, 3600))
        opt("live", "byoyomi", "main_time", withZero(gen(30, 3600 * 4)))
        opt("live", "byoyomi", "period_time", gen(10, 3600))
        opt("live", "absolute", "total_time", gen(600, 14400))

        opt("correspondence", "fischer", "initial_time", gen(86400, 86400 * 28))
        opt("correspondence", "fischer", "time_increment", gen(14400, 86400 * 7))
        opt("correspondence", "fischer", "max_time", gen(86400, 86400 * 28))
        opt("correspondence", "simple", "per_move", gen(3600 * 12, 86400 * 28))
        opt("correspondence", "canadian", "main_time", withZero(gen(86400, 86400 * 28)))
        opt("correspondence", "canadian", "period_time", gen(86400, 86400 * 28))
        opt("correspondence", "byoyomi", "main_time", withZero(gen(86400, 86400 * 28)))
        opt("correspondence", "byoyomi", "period_time", gen(86400, 86400 * 28))
        opt("correspondence", "absolute", "total_time", gen(86400 * 7, 86400 * 28))
    }

    /** Параметры системы в том порядке, в каком их показывает форма. */
    fun keys(system: String): List<String> = when (system) {
        "fischer" -> listOf("initial_time", "time_increment", "max_time")
        "byoyomi" -> listOf("main_time", "period_time", "periods")
        "canadian" -> listOf("main_time", "period_time", "stones_per_period")
        "simple" -> listOf("per_move")
        "absolute" -> listOf("total_time")
        else -> emptyList()
    }

    /** Варианты времени для выпадающего списка (всегда включают значение по умолчанию). Пусто для счётчиков. */
    fun options(speed: String, system: String, key: String): List<Int> {
        val base = OPTIONS["$speed/$system/$key"] ?: return emptyList()
        val default = defaults(speed, system).values[key]
        return if (default != null && default !in base) (base + default).sorted() else base
    }

    /** Границы для счётчиков (periods, stones_per_period); null для параметров-времён. */
    fun countRange(speed: String, key: String): IntRange? {
        val short = speed == "blitz" || speed == "rapid"
        return when (key) {
            "periods" -> if (short) 1..10 else 1..300
            "stones_per_period" -> if (short) 1..10 else 1..50
            else -> null
        }
    }

    fun defaults(speed: String, system: String): TimeControl {
        val d = 86400
        val v: Map<String, Int> = when (speed) {
            "blitz" -> when (system) {
                "fischer" -> mapOf("initial_time" to 30, "time_increment" to 5, "max_time" to 300)
                "byoyomi" -> mapOf("main_time" to 30, "period_time" to 5, "periods" to 5)
                "canadian" -> mapOf("main_time" to 30, "period_time" to 20, "stones_per_period" to 3)
                "simple" -> mapOf("per_move" to 5)
                "absolute" -> mapOf("total_time" to 300)
                else -> emptyMap()
            }
            "rapid" -> when (system) {
                "fischer" -> mapOf("initial_time" to 300, "time_increment" to 7, "max_time" to 3600)
                "byoyomi" -> mapOf("main_time" to 300, "period_time" to 30, "periods" to 5)
                "canadian" -> mapOf("main_time" to 300, "period_time" to 60, "stones_per_period" to 3)
                "simple" -> mapOf("per_move" to 10)
                "absolute" -> mapOf("total_time" to 600)
                else -> emptyMap()
            }
            "live" -> when (system) {
                "fischer" -> mapOf("initial_time" to 600, "time_increment" to 10, "max_time" to 3600)
                "byoyomi" -> mapOf("main_time" to 600, "period_time" to 30, "periods" to 5)
                "canadian" -> mapOf("main_time" to 600, "period_time" to 180, "stones_per_period" to 10)
                "simple" -> mapOf("per_move" to 15)
                "absolute" -> mapOf("total_time" to 900)
                else -> emptyMap()
            }
            else -> when (system) {
                "fischer" -> mapOf("initial_time" to 3 * d, "time_increment" to d, "max_time" to 7 * d)
                "byoyomi" -> mapOf("main_time" to 7 * d, "period_time" to d, "periods" to 5)
                "canadian" -> mapOf("main_time" to 7 * d, "period_time" to 7 * d, "stones_per_period" to 10)
                "simple" -> mapOf("per_move" to 2 * d)
                "absolute" -> mapOf("total_time" to 28 * d)
                else -> emptyMap()
            }
        }
        return TimeControl(system, speed, v, pauseOnWeekends = speed == "correspondence")
    }

    fun speedName(speed: String): String = when (speed) {
        "blitz" -> "Блиц"
        "rapid" -> "Рапид"
        "live" -> "Лайв"
        "correspondence" -> "Заочная"
        else -> speed
    }

    fun systemName(system: String): String = when (system) {
        "fischer" -> "Фишер"
        "byoyomi" -> "Бёёми"
        "canadian" -> "Канадская"
        "simple" -> "Простая"
        "absolute" -> "Абсолютная"
        "none" -> "Без времени"
        else -> system
    }

    fun keyName(key: String): String = when (key) {
        "initial_time" -> "Начальное время"
        "time_increment" -> "Прибавка за ход"
        "max_time" -> "Максимум времени"
        "main_time" -> "Основное время"
        "period_time" -> "Время периода"
        "periods" -> "Количество периодов"
        "stones_per_period" -> "Ходов за период"
        "per_move" -> "Время на ход"
        "total_time" -> "Общее время"
        else -> key
    }

    /** Подпись значения в списке: 0 — «нет», иначе короткая запись времени. */
    fun valueText(v: Int): String = if (v == 0) "нет" else shortDuration(v)
}

// ---------------------------------------------------------------- ранги

object Ranks {
    const val MIN = 5 // 25k
    const val MAX = 38 // 9d

    fun fromRating(rating: Double): Double = ln(rating.coerceIn(100.0, 6000.0) / 525.0) * 23.15

    /** Ранг для проверки допуска к вызовам (bounded_rank на сайте): берём рейтинг, иначе поле `ranking`. */
    fun bounded(rating: Double?, ranking: Double?): Int {
        val raw = when {
            rating != null -> floor(fromRating(rating)).toInt()
            ranking != null -> floor(ranking.coerceIn(-1000.0, 2000.0)).toInt()
            else -> 18
        }
        return raw.coerceIn(MIN, MAX)
    }

    /** 5 → «25k», 30 → «1d». */
    fun name(rank: Int): String = if (rank < 30) "${30 - rank}k" else "${rank - 29}d"

    /** Подпись ранга игрока из вызова; у профессионалов — «3p». */
    fun label(rank: Double, pro: Boolean = false): String {
        if (rank < -900) return "?"
        if (pro || rank > 900) {
            val p = if (rank > 900) rank - 1000 - 36 else rank - 36
            return "${maxOf(1, floor(p).toInt())}p"
        }
        return name(floor(rank).toInt())
    }
}

// ---------------------------------------------------------------- вызов из списка

data class Challenge(
    val id: Long,
    val gameId: Long,
    val userId: Long,
    val username: String,
    val rank: Double,
    val pro: Boolean,
    val minRank: Double,
    val maxRank: Double,
    val name: String,
    val ranked: Boolean,
    /** -1 — автоматическая, 0 — без форы; null — сервер не прислал. */
    val handicap: Int?,
    /** null — автоматическое коми. */
    val komi: Double?,
    val rules: String,
    val width: Int,
    val height: Int,
    /** Цвет создателя: black, white, automatic или random. */
    val creatorColor: String,
    val disableAnalysis: Boolean,
    val time: TimeControl,
    val timePerMove: Int,
    val rengo: Boolean,
    val inviteOnly: Boolean,
) {
    /** Быстрая (идёт сейчас) или заочная партия — как isLiveGame на сайте. */
    val live: Boolean
        get() = if (time.values.isNotEmpty()) {
            time.classify(width, height) != "correspondence"
        } else {
            timePerMove in 1..3600
        }
}

object ChallengeText {
    fun size(c: Challenge): String = "${c.width}×${c.height}"

    fun rules(r: String): String = when (r) {
        "japanese" -> "Японские"
        "chinese" -> "Китайские"
        "aga" -> "AGA"
        "korean" -> "Корейские"
        "nz" -> "Новозеландские"
        "ing" -> "Ing"
        else -> r
    }

    fun handicap(h: Int?): String = when {
        h == null -> "—"
        h < 0 -> "авто"
        h == 0 -> "нет"
        else -> "$h"
    }

    fun komi(komi: Double?): String = if (komi == null) "авто" else komi.toString().removeSuffix(".0")

    /** Какого цвета будет тот, кто примет вызов. */
    fun yourColor(creatorColor: String): String = when (creatorColor) {
        "black" -> "белые"
        "white" -> "чёрные"
        "random" -> "случайно"
        else -> "по умолчанию (определит сервер)"
    }

    fun ratedText(ranked: Boolean): String = if (ranked) "рейтинговая" else "дружеская"
}

sealed class SeekMessage {
    data class Add(val challenge: Challenge) : SeekMessage()
    data class Remove(val challengeId: Long) : SeekMessage()
    data class Started(val challengeId: Long, val gameId: Long?) : SeekMessage()
}

object Seek {
    /** Событие `seekgraph/global`: массив сообщений о вызовах. */
    fun parse(data: JsonElement): List<SeekMessage> {
        val items: List<JsonElement> = when (data) {
            is JsonArray -> data
            is JsonObject -> listOf(data)
            else -> emptyList()
        }
        return items.mapNotNull { (it as? JsonObject)?.let(::parseOne) }
    }

    private fun parseOne(o: JsonObject): SeekMessage? {
        val id = o.long("challenge_id") ?: return null
        if ("game_started" in o) return SeekMessage.Started(id, o.long("game_id"))
        if ("delete" in o) return SeekMessage.Remove(id)
        val userId = o.long("user_id") ?: return null
        val gameId = o.long("game_id") ?: return null
        val width = o.int("width") ?: 19
        val komiAuto = o.str("komi_auto")
        return SeekMessage.Add(
            Challenge(
                id = id,
                gameId = gameId,
                userId = userId,
                username = o.str("username") ?: "#$userId",
                rank = o.dbl("ranking") ?: o.dbl("rank") ?: 0.0,
                pro = o.bool("professional") ?: o.bool("pro") ?: false,
                minRank = o.dbl("min_rank") ?: -1000.0,
                maxRank = o.dbl("max_rank") ?: 1000.0,
                name = o.str("name") ?: "",
                ranked = o.bool("ranked") ?: false,
                handicap = o.int("handicap"),
                komi = if (komiAuto == "automatic") null else o.dbl("komi"),
                rules = o.str("rules") ?: "japanese",
                width = width,
                height = o.int("height") ?: width,
                creatorColor = o.str("challenger_color") ?: "automatic",
                disableAnalysis = o.bool("disable_analysis") ?: false,
                time = TimeControl.fromJson(o.str("time_control"), o.obj("time_control_parameters")),
                timePerMove = o.int("time_per_move") ?: 0,
                rengo = o.bool("rengo") ?: false,
                inviteOnly = o.bool("invite_only") ?: false,
            )
        )
    }
}

data class ChallengeRow(
    val c: Challenge,
    val mine: Boolean,
    val eligible: Boolean,
    /** Почему вызов нельзя принять (null — можно или это ваш вызов). */
    val reason: String?,
)

/** Список открытых вызовов, который сервер ведёт сообщениями «добавить / удалить / партия началась». */
class SeekGraph {
    private val map = LinkedHashMap<Long, Challenge>()

    fun clear() {
        map.clear()
    }

    operator fun get(id: Long): Challenge? = map[id]

    fun apply(messages: List<SeekMessage>) {
        for (m in messages) {
            when (m) {
                is SeekMessage.Add -> map[m.challenge.id] = m.challenge
                is SeekMessage.Remove -> map.remove(m.challengeId)
                is SeekMessage.Started -> map.remove(m.challengeId)
            }
        }
    }

    /** Сколько вызовов-рэнго скрыто: командную игру приложение пока не поддерживает. */
    fun rengoCount(): Int = map.values.count { it.rengo }

    fun rows(myId: Long, myRank: Int): List<ChallengeRow> =
        map.values.filter { !it.rengo }.map { c ->
            val mine = c.userId == myId
            val reason: String? = when {
                mine -> null
                c.inviteOnly -> "только по приглашению"
                c.minRank > myRank -> "мин. ранг: ${Ranks.label(c.minRank)}"
                c.maxRank < myRank -> "макс. ранг: ${Ranks.label(c.maxRank)}"
                else -> null
            }
            ChallengeRow(c, mine, eligible = !mine && reason == null, reason = reason)
        }.sortedWith(ROW_ORDER)

    private companion object {
        /** Свои вызовы, затем доступные, затем по имени, рейтинговые раньше дружеских, затем по номеру. */
        val ROW_ORDER = Comparator<ChallengeRow> { a, b ->
            when {
                a.mine != b.mine -> if (a.mine) -1 else 1
                a.eligible != b.eligible -> if (a.eligible) -1 else 1
                else -> {
                    val byName = a.c.username.compareTo(b.c.username, ignoreCase = true)
                    when {
                        byName != 0 -> byName
                        a.c.ranked != b.c.ranked -> if (a.c.ranked) -1 else 1
                        else -> a.c.id.compareTo(b.c.id)
                    }
                }
            }
        }
    }
}

/** Фильтры списка — те же, что на сайте (shouldDisplayChallenge). */
data class ChallengeFilter(
    val showIneligible: Boolean = false,
    val showRanked: Boolean = true,
    val showUnranked: Boolean = true,
    val show19: Boolean = true,
    val show13: Boolean = true,
    val show9: Boolean = true,
    val showOther: Boolean = true,
    val showHandicap: Boolean = true,
) {
    fun matches(r: ChallengeRow): Boolean {
        if (r.mine) return true
        val c = r.c
        val size = (show19 && c.width == 19 && c.height == 19) ||
            (show13 && c.width == 13 && c.height == 13) ||
            (show9 && c.width == 9 && c.height == 9) ||
            (showOther && (c.width != c.height || (c.width != 19 && c.width != 13 && c.width != 9)))
        val kind = (showUnranked && !c.ranked) || (showRanked && c.ranked)
        val handicap = showHandicap || c.handicap == 0
        return (r.eligible || showIneligible) && kind && size && handicap
    }

    fun toBits(): Int {
        var bits = 0
        val flags = listOf(showIneligible, showRanked, showUnranked, show19, show13, show9, showOther, showHandicap)
        for ((i, f) in flags.withIndex()) if (f) bits = bits or (1 shl i)
        return bits
    }

    companion object {
        fun fromBits(bits: Int): ChallengeFilter {
            fun on(i: Int) = bits and (1 shl i) != 0
            return ChallengeFilter(on(0), on(1), on(2), on(3), on(4), on(5), on(6), on(7))
        }
    }
}

// ---------------------------------------------------------------- создание вызова

/** Что вводит пользователь в форме «Создать вызов». */
data class NewChallenge(
    val name: String = "",
    val size: Int = 19,
    val rules: String = "japanese",
    val ranked: Boolean = true,
    /** -1 — автоматическая, 0 — без форы, 2…9 — столько камней. */
    val handicap: Int = -1,
    /** null — автоматическое коми. */
    val customKomi: Double? = null,
    /** Цвет создателя: automatic, black, white или random. */
    val color: String = "automatic",
    val disableAnalysis: Boolean = false,
    val time: TimeControl = TimePresets.defaults("live", "byoyomi"),
    val restrictRank: Boolean = false,
    val minRank: Int = Ranks.MIN,
    val maxRank: Int = 36,
    /** Пусто — открытый вызов; иначе имя игрока, которому адресован вызов. */
    val opponent: String = "",
) {
    /** Приводит поля к допустимым сочетаниям (как это делает форма на сайте). */
    fun normalized(): NewChallenge {
        var f = this
        if (f.ranked) {
            if (f.handicap > 9) f = f.copy(handicap = 9)
            f = f.copy(customKomi = null)
        }
        if (f.handicap < 0) f = f.copy(customKomi = null)
        if (f.time.system == "fischer" && f.time.value("max_time") < f.time.value("initial_time")) {
            f = f.copy(time = f.time.with("max_time", f.time.value("initial_time")))
        }
        return f
    }

    val live: Boolean get() = time.classify(size, size) != "correspondence"

    /** Текст ошибки, если вызов создать нельзя; null — всё в порядке. */
    fun problem(): String? = when {
        size != 9 && size != 13 && size != 19 -> "Размер доски: 9, 13 или 19"
        restrictRank && minRank > maxRank -> "Минимальный ранг выше максимального"
        time.system != "none" && TimePresets.keys(time.system).any { time.values[it] == null } ->
            "Не заданы параметры времени"
        else -> null
    }

    /** Краткое описание для экрана ожидания. */
    fun summary(): String {
        val h = when {
            handicap < 0 -> "фора авто"
            handicap == 0 -> "без форы"
            else -> "фора $handicap"
        }
        return "$size×$size · ${time.short()} · ${ChallengeText.ratedText(ranked)} · $h"
    }

    /** Тело запроса `POST /api/v1/challenges` (или `/players/<id>/challenge`) — как на сайте. */
    fun requestBody(): JsonObject {
        val f = normalized()
        return buildJsonObject {
            put("min_ranking", if (f.restrictRank) f.minRank else -1000)
            put("max_ranking", if (f.restrictRank) f.maxRank else 1000)
            put("challenger_color", f.color)
            put("rengo_auto_start", 0)
            put("invite_only", false)
            put("game", buildJsonObject {
                put("name", f.name.trim().ifEmpty { "Friendly Match" })
                put("rules", f.rules)
                put("ranked", f.ranked)
                put("width", f.size)
                put("height", f.size)
                put("handicap", f.handicap)
                put("komi_auto", if (f.customKomi == null) "automatic" else "custom")
                f.customKomi?.let { put("komi", it) }
                put("disable_analysis", f.disableAnalysis)
                put("initial_state", JsonNull)
                put("private", false)
                put("rengo", false)
                put("rengo_casual_mode", true)
                put("pause_on_weekends", f.time.pauseOnWeekends)
                put("time_control", f.time.system)
                put("time_control_parameters", f.time.toRequestJson())
            })
        }
    }

    /** Для сохранения последней формы между запусками. */
    fun encode(): String = buildJsonObject {
        put("name", name)
        put("size", size)
        put("rules", rules)
        put("ranked", ranked)
        put("handicap", handicap)
        customKomi?.let { put("komi", it) }
        put("color", color)
        put("disable_analysis", disableAnalysis)
        put("time", time.toRequestJson())
        put("restrict_rank", restrictRank)
        put("min_rank", minRank)
        put("max_rank", maxRank)
    }.toString()

    companion object {
        fun decode(text: String?): NewChallenge? {
            if (text.isNullOrBlank()) return null
            val o = Wire.parseJson(text) as? JsonObject ?: return null
            val t = o.obj("time")
            val tc = if (t == null) null else TimeControl.fromJson(null, t)
            val base = NewChallenge()
            val time = if (tc != null && tc.system in TimePresets.systems && tc.speed in TimePresets.speeds) tc else base.time
            return NewChallenge(
                name = o.str("name") ?: "",
                size = o.int("size")?.takeIf { it == 9 || it == 13 || it == 19 } ?: base.size,
                rules = o.str("rules") ?: base.rules,
                ranked = o.bool("ranked") ?: base.ranked,
                handicap = o.int("handicap") ?: base.handicap,
                customKomi = o.dbl("komi"),
                color = o.str("color")?.takeIf { it in COLORS } ?: base.color,
                disableAnalysis = o.bool("disable_analysis") ?: false,
                time = time,
                restrictRank = o.bool("restrict_rank") ?: false,
                minRank = (o.int("min_rank") ?: base.minRank).coerceIn(Ranks.MIN, Ranks.MAX),
                maxRank = (o.int("max_rank") ?: base.maxRank).coerceIn(Ranks.MIN, Ranks.MAX),
            ).normalized()
        }

        val COLORS = listOf("automatic", "black", "white", "random")
        val RULES = listOf("japanese", "chinese", "aga", "korean", "nz", "ing")
    }
}

data class CreatedChallenge(val challengeId: Long, val gameId: Long, val uuid: String?)

data class PlayerHit(val id: Long, val username: String)

/** Сообщения и ответы вокруг вызовов. */
object ChallengeWire {
    private fun frame(command: String, data: JsonElement): String =
        buildJsonArray { add(command); add(data) }.toString()

    /** Подписка на общий список вызовов. */
    fun seekConnect(): String = frame("seek_graph/connect", buildJsonObject { put("channel", "global") })

    fun seekDisconnect(): String = frame("seek_graph/disconnect", buildJsonObject { put("channel", "global") })

    /** Пока вызов быстрой партии ждёт соперника, раз в секунду нужно подтверждать, что мы ещё здесь. */
    fun keepalive(challengeId: Long, gameId: Long): String =
        frame("challenge/keepalive", buildJsonObject {
            put("challenge_id", challengeId)
            put("game_id", gameId)
        })

    /** Ответ на создание вызова: `{challenge: id, game: id или {id: ...}, uuid}`. */
    fun parseCreated(root: JsonElement): CreatedChallenge? {
        val o = root as? JsonObject ?: return null
        val challengeId = o.long("challenge") ?: o.long("challenge_id") ?: return null
        val game = o["game"]
        val gameId = (game as? JsonObject)?.long("id")
            ?: (game as? JsonPrimitive)?.longOrNull
            ?: o.long("game_id")
            ?: return null
        return CreatedChallenge(challengeId, gameId, o.str("uuid"))
    }

    /** Ищет игрока по имени в ответе `GET /api/v1/players/?username=…` (постраничный список или массив). */
    fun parsePlayerHit(root: JsonElement, wanted: String): PlayerHit? {
        val list: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["results"] as? JsonArray) ?: emptyList()
            else -> emptyList()
        }
        val hits = list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.long("id") ?: return@mapNotNull null
            PlayerHit(id, o.str("username") ?: return@mapNotNull null)
        }
        return hits.firstOrNull { it.username.equals(wanted.trim(), ignoreCase = true) }
    }

    /** Событие `notification`: сопернику не понравился вызов. Возвращает номер партии и пояснение. */
    fun parseOfferRejected(root: JsonElement): Pair<Long?, String?>? {
        val o = root as? JsonObject ?: return null
        if (o.str("type") != "gameOfferRejected") return null
        val details = o.obj("rejection_details")
        val message = o.str("message") ?: details?.str("rejection_code")
        return o.long("game_id") to message
    }
}
