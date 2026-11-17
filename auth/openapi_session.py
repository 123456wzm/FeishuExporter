#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""auth/openapi_session.py —— 官方 Open API 认证（P1 可选模式）。

流程：
  app_id + app_secret  ->  /open-apis/auth/v3/tenant_access_token/internal
  -> tenant_access_token（2 小时有效），调用 API 时放 Authorization 头。
本模块只负责认证与 token 管理；具体的 wiki/docx 接口调用在 source/api_source.py。
"""
import time
import requests

APP_BASE = "https://open.feishu.cn"


class OpenAPISession:
    def __init__(self, app_id: str, app_secret: str, on_message=None):
        self.app_id = app_id
        self.app_secret = app_secret
        self.on_message = on_message or (lambda m: None)
        self._token = ""
        self._expire_at = 0.0

    def _log(self, m):
        self.on_message(m)

    def get_token(self, force: bool = False) -> str:
        """获取 tenant_access_token，过期自动续期。"""
        if not force and self._token and time.time() < self._expire_at - 120:
            return self._token
        url = f"{APP_BASE}/open-apis/auth/v3/tenant_access_token/internal"
        try:
            r = requests.post(url, json={"app_id": self.app_id, "app_secret": self.app_secret}, timeout=20)
            j = r.json()
            if j.get("code") == 0:
                self._token = j.get("tenant_access_token", "")
                self._expire_at = time.time() + int(j.get("expire", 7200))
                self._log("已获取 tenant_access_token")
                return self._token
            self._log(f"获取 token 失败：{j.get('code')} {j.get('msg')}")
        except Exception as e:
            self._log(f"获取 token 异常：{e}")
        return ""

    def headers(self) -> dict:
        return {"Authorization": f"Bearer {self.get_token()}"}

    def get(self, path: str, **kwargs):
        """带认证头与自动续期的 GET。"""
        url = path if path.startswith("http") else APP_BASE + path
        return requests.get(url, headers=self.headers(), **kwargs)

    def valid(self) -> bool:
        return bool(self.get_token())
