package com.iqbal.seatwatcher.core.parser

import com.iqbal.seatwatcher.core.model.ClassSeats
import com.iqbal.seatwatcher.core.model.TrainSnapshot

/**
 * Parses the visible text (document.body.innerText) of the
 * eticket.railway.gov.bd search results page. Ported from userscript v3.0.
 */
object PageParser {
    private val TRAIN_HEADER = Regex("""^[A-Z0-9 ,.'&\-]+\(\d{2,5}\)$""")
    private val CLASS_LABEL = Regex("""^[A-Z_]{2,12}$""")
    private val NUMBER = Regex("""^\d+$""")

    /** The results page has rendered at least one fare card. */
    fun isLoaded(text: String): Boolean =
        text.contains("Available Tickets") || text.contains("BOOK NOW")

    fun lines(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun parse(text: String): List<TrainSnapshot> {
        val lines = lines(text)
        val trains = mutableListOf<Pair<String, MutableList<ClassSeats>>>()
        var current: MutableList<ClassSeats>? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (TRAIN_HEADER.matches(line)) {
                val classes = mutableListOf<ClassSeats>()
                trains += line to classes
                current = classes
                continue
            }
            if (current == null || !line.startsWith("Available Tickets", ignoreCase = true)) continue

            var label = "?"
            for (j in i - 1 downTo maxOf(0, i - 5)) {
                if (CLASS_LABEL.matches(lines[j])) {
                    label = lines[j]; break
                }
            }
            var count = 0
            for (j in i + 1..minOf(lines.lastIndex, i + 4)) {
                if (NUMBER.matches(lines[j])) {
                    count = lines[j].toIntOrNull() ?: 0; break
                }
            }
            current += ClassSeats(label, count)
        }
        return trains.filter { it.second.isNotEmpty() }.map { TrainSnapshot(it.first, it.second.toList()) }
    }
}
