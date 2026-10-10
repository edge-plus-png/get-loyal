package uk.edge.move.handheld

import com.eft.libpositive.wrappers.PositiveTransResult
import org.junit.Assert.*
import org.junit.Test

class LegacyResultTest {
    private fun mapped(approved:Boolean=false,cancelled:Boolean=false,code:String="",error:String=""):PaymentResult {
        val result=PositiveTransResult()
        mapOf("transApproved" to approved,"transCancelled" to cancelled,"responseCode" to code,"errorText" to error,"UTI" to "fixture-uti").forEach { (name,value) -> result.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(result,value) }
        val mapper=LegacyMoveResultMapper
        val kind=mapper.javaClass.declaredClasses.first {it.simpleName=="RequestKind"}.enumConstants.first {it.toString()=="SALE"}
        return mapper.javaClass.getDeclaredMethod("mapTransactionResult",PositiveTransResult::class.java,kind.javaClass).apply {isAccessible=true}.invoke(mapper,result,kind) as PaymentResult
    }
    @Test fun preservePinnedMoveApprovalPrecedence() {
        assertEquals(PaymentStatus.APPROVED,mapped(approved=true).status)
        assertEquals(PaymentStatus.APPROVED,mapped(approved=true,cancelled=true,error="advisory").status)
        assertEquals(PaymentStatus.CANCELLED,mapped(cancelled=true).status)
        assertEquals(PaymentStatus.DECLINED,mapped(code="05").status)
        assertEquals(PaymentStatus.ERROR,mapped(error="failure").status)
        assertEquals(PaymentStatus.UNKNOWN,mapped(code="00").status) // Code alone cannot approve a sale.
    }
}
