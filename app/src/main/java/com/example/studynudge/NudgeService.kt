package com.example.studynudge

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class NudgeService : Service() {

    companion object {
        const val ACTION_TEST = "com.example.studynudge.action.TEST"
        private const val CHANNEL_ID = "study_nudge_watch"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 60_000L

        @Volatile
        var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var collector: SituationCollector
    private lateinit var tracker: LocationTracker
    private lateinit var overlay: OverlayBanner

    @Volatile
    private var busy = false

    @Volatile
    private var sessionStart = 0L
    private var lastNudge = 0L
    private var lastNightNudge = 0L
    private var lastEarlyDay = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> sessionStart = System.currentTimeMillis()
                Intent.ACTION_SCREEN_OFF -> sessionStart = 0L
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                evaluate()
            } catch (e: Exception) {
                prefs.lastError = "内部エラー: ${e.message}"
            }
            try {
                reminderTick()
            } catch (e: Exception) {
                // 「しつこい通知」機能側のエラーはAI催促機能を止めないよう握りつぶす
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * 「しつこい通知」（在宅学習）機能の毎分チェック。画面がロックされていても、
     * 帰宅を取りこぼさないよう isUserActive() のチェックとは独立に毎分実行する。
     * 在宅判定は、登録済みの「自宅」の地図ピン（GPS）との距離で行う。
     */
    private fun reminderTick() {
        val home = prefs.getPlace("home") ?: run {
            com.example.studynudge.reminder.Engine.tick(this, atHome = false)
            return
        }
        val loc = tracker.current()
        val atHome = if (loc == null) {
            false
        } else {
            val res = FloatArray(1)
            android.location.Location.distanceBetween(loc.latitude, loc.longitude, home.lat, home.lng, res)
            res[0] < home.radius.toFloat()
        }
        com.example.studynudge.reminder.Engine.tick(this, atHome)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        collector = SituationCollector(this, prefs)
        tracker = LocationTracker(this)
        overlay = OverlayBanner(this)
        running = true
        prefs.wasRunning = true
        com.example.studynudge.reminder.NotificationHelper.createChannels(this)

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_USER_PRESENT)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (isUserActive()) sessionStart = System.currentTimeMillis()
        handler.postDelayed(tick, 15_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        tracker.start()
        if (intent?.action == ACTION_TEST) {
            handler.post { launch(Trigger.TEST) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
        }
        tracker.stop()
        overlay.hide()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "見守り", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("勉強コーチ")
            .setContentText("あなたの状況を見守っています")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            prefs.lastError = "サービス開始失敗: ${e.message}"
            stopSelf()
        }
    }

    private fun isUserActive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun evaluate() {
        if (busy) return
        if (!isUserActive()) return
        val now = System.currentTimeMillis()
        if (sessionStart == 0L) sessionStart = now
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val sessionMin = ((now - sessionStart) / 60000L).toInt()

        var trigger: Trigger? = null
        if (hour < prefs.earlyHour) {
            // 早朝: 使い始めて1〜5分たったとき（1日1回）
            if (sessionMin in 1..5 && lastEarlyDay != dayKey()) trigger = Trigger.EARLY
        } else if (hour >= prefs.bedtimeHour) {
            // 就寝時刻以降: 60分に1回
            if (now - lastNightNudge >= 60 * 60_000L) trigger = Trigger.NIGHT
        } else {
            if (sessionMin >= 1 && now - lastNudge >= prefs.intervalMin * 60_000L) trigger = Trigger.NORMAL
        }
        if (trigger != null) launch(trigger)
    }

    private fun launch(trigger: Trigger) {
        if (busy) return
        busy = true
        try {
            executor.execute {
                try {
                    doNudge(trigger)
                } catch (e: Exception) {
                    prefs.lastError = "内部エラー: ${e.message}"
                } finally {
                    busy = false
                }
            }
        } catch (e: Exception) {
            busy = false
        }
    }

    /** ワーカースレッドで実行される */
    private fun doNudge(trigger: Trigger) {
        if (!Perm.hasOverlay(this)) {
            prefs.lastError = "「他のアプリの上に表示」が許可されていません"
            return
        }
        val s = collector.collect(tracker, sessionStart)
        if (trigger == Trigger.NORMAL && s.shouldSkip(packageName)) return

        val now = System.currentTimeMillis()
        when (trigger) {
            Trigger.NORMAL -> lastNudge = now
            Trigger.TEST -> lastNudge = now
            Trigger.NIGHT -> lastNightNudge = now
            Trigger.EARLY -> lastEarlyDay = dayKey()
        }

        handler.post { overlay.start() }

        var received = false
        val relayPrefs = RelayPrefs(this)
        if (relayPrefs.isConfigured()) {
            try {
                val text = RelayClient.ask(
                    relayPrefs.dbUrl, relayPrefs.secret, Prompts.SYSTEM, Prompts.user(s, trigger, prefs),
                    timeoutMs = 40_000L, maxTokens = 160
                )
                received = true
                handler.post { overlay.push(text) }
                prefs.lastError = ""
            } catch (e: Exception) {
                prefs.lastError = "パソコンのAIエラー: ${e.message}（Geminiを試します）"
            }
        }

        if (!received) {
            val apiKey = prefs.apiKey
            if (apiKey.isBlank()) {
                if (prefs.lastError.isBlank()) prefs.lastError = "Gemini APIキーが未設定です（定型文を表示）"
            } else {
                try {
                    GeminiClient.stream(apiKey, prefs.model, Prompts.SYSTEM, Prompts.user(s, trigger, prefs)) { chunk ->
                        received = true
                        handler.post { overlay.push(chunk) }
                    }
                    prefs.lastError = ""
                } catch (e: Exception) {
                    prefs.lastError = "AI接続エラー: ${e.message}"
                }
            }
        }
        if (!received) {
            val fb = s.fallbackMessage(trigger)
            handler.post { overlay.push(fb) }
        }
        handler.post { overlay.end() }
    }
}
