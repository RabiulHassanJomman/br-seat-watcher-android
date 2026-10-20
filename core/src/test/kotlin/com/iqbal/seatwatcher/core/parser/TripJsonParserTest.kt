package com.iqbal.seatwatcher.core.parser

import com.iqbal.seatwatcher.core.model.ClassSeats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripJsonParserTest {
    private val sample = """
        {"data":{"trains":[
          {"trip_number":"SUBORNO EXPRESS (701)","departure_date_time":"21 Oct, 07:00 am",
           "seat_types":[
             {"key":"1","type":"AC_S","fare":"1150","seat_counts":{"online":1,"offline":2}},
             {"key":"2","type":"S_CHAIR","fare":"405","seat_counts":{"online":0,"offline":0}}
           ]},
          {"trip_number":"TURNA (741)","seat_types":[{"type":"F_SEAT","seat_counts":{"online":14}}]}
        ]},"extra":null,"ok":true,"note":"a \"quoted\" à value"}
    """.trimIndent()

    @Test fun parsesTripsAndSumsCounterPlusOnline() {
        val trains = TripJsonParser.parse(sample)
        assertEquals(listOf("SUBORNO EXPRESS (701)", "TURNA (741)"), trains.map { it.name })
        assertEquals(listOf(ClassSeats("AC_S", 3), ClassSeats("S_CHAIR", 0)), trains[0].classes)
        assertEquals(listOf(ClassSeats("F_SEAT", 14)), trains[1].classes)
    }

    /** Real search-trips-v2 response captured from the site (Dhaka -> Chattogram, 25 Oct 2026). */
    @Test fun parsesRealSiteResponse() {
        val json = javaClass.getResource("/search-trips-v2.json")!!.readText()
        val trains = TripJsonParser.parse(json)
        assertEquals(7, trains.size)
        assertEquals("PARJOTAK EXPRESS (816)", trains[0].name)
        assertEquals(listOf("AC_S", "SNIGDHA", "S_CHAIR"), trains[0].classes.map { it.label })
        val mahanagar = trains.single { it.name == "MAHANAGAR PROVATI (704)" }
        assertEquals(ClassSeats("AC_S", 1), mahanagar.classes.single { it.label == "AC_S" })
        val chattala = trains.single { it.name == "CHATTALA EXPRESS (802)" }
        assertEquals(listOf(31, 3, 32), chattala.classes.map { it.count })
        assertEquals(ClassSeats("SNIGDHA", 61), trains.single { it.name == "TURNA (742)" }.classes.last())
    }

    @Test fun unknownShapeGivesNothing() {
        assertTrue(TripJsonParser.parse("""{"data":{"items":[{"name":"X","count":5}]}}""").isEmpty())
        assertTrue(TripJsonParser.parse("not json").isEmpty())
        assertTrue(TripJsonParser.parse("""{"trip_number":"A (1)","seat_types":[{"type":"ac","seat_counts":1}]}""").isEmpty())
    }

    @Test fun jsonLiteHandlesEscapesAndNumbers() {
        val m = JsonLite.parse("""{"a":[1,-2.5,3e2],"b":"x\nyA","c":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, -2.5, 300.0), m["a"])
        assertEquals("x\nyA", m["b"])
        assertEquals(emptyMap<String, Any?>(), m["c"])
    }
}
