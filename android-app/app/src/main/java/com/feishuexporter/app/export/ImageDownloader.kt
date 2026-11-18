package com.feishuexporter.app.export

import com.feishuexporter.app.model.ExportConfig
import com.feishuexporter.app.model.ImageRef
import com.feishuexporter.app.model.ProgressEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 图片下载器：从 WebView 的 cookie 下载飞书图片。
 * 端点：cover 失败回退 download/all（对应桌面版踩坑）。
 */
class ImageDownloader(
    private val cookies: String,
    private val origin: String,
    private val cfg: ExportConfig,
    private val onProgress: (ProgressEvent) -> Unit = {},
    // API 模式：传入 apiSource 与 token 后，改用官方 drive 接口下载
    private val apiDownload: ((String) -> ByteArray?)? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 下载单张图片到指定路径，成功返回 true */
    fun downloadOne(ref: ImageRef, destFile: File): Boolean {
        if (cfg.images == "skip" || cfg.images == "url-only") return false
        if (destFile.exists() && destFile.length() > 0 && !cfg.overwrite) return true

        // API 模式：走官方 drive/medias 下载（带 Bearer）
        if (apiDownload != null) {
            repeat(cfg.retries.coerceAtLeast(1)) { attempt ->
                val bytes = apiDownload!!.invoke(ref.token)
                if (bytes != null && bytes.isNotEmpty() && isImage(bytes)) {
                    destFile.parentFile?.mkdirs()
                    destFile.writeBytes(bytes)
                    return true
                }
                if (attempt < cfg.retries - 1) Thread.sleep(300L * (attempt + 1))
            }
            return false
        }

        // 浏览器模式：cover 失败回退 download/all（对应桌面版踩坑）
        val endpoints = listOf(
            "$origin/space/api/box/stream/download/v2/cover/${ref.token}/",
            "$origin/space/api/box/stream/download/all/${ref.token}/"
        )
        for (url in endpoints) {
            var lastErr: Exception? = null
            repeat(cfg.retries.coerceAtLeast(1)) { attempt ->
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Cookie", cookies)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val bytes = resp.body?.bytes()
                        if (resp.isSuccessful && bytes != null && bytes.isNotEmpty()) {
                            // 校验是图片（PNG/JPEG/GIF/WEBP 文件头 或 content-type）
                            val ct = resp.header("Content-Type", "") ?: ""
                            val ok = ct.contains("image", ignoreCase = true) || isImage(bytes)
                            if (ok) {
                                destFile.parentFile?.mkdirs()
                                destFile.writeBytes(bytes)
                                return true
                            }
                        }
                    }
                } catch (e: Exception) { lastErr = e }
                if (attempt < cfg.retries - 1) Thread.sleep(300L * (attempt + 1))
            }
            lastErr?.let { /* 换端点重试 */ }
        }
        return false
    }

    /** 并发下载一个文档的图片到 dataDir */
    suspend fun downloadBatch(refs: List<ImageRef>, dataDir: File, docTitle: String) = withContext(Dispatchers.IO) {
        if (refs.isEmpty()) return@withContext
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val jobs = refs.chunked(cfg.workers).map { chunk ->
            async {
                for (ref in chunk) {
                    val dest = File(dataDir, ref.fname)
                    downloadOne(ref, dest)
                    val n = done.incrementAndGet()
                    if (n % 10 == 0 || n == refs.size) {
                        onProgress(ProgressEvent("export", "$docTitle 图片 $n/${refs.size}", n, refs.size, docTitle))
                    }
                }
            }
        }
        jobs.awaitAll()
    }

    private fun isImage(b: ByteArray): Boolean {
        if (b.size < 2) return false
        return b[0] == 0x89.toByte() ||   // PNG
               b[0] == 'G'.code.toByte() || // GIF
               b[0] == 'R'.code.toByte() || // RIFF(WebP)
               (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) // JPEG
    }
}
