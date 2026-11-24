package com.vh.myrecap.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.Teleprompter
import com.vh.myrecap.core.TeleprompterPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Paper = Color.Black
private val Ink = Color.White
private val Guide = Color(0xFFFFD54F)
private val PanelColor = Color(0xE6161616)

/**
 * The teleprompter: a script read in large text that scrolls by itself at a speaker's pace (words per minute).
 * Two steps: set up (speed, size, mirror, countdown), then read. A tap pauses and shows the controls; nothing here
 * changes or deletes the note, so there is nothing to confirm.
 */
@Composable
fun TeleprompterScreen(vm: AppViewModel, request: AppViewModel.TeleprompterRequest) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prefs = settings.teleprompter
    val text = remember(request.script) { Teleprompter.prepare(request.script) }
    val words = remember(text) { Teleprompter.wordCount(text) }
    var reading by remember { mutableStateOf(false) }
    val change: ((TeleprompterPrefs) -> TeleprompterPrefs) -> Unit = { f -> vm.updateSettings { it.copy(teleprompter = f(it.teleprompter).clean()) } }

    // Setup leaves with Back; reading goes back to setup first (the reader handles its own Back).
    BackHandler(enabled = !reading) { vm.closeTeleprompter() }
    Box(Modifier.fillMaxSize()) {
        if (!reading) {
            TeleprompterSetup(request.title, text, words, prefs, change, onStart = { reading = true }, onClose = vm::closeTeleprompter)
        } else {
            TeleprompterReader(text, words, prefs, change, onExit = { reading = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeleprompterSetup(
    title: String,
    text: String,
    words: Int,
    prefs: TeleprompterPrefs,
    change: ((TeleprompterPrefs) -> TeleprompterPrefs) -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Teleprompter") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Button(
                onClick = onStart,
                enabled = words > 0,
                modifier = Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp).height(52.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Bắt đầu đọc")
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "$words từ · khoảng ${Teleprompter.spoken(Teleprompter.seconds(words, prefs.wpm))} ở ${prefs.wpm} từ/phút",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text.take(PREVIEW_CHARS) + if (text.length > PREVIEW_CHARS) "…" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Stepper(
                label = "Tốc độ đọc",
                value = "${prefs.wpm} từ/phút",
                onMinus = { change { it.copy(wpm = Teleprompter.stepWpm(it.wpm, up = false)) } },
                onPlus = { change { it.copy(wpm = Teleprompter.stepWpm(it.wpm, up = true)) } },
            )
            Slider(
                value = prefs.wpm.toFloat(),
                onValueChange = { v -> change { it.copy(wpm = v.toInt()) } },
                valueRange = Teleprompter.MIN_WPM.toFloat()..Teleprompter.MAX_WPM.toFloat(),
                steps = (Teleprompter.MAX_WPM - Teleprompter.MIN_WPM) / Teleprompter.WPM_STEP - 1,
                modifier = Modifier.semantics { contentDescription = "Tốc độ đọc ${prefs.wpm} từ mỗi phút" },
            )
            Stepper(
                label = "Cỡ chữ",
                value = "${prefs.fontSp}",
                onMinus = { change { it.copy(fontSp = Teleprompter.stepFont(it.fontSp, up = false)) } },
                onPlus = { change { it.copy(fontSp = Teleprompter.stepFont(it.fontSp, up = true)) } },
            )
            Slider(
                value = prefs.fontSp.toFloat(),
                onValueChange = { v -> change { it.copy(fontSp = v.toInt()) } },
                valueRange = Teleprompter.MIN_FONT_SP.toFloat()..Teleprompter.MAX_FONT_SP.toFloat(),
                steps = (Teleprompter.MAX_FONT_SP - Teleprompter.MIN_FONT_SP) / Teleprompter.FONT_STEP - 1,
                modifier = Modifier.semantics { contentDescription = "Cỡ chữ ${prefs.fontSp}" },
            )
            SwitchRow("Đếm ngược 3-2-1 trước khi chạy", prefs.countdown) { v -> change { it.copy(countdown = v) } }
            SwitchRow("Lật gương (đặt điện thoại dưới kính phản chiếu)", prefs.mirror) { v -> change { it.copy(mirror = v) } }
            Text(
                "Chạm màn hình khi đang đọc để tạm dừng và chỉnh tốc độ. Nội dung ghi chú không bị thay đổi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PREVIEW_CHARS = 280

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) { Icon(Icons.Rounded.Remove, contentDescription = "Giảm $label") }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 88.dp))
        IconButton(onClick = onPlus) { Icon(Icons.Rounded.Add, contentDescription = "Tăng $label") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The reading screen: black page, big white text, a marker where the eye should rest; a tap pauses. */
@Composable
private fun TeleprompterReader(
    text: String,
    words: Int,
    prefs: TeleprompterPrefs,
    change: ((TeleprompterPrefs) -> TeleprompterPrefs) -> Unit,
    onExit: () -> Unit,
) {
    val scroll = rememberScrollState()
    var counting by remember { mutableStateOf(prefs.countdown) }
    var count by remember { mutableIntStateOf(3) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    // Keeps the place when the text size changes (the text gets taller or shorter, the place stays the same fraction).
    var anchor by remember { mutableFloatStateOf(-1f) }
    val wpm by rememberUpdatedState(prefs.wpm)
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // The screen stays on while reading, and the bar icons turn light on the black page.
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val wasLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            view.keepScreenOn = false
            if (wasLight != null) controller?.isAppearanceLightStatusBars = wasLight
        }
    }

    LaunchedEffect(Unit) {
        if (counting) {
            for (n in 3 downTo 1) {
                count = n
                delay(1000)
            }
            counting = false
        }
        running = true
    }

    LaunchedEffect(scroll.maxValue) {
        if (anchor >= 0f) {
            scroll.scrollTo((anchor * scroll.maxValue).toInt())
            anchor = -1f
        }
    }

    // One frame at a time: the distance moved follows the real time passed, so the pace does not depend on the phone's speed.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        var carry = 0f
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            if (scroll.isScrollInProgress) continue // a finger on the text wins over the auto-scroll
            carry += Teleprompter.pxPerSecond(scroll.maxValue.toFloat(), words, wpm) * dt
            val whole = carry.toInt()
            if (whole < 1) continue
            carry -= whole
            scroll.scrollBy(whole.toFloat())
            if (scroll.value >= scroll.maxValue) {
                running = false
                finished = true
                break
            }
        }
    }

    BackHandler {
        when {
            running -> running = false
            else -> onExit()
        }
    }

    val progress by remember { derivedStateOf { if (scroll.maxValue <= 0) 0f else scroll.value.toFloat() / scroll.maxValue } }
    val showPanel = !running && !counting
    val flip = if (prefs.mirror) -1f else 1f

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = if (running) "Tạm dừng" else "Tiếp tục") {
                if (!counting) {
                    if (finished) {
                        finished = false
                        running = false
                    } else {
                        running = !running
                    }
                }
            },
    ) {
        val viewport = maxHeight
        val guideY = viewport * GUIDE_AT
        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = flip).clipToBounds()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(Modifier.height(guideY))
                Text(
                    text,
                    color = Ink,
                    fontSize = prefs.fontSp.sp,
                    lineHeight = (prefs.fontSp * 1.4f).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                )
                Spacer(Modifier.height(viewport - guideY))
            }
            // The marker: a small arrow at each edge, level with the line to read.
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Guide, modifier = Modifier.align(Alignment.TopStart).offset(y = guideY).size(28.dp))
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Guide, modifier = Modifier.align(Alignment.TopEnd).offset(y = guideY).size(28.dp).graphicsLayer(scaleX = -1f))
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.TopCenter),
            color = Guide,
            trackColor = Color(0x33FFFFFF),
        )
        if (counting) {
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
                Text("$count", color = Ink, fontSize = 120.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "Bắt đầu sau $count giây" })
            }
        }
        if (showPanel) {
            val left = Teleprompter.seconds(words, wpm) * (1f - progress)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(PanelColor, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    // A tap on the panel itself does nothing (a tap on the page resumes).
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (finished) "Đã hết bài" else "Tạm dừng · còn khoảng ${Teleprompter.clock(left.toInt())}",
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                )
                PanelStepper("Tốc độ", "${prefs.wpm} từ/phút", onMinus = { change { it.copy(wpm = Teleprompter.stepWpm(it.wpm, up = false)) } }, onPlus = { change { it.copy(wpm = Teleprompter.stepWpm(it.wpm, up = true)) } })
                PanelStepper(
                    "Cỡ chữ",
                    "${prefs.fontSp}",
                    onMinus = {
                        val next = Teleprompter.stepFont(prefs.fontSp, up = false)
                        if (next != prefs.fontSp) anchor = progress
                        change { it.copy(fontSp = next) }
                    },
                    onPlus = {
                        val next = Teleprompter.stepFont(prefs.fontSp, up = true)
                        if (next != prefs.fontSp) anchor = progress
                        change { it.copy(fontSp = next) }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (finished) finished = false
                            running = true
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Guide, contentColor = Color.Black),
                    ) { Text(if (finished) "Đọc lại" else "Tiếp tục") }
                    OutlinedButton(onClick = onExit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Thoát", color = Ink) }
                }
                if (progress > 0.01f && !finished) {
                    TextButtonFromTop(onClick = { scope.launch { scroll.scrollTo(0) } })
                }
            }
        }
    }
}

/** Where on the screen the line being read sits (a bit above the middle, so the next lines show below it). */
private const val GUIDE_AT = 0.33f

@Composable
private fun PanelStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) { Icon(Icons.Rounded.Remove, tint = Ink, contentDescription = "Giảm $label") }
        Text(value, color = Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 92.dp))
        IconButton(onClick = onPlus) { Icon(Icons.Rounded.Add, tint = Ink, contentDescription = "Tăng $label") }
    }
}

@Composable
private fun TextButtonFromTop(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text("Về đầu bài", color = Guide) }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
