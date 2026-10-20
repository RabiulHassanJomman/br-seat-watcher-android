package com.iqbal.seatwatcher.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iqbal.seatwatcher.BuildConfig
import com.iqbal.seatwatcher.container
import com.iqbal.seatwatcher.core.model.TrainSnapshot
import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.model.WatchMode
import com.iqbal.seatwatcher.core.source.SiteUrls
import com.iqbal.seatwatcher.core.source.SnapshotCodec
import com.iqbal.seatwatcher.core.source.SourceResult
import com.iqbal.seatwatcher.data.AlertLogEntity
import com.iqbal.seatwatcher.data.AlertStyle
import com.iqbal.seatwatcher.data.FakeMode
import com.iqbal.seatwatcher.data.Settings
import com.iqbal.seatwatcher.data.WatchEntity
import com.iqbal.seatwatcher.data.WatchStateEntity
import com.iqbal.seatwatcher.domain.ServiceStatus
import com.iqbal.seatwatcher.domain.WatchLabels
import com.iqbal.seatwatcher.notify.AlarmPlayer
import com.iqbal.seatwatcher.service.WatchService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class WatchItem(val watch: Watch, val state: WatchStateEntity?) {
    /** Last snapshot, only if it belongs to the watch's current route and date. */
    val snapshot: List<TrainSnapshot>
        get() = if (state?.lastSnapshotSearchKey == watch.searchKey) SnapshotCodec.decode(state?.lastSnapshot) else emptyList()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    /** null until DataStore has loaded, so onboarding does not flash. */
    val settings: StateFlow<Settings?> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val watches: StateFlow<List<WatchItem>?> =
        combine(c.db.watches().observeAll(), c.db.state().observeAll()) { ws, sts ->
            val byId = sts.associateBy { it.watchId }
            ws.map { WatchItem(it.toModel(), byId[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val alerts: StateFlow<List<AlertLogEntity>> =
        c.db.alerts().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val status: StateFlow<ServiceStatus> = c.status.status
    val serviceRunning: StateFlow<Boolean> = WatchService.running
    val alarmPlaying: StateFlow<Boolean> = AlarmPlayer.playing

    private val _refreshing = MutableStateFlow(emptySet<Long>())
    val refreshing: StateFlow<Set<Long>> = _refreshing

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private fun say(msg: String) { _messages.trySend(msg) }

    fun setWatching(on: Boolean) = viewModelScope.launch {
        if (on) {
            if (c.db.watches().enabledCount() == 0) { say("Add or enable a watch first"); return@launch }
            c.controller.start()
        } else c.controller.stop()
    }

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        if (!c.controller.setEnabled(id, enabled)) say("At most ${Settings.MAX_ENABLED_WATCHES} watches can be enabled")
    }

    fun delete(id: Long) = viewModelScope.launch { c.controller.delete(id) }

    fun save(entity: WatchEntity, onSaved: () -> Unit) = viewModelScope.launch {
        val isNew = entity.id == 0L
        val enabledBefore = c.db.watches().enabledCount()
        c.controller.save(entity)
        if (isNew && enabledBefore >= Settings.MAX_ENABLED_WATCHES) say("Saved as disabled: ${Settings.MAX_ENABLED_WATCHES} watches are already enabled")
        onSaved()
    }

    suspend fun getWatch(id: Long): WatchEntity? = c.db.watches().get(id)

    /** Live search for the Add/Edit screen's "Load trains". Never touches engine state. */
    suspend fun loadTrains(from: String, to: String, dateIso: String): SourceResult =
        c.source.fetch(Watch(-1, from, to, dateIso, WatchMode.ANY, null, null, 0.0, false))

    fun refresh(id: Long) = viewModelScope.launch {
        if (id in _refreshing.value) return@launch
        val w = c.db.watches().get(id)?.toModel() ?: return@launch
        _refreshing.value = _refreshing.value + id
        try {
            val runEngine = c.settings.current().watching && w.enabled
            when (val r = c.checker.check(w, runEngine)) {
                is SourceResult.Success -> Unit
                else -> say(describe(r))
            }
        } finally {
            _refreshing.value = _refreshing.value - id
        }
    }

    /** After "Done" on the login screen: one check to confirm the session works. */
    fun sessionCheck() = viewModelScope.launch {
        say("Checking your session…")
        val first = c.db.watches().all().firstOrNull()?.toModel()
        val probe = first ?: Watch(-1, "Dhaka", "Chattogram", LocalDate.now().plusDays(1).toString(), WatchMode.ANY, null, null, 0.0, false)
        val r = c.webSource.fetchUrl(SiteUrls.search(probe))
        say(
            when (r) {
                is SourceResult.Success -> "Session OK: found ${r.snapshot.size} trains"
                else -> describe(r)
            }
        )
        if (r is SourceResult.Success) {
            c.notifier.cancelWarning(com.iqbal.seatwatcher.notify.Warning.LOGIN)
            WatchService.kickIfRunning()
        }
    }

    fun describe(r: SourceResult): String = when (r) {
        is SourceResult.Success -> "Found ${r.snapshot.size} trains"
        SourceResult.LoginRequired -> "Login needed: log in from the status card"
        is SourceResult.Blocked -> "Site asked for a check: ${r.reason}"
        SourceResult.Offline -> "Offline or the site did not load"
        is SourceResult.ParseFailed -> "No trains found on the page (use Settings → Copy debug info)"
    }

    fun stopAlarm() = AlarmPlayer.stop()

    fun clearHistory() = viewModelScope.launch { c.db.alerts().clear() }

    fun setAlertStyle(v: AlertStyle) = viewModelScope.launch { c.settings.setAlertStyle(v) }
    fun setVibrate(v: Boolean) = viewModelScope.launch {
        c.settings.setVibrate(v); c.notifier.ensureChannels(c.settings.current())
    }
    fun setSoundUri(v: String) = viewModelScope.launch {
        c.settings.setSoundUri(v); c.notifier.ensureChannels(c.settings.current())
    }
    fun setInterval(v: Int) = viewModelScope.launch { c.settings.setInterval(v) }
    fun setQuietHours(start: Int, end: Int) = viewModelScope.launch { c.settings.setQuietHours(start, end) }
    fun setOnboardingDone(v: Boolean) = viewModelScope.launch { c.settings.setOnboardingDone(v) }
    fun setFakeMode(v: FakeMode) = viewModelScope.launch { c.settings.setFakeMode(v) }

    fun testAlert() = viewModelScope.launch {
        val s = c.settings.current()
        c.notifier.seatAlert(0, "Test alert", "SUBORNO EXPRESS (701) AC_S: 2 (test)", SiteUrls.BASE, s, quiet = false)
    }

    suspend fun debugInfo(): String {
        val s = c.settings.current()
        val st = status.value
        val sample = c.webSource.lastRawText.ifBlank { s.lastRawSample }
        return buildString {
            appendLine("BR Seat Watcher ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Watching: ${s.watching}, service running: ${serviceRunning.value}, state: ${st.state}")
            if (st.lastCheckAt > 0) appendLine("Last cycle: ${WatchLabels.dayClock(st.lastCheckAt)}")
            appendLine("Interval: ${s.intervalSec}s, style: ${s.alertStyle}")
            appendLine()
            appendLine("Last raw page text (first 2 KB):")
            appendLine(sample.take(2048))
        }
    }
}
