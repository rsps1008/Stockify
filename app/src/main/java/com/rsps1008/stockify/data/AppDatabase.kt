package com.rsps1008.stockify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

@Database(entities = [Stock::class, StockTransaction::class, StockHistoryPrice::class], version = 9, exportSchema = false)
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
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_8_9) // Add migrations
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
                    val settingsDataStore = SettingsDataStore(context)
                    val hasManualTwListUpdate = settingsDataStore.lastStockListUpdateTimeFlow.first() != null

                    syncBundledStockList(
                        context = context,
                        stockDao = stockDao,
                        market = StockMarket.TW,
                        assetName = TW_STOCKS_ASSET_NAME,
                        checksumFileName = TW_STOCKS_CHECKSUM_FILE_NAME,
                        bundledStocks = StockListRepository(context).readBundledStocks(),
                        refreshBundledCache = { StockListRepository(context).refreshBundledCacheFromAsset() },
                        skipIfManuallyUpdated = hasManualTwListUpdate
                    )

                    syncBundledStockList(
                        context = context,
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

            stockDao.replaceStocks(bundledStocks)
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
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE stock_transactions ADD COLUMN `減資比例` REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE stock_transactions ADD COLUMN `減資前股數` REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE stock_transactions ADD COLUMN `減資後股數` REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE stock_transactions ADD COLUMN `退還股款` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE stock_transactions ADD COLUMN `每股拆分` REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE stock_transactions ADD COLUMN `拆分前股數` REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE stock_transactions ADD COLUMN `拆分後股數` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE stocks ADD COLUMN `stockType` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `stock_history_prices` (`stockCode` TEXT NOT NULL, `date` TEXT NOT NULL, `price` REAL NOT NULL, PRIMARY KEY(`stockCode`, `date`))")
            }
        }
    }
}
