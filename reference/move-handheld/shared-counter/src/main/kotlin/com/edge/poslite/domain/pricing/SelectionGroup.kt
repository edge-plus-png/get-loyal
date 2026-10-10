package com.edge.poslite.domain.pricing

import com.edge.poslite.domain.model.*

const val SELECTION_DEAL_GROUP = -900003L
data class SelectionGroupItem(val product: Product, val modifiers: List<ModifierSelection> = emptyList())

fun validateSelectionGroup(button: MeasureButton, productIds: List<Long>, complete: Boolean = true) {
    require(validSelectionConfiguration(button)) { "Selection unavailable. Check the deal settings." }
    require(productIds.all { it in button.eligibleProductIds }) { "Choose an eligible product." }
    require((button.selectionMode == "COUNT" && button.allowRepeats) || productIds.distinct().size == productIds.size) { "Choose each product only once." }
    require(productIds.size <= button.selectionCount) { "Selection is already complete." }
    if (complete) {
        require(productIds.size == button.selectionCount) { "Complete the selection first." }
        require(button.selectionMode != "ALL" || productIds.toSet() == button.eligibleProductIds.toSet()) { "Choose every eligible product once." }
    }
}

// Proportional, integer allocation keeps the fixed price exact across product/VAT lines.
fun allocateSelectionPrice(total: Int, weights: List<Int>): List<Int> {
    require(total in 0..100000000 && weights.isNotEmpty() && weights.all { it >= 0 })
    val normalized = if (weights.all { it == 0 }) weights.map { 1L } else weights.map { it.toLong() }
    val sum = normalized.sum()
    val allocations = normalized.map { (total.toLong() * it / sum).toInt() }.toMutableList()
    val remainder = total - allocations.sum()
    normalized.indices.sortedWith(compareByDescending<Int> { total.toLong() * normalized[it] % sum }.thenBy { it })
        .take(remainder).forEach { allocations[it]++ }
    return allocations
}
fun selectionGroupToken(line: OrderLine): String? = line.modifiers.firstOrNull { it.modifierGroupId == SELECTION_DEAL_GROUP }?.parentOptionName
fun selectionGroupLines(order: Order, line: OrderLine): List<OrderLine> = selectionGroupToken(line)?.let { token -> order.lines.filter { selectionGroupToken(it) == token } } ?: listOf(line)

fun canStartSelectionGroup(button: MeasureButton, availableIds: List<Long>): Boolean {
    if (!validSelectionConfiguration(button)) return false
    val available = availableIds.toSet().intersect(button.eligibleProductIds.toSet())
    return if (button.selectionMode == "ALL") available == button.eligibleProductIds.toSet()
        else available.size >= if (button.allowRepeats) 1 else button.selectionCount
}

// ALL follows configured product identity, never its editable button label.
fun nextAutomaticSelectionProduct(button: MeasureButton, selectedIds: List<Long>): Long? {
    if (button.selectionMode != "ALL") return null
    validateSelectionGroup(button, selectedIds, complete = false)
    return button.eligibleProductIds.firstOrNull { it !in selectedIds }
}

// Validate at the ordering boundary too: callers need not have come through JSON parsing.
private fun validSelectionConfiguration(button: MeasureButton): Boolean =
    button.kind == "DEAL" && button.pricePence != null && button.pricePence in 0..100000000 &&
        button.selectionMode in setOf("COUNT", "ALL") && button.selectionCount in 1..250 &&
        button.eligibleProductIds.isNotEmpty() &&
        button.eligibleProductIds.distinct().size == button.eligibleProductIds.size &&
        (if (button.selectionMode == "ALL") button.selectionCount == button.eligibleProductIds.size
        else button.allowRepeats || button.selectionCount <= button.eligibleProductIds.size)
