package uk.edge.move.handheld

import org.json.JSONArray
import org.json.JSONObject

/** Counter cash-count semantics, rendered with native portrait controls on Handheld. */
internal class CashCount {
    val counts=mutableMapOf<Int,Int>()
    var selected=0
    var denominations=true
    var digits=""
    var note=""
    var zeroAcknowledged=false
    var printReport=true
    val total:Long get()=if(denominations) values.sumOf {it.toLong()*(counts[it] ?: 0)} else digits.toLongOrNull() ?: 0
    fun key(key:String) {
        val old=if(denominations) (counts[values[selected]] ?: 0).toString() else digits
        val next=when(key) {"Clear"->"";"Delete"->old.dropLast(1);else->old+key}.trimStart('0').take(if(denominations) 4 else 8)
        if(denominations) {val count=next.toIntOrNull() ?: 0;counts[values[selected]]=count;zeroAcknowledged=counts.values.none {it>0} && count==0}
        else digits=next
    }
    fun next() {selected=(selected+1)%values.size}
    fun hasEntry(expected:Long)=total>0 || zeroAcknowledged || expected==0L
    fun json()=JSONArray(values.filter {(counts[it] ?: 0)>0}.map {value -> JSONObject().put("valueMinor",value).put("label",label(value)).put("quantity",counts[value]).put("count",counts[value]).put("totalMinor",value.toLong()*requireNotNull(counts[value]))})
    companion object {
        val values=listOf(5000,2000,1000,500,200,100,50,20,10,5,2,1)
        fun label(value:Int)=if(value>=100) formatMinor(value.toLong()) else "${value}p"
        fun validate(rows:JSONArray,total:Long) {
            val seen=mutableSetOf<Int>();var sum=0L
            for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);val value=row.getInt("valueMinor");val count=row.getInt("quantity")
                require(value in values && seen.add(value) && count in 0..9999) {"Invalid denomination count."}
                sum+=value.toLong()*count
                require(row.getLong("totalMinor")==value.toLong()*count) {"Denomination total changed."}
            }
            require(sum==total) {"Counted cash does not match the denomination count."}
        }
    }
}
