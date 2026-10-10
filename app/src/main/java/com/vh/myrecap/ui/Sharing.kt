package com.vh.myrecap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.SessionStore
import java.io.File

object Sharing {
    /** Above this size the text goes as a file: Android intents fail on very large extras. */
    private const val MAX_INLINE_CHARS = 100_000

    fun shareText(context: Context, title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, title)
        if (text.length <= MAX_INLINE_CHARS) {
            intent.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        } else {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, safeName(title) + ".md").apply { writeText(text) }
            intent.setType("text/markdown")
                .putExtra(Intent.EXTRA_STREAM, uri(context, file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
    }

    fun shareAudio(context: Context, store: SessionStore, session: Session) {
        val uris = ArrayList(session.audioClips.sortedBy { it.index }.map { uri(context, store.audioFile(session.id, it)) })
        if (uris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("audio/aac")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, session.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Chia sẻ file ghi âm"))
    }

    fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Đã sao chép", Toast.LENGTH_SHORT).show()
    }

    private fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun safeName(s: String) = s.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(60).ifBlank { "my-recap" }
}
