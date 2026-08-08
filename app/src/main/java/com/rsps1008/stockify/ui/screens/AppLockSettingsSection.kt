package com.rsps1008.stockify.ui.screens

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.rsps1008.stockify.data.APP_LOCK_MAX_PIN_LENGTH
import com.rsps1008.stockify.data.isValidAppLockPin
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel

@Composable
internal fun AppLockSettingsSection(
    viewModel: SettingsViewModel,
    appLockEnabled: Boolean,
    biometricEnabled: Boolean
) {
    var dialog by remember { mutableStateOf<AppLockDialog?>(null) }
    val context = LocalContext.current
    val biometricStatus = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }
    val biometricAvailable = biometricStatus == BiometricManager.BIOMETRIC_SUCCESS

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "隱私設定",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingSwitchRow(
                title = "密碼與指紋鎖定",
                description = if (appLockEnabled) "離開 App 後，需再次輸入數字密碼解鎖" else "若忘記密碼，必須移除並重新安裝APP。",
                checked = appLockEnabled,
                onCheckedChange = { enabled ->
                    dialog = if (enabled) AppLockDialog.Enable else AppLockDialog.Disable
                }
            )

            if (appLockEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                SettingSwitchRow(
                    title = "指紋／生物辨識解鎖",
                    description = biometricDescription(biometricStatus),
                    checked = biometricEnabled,
                    enabled = biometricAvailable,
                    onCheckedChange = viewModel::setAppLockBiometricEnabled
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { dialog = AppLockDialog.ChangePin },
                    modifier = Modifier.fillMaxWidth(),
                    shape = SettingsButtonShape
                ) {
                    Text("變更數字密碼")
                }
            }
        }
    }

    when (dialog) {
        AppLockDialog.Enable -> PinSetupDialog(
            onDismiss = { dialog = null },
            onConfirm = { pin, done ->
                viewModel.enableAppLock(pin) { succeeded ->
                    done(succeeded)
                    if (succeeded) dialog = null
                }
            }
        )
        AppLockDialog.Disable -> CurrentPinDialog(
            title = "關閉應用程式鎖定",
            confirmText = "關閉",
            onDismiss = { dialog = null },
            onConfirm = { pin, done ->
                viewModel.disableAppLock(pin) { succeeded ->
                    done(succeeded)
                    if (succeeded) dialog = null
                }
            }
        )
        AppLockDialog.ChangePin -> ChangePinDialog(
            onDismiss = { dialog = null },
            onConfirm = { currentPin, newPin, done ->
                viewModel.changeAppLockPin(currentPin, newPin) { succeeded ->
                    done(succeeded)
                    if (succeeded) dialog = null
                }
            }
        )
        null -> Unit
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PinSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, (Boolean) -> Unit) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定數字密碼") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinField("輸入 4–8 位數字密碼", pin) { pin = it; error = null }
                PinField("再次輸入數字密碼", confirmation) { confirmation = it; error = null }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    error = when {
                        !isValidAppLockPin(pin) -> "請輸入 4–8 位數字密碼"
                        pin != confirmation -> "兩次輸入的密碼不一致"
                        else -> null
                    }
                    if (error == null) {
                        submitting = true
                        onConfirm(pin) { succeeded ->
                            submitting = false
                            if (!succeeded) error = "無法儲存密碼，請再試一次"
                        }
                    }
                }
            ) { Text("啟用") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CurrentPinDialog(
    title: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String, (Boolean) -> Unit) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinField("目前的數字密碼", pin) { pin = it; error = null }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    if (!isValidAppLockPin(pin)) {
                        error = "請輸入目前的數字密碼"
                    } else {
                        submitting = true
                        onConfirm(pin) { succeeded ->
                            submitting = false
                            if (!succeeded) error = "密碼錯誤"
                        }
                    }
                }
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, (Boolean) -> Unit) -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("變更數字密碼") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinField("目前的數字密碼", currentPin) { currentPin = it; error = null }
                PinField("新的 4–8 位數字密碼", newPin) { newPin = it; error = null }
                PinField("再次輸入新密碼", confirmation) { confirmation = it; error = null }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    error = when {
                        !isValidAppLockPin(currentPin) -> "請輸入目前的數字密碼"
                        !isValidAppLockPin(newPin) -> "新密碼須為 4–8 位數字"
                        newPin != confirmation -> "兩次輸入的新密碼不一致"
                        else -> null
                    }
                    if (error == null) {
                        submitting = true
                        onConfirm(currentPin, newPin) { succeeded ->
                            submitting = false
                            if (!succeeded) error = "目前的密碼錯誤"
                        }
                    }
                }
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PinField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(input.filter(Char::isDigit).take(APP_LOCK_MAX_PIN_LENGTH))
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun biometricDescription(status: Int): String = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> "使用裝置已登錄的指紋或強式生物辨識快速解鎖"
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "請先到系統設定登錄指紋或生物辨識"
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "此裝置不支援生物辨識"
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "生物辨識目前無法使用"
    else -> "此裝置無法使用強式生物辨識"
}

private enum class AppLockDialog { Enable, Disable, ChangePin }
