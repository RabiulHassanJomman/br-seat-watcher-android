package com.iqbal.seatwatcher.core.engine

import com.iqbal.seatwatcher.core.model.ClassSeats
import com.iqbal.seatwatcher.core.model.TrainSnapshot
import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.model.WatchMode
import com.iqbal.seatwatcher.core.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    private val min = 60_000L
    private val suborno = "SUBORNO EXPRESS (701)"
    private val turna = "TURNA (741)"

    private fun watch(
        id: Long = 1, date: String = "2026-11-04", mode: WatchMode = WatchMode.ANY,
        train: String? = null, cls: String? = null, repeat: Double = 0.0, enabled: Boolean = true,
        onIncrease: Boolean = false,
    ) = Watch(id, "Chattogram", "Dhaka", date, mode, train, cls, repeat, enabled, onIncrease)

    private fun snap(vararg trains: Pair<String, List<Pair<String, Int>>>) =
        trains.map { (n, cs) -> TrainSnapshot(n, cs.map { ClassSeats(it.first, it.second) }) }

    /** Runs a sequence of checks, returning the decisions; state threads through. */
    private class Runner(val w: Watch) {
        var state = WatchState.EMPTY
        fun check(s: List<TrainSnapshot>, now: Long, watch: Watch = w): Decision {
            val r = AlertEngine.evaluate(watch, s, state, now)
            state = r.state
            return r.decision
        }
    }

    private fun Decision.isAlert() = this is Decision.Alert

    @Test fun baselineSendsNoAlert() {
        val r = Runner(watch())
        val d = r.check(snap(suborno to listOf("AC_S" to 5)), 0)
        assertEquals(Decision.NoAlert, d)
        assertTrue(r.state.baselineSet)
    }

    @Test fun zeroToNAlertsOnce() {
        val r = Runner(watch())
        r.check(snap(suborno to listOf("AC_S" to 0)), 0)
        val d = r.check(snap(suborno to listOf("AC_S" to 2)), 1 * min)
        assertEquals("SUBORNO EXPRESS (701) AC_S: 2", (d as Decision.Alert).text)
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 2)), 2 * min))
    }

    @Test fun noRepeatWhileSeatsStayAvailable() {
        val r = Runner(watch())
        r.check(snap(suborno to listOf("AC_S" to 0)), 0)
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 3)), min).isAlert())
        for (i in 2..30) assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 4 - i % 2)), i * min))
    }

    @Test fun soldOutThenBackAlertsAgain() {
        val r = Runner(watch())
        r.check(snap(suborno to listOf("AC_S" to 0)), 0)
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 1)), min).isAlert())
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 0)), 2 * min))
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 1)), 3 * min).isAlert())
    }

    @Test fun cardMissingThenBackSendsNoFalseAlert() {
        val r = Runner(watch())
        r.check(snap(suborno to listOf("AC_S" to 4), turna to listOf("S_CHAIR" to 9)), 0)
        // Slow render: TURNA card missing on this read.
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 4)), min))
        assertEquals(9, r.state.counts["$turna|S_CHAIR"])
        // It comes back with seats: not fresh, so no alert.
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 4), turna to listOf("S_CHAIR" to 9)), 2 * min))
    }

    @Test fun trainFilterIgnoresOtherTrains() {
        val r = Runner(watch(mode = WatchMode.ONE, train = suborno))
        r.check(snap(suborno to listOf("AC_S" to 0), turna to listOf("AC_B" to 0)), 0)
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 0), turna to listOf("AC_B" to 6)), min))
        val d = r.check(snap(suborno to listOf("AC_S" to 1), turna to listOf("AC_B" to 6)), 2 * min) as Decision.Alert
        assertEquals(listOf(suborno), d.seats.map { it.train })
    }

    @Test fun classFilterIgnoresOtherClasses() {
        val r = Runner(watch(cls = "AC_S"))
        r.check(snap(suborno to listOf("AC_S" to 0, "F_SEAT" to 0)), 0)
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 0, "F_SEAT" to 14)), min))
        val d = r.check(snap(suborno to listOf("AC_S" to 1, "F_SEAT" to 14)), 2 * min) as Decision.Alert
        assertEquals(listOf("AC_S"), d.seats.map { it.label })
    }

    @Test fun searchKeyChangeResetsBaseline() {
        val r = Runner(watch())
        r.check(snap(suborno to listOf("AC_S" to 0)), 0)
        val moved = watch(date = "2026-11-05")
        // New date: first check is a fresh baseline, even though seats exist.
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 5)), min, moved))
        assertEquals("Chattogram|Dhaka|2026-11-05", r.state.searchKey)
        assertEquals(mapOf("$suborno|AC_S" to 5), r.state.counts)
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 0)), 2 * min, moved) == Decision.NoAlert)
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 2)), 3 * min, moved).isAlert())
    }

    @Test fun repeatFiresOnlyAfterInterval() {
        val r = Runner(watch(repeat = 5.0))
        r.check(snap(suborno to listOf("AC_S" to 0)), 0)
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 2)), 1 * min).isAlert())
        for (t in 2..5) assertEquals("t=$t", Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 2)), t * min))
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 2)), 6 * min).isAlert())
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 2)), 7 * min))
    }

    @Test fun repeatNeverFiresWhenZero() {
        val r = Runner(watch(repeat = 0.0))
        r.check(snap(suborno to listOf("AC_S" to 0)), 0)
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 2)), min).isAlert())
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 2)), 999 * min))
    }

    @Test fun plusNMoreText() {
        val r = Runner(watch())
        val zero = snap(suborno to listOf("AC_S" to 0, "SNIGDHA" to 0, "F_SEAT" to 0), turna to listOf("AC_B" to 0, "S_CHAIR" to 0))
        r.check(zero, 0)
        val d = r.check(snap(suborno to listOf("AC_S" to 1, "SNIGDHA" to 2, "F_SEAT" to 3), turna to listOf("AC_B" to 4, "S_CHAIR" to 5)), min) as Decision.Alert
        assertEquals("SUBORNO EXPRESS (701) AC_S: 1; SUBORNO EXPRESS (701) SNIGDHA: 2; SUBORNO EXPRESS (701) F_SEAT: 3 (+2 more)", d.text)
    }

    @Test fun twoWatchesNeverShareState() {
        val a = Runner(watch(id = 1))
        val b = Runner(watch(id = 2, cls = "AC_S"))
        a.check(snap(suborno to listOf("AC_S" to 0)), 0)
        // b has not had its baseline yet: seats appearing for a must not alert b.
        assertTrue(a.check(snap(suborno to listOf("AC_S" to 1)), min).isAlert())
        assertEquals(Decision.NoAlert, b.check(snap(suborno to listOf("AC_S" to 1)), min))
        assertEquals(Decision.NoAlert, b.check(snap(suborno to listOf("AC_S" to 1)), 2 * min))
        assertTrue(a.state !== b.state)
    }

    @Test fun neverAlertsWhileDisabled() {
        val r = Runner(watch())
        r.check(snap(suborno to listOf("AC_S" to 0)), 0)
        val off = watch(enabled = false)
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 8)), min, off))
    }

    @Test fun clearedStateMeansFirstCheckIsBaseline() {
        val r = Runner(watch())
        r.check(snap(suborno to listOf("AC_S" to 0)), 0)
        r.state = WatchState.EMPTY // toggled off then on
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 3)), min))
    }

    @Test fun increaseIgnoredByDefault() {
        val r = Runner(watch())
        r.check(snap(suborno to listOf("AC_S" to 3)), 0)
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 5)), min))
    }

    @Test fun increaseAlertsWhenEnabled() {
        val r = Runner(watch(onIncrease = true))
        r.check(snap(suborno to listOf("AC_S" to 3)), 0)
        val d = r.check(snap(suborno to listOf("AC_S" to 5)), min) as Decision.Alert
        assertEquals("SUBORNO EXPRESS (701) AC_S: 5", d.text)
        // Same count again: nothing new.
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 5)), 2 * min))
    }

    @Test fun decreaseNeverAlertsButTheNextIncreaseDoes() {
        val r = Runner(watch(onIncrease = true))
        r.check(snap(suborno to listOf("AC_S" to 5)), 0)
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 3)), min))
        assertTrue(r.check(snap(suborno to listOf("AC_S" to 4)), 2 * min).isAlert())
    }

    @Test fun increaseModeOnlyReportsTheClassesThatGrew() {
        val r = Runner(watch(onIncrease = true))
        r.check(snap(suborno to listOf("AC_S" to 3, "F_SEAT" to 10)), 0)
        val d = r.check(snap(suborno to listOf("AC_S" to 4, "F_SEAT" to 10)), min) as Decision.Alert
        assertEquals(listOf("AC_S"), d.seats.map { it.label })
    }

    @Test fun increaseModeNoFalseAlertWhenCardReturns() {
        val r = Runner(watch(onIncrease = true))
        r.check(snap(suborno to listOf("AC_S" to 4), turna to listOf("S_CHAIR" to 9)), 0)
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 4)), min))
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 4), turna to listOf("S_CHAIR" to 9)), 2 * min))
    }

    @Test fun increaseModeBaselineStillSilent() {
        val r = Runner(watch(onIncrease = true))
        assertEquals(Decision.NoAlert, r.check(snap(suborno to listOf("AC_S" to 7)), 0))
    }
}
