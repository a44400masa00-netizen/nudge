package com.example.studynudge.reminder

import android.app.Notification
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 「勉強開始」「開始する」「頑張ってみる」が押された後に起動するサービス。
 * 対象アプリ(target app)が画面の前面に出ているかどうかを定期的に確認し、
 * 3分以上他のアプリに居続けたらEngine.onLeftTooLong()を呼んで自分は停止する。
 *
 * 使用状況へのアクセス（PACKAGE_USAGE_STATS）が許可されていないと、
 * 前面アプリの判定ができないため実質的に何もしない。
 */
class ForegroundMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var awayStartMillis: Long? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, Constants.MONITOR_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        val notif: Notification = NotificationCompat.Builder(this, Constants.CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("学習状況を見守っています")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(Constants.NOTIF_ID_MONITOR_SERVICE, notif)
        awayStartMillis = null
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun poll() {
        val prefs = Prefs(this)
        prefs.ensureFreshDay()
        if (!prefs.studying || prefs.restedToday) {
            stopSelf()
            return
        }
        if (!TimeUtil.isWithinWindow(this)) {
            // 23:00を過ぎたら監視も終了する
            stopSelf()
            return
        }

        val target = prefs.targetPackage
        val current = getForegroundPackage()

        if (target == null || current == null) {
            // 判定不能（権限未許可など）。何もせず様子を見る。
            return
        }

        if (current == target) {
            awayStartMillis = null
            return
        }

        val start = awayStartMillis
        if (start == null) {
            awayStartMillis = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - start >= Constants.LEAVE_TOLERANCE_MS) {
            Engine.onLeftTooLong(this)
            stopSelf()
        }
    }

    /** 直近のUsageEventsから、今前面にあるアプリのパッケージ名を推定する */
    private fun getForegroundPackage(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val begin = end - 30_000L
        val events = usm.queryEvents(begin, end)
        var lastPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPackage = event.packageName
            }
        }
        return lastPackage
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
