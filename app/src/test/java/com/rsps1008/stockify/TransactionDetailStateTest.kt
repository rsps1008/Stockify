package com.rsps1008.stockify

import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.viewmodel.TransactionDetailState
import com.rsps1008.stockify.ui.viewmodel.buildTransactionDetailState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionDetailStateTest {

    @Test
    fun missingTransactionProducesExplicitMissingState() {
        val state = buildTransactionDetailState(transaction = null, stock = null)

        assertEquals(TransactionDetailState.Missing, state)
    }

    @Test
    fun existingTransactionAndStockProduceReadyState() {
        val transaction = StockTransaction(
            id = 7,
            stockCode = "2330",
            market = "TW",
            date = 1L,
            recordTime = 1L,
            type = "買進"
        )
        val stock = Stock(name = "台積電", code = "2330", market = "TW")

        val state = buildTransactionDetailState(transaction, stock)

        assertTrue(state is TransactionDetailState.Ready)
        assertEquals(transaction, (state as TransactionDetailState.Ready).value.transaction)
        assertEquals("台積電", state.value.stockName)
    }
}
