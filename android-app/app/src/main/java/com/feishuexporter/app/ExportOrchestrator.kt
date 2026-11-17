package com.feishuexporter.app

import android.content.Context
import android.net.Uri
import com.feishuexporter.app.engine.FeishuWebView
import com.feishuexporter.app.export.FsLayout
import com.feishuexporter.app.export.ImageDownloader
import com.feishuexporter.app.export.ZipPacker
import com.feishuexporter.app.model.ExportConfig
import com.feishuexporter.app.model.ExportReport
import com.feishuexporter.app.model.ProgressEvent
import com.feishuexporter.app.parser.BlockRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导出编排中枢：串联 引擎→解析→图片→打包。
 * 对应桌面版 orchestrator.py，GUI 通过它驱动整个流程。
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

        val engine = FeishuWebView(context, onProgress)
        try {
            // 1. 解析 origin
            val origin = try {
                val u = java.net.URL(cfg.url)
                "${u.protocol}://${u.host}"
            } catch (e: Exception) { "https://bcnjxil3ai78.feishu.cn" }

            // 2. 登录态检查
            onProgress(ProgressEvent("auth", "检查登录态..."))
            val loggedIn = engine.checkLogin(cfg.url)
            onProgress(ProgressEvent("auth", if (loggedIn) "登录态有效" else "未登录，请在浏览器登录后重试"))
            if (!loggedIn) {
                return@withContext ExportReport(failed = listOf("未登录：请先在 APP 内登录飞书"), elapsedMs = System.currentTimeMillis() - start)
            }

            // 3. 文档树
            val nodes = engine.listNodes(cfg.url)
            val targets = nodes.filter { it.kind == "doc" }
            onProgress(ProgressEvent("export", "开始导出 ${targets.size} 项", 0, targets.size))

            // 4. 工作目录
            val workRoot = File(context.filesDir, "export_${System.currentTimeMillis()}")
            workRoot.mkdirs()
            val layout = FsLayout(workRoot, cfg)
            val cookies = engine.cookiesFor(origin)
            val readmeRecords = mutableListOf<Triple<String, String, Int>>()

            // 5. 逐篇导出
            for ((i, node) in targets.withIndex()) {
                if (cancelled) { onProgress(ProgressEvent("error", "已取消")); break }
                onProgress(ProgressEvent("export", "导出：${node.title}", i + 1, targets.size, node.title))
                try {
                    val doc = engine.getDocument(node)
                    if (doc == null) { failed.add("${node.title}: 读取失败"); continue }

                    val renderer = BlockRenderer(doc.blocksJson)
                    val body = renderer.render(doc.sequenceJson)
                    val docDir = layout.docDir(node)

                    // 图片下载
                    if (renderer.images.isNotEmpty()) {
                        val dataDir = File(docDir, "data")
                        val downloader = ImageDownloader(cookies, origin, cfg, onProgress)
                        downloader.downloadBatch(renderer.images, dataDir, node.title)
                        imgCount += renderer.images.size
                    }

                    // 写 Markdown（图片引用已是 data/xxx 相对路径，无需改写）
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

            // 6. README + zip
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
            engine.close()
            return@withContext report
        } finally {
            engine.close()
        }
    }
}
