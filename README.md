# Feishu Exporter（飞书内容导出器）

把飞书知识库/文档/表格一键导出为本地文件（Markdown/HTML/txt + 图片）的**免安装桌面工具**。源于一次真实的飞书知识库备份实战（52 篇文档 + 601 张图片完整导出），现将验证过的流程产品化。

## 一、功能特性

| 能力 | 说明 |
|---|---|
| **内容范围** | 知识库空间、文件夹节点、单篇文档（docx）、电子表格、多维表格 |
| **零配置认证** | 默认复用浏览器登录态，首次登录后免密；可选官方 Open API |
| **图片保存** | 5 种策略可选（每文档独立 data / 全局目录 / Base64 / 仅链接 / 不保存） |
| **导出格式** | Markdown（默认）、HTML、纯文本 |
| **目录组织** | 树形层级还原 / 扁平模式，标题清洗、重名去重、README 索引 |
| **健壮性** | 断点续传、失败重试、并发下载、单篇失败不影响整体、导出报告 |
| **双入口** | GUI 图形界面（主）+ CLI 命令行（批处理/定时） |

## 二、快速开始

### 方式 A：免安装 EXE（推荐给最终用户）

1. 从 Releases 下载 `FeishuExporter.exe`（或本地打包，见第五节）；
2. 双击运行，弹出图形界面；
3. 填入飞书链接、选择输出目录、选好图片策略；
4. 点击「开始导出」，等待完成。

> EXE 复用系统已安装的 Chrome/Edge（勾选"用系统 Chrome"即可），无需内置浏览器，体积约 50–70MB。

### 方式 B：Python 源码运行

```bash
pip install -r requirements.txt
python main.py              # 启动 GUI
```

CLI 模式：

```bash
python main.py --cli --url "https://xxx.feishu.cn/wiki/space/123" --out ./export
# 或
python cli.py --url "url1;url2" --out ./export --images shared-images --format html
```

## 三、界面与配置项说明

### 3.1 GUI 表单

| 配置项 | 说明 |
|---|---|
| 飞书链接 | 每行一个，支持知识库空间 `/wiki/space/{id}`、节点 `/wiki/{token}`、文档 `/docx/{token}`、表格 `/sheets/{token}`、多维表 `/base/{token}` |
| 输出目录 | 点击「选择...」指定，配置会记住 |
| 认证模式 | `browser`（默认，零配置）/ `api`（需填 app_id/app_secret） |
| 图片保存 | 见下表 |
| 导出格式 | markdown / html / txt |
| 目录模式 | tree（还原层级）/ flat（平铺） |
| 并发线程/间隔/重试 | 下载与请求控制，防限流 |
| 生成 README / 包含子文档 / 用系统 Chrome | 复选项 |

### 3.2 图片保存策略（重点）

| 策略值 | 名称 | 行为 | Markdown 引用 |
|---|---|---|---|
| `per-doc-data` | 每文档独立 data（默认） | 每篇文档文件夹下建 `data/` | `![](data/img_001.png)` |
| `shared-images` | 全局图片目录 | 所有图片放输出根 `images/` | `![](../images/文档_img_001.png)` |
| `inline-base64` | 内嵌 Base64 | 图片转 Base64 写进 md | `![](data:image/png;base64,...)` |
| `url-only` | 仅保留链接 | 不下载，保留飞书 URL | `![](https://...)` |
| `skip` | 不保存 | 丢弃图片引用 | 无 |

通用规则：按文档内出现顺序命名 `img_001.png`；相同图片 token 全局去重；断点续传（已存在非空文件跳过）；下载失败自动保留原 URL 兜底。

## 四、输出结构

```
{输出目录}/
├── README.md                  # 层级索引
├── _export_report.json        # 导出报告（篇数/图片数/失败项）
├── 概述/
│   ├── 概述.md
│   └── data/                  # per-doc-data 策略时
│       ├── img_001.png
│       └── ...
└── 讲义/
    ├── 讲义.md
    └── Web开发介绍/
        ├── Web开发介绍.md
        └── data/
```

## 五、打包 EXE（开发者）

程序**无法在当前 Linux 环境直接产出 Windows EXE**（PyInstaller 不是交叉编译器）。两种路径：

### 路径 1：GitHub Actions 云端构建（推荐）

把项目推送到 GitHub，然后：
- 打 tag 推送（如 `git tag v1.0 && git push --tags`）会自动构建并创建 Release；
- 或在 Actions 页手动触发 `workflow_dispatch`；
- 构建完成后在 Artifacts / Releases 下载 `FeishuExporter.exe`。

### 路径 2：本地 build.bat

在 Windows 机器上，双击 `build/build.bat`，自动安装依赖并打包，产物 `FeishuExporter.exe` 出现在项目根目录。

## 六、认证模式说明

- **浏览器登录态（默认）**：首次运行若未登录，程序会打开浏览器窗口，扫码/登录后自动保存 `browser_state.json`；后续复用。适合个人/公开内容。
- **官方 Open API**：填入企业自建应用的 `app_id`/`app_secret`，需在飞书开放平台为应用申请知识库/文档权限。更稳定但配置门槛高。

## 七、常见问题（FAQ）

| 问题 | 原因/解决 |
|---|---|
| 导出的文档没正文/图片 | 登录态失效 → 删除 `browser_state.json` 重新登录；公开内容无需登录 |
| 部分图片是链接不是本地文件 | 该图下载失败自动兜底为原 URL，检查网络或稍后重试（可勾选覆盖） |
| EXE 被杀毒软件报毒 | PyInstaller 打包常见误报；可加数字签名，或改用源码运行 |
| 表格导出为空 | 飞书表格结构随版本变化，当前为尽力解析；可反馈具体页面适配 |
| 提示"未找到 Chrome" | 安装 Chrome/Edge，或在 GUI 用内置浏览器模式（体积更大） |

## 八、项目结构

```
FeishuExporter/
├── main.py              # 入口（GUI/CLI 分发）
├── config.py            # 配置读写
├── orchestrator.py      # 导出流程编排中枢
├── fs_layout.py         # 目录/命名/索引/报告
├── auth/                # 浏览器登录态 + 官方 API 认证
├── source/              # 统一数据源接口 + 两种引擎
├── parser/              # URL 解析 + block 解析器
├── exporter/            # Markdown/HTML/txt + 图片 + 表格
├── ui/                  # tkinter 界面
├── build/               # 本地打包脚本 + PyInstaller spec
└── .github/workflows/   # 云端自动构建
```

## 九、设计要点

1. **借浏览器之手绕过前端防护**：curl 直连跳登录页，改用持久化 cookie 的浏览器读取 `window.DATA.clientVars.data` 的结构化 block 数据（非 OCR），精确还原文字/代码/表格。
2. **统一数据源接口**：`list_nodes()` / `get_document()` 抽象，浏览器与 API 两种引擎可切换，上层零改动。
3. **内容完整优先于格式完美**：图片下载失败保留原 URL 兜底，单篇失败不影响整批。
4. **可复现配置**：所有策略存 `config.json`，一次配置长期复用。
