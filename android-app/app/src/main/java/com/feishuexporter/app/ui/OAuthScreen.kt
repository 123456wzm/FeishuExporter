package com.feishuexporter.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebSettings

/**
 * OAuth 授权界面：
 * - 未开始：输入 App ID / App Secret / 本地回调端口，点"开始授权"
 * - 已开始：显示授权 WebView（用户扫码/登录），底部展示状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OAuthScreen(
    status: String,
    authorizeUrl: String,
    onStart: (String, String, Int) -> Unit,
    onCancel: () -> Unit
) {
    var appId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("3000") }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(authorizeUrl) { if (authorizeUrl.isNotBlank()) started = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开放平台授权") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("返回") } }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            if (!started) {
                Text("飞书开放平台 OAuth 授权", style = MaterialTheme.typography.titleMedium)
                Text(
                    "用于访问同租户知识库。请在飞书开放平台创建企业自建应用，开通 wiki/docx/drive 权限并发布，把应用加为知识库协作者，并配置重定向 URL 为 http://localhost:端口/callback。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                OutlinedTextField(
                    value = appId, onValueChange = { appId = it },
                    label = { Text("App ID (client_id)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it },
                    label = { Text("App Secret (client_secret)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("本地回调端口（需与后台配置一致）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onStart(appId.trim(), secret.trim(), port.toIntOrNull() ?: 3000) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = appId.isNotBlank() && secret.isNotBlank()
                ) { Text("开始授权") }
            } else {
                if (authorizeUrl.isNotBlank()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                CookieManager.getInstance().setAcceptCookie(true)
                                webViewClient = WebViewClient()
                                loadUrl(authorizeUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize().weight(1f)
                    )
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
    }
}
