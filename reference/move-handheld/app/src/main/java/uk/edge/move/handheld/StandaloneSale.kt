package uk.edge.move.handheld

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode

internal fun standaloneSale(draft:OrderingDraft,cat:Catalogue,config:JSONObject,staff:Staff,terminalCode:String,tender:String,cashReceived:Long=draft.total,now:Long=System.currentTimeMillis(),id:String=UUID.randomUUID().toString(),cashParts:List<JSONObject> = emptyList()):JSONObject {
    require(terminalCode.isNotBlank()) {"Terminal pairing is missing."}
    require(draft.sharedState==null) {"Leave the shared table before starting a standalone sale."}
    require(draft.lines.isNotEmpty() && draft.total in 1..100_000_000) {"Add items before payment."}
    require(staff.allowed("takePaymentEnabled")) {"Payment permission required."}
    require(tender in setOf("CASH","CARD")) {"This tender is not connected yet."}
    require(config.objects("tenders").any { it.optString("type")==tender && it.optBoolean("enabled") }) {"Tender is disabled."}
    require(cashParts.all {it.optString("type")=="CASH" && it.optLong("amountMinor")>0}) {"Invalid cash entries."}
    val priorCash=cashParts.sumOf {it.getLong("amountMinor")}
    val due=draft.total-priorCash
    require(due>0) {"There is no balance to pay."}
    require(tender!="CASH" || cashReceived>=due) {"Cash received is less than the balance."}
    val tenders=cashParts.map {JSONObject(it.toString())}+JSONObject().put("type",tender).put("label",if(tender=="CARD") "Card" else "Cash").put("amountMinor",due)

    val lines=draft.paidLines.map { line ->
        val product=cat.products.singleOrNull {it.model.id==line.productId} ?: error("Product is no longer in this menu. Keep the basket and sync.")
        val vat=BigDecimal(line.totalPence).multiply(BigDecimal(line.vatRatePercent)).divide(BigDecimal(100+line.vatRatePercent),0,RoundingMode.HALF_UP).toInt()
        JSONObject().put("id","$id:${line.id}").put("productId",product.remoteId).put("productName",line.productName)
            .put("parentLineId",draft.productParents[line.id]?.let { "$id:$it" }).put("quantity",line.quantity).put("unitPriceMinor",line.unitPricePence).put("baseUnitPriceMinor",line.baseUnitPricePence)
            .put("takeawayDiscountMinor",line.takeawayDiscountPence*line.quantity).put("grossMinor",line.totalPence)
            .put("totalMinor",line.totalPence).put("netMinor",line.totalPence-vat).put("vatMinor",vat).put("taxRatePercent",line.vatRatePercent)
            .put("taxLabel","${line.vatRatePercent}%").put("lineType","PRODUCT")
            .put("modifiers",JSONArray(line.modifiers.map { m -> JSONObject().put("id",m.id).put("name",m.selectedItemName)
                .put("optionName",m.selectedItemName).put("groupId",m.modifierGroupId).put("groupName",m.modifierGroupName)
                .put("optionId",m.optionId).put("priceMinor",m.priceDeltaPence).put("priceDeltaMinor",m.priceDeltaPence)
                .put("quantity",line.quantity).put("grossMinor",m.priceDeltaPence*line.quantity)
                .put("ticketNamePlacement",m.ticketNamePlacement.name).put("ticketNamePrefix",m.ticketNamePrefix)
                .put("isIncluded",m.isIncluded).put("selectionSequence",m.selectionSequence) }))
    }
    return JSONObject().put("id",id).put("merchantId",cat.merchantCode).put("terminalId",terminalCode)
        .put("simulated",config.optBoolean("handheldFixture")).put("productType","HANDHELD").put("type","SALE").put("status","PENDING").put("amountMinor",draft.total)
        .put("createdAt",now).put("tradingDate",SimpleDateFormat("yyyy-MM-dd",Locale.UK).format(Date(now)))
        .put("tableId",draft.tableId).put("tableScope",if(draft.tableId!=null) "DEVICE" else JSONObject.NULL)
        .put("reference",draft.orderReference ?: id.take(8).uppercase(Locale.UK)).put("serviceMode",draft.serviceMode.name)
        .put("staffId",staff.id).put("staffName",staff.name).put("storeId",cat.storeId).put("locationId",cat.locationId)
        .put("mealDeals",mealApplicationsJson(draft.mealApplications)).put("saleItems",JSONArray(lines)).put("tenders",JSONArray(tenders)).put("cardRequestAmountMinor",if(tender=="CARD") due else 0)
        .put("cashAmountMinor",priorCash+if(tender=="CASH") due else 0).put("cardAmountMinor",if(tender=="CARD") due else 0)
        .put("cashReceivedMinor",cashParts.sumOf {it.optLong("receivedMinor",it.getLong("amountMinor"))}+if(tender=="CASH") cashReceived else 0).put("changeMinor",if(tender=="CASH") cashReceived-due else 0)
        .put("receipt",config.optJSONObject("receipt") ?: JSONObject())
}
internal fun formatMinor(value:Long)="${if(value<0) "−" else ""}£${kotlin.math.abs(value)/100}.${(kotlin.math.abs(value)%100).toString().padStart(2,'0')}"
internal fun saleReceipt(sale:JSONObject):String = ReceiptText().apply {
    val refunds=sale.objects("receiptRefunds")
    val r=sale.optJSONObject("receipt") ?: JSONObject()
    center(r.optString("businessName").ifBlank {"Move"})
    listOf("addressLine1","addressLine2","phoneNumber").forEach {key->r.optString(key).takeIf {it.isNotBlank()}?.let {center(if(key=="phoneNumber") "Tel $it" else it)}}
    if(r.optBoolean("vatRegistered")) r.optString("vatNumber").takeIf {it.isNotBlank()}?.let {center("VAT No $it")}
    line();if(sale.optBoolean("simulated")) center("SIMULATED - NOT A SALE") else if(BuildConfig.IS_STAGING) center("STAGING")
    if(sale.optBoolean("loyaltyTest")) center("LOYALTY TEST - REAL CARD PAYMENT")
    center(if(sale.optString("type")=="REFUND") "REFUND RECEIPT" else "RECEIPT")
    if(refunds.isNotEmpty()) center(if(refunds.sumOf {it.getLong("amountMinor")}>=sale.getLong("amountMinor")) "REFUNDED ORDER" else "PART REFUNDED ORDER")
    rule()
    line("Order number ${sale.optString("reference")}")
    line("Time ${SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.UK).format(Date(sale.getLong("createdAt")))}")
    sale.optString("serviceMode").takeIf {it.isNotBlank()}?.let {
        line("Type ${if(it=="RESTAURANT" || it=="EAT_IN") "EAT IN" else it.uppercase(Locale.UK).replace('_',' ')}")
    }
    line("Server ${sale.optString("staffName")}");rule()
    sale.objects("saleItems").forEach {item->
        row("${item.getInt("quantity")} x ${item.getString("productName")}",formatMinor(item.optLong("originalGrossMinor",item.getLong("grossMinor"))))
        item.objects("modifiers").forEach {line("+ ${it.getString("name")}")}
        if(item.optLong("takeawayDiscountMinor")>0) row("Takeaway discount",formatMinor(-item.getLong("takeawayDiscountMinor")))
        line()
    }
    rule()
    val vat=sale.objects("saleItems").sumOf {it.optLong("vatMinor")}
    if(r.optBoolean("vatRegistered") && vat>0) {
        row("Subtotal",formatMinor(sale.getLong("amountMinor")-vat))
        row("VAT",formatMinor(vat))
    }
    if(sale.has("originalBasketMinor")) {
        row("Basket subtotal",formatMinor(sale.getLong("originalBasketMinor")))
        row("Loyalty discount",formatMinor(-sale.getLong("loyaltyDiscountMinor")))
    }
    row("TOTAL",formatMinor(sale.getLong("amountMinor")));line()
    sale.objects("tenders").forEach {
        row(receiptTenderLabel(it,sale),formatMinor(it.getLong("amountMinor")))
        if(it.optString("type")=="CARD") compactPaymentDetails(sale).forEach(::line)
    }
    if(sale.optLong("cashReceivedMinor")>0) {row("Cash received",formatMinor(sale.getLong("cashReceivedMinor")));row("Change",formatMinor(sale.getLong("changeMinor")))}
    if(sale.optString("type")=="REFUND") {line();line("Reason: ${sale.optString("refundReason")}")}
    if(refunds.isNotEmpty()) {
        section("REFUNDS")
        refunds.forEach {entry ->
            row("Refund",entry.optString("reference"))
            row("Time",SimpleDateFormat("dd/MM/yy HH:mm",Locale.UK).format(Date(entry.getLong("createdAt"))))
            entry.objects("tenders").forEach {tender -> row("Refunded to ${tender.optString("label",tender.optString("type")).lowercase(Locale.UK)}",formatMinor(tender.getLong("amountMinor")))}
            line("Reason: ${entry.optString("refundReason")}")
        }
        val refunded=refunds.sumOf {it.getLong("amountMinor")}
        row("Total refunded",formatMinor(refunded))
        row("Remaining paid",formatMinor((sale.getLong("amountMinor")-refunded).coerceAtLeast(0)))
    }
    rule();center(r.optString("footerText").ifBlank {"Thank you"});repeat(4) { line() }
}.toString()

/** Customer sales receipt only; never derive payment approval from receipt text. */
internal fun receiptTenderLabel(tender:JSONObject,sale:JSONObject):String {
    if(tender.optString("type")!="CARD") return tender.optString("label",tender.optString("type"))
    val lines=sale.optString("cardholderReceipt").lines().map {it.trim()}
    // Accept only known card labels and fully masked account lines, never arbitrary processor fields.
    val card=lines.firstOrNull {it.matches(Regex("(?i)(?:(?:Debit|Credit) )?(?:Visa|Mastercard|Maestro|American Express|Amex|Discover|Diners Club|JCB|UnionPay)(?: (?:Debit|Credit|Electron))?"))}
    val masked=lines.firstOrNull {it.matches(Regex("[\\*xX •-]{4,}[0-9]{4}"))}
    val last4=masked?.takeLast(4)
    return listOfNotNull(card,last4?.let {"****$it"}).joinToString(" ").ifBlank {"Card"}
}

internal fun compactPaymentDetails(sale:JSONObject):List<String> = buildList {
    val lines=sale.optString("cardholderReceipt").lines().map {it.trim()}
    lines.firstOrNull {it in listOf("CONTACTLESS","ICC","CHIP AND PIN","MAGSTRIPE")}
        ?.let {add("Method ${if(it=="ICC") "CHIP" else it}")}
    lines.firstOrNull {it.matches(Regex("(?i)AUTH CODE: *[A-Z0-9]{1,12}"))}
        ?.substringAfter(':')?.trim()?.let {add("Auth code $it")}
    sale.optString("transactionId").takeIf {it.isNotBlank() && it!="null"}
        ?.let {add("Reference $it")}
}

/** Print projection only: never mutate the saved order or refund records. */
internal fun updatedOrderReceipt(original:JSONObject,refunds:List<JSONObject>):JSONObject =
    JSONObject(original.toString()).put("receiptRefunds",JSONArray(refunds.filter {
        it.optString("type")=="REFUND" && it.optString("status")=="APPROVED" && it.optString("originalTransactionId")==original.getString("id")
    }.sortedBy {it.getLong("createdAt")}))

/** Keep the processor's customer-copy fields/wording intact; never substitute merchant data. */
internal fun customerCardReceipt(sale:JSONObject):String {
    if(sale.objects("tenders").none {it.optString("type")=="CARD"}) return ""
    val returned=sale.optString("cardholderReceipt").takeIf {it.isNotBlank() && it!="null"} ?: return ""
    return "\n"+"-".repeat(32)+"\n          CARD RECEIPT\n"+"-".repeat(32)+"\n"+formatCustomerCardCopy(returned)
}

/** Refund quantities are tied to immutable original sale line IDs, not current menu prices. */
internal fun refundableQuantities(original:JSONObject,refunds:List<JSONObject>):Map<String,Int> {
    val completed=refunds.filter {it.optString("type")=="REFUND" && it.optString("status")=="APPROVED" && it.optString("originalTransactionId")==original.getString("id")}
    return original.objects("saleItems").associate {line ->
        val id=line.getString("id")
        val used=completed.sumOf {refund -> refund.objects("saleItems").filter {it.optString("id")==id}.sumOf {it.getInt("quantity")} }
        id to (line.getInt("quantity")-used).coerceAtLeast(0)
    }
}

internal fun selectedRefundLines(original:JSONObject,refunds:List<JSONObject>,quantities:Map<String,Int>):List<JSONObject> {
    val remaining=refundableQuantities(original,refunds)
    require(quantities.isNotEmpty() && quantities.values.any {it>0}) {"Choose items to refund."}
    require(quantities.all {(id,quantity)->id in remaining && quantity>=0 && quantity<=remaining.getValue(id)}) {"Refund quantity exceeds the remaining items."}
    return original.objects("saleItems").mapNotNull {line ->
        val id=line.getString("id");val quantity=quantities[id] ?: 0
        if(quantity==0) return@mapNotNull null
        val originalQuantity=line.getInt("quantity");require(originalQuantity>0)
        val used=originalQuantity-remaining.getValue(id)
        // Cumulative rounding makes repeated quantity refunds add up to the exact original pennies.
        fun portion(value:Long):Long {
            fun cumulative(count:Int)=BigDecimal(value).multiply(BigDecimal(count)).divide(BigDecimal(originalQuantity),0,RoundingMode.HALF_UP).longValueExact()
            return cumulative(used+quantity)-cumulative(used)
        }
        val gross=portion(line.getLong("grossMinor"));val vat=portion(line.optLong("vatMinor"))
        JSONObject(line.toString()).put("quantity",quantity).put("grossMinor",gross).put("totalMinor",gross).put("vatMinor",vat).put("netMinor",gross-vat)
            .put("takeawayDiscountMinor",portion(line.optLong("takeawayDiscountMinor")))
            .put("modifiers",JSONArray(line.objects("modifiers").map {modifier -> JSONObject(modifier.toString()).put("quantity",quantity).apply {
                if(has("grossMinor")) put("grossMinor",portion(modifier.optLong("grossMinor")))
            }}))
    }
}

/** Match Counter: consume each original tender type in payment order, less prior approved refunds. */
internal fun refundTenderAllocation(original:JSONObject,refunds:List<JSONObject>,amount:Long):List<JSONObject> {
    require(amount>0) {"Choose a refund amount."}
    val paid=original.objects("tenders")
    require(paid.isNotEmpty() && paid.all {it.optString("type") in setOf("CASH","CARD") && it.getLong("amountMinor")>0}) {"Original tender is not supported."}
    val completed=refunds.filter {it.optString("type")=="REFUND" && it.optString("status")=="APPROVED" && it.optString("originalTransactionId")==original.getString("id")}
    val capacities=paid.groupBy {it.getString("type")}.mapValues {(type,entries) ->
        val returned=completed.sumOf {it.objects("tenders").filter {t->t.optString("type")==type}.sumOf {t->t.getLong("amountMinor")} }
        (entries.sumOf {it.getLong("amountMinor")}-returned).coerceAtLeast(0)
    }.toMutableMap()
    var remaining=amount
    val allocated=paid.mapNotNull {tender ->
        val type=tender.getString("type");val portion=minOf(remaining,capacities.getValue(type))
        if(portion==0L) return@mapNotNull null
        remaining-=portion;capacities[type]=capacities.getValue(type)-portion
        JSONObject().put("type",type).put("label",if(type=="CASH") "Cash" else "Card").put("amountMinor",portion)
    }
    require(remaining==0L) {"Refund exceeds the remaining original tender."}
    return allocated
}

internal fun standaloneRefund(original:JSONObject,staff:Staff,reason:String,now:Long=System.currentTimeMillis(),refunds:List<JSONObject> = emptyList(),quantities:Map<String,Int> = refundableQuantities(original,refunds)):JSONObject {
    require(staff.allowed("refundsEnabled")) {"Refund permission required."}
    require(original.optString("type")=="SALE" && original.optString("status")=="APPROVED") {"Choose a completed sale."}
    require(reason.trim().isNotEmpty()) {"Enter a refund reason."}
    val lines=selectedRefundLines(original,refunds,quantities)
    val amount=lines.sumOf {it.getLong("grossMinor")}
    require(amount>0) {"There is no remaining value to refund."}
    val refunded=refunds.filter {it.optString("type")=="REFUND" && it.optString("status")=="APPROVED" && it.optString("originalTransactionId")==original.getString("id")}.sumOf {it.getLong("amountMinor")}
    require(amount<=original.getLong("amountMinor")-refunded) {"Refund exceeds the remaining order value."}
    val allocated=refundTenderAllocation(original,refunds,amount)
    val cash=allocated.filter {it.getString("type")=="CASH"}.sumOf {it.getLong("amountMinor")}
    val card=allocated.filter {it.getString("type")=="CARD"}.sumOf {it.getLong("amountMinor")}
    val refund=JSONObject(original.toString())
    listOf("processorResult","transactionId","cardholderReceipt","cardRequestAmountMinor","mealDeals").forEach(refund::remove)
    val id=UUID.randomUUID().toString()
    return refund.put("id",id).put("type","REFUND").put("status","PENDING").put("amountMinor",amount)
        .put("originalTransactionId",original.getString("id")).put("originalProviderTransactionId",original.optString("transactionId"))
        .put("reference","R-"+id.take(8).uppercase(Locale.UK)).put("refundReason",reason.trim())
        .put("staffId",staff.id).put("staffName",staff.name).put("createdAt",now)
        .put("tradingDate",SimpleDateFormat("yyyy-MM-dd",Locale.UK).format(Date(now)))
        .put("saleItems",JSONArray(lines))
        .put("refundItems",JSONArray(lines.map { line -> JSONObject(line.toString()).apply {
            put("lineType","REFUND")
            listOf("grossMinor","netMinor","vatMinor","totalMinor").forEach { key -> put(key,-kotlin.math.abs(optLong(key))) }
        } }))
        .put("tenders",JSONArray(allocated))
        .put("cashAmountMinor",cash).put("cardAmountMinor",card).put("cardRequestAmountMinor",card)
        .put("cashReceivedMinor",0).put("changeMinor",0)
}
