package com.example.studynudge.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 予約した時刻になったら呼ばれ、鳴らすサービスを起動する */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Alarms.ACTION_FIRE) return
    Alarms.onFired(context, intent.getIntExtra("id", -1))
    val service = Intent(context, AlarmRingService::class.java)
      .putExtra("kind", intent.getStringExtra("kind"))
      .putExtra("label", intent.getStringExtra("label"))
    context.startForegroundService(service)
  }
}

/** タイマー/アラームの音と振動。通知の「止める」か、声の「止めて」で止まる（2分で自動停止） */
class AlarmRingService : Service() {

  private var ringtone: Ringtone? = null
  private var vibrator: Vibrator? = null
  private val handler = Handler(Looper.getMainLooper())

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    val kind = intent?.getStringExtra("kind").orEmpty()
    val label = intent?.getStringExtra("label").orEmpty()
    val title = if (kind == "alarm") "アラーム" else "タイマー"
    val text = if (label.isNotBlank()) "${label}の時間です" else "時間です"

    createChannel()
    try {
      startForeground(NOTIF_ID, buildNotification(title, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } catch (e: Exception) {
      stopSelf()
      return START_NOT_STICKY
    }
    startRinging()
    VoiceBus.emit("onMessage", mapOf("role" to "model", "text" to "$title: $text"))

    handler.removeCallbacksAndMessages(null)
    handler.postDelayed({ stopSelf() }, MAX_RING_MS)
    return START_NOT_STICKY
  }

  private fun startRinging() {
    try {
      val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      val r = RingtoneManager.getRingtone(this, uri)
      r.audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
      r.isLooping = true
      r.play()
      ringtone = r
    } catch (e: Exception) {
      // 音が出せなくても通知は出る
    }
    try {
      val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
      val v = vm.defaultVibrator
      v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
      vibrator = v
    } catch (e: Exception) {
      // ignore
    }
  }

  private fun createChannel() {
    val ch = NotificationChannel(CHANNEL_ID, "デイリー タイマー/アラーム", NotificationManager.IMPORTANCE_HIGH)
    ch.setSound(null, null) // 音はこのサービスが自分で鳴らす
    ch.enableVibration(false)
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
  }

  private fun buildNotification(title: String, text: String): Notification {
    val open = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
    val openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val stopPi = PendingIntent.getService(
      this, 1,
      Intent(this, AlarmRingService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val stopAction = Notification.Action.Builder(
      Icon.createWithResource(this, android.R.drawable.ic_media_pause), "止める", stopPi
    ).build()
    return Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("デイリー: $title")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setCategory(Notification.CATEGORY_ALARM)
      .setContentIntent(openPi)
      .setFullScreenIntent(openPi, true)
      .addAction(stopAction)
      .setOngoing(true)
      .build()
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    try {
      ringtone?.stop()
    } catch (e: Exception) {
      // ignore
    }
    ringtone = null
    try {
      vibrator?.cancel()
    } catch (e: Exception) {
      // ignore
    }
    vibrator = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  companion object {
    private const val ACTION_STOP = "com.example.studynudge.voice.ALARM_STOP"
    private const val CHANNEL_ID = "daily_alarm"
    private const val NOTIF_ID = 4202
    private const val MAX_RING_MS = 2 * 60 * 1000L

    fun stop(ctx: Context) {
      ctx.stopService(Intent(ctx, AlarmRingService::class.java))
    }
  }
}
