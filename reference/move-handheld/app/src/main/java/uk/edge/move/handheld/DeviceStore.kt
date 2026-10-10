package uk.edge.move.handheld

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import com.edge.poslite.domain.model.*
import org.json.JSONArray

class DeviceStore(private val context: Context, val training:Boolean=false, private val reportTransport: ((String,JSONObject)->JSONObject)? = null) {
    val trading = TradingStore(context, if(training) "handheld-training.db" else "handheld-trading.db")
    private val preferences = context.getSharedPreferences("handheld", Context.MODE_PRIVATE)
    val localId: String = preferences.getString("localId", null) ?: UUID.randomUUID().toString().also {
        check(preferences.edit().putString("localId", it).commit())
    }
    var dark: Boolean
        get() = preferences.getBoolean("dark", false)
        set(value) { preferences.edit().putBoolean("dark", value).apply() }
    var blocked: Boolean
        get() = preferences.getBoolean("blocked", false)
        set(value) { check(preferences.edit().putBoolean("blocked", value).commit()) }
    var pairing: JSONObject? = read("pairing.json")
        private set
    @Volatile var config: JSONObject? = read("catalogue.json")
        private set
    val code get() = pairing?.optString("setupCode").orEmpty()
    val syncedAt get() = config?.optLong("handheldSyncedAt", 0) ?: 0L
    val acknowledged get() = preferences.getInt("acknowledgedVersion", 0)

    fun requireReadyForReassignment(staff:Staff) {
        requireDeviceSettings(staff)
        requireReadyForReassignment()
    }
    fun requireReadyForReassignment(passcode:String) {
        require(passcode=="9999") {"Passcode not recognised."}
        requireReadyForReassignment()
    }
    private fun requireReadyForReassignment() {
        check(!training) {"Exit training before removing pairing."}
        check(read("shared-pending.json")==null) {"A shared save is awaiting confirmation. Open Saved order recovery in device settings."}
        check(parkedSharedDraft==null) {"An old local draft is parked. Open Saved order recovery to review it."}
        trading.requireReadyForReassignment()
    }
    private fun requireDeviceSettings(staff:Staff) {
        val current=config?.let(Catalogue::parse)?.staff?.singleOrNull {it.id==staff.id && it.pin==staff.pin}
        require(current?.allowed("terminalSettingsEnabled")==true) {"Device settings permission required."}
    }
    val parkedSharedDraft get()=read("parked-shared-draft.json")
    fun discardParkedSharedDraft(staff:Staff,reviewed:JSONObject) {
        requireDeviceSettings(staff)
        validateParkedDiscard(reviewed,parkedSharedDraft,read("shared-pending.json")!=null,requireNotNull(pairing))
        AtomicFile(File(context.filesDir,"parked-shared-draft.json")).delete()
        check(parkedSharedDraft==null) {"Could not remove the local draft. Try again."}
    }

    private var loadedDraft:JSONObject?=null
    fun saveDraft(draft: OrderingDraft) {
        val value=draft.encode().put("merchantCode",pairing?.optString("merchantCode")).put("terminalCode",pairing?.optString("terminalCode"))
        trading.saveDraft(value,loadedDraft);loadedDraft=JSONObject(value.toString())
    }
    fun loadDraft(): OrderingDraft {
        val data = trading.draft { (if(training) null else read("draft.json")) ?: OrderingDraft().apply {
            serviceMode = config?.let { Catalogue.parse(it).defaultServiceMode } ?: ServiceMode.RESTAURANT
        }.encode().put("merchantCode",pairing?.optString("merchantCode")).put("terminalCode",pairing?.optString("terminalCode")) }
        require(data.optString("merchantCode") == pairing?.optString("merchantCode").orEmpty() &&
            data.optString("terminalCode") == pairing?.optString("terminalCode").orEmpty()) { "Saved draft assignment mismatch" }
        loadedDraft=JSONObject(data.toString())
        return decodeDraft(data)
    }

    private fun read(name: String): JSONObject? {
        val file = AtomicFile(File(context.filesDir, name))
        return try { JSONObject(file.openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { null }
    }
    private fun save(name: String, data: JSONObject) {
        val file = AtomicFile(File(context.filesDir, name))
        val stream = file.startWrite()
        try { stream.write(data.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun pair(code: String) {
        require(Regex("[A-Z0-9]{6}").matches(code)) { "Enter the six-character code shown in back office." }
        require(pairing == null || blocked) { "This installation is already paired." }
        val request = JSONObject().put("setupCode", code).put("terminalId", localId)
            .put("softwareVersion", "Move Handheld ${BuildConfig.VERSION_NAME}").put("appCapabilities",org.json.JSONArray(listOf("counter.meal-deals.v1","counter.meal-groups.v1","counter.product-screens.v1")))
        pairing?.let { request.put("expectedMerchantCode", it.getString("merchantCode")).put("expectedTerminalCode", it.getString("terminalCode")) }
        val response = request("/api/terminal/pair", request, authenticated = false)
        require(response.getJSONObject("credentials").getString("apiKey").isNotBlank())
        response.put("setupCode", code)
        save("pairing.json", response)
        // Repair may retain a cached menu and draft, but must not revive stale staff PINs
        // or permissions. Pair returns the assignment's current staff access snapshot.
        config?.let { previous ->
            val updated = JSONObject(previous.toString()).put("terminalUsers", response.getJSONArray("terminalUsers"))
            save("catalogue.json", updated)
            config = updated
        }
        pairing = response
        val existingDraft=trading.draft {OrderingDraft().encode()}
        if(existingDraft.optString("merchantCode").isBlank()) {
            check(decodeDraft(existingDraft).lines.isEmpty()) {"Unassigned basket requires review."}
            saveDraft(OrderingDraft())
        }
        blocked = false
    }

    /** Parse first, replace atomically, then acknowledge. Failed downloads retain the previous complete layout. */
    fun sync(): Catalogue {
        val pair = requireNotNull(pairing) { "Pair this Handheld first." }
        val merchant = pair.getString("merchantCode")
        val terminal = pair.getString("terminalCode")
        val response = request("/api/terminal/config?merchantCode=${encode(merchant)}&terminalId=${encode(terminal)}&appCapabilities=counter.meal-deals.v1,counter.meal-groups.v1,counter.product-screens.v1")
        val parsed = Catalogue.parse(response)
        require(parsed.merchantCode == merchant && parsed.merchantId == pair.getJSONObject("merchant").getString("id")) { "The returned catalogue belongs to another assignment." }
        val old = config?.let(Catalogue::parse)
        require(old == null || parsed.version >= old.version) { "An older configuration was returned; the cached layout is retained." }
        response.put("handheldSyncedAt", System.currentTimeMillis())
        save("catalogue.json", response)
        config = response
        blocked = false
        // The atomically saved version is the durable pending acknowledgement.
        // Reporting retries it independently; never download again just to acknowledge.
        return parsed
    }
    // Read-only compatibility record. Never resend a legacy shared-table command.
    val legacySharedSave get(): JSONObject? {
        val saved=read("shared-pending.json") ?: return null
        require(saved.optString("merchantCode")==pairing?.optString("merchantCode") && saved.optString("terminalCode")==pairing?.optString("terminalCode")) { "Legacy save belongs to another assignment" }
        return saved.optJSONObject("command")
    }
    fun parkSharedDraft(draft:OrderingDraft):OrderingDraft {
        if(draft.sharedState==null) return draft
        check(trading.pending()==null && trading.cashParts().isEmpty()) {"Finish the existing tender before archiving this legacy draft."}
        val archive=draft.encode().put("merchantCode",pairing?.optString("merchantCode")).put("terminalCode",pairing?.optString("terminalCode"))
        validateLegacyArchive(archive,parkedSharedDraft)
        save("parked-shared-draft.json",draft.encode().put("merchantCode",pairing?.optString("merchantCode")).put("terminalCode",pairing?.optString("terminalCode")))
        return OrderingDraft().apply {serviceMode=config?.let(Catalogue::parse)?.defaultServiceMode ?: ServiceMode.TAKEAWAY;saveDraft(this)}
    }
    fun beginSale(draft:OrderingDraft,cat:Catalogue,staff:Staff,tender:String,cashReceived:Long=draft.total):JSONObject {
        check(!training || tender=="CASH") {"Card payments are disabled in training mode."}
        check(tender!="CARD" || !(BuildConfig.DEBUG && config?.optBoolean("handheldFixture")==true)) {"Simulated fixture cannot launch a card payment."}
        requireTradingDay()
        val parts=trading.cashParts()
        if(parts.isNotEmpty()) check(canonicalJson(draft.encode())==canonicalJson(decodeDraft(requireNotNull(loadedDraft)).encode())) {"The basket cannot change after cash is recorded."}
        if(parts.isEmpty()) saveDraft(draft) else check(canonicalJson(loadedDraft)==canonicalJson(trading.draft {error("Missing basket")})) {"Basket changed."}
        val payload=standaloneSale(draft,cat,requireNotNull(config),staff,requireNotNull(pairing).getString("terminalCode"),tender,cashReceived,cashParts=parts)
        if(BuildConfig.IS_LOYALTY_TEST && !training) {
            require(tender=="CARD" && parts.isEmpty()) {"This demo supports full card payments only."}
            payload.put("loyaltyTest",true)
        }
        if(training) payload.put("simulated",true).put("training",true)
        trading.begin(payload,trading.draft { error("Missing basket") })
        return payload
    }
    fun recordCashPart(draft:OrderingDraft,staff:Staff,amount:Long,id:String) {
        check(!BuildConfig.IS_LOYALTY_TEST) {"The loyalty test supports full card payments only."}
        requireTradingDay()
        require(staff.allowed("takePaymentEnabled")) {"Payment permission required."}
        // Cash then card is ordinary checkout; optional split billing must not gate it.
        require(config?.objects("tenders")?.any {it.optString("type")=="CASH" && it.optBoolean("enabled")}==true) {"Cash is disabled."}
        require(draft.sharedState==null) {"Shared table checkout is not connected."}
        if(trading.cashParts().isEmpty()) saveDraft(draft)
        trading.addCashPart(id,amount,requireNotNull(loadedDraft),staff.id)
    }
    fun beginRefund(original:JSONObject,staff:Staff,reason:String,quantities:Map<String,Int>?=null):JSONObject {
        check(!BuildConfig.IS_LOYALTY_TEST) {"Refunds are outside this loyalty demo."}
        check(!training) {"Refunds are disabled in training mode."}
        require(original.optString("merchantId")==pairing?.optString("merchantCode") && original.optString("terminalId")==pairing?.optString("terminalCode")) {"Sale belongs to another device."}
        check(!original.optBoolean("simulated")) {"Simulated sales cannot launch refunds."}
        requireTradingDay()
        val refunds=trading.refundsFor(original.getString("id"))
        return standaloneRefund(original,staff,reason,refunds=refunds,quantities=quantities ?: refundableQuantities(original,refunds)).also {trading.beginRefund(it)}
    }
    fun archiveStagingAttempt(id:String,staff:Staff) {
        require(context.packageName=="uk.edge.move.handheld.staging") {"Staging maintenance only."}
        val current=config?.let(Catalogue::parse)?.staff?.singleOrNull {it.id==staff.id} ?: error("Sign in again.")
        require(current.allowed("terminalSettingsEnabled")) {"Device settings permission required."}
        trading.archiveStagingAttempt(id,current.id,"Operator explicitly authorised resetting a stale staging test; preserve basket, no processor outcome asserted")
    }
    private fun requireTradingDay() {
        if(trading.openDay()==null && trading.cashParts().isNotEmpty()) return // Finish a part-paid basket saved by the pre-day-management build.
        val day=trading.openDay() ?: error("Start the day and enter the opening float from Settings → Start / end day.")
        check(day.optString("merchantCode")==pairing?.optString("merchantCode") && day.optString("terminalId")==pairing?.optString("terminalCode")) {"Open day belongs to another pairing."}
        check(day.optString("tradingDate")==tradingDate(requireNotNull(config))) {"End the previous trading day before taking another payment."}
    }
    fun startDay(staff:Staff,float:Long,note:String="",denominations:org.json.JSONArray?=null) {
        require(staff.allowed("startDayEnabled")) {"Start day permission is required."}
        denominations?.let {CashCount.validate(it,float)}
        val pair=requireNotNull(pairing);val policy=config?.optJSONObject("tradingDay")
        trading.startDay(JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("merchantCode",pair.getString("merchantCode"))
            .put("terminalId",pair.getString("terminalCode")).put("tradingDate",tradingDate(requireNotNull(config)))
            .put("tradingDayMode",policy?.optString("mode","CALENDAR_DAY") ?: "CALENDAR_DAY").put("tradingDayCutoffMinutes",policy?.optInt("cutoffMinutes") ?: 0)
            .put("businessName",config?.optJSONObject("receipt")?.optString("businessName").orEmpty()).put("terminalName",pair.optString("terminalName"))
            .put("training",training).put("openingNote",note).put("openingDenominations",denominations ?: org.json.JSONArray()).put("openingFloatMinor",float).put("openedAt",System.currentTimeMillis()).put("openedByUserId",staff.id).put("openedByUserName",staff.name))
    }
    fun closeDay(staff:Staff,id:String,counted:Long,note:String,denominations:org.json.JSONArray?=null):JSONObject {
        require(staff.allowed("zReportEnabled")) {"End day permission is required."}
        return trading.closeDay(id,counted,staff.id,staff.name,note,denominations)
    }
    val dataBytes get() = preferences.getLong("dataBytes",0)
    private fun recordBytes(count:Long) = synchronized(dataLock) { preferences.edit().putLong("dataBytes",preferences.getLong("dataBytes",0)+count).commit();Unit }
    private fun configVersion() = config?.optInt("configVersion",0) ?: 0
    val pendingReports get() = if(training) 0 else trading.pendingUploadCount() + trading.pendingDayCloses().size + if(configVersion()>acknowledged) 1 else 0
    val lastUploadAt get() = preferences.getLong("lastUploadAt",0)
    val retryAt get() = preferences.getLong("reportRetryAt",0)
    val uploadProblem get() = preferences.getString("reportProblem","").orEmpty()
    private val reportLock=Any()
    fun uploadSales():Int = synchronized(reportLock) {
        if(training || pendingReports==0 || System.currentTimeMillis()<retryAt) return@synchronized 0
        try {
            val sent=deliverReports()
            check(preferences.edit().putInt("reportFailures",0).putLong("reportRetryAt",0).putString("reportProblem","").putLong("lastUploadAt",System.currentTimeMillis()).commit())
            sent
        } catch(e:Exception) {
            val failures=(preferences.getInt("reportFailures",0)+1).coerceAtMost(20)
            val delay=ReportRetry.delayMillis(failures,Math.random())
            check(preferences.edit().putInt("reportFailures",failures).putLong("reportRetryAt",System.currentTimeMillis()+delay).putString("reportProblem","Upload pending; will retry automatically").commit())
            throw e
        }
    }
    private fun reportRequest(path:String,body:JSONObject) = reportTransport?.invoke(path,body) ?: request(path,body)
    private fun deliverReports():Int {
        var sent=0
        while(true) {
        val batch=trading.unuploaded();if(batch.isEmpty()) break
        for(sale in batch) {
            require(sale.getString("merchantId")==pairing?.optString("merchantCode") && sale.getString("terminalId")==pairing?.optString("terminalCode")) {"Saved sale belongs to another pairing."}
            val upload=JSONObject(sale.toString()).apply {remove("receipt");remove("processorResult");remove("cardholderReceipt")}
            val response=reportRequest("/api/terminal/transactions",upload)
            check(response.optBoolean("ok")) {"Back office has not acknowledged the sale."}
            trading.markUploaded(sale.getString("id"));sent++
        }
        }
        for(report in trading.pendingDayCloses()) {
            require(report.getString("merchantCode")==pairing?.optString("merchantCode") && report.getString("terminalId")==pairing?.optString("terminalCode")) {"Cash-up belongs to another pairing."}
            val response=reportRequest("/api/terminal/trading-day-close",report)
            check(response.optBoolean("ok") && response.optString("reportId")==report.getString("reportId")) {"Back office has not acknowledged the cash-up."}
            trading.markDayUploaded(report.getString("id"))
        }
        val applied=configVersion()
        if(applied>acknowledged) {
            val pair=requireNotNull(pairing)
            val response=reportRequest("/api/terminal/config/applied",JSONObject().put("merchantCode",pair.getString("merchantCode")).put("terminalId",pair.getString("terminalCode")).put("appliedConfigVersion",applied))
            check(response.optBoolean("ok")) {"Configuration acknowledgement pending"}
            // A newer saved config remains pending if it arrived during this request.
            check(preferences.edit().putInt("acknowledgedVersion",maxOf(acknowledged,applied)).commit())
        }
        return sent
    }
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun request(path: String, body: JSONObject? = null, authenticated: Boolean = true, revokeOnForbidden: Boolean = true): JSONObject {
        LoyaltyStagingPolicy.requireEnvironment(BuildConfig.APPLICATION_ID, BuildConfig.OPS_URL, BuildConfig.IS_STAGING)
        check(!training) {"Back-office access is disabled in training mode."}
        check(!(BuildConfig.DEBUG && config?.optBoolean("handheldFixture") == true)) { "Simulated dataset: network access is disabled for this fixture." }
        val connection = URL(BuildConfig.OPS_URL + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 30000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        if (authenticated) connection.setRequestProperty("x-edge-terminal-key", requireNotNull(pairing).getJSONObject("credentials").getString("apiKey"))
        try {
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes=body.toString().toByteArray(Charsets.UTF_8)
                recordBytes(bytes.size.toLong())
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (authenticated && (status == 401 || (status == 403 && revokeOnForbidden))) blocked = true
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: byteArrayOf()
            recordBytes(bytes.size.toLong())
            val text = bytes.toString(Charsets.UTF_8)
            if (status !in 200..299) throw IllegalStateException(
                if (status == 401 || (status == 403 && revokeOnForbidden)) "Device access has been revoked. Ask back office for a new code for this same assignment."
                else runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Back office returned HTTP $status. Please retry manually.")
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
    companion object {private val dataLock=Any()}
}
