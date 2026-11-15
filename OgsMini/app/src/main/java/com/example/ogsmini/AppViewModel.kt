package com.example.ogsmini

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ogsmini.core.Coord
import com.example.ogsmini.core.GameSession
import com.example.ogsmini.core.GameSummary
import com.example.ogsmini.core.PlayCheck
import com.example.ogsmini.core.Pkce
import com.example.ogsmini.core.RemovedEvent
import com.example.ogsmini.core.Stone
import com.example.ogsmini.core.Tokens
import com.example.ogsmini.core.Wire
import com.example.ogsmini.net.ApiException
import com.example.ogsmini.net.OgsApi
import com.example.ogsmini.net.OgsSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed interface Screen {
    data object Login : Screen
    data object Games : Screen
    data class Game(val id: Long) : Screen
}

data class LoginUi(val busy: Boolean = false, val error: String? = null)

data class GamesUi(
    val loading: Boolean = false,
    val games: List<GameSummary> = emptyList(),
    val error: String? = null,
)

/** Всё, что нужно экрану партии, одним неизменяемым снимком. */
data class GameUi(
    val gameId: Long,
    val loaded: Boolean = false,
    val title: String = "",
    val width: Int = 19,
    val height: Int = 19,
    val cells: IntArray = IntArray(0), // 0 — пусто, 1 — чёрный, 2 — белый
    val lastMove: Int = -1,
    val removed: Set<Int> = emptySet(),
    val pending: Int = -1,
    val blackName: String = "",
    val whiteName: String = "",
    val blackCaptures: Int = 0,
    val whiteCaptures: Int = 0,
    val blackClock: String? = null,
    val whiteClock: String? = null,
    val toPlay: Stone = Stone.BLACK,
    val myColor: Stone? = null,
    val phase: String = "loading",
    val moveCount: Int = 0,
    val status: String = "Загрузка партии…",
    val canMove: Boolean = false,
    val canRemove: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val REDIRECT_URI = "gobo://callback"
        private const val TAG = "OgsMini"
        private const val VERSION = "0.1"
    }

    private val store = Store(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val api = OgsApi(http)
    private val socket = OgsSocket(http, object : OgsSocket.Listener {
        override fun onReady() {
            viewModelScope.launch { handleReady() }
        }

        override fun onEvent(name: String, data: JsonElement) {
            viewModelScope.launch { handleEvent(name, data) }
        }

        override fun onDisconnected(reason: String) {
            viewModelScope.launch { handleDisconnected(reason) }
        }
    })

    private val _screen = MutableStateFlow<Screen>(if (store.accessToken != null) Screen.Games else Screen.Login)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _login = MutableStateFlow(LoginUi())
    val login: StateFlow<LoginUi> = _login.asStateFlow()

    private val _games = MutableStateFlow(GamesUi())
    val games: StateFlow<GamesUi> = _games.asStateFlow()

    private val _game = MutableStateFlow<GameUi?>(null)
    val game: StateFlow<GameUi?> = _game.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val clientId: String get() = store.clientId
    val username: String get() = store.username

    private var jwt: String? = null
    private var session: GameSession? = null
    private var socketReady = false
    private var pending = -1
    private var awaitingMove = false
    private var lastResyncMs = 0L
    private var tickJob: Job? = null
    private var moveTimeoutJob: Job? = null

    init {
        if (store.accessToken != null) bootstrap()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun say(text: String) {
        _message.value = text
    }

    // ------------------------------------------------------------------ вход

    fun saveClientId(id: String) {
        store.clientId = id
    }

    /** Возвращает адрес для открытия в браузере или null (тогда ошибка уже показана). */
    fun beginBrowserLogin(clientId: String): String? {
        store.clientId = clientId
        if (store.clientId.isEmpty()) {
            _login.value = LoginUi(error = "Укажите Client ID приложения (см. README)")
            return null
        }
        val verifier = Pkce.randomToken()
        val state = Pkce.randomToken(16)
        store.pkceVerifier = verifier
        store.pkceState = state
        _login.value = LoginUi()
        return Pkce.authorizeUrl(store.clientId, REDIRECT_URI, Pkce.challengeFor(verifier), state)
    }

    /** Вызывается из MainActivity, когда браузер вернул нас по адресу gobo://callback?... */
    fun handleRedirect(uri: Uri) {
        val error = uri.getQueryParameter("error")
        if (error != null) {
            _login.value = LoginUi(error = "Вход отклонён: ${uri.getQueryParameter("error_description") ?: error}")
            return
        }
        val code = uri.getQueryParameter("code")
        val verifier = store.pkceVerifier
        if (code == null || verifier == null || uri.getQueryParameter("state") != store.pkceState) {
            _login.value = LoginUi(error = "Неожиданный ответ при входе, попробуйте ещё раз")
            return
        }
        store.pkceVerifier = null
        store.pkceState = null
        runLogin { api.exchangeCode(store.clientId, code, verifier, REDIRECT_URI) }
    }

    fun passwordLogin(clientId: String, username: String, password: String) {
        store.clientId = clientId
        if (store.clientId.isEmpty() || username.isBlank() || password.isEmpty()) {
            _login.value = LoginUi(error = "Заполните Client ID, имя пользователя и пароль")
            return
        }
        runLogin { api.passwordLogin(store.clientId, username.trim(), password) }
    }

    private fun runLogin(getTokens: () -> Tokens) {
        _login.value = LoginUi(busy = true)
        viewModelScope.launch {
            try {
                val cfg = withContext(Dispatchers.IO) {
                    val tokens = getTokens()
                    store.accessToken = tokens.access
                    api.config(tokens.access)
                }
                store.userId = cfg.userId
                store.username = cfg.username
                jwt = cfg.jwt
                _login.value = LoginUi()
                _screen.value = Screen.Games
                socket.connect(cfg.jwt, store.deviceId, VERSION)
                loadGames()
            } catch (e: Exception) {
                store.clearSession()
                _login.value = LoginUi(error = errorText(e))
            }
        }
    }

    /** Токен уже есть (запуск приложения): получаем JWT для сокета и список партий. */
    private fun bootstrap() {
        val token = store.accessToken ?: return
        viewModelScope.launch {
            try {
                val cfg = withContext(Dispatchers.IO) { api.config(token) }
                store.userId = cfg.userId
                store.username = cfg.username
                jwt = cfg.jwt
                socket.connect(cfg.jwt, store.deviceId, VERSION)
                loadGames()
            } catch (e: ApiException) {
                if (e.code == 401 || e.code == 403) logout("Сессия истекла, войдите снова") else {
                    _games.value = GamesUi(error = errorText(e))
                }
            } catch (e: Exception) {
                _games.value = GamesUi(error = errorText(e))
            }
        }
    }

    fun logout(note: String? = null) {
        socket.close()
        jwt = null
        session = null
        tickJob?.cancel()
        socketReady = false
        store.clearSession()
        _game.value = null
        _games.value = GamesUi()
        _screen.value = Screen.Login
        if (note != null) _login.value = LoginUi(error = note)
    }

    private fun errorText(e: Exception): String = when (e) {
        is ApiException -> if (e.code == 0) e.message.orEmpty() else "${e.message} (HTTP ${e.code})"
        else -> "Нет связи с сервером: ${e.message ?: e.javaClass.simpleName}"
    }

    // ------------------------------------------------------------------ список партий

    fun loadGames() {
        val token = store.accessToken ?: return
        _games.value = _games.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { api.overview(token, store.userId) }
                _games.value = GamesUi(games = list)
            } catch (e: ApiException) {
                if (e.code == 401) logout("Сессия истекла, войдите снова")
                else _games.value = _games.value.copy(loading = false, error = errorText(e))
            } catch (e: Exception) {
                _games.value = _games.value.copy(loading = false, error = errorText(e))
            }
        }
    }

    // ------------------------------------------------------------------ партия

    fun openGame(id: Long) {
        session = GameSession(id)
        pending = -1
        awaitingMove = false
        _screen.value = Screen.Game(id)
        publish()
        socket.send(Wire.gameConnect(id))
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                publish()
            }
        }
    }

    fun closeGame() {
        val s = session
        if (s != null) socket.send(Wire.gameDisconnect(s.gameId))
        session = null
        tickJob?.cancel()
        moveTimeoutJob?.cancel()
        _game.value = null
        _screen.value = Screen.Games
        loadGames()
    }

    private fun handleReady() {
        socketReady = true
        session?.let { socket.send(Wire.gameConnect(it.gameId)) }
        publish()
    }

    private fun handleDisconnected(reason: String) {
        Log.d(TAG, "socket down: $reason")
        socketReady = false
        publish()
    }

    private fun handleEvent(name: String, data: JsonElement) {
        val s = session ?: return
        val prefix = "game/${s.gameId}/"
        if (!name.startsWith(prefix)) return
        when (val kind = name.removePrefix(prefix)) {
            "gamedata" -> {
                val d = Wire.parseGameData(data) ?: return
                s.applyGameData(d)
                pending = -1
                awaitingMove = false
            }
            "move" -> {
                val ev = Wire.parseMoveEvent(data) ?: return
                when (s.applyMove(ev.moveNumber, ev.x, ev.y)) {
                    GameSession.MoveResult.APPLIED -> {
                        pending = -1
                        awaitingMove = false
                    }
                    GameSession.MoveResult.DESYNC -> resync()
                    GameSession.MoveResult.IGNORED -> Unit
                }
            }
            "clock" -> Wire.parseClock(data)?.let { s.applyClock(it, System.currentTimeMillis()) }
            "phase" -> {
                Wire.parsePhase(data)?.let { s.applyPhase(it) }
                pending = -1
                resync() // подтянуть итог партии / список снятых камней
            }
            "removed_stones" -> Wire.parseRemovedEvent(data)?.let { s.applyRemoved(it) }
            "undo_accepted", "reset" -> resync()
            else -> Log.d(TAG, "ignored event: $kind")
        }
        publish()
    }

    /** Запросить у сервера свежее состояние партии (не чаще раза в 1,5 с). */
    private fun resync() {
        val s = session ?: return
        val now = System.currentTimeMillis()
        if (now - lastResyncMs < 1500) return
        lastResyncMs = now
        socket.send(Wire.gameConnect(s.gameId))
    }

    fun onBoardTap(x: Int, y: Int) {
        val s = session ?: return
        if (!s.loaded) return
        val me = s.myColor(store.userId)
        when {
            s.phase == "play" && s.isMyTurn(store.userId) && !awaitingMove -> {
                when (s.board.check(x, y, s.toPlay)) {
                    PlayCheck.OK -> pending = s.board.index(x, y)
                    PlayCheck.OCCUPIED -> say("Эта точка занята")
                    PlayCheck.KO -> say("Здесь нельзя: ко")
                    PlayCheck.SUICIDE -> say("Здесь нельзя: самоубийство")
                    PlayCheck.OFF_BOARD -> Unit
                }
            }
            s.phase == "stone removal" && me != null -> {
                val group = s.removalGroup(x, y)
                if (group.isNotEmpty()) {
                    val mark = !s.isRemoved(x, y)
                    val stones = group.joinToString("") { Coord.encode(it % s.board.width, it / s.board.width) }
                    s.applyRemoved(RemovedEvent(mark, stones, null))
                    socket.send(Wire.removedSet(s.gameId, mark, stones))
                }
            }
        }
        publish()
    }

    fun confirmMove() {
        val s = session ?: return
        val p = pending
        if (p < 0 || !s.isMyTurn(store.userId) || awaitingMove) return
        val x = p % s.board.width
        val y = p / s.board.width
        sendMove(s, x, y)
    }

    fun pass() {
        val s = session ?: return
        if (!s.isMyTurn(store.userId) || awaitingMove) return
        pending = -1
        sendMove(s, -1, -1)
    }

    private fun sendMove(s: GameSession, x: Int, y: Int) {
        if (!socket.send(Wire.move(s.gameId, x, y))) {
            say("Нет соединения с сервером")
            return
        }
        awaitingMove = true
        publish()
        moveTimeoutJob?.cancel()
        moveTimeoutJob = viewModelScope.launch {
            delay(6000)
            if (awaitingMove) {
                awaitingMove = false
                pending = -1
                say("Сервер не подтвердил ход — обновляю партию")
                lastResyncMs = 0
                resync()
                publish()
            }
        }
    }

    fun resign() {
        val s = session ?: return
        if (s.myColor(store.userId) == null || s.phase != "play") return
        socket.send(Wire.resign(s.gameId))
    }

    fun acceptRemoval() {
        val s = session ?: return
        if (s.phase != "stone removal" || s.myColor(store.userId) == null) return
        socket.send(Wire.removedAccept(s.gameId, s.removedRaw))
        say("Вы приняли результат подсчёта")
    }

    // ------------------------------------------------------------------ снимок для интерфейса

    private fun publish() {
        val s = session ?: return
        val d = s.data
        if (d == null) {
            _game.value = GameUi(
                gameId = s.gameId,
                status = if (socketReady) "Загрузка партии…" else "Подключение к серверу…",
            )
            return
        }
        val me = s.myColor(store.userId)
        val now = System.currentTimeMillis()
        val myTurn = s.isMyTurn(store.userId)
        val winner = when (d.winnerId) {
            null -> null
            d.black?.id -> d.black?.name
            d.white?.id -> d.white?.name
            else -> null
        }
        val status = when {
            !socketReady -> "Нет соединения, переподключаюсь…"
            s.phase == "finished" ->
                "Партия окончена" + (s.outcome?.let { ": $it" } ?: "") + (winner?.let { ". Победил $it" } ?: "")
            s.phase == "stone removal" ->
                if (me != null) "Подсчёт: нажимайте на мёртвые камни, затем «Принять»" else "Идёт подсчёт"
            s.phase != "play" -> "Фаза: ${s.phase}"
            me == null -> "Вы наблюдаете за партией"
            awaitingMove -> "Отправляю ход…"
            myTurn -> "Ваш ход"
            else -> "Ход соперника"
        }
        _game.value = GameUi(
            gameId = s.gameId,
            loaded = true,
            title = d.name,
            width = d.width,
            height = d.height,
            cells = s.board.snapshot(),
            lastMove = s.board.lastMove,
            removed = s.removed,
            pending = if (myTurn) pending else -1,
            blackName = label(d.black?.name, d.black?.rank),
            whiteName = label(d.white?.name, d.white?.rank),
            blackCaptures = s.board.captures[0],
            whiteCaptures = s.board.captures[1],
            blackClock = clockText(s, Stone.BLACK, now),
            whiteClock = clockText(s, Stone.WHITE, now),
            toPlay = s.toPlay,
            myColor = me,
            phase = s.phase,
            moveCount = s.moveCount,
            status = status,
            canMove = socketReady && myTurn && !awaitingMove,
            canRemove = socketReady && s.phase == "stone removal" && me != null,
        )
    }

    private fun label(name: String?, rank: Double?): String {
        val n = name ?: "?"
        val r = Wire.rankLabel(rank)
        return if (r.isEmpty()) n else "$n [$r]"
    }

    private fun clockText(s: GameSession, color: Stone, now: Long): String? {
        val pc = s.playerClock(color) ?: return null
        val main = s.remainingSeconds(color, now) ?: return null
        val shown = if (main <= 0.0 && pc.periodTime != null) pc.periodTime!! else main
        val total = shown.toLong()
        val text = if (total >= 3600) {
            "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
        } else {
            "%d:%02d".format(total / 60, total % 60)
        }
        val periods = pc.periods
        return if (periods != null && periods > 0) "$text (${periods}п)" else text
    }

    override fun onCleared() {
        socket.release()
        super.onCleared()
    }
}
