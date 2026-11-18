package com.feishuexporter.app.model

/** 文档树节点（对应桌面版 DocNode） */
data class DocNode(
    val wikiToken: String,
    val objToken: String,
    val title: String,
    val path: List<String>,
    val kind: String,      // doc / sheet / base / folder
    val url: String,
    val hasChild: Boolean = false
)

/** 文档正文（block_map 为原始 JSON 字符串，避免跨层对象转换开销） */
data class Document(
    val title: String,
    val blocksJson: String,     // block_map 的 JSON 字符串
    val sequenceJson: String    // block_sequence 的 JSON 字符串
)

/** 图片信息 */
data class ImageRef(
    val token: String,
    val fname: String,
    val mime: String = "image/png"
)

/** 导出配置（对齐桌面版 config.py） */
data class ExportConfig(
    val url: String = "",
    val images: String = "per-doc-data",   // per-doc-data/shared-images/inline-base64/url-only/skip
    val format: String = "markdown",       // markdown/html/txt
    val layout: String = "tree",           // tree/flat
    val workers: Int = 8,
    val intervalMs: Int = 200,
    val readme: Boolean = true,
    val subdocs: Boolean = true,
    val retries: Int = 3,
    val overwrite: Boolean = false,
    val titleAsHeading: Boolean = true,
    // ---- 认证方式 ----
    // "oauth"  = 飞书开放平台应用（user_access_token → 官方 API，稳定，需同租户+协作者）
    // "browser"= WebView 登录态（公开/外部链接兜底，走 window.DATA 抓取）
    // "auto"   = 先试 oauth，失败自动降级 browser
    val authMode: String = "auto",
    val appId: String = "",
    val appSecret: String = "",
    val redirectPort: Int = 3000,           // 本地回调端口，回调路径固定 /callback
    val userAccessToken: String = ""        // UI 层 OAuth 授权后拿到，传入引擎
)

/** 进度事件 */
data class ProgressEvent(
    val phase: String,      // auth/tree/export/done/error
    val message: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val doc: String = ""
)

/** 导出报告 */
data class ExportReport(
    val docs: Int = 0,
    val images: Int = 0,
    val sheets: Int = 0,
    val failed: List<String> = emptyList(),
    val zipPath: String = "",
    val elapsedMs: Long = 0
)
