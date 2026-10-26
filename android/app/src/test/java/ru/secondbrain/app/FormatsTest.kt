package ru.secondbrain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.secondbrain.app.core.daysWord
import ru.secondbrain.app.core.parseDate
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.parseRubToMinor
import ru.secondbrain.app.core.rub
import java.time.LocalDate

class FormatsTest {
    @Test fun rubles() {
        assertEquals("1 234 ₽", rub(123456))
        assertEquals("−50 ₽", rub(-5000))
        assertEquals("2 554,30 ₽", rub(255430, kopecks = true))
        assertEquals("—", rub(null))
    }

    @Test fun parseMoney() {
        assertEquals(255430L, parseRubToMinor("2 554,30"))
        assertEquals(3800L, parseRubToMinor("38"))
        assertEquals(100000L, parseRubToMinor("1 000 ₽"))
        assertNull(parseRubToMinor("abc"))
    }

    @Test fun dates() {
        assertEquals(LocalDate.of(2026, 10, 26), parseDate("2026-10-26"))
        assertEquals(1793013240000L, parseInstant("2026-10-26T14:14:00+03:00")!!.toEpochMilli())
        assertEquals("дня", daysWord(3))
        assertEquals("дней", daysWord(11))
        assertEquals("день", daysWord(21))
    }
}
