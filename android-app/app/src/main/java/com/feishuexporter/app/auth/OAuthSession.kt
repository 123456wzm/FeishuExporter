package com.feishuexporter.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 飞书 OAuth 认证 + 本地回调捕获（完全独立，不依赖外部服务器）。
 *
 * 流程：
 *  1. startCallbackServer(port) 在本机起一个临时 ServerSocket，监听 /callback
 *  2. 用 WebView 打开 buildAuthorizeUrl() 让用户扫码授权
 *  3. 飞书重定向到 http://localhost:{port}/callback?code=xxx&state=xxx
 *  4. waitForCode() 阻塞等待回调，提取 code
 *  5. exchangeToken(code) 用 code+secret 换 user_access_token
 *
 * 对应飞书文档：
 *  - 授权链接：https://accounts.feishu.cn/open-apis/authen/v1/authorize
 *  - 换 token ：POST https://open.feishu.cn/open-apis/authen/v2/oauth/token
 */
class OAuthSession(
    private val appId: String,
    private val appSecret: String,
    private val port: Int = 3000
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var receivedCode: String? = null

    @Volatile
    private var receivedState: String? = null

    @Volatile
    private var stopped = false

    private val state = "fs-${System.currentTimeMillis()}"
    val redirectUri: String get() = "http://localhost:$port/callback"

    /** 需要的权限（知识库/文档/图片下载）。也可让用户自行在应用后台开通。 */
    private val scopes = listOf(
        "wiki:wiki",                  // 知识库节点
        "docx:document:readonly",     // 文档正文
        "drive:drive:readonly",       // 云空间只读
        "drive:media:readonly",       // 下载云文档中的图片/素材
        "docs:doc:readonly",          // 查看、评论和导出文档
        "offline_access"              // 拿 refresh_token
    ).joinToString(" ")

    /** 构造授权链接（用户需在浏览器/WebView 打开） */
    fun buildAuthorizeUrl(): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "https://accounts.feishu.cn/open-apis/authen/v1/authorize" +
                "?client_id=${enc(appId)}" +
                "&redirect_uri=${enc(redirectUri)}" +
                "&response_type=code" +
                "&scope=${enc(scopes)}" +
                "&state=${enc(state)}"
    }

    /**
     * 在后台线程启动本地回调服务器。阻塞直到收到 /callback 或超时。
     * 返回拿到的 code，失败返回 null。
     */
    suspend fun startAndWait(timeoutMs: Long = 180_000): String? = withContext(Dispatchers.IO) {
        stopped = false
        receivedCode = null
        try {
            server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", port))
                soTimeout = 10_000
            }
        } catch (e: Exception) {
            return@withContext null // 端口占用
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !stopped && receivedCode == null) {
            try {
                val sock = server?.accept() ?: break
                sock.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: ""
                    // 读完请求头
                    var line = reader.readLine()
                    while (line != null && line.isNotEmpty()) line = reader.readLine()
                    val path = requestLine.split(" ").getOrNull(1) ?: ""
                    if (path.startsWith("/callback")) {
                        val query = path.substringAfter('?', "")
                        val params = query.split('&').mapNotNull {
                            val kv = it.split('=', limit = 2)
                            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
                        }.toMap()
                        receivedCode = params["code"]
                        receivedState = params["state"]
                        // 给浏览器一个友好响应
                        val html = "<html><body style='font-family:sans-serif;text-align:center;margin-top:80px'>" +
                                "<h2>授权成功</h2><p>可以返回 APP 继续导出了。</p></body></html>"
                        val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
                                "Content-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html"
                        s.getOutputStream().write(resp.toByteArray())
                        s.getOutputStream().flush()
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 继续等
            } catch (e: Exception) {
                if (stopped) break
            }
        }
        receivedCode
    }

    /** 用 code 换取 user_access_token（v2 接口） */
    suspend fun exchangeToken(code: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("client_id", appId)
                put("client_secret", appSecret)
                put("code", code)
                put("redirect_uri", redirectUri)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("https://open.feishu.cn/open-apis/authen/v2/oauth/token")
                .post(body).build()
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                val j = JSONObject(txt)
                if (j.optInt("code", -1) == 0 && j.has("access_token")) {
                    Result.success(j.getString("access_token"))
                } else {
                    Result.failure(Exception("换取 token 失败：${j.optInt("code")} ${j.optString("error_description", j.optString("msg", txt.take(120)))}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 停止回调服务器 */
    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
        server = null
    }
}
