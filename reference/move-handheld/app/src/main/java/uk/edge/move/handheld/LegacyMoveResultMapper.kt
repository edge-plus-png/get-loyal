package uk.edge.move.handheld

import android.content.Context
import android.content.Intent
import android.util.Log
import com.eft.libpositive.PosIntegrate
import com.eft.libpositive.wrappers.PositiveTransResult
import com.eft.libpositive.wrappers.PositiveReportResult

internal enum class PaymentStatus { APPROVED, DECLINED, CANCELLED, ERROR, UNKNOWN }
internal data class PaymentResult(val status:PaymentStatus,val providerTransactionId:String,val message:String,val merchantReceiptText:String?=null,val cardholderReceiptText:String?=null)
internal data class CardReply(val result:PaymentResult,val reference:String?,val amount:Long?,val discount:Long?=null)
/** Exact pinned Move v26 result precedence/fallback. Not Tap's newer recovery semantics. */
internal object LegacyMoveResultMapper {
    private const val TAG="MoveResultMapper"
    private enum class RequestKind { SALE, REFUND, REPORT }
    fun map(context:Context,intent:Intent,refund:Boolean=false):CardReply? {
        if(intent.action!="com.eft.TRANSACTION_RESULT") return null
        if(intent.hasExtra("ReportReceipt") || intent.hasExtra("ReportType") || (intent.hasExtra("SaleAmount") && intent.hasExtra("SaleCount") && !intent.hasExtra("Amount"))) return null
        val kind=if(refund) RequestKind.REFUND else RequestKind.SALE
        val unpacked=runCatching {PosIntegrate.unpackResult(context,intent)}.getOrNull()
        val result=unpacked?.let {mapTransactionResult(it,kind)} ?: fallbackResult(intent,kind)
        return CardReply(merchantSafeFinalResult(result,kind),unpacked?.let {runCatching {it.getMerchantReference()}.getOrNull()},unpacked?.let { if(intent.extras?.get("Amount") is Long) runCatching {it.getAmountTrans()}.getOrNull() else null },unpacked?.let { if(intent.extras?.get("Discount") is Long) runCatching {it.getAmountDiscount()}.getOrNull() else null })
    }
    private fun mapTransactionResult(
        result: PositiveTransResult,
        requestKind: RequestKind,
    ): PaymentResult {
        val approved = runCatching { result.isTransApproved() }.getOrDefault(false)
        val cancelled = runCatching { result.isTransCancelled() }.getOrDefault(false)
        val errorText = runCatching { result.getErrorText() }.getOrNull().orEmpty().trim()
        val responseCode = runCatching { result.getResponseCode() }.getOrNull().orEmpty()
        val merchantReceiptText = result.optionalReceiptLines("getMerchantReceipt").toReceiptText()
        val cardholderReceiptText = result.optionalReceiptLines("getCardholderReceipt").toReceiptText()
        val providerId = listOf(
            runCatching { result.getUTI() }.getOrNull(),
            runCatching { result.getRetrievalReferenceNumber() }.getOrNull(),
            runCatching { result.getReceiptNumber().toString() }.getOrNull(),
        ).firstOrNull { !it.isNullOrBlank() } ?: "PAX-${System.currentTimeMillis()}"

        val reportApproved = requestKind == RequestKind.REPORT && responseCode == "00"
        val status = when {
            approved || reportApproved -> PaymentStatus.APPROVED
            cancelled -> PaymentStatus.CANCELLED
            responseCode.isNotBlank() && responseCode != "00" -> PaymentStatus.DECLINED
            errorText.isNotBlank() -> PaymentStatus.ERROR
            else -> PaymentStatus.UNKNOWN
        }

        val message = when (status) {
            PaymentStatus.APPROVED -> if (requestKind == RequestKind.REPORT) {
                "Terminal Z report completed on POSitive."
            } else {
                "Card approved."
            }
            PaymentStatus.CANCELLED -> if (requestKind == RequestKind.REPORT) {
                "Terminal Z report was cancelled."
            } else {
                "Payment cancelled."
            }
            PaymentStatus.DECLINED -> if (requestKind == RequestKind.REPORT) {
                errorText.ifBlank { "Terminal Z report failed on POSitive." }
            } else {
                errorText.ifBlank { "Card declined." }
            }
            PaymentStatus.ERROR -> if (requestKind == RequestKind.REPORT) {
                errorText.ifBlank { "Terminal Z report could not be completed." }
            } else {
                errorText.ifBlank { "Payment could not be completed." }
            }
            PaymentStatus.UNKNOWN -> if (requestKind == RequestKind.REPORT) {
                "Terminal Z report result could not be confirmed."
            } else {
                "Payment result could not be confirmed."
            }
        }

        return PaymentResult(
            status = status,
            providerTransactionId = providerId,
            message = message,
            merchantReceiptText = merchantReceiptText,
            cardholderReceiptText = cardholderReceiptText,
        )
    }

    private fun mapReportResult(result: PositiveReportResult): PaymentResult {
        val isReportResponse = runCatching { result.isReportResponse() }.getOrDefault(false)
        val resultType = runCatching { result.getResultType() }.getOrNull().orEmpty().trim()
        val reportType = runCatching { result.getReportType() }.getOrNull().orEmpty().trim()
        val reportTotals = runCatching { result.getReportTotals() }.getOrNull().orEmpty().trim()
        val saleCount = runCatching { result.getSaleCount() }.getOrDefault(0L)
        val saleAmount = runCatching { result.getSaleAmount() }.getOrDefault(0L)
        val reportText = listOf(resultType, reportType, reportTotals).joinToString(" ")
        val savedButNotSubmitted = reportText.contains("saved but not submitted", ignoreCase = true)
        val providerId = listOf(reportType, resultType)
            .filter { it.isNotBlank() }
            .joinToString("-")
            .ifBlank { "PAX-Z-${System.currentTimeMillis()}" }

        val approved = savedButNotSubmitted ||
            isReportResponse ||
            resultType.contains("report", ignoreCase = true) ||
            reportType.isNotBlank() ||
            reportTotals.isNotBlank() ||
            saleCount > 0L ||
            saleAmount > 0L

        Log.i(
            TAG,
            "POSitive report unpacked response=$isReportResponse resultType=$resultType " +
                "reportType=$reportType saleCount=$saleCount saleAmount=$saleAmount",
        )

        return PaymentResult(
            status = if (approved) PaymentStatus.APPROVED else PaymentStatus.UNKNOWN,
            providerTransactionId = providerId,
            message = if (approved) {
                "Terminal Z report completed on POSitive."
            } else {
                "Terminal Z report result could not be confirmed."
            },
        )
    }

    private fun fallbackResult(
        intent: Intent,
        requestKind: RequestKind,
    ): PaymentResult {
        val approved = intent.getBooleanExtra("Approved", false)
        val cancelled = intent.getBooleanExtra("Cancelled", false)
        val errorText = intent.getStringExtra("ErrorText").orEmpty()
        val responseCode = intent.getStringExtra("ResponseCode").orEmpty()
        val providerId = extractProviderId(intent).orEmpty().ifBlank { "PAX-${System.currentTimeMillis()}" }
        val merchantReceiptText = receiptLinesFromIntent(intent, "MerchantReceipt").toReceiptText()
        val cardholderReceiptText = receiptLinesFromIntent(intent, "CardholderReceipt").toReceiptText()
        val reportApproved = requestKind == RequestKind.REPORT && responseCode == "00"
        val status = when {
            approved || reportApproved -> PaymentStatus.APPROVED
            cancelled -> PaymentStatus.CANCELLED
            responseCode.isNotBlank() && responseCode != "00" -> PaymentStatus.DECLINED
            errorText.isNotBlank() -> PaymentStatus.ERROR
            else -> PaymentStatus.UNKNOWN
        }
        return PaymentResult(
            status = status,
            providerTransactionId = providerId,
            message = when (status) {
                PaymentStatus.APPROVED -> if (requestKind == RequestKind.REPORT) {
                    "Terminal Z report completed on POSitive."
                } else {
                    "Card approved."
                }
                PaymentStatus.CANCELLED -> if (requestKind == RequestKind.REPORT) {
                    "Terminal Z report was cancelled."
                } else {
                    "Payment cancelled."
                }
                PaymentStatus.ERROR -> if (requestKind == RequestKind.REPORT) {
                    errorText.ifBlank { "Terminal Z report could not be completed." }
                } else {
                    errorText.ifBlank { "Payment could not be completed." }
                }
                PaymentStatus.DECLINED -> if (requestKind == RequestKind.REPORT) {
                    errorText.ifBlank { "Terminal Z report failed on POSitive." }
                } else {
                    "Card declined."
                }
                PaymentStatus.UNKNOWN -> if (requestKind == RequestKind.REPORT) {
                    "Terminal Z report result could not be confirmed."
                } else {
                    "Payment result could not be confirmed."
                }
            },
            merchantReceiptText = merchantReceiptText,
            cardholderReceiptText = cardholderReceiptText,
        )
    }

    private fun PositiveTransResult.optionalReceiptLines(methodName: String): List<String> {
        val lines = runCatching {
            javaClass.methods.firstOrNull { method ->
                method.name == methodName && method.parameterCount == 0
            }?.invoke(this)
        }.getOrNull()
        return normalizeReceiptLines(lines)
    }

    private fun receiptLinesFromIntent(intent: Intent, key: String): List<String> {
        intent.getStringArrayListExtra(key)?.let { return normalizeReceiptLines(it) }
        intent.getStringArrayExtra(key)?.let { return normalizeReceiptLines(it) }
        intent.getCharSequenceExtra(key)?.let { return normalizeReceiptLines(it.toString()) }
        return emptyList()
    }

    private fun normalizeReceiptLines(value: Any?): List<String> = when (value) {
        is Array<*> -> value.filterIsInstance<String>().map(String::trim).filter(String::isNotBlank)
        is List<*> -> value.filterIsInstance<String>().map(String::trim).filter(String::isNotBlank)
        is String -> value.lines().map(String::trimEnd).filter(String::isNotBlank)
        else -> emptyList()
    }

    private fun List<String>.toReceiptText(): String? = joinToString(separator = "\n")
        .trim()
        .takeIf(String::isNotBlank)

    private fun extractProviderId(intent: Intent): String? = listOf(
        intent.getStringExtra("UTI"),
        intent.getStringExtra("uti"),
        intent.getStringExtra("TransId"),
        intent.getStringExtra("TransID"),
        intent.getStringExtra("transactionId"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun merchantSafeFinalResult(
        result: PaymentResult,
        requestKind: RequestKind,
    ): PaymentResult {
        if (result.status != PaymentStatus.UNKNOWN) return result
        if (requestKind == RequestKind.SALE || requestKind == RequestKind.REFUND) {
            return when (requestKind) {
                RequestKind.REFUND -> result.copy(
                    status = PaymentStatus.CANCELLED,
                    message = "Refund not approved. Please check POSitive and try again.",
                )
                RequestKind.SALE -> result.copy(
                    status = PaymentStatus.CANCELLED,
                    message = "Payment not completed. No payment was taken.",
                )
                RequestKind.REPORT -> result
            }
        }

        return when (requestKind) {
            RequestKind.REFUND -> result.copy(
                status = PaymentStatus.CANCELLED,
                message = "Refund not approved. Please check POSitive and try again.",
            )
            RequestKind.REPORT -> result.copy(
                status = PaymentStatus.CANCELLED,
                message = "POSitive Z was requested.",
            )
            RequestKind.SALE -> result.copy(
                status = PaymentStatus.CANCELLED,
                message = "Payment not completed. No payment was taken.",
            )
        }
    }

}
