package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal const val UPDATE_HIGHLIGHTS_VERSION = "1.6.9"

internal fun shouldShowUpdateHighlights(
    currentVersion: String,
    lastShownVersion: String?
): Boolean = currentVersion == UPDATE_HIGHLIGHTS_VERSION &&
        lastShownVersion != UPDATE_HIGHLIGHTS_VERSION

@Composable
fun UpdateHighlightsDialog(
    onDismiss: () -> Unit,
    onDismissUntilNextVersion: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "這次更新，記帳更方便",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "存款與貸款安心備份，交易紀錄更好找。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UpdateHighlightCard(
                    icon = Icons.Filled.CloudUpload,
                    title = "存款、貸款，也能安心備份",
                    description = "在資產總覽最下方，可備份至檔案或 Google Drive；還原前先預覽，與持股備份分開管理。"
                )
                UpdateHighlightCard(
                    icon = Icons.Filled.Search,
                    title = "交易紀錄，更快找到",
                    description = "輸入股票代號、名稱、交易類型、筆記或日期，快速找出需要的紀錄。"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissUntilNextVersion) {
                Text("知道了，不再顯示")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}

@Composable
private fun UpdateHighlightCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
