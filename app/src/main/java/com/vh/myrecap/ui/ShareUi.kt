@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.vh.myrecap.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.cloud.Account
import com.vh.myrecap.cloud.CodePreview
import com.vh.myrecap.cloud.ShareException
import com.vh.myrecap.core.Contact
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.OwnerFilter
import com.vh.myrecap.core.Share
import com.vh.myrecap.core.SharedItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- labels in the lists

/** Everything that belongs to someone else is drawn in this colour (indigo, like the web app). */
val OtherTint = Color(0xFF5B5BD6)

/** Who owns the rows on screen: shared-with-me ids → owner's name, and my item ids → how many people see them. */
data class ShareInfo(val owners: Map<String, String> = emptyMap(), val audiences: Map<String, Int> = emptyMap())

val LocalShareInfo = compositionLocalOf { ShareInfo() }

@Composable
fun ProvideShareInfo(vm: AppViewModel, content: @Composable () -> Unit) {
    val received by vm.received.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val audiences by vm.audiences.collectAsStateWithLifecycle()
    val info = remember(received, contacts, audiences) {
        ShareInfo(
            owners = received.associate { it.id to it.ownerLabel(contacts) },
            audiences = audiences.filterValues { it.isNotEmpty() }.mapValues { it.value.size },
        )
    }
    CompositionLocalProvider(LocalShareInfo provides info, content = content)
}

@Composable
fun Avatar(name: String, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(OtherTint),
        contentAlignment = Alignment.Center,
    ) {
        Text(Share.initial(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
    }
}

/** "🔒 Anh An" on a row someone shared with me: read-only, theirs. */
@Composable
fun OwnerChip(label: String) {
    Surface(color = OtherTint.copy(alpha = 0.14f), contentColor = OtherTint, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = "Chỉ xem", modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "👥 2 người" on my item that others can see. */
@Composable
fun AudienceChip(count: Int) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Group, contentDescription = "Đang chia sẻ", modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("$count người", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** "Tất cả / Của tôi / Được chia sẻ (n)". Only shown when something is shared with me. */
@Composable
fun OwnerFilterRow(selected: OwnerFilter, sharedCount: Int, onSelect: (OwnerFilter) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OwnerFilter.entries.forEach { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onSelect(f) },
                label = { Text(if (f == OwnerFilter.SHARED) "${f.label} ($sharedCount)" else f.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = if (f == OwnerFilter.SHARED) OtherTint else MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = if (f == OwnerFilter.SHARED) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
    }
}

/** "member đã liên kết với bạn · Đặt tên" and "Em Lan đã huỷ liên kết". */
@Composable
fun ShareNoticeCards(vm: AppViewModel) {
    val notices by vm.notices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (notices.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notices.take(3).forEach { n ->
            val linked = n.kind == "linked"
            Surface(color = OtherTint.copy(alpha = 0.10f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(n.fromName, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (linked) "${n.fromName} đã liên kết với bạn" else "${n.fromName} đã huỷ liên kết",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (linked) TextButton(onClick = { vm.openContact(n.fromUid) }) { Text("Đặt tên") }
                    TextButton(onClick = { scope.launch { runCatching { vm.sharing?.dismiss(n.id) } } }) { Text("Đóng") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings: account

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Inside Settings: sign in to sync with the web app and share; who I share with. */
@Composable
fun AccountSection(vm: AppViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val problem by vm.accountProblem.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmOut by remember { mutableStateOf(false) }
    when (val a = account) {
        Account.Off -> Text(
            "Bản cài này chưa bật đồng bộ và chia sẻ (thiếu cấu hình Firebase lúc build). Mọi thứ vẫn lưu trên máy như trước.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Account.SignedOut -> {
            Text(
                "Đăng nhập Google để đồng bộ việc, lịch, ghi chú với bản web trên cùng tài khoản, và chia sẻ từng mục với người đã liên kết.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { context.activity()?.let { vm.signIn(it) } }, modifier = Modifier.fillMaxWidth()) { Text("Đăng nhập Google") }
        }
        is Account.SignedIn -> {
            Text("Đang đồng bộ với ${a.email}", style = MaterialTheme.typography.bodyMedium)
            Surface(
                onClick = vm::openContacts,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Group, null, tint = OtherTint)
                    Spacer(Modifier.width(12.dp))
                    Text("Liên hệ chia sẻ (${contacts.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            }
            TextButton(onClick = { confirmOut = true }) { Text("Đăng xuất") }
        }
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (confirmOut) {
        ConfirmDialog(
            title = "Đăng xuất?",
            text = "Ngừng đồng bộ và chia sẻ trên máy này. Việc, lịch, ghi chú vẫn còn trên máy và trên bản web.",
            confirmLabel = "Đăng xuất",
            destructive = false,
            onConfirm = vm::signOut,
            onDismiss = { confirmOut = false },
        )
    }
}

// ---------------------------------------------------------------- contacts

@Composable
private fun ShareTopBar(title: String, vm: AppViewModel) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Shows [AppViewModel.shareMessage] as a snackbar on this screen. */
@Composable
fun rememberShareMessages(vm: AppViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    val message = vm.shareMessage
    LaunchedEffect(message) {
        if (message != null) {
            vm.clearShareMessage()
            host.showSnackbar(message)
        }
    }
    return host
}

@Composable
fun ContactsScreen(vm: AppViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var making by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(false) }
    val messages = rememberShareMessages(vm)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ShareTopBar("Liên hệ chia sẻ", vm) },
        snackbarHost = { SnackbarHost(messages) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (account !is Account.SignedIn) {
                item {
                    Column {
                        EmptyState(Icons.Rounded.Group, "Cần đăng nhập", "Đăng nhập Google để liên kết và chia sẻ với người khác.")
                        if (account == Account.SignedOut) {
                            Button(onClick = { context.activity()?.let { vm.signIn(it) } }, modifier = Modifier.fillMaxWidth()) { Text("Đăng nhập Google") }
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                Text(
                    "Liên kết cần cả hai đồng ý: một người tạo mã (hết hạn sau 10 phút), người kia nhập mã. " +
                        "Sau đó bạn chọn chia sẻ từng mục với ai; mục khác vẫn riêng tư. Ai cũng có thể huỷ liên kết bất cứ lúc nào.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { making = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tạo mã")
                    }
                    OutlinedButton(onClick = { entering = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nhập mã")
                    }
                }
            }
            if (contacts.isEmpty()) {
                item { EmptyState(Icons.Rounded.Group, "Chưa liên kết với ai", "Tạo mã rồi gửi cho người thân, hoặc nhập mã người đó gửi cho bạn.") }
            } else {
                item { SectionLabel("Đã liên kết", trailing = "${contacts.size}") }
                items(contacts, key = { it.uid }) { c -> ContactRow(c) { vm.openContact(c.uid) } }
            }
        }
    }
    if (making) CodeMakerDialog(vm) { making = false }
    if (entering) CodeEnterDialog(vm) { entering = false }
}

@Composable
private fun ContactRow(c: Contact, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.alias)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.alias, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    listOf(c.googleName.takeIf { it != c.alias }, c.maskedEmail).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Makes a code, shows it big with a countdown; copying or sending it is the person's choice. */
@Composable
private fun CodeMakerDialog(vm: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        code = null
        error = null
        try {
            val (c, e) = vm.sharing?.createCode() ?: throw ShareException("Chưa đăng nhập")
            code = c
            expiresAt = e
        } catch (e: ShareException) {
            error = e.message
        }
    }
    LaunchedEffect(code) {
        while (code != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = ((expiresAt - now) / 1000).coerceAtLeast(0)
    val expired = code != null && left == 0L
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Mã liên kết của bạn") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    code == null -> CircularProgressIndicator()
                    else -> {
                        Text(
                            Share.formatCode(code!!),
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (expired) "Mã đã hết hạn" else "Hết hạn sau %d:%02d".format(left / 60, left % 60),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Gửi mã này cho người bạn muốn liên kết. Họ nhập mã trong Cài đặt › Liên hệ chia sẻ › Nhập mã. Mã dùng được một lần.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!expired) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("Mã liên kết", Share.formatCode(code!!)))
                                    vm.say("Đã chép mã")
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Chép")
                                }
                                OutlinedButton(onClick = {
                                    Sharing.shareText(context, "Mã liên kết", "Mã liên kết VHub Desk của tôi: ${Share.formatCode(code!!)} (hết hạn sau 10 phút)")
                                }) {
                                    Icon(Icons.Rounded.Share, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Gửi")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Đóng") } },
        dismissButton = if (expired || error != null) {
            { TextButton(onClick = { attempt++ }) { Text("Tạo mã mới") } }
        } else {
            null
        },
    )
}

/** Enter the other person's code, see whose it is, choose what to call them, then link. */
@Composable
private fun CodeEnterDialog(vm: AppViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<CodePreview?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val code = Share.normalizeCode(input)
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (preview == null) "Nhập mã liên kết" else "Liên kết với người này?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = preview
                if (p == null) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.uppercase().take(10); error = null },
                        label = { Text("Mã 8 ký tự") },
                        placeholder = { Text("ABCD EFGH") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(p.maskedEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (p.already) Text("Bạn đã liên kết với người này rồi.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it.take(Share.ALIAS_CHARS) },
                        label = { Text("Lưu người này với tên") },
                        supportingText = { Text("Chỉ bạn thấy tên này, sửa lại được.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val p = preview
            Button(
                enabled = !busy && (if (p == null) code != null else Share.cleanAlias(alias).isNotEmpty() && !p.already),
                onClick = {
                    val sharing = vm.sharing ?: return@Button
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            if (p == null) {
                                val found = sharing.previewCode(code!!)
                                preview = found
                                alias = found.name
                            } else {
                                val name = Share.cleanAlias(alias)
                                sharing.joinCode(code!!, name)
                                vm.say("Đã liên kết với $name")
                                onClose()
                            }
                        } catch (e: ShareException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (p == null) "Kiểm tra mã" else "Liên kết") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Huỷ") } },
    )
}

@Composable
fun ContactDetailScreen(vm: AppViewModel, uid: String) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val received by vm.received.collectAsStateWithLifecycle()
    val audiences by vm.audiences.collectAsStateWithLifecycle()
    val contact = contacts.firstOrNull { it.uid == uid }
    val scope = rememberCoroutineScope()
    val messages = rememberShareMessages(vm)
    var confirmUnlink by remember { mutableStateOf(false) }
    var alias by remember(contact?.alias) { mutableStateOf(contact?.alias.orEmpty()) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ShareTopBar(contact?.alias ?: "Liên hệ", vm) },
        snackbarHost = { SnackbarHost(messages) },
    ) { padding ->
        if (contact == null) {
            Box(Modifier.padding(padding)) { EmptyState(Icons.Rounded.Group, "Không còn liên kết", "Người này đã huỷ liên kết, hoặc danh sách đang tải.") }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(contact.alias, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(contact.googleName, style = MaterialTheme.typography.titleMedium)
                    Text(contact.maskedEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it.take(Share.ALIAS_CHARS) },
                label = { Text("Tên gợi nhớ (chỉ bạn thấy)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            val clean = Share.cleanAlias(alias)
            Button(
                enabled = clean.isNotEmpty() && clean != contact.alias,
                onClick = {
                    scope.launch {
                        try {
                            vm.sharing?.rename(contact, clean)
                            vm.say("Đã đổi tên gợi nhớ")
                        } catch (e: ShareException) {
                            vm.say(e.message ?: "Chưa lưu được")
                        }
                    }
                },
            ) { Text("Lưu tên") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Báo cho tôi khi người này chia sẻ hoặc sửa một mục", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = contact.notify, onCheckedChange = { on ->
                    scope.launch {
                        try {
                            vm.sharing?.setNotify(contact, on)
                        } catch (e: ShareException) {
                            vm.say(e.message ?: "Chưa lưu được")
                        }
                    }
                })
            }
            val theirs = received.count { it.ownerUid == uid }
            val mine = audiences.values.count { uid in it }
            Text(
                "Người này chia sẻ với bạn $theirs mục · bạn chia sẻ với người này $mine mục",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.heightIn(min = 8.dp))
            OutlinedButton(
                onClick = { confirmUnlink = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Huỷ liên kết") }
        }
    }
    if (confirmUnlink && contact != null) {
        ConfirmDialog(
            title = "Huỷ liên kết với ${contact.alias}?",
            text = "Hai bên không còn thấy các mục đã chia sẻ cho nhau. Người kia sẽ nhận thông báo. Muốn chia sẻ lại phải tạo mã mới.",
            confirmLabel = "Huỷ liên kết",
            destructive = true,
            onConfirm = {
                scope.launch {
                    try {
                        vm.sharing?.unlink(contact.uid)
                        vm.say("Đã huỷ liên kết với ${contact.alias}")
                        vm.back()
                    } catch (e: ShareException) {
                        vm.say(e.message ?: "Chưa huỷ được")
                    }
                }
            },
            onDismiss = { confirmUnlink = false },
        )
    }
}

// ---------------------------------------------------------------- an item shared with me

@Composable
fun SharedDetailScreen(vm: AppViewModel, id: String) {
    val received by vm.received.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shared = received.firstOrNull { it.id == id }
    val messages = rememberShareMessages(vm)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ShareTopBar(shared?.type?.label ?: "Mục được chia sẻ", vm) },
        snackbarHost = { SnackbarHost(messages) },
    ) { padding ->
        if (shared == null) {
            Box(Modifier.padding(padding)) { EmptyState(Icons.Rounded.Lock, "Không còn được chia sẻ", "Người tạo đã xoá mục này hoặc thôi chia sẻ với bạn.") }
            return@Scaffold
        }
        val item = shared.asItem()
        val owner = shared.ownerLabel(contacts)
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(color = OtherTint.copy(alpha = 0.10f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(owner)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("$owner chia sẻ cho bạn", style = MaterialTheme.typography.titleSmall)
                        Text("Chỉ xem: chỉ người tạo sửa được", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val style = typeStyle(item.type)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(style.icon, style.container, style.content, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (item.important) "❗ ${item.title}" else item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (item.important) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            DetailLine("Khi nào", whenLabel(item))
            DetailLine("Lặp lại", item.recurrence?.label)
            DetailLine("Địa điểm", item.place)
            if (item.details.isNotBlank()) DetailLine(if (item.type == ItemType.NOTE) "Nội dung" else "Ghi chú", item.details)
            val others = shared.audience.size - 1
            if (others > 0) DetailLine("Cùng xem", "$others người khác")
            if ((item.type == ItemType.EVENT || item.type == ItemType.TASK) && item.whenAt != null) {
                OutlinedButton(onClick = { addToCalendar(context, item) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Thêm vào ứng dụng Lịch")
                }
            }
            OutlinedButton(onClick = { vm.copyShared(shared) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chép về của tôi (để tự nhắc và sửa)")
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

// ---------------------------------------------------------------- choosing who sees my item

/** The editor's "Chia sẻ" row: who sees this item now. */
@Composable
fun EditorShareRow(names: List<String>, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Group, null, tint = if (names.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else OtherTint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Chia sẻ", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (names.isEmpty()) "Riêng tư, chỉ bạn thấy" else "Với " + names.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
    }
}

@Composable
fun SharePickerDialog(contacts: List<Contact>, initial: List<String>, onOpenContacts: () -> Unit, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(initial.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chia sẻ mục này với") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Người được chọn chỉ xem được tiêu đề, giờ, địa điểm và ghi chú. Chỉ bạn sửa được. Khi bạn sửa, họ nhận thông báo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (contacts.isEmpty()) {
                    Text("Bạn chưa liên kết với ai.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = onOpenContacts) { Text("Mở Liên hệ chia sẻ") }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(contacts, key = { it.uid }) { c ->
                        val on = c.uid in chosen
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { chosen = if (on) chosen - c.uid else chosen + c.uid }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { chosen = if (it) chosen + c.uid else chosen - c.uid })
                            Avatar(c.alias, 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(c.alias, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDone(contacts.map { it.uid }.filter { it in chosen }) }) {
                Text(if (chosen.isNotEmpty()) "Chia sẻ với ${chosen.size} người" else if (initial.isNotEmpty()) "Thôi chia sẻ" else "Xong")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

/** Mine, theirs, or both, by the filter above the lists. Theirs that are over are left out (see [SharedItem.visible]). */
fun withReceived(mine: List<Item>, received: List<SharedItem>, filter: OwnerFilter): List<Item> {
    val theirs = SharedItem.visible(received, java.time.ZoneId.systemDefault()).map { it.asItem() }
    return when (filter) {
        OwnerFilter.ALL -> mine + theirs
        OwnerFilter.MINE -> mine
        OwnerFilter.SHARED -> theirs
    }
}
