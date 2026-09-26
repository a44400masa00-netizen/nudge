package com.example.studynudge.reminder

import android.content.Context
import java.util.Calendar

object TimeUtil {

    /** 今がユーザー設定の開始〜終了時刻の間かどうか */
    fun isWithinWindow(context: Context): Boolean {
        val prefs = Prefs(context)
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return nowMinutes in prefs.windowStartMinutes until prefs.windowEndMinutes
    }

    /** 今日の「終了時刻」ちょうどの時刻（ミリ秒）を返す */
    fun todayWindowEndMillis(context: Context): Long {
        val prefs = Prefs(context)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, prefs.windowEndMinutes / 60)
        cal.set(Calendar.MINUTE, prefs.windowEndMinutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 指定した時刻(triggerAtMillis)が今日の終了時刻より後になるかどうか */
    fun isPastWindowEnd(context: Context, triggerAtMillis: Long): Boolean {
        return triggerAtMillis >= todayWindowEndMillis(context)
    }
}
