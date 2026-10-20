package com.iqbal.seatwatcher.domain

import android.content.Context
import com.iqbal.seatwatcher.data.AppDatabase
import com.iqbal.seatwatcher.data.Settings
import com.iqbal.seatwatcher.data.SettingsRepository
import com.iqbal.seatwatcher.data.WatchEntity
import com.iqbal.seatwatcher.notify.AlarmPlayer
import com.iqbal.seatwatcher.notify.NotificationIds
import com.iqbal.seatwatcher.notify.Notifier
import com.iqbal.seatwatcher.notify.Warning
import com.iqbal.seatwatcher.service.AlarmScheduler
import com.iqbal.seatwatcher.service.Watchdog
import com.iqbal.seatwatcher.service.WatchService

/** Single place that changes the master switch and watch enablement. */
class WatchController(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val status: StatusBus,
) {
    suspend fun start() {
        // A new run: every watch's first check is a baseline and does not alert.
        db.watches().all().forEach { db.state().resetEngine(it.id) }
        settings.setWatching(true)
        notifier.cancelWarning(Warning.RESUME)
        Watchdog.schedule(context)
        WatchService.start(context)
    }

    suspend fun stop() {
        settings.setWatching(false)
        Watchdog.cancel(context)
        AlarmScheduler.cancel(context)
        AlarmPlayer.stop()
        WatchService.stop(context)
        notifier.cancel(NotificationIds.ONGOING)
        notifier.cancelWarning(Warning.LOGIN)
        notifier.cancelWarning(Warning.BLOCKED)
        status.set(ServiceStatus(GlobalState.PAUSED))
    }

    suspend fun ensureRunningIfWatching() {
        if (settings.current().watching && !WatchService.running.value) {
            Watchdog.schedule(context)
            WatchService.start(context)
        }
    }

    /** Returns false when enabling would exceed the limit of enabled watches. */
    suspend fun setEnabled(id: Long, enabled: Boolean): Boolean {
        if (enabled && db.watches().enabledCount() >= Settings.MAX_ENABLED_WATCHES) return false
        if (enabled) db.state().resetEngine(id)
        db.watches().setEnabled(id, enabled)
        if (!enabled) notifier.cancel(NotificationIds.alert(id))
        WatchService.kickIfRunning()
        return true
    }

    /** Insert or update. A saved watch starts fresh (baseline on its next check). */
    suspend fun save(w: WatchEntity): Long {
        val id = if (w.id == 0L) {
            val canEnable = db.watches().enabledCount() < Settings.MAX_ENABLED_WATCHES
            db.watches().insert(w.copy(enabled = w.enabled && canEnable))
        } else {
            db.watches().update(w)
            w.id
        }
        db.state().resetEngine(id)
        WatchService.kickIfRunning()
        return id
    }

    suspend fun delete(id: Long) {
        db.watches().delete(id)
        db.state().clearCounts(id)
        db.state().deleteState(id)
        notifier.cancel(NotificationIds.alert(id))
    }

    suspend fun snooze(id: Long, minutes: Int = 10) {
        val st = db.state().get(id) ?: return
        db.state().upsert(st.copy(snoozeUntil = System.currentTimeMillis() + minutes * 60_000L))
        notifier.cancel(NotificationIds.alert(id))
    }
}
