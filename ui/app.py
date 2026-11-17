#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ui/app.py —— tkinter 图形界面（主交互方式）。

功能：
  - 链接输入（多行，每行一个）+ 输出目录选择
  - 认证模式 / 图片策略 / 格式 / 目录模式 下拉
  - 并发数、间隔、重试等高级项
  - 开始/取消按钮、进度条、实时日志
  - 完成后弹出"打开输出目录"与导出摘要
导出在后台线程执行，通过 queue 把进度事件传给 UI 线程，避免界面卡死。
"""
import os
import queue
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import config as cfgmod
from orchestrator import Exporter


class App:
    def __init__(self, root):
        self.root = root
        root.title("Feishu Exporter · 飞书内容导出器")
        root.geometry("720x820")
        # 窗口居中
        root.update_idletasks()
        w, h = 720, 820
        x = (root.winfo_screenwidth() // 2) - (w // 2)
        y = (root.winfo_screenheight() // 2) - (h // 2)
        root.geometry(f"{w}x{h}+{x}+{y}")
        self.q = queue.Queue()
        self.exporter = None
        self.worker = None
        self._build_ui()
        self._load_cfg()
        self.root.after(100, self._poll_queue)

    def _build_ui(self):
        frm = ttk.Frame(self.root, padding=12)
        frm.pack(fill='both', expand=True)

        # 链接
        ttk.Label(frm, text="飞书链接（每行一个，支持知识库/文档/表格）").pack(anchor='w')
        self.url_text = tk.Text(frm, height=4, wrap='word')
        self.url_text.pack(fill='x', pady=(2, 8))

        # 输出目录
        out_frame = ttk.Frame(frm)
        out_frame.pack(fill='x', pady=4)
        ttk.Label(out_frame, text="输出目录：").pack(side='left')
        self.out_var = tk.StringVar()
        ttk.Entry(out_frame, textvariable=self.out_var).pack(side='left', fill='x', expand=True, padx=4)
        ttk.Button(out_frame, text="选择...", command=self._choose_dir).pack(side='left')

        # 配置项网格
        grid = ttk.Frame(frm)
        grid.pack(fill='x', pady=8)
        r = 0
        self.vars = {}

        def add_row(label, key, values=None, default=None, width=18):
            nonlocal r
            ttk.Label(grid, text=label).grid(row=r, column=0, sticky='w', padx=(0, 8), pady=3)
            if values:
                v = tk.StringVar(value=default or values[0])
                cb = ttk.Combobox(grid, textvariable=v, values=values, width=width, state='readonly')
                cb.grid(row=r, column=1, sticky='w', pady=3)
            else:
                v = tk.StringVar(value=default or '')
                ttk.Entry(grid, textvariable=v, width=width).grid(row=r, column=1, sticky='w', pady=3)
            self.vars[key] = v
            r += 1

        add_row("认证模式", 'auth_mode', ['browser', 'api'], 'browser')
        add_row("图片保存", 'images', cfgmod.IMAGE_STRATEGIES, 'per-doc-data')
        add_row("导出格式", 'format', cfgmod.FORMATS, 'markdown')
        add_row("目录模式", 'layout', cfgmod.LAYOUTS, 'tree')
        add_row("并发线程", 'workers', None, '12')
        add_row("请求间隔(ms)", 'interval_ms', None, '200')
        add_row("重试次数", 'retries', None, '3')

        # API 凭据（API 模式时用）
        api_frame = ttk.LabelFrame(frm, text="官方 API 凭据（仅 API 模式需要）", padding=6)
        api_frame.pack(fill='x', pady=6)
        ttk.Label(api_frame, text="app_id：").grid(row=0, column=0, sticky='w')
        self.app_id_var = tk.StringVar()
        ttk.Entry(api_frame, textvariable=self.app_id_var, width=40).grid(row=0, column=1, pady=2)
        ttk.Label(api_frame, text="app_secret：").grid(row=1, column=0, sticky='w')
        self.app_secret_var = tk.StringVar()
        ttk.Entry(api_frame, textvariable=self.app_secret_var, width=40, show='*').grid(row=1, column=1, pady=2)

        # 复选项
        opt_frame = ttk.Frame(frm)
        opt_frame.pack(fill='x', pady=4)
        self.readme_var = tk.BooleanVar(value=True)
        self.subdocs_var = tk.BooleanVar(value=True)
        self.syschrome_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(opt_frame, text="生成 README 索引", variable=self.readme_var).pack(side='left', padx=6)
        ttk.Checkbutton(opt_frame, text="包含子文档", variable=self.subdocs_var).pack(side='left', padx=6)
        ttk.Checkbutton(opt_frame, text="用系统 Chrome", variable=self.syschrome_var).pack(side='left', padx=6)

        # 按钮
        btn_frame = ttk.Frame(frm)
        btn_frame.pack(fill='x', pady=8)
        self.start_btn = ttk.Button(btn_frame, text="开始导出", command=self._start)
        self.start_btn.pack(side='left', padx=4)
        self.cancel_btn = ttk.Button(btn_frame, text="取消", command=self._cancel, state='disabled')
        self.cancel_btn.pack(side='left', padx=4)
        self.save_btn = ttk.Button(btn_frame, text="保存为默认配置", command=self._save_cfg)
        self.save_btn.pack(side='left', padx=4)

        # 进度
        self.progress = ttk.Progressbar(frm, mode='determinate')
        self.progress.pack(fill='x', pady=4)
        self.status_var = tk.StringVar(value="就绪")
        ttk.Label(frm, textvariable=self.status_var).pack(anchor='w')

        # 日志
        ttk.Label(frm, text="日志：").pack(anchor='w', pady=(8, 2))
        self.log = tk.Text(frm, height=10, wrap='word', state='disabled')
        self.log.pack(fill='both', expand=True)

    def _choose_dir(self):
        d = filedialog.askdirectory()
        if d:
            self.out_var.set(d)

    def _load_cfg(self):
        c = cfgmod.load()
        if c.get('url'):
            self.url_text.insert('1.0', c['url'])
        self.out_var.set(c.get('output_dir', ''))
        for k, v in self.vars.items():
            if k in c and c[k] is not None:
                v.set(str(c[k]))
        self.app_id_var.set(c.get('app_id', ''))
        self.app_secret_var.set(c.get('app_secret', ''))
        self.readme_var.set(bool(c.get('readme', True)))
        self.subdocs_var.set(bool(c.get('subdocs', True)))
        self.syschrome_var.set(bool(c.get('heads', False)))

    def _collect_cfg(self):
        c = cfgmod.load()
        c.update({
            'url': self.url_text.get('1.0', 'end').strip(),
            'output_dir': self.out_var.get().strip(),
            'auth_mode': self.vars['auth_mode'].get(),
            'images': self.vars['images'].get(),
            'format': self.vars['format'].get(),
            'layout': self.vars['layout'].get(),
            'workers': self.vars['workers'].get(),
            'interval_ms': self.vars['interval_ms'].get(),
            'retries': self.vars['retries'].get(),
            'app_id': self.app_id_var.get().strip(),
            'app_secret': self.app_secret_var.get().strip(),
            'readme': self.readme_var.get(),
            'subdocs': self.subdocs_var.get(),
            'heads': self.syschrome_var.get(),
        })
        errs = cfgmod.validate(c)
        return c, errs

    def _save_cfg(self):
        c, errs = self._collect_cfg()
        cfgmod.save(c)
        messagebox.showinfo("已保存", "配置已保存为默认值")

    def _start(self):
        c, errs = self._collect_cfg()
        if errs:
            messagebox.showerror("配置错误", "\n".join(errs))
            return
        self._append_log("=== 开始导出 ===")
        self.start_btn.config(state='disabled')
        self.cancel_btn.config(state='normal')
        self.progress['value'] = 0
        self.exporter = Exporter(c, on_progress=lambda e: self.q.put(e))

        def run():
            report = self.exporter.run()
            self.q.put({'phase': 'finished', 'report': report})

        self.worker = threading.Thread(target=run, daemon=True)
        self.worker.start()

    def _cancel(self):
        if self.exporter:
            self.exporter.cancel()
            self._append_log("已请求取消...")

    def _poll_queue(self):
        try:
            while True:
                e = self.q.get_nowait()
                self._handle_event(e)
        except queue.Empty:
            pass
        self.root.after(100, self._poll_queue)

    def _handle_event(self, e):
        phase = e.get('phase')
        if phase == 'finished':
            self.start_btn.config(state='normal')
            self.cancel_btn.config(state='disabled')
            rep = e.get('report', {})
            msg = (f"完成：{rep.get('docs',0)} 篇文档，{rep.get('images',0)} 张图片，"
                   f"{rep.get('sheets',0)} 个表格，失败 {len(rep.get('failed',[]))} 项")
            self.status_var.set(msg)
            self._append_log("=== " + msg + " ===")
            if messagebox.askyesno("导出完成", msg + "\n\n是否打开输出目录？"):
                try:
                    os.startfile(self.out_var.get())  # Windows
                except Exception:
                    try:
                        import subprocess
                        subprocess.Popen(['xdg-open', self.out_var.get()])
                    except Exception:
                        pass
            return
        msg = e.get('message', '')
        if msg:
            self._append_log(f"[{phase}] {msg}")
        self.status_var.set(msg or phase)
        if e.get('total', 0):
            pct = e.get('current', 0) / e['total'] * 100
            self.progress['value'] = pct

    def _append_log(self, text):
        self.log.config(state='normal')
        self.log.insert('end', text + '\n')
        self.log.see('end')
        self.log.config(state='disabled')


def main():
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == '__main__':
    main()
