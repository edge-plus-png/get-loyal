package uk.edge.move.handheld

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.eft.libpositive.PosIntegrate
import com.eft.libpositive.wrappers.PositiveError
import com.pax.neptunelite.api.NeptuneLiteUser
import com.pax.dal.entity.EFontTypeAscii
import com.pax.dal.entity.EFontTypeExtCode
import java.util.concurrent.Executors

internal object PaxTrading {
    fun launch(activity:Activity,store:DeviceStore,attempt:org.json.JSONObject) {
        val id=attempt.getString("id")
        val args=hashMapOf(PosIntegrate.CONFIG_TYPE.CT_AMOUNT to attempt.optLong("cardRequestAmountMinor",attempt.getLong("amountMinor")).toString(),PosIntegrate.CONFIG_TYPE.CT_LANGUAGE to "en_GB")
        val result=try {PosIntegrate.executeTransaction(activity,if(attempt.optString("type")=="REFUND") PosIntegrate.TRANSACTION_TYPE.TRANSACTION_TYPE_REFUND else PosIntegrate.TRANSACTION_TYPE.TRANSACTION_TYPE_SALE,args)} catch(e:Exception) {
            // Preserve pinned Move's launch-error release; no approval is inferred.
            store.trading.cancelLaunch(id,"Payment app could not be launched.")
            throw IllegalStateException("Payment app could not be launched. The basket remains unpaid.",e)
        }
        if(result!=PositiveError.NO_ERROR) {
            store.trading.cancelLaunch(id,"Card launch: $result")
            error("Card terminal returned $result. The basket is unpaid.")
        }
    }
    fun endDay(activity:Activity,store:DeviceStore,user:Staff,dayId:String,counted:Long?) {
        require(user.allowed("zReportEnabled")) {"End day permission is required."}
        store.trading.prepareDayEnd(dayId,counted,user.id,user.name)
        val accepted=try {
            dispatchOperationalClose(
                dispatch={PosIntegrate.executeReport(activity,PosIntegrate.TRANSACTION_TYPE.TRANSACTION_TYPE_RECONCILIATION,
                    hashMapOf(PosIntegrate.CONFIG_TYPE.CT_ZREPORT to "true",PosIntegrate.CONFIG_TYPE.CT_DISABLEPRINTING to "false",PosIntegrate.CONFIG_TYPE.CT_LANGUAGE to "en_GB"))==PositiveError.NO_ERROR},
                close={store.trading.completeDayEnd()}
            )
        } catch(e:Exception) {
            store.trading.dayEndLaunchFailed(dayId)
            throw IllegalStateException("End day could not finish locally. Check the saved cash-up before starting another day.",e)
        }
        if(!accepted) {store.trading.dayEndLaunchFailed(dayId);error("POSitive could not start end day. Your day remains open.")}
    }
    fun print(context:Context,sale:org.json.JSONObject) {
        try {
        val printer=NeptuneLiteUser.getInstance().getDalWithProcessSafe(context.applicationContext).printer
        printer.init();printer.setGray(4);printer.leftIndent(0)
        printer.fontSet(EFontTypeAscii.FONT_12_24,EFontTypeExtCode.FONT_24_24)
        val receipt=if(sale.optString("status","APPROVED")=="APPROVED") saleReceipt(sale)
            else sale.optString("cardholderReceipt").takeIf {it.isNotBlank() && it!="null"} ?: error("No processor receipt returned")
        printer.printStr(receipt,null);printer.step(100)
        val result=printer.start()
        check(result==0) {"Printer status $result"}
        } catch(e:Throwable) {throw IllegalStateException("Receipt could not print. The sale is saved. Check paper and try Print receipt again.",e)}
    }
}
class PositiveTransactionReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf("com.eft.TRANSACTION_RESULT","com.eft.TRANSACTION_ACK","com.eft.TRANSACTION_STATUS")) return
        val pending=goAsync()
        worker.execute {
            try {
                val store=DeviceStore(context.applicationContext)
                // POSitive owns Z and its printing. Only its final report return releases
                // the saved Move print; no report outcome changes the local close.
                if(intent.action!="com.eft.TRANSACTION_RESULT") return@execute
                if(intent.getStringExtra("ReceiverResultType") in setOf("Reports","History Reports") ||
                    intent.hasExtra("ReportType") || intent.hasExtra("ReportReceipt")) {
                    val report=PosIntegrate.unpackReport(context,intent)
                    if(isZReportReturn(intent.action,report?.reportType)) {
                        store.trading.reportReturned()
                        Log.i("MoveApp2App","POSitive Z report returned; Move totals may print when foreground")
                    }
                    return@execute
                }
                val attempt=store.trading.pending() ?: return@execute
                val reply=LegacyMoveResultMapper.map(context,intent,attempt.optString("type")=="REFUND") ?: return@execute
                val result=org.json.JSONObject().put("status",reply.result.status.name).put("providerId",reply.result.providerTransactionId).put("reference",reply.reference).put("amountMinor",reply.amount).put("discountMinor",reply.discount).put("message",reply.result.message).put("receipt",reply.result.cardholderReceiptText)
                // Pinned Move binds the final callback to its persisted active request.
                // MerchantReference is optional metadata, not a vendor completion requirement.
                if(store.trading.providerSeen(reply.result.providerTransactionId)) return@execute
                if(!store.trading.recordFinalCallback(attempt.getString("id"),result)) return@execute
                LoyaltyBridge.finalResult(context,attempt.getString("id"),result)
                store.trading.finish(attempt.getString("id"),reply.result.status.name,reply.result.providerTransactionId,reply.result.cardholderReceiptText)
                // POSitive owns foreground/receipt prompts; never pull it away from the operator.
            } catch(e:Exception) {
                Log.e("MoveApp2App","Final callback persistence failed (${e.javaClass.simpleName}); saved attempt retained")
                // Durable attempt/result remains available; no synthetic payment completion.
            } finally {context.sendBroadcast(Intent("uk.edge.move.handheld.TRADING_CHANGED").setPackage(context.packageName),"${context.packageName}.TRADING_INTERNAL");pending.finish()}
        }
    }
    companion object {private val worker=Executors.newSingleThreadExecutor()}
}
