package com.rsps1008.stockify.data

import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class OfflineStockRepository(
    private val stockDao: StockDao,
    private val realtimeStockDataService: RealtimeStockDataService
) : StockRepository {

    override fun getHoldings(): Flow<HoldingsUiState> {
        // Combine held stocks, all transactions, and real-time data to calculate holdings state
        return combine(stockDao.getHeldStocks(), stockDao.getAllTransactions(), realtimeStockDataService.realtimeStockInfo) { stocks, transactions, realTimeData ->
            val holdingInfos = stocks.map { stock ->
                val stockTransactions = transactions.filter { it.stockCode == stock.code }
                val currentPrice = realTimeData[stock.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[stock.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[stock.code]?.changePercent ?: 0.0
                calculateHoldingInfo(stock, stockTransactions, currentPrice, dailyChange, dailyChangePercentage)
            }

            // Aggregate data for the summary
            val cumulativePL = holdingInfos.sumOf { it.totalPL }
            val marketValue = holdingInfos.sumOf { it.marketValue }
            val totalInvestment = holdingInfos.sumOf { it.averageCost * it.shares } // Simplified
            val cumulativePLPercentage = if (totalInvestment > 0) (cumulativePL / totalInvestment) * 100 else 0.0
            val dividendIncome = holdingInfos.sumOf { it.dividendIncome }
            val dailyPL = holdingInfos.sumOf { it.dailyChange * it.shares }

            HoldingsUiState(
                holdings = holdingInfos.filter { it.shares > 0 }, // Only show stocks currently held
                cumulativePL = cumulativePL,
                marketValue = marketValue,
                cumulativePLPercentage = cumulativePLPercentage,
                dividendIncome = dividendIncome,
                dailyPL = dailyPL
            )
        }
    }

    override fun getHoldingInfo(stockCode: String): Flow<HoldingInfo?> {
        val stockFlow = stockDao.getStockByCodeFlow(stockCode)
        val transactionsFlow = stockDao.getTransactionsForStock(stockCode)

        return combine(stockFlow, transactionsFlow, realtimeStockDataService.realtimeStockInfo) { stock, transactions, realTimeData ->
            stock?.let {
                val currentPrice = realTimeData[it.code]?.currentPrice ?: 0.0
                val dailyChange = realTimeData[it.code]?.change ?: 0.0
                val dailyChangePercentage = realTimeData[it.code]?.changePercent ?: 0.0
                calculateHoldingInfo(it, transactions, currentPrice, dailyChange, dailyChangePercentage)
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

    private fun calculateHoldingInfo(
        stock: Stock,
        transactions: List<StockTransaction>,
        currentPrice: Double,
        dailyChange: Double,
        dailyChangePercentage: Double
    ): HoldingInfo {
        var shares = 0.0
        var totalCost = 0.0
        var buySharesTotal = 0.0
        var buyCostTotal = 0.0
        var dividendIncome = 0.0

        val sortedTransactions = transactions.sortedBy { it.date }

        for (it in sortedTransactions) {
            when (it.type) {
                "買進" -> {
                    shares += it.shares
                    val cost = it.price * it.shares + it.fee
                    totalCost += cost
                    buySharesTotal += it.shares
                    buyCostTotal += cost
                }
                "賣出" -> {
                    if (shares > 0) {
                        val costPerShare = totalCost / shares
                        totalCost -= it.shares * costPerShare
                    }
                    shares -= it.shares
                }
                "配股" -> {
                    shares += it.shares
                }
                "配息" -> {
                    dividendIncome += it.income
                }
            }
        }

        if (shares < 0) shares = 0.0

        val averageCost = if (shares > 0 && totalCost > 0) totalCost / shares else 0.0
        val buyAverage = if (buySharesTotal > 0) buyCostTotal / buySharesTotal else 0.0
        val marketValue = shares * currentPrice
        val totalPL = (marketValue - totalCost) + dividendIncome
        val totalPLPercentage = if (totalCost > 0) (totalPL / totalCost) * 100 else 0.0

        return HoldingInfo(
            stock = stock,
            shares = shares,
            averageCost = averageCost,
            buyAverage = buyAverage,
            dividendIncome = dividendIncome,
            currentPrice = currentPrice,
            marketValue = marketValue,
            totalPL = totalPL,
            totalPLPercentage = totalPLPercentage,
            dailyChange = dailyChange,
            dailyChangePercentage = dailyChangePercentage
        )
    }
}
