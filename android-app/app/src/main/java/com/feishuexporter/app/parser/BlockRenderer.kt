package com.feishuexporter.app.parser

import com.feishuexporter.app.model.ImageRef
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 飞书 block 解析器（从 Python convert.py 移植，逻辑一一对应）。
 * 已验证的桌面版核心：富文本 attribs、表格 cell_set、代码块、callout、列表、图片。
 */
class BlockRenderer(
    private val blocksJson: String,
    private val imageDir: String = "data"
) {
    private val bm = HashMap<String, JSONObject>()
    val images = mutableListOf<ImageRef>()

    init {
        val root = JSONObject(blocksJson)
        root.keys().forEach { bid ->
            val b = root.optJSONObject(bid) ?: return@forEach
            bm[bid] = b
        }
    }

    private fun block(bid: String): JSONObject = bm[bid] ?: JSONObject()
    private fun data(bid: String): JSONObject = block(bid).optJSONObject("data") ?: JSONObject()
    private fun children(d: JSONObject): List<String> {
        val arr = d.optJSONArray("children") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }
    private fun typeOf(bid: String): String = data(bid).optString("type", "")

    /** 主入口：返回 markdown 正文 */
    fun render(sequenceJson: String?): String {
        // 找 page 根节点
        var root: String? = null
        for ((bid, b) in bm) {
            if ((b.optJSONObject("data") ?: JSONObject()).optString("type", "") == "page") {
                root = bid; break
            }
        }
        val top = if (root != null) {
            val ch = children(data(root))
            if (ch.isNotEmpty()) ch else sequenceJson?.let { jsonArray(it) } ?: emptyList()
        } else {
            sequenceJson?.let { jsonArray(it) } ?: emptyList()
        }
        val lines = mutableListOf<String>()
        for (bid in top) {
            val d = data(bid)
            val t = d.optString("type", "")
            if (t == "page") continue
            // 跳过作为表格单元格的子 block（避免重复渲染）
            val parentType = typeOf(d.optString("parent_id", ""))
            if (t == "table_cell" && parentType == "table") continue
            val r = renderBlock(bid, d, 0, 1)
            if (r.isNotEmpty()) lines.add(r)
        }
        return lines.joinToString("\n\n")
    }

    private fun renderChildren(bids: List<String>, depth: Int): List<String> {
        val lines = mutableListOf<String>()
        var idx = 0
        for (bid in bids) {
            val d = data(bid)
            if (d.length() == 0) continue
            val parentType = typeOf(d.optString("parent_id", ""))
            if (d.optString("type", "") == "table_cell" && parentType == "table") continue
            val line = renderBlock(bid, d, depth, idx + 1)
            if (line.isNotEmpty()) lines.add(line)
            idx++
        }
        return lines
    }

    private fun renderBlock(bid: String, d: JSONObject, depth: Int, num: Int): String {
        val t = d.optString("type", "")
        val text = getText(d)
        val ch = children(d)
        val indent = "  ".repeat(depth)

        return when {
            t == "page" -> ""
            t.startsWith("heading") && t.length > 7 -> {
                val lvl = (t.last().digitToIntOrNull() ?: 1) + 1
                "#".repeat(lvl.coerceIn(2, 6)) + " $text"
            }
            t == "text" -> text
            t == "bullet" -> {
                val sub = renderChildren(ch, depth + 1)
                "${indent}- $text" + (if (sub.isNotEmpty()) "\n" + sub.joinToString("\n") else "")
            }
            t == "numbered" -> {
                val sub = renderChildren(ch, depth + 1)
                "$indent$num. $text" + (if (sub.isNotEmpty()) "\n" + sub.joinToString("\n") else "")
            }
            t == "todo" -> {
                val checked = d.optBoolean("done", false) || d.optBoolean("checked", false)
                "$indent- [${if (checked) "x" else " "}] $text"
            }
            t == "image" -> renderImage(d)
            t == "code" -> {
                val lang = d.optString("language", "")
                val sub = renderChildren(ch, depth)
                val codeText = if (sub.isNotEmpty()) sub.joinToString("\n") else text
                "```$lang\n$codeText\n```"
            }
            t == "quote" -> {
                val sub = renderChildren(ch, depth)
                val body = if (text.isNotEmpty()) text else sub.joinToString("\n")
                body.split("\n").joinToString("\n") { "> $it" }
            }
            t == "callout" -> {
                val sub = renderChildren(ch, depth)
                val body = if (sub.isNotEmpty()) sub.joinToString("\n") else text
                "> 💡 $body"
            }
            t == "divider" -> "---"
            t == "table" -> renderTable(d, bid)
            t == "grid" || t == "column" -> renderChildren(ch, depth).joinToString("\n")
            else -> {
                val sub = renderChildren(ch, depth)
                val parts = mutableListOf<String>()
                if (text.isNotEmpty()) parts.add(text)
                if (sub.isNotEmpty()) parts.add(sub.joinToString("\n"))
                parts.joinToString("\n")
            }
        }
    }

    private fun renderImage(d: JSONObject): String {
        val img = d.optJSONObject("image") ?: return ""
        val token = img.optString("token", "")
        if (token.isEmpty()) return ""
        val mime = img.optString("mimeType", "image/png")
        val name = img.optString("name", "")
        val seq = images.size
        val fname = "img_${String.format("%03d", seq + 1)}${extOf(mime, name)}"
        val captionObj = img.optJSONObject("caption")
        val caption = ((captionObj?.optJSONObject("text")
            ?.optJSONObject("initialAttributedTexts")
            ?.optJSONObject("text"))?.optString("0", "")) ?: ""
        images.add(ImageRef(token, fname, mime))
        val md = "![]($imageDir/$fname)"
        return md + (if (caption.isNotEmpty()) "\n> $caption" else "")
    }

    private fun renderTable(d: JSONObject, bid: String): String {
        val rowsId = jsonArrayOpt(d, "rows_id")
        val colsId = jsonArrayOpt(d, "columns_id")
        val cellSet = d.optJSONObject("cell_set")

        if (rowsId.isEmpty() || colsId.isEmpty()) {
            // 降级：遍历所有 parent 为本 table 的 table_cell
            val rows = mutableListOf<String>()
            for ((_, b) in bm) {
                val cd = b.optJSONObject("data") ?: continue
                if (cd.optString("type", "") == "table_cell" && cd.optString("parent_id", "") == bid) {
                    rows.add(cellText(cd))
                }
            }
            return rows.joinToString("\n")
        }

        val tableRows = mutableListOf<MutableList<String>>()
        for (rid in rowsId) {
            val cells = mutableListOf<String>()
            for (cid in colsId) {
                val info = cellSet?.optJSONObject("${rid}${cid}") ?: JSONObject()
                val cellBid = info.optString("block_id", "")
                val merge = info.optJSONObject("merge_info")
                val colspan = (merge?.optString("col_span", "1") ?: "1").toIntOrNull() ?: 1
                if (cellBid.isEmpty()) { cells.add(""); continue }
                val cd = data(cellBid)
                cells.add(cellText(cd))
                repeat(colspan - 1) { cells.add("") }
            }
            tableRows.add(cells)
        }
        if (tableRows.isEmpty()) return ""
        val ncol = tableRows[0].size
        val out = mutableListOf<String>()
        for ((ri, row) in tableRows.withIndex()) {
            while (row.size < ncol) row.add("")
            out.add("| " + row.take(ncol).joinToString(" | ") { it.replace("\n", " ") } + " |")
            if (ri == 0) out.add("|" + "---|".repeat(ncol))
        }
        return out.joinToString("\n")
    }

    private fun cellText(cd: JSONObject): String {
        val parts = mutableListOf<String>()
        val ct = getText(cd)
        if (ct.isNotEmpty()) parts.add(ct)
        for (cb in children(cd)) {
            val cbdata = data(cb)
            if (cbdata.optString("type", "") == "image") {
                val r = renderBlock(cb, cbdata, 0, 1)
                if (r.isNotEmpty()) parts.add(r)
            } else {
                val t = getText(cbdata)
                if (t.isNotEmpty()) parts.add(t)
            }
        }
        return parts.joinToString(" ")
    }

    /** 提取 block 富文本 markdown（兼容浏览器 DATA 与官方 API 结构） */
    private fun getText(d: JSONObject): String {
        val t = d.optJSONObject("text") ?: return ""
        val iat = t.optJSONObject("initialAttributedTexts")
        if (iat != null) {
            val texts = iat.optJSONObject("text")
            val attribs = iat.optJSONObject("attribs")
            val raw = texts?.optString("0", "") ?: ""
            val attrib = attribs?.optString("0", "") ?: ""
            if (raw.isNotEmpty() || attrib.isNotEmpty()) {
                return attribToMarkdown(raw, attrib, t.optJSONObject("apool"))
            }
        }
        // 官方 API：elements[].text_run
        val elems = t.optJSONArray("elements") ?: return ""
        val parts = mutableListOf<String>()
        for (i in 0 until elems.length()) {
            val tr = elems.optJSONObject(i)?.optJSONObject("text_run") ?: continue
            var content = tr.optString("content", "")
            val style = tr.optJSONObject("text_element_style")
            if (style != null) {
                if (style.optBoolean("bold", false)) content = "**$content**"
                if (style.optBoolean("italic", false)) content = "*$content*"
                if (style.optBoolean("inline_code", false)) content = "`$content`"
                val url = style.optJSONObject("link")?.optString("url", "") ?: ""
                if (url.isNotEmpty()) content = "[$content]($url)"
            }
            parts.add(content)
        }
        return parts.joinToString("")
    }

    /** 解析 attribs 编码：*N 切换属性，+H(hex) 应用长度 */
    private fun attribToMarkdown(text: String, attrib: String, apool: JSONObject?): String {
        if (text.isEmpty()) return ""
        if (attrib.isEmpty()) return text
        val numToAttrib = apool?.optJSONObject("numToAttrib")
        val segments = mutableListOf<Pair<Int, List<JSONArray>>>()
        var i = 0
        val cur = mutableListOf<JSONArray>()
        val n = attrib.length
        while (i < n) {
            val c = attrib[i]
            when (c) {
                '*' -> {
                    var j = i + 1
                    val sb = StringBuilder()
                    while (j < n && attrib[j].isLetterOrDigit()) { sb.append(attrib[j]); j++ }
                    if (sb.isNotEmpty()) {
                        val attrs = numToAttrib?.optJSONArray(sb.toString())
                        cur.clear()
                        if (attrs != null) for (k in 0 until attrs.length()) {
                            attrs.optJSONArray(k)?.let { cur.add(it) }
                        }
                    }
                    i = j
                }
                '+' -> {
                    var j = i + 1
                    val hx = StringBuilder()
                    while (j < n && (attrib[j] in '0'..'9' || attrib[j] in 'a'..'f' || attrib[j] in 'A'..'F')) {
                        hx.append(attrib[j]); j++
                    }
                    if (hx.isNotEmpty()) {
                        segments.add((hx.toString().toInt(16) to cur.toList()))
                    }
                    i = j
                }
                else -> i++
            }
        }
        if (segments.isEmpty()) return text
        val out = StringBuilder()
        var pos = 0
        for ((cnt, attrs) in segments) {
            val end = (pos + cnt).coerceAtMost(text.length)
            val chunk = text.substring(pos, end)
            pos = end
            if (chunk.isEmpty()) continue
            var bold = false; var italic = false; var inlineCode = false; var link: String? = null
            for (a in attrs) {
                if (a.length() < 2) continue
                val k = a.optString(0, "")
                val v = a.opt(1)
                when (k) {
                    "bold" -> bold = v.toString().equals("true", ignoreCase = true)
                    "italic" -> italic = v.toString().equals("true", ignoreCase = true)
                    "inline_code" -> inlineCode = v.toString().equals("true", ignoreCase = true)
                    "link" -> if (v != null) link = try { URLDecoder.decode(v.toString(), "UTF-8") } catch (e: Exception) { v.toString() }
                }
            }
            var seg = chunk
            if (inlineCode) seg = "`$seg`"
            else {
                if (bold) seg = "**$seg**"
                if (italic) seg = "*$seg*"
                if (link != null) seg = "[$seg]($link)"
            }
            out.append(seg)
        }
        if (pos < text.length) out.append(text.substring(pos))
        return out.toString()
    }

    // ---- 工具 ----
    private fun jsonArray(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) { emptyList() }
    }
    private fun jsonArrayOpt(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }
    private fun extOf(mime: String, name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot >= 0 && dot < name.length - 1) return name.substring(dot)
        return mapOf(
            "image/png" to ".png", "image/jpeg" to ".jpg", "image/jpg" to ".jpg",
            "image/gif" to ".gif", "image/webp" to ".webp", "image/svg+xml" to ".svg"
        )[mime] ?: ".png"
    }
}
