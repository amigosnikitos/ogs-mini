package com.example.ogsmini

import android.content.Context
import java.util.UUID

/** Настройки и токены в SharedPreferences (резервное копирование отключено в манифесте). */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ogs_mini", Context.MODE_PRIVATE)

    private fun read(key: String): String? = prefs.getString(key, null)

    private fun write(key: String, value: String?) {
        val e = prefs.edit()
        if (value == null) e.remove(key) else e.putString(key, value)
        e.apply()
    }

    var clientId: String
        get() = read("client_id").orEmpty()
        set(v) = write("client_id", v.trim())

    var accessToken: String?
        get() = read("access_token")
        set(v) = write("access_token", v)

    var userId: Long
        get() = prefs.getLong("user_id", 0L)
        set(v) {
            prefs.edit().putLong("user_id", v).apply()
        }

    var username: String
        get() = read("username").orEmpty()
        set(v) = write("username", v)

    /** Секретные значения одного захода через браузер (Activity может пересоздаться, пока открыт браузер). */
    var pkceVerifier: String?
        get() = read("pkce_verifier")
        set(v) = write("pkce_verifier", v)

    var pkceState: String?
        get() = read("pkce_state")
        set(v) = write("pkce_state", v)

    val deviceId: String
        get() = read("device_id") ?: UUID.randomUUID().toString().also { write("device_id", it) }

    fun clearSession() {
        accessToken = null
        userId = 0L
        username = ""
    }
}
