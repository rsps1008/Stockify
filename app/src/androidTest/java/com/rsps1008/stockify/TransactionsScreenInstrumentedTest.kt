package com.rsps1008.stockify

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionListSnapshot
import com.rsps1008.stockify.ui.screens.TransactionsContent
import com.rsps1008.stockify.ui.theme.StockifyTheme
import com.rsps1008.stockify.ui.viewmodel.TransactionsUiState
import com.rsps1008.stockify.ui.viewmodel.transactionListUiStates
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionsScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun searchLargeListClearAndOpenMatchingTransaction() {
        val snapshots = MutableStateFlow(populatedSnapshot())
        val queries = MutableStateFlow("")
        var openedId: Int? = null
        val states = transactionListUiStates(snapshots, MutableStateFlow(1), queries)
        compose.setContent {
            val state by states.collectAsState(TransactionsUiState())
            val query by queries.collectAsState()
            StockifyTheme {
                Surface {
                    TransactionsContent(state, query, { queries.value = it }, {}, { openedId = it })
                }
            }
        }
        waitForText("共 300 筆交易")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(150)
        compose.onNodeWithText("搜尋交易").performClick().performTextReplacement("aapl 配息")
        waitForText("找到 1 筆，共 300 筆")
        compose.onNodeWithText("AAPL").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(300, openedId) }
        saveScreenshot("search")
        compose.onNodeWithContentDescription("清除搜尋").performClick()
        waitForText("共 300 筆交易")
        compose.onNodeWithText("買1股").assertIsDisplayed()
    }

    @Test
    fun loadingEmptyAndNoResultsHaveDistinctActionsInLargeDarkText() {
        val snapshots = MutableStateFlow(TransactionListSnapshot())
        val queries = MutableStateFlow("")
        val states = transactionListUiStates(snapshots, MutableStateFlow(1), queries)
        var addClicks = 0
        compose.setContent {
            val state by states.collectAsState(TransactionsUiState())
            val query by queries.collectAsState()
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val darkContext = remember(context, configuration) {
                context.createConfigurationContext(Configuration(configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                })
            }
            CompositionLocalProvider(LocalContext provides darkContext) {
                StockifyTheme(darkTheme = true, amoledTheme = true, textScale = 1.5f) {
                    Surface {
                        TransactionsContent(state, query, { queries.value = it }, { addClicks++ }, {})
                    }
                }
            }
        }
        waitForText("正在載入交易紀錄…")
        compose.onNodeWithText("新增交易").assertDoesNotExist()
        snapshots.value = TransactionListSnapshot(accountId = 1, isLoaded = true)
        waitForText("目前帳戶尚無交易紀錄")
        compose.onNodeWithText("新增交易").performClick()
        compose.runOnIdle { assertEquals(1, addClicks) }
        snapshots.value = populatedSnapshot()
        compose.onNodeWithText("搜尋交易").performTextReplacement("找不到的代號")
        waitForText("找不到符合的交易")
        compose.onNodeWithText("清除搜尋條件").assertIsDisplayed()
        saveScreenshot("empty-dark-large")
        compose.onNodeWithText("清除搜尋條件").performClick()
        waitForText("共 300 筆交易")
        compose.onNodeWithText("買1股").assertIsDisplayed()
    }

    @Test
    fun returningToListRestoresScrollPosition() {
        val snapshots = MutableStateFlow(populatedSnapshot())
        val queries = MutableStateFlow("")
        val states = transactionListUiStates(snapshots, MutableStateFlow(1), queries)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state by states.collectAsState(TransactionsUiState())
            val query by queries.collectAsState()
            StockifyTheme {
                Surface {
                    TransactionsContent(state, query, { queries.value = it }, {}, {})
                }
            }
        }
        waitForText("共 300 筆交易")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(150)
        compose.onNodeWithText("買150股").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        waitForText("共 300 筆交易")
        compose.onNodeWithText("買150股").assertIsDisplayed()
    }

    @Test
    fun compactHeightKeepsSearchAndFirstResultVisible() {
        val queries = MutableStateFlow("")
        val states = transactionListUiStates(MutableStateFlow(populatedSnapshot()), MutableStateFlow(1), queries)
        compose.setContent {
            val state by states.collectAsState(TransactionsUiState())
            val query by queries.collectAsState()
            StockifyTheme(textScale = 1.5f) {
                Surface {
                    Box(Modifier.height(320.dp)) {
                        TransactionsContent(state, query, { queries.value = it }, {}, {})
                    }
                }
            }
        }
        waitForText("共 300 筆交易")
        compose.onNodeWithText("搜尋交易").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).assertHeightIsAtLeast(120.dp)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("買1股").assertIsDisplayed()
        saveScreenshot("compact-large")
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText(text).fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun populatedSnapshot() = TransactionListSnapshot(
        stocks = listOf(
            Stock(name = "台積電", code = "2330", market = "TW"),
            Stock(name = "Apple", code = "AAPL", market = "US")
        ),
        transactions = (1..300).map { id ->
            StockTransaction(
                id = id,
                stockCode = if (id == 300) "AAPL" else "2330",
                market = if (id == 300) "US" else "TW",
                accountId = 1,
                date = 1_780_272_000_000L,
                recordTime = id.toLong(),
                type = if (id == 300) "配息" else "買進",
                buyShares = id.toDouble(),
                buyPrice = 100.0
            )
        },
        accountId = 1,
        isLoaded = true
    )

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val outputDirectory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: instrumentation.targetContext.getExternalFilesDir(null)
        File(outputDirectory, "transactions-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
