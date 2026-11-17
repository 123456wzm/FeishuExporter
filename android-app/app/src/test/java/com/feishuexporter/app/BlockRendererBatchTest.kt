package com.feishuexporter.app

import com.feishuexporter.app.parser.BlockRenderer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 全量对照：52 篇文档应解析出 601 张图片（与桌面版一致） */
class BlockRendererBatchTest {

    @Test
    fun testAllDocs() {
        val fixtureFile = File("src/test/resources/fixture_all.json")
        if (!fixtureFile.exists()) {
            println("跳过全量测试：fixture_all.json 不存在（可从桌面版 all_docs_data.json 生成）")
            return
        }
        val text = fixtureFile.readText()
        val arr = org.json.JSONArray(text)
        var docCount = 0
        var imageCount = 0
        var errors = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            try {
                val renderer = BlockRenderer(o.getJSONObject("blocks").toString(), imageDir = "data")
                renderer.render(o.getJSONArray("sequence").toString())
                imageCount += renderer.images.size
                docCount++
            } catch (e: Exception) {
                errors++
                println("解析失败: ${o.optString("title")} - ${e.message}")
            }
        }
        println("解析文档: $docCount, 图片: $imageCount, 错误: $errors")
        assertEquals("文档数应为 52", 52, docCount)
        assertEquals("图片总数应与桌面版一致为 601", 601, imageCount)
        assertEquals("不应有解析错误", 0, errors)
    }
}
