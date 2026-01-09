package com.rsps1008.stockify.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Entity(
    tableName = "stocks",
    indices = [Index(value = ["code"], unique = true)]
)
@Serializable
data class Stock(
    @PrimaryKey(autoGenerate = true)
    @Transient
    val id: Int = 0,
    val name: String,
    val code: String,
    val market: String = "",
    val industry: String = "",
    val stockType: String = ""
)