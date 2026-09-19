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
    val feeDiscount: Double? = null
)
