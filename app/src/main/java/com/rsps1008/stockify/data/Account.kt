package com.rsps1008.stockify.data

import androidx.room.Entity
import androidx.room.PrimaryKey

import kotlinx.serialization.Serializable

@Entity(tableName = "accounts")
@Serializable
data class Account(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    /** Null means this account uses the shared Taiwan fee discount setting. */
    val feeDiscount: Double? = null,
    /** Null means this account uses the shared regular-lot minimum fee. */
    val minFeeRegular: Int? = null,
    /** Null means this account uses the shared odd-lot minimum fee. */
    val minFeeOddLot: Int? = null
)
