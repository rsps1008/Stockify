package com.rsps1008.stockify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

@Database(entities = [Stock::class, StockTransaction::class, StockHistoryPrice::class, Account::class], version = 17, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun stockDao(): StockDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stock_database"
                )
                .addCallback(AppDatabaseCallback(context))
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class AppDatabaseCallback(
            private val context: Context
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                seedStockLists(context)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                seedStockLists(context)
            }
        }

        private fun seedStockLists(context: Context) {
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    val stockDao = database.stockDao()
                    if (stockDao.getAccountCount() == 0) {
                        stockDao.insertAccount(Account(id = 1, name = "預設帳戶"))
                    }
                    val settingsDataStore = SettingsDataStore(context)
                    val hasManualTwListUpdate = settingsDataStore.lastStockListUpdateTimeFlow.first() != null
                    val bundledTwStocks = StockListRepository(context).readBundledStocks()

                    // 舊版只有 market=TW，使用新版 bundled 清單補回上市/上櫃/興櫃分類。
                    bundledTwStocks.forEach { stock ->
                        if (stock.exchange.isNotBlank()) {
                            stockDao.updateTaiwanStockExchange(stock.code, stock.exchange)
                        }
                    }

                    syncBundledStockList(
                        context = context,
                        database = database,
                        stockDao = stockDao,
                        market = StockMarket.TW,
                        assetName = TW_STOCKS_ASSET_NAME,
                        checksumFileName = TW_STOCKS_CHECKSUM_FILE_NAME,
                        bundledStocks = bundledTwStocks,
                        refreshBundledCache = { StockListRepository(context).refreshBundledCacheFromAsset() },
                        skipIfManuallyUpdated = hasManualTwListUpdate
                    )

                    syncBundledStockList(
                        context = context,
                        database = database,
                        stockDao = stockDao,
                        market = StockMarket.US,
                        assetName = US_STOCKS_ASSET_NAME,
                        checksumFileName = US_STOCKS_CHECKSUM_FILE_NAME,
                        bundledStocks = UsStockListRepository(context).readStocks(),
                        refreshBundledCache = null,
                        skipIfManuallyUpdated = false
                    )
                }
            }
        }

        private suspend fun syncBundledStockList(
            context: Context,
            database: AppDatabase,
            stockDao: StockDao,
            market: String,
            assetName: String,
            checksumFileName: String,
            bundledStocks: List<Stock>,
            refreshBundledCache: (() -> Unit)?,
            skipIfManuallyUpdated: Boolean
        ) {
            val currentCount = stockDao.getStockCountByMarket(market)
            if (currentCount > 0 && skipIfManuallyUpdated) {
                return
            }

            val bundledChecksum = readAssetChecksum(context, assetName) ?: return
            val checksumFile = File(context.filesDir, checksumFileName)
            val storedChecksum = checksumFile.takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { null }
            val needsRefresh = currentCount == 0 || storedChecksum != bundledChecksum
            if (!needsRefresh || bundledStocks.isEmpty()) {
                return
            }

            database.withTransaction {
                stockDao.deleteUnreferencedStocksByMarket(market)
                stockDao.replaceStocks(bundledStocks)
            }
            refreshBundledCache?.invoke()
            checksumFile.writeText(bundledChecksum)
        }

        private fun readAssetChecksum(context: Context, assetName: String): String? {
            return runCatching {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                context.assets.open(assetName).use { inputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }

        private const val TW_STOCKS_ASSET_NAME = "stocks.json"
        private const val US_STOCKS_ASSET_NAME = "us_stocks.json"
        private const val TW_STOCKS_CHECKSUM_FILE_NAME = "stocks.json.sha256"
        private const val US_STOCKS_CHECKSUM_FILE_NAME = "us_stocks.json.sha256"

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `減資比例` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `減資前股數` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `減資後股數` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `退還股款` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `每股拆分` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `拆分前股數` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `拆分後股數` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stocks ADD COLUMN `stockType` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `stock_history_prices` (`stockCode` TEXT NOT NULL, `date` TEXT NOT NULL, `price` REAL NOT NULL, PRIMARY KEY(`stockCode`, `date`))")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO `accounts` (id, name) VALUES (1, '預設帳戶')")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stocks ADD COLUMN `exchange` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融資本金` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融資年利率` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融資批次ID` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `沖抵融資批次ID` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融資還款本金` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融券本金` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融券年費率` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融券批次ID` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `沖抵融券批次ID` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `買券還券股數` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融券補償批次ID` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融券補償金` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融資自備款` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融資實際利息` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `融資自備款是否覆寫` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_transactions ADD COLUMN `補充保費` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_stock_transactions_stock_order` ON `stock_transactions` (`股號`, `日期`, `紀錄時間`, `id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_stock_transactions_date_order` ON `stock_transactions` (`日期`, `紀錄時間`, `id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_stock_transactions_stock_account` ON `stock_transactions` (`股號`, `帳戶ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_stock_transactions_account` ON `stock_transactions` (`帳戶ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_stock_transactions_margin_repayment` ON `stock_transactions` (`沖抵融資批次ID`, `股號`, `帳戶ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_stock_transactions_short_cover` ON `stock_transactions` (`沖抵融券批次ID`, `股號`, `帳戶ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_stock_transactions_short_compensation` ON `stock_transactions` (`融券補償批次ID`, `股號`, `帳戶ID`)")
            }
        }
    }
}
