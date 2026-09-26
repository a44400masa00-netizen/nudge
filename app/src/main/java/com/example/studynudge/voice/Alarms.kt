package com.example.studynudge.voice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * タイマーとアラームを、このアプリ自身で管理する（時計アプリは使わない）。
 * 時刻になると AlarmReceiver → AlarmRingService が鳴らす。端末を再起動すると消える。
 */
object Alarms {

  const val ACTION_FIRE = "com.example.studynudge.voice.ALARM_FIRE"
  private const val PREFS = "daily_alarms"

  private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun load(ctx: Context): MutableList<JSONObject> {
    val arr = try {
      JSONArray(prefs(ctx).getString("list", "[]"))
    } catch (e: Exception) {
      JSONArray()
    }
    return MutableList(arr.length()) { arr.getJSONObject(it) }
  }

  private fun save(ctx: Context, list: List<JSONObject>) {
    val arr = JSONArray()
    list.forEach { arr.put(it) }
    prefs(ctx).edit().putString("list", arr.toString()).apply()
  }

  private fun nextId(ctx: Context): Int {
    val id = prefs(ctx).getInt("next_id", 1)
    prefs(ctx).edit().putInt("next_id", if (id >= 1_000_000) 1 else id + 1).apply()
    return id
  }

  // 同じ id・同じ action の PendingIntent は同一とみなされる（取り消しに使う）
  private fun pending(ctx: Context, id: Int, kind: String, label: String, create: Boolean): PendingIntent? {
    val intent = Intent(ctx, AlarmReceiver::class.java)
      .setAction(ACTION_FIRE)
      .putExtra("id", id)
      .putExtra("kind", kind)
      .putExtra("label", label)
    val flags = PendingIntent.FLAG_IMMUTABLE or
      (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
    return PendingIntent.getBroadcast(ctx, id, intent, flags)
  }

  /** kind: "timer" | "alarm"。設定できなければ理由（日本語）を返す。成功なら null */
  @Synchronized
  fun schedule(ctx: Context, kind: String, triggerAtMillis: Long, label: String): String? {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val allowed = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    if (!allowed) {
      return "正確な時刻のアラームが許可されていません。アプリの設定の「スマホの操作」を確認してください。"
    }
    val id = nextId(ctx)
    val pi = pending(ctx, id, kind, label, true) ?: return "アラームを設定できませんでした。"
    try {
      if (kind == "alarm") {
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
        val show = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), pi)
      } else {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
      }
    } catch (e: SecurityException) {
      return "アラームを設定する権限がありません。"
    }
    val list = load(ctx)
    list.add(JSONObject().put("id", id).put("kind", kind).put("at", triggerAtMillis).put("label", label))
    save(ctx, list)
    return null
  }

  /** 指定した種類の予約をすべて取り消し、取り消した件数を返す */
  @Synchronized
  fun cancelAll(ctx: Context, kind: String): Int {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val list = load(ctx)
    var count = 0
    val keep = ArrayList<JSONObject>()
    for (item in list) {
      if (item.optString("kind") == kind) {
        pending(ctx, item.optInt("id"), kind, item.optString("label"), false)?.let {
          am.cancel(it)
          it.cancel()
        }
        count += 1
      } else {
        keep.add(item)
      }
    }
    save(ctx, keep)
    return count
  }

  @Synchronized
  fun onFired(ctx: Context, id: Int) {
    save(ctx, load(ctx).filter { it.optInt("id") != id })
  }

  /** その時刻の、次に来る日時（今日を過ぎていれば明日） */
  fun nextTimeMillis(hour: Int, minute: Int): Long {
    val cal = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, hour)
      set(Calendar.MINUTE, minute)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
  }
}
