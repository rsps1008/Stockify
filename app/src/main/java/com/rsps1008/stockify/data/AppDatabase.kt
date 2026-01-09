package com.rsps1008.stockify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Stock::class, StockTransaction::class], version = 5, exportSchema = false)
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
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5) // Add migrations
                .build()
                INSTANCE = instance
                instance
            }
        }

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
    }
}
