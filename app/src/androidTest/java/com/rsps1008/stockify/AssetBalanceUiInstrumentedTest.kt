package com.rsps1008.stockify

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rsps1008.stockify.data.AssetBalances
import com.rsps1008.stockify.data.BankDeposit
import com.rsps1008.stockify.data.Loan
import com.rsps1008.stockify.ui.screens.AssetBalanceBackupCard
import com.rsps1008.stockify.ui.screens.AssetBalanceRestoreDialog
import com.rsps1008.stockify.ui.screens.BankDepositEditorDialog
import com.rsps1008.stockify.ui.screens.LoanEditorDialog
import com.rsps1008.stockify.ui.theme.StockifyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetBalanceUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun depositEditorPreservesDecimalAmountAndDraftAcrossStateRestoration() {
        var saved: Double? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            StockifyTheme { BankDepositEditorDialog(BankDeposit(1, "存款", 123.45), onDismiss = {}, onSave = { _, _, amount -> saved = amount }) }
        }
        compose.onNodeWithText("123.45").assertIsDisplayed().performTextReplacement("567.89")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("567.89").assertIsDisplayed()
        compose.onNodeWithText("儲存").performClick()
        compose.runOnIdle { assertEquals(567.89, saved!!, 0.0) }
    }

    @Test
    fun loanEditorRejectsNegativeAmountAndKeepsDecimalPrecision() {
        var saved: Double? = null
        compose.setContent {
            StockifyTheme { LoanEditorDialog(Loan(1, "房貸", 987.65), onDismiss = {}, onSave = { _, _, amount -> saved = amount }) }
        }
        compose.onNodeWithText("987.65").performTextReplacement("-2")
        compose.onNodeWithText("儲存").performClick()
        compose.onNodeWithText("金額不可小於 0").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("-2").performTextReplacement("12.34")
        compose.onNodeWithText("儲存").performClick()
        compose.runOnIdle { assertEquals(12.34, saved!!, 0.0) }
    }

    @Test
    fun busyBackupDisablesBothFileActions() {
        compose.setContent { StockifyTheme { Surface { AssetBalanceBackupCard(true, {}, {}) } } }
        compose.onNodeWithText("備份到檔案").assertIsNotEnabled()
        compose.onNodeWithText("從檔案還原").assertIsNotEnabled()
        compose.onNodeWithText("處理中…").assertIsDisplayed()
    }

    @Test
    fun smallScreenLargeTextStacksBackupActionsAndKeepsThemReachable() {
        compose.setContent {
            StockifyTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Surface { Box(Modifier.width(288.dp)) { AssetBalanceBackupCard(false, {}, {}) } }
                }
            }
        }
        val backup = compose.onNodeWithText("備份到檔案").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val restore = compose.onNodeWithText("從檔案還原").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(restore.top > backup.bottom)
    }

    @Test
    fun restorePreviewRequiresExplicitConfirmAndCancelDoesNotConfirm() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            StockifyTheme {
                AssetBalanceRestoreDialog(AssetBalances(listOf(BankDeposit(1, "A", 123.45)), listOf(Loan(1, "L", 67.89))),
                    false, { confirmed = true }, { dismissed = true })
            }
        }
        compose.onNodeWithText("存款 1 筆，合計 123.45 元").assertIsDisplayed()
        compose.onNodeWithText("貸款 1 筆，合計 67.89 元").assertIsDisplayed()
        compose.runOnIdle { assertFalse(confirmed) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(confirmed) }
    }
}
