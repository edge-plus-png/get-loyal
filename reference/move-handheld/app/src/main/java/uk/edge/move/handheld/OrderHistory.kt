package uk.edge.move.handheld

import org.json.JSONObject

/** One row per original order. Refund transactions remain immutable accounting records. */
internal fun orderHistory(entries:List<JSONObject>,date:String?=null):List<JSONObject> {
    val refunds=entries.filter {it.optString("type")=="REFUND" && it.optString("status")=="APPROVED"}.groupBy {it.optString("originalTransactionId")}
    val orders=entries.filter {it.optString("type")=="SALE" && it.optString("status")=="APPROVED"}.mapNotNull {sale ->
        val returned=refunds[sale.getString("id")].orEmpty()
        if(date!=null && sale.optString("tradingDate")!=date && returned.none {it.optString("tradingDate")==date}) return@mapNotNull null
        val refunded=returned.sumOf {it.getLong("amountMinor")}
        val remaining=(sale.getLong("amountMinor")-refunded).coerceAtLeast(0)
        JSONObject(sale.toString()).put("refundedMinor",refunded).put("remainingRefundableMinor",remaining)
            .put("refundStatus",if(refunded==0L) "" else if(remaining==0L) "Refunded" else "Part refunded")
            .put("lastActivityAt",maxOf(sale.getLong("createdAt"),returned.maxOfOrNull {it.getLong("createdAt")} ?: 0))
    }.sortedByDescending {it.getLong("lastActivityAt")}
    return if(date==null) orders.take(100) else orders
}
