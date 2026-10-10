package uk.edge.move.handheld
import org.junit.Assert.*
import org.junit.Test
class ProductScreenTest {
    @Test fun productsKeepPublishedOrderPastTenAndExcludeDepartmentNavigation() {
        val products=(1..25).map {Tile("Product $it","product","","p$it","",it)}
        val screen=Screen("drinks","Drinks",products.reversed()+Tile("Department","department","d","","",0),emptyList(),"PRODUCTS")
        assertEquals((1..25).map {"p$it"},screen.displayTiles().map {it!!.product})
        assertEquals(25,screen.displayTiles().size)
    }
    @Test fun legacyScreenRetainsPositionsAndGaps() {
        val tile=Tile("Tea","product","","tea","",3)
        val legacy=Screen("s","Screen",listOf(tile),emptyList())
        assertEquals(3,legacy.displayTiles().size)
        assertNull(legacy.displayTiles()[0]);assertEquals(tile,legacy.displayTiles()[2])
    }
    @Test fun emptyProductsScreenDoesNotFallBackToDepartments() {
        assertTrue(Screen("s","Screen",listOf(Tile("Department","department","d","","",1)),emptyList(),"PRODUCTS").displayTiles().isEmpty())
    }
}
