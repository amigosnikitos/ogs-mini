package com.example.ogsmini.net

import com.example.ogsmini.core.Incoming
import com.example.ogsmini.core.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Realtime-соединение с OGS: обычный WebSocket, сообщения — JSON-массивы `[команда, данные]`.
 *
 * Адрес сервера в документации указан противоречиво, поэтому перебираем несколько вариантов
 * и запоминаем тот, который открылся. Колбэки приходят из потоков OkHttp.
 */
class OgsSocket(
    private val http: OkHttpClient,
    private val listener: Listener,
) {
    interface Listener {
        /** Соединение открыто, `authenticate` отправлен — можно подписываться на партии. */
        fun onReady()
        fun onEvent(name: String, data: JsonElement)
        fun onDisconnected(reason: String)
    }

    private val urls = listOf(
        "wss://wsp.online-go.com",
        "wss://online-go.com/ws",
        "wss://online-go.com/socket",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    private var urlIndex = 0
    private var attempt = 0
    private var jwt = ""
    private var deviceId = ""
    private var version = "0.1"
    private var pingJob: Job? = null
    private var retryJob: Job? = null

    @Synchronized
    fun connect(jwt: String, deviceId: String, clientVersion: String) {
        this.jwt = jwt
        this.deviceId = deviceId
        this.version = clientVersion
        wanted = true
        if (ws == null) open()
    }

    fun send(text: String): Boolean = ws?.send(text) ?: false

    @Synchronized
    fun close() {
        wanted = false
        pingJob?.cancel()
        retryJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
    }

    fun release() {
        close()
        scope.cancel()
    }

    @Synchronized
    private fun open() {
        val request = Request.Builder()
            .url(urls[urlIndex])
            .header("Origin", "https://online-go.com")
            .build()
        val opened = AtomicBoolean(false)
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // open() держит блокировку, пока не запишет ws, — так ждём и не теряем первые сообщения
                synchronized(this@OgsSocket) {
                    if (ws !== webSocket) {
                        webSocket.close(1000, null)
                        return
                    }
                }
                opened.set(true)
                attempt = 0
                webSocket.send(Wire.authenticate(jwt, deviceId, version))
                startPing(webSocket)
                listener.onReady()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Wire.parse(text) ?: return
                if (msg is Incoming.Event) listener.onEvent(msg.name, msg.data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropped(webSocket, opened.get(), "соединение закрыто ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
                dropped(webSocket, opened.get(), why)
            }
        })
        ws = socket
    }

    private fun startPing(socket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (ws !== socket) break
                socket.send(Wire.ping(System.currentTimeMillis()))
            }
        }
    }

    @Synchronized
    private fun dropped(socket: WebSocket, wasOpen: Boolean, reason: String) {
        if (ws !== socket) return // устаревший сокет
        ws = null
        pingJob?.cancel()
        if (!wanted) return
        if (!wasOpen) urlIndex = (urlIndex + 1) % urls.size // этот адрес не подошёл — пробуем следующий
        listener.onDisconnected(reason)
        val wait = minOf(15_000L, 1_000L shl minOf(attempt, 4))
        attempt++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            if (wanted && ws == null) open()
        }
    }
}
