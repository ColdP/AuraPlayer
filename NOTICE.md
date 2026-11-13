# Source acknowledgements

- HyperChanger — btm_m and contributors; AGPL-3.0-or-later. Navigation renderers, glass dropdowns/dialogs/search, title bars, motion, theme picker, About/Donate layouts and associated original assets are ported from the user-provided source checkout. Host injection and Xposed settings transport are excluded.
- Retro Music — Hemanth Savarala and contributors; GPL-3.0-or-later. `RetroHomePage.kt` and `HomeLibrary.kt` adapt HomeFragment/HomeAdapter, default home XML geometry, Repository, LastAddedRepository and TopPlayedRepository to MIUIX Compose. MediaLibrary follows its merged MediaStore collection and in-memory locale sorting approach.
- MIUIX — Yukonga and contributors; Apache-2.0. UI, preference components, extended icons and progressive blur. The files in `ui/cascade/` adapt WindowIconCascadingDropdownMenu, CascadingListPopupLayout, CascadingMorphContent, MorphHeaderRow and the popup reveal mask from MIUIX; changes replace popup hosting and surfaces with the HyperChanger Backdrop recipe.
- Backdrop / Shapes — Kyant and contributors; Apache-2.0. Glass rendering and shapes.
- NeteaseCloudMusicApiEnhanced — MIT. `OfficialNeteaseClient.kt` ports request field mapping and WEAPI/EAPI encryption from module files and util/crypto.js to Kotlin/JCA. The Node service remains an optional separate deployment. The original MIT license is included in `app/src/main/assets/licenses/NeteaseCloudMusicApiEnhanced-MIT.txt`.
- AndroidX Media3, Compose, Coil, OkHttp and other runtime dependencies retain their respective upstream notices and licenses.
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

Player appearance additions (2026-11-12): PlayerLyricView adds visual-center focus while browsing and smooth sharp/depth-mask blending to the HyperChanger/HyperMusicCover-derived renderer (AGPL-3.0). Lyrics render inline in SDR with the original held-note animation; the separate HDR panel and extended-range output have been removed. Existing upstream notices and license texts remain applicable.
