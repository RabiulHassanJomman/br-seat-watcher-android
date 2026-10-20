package com.iqbal.seatwatcher.source

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.iqbal.seatwatcher.BuildConfig
import com.iqbal.seatwatcher.core.model.ClassSeats
import com.iqbal.seatwatcher.core.model.TrainSnapshot
import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.source.SeatSource
import com.iqbal.seatwatcher.core.source.SourceResult
import com.iqbal.seatwatcher.data.FakeMode
import com.iqbal.seatwatcher.data.SettingsRepository

object Net {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Debug builds only: pretends seats are absent or present, without touching the site. */
class FakeSeatSource : SeatSource {
    var seats = false
    override suspend fun fetch(watch: Watch): SourceResult {
        val train = watch.trainName ?: "SUBORNO EXPRESS (701)"
        val cls = watch.seatClass?.takeIf { it.isNotBlank() } ?: "AC_S"
        return SourceResult.Success(
            listOf(
                TrainSnapshot(train, listOf(ClassSeats(cls, if (seats) 3 else 0), ClassSeats("F_SEAT", 0))),
                TrainSnapshot("TURNA (741)", listOf(ClassSeats("S_CHAIR", 0))),
            )
        )
    }
}

/**
 * The source the app uses. Today: WebView. An ApiSeatSource can be slotted in here
 * as the primary with the WebView as fallback, without touching the engine or UI.
 */
class AppSeatSource(
    private val settings: SettingsRepository,
    val webView: WebViewSeatSource,
    private val fake: FakeSeatSource = FakeSeatSource(),
) : SeatSource {
    override suspend fun fetch(watch: Watch): SourceResult {
        if (BuildConfig.DEBUG) {
            when (settings.current().fakeMode) {
                FakeMode.NO_SEATS -> return fake.apply { seats = false }.fetch(watch)
                FakeMode.SEATS -> return fake.apply { seats = true }.fetch(watch)
                FakeMode.OFF -> Unit
            }
        }
        return webView.fetch(watch)
    }
}
