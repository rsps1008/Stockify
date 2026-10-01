package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.rsps1008.stockify.data.AssetBalances
import java.util.Locale

@Composable
internal fun AssetBalanceBackupCard(isBusy: Boolean, onBackup: () -> Unit, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("存款與貸款備份", style = MaterialTheme.typography.titleMedium)
            Text("獨立儲存所有存款與貸款，不包含持股、交易或證券帳戶。", style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBackup, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) { Text("備份到檔案") }
                        OutlinedButton(onClick = onRestore, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) { Text("從檔案還原") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBackup, enabled = !isBusy, modifier = Modifier.weight(1f)) { Text("備份到檔案") }
                        OutlinedButton(onClick = onRestore, enabled = !isBusy, modifier = Modifier.weight(1f)) { Text("從檔案還原") }
                    }
                }
            }
            if (isBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("處理中…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun AssetBalanceRestoreDialog(
    preview: AssetBalances,
    isBusy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text("還原存款與貸款") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("存款 ${preview.bankDeposits.size} 筆，合計 ${String.format(Locale.US, "%,.2f", preview.bankDeposits.sumOf { it.amount })} 元")
                Text("貸款 ${preview.loans.size} 筆，合計 ${String.format(Locale.US, "%,.2f", preview.loans.sumOf { it.amount })} 元")
                Text("確認後會替換目前所有存款與貸款；空清單會清除對應資料。持股、交易與證券帳戶不受影響。")
                if (isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !isBusy) { Text("確認替換") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isBusy) { Text("取消") } }
    )
}
