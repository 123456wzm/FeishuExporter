#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""parser/url.py —— 飞书链接解析。

把用户粘贴的各类飞书链接，归一化为结构化的 Resource 对象，
供数据源层决定用哪种方式抓取。
"""
import re
from dataclasses import dataclass, field
from urllib.parse import urlparse, parse_qs


@dataclass
class Resource:
    kind: str            # space | wiki | docx | sheets | base | unknown
    token: str           # 主 token（space_id / wiki_token / docx_token ...）
    raw_url: str
    extra: dict = field(default_factory=dict)

    def __repr__(self):
        return f"<Resource {self.kind} token={self.token}>"


# 各类型的路径正则（飞书域名下的典型路径）
_PATTERNS = [
    ("space", re.compile(r"/wiki/space/(\d+)")),                 # 知识库空间
    ("wiki",  re.compile(r"/wiki/([A-Za-z0-9]+)")),              # 知识库节点/文档
    ("docx",  re.compile(r"/docx/([A-Za-z0-9]+)")),              # 在线文档
    ("sheets",re.compile(r"/sheets/([A-Za-z0-9]+)")),            # 电子表格
    ("base",  re.compile(r"/base/([A-Za-z0-9]+)")),              # 多维表格
    ("base",  re.compile(r"/bitable/([A-Za-z0-9]+)")),           # 多维表格旧路径
]


def parse(raw_url: str) -> Resource:
    """解析单个飞书链接为 Resource。"""
    url = (raw_url or "").strip()
    # 补齐协议，便于 urlparse
    if url and not url.startswith("http"):
        url = "https://" + url
    parsed = urlparse(url)
    path = parsed.path or ""
    qs = parse_qs(parsed.query)

    for kind, pat in _PATTERNS:
        m = pat.search(path)
        if m:
            extra = {}
            # 知识库 space 链接常带 wiki_token 参数指向具体节点
            if "wiki_token" in qs:
                extra["wiki_token"] = qs["wiki_token"][0]
            return Resource(kind=kind, token=m.group(1), raw_url=raw_url.strip(), extra=extra)

    return Resource(kind="unknown", token="", raw_url=raw_url.strip())


def parse_many(text: str) -> list:
    """解析多行/分号分隔的多个链接，忽略空行与 # 注释行。"""
    out = []
    for line in (text or "").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        # 也支持用 ; 或中文分号分隔在同一行
        for part in re.split(r"[;；]", line):
            part = part.strip()
            if part:
                out.append(parse(part))
    # 单行分号场景（无换行）
    if not out and text and (";" in text or "；" in text):
        for part in re.split(r"[;；]", text):
            part = part.strip()
            if part:
                out.append(parse(part))
    return out


def is_feishu(raw_url: str) -> bool:
    """粗判是否为飞书域名链接。"""
    u = (raw_url or "").lower()
    return "feishu.cn" in u or "larksuite.com" in u
