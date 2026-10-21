package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.Triage

/**
 * "Rà soát": the oldest overdue task with one-tap moves, so a long red "Quá hạn" list gets cleaned up
 * one decision at a time. Disappears when nothing is overdue.
 */
internal fun LazyListScope.triageCard(overdueTasks: List<Item>, vm: AppViewModel) {
    val current = overdueTasks.firstOrNull() ?: return
    item(key = "label-triage") { SectionLabel("Rà soát", trailing = "còn ${overdueTasks.size} việc quá hạn") }
    item(key = "triage-card") {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                ItemRow(current, onOpen = { vm.openItem(current.id) }, onToggleDone = { vm.setDone(current.id, it) }, onChecklist = { vm.checklist(current.id, it) })
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (move in Triage.Move.entries) {
                        TextButton(onClick = { vm.triage(current.id, move) }, modifier = Modifier.weight(1f)) {
                            Text(move.label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
