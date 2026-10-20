package com.iqbal.seatwatcher.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.iqbal.seatwatcher.container
import com.iqbal.seatwatcher.core.source.SourceResult
import com.iqbal.seatwatcher.data.Settings
import com.iqbal.seatwatcher.domain.GlobalState
import com.iqbal.seatwatcher.domain.ServiceStatus
import com.iqbal.seatwatcher.domain.WatchLabels
import com.iqbal.seatwatcher.notify.NotificationIds
import com.iqbal.seatwatcher.notify.Warning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Foreground service (type specialUse) that runs every enabled watch in one loop.
 * Between checks the CPU may sleep; an exact allow-while-idle alarm wakes it for the next check.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loopJob?.isActive != true) loopJob = scope.launch { loop() }
        else kicks.trySend(Unit)
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(
            this, NotificationIds.ONGOING,
            container.notifier.ongoing("Starting…"), type,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e)
        false
    }

    private suspend fun loop() {
        val c = container
        var backoffSec = 0
        while (scope.isActive) {
            val settings = c.settings.current()
            if (!settings.watching) break
            val watches = c.db.watches().enabled().take(Settings.MAX_ENABLED_WATCHES).map { it.toModel() }

            var login = false
            var blocked: String? = null
            var offline = false
            if (watches.isNotEmpty()) {
                for ((i, w) in watches.withIndex()) {
                    if (i > 0) delay(Random.nextLong(2_000, 5_001)) // polite jitter between watches
                    val r = withWakeLock { c.checker.check(w, runEngine = true) }
                    when (r) {
                        SourceResult.LoginRequired -> login = true
                        is SourceResult.Blocked -> blocked = r.reason
                        SourceResult.Offline -> offline = true
                        else -> Unit
                    }
                    if (login || blocked != null) break // pointless to keep hitting the site this cycle
                }
            }

            val now = System.currentTimeMillis()
            val state = when {
                watches.isEmpty() -> GlobalState.NO_WATCHES
                blocked != null -> GlobalState.BLOCKED
                login -> GlobalState.LOGIN_NEEDED
                offline -> GlobalState.OFFLINE
                else -> GlobalState.WATCHING
            }
            val nextSec = when (state) {
                GlobalState.BLOCKED, GlobalState.LOGIN_NEEDED, GlobalState.OFFLINE -> {
                    backoffSec = if (backoffSec == 0) (if (state == GlobalState.OFFLINE) 30 else 60)
                    else minOf(backoffSec * 2, MAX_BACKOFF_SEC)
                    backoffSec
                }
                else -> {
                    backoffSec = 0
                    settings.intervalSec
                }
            }

            when (state) {
                GlobalState.LOGIN_NEEDED -> c.notifier.warning(Warning.LOGIN, "Your e-ticket session expired. Tap to log in again; checks resume after.")
                GlobalState.BLOCKED -> c.notifier.warning(Warning.BLOCKED, "${blocked}. Checks are paused and retried with backoff. Open the site in the app to continue.")
                GlobalState.WATCHING -> {
                    c.notifier.cancelWarning(Warning.LOGIN)
                    c.notifier.cancelWarning(Warning.BLOCKED)
                }
                else -> Unit
            }

            val nextAt = now + nextSec * 1000L
            c.status.set(ServiceStatus(state = state, nextCheckAt = nextAt, lastCheckAt = now, message = blocked ?: ""))
            c.notifier.updateOngoing(ongoingText(state, watches.size, now), warning = state == GlobalState.LOGIN_NEEDED || state == GlobalState.BLOCKED)

            AlarmScheduler.schedule(this, nextSec * 1000L)
            val cycleEnd = SystemClock.elapsedRealtime()
            val kicked = withTimeoutOrNull(nextSec * 1000L) { kicks.receive() } != null
            if (kicked && c.settings.current().watching) {
                // Never hit the site more often than every MIN_GAP, even when kicked repeatedly.
                val since = SystemClock.elapsedRealtime() - cycleEnd
                if (since < MIN_GAP_MS) delay(MIN_GAP_MS - since)
            }
        }
        // Watching turned off.
        stopForegroundCompat()
        stopSelf()
    }

    private fun ongoingText(state: GlobalState, n: Int, now: Long): String = when (state) {
        GlobalState.WATCHING -> "Watching $n route${if (n == 1) "" else "s"} · last check ${WatchLabels.clock(now)}"
        GlobalState.NO_WATCHES -> "No enabled watches. Add or enable one."
        GlobalState.LOGIN_NEEDED -> "Login needed · tap to log in"
        GlobalState.OFFLINE -> "Offline, retrying"
        GlobalState.BLOCKED -> "Site asked for a check · paused with backoff"
        GlobalState.PAUSED -> "Paused"
    }

    private suspend fun <T> withWakeLock(block: suspend () -> T): T {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeatWatcher:check")
        wl.acquire(60_000L)
        try {
            return block()
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WatchService"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val MAX_BACKOFF_SEC = 300
        private const val MIN_GAP_MS = 20_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val kicks = Channel<Unit>(Channel.CONFLATED)

        /** Returns false if Android refused to start the service from the background. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java).setAction(ACTION_START))
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when started from the background.
            Log.w(TAG, "start refused: ${e.javaClass.simpleName}", e)
            context.container.notifier.warning(Warning.RESUME, "Android paused the watcher in the background. Tap to open the app and resume.")
            false
        }

        fun stop(context: Context) {
            if (!_running.value) return
            kicks.trySend(Unit) // the loop sees watching=false and exits
            runCatching { context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP)) }
        }

        /** Run the next check now (login finished, alarm fired, watch changed). */
        fun kickIfRunning() {
            if (_running.value) kicks.trySend(Unit)
        }
    }
}
