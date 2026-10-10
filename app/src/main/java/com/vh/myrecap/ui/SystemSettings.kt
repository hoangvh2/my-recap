package com.vh.myrecap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale

/** Permission and system-settings helpers used by the setup checklist and the record buttons. */

fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Returns a click handler that asks for the microphone (and notification) permission when needed,
 * then runs [start]. Recording must start from the visible UI (Android 14+ rule).
 */
@Composable
fun rememberRecordAction(onDenied: () -> Unit, start: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(context, Manifest.permission.RECORD_AUDIO)
        if (micOk) start() else onDenied()
    }
    return {
        val needed = buildList {
            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) start() else launcher.launch(needed.toTypedArray())
    }
}

fun isTranssion(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("tecno", "infinix", "itel")

fun vendorName(): String = if (isTranssion()) "Tecno (HiOS)" else Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

fun vendorGuide(): String = if (isTranssion()) {
    "Phone Master → Tự khởi chạy: bật My Recap. Cài đặt → Pin: tắt tiết kiệm pin cho app. Trong đa nhiệm, khoá thẻ My Recap."
} else {
    "Cho phép tự khởi chạy/chạy nền và khoá app trong màn hình đa nhiệm (tên mục tuỳ hãng)."
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct)
    } catch (_: Exception) {
        tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

fun openNotificationSettings(context: Context) {
    tryStart(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

fun openAppSettings(context: Context) {
    tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

private fun tryStart(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
