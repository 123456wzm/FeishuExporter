#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cli.py —— 命令行入口（便于批处理/定时）。

用法示例：
  python cli.py --url "https://xxx.feishu.cn/wiki/space/123" --out ./export
  python cli.py --url "url1;url2" --out ./export --images shared-images --format html
  python cli.py --config-only   # 只看当前配置
"""
import argparse
import sys
import config as cfgmod
from orchestrator import Exporter


def build_parser():
    d = cfgmod.load()
    p = argparse.ArgumentParser(description="Feishu Exporter 命令行导出工具")
    p.add_argument('--url', default=d.get('url', ''), help='飞书链接，多个用 ; 分隔')
    p.add_argument('--out', default=d.get('output_dir', ''), help='输出目录')
    p.add_argument('--auth', default=d.get('auth_mode', 'browser'), choices=['browser', 'api'])
    p.add_argument('--app-id', default=d.get('app_id', ''))
    p.add_argument('--app-secret', default=d.get('app_secret', ''))
    p.add_argument('--images', default=d.get('images', 'per-doc-data'),
                   choices=cfgmod.IMAGE_STRATEGIES)
    p.add_argument('--format', default=d.get('format', 'markdown'), choices=cfgmod.FORMATS)
    p.add_argument('--layout', default=d.get('layout', 'tree'), choices=cfgmod.LAYOUTS)
    p.add_argument('--workers', type=int, default=d.get('workers', 12))
    p.add_argument('--interval-ms', type=int, default=d.get('interval_ms', 200))
    p.add_argument('--no-readme', action='store_true', help='不生成 README 索引')
    p.add_argument('--no-subdocs', action='store_true', help='不包含子文档')
    p.add_argument('--overwrite', action='store_true', help='覆盖已有文件（默认断点续传）')
    p.add_argument('--system-chrome', action='store_true', help='复用系统 Chrome/Edge')
    p.add_argument('--chrome-path', default=d.get('chrome_path', ''))
    p.add_argument('--save-config', action='store_true', help='把本次参数保存为默认配置')
    p.add_argument('--config-only', action='store_true', help='打印当前配置后退出')
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    cfg = cfgmod.load()
    cfg.update({
        'url': args.url,
        'output_dir': args.out,
        'auth_mode': args.auth,
        'app_id': args.app_id,
        'app_secret': args.app_secret,
        'images': args.images,
        'format': args.format,
        'layout': args.layout,
        'workers': args.workers,
        'interval_ms': args.interval_ms,
        'readme': not args.no_readme,
        'subdocs': not args.no_subdocs,
        'overwrite': args.overwrite,
        'heads': args.system_chrome,
        'chrome_path': args.chrome_path,
    })
    if args.config_only:
        import json
        print(json.dumps(cfg, ensure_ascii=False, indent=2))
        return 0
    if args.save_config:
        cfgmod.save(cfg)

    errs = cfgmod.validate(cfg)
    if errs:
        print("配置错误：")
        for e in errs:
            print("  -", e)
        return 1

    def progress(e):
        msg = e.get('message', '')
        if msg:
            prefix = f"[{e['phase']}]"
            print(prefix, msg, flush=True)

    exporter = Exporter(cfg, on_progress=progress)
    report = exporter.run()
    print("\n=== 导出报告 ===")
    print(f"文档 {report['docs']} 篇，图片 {report['images']} 张，表格 {report['sheets']} 个，"
          f"失败 {len(report['failed'])} 项，耗时 {report.get('elapsed', 0):.1f}s")
    for f in report['failed']:
        print("  失败：", f['title'], '-', f['error'])
    return 0 if not report['failed'] else 2


if __name__ == '__main__':
    sys.exit(main())
