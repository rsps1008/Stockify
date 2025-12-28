package com.rsps1008.stockify.data

import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class OfflineStockRepository(private val stockDao: StockDao) : StockRepository {

    override fun getHoldings(): Flow<HoldingsUiState> {
        // Combine held stocks with all transactions to calculate holdings state
        return stockDao.getHeldStocks().combine(stockDao.getAllTransactions()) { stocks, transactions ->
            val holdingInfos = stocks.map { stock ->
                val stockTransactions = transactions.filter { it.stockId == stock.id }
                calculateHoldingInfo(stock, stockTransactions)
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

    override fun getHoldingInfo(stockId: Int): Flow<HoldingInfo?> {
        val stockFlow = stockDao.getStockById(stockId)
        val transactionsFlow = stockDao.getTransactionsForStock(stockId)

        return stockFlow.combine(transactionsFlow) { stock, transactions ->
            stock?.let {
                calculateHoldingInfo(it, transactions)
            }
        }
    }

    override fun getTransactionsForStock(stockId: Int): Flow<List<TransactionUiState>> {
        return stockDao.getTransactionsForStock(stockId).combine(stockDao.getStockById(stockId)) { transactions, stock ->
            transactions.map { transaction ->
                TransactionUiState(
                    transaction = transaction,
                    stockName = stock?.name ?: "Unknown"
                )
            }
        }
    }

    private fun calculateHoldingInfo(stock: Stock, transactions: List<StockTransaction>): HoldingInfo {
        var shares = 0.0
        var totalCost = 0.0
        var buySharesTotal = 0.0
        var buyCostTotal = 0.0
        var dividendIncome = 0.0

        // Sort transactions by date to process them in order
        val sortedTransactions = transactions.sortedBy { it.date }

        for (it in sortedTransactions) {
            when (it.type) {
                "buy" -> {
                    shares += it.shares
                    val cost = it.price * it.shares + it.fee
                    totalCost += cost
                    buySharesTotal += it.shares
                    buyCostTotal += cost
                }
                "sell" -> {
                    // Reduce cost basis by the average cost of shares sold
                    if (shares > 0) {
                         val costPerShare = totalCost / shares
                         totalCost -= it.shares * costPerShare
                    }
                    shares -= it.shares
                }
                "stock_dividend" -> {
                    shares += it.shares
                }
                "dividend" -> {
                    dividendIncome += it.price // In 'dividend' type, 'price' holds the total amount
                }
            }
        }
        
        // Ensure shares are not negative from bad data
        if (shares < 0) shares = 0.0

        val averageCost = if (shares > 0 && totalCost > 0) totalCost / shares else 0.0
        val buyAverage = if (buySharesTotal > 0) buyCostTotal / buySharesTotal else 0.0
        
        // For an offline repository, current price is not available. We'll leave it at 0.
        // A full implementation would inject a real-time data fetcher.
        val currentPrice = 0.0
        val marketValue = shares * currentPrice
        
        // If we sold everything, PL is based on income vs cost. A simple proxy for this is missing.
        // Let's stick to unrealized PL for now.
        val totalPL = (marketValue - totalCost) + dividendIncome // Simplified: Includes dividend income in PL
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
            dailyChange = 0.0,
            dailyChangePercentage = 0.0
        )
    }
}