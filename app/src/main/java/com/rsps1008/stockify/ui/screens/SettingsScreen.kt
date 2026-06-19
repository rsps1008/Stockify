package com.rsps1008.stockify.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rsps1008.stockify.BuildConfig
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SettingsButtonShape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            application = application,
            realtimeStockDataService = application.realtimeStockDataService,
            exchangeRateService = application.exchangeRateService
        )
    )

    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val lastUpdateTime by viewModel.lastStockListUpdateTime.collectAsState()
    val lastUsUpdateTime by viewModel.lastUsStockListUpdateTime.collectAsState()
    val finnhubApiKey by viewModel.finnhubApiKey.collectAsState()
    val updatingStockListMarket by viewModel.updatingStockListMarket.collectAsState()

    val feeDiscount by viewModel.feeDiscount.collectAsState()
    val minFeeRegular by viewModel.minFeeRegular.collectAsState()
    val minFeeOddLot by viewModel.minFeeOddLot.collectAsState()
    val dividendFee by viewModel.dividendFee.collectAsState()
    val preDeductSellFees by viewModel.preDeductSellFees.collectAsState()
    val useCumulativeReturnRate by viewModel.useCumulativeReturnRate.collectAsState()
    val fetchInterval by viewModel.fetchInterval.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val stockDataSource by viewModel.stockDataSource.collectAsState()
    val usStockDataSource by viewModel.usStockDataSource.collectAsState()
    val notifyFallbackRepeatedly by viewModel.notifyFallbackRepeatedly.collectAsState()
    val taxRateNormalListedStock by viewModel.taxRateNormalListedStock.collectAsState()
    val taxRateDomesticStockEtf by viewModel.taxRateDomesticStockEtf.collectAsState()
    val taxRateBondEtf by viewModel.taxRateBondEtf.collectAsState()
    val taxRateDayTrading by viewModel.taxRateDayTrading.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    val privacyPolicyText = remember {
        context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }
    }

    val scope = rememberCoroutineScope()
    var finnhubApiKeyText by remember(finnhubApiKey) { mutableStateOf(finnhubApiKey) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onMessageShown()
        }
    }

    // ===== 版面：上方固定 Logo，下面才捲動 =====
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.stockify),
            contentDescription = "Stockify Logo",
            modifier = Modifier.fillMaxWidth(0.35f)
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("外觀", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))

                        val themeOptions = remember {
                            mapOf("System" to "系統預設", "Light" to "淺色", "Dark" to "深色")
                        }
                        var expanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = themeOptions[theme] ?: theme,
                                onValueChange = { },
                                label = { Text("主題") },
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                themeOptions.forEach { (key, value) ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = {
                                            scope.launch {
                                                delay(300)
                                                viewModel.setTheme(key)
                                            }
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("股票資料來源", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))

                        val dataSourceOptions = remember {
                            mapOf("TWSE" to "台灣證券交易所 (推薦)", "Yahoo" to "Yahoo! 奇摩股市")
                        }
                        var expandedDataSource by remember { mutableStateOf(false) }
                        val usDataSourceOptions = remember {
                            mapOf("Nasdaq" to "Nasdaq API (推薦)", "Yahoo" to "Yahoo! Finance")
                        }
                        var expandedUsDataSource by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expandedDataSource,
                            onExpandedChange = { expandedDataSource = !expandedDataSource },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = dataSourceOptions[stockDataSource] ?: stockDataSource,
                                onValueChange = { },
                                label = { Text("台股即時資料來源") },
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDataSource)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedDataSource,
                                onDismissRequest = { expandedDataSource = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                dataSourceOptions.forEach { (key, value) ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = {
                                            viewModel.setStockDataSource(key)
                                            expandedDataSource = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        ExposedDropdownMenuBox(
                            expanded = expandedUsDataSource,
                            onExpandedChange = { expandedUsDataSource = !expandedUsDataSource },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = usDataSourceOptions[usStockDataSource] ?: usStockDataSource,
                                onValueChange = { },
                                label = { Text("美股即時資料來源") },
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedUsDataSource)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedUsDataSource,
                                onDismissRequest = { expandedUsDataSource = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                usDataSourceOptions.forEach { (key, value) ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = {
                                            viewModel.setUsStockDataSource(key)
                                            expandedUsDataSource = false
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "*如果選擇的來源不可用，將自動採用另一個作為備用機制。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text("重複提示備援來源", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "當主要資料來源失效時，持續顯示通知",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = notifyFallbackRepeatedly,
                                onCheckedChange = viewModel::setNotifyFallbackRepeatedly
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val intervalOptions = listOf(10, 15, 30, 60)
                        var expandedInterval by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expandedInterval,
                            onExpandedChange = { expandedInterval = !expandedInterval },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = "$fetchInterval 秒",
                                onValueChange = { },
                                label = { Text("股價更新頻率(開盤刷新)") },
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedInterval)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedInterval,
                                onDismissRequest = { expandedInterval = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                intervalOptions.forEach { interval ->
                                    DropdownMenuItem(
                                        text = { Text("$interval 秒") },
                                        onClick = {
                                            viewModel.setFetchInterval(interval)
                                            expandedInterval = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("股票列表更新", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "新上市的股票可透過此區自行新增。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("台股股票列表", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.updateStockListFromTwse() },
                                            enabled = !isLoading,
                                            shape = SettingsButtonShape
                                        ) {
                                            Text("更新台股股票列表")
                                        }
                                        if (updatingStockListMarket == "TW") CircularProgressIndicator()

                                        val updateTimeText = lastUpdateTime?.let { "(${formatTimestamp(it)})" } ?: "(預設列表)"
                                        Text(text = updateTimeText, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("美股股票列表", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "更新前請先到 Finnhub 自行取得免費 API key，再填入下方欄位。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = { uriHandler.openUri("https://finnhub.io/register") }) {
                                        Text("前往 Finnhub 取得免費 API key")
                                    }
                                    OutlinedTextField(
                                        value = finnhubApiKeyText,
                                        onValueChange = { finnhubApiKeyText = it },
                                        label = { Text("Finnhub API key") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Ascii,
                                            autoCorrectEnabled = false,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                focusManager.clearFocus()
                                                viewModel.setFinnhubApiKey(finnhubApiKeyText.trim())
                                            }
                                        ),
                                        visualTransformation = VisualTransformation.None,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focusState ->
                                                if (!focusState.isFocused) {
                                                    viewModel.setFinnhubApiKey(finnhubApiKeyText.trim())
                                                }
                                            }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.updateStockListFromFinnhub() },
                                            enabled = !isLoading && finnhubApiKey.isNotBlank(),
                                            shape = SettingsButtonShape
                                        ) {
                                            Text("更新美股股票列表")
                                        }
                                        if (updatingStockListMarket == "US") CircularProgressIndicator()

                                        val usUpdateTimeText = lastUsUpdateTime?.let { "(${formatTimestamp(it)})" } ?: "(預設列表)"
                                        Text(text = usUpdateTimeText, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("損益計算設定", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("報酬率計算方式", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "預設使用剩餘部位報酬率，會以目前還留在這檔股票中的有效成本作為分母，適合看手上部位的資金效率。開啟累積報酬率後，會改用這檔股票歷來投入成本作為分母，適合看整段交易的總報酬，部分賣出後百分比會較保守。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("使用累積報酬率")
                                Text(
                                    if (useCumulativeReturnRate) "目前：整段交易的總投入報酬"
                                    else "目前：手上剩餘部位的資金效率",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = useCumulativeReturnRate,
                                onCheckedChange = { viewModel.setUseCumulativeReturnRate(it) }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("預先扣除賣出費用與稅金", modifier = Modifier.weight(1f))
                            Switch(
                                checked = preDeductSellFees,
                                onCheckedChange = { viewModel.setPreDeductSellFees(it) }
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("台股費稅設定", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("台股手續費", style = MaterialTheme.typography.titleLarge)
                                Spacer(modifier = Modifier.height(16.dp))

                                var feeDiscountText by remember { mutableStateOf(feeDiscount.toString()) }
                                LaunchedEffect(feeDiscount) { feeDiscountText = feeDiscount.toString() }
                                OutlinedTextField(
                                    value = feeDiscountText,
                                    onValueChange = {
                                        feeDiscountText = it
                                        it.toDoubleOrNull()?.let { discount -> viewModel.setFeeDiscount(discount) }
                                    },
                                    label = { Text("手續費折數 (例如 0.28)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                var minFeeRegularText by remember { mutableStateOf(minFeeRegular.toString()) }
                                LaunchedEffect(minFeeRegular) { minFeeRegularText = minFeeRegular.toString() }
                                OutlinedTextField(
                                    value = minFeeRegularText,
                                    onValueChange = {
                                        minFeeRegularText = it
                                        it.toIntOrNull()?.let { fee -> viewModel.setMinFeeRegular(fee) }
                                    },
                                    label = { Text("整股最低手續費 (元) - 股數為1000倍數時生效") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                var minFeeOddLotText by remember { mutableStateOf(minFeeOddLot.toString()) }
                                LaunchedEffect(minFeeOddLot) { minFeeOddLotText = minFeeOddLot.toString() }
                                OutlinedTextField(
                                    value = minFeeOddLotText,
                                    onValueChange = {
                                        minFeeOddLotText = it
                                        it.toIntOrNull()?.let { fee -> viewModel.setMinFeeOddLot(fee) }
                                    },
                                    label = { Text("零股最低手續費 (元)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                var dividendFeeText by remember { mutableStateOf(dividendFee.toString()) }
                                LaunchedEffect(dividendFee) { dividendFeeText = dividendFee.toString() }
                                OutlinedTextField(
                                    value = dividendFeeText,
                                    onValueChange = {
                                        dividendFeeText = it
                                        it.toIntOrNull()?.let { fee -> viewModel.setDividendFee(fee) }
                                    },
                                    label = { Text("除息手續費 (元)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("台股交易稅率", style = MaterialTheme.typography.titleLarge)
                                Spacer(modifier = Modifier.height(16.dp))

                                var taxRateNormalListedStockText by remember { mutableStateOf(taxRateNormalListedStock.toString()) }
                                LaunchedEffect(taxRateNormalListedStock) { taxRateNormalListedStockText = taxRateNormalListedStock.toString() }
                                OutlinedTextField(
                                    value = taxRateNormalListedStockText,
                                    onValueChange = {
                                        taxRateNormalListedStockText = it
                                        it.toDoubleOrNull()?.let { rate -> viewModel.setTaxRateNormalListedStock(rate) }
                                    },
                                    label = { Text("一般上市股票稅率 (例如 0.003)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                var taxRateDomesticStockEtfText by remember { mutableStateOf(taxRateDomesticStockEtf.toString()) }
                                LaunchedEffect(taxRateDomesticStockEtf) { taxRateDomesticStockEtfText = taxRateDomesticStockEtf.toString() }
                                OutlinedTextField(
                                    value = taxRateDomesticStockEtfText,
                                    onValueChange = {
                                        taxRateDomesticStockEtfText = it
                                        it.toDoubleOrNull()?.let { rate -> viewModel.setTaxRateDomesticStockEtf(rate) }
                                    },
                                    label = { Text("國內股票型 ETF 稅率 (例如 0.001)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                var taxRateBondEtfText by remember { mutableStateOf(taxRateBondEtf.toString()) }
                                LaunchedEffect(taxRateBondEtf) { taxRateBondEtfText = taxRateBondEtf.toString() }
                                OutlinedTextField(
                                    value = taxRateBondEtfText,
                                    onValueChange = {
                                        taxRateBondEtfText = it
                                        it.toDoubleOrNull()?.let { rate -> viewModel.setTaxRateBondEtf(rate) }
                                    },
                                    label = { Text("債券 ETF 稅率 (例如 0)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                var taxRateDayTradingText by remember { mutableStateOf(taxRateDayTrading.toString()) }
                                LaunchedEffect(taxRateDayTrading) { taxRateDayTradingText = taxRateDayTrading.toString() }
                                OutlinedTextField(
                                    value = taxRateDayTradingText,
                                    onValueChange = {
                                        taxRateDayTradingText = it
                                        it.toDoubleOrNull()?.let { rate -> viewModel.setTaxRateDayTrading(rate) }
                                    },
                                    label = { Text("現股當沖稅率 (例如 0.0015)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("隱私政策", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showPrivacyPolicyDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = SettingsButtonShape
                        ) {
                            Text("查看隱私政策")
                        }
                    }
                }
            }

            item {
                Text(
                    text = "目前版本 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    if (showPrivacyPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicyDialog = false },
            title = { Text("隱私政策") },
            text = {
                Column(
                    modifier = Modifier
                        .height(420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text = privacyPolicyText)
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicyDialog = false }) {
                    Text("關閉")
                }
            }
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
