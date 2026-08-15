package com.rsps1008.stockify.data.dividend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YahooDividendRepositoryTest {

    @Test
    fun parseYahooDividendPage_readsCashAndStockDividendFromOnePage() {
        val html = """
            <div class="table-body">
                <ul>
                    <li>
                        <div>2025</div><div>2303</div><div>聯電</div><div></div>
                        <div>1.20</div><div>0.30</div><div>息</div><div>現金</div><div>2025/08/08</div>
                    </li>
                    <li>
                        <div>2024</div><div>2303</div><div>聯電</div><div>2024</div>
                        <div>1,000</div><div>0.45</div><div>息</div><div>現金</div><div>2024/08/08</div>
                    </li>
                </ul>
            </div>
        """.trimIndent()

        val result = parseYahooDividendPage(html)

        assertEquals(1_000.0, result.cashDividend?.amount ?: Double.NaN, 0.0)
        assertEquals("2024/08/08", result.cashDividend?.date)
        assertEquals(0.45, result.stockDividend?.amount ?: Double.NaN, 0.0)
        assertEquals("2024/08/08", result.stockDividend?.date)
    }

    @Test
    fun parseYahooDividendPage_ignoresRowsWithoutBelongingPeriod() {
        val html = """
            <div class="table-body"><ul><li>
                <div>2025</div><div>2303</div><div>聯電</div><div></div>
                <div>1.20</div><div>0.30</div><div>息</div><div>現金</div><div>2025/08/08</div>
            </li></ul></div>
        """.trimIndent()

        val result = parseYahooDividendPage(html)

        assertNull(result.cashDividend)
        assertNull(result.stockDividend)
    }
}
