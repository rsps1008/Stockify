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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceStocks(stocks: List<Stock>)

    @Update
    suspend fun updateStocks(stocks: List<Stock>)

    @Insert
    suspend fun insertStock(stock: Stock)

    @Update
    suspend fun updateStock(stock: Stock)

    @Insert
    suspend fun insertTransaction(transaction: StockTransaction)

    @Insert
    suspend fun insertTransactions(transactions: List<StockTransaction>)

    @Update
    suspend fun updateTransaction(transaction: StockTransaction): Int

    @Delete
    suspend fun deleteTransaction(transaction: StockTransaction)

    @Query("DELETE FROM stock_transactions")
    suspend fun deleteAllTransactions()

    @Query("DELETE FROM stocks")
    suspend fun deleteAllStocks()

    @Query("SELECT * FROM stocks WHERE market = :market AND code IN (:codes)")
    suspend fun getStocksByMarketAndCodes(market: String, codes: List<String>): List<Stock>

    /** Used only by import repair to find a pre-market-boundary master row. */
    @Query("SELECT * FROM stocks WHERE code IN (:codes)")
    suspend fun getStocksByCodesForImportRepair(codes: List<String>): List<Stock>

    @Query(
        """
        SELECT * FROM stocks
        WHERE code COLLATE NOCASE LIKE '%' || :likeQuery || '%' ESCAPE '\'
           OR name COLLATE NOCASE LIKE '%' || :likeQuery || '%' ESCAPE '\'
        ORDER BY CASE
            WHEN code = :query COLLATE NOCASE THEN 0
            WHEN code COLLATE NOCASE LIKE :likeQuery || '%' ESCAPE '\' THEN 1
            WHEN code COLLATE NOCASE LIKE '%' || :likeQuery || '%' ESCAPE '\' THEN 2
            WHEN name COLLATE NOCASE LIKE :likeQuery || '%' ESCAPE '\' THEN 3
            ELSE 4
        END,
        LENGTH(code),
        code,
        name
        LIMIT :limit
        """
    )
    fun searchStocks(query: String, likeQuery: String, limit: Int): Flow<List<Stock>>

    @Query("SELECT DISTINCT s.* FROM stocks s JOIN stock_transactions st ON s.code = st.股號 AND s.market = st.市場")
    fun getHeldStocks(): Flow<List<Stock>>

    @Query("SELECT * FROM stock_transactions WHERE 股號 = :stockCode AND 市場 = :market ORDER BY 日期 DESC, 紀錄時間 DESC, id DESC")
    fun getTransactionsForStock(stockCode: String, market: String): Flow<List<StockTransaction>>

    @Query("SELECT * FROM stock_transactions WHERE 股號 = :stockCode AND 市場 = :market AND 帳戶ID = :accountId ORDER BY 日期 DESC, 紀錄時間 DESC, id DESC")
    fun getTransactionsForStockAndAccount(stockCode: String, market: String, accountId: Int): Flow<List<StockTransaction>>

    @Query("SELECT * FROM stock_transactions ORDER BY 日期 DESC, 紀錄時間 DESC, id DESC")
    fun getAllTransactions(): Flow<List<StockTransaction>>

    @Query("SELECT * FROM stock_transactions WHERE 股號 IN (:stockCodes) ORDER BY 日期 DESC, 紀錄時間 DESC, id DESC")
    suspend fun getTransactionsForStockCodes(stockCodes: List<String>): List<StockTransaction>

    @Query("SELECT IFNULL(MAX(id), 0) FROM stock_transactions")
    suspend fun getMaxTransactionId(): Int

    @Query("SELECT * FROM stock_transactions WHERE 帳戶ID = :accountId ORDER BY 日期 DESC, 紀錄時間 DESC, id DESC")
    fun getTransactionsForAccount(accountId: Int): Flow<List<StockTransaction>>

    @Transaction
    @Query("""
        SELECT st.*, s.id AS stock_id, s.name AS stock_name, s.code AS stock_code,
               s.market AS stock_market, s.exchange AS stock_exchange,
               s.industry AS stock_industry, s.stockType AS stock_stockType
        FROM stock_transactions st
        JOIN stocks s ON s.code = st.股號 AND s.market = st.市場
        ORDER BY st.日期 ASC, st.紀錄時間 ASC, st.id ASC
    """)
    fun getTransactionsWithStock(): Flow<List<TransactionWithStock>>

    @Query("SELECT * FROM stock_transactions WHERE id = :transactionId")
    fun getTransactionById(transactionId: Int): Flow<StockTransaction?>

    @Query("SELECT * FROM stock_transactions WHERE 沖抵融資批次ID = :lotId AND 股號 = :stockCode AND 市場 = :market AND 帳戶ID = :accountId")
    fun getMarginRepaymentsForLot(lotId: String, stockCode: String, market: String, accountId: Int): Flow<List<StockTransaction>>

    @Query("SELECT * FROM stock_transactions WHERE (沖抵融券批次ID = :lotId OR 融券補償批次ID = :lotId) AND 股號 = :stockCode AND 市場 = :market AND 帳戶ID = :accountId")
    fun getShortDependentsForLot(lotId: String, stockCode: String, market: String, accountId: Int): Flow<List<StockTransaction>>

    @Query("SELECT * FROM stocks WHERE id = :stockId")
    fun getStockById(stockId: Int): Flow<Stock?>

    @Query("SELECT * FROM stocks WHERE code = :code AND market = :market LIMIT 1")
    suspend fun getStockByCode(code: String, market: String): Stock?

    @Query("SELECT * FROM stocks WHERE code = :code AND market = :market LIMIT 1")
    fun getStockByCodeFlow(code: String, market: String): Flow<Stock?>

    @Query("SELECT COUNT(*) FROM stocks")
    suspend fun getStocksCount(): Int

    @Query("SELECT COUNT(*) FROM stocks WHERE market = :market")
    suspend fun getStockCountByMarket(market: String): Int

    @Query("SELECT COUNT(*) FROM stocks WHERE market = :market AND exchange = :exchange")
    suspend fun getStockCountByMarketAndExchange(market: String, exchange: String): Int

    @Query("SELECT * FROM stocks WHERE market = :market")
    suspend fun getStocksByMarket(market: String): List<Stock>

    @Query("DELETE FROM stocks WHERE market = :market")
    suspend fun deleteStocksByMarket(market: String)

    @Query("DELETE FROM stocks WHERE market = :market AND code NOT IN (SELECT DISTINCT 股號 FROM stock_transactions WHERE 市場 = :market)")
    suspend fun deleteUnreferencedStocksByMarket(market: String)

    @Query("UPDATE stocks SET exchange = :exchange WHERE code = :code AND market = 'TW'")
    suspend fun updateTaiwanStockExchange(code: String, exchange: String)

    @Query("DELETE FROM stock_transactions WHERE 股號 = :stockCode AND 市場 = :market")
    suspend fun deleteTransactionsByStockCode(stockCode: String, market: String)

    @Query("DELETE FROM stock_transactions WHERE 股號 = :stockCode AND 市場 = :market AND 帳戶ID = :accountId")
    suspend fun deleteTransactionsByStockCodeAndAccountId(stockCode: String, market: String, accountId: Int)

    @Query("UPDATE stock_transactions SET 市場 = :toMarket WHERE 股號 = :stockCode AND 市場 = :fromMarket")
    suspend fun updateTransactionMarket(stockCode: String, fromMarket: String, toMarket: String)

    @Query("DELETE FROM stock_transactions WHERE 帳戶ID = :accountId")
    suspend fun deleteTransactionsByAccountId(accountId: Int)

    @Query("""
    SELECT IFNULL(
        CAST(SUM(
            CASE
                WHEN 交易 IN ('買進', '融資買進') THEN 買進股數
                WHEN 交易 = '賣出' THEN -賣出股數
                WHEN 交易 = '配股' THEN 配發股數
                ELSE 0
            END
        ) AS REAL),
        0
    )
    FROM stock_transactions
    WHERE 股號 = :stockCode AND 市場 = :market
    """)
    suspend fun getHoldingShares(stockCode: String, market: String): Double

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryPrices(prices: List<StockHistoryPrice>)

    @Query("DELETE FROM stock_history_prices")
    suspend fun deleteAllHistoryPrices()

    @Query("SELECT * FROM stock_history_prices WHERE stockCode = :stockCode AND market = :market AND date LIKE :monthPrefix || '%' ORDER BY date ASC")
    suspend fun getHistoryPricesForMonth(stockCode: String, market: String, monthPrefix: String): List<StockHistoryPrice>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getAccountCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(account: Account)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccounts(accounts: List<Account>)

    @Update
    suspend fun updateAccount(account: Account)

    @Delete
    suspend fun deleteAccount(account: Account)

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun getAllAccountsFlow(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE id = :accountId LIMIT 1")
    suspend fun getAccountById(accountId: Int): Account?

    @Query("SELECT * FROM accounts WHERE id IN (:accountIds)")
    suspend fun getAccountsByIds(accountIds: List<Int>): List<Account>

    @Query("DELETE FROM accounts")
    suspend fun deleteAllAccounts()
}
