package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.data.BankDeposit
import com.rsps1008.stockify.data.Loan
import com.rsps1008.stockify.ui.viewmodel.AssetOverviewUiState
import com.rsps1008.stockify.ui.viewmodel.AssetOverviewViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.hypot
import java.util.Locale

private data class AssetSlice(
    val id: String,
    val label: String,
    val value: Double,
    val color: Color,
    val isLiability: Boolean = false
)

private enum class AssetChartMode {
    CATEGORY,
    INDIVIDUAL
}

@Composable
fun AssetOverviewScreen(navController: NavController) {
    val application = androidx.compose.ui.platform.LocalContext.current
        .applicationContext as StockifyApplication
    val viewModel: AssetOverviewViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            realtimeStockDataService = application.realtimeStockDataService,
            exchangeRateService = application.exchangeRateService
        )
    )
    val uiState by viewModel.uiState.collectAsState()
    var chartMode by remember { mutableStateOf(AssetChartMode.CATEGORY) }
    var editingDeposit by remember { mutableStateOf<BankDeposit?>(null) }
    var isEditorVisible by remember { mutableStateOf(false) }
    var deletingDeposit by remember { mutableStateOf<BankDeposit?>(null) }
    var editingLoan by remember { mutableStateOf<Loan?>(null) }
    var isLoanEditorVisible by remember { mutableStateOf(false) }
    var deletingLoan by remember { mutableStateOf<Loan?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("資產總覽", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
        ) {
            item {
                AssetSummaryCard(
                    uiState = uiState,
                    chartMode = chartMode,
                    onChartModeChange = { chartMode = it }
                )
            }
            item {
                BankDepositSection(
                    deposits = uiState.bankDeposits,
                    onAdd = {
                        editingDeposit = null
                        isEditorVisible = true
                    },
                    onEdit = { deposit ->
                        editingDeposit = deposit
                        isEditorVisible = true
                    },
                    onDelete = { deposit -> deletingDeposit = deposit }
                )
            }
            item {
                LoanSection(
                    loans = uiState.loans,
                    onAdd = {
                        editingLoan = null
                        isLoanEditorVisible = true
                    },
                    onEdit = { loan ->
                        editingLoan = loan
                        isLoanEditorVisible = true
                    },
                    onDelete = { loan -> deletingLoan = loan }
                )
            }
        }
    }

    if (isEditorVisible) {
        BankDepositEditorDialog(
            deposit = editingDeposit,
            onDismiss = { isEditorVisible = false },
            onSave = { id, name, amount ->
                viewModel.saveBankDeposit(id, name, amount)
                isEditorVisible = false
            }
        )
    }

    deletingDeposit?.let { deposit ->
        AlertDialog(
            onDismissRequest = { deletingDeposit = null },
            title = { Text("刪除存款") },
            text = { Text("確定要刪除「${deposit.name}」嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBankDeposit(deposit.id)
                        deletingDeposit = null
                    }
                ) {
                    Text("刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingDeposit = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (isLoanEditorVisible) {
        LoanEditorDialog(
            loan = editingLoan,
            onDismiss = { isLoanEditorVisible = false },
            onSave = { id, name, amount ->
                viewModel.saveLoan(id, name, amount)
                isLoanEditorVisible = false
            }
        )
    }

    deletingLoan?.let { loan ->
        AlertDialog(
            onDismissRequest = { deletingLoan = null },
            title = { Text("刪除貸款") },
            text = { Text("確定要刪除「${loan.name}」嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLoan(loan.id)
                        deletingLoan = null
                    }
                ) {
                    Text("刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingLoan = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AssetSummaryCard(
    uiState: AssetOverviewUiState,
    chartMode: AssetChartMode,
    onChartModeChange: (AssetChartMode) -> Unit
) {
    val slices = buildAssetSlices(uiState, chartMode)
    val chartTotal = slices.sumOf { abs(it.value) }
    var selectedSliceId by remember { mutableStateOf<String?>(null) }
    val selectedSlice = slices.firstOrNull { it.id == selectedSliceId }
    LaunchedEffect(slices.map { it.id }) {
        if (selectedSliceId != null && slices.none { it.id == selectedSliceId }) {
            selectedSliceId = null
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chartMode == AssetChartMode.CATEGORY) {
                    Button(
                        onClick = {
                            selectedSliceId = null
                            onChartModeChange(AssetChartMode.CATEGORY)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("資產類別")
                    }
                } else {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            selectedSliceId = null
                            onChartModeChange(AssetChartMode.CATEGORY)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("資產類別")
                    }
                }
                if (chartMode == AssetChartMode.INDIVIDUAL) {
                    Button(
                        onClick = {
                            selectedSliceId = null
                            onChartModeChange(AssetChartMode.INDIVIDUAL)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("個別標的")
                    }
                } else {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            selectedSliceId = null
                            onChartModeChange(AssetChartMode.INDIVIDUAL)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("個別標的")
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AssetPieChart(
                    slices = slices,
                    total = chartTotal,
                    separatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    selectedSliceId = selectedSliceId,
                    onSliceSelected = { id ->
                        selectedSliceId = if (selectedSliceId == id) null else id
                    }
                )
                AssetChartCenterContent(
                    selectedSlice = selectedSlice,
                    netAssets = uiState.netAssets,
                    chartTotal = chartTotal
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            slices.forEach { slice ->
                AssetLegendRow(
                    slice = slice,
                    total = chartTotal,
                    isSelected = selectedSliceId == slice.id,
                    onClick = {
                        selectedSliceId = if (selectedSliceId == slice.id) null else slice.id
                    }
                )
            }
        }
    }
}

@Composable
private fun AssetChartCenterContent(
    selectedSlice: AssetSlice?,
    netAssets: Double,
    chartTotal: Double
) {
    val amount = selectedSlice?.value ?: netAssets
    val amountColor = if (amount < 0.0) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.size(112.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (selectedSlice == null) {
                Text(
                    "淨資產",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatTwd(netAssets),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                AutoFitChartLabel(selectedSlice.label)
                Text(
                    formatTwd(selectedSlice.value),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatPercentage(abs(selectedSlice.value), chartTotal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AutoFitChartLabel(text: String) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val baseStyle = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        val fontSize = listOf(12, 11, 10, 9, 8, 7)
            .map { it.sp }
            .firstOrNull { candidateSize ->
                val layout = textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = baseStyle.copy(fontSize = candidateSize),
                    constraints = Constraints(maxWidth = maxWidthPx),
                    maxLines = 1,
                    softWrap = false
                )
                !layout.didOverflowWidth
            }
            ?: 7.sp

        Text(
            text = text,
            style = baseStyle.copy(fontSize = fontSize),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AssetPieChart(
    slices: List<AssetSlice>,
    total: Double,
    separatorColor: Color,
    selectedSliceId: String?,
    onSliceSelected: (String) -> Unit
) {
    Box(modifier = Modifier.size(220.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices, total, selectedSliceId) {
                    detectTapGestures { tapOffset ->
                        findTappedSlice(
                            slices,
                            total,
                            tapOffset,
                            size.width.toFloat(),
                            size.height.toFloat()
                        )
                            ?.let { onSliceSelected(it.id) }
                    }
                }
        ) {
            drawAssetSlices(slices, total, separatorColor, selectedSliceId)
        }
    }
}

private fun DrawScope.drawAssetSlices(
    slices: List<AssetSlice>,
    total: Double,
    separatorColor: Color,
    selectedSliceId: String?
) {
    if (total <= 0.0) return

    val diameter = size.minDimension
    val topLeft = Offset(
        x = (size.width - diameter) / 2f,
        y = (size.height - diameter) / 2f
    )
    val chartSize = Size(diameter, diameter)
    var startAngle = -90f
    slices.forEach { slice ->
        if (slice.value != 0.0) {
            val sweepAngle = (abs(slice.value) / total * 360.0).toFloat()
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = true,
                topLeft = topLeft,
                size = chartSize
            )
            drawArc(
                color = separatorColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = true,
                topLeft = topLeft,
                size = chartSize,
                style = Stroke(width = 1.5.dp.toPx())
            )
            if (slice.id == selectedSliceId) {
                drawArc(
                    color = Color.White,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = true,
                    topLeft = topLeft,
                    size = chartSize,
                    style = Stroke(width = 6.dp.toPx())
                )
            }
            startAngle += sweepAngle
        }
    }
}

private fun findTappedSlice(
    slices: List<AssetSlice>,
    total: Double,
    tapOffset: Offset,
    width: Float,
    height: Float
): AssetSlice? {
    if (total <= 0.0) return null

    val centerX = width / 2f
    val centerY = height / 2f
    val dx = tapOffset.x - centerX
    val dy = tapOffset.y - centerY
    val radius = hypot(dx.toDouble(), dy.toDouble())
    if (radius > minOf(width, height) / 2f) return null

    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    if (angle < 0f) angle += 360f

    var startAngle = 0f
    slices.forEach { slice ->
        if (slice.value != 0.0) {
            val sweepAngle = (abs(slice.value) / total * 360.0).toFloat()
            if (angle >= startAngle && angle < startAngle + sweepAngle) {
                return slice
            }
            startAngle += sweepAngle
        }
    }
    return null
}

@Composable
private fun AssetLegendRow(
    slice: AssetSlice,
    total: Double,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(slice.color.copy(alpha = if (isSelected) 0.18f else 0.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(slice.color)
        )
        Text(
            text = slice.label,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatTwd(slice.value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (slice.value < 0.0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = formatPercentage(abs(slice.value), total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BankDepositSection(
    deposits: List<BankDeposit>,
    onAdd: () -> Unit,
    onEdit: (BankDeposit) -> Unit,
    onDelete: (BankDeposit) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("存款", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))

            if (deposits.isEmpty()) {
                Text(
                    "尚未新增存款。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                deposits.forEach { deposit ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(deposit.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${formatTwd(deposit.amount)} 元",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onEdit(deposit) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "編輯 ${deposit.name}")
                        }
                        IconButton(onClick = { onDelete(deposit) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "刪除 ${deposit.name}")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("新增存款")
            }
        }
    }
}

@Composable
private fun BankDepositEditorDialog(
    deposit: BankDeposit?,
    onDismiss: () -> Unit,
    onSave: (Long?, String, Double) -> Unit
) {
    var name by remember(deposit?.id) { mutableStateOf(deposit?.name ?: "") }
    var amountText by remember(deposit?.id) {
        mutableStateOf(deposit?.amount?.let(::formatInputAmount) ?: "0")
    }
    var errorMessage by remember(deposit?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deposit == null) "新增存款" else "編輯存款") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        errorMessage = null
                    },
                    label = { Text("名稱") },
                    placeholder = { Text("例如：存款 A") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        errorMessage = null
                    },
                    label = { Text("金額（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = amountText.trim().toDoubleOrNull()
                    errorMessage = when {
                        name.trim().isBlank() -> "請輸入存款名稱"
                        amount == null || !amount.isFinite() -> "請輸入有效金額"
                        amount < 0.0 -> "金額不可小於 0"
                        else -> null
                    }
                    if (errorMessage == null && amount != null) {
                        onSave(deposit?.id, name, amount)
                    }
                }
            ) {
                Text("儲存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun LoanSection(
    loans: List<Loan>,
    onAdd: () -> Unit,
    onEdit: (Loan) -> Unit,
    onDelete: (Loan) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("貸款", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))

            if (loans.isEmpty()) {
                Text(
                    "尚未新增貸款。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                loans.forEach { loan ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(loan.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "-${formatTwd(loan.amount)} 元",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = { onEdit(loan) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "編輯 ${loan.name}")
                        }
                        IconButton(onClick = { onDelete(loan) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "刪除 ${loan.name}")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("新增貸款")
            }
        }
    }
}

@Composable
private fun LoanEditorDialog(
    loan: Loan?,
    onDismiss: () -> Unit,
    onSave: (Long?, String, Double) -> Unit
) {
    var name by remember(loan?.id) { mutableStateOf(loan?.name ?: "") }
    var amountText by remember(loan?.id) {
        mutableStateOf(loan?.amount?.let(::formatInputAmount) ?: "0")
    }
    var errorMessage by remember(loan?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (loan == null) "新增貸款" else "編輯貸款") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        errorMessage = null
                    },
                    label = { Text("名稱") },
                    placeholder = { Text("例如：房屋貸款") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        errorMessage = null
                    },
                    label = { Text("剩餘貸款金額（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = amountText.trim().toDoubleOrNull()
                    errorMessage = when {
                        name.trim().isBlank() -> "請輸入貸款名稱"
                        amount == null || !amount.isFinite() -> "請輸入有效金額"
                        amount < 0.0 -> "金額不可小於 0"
                        else -> null
                    }
                    if (errorMessage == null && amount != null) {
                        onSave(loan?.id, name, amount)
                    }
                }
            ) {
                Text("儲存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun buildAssetSlices(
    uiState: AssetOverviewUiState,
    chartMode: AssetChartMode
): List<AssetSlice> {
    val colors = listOf(
        Color(0xFF4C78A8),
        Color(0xFFF58518),
        Color(0xFF54A24B),
        Color(0xFFE45756),
        Color(0xFFB279A2),
        Color(0xFFFF9DA6)
    )
    return when (chartMode) {
        AssetChartMode.CATEGORY -> buildList {
            val taiwan = AssetSlice(
                "category_tw",
                "台股",
                uiState.taiwanStockValue.coerceAtLeast(0.0),
                colors[0]
            )
            val us = AssetSlice(
                "category_us",
                "美股",
                uiState.usStockValue.coerceAtLeast(0.0),
                colors[1]
            )
            val bank = AssetSlice(
                "category_bank",
                "存款",
                uiState.totalBankDeposit.coerceAtLeast(0.0),
                colors[2]
            )
            val loan = AssetSlice(
                id = "category_loan",
                label = "貸款",
                value = -uiState.totalLoan.coerceAtLeast(0.0),
                color = Color(0xFFD32F2F),
                isLiability = true
            )
            if (taiwan.value > 0.0) add(taiwan)
            if (us.value > 0.0) add(us)
            if (bank.value > 0.0) add(bank)
            if (loan.value != 0.0) add(loan)
        }

        AssetChartMode.INDIVIDUAL -> buildList {
            uiState.stockValues
                .filter { it.marketValue > 0.0 }
                .sortedByDescending { it.marketValue }
                .forEachIndexed { index, stockValue ->
                    add(
                        AssetSlice(
                            id = "stock:${stockValue.stock.market}:${stockValue.stock.code}",
                            label = formatStockLabel(stockValue.stock.code, stockValue.stock.name),
                            value = stockValue.marketValue,
                            color = colors[index % colors.size]
                        )
                    )
                }
            uiState.bankDeposits.forEachIndexed { index, deposit ->
                add(
                    AssetSlice(
                        id = "bank:${deposit.id}",
                        label = deposit.name,
                        value = deposit.amount.coerceAtLeast(0.0),
                        color = colors[(index + uiState.stockValues.size) % colors.size]
                    )
                )
            }
            uiState.loans.forEach { loan ->
                add(
                    AssetSlice(
                        id = "loan:${loan.id}",
                        label = loan.name,
                        value = -loan.amount.coerceAtLeast(0.0),
                        color = Color(0xFFD32F2F),
                        isLiability = true
                    )
                )
            }
        }
    }
}

private fun formatTwd(value: Double): String = String.format(Locale.US, "%,.0f", value)

private fun formatInputAmount(value: Double): String = String.format(Locale.US, "%.0f", value)

private fun formatPercentage(value: Double, total: Double): String {
    val percentage = if (total > 0.0) value / total * 100.0 else 0.0
    return String.format(Locale.US, "%.1f%%", percentage)
}

private fun formatStockLabel(code: String, name: String): String {
    return name.trim().takeIf { it.isNotBlank() }?.let { "$code $it" } ?: code
}
