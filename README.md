# Aura Player

基于 MIUIX + Backdrop 的 Android 音乐播放器，包名 `btm.m.auraplayer`，最低 Android 13。

## 当前功能

- MediaStore 本地音乐扫描、音乐权限、歌曲／歌单／专辑／艺术家、收藏与播放历史。
- Retro Music 默认首页结构：欢迎区、四个快捷入口、8 张封面的推荐拼图、热门／最近艺术家与专辑、收藏。
- Media3 本地与网易云播放、后台播放和媒体通知。
- 网易云官方网页登录，无需配置 API；支持登录状态恢复、歌单、搜索与播放，Cookie 使用 Android Keystore 加密。自建 API 与短信登录仍可在高级设置中使用。
- HyperChanger 的三种浮动底栏、独立搜索按钮、标题栏、页面／Tab 动画、玻璃下拉菜单和对话框。
- MIUIX 主题颜色、黑白名单、时长过滤、简体中文／English；歌曲 More 玻璃级联菜单和持久化排序。

- 随底栏材质与宽度变化的迷你播放器；连续拉宽、拉高、模糊淡入的全屏展开、下滑收起与预测性返回。
- 大封面与歌词页共用封面、标题与歌手信息，通过连续位移、缩放切换并可反向返回。
- HyperChanger / HyperMusicCover 的网易云、QQ、酷我、酷狗、LrcLib、AMLL、TTML Hub 歌词；逐字、翻译、发音、对唱，渐变模糊边缘与歌词点击定位。
- 浏览歌词时显示选中行的圆角背景和右侧时间，停止操作 3 秒后淡出并回到播放行。歌词区域保留独立滚动，歌词模式从顶部下滑收起播放器。
- 封面 Monet 取色与贡献者页面流光背景；网易云实际音质列表与切换，本地格式／采样率／位深／码率只读展示，音量、循环、收藏和播放器内队列页（支持拖动排序，底部切换播放模式）。
- 播放器按钮图标提取自用户指定的 Apple Music 7.0.0-beta 反编译资源，本地提取清单保存在被 Git 忽略的 `docs/` 目录。

## 自适应尺寸

采用 Retro Music 的 dp/sp 尺寸与 smallest width／方向分档：紧凑歌曲行、44dp 封面；横屏和大屏增加列数；卡片、偏好设置和菜单使用统一的 MIUIX 紧凑文字与间距，保留系统字体缩放。

## 构建

安装 Android SDK 37 和 JDK 17 或更新版本，在 `local.properties` 配置 `sdk.dir`：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 网易云登录

默认流程为“设置 → 网易云音乐登录 → 网易云音乐官方登录”，直接在官方 HTTPS 页面完成登录。应用读取会话后验证账号并加载歌单，用户无需部署服务、填写 API 地址或复制 Cookie。账号登录凭据只在官方页面输入，未安装 JavaScript 桥。

默认请求直接发送至网易云官方接口，协议和加密参考 NeteaseCloudMusicApiEnhanced。退出账号会清除本地加密会话及登录 WebView Cookie。

## 可选的自建网易云 API

使用开源 [NeteaseCloudMusicApiEnhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)，接口文档见 [项目站点](https://neteasecloudmusicapienhanced.js.org/)。`server` 固定依赖版本并提供启动入口。

使用 Node.js 22 或更新版本：

```powershell
cd server
npm ci
npm start
```

默认端口为 `3000`，可使用 `PORT` 环境变量调整；也可在 `server` 中运行 `docker compose up -d`。

在应用“设置 → 网易云音乐登录 → 高级连接设置”填写 API 地址，保存后输入手机号与验证码。手机访问本机部署服务时，填写电脑可达的局域网 IP，例如 `http://192.168.1.10:3000/`。生产服务使用 HTTPS。应用支持带路径前缀的 API 地址。

已接入验证码、手机号登录、登录状态、分页歌单、歌单歌曲、搜索与播放地址接口。实际账号、会员与地区权限以网易云返回结果为准。

## 验证范围

本地单元测试覆盖官方请求加密（使用上游固定向量比对）、Cookie 携带、API 请求协议与分页、登录过期、地址校验、文件夹与时长过滤、多存储卷身份、首页分组及歌曲排序；歌词翻译／发音配对、逐字时间轴、双声部焦点、弹簧收敛、音质列表与实际降级结果、FLAC 位深。

读取已连接手机的 MediaStore 时，复现了原排序表达式 `COLLATE LOCALIZED` 被拒绝的问题；新的外部合并集合查询成功返回 289 条记录。应用采用该查询后用 `Collator` 在后台排序。

已验证匿名网易云搜索、播放地址、歌曲音质详情和歌词端点响应。尚未使用真实账号发送验证码、登录或在设备安装当前 APK；完整视觉效果与播放体验仍需真机验证。

## 来源与许可

界面、玻璃组件及动画来源：[HyperChanger](https://github.com/ColdP/HyperChanger)。首页结构与数据排序来源：[Retro Music](https://github.com/RetroMusicPlayer/RetroMusicPlayer)。所有可见控件使用 MIUIX。

本项目使用 AGPL-3.0-or-later。Retro Music 移植部分保留 GPL-3.0-or-later 标识；第三方依赖遵循各自许可。详见 `LICENSE` 和 `NOTICE.md`。
