package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
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

internal const val UPDATE_HIGHLIGHTS_VERSION = "1.7.0"

internal fun shouldShowUpdateHighlights(
    currentVersion: String,
    lastShownVersion: String?
): Boolean = currentVersion.isAtLeastVersion(UPDATE_HIGHLIGHTS_VERSION) &&
        lastShownVersion != UPDATE_HIGHLIGHTS_VERSION

private fun String.isAtLeastVersion(requiredVersion: String): Boolean {
    val currentParts = split('.').map { it.toIntOrNull() ?: return false }
    val requiredParts = requiredVersion.split('.').map { it.toIntOrNull() ?: return false }
    val partCount = maxOf(currentParts.size, requiredParts.size)

    for (index in 0 until partCount) {
        val currentPart = currentParts.getOrElse(index) { 0 }
        val requiredPart = requiredParts.getOrElse(index) { 0 }
        if (currentPart != requiredPart) return currentPart > requiredPart
    }
    return true
}

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
                    text = "這次更新，投資表現更清楚",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "新增報酬日曆，投資表現一眼看懂。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UpdateHighlightCard(
                    icon = Icons.Filled.ShowChart,
                    title = "每天的賺賠，一看就懂",
                    description = "用月曆回顧每天的賺賠與報酬，投資表現更清楚。"
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
