package com.feishuexporter.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.feishuexporter.app.model.ExportConfig
import com.feishuexporter.app.model.ExportReport
import com.feishuexporter.app.model.ProgressEvent

/** 主界面：配置 + 进度 + 日志 + 结果 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    running: Boolean,
    progress: ProgressEvent,
    logs: List<String>,
    report: ExportReport?,
    hasToken: Boolean,
    onLogin: () -> Unit,
    onOAuth: () -> Unit,
    onClearToken: () -> Unit,
    onStart: (ExportConfig) -> Unit,
    onCancel: () -> Unit,
    onOpenResult: () -> Unit
) {
    var url by remember { mutableStateOf(TextFieldValue("")) }
    var images by remember { mutableStateOf("per-doc-data") }
    var format by remember { mutableStateOf("markdown") }
    var layout by remember { mutableStateOf("tree") }
    var workers by remember { mutableStateOf("8") }
    var expandedImages by remember { mutableStateOf(false) }
    var authMode by remember { mutableStateOf(if (hasToken) "oauth" else "auto") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("飞书导出器") },
                actions = {
                    IconButton(onClick = onOAuth) { Icon(Icons.Default.Key, "OAuth 授权") }
                    IconButton(onClick = onLogin) { Icon(Icons.Default.Login, "登录") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("认证方式", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("auto", "oauth", "browser").forEach { m ->
                        FilterChip(
                            selected = authMode == m,
                            onClick = { authMode = m },
                            enabled = !running,
                            label = { Text(when (m) { "auto" -> "自动"; "oauth" -> "API"; else -> "网页" }) }
                        )
                    }
                }
                val hint = when (authMode) {
                    "oauth" -> "API：稳定，用于同租户知识库（需先授权，且应用已加为协作者）"
                    "browser" -> "网页：外部/公开链接兜底，走浏览器登录态"
                    else -> "自动：优先 API，读不到时自动退回网页模式"
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hasToken) "✓ 已获得 API 授权" else "○ 未授权 API",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasToken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasToken) TextButton(onClick = onClearToken, enabled = !running) { Text("清除") }
                }
            }

            item {
                Text("知识库 / 文档链接", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://xxx.feishu.cn/wiki/space/...") },
                    enabled = !running,
                    minLines = 2
                )
            }

            item {
                Text("图片保存策略", style = MaterialTheme.typography.titleMedium)
                ExposedDropdownMenuBox(expanded = expandedImages, onExpandedChange = { expandedImages = it }) {
                    OutlinedTextField(
                        value = imagesLabel(images), onValueChange = {},
                        readOnly = true, enabled = !running,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedImages) }
                    )
                    ExposedDropdownMenu(expanded = expandedImages, onDismissRequest = { expandedImages = false }) {
                        listOf("per-doc-data", "shared-images", "inline-base64", "url-only", "skip").forEach {
                            DropdownMenuItem(text = { Text(imagesLabel(it)) }, onClick = { images = it; expandedImages = false })
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("格式", style = MaterialTheme.typography.labelLarge)
                        SegmentedControl(listOf("markdown", "html", "txt"), format, !running) { format = it }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("目录", style = MaterialTheme.typography.labelLarge)
                        SegmentedControl(listOf("tree", "flat"), layout, !running) { layout = it }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = workers, onValueChange = { workers = it.filter { c -> c.isDigit() } },
                    label = { Text("并发下载数") }, enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                if (running) {
                    LinearProgressIndicator(
                        progress = if (progress.total > 0) progress.current.toFloat() / progress.total else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(progress.message, style = MaterialTheme.typography.bodySmall)
                        Text("${progress.current}/${progress.total}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("取消")
                    }
                } else {
                    Button(
                        onClick = {
                            onStart(ExportConfig(
                                url = url.text.trim(),
                                authMode = authMode,
                                images = images, format = format, layout = layout,
                                workers = workers.toIntOrNull()?.coerceIn(1, 32) ?: 8
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = url.text.isNotBlank()
                    ) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("开始导出") }
                }
            }

            item {
                Text("日志", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp).heightIn(max = 240.dp)) {
                        if (logs.isEmpty()) Text("暂无日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else logs.takeLast(60).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            item {
                if (report != null && !running) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("导出完成", style = MaterialTheme.typography.titleMedium)
                            Text("文档 ${report.docs} 篇 · 图片 ${report.images} 张 · 失败 ${report.failed.size} 项")
                            if (report.zipPath.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = onOpenResult) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("打开 / 分享 zip") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedControl(options: List<String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { opt ->
            FilterChip(
                selected = opt == selected,
                onClick = { onSelect(opt) },
                enabled = enabled,
                label = { Text(opt) }
            )
        }
    }
}

private fun imagesLabel(v: String) = when (v) {
    "per-doc-data" -> "每篇文档独立 data/ 目录"
    "shared-images" -> "全局共享图片目录"
    "inline-base64" -> "内嵌 Base64"
    "url-only" -> "仅保留图片链接"
    "skip" -> "不保存图片"
    else -> v
}
