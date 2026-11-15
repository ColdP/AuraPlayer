# Source acknowledgements

- HyperChanger — btm_m and contributors; AGPL-3.0-or-later. Navigation renderers, glass dropdowns/dialogs/search, title bars, motion, theme picker, About/Donate layouts and associated original assets are ported from the user-provided source checkout. Host injection and Xposed settings transport are excluded.
- Retro Music — Hemanth Savarala and contributors; GPL-3.0-or-later. `RetroHomePage.kt` and `HomeLibrary.kt` adapt HomeFragment/HomeAdapter, default home XML geometry, Repository, LastAddedRepository and TopPlayedRepository to MIUIX Compose. MediaLibrary follows its merged MediaStore collection and in-memory locale sorting approach.
- MIUIX — Yukonga and contributors; Apache-2.0. UI, preference components, extended icons and progressive blur. The files in `ui/cascade/` adapt WindowIconCascadingDropdownMenu, CascadingListPopupLayout, CascadingMorphContent, MorphHeaderRow and the popup reveal mask from MIUIX; changes replace popup hosting and surfaces with the HyperChanger Backdrop recipe.
- Backdrop / Shapes — Kyant and contributors; Apache-2.0 (`io.github.kyant0:backdrop:2.0.0`, `io.github.kyant0:shapes:1.2.0`). Glass rendering and squircle shapes.
- NeteaseCloudMusicApiEnhanced — MIT. `OfficialNeteaseClient.kt` ports request field mapping and WEAPI/EAPI encryption from module files and util/crypto.js to Kotlin/JCA. The Node service remains an optional separate deployment. The original MIT license is included in `app/src/main/assets/licenses/NeteaseCloudMusicApiEnhanced-MIT.txt`.
- AndroidX (Core, Activity Compose, Lifecycle, WorkManager), AndroidX Media3, JetBrains Compose Multiplatform, Coil, and OkHttp retain their respective upstream notices and licenses (Apache-2.0).
- jaudiotagger — LGPL-2.1, https://github.com/Adonai/jaudiotagger-android. Used for local audio tag editing; the complete license is included in `app/src/main/assets/licenses/jaudiotagger-LGPL-2.1.txt`.

The HyperChanger About/Donate assets and author references are retained at the user's explicit request.


HyperMusicCover — https://github.com/zyl6932/HyperMusicCover
Copyright (C) 2026 zyl6932, AGPL-3.0.
Imported via HyperChanger's 2026-10-30 snapshot: NcmLyrics, QqLyrics, KuwoLyrics,
WebLyrics, TtmlHub, QrcCrypt, TimedText, TrackName, LyricMatch, LyricParse,
LyricLine, LyricMotion, LyricFocus, LyricTransition, LyricWordRenderer,
LyricGlow and LyricGlowMask. Source hashes: docs/player-source-manifest.json.
Changes: Aura namespace; hook/thread/logging dependencies replaced by app
lifecycle adapters; current track IDs, coroutine cancellation and playback
position supplied by Media3. PlayerLyricView adapts the original row layout,
staggered springs, emphasis, cached depth blur and word rendering for a player
viewport, adding touch seeking and a continuous edge blur/fade shader.
Original notices and license texts are included:
licenses/HyperMusicCover-NOTICE.txt
licenses/HyperMusicCover-AGPL-3.0.txt
licenses/HyperMusicCover-GPL-3.0.txt

HyperLyrics Enhanced — https://github.com/juren233/HyperLyrics-Enhanced
Copyright 2026 juren233 and contributors.
GPL-3.0: lyric matching and QQ QRC decryption (combined under GPLv3/AGPLv3 s13).
Apache-2.0: Kuwo provider and LunaBeat TTML Hub client; headers retained.

Accompanist Lyrics Core 0.4.7 — https://github.com/6xingyv/Accompanist-Lyrics
Copyright its respective contributors, Apache-2.0; used unmodified as a dependency.
MaterialKolor 5.0.0 — https://github.com/jordond/MaterialKolor
Copyright Jordon de Hoog and contributors, MIT.
Material Color Utilities — Copyright Google / Material Foundation, Apache-2.0.
Monet seed quantization and ambient orbit adapted from HyperChanger's
ContributorsGallery.kt. No Material UI widgets are exposed.

Player icon artwork — extracted from user-provided Apple Music 7.0.0-beta.
Now-playing vectors are preserved under renamed ic_player_* resources.
Copyright Apple Inc. and original rights holders; these proprietary resources
are not relicensed under the application's AGPL license.
Extraction hashes are recorded locally in the Git-ignored docs/ directory.
The former NetEase player controls have been replaced.

- ZXing Core 3.5.3 — ZXing authors; Apache-2.0. Used unmodified for DSP preset QR images. License and upstream third-party notices: `app/src/main/assets/licenses/ZXing-Apache-2.0.txt`. DSP is independently implemented from documented formulas; research and parameter audit: `AUDIO_EFFECTS.md`.
- COUI-Kit 0.0.1-alpha9 — https://github.com/ItosEO/COUI-Kit; MPL-2.0. Used unmodified as a dependency (`io.github.itoseo:coui:0.0.1-alpha9`) for playlist title-bar search (`SearchBar`) and `CouiBackdrop` sampling. Full license: `app/src/main/assets/licenses/COUI-Kit-MPL-2.0.txt`; upstream third-party notices (`NOTICE`, `coui-skia-LICENSE`, `coui-androidx-LICENSE`) and trademark policy (`TRADEMARKS.md`) remain applicable in `META-INF/coui/` and the upstream repository.
- OneUI_Kit — https://github.com/TGP258/OneUI_Kit; MIT (`Copyright (c) 2026 The One UI Kit Authors`), originating from `OneUIProject/oneui-design` (https://github.com/OneUIProject/oneui-design) & `OneUIProject/oneui-core` (https://github.com/OneUIProject/oneui-core) (MIT, `Copyright (c) 2022 Yanndroid & BlackMesa123`). `MonetTones`, `OneUiPalette`, `BoSwitchView`, `DragOnlySeekBar`, and `OneUiWidgets` are imported under `dev.pascal.oneuikit` to power the `One` interface style, One UI navigation bar, and `One` Now Playing theme; `DragOnlySeekBar` extends standard `android.widget.SeekBar` (per `docs/components.md`) to avoid SESL/AndroidX classpath conflicts, with custom seed support and Compose state-sync helpers. Full MIT license & retained upstream notices: `app/src/main/assets/licenses/OneUI-Kit-MIT.txt`. One UI is a trademark of Samsung Electronics Co., Ltd.; this usage is unofficial and implies no affiliation or endorsement.

Player appearance additions (2026-11-12): PlayerLyricView adds visual-center focus while browsing and smooth sharp/depth-mask blending to the HyperChanger/HyperMusicCover-derived renderer (AGPL-3.0). Lyrics render inline in SDR with the original held-note animation; the separate HDR panel and extended-range output have been removed. Existing upstream notices and license texts remain applicable.

- HyperChanger Depth Wallpaper — https://github.com/ColdP/HyperChanger-Depth-Wallpaper; Copyright 2026 btm_m, AGPL-3.0-or-later. `app/src/main/java/btm/m/auraplayer/spatialcover/` (`SpatialCoverModels.kt`, `SpatialCoverPipeline.kt`, `SpatialCoverGeometry.kt`, `SpatialCoverCanvas.kt`, `SpatialRuntimeLoader.kt`) and `ui/SpatialCoverPages.kt` adapt the on-device depth estimation, matting mask refinement, background inpainting, foreground relief displacement, gyroscope parallax renderer, and on-demand resource downloader. Modifications: integrated into Aura Player's Now Playing chrome and album-scoped spatial cache (`album` + `albumArtist` key sharing with full-screen cache preview/management).
- HyperExtreme — https://github.com/22Frederic/HyperExtreme; AGPL-3.0. Referenced alongside HyperChanger Depth Wallpaper for dual-layer spatial parallax and relief rendering concepts.
- Apple Music Motion Cover — Adapted from HyperChanger's HyperMusicCover snapshot (`MotionArt.java`, `MotionCover.java`, Copyright (C) 2026 zyl6932 & btm_m, AGPL-3.0) in `data/AppleMusicMotionCover.kt` and `ui/player/AnimatedMotionCoverVideo.kt`. Changes: Kotlin coroutine fetcher using OkHttp and Media3 `ExoPlayer` + `TextureView` playback, album-scoped `.mp4` cache (`am_motion_cache`), and forced Immersive Cover activation when motion artwork is available.
- ONNX Runtime Android 1.31.0 — https://github.com/microsoft/onnxruntime; MIT (`Copyright (c) Microsoft Corporation`). Java bindings (`com.microsoft.onnxruntime:onnxruntime-android:1.31.0`) are used for on-device Spatial Cover inference; native `arm64-v8a` shared libraries (`libonnxruntime.so`, `libonnxruntime4j_jni.so`) are excluded from the APK and downloaded on demand with SHA-256 verification. Complete MIT license: `app/src/main/assets/licenses/ONNXRuntime-MIT.txt`.
- Optional Spatial Cover ONNX Models (not bundled in the APK; downloaded separately from https://coldp.github.io/hyperchanger-updateinfo/ on user request):
  - Depth Anything V2 Small (INT8) — https://github.com/DepthAnything/Depth-Anything-V2 (Apache-2.0)
  - Depth Anything 3 Base (INT8) — https://github.com/ByteDance-Seed/depth-anything-3 (Apache-2.0)
  - MiDaS v2.1 Small — https://github.com/isl-org/MiDaS (MIT)
  - U2Netp & ISNet / rembg — https://github.com/danielgatis/rembg (MIT; originating U^2-Net and DIS models under Apache-2.0)
  - BiRefNet Lite (INT8) — https://github.com/ZhengPeng7/BiRefNet (MIT)
- Shizuku 13.1.5 — https://github.com/RikkaApps/Shizuku; Apache-2.0. Used unmodified (`dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`) for optional secondary rear-display playback/lyrics launching. Full license: `app/src/main/assets/licenses/Apache-2.0.txt`.
- Aura Player 1.0.0 original components (`AGPL-3.0-or-later`, Copyright 2026 btm_m): Cover themes (`PlayerCoverflow.kt`, `PlayerTiledWall.kt`, Square/Coverflow/Tiled gyroscope tilt parallax and MIUIX highlight outlines), multi-account session manager and cookie inspector (`CloudAccountSession.kt`, `OfficialLoginActivity.kt`, `SwitchUserPage.kt`), AGSL lyric-theme shader backgrounds (`PlayerLyricThemeShaders.kt`), 3D Cel-Shaded Tokyo cityscape and City Walk mode (`PlayerTokyoCityPrimitives.kt`, `PlayerTokyoCityScene.kt`), global solid-color fallback (`disableAdvancedMaterial`), and audio metadata inspection.
