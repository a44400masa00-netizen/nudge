package com.example.studynudge.voice

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.util.Locale

/**
 * AI の返事に含まれる操作 [[ACTION:名前 {...}]] を取り出して、スマホの操作として実行する。
 * 成否は端末で確認し、失敗したときは AI の文章ではなく、失敗の理由を読み上げる。
 */
object PhoneActions {

  private const val SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
  private const val CHANNEL_ID = "daily_actions"

  private val TAG_REGEX = Regex("\\[\\[ACTION:([a-z_]+)\\s*(\\{.*?\\})?\\s*\\]\\]", RegexOption.DOT_MATCHES_ALL)

  private class Outcome(val ok: Boolean, val message: String, val note: String? = null)

  private fun ok(note: String? = null) = Outcome(true, "ok", note)
  private fun fail(message: String) = Outcome(false, message)

  /** 返事から操作を取り除き、実行して、読み上げる文章を返す */
  fun process(ctx: Context, reply: String): String {
    val matches = TAG_REGEX.findAll(reply).toList()
    if (matches.isEmpty()) return reply.trim()

    val text = TAG_REGEX.replace(reply, "").trim()
    val failures = ArrayList<String>()
    val notes = ArrayList<String>()

    for (m in matches) {
      val name = m.groupValues[1]
      val args = try {
        JSONObject(m.groupValues[2].ifBlank { "{}" })
      } catch (e: Exception) {
        JSONObject()
      }
      val outcome = try {
        execute(ctx, name, args)
      } catch (e: SecurityException) {
        fail("この操作の許可がありません。アプリの設定の「スマホの操作」を確認してください。")
      } catch (e: Throwable) {
        fail("操作に失敗しました。")
      }
      if (!outcome.ok) failures.add(outcome.message) else outcome.note?.let { notes.add(it) }
    }

    if (failures.isNotEmpty()) return failures.joinToString(" ")
    val spoken = (listOf(text) + notes).filter { it.isNotBlank() }.joinToString(" ")
    return spoken.ifBlank { "はい、やりました。" }
  }

  private fun execute(ctx: Context, name: String, args: JSONObject): Outcome = when (name) {
    "set_timer" -> setTimer(ctx, args)
    "set_alarm" -> setAlarm(ctx, args)
    "show_alarms" -> showAlarms(ctx)
    "add_calendar_event" -> addCalendarEvent(ctx, args)
    "compose_email" -> composeEmail(ctx, args)
    "open_google_home" -> openGoogleHome(ctx)
    "flashlight" -> flashlight(ctx, args.optBoolean("on", true))
    "volume" -> volume(ctx, args)
    "brightness" -> brightness(ctx, args)
    "battery_saver" -> batterySaver(ctx, args.optBoolean("on", true))
    "do_not_disturb" -> doNotDisturb(ctx, args.optBoolean("on", true))
    "ringer_mode" -> ringerMode(ctx, args.optString("mode", "normal"))
    "open_app" -> openApp(ctx, args.optString("name", ""))
    "play_music" -> playMusic(ctx, args)
    "media" -> mediaControl(ctx, args.optString("command", ""))
    "open_settings" -> openSettings(ctx, args.optString("screen", "other"))
    else -> fail("その操作にはまだ対応していません。")
  }

  // ---- タイマー・アラーム（Android標準の時計アプリに設定する） -----------------------

  private fun setTimer(ctx: Context, args: JSONObject): Outcome {
    val seconds = args.optLong("seconds", 0L)
    if (seconds < 1 || seconds > 24 * 3600L) return fail("タイマーの時間が分かりませんでした。")
    val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
      putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds.toInt())
      putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, args.optString("label", ""))
      putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
      ctx.startActivity(intent)
      ok("時計アプリにタイマーをセットしました。")
    } catch (e: Exception) {
      fail("時計アプリが見つからず、タイマーを設定できませんでした。")
    }
  }

  private fun setAlarm(ctx: Context, args: JSONObject): Outcome {
    val hour = args.optInt("hour", -1)
    val minute = args.optInt("minute", 0)
    if (hour !in 0..23 || minute !in 0..59) return fail("アラームの時刻が分かりませんでした。")
    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
      putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
      putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
      putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, args.optString("label", ""))
      putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
      ctx.startActivity(intent)
      ok("時計アプリにアラームをセットしました。")
    } catch (e: Exception) {
      fail("時計アプリが見つからず、アラームを設定できませんでした。")
    }
  }

  /** 「タイマーを止めて」「アラームを消して」など。標準の時計アプリでは個別の取り消しを
   *  プログラムから行えないため、一覧を開いて手動で止めてもらう */
  private fun showAlarms(ctx: Context): Outcome {
    val intent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      ctx.startActivity(intent)
      ok("時計アプリを開きました。そこで止めたり消したりしてください。")
    } catch (e: Exception) {
      fail("時計アプリを開けませんでした。")
    }
  }

  // ---- カレンダー（Googleカレンダーと同期される端末のカレンダーに直接書き込む） --------

  private fun addCalendarEvent(ctx: Context, args: JSONObject): Outcome {
    if (ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      return fail("カレンダーへの書き込みが許可されていません。アプリの設定を確認してください。")
    }
    val title = args.optString("title", "").ifBlank { return fail("予定のタイトルが分かりませんでした。") }
    val year = args.optInt("year", -1)
    val month = args.optInt("month", -1) // 1-12
    val day = args.optInt("day", -1)
    val hour = args.optInt("hour", -1)
    val minute = args.optInt("minute", 0)
    if (year < 0 || month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) {
      return fail("予定の日時が分かりませんでした。")
    }
    val cal = java.util.Calendar.getInstance()
    cal.set(year, month - 1, day, hour, minute, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val startMillis = cal.timeInMillis
    val durationMinutes = args.optInt("duration_minutes", 60).coerceIn(5, 24 * 60)
    val endMillis = startMillis + durationMinutes * 60_000L

    val calId = findWritableCalendarId(ctx) ?: return fail("書き込めるカレンダーが見つかりませんでした。端末にGoogleアカウントが設定されているか確認してください。")

    return try {
      val values = android.content.ContentValues().apply {
        put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
        put(android.provider.CalendarContract.Events.TITLE, title)
        put(android.provider.CalendarContract.Events.DTSTART, startMillis)
        put(android.provider.CalendarContract.Events.DTEND, endMillis)
        put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
      }
      val uri = ctx.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
      if (uri != null) ok("カレンダーに「$title」を追加しました。") else fail("カレンダーへの追加に失敗しました。")
    } catch (e: Exception) {
      fail("カレンダーへの追加に失敗しました。")
    }
  }

  private fun findWritableCalendarId(ctx: Context): Long? {
    val proj = arrayOf(
      android.provider.CalendarContract.Calendars._ID,
      android.provider.CalendarContract.Calendars.ACCOUNT_TYPE,
      android.provider.CalendarContract.Calendars.IS_PRIMARY,
      android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
    )
    val cursor = ctx.contentResolver.query(android.provider.CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)
    var best: Long? = null
    var bestScore = -1
    cursor?.use { c ->
      while (c.moveToNext()) {
        val access = c.getInt(3)
        if (access < android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
        val isGoogle = c.getString(1) == "com.google"
        val isPrimary = c.getInt(2) == 1
        val score = (if (isGoogle) 2 else 0) + (if (isPrimary) 1 else 0)
        if (score > bestScore) {
          bestScore = score
          best = c.getLong(0)
        }
      }
    }
    return best
  }

  // ---- メール（Gmailを含む、端末のメールアプリで作成画面を開く） ----------------------

  private fun composeEmail(ctx: Context, args: JSONObject): Outcome {
    val to = args.optString("to", "")
    val subject = args.optString("subject", "")
    val body = args.optString("body", "")
    val intent = Intent(Intent.ACTION_SENDTO).apply {
      data = Uri.parse("mailto:" + Uri.encode(to).replace("%40", "@"))
      if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
      if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
      ctx.startActivity(intent)
      ok("メールの作成画面を開きました。")
    } catch (e: Exception) {
      fail("メールアプリを開けませんでした。")
    }
  }

  // ---- Google Home（アプリを開くところまで。個別の家電操作は非対応） -----------------

  private fun openGoogleHome(ctx: Context): Outcome {
    val intent = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.chromecast.app")
      ?: AppFinder.find(ctx, "home")?.let { ctx.packageManager.getLaunchIntentForPackage(it.packageName) }
    if (intent == null) return fail("Google Homeアプリが見つかりませんでした。インストールされているか確認してください。")
    return launchOrNotify(ctx, intent, intent.`package`, "Google Home")
  }

  // ---- ライト・音量・明るさ ---------------------------------------------------------

  private fun flashlight(ctx: Context, on: Boolean): Outcome {
    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val id = cm.cameraIdList.firstOrNull {
      cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } ?: return fail("この端末にはライトが見つかりませんでした。")
    return try {
      cm.setTorchMode(id, on)
      ok()
    } catch (e: Exception) {
      fail("ライトを操作できませんでした。他のアプリがカメラを使っているかもしれません。")
    }
  }

  private fun volume(ctx: Context, args: JSONObject): Outcome {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val stream = when (args.optString("stream", "media")) {
      "ring" -> AudioManager.STREAM_RING
      "alarm" -> AudioManager.STREAM_ALARM
      else -> AudioManager.STREAM_MUSIC
    }
    val max = am.getStreamMaxVolume(stream)
    val step = maxOf(1, max / 5)
    val target = when {
      args.has("percent") -> Math.round(args.optDouble("percent") / 100.0 * max).toInt()
      args.optString("delta") == "up" -> am.getStreamVolume(stream) + step
      args.optString("delta") == "down" -> am.getStreamVolume(stream) - step
      else -> return fail("音量の指定が分かりませんでした。")
    }
    am.setStreamVolume(stream, target.coerceIn(0, max), 0)
    return ok()
  }

  private fun brightness(ctx: Context, args: JSONObject): Outcome {
    if (!Settings.System.canWrite(ctx)) {
      return fail("画面の明るさを変えるには、アプリの設定の「スマホの操作」で「システム設定の変更」を許可してください。")
    }
    val percent = args.optInt("percent", -1)
    if (percent !in 0..100) return fail("明るさの指定が分かりませんでした。")
    val value = (percent * 255 / 100).coerceIn(1, 255)
    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    return ok()
  }

  // ---- 電力モード・おやすみモード・マナーモード ------------------------------------------

  private fun batterySaver(ctx: Context, on: Boolean): Outcome {
    if (ctx.checkSelfPermission(SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
      return fail("電力モードの切り替えには、パソコンからの一度きりの許可設定が必要です。アプリの設定の「スマホの操作」を見てください。")
    }
    Settings.Global.putInt(ctx.contentResolver, "low_power", if (on) 1 else 0)
    Thread.sleep(800) // 反映を待って、本当に切り替わったか確認する（呼び出し元はバックグラウンドスレッド）
    val actual = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    return if (actual == on) ok() else fail("電力モードを切り替えられませんでした。")
  }

  private fun doNotDisturb(ctx: Context, on: Boolean): Outcome {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (!nm.isNotificationPolicyAccessGranted) {
      return fail("おやすみモードを切り替えるには、アプリの設定の「スマホの操作」で通知ポリシーへのアクセスを許可してください。")
    }
    nm.setInterruptionFilter(
      if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
    )
    return ok()
  }

  private fun ringerMode(ctx: Context, mode: String): Outcome {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val target = when (mode) {
      "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
      "silent" -> AudioManager.RINGER_MODE_SILENT
      else -> AudioManager.RINGER_MODE_NORMAL
    }
    if (target == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted) {
      return fail("マナーモード（無音）にするには、アプリの設定の「スマホの操作」で通知ポリシーへのアクセスを許可してください。")
    }
    am.ringerMode = target
    return ok()
  }

  // ---- アプリ・設定画面を開く（ハンズフリー） --------------------------------------------------

  private const val LISTENER_HINT = "タップなしで開くには、アプリの設定で「通知へのアクセス」を許可してください。"

  private fun openApp(ctx: Context, name: String): Outcome {
    if (AppFinder.normalize(name).isEmpty()) return fail("開くアプリの名前が分かりませんでした。")
    val app = AppFinder.find(ctx, name) ?: return fail("「$name」というアプリが見つかりませんでした。")
    val intent = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
      ?: return fail("「${app.label}」を開けませんでした。")
    return launchOrNotify(ctx, intent, app.packageName, app.label)
  }

  /** ハンズフリーで開く。開けなかった（許可なし・画面ロック中など）ときは、タップして開く通知にする */
  private fun launchOrNotify(ctx: Context, intent: Intent, expectedPackage: String?, label: String): Outcome {
    if (Launcher.start(ctx, Intent(intent), expectedPackage)) return ok()
    postOpenNotification(ctx, "$label を開く", "タップすると開きます", intent)
    val reason = when {
      !AppNotificationListener.isEnabled(ctx) -> LISTENER_HINT
      Launcher.isLocked(ctx) -> "画面がロックされているので、ロックを解除してから通知をタップしてください。"
      else -> "自動では開けませんでした。"
    }
    return ok("画面の通知をタップすると、${label}が開きます。$reason")
  }

  private fun openSettings(ctx: Context, screen: String): Outcome {
    val (action, label) = when (screen) {
      "wifi" -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"
      "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
      "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "機内モード"
      "display" -> Settings.ACTION_DISPLAY_SETTINGS to "画面"
      "sound" -> Settings.ACTION_SOUND_SETTINGS to "音"
      "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "電力モード"
      "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "位置情報"
      "mobile" -> Settings.ACTION_NETWORK_OPERATOR_SETTINGS to "モバイル通信"
      else -> Settings.ACTION_SETTINGS to "設定"
    }
    val intent = Intent(action)
    val pkg = intent.resolveActivity(ctx.packageManager)?.packageName
    return launchOrNotify(ctx, intent, pkg, "${label}の設定")
  }

  // ---- 音楽 -----------------------------------------------------------------------------

  @Volatile
  private var endSessionRequested = false

  /** 音楽を流したら会話モードを終える（音楽の歌詞を話しかけと聞き間違えないように）。読み取ったら false に戻る */
  fun consumeEndSession(): Boolean {
    val v = endSessionRequested
    endSessionRequested = false
    return v
  }

  private fun playMusic(ctx: Context, args: JSONObject): Outcome {
    // AIは "title"/"artist" を分けず "query" にまとめて渡してくる想定なので、
    // rawQuery として渡し、MusicPlayer 側の後方互換の検索に任せる
    val r = MusicPlayer.play(
      ctx,
      args.optString("app", ""),
      args.optString("title", ""),
      args.optString("artist", ""),
      args.optString("query", "")
    )
    if (!r.ok) return fail(r.message)
    endSessionRequested = true
    return ok()
  }

  private fun mediaControl(ctx: Context, command: String): Outcome {
    val code = when (command) {
      "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
      "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
      "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
      "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
      "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
      else -> return fail("音楽の操作が分かりませんでした。")
    }
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    return ok()
  }

  /**
   * ハンズフリーで開けなかったときの代わりに、タップして開く通知を出す。
   */
  private fun postOpenNotification(ctx: Context, title: String, text: String, intent: Intent) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "デイリーの操作", NotificationManager.IMPORTANCE_HIGH))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pi = PendingIntent.getActivity(
      ctx, (System.currentTimeMillis() % 100_000).toInt(), intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val n = android.app.Notification.Builder(ctx, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(text)
      .setSmallIcon(android.R.drawable.ic_menu_view)
      .setContentIntent(pi)
      .setFullScreenIntent(pi, true)
      .setAutoCancel(true)
      .build()
    nm.notify((System.currentTimeMillis() % 100_000).toInt() + 5000, n)
  }

  // ---- 設定画面に出す、許可の状態 -------------------------------------------------------

  fun controlStatus(ctx: Context): Map<String, Boolean> {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return mapOf(
      "writeSettings" to Settings.System.canWrite(ctx),
      "notificationPolicy" to nm.isNotificationPolicyAccessGranted,
      "secureSettings" to (ctx.checkSelfPermission(SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED),
      "exactAlarm" to (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()),
      "notificationListener" to AppNotificationListener.isEnabled(ctx)
    )
  }

  fun openControlSettings(ctx: Context, kind: String) {
    val pkg = Uri.parse("package:${ctx.packageName}")
    val intent = when (kind) {
      "writeSettings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
      "notificationPolicy" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
      "exactAlarm" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
      "notificationListener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
      else -> return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
  }
}
