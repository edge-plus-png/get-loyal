package uk.edge.move.handheld

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class TradingDayTest {
    private fun sale(id:String,type:String,cash:Long,card:Long)=JSONObject().put("tradingDayId",id).put("type",type).put("amountMinor",cash+card)
        .put("tenders",JSONArray().put(JSONObject().put("type","CASH").put("amountMinor",cash)).put(JSONObject().put("type","CARD").put("amountMinor",card)))
    @Test fun splitCashRefundsAndChangeDoNotInflateDrawer() {
        val day=JSONObject().put("id","day").put("openingFloatMinor",5000)
        val paid=sale("day","SALE",700,300).put("cashReceivedMinor",1000).put("changeMinor",300)
        val report=dayReport(day,listOf(paid,sale("day","REFUND",200,0),sale("other","SALE",9000,0)),5400)
        assertEquals(5500,report.getLong("expectedCashMinor"));assertEquals(-100,report.getLong("cashVarianceMinor"))
        assertEquals(1000,report.getLong("totalValueMinor"));assertEquals(200,report.getLong("refundedTotalMinor"))
        assertEquals(1,report.getInt("transactionCount"));assertEquals(1,report.getInt("refundCount"))
    }
    @Test fun skippedCashCountIsNotZeroOrBalanced() {
        val report=dayReport(JSONObject().put("id","day").put("openingFloatMinor",5000),emptyList(),null)
        assertTrue(report.isNull("countedCashMinor"))
        assertTrue(report.isNull("cashVarianceMinor"))
        assertEquals(5000,report.getLong("expectedCashMinor"))
        assertTrue(dayReceipt(report).lines().any {it.startsWith("Counted cash") && it.endsWith("Not recorded")})
    }
    @Test fun zeroFloatAndCountAreValid() {
        val report=dayReport(JSONObject().put("id","empty").put("openingFloatMinor",0),emptyList(),0)
        assertEquals(0,report.getLong("cashVarianceMinor"));assertEquals("empty",report.getString("reportId"))
    }
    @Test fun acceptedDispatchClosesOnceWithoutReportOrAck() {
        val events=mutableListOf<String>()
        assertTrue(dispatchOperationalClose({events.add("dispatch");true},{events.add("close")}))
        assertEquals(listOf("dispatch","close"),events)
    }
    @Test fun rejectedDispatchLeavesDayOpen() {
        var closed=false
        assertFalse(dispatchOperationalClose({false},{closed=true}))
        assertFalse(closed)
    }
    @Test fun launchExceptionDoesNotCloseOrRetry() {
        var calls=0;var closed=false
        try {dispatchOperationalClose({calls++;throw IllegalStateException("launch")},{closed=true});fail("Expected failure")}
        catch(expected:IllegalStateException) {assertEquals("launch",expected.message)}
        assertEquals(1,calls);assertFalse(closed)
    }
    @Test fun localCloseFailureNeverResendsZ() {
        var calls=0
        try {dispatchOperationalClose({calls++;true},{throw IllegalStateException("local")});fail("Expected failure")}
        catch(expected:IllegalStateException) {assertEquals("local",expected.message)}
        assertEquals(1,calls)
    }
    @Test fun cardRefundsStaySeparateFromCashCount() {
        val report=dayReport(JSONObject().put("id","d").put("openingFloatMinor",500),listOf(sale("d","SALE",0,2000),sale("d","REFUND",0,700)),500)
        assertEquals(700,report.getLong("cardRefundTotalMinor"))
        assertEquals(500,report.getLong("expectedCashMinor"))
        assertTrue(dayReceipt(report).lines().any {it.startsWith("Card refunds") && it.endsWith("£7.00")})
    }
    @Test fun negativeVarianceFormatsAsMoney() {assertEquals("−£0.50",formatMinor(-50))}
    @Test fun configuredCutoffUsesPreviousLocalDate() {
        val now=java.util.Calendar.getInstance().apply {set(2026,8,24,2,30,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis
        val config=JSONObject().put("tradingDay",JSONObject().put("mode","EXTENDED").put("cutoffMinutes",240))
        assertEquals("2026-09-23",tradingDate(config,now))
        config.getJSONObject("tradingDay").put("mode","CALENDAR_DAY")
        assertEquals("2026-09-24",tradingDate(config,now))
    }
}
