package com.example.ogsmini.net

import com.example.ogsmini.core.GameSummary
import com.example.ogsmini.core.Tokens
import com.example.ogsmini.core.UiConfig
import com.example.ogsmini.core.Wire
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class ApiException(val code: Int, message: String) : Exception(message)

/** REST и OAuth2. Все методы блокирующие — вызывать из Dispatchers.IO. */
class OgsApi(private val http: OkHttpClient) {

    private val base = "https://online-go.com"

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
