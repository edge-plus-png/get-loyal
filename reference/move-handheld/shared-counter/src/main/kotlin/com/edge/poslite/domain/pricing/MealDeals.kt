package com.edge.poslite.domain.pricing

/** Integer-only rule evaluation, shared by Counter, Handheld and shared-table adapters. */
data class MealDealGroup(val name: String, val productIds: List<String>)
data class MealDealRule(val id: String, val name: String, val departmentIds: List<String>, val price: Int, val priority: Int, val groups: List<MealDealGroup> = emptyList())
data class MealDealLine(val key: String, val departmentId: String, val unitPrice: Int, val quantity: Int, val eligible: Boolean = true, val productId: String = "")
data class MealDealUnit(val lineKey: String, val unitIndex: Int, val discount: Int)
data class MealDealApplication(val id: String, val name: String, val units: List<MealDealUnit>) {
    val discount: Int get() = units.sumOf { it.discount }
}

/** Largest-remainder allocation. Shares sum exactly to the discount and never exceed their weights. */
fun allocateMealDiscount(discount: Int, prices: List<Int>): List<Int> {
    val total = prices.sumOf { it.toLong() }
    require(prices.all { it >= 0 } && discount >= 0 && discount.toLong() <= total)
    if (total == 0L) return prices.map { 0 }
    val shares = prices.map { (it.toLong() * discount / total).toInt() }.toMutableList()
    val remainder = discount - shares.sum()
    prices.indices.sortedWith(compareByDescending<Int> { prices[it].toLong() * discount % total }.thenBy { it })
        .take(remainder).forEach { shares[it]++ }
    return shares
}

fun priceMealDeals(rules: List<MealDealRule>, lines: List<MealDealLine>): List<MealDealApplication> {
    require(lines.size <= 500 && lines.map { it.key }.distinct().size == lines.size)
    require(lines.all { it.key.isNotBlank() && it.unitPrice in 0..100_000_000 && it.quantity in 1..999 })
    require(lines.sumOf { it.unitPrice.toLong() * it.quantity } <= 100_000_000)
    require(rules.size <= 100 && rules.map { it.id }.distinct().size == rules.size)
    require(rules.all { it.id.isNotBlank() && it.name.isNotBlank() && (if (it.groups.isNotEmpty()) it.groups.size in 2..5 && it.groups.all { g -> g.name.isNotBlank() && g.name.length <= 80 && g.productIds.size in 1..1000 && g.productIds.all(String::isNotBlank) && g.productIds.distinct().size == g.productIds.size } else it.departmentIds.size in 2..100 && it.departmentIds.all(String::isNotBlank) && it.departmentIds.distinct().size == it.departmentIds.size) && it.price in 1..100_000_000 })
    require(lines.sumOf { it.quantity } <= 5000) { "Meal deals support up to 5000 items per basket" }
    data class Unit(val line: MealDealLine, val index: Int)
    val remaining = lines.associate { it.key to it.quantity }.toMutableMap()
    val byDepartment = lines.filter { it.eligible }.groupBy { it.departmentId }.mapValues { (_, rows) ->
        rows.sortedWith(compareByDescending<MealDealLine> { it.unitPrice }.thenBy { it.key })
    }
    val cursors = mutableMapOf<String,Int>()
    fun next(department: String): Unit? {
        val rows = byDepartment[department] ?: return null
        var cursor = cursors[department] ?: 0
        while (cursor < rows.size && remaining.getValue(rows[cursor].key) == 0) cursor++
        cursors[department] = cursor
        return rows.getOrNull(cursor)?.let { Unit(it,it.quantity - remaining.getValue(it.key)) }
    }
    fun groupUnits(groups: List<MealDealGroup>): List<Unit> {
        val candidates = groups.map { group ->
            lines.filter { it.eligible && it.productId in group.productIds && remaining.getValue(it.key) > 0 }
                .sortedWith(compareByDescending<MealDealLine> { it.unitPrice }.thenBy { it.key })
                .flatMap { line -> (0 until minOf(groups.size,remaining.getValue(line.key))).map { Unit(line,line.quantity-remaining.getValue(line.key)+it) } }.take(groups.size)
        }
        var best = emptyList<Unit>()
        var bestPrice = -1
        val used = mutableSetOf<Pair<String,Int>>()
        fun visit(index: Int, chosen: List<Unit>, price: Int) {
            if (index == groups.size) { if (price > bestPrice) { best=chosen; bestPrice=price }; return }
            for (unit in candidates[index]) {
                val key=unit.line.key to unit.index
                if (!used.add(key)) continue
                visit(index+1,chosen+unit,price+unit.line.unitPrice)
                used.remove(key)
            }
        }
        visit(0,emptyList(),0)
        return best
    }
    val applications = mutableListOf<MealDealApplication>()
    for (rule in rules.sortedWith(compareBy<MealDealRule> { it.priority }.thenBy { it.id })) {
        while (true) {
            val selected = if (rule.groups.isNotEmpty()) groupUnits(rule.groups) else rule.departmentIds.mapNotNull(::next)
            if (selected.size != (if (rule.groups.isNotEmpty()) rule.groups.size else rule.departmentIds.size)) break
            val discount = selected.sumOf { it.line.unitPrice } - rule.price
            if (discount <= 0) break // Never turn a promotion into a surcharge.
            val shares = allocateMealDiscount(discount, selected.map { it.line.unitPrice })
            applications += MealDealApplication(rule.id,rule.name,selected.mapIndexed { index,unit -> MealDealUnit(unit.line.key,unit.index,shares[index]) })
            selected.forEach { remaining[it.line.key] = remaining.getValue(it.line.key) - 1 }
        }
    }
    return applications
}

fun mealDiscountsByLine(applications: List<MealDealApplication>): Map<String,Int> =
    applications.flatMap { it.units }.groupBy { it.lineKey }.mapValues { (_, units) -> units.sumOf { it.discount } }
