package com.iqbal.seatwatcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AlertStyle { NORMAL, LOUD_ALARM }

/** Debug-only fake data source, for testing alerts without the real site. */
enum class FakeMode { OFF, NO_SEATS, SEATS }

data class Settings(
    val alertStyle: AlertStyle = AlertStyle.NORMAL,
    /** Empty = system default notification/alarm sound. */
    val soundUri: String = "",
    val vibrate: Boolean = true,
    val intervalSec: Int = DEFAULT_INTERVAL,
    /** Minutes after midnight; -1 = quiet hours off. */
    val quietStart: Int = -1,
    val quietEnd: Int = -1,
    val onboardingDone: Boolean = false,
    val watching: Boolean = false,
    val fakeMode: FakeMode = FakeMode.OFF,
    val lastRawSample: String = "",
) {
    val quietEnabled get() = quietStart >= 0 && quietEnd >= 0

    fun inQuietHours(minuteOfDay: Int): Boolean {
        if (!quietEnabled || quietStart == quietEnd) return false
        return if (quietStart < quietEnd) minuteOfDay in quietStart until quietEnd
        else minuteOfDay >= quietStart || minuteOfDay < quietEnd
    }

    companion object {
        const val DEFAULT_INTERVAL = 60
        const val MIN_INTERVAL = 30
        const val MAX_INTERVAL = 300
        const val MAX_ENABLED_WATCHES = 3
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val alertStyle = stringPreferencesKey("alert_style")
        val soundUri = stringPreferencesKey("sound_uri")
        val vibrate = booleanPreferencesKey("vibrate")
        val interval = intPreferencesKey("interval_sec")
        val quietStart = intPreferencesKey("quiet_start")
        val quietEnd = intPreferencesKey("quiet_end")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val watching = booleanPreferencesKey("watching")
        val fake = stringPreferencesKey("fake_mode")
        val rawSample = stringPreferencesKey("last_raw_sample")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            alertStyle = p[K.alertStyle]?.let { runCatching { AlertStyle.valueOf(it) }.getOrNull() } ?: AlertStyle.NORMAL,
            soundUri = p[K.soundUri] ?: "",
            vibrate = p[K.vibrate] ?: true,
            intervalSec = (p[K.interval] ?: Settings.DEFAULT_INTERVAL)
                .coerceIn(Settings.MIN_INTERVAL, Settings.MAX_INTERVAL),
            quietStart = p[K.quietStart] ?: -1,
            quietEnd = p[K.quietEnd] ?: -1,
            onboardingDone = p[K.onboarding] ?: false,
            watching = p[K.watching] ?: false,
            fakeMode = p[K.fake]?.let { runCatching { FakeMode.valueOf(it) }.getOrNull() } ?: FakeMode.OFF,
            lastRawSample = p[K.rawSample] ?: "",
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun setAlertStyle(v: AlertStyle) = context.dataStore.edit { it[K.alertStyle] = v.name }
    suspend fun setSoundUri(v: String) = context.dataStore.edit { it[K.soundUri] = v }
    suspend fun setVibrate(v: Boolean) = context.dataStore.edit { it[K.vibrate] = v }
    suspend fun setInterval(v: Int) = context.dataStore.edit {
        it[K.interval] = v.coerceIn(Settings.MIN_INTERVAL, Settings.MAX_INTERVAL)
    }
    suspend fun setQuietHours(start: Int, end: Int) = context.dataStore.edit {
        it[K.quietStart] = start; it[K.quietEnd] = end
    }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[K.onboarding] = v }
    suspend fun setWatching(v: Boolean) = context.dataStore.edit { it[K.watching] = v }
    suspend fun setFakeMode(v: FakeMode) = context.dataStore.edit { it[K.fake] = v.name }
    suspend fun setLastRawSample(v: String) = context.dataStore.edit { it[K.rawSample] = v.take(2048) }
}
