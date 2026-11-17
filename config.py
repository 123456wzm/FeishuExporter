#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""config.py —— 配置读写与默认值管理。

配置持久化到程序目录下的 config.json，记住用户上次的所有设置。
"""
import json
import os

# 配置存放路径：程序根目录（打包后为 exe 同目录）
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")

# 默认配置（覆盖规划文档中的 12 项）
DEFAULTS = {
    "url": "",                      # 飞书链接（GUI 单行；CLI 可多行的用 ; 分隔）
    "output_dir": "",               # 输出目录，空则由 GUI/CLI 指定
    "auth_mode": "browser",         # browser=浏览器登录态 / api=官方 Open API
    "app_id": "",                   # API 模式：app_id
    "app_secret": "",               # API 模式：app_secret
    "images": "per-doc-data",       # 图片策略，见 exporter/image_store.py
    "format": "markdown",           # 导出格式 markdown/html/txt
    "layout": "tree",               # 目录模式 tree=层级还原 / flat=扁平
    "workers": 12,                  # 图片下载并发线程数
    "interval_ms": 200,             # 请求间隔毫秒（防限流）
    "readme": True,                 # 是否生成 README 索引
    "subdocs": True,                 # 是否包含子文档
    "overwrite": False,             # 是否覆盖已有文件；False=断点续传
    "retries": 3,                   # 失败重试次数
    "heads": False,                 # 是否复用系统 Chrome；False 时用 playwright 自带
    "chrome_path": "",              # 自定义 Chrome 路径，空=自动检测
}

# 图片策略可选值
IMAGE_STRATEGIES = ["per-doc-data", "shared-images", "inline-base64", "url-only", "skip"]
AUTH_MODES = ["browser", "api"]
FORMATS = ["markdown", "html", "txt"]
LAYOUTS = ["tree", "flat"]


def load() -> dict:
    """读取配置；文件不存在或字段缺失时用默认值补齐。"""
    cfg = dict(DEFAULTS)
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                user = json.load(f)
            if isinstance(user, dict):
                for k in DEFAULTS:
                    if k in user:
                        cfg[k] = user[k]
        except Exception:
            pass
    return cfg


def save(cfg: dict) -> None:
    """保存配置（只保存已知字段）。"""
    data = {k: cfg.get(k, DEFAULTS[k]) for k in DEFAULTS}
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def validate(cfg: dict) -> list:
    """返回配置错误列表；空列表表示合法。"""
    errs = []
    if not cfg.get("url"):
        errs.append("飞书链接不能为空")
    if not cfg.get("output_dir"):
        errs.append("输出目录不能为空")
    if cfg.get("images") not in IMAGE_STRATEGIES:
        errs.append(f"图片策略必须是 {IMAGE_STRATEGIES} 之一")
    if cfg.get("auth_mode") not in AUTH_MODES:
        errs.append("认证模式非法")
    if cfg.get("auth_mode") == "api":
        if not cfg.get("app_id") or not cfg.get("app_secret"):
            errs.append("API 模式需要填写 app_id 和 app_secret")
    if cfg.get("format") not in FORMATS:
        errs.append("导出格式非法")
    if cfg.get("layout") not in LAYOUTS:
        errs.append("目录模式非法")
    try:
        cfg["workers"] = max(1, min(32, int(cfg.get("workers", 12))))
    except Exception:
        errs.append("并发数必须是数字")
    try:
        cfg["retries"] = max(0, min(10, int(cfg.get("retries", 3))))
    except Exception:
        errs.append("重试次数必须是数字")
    return errs
