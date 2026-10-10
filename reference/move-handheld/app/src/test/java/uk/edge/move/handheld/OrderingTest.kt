package uk.edge.move.handheld

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.edge.poslite.domain.pricing.*
import com.edge.poslite.domain.model.ServiceMode

class OrderingTest {
    @Test fun refundKeepsOriginalVatAndTenderButGetsNewAuditIdentity() {
        val raw=config().put("tenders",org.json.JSONArray().put(JSONObject().put("type","CASH").put("enabled",true)))
        raw.getJSONObject("goCatalog").getJSONArray("products").getJSONObject(0).put("taxRatePercent",20)
        val cat=Catalogue.parse(raw);val d=OrderingDraft();d.add(cat.products.first(),null,null)
        val original=standaloneSale(d,cat,raw,cat.staff.first(),"terminal-a","CASH",1000,1000,"sale-a").put("status","APPROVED").put("transactionId","cash-a")
        val user=cat.staff.first().copy(permissions=JSONObject().put("refundsEnabled",true))
        val refund=standaloneRefund(original,user,"Wrong order",2000)
        assertEquals(-70L,refund.objects("refundItems").sumOf {it.getLong("vatMinor")})
        assertEquals("REFUND",refund.getString("type"));assertEquals("PENDING",refund.getString("status"))
        assertEquals("sale-a",refund.getString("originalTransactionId"));assertNotEquals("sale-a",refund.getString("id"))
        assertEquals(original.getLong("amountMinor"),refund.getLong("amountMinor"))
        assertEquals(-original.objects("saleItems").sumOf {it.getLong("grossMinor")},refund.objects("refundItems").sumOf {it.getLong("grossMinor")})
        assertEquals(-original.objects("saleItems").sumOf {it.getLong("vatMinor")},refund.objects("refundItems").sumOf {it.getLong("vatMinor")})
        assertFalse(refund.has("transactionId"));assertEquals(0,refund.getInt("changeMinor"))
        assertEquals("APPROVED",original.getString("status"));assertTrue(saleReceipt(refund).contains("REFUND"))
        // Refund uploads use negative refundItems, while the local receipt/report retains
        // the original positive saleItems. Exercise the real payload, not a hand-built refund.
        original.put("tradingDayId","sale-day")
        refund.put("tradingDayId","sale-day").put("status","APPROVED")
        val saleDay=JSONObject().put("id","sale-day").put("openingFloatMinor",5000)
        val sameDay=dayReport(saleDay,listOf(original,refund))
        assertEquals(0L,sameDay.getLong("vatTotalMinor"))
        assertEquals(5000L,sameDay.getLong("expectedCashMinor"))
        refund.put("tradingDayId","refund-day")
        val refundDay=dayReport(JSONObject().put("id","refund-day").put("openingFloatMinor",5000),listOf(original,refund))
        assertEquals(-70L,refundDay.getLong("vatTotalMinor"))
        assertEquals(4580L,refundDay.getLong("expectedCashMinor"))
        assertTrue(dayReceipt(refundDay).lines().any {it.startsWith("VAT total") && it.endsWith("-£0.70")})
        assertEquals(70L,dayReport(saleDay,listOf(original,refund)).getLong("vatTotalMinor"))
        assertThrows(IllegalArgumentException::class.java) {standaloneRefund(original,cat.staff.first(),"Reason")}
        assertThrows(IllegalArgumentException::class.java) {standaloneRefund(original,user," ")}
        assertThrows(IllegalArgumentException::class.java) {standaloneRefund(refund,user,"Reason")}
    }
    @Test fun cashThenCardChargesOnlyRemainderAndReportsBothTenders() {
        val raw=config().put("tenders",org.json.JSONArray().put(JSONObject().put("type","CASH").put("enabled",true)).put(JSONObject().put("type","CARD").put("enabled",true)))
        val cat=Catalogue.parse(raw);val d=OrderingDraft();d.add(cat.products.first(),null,null)
        val cash=listOf(JSONObject().put("id","cash-one").put("type","CASH").put("label","Cash").put("amountMinor",100).put("receivedMinor",100))
        val sale=standaloneSale(d,cat,raw,cat.staff.first(),"terminal-a","CARD",cashParts=cash)
        assertEquals(420L,sale.getLong("amountMinor"));assertEquals(320L,sale.getLong("cardRequestAmountMinor"))
        assertEquals(100L,sale.getLong("cashAmountMinor"));assertEquals(320L,sale.getLong("cardAmountMinor"))
        assertEquals(420L,sale.objects("tenders").sumOf {it.getLong("amountMinor")})
        val remainderCash=standaloneSale(d,cat,raw,cat.staff.first(),"terminal-a","CASH",500,cashParts=cash)
        assertEquals(180L,remainderCash.getLong("changeMinor"));assertEquals(600L,remainderCash.getLong("cashReceivedMinor"))
        assertEquals(420L,remainderCash.getLong("cashAmountMinor"))
        assertThrows(IllegalArgumentException::class.java) {standaloneSale(d,cat,raw,cat.staff.first(),"terminal-a","CARD",cashParts=listOf(JSONObject(cash.first().toString()).put("amountMinor",500)))}
    }
    @Test fun twentyPoundsLessFiveCashLeavesFifteenForCardWithoutSplitBilling() {
        for (enabled in listOf(false, true)) {
            val raw=config().put("features",JSONObject().put("splitPaymentsEnabled",enabled))
                .put("tenders",org.json.JSONArray().put(JSONObject().put("type","CASH").put("enabled",true)).put(JSONObject().put("type","CARD").put("enabled",true)))
            raw.getJSONObject("goCatalog").getJSONArray("products").getJSONObject(0).apply {
                put("priceMinor",2000);remove("selectionBar")
            }
            val cat=Catalogue.parse(raw);val draft=OrderingDraft();draft.add(cat.products.first(),null,null)
            val cash=listOf(JSONObject().put("id","five-cash").put("type","CASH").put("amountMinor",500).put("receivedMinor",500))
            val sale=standaloneSale(draft,cat,raw,cat.staff.first(),"terminal-a","CARD",cashParts=cash)
            assertEquals(2000L,sale.getLong("amountMinor"))
            assertEquals(1500L,sale.getLong("cardRequestAmountMinor"))
            assertEquals(500L,sale.getLong("cashAmountMinor"))
            assertEquals(1500L,sale.getLong("cardAmountMinor"))
            assertEquals(2000L,sale.objects("tenders").sumOf {it.getLong("amountMinor")})
            assertEquals(0L,sale.getLong("changeMinor"))
        }
    }
    @Test fun standaloneCashPreservesPricesModifiersVatAndChange() {
        val raw=config().put("tenders",org.json.JSONArray().put(JSONObject().put("type","CASH").put("enabled",true)))
        val cat=Catalogue.parse(raw);val d=OrderingDraft();d.add(cat.products.first(),"double",null)
        val sale=standaloneSale(d,cat,raw,cat.staff.first(),"terminal-a","CASH",1000,1000,"sale-a")
        assertEquals(d.total,sale.getLong("amountMinor"));assertEquals(1000-d.total,sale.getLong("changeMinor"))
        assertEquals("terminal-a",sale.getString("terminalId"));assertEquals("PENDING",sale.getString("status"))
        val line=sale.objects("saleItems").single();assertEquals("gin",line.getString("productId"))
        assertEquals(line.getInt("grossMinor"),line.getInt("netMinor")+line.getInt("vatMinor"))
        assertTrue(saleReceipt(sale).contains("Gin"));assertFalse(sale.toString().contains("1234"))
        assertEquals(d.lines.first().modifiers.size,line.objects("modifiers").size)
    }
    @Test fun standaloneTenderCannotCompleteSharedOrUnauthorizedOrUnderpaidBasket() {
        val raw=config().put("tenders",org.json.JSONArray().put(JSONObject().put("type","CASH").put("enabled",true)))
        val cat=Catalogue.parse(raw);val d=OrderingDraft();d.add(cat.products.first(),null,null)
        assertThrows(IllegalArgumentException::class.java) {standaloneSale(d,cat,raw,cat.staff.first(),"terminal-a","CASH",1)}
        assertThrows(IllegalArgumentException::class.java) {standaloneSale(d,cat,raw,cat.staff.first(),"terminal-a","CARD")}
        assertThrows(IllegalArgumentException::class.java) {standaloneSale(d,cat,raw,cat.staff.first().copy(permissions=JSONObject()),"terminal-a","CASH")}
        d.sharedState=JSONObject().put("id","shared-table-a")
        assertThrows(IllegalArgumentException::class.java) {standaloneSale(d,cat,raw,cat.staff.first(),"terminal-a","CASH")}
    }
    @Test fun savedPermissionsApplyEvenBeforeAnAcknowledgementSucceeds() {
        val old = Staff("one","Operator","1234",JSONObject("{\"takePaymentEnabled\":true,\"syncNowEnabled\":true}"))
        val updated = old.copy(permissions = JSONObject("{\"takePaymentEnabled\":true,\"syncNowEnabled\":false}"))
        assertFalse(reauthorizeStaff(old,listOf(updated))!!.allowed("syncNowEnabled"))
        assertNull(reauthorizeStaff(old,emptyList()))
        assertNull(reauthorizeStaff(old,listOf(updated.copy(pin="5678"))))
        assertNull(reauthorizeStaff(old,listOf(updated.copy(permissions=JSONObject()))))
    }
    @Test fun configuredHolesRemainAndUnpositionedProductsFollow() {
        assertEquals(listOf(1,null,3,1000),positioned(listOf(1000,3,1),24) { it })
    }
    private fun config() = JSONObject("""{
      "configVersion":12,"terminalName":"Handheld one","tillLayout":{"name":"Complete bar"},
      "merchant":{"id":"merchant-a","merchantCode":"A","displayName":"Test venue"},
      "assignment":{"deviceRole":"HANDHELD","storeId":"store-a","locationId":"loc-a","locationName":"Garden"},
      "terminalUsers":[{"id":"staff-a","name":"Staff","terminalPin":"1234","permissions":{"takePaymentEnabled":true,"syncNowEnabled":false}}],
      "goCatalog":{"orderScreen":{"id":"home"},
        "orderScreens":[{"id":"home","name":"Home","tiles":[{"kind":"department","targetDepartmentId":"drinks","sortOrder":2}],"quickFireTiles":[{"label":"Food","targetLayoutId":"food"}]},
          {"id":"food","name":"Food","tiles":[],"quickFireTiles":[]}],
        "departments":[{"id":"drinks","name":"Drinks"}],
        "products":[{"id":"gin","name":"Gin","departmentId":"drinks","priceMinor":420,"active":true,"visible":true,"pricingMode":"FIXED",
          "selectionBar":{"version":1,"defaultMeasureId":"single","basePriceMinor":420,"buttons":[
            {"id":"single","label":"Single","kind":"MEASURE","available":true,"priceMinor":420},
            {"id":"double","label":"Double","kind":"MEASURE","available":true,"priceMinor":675},
            {"id":"missing","label":"Missing","kind":"MEASURE","available":false,"priceMinor":null}]}},
          {"id":"plate","name":"Plate","departmentId":"food","priceMinor":725,"pricingMode":"FIXED"}]}}
    """)
    @Test fun completePublishedLayoutRetainsEveryScreenAndTab() {
        val c = Catalogue.parse(config())
        assertEquals(2,c.screens.size); assertEquals("food",c.screens[0].tabs[0].screen)
        assertEquals(2,c.screens[0].tiles[0].slot); assertEquals("Complete bar",c.layoutName)
    }
    @Test fun completeMainShortcutsRemainAvailableDespiteOlderChildTabs() {
        val root = config()
        val screens = root.getJSONObject("goCatalog").getJSONArray("orderScreens")
        val headers = org.json.JSONArray()
        listOf("drinks", "food", "kids", "specials").forEachIndexed { i, id ->
            headers.put(JSONObject().put("label", id).put("targetLayoutId", id).put("sortOrder", i + 1))
            if (id != "food") screens.put(JSONObject().put("id", id).put("name", id).put("tiles", org.json.JSONArray()).put("quickFireTiles", org.json.JSONArray()))
        }
        screens.getJSONObject(0).put("quickFireTiles", headers)
        screens.getJSONObject(1).put("quickFireTiles", org.json.JSONArray().put(headers.getJSONObject(0)).put(headers.getJSONObject(1)))
        val c = Catalogue.parse(root)
        assertEquals(listOf("drinks", "food", "kids", "specials"), c.shortcuts.map { it.screen })
        assertEquals(5, c.screens.size)
        assertEquals(2, c.products.size)
    }
    @Test fun explicitSizePriceIsUsedAndSnapshotted() {
        val product = Catalogue.parse(config()).products.first()
        val draft = OrderingDraft(); draft.add(product,"double",null)
        assertEquals(675L,draft.total); assertEquals("Double",draft.lines.single().modifierSummary)
        assertEquals(675,draft.lines.single().baseUnitPricePence)
    }
    @Test fun missingSizeNeverFallsBackToBasePrice() {
        val draft = OrderingDraft()
        assertThrows(Exception::class.java) { draft.add(Catalogue.parse(config()).products.first(),"missing",null) }
        assertEquals(0L,draft.total)
    }
    @Test fun malformedSelectionCannotSellAtOrdinaryPrice() {
        val root = config(); root.getJSONObject("goCatalog").getJSONArray("products").getJSONObject(0)
            .getJSONObject("selectionBar").put("version",9)
        val p = Catalogue.parse(root).products.first()
        assertNotNull(p.blockedReason); assertThrows(Exception::class.java) { OrderingDraft().add(p,null,null) }
    }
    @Test fun dealUsesCounterAllocationAndRemovesWholeGroup() {
        val products = Catalogue.parse(config()).products
        val button = MeasureButton("deal","2 for £10","DEAL",1000,true,"COUNT",2,false,products.map { it.model.id })
        val draft = OrderingDraft(); draft.addDeal(button,products)
        assertEquals(1000L,draft.total)
        assertEquals(2,draft.lines.size)
        draft.remove(draft.lines.first()); assertTrue(draft.lines.isEmpty())
    }
    @Test fun incompleteOrRepeatedDealCannotCommit() {
        val products = Catalogue.parse(config()).products
        val button = MeasureButton("deal","Pair","DEAL",1001,true,"COUNT",2,false,products.map { it.model.id })
        val draft = OrderingDraft()
        assertThrows(Exception::class.java) { draft.addDeal(button,listOf(products.first())) }
        assertThrows(Exception::class.java) { draft.addDeal(button,listOf(products.first(),products.first())) }
        assertTrue(draft.lines.isEmpty())
    }
    @Test fun permissionsAreExplicitAndMissingPermissionsDeny() {
        val user = Catalogue.parse(config()).staff.single()
        assertTrue(user.allowed("takePaymentEnabled")); assertFalse(user.allowed("syncNowEnabled"))
        assertFalse(user.allowed("terminalSettingsEnabled"))
    }
    @Test fun counterAssignmentCannotBeUsedSilently() {
        val root = config(); root.getJSONObject("assignment").put("deviceRole","COUNTER")
        assertThrows(Exception::class.java) { Catalogue.parse(root) }
    }
    @Test fun incompleteHomeOrDuplicateProductRejectsWholePackage() {
        val root = config(); root.getJSONObject("goCatalog").getJSONObject("orderScreen").put("id","missing")
        assertThrows(Exception::class.java) { Catalogue.parse(root) }
        val other = config(); val products = other.getJSONObject("goCatalog").getJSONArray("products")
        products.put(products.getJSONObject(0))
        assertThrows(Exception::class.java) { Catalogue.parse(other) }
    }
    @Test fun unsupportedModifierProductCannotBypassSelection() {
        val root = config(); root.getJSONObject("goCatalog").getJSONArray("products").getJSONObject(0)
            .put("modifierGroups",org.json.JSONArray("[{\"id\":\"required\"}]"))
        val p = Catalogue.parse(root).products.first()
        assertThrows(Exception::class.java) { OrderingDraft().add(p,null,null) }
    }
    @Test fun exactAllocationForLargePricesAndZeroWeights() {
        assertEquals(listOf(334,334,333),allocateSelectionPrice(1001,listOf(0,0,0)))
        assertEquals(100000000,allocateSelectionPrice(100000000,listOf(100000000,100000000,1)).sum())
    }
    private fun configuredProduct(open: Boolean = false): MenuProduct {
        val root = config()
        root.getJSONObject("goCatalog").getJSONArray("products").getJSONObject(1)
            .put("pricingMode", if (open) "OPEN" else "FIXED")
            .put("modifierGroups", org.json.JSONArray("""[{"id":"extras","name":"Extras","minSelections":1,"maxSelections":3,"includedSelections":1,"allowRepeatSelections":true,"options":[{"id":"cheese","name":"Cheese","priceMinor":50},{"id":"onion","name":"Onion","priceMinor":75}]}]"""))
        return Catalogue.parse(root).products[1]
    }
    @Test fun modifierLimitsAndIncludedRepeatsUseCounterRules() {
        val p = configuredProduct(); val draft = OrderingDraft()
        assertThrows(Exception::class.java) { draft.add(p,null,null) }
        assertTrue(draft.lines.isEmpty())
        draft.add(p,null,null,choices=mapOf("extras" to listOf("cheese","onion","cheese")))
        assertEquals(850L,draft.total)
        assertEquals(listOf(0,75,50),draft.lines.single().modifiers.map { it.priceDeltaPence })
        assertTrue(draft.lines.single().modifiers.first().isIncluded)
        assertThrows(Exception::class.java) { draft.add(p,null,null,choices=mapOf("extras" to List(4) { "cheese" })) }
        assertThrows(Exception::class.java) { draft.add(p,null,null,choices=mapOf("extras" to listOf("unknown"))) }
        assertEquals(1,draft.lines.size)
    }
    @Test fun disallowedRepeatsAndForeignGroupsCannotBeSubmitted() {
        val p = configuredProduct().let { it.copy(groups=it.groups.map { g -> g.copy(repeats=false) }) }
        assertThrows(Exception::class.java) { OrderingDraft().add(p,null,null,choices=mapOf("extras" to listOf("cheese","cheese"))) }
        assertThrows(Exception::class.java) { OrderingDraft().add(p,null,null,choices=mapOf("other" to listOf("cheese"))) }
    }
    @Test fun openAmountUsesExactPenceAndKeepsModifiers() {
        assertEquals(1234,parseOpenAmount("12.34"))
        assertEquals(10,parseOpenAmount("0.10"))
        listOf("0","-1","1.001","NaN","1e3","1000001","999999999999999").forEach { value -> assertThrows(Exception::class.java) { parseOpenAmount(value) } }
        val p = configuredProduct(true); val d = OrderingDraft()
        assertThrows(Exception::class.java) { d.add(p,null,null,choices=mapOf("extras" to listOf("cheese"))) }
        d.add(p,null,null,1234,mapOf("extras" to listOf("cheese","onion")),"Special lunch")
        assertEquals(1309L,d.total)
        assertEquals(1234,d.lines.single().unitPricePence)
        assertThrows(Exception::class.java) { OrderingDraft().add(configuredProduct(),null,null,1234,mapOf("extras" to listOf("cheese"))) }
    }
    @Test fun configuredProductsCannotBypassModifiersThroughDeals() {
        val p = configuredProduct()
        val deal = MeasureButton("test","One","DEAL",100,true,"COUNT",1,false,listOf(p.model.id))
        assertThrows(Exception::class.java) { OrderingDraft().addDeal(deal,listOf(p)) }
    }

    @Test fun openSaleNameIsRequiredAndCanonicalIdentityIsPreserved() {
        val p = configuredProduct(true); val d = OrderingDraft(); val choices = mapOf("extras" to listOf("cheese"))
        assertThrows(Exception::class.java) { d.add(p,null,null,100,choices) }
        listOf(" ","x".repeat(61),"Name\nOther").forEach { assertThrows(Exception::class.java) { validateOpenName(it) } }
        d.add(p,null,null,1234,choices,"  Soup of the day  ")
        assertEquals("Soup of the day",d.lines.single().productName)
        assertEquals(p.model.id,d.lines.single().productId)
        assertEquals(p.model.vatRatePercent,d.lines.single().vatRatePercent)
        assertEquals("Plate",p.model.name)
    }
    @Test fun explicitCrossDepartmentPlacementPreservesGapsAndCanonicalIdentity() {
        val root = config(); val slots = org.json.JSONArray((0 until 24).map { when(it) { 0 -> "plate"; 1 -> "gin"; 2 -> "plate"; 3 -> "deleted"; else -> "" } })
        root.getJSONObject("goCatalog").put("departmentProductPositions",JSONObject().put("drinks",slots))
        val c = Catalogue.parse(root); val grid = c.departmentGrid("drinks")
        assertEquals(24,grid.size); assertEquals("food",grid[0]!!.department)
        assertEquals(725,grid[0]!!.model.pricePence); assertEquals("gin",grid[1]!!.remoteId)
        assertNull(grid[2]); assertNull(grid[3]); assertNull(grid[23])
        assertEquals(2,c.products.size)
    }
    @Test fun explicitlyEmptyPlacementDoesNotFallBackButMissingPlacementDoes() {
        val c = Catalogue.parse(config())
        assertTrue(c.departmentGrid("drinks").any { it?.remoteId == "gin" })
        assertTrue(c.copy(departmentProductPositions=mapOf("drinks" to List(24) { "" })).departmentGrid("drinks").all { it == null })
        val hidden = c.copy(products=c.products.map { it.copy(model=it.model.copy(isAvailableOnTill=false)) },departmentProductPositions=mapOf("drinks" to List(24) { "gin" }))
        assertTrue(hidden.departmentGrid("drinks").all { it == null })
    }
    @Test fun invalidPlacementRejectsWholeConfiguration() {
        val root = config(); root.getJSONObject("goCatalog").put("departmentProductPositions",JSONObject().put("drinks",org.json.JSONArray().put("gin")))
        assertThrows(Exception::class.java) { Catalogue.parse(root) }
    }

    @Test fun serviceModesRepriceBaseOnlyAndRoundTripWithoutCatalogue() {
        val p = configuredProduct().copy(takeawayBasisPoints=1000,takeawayDiscount=73,takeawayVat=0,
            model=configuredProduct().model.copy(vatRatePercent=20))
        val d = OrderingDraft(); d.add(p,null,null,choices=mapOf("extras" to listOf("cheese","onion")))
        assertEquals(800L,d.total)
        d.changeContext(ServiceMode.TAKEAWAY)
        assertEquals(727L,d.total); assertEquals(0,d.lines.single().vatRatePercent)
        val saved = decodeDraft(d.encode())
        assertEquals(d.lines,saved.lines); assertEquals(ServiceMode.TAKEAWAY,saved.serviceMode)
        saved.changeContext(ServiceMode.OUTSIDE); assertEquals(727L,saved.total)
        saved.changeContext(ServiceMode.RESTAURANT,"table-a","Table A")
        assertEquals(800L,saved.total);assertEquals(20,saved.lines.single().vatRatePercent)
        val reopened = decodeDraft(saved.encode())
        assertEquals("table-a",reopened.tableId);assertEquals("Table A",reopened.orderReference)
        assertEquals(saved.lines,reopened.lines)
    }
    @Test fun openAndMeasuredPricesUseBasisPointsAndMatchDisplay() {
        val p = Catalogue.parse(config()).products.first().copy(takeawayBasisPoints=1000,takeawayDiscount=42,takeawayVat=0)
        val d = OrderingDraft();d.changeContext(ServiceMode.TAKEAWAY);d.add(p,"double",null)
        assertEquals(607L,d.total); assertEquals(607,p.displayPrice("double",null,ServiceMode.TAKEAWAY))
        d.changeContext(ServiceMode.RESTAURANT);assertEquals(675L,d.total)
        val open = configuredProduct(true).copy(takeawayBasisPoints=1000,takeawayVat=0)
        val o=OrderingDraft();o.changeContext(ServiceMode.OUTSIDE)
        o.add(open,null,null,1234,mapOf("extras" to listOf("cheese","onion")),"Lunch")
        assertEquals(1186L,o.total)
        val reopened=decodeDraft(o.encode());assertEquals("Lunch",reopened.lines.single().productName)
        reopened.changeContext(ServiceMode.RESTAURANT);assertEquals(1309L,reopened.total)
    }
    @Test fun fixedSelectionPriceDoesNotReceiveSecondTakeawayDiscount() {
        val products=Catalogue.parse(config()).products.map { it.copy(takeawayBasisPoints=5000,takeawayDiscount=100,takeawayVat=0) }
        val d=OrderingDraft();d.changeContext(ServiceMode.TAKEAWAY)
        val b=MeasureButton("pair","Pair","DEAL",1001,true,"COUNT",2,false,products.map { it.model.id })
        d.addDeal(b,products);assertEquals(1001L,d.total)
        val saved=decodeDraft(d.encode());saved.changeContext(ServiceMode.RESTAURANT);assertEquals(1001L,saved.total)
        saved.remove(saved.lines.first());assertTrue(saved.lines.isEmpty());assertTrue(saved.pricing.isEmpty())
    }
    @Test fun legacyBasketLoadsWithoutInventingServicePricing() {
        val d=OrderingDraft();d.add(Catalogue.parse(config()).products.first(),"double",null)
        val old=d.encode();old.remove("mode");old.remove("draftVersion")
        old.getJSONArray("lines").getJSONObject(0).remove("pricing")
        val restored=decodeDraft(old)
        assertEquals(675L,restored.total)
        assertThrows(Exception::class.java) { restored.changeContext(ServiceMode.TAKEAWAY) }
        assertEquals(ServiceMode.RESTAURANT,restored.serviceMode);assertEquals(675L,restored.total)
        restored.changeContext(ServiceMode.RESTAURANT,"a","Table A");assertEquals("a",restored.tableId)
    }
    @Test fun corruptBasketTotalsAndDuplicateIdentitiesAreRejected() {
        val d=OrderingDraft();d.add(Catalogue.parse(config()).products.first(),null,null)
        val bad=d.encode();bad.getJSONArray("lines").getJSONObject(0).put("total",1)
        assertThrows(Exception::class.java) { decodeDraft(bad) }
        val duplicate=d.encode();duplicate.getJSONArray("lines").put(duplicate.getJSONArray("lines").getJSONObject(0))
        assertThrows(Exception::class.java) { decodeDraft(duplicate) }
    }
    @Test fun publishedServiceModeAndRulesAreConsumedWithoutMutatingProductIdentity() {
        val root=config();val c=root.getJSONObject("goCatalog")
        c.put("defaultServiceMode","OUTSIDE").put("capabilities",JSONObject().put("serviceModes",true))
        c.getJSONArray("products").getJSONObject(0).put("takeawayDiscountBasisPoints",1000).put("takeawayDiscountMinor",42).put("takeawayVatRatePercent",0)
        val parsed=Catalogue.parse(root)
        assertEquals(ServiceMode.OUTSIDE,parsed.defaultServiceMode);assertTrue(parsed.serviceModesEnabled)
        assertEquals(378,parsed.products.first().displayPrice(null,null,ServiceMode.TAKEAWAY))
        assertEquals("gin",parsed.products.first().remoteId)
        c.getJSONArray("products").getJSONObject(0).put("takeawayDiscountBasisPoints",10001)
        assertThrows(Exception::class.java) { Catalogue.parse(root) }
    }

    private fun tieredProduct(): MenuProduct {
        val root=config();val p=root.getJSONObject("goCatalog").getJSONArray("products").getJSONObject(1)
        p.put("pricingMode","TIERED_BUILDER").put("takeawayDiscountBasisPoints",5000).put("takeawayDiscountMinor",300)
        p.put("builderPricing",JSONObject("""{"modifierGroupId":"fillings","additionalSelectionPricing":"OPTION_PRICE","tiers":[{"quantity":1,"priceMinor":500},{"quantity":2,"priceMinor":650},{"quantity":3,"priceMinor":750}]}"""))
        p.put("modifierGroups",org.json.JSONArray("""[{"id":"fillings","name":"Fillings","minSelections":1,"maxSelections":null,"includedSelections":3,"allowRepeatSelections":true,"options":[{"id":"cheese","name":"Cheese","priceMinor":50},{"id":"ham","name":"Ham","priceMinor":100}]}]"""))
        return Catalogue.parse(root).products[1]
    }
    @Test fun tieredBuilderUsesSharedCounterPriceAndChargesOnlyAdditionalOptions() {
        val p=tieredProduct();assertNull(p.blockedReason);assertEquals(500,p.displayPrice(null,null,ServiceMode.TAKEAWAY))
        listOf(1 to 500L,2 to 650L,3 to 750L,4 to 800L).forEach { (count,expected) ->
            val d=OrderingDraft();d.changeContext(ServiceMode.TAKEAWAY)
            d.add(p,null,null,choices=mapOf("fillings" to List(count) { "cheese" }))
            assertEquals(expected,d.total)
            d.changeContext(ServiceMode.RESTAURANT);assertEquals(expected,d.total)
            assertEquals(d.lines,decodeDraft(d.encode()).lines)
        }
        assertThrows(Exception::class.java) { OrderingDraft().add(p,null,null) }
    }
    @Test fun quantityScalesModifiersAndSurvivesModeChangesAndRestart() {
        val p=configuredProduct().copy(takeawayBasisPoints=1000,takeawayDiscount=73)
        val d=OrderingDraft();d.add(p,null,null,choices=mapOf("extras" to listOf("cheese","onion")))
        d.setQuantity(d.lines.single(),3);assertEquals(2400L,d.total);assertEquals(3,d.itemCount)
        d.changeContext(ServiceMode.TAKEAWAY);assertEquals(2181L,d.total)
        val restored=decodeDraft(d.encode());assertEquals(3,restored.itemCount);assertEquals(d.lines,restored.lines)
        restored.changeContext(ServiceMode.RESTAURANT);assertEquals(2400L,restored.total)
        assertThrows(Exception::class.java) { restored.setQuantity(restored.lines.single(),0) }
    }
    @Test fun dealQuantityCannotBreakFixedGroupPrice() {
        val products=Catalogue.parse(config()).products;val d=OrderingDraft()
        d.addDeal(MeasureButton("pair","Pair","DEAL",1000,true,"COUNT",2,false,products.map { it.model.id }),products)
        assertThrows(Exception::class.java) { d.setQuantity(d.lines.first(),2) }
        assertEquals(1000L,d.total)
    }
    @Test fun builderRejectsConflictingIncludedCountsAndMeasures() {
        val raw=JSONObject("""{"pricingMode":"TIERED_BUILDER","builderPricing":{"modifierGroupId":"extras","additionalSelectionPricing":"OPTION_PRICE","tiers":[{"quantity":3,"priceMinor":750}]}}""")
        assertThrows(Exception::class.java) { parseMenuBuilder(raw,configuredProduct().groups) }
        raw.put("selectionBar",JSONObject());assertThrows(Exception::class.java) { parseMenuBuilder(raw,emptyList()) }
    }

    @Test fun configuredDealValidatesRequiredChoicesAndAddsOnlyModifierCharges() {
        val p=configuredProduct();val b=MeasureButton("one","One","DEAL",500,true,"COUNT",1,false,listOf(p.model.id))
        val d=OrderingDraft();d.changeContext(ServiceMode.TAKEAWAY)
        assertThrows(Exception::class.java) { d.addDeal(b,listOf(p)) };assertTrue(d.lines.isEmpty())
        d.addDeal(b,listOf(p),listOf(mapOf("extras" to listOf("cheese","onion"))))
        assertEquals(575L,d.total)
        val restored=decodeDraft(d.encode());assertEquals(d.lines,restored.lines)
        restored.remove(restored.lines.first());assertTrue(restored.lines.isEmpty())
    }

    private fun mealConfig(): JSONObject = config().apply {
        put("tenders",org.json.JSONArray().put(JSONObject().put("type","CASH").put("enabled",true)))
        getJSONObject("goCatalog").apply {
            put("departments",org.json.JSONArray(listOf("sub","cake","crisp").map { JSONObject().put("id",it).put("name",it) }))
            put("products",org.json.JSONArray(listOf("sub","cake","crisp").mapIndexed { i,id -> JSONObject().put("id",id).put("name",id).put("departmentId",id).put("priceMinor",listOf(500,200,100)[i]).put("taxRatePercent",20) }))
            put("mealDeals",org.json.JSONArray("""[{"id":"lunch","name":"Lunch","departmentIds":["sub","cake","crisp"],"bundlePriceMinor":600,"sortOrder":0,"active":true}]"""))
        }
    }
    @Test fun mealDealSurvivesRestartAndSaleItemsMatchAmountPaid() {
        val raw=mealConfig();val cat=Catalogue.parse(raw);val d=OrderingDraft()
        cat.products.forEach { d.add(it,null,null) }
        assertEquals(600L,d.total);assertEquals(listOf(375,150,75),d.paidLines.map { it.totalPence })
        val restored=decodeDraft(d.encode());assertEquals(d.total,restored.total)
        val sale=standaloneSale(restored,cat,raw,cat.staff.first(),"terminal-a","CASH",1000)
        assertEquals(600,sale.getInt("amountMinor"));assertEquals(600,sale.objects("saleItems").sumOf { it.getInt("grossMinor") })
        restored.remove(restored.lines.last());assertEquals(700L,restored.total)
    }

}
