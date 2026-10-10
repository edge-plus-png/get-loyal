package uk.edge.move.handheld

import org.junit.Assert.*
import org.junit.Test

class CashCountTest {
    @Test fun countUsesAllTwelveCounterDenominations() {
        val c=CashCount()
        assertEquals(12,CashCount.values.size)
        repeat(12) {c.key("2");c.next()}
        assertEquals(17776L,c.total)
        CashCount.validate(c.json(),c.total)
        assertEquals(0,c.selected)
    }
    @Test fun quantityEditingMatchesCounterFourDigitLimit() {
        val c=CashCount();repeat(5) {c.key("9")}
        assertEquals(9999,c.counts[5000]);c.key("Delete");assertEquals(999,c.counts[5000])
        c.key("Clear");assertEquals(0L,c.total)
    }
    @Test fun zeroCountNeedsExplicitEntryWhenCashExpected() {
        val c=CashCount();assertFalse(c.hasEntry(5000));assertTrue(c.hasEntry(0))
        c.key("0");assertTrue(c.hasEntry(5000))
    }
    @Test fun switchingOpeningModeKeepsCountAndAmountSeparate() {
        val c=CashCount();c.key("2");c.denominations=false;c.key("5");c.key("0");c.key("0")
        assertEquals(500L,c.total);c.denominations=true;assertEquals(10000L,c.total)
    }
    @Test fun inconsistentCashUpPayloadIsRejected() {
        val c=CashCount();c.key("1")
        assertTrue(runCatching {CashCount.validate(c.json(),999)}.isFailure)
        assertTrue(runCatching {CashCount.validate(c.json().put(c.json().getJSONObject(0)),10000)}.isFailure)
    }
}
