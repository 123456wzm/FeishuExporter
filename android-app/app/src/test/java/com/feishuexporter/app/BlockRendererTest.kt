package com.feishuexporter.app

import com.feishuexporter.app.parser.BlockRenderer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 验证 Kotlin BlockRenderer 移植正确性：
 * 用桌面版真实 block 数据解析，与 Python 版期望输出对比。
 */
class BlockRendererTest {

    @Test
    fun testWebDevelopmentDoc() {
        val fixtureText = File("src/test/resources/fixture_webdev.json").readText()
        val expected = File("src/test/resources/expected_webdev.md").readText()

        val root = org.json.JSONObject(fixtureText)
        val blocks = root.getJSONObject("blocks").toString()
        val sequence = root.getJSONArray("sequence").toString()

        val renderer = BlockRenderer(blocks, imageDir = "data")
        val actual = renderer.render(sequence)

        // 归一化：统一换行与尾部空白
        fun norm(s: String) = s.lines().map { it.trimEnd() }.joinToString("\n").trim()
        val a = norm(actual)
        val e = norm(expected)

        if (a != e) {
            println("=== EXPECTED ===")
            println(e)
            println("=== ACTUAL ===")
            println(a)
        }
        assertEquals("Kotlin 解析输出应与 Python 版一致", e, a)
        assertTrue("应收集到图片", renderer.images.isNotEmpty())
    }
}
