package com.example.studynudge.voice

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * 何もしない通知リスナー。「通知へのアクセス」を許可してもらうと、
 *  (1) Android が「システムに接続されたサービス」を持つアプリとして扱い、バックグラウンドから画面（アプリ）を開けるようになる
 *  (2) 音楽アプリの再生状態の確認・操作（MediaSession）ができるようになる
 * 通知の内容は読まない。
 */
class AppNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    connected = true
  }

  override fun onListenerDisconnected() {
    connected = false
  }

  companion object {
    @Volatile
    var connected = false

    /** 設定で「通知へのアクセス」が許可されているか */
    fun isEnabled(ctx: Context): Boolean {
      val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
      return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
    }
  }
}
