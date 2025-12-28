package com.rsps1008.stockify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader

@Database(entities = [Stock::class, StockTransaction::class], version = 1, exportSchema = false)
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
            val jsonString = context.assets.open("stocks.json").bufferedReader().use(BufferedReader::readText)
            val jsonArray = JSONArray(jsonString)
            val stocks = mutableListOf<Stock>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val code = jsonObject.getString("code")
                val name = jsonObject.getString("name")
                stocks.add(Stock(name = name, code = code))
            }
            stockDao.insertStocks(stocks)
        }
    }
}