package uk.edge.move.handheld

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class ReceiptTextTest {
    @Test fun longNamesAndAmountsFitWithoutTruncation() {
        val receipt=ReceiptText().apply {
            center("A merchant name longer than the paper width")
            row("2 x Roasted Stuffed Piquante Peppers","£123.45")
            row("Long reference", "X".repeat(31))
            row("Variance","−£2.50")
        }.toString()
        assertTrue(receipt.lines().all {it.length<=32})
        assertTrue(receipt.contains("Piquante Peppers"))
        assertTrue(receipt.lines().any {it.endsWith("£123.45") && it.length==32})
        assertTrue(receipt.contains("-£2.50"))
    }
    @Test fun saleRetainsTotalsModifiersAndMerchantAndWrapsAddress() {
        val sale=JSONObject().put("createdAt",0).put("reference","sample").put("type","SALE").put("amountMinor",500).put("staffName","Operator")
            .put("receipt",JSONObject().put("businessName","Test merchant").put("addressLine1","5 Beacon Close, Taunton, TA2 8FS").put("footerText","Thank you"))
            .put("saleItems",JSONArray().put(JSONObject().put("quantity",1).put("productName","Long product title on narrow paper").put("grossMinor",500).put("modifiers",JSONArray().put(JSONObject().put("name","Extra cheese")))))
            .put("tenders",JSONArray().put(JSONObject().put("label","Cash").put("amountMinor",500)))
            .put("cashReceivedMinor",1000).put("changeMinor",500)
        val receipt=saleReceipt(sale)
        assertEquals(BuildConfig.IS_STAGING, receipt.lines().any { it.trim()=="STAGING" })
        assertTrue(receipt.lines().all {it.length<=32})
        for(text in listOf("Test merchant","TA2 8FS","Extra cheese","Cash received","Change","Thank you")) assertTrue(receipt.contains(text))
        assertTrue(receipt.lines().any {it.startsWith("TOTAL") && it.endsWith("£5.00")})
    }
    @Test fun onlyReturnedCustomerCardCopyIsPrinted() {
        val returned="VISA\n**** **** **** 1234\nAUTH: ABC123"
        val sale=JSONObject().put("tenders",JSONArray().put(JSONObject().put("type","CARD"))).put("cardholderReceipt",returned)
        assertTrue(customerCardReceipt(sale).contains(returned))
        sale.remove("cardholderReceipt");sale.put("merchantReceipt","MERCHANT COPY")
        assertEquals("",customerCardReceipt(sale))
        sale.put("cardholderReceipt",returned).put("tenders",JSONArray().put(JSONObject().put("type","CASH")))
        assertEquals("",customerCardReceipt(sale))
    }
    @Test fun cardCopyFitsRulesPreservesFieldsAndLeavesTearSpace() {
        val fields=listOf("MasterCard Debit","************8972","AUTHORISED","AUTH CODE: 123456","**CARDHOLDER COPY**","PLEASE RETAIN RECEIPTS")
        val returned=(listOf("-".repeat(40))+fields).joinToString("\r\n")
        val result=formatCustomerCardCopy(returned)
        assertEquals("-".repeat(32),result.lineSequence().first())
        fields.forEach { assertTrue(result.lines().contains(it)) }
        assertTrue(result.endsWith("PLEASE RETAIN RECEIPTS\n\n\n\n"))
        val longField="CER:"+"1234567890".repeat(5)
        assertTrue(formatCustomerCardCopy(longField).startsWith(longField))
    }
    @Test fun updatedOrderPrintsOriginalAndAllApprovedRefundsWithoutMutatingRecords() {
        val original=JSONObject().put("id","sale").put("type","SALE").put("status","APPROVED")
            .put("reference","ORDER123").put("createdAt",0).put("amountMinor",760)
            .put("cardholderReceipt","ORIGINAL CARD SALE")
            .put("tenders",JSONArray().put(JSONObject().put("type","CARD").put("amountMinor",760)))
        fun refund(id:String,amount:Int)=JSONObject().put("id",id).put("type","REFUND").put("status","APPROVED")
            .put("reference",id).put("originalTransactionId","sale").put("createdAt",1).put("amountMinor",amount)
            .put("refundReason","Order mistake").put("cardholderReceipt","CARD REFUND $id")
            .put("tenders",JSONArray().put(JSONObject().put("type","CARD").put("label","Card").put("amountMinor",amount)))
        val first=refund("R1",200)
        val partial=saleReceipt(updatedOrderReceipt(original,listOf(first)))
        assertTrue(partial.contains("PART REFUNDED ORDER"))
        assertTrue(partial.lines().any {it.startsWith("Remaining paid") && it.endsWith("£5.60")})
        val full=saleReceipt(updatedOrderReceipt(original,listOf(first,refund("R2",560),refund("DECLINED",100).put("status","DECLINED"))))
        for(value in listOf("ORDER123","REFUNDED ORDER","R1","R2")) assertTrue(full.contains(value))
        assertTrue(full.lines().any {it.startsWith("Total refunded") && it.endsWith("£7.60")})
        assertTrue(full.lines().any {it.startsWith("Remaining paid") && it.endsWith("£0.00")})
        assertFalse(full.contains("ORIGINAL CARD SALE"))
        assertFalse(full.contains("CARD REFUND"))
        assertFalse(full.contains("CARD RECEIPT"))
        assertFalse(full.contains("DECLINED"))
        assertFalse(original.has("receiptRefunds"))
        assertEquals(760,original.getInt("amountMinor"))
        assertEquals(200,first.getInt("amountMinor"))
    }
    @Test fun vatSummaryMatchesCounterOrderAndAmounts() {
        val sale=JSONObject().put("createdAt",0).put("amountMinor",160)
            .put("receipt",JSONObject().put("vatRegistered",true))
            .put("saleItems",JSONArray().put(JSONObject().put("productName","Hash browns").put("quantity",1).put("grossMinor",160).put("vatMinor",27)))
            .put("tenders",JSONArray().put(JSONObject().put("label","Card").put("amountMinor",160)))
        val lines=saleReceipt(sale).lines()
        val subtotal=lines.indexOfFirst {it.startsWith("Subtotal")}
        val vat=lines.indexOfFirst {it.startsWith("VAT ")}
        val total=lines.indexOfFirst {it.startsWith("TOTAL")}
        val card=lines.indexOfFirst {it.startsWith("Card")}
        assertTrue(subtotal>=0 && subtotal<vat && vat<total && total<card)
        assertTrue(lines[subtotal].endsWith("£1.33"))
        assertTrue(lines[vat].endsWith("£0.27"))
        assertTrue(lines[total].endsWith("£1.60"))
        assertFalse(lines.any {it.contains("VAT included")})
        sale.getJSONObject("receipt").put("vatRegistered",false)
        assertFalse(saleReceipt(sale).lines().any {it.startsWith("Subtotal") || it.startsWith("VAT ")})
        sale.getJSONObject("receipt").put("vatRegistered",true)
        sale.getJSONArray("saleItems").getJSONObject(0).put("vatMinor",0)
        assertFalse(saleReceipt(sale).lines().any {it.startsWith("Subtotal") || it.startsWith("VAT ")})
    }
    @Test fun compactCardSummaryMatchesCounterWithoutProcessorAppendix() {
        val raw="Debit Mastercard\n************1964\nUTI: PRIVATE\nCryptogram: PRIVATE\nAUTHORISED"
        val sale=JSONObject().put("createdAt",0).put("amountMinor",100).put("cardholderReceipt",raw)
            .put("tenders",JSONArray().put(JSONObject().put("type","CARD").put("amountMinor",100)))
        val receipt=saleReceipt(sale)
        assertTrue(receipt.contains("Debit Mastercard ****1964"))
        assertFalse(receipt.contains("CARD RECEIPT"))
        assertFalse(receipt.contains("PRIVATE"))
        assertEquals(raw,sale.getString("cardholderReceipt"))
        sale.put("cardholderReceipt","4111111111111111\nUTI: 1234")
        assertEquals("Card",receiptTenderLabel(sale.getJSONArray("tenders").getJSONObject(0),sale))
        assertTrue(receipt.lines().all {it.length<=32})
    }
    @Test fun receiptUsesLeftDetailsCompactItemsAndReturnedPaymentFields() {
        val sale=JSONObject().put("createdAt",0).put("reference","ORDER1").put("amountMinor",100)
            .put("transactionId","reference-1").put("cardholderReceipt","CONTACTLESS\nAUTH CODE: AB123\nCryptogram: SECRET")
            .put("saleItems",JSONArray().put(JSONObject().put("productName","Coffee").put("quantity",1).put("grossMinor",100)))
            .put("tenders",JSONArray().put(JSONObject().put("type","CARD").put("amountMinor",100)))
        val receipt=saleReceipt(sale)
        assertTrue(receipt.lines().contains("Order number ORDER1"))
        assertTrue(receipt.lines().any {it.startsWith("1 x Coffee") && it.endsWith("£1.00")})
        assertTrue(receipt.contains("Method CONTACTLESS"))
        assertTrue(receipt.contains("Auth code AB123"))
        assertTrue(receipt.contains("Reference reference-1"))
        assertFalse(receipt.contains("QTY"));assertFalse(receipt.contains("SECRET"))
    }
    @Test fun cashUpSectionsAndMissingCountAreExplicit() {
        val report=dayReport(JSONObject().put("id","day").put("openingFloatMinor",5000),emptyList())
        val receipt=dayReceipt(report)
        for(text in listOf("END OF DAY","SALES","PAYMENTS","CASH RECONCILIATION","Expected cash","Not recorded")) assertTrue(receipt.contains(text))
        assertTrue(receipt.lines().all {it.length<=32})
        assertTrue(receipt.lines().any {it.startsWith("Expected cash") && it.endsWith("£50.00")})
    }
}
