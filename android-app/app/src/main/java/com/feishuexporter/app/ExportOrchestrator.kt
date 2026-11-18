package com.feishuexporter.app

import android.content.Context
import android.net.Uri
import com.feishuexporter.app.engine.FeishuWebView
import com.feishuexporter.app.export.FsLayout
import com.feishuexporter.app.export.ImageDownloader
import com.feishuexporter.app.export.ZipPacker
import com.feishuexporter.app.model.DocNode
import com.feishuexporter.app.model.Document
import com.feishuexporter.app.model.ExportConfig
import com.feishuexporter.app.model.ExportReport
import com.feishuexporter.app.model.ProgressEvent
import com.feishuexporter.app.parser.BlockRenderer
import com.feishuexporter.app.source.ApiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导出编排中枢：串联 数据源→解析→图片→打包。双引擎：
 *  - API 引擎（user_access_token → 官方 wiki/docx/drive API）：内部知识库，稳定
 *  - WebView 引擎（登录态 → window.DATA）：外部/公开链接兜底
 * authMode: oauth=仅 API / browser=仅 WebView / auto=先 API 失败降级 WebView
 */
class ExportOrchestrator(
    private val context: Context,
    private val onProgress: (ProgressEvent) -> Unit
) {
    @Volatile
    var cancelled = false
        private set

    fun cancel() { cancelled = true }

    suspend fun run(cfg: ExportConfig): ExportReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val failed = mutableListOf<String>()
        var docCount = 0; var imgCount = 0
        var zipUri: Uri? = null

        val origin = try {
            val u = java.net.URL(cfg.url)
            "${u.protocol}://${u.host}"
        } catch (e: Exception) { "https://bcnjxil3ai78.feishu.cn" }

        // ---- 决定引擎 ----
        var useApi = cfg.authMode == "oauth" ||
                (cfg.authMode == "auto" && cfg.userAccessToken.isNotBlank())

        var apiSource: ApiSource? = null
        var webEngine: FeishuWebView? = null
        var cookies = ""
        var nodes: List<DocNode> = emptyList()

        // 尝试 API 引擎
        if (useApi && cfg.userAccessToken.isNotBlank()) {
            onProgress(ProgressEvent("auth", "使用开放平台 API（user_access_token）"))
            apiSource = ApiSource(cfg.userAccessToken, onProgress)
            nodes = apiSource.listNodes(cfg.url).filter { it.kind == "doc" }
            if (nodes.isEmpty()) {
                if (cfg.authMode == "oauth") {
                    return@withContext ExportReport(
                        failed = listOf("API 未读到节点：请确认应用已加为知识库协作者、已开通 wiki/docx 权限并发布版本"),
                        elapsedMs = System.currentTimeMillis() - start)
                }
                onProgress(ProgressEvent("auth", "API 无数据，自动降级 WebView 登录态模式"))
                useApi = false
            }
        } else if (cfg.authMode == "oauth") {
            return@withContext ExportReport(
                failed = listOf("OAuth 模式缺少 token：请先在首页完成飞书授权"),
                elapsedMs = System.currentTimeMillis() - start)
        } else {
            useApi = false
        }

        // 尝试 WebView 引擎
        if (!useApi) {
            onProgress(ProgressEvent("auth", "使用 WebView 登录态模式（外部/公开链接）"))
            webEngine = FeishuWebView(context, onProgress)
            val ok = webEngine.checkLogin(cfg.url)
            if (!ok && cfg.authMode == "browser") {
                webEngine.close()
                return@withContext ExportReport(
                    failed = listOf("未登录：公开链接可直接访问；若需登录请先在 WebView 登录后重试"),
                    elapsedMs = System.currentTimeMillis() - start)
            }
            nodes = webEngine.listNodes(cfg.url).filter { it.kind == "doc" }
            cookies = webEngine.cookiesFor(origin)
            if (nodes.isEmpty()) {
                webEngine.close()
                return@withContext ExportReport(
                    failed = listOf("WebView 未读到节点：链接可能需登录，或为动态渲染页面"),
                    elapsedMs = System.currentTimeMillis() - start)
            }
        }

        onProgress(ProgressEvent("export", "开始导出 ${nodes.size} 项", 0, nodes.size))

        val workRoot = File(context.filesDir, "export_${System.currentTimeMillis()}")
        workRoot.mkdirs()
        val layout = FsLayout(workRoot, cfg)
        val readmeRecords = mutableListOf<Triple<String, String, Int>>()
        val apiDl = apiSource?.let { src -> { token: String -> src.downloadImageBytes(token) } }

        try {
            for ((i, node) in nodes.withIndex()) {
                if (cancelled) { onProgress(ProgressEvent("error", "已取消")); break }
                onProgress(ProgressEvent("export", "导出：${node.title}", i + 1, nodes.size, node.title))
                try {
                    val doc: Document? = if (useApi && apiSource != null)
                        apiSource.getDocument(node)
                    else
                        webEngine?.getDocument(node)

                    if (doc == null) { failed.add("${node.title}: 读取失败"); continue }

                    val renderer = BlockRenderer(doc.blocksJson)
                    val body = renderer.render(doc.sequenceJson)
                    val docDir = layout.docDir(node)

                    if (renderer.images.isNotEmpty()) {
                        val dataDir = File(docDir, "data")
                        val downloader = ImageDownloader(cookies, origin, cfg, onProgress, apiDl)
                        downloader.downloadBatch(renderer.images, dataDir, node.title)
                        imgCount += renderer.images.size
                    }

                    val title = doc.title.ifEmpty { node.title }
                    val mdFile = layout.writeMarkdown(docDir, title, body, cfg.titleAsHeading)
                    val rel = mdFile.absolutePath.removePrefix(workRoot.absolutePath + File.separator)
                    readmeRecords.add(Triple(title, rel, node.path.size))
                    docCount++
                } catch (e: Exception) {
                    failed.add("${node.title}: ${e.message}")
                    onProgress(ProgressEvent("error", "失败：${node.title} - ${e.message}", doc = node.title))
                }
            }

            layout.writeReadme(readmeRecords)
            onProgress(ProgressEvent("export", "打包中..."))
            val zipName = "FeishuExport_${java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date())}.zip"
            zipUri = ZipPacker(context).pack(workRoot, zipName)

            val report = ExportReport(
                docs = docCount, images = imgCount, failed = failed,
                zipPath = zipUri?.toString() ?: "",
                elapsedMs = System.currentTimeMillis() - start
            )
            onProgress(ProgressEvent("done", "完成：$docCount 篇，$imgCount 张图，失败 ${failed.size} 项"))
            return@withContext report
        } finally {
            webEngine?.close()
        }
    }
}
