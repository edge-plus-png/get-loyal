package com.edge.poslite.domain.pricing

data class ProductBuilderPriceTier(
    val quantity: Int,
    val pricePence: Int,
)

const val TIERED_BUILDER_PRICING_MODE = "TIERED_BUILDER"

fun parseProductBuilderPriceTiers(value: String): List<ProductBuilderPriceTier> =
    value.split('|')
        .mapNotNull { entry ->
            val parts = entry.split(':', limit = 2)
            val quantity = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val pricePence = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (quantity < 1 || pricePence < 0) return@mapNotNull null
            ProductBuilderPriceTier(quantity, pricePence)
        }
        .distinctBy { it.quantity }
        .sortedBy { it.quantity }

fun productBuilderBasePricePence(tiers: List<ProductBuilderPriceTier>, selectedQuantity: Int): Int {
    if (selectedQuantity <= 0 || tiers.isEmpty()) return 0
    return tiers.firstOrNull { it.quantity == selectedQuantity }?.pricePence
        ?: tiers.lastOrNull { it.quantity <= selectedQuantity }?.pricePence
        ?: tiers.first().pricePence
}

fun productBuilderIncludedQuantity(tiers: List<ProductBuilderPriceTier>): Int =
    tiers.maxOfOrNull { it.quantity } ?: 0

fun productBuilderStartingPricePence(tiers: List<ProductBuilderPriceTier>): Int =
    tiers.minByOrNull { it.quantity }?.pricePence ?: 0

fun productBuilderLineTotalPence(
    tiers: List<ProductBuilderPriceTier>,
    selectedQuantity: Int,
    additionalSelectionsTotalPence: Int,
): Int = productBuilderBasePricePence(tiers, selectedQuantity) + additionalSelectionsTotalPence.coerceAtLeast(0)

fun productBuilderPriceIsAuthoritative(pricingMode: String): Boolean =
    pricingMode == TIERED_BUILDER_PRICING_MODE
