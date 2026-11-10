# Aura Player

基于 MIUIX + Backdrop 的 Android 音乐播放器，包名 `btm.m.auraplayer`，最低 Android 13。

## 当前功能

- MediaStore 本地音乐扫描、音乐权限、歌曲／歌单／专辑／艺术家、收藏与播放历史。
- Retro Music 默认首页结构：欢迎区、四个快捷入口、8 张封面的推荐拼图、热门／最近艺术家与专辑、收藏。
- Media3 本地与网易云播放、后台播放和媒体通知。
- 网易云手机号验证码登录、登录状态恢复、歌单与搜索；Cookie 使用 Android Keystore 加密。
- HyperChanger 的三种浮动底栏、独立搜索按钮、标题栏、页面／Tab 动画、玻璃下拉菜单和对话框。
- MIUIX 主题颜色、黑白名单、时长过滤、简体中文／English。

完整正在播放界面和悬浮迷你播放器留待后续实现。

## 构建

安装 Android SDK 37 和 JDK 17 或更新版本，在 `local.properties` 配置 `sdk.dir`：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 网易云 API

使用开源 [NeteaseCloudMusicApiEnhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)，接口文档见 [项目站点](https://neteasecloudmusicapienhanced.js.org/)。`server` 固定依赖版本并提供启动入口。

使用 Node.js 22 或更新版本：

```powershell
cd server
npm ci
npm start
```

默认端口为 `3000`，可使用 `PORT` 环境变量调整；也可在 `server` 中运行 `docker compose up -d`。

在应用“设置 → 网易云音乐登录”填写 API 地址，保存后输入手机号与验证码。手机访问本机部署服务时，填写电脑可达的局域网 IP，例如 `http://192.168.1.10:3000/`。生产服务使用 HTTPS。应用支持带路径前缀的 API 地址。

已接入验证码、手机号登录、登录状态、分页歌单、歌单歌曲、搜索与播放地址接口。实际账号、会员与地区权限以网易云返回结果为准。

## 验证范围

本地单元测试覆盖 API 请求协议、分页、登录过期、地址校验、文件夹与时长过滤、多存储卷身份，以及首页分组。

读取已连接手机的 MediaStore 时，复现了原排序表达式 `COLLATE LOCALIZED` 被拒绝的问题；新的外部合并集合查询成功返回 289 条记录。应用采用该查询后用 `Collator` 在后台排序。

已验证匿名网易云搜索与播放地址响应。尚未使用真实账号发送验证码、登录或在设备安装当前 APK；完整视觉效果与播放体验仍需真机验证。

## 来源与许可

界面、玻璃组件及动画来源：[HyperChanger](https://github.com/ColdP/HyperChanger)。首页结构与数据排序来源：[Retro Music](https://github.com/RetroMusicPlayer/RetroMusicPlayer)。所有可见控件使用 MIUIX。

本项目使用 AGPL-3.0-or-later。Retro Music 移植部分保留 GPL-3.0-or-later 标识；第三方依赖遵循各自许可。详见 `LICENSE` 和 `NOTICE.md`。
