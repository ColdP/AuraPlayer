# Aura Player 贡献指南

<p>
  <strong>简体中文</strong> |
  <a href="CONTRIBUTING.md">English</a> |
  <a href="CONTRIBUTING_ja.md">日本語</a>
</p>

Aura Player 的贡献内容遵循项目开源许可证 [GNU AGPL-3.0-or-later](LICENSE)。提交贡献即表示你同意你的改动依照 [LICENSE](LICENSE) 中的条款分发；第三方组件与上游致敬说明见 [NOTICE.md](NOTICE.md)。

感谢你为 Aura Player 做出贡献。请遵循以下约定，以便维护者能够高效审查并保持发布质量。

## 开始前

1. 在 GitHub 上 Fork 本仓库。
2. 将你的 Fork 克隆到本地：

   ```bash
   git clone https://github.com/YOUR_USERNAME/AuraPlayer.git
   cd AuraPlayer
   ```

3. 为一个独立改动创建分支：

   ```bash
   git checkout -b feat/your-feature-name
   ```

## 开发环境

- Android Studio Narwhal（2025.1）或更高版本
- JDK 21（与 CI 一致；最低要求 JDK 17+）
- Android SDK 37
- Android 13（API 33）及以上测试真机或模拟器

在 Android Studio 中打开项目，等待 Gradle 同步完成，然后执行以下命令验证改动：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

## 修改规范

- 每个提交只包含一个逻辑明确的改动。
- 使用简洁的英文提交信息，例如：

  ```text
  feat: add lyric share template option
  fix: avoid media notification flicker on pause
  docs: update trilingual README and legal notices
  ```

- 遵循项目已有的 Kotlin 与 Java 代码风格；项目使用 Kotlin 官方代码风格（`kotlin.code.style=official`）。
- 若涉及界面文本改动，请同步更新简体中文默认文本、`app/src/main/assets/languages/en.json`、`app/src/main/assets/languages/ja.json` 及 `languages/example.json`。
- 除非改动明确更新支持范围，否则请保持与 `minSdk = 33` 的兼容性。
- 不要在 Pull Request 中提交 APK、签名密钥（keystore）、生成的构建目录、个人音频文件、账号 Cookie 或日志导出包。

## 提交 Pull Request

1. 在本地完成构建并确保单元测试通过，必要时在真机上验证播放、歌词、音效或界面表现。
2. 将分支推送到你的 Fork：

   ```bash
   git push origin feat/your-feature-name
   ```

3. 向本仓库的 `main` 分支发起 Pull Request。
4. 说明改动内容、测试方式和兼容性影响；界面调整请附上浅色/深色模式截图。

对于较大的架构调整、新增网络数据源或存储格式变更，建议先创建 Issue 讨论方案。

## 反馈问题

提交 Bug 报告时，请尽量提供：

- 设备型号、Android 版本与系统定制版本（如 HyperOS 版本）
- Aura Player 版本号（或提交哈希）
- 复现步骤（本地音乐 / 官方网易云直连 / 自建 API 等场景说明）
- 预期行为与实际行为
- 已移除账号 Cookie、私有服务地址与个人文件路径等敏感信息的 Logcat 输出或截图

## 行为准则

请保持尊重、建设性且聚焦项目的沟通方式。
