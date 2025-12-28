package com.rsps1008.stockify.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.util.Date

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
    val id: Int = 0,
    val stockId: Int,
    val date: Long,
    val type: String,
    val price: Double,
    val shares: Double,
    val fee: Double,
)