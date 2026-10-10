package uk.edge.move.handheld

import org.json.JSONObject
import java.math.BigInteger

/** Test-only full-card sale reconciliation. Does not infer a processor approval. */
internal object LoyaltyReconciliation {
    fun reconcile(pending: JSONObject): JSONObject {
        val result = requireNotNull(pending.optJSONObject("processorResult")) { "No structured payment amount received." }
        require(result.optString("status") == "APPROVED")
        require(!result.isNull("amountMinor") && !result.isNull("discountMinor")) { "PAX payment amount or loyalty discount missing." }
        val original = pending.getLong("amountMinor")
        val paid = result.getLong("amountMinor")
        val discount = result.getLong("discountMinor")
        require(original in 1..100_000_000 && paid in 1..original && discount in 0 until original && paid == original - discount) {
            "PAX payment and loyalty discount do not match the basket. Check the terminal; do not retry payment."
        }
        require(pending.optString("type") == "SALE" && pending.optLong("cashAmountMinor") == 0L)
        require(pending.objects("tenders").size == 1 && pending.objects("tenders").single().getString("type") == "CARD")
        val sale = JSONObject(pending.toString())
        val lines = sale.objects("saleItems")
        require(lines.isNotEmpty() && lines.all { it.getLong("grossMinor") > 0 } && lines.sumOf { it.getLong("grossMinor") } == original)
        val allocations = lines.map { BigInteger.valueOf(it.getLong("grossMinor")).multiply(BigInteger.valueOf(discount)).divideAndRemainder(BigInteger.valueOf(original)) }
        val shares = allocations.map { it[0].toLong() }.toMutableList()
        val remainder = discount - shares.sum()
        allocations.indices.sortedWith(compareByDescending<Int> { allocations[it][1] }.thenBy { it }).take(remainder.toInt()).forEach { shares[it]++ }
        lines.forEachIndexed { i, line ->
            val gross = line.getLong("grossMinor")
            val netGross = gross - shares[i]
            val rate = line.optLong("taxRatePercent")
            require(rate in 0..100)
            val vat = (netGross * rate + (100 + rate) / 2) / (100 + rate)
            line.put("originalGrossMinor", gross).put("loyaltyDiscountMinor", shares[i])
                .put("grossMinor", netGross).put("totalMinor", netGross).put("vatMinor", vat).put("netMinor", netGross - vat)
        }
        sale.getJSONArray("tenders").getJSONObject(0).put("amountMinor", paid)
        return sale.put("originalBasketMinor", original).put("loyaltyDiscountMinor", discount)
            .put("amountMinor", paid).put("cardAmountMinor", paid).put("loyaltyReconciled", true)
    }
}
