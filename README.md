<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Aura Player 图标" width="128">
</p>

<h1 align="center"><strong>Aura Player</strong></h1>

<p align="center">基于 MIUIX 与 Backdrop 的开源 Android 本地与网易云音乐播放器。</p>

<p align="center">
  <strong>简体中文</strong> |
  <a href="README_en.md">English</a> |
  <a href="README_ja.md">日本語</a>
</p>

<p align="center">
  <a href="https://github.com/ColdP/AuraPlayer/releases">发布版本</a> |
  <a href="https://t.me/HyperChanger">Telegram 群组</a> |
  <a href="https://qm.qq.com/q/rQhbQbiYLe">QQ 群：429188055</a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/v/release/ColdP/AuraPlayer?style=flat-square" alt="发布版本">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="平台">
  <img src="https://img.shields.io/badge/minSdk-33-blue?style=flat-square" alt="最低 SDK">
  <img src="https://img.shields.io/badge/targetSdk-37-blue?style=flat-square" alt="目标 SDK">
  <img src="https://img.shields.io/badge/License-AGPL--3.0--or--later-yellow?style=flat-square" alt="许可证">
</p>

## 项目简介

Aura Player（包名 `btm.m.auraplayer`）是一款面向 Android 13（API 33）及以上的音乐播放器，结合 MIUIX 界面组件、Backdrop 玻璃材质与 Media3 音频引擎，同时支持纯离线本地播放与可选的网易云音乐在线服务。

当前源码版本以 [app/build.gradle.kts](app/build.gradle.kts) 中的 `versionName` 为准，README 不重复维护版本号。下载与更新说明请查看 [Releases](https://github.com/ColdP/AuraPlayer/releases)。

## 功能

### 本地媒体库与首页

- MediaStore 本地音乐扫描与权限管理，支持歌曲、本地歌单、专辑、艺术家分类浏览，以及收藏与播放历史。
- 适配 Retro Music 首页结构：欢迎区、快捷入口、最近添加、推荐封面拼图、热门与最近艺术家及专辑。
- 支持文件夹黑白名单、最小时长过滤、持久化排序、下拉刷新与分类搜索。
- 采用自适应响应式布局：紧凑歌曲行与 `44dp` 封面；横屏及大屏设备自动增加列数并保留系统字体缩放。

### 网易云音乐与缓存下载

- **官方直连登录**：默认通过官方 HTTPS 页面（`music.163.com`）登录，无需部署后端或手动复制 Cookie；会话凭证使用 Android Keystore 加密存储，退出登录或更换服务地址时自动清除。
- **可选自建 API**：支持在“高级连接设置”中接入自建 [NeteaseCloudMusicApiEnhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced) 服务（含短信验证码登录）；`server/` 目录提供可直接启动的 Node.js 与 Docker Compose 配置。
- **云端浏览与详情**：支持云端推荐、歌单（每页 30 个）、曲目（每页 50 首）、搜索（综合 / 单曲 / 声音 / 播客）、专辑与艺术家详情及音质切换。
- **下载与解码**：通过系统下载器按所选音质下载，WorkManager 在后台完成 NCM 解码及 FLAC / MP3 标签与封面写入，保存至 `Music/Aura Player`（实际可下载音质以账号权限与网易云返回为准）。
- **缓存管理**：封面缓存上限 `128 MiB`，元数据缓存上限 `16 MiB`，支持在“设置 → 网易云音乐 → 缓存”中分类查看与清理。

### 播放界面、歌词与歌词分享

- **Media3 播放与手势**：支持后台播放与媒体通知、0.5–3 倍速、定时关闭、音量控制、拖动排序队列、左右滑切歌、迷你播放器与全屏连续展开/下滑收起及预测性返回。
- **播放器外观**：支持极光、模糊极光、模糊、单色与全屏主题，提供雪/雨氛围特效、多种切歌封面动效（卡片、封面、翻页、堆叠、垂直/水平翻转）、歌词常亮与沉浸式自动隐藏控件。
- **多源歌词**：集成网易云、QQ 音乐、酷我、酷狗、LrcLib、AMLL 与 TTML Hub 歌词；支持逐字、翻译、发音、对唱、边缘渐隐模糊、点击跳转与浏览焦点 3 秒自动回位。
- **歌词分享工作室**：内置多款模板（Aura Mesh、Cinema、Paper、Polaroid、Vinyl、Swiss 等），可自定义明暗模式、字体、背景、滤镜、行距与圆角并导出保存或分享图片。

### 音效引擎与标签编辑

- **内置 DSP 音效**：提供 15 个内置音效预设（原音忠实、温暖、通透、低音等）及自定义参数调节（多段均衡器、前级增益、自动增益保护、立体声宽度、低域交叉馈送、谐波染色与动态压缩），支持音频淡入淡出及“始终保持播放”。
- **预设分享**：支持通过 JSON 文件、JSON 文本或 ZXing 二维码导入、导出与分享音效预设。
- **本地标签编辑器**：基于 `jaudiotagger`，支持编辑歌曲标题、艺术家、专辑、专辑艺术家、发行日期、年份、音轨号、作曲、作词、注释与内嵌封面（写入前需通过系统文件写入授权）。

### 外观定制与多语言

- 基于 MIUIX 0.9.4 与 Backdrop 构建：支持浅色、深色、跟随系统、Monet 动态取色（经典 2021 / 表现力 2025 规范、多种调色板及自定义颜色）。
- 支持 HyperOS 悬浮、HarmonyOS 悬浮与液态玻璃底部导航样式，并可自定义模糊、不透明度、折射、高光、色散等材质参数。
- 内置简体中文、English、日本語三语界面及完整多语言法律文档（用户协议、免责声明、隐私政策），可参考 [languages/example.json](languages/example.json) 扩展翻译。

## 兼容性与要求

| 项目 | 说明 |
| --- | --- |
| 最低版本 | Android 13（API 33） |
| 编译 / 目标 SDK | API 37 |
| 权限用途 | `READ_MEDIA_AUDIO`（本地库与标签）、`INTERNET`（在线播放与歌词）、`MODIFY_AUDIO_SETTINGS`（音效）、`POST_NOTIFICATIONS` 与前台媒体服务（通知与后台播放） |

## 从源码构建

环境要求：JDK 21（与 CI 一致，代码目标 Java 17+）、Android SDK 37，以及支持项目 Gradle Wrapper（9.6.1）的 Android Studio 或命令行环境。在 `local.properties` 配置 `sdk.dir` 后运行：

```bash
git clone https://github.com/ColdP/AuraPlayer.git
cd AuraPlayer
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Debug APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。CI 构建流程可参考 [.github/workflows/android.yml](.github/workflows/android.yml)。

## 可选的自建网易云 API

如需使用自建接口，可使用 [NeteaseCloudMusicApiEnhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)。仓库 [server/](server) 目录已固定依赖版本并提供启动入口（Node.js 22+）：

```powershell
cd server
npm ci
npm start
```

默认端口为 `3000`（可通过 `PORT` 环境变量修改），亦可在 `server/` 中运行 `docker compose up -d`。随后在应用“设置 → 网易云音乐 → 高级连接设置”填写局域网或 HTTPS 服务地址即可。

## 目录结构

```text
Aura-Player/
|-- app/
|   |-- src/main/java/       Kotlin / Java 应用、播放器、歌词、音效与界面源码
|   |-- src/main/assets/     多语言包、中英日法律文档、开源许可证与声明
|   |-- src/main/res/        Android 资源与自适应图标
|   `-- src/test/java/       本地单元测试
|-- languages/example.json   语言包模板
|-- server/                  可选 NeteaseCloudMusicApiEnhanced 启动与 Docker 配置
|-- tools/                   本地化校验辅助脚本
|-- gradle/                  Gradle Wrapper
|-- README.md                中文主版
|-- README_en.md             英文版
|-- README_ja.md             日文版
|-- CONTRIBUTING_zh.md       中文贡献指南
|-- CONTRIBUTING.md          英文贡献指南
|-- CONTRIBUTING_ja.md       日文贡献指南
|-- NOTICE.md                上游来源与许可说明
`-- LICENSE
```

## 贡献

欢迎提交 Issue 与 Pull Request。参与前请阅读 [中文贡献指南](CONTRIBUTING_zh.md)、[English contributing guide](CONTRIBUTING.md) 或 [日本語コントリビュートガイド](CONTRIBUTING_ja.md)。

## 许可证与致谢

本项目整体采用 [GNU AGPL-3.0-or-later](LICENSE) 开源分发。部分移植模块与第三方依赖保留各自原有协议，完整来源、版权归属与修改说明见 [NOTICE.md](NOTICE.md)：

- 界面风格、玻璃组件、导航动画及关于/捐赠页面布局来源：[HyperChanger](https://github.com/ColdP/HyperChanger)（AGPL-3.0-or-later）。
- 首页结构与 MediaStore 排序逻辑适配自 [Retro Music](https://github.com/RetroMusicPlayer/RetroMusicPlayer)（GPL-3.0-or-later）。
- 控件与模糊基础基于 [MIUIX 0.9.4](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）、[Backdrop / Shapes](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0），歌单标题栏搜索组件使用 [COUI-Kit](https://github.com/ItosEO/COUI-Kit)（MPL-2.0）。
- 歌词解析、匹配与渲染来源：[HyperMusicCover](https://github.com/zyl6932/HyperMusicCover)（AGPL-3.0）、[HyperLyrics Enhanced](https://github.com/juren233/HyperLyrics-Enhanced)（GPL-3.0 / Apache-2.0）与 [Accompanist Lyrics Core](https://github.com/6xingyv/Accompanist-Lyrics)（Apache-2.0）。
- 其他核心依赖：[Media3](https://developer.android.com/media/media3)、[Coil](https://github.com/coil-kt/coil)、[OkHttp](https://github.com/square/okhttp)、[jaudiotagger](https://github.com/Adonai/jaudiotagger-android)（LGPL-2.1）、[MaterialKolor](https://github.com/jordond/MaterialKolor)（MIT）、[ZXing](https://github.com/zxing/zxing)（Apache-2.0）及 [NeteaseCloudMusicApiEnhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)（MIT）。

## Star History

<a href="https://www.star-history.com/?repos=ColdP%2FAuraPlayer&type=date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=ColdP/AuraPlayer&type=date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=ColdP/AuraPlayer&type=date" />
    <img alt="Star History 图表" src="https://api.star-history.com/chart?repos=ColdP/AuraPlayer&type=date" />
  </picture>
</a>
