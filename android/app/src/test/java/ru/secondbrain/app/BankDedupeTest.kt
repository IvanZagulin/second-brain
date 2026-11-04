package ru.secondbrain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.secondbrain.app.notifications.bankDedupeKey

class BankDedupeTest {
    private val pkg = "ru.sberbankmobile"
    private val text = "Покупка 350 ₽ PYATEROCHKA"

    @Test fun repostOfSameNotificationIsDuplicate() {
        assertEquals(bankDedupeKey(pkg, "0|$pkg|101|null|10123", 1_700_000_000_000L, text),
            bankDedupeKey(pkg, "0|$pkg|101|null|10123", 1_700_000_000_000L, text))
    }

    @Test fun twoIdenticalPurchasesInSameMinuteAreDistinct() {
        val a = bankDedupeKey(pkg, "0|$pkg|101|null|10123", 1_700_000_000_000L, text)
        val b = bankDedupeKey(pkg, "0|$pkg|102|null|10123", 1_700_000_020_000L, text)
        assertNotEquals(a, b)
    }

    @Test fun differentTextIsDistinct() {
        assertNotEquals(bankDedupeKey(pkg, "k", 1L, text), bankDedupeKey(pkg, "k", 1L, "$text."))
    }

    @Test fun keyIsHex() {
        val k = bankDedupeKey(pkg, null, 1L, text)
        assertEquals(64, k.length)
        assert(k.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
