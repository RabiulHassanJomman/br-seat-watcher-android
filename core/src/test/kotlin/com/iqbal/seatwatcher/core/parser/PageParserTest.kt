package com.iqbal.seatwatcher.core.parser

import com.iqbal.seatwatcher.core.model.ClassSeats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageParserTest {

    private fun card(label: String, count: Int, vat: Boolean = true) = buildString {
        appendLine(label)
        appendLine("৳1,250")
        if (vat) appendLine("Including VAT")
        appendLine("Available Tickets")
        appendLine("(Counter + Online)")
        appendLine(count.toString())
        if (count > 0) appendLine("BOOK NOW")
    }

    private fun train(header: String, vararg cards: String) = buildString {
        appendLine(header)
        appendLine("0 users are trying to book ticket(s)")
        appendLine("07:00 am")
        appendLine("05h 30m")
        appendLine("12:30 pm")
        appendLine("Train Details")
        cards.forEach { append(it) }
    }

    @Test fun parsesClassWithoutIncludingVatLine() {
        val text = train("SUBORNO EXPRESS (701)", card("AC_S", 3, vat = false), card("S_CHAIR", 0))
        val trains = PageParser.parse(text)
        assertEquals(1, trains.size)
        assertEquals("SUBORNO EXPRESS (701)", trains[0].name)
        assertEquals(listOf(ClassSeats("AC_S", 3), ClassSeats("S_CHAIR", 0)), trains[0].classes)
    }

    @Test fun soldOutClassesParseAsZero() {
        val text = train("SONAR BANGLA EXPRESS (787)", card("SNIGDHA", 0), card("AC_S", 0))
        val classes = PageParser.parse(text).single().classes
        assertEquals(listOf(0, 0), classes.map { it.count })
        assertEquals(listOf("SNIGDHA", "AC_S"), classes.map { it.label })
    }

    @Test fun parsesMultipleTrains() {
        val text = "Some header\nModify Search\n" +
            train("SUBORNO EXPRESS (701)", card("AC_S", 1), card("F_SEAT", 14)) +
            train("TURNA (741)", card("AC_B", 2), card("S_CHAIR", 0, vat = false)) +
            train("CHATTALA EXPRESS (801)", card("S_CHAIR", 120))
        val trains = PageParser.parse(text)
        assertEquals(listOf("SUBORNO EXPRESS (701)", "TURNA (741)", "CHATTALA EXPRESS (801)"), trains.map { it.name })
        assertEquals(ClassSeats("F_SEAT", 14), trains[0].classes[1])
        assertEquals(ClassSeats("AC_B", 2), trains[1].classes[0])
        assertEquals(ClassSeats("S_CHAIR", 120), trains[2].classes[0])
    }

    @Test fun nothingParsedFromEmptyShellOrLoginPage() {
        assertTrue(PageParser.parse("").isEmpty())
        val login = "Login\nMobile Number\nPassword\nForgot Password?\nLOGIN"
        assertTrue(PageParser.parse(login).isEmpty())
        assertFalse(PageParser.isLoaded(login))
    }

    @Test fun handlesWhitespaceAndCrLf() {
        val text = train("SUBORNO EXPRESS (701)", card("AC_S", 5)).replace("\n", "  \r\n\n")
        assertEquals(5, PageParser.parse(text).single().classes.single().count)
        assertTrue(PageParser.isLoaded(text))
    }

    @Test fun unknownLabelDefaultsToQuestionMark() {
        val text = "SUBORNO EXPRESS (701)\nprice only\nAvailable Tickets\n(Counter + Online)\n4\n"
        assertEquals(ClassSeats("?", 4), PageParser.parse(text).single().classes.single())
    }
}
