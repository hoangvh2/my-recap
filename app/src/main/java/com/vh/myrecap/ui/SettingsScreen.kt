package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.OutputLanguage
import com.vh.myrecap.core.ProviderConfig
import com.vh.myrecap.core.ProviderKind
import com.vh.myrecap.settings.AppSettings
import kotlinx.coroutines.launch

/** Every change is saved immediately; there is no Save button to forget. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val update: ((AppSettings) -> AppSettings) -> Unit = { vm.updateSettings(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("1. Chuyển giọng nói → văn bản (luôn bật)") {
                ProviderChoice(s.sttProvider) { k -> update { it.copy(sttProvider = k) } }
                Hint(
                    if (s.sttProvider == ProviderKind.GEMINI) {
                        "Gemini: có gói miễn phí, nhận diện người nói, hỗ trợ trộn Việt/Anh/Nhật. Gói miễn phí: Google có thể dùng dữ liệu để cải thiện sản phẩm."
                    } else {
                        "Whisper (Groq ~$0.04/giờ, có gói miễn phí): rất rẻ và nhanh nhưng KHÔNG tách người nói."
                    },
                )
            }

            Section("2. AI tóm tắt & viết lại") {
                SwitchRow("Bật AI tóm tắt", s.summaryEnabled) { v -> update { it.copy(summaryEnabled = v) } }
                if (s.summaryEnabled) {
                    ProviderChoice(s.summaryProvider) { k -> update { it.copy(summaryProvider = k) } }
                    Text("Ngôn ngữ bản tóm tắt", fontWeight = FontWeight.SemiBold)
                    OutputLanguage.entries.forEach { lang ->
                        RadioRow(lang.label, s.outputLanguage == lang) { update { it.copy(outputLanguage = lang) } }
                    }
                    Field("Yêu cầu tóm tắt tự do (mẫu 'Tự do')", s.customPrompt, singleLine = false) { v ->
                        update { it.copy(customPrompt = v) }
                    }
                    Hint("Ví dụ: \"Viết lại thành email gửi khách hàng, giọng lịch sự\".")
                }
            }

            val usesGemini = s.sttProvider == ProviderKind.GEMINI || (s.summaryEnabled && s.summaryProvider == ProviderKind.GEMINI)
            val usesOpenAi = s.sttProvider == ProviderKind.OPENAI_COMPATIBLE ||
                (s.summaryEnabled && s.summaryProvider == ProviderKind.OPENAI_COMPATIBLE)

            if (usesGemini) {
                Section("Google Gemini") {
                    SecretField("Gemini API key", s.geminiKey) { v -> update { it.copy(geminiKey = v) } }
                    Hint("Lấy key miễn phí: aistudio.google.com → Get API key.")
                    if (s.sttProvider == ProviderKind.GEMINI) {
                        Field("Model chuyển giọng nói", s.geminiSttModel) { v -> update { it.copy(geminiSttModel = v) } }
                    }
                    if (s.summaryEnabled && s.summaryProvider == ProviderKind.GEMINI) {
                        Field("Model tóm tắt", s.geminiSummaryModel) { v -> update { it.copy(geminiSummaryModel = v) } }
                    }
                    Hint("Mặc định ${ProviderConfig.GEMINI_DEFAULT_MODEL} (rẻ nhất). Muốn chính xác hơn: model 'flash' (không lite).")
                    TestButton(vm, if (s.sttProvider == ProviderKind.GEMINI) s.sttConfig() else s.summaryConfig())
                }
            }

            if (usesOpenAi) {
                Section("OpenAI-compatible") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            update {
                                it.copy(
                                    openAiBaseUrl = ProviderConfig.GROQ_BASE_URL,
                                    openAiSttModel = ProviderConfig.GROQ_STT_MODEL,
                                    openAiChatModel = ProviderConfig.GROQ_CHAT_MODEL,
                                )
                            }
                        }) { Text("Dùng Groq") }
                        OutlinedButton(onClick = {
                            update {
                                it.copy(openAiBaseUrl = ProviderConfig.OPENAI_BASE_URL, openAiSttModel = "whisper-1", openAiChatModel = "gpt-4o-mini")
                            }
                        }) { Text("Dùng OpenAI") }
                    }
                    Field("Base URL", s.openAiBaseUrl, keyboard = KeyboardType.Uri) { v -> update { it.copy(openAiBaseUrl = v) } }
                    SecretField("API key", s.openAiKey) { v -> update { it.copy(openAiKey = v) } }
                    if (s.sttProvider == ProviderKind.OPENAI_COMPATIBLE) {
                        Field("Model chuyển giọng nói", s.openAiSttModel) { v -> update { it.copy(openAiSttModel = v) } }
                        Field("Ngôn ngữ (vi/en/ja, để trống = tự nhận)", s.whisperLanguage) { v ->
                            update { it.copy(whisperLanguage = v) }
                        }
                    }
                    if (s.summaryEnabled && s.summaryProvider == ProviderKind.OPENAI_COMPATIBLE) {
                        Field("Model tóm tắt", s.openAiChatModel) { v -> update { it.copy(openAiChatModel = v) } }
                    }
                    TestButton(vm, if (s.sttProvider == ProviderKind.OPENAI_COMPATIBLE) s.sttConfig() else s.summaryConfig())
                }
            }

            Section("3. Xử lý & chi phí") {
                SwitchRow("Tự xử lý khi dừng ghi", s.autoProcess) { v -> update { it.copy(autoProcess = v) } }
                SwitchRow("Chuyển văn bản dần trong lúc ghi (có kết quả sớm hơn)", s.processWhileRecording) { v ->
                    update { it.copy(processWhileRecording = v) }
                }
                SwitchRow("Chỉ xử lý khi có Wi-Fi", s.wifiOnly) { v -> update { it.copy(wifiOnly = v) } }
                Text("Độ dài mỗi đoạn gửi đi", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15).forEach { m ->
                        val selected = s.segmentMinutes == m
                        if (selected) FilledTonalButton(onClick = {}) { Text("$m phút") }
                        else OutlinedButton(onClick = { update { it.copy(segmentMinutes = m) } }) { Text("$m phút") }
                    }
                }
                Hint("Âm thanh ghi ở 32 kbps (~14 MB/giờ). Ước tính chi phí 1 giờ: Gemini Flash-Lite ≈ $0.04 (gói trả phí), Groq Whisper ≈ $0.04.")
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun ProviderChoice(selected: ProviderKind, onSelect: (ProviderKind) -> Unit) {
    ProviderKind.entries.forEach { k -> RadioRow(k.label, selected == k) { onSelect(k) } }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    // Local state keeps typing smooth; the persisted value arrives a frame later via the flow.
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text.trim()) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.trim())
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Ẩn" else "Hiện") } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TestButton(vm: AppViewModel, config: ProviderConfig) {
    val scope = rememberCoroutineScope()
    var result by remember(config) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    FilledTonalButton(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                result = vm.testConnection(config)
                busy = false
            }
        },
    ) { Text(if (busy) "Đang kiểm tra…" else "Kiểm tra kết nối") }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
