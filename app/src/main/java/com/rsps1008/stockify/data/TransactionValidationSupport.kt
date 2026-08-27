package com.rsps1008.stockify.data

/**
 * Validates values that are persisted as part of a transaction.
 *
 * Replay intentionally tolerates old malformed rows so that an existing
 * database can still be displayed. New or imported rows must not add another
 * malformed row to that database.
 */
object TransactionValidationSupport {
    fun validateForWrite(transaction: StockTransaction): String? {
        if (transaction.stockCode.trim().isBlank()) {
            return "股號不可空白"
        }
        if (transaction.accountId <= 0) {
            return "帳戶 ID 必須大於 0"
        }

        val numericValues = listOf(
            transaction.buyPrice,
            transaction.buyShares,
            transaction.sellPrice,
            transaction.sellShares,
            transaction.fee,
            transaction.tax,
            transaction.income,
            transaction.expense,
            transaction.cashDividend,
            transaction.exDividendShares,
            transaction.dividendIncome,
            transaction.supplementaryHealthInsurancePremium,
            transaction.stockDividend,
            transaction.dividendShares,
            transaction.exRightsShares,
            transaction.capitalReductionRatio,
            transaction.sharesBeforeReduction,
            transaction.sharesAfterReduction,
            transaction.cashReturned,
            transaction.stockSplitRatio,
            transaction.sharesBeforeSplit,
            transaction.sharesAfterSplit,
            transaction.marginPrincipal,
            transaction.marginAnnualRate,
            transaction.marginRepayment,
            transaction.marginSelfFunded,
            transaction.marginActualInterest,
            transaction.shortBorrowPrincipal,
            transaction.shortBorrowAnnualRate,
            transaction.shortCoverShares,
            transaction.shortCompensation
        )
        if (numericValues.any { !it.isFinite() }) {
            return "交易欄位包含無效數值"
        }
        if (numericValues.any { it < 0.0 }) {
            return "交易金額、股數、費率與費用不可為負數"
        }

        return when (transaction.type) {
            "買進" -> validateBuy(transaction)
            "賣出" -> validateSell(transaction)
            "配息" -> validateDividend(transaction)
            "配股" -> validateStockDividend(transaction)
            "減資" -> validateCapitalReduction(transaction)
            "分割" -> validateSplit(transaction)
            "融資買進", "融資還款", "融券賣出", "買券還券", "融券補償" -> null
            else -> "不支援的交易類型"
        }
    }

    private fun validateBuy(transaction: StockTransaction): String? {
        if (transaction.buyPrice <= 0.0 || transaction.buyShares <= 0.0 || transaction.expense <= 0.0) {
            return "買進的價格、股數與支出必須大於 0"
        }
        if (transaction.sellPrice != 0.0 || transaction.sellShares != 0.0 ||
            transaction.tax != 0.0 || transaction.income != 0.0 ||
            hasDividendOrCorporateActionFields(transaction) ||
            transaction.hasAnyFinancingFields() || transaction.hasShortFields()
        ) {
            return "買進包含不適用的賣出、稅額、收入、公司行動或融資融券欄位"
        }
        return null
    }

    private fun validateSell(transaction: StockTransaction): String? {
        if (transaction.sellPrice <= 0.0 || transaction.sellShares <= 0.0 || transaction.income <= 0.0) {
            return "賣出的價格、股數與收入必須大於 0"
        }
        if (transaction.buyPrice != 0.0 || transaction.buyShares != 0.0 ||
            transaction.expense != 0.0 || hasDividendOrCorporateActionFields(transaction) ||
            transaction.hasShortFields() || transaction.hasMarginOpeningFields()
        ) {
            return "賣出包含不適用的買進、支出、公司行動或融資融券欄位"
        }
        return null
    }

    private fun validateDividend(transaction: StockTransaction): String? {
        if (transaction.buyPrice != 0.0 || transaction.buyShares != 0.0 ||
            transaction.sellPrice != 0.0 || transaction.sellShares != 0.0 ||
            transaction.tax != 0.0 || transaction.expense != 0.0 ||
            transaction.stockDividend != 0.0 || transaction.dividendShares != 0.0 ||
            transaction.exRightsShares != 0.0 ||
            transaction.hasAnyFinancingFields() || transaction.hasShortFields() ||
            transaction.capitalReductionRatio != 0.0 || transaction.stockSplitRatio != 0.0
        ) {
            return "配息包含不適用的買賣、稅額、支出、公司行動或融資融券欄位"
        }
        return null
    }

    private fun validateStockDividend(transaction: StockTransaction): String? {
        if (transaction.dividendShares <= 0.0) {
            return "配股股數必須大於 0"
        }
        if (transaction.buyPrice != 0.0 || transaction.buyShares != 0.0 ||
            transaction.sellPrice != 0.0 || transaction.sellShares != 0.0 ||
            transaction.fee != 0.0 || transaction.tax != 0.0 ||
            transaction.income != 0.0 || transaction.expense != 0.0 ||
            transaction.cashDividend != 0.0 || transaction.exDividendShares != 0.0 ||
            transaction.dividendIncome != 0.0 ||
            transaction.supplementaryHealthInsurancePremium != 0.0 ||
            transaction.hasAnyFinancingFields() || transaction.hasShortFields() ||
            transaction.capitalReductionRatio != 0.0 || transaction.sharesBeforeReduction != 0.0 ||
            transaction.sharesAfterReduction != 0.0 || transaction.cashReturned != 0.0 ||
            transaction.stockSplitRatio != 0.0 || transaction.sharesBeforeSplit != 0.0 ||
            transaction.sharesAfterSplit != 0.0
        ) {
            return "配股包含不適用的買賣、費稅、金額、公司行動或融資融券欄位"
        }
        return null
    }

    private fun validateCapitalReduction(transaction: StockTransaction): String? {
        if (transaction.capitalReductionRatio <= 0.0 || transaction.capitalReductionRatio >= 100.0) {
            return "減資比例必須大於 0 且小於 100"
        }
        if (transaction.buyPrice != 0.0 || transaction.buyShares != 0.0 ||
            transaction.sellPrice != 0.0 || transaction.sellShares != 0.0 ||
            transaction.fee != 0.0 || transaction.tax != 0.0 ||
            transaction.expense != 0.0 || transaction.cashDividend != 0.0 ||
            transaction.exDividendShares != 0.0 || transaction.dividendIncome != 0.0 ||
            transaction.supplementaryHealthInsurancePremium != 0.0 ||
            transaction.stockDividend != 0.0 || transaction.dividendShares != 0.0 ||
            transaction.exRightsShares != 0.0 || transaction.hasAnyFinancingFields() ||
            transaction.hasShortFields() || transaction.stockSplitRatio != 0.0 ||
            transaction.sharesBeforeSplit != 0.0 || transaction.sharesAfterSplit != 0.0
        ) {
            return "減資包含不適用的買賣、費用或融資融券欄位"
        }
        return null
    }

    private fun validateSplit(transaction: StockTransaction): String? {
        if (transaction.stockSplitRatio <= 0.0) {
            return "分割比例必須大於 0"
        }
        // Older backups may retain a manually entered fee on a split. Preserve
        // that legacy record; it does not turn the corporate action into a buy.
        if (transaction.buyPrice != 0.0 || transaction.buyShares != 0.0 ||
            transaction.sellPrice != 0.0 || transaction.sellShares != 0.0 ||
            transaction.tax != 0.0 || transaction.income != 0.0 ||
            transaction.expense != 0.0 ||
            transaction.cashDividend != 0.0 || transaction.exDividendShares != 0.0 ||
            transaction.dividendIncome != 0.0 ||
            transaction.supplementaryHealthInsurancePremium != 0.0 ||
            transaction.stockDividend != 0.0 || transaction.dividendShares != 0.0 ||
            transaction.exRightsShares != 0.0 ||
            transaction.hasAnyFinancingFields() || transaction.hasShortFields() ||
            transaction.capitalReductionRatio != 0.0 || transaction.sharesBeforeReduction != 0.0 ||
            transaction.sharesAfterReduction != 0.0 || transaction.cashReturned != 0.0
        ) {
            return "分割包含不適用的買賣、稅、金額或融資融券欄位"
        }
        return null
    }

    private fun hasDividendOrCorporateActionFields(transaction: StockTransaction): Boolean {
        return transaction.cashDividend != 0.0 ||
            transaction.exDividendShares != 0.0 ||
            transaction.stockDividend != 0.0 ||
            transaction.dividendShares != 0.0 ||
            transaction.exRightsShares != 0.0 ||
            transaction.dividendIncome != 0.0 ||
            transaction.supplementaryHealthInsurancePremium != 0.0 ||
            transaction.capitalReductionRatio != 0.0 ||
            transaction.sharesBeforeReduction != 0.0 ||
            transaction.sharesAfterReduction != 0.0 ||
            transaction.cashReturned != 0.0 ||
            transaction.stockSplitRatio != 0.0 ||
            transaction.sharesBeforeSplit != 0.0 ||
            transaction.sharesAfterSplit != 0.0
    }

    private fun StockTransaction.hasAnyFinancingFields(): Boolean {
        return marginLotId.isNotBlank() || marginRepaymentLotId.isNotBlank() ||
            marginPrincipal != 0.0 || marginAnnualRate != 0.0 ||
            marginRepayment != 0.0 || marginSelfFunded != 0.0 ||
            marginSelfFundedOverridden || marginActualInterest != 0.0
    }

    private fun StockTransaction.hasMarginOpeningFields(): Boolean {
        return marginLotId.isNotBlank() ||
            marginPrincipal != 0.0 ||
            marginAnnualRate != 0.0 ||
            marginSelfFunded != 0.0 ||
            marginSelfFundedOverridden
    }

    private fun StockTransaction.hasShortFields(): Boolean {
        return shortLotId.isNotBlank() || shortCoverLotId.isNotBlank() ||
            shortCoverShares != 0.0 || shortCompensationLotId.isNotBlank() ||
            shortCompensation != 0.0 || shortBorrowPrincipal != 0.0 ||
            shortBorrowAnnualRate != 0.0
    }
}
