# Contributing to Aura Player

<p>
  <a href="CONTRIBUTING_zh.md">简体中文</a> |
  <strong>English</strong> |
  <a href="CONTRIBUTING_ja.md">日本語</a>
</p>

Contributions to Aura Player are accepted under the [GNU AGPL-3.0-or-later](LICENSE) license. By submitting a contribution, you agree that it is licensed under the terms in [LICENSE](LICENSE); upstream sources and third-party attributions are documented in [NOTICE.md](NOTICE.md).

Thank you for contributing to Aura Player. These guidelines keep changes reviewable and releases dependable.

## Getting Started

1. Fork the repository on GitHub.
2. Clone your fork locally:

   ```bash
   git clone https://github.com/YOUR_USERNAME/AuraPlayer.git
   cd AuraPlayer
   ```

3. Create a branch for one focused change:

   ```bash
   git checkout -b feat/your-feature-name
   ```

## Development Setup

- Android Studio Narwhal (2025.1) or later
- JDK 21 (matching CI; minimum JDK 17+)
- Android SDK 37
- A test device or emulator running Android 13 (API 33) or later

Open the project in Android Studio, allow Gradle sync to complete, and verify your change with:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

## Making Changes

- Keep each commit focused on one logical change.
- Use concise English commit messages, for example:

  ```text
  feat: add lyric share template option
  fix: avoid media notification flicker on pause
  docs: update trilingual README and legal notices
  ```

- Follow the existing Kotlin and Java style. The project uses the Kotlin official code style (`kotlin.code.style=official`).
- When adding or editing UI strings, keep Simplified Chinese defaults, `app/src/main/assets/languages/en.json`, `app/src/main/assets/languages/ja.json`, and `languages/example.json` synchronized.
- Preserve compatibility with `minSdk = 33` unless the change explicitly updates support requirements.
- Do not include APKs, keystores, generated build directories, personal audio files, session cookies, or private device logs in a pull request.

## Submitting a Pull Request

1. Build and run unit tests locally, and verify playback, lyrics, DSP, or UI changes on a device where applicable.
2. Push the branch to your fork:

   ```bash
   git push origin feat/your-feature-name
   ```

3. Open a pull request against this repository's `main` branch.
4. Describe what changed, how it was tested, and any compatibility impact. Include light/dark mode screenshots for UI changes.

Open an issue before implementing large architectural changes, new network sources, or breaking storage format changes so the approach can be discussed first.

## Reporting Issues

Include the following in a bug report:

- Device model, Android version, and OEM OS version (such as HyperOS version)
- Aura Player version (or commit hash)
- Clear reproduction steps (specify local playback, official NetEase login, or custom API setup)
- Expected and actual behavior
- Relevant Logcat output or screenshots with sensitive data (session cookies, private server URLs, personal file paths) removed

## Code of Conduct

Keep discussion respectful, constructive, and focused on the project.
