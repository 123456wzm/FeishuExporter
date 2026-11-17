#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""exporter/html.py / txt.py —— 其他导出格式（P1）。

HTML：把 markdown 正文用 markdown 库转 HTML，图片引用沿用 markdown 的最终引用。
TXT：去除所有 markdown 语法，仅保留纯文本。
"""
import os
import re
import html as html_mod


def safe_filename(name):
    name = (name or "").strip()
    name = re.sub(r'[\\/:*?"<>|]', '_', name)
    return re.sub(r'\s+', ' ', name).strip('.') or "未命名"


def export_html(document, doc_dir, cfg, markdown_body, on_message=None):
    """markdown_body 已经过图片引用替换。"""
    title = safe_filename(document.title)
    os.makedirs(doc_dir, exist_ok=True)
    try:
        import markdown as md_lib
        content_html = md_lib.markdown(markdown_body, extensions=['tables', 'fenced_code'])
    except Exception:
        content_html = f"<pre>{html_mod.escape(markdown_body)}</pre>"
    page = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>{html_mod.escape(document.title)}</title>
<style>
body{{max-width:900px;margin:40px auto;padding:0 20px;font-family:-apple-system,"Microsoft YaHei",sans-serif;line-height:1.7;color:#222}}
img{{max-width:100%}} pre{{background:#f5f5f5;padding:12px;overflow:auto;border-radius:6px}}
table{{border-collapse:collapse}} td,th{{border:1px solid #ddd;padding:6px 12px}}
blockquote{{border-left:4px solid #ccc;margin:0;padding-left:16px;color:#666}}
</style></head><body>
{content_html}
</body></html>"""
    path = os.path.join(doc_dir, f"{title}.html")
    with open(path, 'w', encoding='utf-8') as f:
        f.write(page)
    return {'md_path': path, 'image_dir': os.path.join(doc_dir, 'data')}


def export_txt(document, doc_dir, cfg, markdown_body, on_message=None):
    title = safe_filename(document.title)
    os.makedirs(doc_dir, exist_ok=True)
    text = markdown_body
    # 图片 ![alt](url) -> 保留 alt
    text = re.sub(r'!\[([^\]]*)\]\([^)]+\)', r'\1', text)
    # 链接 [text](url) -> text
    text = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)
    # 标题 # / 加粗 ** / 行内代码 ` / 引用 >
    text = re.sub(r'^#{1,6}\s+', '', text, flags=re.M)
    text = text.replace('**', '').replace('*', '').replace('`', '')
    text = re.sub(r'^>\s?', '', text, flags=re.M)
    text = re.sub(r'\n{3,}', '\n\n', text)
    path = os.path.join(doc_dir, f"{title}.txt")
    with open(path, 'w', encoding='utf-8') as f:
        f.write(f"{document.title}\n\n{text}")
    return {'md_path': path, 'image_dir': os.path.join(doc_dir, 'data')}
