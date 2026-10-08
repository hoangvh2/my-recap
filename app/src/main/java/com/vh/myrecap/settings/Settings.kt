package com.vh.myrecap.settings

import android.content.Context
import com.vh.myrecap.core.InterviewerSpeech
import com.vh.myrecap.core.OutputLanguage
import com.vh.myrecap.core.ProviderConfig
import com.vh.myrecap.core.ProviderKind
import com.vh.myrecap.core.SegmenterConfig
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.VadSensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val sttProvider: ProviderKind = ProviderKind.GEMINI,
    /** Summaries run only when the user asks, over the clips they pick. */
    val summaryProvider: ProviderKind = ProviderKind.GEMINI,

    val geminiKey: String = "",
    val geminiSttModel: String = ProviderConfig.GEMINI_DEFAULT_MODEL,
    val geminiSummaryModel: String = ProviderConfig.GEMINI_DEFAULT_MODEL,
    val geminiBaseUrl: String = ProviderConfig.GEMINI_BASE_URL,

    val openAiBaseUrl: String = ProviderConfig.GROQ_BASE_URL,
    val openAiKey: String = "",
    val openAiSttModel: String = ProviderConfig.GROQ_STT_MODEL,
    val openAiChatModel: String = ProviderConfig.GROQ_CHAT_MODEL,
    /** ISO-639-1 hint for Whisper; blank = auto-detect. */
    val whisperLanguage: String = "",

    val outputLanguage: OutputLanguage = OutputLanguage.VI,
    val customPrompt: String = "",
    val defaultMode: SessionMode = SessionMode.INTERVIEW,

    /** Transcribe clips automatically (no summary) once recorded. */
    val autoProcess: Boolean = true,
    /** Transcribe each finished clip while still recording, so results are ready sooner. */
    val processWhileRecording: Boolean = true,
    val wifiOnly: Boolean = false,
    /** Upper bound for one clip, so each upload stays small. */
    val segmentMinutes: Int = 10,

    /** Split the recording into one clip per conversational turn, at long pauses. */
    val autoSplit: Boolean = true,
    val splitPauseSec: Int = 6,
    /** Drop long silences inside a clip before upload. */
    val trimSilence: Boolean = true,
    val vadSensitivity: VadSensitivity = VadSensitivity.NORMAL,
    val interviewerSpeech: InterviewerSpeech = InterviewerSpeech.KEEP,
    /** User confirmed they followed the vendor (Tecno/HiOS) background-run guide. */
    val vendorGuideDone: Boolean = false,
) {
    fun sttConfig(): ProviderConfig = when (sttProvider) {
        ProviderKind.GEMINI -> ProviderConfig(ProviderKind.GEMINI, geminiBaseUrl.trimEnd('/'), geminiKey.trim(), geminiSttModel.trim())
        ProviderKind.OPENAI_COMPATIBLE ->
            ProviderConfig(ProviderKind.OPENAI_COMPATIBLE, openAiBaseUrl.trimEnd('/'), openAiKey.trim(), openAiSttModel.trim())
    }

    /** Clip segmentation; with auto-split off, clips are fixed-length chunks as before. */
    fun segmenterConfig(): SegmenterConfig = SegmenterConfig(
        splitPauseMs = if (autoSplit) splitPauseSec.coerceIn(2, 60) * 1000 else Int.MAX_VALUE,
        trimSilence = trimSilence,
        maxClipMs = segmentMinutes.coerceIn(1, 30) * 60_000,
        minSpeechMs = if (autoSplit || trimSilence) SegmenterConfig().minSpeechMs else 0,
    )

    /** VAD only matters when splitting or trimming; otherwise everything is kept. */
    val usesVad: Boolean get() = autoSplit || trimSilence

    fun summaryConfig(): ProviderConfig = when (summaryProvider) {
        ProviderKind.GEMINI -> ProviderConfig(ProviderKind.GEMINI, geminiBaseUrl.trimEnd('/'), geminiKey.trim(), geminiSummaryModel.trim())
        ProviderKind.OPENAI_COMPATIBLE ->
            ProviderConfig(ProviderKind.OPENAI_COMPATIBLE, openAiBaseUrl.trimEnd('/'), openAiKey.trim(), openAiChatModel.trim())
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        save(next)
        _settings.value = next
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            sttProvider = enumPref("sttProvider", d.sttProvider),
            summaryProvider = enumPref("summaryProvider", d.summaryProvider),
            geminiKey = secrets.decrypt(prefs.getString("geminiKey", "") ?: ""),
            geminiSttModel = prefs.getString("geminiSttModel", null) ?: d.geminiSttModel,
            geminiSummaryModel = prefs.getString("geminiSummaryModel", null) ?: d.geminiSummaryModel,
            geminiBaseUrl = prefs.getString("geminiBaseUrl", null) ?: d.geminiBaseUrl,
            openAiBaseUrl = prefs.getString("openAiBaseUrl", null) ?: d.openAiBaseUrl,
            openAiKey = secrets.decrypt(prefs.getString("openAiKey", "") ?: ""),
            openAiSttModel = prefs.getString("openAiSttModel", null) ?: d.openAiSttModel,
            openAiChatModel = prefs.getString("openAiChatModel", null) ?: d.openAiChatModel,
            whisperLanguage = prefs.getString("whisperLanguage", null) ?: d.whisperLanguage,
            outputLanguage = enumPref("outputLanguage", d.outputLanguage),
            customPrompt = prefs.getString("customPrompt", null) ?: d.customPrompt,
            defaultMode = enumPref("defaultMode", d.defaultMode),
            autoProcess = prefs.getBoolean("autoProcess", d.autoProcess),
            processWhileRecording = prefs.getBoolean("processWhileRecording", d.processWhileRecording),
            wifiOnly = prefs.getBoolean("wifiOnly", d.wifiOnly),
            segmentMinutes = prefs.getInt("segmentMinutes", d.segmentMinutes),
            vendorGuideDone = prefs.getBoolean("vendorGuideDone", d.vendorGuideDone),
            autoSplit = prefs.getBoolean("autoSplit", d.autoSplit),
            splitPauseSec = prefs.getInt("splitPauseSec", d.splitPauseSec),
            trimSilence = prefs.getBoolean("trimSilence", d.trimSilence),
            vadSensitivity = enumPref("vadSensitivity", d.vadSensitivity),
            interviewerSpeech = enumPref("interviewerSpeech", d.interviewerSpeech),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("sttProvider", s.sttProvider.name)
            .putString("summaryProvider", s.summaryProvider.name)
            .putString("geminiKey", secrets.encrypt(s.geminiKey))
            .putString("geminiSttModel", s.geminiSttModel)
            .putString("geminiSummaryModel", s.geminiSummaryModel)
            .putString("geminiBaseUrl", s.geminiBaseUrl)
            .putString("openAiBaseUrl", s.openAiBaseUrl)
            .putString("openAiKey", secrets.encrypt(s.openAiKey))
            .putString("openAiSttModel", s.openAiSttModel)
            .putString("openAiChatModel", s.openAiChatModel)
            .putString("whisperLanguage", s.whisperLanguage)
            .putString("outputLanguage", s.outputLanguage.name)
            .putString("customPrompt", s.customPrompt)
            .putString("defaultMode", s.defaultMode.name)
            .putBoolean("autoProcess", s.autoProcess)
            .putBoolean("processWhileRecording", s.processWhileRecording)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putInt("segmentMinutes", s.segmentMinutes)
            .putBoolean("vendorGuideDone", s.vendorGuideDone)
            .putBoolean("autoSplit", s.autoSplit)
            .putInt("splitPauseSec", s.splitPauseSec)
            .putBoolean("trimSilence", s.trimSilence)
            .putString("vadSensitivity", s.vadSensitivity.name)
            .putString("interviewerSpeech", s.interviewerSpeech.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumPref(key: String, fallback: E): E {
        val name = prefs.getString(key, null) ?: return fallback
        return enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
