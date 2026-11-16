## 改动说明 / Summary

<!-- 请简要说明本次 Pull Request 解决的问题或新增的功能，若关联 Issue 请写明 Closes #<number> -->

## 验证方式 / Verification

- [ ] 已本地执行 `./gradlew :app:assembleDebug`（Windows PowerShell: `.\gradlew.bat :app:assembleDebug`）
- [ ] 已本地执行 `./gradlew :app:testDebugUnitTest` 且全部测试通过
- [ ] 保持对 `minSdk = 33` 的兼容性（未引入未做版本保护的高版本 API）
- [ ] 如涉及 UI 字符串，已同步更新简体中文默认文案、`app/src/main/assets/languages/en.json`、`app/src/main/assets/languages/ja.json` 及 `languages/example.json`
- [ ] 确认未提交任何 APK、签名密钥（keystore）、个人音频、账号 Cookie 或隐私日志

## 截图或录屏 / Screenshots (UI 改动必填)

<!-- 界面或主题类调整请附上浅色 / 深色模式对比截图 -->
