package com.example.studynudge.reminder

import android.content.Context
import android.content.Intent

/**
 * 「しつこい通知」（在宅学習）のアプリ全体の状態遷移をまとめるところ。
 * Receiver / Service はすべてここのメソッドを呼ぶだけにして、ロジックの重複を避ける。
 *
 * 在宅判定は、Wi-FiのSSIDではなく、StudyNudge本体の「自宅」の地図ピン（GPS）を使う。
 * NudgeServiceが毎分の見回りの中で位置を調べ、tick(context, atHome) を呼び出す。
 */
object Engine {

    /** NudgeServiceの毎分の見回りから呼ばれる入口。在宅かどうかは呼び出し側が判定して渡す */
    fun tick(context: Context, atHome: Boolean) {
        maybeStartCycle(context, atHome)
    }

    /** 在宅・時間帯・休みフラグを確認し、条件を満たしていればStudyサイクルを開始する */
    private fun maybeStartCycle(context: Context, atHome: Boolean) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()

        if (!prefs.enabled) return
        if (prefs.restedToday) return
        if (prefs.cycleActive) return
        if (!TimeUtil.isWithinWindow(context)) return
        if (!atHome) return

        prefs.cycleActive = true
        NotificationHelper.showStudyNotification(context)
        AlarmScheduler.scheduleStudyResend(context)
    }

    fun onStudyStart(context: Context) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        AlarmScheduler.cancelStudyResend(context)
        NotificationHelper.cancelStudyAndWarning(context)
        prefs.warningCount = 0
        launchTargetAndMonitor(context)
    }

    fun onStudyRest(context: Context) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        AlarmScheduler.cancelStudyResend(context)
        NotificationHelper.cancelStudyAndWarning(context)
        prefs.restedToday = true
        prefs.studying = false
        context.stopService(Intent(context, ForegroundMonitorService::class.java))
    }

    fun onStudySnooze(context: Context) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        AlarmScheduler.cancelStudyResend(context)
        NotificationHelper.cancelStudyAndWarning(context)
        AlarmScheduler.scheduleStudySnooze(context)
    }

    /** 5分無応答による自動再送、または1時間スヌーズによる再送 */
    fun onStudyResendOrFire(context: Context) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        if (prefs.restedToday || !prefs.enabled) return
        if (!TimeUtil.isWithinWindow(context)) return
        NotificationHelper.showStudyNotification(context)
        AlarmScheduler.scheduleStudyResend(context)
    }

    /** 監視サービスが3分以上の離脱を検知した時に呼ぶ */
    fun onLeftTooLong(context: Context) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        if (prefs.restedToday) return
        if (!TimeUtil.isWithinWindow(context)) return
        prefs.studying = false
        prefs.warningCount += 1
        NotificationHelper.showWarningNotification(context, prefs.warningCount)
        OverlayService.show(context, OverlayService.MSG_CAUTION)
    }

    fun onWarningStart(context: Context) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        NotificationHelper.cancelStudyAndWarning(context)
        // 警告レベルはリセットしない（直前の続きから継続する仕様）
        launchTargetAndMonitor(context)
    }

    fun onWarningRest(context: Context) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        NotificationHelper.cancelStudyAndWarning(context)
        prefs.restedToday = true
        prefs.studying = false
        context.stopService(Intent(context, ForegroundMonitorService::class.java))
    }

    fun onWarningGiveUp(context: Context) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        NotificationHelper.cancelStudyAndWarning(context)
        NotificationHelper.showGiveUpNotification(context)
        prefs.restedToday = true
        prefs.studying = false
        context.stopService(Intent(context, ForegroundMonitorService::class.java))
    }

    /** 「1時間後にまた通知する」「1時間後に通知する」共通 */
    fun onWarningSnooze(context: Context, currentCount: Int) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        NotificationHelper.cancelStudyAndWarning(context)
        AlarmScheduler.scheduleWarningSnooze(context, currentCount + 1)
    }

    /** 1時間経過後に実際に警告を出す */
    fun onWarningFire(context: Context, count: Int) {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        if (prefs.restedToday) return
        if (!TimeUtil.isWithinWindow(context)) return
        prefs.warningCount = count
        NotificationHelper.showWarningNotification(context, count)
        OverlayService.show(context, OverlayService.MSG_CAUTION)
    }

    /** 設定画面の「状態を確認する」ボタンから呼ぶ、原因切り分け用の診断テキストを作る */
    fun debugStatus(context: Context, atHome: Boolean): String {
        val prefs = Prefs(context)
        prefs.ensureFreshDay()
        val sb = StringBuilder()
        sb.append("有効: ${prefs.enabled}\n")
        sb.append("休み中フラグ: ${prefs.restedToday}\n")
        sb.append("本日サイクル開始済み: ${prefs.cycleActive}\n")
        sb.append("設定時間帯: ${Prefs.formatMinutes(prefs.windowStartMinutes)}〜${Prefs.formatMinutes(prefs.windowEndMinutes)}\n")
        sb.append("時間帯内か: ${TimeUtil.isWithinWindow(context)}\n")
        sb.append("在宅判定（自宅ピンの半径内か）: $atHome\n")
        sb.append("対象アプリ: ${prefs.targetAppLabel ?: "未設定"}")
        return sb.toString()
    }

    private fun launchTargetAndMonitor(context: Context) {
        val prefs = Prefs(context)
        val pkg = prefs.targetPackage
        if (pkg != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        }
        prefs.studying = true
        OverlayService.show(context, OverlayService.MSG_GET_DOWN)
        val serviceIntent = Intent(context, ForegroundMonitorService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
