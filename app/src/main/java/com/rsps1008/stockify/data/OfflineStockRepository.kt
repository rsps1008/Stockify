package com.rsps1008.stockify.data

import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class OfflineStockRepository(
    private val stockDao: StockDao,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val settingsDataStore: SettingsDataStore,
    private val exchangeRateService: UsdTwdExchangeRateService
) : StockRepository {

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
            settingsDataStore.activeAccountIdFlow
        ) { values ->
            val stocks = values[0] as List<Stock>
            val allTransactions = values[1] as List<StockTransaction>
            val realTimeData = values[2] as Map<String, RealtimeStockInfo>
            val preDeductSellFees = values[3] as Boolean
            val usdToTwdRate = values[4] as Double
            val homeDisplayMode = values[5] as String
            val returnRateMode = values[6] as ReturnRateMode
            val activeAccountId = values[7] as Int

            val transactions = if (activeAccountId == 0) {
                allTransactions
            } else {
                allTransactions.filter { it.accountId == activeAccountId }
            }

            val mode = HomeDisplayMode.normalize(homeDisplayMode)
            val filteredStocks = when (mode) {
                HomeDisplayMode.TW -> stocks.filter { StockMarket.isTw(it.market) }
                HomeDisplayMode.US -> stocks.filter { StockMarket.isUs(it.market) }
                else -> stocks
            }.filter { stock ->
                transactions.any { it.stockCode == stock.code }
            }
            val currentDateMillis = System.currentTimeMillis()

            val holdingInfos = filteredStocks.map { stock ->
                val realtime = realTimeData[stock.code]
                val stockTransactions = transactions.filter { it.stockCode == stock.code }
                val currentPrice = realTimeData[stock.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[stock.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[stock.code]?.changePercent ?: 0.0
                val limitState = realtime?.limitState ?: LimitState.NONE
                calculateHoldingInfo(
                    stock = stock,
                    transactions = stockTransactions,
                    currentPrice = currentPrice,
                    dailyChange = dailyChange,
                    dailyChangePercentage = dailyChangePercentage,
                    limitState = limitState,
                    preDeductSellFees = preDeductSellFees,
                    returnRateMode = returnRateMode,
                    currentDateMillis = currentDateMillis
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
                    ReturnRateMode.REMAINING_POSITION -> HoldingCalculationSupport.remainingPositionDenominator(
                        shares = holding.shares,
                        costBasis = holding.averageCost * holding.shares,
                        totalInvestment = holding.totalInvestment
                    )
                    ReturnRateMode.XIRR -> holding.averageCost * holding.shares
                }
                basis * rate
            }
            val cumulativePLPercentage = when (returnRateMode) {
                ReturnRateMode.REMAINING_POSITION,
                ReturnRateMode.CUMULATIVE_INVESTMENT -> if (totalInvestment > 0) (cumulativePL / totalInvestment) * 100 else 0.0
                ReturnRateMode.XIRR -> {
                    val portfolioCashFlows = filteredStocks.flatMap { stock ->
                        val stockTransactions = transactions.filter { it.stockCode == stock.code }
                        val currentPrice = realTimeData[stock.code]?.currentPrice ?: 0.0
                        val shares = holdingInfos.firstOrNull { it.stock.code == stock.code }?.shares ?: 0.0
                        val rate = if (summaryIsCombined && StockMarket.isUs(stock.market)) usdToTwdRate else 1.0
                        buildCashFlowsForStock(stockTransactions, currentPrice, shares, currentDateMillis, rate)
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
                holding.dailyChange * holding.shares * rate
            }

            HoldingsUiState(
                holdings = holdingInfos, // Only show stocks currently held
                cumulativePL = cumulativePL,
                marketValue = marketValue,
                totalCost = totalCost,
                cumulativePLPercentage = cumulativePLPercentage,
                dividendIncome = dividendIncome,
                dailyPL = dailyPL
            )
        }
    }

    override fun getHoldingInfo(stockCode: String): Flow<HoldingInfo?> {
        val stockFlow = stockDao.getStockByCodeFlow(stockCode)
        val transactionsFlow = stockDao.getTransactionsForStock(stockCode)

        return combine(
            stockFlow,
            transactionsFlow,
            realtimeStockDataService.realtimeStockInfo,
            settingsDataStore.preDeductSellFeesFlow,
            settingsDataStore.returnRateModeFlow
        ) { stock, transactions, realTimeData, preDeductSellFees, returnRateMode ->
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
                    currentDateMillis = System.currentTimeMillis()
                )
            }
        }
    }

    override fun getTransactionsForStock(stockCode: String): Flow<List<TransactionUiState>> {
        return stockDao.getTransactionsForStock(stockCode).combine(stockDao.getStockByCodeFlow(stockCode)) { transactions, stock ->
            transactions.map { transaction ->
                TransactionUiState(
                    transaction = transaction,
                    stockName = stock?.name ?: "Unknown",
                    market = stock?.market ?: ""
                )
            }
        }
    }

    private fun adjustTransactionsForSplits(transactions: List<StockTransaction>): List<StockTransaction> {
        val chronologicallySorted = transactions.sortedBy { it.date }
        val adjustedTransactions = mutableListOf<StockTransaction>()
        var splitMultiplier = 1.0

        // Iterate backwards from the newest transaction
        for (tx in chronologicallySorted.reversed()) {
            if (tx.type == "分割") {
                if (tx.stockSplitRatio > 0) {
                    splitMultiplier *= tx.stockSplitRatio
                }
                // Don't add the split transaction itself to the calculation list
                continue
            }

            // For transactions that happened before the split, adjust their shares and prices
            if (splitMultiplier != 1.0) {
                adjustedTransactions.add(tx.copy(
                    buyShares = tx.buyShares * splitMultiplier,
                    buyPrice = tx.buyPrice / splitMultiplier,
                    sellShares = tx.sellShares * splitMultiplier,
                    sellPrice = tx.sellPrice / splitMultiplier,
                    dividendShares = tx.dividendShares * splitMultiplier,
                    exDividendShares = tx.exDividendShares * splitMultiplier,
                    exRightsShares = tx.exRightsShares * splitMultiplier,
                    sharesBeforeReduction = tx.sharesBeforeReduction * splitMultiplier,
                    sharesAfterReduction = tx.sharesAfterReduction * splitMultiplier
                    // Monetary values like fee, tax, income, expense, cashReturned are NOT adjusted
                ))
            } else {
                // No adjustment needed for transactions after the last split
                adjustedTransactions.add(tx)
            }
        }

        // Return the list in chronological order
        return adjustedTransactions.reversed()
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
        currentDateMillis: Long
    ): HoldingInfo {
        var shares = 0.0
        var totalBuyExpense = 0.0
        var totalSellIncome = 0.0
        var totalSellNetIncome = 0.0
        var sellSharesTotal = 0.0
        var sellAmountBeforeFee = 0.0   // 成交金額（未扣費）
        var totalDividendIncome = 0.0
        var buySharesTotal = 0.0
        var buyCostTotal = 0.0

        val adjustedTransactions = adjustTransactionsForSplits(transactions)

        for (it in adjustedTransactions) {
            when (it.type) {
                "買進" -> {
                    shares += it.buyShares
                    totalBuyExpense += it.expense
                    buySharesTotal += it.buyShares
                    buyCostTotal += it.expense
                }
                "賣出" -> {
                    shares -= it.sellShares
                    sellSharesTotal += it.sellShares
                    sellAmountBeforeFee += it.sellPrice * it.sellShares
                    totalSellIncome += it.income
                    totalSellNetIncome += it.income
                }
                "配股" -> {
                    shares += it.dividendShares
                }
                "配息" -> {
                    totalDividendIncome += HoldingCalculationSupport.resolveDividendIncome(it)
                }
                "減資" -> {
                    shares += it.sharesAfterReduction - it.sharesBeforeReduction
                    totalSellIncome += it.cashReturned
                }
            }
        }

        if (shares < 0) shares = 0.0
        val sellAverage = if (sellSharesTotal > 0) sellAmountBeforeFee / sellSharesTotal else 0.0
        val costBasis = totalBuyExpense - totalSellIncome - totalDividendIncome
        val totalSellFeeAndTax = (sellAmountBeforeFee - totalSellNetIncome).coerceAtLeast(0.0)
        val totalInvestment = totalBuyExpense + totalSellFeeAndTax
        val averageCost = if (shares > 0) costBasis / shares else 0.0
        val buyAverage = if (buySharesTotal > 0) buyCostTotal / buySharesTotal else 0.0
        val marketValue = shares * currentPrice
        var totalPL = marketValue - costBasis

        if (preDeductSellFees && marketValue > 0.0 && !StockMarket.isUs(stock.market)) {
            val feeDiscount = settingsDataStore.feeDiscountFlow.first()
            val minFeeRegular = settingsDataStore.minFeeRegularFlow.first()

            val sellFee = (marketValue * 0.001425 * feeDiscount).coerceAtLeast(minFeeRegular.toDouble())
            val taxRate = if (stock.stockType == "ETF") 0.001 else 0.003
            val sellTax = marketValue * taxRate
            totalPL -= (sellFee + sellTax)
        }

        val totalPLPercentage = when (returnRateMode) {
            ReturnRateMode.REMAINING_POSITION -> {
                val denominator = HoldingCalculationSupport.remainingPositionDenominator(
                    shares = shares,
                    costBasis = costBasis,
                    totalInvestment = totalInvestment
                )
                if (denominator > 0) (totalPL / denominator) * 100 else 0.0
            }
            ReturnRateMode.CUMULATIVE_INVESTMENT -> if (totalInvestment > 0) (totalPL / totalInvestment) * 100 else 0.0
            ReturnRateMode.XIRR -> {
                val cashFlows = buildCashFlowsForStock(transactions, currentPrice, shares, currentDateMillis)
                ReturnRateCalculator.calculateXirrPercentage(cashFlows) ?: 0.0
            }
        }

        return HoldingInfo(
            stock = stock,
            shares = shares,
            averageCost = averageCost,
            buyAverage = buyAverage,
            totalInvestment = totalInvestment,
            sellAverage = sellAverage,
            dividendIncome = totalDividendIncome,
            currentPrice = currentPrice,
            marketValue = marketValue,
            totalPL = totalPL,
            totalPLPercentage = totalPLPercentage,
            dailyChange = dailyChange,
            dailyChangePercentage = dailyChangePercentage,
            limitState = limitState
        )
    }

    private fun buildCashFlowsForStock(
        transactions: List<StockTransaction>,
        currentPrice: Double,
        shares: Double,
        currentDateMillis: Long,
        currencyRate: Double = 1.0
    ): List<CashFlow> {
        val adjustedTransactions = adjustTransactionsForSplits(transactions)
        val cashFlows = adjustedTransactions.mapNotNull { transaction ->
            when (transaction.type) {
                "買進" -> CashFlow(transaction.date, -transaction.expense * currencyRate)
                "賣出" -> CashFlow(transaction.date, transaction.income * currencyRate)
                "配息" -> CashFlow(
                    transaction.date,
                    HoldingCalculationSupport.resolveDividendIncome(transaction) * currencyRate
                )
                "減資" -> CashFlow(transaction.date, transaction.cashReturned * currencyRate)
                else -> null
            }
        }.toMutableList()

        if (shares > 0.0 && currentPrice > 0.0) {
            cashFlows.add(CashFlow(currentDateMillis, shares * currentPrice * currencyRate))
        }

        return cashFlows
    }
}
