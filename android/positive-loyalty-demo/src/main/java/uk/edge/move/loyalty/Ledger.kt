package uk.edge.move.loyalty

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class Ledger(context:Context,databaseName:String="move-loyalty-v1.db"):SQLiteOpenHelper(context,databaseName,null,1) {
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE state(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE members(merchant TEXT NOT NULL,id TEXT NOT NULL,number TEXT NOT NULL,stamps INTEGER NOT NULL,consent TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(merchant,id),UNIQUE(merchant,number))")
        db.execSQL("CREATE TABLE credentials(merchant TEXT NOT NULL,fingerprint TEXT NOT NULL,member TEXT NOT NULL,PRIMARY KEY(merchant,fingerprint))")
        db.execSQL("CREATE TABLE events(merchant TEXT NOT NULL,id TEXT NOT NULL,member TEXT NOT NULL,type TEXT NOT NULL,amount INTEGER NOT NULL,discount INTEGER NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(merchant,id))")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int)=Unit
    private fun read(db:SQLiteDatabase,id:String):JSONObject?=db.rawQuery("SELECT payload FROM state WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) JSONObject(it.getString(0)) else null}
    private fun put(db:SQLiteDatabase,id:String,o:JSONObject) {db.execSQL("INSERT OR REPLACE INTO state VALUES(?,?)",arrayOf(id,o.toString()))}
    private fun <T> tx(work:(SQLiteDatabase)->T):T {val db=writableDatabase;db.beginTransaction();try {val out=work(db);db.setTransactionSuccessful();return out} finally {db.endTransaction()}}
    fun rules():Rules {val o=read(readableDatabase,"rules") ?: return Rules();return Rules(o.getBoolean("enabled"),o.getLong("minimum"),o.getInt("target"),o.getString("kind"),o.getLong("reward"),o.getString("mode"),o.getInt("version"))}
    fun saveRules(r:Rules) {r.validate();tx {db->if(read(db,"context")?.optLong("expires",0)?.let {it<System.currentTimeMillis()}==true) db.execSQL("DELETE FROM state WHERE id='context'");check(read(db,"context")==null){"Finish the active payment before changing rules."};val old=rules();put(db,"rules",JSONObject().put("enabled",r.enabled).put("minimum",r.minimum).put("target",r.target).put("kind",r.kind).put("reward",r.reward).put("mode",r.mode).put("version",old.version+1))}}
    fun prepare(o:JSONObject) = tx { db ->
        require(o.getString("merchant").isNotBlank() && o.getString("terminal").isNotBlank() && o.getString("attempt").isNotBlank())
        require(o.getLong("amount") in 1..100_000_000)
        require(o.getLong("expires")>System.currentTimeMillis() && o.getLong("expires")<=System.currentTimeMillis()+310_000)
        val old=read(db,"context")
        check(old==null || old.optString("attempt")==o.getString("attempt") || old.getLong("expires")<System.currentTimeMillis()) {"A loyalty payment is already active."}
        if(old?.optString("attempt")!=o.getString("attempt")) {
            put(db,"context",o);put(db,"assignment",JSONObject().put("merchant",o.getString("merchant")).put("terminal",o.getString("terminal")))
        }
    }
    fun closeAttempt(attempt:String) = tx {db -> if(read(db,"context")?.optString("attempt")==attempt) db.execSQL("DELETE FROM state WHERE id='context'")}
    fun active():Boolean=read(readableDatabase,"context")?.let {it.getLong("expires")>System.currentTimeMillis()} ?: false
    data class Response(val status:Int=6,val discount:Long=0,val message:String="",val number:String="")
    fun callback(token:String,amount:Long,phase:String):Response = tx {db ->
        val c=read(db,"context") ?: return@tx Response()
        if(c.getLong("expires")<System.currentTimeMillis() || c.getLong("amount")!=amount || token.isBlank()) return@tx Response()
        val merchant=c.getString("merchant");val attempt=c.getString("attempt");val now=System.currentTimeMillis()
        val fp=MessageDigest.getInstance("SHA-256").digest((merchant+":"+token).toByteArray()).joinToString(""){"%02x".format(it)}
        // Raw tokens are never persisted, displayed or sent in logs.
        if(c.has("fingerprint") && c.getString("fingerprint")!=fp) return@tx Response(message="Use the same card for this payment.")
        var member=db.rawQuery("SELECT member FROM credentials WHERE merchant=? AND fingerprint=?",arrayOf(merchant,fp)).use {if(it.moveToFirst()) it.getString(0) else null}
        if(phase=="post_card_presented") {
            if(!rules().enabled) return@tx Response()
            if(c.has("fingerprint")) return@tx Response() // Same transaction's second tap never requests another discount.
            c.put("fingerprint",fp)
            if(member==null && c.optBoolean("optIn")) {
                val id=UUID.randomUUID().toString();var number:String
                do {number=java.security.SecureRandom().nextInt(100_000_000).toString().padStart(8,'0')} while(db.rawQuery("SELECT 1 FROM members WHERE merchant=? AND number=?",arrayOf(merchant,number)).use{it.moveToFirst()})
                val consent=JSONObject().put("wording",c.getString("consent")).put("operator",c.getString("operator")).put("terminal",c.getString("terminal")).put("attempt",attempt).put("at",now).toString()
                db.execSQL("INSERT INTO members VALUES(?,?,?,?,?,?)",arrayOf(merchant,id,number,0,consent,now))
                db.execSQL("INSERT INTO credentials VALUES(?,?,?)",arrayOf(merchant,fp,id))
                db.execSQL("INSERT INTO events VALUES(?,?,?,?,?,?,?)",arrayOf(merchant,"enrol:$attempt",id,"ENROLMENT",0,0,now));member=id
            }
            if(member==null) {put(db,"context",c);return@tx Response()}
            val r=rules();val stamps=db.rawQuery("SELECT stamps FROM members WHERE merchant=? AND id=?",arrayOf(merchant,member)).use{it.moveToFirst();it.getInt(0)}
            val discount=r.quote(amount,stamps)
            c.put("member",member).put("discount",discount).put("ruleVersion",r.version)
            put(db,"context",c)
            val number=memberNumber(db,merchant,member)
            return@tx if(discount>0) Response(5,discount,"Member $number\nDiscount £${money(discount)}\nTap again in POSitive to pay £${money(amount-discount)}",number) else Response(number=number)
        }
        if(phase!="post_transaction" || member==null || c.optString("member")!=member) return@tx Response()
        c.put("completionSeen",true);put(db,"context",c)
        Response(message="Member ${memberNumber(db,merchant,member)}\nPayment completed\nUpdating loyalty in Move",number=memberNumber(db,merchant,member))
    }
    fun finalResult(attempt:String,status:String,paid:Long,discount:Long):Boolean = tx {db ->
        val alreadyRecorded=db.rawQuery("SELECT 1 FROM events WHERE id=?",arrayOf("complete:$attempt")).use{it.moveToFirst()}
        if(alreadyRecorded) return@tx true
        val c=read(db,"context") ?: return@tx false
        if(c.getString("attempt")!=attempt) return@tx false
        if(status!="APPROVED") {db.execSQL("DELETE FROM state WHERE id='context'");return@tx true}
        val member=c.optString("member")
        if(member.isBlank()) {db.execSQL("DELETE FROM state WHERE id='context'");return@tx true}
        val original=c.getLong("amount");val expected=c.optLong("discount")
        // Stamp/reward mutations require the final structured Move result to balance.
        require(discount==expected && paid==original-expected && paid>0) {"Payment amounts need review; loyalty retained."}
        val merchant=c.getString("merchant");val r=rules()
        require(c.getInt("ruleVersion")==r.version)
        if(!db.rawQuery("SELECT 1 FROM events WHERE merchant=? AND id=?",arrayOf(merchant,"complete:$attempt")).use{it.moveToFirst()}) {
            val old=db.rawQuery("SELECT stamps FROM members WHERE merchant=? AND id=?",arrayOf(merchant,member)).use{it.moveToFirst();it.getInt(0)}
            val stamps=r.completedStamps(old,original,discount)
            db.execSQL("UPDATE members SET stamps=? WHERE merchant=? AND id=?",arrayOf(stamps,merchant,member))
            val type=if(discount>0) "REDEMPTION" else if(stamps>old) "STAMP" else "VISIT"
            db.execSQL("INSERT INTO events VALUES(?,?,?,?,?,?,?)",arrayOf(merchant,"complete:$attempt",member,type,original,discount,System.currentTimeMillis()))
        }
        db.execSQL("DELETE FROM state WHERE id='context'")
        true
    }
    private fun memberNumber(db:SQLiteDatabase,merchant:String,member:String):String=db.rawQuery("SELECT number FROM members WHERE merchant=? AND id=?",arrayOf(merchant,member)).use{it.moveToFirst();it.getString(0)}
    fun report():String {
        val db=readableDatabase;val merchant=read(db,"assignment")?.optString("merchant") ?: return "No Move terminal connected yet."
        fun count(sql:String)=db.rawQuery(sql,arrayOf(merchant)).use{it.moveToFirst();it.getLong(0)}
        val lines=mutableListOf("THIS TERMINAL · PILOT DATA", "Members: ${count("SELECT COUNT(*) FROM members WHERE merchant=?")}","Stamps earned: ${count("SELECT COUNT(*) FROM events WHERE merchant=? AND type='STAMP'")}","Rewards redeemed: ${count("SELECT COUNT(*) FROM events WHERE merchant=? AND type='REDEMPTION'")}","Discounts given: £${money(count("SELECT COALESCE(SUM(discount),0) FROM events WHERE merchant=?"))}","\nMemberships")
        db.rawQuery("SELECT number,stamps FROM members WHERE merchant=? ORDER BY created DESC LIMIT 100",arrayOf(merchant)).use {while(it.moveToNext()) lines.add("${it.getString(0)} · ${it.getInt(1)} stamps")}
        return lines.joinToString("\n")
    }
    companion object {fun money(n:Long)=String.format(java.util.Locale.UK,"%d.%02d",n/100,n%100)}
}
