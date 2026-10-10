package uk.edge.move.handheld

import org.json.JSONObject
import com.edge.poslite.domain.model.*
import com.edge.poslite.domain.pricing.*
import java.math.BigDecimal

data class MenuModifierOption(val id: String, val name: String, val price: Int, val linkedProduct: String)
data class MenuModifierGroup(val id: String, val name: String, val min: Int, val max: Int, val included: Int,
    val repeats: Boolean, val placement: ModifierTicketNamePlacement, val prefix: String, val options: List<MenuModifierOption>)

internal fun parseOpenAmount(value: String): Int {
    val clean = value.trim()
    require(Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(clean)) { "Enter pounds and pence, for example 12.50" }
    val pence = BigDecimal(clean).movePointRight(2).intValueExact()
    require(pence in 1..100000000) { "Enter an amount between £0.01 and £1,000,000" }
    return pence
}
internal fun parseMenuModifiers(product: JSONObject, products: List<JSONObject>): List<MenuModifierGroup> =
    product.objects("modifierGroups").filter { it.optBoolean("active", true) }.sortedBy { it.optInt("sortOrder") }.map { g ->
        var options = g.objects("options").filter { it.optBoolean("active", true) }.sortedBy { it.optInt("sortOrder") }.map { o ->
            val linked = o.optString("linkedProductId")
            if (linked.isNotBlank()) {
                val target = products.singleOrNull { it.optString("id") == linked }
                require(target != null && target.optBoolean("active", true) && target.optBoolean("visible", true)) { "Linked modifier product is unavailable" }
                require(target.optString("pricingMode", "FIXED") == "FIXED" && target.objects("modifierGroups").isEmpty() && target.optJSONObject("selectionBar") == null) { "Nested product modifiers are not supported yet" }
            }
            MenuModifierOption(o.getString("id"), o.getString("name"), o.getInt("priceMinor").also { require(it in 0..100000000) }, linked)
        }
        if (g.optString("optionSortMode") == "ALPHABETICAL") options = options.sortedBy { it.name.lowercase() }
        val min = g.optInt("minSelections", 0)
        val max = if (g.has("maxSelections") && g.isNull("maxSelections")) UNLIMITED_MODIFIER_SELECTIONS else g.optInt("maxSelections", options.size)
        val included = g.optInt("includedSelections", 0)
        require(min >= 0 && max >= min && included >= 0) { "Invalid modifier selection limits" }
        require(options.map { it.id }.distinct().size == options.size) { "Duplicate modifier options" }
        require(min == 0 || (options.isNotEmpty() && (g.optBoolean("allowRepeatSelections") || options.size >= min))) { "Required modifiers are unavailable" }
        MenuModifierGroup(g.getString("id"), g.getString("name"), min, max, included, g.optBoolean("allowRepeatSelections"),
            runCatching { ModifierTicketNamePlacement.valueOf(g.optString("ticketNamePlacement", "DETAIL")) }.getOrDefault(ModifierTicketNamePlacement.DETAIL),
            g.optString("ticketNamePrefix"), options)
    }.also { require(it.map { g -> g.id }.distinct().size == it.size) { "Duplicate modifier groups" } }

internal fun configuredModifiers(product: MenuProduct, choices: Map<String,List<String>>): List<OrderLineModifier> {
    require(choices.keys.all { key -> product.groups.any { it.id == key } }) { "Unknown modifier group" }
    return product.groups.flatMap { group ->
        val selected = choices[group.id].orEmpty()
        require(modifierSelectionIsValid(selected.size, group.min, group.max)) { "${group.name}: choose ${group.min} to ${if (group.max == Int.MAX_VALUE) "any number of" else group.max} options" }
        require(group.repeats || selected.distinct().size == selected.size) { "${group.name}: repeats are not allowed" }
        selected.mapIndexed { index, id ->
            val option = requireNotNull(group.options.singleOrNull { it.id == id }) { "Unknown modifier option" }
            OrderLineModifier(0, ModifierSourceType.MANUAL, ModifierOptionType.STANDARD, remoteId(id), remoteId(group.id), group.name,
                null, null, option.linkedProduct.takeIf { it.isNotBlank() }?.let(::remoteId), option.name,
                modifierPriceDeltaPence(index, group.included, option.price), selectionSequence = index, isIncluded = index < group.included,
                ticketNamePlacement = group.placement, ticketNamePrefix = group.prefix)
        }
    }
}

internal fun validateOpenName(value: String): String {
    val name = value.trim()
    require(name.isNotEmpty()) { "Enter a product name" }
    require(name.length <= 60 && name.none { it.isISOControl() }) { "Use a product name of up to 60 characters" }
    return name
}

data class MenuBuilder(val groupId: String, val tiers: List<ProductBuilderPriceTier>)
internal fun parseMenuBuilder(product: JSONObject, groups: List<MenuModifierGroup>): MenuBuilder? {
    if (product.optString("pricingMode") != TIERED_BUILDER_PRICING_MODE) return null
    require(product.optJSONObject("selectionBar") == null) { "A tiered builder cannot also have measure pricing" }
    val builder=product.getJSONObject("builderPricing")
    require(builder.optString("additionalSelectionPricing") == "OPTION_PRICE") { "Unsupported builder extra pricing" }
    val tiers=builder.objects("tiers").map { ProductBuilderPriceTier(it.getInt("quantity"),it.getInt("priceMinor")) }.sortedBy { it.quantity }
    require(tiers.isNotEmpty() && tiers.all { it.quantity > 0 && it.pricePence in 0..100000000 } && tiers.map { it.quantity }.distinct().size == tiers.size) { "Invalid builder price tiers" }
    val id=builder.getString("modifierGroupId")
    val group=requireNotNull(groups.singleOrNull { it.id == id }) { "Missing builder modifier group" }
    require(group.min >= 1 && group.included == productBuilderIncludedQuantity(tiers)) { "Builder selection rules do not match price tiers" }
    return MenuBuilder(id,tiers)
}
