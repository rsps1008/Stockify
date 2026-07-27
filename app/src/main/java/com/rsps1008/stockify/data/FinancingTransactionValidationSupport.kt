package com.rsps1008.stockify.data

object FinancingTransactionValidationSupport {
    private val financingTypes = setOf("融資買進", "融資還款", "融券賣出", "買券還券", "融券補償")
    private data class LotScope(val stockCode: String, val accountId: Int, val lotId: String)

    fun usesFinancing(transaction: StockTransaction): Boolean {
        return transaction.type in financingTypes || transaction.hasFinancingReference()
    }

    fun validateFinancingMarket(transaction: StockTransaction, market: String): String? {
        if (!usesFinancing(transaction)) return null
        return if (!StockMarket.isTw(market) || !StockMarket.isTw(StockMarket.inferFromCode(transaction.stockCode))) {
            "融資融券僅支援台股"
        } else {
            null
        }
    }

    fun validate(transactions: List<StockTransaction>): String? {
        val marginLotIds = mutableSetOf<LotScope>()
        val shortLotIds = mutableSetOf<LotScope>()

        transactions.forEach { transaction ->
            validateCompanyAction(transaction)?.let { return it }
            if (!usesFinancing(transaction)) {
                return@forEach
            }
            if (!transaction.hasFiniteFinancingNumbers()) {
                return "融資融券欄位包含無效數值"
            }
            if (transaction.hasNegativeFinancialNumbers()) {
                return "融資融券的金額、股數、費率與費用不可為負數"
            }

            when (transaction.type) {
                "融資買進" -> {
                    if (transaction.hasMarginPayment() || transaction.hasAnyShortFields()) {
                        return "融資買進不可同時包含還款或融券欄位"
                    }
                    if (transaction.hasSellTradeFields() ||
                        transaction.tax != 0.0 ||
                        transaction.income != 0.0 ||
                        transaction.hasNonFinancingEventFields()
                    ) {
                        return "融資買進包含不適用的賣出、稅額、收入或公司行動欄位"
                    }
                    if (transaction.buyPrice <= 0.0 || transaction.buyShares <= 0.0 || transaction.expense <= 0.0) {
                        return "融資買進的價格、股數與支出必須大於 0"
                    }
                    if (transaction.marginPrincipal <= 0.0 || transaction.marginPrincipal > transaction.expense) {
                        return "融資本金必須大於 0 且不得超過支出"
                    }
                    if (transaction.marginSelfFunded !in 0.0..transaction.expense) {
                        return "融資自備款不可為負數或超過支出"
                    }
                    if (transaction.marginLotId.isBlank()) {
                        return "融資買進缺少批次 ID"
                    }
                    if (!marginLotIds.add(transaction.toLotScope(transaction.marginLotId))) {
                        return "融資批次 ID 重複"
                    }
                }

                "融資還款" -> {
                    if (transaction.hasMarginOpeningFields() || transaction.hasAnyShortFields()) {
                        return "融資還款不可同時包含開倉或融券欄位"
                    }
                    if (transaction.hasBuyTradeFields() ||
                        transaction.hasSellTradeFields() ||
                        transaction.fee != 0.0 ||
                        transaction.tax != 0.0 ||
                        transaction.income != 0.0 ||
                        transaction.hasNonFinancingEventFields()
                    ) {
                        return "融資還款包含不適用的買賣、費稅、收入或公司行動欄位"
                    }
                    if (transaction.marginRepayment < 0.0 || transaction.marginActualInterest < 0.0 ||
                        transaction.marginRepayment + transaction.marginActualInterest <= 0.0
                    ) {
                        return "融資還款本金與實際利息必須為非負數，且至少一項大於 0"
                    }
                    if (transaction.marginRepaymentLotId.isBlank()) {
                        return "融資還款缺少批次 ID"
                    }
                    if (!approximatelyEqual(
                            transaction.expense,
                            transaction.marginRepayment + transaction.marginActualInterest
                        )
                    ) {
                        return "融資還款支出必須等於還款本金加實際利息"
                    }
                }

                "融券賣出" -> {
                    if (transaction.hasAnyMarginFields() ||
                        transaction.hasShortCoverFields() ||
                        transaction.hasShortCompensationFields()
                    ) {
                        return "融券賣出不可同時包含融資、還券或補償欄位"
                    }
                    if (transaction.hasBuyTradeFields() ||
                        transaction.expense != 0.0 ||
                        transaction.hasNonFinancingEventFields()
                    ) {
                        return "融券賣出包含不適用的買進、支出或公司行動欄位"
                    }
                    if (transaction.sellPrice <= 0.0 || transaction.sellShares <= 0.0 ||
                        transaction.shortBorrowPrincipal <= 0.0 || transaction.income <= 0.0
                    ) {
                        return "融券賣出的價格、股數、融券本金與收入必須大於 0"
                    }
                    if (!approximatelyEqual(transaction.shortBorrowPrincipal, transaction.sellPrice * transaction.sellShares)) {
                        return "融券本金必須等於融券賣出價格乘以股數"
                    }
                    if (transaction.shortLotId.isBlank()) {
                        return "融券賣出缺少批次 ID"
                    }
                    if (!shortLotIds.add(transaction.toLotScope(transaction.shortLotId))) {
                        return "融券批次 ID 重複"
                    }
                }

                "買券還券" -> {
                    if (transaction.hasAnyMarginFields() ||
                        transaction.hasShortOpeningFields() ||
                        transaction.hasShortCompensationFields()
                    ) {
                        return "買券還券不可同時包含融資、開倉或補償欄位"
                    }
                    if (transaction.hasSellTradeFields() ||
                        transaction.tax != 0.0 ||
                        transaction.income != 0.0 ||
                        transaction.hasNonFinancingEventFields()
                    ) {
                        return "買券還券包含不適用的賣出、稅額、收入或公司行動欄位"
                    }
                    if (transaction.buyPrice <= 0.0 || transaction.buyShares <= 0.0 ||
                        transaction.shortCoverShares <= 0.0 ||
                        transaction.expense <= 0.0
                    ) {
                        return "買券還券的價格、股數與支出必須大於 0"
                    }
                    if (!approximatelyEqual(transaction.buyShares, transaction.shortCoverShares)) {
                        return "買券還券的買進股數必須等於還券股數"
                    }
                    if (transaction.shortCoverLotId.isBlank()) {
                        return "買券還券缺少批次 ID"
                    }
                }

                "融券補償" -> {
                    if (transaction.hasAnyMarginFields() ||
                        transaction.hasShortOpeningFields() ||
                        transaction.hasShortCoverFields()
                    ) {
                        return "融券補償不可同時包含融資、開倉或還券欄位"
                    }
                    if (transaction.hasBuyTradeFields() ||
                        transaction.hasSellTradeFields() ||
                        transaction.fee != 0.0 ||
                        transaction.tax != 0.0 ||
                        transaction.income != 0.0 ||
                        transaction.hasNonFinancingEventFields()
                    ) {
                        return "融券補償包含不適用的買賣、費稅、收入或公司行動欄位"
                    }
                    if (transaction.shortCompensation <= 0.0) {
                        return "融券補償金必須大於 0"
                    }
                    if (transaction.shortCompensationLotId.isBlank()) {
                        return "融券補償缺少批次 ID"
                    }
                    if (!approximatelyEqual(transaction.expense, transaction.shortCompensation)) {
                        return "融券補償支出必須等於補償金"
                    }
                }

                "賣出" -> if (transaction.hasMarginPayment()) {
                    if (transaction.hasMarginOpeningFields() || transaction.hasAnyShortFields()) {
                        return "賣出還融資不可同時包含開倉或融券欄位"
                    }
                    if (transaction.hasBuyTradeFields() ||
                        transaction.expense != 0.0 ||
                        transaction.hasNonFinancingEventFields()
                    ) {
                        return "賣出還融資包含不適用的買進、支出或公司行動欄位"
                    }
                    if (transaction.sellPrice <= 0.0 || transaction.sellShares <= 0.0 ||
                        transaction.income <= 0.0 || transaction.marginRepayment <= 0.0
                    ) {
                        return "賣出還融資的價格、股數、收入與還款本金必須大於 0"
                    }
                    if (transaction.marginRepaymentLotId.isBlank()) {
                        return "賣出還融資缺少批次 ID"
                    }
                }

                else -> return "交易類型與融資融券欄位不一致"
            }
        }
        return null
    }

    private fun validateCompanyAction(transaction: StockTransaction): String? {
        return when (transaction.type) {
            "減資" -> {
                val values = listOf(
                    transaction.capitalReductionRatio,
                    transaction.sharesBeforeReduction,
                    transaction.sharesAfterReduction,
                    transaction.cashReturned
                )
                when {
                    values.any { !it.isFinite() } -> "減資欄位包含無效數值"
                    transaction.capitalReductionRatio <= 0.0 ||
                        transaction.capitalReductionRatio >= 100.0 ->
                        "減資比例必須大於 0 且小於 100"
                    transaction.sharesBeforeReduction < 0.0 ||
                        transaction.sharesAfterReduction < 0.0 ->
                        "減資前後股數不可為負數"
                    transaction.cashReturned < 0.0 -> "減資返還現金不可為負數"
                    else -> null
                }
            }

            "分割" -> {
                val values = listOf(
                    transaction.stockSplitRatio,
                    transaction.sharesBeforeSplit,
                    transaction.sharesAfterSplit
                )
                when {
                    values.any { !it.isFinite() } -> "分割欄位包含無效數值"
                    transaction.stockSplitRatio <= 0.0 -> "分割比例必須大於 0"
                    transaction.sharesBeforeSplit < 0.0 ||
                        transaction.sharesAfterSplit < 0.0 ->
                        "分割前後股數不可為負數"
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun StockTransaction.hasFinancingReference(): Boolean {
        return marginLotId.isNotBlank() ||
            marginRepaymentLotId.isNotBlank() ||
            marginPrincipal != 0.0 ||
            marginAnnualRate != 0.0 ||
            marginRepayment != 0.0 ||
            marginSelfFunded != 0.0 ||
            marginSelfFundedOverridden ||
            marginActualInterest != 0.0 ||
            shortLotId.isNotBlank() ||
            shortCoverLotId.isNotBlank() ||
            shortBorrowPrincipal != 0.0 ||
            shortBorrowAnnualRate != 0.0 ||
            shortCoverShares != 0.0 ||
            shortCompensationLotId.isNotBlank() ||
            shortCompensation != 0.0
    }

    private fun StockTransaction.toLotScope(lotId: String): LotScope =
        LotScope(stockCode = stockCode, accountId = accountId, lotId = lotId)

    private fun StockTransaction.hasMarginPayment(): Boolean {
        return marginRepaymentLotId.isNotBlank() || marginRepayment != 0.0 || marginActualInterest != 0.0
    }

    private fun StockTransaction.hasMarginOpeningFields(): Boolean {
        return marginLotId.isNotBlank() ||
            marginPrincipal != 0.0 ||
            marginAnnualRate != 0.0 ||
            marginSelfFunded != 0.0 ||
            marginSelfFundedOverridden
    }

    private fun StockTransaction.hasAnyMarginFields(): Boolean {
        return hasMarginOpeningFields() || hasMarginPayment()
    }

    private fun StockTransaction.hasShortOpeningFields(): Boolean {
        return shortLotId.isNotBlank() ||
            shortBorrowPrincipal != 0.0 ||
            shortBorrowAnnualRate != 0.0
    }

    private fun StockTransaction.hasShortCoverFields(): Boolean {
        return shortCoverLotId.isNotBlank() || shortCoverShares != 0.0
    }

    private fun StockTransaction.hasShortCompensationFields(): Boolean {
        return shortCompensationLotId.isNotBlank() || shortCompensation != 0.0
    }

    private fun StockTransaction.hasAnyShortFields(): Boolean {
        return hasShortOpeningFields() || hasShortCoverFields() || hasShortCompensationFields()
    }

    private fun StockTransaction.hasBuyTradeFields(): Boolean {
        return buyPrice != 0.0 || buyShares != 0.0
    }

    private fun StockTransaction.hasSellTradeFields(): Boolean {
        return sellPrice != 0.0 || sellShares != 0.0
    }

    private fun StockTransaction.hasNonFinancingEventFields(): Boolean {
        return cashDividend != 0.0 ||
            exDividendShares != 0.0 ||
            stockDividend != 0.0 ||
            dividendShares != 0.0 ||
            exRightsShares != 0.0 ||
            dividendIncome != 0.0 ||
            capitalReductionRatio != 0.0 ||
            sharesBeforeReduction != 0.0 ||
            sharesAfterReduction != 0.0 ||
            cashReturned != 0.0 ||
            stockSplitRatio != 0.0 ||
            sharesBeforeSplit != 0.0 ||
            sharesAfterSplit != 0.0
    }

    private fun StockTransaction.hasFiniteFinancingNumbers(): Boolean {
        return listOf(
            buyPrice,
            buyShares,
            sellPrice,
            sellShares,
            fee,
            tax,
            income,
            expense,
            marginPrincipal,
            marginAnnualRate,
            marginRepayment,
            marginSelfFunded,
            marginActualInterest,
            shortBorrowPrincipal,
            shortBorrowAnnualRate,
            shortCoverShares,
            shortCompensation
        ).all(Double::isFinite)
    }

    private fun StockTransaction.hasNegativeFinancialNumbers(): Boolean {
        return listOf(
            buyPrice,
            buyShares,
            sellPrice,
            sellShares,
            fee,
            tax,
            income,
            expense,
            marginPrincipal,
            marginAnnualRate,
            marginRepayment,
            marginSelfFunded,
            marginActualInterest,
            shortBorrowPrincipal,
            shortBorrowAnnualRate,
            shortCoverShares,
            shortCompensation
        ).any { it < 0.0 }
    }

    private fun approximatelyEqual(left: Double, right: Double): Boolean {
        return kotlin.math.abs(left - right) <= AMOUNT_EPSILON
    }

    private const val AMOUNT_EPSILON = 1e-6
}
