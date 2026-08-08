package com.rsps1008.stockify.ui.screens

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rsps1008.stockify.data.APP_LOCK_MAX_PIN_LENGTH
import com.rsps1008.stockify.data.SettingsDataStore
import kotlinx.coroutines.launch

@Composable
fun AppLockScreen(
    activity: AppCompatActivity,
    settingsDataStore: SettingsDataStore,
    onUnlocked: () -> Unit
) {
    BackHandler(enabled = true) {}
    val biometricEnabled by settingsDataStore.appLockBiometricEnabledFlow.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checkingPin by remember { mutableStateOf(false) }
    var biometricPromptShown by remember { mutableStateOf(false) }
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    val canUseBiometric = remember(activity, biometricEnabled) {
        biometricEnabled && BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    val biometricPrompt = remember(activity) {
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    currentOnUnlocked()
                }

                override fun onAuthenticationFailed() {
                    error = "生物辨識失敗，請再試一次或輸入數字密碼"
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        error = errString.toString()
                    }
                }
            }
        )
    }
    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("解鎖韭菜記帳本")
            .setSubtitle("使用指紋或生物辨識解鎖")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("改用數字密碼")
            .build()
    }

    fun requestBiometricUnlock() {
        error = null
        biometricPrompt.authenticate(promptInfo)
    }

    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric && !biometricPromptShown) {
            biometricPromptShown = true
            requestBiometricUnlock()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("韭菜記帳本已上鎖", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "輸入數字密碼以查看持股資料",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { input ->
                    pin = input.filter(Char::isDigit).take(APP_LOCK_MAX_PIN_LENGTH)
                    error = null
                },
                label = { Text("數字密碼") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                enabled = !checkingPin,
                onClick = {
                    checkingPin = true
                    scope.launch {
                        if (settingsDataStore.verifyAppLockPin(pin)) {
                            currentOnUnlocked()
                        } else {
                            error = "數字密碼錯誤"
                            pin = ""
                        }
                        checkingPin = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = SettingsButtonShape
            ) {
                Text("解鎖")
            }
            if (canUseBiometric) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = ::requestBiometricUnlock,
                    modifier = Modifier.fillMaxWidth(),
                    shape = SettingsButtonShape
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Text("  使用指紋／生物辨識")
                }
            }
        }
    }
}
