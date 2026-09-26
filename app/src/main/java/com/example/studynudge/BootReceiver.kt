package com.example.studynudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 端末再起動後に、見守りサービス（NudgeService）を再開する。
 * NudgeServiceの毎分の見回り（tick）が、AI催促・しつこい通知（在宅学習）の両方を
 * カバーしているので、ここで再開すべきものはこのサービス1つだけでよい。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.wasRunning) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, NudgeService::class.java))
            } catch (e: Exception) {
                // 機種によっては再起動直後にフォアグラウンドサービスを開始できないことがある。
                // その場合はユーザーがアプリを開いたときに再度「見守りを開始」してもらう。
            }
        }
        val voicePrefs = com.example.studynudge.voice.VoicePrefs(context)
        if (voicePrefs.enabled) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, com.example.studynudge.voice.VoiceListenerService::class.java)
                )
            } catch (e: Exception) {
                // 同上。マイクを使うフォアグラウンドサービスは特に制限されやすい。
            }
        }
    }
}
