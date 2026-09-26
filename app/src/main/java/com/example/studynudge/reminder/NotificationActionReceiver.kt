package com.example.studynudge.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知のボタン（勉強開始・お休み・開始する・あきらめる等）を受け取る */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Constants.ACTION_STUDY_START -> Engine.onStudyStart(context)
            Constants.ACTION_STUDY_REST -> Engine.onStudyRest(context)
            Constants.ACTION_STUDY_SNOOZE -> Engine.onStudySnooze(context)

            Constants.ACTION_WARNING_START -> Engine.onWarningStart(context)
            Constants.ACTION_WARNING_REST -> Engine.onWarningRest(context)
            Constants.ACTION_WARNING_GIVEUP -> Engine.onWarningGiveUp(context)
            Constants.ACTION_WARNING_SNOOZE -> {
                val count = intent.getIntExtra(Constants.EXTRA_WARNING_COUNT, 1)
                Engine.onWarningSnooze(context, count)
            }
        }
    }
}
