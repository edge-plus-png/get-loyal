package uk.edge.move.handheld

import android.app.Activity
import android.content.*
import org.json.JSONObject

/** Explicit, signature-protected staging companion context; never modifies a payment outcome. */
internal object LoyaltyBridge {
    const val PACKAGE="com.eft.positiveloyaltydemo"
    const val CONSENT="Customer agreed to recognise this payment card for this merchant's loyalty programme."
    fun available(context:Context)=runCatching {context.packageManager.getApplicationInfo(PACKAGE,0).enabled}.getOrDefault(false)
    fun installed(context:Context)=available(context) && runCatching {context.packageManager.getActivityInfo(ComponentName(PACKAGE,"uk.edge.move.loyalty.PaymentActivity"),android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).enabled}.getOrDefault(false)
    fun prepare(activity:Activity,attempt:JSONObject,operator:String,optIn:Boolean,ready:(Boolean)->Unit) {
        val i=Intent("uk.edge.move.loyalty.PREPARE").setComponent(ComponentName(PACKAGE,"uk.edge.move.loyalty.ContextReceiver"))
            .putExtra("merchant",attempt.getString("merchantId")).putExtra("terminal",attempt.getString("terminalId"))
            .putExtra("attempt",attempt.getString("id")).putExtra("operator",operator)
            .putExtra("amount",attempt.getLong("amountMinor")).putExtra("expires",System.currentTimeMillis()+300_000)
            .putExtra("optIn",optIn).putExtra("consent",CONSENT)
        activity.sendOrderedBroadcast(i,null,object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){ready(resultCode==Activity.RESULT_OK)}},null,Activity.RESULT_CANCELED,null,null)
    }
    private val inFlight=mutableSetOf<String>()
    private fun outbox(context:Context)=context.getSharedPreferences("loyalty-v1-outbox",Context.MODE_PRIVATE)
    fun pendingCount(context:Context)=outbox(context).all.size
    fun finalResult(context:Context,attempt:String,result:JSONObject) {
        if(!installed(context))return
        val data=JSONObject().put("attempt",attempt).put("status",result.optString("status"))
            .put("paid",if(result.isNull("amountMinor")) -1L else result.optLong("amountMinor",-1))
            .put("discount",if(result.isNull("discountMinor")) -1L else result.optLong("discountMinor",-1))
        check(outbox(context).edit().putString(attempt,data.toString()).commit()) {"Loyalty event could not be retained."}
        retry(context)
    }
    fun retry(context:Context) {
        if(!installed(context))return
        outbox(context).all.forEach { (attempt,raw) ->
            if(!synchronized(inFlight) {inFlight.add(attempt)}) return@forEach
            try {
                val data=JSONObject(raw as String)
                val i=Intent("uk.edge.move.loyalty.FINAL").setComponent(ComponentName(PACKAGE,"uk.edge.move.loyalty.ContextReceiver"))
                    .putExtra("attempt",attempt).putExtra("status",data.getString("status"))
                    .putExtra("paid",data.getLong("paid")).putExtra("discount",data.getLong("discount"))
                context.sendOrderedBroadcast(i,null,object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
                    if(resultCode==Activity.RESULT_OK) outbox(context).edit().remove(attempt).commit()
                    synchronized(inFlight) {inFlight.remove(attempt)}
                }},null,Activity.RESULT_CANCELED,null,null)
            } catch(_:Exception) {synchronized(inFlight) {inFlight.remove(attempt)}}
        }
    }
    fun close(context:Context,attempt:String) {
        if(installed(context)) context.sendBroadcast(Intent("uk.edge.move.loyalty.CLOSE").setComponent(ComponentName(PACKAGE,"uk.edge.move.loyalty.ContextReceiver")).putExtra("attempt",attempt))
    }
    fun manage(activity:Activity) {activity.startActivityForResult(Intent().setComponent(ComponentName(PACKAGE,"uk.edge.move.loyalty.MainActivity")),7301)}
}
