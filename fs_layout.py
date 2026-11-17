#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fs_layout.py —— 文件系统布局、命名去重、README 索引、导出报告。

负责：
  - 根据 DocNode.path + title 计算每篇文档的落盘目录（树形/扁平两种模式）；
  - 标题非法字符清洗 + 同名去重（追加 (2)、(3)…）；
  - 生成 README.md 层级索引（链接做 URL 编码，兼容括号/空格/中文）；
  - 生成 _export_report.json（篇数、图片数、失败项）。
"""
import os
import re
import json
import urllib.parse
from collections import defaultdict


def safe_name(name):
    name = (name or "").strip()
    name = re.sub(r'[\\/:*?"<>|]', '_', name)
    name = name.replace('\n', ' ').replace('\r', ' ')
    name = re.sub(r'\s+', ' ', name).strip().strip('.')
    return name or "未命名"


class Layout:
    def __init__(self, output_dir, mode='tree'):
        self.base = output_dir
        self.mode = mode
        self._used = defaultdict(int)   # 同名计数，key=目录相对路径

    def doc_dir(self, node):
        """返回文档应落盘的目录绝对路径（含去重）。"""
        if self.mode == 'flat':
            rel = safe_name(node.title)
        else:
            parts = [safe_name(p) for p in node.path] + [safe_name(node.title)]
            rel = os.path.join(*parts)
        # 去重
        self._used[rel] += 1
        n = self._used[rel]
        if n > 1:
            rel = f"{rel}({n})"
        path = os.path.join(self.base, rel)
        os.makedirs(path, exist_ok=True)
        return path


def write_readme(base, records, title="知识库导出"):
    """生成层级索引 README。records: [{title, md_rel, depth}]"""
    lines = [f"# {title}", "", f"> 共 {len(records)} 篇文档", "", "## 目录", ""]
    for r in records:
        enc = '/'.join(urllib.parse.quote(seg) for seg in r['md_rel'].split('/'))
        lines.append(f"{'  ' * r['depth']}- [{r['title']}]({enc})")
    lines += ["", "> 说明：每篇文档的图片按所选策略存放，Markdown 内以相对路径引用。"]
    path = os.path.join(base, 'README.md')
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    return path


def write_report(base, report):
    path = os.path.join(base, '_export_report.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    return path
