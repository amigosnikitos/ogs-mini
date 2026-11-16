package com.example.ogsmini

import android.content.Context
import com.example.ogsmini.core.ChallengeFilter
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

    /** Ранг игрока по шкале сайта; нужен, чтобы понимать, какие вызовы вам доступны. */
    var userRank: Int
        get() = prefs.getInt("user_rank", 18)
        set(v) {
            prefs.edit().putInt("user_rank", v).apply()
        }

    /** Выбранные фильтры списка вызовов (битовая маска, см. ChallengeFilter). */
    var challengeFilterBits: Int
        get() = prefs.getInt("challenge_filter", ChallengeFilter().toBits())
        set(v) {
            prefs.edit().putInt("challenge_filter", v).apply()
        }

    /** Открытая вкладка списка вызовов: 0 — быстрые, 1 — заочные. */
    var challengeTab: Int
        get() = prefs.getInt("challenge_tab", 0)
        set(v) {
            prefs.edit().putInt("challenge_tab", v).apply()
        }

    /** Последняя использованная форма «Создать вызов» (JSON). */
    var challengeForm: String?
        get() = read("challenge_form")
        set(v) = write("challenge_form", v)

    /** Тёмная тема: null — как в системе (пока пользователь сам не переключил), иначе его выбор. */
    var darkTheme: Boolean?
        get() = if (prefs.contains("dark_theme")) prefs.getBoolean("dark_theme", false) else null
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("dark_theme") else e.putBoolean("dark_theme", v)
            e.apply()
        }

    val deviceId: String
        get() = read("device_id") ?: UUID.randomUUID().toString().also { write("device_id", it) }

    fun clearSession() {
        accessToken = null
        userId = 0L
        username = ""
    }
}
