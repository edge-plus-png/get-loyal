package uk.edge.move.handheld
import com.edge.poslite.domain.model.*
import com.edge.poslite.domain.pricing.*
import org.junit.Test
import org.junit.Assert.*
class SelectionDepartmentTest {
 private fun product(id:Long, price:Int, vat:Int=20) = MenuProduct(id.toString(),"dept",Product(id,"Product $id",null,price,vat,1,1,true,true,false,false,true,false,sortOrder=0),null)
 @Test fun doubleKeepsPriceAndAdditionHasOwnVatAfterRestore() {
  val gin=product(1,520).copy(selectionProductIds=listOf("2","3"),bar=ProductSelectionBar("single",listOf(MeasureButton("single","Single","MEASURE",520,true),MeasureButton("double","Double","MEASURE",870,true))))
  val tonic=product(2,200,0);val sauce=product(3,0)
  val draft=OrderingDraft();draft.add(gin,"double",null);val parent=draft.lines.single()
  draft.addSelection(tonic,parent,gin);draft.addSelection(sauce,parent,gin)
  assertEquals(1070L,draft.total);assertEquals(870,draft.lines.first().unitPricePence);assertEquals(0,draft.lines[1].vatRatePercent)
  val restored=decodeDraft(draft.encode());assertEquals(draft.productParents,restored.productParents);assertEquals(1070L,restored.total)
  assertThrows(IllegalArgumentException::class.java) {restored.setQuantity(restored.lines.first(),2)}
  restored.remove(restored.lines.first());assertTrue(restored.lines.isEmpty());assertTrue(restored.productParents.isEmpty())
 }
 @Test fun unrelatedProductsAndNoTargetCannotAttach() {
  val source=product(1,100).copy(selectionProductIds=listOf("2"));val draft=OrderingDraft();draft.add(source,null,null)
  val parent=draft.lines.single()
  assertThrows(IllegalArgumentException::class.java) {draft.addSelection(product(3,30),parent,source)}
  draft.selectedProductLineId=null
  assertThrows(IllegalArgumentException::class.java) {draft.addSelection(product(2,30),parent,source)}
  assertEquals(100L,draft.total)
 }
 @Test fun staleParentQuantityCannotAcceptAddition() {
  val source=product(1,100).copy(selectionProductIds=listOf("2"));val draft=OrderingDraft();draft.add(source,null,null)
  val oldParent=draft.lines.single();draft.setQuantity(oldParent,2)
  assertThrows(IllegalArgumentException::class.java) { draft.addSelection(product(2,30),oldParent,source) }
  assertEquals(200L,draft.total);assertEquals(1,draft.lines.size);assertTrue(draft.productParents.isEmpty())
 }

 @Test fun departmentOptionsExistWithoutBasketAndStayScoped() {
  val breakfast=product(1,100).copy(department="breakfast",selectionProductIds=listOf("2","3"))
  val second=product(4,200).copy(department="breakfast",selectionProductIds=listOf("3","2"))
  val all=listOf(breakfast,product(2,10),product(3,0),second)
  assertEquals(listOf("2","3"),departmentSelectionProducts("breakfast",all).map {it.remoteId})
  assertTrue(departmentSelectionProducts(null,all).isEmpty())
  assertTrue(departmentSelectionProducts("lunch",all).isEmpty())
 }
}
