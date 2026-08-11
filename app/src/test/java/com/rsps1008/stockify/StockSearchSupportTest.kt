package com.rsps1008.stockify

import com.rsps1008.stockify.ui.viewmodel.escapeStockSearchLikePattern
import org.junit.Assert.assertEquals
import org.junit.Test

class StockSearchSupportTest {

    @Test
    fun plainSearchTextIsUnchanged() {
        assertEquals("2330", escapeStockSearchLikePattern("2330"))
        assertEquals("台積電", escapeStockSearchLikePattern("台積電"))
    }

    @Test
    fun sqliteLikeWildcardsAreTreatedAsLiteralCharacters() {
        assertEquals(
            "A\\%B\\_C\\\\D",
            escapeStockSearchLikePattern("A%B_C\\D")
        )
    }
}
