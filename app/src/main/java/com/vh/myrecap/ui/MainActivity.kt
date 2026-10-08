package com.vh.myrecap.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
            com.vh.myrecap.recorder.RecorderState.consumeFinished()
        }
    }

    if (recorder.active) {
        RecordingScreen(recorder, onTogglePause = vm::togglePause, onBookmark = vm::bookmark, onStop = vm::stop)
        return
    }
    when (val screen = vm.screen) {
        Screen.Home -> HomeScreen(vm)
        is Screen.Detail -> {
            BackHandler { vm.back() }
            SessionScreen(vm, screen.id)
        }
        Screen.Settings -> {
            BackHandler { vm.back() }
            SettingsScreen(vm)
        }
    }
}

@Composable
fun MyRecapTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFFFB4AB), error = Color(0xFFFFB4AB))
        else -> lightColorScheme(primary = Color(0xFFC62828), error = Color(0xFFB3261E))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
