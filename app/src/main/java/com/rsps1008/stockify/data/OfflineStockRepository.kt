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
                val stockTransactions = transactions.filter { it.stockCode == stock.code }
                val currentPrice = realTimeData[stock.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[stock.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[stock.code]?.changePercent ?: 0.0
                calculateHoldingInfo(stock, stockTransactions, currentPrice, dailyChange, dailyChangePercentage, preDeductSellFees)
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
                holdings = holdingInfos.filter { it.shares > 0 }, // Only show stocks currently held
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
                val currentPrice = realTimeData[it.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[it.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[it.code]?.changePercent ?: 0.0
                calculateHoldingInfo(it, transactions, currentPrice, dailyChange, dailyChangePercentage, preDeductSellFees)
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

    private suspend fun calculateHoldingInfo(
        stock: Stock,
        transactions: List<StockTransaction>,
        currentPrice: Double,
        dailyChange: Double,
        dailyChangePercentage: Double,
        preDeductSellFees: Boolean
    ): HoldingInfo {
        var shares = 0.0
        var totalBuyExpense = 0.0
        var totalSellIncome = 0.0
        var totalDividendIncome = 0.0
        var buySharesTotal = 0.0
        var buyCostTotal = 0.0

        val sortedTransactions = transactions.sortedBy { it.date }

        for (it in sortedTransactions) {
            when (it.type) {
                "買進" -> {
                    shares += it.buyShares
                    totalBuyExpense += it.expense
                    buySharesTotal += it.buyShares
                    buyCostTotal += it.expense
                }
                "賣出" -> {
                    shares -= it.sellShares
                    totalSellIncome += it.income
                }
                "配股" -> {
                    shares += it.dividendShares
                }
                "配息" -> {
                    totalDividendIncome += it.income
                }
            }
        }

        if (shares < 0) shares = 0.0

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
            val sellTax = marketValue * 0.003
            totalPL -= (sellFee + sellTax)
        }


        return HoldingInfo(
            stock = stock,
            shares = shares,
            averageCost = averageCost,
            buyAverage = buyAverage,
            dividendIncome = totalDividendIncome,
            currentPrice = currentPrice,
            marketValue = marketValue,
            totalPL = totalPL,
            totalPLPercentage = totalPLPercentage,
            dailyChange = dailyChange,
            dailyChangePercentage = dailyChangePercentage
        )
    }
}
