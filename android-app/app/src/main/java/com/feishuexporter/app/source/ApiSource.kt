package com.feishuexporter.app.source

import com.feishuexporter.app.model.DocNode
import com.feishuexporter.app.model.Document
import com.feishuexporter.app.model.ProgressEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 飞书官方 Open API 数据源（user_access_token）。
 *
 * 链路（对应桌面版 source/api_source.py）：
 *  1. 从链接解析 space_id
 *  2. GET /wiki/v2/spaces/{space_id}/nodes  →  递归展开 has_child 拿整棵树
 *  3. GET /docx/v1/documents/{obj_token}/blocks  →  全部 blocks（数字 block_type）
 *  4. 转成浏览器风格的 block_map + block_sequence，复用现有 BlockRenderer 解析成 Markdown
 *
 * 图片：官方 blocks 的 image block 里 image.token 是文件 token，
 *      在 Orchestrator 里用 /drive/v1/medias/{token}/download 下载（带 Bearer）。
 */
class ApiSource(
    private val token: String,
    private val onProgress: (ProgressEvent) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val base = "https://open.feishu.cn"

    private fun get(path: String): JSONObject? {
        val req = Request.Builder()
            .url(if (path.startsWith("http")) path else base + path)
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: return null
            return try { JSONObject(txt) } catch (e: Exception) { null }
        }
    }

    /** 从任意 wiki 链接解析 space_id */
    private fun spaceIdFromUrl(url: String): String =
        Regex("/wiki/space/(\\d+)").find(url)?.groupValues?.get(1) ?: ""

    /**
     * 遍历 wiki 文档树。
     * 先拉根节点列表（无 parent_node_token），再对 has_child 的节点递归。
     */
    suspend fun listNodes(rawUrl: String): List<DocNode> = withContext(Dispatchers.IO) {
        val spaceId = spaceIdFromUrl(rawUrl)
        if (spaceId.isEmpty()) {
            onProgress(ProgressEvent("tree", "无法解析 space_id，请传知识库空间链接"))
            return@withContext emptyList()
        }
        onProgress(ProgressEvent("tree", "API 模式解析文档树..."))
        val result = mutableListOf<DocNode>()
        val visited = HashSet<String>()

        suspend fun walk(parentToken: String, path: List<String>) {
            var page: String? = null
            var guard = 0
            while (guard < 100) {
                guard++
                val url = "$base/open-apis/wiki/v2/spaces/$spaceId/nodes" +
                        "?page_size=50" +
                        (if (parentToken.isEmpty()) "" else "&parent_node_token=$parentToken") +
                        (if (page.isNullOrEmpty()) "" else "&page_token=$page")
                val j = get(url) ?: break
                if (j.optInt("code", -1) != 0) {
                    onProgress(ProgressEvent("tree", "节点读取失败：${j.optInt("code")} ${j.optString("msg")}"))
                    break
                }
                val data = j.optJSONObject("data") ?: break
                val items = data.optJSONArray("items")
                if (items != null) for (ix in 0 until items.length()) {
                    val item = items.optJSONObject(ix) ?: continue
                    val nodeToken = item.optString("node_token", "")
                    if (nodeToken.isEmpty() || !visited.add(nodeToken)) continue
                    val objType = item.optString("obj_type", "")
                    val objToken = item.optString("obj_token", "")
                    val title = item.optString("title", "未命名")
                    val hasChild = item.optBoolean("has_child", false)
                    val kind = if (objType == "folder") "folder" else "doc"
                    if (objToken.isNotEmpty()) {
                        result.add(DocNode(
                            wikiToken = nodeToken,
                            objToken = objToken,
                            title = title,
                            path = path,
                            kind = kind,
                            url = "https://feishu.cn/wiki/$nodeToken",
                            hasChild = hasChild
                        ))
                    }
                    if (hasChild) walk(nodeToken, path + listOf(title))
                }
                if (!data.optBoolean("has_more", false)) break
                page = data.optString("page_token", "")
                if (page.isNullOrEmpty()) break
            }
        }

        walk("", emptyList())
        onProgress(ProgressEvent("tree", "API 模式解析完成：${result.size} 个节点"))
        result
    }

    /**
     * 读取单篇文档正文，转成浏览器风格 JSON 交给 BlockRenderer。
     */
    suspend fun getDocument(node: DocNode): Document? = withContext(Dispatchers.IO) {
        val token = node.objToken.ifEmpty { node.wikiToken }
        val all = LinkedHashMap<String, JSONObject>()
        var page: String? = null
        var guard = 0
        while (guard < 200) {
            guard++
            var url = "$base/open-apis/docx/v1/documents/$token/blocks?page_size=500&document_revision_id=-1"
            if (!page.isNullOrEmpty()) url += "&page_token=$page"
            val j = get(url) ?: break
            if (j.optInt("code", -1) != 0) {
                onProgress(ProgressEvent("export", "正文读取失败：${node.title} -> ${j.optString("msg")}", doc = node.title))
                break
            }
            val data = j.optJSONObject("data") ?: break
            val items = data.optJSONArray("items")
            if (items != null) for (ix in 0 until items.length()) {
                val b = items.optJSONObject(ix) ?: continue
                all[b.optString("block_id")] = b
            }
            if (!data.optBoolean("has_more", false)) break
            page = data.optString("page_token", "")
            if (page.isNullOrEmpty()) break
        }
        if (all.isEmpty()) return@withContext null

        // 官方 block → 浏览器风格 {block_id: {data: {...}}}
        val blockMap = JSONObject()
        for ((bid, b) in all) {
            blockMap.put(bid, JSONObject().put("data", toBrowserBlock(b, all)))
        }
        val seq = JSONArray()
        for (bid in all.keys) seq.put(bid as String)
        Document(
            title = node.title,
            blocksJson = blockMap.toString(),
            sequenceJson = seq.toString()
        )
    }

    /**
     * 官方 API block → 浏览器风格 {data:{...}}，让现有 BlockRenderer 直接解析。
     * BlockRenderer.getText() 已兼容官方 elements 结构，renderImage 读 data.image.token。
     * 因此这里保留官方子对象（text/headingN/code/image/...），只补 data.type 等导航字段。
     */
    private fun toBrowserBlock(b: JSONObject, all: Map<String, JSONObject>): JSONObject {
        val d = JSONObject()
        val bt = b.optInt("block_type", 0)
        val type = when (bt) {
            1 -> "page"
            2 -> "text"
            in 3..11 -> "heading${bt - 2}"
            12 -> "bullet"
            13 -> "numbered"
            14 -> "code"
            15 -> "quote"
            17 -> "todo"
            19 -> "callout"
            22 -> "divider"
            27 -> "image"
            31 -> "table"
            32 -> "table_cell"
            34 -> "quote_container"
            else -> "text"
        }
        d.put("type", type)
        d.put("parent_id", b.optString("parent_id", ""))

        // children
        val ch = JSONArray()
        val children = b.optJSONArray("children")
        if (children != null) for (ix in 0 until children.length()) {
            val cid = children.optString(ix)
            if (cid.isNotEmpty()) ch.put(cid)
        }
        d.put("children", ch)

        // 富文本 holder：BlockRenderer.getText 读 data.text（含 elements 或 initialAttributedTexts）
        val holderKey = when (bt) {
            2 -> "text"
            in 3..11 -> "heading${bt - 2}"
            12 -> "bullet"
            13 -> "ordered"
            14 -> "code"
            15 -> "quote"
            17 -> "todo"
            else -> null
        }
        if (holderKey != null) {
            b.optJSONObject(holderKey)?.let { d.put("text", it) }
            // 代码块语言
            if (bt == 14) {
                val lang = it0(b, holderKey)?.optJSONObject("style")?.optInt("language", 0)
                d.put("language", langName(lang))
            }
        }
        // todo 完成态
        if (bt == 17) d.put("done", b.optJSONObject("todo")?.optBoolean("done", false) ?: false)

        // 图片：renderImage 读 data.image.token（官方 image.token 即下载用 file token）
        if (bt == 27) {
            val img = b.optJSONObject("image") ?: JSONObject()
            d.put("image", img)
        }

        // 表格：保留 cell_set/rows_id/columns_id（若官方返回了这些），否则降级靠 table_cell 遍历
        if (bt == 31) {
            b.optJSONObject("table")?.let { d.put("table", it) }
            b.optJSONArray("rows_id")?.let { d.put("rows_id", it) }
            b.optJSONArray("columns_id")?.let { d.put("columns_id", it) }
            b.optJSONObject("cell_set")?.let { d.put("cell_set", it) }
        }
        return d
    }

    private fun it0(b: JSONObject, key: String): JSONObject? = b.optJSONObject(key)

    private fun langName(n: Int?): String = when (n) {
        1 -> "javascript"; 2 -> "python"; 3 -> "java"; 4 -> "cpp"; 5 -> "go"
        6 -> "rust"; 7 -> "typescript"; 8 -> "bash"; 9 -> "sql"; 10 -> "json"
        11 -> "html"; 12 -> "css"; 13 -> "markdown"; else -> ""
    }

    /** 供图片下载器用：从 token 下载图片字节 */
    fun downloadImageBytes(fileToken: String): ByteArray? {
        val url = "$base/open-apis/drive/v1/medias/$fileToken/download"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null
            return bytes
        }
    }
}
