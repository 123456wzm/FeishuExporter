#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""main.py —— 程序入口。

- 有 --cli 参数或检测到命令行参数时走 CLI；
- 否则默认启动 GUI。
PyInstaller 打包以此为入口。
"""
import sys
import os


def main():
    args = sys.argv[1:]
    # 显式 --cli 或存在其它命令行选项时走 CLI
    if args and (args[0] == '--cli' or any(a.startswith('-') for a in args)):
        from cli import main as cli_main
        cli_args = args[1:] if args and args[0] == '--cli' else args
        return cli_main(cli_args)
    # 默认 GUI
    try:
        from ui.app import main as gui_main
        gui_main()
    except ImportError as e:
        print(f"无法启动图形界面：{e}")
        print("可使用命令行模式：FeishuExporter.exe --cli --url <链接> --out <目录>")
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
