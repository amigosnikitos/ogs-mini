package com.example.ogsmini.net

import com.example.ogsmini.core.ChallengeWire
import com.example.ogsmini.core.CreatedChallenge
import com.example.ogsmini.core.GameSummary
import com.example.ogsmini.core.PlayerHit
import com.example.ogsmini.core.Tokens
import com.example.ogsmini.core.UiConfig
import com.example.ogsmini.core.Wire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : Exception(message)

/** REST и OAuth2. Все методы блокирующие — вызывать из Dispatchers.IO. */
class OgsApi(private val http: OkHttpClient) {

    private val base = "https://online-go.com"
    private val jsonType = "application/json".toMediaType()

    /** Шаг 2 входа через браузер: меняем code на токены (PKCE, без client_secret). */
    fun exchangeCode(clientId: String, code: String, verifier: String, redirectUri: String): Tokens =
        postToken(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
                .build()
        )

    /** Запасной вход по логину и паролю (grant_type=password), если приложение зарегистрировано с таким типом. */
    fun passwordLogin(clientId: String, username: String, password: String): Tokens =
        postToken(
            FormBody.Builder()
                .add("grant_type", "password")
                .add("username", username)
                .add("password", password)
                .add("client_id", clientId)
                .build()
        )

    /** Профиль и JWT для realtime-сокета. */
    fun config(token: String): UiConfig =
        Wire.parseConfig(getJson("/api/v1/ui/config", token))
            ?: throw ApiException(0, "В ответе /ui/config нет user_jwt или user.id")

    /** Мои активные партии. */
    fun overview(token: String, myId: Long): List<GameSummary> =
        Wire.parseOverview(getJson("/api/v1/ui/overview", token), myId)

    // ---------------------------------------------------------------- вызовы

    /** Создать открытый вызов, а при [playerId] — вызов конкретному игроку. Адреса те же, что у сайта. */
    fun createChallenge(token: String, body: JsonObject, playerId: Long?): CreatedChallenge {
        val path = if (playerId == null) "/api/v1/challenges" else "/api/v1/players/$playerId/challenge"
        val res = call("POST", path, token, body)
        return res?.let { ChallengeWire.parseCreated(it) }
            ?: throw ApiException(0, "Вызов создан, но ответ сервера непонятен: ${res.toString().take(300)}")
    }

    /** Принять чужой открытый вызов; затем можно открывать партию по `game_id` из списка. */
    fun acceptChallenge(token: String, challengeId: Long) {
        call("POST", "/api/v1/challenges/$challengeId/accept", token, buildJsonObject { })
    }

    /** Снять свой вызов. На сайте есть два адреса (список и окно ожидания) — пробуем оба. */
    fun cancelChallenge(token: String, challengeId: Long) {
        try {
            call("DELETE", "/api/v1/challenges/$challengeId", token, null)
        } catch (e: ApiException) {
            if (e.code != 403 && e.code != 404 && e.code != 405) throw e
            call("DELETE", "/api/v1/me/challenges/$challengeId", token, null)
        }
    }

    /** Найти игрока по имени (для вызова конкретному игроку). */
    fun findPlayer(token: String, username: String): PlayerHit? {
        val q = URLEncoder.encode(username.trim(), "UTF-8")
        val res = call("GET", "/api/v1/players?username=$q", token, null) ?: return null
        return ChallengeWire.parsePlayerHit(res, username)
    }

    /**
     * Запрос к REST API. В тексте ошибки остаются метод, адрес и начало ответа сервера:
     * по ним понятно, что именно не понравилось серверу.
     */
    private fun call(method: String, path: String, token: String, body: JsonElement?): JsonElement? {
        val builder = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        when (method) {
            "POST" -> builder.post((body?.toString() ?: "").toRequestBody(jsonType))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "$method ${path.substringBefore('?')} — ${Wire.describeError(text, resp.code, 400)}")
            }
            return if (text.isBlank()) null else Wire.parseJson(text)
        }
    }

    private fun postToken(body: RequestBody): Tokens {
        val req = Request.Builder()
            .url("$base/oauth2/token/")
            .header("Accept", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, Wire.describeError(text, resp.code))
            return Wire.parseJson(text)?.let { Wire.parseTokens(it) }
                ?: throw ApiException(resp.code, "Не удалось разобрать ответ сервера авторизации")
        }
    }

    private fun getJson(path: String, token: String): JsonElement {
        val req = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, Wire.describeError(text, resp.code))
            return Wire.parseJson(text) ?: throw ApiException(resp.code, "Сервер вернул не JSON")
        }
    }
}
