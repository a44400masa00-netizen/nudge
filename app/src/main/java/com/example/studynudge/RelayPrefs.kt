package com.example.studynudge

import android.content.Context

class RelayPrefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("relay_prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    var dbUrl: String
        get() = sp.getString("db_url", "") ?: ""
        set(v) { sp.edit().putString("db_url", v).apply() }

    var secret: String
        get() = sp.getString("secret", "") ?: ""
        set(v) { sp.edit().putString("secret", v).apply() }

    fun isConfigured(): Boolean = enabled && dbUrl.isNotBlank() && secret.isNotBlank()
}
