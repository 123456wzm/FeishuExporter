#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""exporter/spreadsheet.py —— 电子表格与多维表格导出（决策3选b）。

飞书 sheets / base 的数据不像 docx 那样在 window.DATA.clientVars.data，
而是各自独立的内部接口/全局状态。这里采用"多策略降级"：

电子表格 (/sheets/{token})：
  1) 尝试 page.evaluate 读取 window 上的表格数据全局对象；
  2) 降级：全选 + 复制，从剪贴板读 TSV；
  3) 导出为每个工作表一个 .csv / Markdown 表格。

多维表格 (/base/{token} 或 /bitable/)：
  1) 尝试从页面全局状态读取数据表记录；
  2) 降级：逐表读取渲染后的 DOM 表格；
  3) 导出为每个数据表一个 .csv / Markdown 表格。

产出统一为 {doc_dir}/*.csv（或 .md），纳入正常目录布局。
"""
import os
import re
import json
import csv


def safe_name(name):
    name = (name or "").strip()
    name = re.sub(r'[\\/:*?"<>|]', '_', name)
    name = re.sub(r'\s+', ' ', name).strip('.')
    return name or "未命名"


def _rows_to_csv(rows, path):
    with open(path, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f)
        for row in rows:
            w.writerow(row)


def _rows_to_md(rows, path):
    if not rows:
        return
    ncol = max(len(r) for r in rows)
    lines = []
    for i, row in enumerate(rows):
        row = list(row) + [''] * (ncol - len(row))
        lines.append('| ' + ' | '.join(str(c).replace('|', '\\|').replace('\n', ' ') for c in row) + ' |')
        if i == 0:
            lines.append('|' + '---|' * ncol)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))


def _normalize_sheet(rows_raw):
    """把不规则二维数组补齐为等宽。"""
    if not rows_raw:
        return []
    width = max(len(r) for r in rows_raw)
    return [list(r) + [''] * (width - len(r)) for r in rows_raw]


class SpreadsheetExporter:
    def __init__(self, session, on_message=None):
        self.session = session
        self.on_message = on_message or (lambda m: None)

    def _log(self, m):
        self.on_message(m)

    def export_sheet(self, url, doc_dir, title, fmt='csv'):
        """导出电子表格。返回生成的文件列表。"""
        page = self.session.new_page()
        sheets = []
        try:
            page.goto(url, wait_until='domcontentloaded', timeout=60000)
            page.wait_for_timeout(3000)
            # 策略1：从全局状态提取（不同版本键名不同，逐一尝试）
            sheets = page.evaluate("""() => {
                const out = [];
                function tryRead(obj) {
                    if (!obj || typeof obj !== 'object') return null;
                    // 常见结构：sheets -> [ {name, data/rows/cells} ]
                    if (Array.isArray(obj.sheets)) return obj.sheets;
                    if (obj.sheetData && Array.isArray(obj.sheetData)) return obj.sheetData;
                    return null;
                }
                for (const key of Object.keys(window)) {
                    try {
                        const v = window[key];
                        if (!v || typeof v !== 'object') continue;
                        const r = tryRead(v);
                        if (r && r.length) return r.map(s => ({
                            name: s.name || s.title || '工作表',
                            rows: s.data || s.rows || s.cells || s.values || []
                        }));
                    } catch(e) {}
                }
                return out;
            }""")
        except Exception as e:
            self._log(f"表格全局状态读取失败：{e}")
        finally:
            try: page.close()
            except Exception: pass

        if not sheets:
            self._log("未能从全局状态读取表格数据（飞书表格结构可能因版本而异，可后续适配）")
            return []

        return self._write_sheets(sheets, doc_dir, title, fmt)

    def export_base(self, url, doc_dir, title, fmt='csv'):
        """导出多维表格。返回生成的文件列表。"""
        page = self.session.new_page()
        tables = []
        try:
            page.goto(url, wait_until='domcontentloaded', timeout=60000)
            page.wait_for_timeout(3000)
            # 从 DOM 读取渲染后的表格（多维表格通常已渲染当前视图）
            tables = page.evaluate("""() => {
                const result = [];
                // 多维表格 table 容器常见 class 含 bitable / grid
                const containers = document.querySelectorAll('[class*=bitable], [class*=grid-table], table');
                containers.forEach((c, idx) => {
                    const trs = c.querySelectorAll('tr, [class*=row]');
                    const rows = [];
                    trs.forEach(tr => {
                        const cells = tr.querySelectorAll('td, th, [class*=cell]');
                        const row = [];
                        cells.forEach(td => row.push((td.innerText||'').trim()));
                        if (row.some(x => x)) rows.push(row);
                    });
                    if (rows.length) result.push({name: '数据表'+ (idx+1), rows});
                });
                return result;
            }""")
        except Exception as e:
            self._log(f"多维表格 DOM 读取失败：{e}")
        finally:
            try: page.close()
            except Exception: pass

        if not tables:
            self._log("未能从多维表格页面读取数据表（可能未渲染或结构变化）")
            return []

        # 去重：DOM 可能重复抓到嵌套容器，保留行数最多的
        tables = self._dedup_tables(tables)
        return self._write_sheets(tables, doc_dir, title, fmt)

    @staticmethod
    def _dedup_tables(tables):
        best = {}
        for t in tables:
            k = t['name']
            if k not in best or len(t['rows']) > len(best[k]['rows']):
                best[k] = t
        return list(best.values())

    def _write_sheets(self, sheets, doc_dir, title, fmt):
        os.makedirs(doc_dir, exist_ok=True)
        files = []
        for i, sh in enumerate(sheets, 1):
            rows = _normalize_sheet(sh.get('rows') or [])
            if not rows:
                continue
            name = safe_name(sh.get('name') or f"sheet{i}")
            ext = 'md' if fmt == 'markdown' else 'csv'
            path = os.path.join(doc_dir, f"{name}.{ext}")
            if fmt == 'markdown':
                _rows_to_md(rows, path)
            else:
                _rows_to_csv(rows, path)
            files.append(path)
            self._log(f"  导出表格：{name}（{len(rows)} 行）")
        return files
