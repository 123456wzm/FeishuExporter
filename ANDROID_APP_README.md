# 飞书导出器 · Android 独立 APP

完全在手机内运行的飞书知识库导出工具——**不需要电脑、不需要服务器**。

---

## 一、它是什么

这是桌面版 Feishu Exporter 的 Android 独立版本。它把桌面版的抓取引擎完整移植到手机：

| 桌面版 | Android 独立版 |
|---|---|
| Playwright + Chrome | 内嵌 WebView（本身就是 Chromium） |
| Python 解析 block | Kotlin 移植的 BlockRenderer |
| Python 下载图片 + 打包 | OkHttp + ZipOutputStream |
| Windows EXE | Android APK |

三者共享同一套飞书数据模型与解析算法，产出的 Markdown 与图片目录结构一致。

---

## 二、工作原理

```
飞书知识库
   │  （用户在 APP 内 WebView 登录一次）
   ▼
WebView 渲染页面 → JS 读取 window.DATA.clientVars.data（block 数据）
   │
   ├─ tree/get_info 接口遍历文档树
   ├─ 逐篇导航读取正文 block
   ├─ Kotlin BlockRenderer → Markdown
   ├─ OkHttp 带 cookie 下载图片到 data/
   └─ 打成 zip 保存到手机 Downloads
```

**关键点**：APP 内嵌的 WebView 既是浏览器（渲染飞书、维持登录），又是数据源（JS 读取飞书的内部数据对象）。登录一次后 cookie 全局共享，后续自动复用。

---

## 三、如何获取 APK

当前 Linux 环境无法直接编译 Android APP（缺 Android SDK），通过 GitHub Actions 云端构建：

1. 源码已提交到仓库的 `android-app/` 子目录；
2. **打 tag 触发构建**（推荐）：
   ```bash
   git tag app-v1.0
   git push origin app-v1.0
   ```
3. 或网页手动：仓库 → Actions → **Build Feishu Exporter Android APK** → Run workflow；
4. 构建完成后，到 **Releases** 或该次运行的 **Artifacts** 下载 `FeishuExporter-v1.0.apk`。

---

## 四、如何安装

1. 把 APK 传到手机（数据线 / 微信文件传输 / 网盘）；
2. 点击 APK 安装；
3. 系统提示"允许安装未知来源应用"时，选择允许（因未上架应用商店）；
4. 安装完成，桌面出现"飞书导出器"图标。

> APK 使用 debug 签名，可直接安装。若手机厂商 ROM 拦截，需在设置里额外允许。

---

## 五、如何使用

### 首次登录（必须）

1. 打开 APP，点右上角**登录**图标；
2. 在打开的 WebView 中登录你的飞书账号（账密 / 扫码 / 短信均可）；
3. 登录成功后点"登录完成后点此返回"。

> 登录态保存在 APP 内，后续无需重复登录。若提示"未登录"，重新执行此步。

### 导出知识库

1. 粘贴飞书链接（知识库 space 链接、单篇文档链接均可）；
2. 选择**图片保存策略**、**格式**、**目录模式**、**并发数**；
3. 点"开始导出"；
4. 观察进度条与日志，可随时取消；
5. 完成后点"打开 / 分享 zip"，把导出文件保存到手机或用其他应用打开。

### 图片保存策略

| 策略 | 说明 |
|---|---|
| 每篇文档独立 data/ 目录 | 默认，与桌面版一致，文档与图片文件夹同级 |
| 全局共享图片目录 | 所有图片放一个目录 |
| 内嵌 Base64 | 图片以 Base64 写进 Markdown（体积小、单文件） |
| 仅保留图片链接 | 不下载图片，Markdown 保留原始 URL |
| 不保存图片 | 跳过所有图片 |

---

## 六、已知限制与风险

| 项 | 说明 |
|---|---|
| **飞书内部对象依赖** | 读取 `window.DATA` 依赖飞书前端结构，飞书改版可能失效，需更新 APP |
| **大 JSON 回传** | 超大文档的 block 数据经 JS 桥接可能受限，真机如遇失败请反馈 |
| **WebView 内核差异** | 国产 ROM 的第三方 WebView 行为可能不一致，建议使用标准 WebView |
| **登录态时效** | cookie 过期需重新登录 |
| **未上架商店** | 需手动允许安装未知来源 |
| **首次构建需迭代** | Kotlin 代码未经本地编译（Linux 无 Android SDK），首次云端构建可能报编译错误，会据日志修复 |

---

## 七、源码结构

```
android-app/
├── settings.gradle.kts / build.gradle.kts   # Gradle 配置
├── gradlew / gradle/wrapper/                # Gradle 包装器
└── app/src/main/
    ├── AndroidManifest.xml                  # 权限声明
    └── java/com/feishuexporter/app/
        ├── MainActivity.kt                  # 界面入口 + 登录 WebView
        ├── ExportOrchestrator.kt            # 导出编排（串联各模块）
        ├── engine/FeishuWebView.kt          # 抓取引擎（核心）
        ├── parser/BlockRenderer.kt          # block → Markdown（移植）
        ├── export/ImageDownloader.kt        # 图片下载
        ├── export/ZipPacker.kt              # zip 打包保存
        ├── export/FsLayout.kt               # 目录布局
        ├── model/Models.kt                  # 数据模型
        └── ui/MainScreen.kt                 # Compose 界面
```

---

## 八、与桌面版如何选择

- **桌面 EXE 版**：稳定、已在真实知识库验证（52 篇 + 601 图），适合电脑前使用；
- **Android 独立版**：手机随时随地导出，但需真机验证、依赖飞书前端结构，适合移动场景。

两者独立运行，互不依赖，产出格式一致。
