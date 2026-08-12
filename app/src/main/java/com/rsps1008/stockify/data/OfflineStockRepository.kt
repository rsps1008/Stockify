package com.rsps1008.stockify.data

import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.AssetStockValue
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class OfflineStockRepository(
    private val stockDao: StockDao,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val settingsDataStore: SettingsDataStore,
    private val exchangeRateService: UsdTwdExchangeRateService
) : StockRepository {

    @Suppress("UNCHECKED_CAST")
    override fun getHoldings(): Flow<HoldingsUiState> {
        // Combine held stocks, all transactions, and real-time data to calculate holdings state
        return combine(
            stockDao.getHeldStocks(),
            stockDao.getAllTransactions(),
            realtimeStockDataService.realtimeStockInfo,
            settingsDataStore.preDeductSellFeesFlow,
            exchangeRateService.usdToTwdRate,
            settingsDataStore.homeDisplayModeFlow,
            settingsDataStore.returnRateModeFlow,
            settingsDataStore.marginDayCountFlow,
            settingsDataStore.activeAccountIdFlow
        ) { values ->
            val stocks = values[0] as List<Stock>
            val allTransactions = values[1] as List<StockTransaction>
            val realTimeData = values[2] as Map<String, RealtimeStockInfo>
            val preDeductSellFees = values[3] as Boolean
            val usdToTwdRate = values[4] as Double
            val homeDisplayMode = values[5] as String
            val returnRateMode = values[6] as ReturnRateMode
            val marginDayCount = values[7] as Int
            val activeAccountId = values[8] as Int

            val transactions = if (activeAccountId == 0) {
                allTransactions
            } else {
                allTransactions.filter { it.accountId == activeAccountId }
            }

            val currentDateMillis = System.currentTimeMillis()
            val transactionsByStock = transactions.groupBy { it.stockCode }
            val mode = HomeDisplayMode.normalize(homeDisplayMode)
            val transactedStocks = stocks.filter { stock ->
                transactionsByStock[stock.code]?.any { it.date <= currentDateMillis } == true
            }

            val filteredStocks = when (mode) {
                HomeDisplayMode.TW -> transactedStocks.filter { StockMarket.isTw(it.market) }
                HomeDisplayMode.US -> transactedStocks.filter { StockMarket.isUs(it.market) }
                else -> transactedStocks
            }

            suspend fun calculateHoldingInfoFor(stock: Stock): HoldingInfo {
                val realtime = realTimeData[stock.code]
                val stockTransactions = transactionsByStock[stock.code].orEmpty()
                val currentPrice = realTimeData[stock.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[stock.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[stock.code]?.changePercent ?: 0.0
                val limitState = realtime?.limitState ?: LimitState.NONE
                return calculateHoldingInfo(
                    stock = stock,
                    transactions = stockTransactions,
                    currentPrice = currentPrice,
                    dailyChange = dailyChange,
                    dailyChangePercentage = dailyChangePercentage,
                    limitState = limitState,
                    preDeductSellFees = preDeductSellFees,
                    returnRateMode = returnRateMode,
                    currentDateMillis = currentDateMillis,
                    marginDayCount = marginDayCount
                )
            }

            val allHoldingInfos = mutableListOf<HoldingInfo>()
            for (stock in transactedStocks) {
                allHoldingInfos += calculateHoldingInfoFor(stock)
            }
            val holdingInfos = allHoldingInfos.filter { holding ->
                when (mode) {
                    HomeDisplayMode.TW -> StockMarket.isTw(holding.stock.market)
                    HomeDisplayMode.US -> StockMarket.isUs(holding.stock.market)
                    else -> true
                }
            }

            val taiwanMarketValue = allHoldingInfos
                .filter { StockMarket.isTw(it.stock.market) }
                .sumOf { it.marketValue }
            val usMarketValue = allHoldingInfos
                .filter { StockMarket.isUs(it.stock.market) }
                .sumOf { it.marketValue * usdToTwdRate }
            val assetStockValues = allHoldingInfos.map { holding ->
                AssetStockValue(
                    stock = holding.stock,
                    marketValue = holding.marketValue * if (StockMarket.isUs(holding.stock.market)) usdToTwdRate else 1.0
                )
            }

            val summaryIsCombined = mode == HomeDisplayMode.COMBINED
            val cumulativePL = holdingInfos.sumOf { holding ->
                val rate = if (summaryIsCombined && StockMarket.isUs(holding.stock.market)) usdToTwdRate else 1.0
                holding.totalPL * rate
            }
            val marketValue = holdingInfos.sumOf { holding ->
                val rate = if (summaryIsCombined && StockMarket.isUs(holding.stock.market)) usdToTwdRate else 1.0
                holding.marketValue * rate
            }
            val totalCost = holdingInfos.sumOf { holding ->
                val rate = if (summaryIsCombined && StockMarket.isUs(holding.stock.market)) usdToTwdRate else 1.0
                (holding.averageCost * holding.shares) * rate
            }
            val totalInvestment = holdingInfos.sumOf { holding ->
                val rate = if (summaryIsCombined && StockMarket.isUs(holding.stock.market)) usdToTwdRate else 1.0
                val basis = when (returnRateMode) {
                    ReturnRateMode.CUMULATIVE_INVESTMENT -> holding.totalInvestment
                    ReturnRateMode.REMAINING_POSITION -> holding.remainingPositionInvestment
                    ReturnRateMode.XIRR -> holding.averageCost * holding.shares
                }
                basis * rate
            }
            val cumulativePLPercentage = when (returnRateMode) {
                ReturnRateMode.REMAINING_POSITION,
                ReturnRateMode.CUMULATIVE_INVESTMENT -> if (totalInvestment > 0) (cumulativePL / totalInvestment) * 100 else 0.0
                ReturnRateMode.XIRR -> {
                    val portfolioCashFlows = filteredStocks.flatMap { stock ->
                        val stockTransactions = transactionsByStock[stock.code].orEmpty()
                        val currentPrice = realTimeData[stock.code]?.currentPrice ?: 0.0
                        val shares = holdingInfos.firstOrNull { it.stock.code == stock.code }?.shares ?: 0.0
                        val rate = if (summaryIsCombined && StockMarket.isUs(stock.market)) usdToTwdRate else 1.0
                buildCashFlowsForStock(stockTransactions, currentPrice, shares, currentDateMillis, rate, marginDayCount)
                    }
                    ReturnRateCalculator.calculateXirrPercentage(portfolioCashFlows) ?: 0.0
                }
            }
            val dividendIncome = holdingInfos.sumOf { holding ->
                val rate = if (summaryIsCombined && StockMarket.isUs(holding.stock.market)) usdToTwdRate else 1.0
                holding.dividendIncome * rate
            }
            val dailyPL = holdingInfos.sumOf { holding ->
                val rate = if (summaryIsCombined && StockMarket.isUs(holding.stock.market)) usdToTwdRate else 1.0
                holding.dailyChange * (holding.shares - holding.shortOutstandingShares) * rate
            }

            HoldingsUiState(
                holdings = holdingInfos, // Only show stocks currently held
                cumulativePL = cumulativePL,
                marketValue = marketValue,
                taiwanMarketValue = taiwanMarketValue,
                usMarketValue = usMarketValue,
                totalCost = totalCost,
                cumulativePLPercentage = cumulativePLPercentage,
                dividendIncome = dividendIncome,
                assetStockValues = assetStockValues,
                dailyPL = dailyPL
            )
        }.flowOn(Dispatchers.Default)
    }

    override fun getHoldingInfo(stockCode: String, accountId: Int): Flow<HoldingInfo?> {
        val stockFlow = stockDao.getStockByCodeFlow(stockCode)
        val transactionsFlow = stockDao.getTransactionsForStock(stockCode).map { transactions ->
            if (accountId == 0) transactions else transactions.filter { it.accountId == accountId }
        }

        return combine(
            stockFlow,
            transactionsFlow,
            realtimeStockDataService.realtimeStockInfo,
            settingsDataStore.preDeductSellFeesFlow,
            settingsDataStore.returnRateModeFlow,
            settingsDataStore.marginDayCountFlow
        ) { values ->
            val stock = values[0] as Stock?
            val transactions = values[1] as List<StockTransaction>
            val realTimeData = values[2] as Map<String, RealtimeStockInfo>
            val preDeductSellFees = values[3] as Boolean
            val returnRateMode = values[4] as ReturnRateMode
            val marginDayCount = values[5] as Int
            stock?.let {
                val realtime = realTimeData[stock.code]
                val currentPrice = realTimeData[it.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[it.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[it.code]?.changePercent ?: 0.0
                val limitState = realtime?.limitState ?: LimitState.NONE
                calculateHoldingInfo(
                    stock = it,
                    transactions = transactions,
                    currentPrice = currentPrice,
                    dailyChange = dailyChange,
                    dailyChangePercentage = dailyChangePercentage,
                    limitState = limitState,
                    preDeductSellFees = preDeductSellFees,
                    returnRateMode = returnRateMode,
                    currentDateMillis = System.currentTimeMillis(),
                    marginDayCount = marginDayCount
                )
            }
        }
    }

    override fun getTransactionsForStock(stockCode: String, accountId: Int): Flow<List<TransactionUiState>> {
        return stockDao.getTransactionsForStock(stockCode)
            .map { transactions ->
                if (accountId == 0) transactions else transactions.filter { it.accountId == accountId }
            }
            .combine(stockDao.getStockByCodeFlow(stockCode)) { transactions, stock ->
            transactions.map { transaction ->
                TransactionUiState(
                    transaction = transaction,
                    stockName = stock?.name ?: "Unknown",
                    market = stock?.market ?: ""
                )
            }
        }
    }

    private suspend fun calculateHoldingInfo(
        stock: Stock,
        transactions: List<StockTransaction>,
        currentPrice: Double,
        dailyChange: Double,
        dailyChangePercentage: Double,
        limitState: LimitState,
        preDeductSellFees: Boolean,
        returnRateMode: ReturnRateMode,
        currentDateMillis: Long,
        marginDayCount: Int
    ): HoldingInfo {
        val effectiveTransactions = HoldingCalculationSupport.transactionsAtOrBefore(
            transactions,
            currentDateMillis
        )
        val replay = HoldingCalculationSupport.replayLongPosition(
            effectiveTransactions,
            currentDateMillis
        )
        val shares = replay.shares
        val totalBuyExpense = replay.totalBuyExpense
        val totalSellIncome = replay.totalSellIncome
        val totalSellNetIncome = replay.totalSellNetIncome
        val sellSharesTotal = replay.sellSharesTotal
        val sellAmountBeforeFee = replay.sellAmountBeforeFee
        val totalDividendIncome = replay.totalDividendIncome
        val buySharesTotal = replay.buySharesTotal
        val buyCostTotal = replay.buyCostTotal
        val sellAverage = if (sellSharesTotal > 0) sellAmountBeforeFee / sellSharesTotal else 0.0
        val costBasis = totalBuyExpense - totalSellIncome - totalDividendIncome
        val totalSellFeeAndTax = (sellAmountBeforeFee - totalSellNetIncome).coerceAtLeast(0.0)
        val totalInvestment = totalBuyExpense + totalSellFeeAndTax
        val averageCost = if (shares > 0) costBasis / shares else 0.0
        val buyAverage = if (buySharesTotal > 0) buyCostTotal / buySharesTotal else 0.0
        val marketValue = shares * currentPrice
        val marginSummary = MarginCalculationSupport.calculate(effectiveTransactions, currentDateMillis, marginDayCount)
        val shortSummary = ShortSellingCalculationSupport.calculate(effectiveTransactions, currentDateMillis, marginDayCount)
        val shortMarketLiability = shortSummary.outstandingShares * currentPrice
        val hasMarginPurchase = effectiveTransactions.any { it.type == "融資買進" }
        val longInvestment = if (hasMarginPurchase) {
            marginSummary.selfFundedCapital + totalSellFeeAndTax
        } else {
            totalInvestment
        }
        val investmentBasis = HoldingCalculationSupport.positionInvestmentBasis(
            shares = shares,
            costBasis = costBasis,
            longInvestment = longInvestment,
            financedRemainingInvestment = if (hasMarginPurchase) {
                (-marginSummary.cashBalance).coerceAtLeast(0.0)
            } else {
                null
            },
            marginDebt = marginSummary.outstandingPrincipal + marginSummary.accruedInterest,
            shortOutstandingShares = shortSummary.outstandingShares,
            shortRemainingInvestment = shortSummary.openedPrincipal,
            shortCumulativeInvestment = shortSummary.cumulativeOpenedPrincipal
        )
        var totalPL = marketValue - costBasis

        if (preDeductSellFees && marketValue > 0.0 && !StockMarket.isUs(stock.market)) {
            val feeDiscount = settingsDataStore.feeDiscountFlow.first()
            val minFeeRegular = settingsDataStore.minFeeRegularFlow.first()

            val sellFee = (marketValue * 0.001425 * feeDiscount).coerceAtLeast(minFeeRegular.toDouble())
            val taxRate = if (stock.stockType == "ETF") 0.001 else 0.003
            val sellTax = marketValue * taxRate
            totalPL -= (sellFee + sellTax)
        }

        val shortIncome = effectiveTransactions.filter { it.type == "融券賣出" }.sumOf { it.income }
        val shortCoverExpense = effectiveTransactions.filter { it.type == "買券還券" }.sumOf { it.expense }
        totalPL += shortIncome - shortCoverExpense - shortMarketLiability - shortSummary.accruedBorrowFee - shortSummary.compensationExpense
        totalPL -= marginSummary.totalInterestExpense

        val totalPLPercentage = when (returnRateMode) {
            ReturnRateMode.REMAINING_POSITION -> {
                val denominator = investmentBasis.remaining
                if (denominator > 0) (totalPL / denominator) * 100 else 0.0
            }
            ReturnRateMode.CUMULATIVE_INVESTMENT -> {
                val denominator = investmentBasis.cumulative
                if (denominator > 0) (totalPL / denominator) * 100 else 0.0
            }
            ReturnRateMode.XIRR -> {
                val cashFlows = buildCashFlowsForStock(
                    effectiveTransactions,
                    currentPrice,
                    shares,
                    currentDateMillis,
                    marginDayCount = marginDayCount
                )
                ReturnRateCalculator.calculateXirrPercentage(cashFlows) ?: 0.0
            }
        }

        return HoldingInfo(
            stock = stock,
            shares = shares,
            averageCost = averageCost,
            buyAverage = buyAverage,
            totalInvestment = investmentBasis.cumulative,
            remainingPositionInvestment = investmentBasis.remaining,
            sellAverage = sellAverage,
            dividendIncome = totalDividendIncome,
            currentPrice = currentPrice,
            marketValue = marketValue,
            totalPL = totalPL,
            totalPLPercentage = totalPLPercentage,
            dailyChange = dailyChange,
            dailyChangePercentage = dailyChangePercentage,
            limitState = limitState
            ,marginOutstandingPrincipal = marginSummary.outstandingPrincipal
            ,marginAccruedInterest = marginSummary.accruedInterest
            ,marginNetEquity = marketValue - marginSummary.outstandingPrincipal - marginSummary.accruedInterest
            ,shortOutstandingShares = shortSummary.outstandingShares
            ,shortMarketLiability = shortMarketLiability
            ,shortAccruedBorrowFee = shortSummary.accruedBorrowFee
            ,shortCompensationExpense = shortSummary.compensationExpense
        )
    }

    private fun buildCashFlowsForStock(
        transactions: List<StockTransaction>,
        currentPrice: Double,
        shares: Double,
        currentDateMillis: Long,
        currencyRate: Double = 1.0,
        marginDayCount: Int = 365
    ): List<CashFlow> {
        val effectiveTransactions = HoldingCalculationSupport.transactionsAtOrBefore(
            transactions,
            currentDateMillis
        )
        val cashFlows = effectiveTransactions.mapNotNull { transaction ->
            when (transaction.type) {
                "買進" -> CashFlow(transaction.date, -transaction.expense * currencyRate)
                "融資買進" -> CashFlow(transaction.date, -(if (transaction.marginSelfFundedOverridden) transaction.marginSelfFunded else transaction.expense - transaction.marginPrincipal) * currencyRate)
                "賣出" -> CashFlow(transaction.date, (transaction.income - transaction.marginRepayment - transaction.marginActualInterest) * currencyRate)
                "融資還款" -> CashFlow(transaction.date, -(transaction.marginRepayment + transaction.marginActualInterest) * currencyRate)
                "配息" -> CashFlow(
                    transaction.date,
                    HoldingCalculationSupport.resolveDividendIncome(transaction) * currencyRate
                )
                "減資" -> CashFlow(transaction.date, transaction.cashReturned * currencyRate)
                else -> null
            }
        }.toMutableList()

        val margin = MarginCalculationSupport.calculate(
            effectiveTransactions,
            currentDateMillis,
            marginDayCount
        )
        cashFlows += ShortSellingCalculationSupport.buildXirrCashFlows(
            transactions = effectiveTransactions,
            valuationDate = currentDateMillis,
            currentPrice = currentPrice,
            dayCount = marginDayCount
        ).map { it.copy(amount = it.amount * currencyRate) }

        val hasValuedPosition = shares > 0.0 && currentPrice > 0.0
        val hasMarginDebt = margin.outstandingPrincipal > 0.0 || margin.accruedInterest > 0.0
        if (hasValuedPosition || hasMarginDebt) {
            cashFlows.add(CashFlow(currentDateMillis, (shares * currentPrice - margin.outstandingPrincipal - margin.accruedInterest) * currencyRate))
        }

        return cashFlows
    }
}
