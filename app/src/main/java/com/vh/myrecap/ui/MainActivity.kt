package com.vh.myrecap.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.ui.theme.MyRecapTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // While recording, this screen may appear over the lock screen so the big controls work without
        // unlocking. Only the recording screen is shown then (see AppRoot), never the session list.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                vm.recorder.collect { setShowWhenLocked(it.active) }
            }
        }
        setContent { MyRecapTheme { AppRoot(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SESSION_ID)?.let { vm.openSession(it) }
        intent?.getStringExtra(EXTRA_ITEM_ID)?.let { vm.openItem(it) }
        if (intent?.action == ACTION_QUICK_CAPTURE) vm.requestQuickCapture()
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_ITEM_ID = "itemId"
        /** Launcher shortcut: open straight into a quick capture. */
        const val ACTION_QUICK_CAPTURE = "com.vh.myrecap.QUICK_CAPTURE"
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    val recorder by vm.recorder.collectAsStateWithLifecycle()

    LaunchedEffect(recorder.finishedSessionId) {
        recorder.finishedSessionId?.let {
            vm.openSession(it)
            RecorderState.consumeFinished()
        }
    }

    if (recorder.active) {
        RecordingScreen(
            recorder,
            onTogglePause = vm::togglePause,
            onBookmark = vm::bookmark,
            onStop = vm::stop,
            onDiscard = vm::discardRecording,
        )
        return
    }
    val nav = vm.nav
    if (nav.depth > 1) BackHandler { vm.back() }
    AnimatedContent(
        targetState = nav,
        contentKey = { it.screen },
        transitionSpec = {
            // Deeper screens slide in from the right; going back slides the other way.
            val forward = targetState.depth >= initialState.depth
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(260)) { -it / 10 * dir } + fadeOut(tween(160)))
        },
        label = "screens",
    ) { entry ->
        when (val target = entry.screen) {
            Screen.Home -> HomeScreen(vm)
            is Screen.Detail -> SessionScreen(vm, target.id)
            is Screen.Clip -> ClipScreen(vm, target.id, target.index)
            is Screen.Summary -> SummaryScreen(vm, target.id, target.jobId)
            is Screen.Review -> ReviewScreen(vm, target.id)
            is Screen.ItemEdit -> ItemEditScreen(vm, target.id, target.type)
            Screen.Settings -> SettingsScreen(vm)
        }
    }
}
