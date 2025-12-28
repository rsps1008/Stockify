package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            val transactionTypeText = when (uiState.transaction.type) {
                "buy" -> "買進"
                "sell" -> "賣出"
                "dividend" -> "配息"
                "stock_dividend" -> "配股"
                else -> uiState.transaction.type
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                DetailRow(label = "股票", value = uiState.stockName)
                DetailRow(label = "日期", value = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(uiState.transaction.date)))
                DetailRow(label = "交易", value = transactionTypeText)
                DetailRow(label = "股價", value = String.format("%,.2f", uiState.transaction.price))
                DetailRow(label = "股數", value = String.format("%,.0f", uiState.transaction.shares))
                DetailRow(label = "手續費", value = String.format("%,.0f", uiState.transaction.fee))
                val amount = when (uiState.transaction.type) {
                    "buy" -> - (uiState.transaction.price * uiState.transaction.shares + uiState.transaction.fee)
                    "sell" -> uiState.transaction.price * uiState.transaction.shares - uiState.transaction.fee
                    "dividend" -> uiState.transaction.price * uiState.transaction.shares
                    else -> 0.0
                }
                val amountColor = if (amount >= 0) Color.Red else Color.Green
                DetailRow(label = "支出金額", value = String.format("%,.0f", amount), valueColor = amountColor)
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