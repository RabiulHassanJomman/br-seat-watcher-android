package com.iqbal.seatwatcher.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Loops the alarm sound on the alarm stream until stopped from the notification or the app. */
object AlarmPlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    @Synchronized
    fun start(context: Context, soundUri: String) {
        if (player != null) return
        val uri = soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context.applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w("AlarmPlayer", "cannot play $uri", e)
            null
        }
        _playing.value = player != null
    }

    @Synchronized
    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        _playing.value = false
    }
}
