package com.example.studynudge.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManagerから発火するアラーム（5分再送・1時間スヌーズ・毎日18時開始）を受け取る */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Constants.ACTION_STUDY_RESEND -> Engine.onStudyResendOrFire(context)
            Constants.ACTION_STUDY_SNOOZE -> Engine.onStudyResendOrFire(context)
            Constants.ACTION_WARNING_FIRE -> {
                val count = intent.getIntExtra(Constants.EXTRA_WARNING_COUNT, 1)
                Engine.onWarningFire(context, count)
            }
        }
    }
}
