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
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
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
        RecordingScreen(recorder, onTogglePause = vm::togglePause, onBookmark = vm::bookmark, onStop = vm::stop)
        return
    }
    val screen = vm.screen
    if (screen != Screen.Home) BackHandler { vm.back() }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            // Deeper screens slide in from the right; going back slides the other way.
            val forward = depth(targetState) >= depth(initialState)
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(260)) { -it / 10 * dir } + fadeOut(tween(160)))
        },
        label = "screens",
    ) { target ->
        when (target) {
            Screen.Home -> HomeScreen(vm)
            is Screen.Detail -> SessionScreen(vm, target.id)
            is Screen.Clip -> ClipScreen(vm, target.id, target.index)
            is Screen.Summary -> SummaryScreen(vm, target.id, target.jobId)
            Screen.Settings -> SettingsScreen(vm)
        }
    }
}

private fun depth(screen: Screen) = when (screen) {
    Screen.Home -> 0
    Screen.Settings, is Screen.Detail -> 1
    is Screen.Clip, is Screen.Summary -> 2
}
