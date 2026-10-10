package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.TextSearch
import kotlinx.coroutines.delay

/** One search box over everything the app keeps; typing without accents works. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    // Debounced: search after a short pause in typing, not on every key.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        delay(250)
        searching = true
        results = vm.search(query)
        searching = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
                    },
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Tìm việc, lịch, chi tiêu, transcript…") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Xoá tìm kiếm") }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            shape = RoundedCornerShape(50),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp)
                                .focusRequester(focus),
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        val r = results
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                r == null -> item {
                    EmptyState(
                        Icons.Rounded.Search,
                        "Tìm trong mọi thứ",
                        "Việc, lịch hẹn, chi tiêu, ghi chú, lời ghi nhanh và transcript phỏng vấn. Gõ không dấu cũng được.",
                    )
                }
                r.isEmpty && !searching -> item {
                    EmptyState(Icons.Rounded.SearchOff, "Không tìm thấy", "Không có gì khớp với “${r.query}”.")
                }
                else -> {
                    val tokens = TextSearch.tokens(r.query)
                    if (r.items.isNotEmpty()) {
                        item { SectionLabel("Việc, lịch, chi tiêu, ghi chú", trailing = "${r.items.size}") }
                        items(r.items, key = { "i-${it.id}" }) { item ->
                            ItemRow(item, onOpen = { vm.openItem(item.id) }, onToggleDone = { vm.setDone(item.id, it) })
                        }
                    }
                    if (r.memos.isNotEmpty()) {
                        item { SectionLabel("Ghi nhanh", trailing = "${r.memos.size}") }
                        items(r.memos, key = { "m-${it.first.id}" }) { (memo, snippet) ->
                            HitRow(
                                style = modeStyle(memo.mode),
                                label = "Ghi nhanh · ${formatDate(memo.createdAt)}",
                                snippet = highlight(snippet, tokens),
                                onClick = { vm.openSession(memo.id) },
                            )
                        }
                    }
                    if (r.clips.isNotEmpty()) {
                        item { SectionLabel("Phỏng vấn & cuộc họp", trailing = "${r.clips.size}") }
                        items(r.clips, key = { "c-${it.session.id}-${it.index}" }) { hit ->
                            HitRow(
                                style = modeStyle(hit.session.mode),
                                label = if (hit.index == null) hit.label else "${hit.session.title} · ${hit.label}",
                                snippet = highlight(hit.snippet, tokens),
                                onClick = {
                                    if (hit.index == null) vm.openSession(hit.session.id) else vm.openClip(hit.session.id, hit.index)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(style: ModeStyle, label: String, snippet: AnnotatedString, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            IconTile(style.icon, style.container, style.content, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Bolds the query words in [text]; accent-folding keeps positions aligned for Vietnamese. */
@Composable
private fun highlight(text: String, tokens: List<String>): AnnotatedString {
    val folded = TextSearch.fold(text)
    val accent = MaterialTheme.colorScheme.primary
    if (folded.length != text.length) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (t in tokens) {
            var from = 0
            while (true) {
                val at = folded.indexOf(t, from)
                if (at < 0) break
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accent), at, at + t.length)
                from = at + t.length
            }
        }
    }
}
