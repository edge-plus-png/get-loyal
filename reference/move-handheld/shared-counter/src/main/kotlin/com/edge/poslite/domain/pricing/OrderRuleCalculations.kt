package com.edge.poslite.domain.pricing

import com.edge.poslite.domain.model.UNLIMITED_MODIFIER_SELECTIONS

/** Rounds a percentage discount to the nearest penny using integer arithmetic. */
fun calculateTakeawayDiscountPence(basePricePence: Int, discountBasisPoints: Int): Int =
    (((basePricePence.coerceAtLeast(0).toLong() * discountBasisPoints.coerceIn(0, 10_000)) + 5_000L) / 10_000L)
        .toInt()
        .coerceAtMost(basePricePence.coerceAtLeast(0))

/** The first [includedSelections] selections in a modifier group are included; later choices keep their configured price. */
fun modifierPriceDeltaPence(selectionIndex: Int, includedSelections: Int, configuredPricePence: Int): Int =
    if (selectionIndex < includedSelections.coerceAtLeast(0)) 0 else configuredPricePence

fun modifierSelectionCanGrow(selectedCount: Int, maxSelections: Int): Boolean =
    maxSelections == UNLIMITED_MODIFIER_SELECTIONS || selectedCount < maxSelections

fun modifierSelectionIsValid(selectedCount: Int, minSelections: Int, maxSelections: Int): Boolean =
    selectedCount >= minSelections &&
        (maxSelections == UNLIMITED_MODIFIER_SELECTIONS || selectedCount <= maxSelections)
