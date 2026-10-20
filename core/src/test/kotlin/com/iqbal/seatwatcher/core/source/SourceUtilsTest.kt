package com.iqbal.seatwatcher.core.source

import com.iqbal.seatwatcher.core.model.ClassSeats
import com.iqbal.seatwatcher.core.model.TrainSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceUtilsTest {
    @Test fun buildsSearchUrlWithSiteDateFormat() {
        assertEquals(
            "https://eticket.railway.gov.bd/booking/train/search?fromcity=Chattogram&tocity=Dhaka&doj=04-Oct-2026&class=S_CHAIR",
            SiteUrls.search("Chattogram", "Dhaka", "2026-10-04"),
        )
        assertEquals(
            "https://eticket.railway.gov.bd/booking/train/search?fromcity=Cox%27s%20Bazar&tocity=Dhaka&doj=31-Dec-2026&class=AC_S",
            SiteUrls.search("Cox's Bazar", "Dhaka", "2026-12-31", "AC_S"),
        )
        assertEquals(
            "https://railspaapi.shohoz.com/v1.0/web/bookings/search-trips-v2?from_city=Dhaka&to_city=Chattogram&date_of_journey=25-Oct-2026&seat_class=S_CHAIR",
            SiteUrls.api("Dhaka", "Chattogram", "2026-10-25"),
        )
    }

    @Test fun snapshotCodecRoundTrips() {
        val s = listOf(
            TrainSnapshot("SUBORNO EXPRESS (701)", listOf(ClassSeats("AC_S", 1), ClassSeats("F_SEAT", 14))),
            TrainSnapshot("TURNA (741)", listOf(ClassSeats("S_CHAIR", 0))),
        )
        assertEquals(s, SnapshotCodec.decode(SnapshotCodec.encode(s)))
        assertEquals(emptyList<TrainSnapshot>(), SnapshotCodec.decode(null))
    }
}
