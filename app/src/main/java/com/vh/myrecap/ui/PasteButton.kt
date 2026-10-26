package com.vh.myrecap.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * Whether the clipboard holds text a field could take. Reads only its description, so no clipboard-access notice.
 * A clip whose description cannot be read counts as text: better a button that pastes nothing than none.
 */
private fun clipboardHasText(cm: ClipboardManager): Boolean {
    if (!cm.hasPrimaryClip()) return false
    val d = cm.primaryClipDescription ?: return true
    return (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("text/") }
}

/**
 * A small "Dán" button above the keyboard while one is open and the clipboard holds text. It sends Ctrl+V to the
 * focused field, so the text lands at the cursor like the long-press menu's Paste, without the long press.
 * Place it in a Box over the screen, aligned to the bottom end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PasteButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val cm = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var hasText by remember { mutableStateOf(clipboardHasText(cm)) }
    // The change listener is not called while the app is in the background (Android only lets the focused app see the
    // clipboard), so a copy made in another app has to be looked for again when the window gets focus back and
    // whenever the keyboard opens, which is when the person is about to paste.
    DisposableEffect(cm, view) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { hasText = clipboardHasText(cm) }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { if (it) hasText = clipboardHasText(cm) }
        cm.addPrimaryClipChangedListener(listener)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        hasText = clipboardHasText(cm)
        onDispose {
            cm.removePrimaryClipChangedListener(listener)
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) hasText = clipboardHasText(cm) }
    AnimatedVisibility(visible = imeVisible && hasText, enter = fadeIn(), exit = fadeOut(), modifier = modifier.imePadding().padding(end = 12.dp, bottom = 8.dp)) {
        FilledTonalButton(
            onClick = {
                val now = SystemClock.uptimeMillis()
                val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, meta))
                view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, meta))
            },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Dán", modifier = Modifier.padding(start = 6.dp))
        }
    }
}
