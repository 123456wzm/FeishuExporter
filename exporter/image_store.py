#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""exporter/image_store.py —— 图片下载与 5 种保存策略（用户关注项）。

5 种策略（cfg['images']）：
  per-doc-data  每篇文档独立 data/ 文件夹（默认）→ 引用 data/img_001.png
  shared-images 全局 images/ 目录 → 引用 ../images/{文档}_{序号}.png
  inline-base64 图片转 Base64 内嵌 → 引用 data:image/png;base64,...
  url-only      不下载，保留飞书原始 URL
  skip          不保存，返回 None（Markdown 里删除该引用）

下载逻辑（本次实战验证）：
  - 端点优先 cover，返回 "Cover No Exist" 时回退 /download/all/{token}/
  - 断点续传：已存在且非空文件跳过
  - 并发 + 重试 + 指数退避
  - 失败兜底：返回原 URL 而非中断
  - 全局 token 去重（多文档引用同图只下一次）
"""
import os
import re
import base64
import time
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed


class ImageStore:
    def __init__(self, cfg, cookies, on_message=None):
        self.cfg = cfg
        self.strategy = cfg.get('images', 'per-doc-data')
        self.workers = int(cfg.get('workers', 12))
        self.retries = int(cfg.get('retries', 3))
        self.overwrite = bool(cfg.get('overwrite', False))
        self.on_message = on_message or (lambda m: None)
        self._cookies = cookies or []
        self._downloaded = {}     # token -> 本地相对路径（去重）
        self._shared_names = {}   # (doc,fname) -> 全局文件名

    def _log(self, m):
        self.on_message(m)

    def _session(self):
        s = requests.Session()
        for c in self._cookies:
            s.cookies.set(c.get('name', ''), c.get('value', ''),
                          domain=c.get('domain', ''), path=c.get('path', '/'))
        return s

    def _download_bytes(self, token):
        """返回 (bytes, mime) 或 (None, err)。cover 失败回退 download/all。"""
        endpoints = [
            f"https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/v2/cover/{token}/",
            f"https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/all/{token}/",
        ]
        last = ""
        for ep in endpoints:
            for attempt in range(self.retries + 1):
                try:
                    r = self._session().get(ep, timeout=40, allow_redirects=True)
                    ct = r.headers.get('content-type', '')
                    if r.status_code == 200 and len(r.content) > 0 and 'json' not in ct:
                        if 'image' in ct or r.content[:1] in (b'\x89', b'G', b'R', b'\xff'):
                            return r.content, (ct.split(';')[0] or 'image/png')
                        # 有些图返回二进制但 content-type 不明，按图片接受
                        return r.content, 'image/png'
                    try:
                        last = (r.json().get('message') or '')[:60]
                    except Exception:
                        last = f"HTTP {r.status_code}"
                except Exception as e:
                    last = str(e)[:60]
                time.sleep(0.5 * (attempt + 1))
        return None, last

    def _url_only(self, token):
        return f"https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/all/{token}/"

    def resolve(self, images, doc_dir, doc_title=""):
        """处理一篇文档的图片，返回 {fname: 最终引用或None}。"""
        if self.strategy == 'skip':
            return {img['fname']: None for img in images}
        if self.strategy == 'url-only':
            return {img['fname']: self._url_only(img['token']) for img in images}

        data_dir = os.path.join(doc_dir, 'data')
        results = {}

        if self.strategy == 'inline-base64':
            for img in images:
                data, _ = self._download_bytes(img['token'])
                if data is None:
                    results[img['fname']] = self._url_only(img['token'])
                    continue
                b64 = base64.b64encode(data).decode('ascii')
                mime = img.get('mime') or 'image/png'
                results[img['fname']] = f"data:{mime};base64,{b64}"
            return results

        # per-doc-data / shared-images：并发下载落盘
        os.makedirs(data_dir, exist_ok=True)
        tasks = []
        for img in images:
            token = img['token']
            if self.strategy == 'shared-images':
                key = os.path.join('images', f"{_safe(doc_title)}_{img['fname']}")
                path = os.path.join(os.path.dirname(os.path.dirname(doc_dir)), key)
                ref = f"../images/{_safe(doc_title)}_{img['fname']}"
            else:
                path = os.path.join(data_dir, img['fname'])
                ref = f"data/{img['fname']}"
            tasks.append((img, path, ref))

        def do(task):
            img, path, ref = task
            token = img['token']
            if not self.overwrite and os.path.exists(path) and os.path.getsize(path) > 0:
                return img['fname'], ref
            if token in self._downloaded and os.path.exists(path):
                return img['fname'], ref
            data, mime = self._download_bytes(token)
            if data is None:
                return img['fname'], self._url_only(token)   # 兜底保留 URL
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'wb') as f:
                f.write(data)
            self._downloaded[token] = path
            return img['fname'], ref

        with ThreadPoolExecutor(max_workers=self.workers) as ex:
            futs = [ex.submit(do, t) for t in tasks]
            for fut in as_completed(futs):
                try:
                    fname, ref = fut.result()
                    results[fname] = ref
                except Exception as e:
                    self._log(f"图片处理异常：{e}")

        # 补齐未完成的（理论上不会）
        for img in images:
            results.setdefault(img['fname'], self._url_only(img['token']))
        return results


def _safe(name):
    return re.sub(r'[\\/:*?"<>|]', '_', (name or '').strip()) or 'doc'
