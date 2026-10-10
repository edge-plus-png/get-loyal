package uk.edge.move.loyalty
import org.junit.Assert.*
import org.junit.Test
class PolicyTest {
 @Test fun noRewardBeforeThreshold() {assertEquals(0,Rules(true).quote(1080,4))}
 @Test fun exactSingleDiscountExample() {assertEquals(108,Rules(true).quote(1080,5));assertEquals(0,Rules(true).completedStamps(5,1080,108))}
 @Test fun fifthPurchaseEarnsRewardForNextPurchase() {val r=Rules(true);assertEquals(0,r.quote(500,4));assertEquals(5,r.completedStamps(4,500,0));assertEquals(50,r.quote(500,5))}
 @Test fun lowValueEarnsNothing() {assertEquals(2,Rules(true).completedStamps(2,499,0));assertEquals(0,Rules(true).quote(499,5))}
 @Test fun pausedProgrammeDoesNotAwardOrSpend() {assertEquals(0,Rules().quote(1080,5));assertEquals(5,Rules().completedStamps(5,1080,0))}
 @Test fun fixedRewardCannotProduceZeroPayment() {assertEquals(499,Rules(true,kind="FIXED",reward=1000).quote(500,5))}
 @Test fun recurringMemberDiscountNeedsNoStamps() {val r=Rules(true,mode="MEMBER_DISCOUNT");assertEquals(108,r.quote(1080,0));assertEquals(0,r.completedStamps(0,1080,108))}
 @Test fun invalidProgrammeRejected() {assertThrows(IllegalArgumentException::class.java) {Rules(true,target=0).validate()}}
}
