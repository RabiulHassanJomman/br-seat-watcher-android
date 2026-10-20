package com.iqbal.seatwatcher.domain

import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.model.WatchMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object WatchLabels {
    private val LONG = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)
    private val DAY_CLOCK = DateTimeFormatter.ofPattern("d MMM, HH:mm:ss", Locale.ENGLISH)

    fun route(w: Watch) = "${w.from} → ${w.to}"

    fun longDate(iso: String): String = runCatching { LocalDate.parse(iso).format(LONG) }.getOrDefault(iso)
    fun shortDate(iso: String): String = runCatching { LocalDate.parse(iso).format(SHORT) }.getOrDefault(iso)

    fun filter(w: Watch): String {
        val train = if (w.mode == WatchMode.ONE && !w.trainName.isNullOrBlank()) w.trainName else "Any train"
        val cls = w.seatClass?.takeIf { it.isNotBlank() } ?: "Any class"
        return "$train · $cls"
    }

    fun clock(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)

    fun dayClock(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DAY_CLOCK)

    /** "SUBORNO EXPRESS (701)" -> "SUBORNO (701)" for compact lines. */
    fun shortTrain(name: String): String {
        val code = Regex("""\((\d+)\)$""").find(name)?.value ?: return name
        val first = name.substringBefore(' ')
        return "$first $code"
    }
}
