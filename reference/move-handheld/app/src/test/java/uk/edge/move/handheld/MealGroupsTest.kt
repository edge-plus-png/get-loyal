package uk.edge.move.handheld
import com.edge.poslite.domain.model.*
import com.edge.poslite.domain.pricing.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
class MealGroupsTest {
 @Test fun groupDealAppliesAndSurvivesDraftRestore() {
  val rule=MealDealRule("lunch","Lunch",emptyList(),500,0,listOf(MealDealGroup("Main",listOf("sub")),MealDealGroup("Snack",listOf("cake"))))
  fun product(key:String,price:Int)=MenuProduct(key,"dept",Product(remoteId(key),key,null,price,20,1,1,true,true,false,false,true,false,sortOrder=0),null,mealRules=listOf(rule))
  val draft=OrderingDraft();draft.add(product("sub",400),null,null);assertEquals(400L,draft.total)
  draft.add(product("cake",300),null,null);assertEquals(500L,draft.total)
  val restored=decodeDraft(draft.encode());assertEquals(500L,restored.total)
  assertEquals(rule,restored.mealPricing!!.rules.single())
 }
 @Test fun groupCatalogueValidatesProductMembership() {
  val json=JSONObject("""{"departments":[],"products":[{"id":"a"},{"id":"b"}],"mealDeals":[{"id":"g","name":"Lunch","active":true,"sortOrder":0,"bundlePriceMinor":500,"departmentIds":[],"groups":[{"name":"Main","productIds":["a"]},{"name":"Snack","productIds":["b"]}]}]}""")
  assertEquals(2,parseMealRules(json).single().groups.size)
  json.getJSONArray("products").remove(1)
  assertThrows(IllegalArgumentException::class.java) {parseMealRules(json)}
 }
}
