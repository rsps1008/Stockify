package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.data.dividend.YahooDividendRepository
import com.rsps1008.stockify.ui.viewmodel.DividendInfoViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import com.rsps1008.stockify.ui.viewmodel.DividendItemUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DividendInfoScreen(navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: DividendInfoViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            realtimeStockDataService = application.realtimeStockDataService,
            dividendRepository = YahooDividendRepository(application.httpClient)
        )
    )

    val dividendList by viewModel.dividendList.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("最新配息配股") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(dividendList) { item ->
                DividendInfoCard(item)
            }
        }
    }
}

@Composable
fun DividendInfoCard(item: DividendItemUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Stock Name and Code
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "${item.stockName} (${item.stockCode})",
                    style = MaterialTheme.typography.titleMedium
                )
                if (item.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Cash Dividend
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("現金股利", style = MaterialTheme.typography.bodyMedium)
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    if (item.cashDividend != null) {
                        Text("最新: ${item.cashDividend}", style = MaterialTheme.typography.bodyLarge)
                        item.cashDividendDate?.let {
                            Text("除息日: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("最新: -", style = MaterialTheme.typography.bodyMedium)
                    }
                    
                    if (item.lastLocalCashDividend != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("上次領取: ${item.lastLocalCashDividend}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text("日期: ${item.lastLocalCashDividendDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            // Stock Dividend
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("股票股利", style = MaterialTheme.typography.bodyMedium)
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    if (item.stockDividend != null) {
                        Text("最新: ${item.stockDividend}", style = MaterialTheme.typography.bodyLarge)
                        item.stockDividendDate?.let {
                            Text("除權日: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("最新: -", style = MaterialTheme.typography.bodyMedium)
                    }

                    if (item.lastLocalStockDividend != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("上次領取: ${item.lastLocalStockDividend}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text("日期: ${item.lastLocalStockDividendDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            
            if (item.errorMessage != null) {
                 Spacer(modifier = Modifier.height(8.dp))
                 Text(
                    text = "錯誤: ${item.errorMessage}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                 )
            }
        }
    }
}
