package com.edge.poslite.domain.model

enum class UserRole {
    STAFF,
    SUPPORT_QA,
    MANAGER,
}

enum class OrderStatus {
    ACTIVE,
    OPEN_UNPAID,
    COMPLETED,
    CANCELLED,
}

enum class PaymentMethod {
    CASH,
    CARD,
    SPLIT,
}

enum class ServiceMode {
    RESTAURANT,
    OUTSIDE,
    TAKEAWAY,
}

enum class TenderType {
    CASH,
    CARD,
    MANUAL_CARD,
    VOUCHER,
    ACCOUNT,
    GIFT_CARD,
    OTHER,
}

enum class TradingDayStatus {
    OPEN,
    CLOSED,
}

enum class CashDrawerMovementType {
    PAID_IN,
    PAID_OUT,
}

enum class PrinterTarget {
    RECEIPT,
    ORDER,
}

enum class OrderTicketPrintMode {
    OFF,
    COMBINED,
    SPLIT_BY_PRINT_GROUP,
}

enum class TicketReferenceMode {
    AUTO_NUMBER,
    MANUAL_REFERENCE,
    AUTO_NUMBER_AND_REFERENCE,
}

enum class CatalogueChannel {
    MOVE,
    COUNTER,
    PRO,
    RETAIL,
    ONLINE,
}

data class ProductCapabilities(
    val channel: CatalogueChannel = CatalogueChannel.COUNTER,
    val sharedCatalogue: Boolean = true,
    val screenLayout: Boolean = true,
    val voucherTender: Boolean = true,
    val serviceModes: Boolean = true,
    val tables: Boolean = true,
    val orderRoutes: Boolean = true,
    val barcodeScan: Boolean = false,
    val stock: Boolean = false,
    val returns: Boolean = false,
    val tabs: Boolean = false,
    val splitBill: Boolean = false,
    val payAtTable: Boolean = false,
    val onlineAvailability: Boolean = false,
    val collectionDelivery: Boolean = false,
    val onlinePricing: Boolean = false,
    val deliveryCharges: Boolean = false,
    val slotCapacity: Boolean = false,
    val productCapacityLimits: Boolean = false,
) {
    val hasOrderContextOptions: Boolean
        get() = tables || serviceModes

    val screenName: String
        get() = when (channel) {
            CatalogueChannel.MOVE -> "Move screen"
            CatalogueChannel.COUNTER -> "Counter screen"
            CatalogueChannel.PRO -> "Pro screen"
            CatalogueChannel.RETAIL -> "Retail screen"
            CatalogueChannel.ONLINE -> "Online setup"
        }

    companion object {
        fun forChannel(channel: CatalogueChannel): ProductCapabilities =
            when (channel) {
                CatalogueChannel.MOVE -> ProductCapabilities(
                    channel = CatalogueChannel.MOVE,
                    serviceModes = false,
                    tables = false,
                    orderRoutes = false,
                )
                CatalogueChannel.COUNTER -> ProductCapabilities()
                CatalogueChannel.PRO -> ProductCapabilities(
                    channel = CatalogueChannel.PRO,
                    barcodeScan = true,
                    stock = true,
                    tabs = true,
                    splitBill = true,
                    payAtTable = true,
                )
                CatalogueChannel.RETAIL -> ProductCapabilities(
                    channel = CatalogueChannel.RETAIL,
                    voucherTender = false,
                    serviceModes = false,
                    tables = false,
                    orderRoutes = false,
                    barcodeScan = true,
                    stock = true,
                    returns = true,
                )
                CatalogueChannel.ONLINE -> ProductCapabilities(
                    channel = CatalogueChannel.ONLINE,
                    screenLayout = false,
                    voucherTender = false,
                    serviceModes = false,
                    tables = false,
                    orderRoutes = false,
                    onlineAvailability = true,
                    collectionDelivery = true,
                    onlinePricing = true,
                    deliveryCharges = true,
                    slotCapacity = true,
                    productCapacityLimits = true,
                )
            }
    }
}

enum class ConnectionHealth {
    ONLINE,
    DEGRADED,
    OFFLINE,
    NOT_CONFIGURED,
}

enum class ModifierPricingMode {
    FIXED_ADD_ON,
}

enum class ModifierSourceType {
    MANUAL,
    DEPARTMENT,
}

enum class ModifierDisplayStyle {
    BUTTON_GRID,
}

enum class ModifierOptionSortMode {
    BACK_OFFICE,
    ALPHABETICAL,
}

enum class ModifierTicketNamePlacement {
    PREFIX,
    SUFFIX,
    DETAIL,
    OPTION_ONLY,
    HIDDEN,
    EXTRA_DETAIL,
}

const val UNLIMITED_MODIFIER_SELECTIONS: Int = Int.MAX_VALUE

enum class ModifierOptionType {
    STANDARD,
    PRODUCT_CONFIG,
    PRODUCT_ADDON,
}

enum class BundleGroupSourceType {
    DEPARTMENT,
}

enum class PromotionType {
    BOGOF,
    MULTIBUY_FIXED_PRICE,
    SET_UNIT_PRICE,
}

enum class PromotionSourceType {
    DEPARTMENT,
    PRODUCT,
}

data class User(
    val id: String,
    val name: String,
    val role: UserRole,
    val pin: String,
    val isActive: Boolean = true,
    val isTrainingMode: Boolean = false,
    val permissions: CounterPermissions = CounterPermissions.forRole(role),
)

data class CounterPermissions(
    val takePaymentEnabled: Boolean = true,
    val transactionsEnabled: Boolean = false,
    val totalsTodayEnabled: Boolean = false,
    val zReportEnabled: Boolean = false,
    val refundsEnabled: Boolean = false,
    val openRefundsEnabled: Boolean = false,
    val voidsEnabled: Boolean = false,
    val reportsEnabled: Boolean = false,
    val syncNowEnabled: Boolean = false,
    val terminalSettingsEnabled: Boolean = false,
    val manualPaymentModeEnabled: Boolean = false,
    val printTransactionListEnabled: Boolean = false,
) {
    companion object {
        fun forRole(role: UserRole): CounterPermissions =
            when (role) {
                UserRole.MANAGER -> CounterPermissions(
                    takePaymentEnabled = true,
                    transactionsEnabled = true,
                    totalsTodayEnabled = true,
                    zReportEnabled = true,
                    refundsEnabled = true,
                    openRefundsEnabled = true,
                    voidsEnabled = true,
                    reportsEnabled = true,
                    syncNowEnabled = true,
                    terminalSettingsEnabled = true,
                    manualPaymentModeEnabled = true,
                    printTransactionListEnabled = true,
                )
                UserRole.SUPPORT_QA -> CounterPermissions(
                    takePaymentEnabled = false,
                    transactionsEnabled = true,
                    totalsTodayEnabled = true,
                    zReportEnabled = true,
                    refundsEnabled = false,
                    openRefundsEnabled = false,
                    voidsEnabled = false,
                    reportsEnabled = true,
                    syncNowEnabled = true,
                    terminalSettingsEnabled = true,
                    manualPaymentModeEnabled = false,
                    printTransactionListEnabled = false,
                )
                UserRole.STAFF -> CounterPermissions()
            }
    }
}

data class Category(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val isActive: Boolean,
    val showOnTill: Boolean,
    val headlineGroupId: Long? = null,
    val printGroupId: Long? = null,
)

data class Department(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val sortOrder: Int,
)

data class DepartmentAdminItem(
    val department: Category,
    val productCount: Int,
    val previewProductNames: List<String>,
    val sourcedModifierGroupCount: Int,
)

data class ReportingGroupAdminItem(
    val reportingGroup: Department,
    val productCount: Int,
    val previewProductNames: List<String>,
)

data class HeadlineDepartmentGroup(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val sortOrder: Int,
)

data class HeadlineGroupAdminItem(
    val group: HeadlineDepartmentGroup,
    val mappedDepartments: List<Category>,
)

data class TillLayoutSettings(
    val useHeadlineDepartmentGroups: Boolean,
    val isVatRegistered: Boolean = true,
    val vatStatus: String = "REGISTERED",
    val autoPrintReceipt: Boolean = false,
    val autoPrintStartDay: Boolean = false,
    val autoPrintEndDayReport: Boolean = false,
    val receiptHeaderText: String = "",
    val receiptFooterText: String = "",
    val orderTicketPrintMode: OrderTicketPrintMode = OrderTicketPrintMode.OFF,
    val ticketReferenceMode: TicketReferenceMode = TicketReferenceMode.AUTO_NUMBER,
    val orderTicketShowOrderNumber: Boolean = true,
    val orderTicketShowHandoverFooter: Boolean = false,
    val allergyPromptEnabled: Boolean = false,
    val allergyOptions: List<String> = emptyList(),
)

data class PrinterSettings(
    val target: PrinterTarget,
    val name: String,
    val ipAddress: String,
    val port: Int,
    val isEnabled: Boolean,
)

data class PrintGroup(
    val id: Long,
    val name: String,
    val printerName: String,
    val ipAddress: String,
    val port: Int,
    val isActive: Boolean,
    val sortOrder: Int,
)

data class TablePosition(
    val id: Long,
    val label: String,
    val areaName: String,
    val isActive: Boolean,
    val sortOrder: Int,
)

data class DeviceStatus(
    val tillHealth: ConnectionHealth = ConnectionHealth.OFFLINE,
    val receiptPrinterHealth: ConnectionHealth = ConnectionHealth.NOT_CONFIGURED,
    val orderPrinterHealth: ConnectionHealth = ConnectionHealth.NOT_CONFIGURED,
)

data class DiscountPreset(
    val id: Long,
    val name: String,
    val percentage: Int,
    val isActive: Boolean,
    val sortOrder: Int,
)

data class Product(
    val id: Long,
    val name: String,
    val shortName: String?,
    val pricePence: Int,
    val vatRatePercent: Int,
    val categoryId: Long,
    val departmentId: Long?,
    val isActive: Boolean,
    val isAvailableOnTill: Boolean,
    val isOutOfStock: Boolean,
    val forceModifierSelection: Boolean,
    val quickAddEnabled: Boolean,
    val isFeatured: Boolean,
    val isOpenPrice: Boolean = false,
    val pricingMode: String = "FIXED",
    val selectionBarJson: String = "",
    val builderPriceTiersCsv: String = "",
    val builderModifierGroupId: Long? = null,
    val sortOrder: Int,
    val printGroupId: Long? = null,
    val takeawayPricePence: Int = pricePence,
    val takeawayDiscountPence: Int = 0,
    val takeawayDiscountBasisPoints: Int = 0,
    val takeawayVatRatePercent: Int = vatRatePercent,
    val kitchenPrintName: String = "",
)

data class ModifierOption(
    val id: Long,
    val groupId: Long,
    val sourceType: ModifierSourceType,
    val optionType: ModifierOptionType,
    val linkedProductId: Long?,
    val name: String,
    val priceDeltaPence: Int,
    val printGroupId: Long? = null,
    val sortOrder: Int,
    val isActive: Boolean,
    val selectedItemId: Long?,
)

data class ModifierGroup(
    val id: Long,
    val name: String,
    val minSelect: Int,
    val maxSelect: Int,
    val pricingMode: ModifierPricingMode,
    val sourceType: ModifierSourceType,
    val sourceDepartmentId: Long?,
    val displayStyle: ModifierDisplayStyle,
    val sortOrder: Int,
    val isActive: Boolean,
    val includedSelections: Int = 0,
    val allowRepeatSelections: Boolean = false,
    val optionSortMode: ModifierOptionSortMode = ModifierOptionSortMode.BACK_OFFICE,
    val ticketNamePlacement: ModifierTicketNamePlacement = ModifierTicketNamePlacement.DETAIL,
    val ticketNamePrefix: String = "",
    val options: List<ModifierOption>,
) {
    val isRequired: Boolean get() = minSelect > 0
    val selectionSummary: String
        get() = when {
            minSelect == 1 && maxSelect == 1 -> "Choose 1"
            minSelect == 0 && maxSelect == 1 -> "Choose 0 to 1"
            maxSelect == UNLIMITED_MODIFIER_SELECTIONS && minSelect == 0 -> "Choose any amount"
            maxSelect == UNLIMITED_MODIFIER_SELECTIONS -> "Choose at least $minSelect"
            minSelect == 0 -> "Choose up to $maxSelect"
            else -> "Choose $minSelect to $maxSelect"
        }
}

data class ModifierGroupAdminItem(
    val group: ModifierGroup,
    val sourceDepartmentName: String?,
    val sourcePreviewProductNames: List<String>,
    val attachedProductCount: Int,
)

data class ProductModifierGroup(
    val modifierGroup: ModifierGroup,
    val displayOrder: Int,
    val openByDefault: Boolean,
)

data class ProductCatalogItem(
    val product: Product,
    val modifierGroups: List<ProductModifierGroup>,
    val linkedAddons: List<Product> = emptyList(),
)

data class ProductModifierLink(
    val productId: Long,
    val modifierGroupId: Long,
    val displayOrder: Int,
    val openByDefault: Boolean,
)

data class ProductLinkedAddonLink(
    val productId: Long,
    val linkedProductId: Long,
    val sortOrder: Int,
)

data class BundleGroup(
    val id: Long,
    val bundleId: Long,
    val name: String,
    val requiredQuantity: Int,
    val sourceType: BundleGroupSourceType,
    val sourceDepartmentId: Long?,
    val sortOrder: Int,
)

data class Bundle(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val fixedPricePence: Int,
    val autoApply: Boolean,
    val availableOnTill: Boolean,
    val sortOrder: Int,
    val groups: List<BundleGroup> = emptyList(),
)

data class BundleAdminItem(
    val bundle: Bundle,
    val sourceDepartmentNames: List<String>,
)

data class Promotion(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val type: PromotionType,
    val sourceType: PromotionSourceType,
    val sourceDepartmentId: Long?,
    val sourceProductId: Long?,
    val triggerQuantity: Int,
    val fixedPricePence: Int?,
    val setPricePence: Int?,
    val autoApply: Boolean,
    val availableOnTill: Boolean,
    val sortOrder: Int,
)

data class PromotionAdminItem(
    val promotion: Promotion,
    val sourceLabel: String?,
)

data class OrderBundleApplication(
    val id: Long,
    val bundleId: Long,
    val bundleName: String,
    val applicationCount: Int,
    val discountPence: Int,
    val matchedLineIds: List<Long>,
)

data class OrderPromotionApplication(
    val id: Long,
    val promotionId: Long,
    val promotionName: String,
    val applicationCount: Int,
    val discountPence: Int,
    val matchedLineIds: List<Long>,
)

data class OrderLineModifier(
    val id: Long,
    val sourceType: ModifierSourceType,
    val optionType: ModifierOptionType,
    val optionId: Long,
    val modifierGroupId: Long,
    val modifierGroupName: String,
    val parentOptionId: Long?,
    val parentOptionName: String?,
    val selectedItemId: Long?,
    val selectedItemName: String,
    val priceDeltaPence: Int,
    val printGroupId: Long? = null,
    val selectionSequence: Int = 0,
    val isIncluded: Boolean = false,
    val ticketNamePlacement: ModifierTicketNamePlacement = ModifierTicketNamePlacement.DETAIL,
    val ticketNamePrefix: String = "",
)

data class OrderLine(
    val id: Long,
    val productId: Long?,
    val productName: String,
    val unitPricePence: Int,
    val originalUnitPricePence: Int?,
    val priceOverrideReason: String?,
    val quantity: Int,
    val note: String?,
    val vatRatePercent: Int,
    val modifiers: List<OrderLineModifier>,
    val totalPence: Int,
    val modifierTotalPence: Int,
    val baseUnitPricePence: Int = unitPricePence,
    val takeawayDiscountPence: Int = 0,
) {
    val modifierSummary: String
        get() = modifiers.joinToString(separator = " • ") { it.selectedItemName }
    val isPriceOverridden: Boolean get() = originalUnitPricePence != null
}

data class Order(
    val id: Long,
    val orderNumber: String,
    val orderReference: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val status: OrderStatus,
    val paymentMethod: PaymentMethod?,
    val deviceId: String,
    val tradingDayId: Long?,
    val subtotalPence: Int,
    val vatPence: Int,
    val totalPence: Int,
    val userId: String,
    val completedAt: Long?,
    val serviceMode: ServiceMode,
    val lines: List<OrderLine>,
    val bundleApplications: List<OrderBundleApplication> = emptyList(),
    val bundleDiscountPence: Int = 0,
    val promotionApplications: List<OrderPromotionApplication> = emptyList(),
    val promotionDiscountPence: Int = 0,
    val basketDiscountPence: Int = 0,
    val basketDiscountReason: String? = null,
    val allergies: List<String> = emptyList(),
    val tenders: List<OrderTender> = emptyList(),
    val refunds: List<OrderRefund> = emptyList(),
) {
    val refundedPence: Int get() = refunds.sumOf { it.amountPence }
    val remainingRefundablePence: Int get() = (totalPence - refundedPence).coerceAtLeast(0)
    val tipPence: Int get() = tenders.sumOf { it.tipPence }
    val refundedTipPence: Int get() = refunds.sumOf { it.tipPence }
    val remainingTipRefundablePence: Int get() = (tipPence - refundedTipPence).coerceAtLeast(0)
    val isFullyRefunded: Boolean get() = totalPence > 0 &&
        remainingRefundablePence <= 0 &&
        remainingTipRefundablePence <= 0
    val isPartiallyRefunded: Boolean get() = refundedPence in 1 until totalPence
}

data class OrderRefund(
    val id: Long,
    val tenderType: TenderType,
    val amountPence: Int,
    val tipPence: Int = 0,
    val reason: String,
    val createdAt: Long,
    val createdByUserId: String,
    val itemLinesJson: String? = null,
)

data class RefundLineItem(
    val orderLineId: Long,
    val productId: Long?,
    val productName: String,
    val quantity: Int,
    val grossPence: Int,
    val vatPence: Int,
    val netPence: Int,
    val vatRatePercent: Int,
)

data class SalesReport(
    val salesTotalPence: Int,
    val orderCount: Int,
    val vatTotalPence: Int,
    val cashTotalPence: Int,
    val cardTotalPence: Int,
    val manualCardTotalPence: Int = 0,
    val otherTotalPence: Int = 0,
    val tipTotalPence: Int = 0,
    val cashTipPence: Int = 0,
    val cardTipPence: Int = 0,
    val manualCardTipPence: Int = 0,
    val otherTipPence: Int = 0,
    val refundTotalPence: Int = 0,
    val cashRefundTotalPence: Int = 0,
    val cashRefundTipPence: Int = 0,
    val cardRefundTotalPence: Int = 0,
    val manualCardRefundTotalPence: Int = 0,
    val otherRefundTotalPence: Int = 0,
    val refundCount: Int = 0,
    val itemCount: Int = 0,
    val discountTotalPence: Int = 0,
)

data class HourlySales(
    val hour: Int,
    val salesTotalPence: Int,
    val orderCount: Int,
)

data class OrderTender(
    val id: Long,
    val tenderType: TenderType,
    val amountPence: Int,
    val receivedPence: Int?,
    val changeDuePence: Int?,
    val createdAt: Long,
    val tipPence: Int = 0,
    val cardScheme: String? = null,
    val cardType: String? = null,
    val cardLast4: String? = null,
    val transactionReference: String? = null,
    val retrievalReferenceNumber: String? = null,
)

data class TenderInput(
    val tenderType: TenderType,
    val amountPence: Int,
    val receivedPence: Int? = null,
    val changeDuePence: Int? = null,
    val tipPence: Int = 0,
    val cardScheme: String? = null,
    val cardType: String? = null,
    val cardLast4: String? = null,
    val transactionReference: String? = null,
    val retrievalReferenceNumber: String? = null,
)

data class TradingDay(
    val id: Long,
    val businessDate: String,
    val openedAt: Long,
    val openedByUserId: String,
    val openingFloatPence: Int,
    val status: TradingDayStatus,
    val openingNote: String?,
    val closedAt: Long?,
    val closedByUserId: String?,
    val countedCashPence: Int?,
    val expectedCashPence: Int?,
    val variancePence: Int?,
    val closingNote: String?,
)

data class TradingDayReport(
    val tradingDay: TradingDay? = null,
    val grossSalesPence: Int = 0,
    val orderCount: Int = 0,
    val vatTotalPence: Int = 0,
    val itemCount: Int = 0,
    val discountTotalPence: Int = 0,
    val cashSalesPence: Int = 0,
    val cardSalesPence: Int = 0,
    val manualCardSalesPence: Int = 0,
    val otherSalesPence: Int = 0,
    val tipTotalPence: Int = 0,
    val cashTipPence: Int = 0,
    val cardTipPence: Int = 0,
    val manualCardTipPence: Int = 0,
    val otherTipPence: Int = 0,
    val refundTotalPence: Int = 0,
    val cashRefundPence: Int = 0,
    val cardRefundPence: Int = 0,
    val manualCardRefundPence: Int = 0,
    val otherRefundPence: Int = 0,
    val refundCount: Int = 0,
    val openingFloatPence: Int = 0,
    val paidInTotalPence: Int = 0,
    val paidOutTotalPence: Int = 0,
    val expectedCashPence: Int = 0,
    val countedCashPence: Int? = null,
    val variancePence: Int? = null,
    val allergies: List<String> = emptyList(),
)

data class CashDrawerMovement(
    val id: Long,
    val tradingDayId: Long,
    val type: CashDrawerMovementType,
    val amountPence: Int,
    val reason: String,
    val note: String?,
    val createdAt: Long,
    val createdByUserId: String,
)

data class ModifierSelection(
    val sourceType: ModifierSourceType,
    val optionType: ModifierOptionType,
    val optionId: Long,
    val groupId: Long,
    val groupName: String,
    val parentOptionId: Long? = null,
    val parentOptionName: String? = null,
    val selectedItemId: Long?,
    val selectedItemName: String,
    val priceDeltaPence: Int,
    val printGroupId: Long? = null,
    val selectionSequence: Int = 0,
    val isIncluded: Boolean = false,
    val ticketNamePlacement: ModifierTicketNamePlacement = ModifierTicketNamePlacement.DETAIL,
    val ticketNamePrefix: String = "",
)

data class TillCatalog(
    val categories: List<Category>,
    val departments: List<Department>,
    val modifierGroups: List<ModifierGroup>,
    val products: List<ProductCatalogItem>,
)
