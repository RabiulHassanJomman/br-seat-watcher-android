package com.iqbal.seatwatcher.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.iqbal.seatwatcher.core.source.SiteUrls
import com.iqbal.seatwatcher.notify.AlarmPlayer
import com.iqbal.seatwatcher.ui.theme.SeatWatcherTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Screen requested by a notification tap; consumed by the UI. */
    private val pendingNav = MutableStateFlow<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            SeatWatcherTheme {
                AppRoot(pendingNav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_STOP_ALARM, false)) AlarmPlayer.stop()
        when {
            intent.hasExtra(EXTRA_BOOKING_URL) ->
                pendingNav.value = Screen.Web(intent.getStringExtra(EXTRA_BOOKING_URL) ?: SiteUrls.BASE, login = false)
            intent.getBooleanExtra(EXTRA_OPEN_LOGIN, false) ->
                pendingNav.value = Screen.Web(SiteUrls.LOGIN, login = true)
        }
        // Consume so a recreation does not reopen it.
        intent.removeExtra(EXTRA_BOOKING_URL)
        intent.removeExtra(EXTRA_OPEN_LOGIN)
        intent.removeExtra(EXTRA_STOP_ALARM)
    }

    companion object {
        const val EXTRA_BOOKING_URL = "booking_url"
        const val EXTRA_WATCH_ID = "watch_id"
        const val EXTRA_OPEN_LOGIN = "open_login"
        const val EXTRA_STOP_ALARM = "stop_alarm"
    }
}
