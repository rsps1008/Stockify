package com.rsps1008.stockify.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "stock_transactions")
data class StockTransaction(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,

    @ColumnInfo(name = "股號")
    val stockCode: String,

    @ColumnInfo(name = "帳戶ID")
    val accountId: Int = 1,

    @ColumnInfo(name = "日期")
    val date: Long,

    @ColumnInfo(name = "紀錄時間")
    val recordTime: Long,

    @ColumnInfo(name = "交易")
    val type: String,

    @ColumnInfo(name = "價格")
    val price: Double = 0.0,

    @ColumnInfo(name = "股數")
    val shares: Double = 0.0,

    @ColumnInfo(name = "手續費")
    val fee: Double = 0.0,

    @ColumnInfo(name = "交易稅")
    val tax: Double = 0.0,

    @ColumnInfo(name = "收入")
    val income: Double = 0.0,

    @ColumnInfo(name = "支出")
    val expense: Double = 0.0,

    @ColumnInfo(name = "現金股利")
    val cashDividend: Double = 0.0,

    @ColumnInfo(name = "除息股數")
    val exDividendShares: Double = 0.0,

    @ColumnInfo(name = "股息收入")
    val dividendIncome: Double = 0.0,

    @ColumnInfo(name = "股票股利")
    val stockDividend: Double = 0.0,

    @ColumnInfo(name = "配發股數")
    val dividendShares: Double = 0.0,

    @ColumnInfo(name = "除權股數")
    val exRightsShares: Double = 0.0,

    @ColumnInfo(name = "退還股款")
    val capitalReturn: Double = 0.0,

    @ColumnInfo(name = "筆記")
    val note: String = ""
)

data class TransactionWithStock(
    @Embedded val transaction: StockTransaction,
    @Relation(
        parentColumn = "股號",
        entityColumn = "code"
    )
    val stock: Stock
)
