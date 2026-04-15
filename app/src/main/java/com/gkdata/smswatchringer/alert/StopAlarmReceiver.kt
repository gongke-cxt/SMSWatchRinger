package com.gkdata.smswatchringer.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP_ALARM) return
        AlarmService.stop(context)
        TtsSpeaker.stop()
    }

    companion object {
        const val ACTION_STOP_ALARM = "com.gkdata.smswatchringer.action.STOP_ALARM"
    }
}
