package uk.edge.move.handheld

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun tradingDate(config:JSONObject, now:Long=System.currentTimeMillis()):String {
    val policy=config.optJSONObject("tradingDay")
    val cutoff=if(policy?.optString("mode")=="EXTENDED") policy.optInt("cutoffMinutes").coerceIn(0,1439) else 0
    val calendar=Calendar.getInstance().apply {timeInMillis=now;add(Calendar.MINUTE,-cutoff)}
    return SimpleDateFormat("yyyy-MM-dd",Locale.UK).format(calendar.time)
}
internal fun dayReport(day:JSONObject,sales:List<JSONObject>,counted:Long?=null,now:Long=System.currentTimeMillis()):JSONObject {
    val entries=sales.filter {it.optString("tradingDayId")==day.getString("id")}
    val paid=entries.filter {it.optString("type")=="SALE"}
    val refunds=entries.filter {it.optString("type")=="REFUND"}
    fun tender(rows:List<JSONObject>,type:String)=rows.sumOf {s -> s.objects("tenders").filter {it.optString("type")==type}.sumOf {it.optLong("amountMinor")} }
    val cash=tender(paid,"CASH");val cashRefunds=tender(refunds,"CASH")
    val expected=day.getLong("openingFloatMinor")+cash-cashRefunds
    return JSONObject(day.toString()).put("reportId",day.getString("id")).put("schemaVersion",1)
        .put("totalValueMinor",paid.sumOf {it.optLong("amountMinor")}).put("cashTotalMinor",cash)
        .put("cardRefundTotalMinor",tender(refunds,"CARD")).put("cardTotalMinor",tender(paid,"CARD")).put("cashRefundTotalMinor",cashRefunds)
        .put("refundedTotalMinor",refunds.sumOf {it.optLong("amountMinor")}).put("refundCount",refunds.size)
        .put("transactionCount",paid.size).put("approvedCount",paid.size)
        .put("itemCount",paid.sumOf {it.objects("saleItems").sumOf {line -> line.optInt("quantity")}})
        .put("vatTotalMinor",paid.sumOf {it.objects("saleItems").sumOf {line -> line.optLong("vatMinor")}}-refunds.sumOf {it.objects("saleItems").sumOf {line -> line.optLong("vatMinor")}})
        .put("expectedCashMinor",expected).put("countedCashMinor",counted ?: JSONObject.NULL)
        .put("cashVarianceMinor",counted?.minus(expected) ?: JSONObject.NULL)
        .put("closedAt",SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.UK).apply {timeZone=TimeZone.getTimeZone("UTC")}.format(Date(now)))
}
internal fun dayReceipt(report:JSONObject):String=ReceiptText().apply {
    center(report.optString("businessName").ifBlank {"Move"});center("END OF DAY");if(report.optBoolean("training")) center("TRAINING - NOT REAL TAKINGS") else if(BuildConfig.IS_STAGING) center("STAGING");rule()
    row("Trading date",report.optString("tradingDate"))
    row("Device",report.optString("terminalName").ifBlank {report.optString("terminalId")})
    row("Closed by",report.optString("closedByUserName"))
    if(report.optLong("openedAt")>0) row("Opened",SimpleDateFormat("dd/MM/yy HH:mm",Locale.UK).format(Date(report.getLong("openedAt"))))
    report.optString("closedAt").takeIf {it.isNotBlank()}?.let {closed->
        val date=runCatching {SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.UK).apply {timeZone=TimeZone.getTimeZone("UTC")}.parse(closed)}.getOrNull()
        row("Closed",date?.let {SimpleDateFormat("dd/MM/yy HH:mm",Locale.UK).format(it)} ?: closed)
    }
    fun amount(label:String,key:String) {row(label,if(report.has(key) && !report.isNull(key)) formatMinor(report.getLong(key)) else "Not recorded")}
    section("SALES")
    amount("Gross sales","totalValueMinor");amount("Refunds","refundedTotalMinor")
    row("Net sales",formatMinor(report.optLong("totalValueMinor")-report.optLong("refundedTotalMinor")))
    row("Transactions",report.optInt("transactionCount").toString());row("Refund count",report.optInt("refundCount").toString());row("Items sold",report.optInt("itemCount").toString())
    section("PAYMENTS")
    amount("Cash sales","cashTotalMinor");amount("Cash refunds","cashRefundTotalMinor")
    row("Net cash",formatMinor(report.optLong("cashTotalMinor")-report.optLong("cashRefundTotalMinor")))
    line();amount("Card sales","cardTotalMinor");amount("Card refunds","cardRefundTotalMinor")
    row("Net card",formatMinor(report.optLong("cardTotalMinor")-report.optLong("cardRefundTotalMinor")))
    section("CASH RECONCILIATION")
    amount("Opening float","openingFloatMinor");amount("Cash sales","cashTotalMinor");amount("Cash refunds","cashRefundTotalMinor")
    rule();amount("Expected cash","expectedCashMinor");amount("Counted cash","countedCashMinor");amount("Variance","cashVarianceMinor")
    if(report.objects("cashDenominations").isNotEmpty()) {section("CASH COUNT");report.objects("cashDenominations").forEach {row("${it.optString("label")} x ${it.optInt("quantity")}",formatMinor(it.optLong("totalMinor")))}}
    section("VAT");amount("VAT total","vatTotalMinor")
    report.optString("cashUpNote").takeIf {it.isNotBlank()}?.let {section("NOTE");line(it)}
    rule();center("Move cash-up totals");center("Keep for your records");line()
}.toString()

/** POSitive owns processing after accepting dispatch; no result callback gates the local close. */
internal fun dispatchOperationalClose(dispatch:()->Boolean,close:()->Unit):Boolean {
    if(!dispatch()) return false
    close()
    return true
}

/** Final report return is a printer handoff, not a settlement approval decision. */
internal fun isZReportReturn(action:String?,reportType:String?):Boolean =
    action=="com.eft.TRANSACTION_RESULT" && reportType=="ZReport"
internal fun moveTotalsMayPrint(reportReturned:Boolean,inForeground:Boolean):Boolean = reportReturned && inForeground
