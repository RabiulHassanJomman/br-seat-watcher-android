package com.iqbal.seatwatcher.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iqbal.seatwatcher.container
import kotlinx.coroutines.launch

/** Snooze / Stop / dismiss actions from notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val watchId = intent.getLongExtra(EXTRA_WATCH_ID, 0)
        AlarmPlayer.stop()
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> c.controller.snooze(watchId, 10)
                    ACTION_STOP -> {
                        c.controller.stop()
                        if (watchId != 0L) c.notifier.cancel(NotificationIds.alert(watchId))
                    }
                    ACTION_STOP_ALARM -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.iqbal.seatwatcher.SNOOZE"
        const val ACTION_STOP = "com.iqbal.seatwatcher.STOP"
        const val ACTION_STOP_ALARM = "com.iqbal.seatwatcher.STOP_ALARM"
        const val EXTRA_WATCH_ID = "watch_id"
    }
}
