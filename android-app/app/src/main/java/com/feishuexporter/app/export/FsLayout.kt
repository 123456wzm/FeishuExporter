package com.feishuexporter.app.export

import com.feishuexporter.app.model.DocNode
import com.feishuexporter.app.model.ExportConfig
import java.io.File

/** 文件系统布局：树形层级还原 + 文件名清洗 + 重名去重 */
class FsLayout(private val root: File, private val cfg: ExportConfig) {
    private val usedPaths = HashSet<String>()

    fun docDir(node: DocNode): File {
        val parts = mutableListOf<String>()
        if (cfg.layout == "tree") {
            parts.addAll(node.path.map { safeName(it) })
        }
        parts.add(safeName(node.title))
        var dir = root
        for (p in parts) dir = File(dir, p)
        // 重名去重
        var final = dir
        var i = 2
        while (usedPaths.contains(final.absolutePath) && final.exists()) {
            final = File(dir.parentFile, "${dir.name}_$i"); i++
        }
        usedPaths.add(final.absolutePath)
        final.mkdirs()
        return final
    }

    fun writeMarkdown(dir: File, title: String, body: String, withHeading: Boolean): File {
        val content = if (withHeading) "# $title\n\n$body" else body
        val f = File(dir, "${safeName(title)}.md")
        f.writeText(content, Charsets.UTF_8)
        return f
    }

    fun writeReadme(records: List<Triple<String, String, Int>>) {
        if (!cfg.readme || records.isEmpty()) return
        val sb = StringBuilder()
        sb.appendLine("# 飞书知识库导出")
        sb.appendLine()
        sb.appendLine("> 共 ${records.size} 篇文档")
        sb.appendLine()
        for ((title, rel, depth) in records) {
            val enc = rel.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            sb.appendLine("${"  ".repeat(depth)}- [$title]($enc)")
        }
        File(root, "README.md").writeText(sb.toString(), Charsets.UTF_8)
    }

    companion object {
        fun safeName(name: String): String {
            var s = name.trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
            if (s.length > 120) s = s.take(120)
            return s.ifEmpty { "未命名" }
        }
    }
}
