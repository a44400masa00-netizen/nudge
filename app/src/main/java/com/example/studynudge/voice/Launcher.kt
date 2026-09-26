package com.example.studynudge.voice

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import java.util.Locale

/** バックグラウンド（画面ロック中でない状態）から、アプリや設定画面をハンズフリーで開く */
object Launcher {

  fun isLocked(ctx: Context): Boolean =
    (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

  /**
   * 画面を開く。Android はバックグラウンドからの画面起動を制限しているが、
   * 「通知へのアクセス」が許可されていれば（システムに接続されたサービスを持つので）開ける。
   * 開いたことを使用状況（前面に来たか）で確認できたら true。
   * 開けなかった・確認できなかった場合は false（呼び出し側が代わりの方法を使う）。
   */
  fun start(ctx: Context, intent: Intent, expectedPackage: String?): Boolean {
    if (!AppNotificationListener.isEnabled(ctx)) return false
    if (isLocked(ctx)) return false

    val startedAt = System.currentTimeMillis()
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      ctx.startActivity(intent)
    } catch (e: Exception) {
      return false
    }
    if (expectedPackage == null || !UsageCollector.hasPermission(ctx)) return true
    return waitForeground(ctx, expectedPackage, startedAt, 3_000)
  }

  private fun waitForeground(ctx: Context, pkg: String, since: Long, timeoutMs: Long): Boolean {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val events = usm.queryEvents(since - 300, System.currentTimeMillis())
      val e = UsageEvents.Event()
      while (events.hasNextEvent()) {
        events.getNextEvent(e)
        if (e.eventType == 1 && e.packageName == pkg) return true // ACTIVITY_RESUMED
      }
      Thread.sleep(250)
    }
    return false
  }
}

/** 名前（「Instagram」「スポティファイ」など）からインストール済みのアプリを探す */
object AppFinder {

  class Found(val packageName: String, val label: String)

  // 音声認識・AI が使いがちなカタカナの呼び名 → 英語表記
  private val ALIASES = mapOf(
    "インスタ" to "instagram", "インスタグラム" to "instagram",
    "ユーチューブ" to "youtube", "ユーチューブミュージック" to "youtubemusic",
    "ライン" to "line", "ラインミュージック" to "linemusic",
    "スポティファイ" to "spotify", "アマゾンミュージック" to "amazonmusic",
    "アップルミュージック" to "applemusic", "ネットフリックス" to "netflix",
    "ツイッター" to "x", "エックス" to "x", "クローム" to "chrome",
    "グーグルマップ" to "maps", "マップ" to "maps", "ジーメール" to "gmail", "カメラ" to "camera"
  )

  fun normalize(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

  private class Candidate(val packageName: String, val label: String, val norm: String)

  fun find(ctx: Context, name: String): Found? {
    val raw = normalize(name)
    if (raw.isEmpty()) return null
    val target = ALIASES[raw] ?: raw

    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(launcher, 0).map {
      val label = it.loadLabel(pm).toString()
      Candidate(it.activityInfo.packageName, label, normalize(label))
    }

    val matches = apps.filter {
      it.norm.isNotEmpty() && (
        it.norm.contains(target) ||
          (it.norm.length >= 3 && target.contains(it.norm)) ||
          it.packageName.lowercase(Locale.ROOT).contains(target)
        )
    }
    val best = matches.sortedWith(compareBy({ if (it.norm == target) 0 else 1 }, { it.norm.length })).firstOrNull()
      ?: return null
    return Found(best.packageName, best.label)
  }
}
