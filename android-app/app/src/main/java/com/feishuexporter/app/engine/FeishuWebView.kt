package com.feishuexporter.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.feishuexporter.app.model.DocNode
import com.feishuexporter.app.model.Document
import com.feishuexporter.app.model.ProgressEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * 飞书抓取引擎（独立 APP 核心）。
 * 用 WebView 替代桌面版的 Playwright + Chrome。
 *
 * 职责：
 *  1. 登录态：WebView 内登录飞书，CookieManager 持久化
 *  2. 文档树：evaluateJavascript 调 tree/get_info（同源带 cookie），BFS 遍历
 *  3. 正文：逐篇 loadUrl，等 window.DATA 就绪，evaluateJavascript 取 block 数据
 */
class FeishuWebView(
    private val context: Context,
    private val onProgress: (ProgressEvent) -> Unit = {}
) {

    // 在 UI 线程创建 WebView（隐藏，仅作引擎用）
    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageReady = false

    // JS 回传的桥接结果（每次调用前清空）
    private val bridgeResult = AtomicReference<String>("")

    init {
        // WebView 必须在主线程创建
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            webView = createWebView()
        }
        // 等待创建完成
        var waited = 0
        while (webView == null && waited < 3000) {
            Thread.sleep(50); waited += 50
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context.applicationContext)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.userAgentString = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.loadsImagesAutomatically = false  // 引擎 WebView 不加载图片，加速
        s.blockNetworkImage = true

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(wv, true)
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
            }
        }
        wv.webChromeClient = WebChromeClient()
        wv.addJavascriptInterface(JsBridge(), "FeishuBridge")
        return wv
    }

    /** JS → Kotlin 回传桥 */
    inner class JsBridge {
        @JavascriptInterface
        fun onResult(json: String) {
            bridgeResult.set(json)
        }
    }

    /** 在页面上下文执行 JS 并取回字符串结果（带超时） */
    @SuppressLint("JavascriptInterface")
    private suspend fun evalJs(js: String, timeoutMs: Long = 30000): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        bridgeResult.set("")
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                wv.evaluateJavascript(js) { value ->
                    // evaluateJavascript 直接返回值；若为 null 用桥接结果兜底
                    val result = if (value == null || value == "null") bridgeResult.get() else value
                    if (cont.isActive) cont.resume(result)
                }
            }
        }
    }

    /** 导航到 URL 并等待页面就绪 */
    private suspend fun navigate(url: String, timeoutMs: Long = 30000): Boolean = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext false
        pageReady = false
        wv.loadUrl(url)
        // 轮询等待 onPageFinished
        var waited = 0L
        while (!pageReady && waited < timeoutMs) {
            kotlinx.coroutines.delay(100); waited += 100
        }
        pageReady
    }

    /** 检查登录态：加载飞书首页看是否跳登录页 */
    suspend fun checkLogin(url: String): Boolean {
        val ok = navigate(url)
        if (!ok) return false
        val href = evalJs("location.href") ?: return false
        return !href.contains("login", ignoreCase = true) && !href.contains("passport", ignoreCase = true)
    }

    /** 打开登录页（用户在可见 WebView 中登录）——由 UI 层用一个可见 WebView 调用 */
    fun getWebViewForLogin(): WebView? = webView

    /**
     * 解析 URL → (origin, spaceId, 起始 wiki_token)
     */
    private suspend fun resolveSpace(rawUrl: String): Triple<String, String, String> {
        val origin = try {
            val u = java.net.URL(rawUrl)
            "${u.protocol}://${u.host}"
        } catch (e: Exception) { "https://bcnjxil3ai78.feishu.cn" }

        // space_id 从 /wiki/space/(\d+) 提取
        val spaceId = Regex("/wiki/space/(\\d+)").find(rawUrl)?.groupValues?.get(1) ?: ""

        navigate(rawUrl)
        val finalUrl = evalJs("location.href") ?: rawUrl
        val token = Regex("/wiki/([A-Za-z0-9]{10,})").find(finalUrl)?.groupValues?.get(1) ?: ""
        return Triple(origin, spaceId, token)
    }

    /**
     * 获取文档树：BFS 遍历 get_info，合并 nodes / child_map。
     * JS 内用 fetch（同源自动带 WebView cookie）。
     */
    suspend fun listNodes(rawUrl: String): List<DocNode> {
        val (origin, spaceId, startToken) = resolveSpace(rawUrl)
        onProgress(ProgressEvent("tree", "解析文档树..."))

        val allNodes = LinkedHashMap<String, JSONObject>()
        val childMap = HashMap<String, MutableList<String>>()

        // BFS 队列
        val queue = ArrayDeque<String>()
        if (startToken.isNotEmpty()) queue.add(startToken) else queue.add("")
        val seen = HashSet<String>()

        var guard = 0
        while (queue.isNotEmpty() && guard < 5000) {
            guard++
            val tk = queue.removeFirst()
            if (seen.contains(tk)) continue
            seen.add(tk)

            val js = buildFetchTreeJs(origin, spaceId, tk)
            val jsonStr = evalJs(js) ?: continue
            val parsed = parseTreeJson(jsonStr) ?: continue
            val nodes = parsed.first
            val cm = parsed.second
            allNodes.putAll(nodes)
            cm.forEach { (k, v) -> childMap.getOrPut(k) { mutableListOf() }.addAll(v) }

            // 找 has_child 但未展开的节点入队
            for ((_, n) in nodes) {
                val wtk = optStr(n, "wiki_token")
                if (optBool(n, "has_child") && wtk.isNotEmpty() && !seen.contains(wtk)) {
                    queue.add(wtk)
                }
            }
        }

        onProgress(ProgressEvent("tree", "文档树解析完成：${allNodes.size} 个节点"))
        return nodesToDocs(allNodes, childMap)
    }

    /** 构造 fetch tree 的 JS（返回 JSON.stringify） */
    private fun buildFetchTreeJs(origin: String, spaceId: String, tk: String): String {
        val params = ("space_id=$spaceId&with_space=false&with_perm=false&expand_shortcut=true"
                + "&need_shared=true&exclude_fields=5&with_deleted=true&wiki_token=$tk")
        val url = "$origin/space/api/wiki/v2/tree/get_info/?$params"
        // 用 Promise + FeishuBridge.onResult 回传
        return """
            (async function(){
              try {
                const r = await fetch(${jsStr(url)}, {credentials:'include'});
                const j = await r.json();
                const tree = (j.data && j.data.tree) || {};
                FeishuBridge.onResult(JSON.stringify({nodes: tree.nodes||{}, child_map: tree.child_map||{}}));
              } catch(e) { FeishuBridge.onResult(JSON.stringify({nodes:{},child_map:{}})); }
              return 'ok';
            })()
        """.trimIndent()
    }

    /** 解析 tree JSON（可能是 evaluateJavascript 直返或桥接结果） */
    private fun parseTreeJson(raw: String): Pair<Map<String, JSONObject>, Map<String, List<String>>>? {
        try {
            // 若 raw 是 '"{...}"'（JS 字符串），先解包
            var s = raw.trim().trim('"')
            s = s.replace("\\\"", "\"").replace("\\\\", "\\")
            val obj = JSONObject(s)
            val nodesObj = obj.optJSONObject("nodes") ?: return null
            val nodes = HashMap<String, JSONObject>()
            nodesObj.keys().forEach { nodes[it] = nodesObj.getJSONObject(it) }
            val cmObj = obj.optJSONObject("child_map")
            val cm = HashMap<String, List<String>>()
            if (cmObj != null) {
                cmObj.keys().forEach { k ->
                    val arr = cmObj.getJSONArray(k)
                    cm[k] = (0 until arr.length()).map { arr.getString(it) }
                }
            }
            return nodes to cm
        } catch (e: Exception) {
            return null
        }
    }

    /** nodes + child_map → 有序 DocNode 列表（按层级 DFS） */
    private fun nodesToDocs(
        nodes: Map<String, JSONObject>,
        childMap: Map<String, List<String>>
    ): List<DocNode> {
        val result = mutableListOf<DocNode>()
        val visited = HashSet<String>()

        fun walk(tk: String, path: List<String>) {
            if (tk.isEmpty() || !nodes.containsKey(tk) || visited.contains(tk)) return
            visited.add(tk)
            val n = nodes[tk]!!
            val title = optStr(n, "title").ifEmpty { "未命名_${tk.take(6)}" }
            val objType = optInt(n, "obj_type")
            val objToken = optStr(n, "obj_token")
            val kind = when (objType) {
                22 -> "doc"
                23 -> "folder"
                else -> if (objToken.isNotEmpty()) "sheet" else "folder"
            }
            if (objToken.isNotEmpty() && objType != 23) {
                val relUrl = optStr(n, "url").ifEmpty { "https://bcnjxil3ai78.feishu.cn/wiki/$tk" }
                result.add(DocNode(tk, objToken, title, path, kind, relUrl, optBool(n, "has_child")))
            }
            val nextPath = path + title
            childMap[tk]?.forEach { walk(it, nextPath) }
        }

        // 找出根节点（没有 parent 或 parent 不在 nodes 中）
        val roots = nodes.filter { (_, n) ->
            val parent = optStr(n, "parent_wiki_token")
            parent.isEmpty() || !nodes.containsKey(parent)
        }.keys
        if (roots.isNotEmpty()) roots.forEach { walk(it, emptyList()) }
        else nodes.keys.forEach { walk(it, emptyList()) }
        // 补充未访问的节点
        nodes.keys.forEach { if (!visited.contains(it)) walk(it, emptyList()) }
        return result
    }

    /**
     * 读取单篇文档正文：导航 → 等 window.DATA 就绪 → 取 block_map/block_sequence
     */
    suspend fun getDocument(node: DocNode): Document? {
        val url = if (node.url.startsWith("http")) node.url
                  else "https://bcnjxil3ai78.feishu.cn/wiki/${node.wikiToken}"
        navigate(url)

        // 轮询等待 window.DATA.clientVars.data.block_map 就绪
        var waited = 0L
        var ready = false
        while (waited < 20000) {
            val v = evalJs("!!(window.DATA && window.DATA.clientVars && window.DATA.clientVars.data && window.DATA.clientVars.data.block_map)", 5000)
            if (v == "true" || v?.contains("true") == true) { ready = true; break }
            kotlinx.coroutines.delay(300); waited += 300
        }
        if (!ready) return null

        // 取 block_map + block_sequence（可能超大，用桥接回传）
        val js = """
            (function(){
              try {
                const d = window.DATA.clientVars.data;
                FeishuBridge.onResult(JSON.stringify({
                  title: (window.DATA.meta && window.DATA.meta.title) || '',
                  block_map: d.block_map,
                  block_sequence: d.block_sequence
                }));
                return 'ok';
              } catch(e){ return 'err:' + e.message; }
            })()
        """.trimIndent()
        evalJs(js, 60000)
        val jsonStr = bridgeResult.get()
        if (jsonStr.isEmpty()) return null
        return try {
            val o = JSONObject(jsonStr)
            Document(
                title = o.optString("title"),
                blocksJson = o.getJSONObject("block_map").toString(),
                sequenceJson = o.getJSONArray("block_sequence").toString()
            )
        } catch (e: Exception) { null }
    }

    /** 取当前域名的 cookie 字符串（供 OkHttp 下载图片） */
    fun cookiesFor(url: String): String {
        return try { CookieManager.getInstance().getCookie(url) ?: "" } catch (e: Exception) { "" }
    }

    fun close() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            webView?.destroy(); webView = null
        }
    }

    // ---- 工具函数 ----
    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun optStr(o: JSONObject, k: String): String = try { o.optString(k, "") } catch (e: Exception) { "" }
    private fun optInt(o: JSONObject, k: String): Int = try { o.optInt(k, 0) } catch (e: Exception) { 0 }
    private fun optBool(o: JSONObject, k: String): Boolean = try { o.optBoolean(k, false) } catch (e: Exception) { false }
}
