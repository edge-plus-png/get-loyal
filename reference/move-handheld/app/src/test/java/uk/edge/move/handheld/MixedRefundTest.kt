package uk.edge.move.handheld
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MixedRefundTest {
    private val staff=Staff("operator","Operator","9999",JSONObject().put("refundsEnabled",true))
    private fun tender(type:String,amount:Long)=JSONObject().put("type",type).put("label",type).put("amountMinor",amount)
    private fun source(cardFirst:Boolean=false):JSONObject {
        val tenders=if(cardFirst) listOf(tender("CARD",900),tender("CASH",1000)) else listOf(tender("CASH",400),tender("CASH",600),tender("CARD",900))
        return JSONObject().put("id","sale").put("type","SALE").put("status","APPROVED").put("amountMinor",1900).put("tradingDayId","day").put("createdAt",1)
            .put("tenders",JSONArray(tenders)).put("saleItems",JSONArray().put(JSONObject().put("id","line").put("productName","Item").put("quantity",19).put("grossMinor",1900).put("totalMinor",1900).put("netMinor",1583).put("vatMinor",317)))
    }
    @Test fun fullRefundSendsOnlyNinePoundsToCardAndReturnsTenCash() {
        val original=source();val refund=standaloneRefund(original,staff,"Returned")
        assertEquals(1900L,refund.getLong("amountMinor"));assertEquals(900L,refund.getLong("cardRequestAmountMinor"))
        assertEquals(1000L,refund.getLong("cashAmountMinor"));assertEquals(900L,refund.getLong("cardAmountMinor"))
        assertEquals(listOf("CASH","CARD"),refund.objects("tenders").map {it.getString("type")})
        refund.put("status","APPROVED").put("tradingDayId","day")
        val report=dayReport(JSONObject().put("id","day").put("openingFloatMinor",0),listOf(original,refund))
        assertEquals(1000L,report.getLong("cashRefundTotalMinor"));assertEquals(900L,report.getLong("cardRefundTotalMinor"))
        assertEquals(0L,report.getLong("expectedCashMinor"));assertEquals(0L,report.getLong("vatTotalMinor"))
    }
    @Test fun partialThenRemainderUsesOriginalTenderCapacityWithoutDoubleRefund() {
        val original=source()
        val first=standaloneRefund(original,staff,"Returned",quantities=mapOf("line" to 6)).put("status","APPROVED")
        assertEquals(600L,first.getLong("cashAmountMinor"));assertEquals(0L,first.getLong("cardRequestAmountMinor"))
        val next=standaloneRefund(original,staff,"Rest",refunds=listOf(first))
        assertEquals(400L,next.getLong("cashAmountMinor"));assertEquals(900L,next.getLong("cardRequestAmountMinor"))
        assertEquals(1300L,next.getLong("amountMinor"))
        next.put("status","APPROVED")
        assertThrows(IllegalArgumentException::class.java) {standaloneRefund(original,staff,"Again",refunds=listOf(first,next))}
    }
    @Test fun cancelledAndDeclinedMixedRefundLeaveBothTenderBalancesAvailable() {
        val original=source()
        for(status in listOf("CANCELLED","DECLINED")) {
            val failed=standaloneRefund(original,staff,"Returned").put("status",status)
            val retry=standaloneRefund(original,staff,"Retry",refunds=listOf(failed))
            assertEquals(1000L,retry.getLong("cashAmountMinor"));assertEquals(900L,retry.getLong("cardRequestAmountMinor"))
        }
    }
    @Test fun allocationFollowsCounterOriginalTenderOrder() {
        val refund=standaloneRefund(source(true),staff,"Returned",quantities=mapOf("line" to 12))
        assertEquals(listOf("CARD","CASH"),refund.objects("tenders").map {it.getString("type")})
        assertEquals(900L,refund.getLong("cardRequestAmountMinor"));assertEquals(300L,refund.getLong("cashAmountMinor"))
    }
    @Test fun priorCashRefundCanLeaveCardOnlyRemainder() {
        val original=source()
        val cash=standaloneRefund(original,staff,"Returned",quantities=mapOf("line" to 10)).put("status","APPROVED")
        val card=standaloneRefund(original,staff,"Rest",refunds=listOf(cash))
        assertEquals(0L,card.getLong("cashAmountMinor"));assertEquals(900L,card.getLong("cardRequestAmountMinor"))
        assertEquals(1,card.objects("tenders").size)
    }
}
