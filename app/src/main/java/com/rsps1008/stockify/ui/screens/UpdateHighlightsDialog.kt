package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CardGiftcard
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

internal const val UPDATE_HIGHLIGHTS_VERSION = "1.6.8"

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
                    text = "這次更新，損益更好懂",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "兩個新選項，讓畫面更貼近你的投資習慣。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UpdateHighlightCard(
                    icon = Icons.Filled.AutoGraph,
                    title = "已賣出、還持有，一眼分清楚",
                    description = "部分賣出後，可將已落袋的損益分開顯示，不再和手上的部位混在一起。"
                )
                UpdateHighlightCard(
                    icon = Icons.Filled.CardGiftcard,
                    title = "想看純股價表現，也可以",
                    description = "損益與報酬可選擇不計股息，方便專注查看買賣表現；原有股息紀錄仍會保留。"
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
