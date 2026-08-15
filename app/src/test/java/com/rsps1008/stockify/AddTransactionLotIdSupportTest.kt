package com.rsps1008.stockify

import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.viewmodel.resolveMarginOpeningLotId
import com.rsps1008.stockify.ui.viewmodel.resolveShortOpeningLotId
import com.rsps1008.stockify.ui.viewmodel.transactionFeeForType
import com.rsps1008.stockify.ui.viewmodel.calculateSupplementaryHealthInsurancePremium
import com.rsps1008.stockify.ui.viewmodel.dividendDateToTransactionDateMillis
import com.rsps1008.stockify.ui.viewmodel.EditTransactionState
import com.rsps1008.stockify.ui.viewmodel.EDIT_TRANSACTION_MISSING
import com.rsps1008.stockify.ui.viewmodel.editTransactionUpdateError
import com.rsps1008.stockify.ui.viewmodel.holdingSharesAtDate
import com.rsps1008.stockify.ui.viewmodel.resolvedEditTransactionState
import com.rsps1008.stockify.ui.viewmodel.transactionsBeforeCandidateForLotSelection
import com.rsps1008.stockify.ui.viewmodel.transactionsWithCandidateForValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AddTransactionLotIdSupportTest {
    @Test
    fun editTransactionStateDistinguishesNewAndLoadedResultFromMissingTarget() {
        val transaction = StockTransaction(
            id = 42,
            stockCode = "2330",
            accountId = 1,
            date = 1_000L,
            recordTime = 1_000L,
            type = "買進"
        )

        assertEquals(
            EditTransactionState.NotEditing,
            resolvedEditTransactionState(transactionId = null, transaction = null)
        )
        assertEquals(
            EditTransactionState.Missing,
            resolvedEditTransactionState(transactionId = transaction.id, transaction = null)
        )
        assertEquals(
            EditTransactionState.Ready(transaction),
            resolvedEditTransactionState(transactionId = transaction.id, transaction = transaction)
        )
    }

    @Test
    fun editTransactionRequiresAnUpdatedRow() {
        assertNull(editTransactionUpdateError(1))
        assertNull(editTransactionUpdateError(2))
        assertEquals(EDIT_TRANSACTION_MISSING, editTransactionUpdateError(0))
    }

    @Test
    fun supplementaryHealthInsurancePremium_appliesOnlyToTaiwanDividendsAboveThreshold() {
        assertEquals(0.0, calculateSupplementaryHealthInsurancePremium(20_000.0, "TW"), 0.0)
        assertEquals(422.0, calculateSupplementaryHealthInsurancePremium(20_001.0, "TW"), 0.0)
        assertEquals(0.0, calculateSupplementaryHealthInsurancePremium(100_000.0, "US"), 0.0)
        assertEquals(2_110.0, calculateSupplementaryHealthInsurancePremium(100_000.0, "TW"), 0.0)
    }

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
    fun dividendDateParserAcceptsSlashAndDashSeparatedDates() {
        val taipei = ZoneId.of("Asia/Taipei")
        val expected = LocalDate.of(2026, 8, 10)
            .atStartOfDay(taipei)
            .toInstant()
            .toEpochMilli()

        assertEquals(expected, dividendDateToTransactionDateMillis("2026/08/10", taipei))
        assertEquals(expected, dividendDateToTransactionDateMillis("2026-08-10", taipei))
        assertNull(dividendDateToTransactionDateMillis("2026.08.10", taipei))
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

    @Test
    fun dividendHoldingSharesAreScopedByAccountDateAndCompanyActions() {
        val day = 86_400_000L
        val transactions = listOf(
            StockTransaction(
                id = 1,
                stockCode = "2330",
                accountId = 1,
                date = 0L,
                recordTime = 0L,
                type = "買進",
                buyShares = 100.0
            ),
            StockTransaction(
                id = 2,
                stockCode = "2330",
                accountId = 2,
                date = 0L,
                recordTime = 0L,
                type = "買進",
                buyShares = 200.0
            ),
            StockTransaction(
                id = 3,
                stockCode = "2330",
                accountId = 1,
                date = day,
                recordTime = day,
                type = "分割",
                stockSplitRatio = 2.0,
                sharesBeforeSplit = 100.0,
                sharesAfterSplit = 200.0
            ),
            StockTransaction(
                id = 4,
                stockCode = "2330",
                accountId = 1,
                date = day * 3,
                recordTime = day * 3,
                type = "買進",
                buyShares = 50.0
            )
        )

        assertEquals(100.0, holdingSharesAtDate(transactions, "2330", 1, day - 1), 0.0)
        assertEquals(200.0, holdingSharesAtDate(transactions, "2330", 1, day * 2), 0.0)
        assertEquals(200.0, holdingSharesAtDate(transactions, "2330", 2, day * 2), 0.0)
        assertEquals(200.0, holdingSharesAtDate(transactions, "2330", 1, day * 2 + 1), 0.0)
    }
}
