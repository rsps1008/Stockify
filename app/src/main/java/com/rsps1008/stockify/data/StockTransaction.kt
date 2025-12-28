package com.rsps1008.stockify.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "stock_transactions",
    foreignKeys = [
        ForeignKey(
            entity = Stock::class,
            parentColumns = ["id"],
            childColumns = ["stockId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class StockTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0, // 流水號
    val stockId: Int, // 對應到 Stock 表的 ID
    val accountId: Int = 1, // 帳戶ID

    val date: Long, // 日期
    val recordTime: Long, // 紀錄時間

    val type: String, // 交易: 買進, 賣出, 配息, 配股

    // 買賣相關
    val price: Double = 0.0, // 買進價格 / 賣出價格
    val shares: Double = 0.0, // 買進股數 / 賣出股數

    // 成本與收益
    val fee: Double = 0.0, // 手續費
    val tax: Double = 0.0, // 交易稅
    val income: Double = 0.0,      // 收入 (股息收入或賣出所得)
    val expense: Double = 0.0,     // 支出

    // 配息相關
    val cashDividend: Double = 0.0, // 現金股利 (per share)
    val exDividendShares: Double = 0.0, // 除息股數

    // 配股相關
    val stockDividend: Double = 0.0, // 股票股利 (per share)
    val dividendShares: Double = 0.0, // 配發股數
    val exRightsShares: Double = 0.0, // 除權股數

    // 其他
    val capitalReturn: Double = 0.0, // 退還股款
    val note: String = "" // 筆記
)

data class TransactionWithStock(
    @Embedded val transaction: StockTransaction,
    @Relation(
        parentColumn = "stockId",
        entityColumn = "id"
    )
    val stock: Stock
)
