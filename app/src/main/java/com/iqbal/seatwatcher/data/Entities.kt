package com.iqbal.seatwatcher.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.model.WatchMode

@Entity(tableName = "watches")
data class WatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromCity: String,
    val toCity: String,
    val dateIso: String,
    val mode: String,
    val trainName: String?,
    val seatClass: String?,
    val repeatMinutes: Double,
    val enabled: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(defaultValue = "0") val alertOnIncrease: Boolean = false,
) {
    fun toModel() = Watch(
        id = id, from = fromCity, to = toCity, dateIso = dateIso,
        mode = runCatching { WatchMode.valueOf(mode) }.getOrDefault(WatchMode.ANY),
        trainName = trainName, seatClass = seatClass, repeatMinutes = repeatMinutes, enabled = enabled,
        alertOnIncrease = alertOnIncrease,
    )
}

/** Last seen seat count per "train|label" key, scoped by searchKey. */
@Entity(tableName = "seen_counts", primaryKeys = ["watchId", "key"], indices = [Index("watchId")])
data class SeenCountEntity(
    val watchId: Long,
    val searchKey: String,
    val key: String,
    val count: Int,
)

enum class CheckStatus { NEVER, OK, LOGIN_REQUIRED, BLOCKED, OFFLINE, PARSE_FAILED }

/** Per-watch engine bookkeeping plus the latest result shown on the home card. */
@Entity(tableName = "watch_state")
data class WatchStateEntity(
    @PrimaryKey val watchId: Long,
    val searchKey: String = "",
    val baselineSet: Boolean = false,
    val lastNotifiedAt: Long = 0,
    val snoozeUntil: Long = 0,
    val lastCheckedAt: Long = 0,
    val lastStatus: String = CheckStatus.NEVER.name,
    /** SnapshotCodec text of the last successful read. */
    val lastSnapshot: String? = null,
    val lastSnapshotSearchKey: String? = null,
)

@Entity(tableName = "alert_log", indices = [Index("at")])
data class AlertLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    val watchLabel: String,
    val text: String,
    val url: String,
    val at: Long,
)
