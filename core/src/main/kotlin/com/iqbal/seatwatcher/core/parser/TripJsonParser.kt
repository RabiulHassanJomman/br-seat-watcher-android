package com.iqbal.seatwatcher.core.parser

import com.iqbal.seatwatcher.core.model.ClassSeats
import com.iqbal.seatwatcher.core.model.TrainSnapshot

/**
 * Reads trains from the search API response the e-ticket page itself downloads.
 * Expected shape (searched anywhere in the document):
 *   { "trip_number": "SUBORNO EXPRESS (701)",
 *     "seat_types": [ { "type": "AC_S", "seat_counts": { "online": 1, "offline": 0 } } ] }
 * The seat count is online + offline, matching the page's "Available Tickets (Counter + Online)".
 * Returns empty if the shape doesn't match, so a wrong guess can never invent seats.
 */
object TripJsonParser {
    private val LABEL = Regex("""^[A-Z_]{2,12}$""")

    fun parse(json: String): List<TrainSnapshot> {
        val root = runCatching { JsonLite.parse(json) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, TrainSnapshot>()
        walk(root) { m ->
            val name = (m["trip_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@walk
            val types = m["seat_types"] as? List<*> ?: return@walk
            val classes = types.mapNotNull { t ->
                val st = t as? Map<*, *> ?: return@mapNotNull null
                val label = (st["type"] as? String)?.trim()?.takeIf { LABEL.matches(it) } ?: return@mapNotNull null
                val count = seatCount(st["seat_counts"]) ?: return@mapNotNull null
                ClassSeats(label, count)
            }
            if (classes.isNotEmpty() && name !in out) out[name] = TrainSnapshot(name, classes)
        }
        return out.values.toList()
    }

    private fun seatCount(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is Map<*, *> -> {
            val online = (v["online"] as? Number)?.toInt()
            val offline = (v["offline"] as? Number)?.toInt()
            if (online == null && offline == null) null else (online ?: 0) + (offline ?: 0)
        }
        else -> null
    }

    private fun walk(node: Any?, visit: (Map<*, *>) -> Unit) {
        when (node) {
            is Map<*, *> -> { visit(node); node.values.forEach { walk(it, visit) } }
            is List<*> -> node.forEach { walk(it, visit) }
        }
    }
}
