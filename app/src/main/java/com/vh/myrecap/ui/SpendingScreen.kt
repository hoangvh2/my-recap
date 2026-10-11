package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Money
import com.vh.myrecap.core.SpendingStats
import java.time.YearMonth
import java.time.ZoneId

/**
 * "Thống kê chi tiêu": month total against last month, a forecast to month end, alerts for anything
 * unusual, spending by category and a six-month trend. All numbers come from [SpendingStats].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingScreen(vm: AppViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var day by remember(month) { mutableStateOf<Int?>(null) }
    var table by remember { mutableStateOf(false) }
    val report = remember(items, month) {
        SpendingStats.report(items.filter { it.type == ItemType.EXPENSE }, month, System.currentTimeMillis(), ZoneId.systemDefault())
    }
    var picked by remember(month) { mutableStateOf<Int?>(null) }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Thống kê chi tiêu", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Tháng trước") }
                        Text(
                            "Tháng ${month.monthValue}/${month.year}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Tháng sau")
                        }
                    }
                    Text(
                        Money.format(report.total),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "${report.count} khoản",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    DeltaLine(report)
                    report.forecast?.let {
                        Text(
                            "Dự kiến cuối tháng: ${Money.format(it)}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Text(
                            "Ước tính: đã chi + phần các tháng trước còn chi sau ngày này (hoặc theo nhịp hiện tại nếu chưa đủ 2 tháng dữ liệu).",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            if (report.total == 0L && report.months.all { it.total == 0L }) {
                item { EmptyState(Icons.Rounded.Inbox, "Chưa có chi tiêu", "Nói “ăn trưa 85k” khi ghi nhanh; thống kê sẽ hiện ở đây.") }
                return@LazyColumn
            }

            if (report.alerts.isNotEmpty()) {
                item { SectionLabel("Cần chú ý", trailing = "${report.alerts.size}") }
                items(report.alerts.size) { i ->
                    val alert = report.alerts[i]
                    AlertRow(alert.message, onClick = alert.itemId?.let { id -> { vm.openItem(id) } })
                }
            }

            if (report.cumulative.isNotEmpty()) {
                item {
                    Card {
                        Text("Chi tiêu cộng dồn theo ngày", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.size(4.dp))
                        Text(readout(report, day), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        CumulativeChart(
                            current = report.cumulative,
                            previous = report.previousCumulative,
                            forecast = report.forecast,
                            daysInMonth = report.daysInMonth,
                            selectedDay = day,
                            onSelect = { day = it },
                            description = "Biểu đồ chi tiêu cộng dồn tháng ${month.monthValue}: ${Money.format(report.total)}" +
                                (report.forecast?.let { ", dự kiến ${Money.format(it)}" } ?: ""),
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            LegendItem(scheme.primary, "Tháng này")
                            LegendItem(scheme.outline, "Tháng trước", dashed = true)
                            if (report.forecast != null) LegendItem(scheme.primary.copy(alpha = 0.55f), "Dự kiến", dashed = true)
                        }
                    }
                }
            }

            if (report.categories.isNotEmpty()) {
                item {
                    Card {
                        Text("Theo danh mục", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 10.dp))
                        RankedBars(
                            report.categories.map {
                                RankedRow(it.name, it.amount, Money.format(it.amount), "${Math.round(it.share * 100)}% tổng chi", muted = it.name == SpendingStats.OTHER)
                            },
                        )
                    }
                }
            }

            item {
                Card {
                    Text("6 tháng gần đây", style = MaterialTheme.typography.titleSmall)
                    val selected = picked ?: report.months.lastIndex
                    val chosen = report.months[selected]
                    Text(
                        "Tháng ${chosen.month.monthValue}/${chosen.month.year}: ${Money.format(chosen.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    ColumnChart(
                        labels = report.months.map { "T${it.month.monthValue}" },
                        values = report.months.map { it.total },
                        selected = selected,
                        onSelect = { picked = it },
                        description = "Chi tiêu 6 tháng: " + report.months.joinToString("; ") { "tháng ${it.month.monthValue} ${Money.format(it.total)}" },
                    )
                }
            }

            item {
                TextButton(onClick = { table = !table }) { Text(if (table) "Ẩn bảng số liệu" else "Xem dạng bảng") }
                if (table) {
                    Card {
                        report.months.forEach { m ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tháng ${m.month.monthValue}/${m.month.year}", style = MaterialTheme.typography.bodyMedium)
                                Text(Money.format(m.total), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** "▲ 12% so với cùng kỳ tháng trước": arrow and words, neutral colour (spending up is not an error in itself). */
@Composable
private fun DeltaLine(report: SpendingStats.Report) {
    val before = report.previousToDate ?: return
    if (report.total == 0L) return
    val pct = Math.round((report.total - before) * 100.0 / before).toInt()
    val up = pct > 0
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (pct != 0) Icon(if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            if (pct == 0) "Ngang cùng kỳ tháng trước (${Money.format(before)})" else "${Math.abs(pct)}% ${if (up) "cao hơn" else "thấp hơn"} cùng kỳ tháng trước (${Money.format(before)})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun readout(report: SpendingStats.Report, day: Int?): String {
    val d = day ?: return "Chạm hoặc kéo trên biểu đồ để xem từng ngày"
    val now = report.cumulative.getOrNull(d)
    val before = report.previousCumulative.getOrNull(d)
    return buildString {
        append("Ngày ${d + 1}: ")
        append(now?.let { Money.format(it) } ?: "chưa tới")
        if (before != null) append("  ·  tháng trước ${Money.format(before)}")
    }
}
