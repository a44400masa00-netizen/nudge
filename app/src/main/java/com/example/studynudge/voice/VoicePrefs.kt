package com.example.studynudge.voice

import android.content.Context

/**
 * 音声アシスタント（デイリー機能）の設定。VoiceListenerService.PREFS と同じ
 * SharedPreferencesファイルを使う（サービス側は元のコードのまま、キー名で直接読み書きする）。
 */
class VoicePrefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences(VoiceListenerService.PREFS, Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v).apply() }

    var model: String
        get() = sp.getString("model", "gemini-3.8-flash") ?: "gemini-3.8-flash"
        set(v) { sp.edit().putString("model", v).apply() }

    var speak: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) { sp.edit().putBoolean("speak", v).apply() }

    /** auto / cloud / device */
    var brain: String
        get() = sp.getString("brain", "auto") ?: "auto"
        set(v) { sp.edit().putString("brain", v).apply() }

    /** masa / you */
    var callName: String
        get() = sp.getString("call_name", "masa") ?: "masa"
        set(v) { sp.edit().putString("call_name", v).apply() }

    /** polite / casual */
    var tone: String
        get() = sp.getString("tone", "polite") ?: "polite"
        set(v) { sp.edit().putString("tone", v).apply() }

    var musicApp: String
        get() = sp.getString("music_app", "") ?: ""
        set(v) { sp.edit().putString("music_app", v).apply() }

    var spotifyClientId: String
        get() = sp.getString("spotify_client_id", "") ?: ""
        set(v) { sp.edit().putString("spotify_client_id", v).apply() }

    var spotifyClientSecret: String
        get() = sp.getString("spotify_client_secret", "") ?: ""
        set(v) { sp.edit().putString("spotify_client_secret", v).apply() }

    var enabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) { sp.edit().putBoolean("service_enabled", v).apply() }
}
