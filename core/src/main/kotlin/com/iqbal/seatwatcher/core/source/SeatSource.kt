package com.iqbal.seatwatcher.core.source

import com.iqbal.seatwatcher.core.model.TrainSnapshot
import com.iqbal.seatwatcher.core.model.Watch
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface SourceResult {
    data class Success(val snapshot: List<TrainSnapshot>) : SourceResult
    data object LoginRequired : SourceResult
    data class Blocked(val reason: String) : SourceResult
    data object Offline : SourceResult
    data class ParseFailed(val rawSample: String) : SourceResult
}

/** Gets a snapshot for a watch. Implementations: WebView (default), API (later), Fake (debug). */
interface SeatSource {
    suspend fun fetch(watch: Watch): SourceResult
}

object SiteUrls {
    const val BASE = "https://eticket.railway.gov.bd"
    /** The exact login route is unverified, so the login screen opens the home page. */
    const val LOGIN = BASE
    private val SITE_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)

    fun siteDate(dateIso: String): String = LocalDate.parse(dateIso).format(SITE_DATE)

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

    /** The `class` param only highlights a card, so it is omitted. */
    fun search(from: String, to: String, dateIso: String): String =
        "$BASE/booking/train/search?fromcity=${enc(from)}&tocity=${enc(to)}&doj=${siteDate(dateIso)}"

    fun search(watch: Watch) = search(watch.from, watch.to, watch.dateIso)
}

/** Compact text encoding for storing a snapshot (no JSON dependency in core). */
object SnapshotCodec {
    fun encode(snapshot: List<TrainSnapshot>): String = buildString {
        for (t in snapshot) {
            append("T\t").append(t.name.replace('\t', ' ')).append('\n')
            for (c in t.classes) append("C\t").append(c.label).append('\t').append(c.count).append('\n')
        }
    }

    fun decode(text: String?): List<TrainSnapshot> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<TrainSnapshot>()
        var name: String? = null
        var classes = mutableListOf<com.iqbal.seatwatcher.core.model.ClassSeats>()
        fun flush() { name?.let { out += TrainSnapshot(it, classes.toList()) } }
        for (line in text.split('\n')) {
            val p = line.split('\t')
            when (p.firstOrNull()) {
                "T" -> { flush(); name = p.getOrNull(1); classes = mutableListOf() }
                "C" -> if (p.size >= 3) classes += com.iqbal.seatwatcher.core.model.ClassSeats(p[1], p[2].toIntOrNull() ?: 0)
            }
        }
        flush()
        return out
    }
}
