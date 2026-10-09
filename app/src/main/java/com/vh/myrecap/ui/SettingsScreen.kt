package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
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
import com.vh.myrecap.core.InterviewerSpeech
import com.vh.myrecap.core.OutputLanguage
import com.vh.myrecap.core.VadSensitivity
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("1. Chuyển giọng nói → văn bản") {
                ProviderChoice(s.sttProvider) { k -> update { it.copy(sttProvider = k) } }
                Hint(
                    if (s.sttProvider == ProviderKind.GEMINI) {
                        "Gemini: có gói miễn phí, nhận diện người nói, hỗ trợ trộn Việt/Anh/Nhật, tự đặt tiêu đề cho từng đoạn. Gói miễn phí: Google có thể dùng dữ liệu để cải thiện sản phẩm."
                    } else {
                        "Whisper (Groq ~$0.04/giờ, có gói miễn phí): rất rẻ và nhanh nhưng KHÔNG tách người nói và không tự đặt tiêu đề."
                    },
                )
                SwitchRow("Tự chuyển văn bản sau khi ghi", s.autoProcess) { v -> update { it.copy(autoProcess = v) } }
                if (s.autoProcess) {
                    SwitchRow("Chuyển ngay từng đoạn trong lúc ghi", s.processWhileRecording) { v ->
                        update { it.copy(processWhileRecording = v) }
                    }
                }
                SwitchRow("Chỉ gửi khi có Wi-Fi", s.wifiOnly) { v -> update { it.copy(wifiOnly = v) } }
            }

            Section("2. Tự nhận biết hội thoại") {
                SwitchRow("Tự tách đoạn khi có khoảng lặng", s.autoSplit) { v -> update { it.copy(autoSplit = v) } }
                if (s.autoSplit) {
                    Text("Khoảng lặng để kết thúc 1 đoạn", fontWeight = FontWeight.SemiBold)
                    ChoiceRow(listOf(4, 6, 8, 12, 15), s.splitPauseSec, { "$it giây" }) { v -> update { it.copy(splitPauseSec = v) } }
                    Hint(
                        "Ví dụ 6 giây: ứng viên trả lời xong, bạn im lặng ≥6 giây để chuẩn bị câu hỏi tiếp → app đóng đoạn " +
                            "hiện tại và chờ đoạn mới. Ngập ngừng ngắn khi trả lời vẫn nằm chung 1 đoạn.",
                    )
                }
                SwitchRow("Bỏ khoảng lặng dài trước khi gửi (giữ tối đa 0,8 giây)", s.trimSilence) { v ->
                    update { it.copy(trimSilence = v) }
                }
                if (s.usesVad) {
                    Text("Độ nhạy nhận giọng nói", fontWeight = FontWeight.SemiBold)
                    VadSensitivity.entries.forEach { v ->
                        RadioRow(v.label, s.vadSensitivity == v) { update { it.copy(vadSensitivity = v) } }
                    }
                    Hint("Tiếng động ngắn (ho, gõ bàn < 1,5 giây) bị bỏ qua. Nếu bị mất lời nói nhỏ, tăng độ nhạy.")
                }
                Text("Độ dài tối đa 1 đoạn", fontWeight = FontWeight.SemiBold)
                ChoiceRow(listOf(5, 10, 15), s.segmentMinutes, { "$it phút" }) { v -> update { it.copy(segmentMinutes = v) } }
            }

            Section("3. Lời người phỏng vấn (chế độ Phỏng vấn)") {
                InterviewerSpeech.entries.forEach { v ->
                    RadioRow(v.label, s.interviewerSpeech == v) { update { it.copy(interviewerSpeech = v) } }
                }
                Hint(
                    if (s.sttProvider == ProviderKind.GEMINI) {
                        "AI nhận ra ai là người hỏi theo ngữ cảnh. Giúp transcript gọn và giảm phí đầu ra; âm thanh vẫn được gửi đủ " +
                            "(phí âm thanh không đổi). 'Rút gọn' được khuyên dùng: giữ câu hỏi để tóm tắt vẫn đủ ngữ cảnh."
                    } else {
                        "Chỉ áp dụng khi dùng Gemini (Whisper không phân biệt người nói)."
                    },
                )
            }

            Section("4. AI tóm tắt & viết lại (khi bạn bấm)") {
                Hint("Không tự chạy. Trong folder, chọn các đoạn cần thiết rồi bấm Tóm tắt.")
                ProviderChoice(s.summaryProvider) { k -> update { it.copy(summaryProvider = k) } }
                Text("Ngôn ngữ tóm tắt & tiêu đề đoạn", fontWeight = FontWeight.SemiBold)
                OutputLanguage.entries.forEach { lang ->
                    RadioRow(lang.label, s.outputLanguage == lang) { update { it.copy(outputLanguage = lang) } }
                }
                Field("Yêu cầu tóm tắt tự do (mẫu 'Tự do')", s.customPrompt, singleLine = false) { v ->
                    update { it.copy(customPrompt = v) }
                }
                Hint("Ví dụ: \"Viết lại thành email gửi khách hàng, giọng lịch sự\".")
            }

            val usesGemini = s.sttProvider == ProviderKind.GEMINI || s.summaryProvider == ProviderKind.GEMINI
            val usesOpenAi = s.sttProvider == ProviderKind.OPENAI_COMPATIBLE || s.summaryProvider == ProviderKind.OPENAI_COMPATIBLE

            if (usesGemini) {
                Section("Google Gemini") {
                    SecretField("Gemini API key", s.geminiKey) { v -> update { it.copy(geminiKey = v) } }
                    Hint("Lấy key miễn phí: aistudio.google.com → Get API key.")
                    if (s.sttProvider == ProviderKind.GEMINI) {
                        Field("Model chuyển giọng nói", s.geminiSttModel) { v -> update { it.copy(geminiSttModel = v) } }
                    }
                    if (s.summaryProvider == ProviderKind.GEMINI) {
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
                    if (s.summaryProvider == ProviderKind.OPENAI_COMPATIBLE) {
                        Field("Model tóm tắt", s.openAiChatModel) { v -> update { it.copy(openAiChatModel = v) } }
                    }
                    TestButton(vm, if (s.sttProvider == ProviderKind.OPENAI_COMPATIBLE) s.sttConfig() else s.summaryConfig())
                }
            }

            Hint(
                "Âm thanh ghi ở 32 kbps (~14 MB/giờ). Chi phí chuyển văn bản ~\$0.04/giờ âm thanh (Gemini Flash-Lite gói trả phí " +
                    "hoặc Groq Whisper); phần im lặng đã lược bỏ không bị tính.",
            )
            Spacer(Modifier.padding(8.dp))
        }
    }
}

/** Titled group: the label sits above a rounded card, like system settings. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title.substringAfter(". "),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                shape = RoundedCornerShape(50),
            )
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
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
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
    var result by remember(config) { mutableStateOf<ConnectionResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
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
    }
    result?.let { r ->
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (r.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(r.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
