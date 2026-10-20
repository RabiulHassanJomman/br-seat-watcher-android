package com.iqbal.seatwatcher.core.engine

import com.iqbal.seatwatcher.core.model.TrainSnapshot
import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.model.WatchMode
import com.iqbal.seatwatcher.core.model.WatchState

data class AvailableSeat(val key: String, val train: String, val label: String, val count: Int)

sealed interface Decision {
    data object NoAlert : Decision
    data class Alert(val text: String, val seats: List<AvailableSeat>) : Decision
}

data class EvalResult(val decision: Decision, val state: WatchState, val available: List<AvailableSeat>)

/** Pure alert logic, ported exactly from userscript v3.0. */
object AlertEngine {
    fun key(train: String, label: String) = "$train|$label"

    fun available(watch: Watch, snapshot: List<TrainSnapshot>): List<AvailableSeat> {
        val wantClass = watch.seatClass?.takeIf { it.isNotBlank() }
        return snapshot
            .filter { watch.mode != WatchMode.ONE || it.name == watch.trainName }
            .flatMap { train ->
                train.classes
                    .filter { it.count > 0 && (wantClass == null || it.label == wantClass) }
                    .map { AvailableSeat(key(train.name, it.label), train.name, it.label, it.count) }
            }
    }

    fun evaluate(watch: Watch, snapshot: List<TrainSnapshot>, state: WatchState, nowMs: Long): EvalResult {
        val available = available(watch, snapshot)
        if (!watch.enabled) return EvalResult(Decision.NoAlert, state, available)

        val searchKey = watch.searchKey
        val hasBaseline = state.baselineSet && state.searchKey == searchKey
        val prevCounts = if (hasBaseline) state.counts else emptyMap()

        // Merge, never replace: a card missing during a slow render keeps its last count.
        val newCounts = prevCounts.toMutableMap()
        for (train in snapshot) for (c in train.classes) newCounts[key(train.name, c.label)] = c.count

        val saved = state.copy(
            searchKey = searchKey,
            counts = newCounts,
            baselineSet = true,
            lastNotifiedAt = if (hasBaseline) state.lastNotifiedAt else 0L,
        )
        if (!hasBaseline) return EvalResult(Decision.NoAlert, saved, available)

        val fresh = available.filter { (prevCounts[it.key] ?: 0) <= 0 }
        val repeatMs = (watch.repeatMinutes * 60_000).toLong()
        val repeatDue = watch.repeatMinutes > 0 && available.isNotEmpty() &&
            nowMs - saved.lastNotifiedAt >= repeatMs
        val toAlert = when {
            fresh.isNotEmpty() -> fresh
            repeatDue -> available
            else -> emptyList()
        }
        if (toAlert.isEmpty()) return EvalResult(Decision.NoAlert, saved, available)

        return EvalResult(Decision.Alert(formatText(toAlert), toAlert), saved.copy(lastNotifiedAt = nowMs), available)
    }

    fun formatText(seats: List<AvailableSeat>): String {
        val head = seats.take(3).joinToString("; ") { "${it.train} ${it.label}: ${it.count}" }
        return if (seats.size > 3) "$head (+${seats.size - 3} more)" else head
    }
}
