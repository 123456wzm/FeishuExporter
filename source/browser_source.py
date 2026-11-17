#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""source/browser_source.py —— 浏览器登录态抓取引擎（P0 核心）。

复用本次实战验证过的逻辑：
  1) 文档树：GET /space/api/wiki/v2/tree/get_info/?space_id=...&wiki_token=...
     不同 wiki_token 返回不同子树 → BFS 递归合并 nodes/child_map 得到整棵树。
  2) 正文：逐篇 page.goto(url)，读取 window.DATA.clientVars.data 的
     block_map + block_sequence（飞书 docx 的标准块模型）。
  3) 图片：从 block 的 image.token 提取，cookie 由 BrowserSession 提供。
"""
from urllib.parse import urlparse
import requests
from .base import BaseSource, DocNode, Document


class BrowserSource(BaseSource):
    def __init__(self, session, on_message=None, interval_ms=200, subdocs=True):
        self.session = session          # BrowserSession 实例
        self.on_message = on_message or (lambda m: None)
        self.interval_ms = interval_ms
        self.subdocs = subdocs
        self._cookies = None
        self._base_origin = ""

    def _log(self, m):
        self.on_message(m)

    def _requests_session(self):
        """构造带浏览器 cookie 的 requests.Session。"""
        if self._cookies is None:
            self._cookies = self.session.cookies_for_requests()
        s = requests.Session()
        for c in self._cookies:
            s.cookies.set(c.get("name", ""), c.get("value", ""),
                          domain=c.get("domain", ""), path=c.get("path", "/"))
        return s

    def _origin(self, raw_url):
        u = raw_url if raw_url.startswith("http") else "https://" + raw_url
        p = urlparse(u)
        return f"{p.scheme}://{p.netloc}"

    # ---------- 文档树 ----------
    def _fetch_tree(self, origin, space_id, wiki_token):
        """调用 get_info 获取以 wiki_token 为根的子树（含 nodes/child_map）。"""
        url = f"{origin}/space/api/wiki/v2/tree/get_info/"
        params = {
            "space_id": space_id,
            "with_space": "false", "with_perm": "false",
            "expand_shortcut": "true", "need_shared": "true",
            "exclude_fields": "5", "with_deleted": "true",
            "wiki_token": wiki_token,
        }
        s = self._requests_session()
        r = s.get(url, params=params, timeout=30)
        j = r.json()
        tree = (j.get("data") or {}).get("tree") or {}
        return tree.get("nodes") or {}, tree.get("child_map") or {}

    def _resolve_space(self, resource, page):
        """打开 space 链接，解析出 space_id 与根 wiki_token。"""
        space_id = resource.token
        page.goto(resource.raw_url, wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(1500)
        final_url = page.url
        # 从跳转后的 URL 里提取一个 wiki token 作为遍历起点
        import re
        m = re.search(r"/wiki/([A-Za-z0-9]{10,})", final_url)
        start_token = m.group(1) if m else ""
        return space_id, start_token, self._origin(resource.raw_url)

    def list_nodes(self, resource) -> list:
        """根据 Resource 返回 DocNode 列表。"""
        origin = self._origin(resource.raw_url)
        page = self.session.new_page()
        nodes = {}
        child_map = {}

        try:
            if resource.kind == "space":
                space_id, start_token, origin = self._resolve_space(resource, page)
                if not start_token:
                    self._log("无法从知识库页面解析起始节点，尝试直接拉树")
                    start_token = ""
                # BFS 遍历：从起始 token 或空 token 开始
                queue = [start_token] if start_token else [""]
                seen = set()
                while queue:
                    tk = queue.pop(0)
                    if tk in seen:
                        continue
                    seen.add(tk)
                    ns, cm = self._fetch_tree(origin, space_id, tk)
                    nodes.update(ns)
                    child_map.update(cm)
                    # 找有子节点但未展开的，继续入队
                    for nid, n in ns.items():
                        wtk = n.get("wiki_token")
                        if n.get("has_child") and wtk and wtk not in seen:
                            queue.append(wtk)
                    if len(nodes) > 5000:
                        self._log("节点数异常，停止遍历")
                        break
            else:
                # wiki/docx/sheets/base 单节点：直接用 URL 里的 token
                space_id, start_token, origin = self._resolve_space(resource, page)
                if start_token:
                    ns, cm = self._fetch_tree(origin, space_id, start_token)
                    nodes.update(ns)
                    child_map.update(cm)
                    if resource.kind == "wiki" and self.subdocs:
                        queue = [start_token]
                        seen = {start_token}
                        while queue:
                            tk = queue.pop(0)
                            ns2, cm2 = self._fetch_tree(origin, space_id, tk)
                            nodes.update(ns2); child_map.update(cm2)
                            for nid, n in ns2.items():
                                wtk = n.get("wiki_token")
                                if n.get("has_child") and wtk and wtk not in seen:
                                    seen.add(wtk); queue.append(wtk)
        finally:
            try:
                page.close()
            except Exception:
                pass

        # 构造 DocNode 列表并计算 path
        result = self._build_nodes(nodes, child_map, origin)
        self._log(f"文档树解析完成：{len(result)} 个节点")
        return result

    def _build_nodes(self, nodes, child_map, origin):
        """从 nodes/child_map 构造 DocNode，补全父级路径。"""
        # 找根：出现在 child_map 的值里但不在任何 nodes 的 parent 里
        all_children = set()
        for kids in child_map.values():
            all_children.update(kids)
        roots = [tk for tk in nodes if tk not in all_children]

        def rel_path(tk):
            """返回父级标题路径（不含自身）。"""
            n = nodes.get(tk, {})
            parent = n.get("parent_wiki_token", "")
            if not parent or parent not in nodes:
                return []
            return rel_path(parent) + [nodes[parent].get("title", "").strip()]

        docs = []
        seen_tokens = set()
        for tk, n in nodes.items():
            obj_type = n.get("obj_type")
            obj_token = n.get("obj_token", "")
            title = (n.get("title") or "").strip() or f"未命名_{tk[:6]}"
            url = n.get("url") or f"{origin}/wiki/{tk}"
            # 类型判定：22=docx 文档；文件夹/快捷方式等按需过滤
            kind = "doc"
            if obj_type == 23:        # 文件夹节点，通常无正文
                kind = "folder"
            elif obj_type in (3,):    # 部分租户 sheet 类型
                kind = "sheet"
            # obj_token 为空基本是纯文件夹，跳过正文抓取
            key = (obj_token or tk)
            if key in seen_tokens:
                continue
            seen_tokens.add(key)
            docs.append(DocNode(
                wiki_token=tk, obj_token=obj_token, obj_type=obj_type or 0,
                title=title, parent=n.get("parent_wiki_token", ""),
                path=rel_path(tk), kind=kind, url=url,
                has_child=bool(n.get("has_child")),
            ))
        return docs

    # ---------- 正文 ----------
    def get_document(self, node: DocNode) -> Document:
        """逐篇导航，读取 window.DATA.clientVars.data 的 block 数据。"""
        page = self.session.new_page()
        try:
            page.goto(node.url, wait_until="domcontentloaded", timeout=60000)
            # 等待正文数据就绪，且确认 token 与当前文档一致（防止 SPA 复用旧页面）
            try:
                page.wait_for_function(
                    """(ot) => {
                        const d = window.DATA && window.DATA.clientVars && window.DATA.clientVars.data;
                        if (!d || !d.block_map) return false;
                        // meta.token 与 obj_token 一致时最可靠；拿不到则用标题兜底
                        const m = window.DATA.meta || {};
                        if (ot && m.token && m.token === ot) return true;
                        return !!m.title;
                    }""",
                    arg=node.obj_token, timeout=30000,
                )
            except Exception:
                # 放宽：只要 block_map 在就行
                page.wait_for_function(
                    "() => window.DATA && window.DATA.clientVars && window.DATA.clientVars.data && window.DATA.clientVars.data.block_map",
                    timeout=20000,
                )
            page.wait_for_timeout(400)
            data = page.evaluate("""() => {
                const d = window.DATA.clientVars.data;
                return {
                    block_map: d.block_map,
                    block_sequence: d.block_sequence,
                    title: (window.DATA.meta || {}).title || ''
                };
            }""")
            return Document(
                title=data.get("title") or node.title,
                blocks=data.get("block_map") or {},
                sequence=data.get("block_sequence") or [],
                raw={"url": node.url, "obj_token": node.obj_token},
            )
        finally:
            try:
                page.close()
            except Exception:
                pass
