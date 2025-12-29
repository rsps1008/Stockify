package com.rsps1008.stockify.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StockDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStocks(stocks: List<Stock>)

    @Insert
    suspend fun insertStock(stock: Stock)

    @Insert
    suspend fun insertTransaction(transaction: StockTransaction)

    @Update
    suspend fun updateTransaction(transaction: StockTransaction)

    @Delete
    suspend fun deleteTransaction(transaction: StockTransaction)

    @Query("DELETE FROM stock_transactions")
    suspend fun deleteAllTransactions()

    @Query("DELETE FROM stocks")
    suspend fun deleteAllStocks()

    @Query("SELECT * FROM stocks")
    fun getAllStocks(): Flow<List<Stock>>

    @Query("SELECT DISTINCT s.* FROM stocks s JOIN stock_transactions st ON s.code = st.股號")
    fun getHeldStocks(): Flow<List<Stock>>

    @Query("SELECT * FROM stock_transactions WHERE 股號 = :stockCode ORDER BY 日期 DESC")
    fun getTransactionsForStock(stockCode: String): Flow<List<StockTransaction>>

    @Query("SELECT * FROM stock_transactions ORDER BY 日期 DESC")
    fun getAllTransactions(): Flow<List<StockTransaction>>

    @Transaction
    @Query("SELECT * FROM stock_transactions")
    fun getTransactionsWithStock(): Flow<List<TransactionWithStock>>

    @Query("SELECT * FROM stock_transactions WHERE id = :transactionId")
    fun getTransactionById(transactionId: Int): Flow<StockTransaction?>

    @Query("SELECT * FROM stocks WHERE id = :stockId")
    fun getStockById(stockId: Int): Flow<Stock?>

    @Query("SELECT * FROM stocks WHERE code = :code LIMIT 1")
    suspend fun getStockByCode(code: String): Stock?

    @Query("SELECT * FROM stocks WHERE code = :code LIMIT 1")
    fun getStockByCodeFlow(code: String): Flow<Stock?>

    @Query("SELECT COUNT(*) FROM stocks")
    suspend fun getStocksCount(): Int

    @Query("DELETE FROM stock_transactions WHERE 股號 = :stockCode")
    suspend fun deleteTransactionsByStockCode(stockCode: String)
}