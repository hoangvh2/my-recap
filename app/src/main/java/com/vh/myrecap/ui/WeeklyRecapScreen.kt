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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.vh.myrecap.core.Money
import com.vh.myrecap.core.WeeklyRecap
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY_MONTH = DateTimeFormatter.ofPattern("d/M")
private val DAY_LABELS = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

/** "Tổng kết tuần": tasks done per day, spending, recordings, what is stuck and how heavy next week looks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyRecapScreen(vm: AppViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val thisWeek = WeeklyRecap.weekStartOf(LocalDate.now())
    var week by remember { mutableStateOf(thisWeek) }
    var pickedDay by remember(week) { mutableStateOf<Int?>(null) }
    var pickedNext by remember(week) { mutableStateOf<Int?>(null) }
    val report = remember(items, sessions, week) {
        WeeklyRecap.build(items, sessions.map { it.createdAt }, week, System.currentTimeMillis(), ZoneId.systemDefault())
    }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Tổng kết tuần", style = MaterialTheme.typography.titleMedium) },
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
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { week = week.minusWeeks(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Tuần trước") }
                        Text(
                            "${week.format(DAY_MONTH)} – ${week.plusDays(6).format(DAY_MONTH)}" + if (week == thisWeek) "  (tuần này)" else "",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        IconButton(onClick = { week = week.plusWeeks(1) }, enabled = week < thisWeek) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Tuần sau")
                        }
                    }
                    Text(
                        "${report.done} việc đã xong",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Delta(report.done.toLong(), report.previousDone.toLong(), "tuần trước") { "$it việc" }
                    report.averageDone?.let {
                        Text(
                            "Trung bình 4 tuần trước: ${Math.round(it * 10) / 10.0} việc/tuần".replace('.', ','),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            if (report.insights.isNotEmpty()) {
                item { SectionLabel("Cần chú ý", trailing = "${report.insights.size}") }
                items(report.insights.size) { i -> AlertRow(report.insights[i].message) }
            }

            item {
                Panel {
                    Text("Việc xong theo ngày", style = MaterialTheme.typography.titleSmall)
                    val sel = pickedDay ?: report.doneByDay.indices.maxByOrNull { report.doneByDay[it] } ?: 0
                    Text(
                        "${WeeklyRecap.weekdayName(sel)}: ${report.doneByDay[sel]} việc",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    ColumnChart(
                        labels = DAY_LABELS,
                        values = report.doneByDay.map { it.toLong() },
                        selected = sel,
                        onSelect = { pickedDay = it },
                        format = { it.toString() },
                        description = "Việc xong mỗi ngày: " + report.doneByDay.indices.joinToString("; ") { "${WeeklyRecap.weekdayName(it)} ${report.doneByDay[it]}" },
                    )
                }
            }

            item {
                Panel {
                    Text("Chi tiêu tuần", style = MaterialTheme.typography.titleSmall)
                    Text(
                        Money.format(report.spent),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Delta(report.spent, report.previousSpent, "tuần trước", center = false) { Money.format(it) }
                    report.topCategory?.let { (name, amount) ->
                        Text("Nhiều nhất: $name (${Money.format(amount)})", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            item {
                Panel {
                    Text("Tình hình", style = MaterialTheme.typography.titleSmall)
                    StatLine("Ghi âm trong tuần", "${report.recordings} buổi")
                    StatLine("Việc đang quá hạn", "${report.overdue}")
                }
            }

            item {
                Panel {
                    Text("Tuần sau: ${report.nextWeekTotal} việc và lịch hẹn", style = MaterialTheme.typography.titleSmall)
                    val busiest = report.nextWeekByDay.indices.maxByOrNull { report.nextWeekByDay[it] } ?: 0
                    val sel = pickedNext ?: busiest
                    Text(
                        "${WeeklyRecap.weekdayName(sel)}: ${report.nextWeekByDay[sel]} mục",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    ColumnChart(
                        labels = DAY_LABELS,
                        values = report.nextWeekByDay.map { it.toLong() },
                        selected = sel,
                        onSelect = { pickedNext = it },
                        format = { it.toString() },
                        description = "Số mục mỗi ngày tuần sau: " + report.nextWeekByDay.indices.joinToString("; ") { "${WeeklyRecap.weekdayName(it)} ${report.nextWeekByDay[it]}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** "▲ 2 việc so với tuần trước": arrow plus words in a neutral colour. */
@Composable
private fun Delta(now: Long, before: Long, versus: String, center: Boolean = true, format: (Long) -> String) {
    val diff = now - before
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (diff != 0L) {
            Icon(if (diff > 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (diff == 0L) "Bằng $versus" else "${format(Math.abs(diff))} ${if (diff > 0) "nhiều hơn" else "ít hơn"} $versus",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
