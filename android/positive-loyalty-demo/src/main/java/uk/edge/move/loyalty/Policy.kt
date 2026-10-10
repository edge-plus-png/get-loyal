package uk.edge.move.loyalty

/** Money is always integer minor units; policy has no Android or processor dependencies. */
data class Rules(val enabled:Boolean=false,val minimum:Long=500,val target:Int=5,
    val kind:String="PERCENT",val reward:Long=10,val mode:String="STAMPS",val version:Int=1) {
    fun validate() {
        require(minimum in 1..100_000_000 && target in 1..100)
        require(kind in listOf("PERCENT","FIXED") && mode in listOf("STAMPS","MEMBER_DISCOUNT"))
        require(if(kind=="PERCENT") reward in 1..99 else reward in 1..100_000_000)
    }
    fun quote(amount:Long,stamps:Int):Long {
        validate()
        if(!enabled || amount<minimum || (mode=="STAMPS" && stamps<target)) return 0
        val discount=if(kind=="FIXED") reward else (amount*reward+50)/100
        return discount.coerceIn(0,amount-1)
    }
    fun completedStamps(stamps:Int,amount:Long,discount:Long):Int = when {
        !enabled || mode!="STAMPS" || amount<minimum -> stamps
        discount>0 -> 0
        else -> (stamps+1).coerceAtMost(target)
    }
}
