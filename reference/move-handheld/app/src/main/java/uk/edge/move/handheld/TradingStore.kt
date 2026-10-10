package uk.edge.move.handheld

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import org.json.JSONObject

/** SQLite transaction commits the paid sale and clears its basket together. No scheduled worker. */
class TradingStore(context: Context, databaseName:String="handheld-trading.db") : SQLiteOpenHelper(context.applicationContext,databaseName,null,3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE callbacks (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE attempts (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sales (id TEXT PRIMARY KEY, payload TEXT NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)")
        createDays(db)
    }
    private fun createDays(db:SQLiteDatabase) {db.execSQL("CREATE TABLE days (id TEXT PRIMARY KEY, payload TEXT NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0)")}
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        if(oldVersion<2) createDays(db)
        if(oldVersion<3) {
            // Retire the obsolete local Z supervision gate, without asserting a processor outcome.
            // Keep the original request for audit; the open day, sales and count remain intact.
            read(db,"dayEnd")?.let {put(db,"legacyDayEndRequest",it)}
            db.delete("state","id=?",arrayOf("dayEnd"))
            db.delete("state","id=?",arrayOf("dayEndError"))
        }
    }
    private fun read(db:SQLiteDatabase,id:String):JSONObject? = db.rawQuery("SELECT value FROM state WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
    private fun put(db:SQLiteDatabase,id:String,value:JSONObject) { db.delete("state","id=?",arrayOf(id));db.insertOrThrow("state",null,ContentValues().apply { put("id",id);put("value",value.toString()) }) }
    private fun <T> atomic(work:(SQLiteDatabase)->T):T = synchronized(lock) { val db=writableDatabase;db.beginTransaction();try { val result=work(db);db.setTransactionSuccessful();result } finally {db.endTransaction()} }
    fun draft(fallback:()->JSONObject):JSONObject = atomic { db -> read(db,"draft") ?: fallback().also {put(db,"draft",it)} }
    fun saveDraft(value:JSONObject,expected:JSONObject?=null) = atomic { db ->
        check(read(db,"dayEnd")==null) {"Finish end day in POSitive first."}
        check(read(db,"attempt")==null) { "Finish the card payment before changing this basket." }
        check(read(db,"cashParts")==null) {"Cash has been recorded. Finish the balance or return the cash before editing."}
        check(expected==null || canonicalJson(read(db,"draft"))==canonicalJson(expected)) {"The saved basket changed. Reload it before editing."}
        put(db,"draft",value)
    }
    fun cashParts():List<JSONObject> = synchronized(lock) {read(readableDatabase,"cashParts")?.objects("items").orEmpty()}
    fun addCashPart(id:String,amount:Long,expectedDraft:JSONObject,staffId:String) = atomic { db ->
        check(read(db,"dayEnd")==null) {"Finish end day in POSitive first."}
        check(read(db,"attempt")==null) {"Finish the card payment first."}
        check(canonicalJson(read(db,"draft"))==canonicalJson(expectedDraft)) {"Basket changed. Open checkout again."}
        val parts=read(db,"cashParts")?.objects("items").orEmpty()
        if(parts.any {it.optString("id")==id}) return@atomic
        val remaining=decodeDraft(expectedDraft).total-parts.sumOf {it.getLong("amountMinor")}
        require(amount in 1 until remaining) {"Cash part must be less than the remaining balance."}
        put(db,"cashParts",JSONObject().put("items",org.json.JSONArray(parts+JSONObject().put("id",id).put("type","CASH").put("label","Cash").put("amountMinor",amount).put("receivedMinor",amount).put("staffId",staffId).put("createdAt",System.currentTimeMillis()))))
    }
    fun returnCash(staffId:String) = atomic {db ->
        check(read(db,"attempt")==null) {"Finish the card payment first."}
        val cash=read(db,"cashParts") ?: return@atomic
        val id=java.util.UUID.randomUUID().toString()
        db.insertOrThrow("attempts",null,ContentValues().apply {put("id",id);put("payload",cash.put("type","CASH_RETURN").put("staffId",staffId).put("createdAt",System.currentTimeMillis()).toString())})
        db.delete("state","id=?",arrayOf("cashParts"))
    }
    fun localTable(id:String):JSONObject? = synchronized(lock) {read(readableDatabase,"table:$id")}
    fun saveLocalTable(expected:JSONObject) = atomic {db ->
        check(read(db,"attempt")==null && read(db,"cashParts")==null) {"Finish the tender before saving this table."}
        check(canonicalJson(read(db,"draft"))==canonicalJson(expected)) {"Basket changed."}
        val table=decodeDraft(expected);require(table.sharedState==null && !table.tableId.isNullOrBlank())
        put(db,"table:${table.tableId}",expected)
        put(db,"draft",OrderingDraft().apply {serviceMode=table.serviceMode}.encode().put("merchantCode",expected.optString("merchantCode")).put("terminalCode",expected.optString("terminalCode")))
    }
    fun openLocalTable(id:String,label:String) = atomic {db ->
        check(read(db,"attempt")==null && read(db,"cashParts")==null) {"Finish the tender first."}
        val old=read(db,"draft") ?: error("Missing basket")
        val active=decodeDraft(old)
        val stored=read(db,"table:$id")
        check(active.tableId==null && (active.lines.isEmpty() || stored==null)) {"Save or finish the current basket first."}
        val saved=stored ?: active.apply {changeContext(com.edge.poslite.domain.model.ServiceMode.RESTAURANT,id,label)}.encode().put("merchantCode",old.optString("merchantCode")).put("terminalCode",old.optString("terminalCode"))
        check(saved.optString("merchantCode")==old.optString("merchantCode") && saved.optString("terminalCode")==old.optString("terminalCode")) {"Table belongs to another assignment."}
        put(db,"draft",saved);db.delete("state","id=?",arrayOf("table:$id"))
    }
    fun pending():JSONObject? = synchronized(lock) {read(readableDatabase,"attempt")}
    fun begin(payload:JSONObject,expectedDraft:JSONObject) = atomic { db ->
        check(read(db,"dayEnd")==null) {"Finish end day in POSitive first."}
        check(read(db,"attempt")==null) {"A card payment is already in progress."}
        check(canonicalJson(read(db,"draft"))==canonicalJson(expectedDraft)) {"The basket changed; open checkout again."}
        val cash=read(db,"cashParts")?.objects("items").orEmpty()
        check(cash.isEmpty() || canonicalJson(org.json.JSONArray(payload.objects("tenders").take(cash.size)))==canonicalJson(org.json.JSONArray(cash))) {"Cash entries changed. Open checkout again."}
        db.delete("state","id=?",arrayOf("lastResult"));db.delete("state","id=?",arrayOf("lastOutcome"));put(db,"attempt",payload.apply {read(db,"openDay")?.let {day -> put("tradingDayId",day.getString("id"));put("tradingDate",day.getString("tradingDate"))}})
    }
    fun recordCallback(result:JSONObject) = atomic { db ->
        val bytes=java.security.MessageDigest.getInstance("SHA-256").digest(canonicalJson(result).toByteArray())
        val id=bytes.joinToString("") {"%02x".format(it)}
        db.insertWithOnConflict("callbacks",null,ContentValues().apply {put("id",id);put("payload",result.toString())},SQLiteDatabase.CONFLICT_IGNORE)
    }
    /** Final callback journal and active-request association must survive or roll back together. */
    fun recordFinalCallback(attemptId:String,result:JSONObject):Boolean = atomic { db ->
        val attempt=read(db,"attempt")?.takeIf {it.optString("id")==attemptId} ?: return@atomic false
        val bytes=java.security.MessageDigest.getInstance("SHA-256").digest(canonicalJson(result).toByteArray())
        val callbackId=bytes.joinToString("") {"%02x".format(it)}
        val recorded=JSONObject(result.toString()).put("attemptId",attemptId)
        db.insertWithOnConflict("callbacks",null,ContentValues().apply {put("id",callbackId);put("payload",recorded.toString())},SQLiteDatabase.CONFLICT_IGNORE)
        put(db,"attempt",attempt.put("processorResult",result))
        true
    }
    fun recordResult(id:String,result:JSONObject) = atomic { db ->
        read(db,"attempt")?.takeIf {it.optString("id")==id}?.let {put(db,"attempt",it.put("processorResult",result))}
    }
    /** Explicit staging operator action, not a processor outcome or sale/refund. */
    fun archiveStagingAttempt(id:String,staffId:String,reason:String) = atomic { db ->
        require(BuildConfig.APPLICATION_ID.startsWith("uk.edge.move.handheld.staging") && BuildConfig.OPS_URL=="https://move-staging.getedgeportal.app")
        require(staffId.isNotBlank() && reason.isNotBlank())
        val attempt=read(db,"attempt") ?: error("No test attempt to archive.")
        check(attempt.getString("id")==id) {"The pending attempt changed."}
        check(!attempt.has("processorResult")) {"A processor result is already saved; finish that result instead."}
        val archived=JSONObject(attempt.toString()).put("operatorAction","ARCHIVE_STAGING_ATTEMPT")
            .put("operatorStaffId",staffId).put("operatorReason",reason).put("operatorActionAt",System.currentTimeMillis())
        db.insertOrThrow("attempts",null,ContentValues().apply {put("id",id);put("payload",archived.toString())})
        db.delete("state","id=?",arrayOf("attempt"))
        put(db,"lastResult",JSONObject().put("message","Staging attempt archived by operator. No payment outcome recorded."))
    }
    fun cancelLaunch(id:String,message:String) = atomic { db ->
        if(read(db,"attempt")?.optString("id")==id) { put(db,"lastResult",JSONObject().put("message",message));db.delete("state","id=?",arrayOf("attempt")) }
    }
    fun finish(id:String,status:String,providerId:String,receipt:String?=null):JSONObject? = atomic { db ->
        var pending=read(db,"attempt") ?: return@atomic null
        if(pending.getString("id")!=id) return@atomic null
        if(status=="APPROVED" && pending.optBoolean("loyaltyTest")) {
            try { pending=LoyaltyReconciliation.reconcile(pending) } catch(e:IllegalArgumentException) {
                put(db,"attempt",pending.put("loyaltyReconciliationError", "Approved at PAX; amounts unresolved. ${e.message} Do not start another payment."))
                return@atomic null
            }
        }
        db.insertWithOnConflict("attempts",null,ContentValues().apply {put("id",id);put("payload",pending.toString())},SQLiteDatabase.CONFLICT_REPLACE)
        put(db,"lastOutcome",JSONObject(pending.toString()).put("status",status).put("transactionId",providerId).apply {receipt?.let {put("cardholderReceipt",it)}})
        if(status!="APPROVED") { put(db,"lastResult",JSONObject().put("message",status));db.delete("state","id=?",arrayOf("attempt"));return@atomic null }
        val sale=JSONObject(pending.toString()).put("status","APPROVED").put("transactionId",providerId)
        receipt?.let {sale.put("cardholderReceipt",it)}
        db.insertOrThrow("sales",null,ContentValues().apply { put("id",id);put("payload",sale.toString());put("created",sale.getLong("createdAt"));put("uploaded",0) })
        val old=read(db,"draft") ?: error("Missing basket")
        val empty=OrderingDraft().apply { serviceMode=decodeDraft(old).serviceMode }.encode()
            .put("merchantCode",old.optString("merchantCode")).put("terminalCode",old.optString("terminalCode"))
        if(pending.optString("type")!="REFUND") {put(db,"draft",empty);db.delete("state","id=?",arrayOf("cashParts"))};put(db,"lastSale",JSONObject().put("id",id));db.delete("state","id=?",arrayOf("attempt"));sale
    }
    fun history(date:String?=null):List<JSONObject> = synchronized(lock) {
        val entries=readableDatabase.rawQuery("SELECT payload FROM sales ORDER BY created DESC",null).use {c -> buildList {
            while(c.moveToNext()) add(JSONObject(c.getString(0)))
        }}
        orderHistory(entries,date)
    }
    fun refundsFor(originalId:String):List<JSONObject> = synchronized(lock) {
        readableDatabase.rawQuery("SELECT payload FROM sales",null).use {c -> buildList {
            while(c.moveToNext()) {val entry=JSONObject(c.getString(0));if(entry.optString("type")=="REFUND" && entry.optString("originalTransactionId")==originalId && entry.optString("status")=="APPROVED") add(entry)}
        }}
    }
    fun beginRefund(payload:JSONObject) = atomic { db ->
        check(read(db,"dayEnd")==null) {"Finish end day in POSitive first."}
        check(read(db,"attempt")==null) {"Finish the pending payment first."}
        val original=payload.getString("originalTransactionId")
        check(sale(original)!=null) {"Original sale is missing."}
        val source=requireNotNull(sale(original))
        check(source.optString("type")=="SALE" && source.optString("status")=="APPROVED") {"Choose a completed sale."}
        val previous=refundsFor(original)
        val requested=payload.objects("saleItems").associate {it.getString("id") to it.getInt("quantity")}
        check(requested.size==payload.objects("saleItems").size) {"Duplicate refund lines."}
        val expected=selectedRefundLines(source,previous,requested)
        check(expected.sumOf {it.getLong("grossMinor")}==payload.getLong("amountMinor")) {"Refund amount no longer matches the remaining items."}
        val allocation=refundTenderAllocation(source,previous,payload.getLong("amountMinor"))
        check(canonicalJson(org.json.JSONArray(allocation))==canonicalJson(payload.getJSONArray("tenders"))) {"Refund tenders no longer match. Select the items again."}
        val card=allocation.filter {it.getString("type")=="CARD"}.sumOf {it.getLong("amountMinor")}
        check(payload.optLong("cardRequestAmountMinor")==card) {"Card refund amount does not match."}
        expected.forEach {line ->
            val supplied=payload.objects("saleItems").single {it.getString("id")==line.getString("id")}
            check(listOf("grossMinor","netMinor","vatMinor","totalMinor").all {supplied.optLong(it)==line.optLong(it)}) {"Refund values no longer match. Select the items again."}
        }
        check(payload.objects("tenders").sumOf {it.getLong("amountMinor")}==payload.getLong("amountMinor")) {"Refund tender does not match."}
        db.delete("state","id=?",arrayOf("lastResult"));db.delete("state","id=?",arrayOf("lastOutcome"));put(db,"attempt",payload.apply {read(db,"openDay")?.let {day -> put("tradingDayId",day.getString("id"));put("tradingDate",day.getString("tradingDate"))}})
    }
    fun lastOutcome():JSONObject?=synchronized(lock) {read(readableDatabase,"lastOutcome")}
    fun dismissOutcome(id:String)=atomic {db ->
        if(read(db,"lastOutcome")?.optString("id")==id) db.delete("state","id=?",arrayOf("lastOutcome"))
    }
    fun lastResult():String?=synchronized(lock) {read(readableDatabase,"lastResult")?.optString("message")}
    fun recoverRecordedResult() {
        val p=pending() ?: return
        val id=p.getString("id")
        if(p.objects("tenders").isNotEmpty() && p.objects("tenders").all {it.optString("type")=="CASH"}) { finish(id,"APPROVED","CASH-$id");return }
        val result=p.optJSONObject("processorResult") ?: return
        finish(id,result.getString("status"),result.getString("providerId"),result.optString("receipt").takeIf {it.isNotBlank() && it!="null"})
    }
    fun sale(id:String):JSONObject? = synchronized(lock) { readableDatabase.rawQuery("SELECT payload FROM sales WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null } }
    fun lastSale():JSONObject? = synchronized(lock) {read(readableDatabase,"lastSale")?.optString("id")?.let(::sale)}
    fun unuploaded():List<JSONObject> = synchronized(lock) { readableDatabase.rawQuery("SELECT payload FROM sales WHERE uploaded=0 ORDER BY created LIMIT 100",null).use { c -> buildList {while(c.moveToNext()) add(JSONObject(c.getString(0)))} } }
    fun pendingUploadCount():Int = synchronized(lock) {readableDatabase.rawQuery("SELECT COUNT(*) FROM sales WHERE uploaded=0",null).use {it.moveToFirst();it.getInt(0)}}
    fun markUploaded(id:String) { synchronized(lock) {writableDatabase.update("sales",ContentValues().apply {put("uploaded",1)},"id=?",arrayOf(id))} }
    fun uploaded(id:String):Boolean = synchronized(lock) {readableDatabase.rawQuery("SELECT uploaded FROM sales WHERE id=?",arrayOf(id)).use {it.moveToFirst() && it.getInt(0)==1}}
    fun providerSeen(id:String):Boolean = synchronized(lock) {readableDatabase.rawQuery("SELECT payload FROM sales WHERE payload LIKE ?",arrayOf("%$id%")).use { c -> var found=false;while(c.moveToNext()) if(JSONObject(c.getString(0)).optString("transactionId")==id) found=true;found}}
    fun openDay():JSONObject?=synchronized(lock) {read(readableDatabase,"openDay")}
    /** A reassignment must never discard outstanding money, orders or reports. */
    fun requireReadyForReassignment()=synchronized(lock) {
        val db=readableDatabase
        check(read(db,"attempt")==null && read(db,"cashParts")==null && read(db,"dayEnd")==null) {"Finish the current payment or end-day request first."}
        check(read(db,"openDay")==null) {"End the trading day before removing pairing."}
        check(read(db,"draft")?.let {decodeDraft(it).let {d->d.lines.isEmpty() && d.sharedState==null}}!=false) {"Finish or cancel the basket first."}
        check(db.rawQuery("SELECT id FROM state WHERE id LIKE 'table:%' LIMIT 1",null).use {!it.moveToFirst()}) {"Finish saved tables first."}
        check(pendingUploadCount()==0 && pendingDayCloses().isEmpty()) {"Upload all sales and cash-up reports first."}
    }
    fun startDay(day:JSONObject)=atomic {db ->
        check(read(db,"dayEnd")==null) {"Finish end day in POSitive first."}
        check(read(db,"openDay")==null) {"A trading day is already open."}
        check(read(db,"attempt")==null && read(db,"cashParts")==null) {"Finish the current payment first."}
        require(day.getLong("openingFloatMinor") in 0..100_000_000)
        put(db,"openDay",day)
    }
    fun currentDayReport():JSONObject?=synchronized(lock) {openDay()?.let {day -> dayReport(day,daySales(readableDatabase))}}
    private fun daySales(db:SQLiteDatabase):List<JSONObject> = db.rawQuery("SELECT payload FROM sales",null).use {c->buildList {while(c.moveToNext()) add(JSONObject(c.getString(0)))}}
    fun closeDay(id:String,counted:Long?,staffId:String,staffName:String,note:String,denominations:org.json.JSONArray?=null):JSONObject=atomic {db ->
        val day=read(db,"openDay") ?: error("No day is open.")
        check(day.getString("id")==id) {"The trading day changed."}
        require(counted==null || counted in 0..100_000_000)
        denominations?.let {CashCount.validate(it,requireNotNull(counted))}
        check(read(db,"attempt")==null && read(db,"cashParts")==null) {"Finish the current payment first."}
        check(read(db,"draft")?.let {decodeDraft(it).lines.isEmpty()}!=false) {"Finish or cancel the basket before cashing up."}
        check(db.rawQuery("SELECT id FROM state WHERE id LIKE 'table:%' LIMIT 1",null).use {!it.moveToFirst()}) {"Finish the saved tables before cashing up."}
        val report=dayReport(day,daySales(db),counted).put("closedByUserId",staffId).put("closedByUserName",staffName).put("cashUpNote",note).put("cashDenominations",denominations ?: org.json.JSONArray())
        db.insertOrThrow("days",null,ContentValues().apply {put("id",id);put("payload",report.toString());put("uploaded",0)})
        db.delete("state","id=?",arrayOf("openDay"));put(db,"lastDayClose",report)
        report
    }
    fun pendingDayEnd():JSONObject?=synchronized(lock) {read(readableDatabase,"dayEnd")}
    fun prepareDayEnd(id:String,counted:Long?,staffId:String,staffName:String):JSONObject=atomic {db ->
        check(read(db,"dayEnd")==null) {"End day has already been sent to POSitive."}
        val day=read(db,"openDay") ?: error("No day is open.")
        check(day.getString("id")==id) {"The trading day changed."}
        require(counted==null || counted in 0..100_000_000)
        check(read(db,"attempt")==null && read(db,"cashParts")==null) {"Finish the current payment first."}
        check(read(db,"draft")?.let {decodeDraft(it).lines.isEmpty()}!=false) {"Finish or cancel the basket before cashing up."}
        check(db.rawQuery("SELECT id FROM state WHERE id LIKE 'table:%' LIMIT 1",null).use {!it.moveToFirst()}) {"Finish the saved tables before cashing up."}
        JSONObject().put("id",id).put("counted",counted ?: JSONObject.NULL).put("staffId",staffId).put("staffName",staffName)
            .put("requestedAt",System.currentTimeMillis()).also {put(db,"dayEnd",it)}
    }
    fun dayEndLaunchFailed(id:String)=atomic {db ->
        if(read(db,"dayEnd")?.optString("id")==id) db.delete("state","id=?",arrayOf("dayEnd"))
    }
    fun acknowledgeDayEnd(uti:String)=atomic {db ->
        read(db,"dayEnd")?.let {request->if(uti.isNotBlank() && !request.has("uti")) put(db,"dayEnd",request.put("uti",uti))};Unit
    }
    fun failDayEnd(uti:String?,message:String):Boolean=atomic {db ->
        val request=read(db,"dayEnd") ?: return@atomic false
        if(uti.isNullOrBlank() || uti!=request.optString("uti")) return@atomic false
        db.delete("state","id=?",arrayOf("dayEnd"))
        put(db,"dayEndError",JSONObject().put("message",message));true
    }
    fun consumeDayEndError():String?=atomic {db ->read(db,"dayEndError")?.let {db.delete("state","id=?",arrayOf("dayEndError"));it.optString("message")}}
    /** Close the Move operational day after POSitive accepts dispatch. This is not processor settlement. */
    fun completeDayEnd():JSONObject?=atomic {db ->
        val request=read(db,"dayEnd") ?: return@atomic null
        val report=closeDay(request.getString("id"),if(request.isNull("counted")) null else request.getLong("counted"),request.getString("staffId"),request.getString("staffName"),"")
        db.delete("state","id=?",arrayOf("dayEnd"))
        db.delete("state","id=?",arrayOf("lastOutcome"))
        put(db,"dayEndResult",JSONObject().put("id",report.getString("id")).put("report",report)
            .put("reportReturned",request.optBoolean("reportReturned")))
        report
    }
    fun dayEndPrintResult(message:String)=atomic {db -> read(db,"dayEndResult")?.let {put(db,"dayEndResult",it.put("message",message))};Unit}
    // Receipt sequencing only: never changes the closed day or sends another Z.
    fun reportReturned()=atomic {db ->
        val key=if(read(db,"dayEnd")!=null) "dayEnd" else "dayEndResult"
        read(db,key)?.let {put(db,key,it.put("reportReturned",true))};Unit
    }
    fun consumeDayEndResult(inForeground:Boolean):JSONObject?=atomic {db ->
        read(db,"dayEndResult")?.takeIf {moveTotalsMayPrint(it.optBoolean("reportReturned"),inForeground)}
            ?.also {db.delete("state","id=?",arrayOf("dayEndResult"))}
    }
    fun lastDayClose():JSONObject?=synchronized(lock) {read(readableDatabase,"lastDayClose")}
    fun pendingDayCloses():List<JSONObject> =synchronized(lock) {readableDatabase.rawQuery("SELECT payload FROM days WHERE uploaded=0",null).use {c->buildList {while(c.moveToNext()) add(JSONObject(c.getString(0)))}}}
    fun markDayUploaded(id:String)=synchronized(lock) {writableDatabase.update("days",ContentValues().apply {put("uploaded",1)},"id=?",arrayOf(id))}
    companion object {private val lock=Any()}
}
