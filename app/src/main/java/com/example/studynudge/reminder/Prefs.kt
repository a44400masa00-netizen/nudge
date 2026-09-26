package com.example.studynudge.reminder

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * アプリの状態を保存するためのラッパー。
 * SharedPreferencesに全ての状態（今日の日付、休み中かどうか、警告の回数など）を保持する。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("study_reminder_prefs", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN)

    // --- ユーザー設定 ---
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    var targetPackage: String?
        get() = sp.getString("target_package", null)
        set(value) = sp.edit().putString("target_package", value).apply()

    var targetAppLabel: String?
        get() = sp.getString("target_app_label", null)
        set(value) = sp.edit().putString("target_app_label", value).apply()

    /** 登録済みの自宅Wi-FiのSSID一覧（最大5件） */
    var homeSsids: MutableSet<String>
        get() = HashSet(sp.getStringSet("home_ssids", emptySet()) ?: emptySet())
        set(value) = sp.edit().putStringSet("home_ssids", value).apply()

    companion object {
        const val MAX_HOME_SSIDS = 5

        fun formatMinutes(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return String.format(Locale.JAPAN, "%02d:%02d", h, m)
        }
    }

    /** SSIDを追加登録する。既に5件登録済みなら false を返す */
    fun addHomeSsid(ssid: String): Boolean {
        val current = homeSsids
        if (current.contains(ssid)) return true
        if (current.size >= MAX_HOME_SSIDS) return false
        current.add(ssid)
        homeSsids = current
        return true
    }

    fun removeHomeSsid(ssid: String) {
        val current = homeSsids
        current.remove(ssid)
        homeSsids = current
    }

    // --- 実行時の状態 ---
    var lastResetDate: String?
        get() = sp.getString("last_reset_date", null)
        set(value) = sp.edit().putString("last_reset_date", value).apply()

    /** 通知を開始する時刻（0:00からの分数。例: 18:00なら1080） */
    var windowStartMinutes: Int
        get() = sp.getInt("window_start_minutes", Constants.DEFAULT_WINDOW_START_MINUTES)
        set(value) = sp.edit().putInt("window_start_minutes", value).apply()

    /** 通知を終了する時刻（0:00からの分数。例: 23:00なら1380） */
    var windowEndMinutes: Int
        get() = sp.getInt("window_end_minutes", Constants.DEFAULT_WINDOW_END_MINUTES)
        set(value) = sp.edit().putInt("window_end_minutes", value).apply()

    var restedToday: Boolean
        get() = sp.getBoolean("rested_today", false)
        set(value) = sp.edit().putBoolean("rested_today", value).apply()

    /** 0=Study通知段階でまだ警告なし。1以降=警告のエスカレーション回数 */
    var warningCount: Int
        get() = sp.getInt("warning_count", 0)
        set(value) = sp.edit().putInt("warning_count", value).apply()

    /** その日のサイクル（Study通知）が既に開始しているか */
    var cycleActive: Boolean
        get() = sp.getBoolean("cycle_active", false)
        set(value) = sp.edit().putBoolean("cycle_active", value).apply()

    /** 今、Studyplus等の対象アプリを利用中（監視サービス稼働中）か */
    var studying: Boolean
        get() = sp.getBoolean("studying", false)
        set(value) = sp.edit().putBoolean("studying", value).apply()

    fun todayString(): String = dateFormat.format(Date())

    /**
     * 日付が変わっていたら、当日限定の状態（休み中フラグ・警告回数など）をリセットする。
     * 全てのエントリーポイント（Worker, Receiver, Service）の先頭で呼ぶこと。
     */
    fun ensureFreshDay() {
        val today = todayString()
        if (lastResetDate != today) {
            lastResetDate = today
            restedToday = false
            warningCount = 0
            cycleActive = false
            studying = false
        }
    }
}
