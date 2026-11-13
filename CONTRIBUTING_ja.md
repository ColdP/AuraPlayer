# Aura Player コントリビュートガイド

<p>
  <a href="CONTRIBUTING_zh.md">简体中文</a> |
  <a href="CONTRIBUTING.md">English</a> |
  <strong>日本語</strong>
</p>

Aura Player への貢献は [GNU AGPL-3.0-or-later](LICENSE) ライセンスの下で受け付けます。コントリビューションを送信することにより、その変更が [LICENSE](LICENSE) の条件に従って提供されることに同意したものとみなされます。上流プロジェクトおよびサードパーティの著作権表記は [NOTICE.md](NOTICE.md) に記載しています。

Aura Player へのご協力ありがとうございます。円滑なレビューと安定したリリースのため、以下のガイドラインに沿ってお進めください。

## はじめに

1. GitHub で本リポジトリを Fork します。
2. Fork したリポジトリをローカルにクローンします：

   ```bash
   git clone https://github.com/YOUR_USERNAME/AuraPlayer.git
   cd AuraPlayer
   ```

3. 1 つの目的に絞った作業ブランチを作成します：

   ```bash
   git checkout -b feat/your-feature-name
   ```

## 開発環境

- Android Studio Narwhal（2025.1）以降
- JDK 21（CI と同じ；最小要件 JDK 17+）
- Android SDK 37
- Android 13（API 33）以降を搭載した検証用実機またはエミュレーター

Android Studio でプロジェクトを開き、Gradle 同期完了後に以下のコマンドでビルドとテストを確認してください：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

## 変更時のルール

- 1 つのコミットには、論理的にまとまった 1 つの変更のみを含めてください。
- コミットメッセージは簡潔な英語で記述してください（例）：

  ```text
  feat: add lyric share template option
  fix: avoid media notification flicker on pause
  docs: update trilingual README and legal notices
  ```

- 既存の Kotlin / Java スタイルに従ってください。本プロジェクトは Kotlin 公式コードスタイル（`kotlin.code.style=official`）を使用しています。
- UI 文字列を追加・変更する場合は、簡体字中国語の既定値、`app/src/main/assets/languages/en.json`、`app/src/main/assets/languages/ja.json`、および `languages/example.json` を同期して更新してください。
- サポート要件の更新を目的としない限り、`minSdk = 33` との互換性を維持してください。
- APK、キーストア、ビルド生成ディレクトリ、個人の音声ファイル、セッション Cookie、個人用ログを Pull Request に含めないでください。

## Pull Request の提出

1. ローカルでビルドと単体テストを実行し、必要に応じて実機で再生・歌詞・DSP・UI の動作を確認します。
2. ブランチを自分の Fork にプッシュします：

   ```bash
   git push origin feat/your-feature-name
   ```

3. 本リポジトリの `main` ブランチ宛てに Pull Request を作成します。
4. 変更内容、テスト方法、互換性への影響を明記してください。UI の変更にはライト／ダークモードのスクリーンショットを添付してください。

大規模な設計変更、新しいネットワーク取得元の追加、またはデータ形式の破壊的変更を行う場合は、事前に Issue を作成して方針をご相談ください。

## 不具合の報告

バグ報告には可能な限り以下の情報をご記載ください：

- 端末モデル、Android バージョン、メーカー独自 OS バージョン（HyperOS バージョンなど）
- Aura Player のバージョン（またはコミットハッシュ）
- 明確な再現手順（ローカル再生／公式 NetEase 接続／自前 API などの利用形態を含む）
- 期待される動作と実際の動作
- セッション Cookie、非公開サーバー URL、個人のファイルパスなどの機密情報を削除した Logcat 出力やスクリーンショット

## 行動規範

プロジェクトに焦点を当て、敬意を持った建設的な議論を心がけてください。
