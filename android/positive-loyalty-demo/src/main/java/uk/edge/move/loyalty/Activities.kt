package uk.edge.move.loyalty

import android.app.Activity
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.math.BigDecimal

class ContextReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        try {
            val ledger=Ledger(context)
            if(intent.action=="uk.edge.move.loyalty.FINAL") {if(!ledger.finalResult(intent.getStringExtra("attempt").orEmpty(),intent.getStringExtra("status").orEmpty(),intent.getLongExtra("paid",-1),intent.getLongExtra("discount",-1))) {resultCode=Activity.RESULT_CANCELED;return}}
            else if(intent.action=="uk.edge.move.loyalty.CLOSE") ledger.closeAttempt(intent.getStringExtra("attempt").orEmpty())
            else if(intent.action=="uk.edge.move.loyalty.PREPARE") ledger.prepare(JSONObject()
                .put("merchant",intent.getStringExtra("merchant")).put("terminal",intent.getStringExtra("terminal"))
                .put("attempt",intent.getStringExtra("attempt")).put("operator",intent.getStringExtra("operator"))
                .put("amount",intent.getLongExtra("amount",0)).put("expires",intent.getLongExtra("expires",0))
                .put("optIn",intent.getBooleanExtra("optIn",false)).put("consent",intent.getStringExtra("consent")))
            else return
            resultCode=Activity.RESULT_OK
        } catch(_:Exception) {resultCode=Activity.RESULT_CANCELED}
    }
}
class NominationReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action=="com.nominateloyalty.confirm" && Ledger(context).active())
            context.sendBroadcast(Intent("com.payment.confirm").setPackage("com.eft.positive").putExtra("status",6).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
    }
}
class PaymentActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // Only POSitive's startActivityForResult path may mutate membership/loyalty.
        if(callingPackage!="com.eft.positive") {finish();return}
        val amount=try {BigDecimal(intent.getStringExtra("amount") ?: "0").movePointRight(2).longValueExact()} catch(_:Exception){0}
        val result=try {Ledger(this).callback(intent.getStringExtra("card_token").orEmpty(),amount,intent.getStringExtra("launch_from").orEmpty())} catch(_:Exception){Ledger.Response()}
        val out=Intent().putExtra("status",result.status)
        if(result.status==5 && result.discount>0) out.putExtra("discount_amount",Ledger.money(result.discount)).putExtra("is_reward",true)
        setResult(RESULT_OK,out)
        if(result.message.isBlank()) {finish();return}
        val label=TextView(this).apply {text=result.message;textSize=28f;gravity=17;setPadding(30,30,30,30)}
        setContentView(label)
        Handler(Looper.getMainLooper()).postDelayed({finish()},2500)
    }
}
class MainActivity:Activity() {
    private lateinit var ledger:Ledger
    private lateinit var body:LinearLayout
    private var manage=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);ledger=Ledger(this)
        manage=callingPackage=="uk.edge.move.handheld.loyaltytest.staging" && packageManager.checkSignatures(callingPackage!!,packageName)==android.content.pm.PackageManager.SIGNATURE_MATCH
        home()
    }
    private fun page(title:String) {
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        setContentView(ScrollView(this).apply {addView(body)})
        label(title,28f);label("V1 · SINGLE-TERMINAL PILOT",14f)
    }
    private fun label(value:String,size:Float=18f) {body.addView(TextView(this).apply {text=value;textSize=size;setPadding(0,12,0,12)})}
    private fun button(value:String,action:()->Unit) {body.addView(Button(this).apply{text=value;setOnClickListener{action()}})}
    private fun home() {
        page("Move Loyalty")
        val r=ledger.rules()
        label(if(!r.enabled) "Programme paused" else if(r.mode=="STAMPS") "${r.target} stamps · minimum £${Ledger.money(r.minimum)}" else "Member discount on each qualifying purchase")
        label("Reward: "+if(r.kind=="PERCENT") "${r.reward}%" else "£${Ledger.money(r.reward)}")
        label(ledger.report())
        button("Refresh") {home()}
        if(manage) button("Programme settings") {settings()} else label("Open Loyalty from Move's device settings to change the programme.",14f)
        label("Payment card recognition only. Cash lookup and card merging are not enabled. Membership numbers are references, not passwords.",14f)
        button("Close") {finish()}
    }
    private fun settings() {
        if(!manage)return
        page("Programme settings");val old=ledger.rules()
        val enabled=CheckBox(this).apply{text="Programme enabled";isChecked=old.enabled};body.addView(enabled)
        fun choices(values:List<String>,selected:String):Spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,values);setSelection(values.indexOf(selected));body.addView(this)}
        label("Programme type");val mode=choices(listOf("STAMPS","MEMBER_DISCOUNT"),old.mode)
        fun field(title:String,value:String):EditText {label(title);return EditText(this).apply {setText(value);inputType=8194;body.addView(this)}}
        val minimum=field("Minimum purchase (£)",Ledger.money(old.minimum));val target=field("Stamps required",old.target.toString())
        label("Reward type");val kind=choices(listOf("PERCENT","FIXED"),old.kind)
        val reward=field("Reward (% or pounds for FIXED)",if(old.kind=="PERCENT") old.reward.toString() else Ledger.money(old.reward))
        label("Changes apply to the programme; existing stamp balances are retained. Fixed rewards leave at least 1p to pay. No account or cash recognition.",14f)
        button("Save programme") {
            try {
                val value=if(kind.selectedItem=="PERCENT") reward.text.toString().toLong() else BigDecimal(reward.text.toString()).movePointRight(2).longValueExact()
                ledger.saveRules(Rules(enabled.isChecked,BigDecimal(minimum.text.toString()).movePointRight(2).longValueExact(),target.text.toString().toInt(),kind.selectedItem.toString(),value,mode.selectedItem.toString()))
                home()
            } catch(e:Exception) {Toast.makeText(this,e.message ?: "Check programme values",Toast.LENGTH_LONG).show()}
        }
        button("Back") {home()}
    }
}
