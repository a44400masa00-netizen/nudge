package com.example.studynudge

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Trigger { NORMAL, EARLY, NIGHT, TEST }

data class Place(val key: String, val label: String, val lat: Double, val lng: Double, val radius: Int = 150)

object PlaceKeys {
    val ALL: List<Pair<String, String>> = listOf(
        "home" to "自宅",
        "school" to "学校",
        "station" to "最寄り駅",
        "cram" to "塾"
    )
}

data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean)

object Perm {
    fun hasOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    @Suppress("DEPRECATION")
    fun hasUsage(c: Context): Boolean {
        return try {
            val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun hasLocation(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCalendar(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCalendarWrite(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasMic(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotificationListener(c: Context): Boolean =
        com.example.studynudge.voice.AppNotificationListener.isEnabled(c)

    fun hasWriteSettings(c: Context): Boolean = Settings.System.canWrite(c)

    fun hasNotificationPolicy(c: Context): Boolean {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun hasExactAlarm(c: Context): Boolean {
        val am = c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        return android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun isIgnoringBatteryOptimizations(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }
}

data class Situation(
    val nowMillis: Long,
    val placeKey: String?,
    val placeLabel: String?,
    val hasLocation: Boolean,
    val moving: Boolean,
    val speedKmh: Double,
    val hasCalendar: Boolean,
    val events: List<CalEvent>,
    val currentEvent: CalEvent?,
    val nextEvent: CalEvent?,
    val foregroundPackage: String?,
    val foregroundLabel: String?,
    val foregroundCategory: String?,
    val sessionMinutes: Int,
    val todayUsageMinutes: Int?
) {
    fun shouldSkip(ownPackage: String): Boolean {
        if (placeKey == "school" || placeKey == "cram") return true
        if (currentEvent != null) return true
        if (foregroundPackage == ownPackage) return true
        return false
    }

    fun minutesToNext(): Int? = nextEvent?.let { ((it.begin - nowMillis) / 60000L).toInt() }

    private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(ms))

    fun promptText(): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        val dow = arrayOf("日", "月", "火", "水", "木", "金", "土")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val sb = StringBuilder()
        sb.append("日時: ")
            .append(SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date(nowMillis)))
            .append("（").append(dow).append("曜日）")
            .append(hm(nowMillis)).append("\n")

        if (!hasLocation) {
            sb.append("現在地: 取得できていない\n")
        } else {
            if (placeLabel != null) {
                sb.append("現在地: ユーザーが登録した「").append(placeLabel).append("」の近く\n")
            } else {
                sb.append("現在地: 登録した場所（自宅・学校・最寄り駅・塾）のどこからも離れている\n")
            }
            if (moving) {
                sb.append("移動状況: 移動中（時速およそ")
                    .append(String.format(Locale.US, "%.0f", speedKmh)).append("km）\n")
            } else {
                sb.append("移動状況: ほぼ静止している\n")
            }
        }

        if (!hasCalendar) {
            sb.append("今日の予定: カレンダーを読み取れない\n")
        } else if (events.isEmpty()) {
            sb.append("今日の予定: 何もない（予定のない日）\n")
        } else {
            sb.append("今日の予定:\n")
            for (e in events) {
                if (e.allDay) {
                    sb.append("- 終日: ").append(e.title).append("\n")
                } else {
                    sb.append("- ").append(hm(e.begin)).append("〜").append(hm(e.end))
                        .append(" ").append(e.title).append("\n")
                }
            }
            val n = nextEvent
            val m = minutesToNext()
            if (n != null && m != null) {
                sb.append("次の予定: ").append(n.title).append("（あと").append(m).append("分）\n")
            }
        }

        if (foregroundLabel != null) {
            sb.append("今使っているアプリ: ").append(foregroundLabel)
            if (foregroundCategory != null) sb.append("（").append(foregroundCategory).append("）")
            sb.append("\n")
        }
        sb.append("今回のスマホ連続使用: ").append(sessionMinutes).append("分\n")
        if (todayUsageMinutes != null) {
            sb.append("今日のスマホ使用時間の合計: 約").append(todayUsageMinutes).append("分\n")
        }
        return sb.toString()
    }

    fun fallbackMessage(trigger: Trigger): String {
        if (trigger == Trigger.EARLY) return "おはようございます。こんなに早い時間に、何をされているんですか？"
        if (trigger == Trigger.NIGHT) return "夜更かしですか？何をするんですか？ 無理せず、今日やることを決めて取り組みましょう。"
        val m = minutesToNext()
        if (nextEvent != null && m != null) {
            return if (m > 45) {
                "次の予定まで、あと${m}分ほどあります。予定の30分前までは勉強をして、その後は準備をしましょう。"
            } else {
                "そろそろ予定の時間が近づいています。準備を整えて、余裕を持って出かけましょう。"
            }
        }
        if (moving) return "今は移動中のようですね。この時間を使って勉強しましょう。"
        if (hasCalendar && events.isEmpty()) return "今日は予定がないようですね。今日は一日勉強に取り組みましょう。"
        return "スマホを見ているようですね。少しだけ勉強に時間を使ってみませんか？"
    }
}
