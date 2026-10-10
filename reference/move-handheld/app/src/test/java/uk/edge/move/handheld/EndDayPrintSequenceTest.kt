package uk.edge.move.handheld

import org.junit.Assert.*
import org.junit.Test

class EndDayPrintSequenceTest {
    @Test fun dispatchAndInterimStatusCannotReleaseMovePrinter() {
        val events=listOf("com.eft.TRANSACTION_ACK","com.eft.TRANSACTION_STATUS",null)
        events.forEach {assertFalse(isZReportReturn(it,"ZReport"))}
        assertFalse(moveTotalsMayPrint(false,true))
    }
    @Test fun xAndHistoryReportsCannotReleaseMovePrinter() {
        listOf("XReport","HistoryReport",null,"").forEach {
            assertFalse(isZReportReturn("com.eft.TRANSACTION_RESULT",it))
        }
    }
    @Test fun positiveKeepsPrinterUntilFinalReportAndForegroundReturn() {
        var returned=false
        assertFalse(moveTotalsMayPrint(returned,true))
        returned=isZReportReturn("com.eft.TRANSACTION_RESULT","ZReport")
        assertTrue(returned)
        assertFalse(moveTotalsMayPrint(returned,false))
        assertTrue(moveTotalsMayPrint(returned,true))
    }
}
