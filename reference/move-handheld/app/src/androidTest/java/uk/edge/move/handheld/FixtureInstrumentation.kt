package uk.edge.move.handheld

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import org.json.JSONObject
import org.json.JSONArray

/** Signed test APK only. Seeds the separate debuggable benchmark install; never targets a real pairing. */
class FixtureInstrumentation : Instrumentation() {
    private var trainingTests=false
    private var tradingTests=false
    private var reportTests=false
    override fun onCreate(arguments: Bundle?) { trainingTests=arguments?.getString("trainingTests")=="true"; tradingTests=arguments?.getString("tradingTests")=="true";reportTests=arguments?.getString("reportTests")=="true";super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            check(targetContext.packageName == "uk.edge.move.handheld.staging.benchmark")
            check(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            if(trainingTests) {runTrainingTests();finish(Activity.RESULT_OK,Bundle().apply {putString("stream","Training isolation, blocked card/refund, zero upload, day restart and unpair passcode tests passed. Synthetic package only.\n")});return}
            if(reportTests) {runReportTests();finish(Activity.RESULT_OK,Bundle().apply {putString("stream","Reporting replay/backoff/config acknowledgement tests passed; synthetic benchmark only.\n")});return}
            if(tradingTests) {runTradingTests();finish(Activity.RESULT_OK,Bundle().apply {putString("stream","Trading SQLite crash/restart/idempotency tests passed. Synthetic benchmark database only.\n")});return}
            val directory = targetContext.filesDir
            check(!java.io.File(directory,"pairing.json").exists()) { "Refusing to overwrite an existing pairing; clear only the dedicated synthetic benchmark install before repeating." }
            val pair = JSONObject("""{"setupCode":"SIM123","merchantCode":"SIMULATED","merchant":{"id":"synthetic"},"terminalCode":"synthetic-handheld","terminalName":"SIMULATED HANDHELD","credentials":{"apiKey":"NOT-A-REAL-CREDENTIAL"}}""")
            val config = JSONObject("""{"handheldFixture":true,"configVersion":1,"terminalName":"SIMULATED · 1,000 products",
              "tillLayout":{"name":"Synthetic complete layout"},"merchant":{"id":"synthetic","merchantCode":"SIMULATED","displayName":"SIMULATED TEST VENUE"},
              "assignment":{"deviceRole":"HANDHELD","storeId":"synthetic-store","locationId":"synthetic-location","locationName":"TEST DATA ONLY"},
              "terminalUsers":[{"id":"fixture-operator","name":"Simulated operator","terminalPin":"2468","permissions":{"takePaymentEnabled":true,"syncNowEnabled":false}}],
              "goCatalog":{"orderScreen":{"id":"home"},"orderScreens":[{"id":"home","name":"Test menu","tiles":[{"kind":"department","label":"Drinks","targetDepartmentId":"drinks","sortOrder":1},{"kind":"department","label":"Small plates","targetDepartmentId":"food","sortOrder":3}],"quickFireTiles":[{"label":"Drinks","targetDepartmentId":"drinks"},{"label":"Food","targetLayoutId":"food-screen"}]},{"id":"food-screen","name":"Small plates","tiles":[{"kind":"department","label":"Small plates","targetDepartmentId":"food","sortOrder":1}],"quickFireTiles":[]}],
              "departments":[{"id":"drinks","name":"Drinks"},{"id":"food","name":"Small plates"}],"products":[]}}""")
            val products = config.getJSONObject("goCatalog").getJSONArray("products")
            repeat(1000) { i ->
                val bar = if (i < 500) JSONObject("""{"version":1,"defaultMeasureId":"single","basePriceMinor":450,"buttons":[{"id":"single","label":"Single","kind":"MEASURE","available":true,"priceMinor":450},{"id":"double","label":"Double","kind":"MEASURE","available":true,"priceMinor":675}]}""")
                else JSONObject("""{"version":1,"defaultMeasureId":"","basePriceMinor":725,"buttons":[{"id":"pair","label":"2 for £14.50","kind":"DEAL","available":true,"priceMinor":1450,"selectionMode":"COUNT","selectionCount":2,"allowRepeats":false,"eligibleProductIds":["p500","p501","p502"]}]}""")
                products.put(JSONObject().put("id","p$i").put("name", when(i) { 0 -> "Rose Gin"; 1 -> "London Dry Gin"; 500 -> "Patatas bravas"; 501 -> "Garlic mushrooms"; 502 -> "Crispy squid"; else -> "Test product ${i+1}" })
                    .put("departmentId",if(i < 500) "drinks" else "food").put("priceMinor",if(i < 500) 450 else 725)
                    .put("pricingMode","FIXED").put("sortOrder",i%500+1).put("active",true).put("visible",true).put("selectionBar",bar))
            }
            Catalogue.parse(config)
            java.io.File(directory,"pairing.json").writeText(pair.toString())
            java.io.File(directory,"catalogue.json").writeText(config.toString())
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Seeded separate benchmark package: synthetic operator, 1,000 products, 2 complete screens. No real merchant/session or network pairing.\n") })
        } catch(e:Exception) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",e.toString()) }) }
    }
    private fun runTrainingTests() {
        // This runner refuses every package except the separate debuggable benchmark.
        targetContext.deleteDatabase("handheld-training.db")
        targetContext.deleteDatabase("handheld-trading.db")
        java.io.File(targetContext.filesDir,"pairing.json").writeText("""{"merchantCode":"SIMULATED","terminalCode":"TEST"}""")
        java.io.File(targetContext.filesDir,"catalogue.json").writeText("""{"configVersion":1}""")
        val training=DeviceStore(targetContext,training=true) {_,_->error("Training attempted reporting")}
        val live=DeviceStore(targetContext)
        val user=Staff("test","Test","2468",JSONObject().put("startDayEnabled",true).put("takePaymentEnabled",true))
        val cat=Catalogue(1,"SIMULATED","SIMULATED","Test","TEST","","","","",emptyList(),emptyList(),emptyList(),"",listOf(user))
        check(runCatching {training.beginSale(OrderingDraft(),cat,user,"CARD")}.exceptionOrNull()?.message=="Card payments are disabled in training mode.")
        check(training.trading.pending()==null)
        check(runCatching {training.beginRefund(JSONObject(),user,"test")}.isFailure)
        check(runCatching {training.sync()}.isFailure)
        check(runCatching {live.requireReadyForReassignment("0000")}.exceptionOrNull()?.message=="Passcode not recognised.")
        live.requireReadyForReassignment("9999")
        training.startDay(user,1000)
        val first=training.trading.openDay()!!
        check(live.trading.openDay()==null)
        val sale=JSONObject().put("id","training-test").put("merchantId","SIMULATED").put("terminalId","TEST").put("simulated",true)
        training.trading.writableDatabase.execSQL("INSERT INTO sales(id,payload,created) VALUES(?,?,?)",arrayOf<Any>("training-test",sale.toString(),1))
        training.trading.closeDay(first.getString("id"),null,user.id,user.name,"Training")
        check(training.trading.pendingDayCloses().size==1)
        check(training.uploadSales()==0 && training.pendingReports==0)
        check(live.trading.unuploaded().isEmpty() && live.trading.pendingDayCloses().isEmpty())
        check(dayReceipt(training.trading.lastDayClose()!!).contains("TRAINING"))
        training.startDay(user,0)
        check(training.trading.openDay()!!.getString("id")!=first.getString("id"))
        training.trading.close()
        val restarted=DeviceStore(targetContext,training=true) {_,_->error("Training restart attempted reporting")}
        check(restarted.trading.unuploaded().size==1 && restarted.uploadSales()==0)
        restarted.trading.close();live.trading.close()
        targetContext.deleteDatabase("handheld-training.db");targetContext.deleteDatabase("handheld-trading.db")
        java.io.File(targetContext.filesDir,"pairing.json").delete();java.io.File(targetContext.filesDir,"catalogue.json").delete()
    }
    private fun runReportTests() {
        // Isolated package, no real credentials, no HTTP: transport is injected in-process only.
        val prefs=targetContext.getSharedPreferences("handheld",0)
        prefs.edit().clear().commit()
        targetContext.deleteDatabase("handheld-trading.db")
        java.io.File(targetContext.filesDir,"pairing.json").writeText("""{"merchantCode":"SIMULATED","terminalCode":"TEST"}""")
        java.io.File(targetContext.filesDir,"catalogue.json").writeText("""{"configVersion":1}""")
        var calls=0
        val persisted=mutableSetOf<String>()
        var fail=true
        val transport:(String,JSONObject)->JSONObject={path,body ->
            calls++
            if(path.endsWith("transactions")) {
                check(!body.has("cardholderReceipt"))
                persisted.add(body.getString("id"))
                if(fail) throw java.io.IOException("Synthetic lost acknowledgement after persistence")
            }
            JSONObject().put("ok",true)
        }
        var store=DeviceStore(targetContext,reportTransport=transport)
        val sale=JSONObject().put("id","replay-test").put("merchantId","SIMULATED").put("terminalId","TEST").put("cardholderReceipt","PRIVATE")
        store.trading.writableDatabase.execSQL("INSERT INTO sales(id,payload,created) VALUES(?,?,?)",arrayOf<Any>("replay-test",sale.toString(),1))
        check(runCatching {store.uploadSales()}.isFailure)
        val deadline=store.retryAt
        check(deadline>System.currentTimeMillis() && store.pendingReports==2)
        repeat(5) {store.uploadSales()};check(calls==1)
        store.trading.close();store=DeviceStore(targetContext,reportTransport=transport)
        check(store.retryAt==deadline);store.uploadSales();check(calls==1)
        // Advance the stored deadline in the isolated test, without waiting minutes.
        prefs.edit().putLong("reportRetryAt",0).commit();fail=false
        store.uploadSales()
        check(persisted.size==1 && store.pendingReports==0 && store.acknowledged==1)
        val before=calls;repeat(5) {store.uploadSales()};check(calls==before)
        store.trading.close()
        java.io.File(targetContext.filesDir,"catalogue.json").writeText("""{"configVersion":2}""")
        store=DeviceStore(targetContext) {path,body ->
            check(path.endsWith("config/applied") && body.getInt("appliedConfigVersion")==2)
            // Simulate a newer durable config arriving while the older ack is in flight.
            store.config!!.put("configVersion",3)
            java.io.File(targetContext.filesDir,"catalogue.json").writeText(store.config.toString())
            JSONObject().put("ok",true)
        }
        store.uploadSales();check(store.acknowledged==2 && store.pendingReports==1)
        store.trading.close();store=DeviceStore(targetContext) {path,body ->
            check(path.endsWith("config/applied") && body.getInt("appliedConfigVersion")==3)
            JSONObject().put("ok",true)
        }
        store.uploadSales();check(store.pendingReports==0 && store.acknowledged==3)
        store.trading.close()
        java.io.File(targetContext.filesDir,"catalogue.json").writeText("""{"configVersion":4}""")
        var ackCalls=0
        store=DeviceStore(targetContext) {path,_ ->
            check(path.endsWith("config/applied"));ackCalls++
            JSONObject().put("ok",false)
        }
        check(runCatching {store.uploadSales()}.isFailure)
        check(store.acknowledged==3 && store.pendingReports==1)
        repeat(3) {store.uploadSales()};check(ackCalls==1)
        store.trading.close();prefs.edit().putLong("reportRetryAt",0).commit()
        store=DeviceStore(targetContext) {path,_ ->check(path.endsWith("config/applied"));JSONObject().put("ok",true)}
        store.uploadSales();check(store.acknowledged==4 && store.pendingReports==0)
        val close=JSONObject().put("id","close-local").put("reportId","close-server").put("merchantCode","SIMULATED").put("terminalId","TEST")
        store.trading.writableDatabase.execSQL("INSERT INTO days(id,payload) VALUES(?,?)",arrayOf("close-local",close.toString()))
        store.trading.close()
        store=DeviceStore(targetContext) {path,_ ->check(path.endsWith("trading-day-close"));JSONObject().put("ok",true).put("reportId","wrong-report")}
        check(runCatching {store.uploadSales()}.isFailure);check(store.pendingReports==1)
        store.trading.close();prefs.edit().putLong("reportRetryAt",0).commit()
        store=DeviceStore(targetContext) {path,body ->check(path.endsWith("trading-day-close"));JSONObject().put("ok",true).put("reportId",body.getString("reportId"))}
        store.uploadSales();check(store.pendingReports==0);store.trading.close()
        // Remove only synthetic benchmark state so the normal fixture can be reseeded.
        targetContext.deleteDatabase("handheld-trading.db");prefs.edit().clear().commit()
        java.io.File(targetContext.filesDir,"pairing.json").delete()
        java.io.File(targetContext.filesDir,"catalogue.json").delete()
    }
    private fun runTradingTests() {
        val name="handheld-trading-tests.db"
        targetContext.deleteDatabase(name)
        var ledger=TradingStore(targetContext,name)
        val draft=OrderingDraft().apply {orderReference="ACTIVE TEST"}.encode().put("merchantCode","SIMULATED").put("terminalCode","test")
        ledger.draft {draft}
        ledger.requireReadyForReassignment()
        val payload=JSONObject().put("id","sale-fixture").put("type","SALE").put("createdAt",1).put("amountMinor",420)
            .put("saleItems",JSONArray().put(JSONObject().put("id","fixture-line").put("productName","Synthetic item").put("quantity",1).put("grossMinor",420).put("totalMinor",420).put("netMinor",350).put("vatMinor",70)))
            .put("tenders",JSONArray().put(JSONObject().put("type","CARD").put("amountMinor",420)))
        ledger.begin(payload,draft)
        check(runCatching {ledger.requireReadyForReassignment()}.isFailure)
        check(runCatching {ledger.saveDraft(draft)}.isFailure)
        val finalResult=JSONObject().put("status","APPROVED").put("amountMinor",420).put("providerId","fixture-uti")
        ledger.writableDatabase.execSQL("CREATE TRIGGER fail_result BEFORE INSERT ON state WHEN NEW.id='attempt' BEGIN SELECT RAISE(ABORT, 'synthetic interruption before result association'); END")
        check(runCatching {ledger.recordFinalCallback("sale-fixture",finalResult)}.isFailure)
        check(ledger.pending()?.has("processorResult")==false)
        check(ledger.readableDatabase.rawQuery("SELECT COUNT(*) FROM callbacks",null).use {it.moveToFirst();it.getInt(0)}==0)
        ledger.writableDatabase.execSQL("DROP TRIGGER fail_result")
        check(!ledger.recordFinalCallback("stale-request",finalResult))
        check(ledger.recordFinalCallback("sale-fixture",finalResult))
        check(ledger.recordFinalCallback("sale-fixture",finalResult))
        check(ledger.readableDatabase.rawQuery("SELECT COUNT(*) FROM callbacks",null).use {it.moveToFirst();it.getInt(0)}==1)
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.pending()?.getJSONObject("processorResult")?.getString("providerId")=="fixture-uti")
        ledger.writableDatabase.execSQL("CREATE TRIGGER fail_draft BEFORE INSERT ON state WHEN NEW.id='draft' BEGIN SELECT RAISE(ABORT, 'synthetic interrupted completion'); END")
        check(runCatching {ledger.recoverRecordedResult()}.isFailure)
        check(ledger.sale("sale-fixture")==null)
        check(ledger.pending()!=null)
        check(ledger.draft {error("Lost draft")}.getString("reference")=="ACTIVE TEST")
        ledger.writableDatabase.execSQL("DROP TRIGGER fail_draft")
        ledger.close();ledger=TradingStore(targetContext,name)
        ledger.recoverRecordedResult()
        check(ledger.pending()==null)
        check(ledger.lastOutcome()?.getString("status")=="APPROVED")
        ledger.dismissOutcome("different-result");check(ledger.lastOutcome()!=null)
        ledger.dismissOutcome("sale-fixture");check(ledger.lastOutcome()==null)
        check(ledger.unuploaded().size==1)
        check(runCatching {ledger.requireReadyForReassignment()}.isFailure)
        check(runCatching {ledger.saveDraft(draft,draft)}.isFailure) // stale UI cannot restore the paid basket
        check(decodeDraft(ledger.draft {error("Lost draft")}).orderReference==null)
        check(ledger.finish("sale-fixture","APPROVED","fixture-uti")==null)
        check(ledger.unuploaded().size==1)
        ledger.markUploaded("sale-fixture");check(ledger.unuploaded().isEmpty())
        ledger.requireReadyForReassignment()
        check(ledger.uploaded("sale-fixture"));check(ledger.sale("sale-fixture")!=null)
        check(ledger.providerSeen("fixture-uti"))
        val second=OrderingDraft().apply {orderReference="SECOND"}.encode()
        ledger.saveDraft(second)
        val secondPayload=JSONObject(payload.toString()).put("id","second")
        ledger.begin(secondPayload,second)
        check(ledger.finish("sale-fixture","APPROVED","fixture-uti")==null)
        check(ledger.pending()?.getString("id")=="second")
        val partial=JSONObject().put("status","APPROVED").put("amountMinor",200).put("providerId","partial-uti")
        ledger.recordCallback(partial);ledger.recordCallback(partial)
        check(ledger.readableDatabase.rawQuery("SELECT COUNT(*) FROM callbacks",null).use {it.moveToFirst();it.getInt(0)}==2)
        check(ledger.pending()!=null);check(ledger.sale("second")==null) // journalling alone never approves
        ledger.recordResult("second",JSONObject().put("status","CANCELLED").put("providerId","cancel-uti"))
        ledger.recoverRecordedResult();check(ledger.pending()==null)
        check(ledger.lastOutcome()?.getString("status")=="CANCELLED")
        ledger.dismissOutcome("second");check(ledger.lastOutcome()==null)
        check(ledger.draft {error("Lost unpaid basket")}.getString("reference")=="SECOND")
        val archivedAttempt=JSONObject(secondPayload.toString()).put("id","operator-reset")
        ledger.begin(archivedAttempt,second)
        check(runCatching {ledger.archiveStagingAttempt("wrong-id","operator","authorised test")}.isFailure)
        ledger.archiveStagingAttempt("operator-reset","operator","authorised test")
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.pending()==null && ledger.sale("operator-reset")==null)
        check(ledger.draft {error("Lost basket")}.getString("reference")=="SECOND")
        check(ledger.readableDatabase.rawQuery("SELECT payload FROM attempts WHERE id='operator-reset'",null).use {
            it.moveToFirst() && JSONObject(it.getString(0)).getString("operatorAction")=="ARCHIVE_STAGING_ATTEMPT"
        })
        val guarded=JSONObject(secondPayload.toString()).put("id","saved-result")
        ledger.begin(guarded,second);ledger.recordFinalCallback("saved-result",JSONObject().put("status","CANCELLED").put("providerId","saved-cancel"))
        check(runCatching {ledger.archiveStagingAttempt("saved-result","operator","authorised test")}.isFailure)
        ledger.recoverRecordedResult()
        val refundUser=Staff("refund-test","Refund test","9999",JSONObject().put("refundsEnabled",true))
        val refund=standaloneRefund(requireNotNull(ledger.sale("sale-fixture")),refundUser,"Fixture return").put("id","refund-one")
        ledger.beginRefund(refund)
        ledger.recordResult("refund-one",JSONObject().put("status","APPROVED").put("providerId","refund-provider").put("amountMinor",420))
        ledger.close();ledger=TradingStore(targetContext,name);ledger.recoverRecordedResult()
        check(ledger.sale("refund-one")?.getString("type")=="REFUND")
        check(ledger.draft {error("Basket lost during refund")}.getString("reference")=="SECOND")
        check(runCatching {ledger.beginRefund(JSONObject(refund.toString()).put("id","duplicate-refund"))}.isFailure)
        check(ledger.pending()==null)
        // Partial refunds survive SQLite close/reopen and retain per-line quantities.
        val multi=JSONObject(payload.toString()).put("id","multi-refund-sale").put("status","APPROVED").put("amountMinor",1000)
            .put("saleItems",JSONArray().put(JSONObject().put("id","multi-line").put("productName","Synthetic item").put("quantity",3).put("grossMinor",1000).put("totalMinor",1000).put("netMinor",833).put("vatMinor",167)))
            .put("tenders",JSONArray().put(JSONObject().put("type","CARD").put("amountMinor",1000)))
        ledger.writableDatabase.execSQL("INSERT INTO sales(id,payload,created) VALUES(?,?,?)",arrayOf<Any>(multi.getString("id"),multi.toString(),2))
        val one=standaloneRefund(multi,refundUser,"One returned",quantities=mapOf("multi-line" to 1))
        ledger.beginRefund(one)
        ledger.recordFinalCallback(one.getString("id"),JSONObject().put("status","APPROVED").put("providerId","partial-refund-result"))
        ledger.close();ledger=TradingStore(targetContext,name);ledger.recoverRecordedResult()
        val prior=ledger.refundsFor(multi.getString("id"))
        check(prior.size==1 && prior.single().getLong("amountMinor")==333L)
        check(refundableQuantities(multi,prior).getValue("multi-line")==2)
        val cancel=standaloneRefund(multi,refundUser,"Cancel test",refunds=prior)
        ledger.beginRefund(cancel);ledger.finish(cancel.getString("id"),"CANCELLED","cancel-test")
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.refundsFor(multi.getString("id")).size==1)
        val rest=standaloneRefund(multi,refundUser,"Remaining returned",refunds=ledger.refundsFor(multi.getString("id")))
        check(rest.getLong("amountMinor")==667L)
        ledger.beginRefund(rest);ledger.finish(rest.getString("id"),"APPROVED","remaining-refund-result")
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.refundsFor(multi.getString("id")).sumOf {it.getLong("amountMinor")}==1000L)
        check(runCatching {ledger.beginRefund(rest)}.isFailure)
        // Mixed refund keeps cash unrecorded until the existing card result approves.
        val mixed=JSONObject(multi.toString()).put("id","mixed-refund-sale").put("amountMinor",1900)
            .put("saleItems",JSONArray().put(JSONObject().put("id","mixed-line").put("productName","Synthetic mixed item").put("quantity",1).put("grossMinor",1900).put("totalMinor",1900).put("netMinor",1900).put("vatMinor",0)))
            .put("tenders",JSONArray().put(JSONObject().put("type","CASH").put("amountMinor",1000)).put(JSONObject().put("type","CARD").put("amountMinor",900)))
        ledger.writableDatabase.execSQL("INSERT INTO sales(id,payload,created) VALUES(?,?,?)",arrayOf<Any>(mixed.getString("id"),mixed.toString(),3))
        val mixedCancelled=standaloneRefund(mixed,refundUser,"Cancelled mixed refund")
        ledger.beginRefund(mixedCancelled)
        check(ledger.pending()?.getLong("cardRequestAmountMinor")==900L)
        check(ledger.refundsFor(mixed.getString("id")).isEmpty())
        ledger.recordFinalCallback(mixedCancelled.getString("id"),JSONObject().put("status","CANCELLED").put("providerId","mixed-cancel"))
        ledger.close();ledger=TradingStore(targetContext,name);ledger.recoverRecordedResult()
        check(ledger.refundsFor(mixed.getString("id")).isEmpty())
        val mixedApproved=standaloneRefund(mixed,refundUser,"Approved mixed refund",refunds=ledger.refundsFor(mixed.getString("id")))
        ledger.beginRefund(mixedApproved)
        ledger.recordFinalCallback(mixedApproved.getString("id"),JSONObject().put("status","APPROVED").put("providerId","mixed-approve"))
        ledger.close();ledger=TradingStore(targetContext,name);ledger.recoverRecordedResult();ledger.recoverRecordedResult()
        val savedMixed=ledger.refundsFor(mixed.getString("id"))
        check(savedMixed.size==1 && savedMixed.single().getLong("amountMinor")==1900L)
        check(savedMixed.single().getLong("cashAmountMinor")==1000L && savedMixed.single().getLong("cardAmountMinor")==900L)
        check(runCatching {ledger.beginRefund(mixedApproved)}.isFailure)
        val partDraft=JSONObject(second.toString()).put("lines",JSONArray().put(JSONObject().put("id",1).put("productId",1).put("name","Synthetic item").put("price",1000).put("vat",0).put("total",1000).put("modifierTotal",0)))
        ledger.saveDraft(partDraft)
        ledger.addCashPart("cash-one",300,partDraft,"synthetic-staff")
        ledger.addCashPart("cash-one",300,partDraft,"synthetic-staff")
        check(ledger.cashParts().sumOf {it.getLong("amountMinor")}==300L)
        check(runCatching {ledger.saveDraft(second)}.isFailure)
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.cashParts().size==1)
        val split=JSONObject().put("id","split-cancel").put("type","SALE").put("createdAt",2).put("amountMinor",1000).put("cashAmountMinor",300).put("cardAmountMinor",700).put("cardRequestAmountMinor",700).put("tenders",JSONArray(ledger.cashParts()+JSONObject().put("type","CARD").put("amountMinor",700)))
        ledger.begin(split,partDraft);ledger.finish("split-cancel","CANCELLED","cancel-split")
        check(ledger.cashParts().size==1);check(ledger.sale("split-cancel")==null)
        split.put("id","split-approved");ledger.begin(split,partDraft)
        ledger.finish("split-approved","APPROVED","approved-split")
        check(ledger.cashParts().isEmpty());check(ledger.sale("split-approved")?.getLong("cardAmountMinor")==700L)
        ledger.saveDraft(partDraft);ledger.addCashPart("cash-return",200,partDraft,"synthetic-staff");ledger.returnCash("synthetic-staff")
        check(ledger.cashParts().isEmpty());check(decodeDraft(ledger.draft {error("Lost basket")}).total==1000L)
        ledger.openLocalTable("local-one","Table 1")
        val tableDraft=ledger.draft {error("Table missing")}
        check(decodeDraft(tableDraft).tableId=="local-one")
        ledger.saveLocalTable(tableDraft)
        check(runCatching {ledger.requireReadyForReassignment()}.isFailure)
        check(decodeDraft(ledger.draft {error("Missing draft")}).lines.isEmpty())
        ledger.close();ledger=TradingStore(targetContext,name)
        check(decodeDraft(requireNotNull(ledger.localTable("local-one"))).total==1000L)
        ledger.openLocalTable("local-one","Table 1")
        check(decodeDraft(ledger.draft {error("Lost reopened table")}).total==1000L)
        check(ledger.localTable("local-one")==null)
        check(runCatching {ledger.openLocalTable("local-two","Table 2")}.isFailure)
        // Migrate the exact v1 schema in place, preserving its draft and sales.
        ledger.writableDatabase.execSQL("DROP TABLE days")
        ledger.writableDatabase.version=1
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.sale("sale-fixture")!=null)
        check(decodeDraft(ledger.draft {error("Migration lost draft")}).total==1000L)
        val day=JSONObject().put("id","day-one").put("tradingDate","2026-09-24").put("openingFloatMinor",5000)
        ledger.startDay(day)
        check(runCatching {ledger.requireReadyForReassignment()}.isFailure)
        check(runCatching {ledger.startDay(day)}.isFailure)
        check(runCatching {ledger.closeDay("day-one",5000,"operator","Operator","")}.isFailure) // active basket
        val expected=ledger.draft {error("Lost draft")}
        val daySale=JSONObject().put("id","day-sale").put("type","SALE").put("createdAt",3).put("amountMinor",1000)
            .put("tenders",JSONArray().put(JSONObject().put("type","CASH").put("amountMinor",400)).put(JSONObject().put("type","CARD").put("amountMinor",600)))
        ledger.begin(daySale,expected)
        check(runCatching {ledger.closeDay("day-one",5400,"operator","Operator","")}.isFailure)
        ledger.finish("day-sale","APPROVED","day-provider")
        check(ledger.sale("day-sale")?.getString("tradingDayId")=="day-one")
        check(ledger.currentDayReport()?.getLong("expectedCashMinor")==5400L)
        ledger.writableDatabase.execSQL("CREATE TRIGGER fail_close BEFORE INSERT ON days BEGIN SELECT RAISE(ABORT, 'synthetic interrupted close'); END")
        check(runCatching {ledger.closeDay("day-one",5300,"operator","Operator","")}.isFailure)
        check(ledger.openDay()!=null && ledger.pendingDayCloses().isEmpty())
        ledger.writableDatabase.execSQL("DROP TRIGGER fail_close")
        val denominationCounts=CashCount().apply {key("1");selected=4;key("1");selected=5;key("1")}
        ledger.closeDay("day-one",5300,"operator","Operator","Short £1",denominationCounts.json())
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.openDay()==null)
        check(ledger.pendingDayCloses().single().getJSONArray("cashDenominations").length()==3)
        check(ledger.pendingDayCloses().single().getLong("cashVarianceMinor")==-100L)
        check(runCatching {ledger.closeDay("day-one",5300,"operator","Operator","")}.isFailure)
        ledger.markDayUploaded("day-one");ledger.markDayUploaded("day-one")
        check(ledger.pendingDayCloses().isEmpty() && ledger.lastDayClose()!=null)
        // Synthetic Z handoff: dispatch does not close; restart and duplicate callbacks are safe.
        ledger.startDay(JSONObject().put("id","day-z").put("openingFloatMinor",1000))
        ledger.prepareDayEnd("day-z",900,"operator","Operator")
        check(ledger.openDay()!=null && ledger.pendingDayCloses().isEmpty())
        check(runCatching {ledger.prepareDayEnd("day-z",900,"operator","Operator")}.isFailure)
        check(runCatching {ledger.begin(daySale,ledger.draft {error("Missing draft")})}.isFailure)
        ledger.dayEndLaunchFailed("wrong-id");check(ledger.pendingDayEnd()!=null)
        ledger.dayEndLaunchFailed("day-z");check(ledger.openDay()!=null && ledger.pendingDayEnd()==null)
        ledger.prepareDayEnd("day-z",900,"operator","Operator")
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.pendingDayEnd()?.getLong("counted")==900L)
        ledger.writableDatabase.execSQL("CREATE TRIGGER fail_z BEFORE INSERT ON days BEGIN SELECT RAISE(ABORT, 'synthetic interrupted Z close'); END")
        check(runCatching {ledger.completeDayEnd()}.isFailure)
        check(ledger.openDay()!=null && ledger.pendingDayEnd()!=null && ledger.pendingDayCloses().isEmpty())
        ledger.writableDatabase.execSQL("DROP TRIGGER fail_z")
        check(ledger.completeDayEnd()?.getLong("cashVarianceMinor")==-100L)
        check(ledger.completeDayEnd()==null && ledger.openDay()==null && ledger.pendingDayEnd()==null)
        ledger.dayEndPrintResult("Day closed · totals printed")
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.consumeDayEndResult()?.getString("message")=="Day closed · totals printed")
        check(ledger.consumeDayEndResult()==null && ledger.pendingDayCloses().size==1)
        ledger.startDay(JSONObject().put("id","optional-count").put("openingFloatMinor",500))
        ledger.prepareDayEnd("optional-count",null,"operator","Operator")
        check(ledger.pendingDayEnd()?.isNull("counted")==true)
        ledger.acknowledgeDayEnd("z-uti")
        check(!ledger.failDayEnd("other-uti","Wrong report"))
        check(!ledger.failDayEnd(null,"Unmatched report"))
        ledger.close();ledger=TradingStore(targetContext,name)
        check(ledger.pendingDayEnd()?.getString("uti")=="z-uti")
        check(ledger.failDayEnd("z-uti","Empty batch"))
        check(ledger.openDay()!=null && ledger.pendingDayEnd()==null)
        check(ledger.consumeDayEndError()=="Empty batch")
        ledger.prepareDayEnd("optional-count",null,"operator","Operator")
        val optional= requireNotNull(ledger.completeDayEnd())
        check(optional.isNull("countedCashMinor") && optional.isNull("cashVarianceMinor"))
        check(ledger.openDay()==null)
        ledger.close();targetContext.deleteDatabase(name)
    }

}
