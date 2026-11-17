#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""source/base.py —— 统一数据源抽象接口。

两种引擎（浏览器登录态 / 官方 API）都实现下面两个方法，
上层 exporter 只依赖此接口，不关心数据从哪来。

数据结构约定：
  DocNode: 文档树节点
    {wiki_token, obj_token, obj_type, title, parent, path:[父级标题...],
     kind: 'doc'|'folder'|'sheet'|'base', url}
  Document: 单篇内容
    {title, blocks: {block_id: {...}}, sequence:[block_id...], raw:原始响应}
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class DocNode:
    wiki_token: str = ""
    obj_token: str = ""
    obj_type: int = 0
    title: str = ""
    parent: str = ""
    path: list = field(default_factory=list)   # 父级标题列表（不含自身）
    kind: str = "doc"
    url: str = ""
    has_child: bool = False

    @property
    def is_doc(self):
        return self.kind == "doc"

    @property
    def is_sheet(self):
        return self.kind == "sheet"

    @property
    def is_base(self):
        return self.kind == "base"


@dataclass
class Document:
    title: str = ""
    blocks: dict = field(default_factory=dict)     # block_map
    sequence: list = field(default_factory=list)   # block_sequence
    raw: dict = field(default_factory=dict)


class BaseSource(ABC):
    @abstractmethod
    def list_nodes(self, resource) -> list:
        """根据 Resource 返回 DocNode 列表（文档树，已按层级带 path）。"""
        raise NotImplementedError

    @abstractmethod
    def get_document(self, node: DocNode) -> Document:
        """返回单篇文档的 block 数据。"""
        raise NotImplementedError

    def close(self):
        pass
