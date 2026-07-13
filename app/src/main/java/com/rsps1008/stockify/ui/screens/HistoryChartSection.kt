package com.rsps1008.stockify.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import com.rsps1008.stockify.ui.viewmodel.PersonalHistoryPoint
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.HistoryRange
import com.rsps1008.stockify.ui.viewmodel.HistoryState
import com.rsps1008.stockify.ui.viewmodel.StockDetailViewModel
import kotlin.math.roundToInt
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowDropDown

import com.rsps1008.stockify.ui.viewmodel.HoldingsViewModel
import com.rsps1008.stockify.data.HomeDisplayMode
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.formatHomeAmount
import com.rsps1008.stockify.data.formatShareCount

@Composable
fun HistoryChartSection(
    viewModel: StockDetailViewModel,
    modifier: Modifier = Modifier
) {
    val historyState by viewModel.historyState.collectAsState()
    val isExpanded by viewModel.detailHistoryChartExpanded.collectAsState()
    val holdingInfo by viewModel.holdingInfo.collectAsState()
    val displayMode = if (StockMarket.isUs(holdingInfo?.stock?.market)) {
        HomeDisplayMode.US
    } else {
        HomeDisplayMode.TW
    }
    HistoryChartSectionContent(
        historyState = historyState,
        onRangeSelected = { viewModel.fetchStockHistory(it) },
        isExpanded = isExpanded,
        onToggleExpanded = { viewModel.setDetailHistoryChartExpanded(it) },
        displayMode = displayMode,
        modifier = modifier
    )
}

@Composable
fun HistoryChartSection(
    viewModel: HoldingsViewModel,
    modifier: Modifier = Modifier
) {
    val historyState by viewModel.historyState.collectAsState()
    val isExpanded by viewModel.homeHistoryChartExpanded.collectAsState()
    val displayMode by viewModel.homeDisplayMode.collectAsState()
    val selectedRange by viewModel.selectedHomeHistoryRange.collectAsState()
    HistoryChartSectionContent(
        historyState = historyState,
        onRangeSelected = { viewModel.fetchPortfolioHistory(it) },
        isExpanded = isExpanded,
        onToggleExpanded = { viewModel.setHomeHistoryChartExpanded(it) },
        displayMode = displayMode,
        controlledSelectedRange = selectedRange,
        modifier = modifier
    )
}

@Composable
fun HistoryChartSectionContent(
    historyState: HistoryState,
    onRangeSelected: (HistoryRange) -> Unit,
    isExpanded: Boolean,
    onToggleExpanded: (Boolean) -> Unit,
    displayMode: String = HomeDisplayMode.TW,
    controlledSelectedRange: HistoryRange? = null,
    modifier: Modifier = Modifier
) {
    var selectedRange by remember { mutableStateOf(HistoryRange.ONE_MONTH) }
    var selectedMetric by remember { mutableStateOf("市值") }
    val normalizedDisplayMode = HomeDisplayMode.normalize(displayMode)
    LaunchedEffect(controlledSelectedRange) {
        controlledSelectedRange?.let { selectedRange = it }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 280))
            .padding(
                top = 8.dp,
                bottom = 0.dp
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = if (isExpanded) 16.dp else 4.dp
            )
        ) {
            // Header Row: Title & Toggle Expand Arrow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded(!isExpanded) }
                    .padding(vertical = if (isExpanded) 4.dp else 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "歷史走勢與報酬",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                // Metric toggler and range selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricSelectorRow(
                        selectedMetric = selectedMetric,
                        onMetricSelected = { selectedMetric = it }
                    )
                    
                    RangeSelectorRow(
                        selectedRange = selectedRange,
                        onRangeSelected = { range ->
                            selectedRange = range
                            onRangeSelected(range)
                        },
                        enabled = historyState !is HistoryState.Loading
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // State content
                Crossfade(
                    targetState = historyState to normalizedDisplayMode,
                    label = "HistoryStateCrossfade"
                ) { (state, crossfadeDisplayMode) ->
                    when (state) {
                        is HistoryState.Idle -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(146.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("無資料")
                            }
                        }
                        is HistoryState.Loading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(146.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    progress = { state.progress.coerceIn(0f, 1f) },
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = state.statusText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is HistoryState.Error -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(146.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = { onRangeSelected(selectedRange) }) {
                                    Text("重試")
                                }
                            }
                        }
                        is HistoryState.Success -> {
                            HistoricalChartContent(
                                points = state.points,
                                selectedMetric = selectedMetric,
                                displayMode = crossfadeDisplayMode
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricSelectorRow(
    selectedMetric: String,
    onMetricSelected: (String) -> Unit
) {
    val metrics = listOf("市值", "報酬率")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp)
    ) {
        metrics.forEach { metric ->
            val isSelected = selectedMetric == metric
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.secondary
                        else Color.Transparent
                    )
                    .clickable { onMetricSelected(metric) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = metric,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RangeSelectorRow(
    selectedRange: HistoryRange,
    onRangeSelected: (HistoryRange) -> Unit,
    enabled: Boolean
) {
    val ranges = listOf(
        HistoryRange.ONE_MONTH to "1M",
        HistoryRange.SIX_MONTHS to "6M",
        HistoryRange.ONE_YEAR to "1Y"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (enabled) 0.5f else 0.2f
                )
            )
            .padding(4.dp)
    ) {
        ranges.forEach { (range, label) ->
            val isSelected = selectedRange == range
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) {
                            if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .then(
                        if (enabled) Modifier.clickable { onRangeSelected(range) }
                        else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) {
                        if (enabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                    } else {
                        if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoricalChartContent(
    points: List<PersonalHistoryPoint>,
    selectedMetric: String,
    displayMode: String
) {
    if (points.isEmpty()) return

    val firstPoint = points.first()
    val lastPoint = points.last()

    // Tracking active touch point index
    var activeIndex by remember(points) { mutableStateOf<Int?>(null) }

    // Resolve active point details
    val displayPoint = activeIndex?.let { points.getOrNull(it) } ?: lastPoint
    val isInteractive = activeIndex != null

    val displayShares = displayPoint.shares
    val displayMarketValue = displayPoint.marketValue
    val displayPL = displayPoint.totalPL
    val displayPLPercentage = displayPoint.totalPLPercentage

    val gainColor = StockifyAppTheme.stockColors.gain
    val lossColor = StockifyAppTheme.stockColors.loss
    val changeColor = if (displayPLPercentage >= 0) gainColor else lossColor
    val currencyLabel = if (HomeDisplayMode.normalize(displayMode) == HomeDisplayMode.US) "US$" else "NT$"
    val currencyName = if (HomeDisplayMode.normalize(displayMode) == HomeDisplayMode.US) "美元" else "台幣"

    Column(modifier = Modifier.fillMaxWidth()) {
        // Row 1: Market Value & Shares
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isInteractive) "選股市值 ($currencyName)" else "目前市值 ($currencyName)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$currencyLabel ${formatHomeAmount(displayMarketValue, displayMode)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "持股數量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${formatShareCount(displayShares)} 股",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: P/L & Date
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isInteractive) "選股損益" else "累積損益",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = String.format(
                        "%+.2f%% (%s %s%s)",
                        displayPLPercentage,
                        currencyLabel,
                        if (displayPL >= 0) "+" else "",
                        formatHomeAmount(displayPL, displayMode)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = changeColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isInteractive) "選取日期" else "統計區間",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isInteractive) displayPoint.date.replace("-", "/")
                           else {
                               val firstYrMo = if (firstPoint.date.length >= 7) firstPoint.date.substring(0, 7).replace("-", "/") else firstPoint.date
                               val lastYrMo = if (lastPoint.date.length >= 7) lastPoint.date.substring(0, 7).replace("-", "/") else lastPoint.date
                               "$firstYrMo ~ $lastYrMo"
                           },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Draw Interactive Line Chart
        InteractiveLineChart(
            points = points,
            selectedMetric = selectedMetric,
            activeIndex = activeIndex,
            onActiveIndexChanged = { activeIndex = it },
            lineColor = if (lastPoint.totalPLPercentage >= 0) gainColor else lossColor,
            displayMode = displayMode
        )
    }
}

@Composable
private fun InteractiveLineChart(
    points: List<PersonalHistoryPoint>,
    selectedMetric: String,
    activeIndex: Int?,
    onActiveIndexChanged: (Int?) -> Unit,
    lineColor: Color,
    displayMode: String
) {
    val values = points.map {
        if (selectedMetric == "市值") it.marketValue else it.totalPLPercentage
    }
    val isPercentage = selectedMetric == "報酬率"
    val profitValues = points.map { it.totalPLPercentage }
    val shouldColorByProfitLoss = selectedMetric == "市值" || isPercentage
    val rawMinVal = values.minOrNull() ?: 0.0
    val rawMaxVal = values.maxOrNull() ?: 1.0
    val rawRange = (rawMaxVal - rawMinVal).coerceAtLeast(0.01)

    // Keep the chart readable for large positive/negative returns. Only include
    // 0% in the visible range when it is reasonably close to the data range.
    val shouldIncludeZero = isPercentage && when {
        rawMinVal <= 0.0 && rawMaxVal >= 0.0 -> true
        rawMinVal > 0.0 -> rawMinVal <= rawRange * 0.5
        else -> -rawMaxVal <= rawRange * 0.5
    }
    val minVal = if (shouldIncludeZero) minOf(rawMinVal, 0.0) else rawMinVal
    val maxVal = if (shouldIncludeZero) maxOf(rawMaxVal, 0.0) else rawMaxVal
    val valRange = (maxVal - minVal).coerceAtLeast(0.01)

    val density = LocalDensity.current
    val topPaddingPx = with(density) { 16.dp.toPx() }
    val bottomPaddingPx = with(density) { 16.dp.toPx() }
    val currencyLabel = if (HomeDisplayMode.normalize(displayMode) == HomeDisplayMode.US) "US$" else "NT$"
    val gainColor = StockifyAppTheme.stockColors.gain
    val lossColor = StockifyAppTheme.stockColors.loss
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .pointerInput(points, selectedMetric) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null) {
                            if (change.pressed) {
                                val x = change.position.x
                                val idx = (x / size.width * (points.size - 1))
                                    .roundToInt()
                                    .coerceIn(0, points.size - 1)
                                onActiveIndexChanged(idx)
                                change.consume()
                            } else {
                                onActiveIndexChanged(null)
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val chartHeight = height - topPaddingPx - bottomPaddingPx

            // 1. Draw Gridlines (3 horizontal lines)
            val gridLineCount = 3
            val gridColor = Color.Gray.copy(alpha = 0.15f)
            val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

            for (i in 0 until gridLineCount) {
                val y = topPaddingPx + (chartHeight / (gridLineCount - 1)) * i
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = pathEffect
                )
            }

            // Draw a stronger 0% reference line only when it is inside the
            // selected percentage chart range. This avoids compressing charts
            // whose returns are, for example, entirely between 100% and 200%.
            if (isPercentage && shouldIncludeZero) {
                val zeroY = topPaddingPx + chartHeight * (1.0 - ((0.0 - minVal) / valRange)).toFloat()
                drawLine(
                    color = zeroLineColor,
                    start = Offset(0f, zeroY),
                    end = Offset(width, zeroY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = pathEffect
                )
            }

            // Calculate point positions
            val sizeCount = points.size
            val xStep = width / (sizeCount - 1).coerceAtLeast(1)
            val coordinates = values.mapIndexed { idx, valItem ->
                val x = idx * xStep
                val normalizedVal = (valItem - minVal) / valRange
                val y = topPaddingPx + chartHeight * (1.0 - normalizedVal).toFloat()
                Offset(x, y)
            }

            // 2. Draw Trend Line & Gradient below it
            if (coordinates.size >= 2) {
                val linePath = Path().apply {
                    moveTo(coordinates[0].x, coordinates[0].y)
                    for (i in 1 until coordinates.size) {
                        lineTo(coordinates[i].x, coordinates[i].y)
                    }
                }

                if (shouldColorByProfitLoss) {
                    // Fill each segment with the same gain/loss color as the
                    // line above it. Splitting crossing segments keeps the
                    // gradient transition aligned with the 0% crossing point.
                    for (i in 0 until coordinates.lastIndex) {
                        val start = coordinates[i]
                        val end = coordinates[i + 1]
                        val startValue = profitValues[i]
                        val endValue = profitValues[i + 1]

                        fun drawSegmentFill(segmentStart: Offset, segmentEnd: Offset, color: Color) {
                            val segmentFillPath = Path().apply {
                                moveTo(segmentStart.x, segmentStart.y)
                                lineTo(segmentEnd.x, segmentEnd.y)
                                lineTo(segmentEnd.x, height)
                                lineTo(segmentStart.x, height)
                                close()
                            }
                            drawPath(
                                path = segmentFillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(color.copy(alpha = 0.25f), Color.Transparent),
                                    startY = topPaddingPx,
                                    endY = height
                                )
                            )
                        }

                        if ((startValue < 0.0 && endValue > 0.0) ||
                            (startValue > 0.0 && endValue < 0.0)
                        ) {
                            val fraction = (0.0 - startValue) / (endValue - startValue)
                            val zeroPoint = Offset(
                                x = start.x + (end.x - start.x) * fraction.toFloat(),
                                y = start.y + (end.y - start.y) * fraction.toFloat()
                            )
                            drawSegmentFill(
                                start,
                                zeroPoint,
                                if (startValue >= 0.0) gainColor else lossColor
                            )
                            drawSegmentFill(
                                zeroPoint,
                                end,
                                if (endValue >= 0.0) gainColor else lossColor
                            )
                        } else {
                            drawSegmentFill(
                                start,
                                end,
                                if (startValue >= 0.0) gainColor else lossColor
                            )
                        }
                    }
                } else {
                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(width, height)
                        lineTo(0f, height)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                lineColor.copy(alpha = 0.25f),
                                Color.Transparent
                            ),
                            startY = topPaddingPx,
                            endY = height
                        )
                    )
                }

                // Draw the line in positive/negative segments for returns.
                // When a segment crosses 0%, split it at the exact crossing
                // point so the color change happens on the baseline.
                if (shouldColorByProfitLoss) {
                    for (i in 0 until coordinates.lastIndex) {
                        val start = coordinates[i]
                        val end = coordinates[i + 1]
                        val startValue = profitValues[i]
                        val endValue = profitValues[i + 1]

                        if ((startValue < 0.0 && endValue > 0.0) ||
                            (startValue > 0.0 && endValue < 0.0)
                        ) {
                            val fraction = (0.0 - startValue) / (endValue - startValue)
                            val zeroPoint = Offset(
                                x = start.x + (end.x - start.x) * fraction.toFloat(),
                                y = start.y + (end.y - start.y) * fraction.toFloat()
                            )
                            drawLine(
                                color = if (startValue >= 0.0) gainColor else lossColor,
                                start = start,
                                end = zeroPoint,
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = if (endValue >= 0.0) gainColor else lossColor,
                                start = zeroPoint,
                                end = end,
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        } else {
                            drawLine(
                                color = if (startValue >= 0.0) gainColor else lossColor,
                                start = start,
                                end = end,
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                } else {
                    drawPath(
                        path = linePath,
                        color = lineColor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    )
                }
            }

            // 3. Draw Interactive Tooltip Guideline and highlights
            if (activeIndex != null && activeIndex in coordinates.indices) {
                val activePoint = coordinates[activeIndex]

                // Draw vertical guideline
                drawLine(
                    color = Color.Gray.copy(alpha = 0.4f),
                    start = Offset(activePoint.x, 0f),
                    end = Offset(activePoint.x, height),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = pathEffect
                )

                // Draw highlight points
                drawCircle(
                    color = lineColor.copy(alpha = 0.2f),
                    radius = 8.dp.toPx(),
                    center = activePoint
                )
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = activePoint
                )
            }
        }

        // 4. Labels Overlay
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .align(Alignment.CenterStart),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isPercentage) String.format("%+.1f%%", maxVal) else "$currencyLabel ${formatHomeAmount(maxVal, displayMode)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = if (isPercentage) String.format("%+.1f%%", (maxVal + minVal) / 2) else "$currencyLabel ${formatHomeAmount((maxVal + minVal) / 2, displayMode)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                text = if (isPercentage) String.format("%+.1f%%", minVal) else "$currencyLabel ${formatHomeAmount(minVal, displayMode)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
