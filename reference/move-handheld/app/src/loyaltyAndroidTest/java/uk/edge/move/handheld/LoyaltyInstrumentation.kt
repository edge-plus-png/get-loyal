package uk.edge.move.handheld

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

/** Synthetic SQLite tests in a separate package/database. No payment dispatch. */
class LoyaltyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            check(targetContext.packageName == "uk.edge.move.handheld.loyaltytest.staging.benchmark")
            check(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            val name="loyalty-fixture.db"
            targetContext.deleteDatabase(name)
            var store=TradingStore(targetContext,name)
            fun begin(id:String) {
                val draft=OrderingDraft().encode()
                store.draft {draft}
                store.begin(JSONObject("""{"id":"$id","loyaltyTest":true,"type":"SALE","createdAt":1,"amountMinor":1000,"cardRequestAmountMinor":1000,"cashAmountMinor":0,"cardAmountMinor":1000,"tenders":[{"type":"CARD","amountMinor":1000}],"saleItems":[{"grossMinor":1000,"taxRatePercent":0}]}"""),store.draft {draft})
            }
            begin("approval")
            store.recordFinalCallback("approval",JSONObject("""{"status":"APPROVED","providerId":"SYNTHETIC-1","amountMinor":900,"discountMinor":100}"""))
            store.close();store=TradingStore(targetContext,name);store.recoverRecordedResult()
            check(store.pending()==null && store.sale("approval")!!.getLong("amountMinor")==900L)
            store.recoverRecordedResult();check(store.history().size==1)
            check(store.finish("approval","APPROVED","SYNTHETIC-1")==null)
            for(status in listOf("DECLINED","CANCELLED")) {
                begin(status);store.recordFinalCallback(status,JSONObject().put("status",status).put("providerId",status))
                store.recoverRecordedResult();check(store.pending()==null && store.sale(status)==null)
            }
            begin("unresolved")
            store.recordFinalCallback("unresolved",JSONObject("""{"status":"APPROVED","providerId":"SYNTHETIC-2","amountMinor":900}"""))
            store.recoverRecordedResult();check(store.sale("unresolved")==null && store.pending()!!.has("loyaltyReconciliationError"))
            store.close();store=TradingStore(targetContext,name);store.recoverRecordedResult()
            check(store.pending()!!.getString("id")=="unresolved" && store.history().size==1)
            store.close();targetContext.deleteDatabase(name)
            verifyStagingUpload()
            finish(Activity.RESULT_OK,Bundle().apply {putString("stream","PASS: discounted commit, restart replay, idempotency, decline, cancellation, missing discount retained across restart, staging sale tagging, cash/refund exclusions, upload failure/restart/retry, discounted upload and VAT totals. Synthetic benchmark with injected transport only; no network or PAX dispatch.\n")})
        } catch(e:Exception) {finish(Activity.RESULT_CANCELED,Bundle().apply {putString("stream","FAIL: $e\n")})}
    }
    private fun verifyStagingUpload() {
        val directory=targetContext.filesDir
        check(!java.io.File(directory,"pairing.json").exists()) {"Use an empty synthetic benchmark install."}
        targetContext.deleteDatabase("handheld-trading.db")
        val config=JSONObject("""{"configVersion":1,"terminalName":"Synthetic","tillLayout":{"name":"Synthetic"},
          "merchant":{"id":"SIMULATED","merchantCode":"SIMULATED","displayName":"Synthetic"},
          "assignment":{"storeId":"synthetic","locationId":"synthetic","locationName":"Synthetic","deviceRole":"HANDHELD"},
          "terminalUsers":[{"id":"fixture","name":"Synthetic operator","terminalPin":"2468","permissions":{"takePaymentEnabled":true,"startDayEnabled":true}}],
          "tenders":[{"type":"CASH","enabled":true},{"type":"CARD","enabled":true}],
          "goCatalog":{"orderScreen":{"id":"home"},"orderScreens":[{"id":"home","name":"Home","tiles":[]}],
          "departments":[{"id":"test","name":"Test"}],"products":[{"id":"test","name":"Synthetic item","departmentId":"test","priceMinor":1000,"vatRatePercent":20,"active":true,"visible":true,"pricingMode":"FIXED"}]}}""")
        java.io.File(directory,"pairing.json").writeText("""{"merchantCode":"SIMULATED","terminalCode":"LOYALTY-STAGING-SYNTHETIC"}""")
        java.io.File(directory,"catalogue.json").writeText(config.toString())
        val cat=Catalogue.parse(config);val user=cat.staff.single()
        var device=DeviceStore(targetContext,reportTransport={_,_->error("Synthetic interrupted upload")})
        device.startDay(user,0)
        val draft=device.loadDraft();draft.add(cat.products.single(),null,null)
        check(runCatching {device.beginSale(draft,cat,user,"CASH")}.isFailure)
        check(runCatching {device.recordCashPart(draft,user,100,"synthetic-cash")}.isFailure)
        check(runCatching {device.beginRefund(JSONObject(),user,"Synthetic")}.isFailure)
        val sale=device.beginSale(draft,cat,user,"CARD")
        check(sale.getBoolean("loyaltyTest") && !config.has("loyaltyTest"))
        val id=sale.getString("id")
        device.trading.recordFinalCallback(id,JSONObject("""{"status":"APPROVED","providerId":"SYNTHETIC-UPLOAD","amountMinor":900,"discountMinor":100}"""))
        device.trading.recoverRecordedResult()
        check(runCatching {device.uploadSales()}.isFailure && device.trading.unuploaded().size==1)
        device.trading.close()
        targetContext.getSharedPreferences("handheld",0).edit().putLong("reportRetryAt",0).commit()
        val requests=mutableListOf<Pair<String,JSONObject>>()
        device=DeviceStore(targetContext,reportTransport={path,body -> requests.add(path to JSONObject(body.toString()));JSONObject().put("ok",true)})
        check(device.uploadSales()==1 && device.pendingReports==0 && device.trading.unuploaded().isEmpty())
        check(device.uploadSales()==0 && requests.size==2)
        val upload=requests.single {it.first=="/api/terminal/transactions"}.second
        check(upload.getString("merchantId")=="SIMULATED" && upload.getString("terminalId")=="LOYALTY-STAGING-SYNTHETIC")
        check(upload.getLong("amountMinor")==900L && upload.getLong("cardAmountMinor")==900L)
        check(upload.getLong("originalBasketMinor")==1000L && upload.getLong("loyaltyDiscountMinor")==100L)
        check(upload.objects("saleItems").sumOf {it.getLong("grossMinor")}==900L)
        check(upload.objects("tenders").sumOf {it.getLong("amountMinor")}==900L)
        check(upload.objects("saleItems").sumOf {it.getLong("netMinor")+it.getLong("vatMinor")}==900L)
        check(!upload.has("processorResult") && !upload.has("receipt") && !upload.has("cardholderReceipt"))
        device.trading.close()
        targetContext.deleteDatabase("handheld-trading.db")
        java.io.File(directory,"pairing.json").delete();java.io.File(directory,"catalogue.json").delete()
    }
}
