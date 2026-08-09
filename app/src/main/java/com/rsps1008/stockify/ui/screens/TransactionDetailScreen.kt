package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.TransactionDetailViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import com.rsps1008.stockify.data.formatMarketAmount
import com.rsps1008.stockify.data.formatShareCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(transactionId: Int, navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val locale = LocalConfiguration.current.locales[0]
    val viewModel: TransactionDetailViewModel = viewModel(
        factory = ViewModelFactory(application.database.stockDao(), transactionId = transactionId)
    )
    val transactionUiState by viewModel.transactionUiState.collectAsState()
    val canModifyTransaction by viewModel.canModifyTransaction.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("確認刪除") },
            text = { Text("您確定要刪除這筆交易紀錄嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTransaction { error ->
                            if (error != null) {
                                Toast.makeText(application, error, Toast.LENGTH_LONG).show()
                            } else {
                                navController.popBackStack()
                            }
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text("確定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("明細") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }, enabled = canModifyTransaction) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                    IconButton(
                        onClick = { navController.navigate(Screen.AddTransaction.createRoute(transactionId)) },
                        enabled = canModifyTransaction
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                }
            )
        }
    ) { paddingValues ->
        transactionUiState?.let { uiState ->
            val transaction = uiState.transaction
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                DetailRow(label = "股票", value = uiState.stockName)
                DetailRow(label = "日期", value = SimpleDateFormat("yyyy/MM/dd", locale).format(Date(transaction.date)))
                DetailRow(label = "交易", value = transaction.type)

                when (transaction.type) {
                    "買進", "融資買進" -> {
                        DetailRow(label = "買進價格", value = String.format(Locale.US, "%,.2f", transaction.buyPrice))
                        DetailRow(label = "買進股數", value = formatShareCount(transaction.buyShares))
                        DetailRow(label = "手續費", value = formatMarketAmount(transaction.fee, uiState.market))
                        DetailRow(label = "支出", value = formatMarketAmount(transaction.expense, uiState.market), valueColor = StockifyAppTheme.stockColors.loss)
                        if (transaction.type == "融資買進") {
                            DetailRow(label = "融資本金", value = formatMarketAmount(transaction.marginPrincipal, uiState.market))
                            DetailRow(label = "融資自備款", value = formatMarketAmount(if (transaction.marginSelfFundedOverridden) transaction.marginSelfFunded else transaction.expense - transaction.marginPrincipal, uiState.market))
                            DetailRow(label = "年利率", value = String.format(Locale.US, "%.4f%%", transaction.marginAnnualRate))
                            DetailRow(label = "融資批次", value = formatLotId(transaction.marginLotId))
                        }
                    }
                    "賣出" -> {
                        DetailRow(label = "賣出價格", value = String.format(Locale.US, "%,.2f", transaction.sellPrice))
                        DetailRow(label = "賣出股數", value = formatShareCount(transaction.sellShares))
                        DetailRow(label = "手續費", value = formatMarketAmount(transaction.fee, uiState.market))
                        DetailRow(label = "交易稅", value = formatMarketAmount(transaction.tax, uiState.market))
                        DetailRow(label = "收入", value = formatMarketAmount(transaction.income, uiState.market), valueColor = StockifyAppTheme.stockColors.gain)
                        if (transaction.marginRepayment > 0.0) DetailRow(label = "還融資本金", value = formatMarketAmount(transaction.marginRepayment, uiState.market), valueColor = StockifyAppTheme.stockColors.loss)
                        if (transaction.marginActualInterest > 0.0) DetailRow(label = "實際扣款利息", value = formatMarketAmount(transaction.marginActualInterest, uiState.market), valueColor = StockifyAppTheme.stockColors.loss)
                        if (transaction.marginRepaymentLotId.isNotBlank()) {
                            DetailRow(label = "沖抵融資批次", value = formatLotId(transaction.marginRepaymentLotId))
                        }
                    }
                    "配息" -> {
                        DetailRow(label = "每股股息", value = String.format(Locale.US, "%,.4f", transaction.cashDividend))
                        DetailRow(label = "除息股數", value = formatShareCount(transaction.exDividendShares))
                        DetailRow(label = "手續費", value = formatMarketAmount(transaction.fee, uiState.market))
                        DetailRow(label = "補充保費", value = formatMarketAmount(transaction.supplementaryHealthInsurancePremium, uiState.market))
                        DetailRow(label = "股息收入", value = formatMarketAmount(transaction.income, uiState.market), valueColor = StockifyAppTheme.stockColors.gain)
                    }
                    "配股" -> {
                        DetailRow(label = "股票股利", value = String.format(Locale.US, "%,.4f", transaction.stockDividend))
                        DetailRow(label = "除權股數", value = formatShareCount(transaction.exRightsShares))
                        DetailRow(label = "配發股數", value = formatShareCount(transaction.dividendShares))
                    }
                    "減資" -> {
                        DetailRow(label = "減資比例", value = "${String.format(Locale.US, "%.2f", transaction.capitalReductionRatio)}%")
                        DetailRow(label = "原持股數", value = formatShareCount(transaction.sharesBeforeReduction))
                        DetailRow(label = "新持股數", value = formatShareCount(transaction.sharesAfterReduction))
                        DetailRow(label = "退還股款", value = String.format(Locale.US, "%,.0f", transaction.cashReturned), valueColor = StockifyAppTheme.stockColors.gain)
                    }
                    "分割" -> {
                        DetailRow(label = "每股拆分", value = String.format(Locale.US, "%,.0f", transaction.stockSplitRatio))
                        DetailRow(label = "原持股數", value = formatShareCount(transaction.sharesBeforeSplit))
                        DetailRow(label = "新持股數", value = formatShareCount(transaction.sharesAfterSplit))
                    }
                    "融資還款" -> {
                        DetailRow(label = "還款本金", value = formatMarketAmount(transaction.marginRepayment, uiState.market), valueColor = StockifyAppTheme.stockColors.loss)
                        if (transaction.marginActualInterest > 0.0) DetailRow(label = "實際扣款利息", value = formatMarketAmount(transaction.marginActualInterest, uiState.market), valueColor = StockifyAppTheme.stockColors.loss)
                        DetailRow(label = "沖抵融資批次", value = formatLotId(transaction.marginRepaymentLotId))
                    }
                    "融券賣出" -> {
                        DetailRow(label = "融券賣出價格", value = String.format(Locale.US, "%,.2f", transaction.sellPrice))
                        DetailRow(label = "融券賣出股數", value = formatShareCount(transaction.sellShares))
                        DetailRow(label = "手續費", value = formatMarketAmount(transaction.fee, uiState.market))
                        DetailRow(label = "交易稅", value = formatMarketAmount(transaction.tax, uiState.market))
                        DetailRow(label = "收入", value = formatMarketAmount(transaction.income, uiState.market), valueColor = StockifyAppTheme.stockColors.gain)
                        DetailRow(label = "融券本金", value = formatMarketAmount(transaction.shortBorrowPrincipal, uiState.market))
                        DetailRow(label = "借券年費率", value = String.format(Locale.US, "%.4f%%", transaction.shortBorrowAnnualRate))
                        DetailRow(label = "融券批次", value = formatLotId(transaction.shortLotId))
                    }
                    "買券還券" -> {
                        DetailRow(label = "買券價格", value = String.format(Locale.US, "%,.2f", transaction.buyPrice))
                        DetailRow(label = "還券股數", value = formatShareCount(transaction.shortCoverShares))
                        DetailRow(label = "手續費", value = formatMarketAmount(transaction.fee, uiState.market))
                        DetailRow(label = "支出", value = formatMarketAmount(transaction.expense, uiState.market), valueColor = StockifyAppTheme.stockColors.loss)
                        DetailRow(label = "沖抵融券批次", value = formatLotId(transaction.shortCoverLotId))
                    }
                    "融券補償" -> {
                        DetailRow(label = "補償金額", value = formatMarketAmount(transaction.shortCompensation, uiState.market), valueColor = StockifyAppTheme.stockColors.loss)
                        DetailRow(label = "融券批次", value = formatLotId(transaction.shortCompensationLotId))
                    }
                }
                if (transaction.note.isNotBlank()) {
                    DetailRow(label = "交易筆記", value = transaction.note)
                }
            }
        }
    }
}

private fun formatLotId(lotId: String): String = when {
    lotId.isBlank() -> "-"
    lotId.length <= 8 -> lotId
    else -> "${lotId.take(8)}…"
}

@Composable
fun DetailRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = valueColor)
    }
}
