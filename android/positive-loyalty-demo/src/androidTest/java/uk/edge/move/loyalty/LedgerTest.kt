package uk.edge.move.loyalty
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject

/** Synthetic fixture DB only: no payment dispatch, card data, merchant roles or live app state. */
@RunWith(AndroidJUnit4::class)
class LedgerTest {
 private val context=InstrumentationRegistry.getInstrumentation().targetContext
 private lateinit var ledger:Ledger
 private val dbName="loyalty-v1-fixture.db"
 @Before fun before(){context.deleteDatabase(dbName);ledger=Ledger(context,dbName);ledger.saveRules(Rules(true))}
 @After fun after(){ledger.close();context.deleteDatabase(dbName)}
 private fun prepare(id:String,optIn:Boolean=true,merchant:String="fixture-merchant",amount:Long=1000){ledger.prepare(JSONObject().put("merchant",merchant).put("terminal","fixture-terminal").put("attempt",id).put("operator","fixture-operator").put("amount",amount).put("expires",System.currentTimeMillis()+300_000).put("optIn",optIn).put("consent","Fixture explicit consent"))}
 private fun tap(token:String="synthetic-a",amount:Long=1000)=ledger.callback(token,amount,"post_card_presented")
 private fun complete(id:String,discount:Long=0){ledger.callback("synthetic-a",1000,"post_transaction");ledger.finalResult(id,"APPROVED",1000-discount,discount)}
 @Test fun skippingOptInCreatesNoMemberOrStamp(){prepare("skip",false);tap();complete("skip");assertTrue(ledger.report().contains("Members: 0"));assertTrue(ledger.report().contains("Stamps earned: 0"))}
 @Test fun sameCardGetsOneMembershipAndOneStampPerSale(){prepare("one");val a=tap();complete("one");prepare("two",false);val b=tap();assertEquals(a.number,b.number);complete("two");assertTrue(ledger.report().contains("Members: 1"));assertTrue(ledger.report().contains("Stamps earned: 2"))}
 @Test fun duplicateCompletionNeverDoubleCredits(){prepare("dup");tap();ledger.callback("synthetic-a",1000,"post_transaction");ledger.callback("synthetic-a",1000,"post_transaction");ledger.finalResult("dup","APPROVED",1000,0);ledger.finalResult("dup","APPROVED",1000,0);assertTrue(ledger.report().contains("Stamps earned: 1"))}
 @Test fun fullRewardCycleReturnsDiscountOnceAndConsumesOnce(){for(i in 1..5){prepare("earn$i",i==1);tap();complete("earn$i")};prepare("reward",false);val first=tap();assertEquals(100,first.discount);assertEquals(5,first.status);assertEquals(6,tap().status);val post=ledger.callback("synthetic-a",1000,"post_transaction");assertEquals(6,post.status);assertEquals(0,post.discount);ledger.finalResult("reward","APPROVED",900,100);ledger.finalResult("reward","APPROVED",900,100);assertTrue(ledger.report().contains("Rewards redeemed: 1"));assertTrue(ledger.report().contains("0 stamps"))}
 @Test fun cancelledPaymentAwardsNoStamp(){prepare("cancel");tap();ledger.finalResult("cancel","CANCELLED",0,0);assertTrue(ledger.report().contains("Stamps earned: 0"))}
 @Test fun unrelatedMerchantDoesNotRecogniseCredential(){prepare("a");val number=tap().number;complete("a");prepare("b",false,"other-fixture");assertEquals("",tap().number);complete("b");assertTrue(ledger.report().contains("Members: 0"));assertTrue(number.isNotBlank())}
 @Test fun mismatchPreservesBalanceAndPendingContext(){prepare("bad");tap();try{ledger.finalResult("bad","APPROVED",900,0);fail("Expected amount rejection")}catch(_:IllegalArgumentException){};assertTrue(ledger.active());assertTrue(ledger.report().contains("Stamps earned: 0"))}
 @Test fun wrongSecondCredentialCannotTakeRewardOrEarnStamp(){prepare("wrong");tap();val response=tap("synthetic-b");assertEquals(6,response.status);assertEquals(0,response.discount);assertTrue(response.message.contains("same card"))}
}
