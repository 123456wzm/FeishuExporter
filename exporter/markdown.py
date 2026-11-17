#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""exporter/markdown.py —— Markdown 导出器。

职责：
  1. 用 BlockRenderer 把 document.blocks 渲染为 markdown 正文 + 图片列表；
  2. 调用 image_resolver（由 image_store 提供）把图片占位替换为最终引用；
  3. 写入 {文档名}.md，顶部加一级标题。

图片引用策略由 image_store 决定，本模块只负责替换占位符 ![](data/xxx)。
"""
import os
import re
from parser.docx_blocks import BlockRenderer


def _clean_filename(name):
    name = (name or "").strip()
    name = re.sub(r'[\\/:*?"<>|]', '_', name)
    name = name.replace('\n', ' ').replace('\r', ' ')
    name = re.sub(r'\s+', ' ', name).strip().strip('.')
    return name or "未命名"


def export(document, doc_dir: str, cfg: dict, image_resolver, on_message=None) -> dict:
    """导出一篇文档为 Markdown。
    :return: {'md_path':..., 'images':[...], 'image_dir':...}
    """
    on_message = on_message or (lambda m: None)
    _clean_filename(cfg.get("_title", ""))  # noop，保留接口
    title = _clean_filename(document.title)
    os.makedirs(doc_dir, exist_ok=True)

    renderer = BlockRenderer(document.blocks, image_dir="data")
    body = renderer.render(document.sequence)
    images = renderer.images

    # 解析图片最终引用：{fname: 最终URL或None}
    ref_map = image_resolver(images, doc_dir) if image_resolver else {}

    # 替换 ![](data/fname) -> ![](最终引用)；None 则删除该行
    def repl(m):
        alt, fname = m.group(1), m.group(2)
        key = os.path.basename(fname)
        final = ref_map.get(key, fname)
        if final is None:
            return ""  # skip 策略
        return f"![{alt}]({final})"

    body = re.sub(r'!\[([^\]]*)\]\(data/([^)]+)\)', repl, body)
    # 清理 skip 后产生的空行堆积
    body = re.sub(r'\n{3,}', '\n\n', body)

    md_path = os.path.join(doc_dir, f"{title}.md")
    with open(md_path, 'w', encoding='utf-8') as f:
        f.write(f"# {document.title}\n\n")
        f.write(body)
        f.write("\n")

    return {
        'md_path': md_path,
        'images': images,
        'image_dir': os.path.join(doc_dir, 'data'),
    }
