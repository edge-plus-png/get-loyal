package uk.edge.move.handheld

import com.edge.poslite.domain.pricing.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun mealGroups(r: JSONObject): List<MealDealGroup> = r.optJSONArray("groups")?.let { a -> (0 until a.length()).map { i -> val g=a.getJSONObject(i);val ps=g.getJSONArray("productIds");MealDealGroup(g.getString("name"),(0 until ps.length()).map { ps.getString(it) }) } } ?: emptyList()
internal fun parseMealRules(source: JSONObject): List<MealDealRule> = source.objects("mealDeals")
    .filter { it.getBoolean("active") }.map { r ->
        MealDealRule(r.getString("id"),r.getString("name"),r.getJSONArray("departmentIds").let { a ->
            (0 until a.length()).map { a.getString(it) }
        },r.getInt("bundlePriceMinor"),r.getInt("sortOrder"),mealGroups(r))
    }.also { rules ->
        priceMealDeals(rules,emptyList())
        val products=source.objects("products").map { it.getString("id") }.toSet()
        require(rules.all { r -> r.groups.all { g -> g.productIds.all { it in products } } }) { "Meal-deal product is missing" }
        val departments=source.objects("departments").map { it.getString("id") }.toSet()
        require(rules.all { r -> r.departmentIds.all { it in departments } }) { "Meal-deal department is missing" }
    }

/** Pinned to the basket; saved raw prices remain available for edits and service-mode changes. */
class DraftMealPricing(val rules: List<MealDealRule>) {
    val lineIds=mutableMapOf<Long,String>()
    val departments=mutableMapOf<Long,String>()
    fun add(id: Long, department: String) { lineIds[id]=UUID.randomUUID().toString(); departments[id]=department }
    fun retain(ids: Set<Long>) { lineIds.keys.retainAll(ids);departments.keys.retainAll(ids) }
    fun applications(draft: OrderingDraft): List<MealDealApplication> = if (rules.isEmpty()) emptyList() else priceMealDeals(rules.map { r -> r.copy(groups=r.groups.map { g -> g.copy(productIds=g.productIds.map { remoteId(it).toString() }) }) },draft.lines.map { line ->
        MealDealLine(lineIds.getValue(line.id),departments.getValue(line.id),line.unitPricePence,line.quantity,
            line.id !in draft.productParents && line.modifiers.none { it.modifierGroupId == SELECTION_DEAL_GROUP },line.productId.toString())
    })
    fun encode(): JSONObject = JSONObject().put("rules",JSONArray(rules.map { r ->
        JSONObject().put("id",r.id).put("name",r.name).put("departmentIds",JSONArray(r.departmentIds)).put("price",r.price).put("priority",r.priority).apply { if(r.groups.isNotEmpty()) put("groups",JSONArray(r.groups.map { g -> JSONObject().put("name",g.name).put("productIds",JSONArray(g.productIds)) })) }
    })).put("lineIds",JSONObject(lineIds.mapKeys { it.key.toString() })).put("departments",JSONObject(departments.mapKeys { it.key.toString() }))
    companion object {
        fun decode(o: JSONObject): DraftMealPricing {
            val rules=o.objects("rules").map { r ->
                require(r.keys().asSequence().all { it in setOf("id","name","departmentIds","price","priority","groups") })
                MealDealRule(r.getString("id"),r.getString("name"),r.getJSONArray("departmentIds").let { a -> (0 until a.length()).map { a.getString(it) } },r.getInt("price"),r.getInt("priority"),mealGroups(r)) }
            priceMealDeals(rules,emptyList())
            return DraftMealPricing(rules).apply {
                val ids=o.getJSONObject("lineIds");ids.keys().forEach { key -> lineIds[key.toLong()]=ids.getString(key).also { UUID.fromString(it) } }
                require(lineIds.values.distinct().size==lineIds.size)
                val depts=o.getJSONObject("departments");depts.keys().forEach { key -> departments[key.toLong()]=depts.getString(key) }
                require(lineIds.keys==departments.keys)
            }
        }
    }
}

internal fun mealApplicationsJson(apps: List<MealDealApplication>) = JSONArray(apps.map { a ->
    JSONObject().put("id",a.id).put("name",a.name).put("units",JSONArray(a.units.map { u ->
        JSONObject().put("lineKey",u.lineKey).put("unitIndex",u.unitIndex).put("discount",u.discount)
    }))
})
