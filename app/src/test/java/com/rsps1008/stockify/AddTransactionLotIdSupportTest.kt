package com.rsps1008.stockify

import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.viewmodel.resolveMarginOpeningLotId
import com.rsps1008.stockify.ui.viewmodel.resolveShortOpeningLotId
import com.rsps1008.stockify.ui.viewmodel.transactionFeeForType
import com.rsps1008.stockify.ui.viewmodel.transactionsBeforeCandidateForLotSelection
import com.rsps1008.stockify.ui.viewmodel.transactionsWithCandidateForValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddTransactionLotIdSupportTest {
    @Test
    fun openingTransactionsPreserveExistingLotIdsOrCreateMissingIds() {
        assertEquals("margin-existing", resolveMarginOpeningLotId("融資買進", "margin-existing"))
        assertEquals("short-existing", resolveShortOpeningLotId("融券賣出", "short-existing"))
        assertTrue(resolveMarginOpeningLotId("融資買進", "").isNotBlank())
        assertTrue(resolveShortOpeningLotId("融券賣出", "").isNotBlank())
    }

    @Test
    fun regularTransactionsDoNotCreateFinancingLotIds() {
        assertEquals("", resolveMarginOpeningLotId("買進", ""))
        assertEquals("", resolveShortOpeningLotId("賣出", ""))
    }

    @Test
    fun transactionTypesWithoutAFeeFieldCannotKeepAStaleTradeFee() {
        assertEquals(25.0, transactionFeeForType("融資買進", 25.0, 10.0), 0.0)
        assertEquals(25.0, transactionFeeForType("融券賣出", 25.0, 10.0), 0.0)
        assertEquals(25.0, transactionFeeForType("買券還券", 25.0, 10.0), 0.0)
        assertEquals(10.0, transactionFeeForType("配息", 25.0, 10.0), 0.0)
        assertEquals(0.0, transactionFeeForType("融資還款", 25.0, 10.0), 0.0)
        assertEquals(0.0, transactionFeeForType("融券補償", 25.0, 10.0), 0.0)
    }

    @Test
    fun newCandidateWithEqualTimestampIsValidatedAfterExistingRows() {
        val opening = StockTransaction(
            id = 10,
            stockCode = "2330",
            accountId = 1,
            date = 1_000L,
            recordTime = 2_000L,
            type = "融資買進",
            marginPrincipal = 100_000.0,
            marginLotId = "margin-lot"
        )
        val repayment = StockTransaction(
            stockCode = "2330",
            accountId = 1,
            date = 1_000L,
            recordTime = 2_000L,
            type = "融資還款",
            marginRepaymentLotId = "margin-lot",
            marginRepayment = 20_000.0
        )

        val replay = transactionsWithCandidateForValidation(listOf(opening), repayment)

        assertTrue(MarginCalculationSupport.hasValidRepaymentBalances(replay))
        assertTrue(replay.last().id > opening.id)
    }

    @Test
    fun lotSelectionReplaysOnlyTransactionsBeforeTheEditedCandidate() {
        val opening = StockTransaction(
            id = 1,
            stockCode = "2330",
            accountId = 1,
            date = 1_000L,
            recordTime = 1_000L,
            type = "融資買進",
            marginPrincipal = 100_000.0,
            marginLotId = "margin-lot"
        )
        val editedInterestPayment = StockTransaction(
            id = 2,
            stockCode = "2330",
            accountId = 1,
            date = 1_000L,
            recordTime = 2_000L,
            type = "融資還款",
            marginRepaymentLotId = "margin-lot",
            marginActualInterest = 100.0
        )
        val laterFullRepayment = StockTransaction(
            id = 3,
            stockCode = "2330",
            accountId = 1,
            date = 1_000L,
            recordTime = 3_000L,
            type = "融資還款",
            marginRepaymentLotId = "margin-lot",
            marginRepayment = 100_000.0
        )

        val replay = transactionsBeforeCandidateForLotSelection(
            transactions = listOf(opening, laterFullRepayment),
            candidateDate = editedInterestPayment.date,
            candidateRecordTime = editedInterestPayment.recordTime,
            candidateId = editedInterestPayment.id
        )
        val summary = MarginCalculationSupport.calculate(replay, editedInterestPayment.date)

        assertEquals(100_000.0, summary.lots.single().remainingPrincipal, 0.0)
    }
}
