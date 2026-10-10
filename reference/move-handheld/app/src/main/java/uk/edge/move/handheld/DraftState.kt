package uk.edge.move.handheld

import com.edge.poslite.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

internal fun ServiceMode.usesTakeawayPricing() = this == ServiceMode.TAKEAWAY || this == ServiceMode.OUTSIDE
internal fun ServiceMode.label() = when(this) { ServiceMode.RESTAURANT -> "Eat in"; ServiceMode.TAKEAWAY -> "Takeaway"; ServiceMode.OUTSIDE -> "Outside" }
data class LinePricing(val base: Int, val discount: Int, val standardVat: Int, val takeawayVat: Int) {
    fun apply(line: OrderLine, mode: ServiceMode): OrderLine {
        require(base >= 0 && discount in 0..base && standardVat in 0..100 && takeawayVat in 0..100)
        val price = if (mode.usesTakeawayPricing()) base - discount else base
        return line.copy(unitPricePence=price, baseUnitPricePence=base, takeawayDiscountPence=discount,
            vatRatePercent=if (mode.usesTakeawayPricing()) takeawayVat else standardVat,
            totalPence=Math.multiplyExact(Math.addExact(price,line.modifierTotalPence),line.quantity))
    }
}

internal fun OrderingDraft.encode(): JSONObject = JSONObject().put("draftVersion",4).put("mode",serviceMode.name)
    .put("mealPricing",mealPricing?.encode()).put("sharedState",sharedState).put("productParents",JSONObject(productParents.mapKeys { it.key.toString() })).put("selectedProductLineId",selectedProductLineId).put("tableId",tableId).put("reference",orderReference).put("lines",JSONArray(lines.map { line ->
        JSONObject().put("id",line.id).put("productId",line.productId).put("name",line.productName)
            .put("price",line.unitPricePence).put("vat",line.vatRatePercent).put("total",line.totalPence)
            .put("quantity",line.quantity).put("modifierTotal",line.modifierTotalPence)
            .put("base",line.baseUnitPricePence).put("discount",line.takeawayDiscountPence)
            .put("pricing",pricing[line.id]?.let { p -> JSONObject().put("base",p.base).put("discount",p.discount).put("standardVat",p.standardVat).put("takeawayVat",p.takeawayVat) })
            .put("modifiers",JSONArray(line.modifiers.map { m ->
                JSONObject().put("id",m.id).put("source",m.sourceType.name).put("type",m.optionType.name)
                    .put("optionId",m.optionId).put("groupId",m.modifierGroupId).put("group",m.modifierGroupName)
                    .put("parentId",m.parentOptionId).put("token",m.parentOptionName).put("itemId",m.selectedItemId)
                    .put("name",m.selectedItemName).put("price",m.priceDeltaPence).put("sequence",m.selectionSequence)
                    .put("included",m.isIncluded).put("placement",m.ticketNamePlacement.name).put("prefix",m.ticketNamePrefix)
            }))
    }))

internal fun decodeDraft(data: JSONObject): OrderingDraft = OrderingDraft().apply {
    require(data.optInt("draftVersion",1) in 1..4) { "Unsupported basket version" }
    sharedState = data.optJSONObject("sharedState")
    serviceMode = ServiceMode.valueOf(data.optString("mode","RESTAURANT"))
    tableId = data.optString("tableId").takeIf { it.isNotBlank() }
    orderReference = data.optString("reference").takeIf { it.isNotBlank() }
    require(tableId == null || serviceMode == ServiceMode.RESTAURANT)
    lines += data.objects("lines").map { l ->
        val line = OrderLine(l.getLong("id"),l.getLong("productId"),l.getString("name"),l.getInt("price"),null,null,l.optInt("quantity",1),null,
            l.getInt("vat"),l.objects("modifiers").map { m ->
                OrderLineModifier(m.getLong("id"),ModifierSourceType.valueOf(m.optString("source","MANUAL")),ModifierOptionType.valueOf(m.optString("type","STANDARD")),
                    m.getLong("optionId"),m.getLong("groupId"),m.getString("group"),if(m.isNull("parentId")) null else m.getLong("parentId"),
                    if(m.isNull("token")) null else m.getString("token"),if(m.isNull("itemId")) null else m.getLong("itemId"),m.getString("name"),m.getInt("price"),
                    selectionSequence=m.optInt("sequence"),isIncluded=m.optBoolean("included"),
                    ticketNamePlacement=ModifierTicketNamePlacement.valueOf(m.optString("placement","DETAIL")),ticketNamePrefix=m.optString("prefix"))
            },l.getInt("total"),l.getInt("modifierTotal"),l.optInt("base",l.getInt("price")),l.optInt("discount",0))
        require(line.quantity > 0 && line.unitPricePence >= 0 && line.vatRatePercent in 0..100)
        require(line.modifierTotalPence == line.modifiers.fold(0) { n,m -> Math.addExact(n,m.priceDeltaPence) })
        require(line.totalPence == Math.multiplyExact(Math.addExact(line.unitPricePence,line.modifierTotalPence),line.quantity))
        l.optJSONObject("pricing")?.let { p ->
            val rule = LinePricing(p.getInt("base"),p.getInt("discount"),p.getInt("standardVat"),p.getInt("takeawayVat"))
            require(rule.apply(line,serviceMode) == line) { "Basket pricing snapshot mismatch" }
            pricing[line.id] = rule
        }
        line
    }
    data.optJSONObject("productParents")?.let { parents -> parents.keys().forEach { key -> productParents[key.toLong()] = parents.getLong(key) } }
    require(productParents.all { (child,parent) -> child != parent && lines.any { it.id == child && it.quantity == 1 } && lines.any { it.id == parent && it.quantity == 1 } && parent !in productParents }) { "Invalid product attachments" }
    selectedProductLineId = if (data.isNull("selectedProductLineId")) null else data.optLong("selectedProductLineId").takeIf { id -> lines.any { it.id == id } && id !in productParents }
    mealPricing = data.optJSONObject("mealPricing")?.let(DraftMealPricing::decode)
    mealPricing?.let { require(it.lineIds.keys == lines.map { l -> l.id }.toSet()); it.applications(this) }
    val ids = lines.flatMap { listOf(it.id) + it.modifiers.map { m -> m.id } }
    require(ids.all { it > 0 } && ids.distinct().size == ids.size) { "Invalid basket identities" }
    nextId = Math.addExact(ids.maxOrNull() ?: 0L,1L)
}
