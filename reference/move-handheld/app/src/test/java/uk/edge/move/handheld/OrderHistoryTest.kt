package uk.edge.move.handheld
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class OrderHistoryTest {
    private fun sale()=JSONObject().put("id","original").put("reference","ORDER1").put("type","SALE").put("status","APPROVED").put("amountMinor",840).put("createdAt",1).put("tradingDate","2026-09-29")
    private fun refund(amount:Long,status:String="APPROVED")=JSONObject().put("id","refund-$amount").put("originalTransactionId","original").put("type","REFUND").put("status",status).put("amountMinor",amount).put("createdAt",2).put("tradingDate","2026-09-30")
    @Test fun fullRefundUpdatesOriginalOrderWithoutSecondTicket() {
        val original=sale();val before=original.toString()
        val rows=orderHistory(listOf(original,refund(840)))
        assertEquals(1,rows.size);assertEquals("original",rows.single().getString("id"));assertEquals("ORDER1",rows.single().getString("reference"))
        assertEquals("Refunded",rows.single().getString("refundStatus"));assertEquals(840L,rows.single().getLong("refundedMinor"));assertEquals(0L,rows.single().getLong("remainingRefundableMinor"))
        assertEquals(840L,rows.single().getLong("amountMinor"));assertEquals(before,original.toString())
    }
    @Test fun severalPartialRefundsRollUpAndDeclineDoesNotCount() {
        val row=orderHistory(listOf(sale(),refund(210),refund(160),refund(470,"DECLINED"))).single()
        assertEquals("Part refunded",row.getString("refundStatus"));assertEquals(370L,row.getLong("refundedMinor"));assertEquals(470L,row.getLong("remainingRefundableMinor"))
    }
    @Test fun refundTodayShowsOriginalOrderEvenIfSoldYesterday() {
        val rows=orderHistory(listOf(sale(),refund(210)),"2026-09-30")
        assertEquals("original",rows.single().getString("id"));assertTrue(orderHistory(listOf(sale()),"2026-09-30").isEmpty())
    }
    @Test fun recentLimitCountsOrdersNotRefunds() {
        val entries=(1..105).map {sale().put("id","order-$it").put("createdAt",it)}+refund(840)
        assertEquals(100,orderHistory(entries).size);assertTrue(orderHistory(entries).all {it.getString("type")=="SALE"})
    }
}
