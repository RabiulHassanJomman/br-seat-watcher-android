package com.iqbal.seatwatcher.core.model

data class ClassSeats(val label: String, val count: Int)

data class TrainSnapshot(val name: String, val classes: List<ClassSeats>)

enum class WatchMode { ANY, ONE }

/**
 * One route + date the user wants watched.
 * [dateIso] is yyyy-MM-dd. [trainName] is used only when [mode] is ONE.
 * [seatClass] null or blank means "any class".
 */
data class Watch(
    val id: Long,
    val from: String,
    val to: String,
    val dateIso: String,
    val mode: WatchMode,
    val trainName: String?,
    val seatClass: String?,
    val repeatMinutes: Double,
    val enabled: Boolean,
) {
    val searchKey: String get() = "$from|$to|$dateIso"
}

/** Engine state for one watch. Never shared between watches. */
data class WatchState(
    val searchKey: String = "",
    val counts: Map<String, Int> = emptyMap(),
    val lastNotifiedAt: Long = 0L,
    val baselineSet: Boolean = false,
) {
    companion object {
        val EMPTY = WatchState()
    }
}
