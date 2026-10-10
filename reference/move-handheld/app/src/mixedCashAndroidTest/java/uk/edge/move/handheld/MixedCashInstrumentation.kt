package uk.edge.move.handheld

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

/** Isolated synthetic test source set. No real pairing, payment launch or network. */
class MixedCashInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        try {
            check(targetContext.packageName=="uk.edge.move.handheld.staging.benchmark")
            check(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            runMixedCashTests()
            finish(Activity.RESULT_OK,Bundle().apply {putString("stream","Cash then card: split flag absent/false/true, £20 minus £5 = £15, restart, duplicate cash, cancel/decline/launch failure, approval allocation passed. Synthetic package only; no PAX launch.\n")})
        } catch(e:Exception) {finish(Activity.RESULT_CANCELED,Bundle().apply {putString("stream",e.toString())})}
    }
    private fun runMixedCashTests() {
        // The package/debuggable guard above excludes every real merchant install.
        val directory=targetContext.filesDir
        check(!java.io.File(directory,"pairing.json").exists()) {"Use an empty synthetic benchmark install."}
        for (split in listOf<Boolean?>(null,false,true)) {
            targetContext.deleteDatabase("handheld-trading.db")
            val config=JSONObject("""{
              "configVersion":1,"terminalName":"Synthetic","tillLayout":{"name":"Synthetic"},"merchant":{"id":"SIMULATED","merchantCode":"SIMULATED","displayName":"Synthetic"},
              "assignment":{"storeId":"synthetic","locationId":"synthetic","locationName":"Synthetic","deviceRole":"HANDHELD"},
              "terminalUsers":[{"id":"fixture","name":"Synthetic operator","terminalPin":"2468","permissions":{"takePaymentEnabled":true,"startDayEnabled":true}}],
              "tenders":[{"type":"CASH","enabled":true},{"type":"CARD","enabled":true}],
              "goCatalog":{"orderScreen":{"id":"home"},"orderScreens":[{"id":"home","name":"Home","tiles":[]}],
                "departments":[{"id":"test","name":"Test"}],"products":[{"id":"test","name":"Synthetic item","departmentId":"test","priceMinor":2000,"active":true,"visible":true,"pricingMode":"FIXED"}]}
            }""")
            if(split!=null) config.put("features",JSONObject().put("splitPaymentsEnabled",split))
            java.io.File(directory,"pairing.json").writeText("""{"merchantCode":"SIMULATED","terminalCode":"TEST"}""")
            java.io.File(directory,"catalogue.json").writeText(config.toString())
            val cat=Catalogue.parse(config);val user=cat.staff.single()
            var store=DeviceStore(targetContext,reportTransport={_,_->error("No network allowed in ledger test")})
            store.startDay(user,0)
            var draft=store.loadDraft();draft.add(cat.products.single(),null,null)
            store.recordCashPart(draft,user,500,"cash-five")
            store.recordCashPart(draft,user,500,"cash-five")
            check(store.trading.cashParts().sumOf {it.getLong("amountMinor")}==500L)
            store.trading.close()
            store=DeviceStore(targetContext,reportTransport={_,_->error("No network allowed in ledger test")})
            draft=store.loadDraft()
            check(draft.total==2000L && store.trading.cashParts().sumOf {it.getLong("amountMinor")}==500L)
            for(outcome in listOf("CANCELLED","DECLINED","LAUNCH_FAILED")) {
                val attempt=store.beginSale(draft,cat,user,"CARD")
                check(attempt.getLong("cardRequestAmountMinor")==1500L)
                if(outcome=="LAUNCH_FAILED") store.trading.cancelLaunch(attempt.getString("id"),"Synthetic launch failure")
                else store.trading.finish(attempt.getString("id"),outcome,"synthetic-result")
                check(store.trading.sale(attempt.getString("id"))==null)
                check(store.trading.pending()==null && store.trading.cashParts().sumOf {it.getLong("amountMinor")}==500L)
                store.trading.close();store=DeviceStore(targetContext,reportTransport={_,_->error("No network allowed")})
                draft=store.loadDraft()
            }
            val approved=store.beginSale(draft,cat,user,"CARD")
            check(approved.getLong("cardRequestAmountMinor")==1500L)
            store.trading.finish(approved.getString("id"),"APPROVED","synthetic-only")
            val saved=requireNotNull(store.trading.sale(approved.getString("id")))
            check(saved.getLong("amountMinor")==2000L && saved.getLong("cashAmountMinor")==500L && saved.getLong("cardAmountMinor")==1500L)
            check(saved.objects("tenders").sumOf {it.getLong("amountMinor")}==2000L)
            check(store.trading.cashParts().isEmpty() && store.loadDraft().lines.isEmpty())
            store.trading.close()
            targetContext.deleteDatabase("handheld-trading.db")
            java.io.File(directory,"pairing.json").delete();java.io.File(directory,"catalogue.json").delete()
        }
    }
}
