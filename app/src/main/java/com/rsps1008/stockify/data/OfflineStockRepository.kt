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
    private val settingsDataStore: SettingsDataStore
) : StockRepository {

    override fun getHoldings(): Flow<HoldingsUiState> {
        // Combine held stocks, all transactions, and real-time data to calculate holdings state
        return combine(stockDao.getHeldStocks(), stockDao.getAllTransactions(), realtimeStockDataService.realtimeStockInfo, settingsDataStore.preDeductSellFeesFlow) { stocks, transactions, realTimeData, preDeductSellFees ->
            val holdingInfos = stocks.map { stock ->
                val realtime = realTimeData[stock.code]
                val stockTransactions = transactions.filter { it.stockCode == stock.code }
                val currentPrice = realTimeData[stock.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[stock.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[stock.code]?.changePercent ?: 0.0
                val limitState = realtime?.limitState ?: LimitState.NONE
                calculateHoldingInfo(stock, stockTransactions, currentPrice, dailyChange, dailyChangePercentage, limitState, preDeductSellFees)
            }

            // Aggregate data for the summary
            val cumulativePL = holdingInfos.sumOf { it.totalPL }
            val marketValue = holdingInfos.sumOf { it.marketValue }
            val totalCost = holdingInfos.sumOf { it.averageCost * it.shares }
            val totalInvestment = holdingInfos.sumOf { it.averageCost * it.shares } // Simplified
            val cumulativePLPercentage = if (totalInvestment > 0) (cumulativePL / totalInvestment) * 100 else 0.0
            val dividendIncome = holdingInfos.sumOf { it.dividendIncome }
            val dailyPL = holdingInfos.sumOf { it.dailyChange * it.shares }

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

        return combine(stockFlow, transactionsFlow, realtimeStockDataService.realtimeStockInfo, settingsDataStore.preDeductSellFeesFlow) { stock, transactions, realTimeData, preDeductSellFees ->
            stock?.let {
                val realtime = realTimeData[stock.code]
                val currentPrice = realTimeData[it.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[it.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[it.code]?.changePercent ?: 0.0
                val limitState = realtime?.limitState ?: LimitState.NONE
                calculateHoldingInfo(it, transactions, currentPrice, dailyChange, dailyChangePercentage, limitState, preDeductSellFees)
            }
        }
    }

    override fun getTransactionsForStock(stockCode: String): Flow<List<TransactionUiState>> {
        return stockDao.getTransactionsForStock(stockCode).combine(stockDao.getStockByCodeFlow(stockCode)) { transactions, stock ->
            transactions.map { transaction ->
                TransactionUiState(
                    transaction = transaction,
                    stockName = stock?.name ?: "Unknown"
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
        preDeductSellFees: Boolean
    ): HoldingInfo {
        var shares = 0.0
        var totalBuyExpense = 0.0
        var totalSellIncome = 0.0
        var sellSharesTotal = 0.0
        var sellAmountBeforeFee = 0.0   // 成交金額（未扣費）
        var sellIncomeTotal = 0.0
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
                }
                "配股" -> {
                    shares += it.dividendShares
                }
                "配息" -> {
                    totalDividendIncome += it.income
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
        val averageCost = if (shares > 0) costBasis / shares else 0.0
        val buyAverage = if (buySharesTotal > 0) buyCostTotal / buySharesTotal else 0.0
        val marketValue = shares * currentPrice
        var totalPL = marketValue - costBasis
        val totalPLPercentage = if (costBasis > 0) (totalPL / costBasis) * 100 else 0.0

        if (preDeductSellFees) {
            val feeDiscount = settingsDataStore.feeDiscountFlow.first()
            val minFeeRegular = settingsDataStore.minFeeRegularFlow.first()

            val sellFee = (marketValue * 0.001425 * feeDiscount).coerceAtLeast(minFeeRegular.toDouble())
            val taxRate = if (stock.stockType == "ETF") 0.001 else 0.003
            val sellTax = marketValue * taxRate
            totalPL -= (sellFee + sellTax)
        }


        return HoldingInfo(
            stock = stock,
            shares = shares,
            averageCost = averageCost,
            buyAverage = buyAverage,
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
}
