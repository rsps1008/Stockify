package com.rsps1008.stockify.data

import kotlinx.serialization.Serializable

@Serializable
data class Loan(
    val id: Long,
    val name: String,
    val amount: Double
)
