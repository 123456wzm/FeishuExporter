#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""source/api_source.py —— 官方 Open API 抓取引擎（P1 可选）。

链路：
  spaces（获取 space_id）→ wiki nodes（文档树）→ docx/blocks（正文）
接口：
  GET /open-apis/wiki/v2/spaces/{space_id}/nodes
  GET /open-apis/docx/v1/documents/{doc_token}/blocks
注意：官方接口返回的分页、block 结构与浏览器内部 DATA 略有差异，
这里做适配，使其产出与 BrowserSource 相同的 DocNode / Document。
"""
from urllib.parse import urlparse
import re
from .base import BaseSource, DocNode, Document


class APISource(BaseSource):
    def __init__(self, auth_session, on_message=None):
        self.auth = auth_session
        self.on_message = on_message or (lambda m: None)

    def _log(self, m):
        self.on_message(m)

    def _space_id_from_url(self, raw_url):
        m = re.search(r"/wiki/space/(\d+)", raw_url)
        return m.group(1) if m else ""

    def list_nodes(self, resource) -> list:
        """通过官方 wiki 接口遍历文档树。"""
        space_id = self._space_id_from_url(resource.raw_url)
        if not space_id:
            self._log("未能从链接解析 space_id（API 模式需知识库空间链接）")
            return []
        nodes = []
        page_token = ""
        while True:
            path = f"/open-apis/wiki/v2/spaces/{space_id}/nodes"
            params = {"page_size": 50}
            if page_token:
                params["page_token"] = page_token
            r = self.auth.get(path, params=params, timeout=30)
            j = r.json()
            if j.get("code") != 0:
                self._log(f"获取节点失败：{j.get('code')} {j.get('msg')}（请确认应用已获知识库授权）")
                break
            data = j.get("data") or {}
            for item in data.get("items", []):
                obj_type = item.get("obj_type")
                kind = "doc"
                if obj_type == "folder":
                    kind = "folder"
                nodes.append(DocNode(
                    wiki_token=item.get("node_token", ""),
                    obj_token=item.get("obj_token", ""),
                    obj_type=22 if obj_type == "docx" else 0,
                    title=item.get("title", ""),
                    parent=item.get("parent_node_token", ""),
                    path=[], kind=kind,
                    url=f"https://feishu.cn/wiki/{item.get('node_token','')}",
                    has_child=bool(item.get("has_child")),
                ))
            if not data.get("has_more"):
                break
            page_token = data.get("page_token", "")
            if not page_token:
                break
        self._log(f"API 模式解析节点：{len(nodes)} 个")
        return nodes

    def get_document(self, node: DocNode) -> Document:
        """通过官方 docx blocks 接口获取正文。"""
        token = node.obj_token or node.wiki_token
        path = f"/open-apis/docx/v1/documents/{token}/blocks"
        all_blocks = {}
        page_token = ""
        while True:
            params = {"page_size": 500}
            if page_token:
                params["page_token"] = page_token
            r = self.auth.get(path, params=params, timeout=30)
            j = r.json()
            if j.get("code") != 0:
                self._log(f"获取正文失败：{node.title} -> {j.get('msg')}")
                break
            data = j.get("data") or {}
            for b in data.get("items", []):
                bid = b.get("block_id")
                all_blocks[bid] = b
            if not data.get("has_more"):
                break
            page_token = data.get("page_token", "")
            if not page_token:
                break
        seq = list(all_blocks.keys())
        return Document(title=node.title, blocks=all_blocks, sequence=seq,
                        raw={"source": "api", "token": token})
