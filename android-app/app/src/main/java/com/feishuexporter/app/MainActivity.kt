package com.feishuexporter.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.feishuexporter.app.auth.OAuthSession
import com.feishuexporter.app.model.ExportConfig
import com.feishuexporter.app.model.ExportReport
import com.feishuexporter.app.model.ProgressEvent
import com.feishuexporter.app.ui.MainScreen
import com.feishuexporter.app.ui.OAuthScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var orchestrator: ExportOrchestrator? = null
    private val progress = mutableStateOf(ProgressEvent("idle"))
    private val running = mutableStateOf(false)
    private val logs = mutableStateListOf<String>()
    private val report = mutableStateOf<ExportReport?>(null)
    private val showLogin = mutableStateOf(false)
    private val showOAuth = mutableStateOf(false)
    private val userToken = mutableStateOf("")       // OAuth 授权得到的 user_access_token
    private val oauthStatus = mutableStateOf("")
    private val oauthAuthorizeUrl = mutableStateOf("") // OAuth 授权页 WebView 的 URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                when {
                    showOAuth.value -> OAuthScreen(
                        status = oauthStatus.value,
                        authorizeUrl = oauthAuthorizeUrl.value,
                        onStart = { appId, secret, port -> startOAuth(appId, secret, port) },
                        onCancel = { showOAuth.value = false }
                    )
                    showLogin.value -> LoginScreen(onDone = { showLogin.value = false })
                    else -> MainScreen(
                        running = running.value,
                        progress = progress.value,
                        logs = logs,
                        report = report.value,
                        hasToken = userToken.value.isNotBlank(),
                        onLogin = { showLogin.value = true },
                        onOAuth = { showOAuth.value = true },
                        onClearToken = {
                            userToken.value = ""
                            Toast.makeText(this@MainActivity, "已清除授权", Toast.LENGTH_SHORT).show()
                        },
                        onStart = { cfg ->
                            // 把当前 token 注入配置
                            startExport(cfg.copy(userAccessToken = userToken.value))
                        },
                        onCancel = { orchestrator?.cancel() },
                        onOpenResult = { report.value?.zipPath?.let { openOrShare(it) } }
                    )
                }
            }
        }
    }

    private fun startExport(cfg: ExportConfig) {
        if (cfg.url.isBlank()) {
            Toast.makeText(this, "请先填写飞书链接", Toast.LENGTH_SHORT).show()
            return
        }
        if (cfg.authMode == "oauth" && cfg.userAccessToken.isBlank()) {
            Toast.makeText(this, "请先完成飞书开放平台授权", Toast.LENGTH_SHORT).show()
            return
        }
        running.value = true
        report.value = null
        logs.clear()
        progress.value = ProgressEvent("auth", "准备中...")
        orchestrator = ExportOrchestrator(this) { e ->
            runOnUiThread {
                progress.value = e
                if (e.message.isNotEmpty()) logs.add("[${e.phase}] ${e.message}")
            }
        }
        val current = orchestrator!!
        lifecycleScopeLaunch {
            val r = current.run(cfg)
            runOnUiThread {
                running.value = false
                report.value = r
            }
        }
    }

    /** OAuth 授权：本地起回调 server → WebView 打开授权链接 → 拿 code 换 token */
    private fun startOAuth(appId: String, secret: String, port: Int) {
        if (appId.isBlank() || secret.isBlank()) {
            oauthStatus.value = "请填写 App ID 和 App Secret"
            return
        }
        val session = OAuthSession(appId.trim(), secret.trim(), port)
        oauthStatus.value = "正在打开授权页面，请完成扫码/登录授权…"
        lifecycleScopeLaunch {
            // 后台等回调
            val codeDeferred = async(Dispatchers.IO) {
                session.startAndWait(timeoutMs = 180_000)
            }
            // 主线程打开授权 WebView
            runOnUiThread {
                oauthAuthorizeUrl.value = session.buildAuthorizeUrl()
            }
            val code = codeDeferred.await()
            session.stop()
            if (code == null) {
                runOnUiThread {
                    oauthStatus.value = "未收到授权回调（超时或端口 $port 被占用），请重试"
                }
                return@lifecycleScopeLaunch
            }
            oauthStatus.value = "授权成功，正在换取 token…"
            val result = session.exchangeToken(code)
            runOnUiThread {
                result.fold(
                    onSuccess = { token ->
                        userToken.value = token
                        oauthStatus.value = "授权完成，token 已就绪"
                        showOAuth.value = false
                        Toast.makeText(this@MainActivity, "授权成功", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        oauthStatus.value = "换取 token 失败：${e.message}"
                    }
                )
            }
        }
    }

    private fun lifecycleScopeLaunch(block: suspend CoroutineScope.() -> Unit) {
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch(block = block)
    }

    private fun openOrShare(path: String) {
        try {
            val uri = Uri.parse(path)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/zip")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "打开或分享导出文件")
            if (intent.resolveActivity(packageManager) != null) startActivity(chooser)
            else {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                }
                startActivity(Intent.createChooser(share, "分享导出文件"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件：$e", Toast.LENGTH_LONG).show()
        }
    }

    /** 登录态 WebView（外部/公开链接兜底）：默认 UA + 拦截 lark:// 等非 http(s) 协议 */
    @Composable
    fun LoginScreen(onDone: () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) { Text("登录完成后点此返回") }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        val wv = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                setAcceptThirdPartyCookies(wv, true)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?, request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                return !(url.startsWith("http://") || url.startsWith("https://"))
                            }
                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (url == null) return false
                                return !(url.startsWith("http://") || url.startsWith("https://"))
                            }
                        }
                        loadUrl("https://bcnjxil3ai78.feishu.cn/")
                    }
                },
                modifier = Modifier.fillMaxSize().weight(1f)
            )
        }
    }
}
