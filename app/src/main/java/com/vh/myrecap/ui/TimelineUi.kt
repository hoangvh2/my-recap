package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.Timeline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NOW_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** "Hôm nay theo giờ": a time column, a "now" line before the first entry still ahead, past appointments dimmed. */
internal fun LazyListScope.todayTimeline(today: List<Item>, vm: AppViewModel) {
    if (today.isEmpty()) return
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val day = Timeline.build(today, now, zone)
    item(key = "label-timeline") { SectionLabel("Hôm nay theo giờ", trailing = "${today.size}") }
    day.rows.forEachIndexed { index, row ->
        if (index == day.nowAt && day.nowAt > 0) item(key = "timeline-now") { NowLine(Instant.ofEpochMilli(now).atZone(zone).format(NOW_FORMAT)) }
        item(key = "timeline-${row.item.id}") {
            Row(Modifier.fillMaxWidth().alpha(if (row.past) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp),
                )
                ItemRow(row.item, onOpen = { vm.openItem(row.item.id) }, onToggleDone = { vm.setDone(row.item.id, it) }, modifier = Modifier.weight(1f))
            }
        }
    }
    if (day.nowAt == day.rows.size && day.nowAt > 0) item(key = "timeline-now") { NowLine(Instant.ofEpochMilli(now).atZone(zone).format(NOW_FORMAT)) }
}

@androidx.compose.runtime.Composable
private fun NowLine(time: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(time, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
        Spacer(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}
