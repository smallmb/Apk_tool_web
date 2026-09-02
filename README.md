# Apktool Studio

基于 Apktool 3.0.3 的本地 APK 解码与回编工作台。项目提供原始 Apktool CLI，并增加了一个只监听本机回环地址的 Web 界面，方便上传 APK、查看解码项目文件与回编结果。

> 仅应处理你拥有、获授权或法律允许分析的 APK。请勿将第三方 APK、解码结果或签名材料提交到仓库。

## 功能

- 使用 Apktool CLI 解码和回编 APK。
- 本地 Web 工作台：上传、任务日志、项目文件浏览与构建输出目录设置。
- 可切换内置配色，也可在设置页保存自定义配色。
- 使用 WebGL 液态玻璃效果；不支持 WebGL 的浏览器自动降级为普通玻璃样式。
- 工作文件保存至 `web-work/`，默认已由 Git 忽略。

## 环境要求

- Windows 10 或更新版本
- Java 8 或更新版本（推荐使用较新的 LTS JDK）
- Python 3（用于提供本地 Web 静态文件）

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
