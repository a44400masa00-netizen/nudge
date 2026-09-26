package com.example.studynudge.reminder

object Constants {
    // 通知チャンネル
    const val CHANNEL_STUDY = "study_channel"
    const val CHANNEL_MONITOR = "monitor_channel"

    // 通知ID
    const val NOTIF_ID_STUDY = 1001
    const val NOTIF_ID_WARNING = 1002
    const val NOTIF_ID_MONITOR_SERVICE = 1003

    // アラームのリクエストコード
    const val ALARM_REQ_RESEND_STUDY = 2001
    const val ALARM_REQ_SNOOZE_STUDY = 2002
    const val ALARM_REQ_SNOOZE_WARNING = 2003

    // Intentアクション（通知ボタン・アラーム共通）
    const val ACTION_STUDY_START = "com.example.studynudge.reminder.ACTION_STUDY_START"
    const val ACTION_STUDY_REST = "com.example.studynudge.reminder.ACTION_STUDY_REST"
    const val ACTION_STUDY_SNOOZE = "com.example.studynudge.reminder.ACTION_STUDY_SNOOZE"
    const val ACTION_STUDY_RESEND = "com.example.studynudge.reminder.ACTION_STUDY_RESEND"

    const val ACTION_WARNING_START = "com.example.studynudge.reminder.ACTION_WARNING_START"
    const val ACTION_WARNING_REST = "com.example.studynudge.reminder.ACTION_WARNING_REST"
    const val ACTION_WARNING_SNOOZE = "com.example.studynudge.reminder.ACTION_WARNING_SNOOZE"
    const val ACTION_WARNING_GIVEUP = "com.example.studynudge.reminder.ACTION_WARNING_GIVEUP"
    const val ACTION_WARNING_FIRE = "com.example.studynudge.reminder.ACTION_WARNING_FIRE"

    const val EXTRA_WARNING_COUNT = "extra_warning_count"

    // 時間帯（ユーザーが変更しない場合のデフォルト値。実際の値はPrefsに保存される）
    const val DEFAULT_WINDOW_START_MINUTES = 18 * 60  // 18:00
    const val DEFAULT_WINDOW_END_MINUTES = 23 * 60     // 23:00

    const val RESEND_INTERVAL_MS = 5 * 60 * 1000L      // 5分
    const val SNOOZE_INTERVAL_MS = 60 * 60 * 1000L     // 1時間
    const val LEAVE_TOLERANCE_MS = 3 * 60 * 1000L      // 3分
    const val MONITOR_POLL_INTERVAL_MS = 15 * 1000L    // 監視の確認間隔
}
