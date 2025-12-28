package com.rsps1008.stockify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rsps1008.stockify.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.BufferedReader

@Database(entities = [Stock::class, StockTransaction::class], version = 3, exportSchema = false) // Bumped version
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
                .fallbackToDestructiveMigration() // Handle migrations destructively
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            prepopulateDatabase(context, getDatabase(context).stockDao())
                        }
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }

        private suspend fun prepopulateDatabase(context: Context, stockDao: StockDao) {
            try {
                val jsonString = context.assets.open("stocks.json").bufferedReader().use(BufferedReader::readText)
                val stocks = Json.decodeFromString<List<Stock>>(jsonString)
                stockDao.insertStocks(stocks)
            } catch (e: Exception) {
                // Handle exception, e.g. file not found
            }
        }
    }
}