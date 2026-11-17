#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""orchestrator.py —— 导出流程编排中枢。

把 URL解析 → 认证 → 数据源 → 解析 → 图片 → 布局 → 报告 串成一条流水线。
GUI 与 CLI 都通过 Exporter.run() 调用，进度通过 on_progress 回调上报。

on_progress(dict) 事件格式：
  {'phase': 'auth'|'tree'|'export'|'done'|'error', 'current':int, 'total':int,
   'message':str, 'doc':str}
"""
import os
import time
from parser import url as url_parser
from fs_layout import Layout, write_readme, write_report, safe_name
from exporter import markdown as md_exporter
from exporter import html_txt
from exporter.image_store import ImageStore
from exporter.spreadsheet import SpreadsheetExporter


class Exporter:
    def __init__(self, cfg, on_progress=None):
        self.cfg = cfg
        self.on_progress = on_progress or (lambda e: None)
        self._cancelled = False
        self._session = None
        self._source = None

    def _emit(self, phase, message="", current=0, total=0, doc=""):
        self.on_progress({
            'phase': phase, 'message': message,
            'current': current, 'total': total, 'doc': doc,
        })

    def cancel(self):
        self._cancelled = True

    def _build_source(self):
        """按认证模式构建数据源与会话。"""
        mode = self.cfg.get('auth_mode', 'browser')
        if mode == 'api':
            from auth.openapi_session import OpenAPISession
            from source.api_source import APISource
            auth = OpenAPISession(self.cfg.get('app_id', ''), self.cfg.get('app_secret', ''),
                                  on_message=lambda m: self._emit('auth', m))
            if not auth.valid():
                raise RuntimeError("API 认证失败，请检查 app_id/app_secret")
            self._session = auth
            self._source = APISource(auth, on_message=lambda m: self._emit('auth', m))
            return []
        else:
            from auth.browser_session import BrowserSession
            from source.browser_source import BrowserSource
            bs = BrowserSession(
                chrome_path=self.cfg.get('chrome_path', ''),
                use_system_chrome=bool(self.cfg.get('heads', False)),
                headless=True,
                on_message=lambda m: self._emit('auth', m),
            )
            bs.load_state_context()   # 用已保存 state；无 state 则用匿名 context（公开内容可下）
            self._session = bs
            self._source = BrowserSource(
                bs, on_message=lambda m: self._emit('tree', m),
                interval_ms=int(self.cfg.get('interval_ms', 200)),
                subdocs=bool(self.cfg.get('subdocs', True)),
            )
            # 返回 cookies 供图片下载
            return bs.cookies_for_requests()

    def run(self):
        """执行完整导出。返回报告 dict。"""
        report = {'docs': 0, 'images': 0, 'sheets': 0, 'failed': [], 'start': time.time()}
        try:
            # 1. 解析链接
            resources = url_parser.parse_many(self.cfg.get('url', ''))
            resources = [r for r in resources if url_parser.is_feishu(r.raw_url) or r.kind != 'unknown']
            if not resources:
                raise RuntimeError("没有有效的飞书链接")

            # 2. 认证 + 数据源
            self._emit('auth', "正在初始化认证...")
            cookies = self._build_source()

            # 3. 图片仓库
            image_store = ImageStore(self.cfg, cookies, on_message=lambda m: self._emit('export', m))

            # 4. 表格导出器（浏览器模式才支持）
            sheet_exporter = None
            if self.cfg.get('auth_mode', 'browser') == 'browser' and self._session and hasattr(self._session, 'new_page'):
                sheet_exporter = SpreadsheetExporter(self._session, on_message=lambda m: self._emit('export', m))

            out_dir = self.cfg['output_dir']
            os.makedirs(out_dir, exist_ok=True)
            layout = Layout(out_dir, mode=self.cfg.get('layout', 'tree'))
            fmt = self.cfg.get('format', 'markdown')
            readme_records = []

            # 5. 遍历资源 → 文档树 → 逐篇导出
            all_nodes = []
            for res in resources:
                self._emit('tree', f"解析文档树：{res.raw_url}")
                nodes = self._source.list_nodes(res)
                all_nodes.extend(nodes)

            # 过滤：只保留文档/表格/多维表节点（文件夹节点跳过正文）
            targets = [n for n in all_nodes if n.kind in ('doc', 'sheet', 'base')]
            total = len(targets)
            self._emit('export', f"开始导出 {total} 项", current=0, total=total)

            for i, node in enumerate(targets, 1):
                if self._cancelled:
                    self._emit('error', "导出已取消")
                    break
                self._emit('export', f"导出：{node.title}", current=i, total=total, doc=node.title)
                try:
                    doc_dir = layout.doc_dir(node)
                    depth = len(node.path)

                    if node.kind in ('sheet', 'base'):
                        if not sheet_exporter:
                            raise RuntimeError("表格导出需要浏览器模式")
                        files = (sheet_exporter.export_sheet(node.url, doc_dir, node.title, fmt)
                                 if node.kind == 'sheet' else
                                 sheet_exporter.export_base(node.url, doc_dir, node.title, fmt))
                        report['sheets'] += len(files)
                        main_file = files[0] if files else None
                    else:
                        document = self._source.get_document(node)
                        resolver = lambda imgs, dd, t=node.title: image_store.resolve(imgs, dd, t)
                        info = md_exporter.export(document, doc_dir, self.cfg, resolver,
                                                  on_message=lambda m: self._emit('export', m))

                        # 其他格式：基于 markdown 正文再转（复用 resolver 后的内容）
                        if fmt in ('html', 'txt'):
                            self._export_alt_format(document, doc_dir, self.cfg, resolver, fmt)
                            main_file = info['md_path'].rsplit('.', 1)[0] + ('.html' if fmt == 'html' else '.txt')
                        else:
                            main_file = info['md_path']
                        report['images'] += len(info['images'])

                    if main_file and self.cfg.get('readme', True):
                        md_rel = os.path.relpath(main_file, out_dir)
                        readme_records.append({'title': node.title, 'md_rel': md_rel, 'depth': depth})
                    report['docs'] += 1
                except Exception as e:
                    self._emit('error', f"失败：{node.title} - {e}", doc=node.title)
                    report['failed'].append({'title': node.title, 'error': str(e)})

            # 6. README + 报告
            if self.cfg.get('readme', True) and readme_records:
                write_readme(out_dir, readme_records, title="知识库导出")
            report['elapsed'] = time.time() - report['start']
            write_report(out_dir, report)
            self._emit('done', f"完成：{report['docs']} 篇文档，{report['images']} 张图片，"
                              f"{report['sheets']} 个表格，失败 {len(report['failed'])} 项",
                       current=total, total=total)
            return report
        except Exception as e:
            self._emit('error', f"导出异常：{e}")
            report['failed'].append({'title': '(全局)', 'error': str(e)})
            return report
        finally:
            self.close()

    def _export_alt_format(self, document, doc_dir, cfg, resolver, fmt):
        """HTML/txt 需要拿到"替换图片引用后"的 markdown 正文。"""
        from parser.docx_blocks import BlockRenderer
        import re
        renderer = BlockRenderer(document.blocks, image_dir='data')
        body = renderer.render(document.sequence)
        ref_map = resolver(renderer.images, doc_dir)

        def repl(m):
            key = os.path.basename(m.group(2))
            final = ref_map.get(key, m.group(2))
            return "" if final is None else f"![{m.group(1)}]({final})"
        body = re.sub(r'!\[([^\]]*)\]\(data/([^)]+)\)', repl, body)
        if fmt == 'html':
            html_txt.export_html(document, doc_dir, cfg, body)
        else:
            html_txt.export_txt(document, doc_dir, cfg, body)

    def close(self):
        try:
            if self._session and hasattr(self._session, 'close'):
                self._session.close()
        except Exception:
            pass
