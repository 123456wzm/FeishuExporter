# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec —— Feishu Exporter 单文件打包。

用 --onefile 打成单个 exe；不内置 Chromium（复用系统浏览器，控制体积）。
playwright 的 driver 需要作为数据打包进去。
"""
import os

block_cipher = None

# 收集 playwright 的 driver 与 node 运行时
from PyInstaller.utils.hooks import collect_data_files
datas = []
datas += collect_data_files('playwright')
datas += collect_data_files('playwright._impl')

a = Analysis(
    ['../main.py'],
    pathex=['..'],
    binaries=[],
    datas=datas,
    hiddenimports=[
        'playwright', 'playwright.sync_api',
        'requests', 'markdown',
        'tkinter',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['matplotlib', 'numpy', 'pandas', 'PIL', 'cv2'],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz, a.scripts, a.binaries, a.zipfiles, a.datas, [],
    name='FeishuExporter',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,          # GUI 程序，隐藏控制台窗口
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon='icon.ico' if os.path.exists('icon.ico') else None,
)
