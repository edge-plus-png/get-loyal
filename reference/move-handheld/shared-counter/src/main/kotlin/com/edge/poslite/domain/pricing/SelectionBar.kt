package com.edge.poslite.domain.pricing

import org.json.JSONObject
import com.edge.poslite.domain.model.*

// Stored in the catalogue; order snapshots use existing persisted modifier records.
data class MeasureButton(val id: String, val label: String, val kind: String, val pricePence: Int?, val showInBar: Boolean, val selectionMode: String = "COUNT", val selectionCount: Int = 0, val allowRepeats: Boolean = false, val eligibleProductIds: List<Long> = emptyList())
data class ProductSelectionBar(val defaultMeasureId: String, val buttons: List<MeasureButton>, val basePricePence: Int = 0)
data class SelectedMeasure(val pricePence: Int, val modifiers: List<ModifierSelection>)
const val SELECTION_MEASURE_GROUP = -900001L
const val SELECTION_EXTRA_GROUP = -900002L

fun parseSelectionBar(json: String): ProductSelectionBar? {
    if (json.isBlank()) return null
    val root = JSONObject(json)
    require(root.getInt("version") == 1) { "Unsupported selection bar version" }
    val array = root.getJSONArray("buttons")
    require(array.length() in 1..12)
    val buttons = (0 until array.length()).map { index ->
        val b = array.getJSONObject(index)
        val price = if (b.optBoolean("available") && !b.isNull("priceMinor")) b.getInt("priceMinor") else null
        require(price == null || price in 0..100000000)
        val kind = b.getString("kind")
        require(kind in setOf("MEASURE", "EXTRA", "DEAL"))
        val eligible = b.optJSONArray("eligibleProductIds")
        val ids = if (eligible == null) emptyList() else (0 until eligible.length()).map { eligible.getLong(it) }
        val count = b.optInt("selectionCount", 0)
        val mode = b.optString("selectionMode", "COUNT")
        if (kind == "DEAL") {
            require(mode in setOf("COUNT", "ALL") && ids.isNotEmpty() && ids.distinct().size == ids.size && count in 1..250)
            require(mode != "ALL" || count == ids.size)
            require(b.optBoolean("allowRepeats", false) || count <= ids.size)
        }
        MeasureButton(b.getString("id"), b.getString("label"), kind, price, b.optBoolean("showInBar", true), mode, count, mode == "COUNT" && b.optBoolean("allowRepeats", false), ids)
    }
    require(buttons.map { it.id }.distinct().size == buttons.size)
    val default = if (root.isNull("defaultMeasureId")) "" else root.optString("defaultMeasureId", "")
    require(if (buttons.any { it.kind == "MEASURE" }) buttons.any { it.id == default && it.kind == "MEASURE" && it.pricePence != null } else default.isEmpty())
    return ProductSelectionBar(default, buttons, root.optInt("basePriceMinor", 0).also { require(it in 0..100000000) })
}

fun selectMeasure(bar: ProductSelectionBar, measureId: String?, extraId: String?): SelectedMeasure {
    val measure = bar.buttons.singleOrNull { it.id == (measureId ?: bar.defaultMeasureId) && it.kind == "MEASURE" && it.pricePence != null }
        ?: if (measureId == null && bar.defaultMeasureId.isEmpty()) null else error("That measure is unavailable for this product.")
    val extra = extraId?.let { id -> bar.buttons.singleOrNull { it.id == id && it.kind == "EXTRA" && it.pricePence != null }
        ?: error("That extra is unavailable for this product.") }
    fun snapshot(button: MeasureButton, group: Long, charge: Int) = ModifierSelection(
        sourceType = ModifierSourceType.MANUAL, optionType = ModifierOptionType.STANDARD,
        optionId = -1L - (button.id.hashCode().toLong() and 0xffffffffL), groupId = group,
        groupName = if (group == SELECTION_MEASURE_GROUP) "Measure" else "Draught extra",
        selectedItemId = null, selectedItemName = button.label, priceDeltaPence = charge,
        ticketNamePlacement = ModifierTicketNamePlacement.DETAIL,
    )
    return SelectedMeasure(measure?.pricePence ?: bar.basePricePence, listOfNotNull(measure?.let { snapshot(it, SELECTION_MEASURE_GROUP, 0) }) +
        listOfNotNull(extra?.let { snapshot(it, SELECTION_EXTRA_GROUP, it.pricePence!!) }))
}

fun selectionBarDisplayProduct(product: Product, measureId: String?, extraId: String?): Product {
    if (product.selectionBarJson.isBlank()) return product
    return try {
        val selected = selectMeasure(parseSelectionBar(product.selectionBarJson)!!, measureId, extraId)
        val extra = selected.modifiers.sumOf { it.priceDeltaPence }
        val discount = calculateTakeawayDiscountPence(selected.pricePence, product.takeawayDiscountBasisPoints)
        product.copy(pricePence = selected.pricePence + extra,
            takeawayPricePence = selected.pricePence - discount + extra,
            takeawayDiscountPence = discount,
            takeawayDiscountBasisPoints = 0)
    } catch (_: Exception) {
        product.copy(isAvailableOnTill = false)
    }
}
