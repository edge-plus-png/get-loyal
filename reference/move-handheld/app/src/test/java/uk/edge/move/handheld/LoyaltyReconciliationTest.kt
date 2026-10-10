package uk.edge.move.handheld

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoyaltyReconciliationTest {
    private fun pending(paid: Long = 900, discount: Long = 100) = JSONObject("""{"type":"SALE","amountMinor":1000,"cashAmountMinor":0,"tenders":[{"type":"CARD","amountMinor":1000}],"saleItems":[{"productName":"Test","quantity":1,"grossMinor":1000,"taxRatePercent":20}],"processorResult":{"status":"APPROVED","amountMinor":$paid,"discountMinor":$discount}}""")
    @Test fun approvedDiscountBalancesAndDoesNotMutateOriginal() {
        val original=pending();val sale=LoyaltyReconciliation.reconcile(original)
        assertEquals(1000L,sale.getLong("originalBasketMinor"));assertEquals(100L,sale.getLong("loyaltyDiscountMinor"))
        assertEquals(900L,sale.getLong("amountMinor"));assertEquals(900L,sale.getJSONArray("tenders").getJSONObject(0).getLong("amountMinor"))
        val line=sale.getJSONArray("saleItems").getJSONObject(0)
        assertEquals(150L,line.getLong("vatMinor"));assertEquals(750L,line.getLong("netMinor"))
        assertEquals(1000L,original.getLong("amountMinor"));assertEquals(1000L,original.getJSONArray("saleItems").getJSONObject(0).getLong("grossMinor"))
    }
    @Test fun ordinaryApprovalHasNoDiscount() { assertEquals(1000L,LoyaltyReconciliation.reconcile(pending(1000,0)).getLong("amountMinor")) }
    @Test fun missingAndInconsistentAmountsRejected() {
        for(p in listOf(pending(900,0),pending(1001,0),pending(900,-1),pending(0,1000),pending().apply {getJSONObject("processorResult").remove("discountMinor")},pending().apply {getJSONObject("processorResult").remove("amountMinor")})) {
            assertTrue(runCatching {LoyaltyReconciliation.reconcile(p)}.isFailure)
        }
    }
    @Test fun declineCancellationAndMixedTenderNeverReconciledAsPaid() {
        for(status in listOf("DECLINED","CANCELLED")) assertTrue(runCatching {LoyaltyReconciliation.reconcile(pending().apply {getJSONObject("processorResult").put("status",status)})}.isFailure)
        assertTrue(runCatching {LoyaltyReconciliation.reconcile(pending().put("cashAmountMinor",100))}.isFailure)
    }
    @Test fun roundingAllocatesEveryPennyAndReceiptShowsImpact() {
        val p=pending(999,1).put("saleItems",org.json.JSONArray("""[{"productName":"A","quantity":1,"grossMinor":333},{"productName":"B","quantity":1,"grossMinor":667}]"""))
        val sale=LoyaltyReconciliation.reconcile(p).put("createdAt",1).put("staffName","Test").put("reference","TEST")
        assertEquals(999L,sale.objects("saleItems").sumOf {it.getLong("grossMinor")})
        assertEquals(1L,sale.objects("saleItems").sumOf {it.getLong("loyaltyDiscountMinor")})
        val receipt=saleReceipt(sale)
        assertTrue(receipt.contains("Basket subtotal"));assertTrue(receipt.contains("Loyalty discount"));assertTrue(receipt.contains("£9.99"))
    }
}
