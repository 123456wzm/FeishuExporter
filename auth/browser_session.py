#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""auth/browser_session.py —— 浏览器登录态认证（P0 默认模式）。

原理：
  curl 直连飞书会跳登录页，但浏览器有 cookie 持久化。首次运行时打开一个可见
  浏览器窗口让用户扫码/登录，登录后把 storage_state（cookie + localStorage）
  保存到本地；后续运行复用该 state，实现"零配置"。当检测到登录态失效时，
  提示用户重新登录。

对外提供：
  BrowserSession.ensure_login()  确保已登录（必要时弹出登录窗口）
  BrowserSession.get_context()   返回 Playwright BrowserContext（含登录态）
  BrowserSession.cookies_for_requests()  导出 cookie 供 requests 下载图片
  BrowserSession.is_logged_in()  检测登录态是否有效
"""
import os
import glob
import json

STATE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "browser_state.json")
STATE_PATH = os.path.abspath(STATE_PATH)

# 飞书登录态特征 cookie（存在其一即认为大概率已登录）
_LOGIN_COOKIES = {"session", "passport_csrf_token", "_csrf_token", "sessionid"}


def detect_chrome(explicit: str = "") -> str:
    """自动检测系统 Chrome/Edge 可执行文件路径；找不到返回空串（用 Playwright 自带）。"""
    if explicit and os.path.exists(explicit):
        return explicit
    candidates = []
    import platform
    sysname = platform.system()
    if sysname == "Windows":
        pf = os.environ.get("ProgramFiles", r"C:\Program Files")
        pf86 = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
        local = os.environ.get("LOCALAPPDATA", "")
        candidates = [
            os.path.join(pf, r"Google\Chrome\Application\chrome.exe"),
            os.path.join(pf86, r"Google\Chrome\Application\chrome.exe"),
            os.path.join(local, r"Google\Chrome\Application\chrome.exe"),
            os.path.join(pf, r"Microsoft\Edge\Application\msedge.exe"),
            os.path.join(pf86, r"Microsoft\Edge\Application\msedge.exe"),
        ]
    elif sysname == "Darwin":  # macOS
        candidates = [
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        ]
    else:  # Linux
        candidates = [
            "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium", "/usr/bin/chromium-browser",
        ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return ""


class BrowserSession:
    """管理 Playwright 浏览器的生命周期与登录态。"""

    def __init__(self, chrome_path: str = "", headless: bool = True,
                 use_system_chrome: bool = True, on_message=None):
        self.chrome_path = detect_chrome(chrome_path) if use_system_chrome else ""
        self.headless = headless
        self.on_message = on_message or (lambda m: None)
        self._pw = None
        self._browser = None
        self._context = None

    def _log(self, msg):
        self.on_message(msg)

    def start(self):
        """启动 Playwright 与浏览器。"""
        from playwright.sync_api import sync_playwright
        self._pw = sync_playwright().start()
        launch_kwargs = dict(headless=self.headless)
        if self.chrome_path:
            launch_kwargs["executable_path"] = self.chrome_path
            self._log(f"使用系统浏览器：{self.chrome_path}")
        else:
            self._log("使用 Playwright 内置 Chromium")
        # 容器/root 环境需要 --no-sandbox；在 Windows 上无害
        launch_kwargs["args"] = ["--no-sandbox", "--disable-dev-shm-usage"]
        self._browser = self._pw.chromium.launch(**launch_kwargs)

    def _has_state(self):
        return os.path.exists(STATE_PATH) and os.path.getsize(STATE_PATH) > 0

    def is_logged_in(self) -> bool:
        """用已保存的 state 建临时上下文，访问飞书个人页检测是否跳转登录。"""
        if not self._has_state():
            return False
        try:
            ctx = self._browser.new_context(storage_state=STATE_PATH)
            page = ctx.new_page()
            resp = page.goto("https://account.feishu.cn/", timeout=20000, wait_until="domcontentloaded")
            url = page.url.lower()
            page.close()
            ctx.close()
            # 跳到 passport 登录页说明失效
            if "passport" in url or "login" in url:
                return False
            return resp is not None
        except Exception:
            return False

    def ensure_login(self, login_url: str = "https://www.feishu.cn/", interactive: bool = True):
        """确保登录态可用。
        - 已有 state 且有效：直接复用；
        - 否则：打开可见浏览器让用户登录，登录后保存 state。
        """
        if self._browser is None:
            self.start()

        if self._has_state() and self.is_logged_in():
            self._context = self._browser.new_context(storage_state=STATE_PATH)
            self._log("已复用保存的登录态")
            return True

        if not interactive:
            # 非交互（如公开分享知识库）：用空 context 也能抓公开内容
            self._log("未检测到登录态，使用匿名上下文（仅公开内容可导出）")
            self._context = self._browser.new_context()
            return False

        self._log("需要登录：即将打开浏览器窗口，请在窗口中完成登录...")
        # 登录需要可见窗口
        login_browser = self._browser
        ctx = login_browser.new_context()
        page = ctx.new_page()
        page.goto(login_url, wait_until="domcontentloaded", timeout=30000)
        self._log("请在弹出的浏览器中完成登录（扫码/账号密码），完成后回到此窗口按回车键继续。")
        try:
            input()  # CLI 场景阻塞等待用户确认
        except Exception:
            pass
        # 保存登录态
        ctx.storage_state(path=STATE_PATH)
        self._log(f"登录态已保存：{STATE_PATH}")
        page.close()
        ctx.close()
        self._context = self._browser.new_context(storage_state=STATE_PATH)
        return True

    def load_state_context(self):
        """直接用已保存 state 建立主工作上下文（不做在线检测，供 CI/自动化）。"""
        if self._browser is None:
            self.start()
        if self._has_state():
            self._context = self._browser.new_context(storage_state=STATE_PATH)
        else:
            self._context = self._browser.new_context()
        return self._context

    def get_context(self):
        if self._context is None:
            return self.load_state_context()
        return self._context

    def new_page(self):
        return self.get_context().new_page()

    def cookies_for_requests(self) -> list:
        """导出当前上下文的 cookie 为 requests 可用的 dict 列表。"""
        cookies = []
        try:
            for c in self.get_context().cookies():
                cookies.append({
                    "name": c["name"], "value": c["value"],
                    "domain": c.get("domain", ""), "path": c.get("path", "/"),
                })
        except Exception:
            # 从 state 文件读取兜底
            if self._has_state():
                with open(STATE_PATH, "r", encoding="utf-8") as f:
                    st = json.load(f)
                cookies = st.get("cookies", [])
        return cookies

    def close(self):
        try:
            if self._context:
                self._context.close()
            if self._browser:
                self._browser.close()
            if self._pw:
                self._pw.stop()
        except Exception:
            pass
