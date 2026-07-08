package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rsps1008.stockify.data.Account

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherBadge(
    activeAccountId: Int,
    accounts: List<Account>,
    onAccountSelected: (Int) -> Unit,
    onAddAccount: (String) -> Unit,
    onRenameAccount: (Account, String) -> Unit,
    onDeleteAccount: (Account) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }

    val activeAccountName = when (activeAccountId) {
        0 -> "全部帳戶"
        else -> accounts.find { it.id == activeAccountId }?.name ?: "預設帳戶"
    }

    Surface(
        onClick = { showSheet = true },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (activeAccountId == 0) Icons.Default.AccountBalance else Icons.Default.Wallet,
                contentDescription = "Account Icon",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = activeAccountName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Dropdown",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }

    if (showSheet) {
        AccountSwitcherSheet(
            activeAccountId = activeAccountId,
            accounts = accounts,
            onAccountSelected = {
                onAccountSelected(it)
                showSheet = false
            },
            onAddAccount = onAddAccount,
            onRenameAccount = onRenameAccount,
            onDeleteAccount = onDeleteAccount,
            onDismiss = { showSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    activeAccountId: Int,
    accounts: List<Account>,
    onAccountSelected: (Int) -> Unit,
    onAddAccount: (String) -> Unit,
    onRenameAccount: (Account, String) -> Unit,
    onDeleteAccount: (Account) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isManageMode by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<Account?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Account?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "選擇帳戶",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = { isManageMode = !isManageMode }) {
                    Text(if (isManageMode) "完成" else "管理帳戶")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isManageMode) {
                    item {
                        AccountItemRow(
                            name = "全部帳戶 (合併總覽)",
                            isSelected = activeAccountId == 0,
                            isManageMode = false,
                            onSelect = { onAccountSelected(0) },
                            onRename = {},
                            onDelete = {},
                            isSystemAccount = true
                        )
                    }
                }

                items(accounts, key = { it.id }) { account ->
                    AccountItemRow(
                        name = account.name,
                        isSelected = activeAccountId == account.id,
                        isManageMode = isManageMode,
                        onSelect = { onAccountSelected(account.id) },
                        onRename = { showRenameDialog = account },
                        onDelete = { showDeleteDialog = account },
                        isSystemAccount = account.id == 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
                Spacer(modifier = Modifier.width(8.dp))
                Text("新增帳戶")
            }
        }
    }

    if (showAddDialog) {
        var newAccountName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增帳戶") },
            text = {
                OutlinedTextField(
                    value = newAccountName,
                    onValueChange = { newAccountName = it },
                    label = { Text("帳戶名稱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newAccountName.isNotBlank()) {
                            onAddAccount(newAccountName.trim())
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("新增")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    showRenameDialog?.let { account ->
        var renameValue by remember { mutableStateOf(account.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("重新命名帳戶") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("帳戶名稱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameValue.isNotBlank()) {
                            onRenameAccount(account, renameValue.trim())
                            showRenameDialog = null
                        }
                    }
                ) {
                    Text("儲存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    showDeleteDialog?.let { account ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("確認刪除帳戶") },
            text = { Text("確定要刪除「${account.name}」嗎？此帳戶下的所有交易紀錄也將被一併刪除。此動作無法復原！") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAccount(account)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("確認刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun AccountItemRow(
    name: String,
    isSelected: Boolean,
    isManageMode: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    isSystemAccount: Boolean
) {
    Surface(
        onClick = { if (!isManageMode) onSelect() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected && !isManageMode) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isSystemAccount) Icons.Default.AccountBalance else Icons.Default.Wallet,
                    contentDescription = "Wallet",
                    tint = if (isSelected && !isManageMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = name,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected && !isManageMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected && !isManageMode) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            if (isManageMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (!isSystemAccount) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            } else if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
