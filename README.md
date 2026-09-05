# Apktool Studio

基于 Apktool 3.0.3 的本地 APK 解码与回编工作台。项目提供原始 Apktool CLI，并增加了一个只监听本机回环地址的 Web 界面，方便上传 APK、查看解码项目文件与回编结果。

> 仅应处理你拥有、获授权或法律允许分析的 APK。请勿将第三方 APK、解码结果或签名材料提交到仓库。

## 功能

- 使用 Apktool CLI 解码和回编 APK。
- 本地 Web 工作台：上传、任务日志、项目文件浏览与构建输出目录设置。
- DEX 工作台：类列表检索、Java/Smali 双模式预览、Smali 编辑 -> 内存汇编 -> 实时重反编译（内嵌 jadx 引擎），支持导出补丁 DEX / 重打包 APK（未签名）。
- 可切换内置配色，也可在设置页保存自定义配色。
- 使用 WebGL 液态玻璃效果；不支持 WebGL 的浏览器自动降级为普通玻璃样式。
- 工作文件保存至 `web-work/`，默认已由 Git 忽略。

## 本轮 Web 工作台更新

- DEX 工作台上线：在项目文件页点击 `original-dex/classes*.dex` 即可进入。
- 左侧类列表支持按类名即时检索；右侧提供 Java 反编译预览与 Smali 反汇编双模式。
- Smali 模式为左编辑右预览分栏：编辑后 Ctrl+S 应用补丁，或勾选"实时预览"输入停顿约 900ms 自动应用。
- 补丁流程由内嵌 jadx 引擎完成：smali 内存汇编 dex -> 替换类数据 -> 实时重新反编译，无需重启或重新解码。
- 汇编错误显示原始错误信息，并自动把光标定位到第一个出错行。
- 支持导出补丁 DEX（DexPool 按原始文件名重建，无效假 dex 原样保留）与重打包 APK（未签名）。
- 支持多个项目标签页，点击项目切换且不会重复创建；点击项目内部文件不会新增项目标签。
- 项目文件页采用独立的左右双卡片布局：左侧项目目录，右侧文件预览，卡片高度固定为 730px。
- 支持 XML、Smali、YML、JSON、TXT 等文本文件预览，以及 PNG、JPG、GIF、WebP 图片预览。
- 文件预览接口支持最大 20MB 文件，并进行路径安全校验。

## DEX / Multidex 预览规划

已实现：基于修改版 jadx-core（新增 `ClassNode.reloadClassData`）+ smali 3.0.9 的 DEX 结构化查看、Smali 编辑与实时预览，以及补丁类写回原始 dex / 重打包 APK。

## 环境要求

- Windows 10 或更新版本
- Java 11 或更新版本（推荐使用较新的 LTS JDK；DEX 工作台引擎要求 Java 11+）
- Python 3（用于提供本地 Web 静态文件）

### DEX 工作台引擎依赖

后端通过 `apktool-web/libs/jadx/` 下的 4 个 jar 内嵌 jadx 引擎（该目录不入库）。首次使用前，从本地的 jadx-master 构建并拷贝：

```bat
cd jadx-master 项目目录
gradlew :jadx-core:jar :jadx-plugins:jadx-input-api:jar :jadx-commons:jadx-zip:jar :jadx-plugins:jadx-dex-input:jar
copy jadx-core\build\libs\jadx-core-*.jar           Apktool项目\apktool-web\libs\jadx\
copy jadx-plugins\jadx-input-api\build\libs\*.jar   Apktool项目\apktool-web\libs\jadx\
copy jadx-commons\jadx-zip\build\libs\*.jar         Apktool项目\apktool-web\libs\jadx\
copy jadx-plugins\jadx-dex-input\build\libs\*.jar   Apktool项目\apktool-web\libs\jadx\
```

其余依赖（smali 3.0.9、gson、slf4j）由 Gradle 从 Maven 自动解析。

## 快速开始

双击根目录的 `start-apktool-studio.bat`。脚本会启动：

- Web 界面：<http://127.0.0.1:5173/>
- Java API：<http://127.0.0.1:8787/api/health>

首次启动会由 Gradle 编译相关模块，耗时会略长。看到 `Apktool Web API listening` 后即可在浏览器中使用。

### 启动与停止脚本

| 脚本 | 用途 |
| --- | --- |
| `start-apktool-studio.bat` | 同时启动 Java API 与 Web 界面。 |
| `start-apktool-studio-api.bat` | 仅启动 Java API，便于排查后端问题。 |
| `start-apktool-studio-web.bat` | 仅启动 Web 静态服务，便于调整界面。 |
| `stop-apktool-studio.bat` | 停止监听 8787、5173 端口的 Apktool Studio 服务。 |

启动器会校验 JDK、Python 与端口占用，并将启动事件写入 `logs/launcher.log`。若端口已被占用，请先关闭旧窗口，或运行 `stop-apktool-studio.bat` 后再启动。

### 常见启动问题

- `Java was not found`：安装 JDK 并将 `java`、`javac` 加入 `PATH`。仅安装 JRE 无法编译 Java API。
- `Python was not found`：安装 Python 3，并在安装时勾选加入 `PATH`。
- `port 8787/5173 is already in use`：已有服务仍在运行；先执行停止脚本，或结束提示中的进程。
- Java 8 的 source/target 过时警告不会阻止当前构建；后续建议迁移到受支持的 LTS JDK。

## 命令行使用

构建 CLI：

```bat
gradlew.bat :brut.apktool:apktool-cli:build
```

解码 APK：

```bat
scripts\windows\apktool.bat d your-app.apk
```

## 项目结构

- `brut.apktool/`：Apktool CLI 与核心库。
- `apktool-web/`：本地 Java API。
- `web/`：静态 Web 界面和本地液态玻璃资源。
- `web-work/`：上传、解码与回编工作目录（不提交）。

## 开源许可

本项目沿用 [Apache License 2.0](LICENSE.md)。`web/prism-glass/liquidGL.js` 采用 MIT 许可，许可证位于同目录的 `liquidGL-LICENSE.txt`。
