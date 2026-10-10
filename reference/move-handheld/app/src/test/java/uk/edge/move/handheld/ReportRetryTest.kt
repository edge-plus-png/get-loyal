package uk.edge.move.handheld
import org.junit.Assert.*
import org.junit.Test
class ReportRetryTest {
    @Test fun initialRetryHasBoundedJitter() {
        assertEquals(15000L,ReportRetry.delayMillis(1,0.0))
        assertEquals(30000L,ReportRetry.delayMillis(1,1.0))
    }
    @Test fun repeatedOutagesGrowAndStayBounded() {
        assertEquals(60000L,ReportRetry.delayMillis(2,1.0))
        assertEquals(900000L,ReportRetry.delayMillis(100,1.0))
        assertEquals(450000L,ReportRetry.delayMillis(100,0.0))
    }
}
