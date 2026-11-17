package com.feishuexporter.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
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
import com.feishuexporter.app.model.ExportConfig
import com.feishuexporter.app.model.ExportReport
import com.feishuexporter.app.model.ProgressEvent
import com.feishuexporter.app.ui.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var orchestrator: ExportOrchestrator? = null
    private val progress = mutableStateOf(ProgressEvent("idle"))
    private val running = mutableStateOf(false)
    private val logs = mutableStateListOf<String>()
    private val report = mutableStateOf<ExportReport?>(null)
    private val showLogin = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                if (showLogin.value) {
                    LoginScreen(onDone = { showLogin.value = false })
                } else {
                    MainScreen(
                        running = running.value,
                        progress = progress.value,
                        logs = logs,
                        report = report.value,
                        onLogin = { showLogin.value = true },
                        onStart = { cfg -> startExport(cfg) },
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

    /** 简化协程启动（不依赖 lifecycle-runtime-ktx 扩展） */
    private fun lifecycleScopeLaunch(block: suspend () -> Unit) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()).launch {
            block()
        }
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

    /** 登录页：可见 WebView，用户在此登录飞书，cookie 全局共享给引擎 */
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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = WebViewClient()
                        loadUrl("https://bcnjxil3ai78.feishu.cn/")
                    }
                },
                modifier = Modifier.fillMaxSize().weight(1f)
            )
        }
    }
}
