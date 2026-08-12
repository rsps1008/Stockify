package com.rsps1008.stockify

import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.requireCompleteTaiwanStockLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaiwanStockListCompletenessTest {

    @Test
    fun completeSectionsAreCombinedInExpectedOrder() {
        val listed = stock("2330")
        val otc = stock("6488")
        val emerging = stock("7777")

        val result = requireCompleteTaiwanStockLists(
            mapOf(
                "5" to listOf(emerging),
                "2" to listOf(listed),
                "4" to listOf(otc)
            )
        )

        assertEquals(listOf(listed, otc, emerging), result)
    }

    @Test
    fun missingSectionRejectsEntireList() {
        val error = assertThrows(IllegalStateException::class.java) {
            requireCompleteTaiwanStockLists(
                mapOf(
                    "2" to listOf(stock("2330")),
                    "5" to listOf(stock("7777"))
                )
            )
        }

        assertEquals("台股上櫃清單取得失敗", error.message)
    }

    @Test
    fun emptySectionRejectsEntireList() {
        val error = assertThrows(IllegalStateException::class.java) {
            requireCompleteTaiwanStockLists(
                mapOf(
                    "2" to emptyList(),
                    "4" to listOf(stock("6488")),
                    "5" to listOf(stock("7777"))
                )
            )
        }

        assertEquals("台股上市清單解析結果為空", error.message)
    }

    private fun stock(code: String) = Stock(name = code, code = code)
}
