package com.vh.myrecap.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemText
import com.vh.myrecap.core.Money
import com.vh.myrecap.core.QuickTemplate
import com.vh.myrecap.ui.theme.MyRecapTheme
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * Quick-add sheet over the home screen, opened from the quick-add widget or the launcher shortcut. A
 * one-tap line is saved at once and shown with Undo; otherwise the sheet holds the quick-add box,
 * filled with the chosen line when it has a blank.
 */
class QuickAddActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT)
        val addNow = intent.getBooleanExtra(EXTRA_ADD_NOW, false) && text != null
        // A recreated sheet (rotation, dark mode) must not add the line a second time.
        if (savedInstanceState == null && text != null) {
            if (addNow) vm.addFromTemplate(QuickTemplate(text)) else vm.templateUsed(QuickTemplate(text))
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            MyRecapTheme(settings.themeMode) {
                QuickAddSheet(
                    vm = vm,
                    initial = text.takeUnless { addNow },
                    waitingForAdd = addNow,
                    onClose = ::finish,
                    onOpen = { item ->
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_ITEM_ID, item.id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ADD_NOW = "addNow"

        /** [text]: a saved line, or null for an empty box; [addNow]: save it without showing the box. */
        fun intent(context: Context, text: String?, addNow: Boolean): Intent =
            Intent(context, QuickAddActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_ADD_NOW, addNow)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

/** How long the "added" card stays before the sheet closes on its own. */
private const val ADDED_MS = 4_000L

@Composable
private fun QuickAddSheet(vm: AppViewModel, initial: String?, waitingForAdd: Boolean, onClose: () -> Unit, onOpen: (Item) -> Unit) {
    val added = vm.lastAddedItem
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .imePadding()
                // Taps on the sheet itself must not close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gõ nhanh", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Đóng") }
                }
                when {
                    added != null -> AddedCard(vm, added, onClose, onOpen)
                    waitingForAdd -> Text("Đang thêm…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> QuickAddBar(vm, initial = initial, autoFocus = true)
                }
            }
        }
    }
}

@Composable
private fun AddedCard(vm: AppViewModel, item: Item, onClose: () -> Unit, onOpen: (Item) -> Unit) {
    LaunchedEffect(item.id) {
        delay(ADDED_MS)
        vm.clearAddedItem(item)
        onClose()
    }
    val amount = item.amount
    val detail = listOfNotNull(
        item.type.label,
        amount?.let { Money.format(it) },
        item.category,
        ItemText.whenText(item, ZoneId.systemDefault()),
    ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Đã thêm “${item.title}”", style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.undoAddItem(item); onClose() }) { Text("Hoàn tác") }
        TextButton(onClick = { vm.clearAddedItem(item); onOpen(item) }) { Text("Sửa chi tiết") }
        Spacer(Modifier.weight(1f))
        Button(onClick = { vm.clearAddedItem(item); onClose() }) { Text("Xong") }
    }
}
