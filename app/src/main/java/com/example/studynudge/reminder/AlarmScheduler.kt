package com.example.studynudge.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 「5分後に再送」「1時間後にスヌーズ」などの単発の未来通知をAlarmManagerで予約するヘルパー。
 * 23:00を過ぎるものは予約しない（絶対に夜11時以降は通知しない、という仕様のため）。
 */
object AlarmScheduler {

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun schedule(context: Context, requestCode: Int, action: String, extraCount: Int?, delayMs: Long): Boolean {
        val triggerAt = System.currentTimeMillis() + delayMs
        if (TimeUtil.isPastWindowEnd(context, triggerAt)) {
            // 23:00を過ぎてしまう場合は何もしない
            return false
        }
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            if (extraCount != null) putExtra(Constants.EXTRA_WARNING_COUNT, extraCount)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = alarmManager(context)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            // 「アラームとリマインダー」権限が許可されていない場合はここに来る。
            // 設定画面で許可するよう促す必要がある。
            return false
        }
        return true
    }

    fun cancel(context: Context, requestCode: Int, action: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply { this.action = action }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager(context).cancel(pi)
    }

    fun scheduleStudyResend(context: Context) {
        schedule(context, Constants.ALARM_REQ_RESEND_STUDY, Constants.ACTION_STUDY_RESEND, null, Constants.RESEND_INTERVAL_MS)
    }

    fun cancelStudyResend(context: Context) {
        cancel(context, Constants.ALARM_REQ_RESEND_STUDY, Constants.ACTION_STUDY_RESEND)
    }

    fun scheduleStudySnooze(context: Context) {
        schedule(context, Constants.ALARM_REQ_SNOOZE_STUDY, Constants.ACTION_STUDY_SNOOZE, null, Constants.SNOOZE_INTERVAL_MS)
    }

    fun scheduleWarningSnooze(context: Context, nextCount: Int) {
        schedule(context, Constants.ALARM_REQ_SNOOZE_WARNING, Constants.ACTION_WARNING_FIRE, nextCount, Constants.SNOOZE_INTERVAL_MS)
    }

    // 「毎日◯時に開始チェック」は、NudgeServiceの毎分の見回り（Engine.tick）が
    // 常に時間帯と在宅を確認しているため不要（専用のアラームを持たない）。
}
