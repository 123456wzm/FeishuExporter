#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""parser/docx_blocks.py —— 飞书 block 结构化解析器。

把浏览器/API 拿到的 block_map + block_sequence 解析为统一的"内容块"结构，
再交由 exporter 渲染成 Markdown/HTML/txt。这样解析与导出解耦。

本次实战的核心逻辑（已验证 52 篇文档、601 张图）完整迁移至此：
  - 富文本 attribs 编码解析（加粗/斜体/链接/行内代码）
  - 表格 cell_set 按行列还原
  - 代码块、callout、列表、图片等
"""
import urllib.parse


def attrib_to_markdown(text, attrib, apool):
    """根据 attribs 编码还原富文本为 markdown。"""
    if not text:
        return ""
    if not attrib:
        return text
    num_to_attrib = ((apool or {}).get('numToAttrib') or {})
    segments = []
    i = 0
    cur = []
    n = len(attrib)
    while i < n:
        c = attrib[i]
        if c == '*':
            j = i + 1
            num = ''
            while j < n and (attrib[j].isdigit() or attrib[j].isalpha()):
                num += attrib[j]; j += 1
            if num:
                cur = num_to_attrib.get(num, []) or []
            i = j
        elif c == '+':
            j = i + 1
            hx = ''
            while j < n and attrib[j] in '0123456789abcdefABCDEF':
                hx += attrib[j]; j += 1
            if hx:
                segments.append((int(hx, 16), list(cur)))
            i = j
        else:
            i += 1
    if not segments:
        return text
    out = []
    pos = 0
    for cnt, attrs in segments:
        chunk = text[pos:pos + cnt]
        pos += cnt
        if not chunk:
            continue
        bold = italic = inline_code = False
        link = None
        for a in attrs:
            if not isinstance(a, list) or len(a) < 2:
                continue
            k, v = a[0], a[1]
            if k == 'bold' and str(v).lower() == 'true': bold = True
            elif k == 'italic' and str(v).lower() == 'true': italic = True
            elif k == 'inline_code' and str(v).lower() == 'true': inline_code = True
            elif k == 'link' and v:
                link = urllib.parse.unquote(str(v))
        seg = chunk
        if inline_code:
            seg = f"`{seg}`"
        else:
            if bold: seg = f"**{seg}**"
            if italic: seg = f"*{seg}*"
            if link: seg = f"[{seg}]({link})"
        out.append(seg)
    if pos < len(text):
        out.append(text[pos:])
    return ''.join(out)


def get_text(data):
    """提取 block 的富文本 markdown（兼容浏览器 DATA 与官方 API 两种结构）。"""
    # 浏览器内部：text.initialAttributedTexts
    t = data.get('text') or {}
    iat = t.get('initialAttributedTexts') or {}
    texts = iat.get('text') or {}
    attribs = iat.get('attribs') or {}
    apool = t.get('apool') or {}
    raw = texts.get('0', '')
    attrib = attribs.get('0', '')
    if raw or attrib:
        return attrib_to_markdown(raw, attrib, apool)
    # 官方 API：text.elements[].text_run.content
    elems = (t.get('elements') or [])
    parts = []
    for e in elems:
        tr = e.get('text_run') or {}
        content = tr.get('content', '')
        style = (tr.get('text_element_style') or {})
        if style.get('bold'): content = f"**{content}**"
        if style.get('italic'): content = f"*{content}*"
        if style.get('inline_code'): content = f"`{content}`"
        ln = style.get('link') or {}
        if ln.get('url'): content = f"[{content}]({ln['url']})"
        parts.append(content)
    return ''.join(parts)


class BlockRenderer:
    """把 block 渲染为 markdown 行，并收集图片。

    注意：此类既做"解析"也做"markdown 渲染"， exporter 层再包一层标题与策略。
    之所以合并，是因为飞书 block 的嵌套渲染本身就与 markdown 语法强耦合。
    HTML/txt 导出可另写 renderer，复用 get_text 与图片收集。
    """

    def __init__(self, block_map, image_dir="data"):
        self.bm = block_map
        self.image_dir = image_dir
        self.images = []   # {token, fname, mime, caption}
        # 注入 _bid 便于反查
        for bid, b in block_map.items():
            d = b.get('data')
            if isinstance(d, dict):
                d['_bid'] = bid

    def block(self, bid):
        return self.bm.get(bid, {})

    def children(self, data):
        return data.get('children', []) or []

    def render(self, sequence=None):
        """主入口：返回 markdown 正文字符串。"""
        root = None
        for bid, b in self.bm.items():
            if (b.get('data') or {}).get('type') == 'page':
                root = bid; break
        if root:
            top = self.block(root).get('data', {}).get('children', []) or sequence or []
        else:
            top = sequence or []
        lines = []
        for bid in top:
            b = self.block(bid)
            data = b.get('data', {})
            if (data.get('type') == 'page'):
                continue
            pid = data.get('parent_id', '')
            ptype = (self.block(pid).get('data', {}) or {}).get('type', '')
            if data.get('type') == 'table_cell' and ptype == 'table':
                continue
            r = self.render_block(bid, data, 0, 0)
            if r is not None and r != '':
                lines.append(r)
        return '\n\n'.join(lines)

    def render_children(self, bids, depth=0):
        lines = []
        idx = 0
        for bid in bids:
            b = self.block(bid)
            data = b.get('data', {})
            if not data:
                continue
            pt = (self.block(data.get('parent_id', '')).get('data', {}) or {}).get('type', '')
            if data.get('type') == 'table_cell' and pt == 'table':
                continue
            line = self.render_block(bid, data, depth, idx + 1)
            if line is not None:
                lines.append(line)
            idx += 1
        return lines

    def render_block(self, bid, data, depth, num):
        t = data.get('type', '')
        text = get_text(data)
        ch = self.children(data)
        indent = '  ' * depth
        if t == 'page':
            return None
        elif t and t.startswith('heading'):
            try:
                lvl = int(t[-1]) + 1
            except Exception:
                lvl = 2
            lvl = min(max(lvl, 2), 6)
            return f"{'#' * lvl} {text}"
        elif t == 'text':
            return text
        elif t == 'bullet':
            sub = self.render_children(ch, depth + 1)
            res = f"{indent}- {text}"
            return res + ("\n" + "\n".join(sub) if sub else "")
        elif t == 'numbered':
            sub = self.render_children(ch, depth + 1)
            res = f"{indent}{num}. {text}"
            return res + ("\n" + "\n".join(sub) if sub else "")
        elif t == 'todo':
            checked = data.get('done') or data.get('checked') or False
            return f"{indent}- [{'x' if checked else ' '}] {text}"
        elif t == 'image':
            img = data.get('image') or {}
            token = img.get('token', '')
            if not token:
                return ''
            name = img.get('name') or f"image_{token[:8]}.png"
            mime = img.get('mimeType', 'image/png')
            seq = len(self.images)
            ext = self._ext(mime, name)
            fname = f"img_{seq + 1:03d}{ext}"
            cap = img.get('caption') or {}
            captext = ((cap.get('text') or {}).get('initialAttributedTexts') or {}).get('text') or {}
            caption = captext.get('0', '')
            self.images.append({'token': token, 'fname': fname, 'mime': mime, 'caption': caption})
            link = f"{self.image_dir}/{fname}"
            md = f"![]({link})"
            return md + (f"\n> {caption}" if caption else '')
        elif t == 'code':
            lang = data.get('language') or ''
            sub = self.render_children(ch, depth)
            code_text = '\n'.join(sub) if sub else text
            return f"```{lang}\n{code_text}\n```"
        elif t == 'quote':
            sub = self.render_children(ch, depth)
            body = text if text else '\n'.join(sub)
            return '\n'.join(f"> {ln}" for ln in body.split('\n'))
        elif t == 'callout':
            sub = self.render_children(ch, depth)
            body = '\n'.join(sub) if sub else text
            return f"> 💡 {body}"
        elif t == 'divider':
            return '---'
        elif t == 'table':
            return self.render_table(data)
        elif t == 'grid' or t == 'column':
            return '\n'.join(self.render_children(ch, depth))
        else:
            sub = self.render_children(ch, depth)
            parts = []
            if text: parts.append(text)
            if sub: parts.append('\n'.join(sub))
            return '\n'.join(parts) if parts else ''

    def render_table(self, data):
        rows_id = data.get('rows_id', []) or []
        cols_id = data.get('columns_id', []) or []
        cell_set = data.get('cell_set', {}) or {}
        if not rows_id or not cols_id:
            rows = []
            for bid, b in self.bm.items():
                d = b.get('data', {})
                if d.get('type') == 'table_cell' and d.get('parent_id') == data.get('_bid'):
                    rows.append(self._cell_text(d))
            return '\n'.join(rows) if rows else ''
        table_rows = []
        for rid in rows_id:
            cells = []
            for cid in cols_id:
                info = cell_set.get(f"{rid}{cid}", {}) or {}
                bid = info.get('block_id')
                merge = info.get('merge_info', {}) or {}
                colspan = int(merge.get('col_span', 1) or 1)
                if not bid:
                    cells.append(''); continue
                cd = self.block(bid).get('data', {})
                cells.append(self._cell_text(cd))
                for _ in range(colspan - 1):
                    cells.append('')
            table_rows.append(cells)
        if not table_rows:
            return ''
        ncol = len(table_rows[0])
        out = []
        for ri, row in enumerate(table_rows):
            while len(row) < ncol: row.append('')
            out.append('| ' + ' | '.join(c.replace('\n', ' ') for c in row[:ncol]) + ' |')
            if ri == 0:
                out.append('|' + '---|' * ncol)
        return '\n'.join(out)

    def _cell_text(self, cd):
        parts = []
        ct = get_text(cd)
        if ct: parts.append(ct)
        for cb in self.children(cd):
            cbdata = self.block(cb).get('data', {})
            if cbdata.get('type') == 'image':
                r = self.render_block(cb, cbdata, 0, 0)
                if r: parts.append(r)
            else:
                t = get_text(cbdata)
                if t: parts.append(t)
        return ' '.join(parts)

    @staticmethod
    def _ext(mime, name):
        import os
        ext = os.path.splitext(name)[1]
        if ext: return ext
        m = {'image/png': '.png', 'image/jpeg': '.jpg', 'image/jpg': '.jpg',
             'image/gif': '.gif', 'image/webp': '.webp', 'image/svg+xml': '.svg'}
        return m.get(mime, '.png')
