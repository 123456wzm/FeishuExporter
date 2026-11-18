package com.feishuexporter.app

import com.feishuexporter.app.parser.BlockRenderer
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/**
 * 验证"官方 API block 转换后的形态"能被 BlockRenderer 正确渲染。
 *
 * ApiSource.toBrowserBlock 会把官方数字 block_type + elements 转成：
 *  - data.type 字符串（heading1/bullet/text/image ...）
 *  - data.text = 官方 holder（含 elements[].text_run.content）
 *  - data.image = 官方 image 对象（含 token）
 *  - data.children / parent_id
 * 本测试直接构造这种转换后的 block_map，验证与桌面版渲染一致。
 */
class ApiBlockRenderTest {

    private fun textBlock(text: String) = JSONObject().put("elements",
        org.json.JSONArray().put(JSONObject().put("text_run", JSONObject().put("content", text))))

    @Test
    fun testOfficialShapeRendering() {
        val blocks = JSONObject()
        // page
        blocks.put("root", JSONObject().put("data", JSONObject()
            .put("type", "page")
            .put("children", org.json.JSONArray().put("h1").put("t1").put("b1").put("img1"))))
        // heading1
        blocks.put("h1", JSONObject().put("data", JSONObject()
            .put("type", "heading1").put("parent_id", "root")
            .put("text", textBlock("章节标题"))))
        // text with bold element
        val richText = JSONObject().put("elements", org.json.JSONArray()
            .put(JSONObject().put("text_run", JSONObject()
                .put("content", "加粗")
                .put("text_element_style", JSONObject().put("bold", true))))
            .put(JSONObject().put("text_run", JSONObject().put("content", "普通"))))
        blocks.put("t1", JSONObject().put("data", JSONObject()
            .put("type", "text").put("parent_id", "root").put("text", richText)))
        // bullet
        blocks.put("b1", JSONObject().put("data", JSONObject()
            .put("type", "bullet").put("parent_id", "root")
            .put("text", textBlock("列表项"))))
        // image
        blocks.put("img1", JSONObject().put("data", JSONObject()
            .put("type", "image").put("parent_id", "root")
            .put("image", JSONObject().put("token", "boxcnABC123").put("mimeType", "image/png"))))

        val seq = org.json.JSONArray().put("root").put("h1").put("t1").put("b1").put("img1")
        val renderer = BlockRenderer(blocks.toString(), imageDir = "data")
        val md = renderer.render(seq.toString())

        assertTrue("应含二级标题(heading1→##)", md.contains("## 章节标题"))
        assertTrue("应含加粗", md.contains("**加粗**"))
        assertTrue("应含列表项", md.contains("- 列表项"))
        assertTrue("应含图片相对路径", md.contains("![](data/img_001.png)"))
        assertEquals("应收集 1 张图", 1, renderer.images.size)
        assertEquals("图片 token 应为官方 file_token", "boxcnABC123", renderer.images[0].token)
    }
}
