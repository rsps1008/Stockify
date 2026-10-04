package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rsps1008.stockify.data.DailyPerformance
import com.rsps1008.stockify.data.HomeDisplayMode
import com.rsps1008.stockify.data.PerformanceCalendarCalculationSupport
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.PersonalHistoryPoint
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectTapGestures

@Composable
fun PerformanceCalendarSection(
    points: List<PersonalHistoryPoint>,
    selectedYearMonth: String?,
    displayMode: String,
    onYearMonthSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val availableMonths = remember(points) {
        points.mapNotNull { point -> point.date.takeIf { it.length >= 7 }?.take(7) }
            .distinct()
            .sorted()
    }
    val latestMonth = availableMonths.lastOrNull() ?: return
    val currentMonth = YearMonth.now()
    val initialMonth = selectedYearMonth
        ?.takeIf { it in availableMonths }
        ?: latestMonth
    val displayedMonth = YearMonth.parse(initialMonth)
    val monthData = remember(points, initialMonth) {
        PerformanceCalendarCalculationSupport.calculateMonthlyPerformance(points, initialMonth)
    } ?: return
    var selectedDate by remember(initialMonth) { mutableStateOf<String?>(null) }
    val gainColor = StockifyAppTheme.stockColors.gain
    val lossColor = StockifyAppTheme.stockColors.loss
    val isUs = HomeDisplayMode.normalize(displayMode) == HomeDisplayMode.US
    val currency = if (isUs) "US$" else "NT$"

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                enabled = displayedMonth.minusMonths(1).toString() in availableMonths,
                onClick = { onYearMonthSelected(displayedMonth.minusMonths(1).toString()) }
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "上一個月")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = displayedMonth.format(DateTimeFormatter.ofPattern("yyyy/MM")) + " 報酬",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = signedAmount(monthData.profitLoss),
                    style = MaterialTheme.typography.titleMedium,
                    color = performanceColor(monthData.profitLoss, gainColor, lossColor),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = signedPercent(monthData.returnPercentage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = performanceColor(monthData.returnPercentage, gainColor, lossColor)
                )
            }
            IconButton(
                enabled = displayedMonth < currentMonth &&
                    displayedMonth.plusMonths(1).toString() in availableMonths,
                onClick = { onYearMonthSelected(displayedMonth.plusMonths(1).toString()) }
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "下一個月")
            }
        }

        Spacer(Modifier.height(8.dp))
        CalendarGrid(
            month = displayedMonth,
            performances = monthData.dailyItems.associateBy { it.date },
            selectedDate = selectedDate,
            gainColor = gainColor,
            lossColor = lossColor,
            onDateSelected = { date -> selectedDate = date }
        )
        Spacer(Modifier.height(14.dp))
        Text("每日損益", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        PerformanceBarChart(
            items = monthData.dailyItems,
            selectedDate = selectedDate,
            gainColor = gainColor,
            lossColor = lossColor,
            onDateSelected = { selectedDate = it }
        )
        selectedDate?.let { date ->
            monthData.dailyItems.firstOrNull { it.date == date }?.let { item ->
                Text(
                    text = "${date.replace('-', '/')}　$currency ${signedAmount(item.profitLoss)}　${signedPercent(item.returnPercentage)}",
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = performanceColor(item.profitLoss, gainColor, lossColor)
                )
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    month: YearMonth,
    performances: Map<String, DailyPerformance>,
    selectedDate: String?,
    gainColor: Color,
    lossColor: Color,
    onDateSelected: (String) -> Unit
) {
    val days = listOf("日", "一", "二", "三", "四", "五", "六")
    Row(Modifier.fillMaxWidth()) {
        days.forEach { day ->
            Text(day, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val firstOffset = month.atDay(1).dayOfWeek.value % 7
    val cells = List(firstOffset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { day ->
                if (day == null) Spacer(Modifier.weight(1f).height(60.dp))
                else CalendarCell(
                    date = day,
                    performance = performances[day.toString()],
                    isSelected = selectedDate == day.toString(),
                    gainColor = gainColor,
                    lossColor = lossColor,
                    onClick = { onDateSelected(day.toString()) },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f).height(60.dp)) }
        }
    }
}

@Composable
private fun CalendarCell(
    date: LocalDate,
    performance: DailyPerformance?,
    isSelected: Boolean,
    gainColor: Color,
    lossColor: Color,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val color = performance?.let { performanceColor(it.profitLoss, gainColor, lossColor) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            // Do not use a fixed height: larger system text must be able to
            // extend the row instead of letting the return-percent line spill
            // into the following week.
            .heightIn(min = 60.dp)
            .padding(1.dp)
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(top = 3.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            fontSize = 11.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        performance?.let {
            Spacer(Modifier.height(1.dp))
            Text(
                text = signedCompactAmount(it.profitLoss),
                fontSize = 10.sp,
                lineHeight = 11.sp,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = signedPercent(it.returnPercentage),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = color
            )
        }
    }
}

@Composable
private fun PerformanceBarChart(
    items: List<DailyPerformance>,
    selectedDate: String?,
    gainColor: Color,
    lossColor: Color,
    onDateSelected: (String) -> Unit
) {
    val maxValue = items.maxOfOrNull { abs(it.profitLoss) }?.takeIf { it > 0.0 } ?: 1.0
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val selectedOutlineColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .pointerInput(items) {
                detectTapGestures { offset ->
                    if (items.isNotEmpty() && size.width > 0) {
                        val index = (offset.x / size.width * items.size)
                            .toInt()
                            .coerceIn(0, items.lastIndex)
                        onDateSelected(items[index].date)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val baseline = size.height / 2f
            drawLine(outlineColor, androidx.compose.ui.geometry.Offset(0f, baseline), androidx.compose.ui.geometry.Offset(size.width, baseline), strokeWidth = 1f)
            val step = size.width / items.size.coerceAtLeast(1)
            items.forEachIndexed { index, item ->
                val height = (abs(item.profitLoss) / maxValue * (size.height * .42f)).toFloat()
                val left = index * step + step * .18f
                val right = (index + 1) * step - step * .18f
                val top = if (item.profitLoss >= 0) baseline - height else baseline
                val bottom = if (item.profitLoss >= 0) baseline else baseline + height
                drawRect(if (item.profitLoss >= 0) gainColor else lossColor, topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(1f), bottom - top))
                if (selectedDate == item.date) {
                    drawRect(selectedOutlineColor, topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(1f), bottom - top), style = Stroke(1.5f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(items.firstOrNull()?.date?.takeLast(2) ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(items.lastOrNull()?.date?.takeLast(2) ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun performanceColor(value: Double, gainColor: Color, lossColor: Color): Color = when {
    value > 0.0 -> gainColor
    value < 0.0 -> lossColor
    else -> Color.Unspecified
}

private fun signedPercent(value: Double): String = String.format(Locale.US, "%+.2f%%", value)

private fun signedAmount(value: Double): String = String.format(Locale.US, "%+,.0f", value)

private fun signedCompactAmount(value: Double): String {
    val absolute = abs(value)
    val display = when {
        absolute >= 1_000_000 -> String.format(Locale.US, "%.1fM", absolute / 1_000_000)
        absolute >= 10_000 -> String.format(Locale.US, "%.1fK", absolute / 1_000)
        else -> String.format(Locale.US, "%,.0f", absolute)
    }
    return if (value > 0.0) "+$display" else if (value < 0.0) "-$display" else display
}
