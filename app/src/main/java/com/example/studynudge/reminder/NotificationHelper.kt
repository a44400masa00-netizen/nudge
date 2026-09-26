package com.example.studynudge.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(Constants.CHANNEL_STUDY, "Study通知", NotificationManager.IMPORTANCE_HIGH)
            )
            manager.createNotificationChannel(
                NotificationChannel(Constants.CHANNEL_MONITOR, "監視サービス", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int, warningCount: Int? = null): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            if (warningCount != null) putExtra(Constants.EXTRA_WARNING_COUNT, warningCount)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 最初の「Study」通知。 ボタン: 勉強開始 / お休み / 1時間後にまた通知する */
    fun showStudyNotification(context: Context) {
        val notif = NotificationCompat.Builder(context, Constants.CHANNEL_STUDY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Study")
            .setContentText("今日の勉強を始めますか？")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_send, "勉強開始", actionIntent(context, Constants.ACTION_STUDY_START, 10))
            .addAction(android.R.drawable.ic_menu_send, "お休み", actionIntent(context, Constants.ACTION_STUDY_REST, 11))
            .addAction(android.R.drawable.ic_menu_send, "1時間後にまた通知する", actionIntent(context, Constants.ACTION_STUDY_SNOOZE, 12))
            .build()
        NotificationManagerCompat.from(context).notify(Constants.NOTIF_ID_STUDY, notif)
    }

    /**
     * 警告通知。warningCountに応じてタイトルとボタン構成が変わる。
     * 1,2回目: 「開始する / 今日は休む / 1時間後にまた通知する」
     * 3回目以降: 「頑張ってみる / あきらめる / 1時間後に通知する」
     * 4回目以降はタイトルが固定文言になる。
     */
    fun showWarningNotification(context: Context, warningCount: Int) {
        val title: String
        val useNewButtons: Boolean

        when {
            warningCount <= 1 -> {
                title = "勉強アプリから離れているようです。もう少し頑張りましょう。"
                useNewButtons = false
            }
            warningCount == 2 -> {
                title = "休憩が続いているようですね。今日はどうしますか？もう少し頑張ってみますか？"
                useNewButtons = false
            }
            warningCount == 3 -> {
                title = "やるべきことを終えた後の解放感は格別です。明日の自分のために、もう少し頑張ってみませんか？"
                useNewButtons = true
            }
            else -> {
                title = "今日はここまでにしますか？無理せず、続けるかどうか決めてください。"
                useNewButtons = true
            }
        }

        val builder = NotificationCompat.Builder(context, Constants.CHANNEL_STUDY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)

        if (!useNewButtons) {
            builder
                .addAction(android.R.drawable.ic_menu_send, "開始する", actionIntent(context, Constants.ACTION_WARNING_START, 20))
                .addAction(android.R.drawable.ic_menu_send, "今日は休む", actionIntent(context, Constants.ACTION_WARNING_REST, 21))
                .addAction(android.R.drawable.ic_menu_send, "1時間後にまた通知する", actionIntent(context, Constants.ACTION_WARNING_SNOOZE, 22, warningCount))
        } else {
            builder
                .addAction(android.R.drawable.ic_menu_send, "頑張ってみる", actionIntent(context, Constants.ACTION_WARNING_START, 23))
                .addAction(android.R.drawable.ic_menu_send, "あきらめる", actionIntent(context, Constants.ACTION_WARNING_GIVEUP, 24))
                .addAction(android.R.drawable.ic_menu_send, "1時間後に通知する", actionIntent(context, Constants.ACTION_WARNING_SNOOZE, 25, warningCount))
        }

        NotificationManagerCompat.from(context).notify(Constants.NOTIF_ID_WARNING, builder.build())
    }

    /** 「あきらめる」を押した時の最終通知 */
    fun showGiveUpNotification(context: Context) {
        val title = "今日はここまでにしましょう。明日また頑張りましょう。"
        val notif = NotificationCompat.Builder(context, Constants.CHANNEL_STUDY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(Constants.NOTIF_ID_WARNING, notif)
    }

    fun cancelStudyAndWarning(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(Constants.NOTIF_ID_STUDY)
        nm.cancel(Constants.NOTIF_ID_WARNING)
    }
}
