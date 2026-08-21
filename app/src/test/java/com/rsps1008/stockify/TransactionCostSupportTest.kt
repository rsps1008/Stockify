package com.rsps1008.stockify

import com.rsps1008.stockify.data.TransactionCostSupport
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionCostSupportTest {
    @Test
    fun regularLotUsesRegularMinimum() {
        assertEquals(
            20.0,
            TransactionCostSupport.minimumTaiwanSellFee(1_000.0, 20.0, 1.0),
            0.0
        )
    }

    @Test
    fun oddLotUsesOddLotMinimum() {
        assertEquals(
            1.0,
            TransactionCostSupport.minimumTaiwanSellFee(10.0, 20.0, 1.0),
            0.0
        )
    }
}
