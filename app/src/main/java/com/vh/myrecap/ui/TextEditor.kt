package com.vh.myrecap.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen editor for a transcript: fix what speech-to-text got wrong, or type what it missed.
 * Closing with unsaved changes asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorDialog(
    title: String,
    initial: String,
    saveLabel: String,
    hint: String? = null,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var confirmClose by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val close = { if (text != initial) confirmClose = true else onDismiss() }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { IconButton(onClick = close) { Icon(Icons.Rounded.Close, contentDescription = "Đóng") } },
                    actions = {
                        TextButton(
                            onClick = {
                                onSave(text.trim())
                                onDismiss()
                            },
                            enabled = text.isNotBlank() && text != initial,
                        ) { Text(saveLabel) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                supportingText = hint?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(16.dp)
                    .focusRequester(focus),
            )
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    if (confirmClose) {
        ConfirmDialog(
            title = "Bỏ thay đổi?",
            text = "Nội dung bạn vừa sửa sẽ không được lưu.",
            confirmLabel = "Bỏ",
            destructive = true,
            onConfirm = onDismiss,
            onDismiss = { confirmClose = false },
        )
    }
}
