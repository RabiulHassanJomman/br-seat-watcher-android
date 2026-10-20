package com.iqbal.seatwatcher.domain

import com.iqbal.seatwatcher.core.engine.AlertEngine
import com.iqbal.seatwatcher.core.engine.Decision
import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.model.WatchState
import com.iqbal.seatwatcher.core.source.SeatSource
import com.iqbal.seatwatcher.core.source.SiteUrls
import com.iqbal.seatwatcher.core.source.SnapshotCodec
import com.iqbal.seatwatcher.core.source.SourceResult
import com.iqbal.seatwatcher.data.AlertLogEntity
import com.iqbal.seatwatcher.data.AppDatabase
import com.iqbal.seatwatcher.data.CheckStatus
import com.iqbal.seatwatcher.data.SeenCountEntity
import com.iqbal.seatwatcher.data.SettingsRepository
import com.iqbal.seatwatcher.data.WatchStateEntity
import com.iqbal.seatwatcher.notify.Notifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime

/** Runs one check for one watch: fetch, persist, evaluate, notify, log. */
class WatchChecker(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val source: SeatSource,
    private val notifier: Notifier,
    private val status: StatusBus,
) {
    private val engineLock = Mutex()

    /**
     * @param runEngine false = only refresh the displayed snapshot (used when watching is off),
     * so a manual refresh can never alert or disturb the baseline.
     */
    suspend fun check(watch: Watch, runEngine: Boolean): SourceResult {
        status.update { it.copy(checking = true) }
        val result = try {
            source.fetch(watch)
        } finally {
            status.update { it.copy(checking = false) }
        }
        record(watch, result, runEngine)
        return result
    }

    private suspend fun record(fetched: Watch, result: SourceResult, runEngine: Boolean): Unit = engineLock.withLock {
        val now = System.currentTimeMillis()
        // Re-read: the watch may have been edited, disabled or deleted while the page loaded.
        val watch = db.watches().get(fetched.id)?.toModel() ?: return@withLock
        if (watch.searchKey != fetched.searchKey) return@withLock
        val st = db.state().get(watch.id) ?: WatchStateEntity(watchId = watch.id)

        when (result) {
            is SourceResult.Success -> {
                var next = st.copy(
                    lastCheckedAt = now,
                    lastStatus = CheckStatus.OK.name,
                    lastSnapshot = SnapshotCodec.encode(result.snapshot),
                    lastSnapshotSearchKey = watch.searchKey,
                )
                if (runEngine && watch.enabled) {
                    val counts = db.state().counts(watch.id)
                        .filter { it.searchKey == st.searchKey }
                        .associate { it.key to it.count }
                    val r = AlertEngine.evaluate(
                        watch, result.snapshot,
                        WatchState(st.searchKey, counts, st.lastNotifiedAt, st.baselineSet), now,
                    )
                    db.state().replaceCounts(
                        watch.id,
                        r.state.counts.map { (k, v) -> SeenCountEntity(watch.id, r.state.searchKey, k, v) },
                    )
                    next = next.copy(
                        searchKey = r.state.searchKey,
                        baselineSet = r.state.baselineSet,
                        lastNotifiedAt = r.state.lastNotifiedAt,
                    )
                    db.state().upsert(next)
                    val d = r.decision
                    if (d is Decision.Alert && now >= st.snoozeUntil) alert(watch, d.text, now)
                } else {
                    db.state().upsert(next)
                }
            }
            is SourceResult.ParseFailed -> {
                settings.setLastRawSample(result.rawSample)
                db.state().upsert(st.copy(lastCheckedAt = now, lastStatus = CheckStatus.PARSE_FAILED.name))
            }
            SourceResult.LoginRequired ->
                db.state().upsert(st.copy(lastCheckedAt = now, lastStatus = CheckStatus.LOGIN_REQUIRED.name))
            is SourceResult.Blocked ->
                db.state().upsert(st.copy(lastCheckedAt = now, lastStatus = CheckStatus.BLOCKED.name))
            SourceResult.Offline ->
                db.state().upsert(st.copy(lastCheckedAt = now, lastStatus = CheckStatus.OFFLINE.name))
        }
    }

    private suspend fun alert(watch: Watch, text: String, now: Long) {
        val s = settings.current()
        val t = LocalTime.now()
        val quiet = s.inQuietHours(t.hour * 60 + t.minute)
        val url = SiteUrls.search(watch)
        val label = WatchLabels.route(watch) + " · " + WatchLabels.shortDate(watch.dateIso)
        notifier.seatAlert(watch.id, label, text, url, s, quiet)
        db.alerts().insert(AlertLogEntity(watchId = watch.id, watchLabel = label, text = text, url = url, at = now))
    }
}
