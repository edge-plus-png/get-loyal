package uk.edge.move.handheld

import com.edge.poslite.domain.model.*
import com.edge.poslite.domain.pricing.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.CRC32

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.objects(key: String) = optJSONArray(key)?.objects().orEmpty()
internal fun remoteId(id: String): Long = 1_000_000_000L + CRC32().apply { update(id.toByteArray(Charsets.UTF_8)) }.value

/** Preserve configured holes; products outside the fixed slots continue after the grid. */
internal fun <T> positioned(items: List<T>, limit: Int, slot: (T) -> Int): List<T?> {
    val fixed = items.filter { slot(it) in 1..limit }
    val duplicates = fixed.groupBy(slot).values.any { it.size > 1 }
    if (duplicates) return items.sortedBy(slot) // Legacy catalogues can have tied sort orders.
    val last = fixed.maxOfOrNull(slot) ?: 0
    return (1..last).map { n -> fixed.find { slot(it) == n } } + items.filter { slot(it) !in 1..limit }.sortedBy(slot)
}

data class Staff(val id: String, val name: String, val pin: String, val permissions: JSONObject) {
    fun allowed(permission: String) = permissions.optBoolean(permission, false)
}
internal fun reauthorizeStaff(current: Staff?, staff: List<Staff>): Staff? = current?.let { old ->
    staff.singleOrNull { it.id == old.id && it.pin == old.pin && it.allowed("takePaymentEnabled") }
}
data class MenuProduct(val remoteId: String, val department: String, val model: Product, val blockedReason: String?, val bar: ProductSelectionBar? = null, val openPrice: Boolean = false, val groups: List<MenuModifierGroup> = emptyList(), val takeawayBasisPoints: Int = 0, val takeawayDiscount: Int = 0, val takeawayVat: Int = model.vatRatePercent, val builder: MenuBuilder? = null, val mealRules: List<MealDealRule> = emptyList(), val selectionProductIds: List<String> = emptyList()) {
    fun displayPrice(measure: String?, extra: String?, mode: com.edge.poslite.domain.model.ServiceMode = com.edge.poslite.domain.model.ServiceMode.RESTAURANT): Int? = if (blockedReason != null) null else runCatching {
        if (builder != null) return@runCatching productBuilderStartingPricePence(builder.tiers)
        val selected = bar?.let { selectMeasure(it,measure,extra) }
        val base = selected?.pricePence ?: model.pricePence
        val discount = if (selected == null) takeawayDiscount else calculateTakeawayDiscountPence(base,takeawayBasisPoints)
        val effective = if (mode.usesTakeawayPricing()) base - discount else base
        Math.addExact(effective, selected?.modifiers?.sumOf { it.priceDeltaPence } ?: 0)
    }.getOrNull()
}
data class Tile(val label: String, val kind: String, val department: String, val product: String, val screen: String, val slot: Int)
data class Screen(val id: String, val name: String, val tiles: List<Tile>, val tabs: List<Tile>, val contentMode: String = "")
data class Department(val id: String, val name: String)
data class Catalogue(
    val version: Int, val merchantId: String, val merchantCode: String, val merchant: String,
    val terminal: String, val storeId: String, val locationId: String, val location: String,
    val layoutName: String, val products: List<MenuProduct>, val departments: List<Department>,
    val screens: List<Screen>, val home: String, val staff: List<Staff>,
    val departmentProductPositions: Map<String,List<String>> = emptyMap(),
    val defaultServiceMode: ServiceMode = ServiceMode.RESTAURANT, val serviceModesEnabled: Boolean = false,
) {
    val shortcuts: List<Tile> get() = screens.first { it.id == home }.tabs

    fun departmentGrid(id: String): List<MenuProduct?> {
        val positions = departmentProductPositions[id]
        if (positions != null) {
            val seen = mutableSetOf<String>()
            return positions.map { key ->
                if (key.isBlank() || !seen.add(key)) null
                else products.find { it.remoteId == key && it.model.isAvailableOnTill && it.model.isActive }
            }
        }
        return positioned(products.filter { it.department == id && it.model.isAvailableOnTill },24) { it.model.sortOrder }
    }

    companion object {
        fun staff(root: JSONObject): List<Staff> = root.objects("terminalUsers").map {
            Staff(it.getString("id"), it.getString("name"), it.optString("terminalPin", it.optString("pin")),
                it.optJSONObject("permissions") ?: JSONObject())
        }.also { require(it.map(Staff::id).distinct().size == it.size) { "Duplicate staff identities" } }

        fun parse(root: JSONObject): Catalogue {
            val assignment = root.getJSONObject("assignment")
            require(assignment.getString("deviceRole") == "HANDHELD") { "Assign this device the Handheld role in back office." }
            val merchant = root.getJSONObject("merchant")
            val source = root.getJSONObject("goCatalog")
            val mealRules = parseMealRules(source)
            val rawProducts = source.getJSONArray("products").objects()
            require(rawProducts.map { it.getString("id") }.distinct().size == rawProducts.size)
            require(rawProducts.map { remoteId(it.getString("id")) }.distinct().size == rawProducts.size) { "Product identity collision" }
            // Departments commonly repeat identical selection configuration across hundreds
            // of products. Intern and validate each distinct configuration once per download.
            val selections = mutableMapOf<String,Pair<String,Result<ProductSelectionBar?>>>()
            val products = rawProducts.mapIndexed { index, p ->
                val rawSelection = p.optJSONObject("selectionBar")?.toString().orEmpty()
                val (selectionJson, parsedSelection) = selections.getOrPut(rawSelection) {
                    val selection = rawSelection.takeIf { it.isNotBlank() }?.let(::JSONObject)
                    selection?.objects("buttons")?.forEach { button ->
                        button.optJSONArray("eligibleProductIds")?.let { ids ->
                            button.put("eligibleProductIds", JSONArray((0 until ids.length()).map { remoteId(ids.getString(it)) }))
                        }
                    }
                    val json = selection?.toString().orEmpty()
                    json to runCatching { parseSelectionBar(json) }
                }
                val price = p.getInt("priceMinor").also { require(it in 0..100000000) }
                val invalidSelection = parsedSelection.isFailure
                val mode = p.optString("pricingMode", "FIXED")
                val parsedGroups = runCatching { parseMenuModifiers(p, rawProducts) }
                val parsedBuilder = runCatching { parseMenuBuilder(p,parsedGroups.getOrThrow()) }
                val blocked = when {
                    parsedBuilder.isFailure -> parsedBuilder.exceptionOrNull()?.message ?: "Invalid builder"
                    parsedGroups.isFailure -> parsedGroups.exceptionOrNull()?.message ?: "Invalid modifiers"
                    !p.optBoolean("active", true) -> p.optString("selectionBarError").ifBlank { "Unavailable in the published catalogue" }
                    invalidSelection -> "Selection configuration needs attention"
                    mode !in listOf("FIXED", "OPEN", TIERED_BUILDER_PRICING_MODE) -> "${mode.lowercase().replace('_', ' ')} ordering is not available in this first staging build"
                    else -> null
                }
                MenuProduct(p.getString("id"), p.optString("departmentId"), Product(
                    id = remoteId(p.getString("id")), name = p.getString("name"), shortName = p.optString("shortLabel"),
                    pricePence = price, vatRatePercent = p.optInt("taxRatePercent"), categoryId = remoteId(p.optString("departmentId")),
                    departmentId = remoteId(p.optString("departmentId")), isActive = p.optBoolean("active", true),
                    isAvailableOnTill = p.optBoolean("visible", true), isOutOfStock = false, forceModifierSelection = false,
                    quickAddEnabled = true, isFeatured = false, selectionBarJson = selectionJson, sortOrder = p.optInt("sortOrder", index),
                ), blocked, parsedSelection.getOrNull(), mode == "OPEN", parsedGroups.getOrDefault(emptyList()),
                    p.optInt("takeawayDiscountBasisPoints",0).also { require(it in 0..10000) },
                    p.optInt("takeawayDiscountMinor",0).also { require(it in 0..price) },
                    p.optInt("takeawayVatRatePercent",p.optInt("taxRatePercent")).also { require(it in 0..100) }, parsedBuilder.getOrNull(),mealRules, p.optJSONArray("mixerProductIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
            }
            fun tiles(s: JSONObject, key: String) = s.objects(key).mapIndexed { i, t ->
                Tile(t.optString("label"), t.optString("kind"), t.optString("targetDepartmentId"),
                    t.optString("targetProductId"), t.optString("targetLayoutId"), t.optInt("sortOrder", i + 1))
            }.sortedBy { it.slot }
            val screens = source.getJSONArray("orderScreens").objects().map {
                Screen(it.getString("id"), it.getString("name"), tiles(it, "tiles"), tiles(it, "quickFireTiles"), it.optString("contentMode"))
            }
            val home = source.getJSONObject("orderScreen").getString("id")
            require(screens.any { it.id == home }) { "Assigned home screen is missing from the complete layout." }
            require(screens.map { it.id }.distinct().size == screens.size)
            return Catalogue(root.getInt("configVersion").also { require(it > 0) }, merchant.getString("id"),
                merchant.getString("merchantCode"), merchant.getString("displayName"), root.getString("terminalName"),
                assignment.getString("storeId"), assignment.getString("locationId"), assignment.optString("locationName"),
                root.getJSONObject("tillLayout").getString("name"), products,
                source.objects("departments").filter { it.optBoolean("visible", true) }.map { Department(it.getString("id"), it.getString("name")) },
                screens, home, staff(root), source.optJSONObject("departmentProductPositions")?.let { mapping ->
                    mapping.keys().asSequence().associateWith { id ->
                        val slots = mapping.getJSONArray(id)
                        require(slots.length() == 24) { "Department placement must contain 24 slots" }
                        (0 until slots.length()).map { slots.getString(it) }
                    }
                }.orEmpty(), source.optString("defaultServiceMode","RESTAURANT").let { raw ->
                    when(raw) { "EAT_IN", "RESTAURANT" -> ServiceMode.RESTAURANT; "TAKEAWAY" -> ServiceMode.TAKEAWAY; "OUTSIDE" -> ServiceMode.OUTSIDE; else -> error("Unsupported service mode: $raw") }
                }, source.optJSONObject("capabilities")?.optBoolean("serviceModes",false) == true)
        }
    }
}

/** Local UI draft only. Uses Counter line snapshots and selection functions; never creates a sale or tender. */
class OrderingDraft {
    val lines = mutableListOf<OrderLine>()
    val productParents = mutableMapOf<Long,Long>()
    var selectedProductLineId: Long? = null
    fun addSelection(product: MenuProduct, parent: OrderLine, source: MenuProduct) {
        require(sharedState == null) { "Selection products require a local order" }
        val currentParent = requireNotNull(lines.singleOrNull { it.id == parent.id }) { "Select the item to add to" }
        require(currentParent.id == selectedProductLineId && currentParent.productId == parent.productId && currentParent.productId == source.model.id) { "Select the item to add to" }
        require(currentParent.quantity == 1 && currentParent.id !in productParents && selectionGroupToken(currentParent) == null) { "Select a single item outside a deal" }
        require(source.selectionProductIds.size <= 5 && product.remoteId in source.selectionProductIds) { "Product is not in this selection department" }
        require(!product.openPrice && product.builder == null && product.bar == null && product.groups.isEmpty()) { "This product needs its own choices" }
        add(product,null,null)
        val child = lines.last()
        lines.removeAt(lines.lastIndex)
        val anchor = lines.indexOfLast { it.id == parent.id || productParents[it.id] == parent.id }
        lines.add(anchor + 1, child)
        productParents[child.id] = parent.id
        selectedProductLineId = parent.id
    }
    var sharedState: JSONObject? = null
    var nextId = 1L
    var serviceMode = ServiceMode.RESTAURANT
    var tableId: String? = null
    var orderReference: String? = null
    val pricing = mutableMapOf<Long,LinePricing>()
    var mealPricing: DraftMealPricing? = null
    val mealApplications get() = mealPricing?.applications(this).orEmpty()
    val paidLines get() = mealDiscountsByLine(mealApplications).let { discounts -> lines.map { line -> line.copy(totalPence=line.totalPence - (discounts[mealPricing?.lineIds?.get(line.id)] ?: 0)) } }
    fun changeContext(mode: ServiceMode, table: String? = null, reference: String? = null) {
        require(mode == ServiceMode.RESTAURANT || table == null) { "Tables require eat-in service" }
        val updated = if (mode == serviceMode) lines.toList() else lines.map { line ->
            requireNotNull(pricing[line.id]) { "This older basket has no service-price snapshot. Keep its current mode until the basket is finished." }.apply(line,mode)
        }
        lines.clear(); lines.addAll(updated); serviceMode = mode; tableId = table; orderReference = reference
    }
    fun clear() { productParents.clear(); selectedProductLineId = null; mealPricing = null; lines.clear(); pricing.clear(); sharedState = null; nextId = 1; tableId = null; orderReference = null }
    val total: Long get() = paidLines.sumOf { it.totalPence.toLong() }
    fun add(product: MenuProduct, measure: String?, extra: String?, openAmount: Int? = null, choices: Map<String,List<String>> = emptyMap(), openName: String? = null) {
        require(product.blockedReason == null) { product.blockedReason.orEmpty() }
        require(product.model.isAvailableOnTill) { "Product unavailable" }
        require(!product.openPrice || (openAmount != null && openAmount in 1..100000000)) { "Enter an amount greater than zero" }
        require(product.openPrice || openAmount == null) { "This product has a fixed price" }
        val lineName = if (product.openPrice) validateOpenName(openName.orEmpty()) else product.model.name
        require(product.openPrice || openName == null) { "Only open-sale products can be renamed" }
        val configured = configuredModifiers(product, choices)
        val bar = product.bar
        val selected = bar?.let { selectMeasure(it, measure, extra) }
        val base = openAmount ?: selected?.pricePence ?: product.builder?.let { productBuilderBasePricePence(it.tiers,choices[it.groupId].orEmpty().size) } ?: product.model.pricePence
        val mods = selected?.modifiers.orEmpty().map { m -> OrderLineModifier(
            nextId++, m.sourceType, m.optionType, m.optionId, m.groupId, m.groupName, null, null,
            m.selectedItemId, m.selectedItemName, m.priceDeltaPence) } + configured.map { it.copy(id = nextId++) }
        val modifierTotal = mods.fold(0) { total, m -> Math.addExact(total,m.priceDeltaPence) }
        val id = nextId++
        val snapshot = LinePricing(base, if (product.builder != null) 0 else if (openAmount != null || selected != null) calculateTakeawayDiscountPence(base,product.takeawayBasisPoints) else product.takeawayDiscount, product.model.vatRatePercent,product.takeawayVat)
        val line = OrderLine(id, product.model.id, lineName, base, null, null, 1, null,
            product.model.vatRatePercent, mods, Math.addExact(base, modifierTotal), modifierTotal)
        if (lines.isEmpty() && mealPricing == null) mealPricing = DraftMealPricing(product.mealRules)
        mealPricing?.add(id,product.department)
        lines += snapshot.apply(line, serviceMode)
        pricing[id] = snapshot
        selectedProductLineId = id.takeIf { product.selectionProductIds.isNotEmpty() }
    }
    fun addDeal(button: MeasureButton, products: List<MenuProduct>, choices: List<Map<String,List<String>>> = List(products.size) { emptyMap() }) {
        validateSelectionGroup(button, products.map { it.model.id })
        require(products.all { it.blockedReason == null && it.model.isAvailableOnTill && !it.openPrice && it.builder == null }) { "Open and tiered products cannot be added through this deal" }
        require(choices.size == products.size)
        val configured = products.mapIndexed { i,p -> configuredModifiers(p,choices[i]) }
        val amounts = allocateSelectionPrice(requireNotNull(button.pricePence), products.map { it.model.pricePence })
        val token = java.util.UUID.randomUUID().toString()
        if (lines.isEmpty() && mealPricing == null) mealPricing = DraftMealPricing(products.first().mealRules)
        products.forEachIndexed { i, p ->
            val marker = OrderLineModifier(nextId++, ModifierSourceType.MANUAL, ModifierOptionType.STANDARD,
                -1L - (button.id.hashCode().toLong() and 0xffffffffL), SELECTION_DEAL_GROUP, button.label, null, token,
                p.model.id, button.label, 0)
            val mods = configured[i].map { it.copy(id=nextId++) } + marker
            val modifierTotal = mods.fold(0) { n,m -> Math.addExact(n,m.priceDeltaPence) }
            val id = nextId++
            val snapshot = LinePricing(amounts[i],0,p.model.vatRatePercent,p.takeawayVat)
            lines += snapshot.apply(OrderLine(id, p.model.id, p.model.name, amounts[i], null, null, 1, null,
                p.model.vatRatePercent, mods, Math.addExact(amounts[i],modifierTotal), modifierTotal),serviceMode)
            mealPricing?.add(id,p.department)
            pricing[id] = snapshot
        }
    }
    val itemCount: Int get() = lines.sumOf { it.quantity }
    fun setQuantity(line: OrderLine, quantity: Int) {
        require(selectionGroupToken(line) == null) { "Remove the complete deal or add another deal" }
        require(line.id !in productParents && line.id !in productParents.values) { "Remove additions before changing quantity" }
        require(quantity in 1..999) { "Quantity must be between 1 and 999" }
        val index = lines.indexOfFirst { it.id == line.id }; require(index >= 0)
        val current = lines[index]
        val total = Math.multiplyExact(Math.addExact(current.unitPricePence,current.modifierTotalPence),quantity)
        lines[index] = current.copy(quantity=quantity,totalPence=total)
    }
    fun remove(line: OrderLine) {
        val token = selectionGroupToken(line)
        lines.removeAll { if (token == null) (it.id == line.id || productParents[it.id] == line.id) else selectionGroupToken(it) == token }
        productParents.keys.retainAll(lines.map { it.id }.toSet())
        if (lines.none { it.id == selectedProductLineId }) selectedProductLineId = null
        pricing.keys.retainAll(lines.map { it.id }.toSet())
        mealPricing?.retain(lines.map { it.id }.toSet())
        if (lines.isEmpty() && sharedState == null) mealPricing = null
    }
}

/** Stable configured additions for the active department, independent of basket selection. */
internal fun departmentSelectionProducts(department:String?,products:List<MenuProduct>):List<MenuProduct> {
    if(department.isNullOrBlank()) return emptyList()
    val ids=products.filter {it.department==department && it.selectionProductIds.size in 1..5}
        .flatMap {it.selectionProductIds}.distinct()
    return ids.mapNotNull {id -> products.firstOrNull {it.remoteId==id}}
}

/** Products screens use the published ordered list without the legacy ten-slot geometry. */
internal fun Screen.displayTiles(): List<Tile?> = if(contentMode == "PRODUCTS") {
    tiles.filter { it.product.isNotBlank() && it.department.isBlank() && it.screen.isBlank() }
        .sortedBy { it.slot }.distinctBy { it.product }
} else positioned(tiles,10) { it.slot }
