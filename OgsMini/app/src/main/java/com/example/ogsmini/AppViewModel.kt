package com.example.ogsmini

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ogsmini.core.ChallengeFilter
import com.example.ogsmini.core.ChallengeRow
import com.example.ogsmini.core.ChallengeWire
import com.example.ogsmini.core.Coord
import com.example.ogsmini.core.CreatedChallenge
import com.example.ogsmini.core.GameSession
import com.example.ogsmini.core.GameSummary
import com.example.ogsmini.core.NewChallenge
import com.example.ogsmini.core.PlayCheck
import com.example.ogsmini.core.Pkce
import com.example.ogsmini.core.Ranks
import com.example.ogsmini.core.RemovedEvent
import com.example.ogsmini.core.Seek
import com.example.ogsmini.core.SeekGraph
import com.example.ogsmini.core.SeekMessage
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
    data object Challenges : Screen
    data object CreateChallenge : Screen
    data object Waiting : Screen
}

data class LoginUi(val busy: Boolean = false, val error: String? = null)

data class GamesUi(
    val loading: Boolean = false,
    val games: List<GameSummary> = emptyList(),
    val error: String? = null,
)

/** Список открытых вызовов: уже отфильтрованный и разложенный по вкладкам. */
data class ChallengesUi(
    val connected: Boolean = false,
    val live: List<ChallengeRow> = emptyList(),
    val correspondence: List<ChallengeRow> = emptyList(),
    /** Сколько вызовов скрыто фильтрами. */
    val hiddenCount: Int = 0,
    /** Сколько командных (рэнго) вызовов не показано: такие партии приложение пока не умеет. */
    val rengoHidden: Int = 0,
    val filter: ChallengeFilter = ChallengeFilter(),
    val tab: Int = 0,
    /** Вызов, который сейчас принимается или снимается. */
    val busyId: Long? = null,
    val error: String? = null,
    val myRank: Int = 18,
)

data class CreateUi(
    val form: NewChallenge = NewChallenge(),
    val busy: Boolean = false,
    val error: String? = null,
    val myRank: Int = 18,
)

/** Ожидание соперника для только что созданного вызова. */
data class WaitingUi(
    val challengeId: Long,
    val gameId: Long,
    val summary: String,
    /** Кому адресован вызов; null — открытый вызов. */
    val opponent: String?,
    val seconds: Int = 0,
    val connected: Boolean = true,
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
        private const val VERSION = "0.4"
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

    private val _challenges = MutableStateFlow(
        ChallengesUi(
            filter = ChallengeFilter.fromBits(store.challengeFilterBits),
            tab = store.challengeTab.coerceIn(0, 1),
            myRank = store.userRank,
        )
    )
    val challenges: StateFlow<ChallengesUi> = _challenges.asStateFlow()

    private val _create = MutableStateFlow(
        CreateUi(form = NewChallenge.decode(store.challengeForm) ?: NewChallenge(), myRank = store.userRank)
    )
    val create: StateFlow<CreateUi> = _create.asStateFlow()

    private val _waiting = MutableStateFlow<WaitingUi?>(null)
    val waiting: StateFlow<WaitingUi?> = _waiting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Выбранная тема: null — следовать системной, true — тёмная, false — светлая. */
    private val _darkTheme = MutableStateFlow(store.darkTheme)
    val darkTheme: StateFlow<Boolean?> = _darkTheme.asStateFlow()

    fun setDarkTheme(on: Boolean) {
        store.darkTheme = on
        _darkTheme.value = on
    }

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

    private val graph = SeekGraph()
    private var seekSubscribed = false
    private var keepaliveJob: Job? = null
    private var goneJob: Job? = null
    private var waitingStartedMs = 0L
    private var lastGamesLoadMs = 0L
    private var createFromGames = false

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
                store.userRank = cfg.rank
                jwt = cfg.jwt
                _login.value = LoginUi()
                go(Screen.Games)
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
                store.userRank = cfg.rank
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
        stopKeepalive()
        goneJob?.cancel()
        _waiting.value = null
        _game.value = null
        _games.value = GamesUi()
        go(Screen.Login)
        if (note != null) _login.value = LoginUi(error = note)
    }

    private fun errorText(e: Exception): String = when (e) {
        is ApiException -> if (e.code == 0) e.message.orEmpty() else "${e.message} (HTTP ${e.code})"
        else -> "Нет связи с сервером: ${e.message ?: e.javaClass.simpleName}"
    }

    // ------------------------------------------------------------------ список партий

    fun loadGames() {
        val token = store.accessToken ?: return
        lastGamesLoadMs = System.currentTimeMillis()
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
        go(Screen.Game(id))
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
        go(Screen.Games)
        loadGames()
    }

    private fun handleReady() {
        socketReady = true
        session?.let { socket.send(Wire.gameConnect(it.gameId)) }
        if (seekSubscribed) {
            graph.clear()
            socket.send(ChallengeWire.seekConnect())
        }
        _waiting.value?.let { socket.send(Wire.gameConnect(it.gameId)) }
        publish()
        publishChallenges()
    }

    private fun handleDisconnected(reason: String) {
        Log.d(TAG, "socket down: $reason")
        socketReady = false
        publish()
        publishChallenges()
    }

    private fun handleEvent(name: String, data: JsonElement) {
        when {
            name == "seekgraph/global" -> onSeekGraph(data)
            name == "notification" -> onNotification(data)
            name == "active_game" -> onActiveGame()
            else -> {
                val w = _waiting.value
                if (w != null && name == "game/${w.gameId}/gamedata") onWaitingStarted(w.gameId)
                else handleGameEvent(name, data)
            }
        }
    }

    private fun handleGameEvent(name: String, data: JsonElement) {
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

    // ------------------------------------------------------------------ вызовы

    /** Любое переключение экрана идёт через эту функцию: она заодно подписывает сокет на список вызовов или отписывает. */
    private fun go(next: Screen) {
        _screen.value = next
        updateSeekSubscription()
    }

    private fun updateSeekSubscription() {
        val want = when (_screen.value) {
            is Screen.Challenges, is Screen.CreateChallenge, is Screen.Waiting -> true
            else -> false
        }
        if (want == seekSubscribed) return
        seekSubscribed = want
        graph.clear()
        if (want) {
            if (socketReady) socket.send(ChallengeWire.seekConnect())
        } else {
            socket.send(ChallengeWire.seekDisconnect())
        }
        publishChallenges()
    }

    private fun publishChallenges() {
        val cur = _challenges.value
        val all = graph.rows(store.userId, store.userRank)
        val shown = all.filter { cur.filter.matches(it) }
        _challenges.value = cur.copy(
            connected = socketReady && seekSubscribed,
            live = shown.filter { it.c.live },
            correspondence = shown.filter { !it.c.live },
            hiddenCount = all.size - shown.size,
            rengoHidden = graph.rengoCount(),
            myRank = store.userRank,
        )
    }

    private fun onSeekGraph(data: JsonElement) {
        if (!seekSubscribed) return
        val messages = Seek.parse(data)
        val me = store.userId

        val w = _waiting.value
        if (w != null) {
            val started = messages.firstOrNull { it is SeekMessage.Started && it.challengeId == w.challengeId }
            if (started != null) {
                onWaitingStarted((started as SeekMessage.Started).gameId ?: w.gameId)
                return
            }
            if (messages.any { it is SeekMessage.Remove && it.challengeId == w.challengeId }) scheduleGoneCheck(w)
        } else if (_screen.value is Screen.Challenges) {
            val acceptedMine = messages.any {
                it is SeekMessage.Started && graph[it.challengeId]?.userId == me
            }
            if (acceptedMine) say("Ваш вызов приняли — партия появится в «Мои партии»")
        }
        graph.apply(messages)
        publishChallenges()
    }

    private fun onNotification(data: JsonElement) {
        val rejected = ChallengeWire.parseOfferRejected(data) ?: return
        val w = _waiting.value ?: return
        if (rejected.first != null && rejected.first != w.gameId) return
        stopKeepalive()
        _waiting.value = w.copy(error = "Соперник отклонил вызов" + (rejected.second?.let { ": $it" } ?: ""))
    }

    /** Сервер сообщает о новой партии пользователя: если открыт список партий, обновляем его. */
    private fun onActiveGame() {
        if (_screen.value !is Screen.Games) return
        if (System.currentTimeMillis() - lastGamesLoadMs < 3000) return
        loadGames()
    }

    fun openChallenges() {
        _challenges.value = _challenges.value.copy(error = null, busyId = null)
        go(Screen.Challenges)
    }

    fun closeChallenges() {
        go(Screen.Games)
        loadGames()
    }

    fun setFilter(filter: ChallengeFilter) {
        store.challengeFilterBits = filter.toBits()
        _challenges.value = _challenges.value.copy(filter = filter)
        publishChallenges()
    }

    fun setTab(tab: Int) {
        store.challengeTab = tab
        _challenges.value = _challenges.value.copy(tab = tab)
    }

    fun dismissChallengesError() {
        _challenges.value = _challenges.value.copy(error = null)
    }

    /** Ответ на сбой запроса: при просроченном токене разлогиниваем, иначе возвращаем текст для показа. */
    private fun failText(e: Exception): String {
        if (e is ApiException && e.code == 401) {
            logout("Сессия истекла, войдите снова")
            return ""
        }
        return errorText(e)
    }

    fun acceptChallenge(id: Long) {
        val token = store.accessToken ?: return
        val challenge = graph[id] ?: return
        if (_challenges.value.busyId != null) return
        _challenges.value = _challenges.value.copy(busyId = id, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.acceptChallenge(token, id) }
                _challenges.value = _challenges.value.copy(busyId = null)
                openGame(challenge.gameId)
            } catch (e: Exception) {
                _challenges.value = _challenges.value.copy(busyId = null, error = failText(e))
            }
        }
    }

    /** Снять свой вызов из списка. */
    fun cancelChallenge(id: Long) {
        val token = store.accessToken ?: return
        if (_challenges.value.busyId != null) return
        _challenges.value = _challenges.value.copy(busyId = id, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.cancelChallenge(token, id) }
                graph.apply(listOf(SeekMessage.Remove(id)))
                _challenges.value = _challenges.value.copy(busyId = null)
                publishChallenges()
            } catch (e: ApiException) {
                if (e.code == 404) { // вызова уже нет — это и требовалось
                    graph.apply(listOf(SeekMessage.Remove(id)))
                    _challenges.value = _challenges.value.copy(busyId = null)
                    publishChallenges()
                } else {
                    _challenges.value = _challenges.value.copy(busyId = null, error = failText(e))
                }
            } catch (e: Exception) {
                _challenges.value = _challenges.value.copy(busyId = null, error = failText(e))
            }
        }
    }

    // ---- форма создания

    /** Форму можно открыть и со списка партий, и со списка вызовов; «Назад» возвращает туда, откуда пришли. */
    fun openCreate() {
        createFromGames = _screen.value is Screen.Games
        _create.value = _create.value.copy(error = null, busy = false, myRank = store.userRank)
        go(Screen.CreateChallenge)
    }

    fun backFromCreate() {
        if (createFromGames) {
            go(Screen.Games)
            loadGames()
        } else {
            go(Screen.Challenges)
        }
    }

    fun updateForm(change: (NewChallenge) -> NewChallenge) {
        val cur = _create.value
        _create.value = cur.copy(form = change(cur.form).normalized(), error = null)
    }

    /** Включить или выключить ограничение рангов; при включении предлагаем диапазон вокруг своего ранга. */
    fun setRestrictRank(on: Boolean) {
        val my = store.userRank
        updateForm {
            if (on) {
                it.copy(
                    restrictRank = true,
                    minRank = maxOf(Ranks.MIN, my - 3),
                    maxRank = minOf(Ranks.MAX, my + 3),
                )
            } else {
                it.copy(restrictRank = false)
            }
        }
    }

    fun submitChallenge() {
        val token = store.accessToken ?: return
        val cur = _create.value
        if (cur.busy) return
        val form = cur.form.normalized()
        val problem = form.problem()
        if (problem != null) {
            _create.value = cur.copy(form = form, error = problem)
            return
        }
        _create.value = cur.copy(form = form, busy = true, error = null)
        viewModelScope.launch {
            try {
                val opponent = form.opponent.trim()
                val created = withContext(Dispatchers.IO) {
                    val playerId = if (opponent.isEmpty()) {
                        null
                    } else {
                        api.findPlayer(token, opponent)?.id ?: throw ApiException(0, "Игрок «$opponent» не найден")
                    }
                    api.createChallenge(token, form.requestBody(), playerId)
                }
                store.challengeForm = form.encode()
                // Имя соперника не запоминаем: следующий вызов по умолчанию открытый
                _create.value = _create.value.copy(busy = false, form = _create.value.form.copy(opponent = ""))
                when {
                    form.live -> startWaiting(form, created, opponent.ifEmpty { null })
                    opponent.isEmpty() -> {
                        say("Вызов создан и виден в списке, пока его кто-нибудь не примет")
                        go(Screen.Challenges)
                    }
                    else -> {
                        say("Вызов отправлен игроку $opponent")
                        go(Screen.Challenges)
                    }
                }
            } catch (e: Exception) {
                _create.value = _create.value.copy(busy = false, error = failText(e))
            }
        }
    }

    // ---- ожидание соперника

    private fun startWaiting(form: NewChallenge, created: CreatedChallenge, opponent: String?) {
        stopKeepalive()
        goneJob?.cancel()
        waitingStartedMs = System.currentTimeMillis()
        _waiting.value = WaitingUi(created.challengeId, created.gameId, form.summary(), opponent)
        go(Screen.Waiting)
        socket.send(Wire.gameConnect(created.gameId))
        // Пока вызов быстрой партии ждёт соперника, сервер требует раз в секунду подтверждать, что мы здесь.
        keepaliveJob = viewModelScope.launch {
            while (true) {
                val w = _waiting.value ?: break
                if (w.error != null) break
                socket.send(ChallengeWire.keepalive(w.challengeId, w.gameId))
                _waiting.value = w.copy(
                    seconds = ((System.currentTimeMillis() - waitingStartedMs) / 1000).toInt(),
                    connected = socketReady,
                )
                delay(1000)
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /** Вызов исчез из списка: если партия сразу не началась, значит, сервер его снял. */
    private fun scheduleGoneCheck(w: WaitingUi) {
        goneJob?.cancel()
        goneJob = viewModelScope.launch {
            delay(3000)
            val cur = _waiting.value
            if (cur != null && cur.challengeId == w.challengeId && cur.error == null) {
                stopKeepalive()
                _waiting.value = cur.copy(error = "Сервер снял вызов: его отменили или пропала связь. Создайте вызов заново.")
            }
        }
    }

    private fun onWaitingStarted(gameId: Long) {
        stopKeepalive()
        goneJob?.cancel()
        _waiting.value = null
        openGame(gameId)
    }

    /** Отмена ожидания (кнопка или «Назад»): снимаем вызов и возвращаемся к списку. */
    fun cancelWaiting() {
        val w = _waiting.value ?: return
        stopKeepalive()
        goneJob?.cancel()
        _waiting.value = null
        socket.send(Wire.gameDisconnect(w.gameId))
        go(Screen.Challenges)
        val token = store.accessToken ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.cancelChallenge(token, w.challengeId) }
            } catch (e: ApiException) {
                if (e.code != 404) {
                    _challenges.value = _challenges.value.copy(error = "Не удалось снять вызов: ${failText(e)}")
                }
            } catch (e: Exception) {
                _challenges.value = _challenges.value.copy(error = "Не удалось снять вызов: ${failText(e)}")
            }
        }
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
