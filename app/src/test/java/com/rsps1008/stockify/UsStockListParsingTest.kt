package com.rsps1008.stockify

import com.rsps1008.stockify.data.UsStockListSource
import com.rsps1008.stockify.data.parseUsStockListSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsStockListParsingTest {
    private val nasdaqSource = UsStockListSource(
        url = "unused",
        symbolColumn = "Symbol",
        label = "Nasdaq Listed"
    )
    private val otherSource = UsStockListSource(
        url = "unused",
        symbolColumn = "ACT Symbol",
        label = "Other Listed"
    )
    private val validNasdaqText = buildString {
        appendLine("Symbol|Security Name|Test Issue|ETF")
        repeat(100) { index ->
            appendLine("STOCK$index|Example $index|N|N")
        }
        appendLine("TEST|Test Issue|Y|Y")
        append("File Creation Time:|20260825")
    }

    @Test
    fun validSourceRequiresFooterAndFiltersTestIssues() {
        val stocks = parseUsStockListSource(validNasdaqText, nasdaqSource)

        assertEquals(100, stocks.size)
        assertEquals("STOCK0", stocks.first().code)
        assertEquals("Stock", stocks.first().stockType)
    }

    @Test
    fun missingRequiredColumnRejectsTheEntireSource() {
        assertThrows(IllegalStateException::class.java) {
            parseUsStockListSource(
                """
                Symbol|Test Issue|ETF
                STOCK0|N|N
                File Creation Time:|20260825
                """.trimIndent(),
                nasdaqSource
            )
        }
    }

    @Test
    fun malformedOtherListedSourceCannotBeTreatedAsAValidPartialRefresh() {
        assertThrows(IllegalStateException::class.java) {
            parseUsStockListSource(
                """
                Symbol|Security Name|Test Issue|ETF
                NYSE|NYSE Example|N|N
                File Creation Time:|20260825
                """.trimIndent(),
                otherSource
            )
        }
    }

    @Test
    fun sourceWithoutFooterIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            parseUsStockListSource(
                validNasdaqText.substringBefore("File Creation Time:"),
                nasdaqSource
            )
        }
    }

    @Test
    fun truncatedRowsCannotSatisfyTheMinimumSourceRecordCount() {
        val truncatedRows = buildString {
            appendLine("Symbol|Security Name|Test Issue|ETF")
            repeat(100) { index -> appendLine("STOCK$index") }
            append("File Creation Time:|20260825")
        }

        assertThrows(IllegalStateException::class.java) {
            parseUsStockListSource(truncatedRows, nasdaqSource)
        }
    }
}
