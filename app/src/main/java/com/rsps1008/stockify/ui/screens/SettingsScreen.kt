package com.rsps1008.stockify.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen() {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            application = application
        )
    )

    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val lastUpdateTime by viewModel.lastStockListUpdateTime.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onMessageShown() // Reset message after showing
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.updateStockListFromTwse() }, enabled = !isLoading) {
                Text("更新股票列表")
            }
            if (isLoading) {
                CircularProgressIndicator()
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        lastUpdateTime?.let {
            Text("上次更新時間：${formatTimestamp(it)}")
        } ?: Text("尚未更新過股票列表")
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { viewModel.deleteAllData() }) {
            Text("刪除所有資料")
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
