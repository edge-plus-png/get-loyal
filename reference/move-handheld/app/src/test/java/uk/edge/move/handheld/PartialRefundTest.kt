package uk.edge.move.handheld
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PartialRefundTest {
    private fun original(tender:String="CASH")=JSONObject().put("id","original").put("type","SALE").put("status","APPROVED").put("amountMinor",1900)
        .put("createdAt",1000).put("staffName","Server")
        .put("saleItems",JSONArray().put(line("a",3,1000,167)).put(line("b",1,900,150)))
        .put("tenders",JSONArray().put(JSONObject().put("type",tender).put("amountMinor",1900)))
    private fun line(id:String,qty:Int,gross:Long,vat:Long)=JSONObject().put("id",id).put("productName",id).put("quantity",qty)
        .put("grossMinor",gross).put("totalMinor",gross).put("vatMinor",vat).put("netMinor",gross-vat)
    private val staff get()=Staff("operator","Operator","9999",JSONObject().put("refundsEnabled",true))
    @Test fun itemThenAllRefundsOnlyRemainingValueAndTax() {
        val source=original()
        val first=standaloneRefund(source,staff,"Returned",quantities=mapOf("a" to 1)).put("status","APPROVED")
        assertEquals(333L,first.getLong("amountMinor"));assertEquals(56L,first.objects("saleItems").single().getLong("vatMinor"))
        val remaining=standaloneRefund(source,staff,"Rest returned",refunds=listOf(first))
        assertEquals(1567L,remaining.getLong("amountMinor"));assertEquals(3,remaining.objects("saleItems").sumOf {it.getInt("quantity")})
        assertEquals(1900L,first.getLong("amountMinor")+remaining.getLong("amountMinor"))
        assertEquals(317L,(first.objects("saleItems")+remaining.objects("saleItems")).sumOf {it.getLong("vatMinor")})
        assertEquals(1567L,remaining.objects("tenders").single().getLong("amountMinor"))
        remaining.put("status","APPROVED")
        assertTrue(refundableQuantities(source,listOf(first,remaining)).values.all {it==0})
        assertThrows(IllegalArgumentException::class.java) {standaloneRefund(source,staff,"Again",refunds=listOf(first,remaining))}
    }
    @Test fun repeatedSingleQuantitiesPreserveEveryPennyAndOriginal() {
        val source=original();val before=source.toString();val refunds=mutableListOf<JSONObject>()
        repeat(3) {refunds+=standaloneRefund(source,staff,"Returned",refunds=refunds,quantities=mapOf("a" to 1)).put("status","APPROVED")}
        assertEquals(listOf(333L,334L,333L),refunds.map {it.getLong("amountMinor")})
        assertEquals(167L,refunds.sumOf {it.objects("saleItems").single().getLong("vatMinor")});assertEquals(before,source.toString())
        assertThrows(IllegalArgumentException::class.java) {standaloneRefund(source,staff,"Again",refunds=refunds,quantities=mapOf("a" to 1))}
    }
    @Test fun cancelledAndUnrelatedRefundsDoNotConsumeBalance() {
        val source=original("CARD")
        val cancelled=standaloneRefund(source,staff,"Returned",quantities=mapOf("b" to 1)).put("status","CANCELLED")
        val other=JSONObject(cancelled.toString()).put("status","APPROVED").put("originalTransactionId","other")
        val retry=standaloneRefund(source,staff,"Returned",refunds=listOf(cancelled,other),quantities=mapOf("b" to 1))
        assertEquals(900L,retry.getLong("amountMinor"));assertEquals(900L,retry.getLong("cardAmountMinor"))
        assertEquals(900L,retry.objects("tenders").single().getLong("amountMinor"));assertEquals(-150L,retry.objects("refundItems").single().getLong("vatMinor"))
    }
    @Test fun invalidAndStaleSelectionsRejected() {
        val source=original()
        for(selection in listOf(emptyMap(),mapOf("a" to 4),mapOf("missing" to 1),mapOf("a" to -1))) {
            assertThrows(IllegalArgumentException::class.java) {standaloneRefund(source,staff,"Returned",quantities=selection)}
        }
        val first=standaloneRefund(source,staff,"Returned",quantities=mapOf("a" to 2)).put("status","APPROVED")
        assertThrows(IllegalArgumentException::class.java) {standaloneRefund(source,staff,"Returned",refunds=listOf(first),quantities=mapOf("a" to 2))}
    }
    @Test fun partialRefundReceiptAndDayTotalsContainOnlySelectedItems() {
        val source=original().put("tradingDayId","day")
        val refund=standaloneRefund(source,staff,"Returned",quantities=mapOf("b" to 1)).put("status","APPROVED").put("tradingDayId","day")
        assertEquals(listOf("b"),refund.objects("saleItems").map {it.getString("id")})
        assertTrue(saleReceipt(refund).contains("£9.00"))
        val report=dayReport(JSONObject().put("id","day").put("openingFloatMinor",0),listOf(source,refund))
        assertEquals(1000L,report.getLong("expectedCashMinor"));assertEquals(167L,report.getLong("vatTotalMinor"))
    }
}
