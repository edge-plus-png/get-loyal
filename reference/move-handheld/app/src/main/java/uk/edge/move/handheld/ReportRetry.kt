package uk.edge.move.handheld

/** Equal jitter: 15–30 seconds initially, capped at 7.5–15 minutes. */
internal object ReportRetry {
    fun delayMillis(failures:Int, random:Double):Long {
        val ceiling=(30_000L * (1L shl (failures-1).coerceIn(0,5))).coerceAtMost(900_000L)
        return ceiling/2 + ((ceiling/2)*random.coerceIn(0.0,1.0)).toLong()
    }
}
