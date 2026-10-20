package com.iqbal.seatwatcher.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.iqbal.seatwatcher.R
import com.iqbal.seatwatcher.data.AlertStyle
import com.iqbal.seatwatcher.data.Settings
import com.iqbal.seatwatcher.ui.MainActivity

enum class Warning(val id: Int, val title: String) {
    LOGIN(NotificationIds.LOGIN, "Login needed"),
    BLOCKED(NotificationIds.BLOCKED, "Site is blocking checks"),
    RESUME(NotificationIds.RESUME, "Watching stopped"),
}

object NotificationIds {
    const val ONGOING = 1
    const val LOGIN = 2
    const val BLOCKED = 3
    const val RESUME = 4
    const val TEST = 5
    fun alert(watchId: Long) = 1000 + watchId.toInt()
}

class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels(s: Settings) {
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCHING, "Watching status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while checks run"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARNINGS, "Warnings", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Login needed, site blocking checks"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Seat alerts (loud alarm)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Used with the Loud alarm style; the app plays the alarm sound itself"
                setSound(null, null)
                enableVibration(s.vibrate)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "Seat alerts (quiet hours)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        // Channel sound and vibration are fixed once created, so each combination gets its own channel.
        val id = alertChannelId(s)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(id, "Seat alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Heads-up alert when a seat opens"
                    val sound = s.soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
                        ?: android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
                    setSound(
                        sound,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(s.vibrate)
                    if (s.vibrate) vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
                }
            )
        }
        nm.notificationChannels
            .filter { it.id.startsWith(CH_ALERT_PREFIX) && it.id != id }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    private fun alertChannelId(s: Settings) =
        CH_ALERT_PREFIX + Integer.toHexString((s.soundUri + "|" + s.vibrate).hashCode())

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ongoing(text: String, warning: Boolean = false): Notification =
        NotificationCompat.Builder(context, CH_WATCHING)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle(if (warning) "BR Seat Watcher: action needed" else "BR Seat Watcher")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(0))
            .addAction(0, "Stop", action(NotificationActionReceiver.ACTION_STOP, 0))
            .addAction(0, "Open app", openApp(1))
            .build()

    @SuppressLint("MissingPermission")
    fun updateOngoing(text: String, warning: Boolean = false) {
        if (canPost()) NotificationManagerCompat.from(context).notify(NotificationIds.ONGOING, ongoing(text, warning))
    }

    @SuppressLint("MissingPermission")
    fun seatAlert(watchId: Long, title: String, text: String, url: String, s: Settings, quiet: Boolean) {
        if (!canPost()) return
        ensureChannels(s)
        val loud = s.alertStyle == AlertStyle.LOUD_ALARM && !quiet
        val channel = when {
            quiet -> CH_QUIET
            loud -> CH_ALARM
            else -> alertChannelId(s)
        }
        val open = bookingIntent(watchId, url)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle("Seat available!")
            .setContentText(text)
            .setSubText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (loud) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setColor(0xFF0F7B3F.toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
            .setDeleteIntent(action(NotificationActionReceiver.ACTION_STOP_ALARM, watchId))
            .addAction(0, "Open booking", open)
            .addAction(0, "Snooze 10 min", action(NotificationActionReceiver.ACTION_SNOOZE, watchId))
            .addAction(0, "Stop", action(NotificationActionReceiver.ACTION_STOP, watchId))
            .build()
        if (loud) n.flags = n.flags or Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(context).notify(NotificationIds.alert(watchId), n)
        if (loud) AlarmPlayer.start(context, s.soundUri)
    }

    @SuppressLint("MissingPermission")
    fun warning(w: Warning, text: String) {
        if (!canPost()) return
        val intent = if (w == Warning.LOGIN) loginIntent() else openApp(10 + w.id)
        val n = NotificationCompat.Builder(context, CH_WARNINGS)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle(w.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        NotificationManagerCompat.from(context).notify(w.id, n)
    }

    fun cancel(id: Int) = nm.cancel(id)

    fun cancelWarning(w: Warning) = nm.cancel(w.id)

    private fun openApp(req: Int): PendingIntent =
        PendingIntent.getActivity(
            context, req,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun loginIntent(): PendingIntent =
        PendingIntent.getActivity(
            context, 20,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_LOGIN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun bookingIntent(watchId: Long, url: String): PendingIntent =
        PendingIntent.getActivity(
            context, 100_000 + watchId.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_BOOKING_URL, url)
                .putExtra(MainActivity.EXTRA_WATCH_ID, watchId)
                .putExtra(MainActivity.EXTRA_STOP_ALARM, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(action: String, watchId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, (action.hashCode() * 31) + watchId.toInt(),
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(NotificationActionReceiver.EXTRA_WATCH_ID, watchId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CH_WATCHING = "watching"
        const val CH_WARNINGS = "warnings"
        const val CH_ALARM = "seat_alarm"
        const val CH_QUIET = "seat_quiet"
        const val CH_ALERT_PREFIX = "seat_alerts_"
    }
}
