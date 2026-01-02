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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.viewmodel.TransactionDetailViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(transactionId: Int, navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: TransactionDetailViewModel = viewModel(
        factory = ViewModelFactory(application.database.stockDao(), transactionId = transactionId)
    )
    val transactionUiState by viewModel.transactionUiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("確認刪除") },
            text = { Text("您確定要刪除這筆交易紀錄嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTransaction()
                        showDeleteDialog = false
                        navController.popBackStack()
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
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                    IconButton(onClick = { navController.navigate(Screen.AddTransaction.createRoute(transactionId)) }) {
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
                DetailRow(label = "日期", value = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(transaction.date)))
                DetailRow(label = "交易", value = transaction.type)

                when (transaction.type) {
                    "買進" -> {
                        DetailRow(label = "買進價格", value = String.format("%,.2f", transaction.buyPrice))
                        DetailRow(label = "買進股數", value = String.format("%,.0f", transaction.buyShares))
                        DetailRow(label = "手續費", value = String.format("%,.0f", transaction.fee))
                        DetailRow(label = "交易稅", value = String.format("%,.0f", transaction.tax))
                        DetailRow(label = "支出", value = String.format("%,.0f", transaction.expense), valueColor = Color.Green)
                    }
                    "賣出" -> {
                        DetailRow(label = "賣出價格", value = String.format("%,.2f", transaction.sellPrice))
                        DetailRow(label = "賣出股數", value = String.format("%,.0f", transaction.sellShares))
                        DetailRow(label = "手續費", value = String.format("%,.0f", transaction.fee))
                        DetailRow(label = "交易稅", value = String.format("%,.0f", transaction.tax))
                        DetailRow(label = "收入", value = String.format("%,.0f", transaction.income), valueColor = Color.Red)
                    }
                    "配息" -> {
                        DetailRow(label = "每股股息", value = String.format("%,.4f", transaction.cashDividend))
                        DetailRow(label = "除息股數", value = String.format("%,.0f", transaction.exDividendShares))
                        DetailRow(label = "股息收入", value = String.format("%,.0f", transaction.income), valueColor = Color.Red)
                        DetailRow(label = "手續費", value = String.format("%,.0f", transaction.fee))
                    }
                    "配股" -> {
                        DetailRow(label = "股票股利", value = String.format("%,.4f", transaction.stockDividend))
                        DetailRow(label = "除權股數", value = String.format("%,.0f", transaction.exRightsShares))
                        DetailRow(label = "配發股數", value = String.format("%,.0f", transaction.dividendShares))
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = valueColor)
    }
}