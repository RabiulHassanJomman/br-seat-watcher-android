package com.iqbal.seatwatcher

import android.app.Application
import android.content.Context
import com.iqbal.seatwatcher.data.AppDatabase
import com.iqbal.seatwatcher.data.SettingsRepository
import com.iqbal.seatwatcher.domain.StatusBus
import com.iqbal.seatwatcher.domain.WatchChecker
import com.iqbal.seatwatcher.domain.WatchController
import com.iqbal.seatwatcher.notify.Notifier
import com.iqbal.seatwatcher.source.AppSeatSource
import com.iqbal.seatwatcher.source.WebViewSeatSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container; one per process. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val webSource = WebViewSeatSource(context)
    val source = AppSeatSource(settings, webSource)
    val notifier = Notifier(context)
    val status = StatusBus()
    val checker = WatchChecker(db, settings, source, notifier, status)
    val controller = WatchController(context, db, settings, notifier, status)
}

class SeatWatcherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.notifier.ensureChannels(container.settings.current())
            // If watching was on (app updated, process restarted), make sure the service runs.
            container.controller.ensureRunningIfWatching()
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as SeatWatcherApp).container
