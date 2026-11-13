from pathlib import Path
import json, re

root = Path(__file__).resolve().parents[1]
translations = '''
home|Home|ホーム
internal_storage|Internal storage|内部ストレージ
storage_card|Storage card|ストレージカード
searching|Searching…|検索中…
playlists|Playlists|プレイリスト
songs|Songs|曲
albums_artists|Albums & artists|アルバムとアーティスト
settings|Settings|設定
theme_color|Theme & color|テーマと色
netease_login|NetEase Music login|NetEase Music ログイン
about|About|このアプリについて
donate|Donate|寄付
local|Local|ローカル
netease|NetEase Music|NetEase Music
load_more|Load more|もっと読み込む
notice|Notice|お知らせ
ok|OK|確認
unknown_title|Untitled song|無題の曲
good_morning|Good morning|おはようございます
good_afternoon|Good afternoon|こんにちは
good_evening|Good evening|こんばんは
your_music|Let the music move you.|音楽を、心のままに。
history|History|最近の再生
last_added|Last added|最近の追加
most_played|Most played|よく聴く曲
favorites|Favorites|お気に入り
suggestions|For you|おすすめ
shuffle_summary|A little surprise in familiar melodies|いつものメロディーに、少しの驚きを
shuffle_all|Shuffle all|すべてシャッフル
recently_added|Recently added|最近追加した曲
recent_albums|Recent albums|最近のアルバム
empty_library|Your library is empty|ライブラリは空です
empty_library_summary|Add music to your phone, then refresh the library|音楽を追加してライブラリを更新してください
song_count|songs|曲
permission_title|Let Aura find your music|Aura に音楽を探してもらう
permission_summary|Allow audio access to see your songs, albums and artists|音声へのアクセスを許可すると曲やアルバムが表示されます
grant_audio|Allow music access|音楽へのアクセスを許可
scanning|Scanning…|スキャン中…
shuffle|Shuffle|シャッフル
no_songs|No songs found|曲が見つかりません
check_filters|Check permissions, folders and library filters|権限、フォルダー、フィルターを確認してください
rescan|Scan again|再スキャン
no_collections|Nothing here yet|まだ何もありません
library_content_hint|Your local music will appear here automatically|ローカル音楽が自動的に表示されます
play_all|Play all|すべて再生
add|Add|追加
new_playlist|New playlist|プレイリストを作成
add_songs|Add songs|曲を追加
delete|Delete|削除
playlist_empty|Create your first playlist|最初のプレイリストを作成
playlist_empty_summary|Keep your favorite local music together|お気に入りの音楽をまとめましょう
loading|Loading…|読み込み中…
login_to_playlists|Log in to see your NetEase playlists|ログインしてプレイリストを表示
account_manage|Manage account and connection|アカウントと接続を管理
no_cloud_playlists|No NetEase playlists|プレイリストはありません
refresh|Refresh|更新
playlist_name|Playlist name|プレイリスト名
create|Create|作成
delete_playlist|Delete playlist?|プレイリストを削除しますか？
delete_playlist_summary|Only the playlist is deleted. Your music files are kept.|音楽ファイルは削除されません
cancel|Cancel|キャンセル
save|Save|保存
appearance|Appearance|外観
navigation_style|Bottom navigation style|下部ナビゲーションのスタイル
nav_hyper|HyperOS floating navigation|HyperOS フローティングナビゲーション
nav_harmony|HarmonyOS floating navigation|HarmonyOS フローティングナビゲーション
nav_liquid|Liquid glass navigation|リキッドガラスナビゲーション
navigation_labels|Navigation labels|ナビゲーションのラベル
icons_and_text|Icons and text|アイコンとテキスト
icons_only|Icons only|アイコンのみ
text_only|Text only|テキストのみ
predictive_back|Predictive back animation|予測型戻るアニメーション
back_maximum|Maximum predictive back progress|予測型戻るの最大進行度
login_summary|Sign in on the official NetEase page|NetEase 公式ページでログイン
blacklist|Blacklist|ブラックリスト
blacklist_disabled|Whitelist is enabled; blacklist is inactive|ホワイトリスト有効中のため無効です
folder_count|folders|フォルダー
whitelist|Whitelist|ホワイトリスト
whitelist_summary|Only show music in the Music directory|Music フォルダーの音楽のみ表示
filter_duration|Filter by song duration|曲の長さでフィルター
minimum_duration|Minimum song duration|曲の最短時間
select_language|Language|言語
chinese|简体中文|简体中文
english|English|English
information|Information|情報
unsupported_folder|Choose a music folder on your phone or SD card|端末またはSDカードの音楽フォルダーを選択してください
blacklist_summary|Folders on this list are hidden from the music library|リスト内のフォルダーはライブラリから非表示になります
blacklist_empty|No folders added|フォルダーはありません
clear|Clear|クリア
saved|Saved|保存しました
done|Done|完了
clear_blacklist|Clear blacklist?|ブラックリストをクリアしますか？
clear_blacklist_summary|After saving, music in these folders will reappear|保存後、これらのフォルダーの曲が再表示されます
theme|Theme|テーマ
theme_mode|Theme mode|テーマモード
follow_system|Follow system|システムに従う
light_mode|Light|ライト
dark_mode|Dark|ダーク
custom|Custom|カスタム
colors|Colors|色
monet|Monet colors|Monet カラー
monet_summary|Generate dynamic theme colors with MIUIX|MIUIX で動的テーマカラーを生成
color_theme|Color theme|カラーテーマ
palette|Palette|パレット
palette_tonal|Tonal spot|柔らかな色調
palette_neutral|Neutral|ニュートラル
palette_vibrant|Vibrant|鮮やか
palette_expressive|Expressive|豊かな表現
palette_rainbow|Rainbow|レインボー
palette_fruit|Fruit salad|フルーツサラダ
palette_mono|Monochrome|モノクローム
palette_fidelity|Fidelity|忠実
palette_content|Content|コンテンツ
color_spec|Color specification|色の仕様
spec_2021|Classic (2021)|クラシック（2021）
spec_2025|Expressive (2025)|豊かな表現（2025）
choose_color|Choose color|色を選択
spec_summary|The 2025 specification applies to tonal spot, neutral, vibrant and expressive palettes. Other palettes use 2021.|2025 仕様は柔らかな色調・ニュートラル・鮮やか・豊かな表現に対応し、他は2021仕様です。
hex_color|Hex color (RRGGBB)|16進カラー（RRGGBB）
invalid_color|Enter a six-digit hexadecimal color|6桁の16進カラーを入力してください
connection|Connection|接続
api_address|NetEase API service URL|NetEase API サービスのURL
save_address|Save service URL|サービスURLを保存
api_hint|Connect to a trusted service supporting NeteaseCloudMusicApiEnhanced.|信頼できる NeteaseCloudMusicApiEnhanced サービスに接続してください。
api_project|API source code & deployment|API ソースコードと構築方法
account|Account|アカウント
logout|Log out|ログアウト
phone_login|Log in with an SMS code|SMS認証コードでログイン
country_code|Country calling code|国番号
phone|Phone number|電話番号
send_captcha|Send code|認証コードを送信
captcha|Verification code|認証コード
connecting|Connecting…|接続中…
login|Log in|ログイン
logout_confirm|Log out of NetEase Music?|NetEase Music からログアウトしますか？
not_detected|Not detected|検出されませんでした
device_model|Device model|デバイスモデル
android_version|Android version|Android バージョン
hyperos_version|HyperOS version|HyperOS バージョン
supported_version|Aura Player compatibility|Aura Player 対応バージョン
license|LICENSE|ライセンス
github_repository|GitHub Repository|GitHub リポジトリ
telegram_group|Telegram group|Telegram グループ
qq_group|QQ group|QQ グループ
qq_number|Group: 429188055|グループ番号: 429188055
user_agreement|User agreement|利用規約
disclaimer|Disclaimer|免責事項
privacy|Privacy policy|プライバシーポリシー
open_source|Open source notices|オープンソース声明
afdian|Afdian|Afdian
support_afdian|Support me on Afdian|Afdian で応援
afdian_author|Afdian: btm_m|Afdian: btm_m
wechat_reward|WeChat donation code|WeChat 寄付コード
playback_error|Playback failed. Check the file, network or playback rights.|再生できません。ファイル、接続、再生権限を確認してください。
player_connect_error|Could not connect to the playback service|再生サービスに接続できませんでした
cloud_search_error|NetEase search failed. Local results are still available.|NetEase検索に失敗しました。ローカルの結果は利用できます。
scan_error|Could not read music. Try scanning again.|音楽を読み込めませんでした。再スキャンしてください。
player_connecting|The playback service is connecting. Try again shortly.|再生サービスに接続中です。少し後でお試しください。
api_saved|Service URL saved|サービスURLを保存しました
invalid_api|Enter an HTTPS URL. Local network services may use HTTP.|HTTPS URLを入力してください。ローカルサービスはHTTPも利用できます。
configure_api|Configure a NetEase API service URL first|まず NetEase API サービスのURLを設定してください
session_expired|Your session expired. Please log in again.|セッションが切れました。再ログインしてください。
cloud_request_failed|NetEase request failed|NetEase リクエストに失敗しました
cloud_network_error|Connection failed. Check your network and retry.|接続に失敗しました。ネットワークを確認して再試行してください。
captcha_sent|Verification code sent|認証コードを送信しました
login_success|Logged in|ログインしました
logged_out|Logged out|ログアウトしました
albums|Albums|アルバム
artists|Artists|アーティスト
category_search_title|Search|検索
category_search_start|Search local and NetEase songs, artists or albums|ローカルとNetEaseの曲・アーティスト・アルバムを検索
category_search_no_results|No matching music found|一致する音楽が見つかりません
category_search_placeholder|Search songs, artists or albums|曲・アーティスト・アルバムを検索
category_search_open|Search music|音楽を検索
category_search_close|Close search|検索を閉じる
category_search_clear|Clear|クリア
appNavigationSearchHint|Search music|音楽を検索
color_blue|Clear sky|青空
color_cyan|Spring cyan|清流
color_teal|Mint|ミント
color_moss|Moss|苔
color_amber|Amber|琥珀
color_apricot|Apricot|アプリコット
color_rose|Rose|ローズ
color_wisteria|Wisteria|藤
color_indigo|Indigo|藍
color_slate|Slate|スレート
color_mono|Minimal monochrome|ミニマルモノクローム
'''
en, ja = {}, {}
for line in translations.strip().splitlines():
    key, english, japanese = line.split('|')
    en[key], ja[key] = english, japanese

extra = {
    "play": ("Play", "再生"),
    'previous_track': ('Previous track', '前の曲'),
    'next_track': ('Next track', '次の曲'),
    'pause': ('Pause', '一時停止'),
    'collapse_player': ('Collapse player', 'プレーヤーを閉じる'),
    'lyrics_loading': ('Fetching lyrics…', '歌詞を取得中…'),
    'lyrics_empty': ('No lyrics available', '歌詞がありません'),
    'lyrics_options': ('Lyrics options', '歌詞の設定'),
    'playback_progress': ('Playback position', '再生位置'),
    'lyrics': ('Lyrics', '歌詞'),
    'shuffle_mode': ('Shuffle', 'シャッフル再生'),
    'repeat_one_mode': ('Repeat one', '1曲リピート'),
    'repeat_all_mode': ('Repeat all', '全曲リピート'),
    'unfavorite': ('Remove from favorites', 'お気に入りから削除'),
    'favorite': ('Favorite', 'お気に入りに追加'),
    'play_queue': ('Play queue', '再生キュー'),
    'reorder_queue': ('Reorder queue', '再生順序を変更'),
    'move_up': ('Move up', '上へ移動'),
    'move_down': ('Move down', '下へ移動'),
    'media_volume': ('Media volume', 'メディアの音量'),
    'quality_standard': ('Standard', '標準'),
    'quality_higher': ('Higher', '高音質'),
    'quality_high': ('Very high', '最高音質'),
    'quality_lossless': ('Lossless', 'ロスレス'),
    'quality_hires': ('Hi-Res', 'Hi-Res'),
    'quality_spatial': ('HD clarity', '高精細サウンド'),
    'quality_surround': ('Immersive surround', 'イマーシブサラウンド'),
    'quality_dolby': ('Dolby Atmos', 'Dolby Atmos'),
    'quality_vivid': ('Vivid spatial audio', '全方位サウンド'),
    'quality_master': ('Master quality', 'マスター音質'),
    'khz': ('kHz', 'kHz'),
    'bit': ('bit', 'bit'),
    'kbps': ('kb/s', 'kb/s'),
    'audio_details_loading': ('Reading audio format…', '音声形式を読み取り中…'),
    'audio_quality': ('Audio quality', '音質'),
    'quality_access': ('Quality is provided by NetEase. Playback depends on the recording and your account access.', '音質はNetEaseが提供します。曲とアカウントの利用権限により再生できる音質が異なります。'),
    'quality_retry': ('Unable to fetch quality options. Tap to retry.', '音質を取得できません。タップして再試行'),
    'quality_unavailable': ('No quality options available for this song', 'この曲に利用可能な音質がありません'),
    'selected': ('Selected', '選択中'),
    'lyrics_translation': ('Show translation', '翻訳を表示'),
    'lyrics_pronunciation': ('Show pronunciation', '発音を表示'),
    'hide_translation': ('Hide translation', '翻訳を非表示'),
    'show_translation': ('Show translation', '翻訳を表示'),
    'hide_pronunciation': ('Hide pronunciation', '発音を非表示'),
    'show_pronunciation': ('Show pronunciation', '発音を表示'),
    'lyrics_size': ('Lyric size', '歌詞のサイズ'),
    'lyrics_size_extra_small': ('Extra small', '極小'),
    'lyrics_size_small': ('Small', '小'),
    'lyrics_size_regular': ('Regular', '標準'),
    'lyrics_size_large': ('Large', '大'),
    'lyrics_size_extra_large': ('Extra large', '極大'),

    'official_login': ('Sign in with NetEase Music', 'NetEase Music にログイン'),
    'official_login_summary': ('Sign in directly. No server or API address required.', 'サーバーや API アドレスの設定は不要です。'),
    'advanced_connection': ('Advanced connection settings', '高度な接続設定'),
    'advanced_connection_summary': ('Optional: use your own API service', '任意：独自の API サービスを使用'),
    'use_official': ('Use the official NetEase connection', 'NetEase 公式接続を使用'),
    'verifying_account': ('Verifying account…', 'アカウントを確認中…'),
    'official_page_failed': ('Could not load the sign-in page. Check your connection and retry.', 'ログインページを読み込めません。接続を確認して再試行してください。'),
    'retry': ('Retry', '再試行'),
    'retry_verification': ('Verify sign-in again', 'ログインを再確認'),
    'sort': ('Sort', '並べ替え'),
    'more': ('More', 'その他'),
    'sort_default': ('Default', '既定'),
    'sort_az': ('Name A–Z', '名前 A–Z'),
    'sort_za': ('Name Z–A', '名前 Z–A'),
    'sort_artist': ('Artist', 'アーティスト'),
    'sort_album': ('Album', 'アルバム'),
    'sort_year': ('Year', '年'),
    'sort_added': ('Date added', '追加日'),
    'sort_modified': ('Date modified', '更新日'),
    'sort_composer': ('Composer', '作曲者'),
    'sort_album_artist': ('Album artist', 'アルバムアーティスト'),
    'sort_duration': ('Duration', '再生時間'),

    'categories': ('Categories', '分類'),
    'welcome': ('Welcome,', 'ようこそ、'),
    'suggestion_songs': ('Suggestions', 'おすすめ'),
    'new_music_mix': ('New Music Mix', '新しい音楽ミックス'),
    'top_artists': ('Top artists', '人気のアーティスト'),
    'top_albums': ('Top albums', '人気のアルバム'),
    'recent_artists': ('Recent artists', '最近のアーティスト'),
    '返回': ('Back', '戻る'),
    '取消': ('Cancel', 'キャンセル'),
    '确定': ('OK', '確認'),
    '保存': ('Save', '保存'),
    '数值调整': ('Adjust value', '数値を調整'),
    '恢复默认数值': ('Restore default value', '既定値に戻す'),
}
extra.update({'cloud_load_retry': ('Loading failed. Tap to retry', '読み込めません。タップして再試行'),
 'cache': ('Cache', 'キャッシュ'),
 'cache_calculating': ('Calculating…', '計算中…'),
 'clear_cache': ('Clear cache', 'キャッシュを削除'),
 'cache_total': ('Current total cache size', '現在のキャッシュ合計サイズ'),
 'cache_artwork': ('Artwork and images', 'カバーと画像'),
 'cache_metadata': ('Playlist and song information', 'プレイリストと曲の情報'),
 'cache_playback_audio': ('Playback audio cache', '再生オーディオキャッシュ'),
 'download_confirm': ('Download now', '今すぐダウンロード'),
 'cache_clear_failed': ('Could not clear cache. Please retry', '削除できませんでした。再試行してください'),
 'cache_clearing': ('Clearing…', '削除中…'),
 'cache_clean': ('Clear', '削除'),
 'download': ('Download', 'ダウンロード'),
 'downloads': ('Music downloads', '音楽のダウンロード'),
 'download_complete': ('Download complete. Saved to Music/Aura Player', 'ダウンロード完了。Music/Aura Playerに保存しました'),
 'download_failed': ('Download or processing failed. Please select a quality and retry',
                     'ダウンロードまたは処理に失敗しました。音質を選んで再試行してください'),
 'select_quality': ('Audio quality', '音質を選択'),
 'audio_effects': ('Audio effects', '音響効果'),
 'album': ('Album', 'アルバム'),
 'artist': ('Artist', 'アーティスト'),
 'sleep_timer': ('Sleep timer', 'スリープタイマー'),
 'playback_speed': ('Playback speed', '再生速度'),
 'download_quality': ('Download quality', 'ダウンロード音質'),
 'download_unavailable': ('Download unavailable. This quality may require a subscription or purchase. Try another '
                          'quality.',
                          'ダウンロードできません。この音質には会員登録または購入が必要な場合があります。別の音質をお試しください。'),
 'download_destination': ('Uses the system downloader, writes music tags, and saves to Music/Aura Player',
                          'システムでダウンロードし、タグを書き込んでMusic/Aura Playerに保存します'),
 'preparing_download': ('Preparing…', '準備中…'),
 'cancel_timer': ('Cancel sleep timer', 'タイマーを解除'),
 'minutes': ('min', '分'),
 'favorite_artist': ('Favorite artist', 'アーティストをお気に入りに追加'),
 'album_details': ('Album details', 'アルバムの詳細'),
 'download_album_artwork': ('Download album artwork', 'アルバムアートワークをダウンロード'),
 'play_next': ('Play next', '次に再生'),
 'latest_release': ('Latest release', '最新リリース'),
 'top_songs': ('Top songs', '人気の曲'),
 'record_label': ('Record label', 'レーベル'),
 'release_time': ('Release date', 'リリース日'),
 'more_by_artist': ('More by this artist', 'このアーティストのほかの作品'),
 'artist_info_unavailable': ('No artist biography available', 'アーティストの紹介はありません')})
extra.update({
 'cloud': ('Cloud', 'クラウド'),
 'playback_lists': ('Playlists', 'プレイリスト'),
 'listening_rank': ('Listening history ranking', '再生ランキング'),
 'liked_music': ('Liked songs', 'お気に入りの曲'),
 'saved_albums': ('Saved albums', '保存したアルバム'),
 'no_saved_albums': ('No saved albums', '保存したアルバムはありません'),
 'login_required': ('Sign in to NetEase Music first', '先にNetEase Musicにログインしてください'),
 'cloud_search_failed': ('Cloud search failed. Please retry', 'クラウド検索に失敗しました。再試行してください'),
 'search_all': ('All', 'すべて'),
 'search_song': ('Songs', '曲'),
 'search_voice': ('Audio', '音声'),
 'search_blog': ('Podcasts', 'ポッドキャスト'),
})
extra['timer_off'] = ('Off', 'オフ')
extra.update({
 'playlist_add_failed': ('Could not add the song. Tap a playlist to retry', '追加できませんでした。プレイリストをタップして再試行してください'),
 'remove_from_playlist': ('Remove from playlist', 'プレイリストから削除'),
 'saving': ('Saving…', '保存中…'),
})
extra.update({
 'add_to_playlist': ('Save to playlist', 'プレイリストに保存'),
 'add_to_queue': ('Add to queue', '再生キューに追加'),
 'cloud_playlist': ('Cloud playlist', 'クラウドプレイリスト'),
 'download_again': ('Download again?', 'もう一度ダウンロードしますか？'),
 'downloaded': ('Downloaded', 'ダウンロード済み'),
 'downloaded_music': ('Downloaded music', 'ダウンロード済みの音楽'),
 'downloaded_music_hint': ('NetEase Music saved by Aura Player appears here', 'Aura Playerで保存したNetEase Musicがここに表示されます'),
 'local_playlist': ('Local playlist', 'ローカルプレイリスト'),
 'no_downloaded_music': ('No downloaded music', 'ダウンロード済みの音楽はありません'),
 'no_playlists': ('No playlists available', '利用できるプレイリストはありません'),
})
extra.update({
 'playback': ('Playback', '再生'),
 'now_playing_page': ('Now playing page', '再生中の画面'),
 'audio_settings': ('Audio', 'オーディオ'),
 'preset_reference': ('Reference', '原音忠実'),
 'preset_warmth': ('Warmth', 'ぬくもり'),
 'preset_clarity': ('Clarity', '透明感'),
 'preset_deep_bass': ('Deep Bass', '深い低音'),
 'preset_wide_stage': ('Wide Stage', '広がる音場'),
 'preset_j_pop': ('J-Pop', 'J-Pop'),
 'preset_vocaloid': ('Vocaloid', '電子の歌声'),
 'preset_anime': ('Anime Soundtrack', 'アニメサウンドトラック'),
 'preset_electronic': ('Electronic', 'エレクトロニック'),
 'preset_rock': ('Rock', 'ロック'),
 'preset_acoustic': ('Acoustic', 'アコースティック'),
 'preset_classical': ('Classical', 'クラシック'),
 'preset_jazz': ('Jazz Lounge', 'ジャズラウンジ'),
 'preset_piano': ('Piano Solo', 'ピアノソロ'),
 'preset_live': ('Live Recording', 'ライブ録音'),
 'preset_drums': ('Drums', 'ドラム'),
 'preset_bass': ('Bass Guitar', 'ベース'),
 'preset_acoustic_guitar': ('Acoustic Guitar', 'アコースティックギター'),
 'preset_electric_guitar': ('Electric Guitar', 'エレキギター'),
 'preset_smart': ('Smart Tuning', 'スマートチューニング'),
 'smart_custom_disabled': ('Custom parameters are disabled while Smart Tuning is active', 'スマートチューニング中はカスタム設定が無効になります'),
'smart_preset_not_exportable': ('Smart Tuning adapts per track and cannot be exported', 'スマートチューニングはリアルタイム自動調整のためエクスポートできません'),
 'preset_reference_summary': ('Bypass built-in processing; pass PCM through unchanged', '内蔵処理を無効にし、PCMをそのまま出力'),
 'preset_warmth_summary': ('Softer highs with moderate low-mid warmth', '高音を柔らかくし、中低域に適度な厚みを追加'),
 'preset_clarity_summary': ('Less muddiness; clearer vocals and a little air', '濁りを抑え、歌声と空気感を控えめに強調'),
 'preset_bass_summary': ('Deeper, punchier bass with gentle dynamics control', '低音の深さと迫力を、穏やかなダイナミクス制御で'),
 'preset_wide_summary': ('A wider stereo image with centered vocals', '中央の歌声を保ちながらステレオ感を拡張'),
 'preset_j_pop_summary': ('Light bass and clear vocals for Japanese pop', '軽快な低音と明瞭な歌声'),
 'preset_vocaloid_summary': ('Soften synthetic vocal sharpness; keep electronic texture', '合成歌声の鋭さを和らげ、電子的な質感を維持'),
 'preset_anime_summary': ('Balance dialogue, score detail and space', '台詞、音楽の細部、空間表現のバランスを調整'),
 'preset_electronic_summary': ('Fuller bass, wider image and gentle compression', '豊かな低音、広い音場、穏やかな圧縮'),
 'preset_rock_summary': ('Thicker instruments with less high-frequency harshness', '楽器の厚みを増し、高域の刺激を緩和'),
 'preset_acoustic_summary': ('Reduce boxiness; preserve string detail', 'こもりを抑え、弦の細部を維持'),
 'preset_classical_summary': ('Subtle tone shaping; preserve original dynamics', '音色を控えめに調整し、元のダイナミクスを維持'),
 'preset_jazz_summary': ('Warm low mids with subtle headphone crossfeed', '温かい中低域と控えめなヘッドホンクロスフィード'),
 'preset_piano_summary': ('Tighter low mids; retain attack and harmonics', '中低域を引き締め、打鍵感と倍音を維持'),
 'preset_live_summary': ('Improve live-recording clarity and stereo width', 'ライブ録音の明瞭さとステレオ幅を調整'),
 'preset_drums_summary': ('Punchy kick, crisp snare transients and airy cymbals', 'キックの迫力とスネアの立ち上がりを強調し、シンバルの空気感を維持'),
 'preset_bass_guitar_summary': ('Defined bass growl and tight low-end dynamics', 'ベースの輪郭と中域の質感を引き立て、安定した低域を維持'),
 'preset_acoustic_guitar_summary': ('Less body boom; crisp pluck detail and natural shimmer', '箱鳴りの濁りを抑え、ピッキングの粒立ちと倍音を強調'),
 'preset_electric_guitar_summary': ('Focused midrange bite, cab warmth and smoother highs', '中域の抜けとキャビネットの厚みを出し、高域の刺さりを緩和'),
 'preset_smart_summary': ("Analyze each track's spectrum and dynamics to balance tone automatically", '楽曲の周波数特性とダイナミクスをリアルタイム解析し、音色を自動で最適化'),
 'preset_custom_summary': ('Adjust EQ, dynamics and spatial parameters in real time', 'EQ、ダイナミクス、空間パラメータをリアルタイムで調整'),
 'preset_library': ('Preset library', 'プリセットライブラリ'),
 'sound_style': ('Sound style', 'サウンドスタイル'),
 'overall_sound_style': ('Overall sound style', '全体のサウンドスタイル'),
 'style_classic': ('Classic', 'クラシック'),
 'style_balanced': ('Balanced', 'バランス'),
 'style_original': ('Original', 'オリジナル'),
 'system_equalizer': ('System equalizer', 'システムイコライザー'),
 'system_equalizer_summary': ('Open the audio effects panel provided by your device', '端末の音響効果パネルを開く'),
 'equalizer_play_first': ('Play a song first', '先に曲を再生してください'),
 'equalizer_unavailable': ('This device has no system equalizer panel', 'この端末にはシステムイコライザーがありません'),
 'effects_hint': ('Presets suggest a sound character, not a fixed response for each genre. Reference ignores the overall style; other presets reserve gain headroom automatically.', 'プリセットは音の傾向を提案するもので、ジャンルごとの固定特性ではありません。原音忠実は全体スタイルの影響を受けず、ほかは自動でゲイン余裕を確保します。'),
 'import_preset': ('Import preset', 'プリセットをインポート'),
 'export_preset': ('Export preset', 'プリセットをエクスポート'),
 'online_presets': ('Online preset library', 'オンラインプリセット'),
 'core_sound_styles': ('Core sound styles', '基本サウンドスタイル'),
 'genre_sound_styles': ('Music style suggestions', '音楽スタイルの提案'),
 'my_presets': ('My presets', 'マイプリセット'),
 'delete_preset_confirm': ('Delete this preset?', 'このプリセットを削除しますか？'),
 'preset_imported': ('Preset imported and applied', 'プリセットをインポートして適用しました'),
 'preset_library_full': ('Preset library is full. Delete some saved presets first.', 'ライブラリがいっぱいです。保存済みプリセットを削除してください。'),
 'preset_import_failed': ('Import failed. Check the preset format, version and parameter ranges.', 'インポートできません。形式、バージョン、パラメータ範囲を確認してください。'),
 'preset_exported': ('Preset exported', 'プリセットをエクスポートしました'),
 'preset_export_failed': ('Export failed. Choose a save location again.', 'エクスポートできません。保存先を再度選択してください。'),
 'preset_transfer_hint': ('Supports Aura JSON files, QR images and JSON text', 'Aura JSONファイル、QR画像、JSONテキストに対応'),
 'preset_json_file': ('JSON file', 'JSONファイル'),
 'preset_qr_image': ('QR image', 'QRコード画像'),
 'paste_json': ('Paste JSON text', 'JSONテキストを貼り付け'),
 'copy_json': ('Copy JSON text', 'JSONテキストをコピー'),
 'preset_json_text': ('Preset JSON text', 'プリセットのJSONテキスト'),
 'import_apply': ('Import and apply', 'インポートして適用'),
 'edit_from_current': ('Start editing from the current preset', '現在のプリセットから編集'),
 'save_as_preset': ('Save as a new preset', '新しいプリセットとして保存'),
 'enable_custom_processing': ('Enable custom processing', 'カスタム処理を有効化'),
 'effect_headroom': ('Gain headroom', 'ゲインの余裕'),
 'effect_preamp': ('Preamp', 'プリアンプ'),
 'automatic_headroom': ('Automatic gain protection', '自動ゲイン保護'),
 'automatic_headroom_summary': ('Reserve extra EQ and spatial headroom below the preamp setting, and limit output peaks', 'プリアンプ設定からEQと音場用の余裕を追加で確保し、出力ピークを制限'),
 'eq_band': ('EQ band', 'EQバンド'),
 'filter_type': ('Filter type', 'フィルターの種類'),
 'filter_peak': ('Peaking', 'ピーキング'),
 'filter_low': ('Low shelf', 'ローシェルフ'),
 'filter_high': ('High shelf', 'ハイシェルフ'),
 'eq_frequency': ('Frequency', '周波数'),
 'eq_gain': ('Gain', 'ゲイン'),
 'eq_q': ('Q factor', 'Q値'),
 'effect_spatial': ('Space and tone', '空間と音色'),
 'effect_width': ('Stereo width', 'ステレオ幅'),
 'effect_crossfeed': ('Low-frequency crossfeed', '低域クロスフィード'),
 'effect_saturation': ('Harmonic coloration', '倍音の色付け'),
 'effect_dynamics': ('Dynamics', 'ダイナミクス'),
 'compressor_ratio': ('Compression ratio', '圧縮比'),
 'compressor_threshold': ('Threshold', 'しきい値'),
 'compressor_attack': ('Attack', 'アタック'),
 'compressor_release': ('Release', 'リリース'),
 'preset_name': ('Preset name', 'プリセット名'),
 'audio_fade': ('Audio fade in/out', 'オーディオのフェードイン・アウト'),
 'audio_fade_summary': ('Smooth volume changes when playing and pausing', '再生・一時停止時に音量を滑らかに変更'),
 'audio_fade_duration': ('Fade duration', 'フェード時間'),
 'audio_always_play': ('Always play', '常に再生'),
 'audio_always_play_summary': ('Keep playing in the background when other audio starts', 'ほかの音声の再生中もバックグラウンドで再生を続行'),
 'no_user_presets': ('No user presets yet', 'ユーザープリセットはまだありません'),
 'user_presets_hint': ('Import a preset, or save your sound style on the Custom page', 'プリセットをインポートするか、カスタム画面で保存してください'),
 'export': ('Export', 'エクスポート'),
 'share': ('Share', '共有'),
 'share_preset': ('Share preset', 'プリセットを共有'),
 'preset_share_failed': ('Could not open sharing. Try exporting instead.', '共有を開けません。エクスポートをお試しください。'),
 'use_preset': ('Use', '使用'),
 'builtin_presets': ('Built-in presets', '内蔵プリセット'),
 'user_presets': ('User presets', 'ユーザープリセット'),
 'copied': ('Copied', 'コピーしました'),
})
extra.update({
 'restore_default_parameters': ('Restore default parameters', 'パラメータを初期設定に戻す'),
 'unsaved_preset': ('Unsaved preset', '未保存のプリセット'),
 'restore_effects_hint': ('Restore Reference and the default overall style. Keep user presets and audio settings.', '原音忠実と標準スタイルに戻します。ユーザープリセットと音声設定は保持します。'),
 'restore': ('Restore', '元に戻す'),
 'preset_text': ('Text', 'テキスト'),
 'preset_has_changes': ('The current preset has changes', '現在のプリセットは変更されています'),
 'save_before_switch': ('Save this preset before applying another one?', '別のプリセットを適用する前に保存しますか？'),
 'dont_save': ('Do not save', '保存しない'),
 'preset_imported': ('Preset imported', 'プリセットをインポートしました'),
})
extra.update({'add_eq_band': ('Add EQ band', 'EQバンドを追加'), 'remove_eq_band': ('Remove this band', 'このバンドを削除')})
extra.update({
 'pull_to_refresh': ('Pull to refresh', '下に引いて更新'),
 'release_to_refresh': ('Release to refresh', '離して更新'),
 'refreshing': ('Refreshing…', '更新中…'),
 'refresh_completed': ('Refresh complete', '更新完了'),
})
extra.update({
 'audio_live_output': ('Live audio output', 'リアルタイム音声出力'),
 'effect_presets': ('Sound presets', '音響プリセット'),
})
extra.update({
 'default': ('Default', 'デフォルト'),
 'player_interface_theme': ('Interface theme', '画面テーマ'),
 'player_glass_effect': ('Glass effect', 'ガラス効果'),
 'player_theme_aurora': ('Aurora', 'オーロラ'),
 'player_theme_aurora_blur': ('Blurred aurora', 'ぼかしオーロラ'),
 'player_theme_blur': ('Blur', 'ぼかし'),
 'player_theme_color': ('Color', '単色'),
 'player_theme_fullscreen': ('Full screen', '全画面'),
 'player_blur_amount': ('Blur amount', 'ぼかしの強さ'),
 'player_special_effect': ('Special effects', '特殊効果'),
 'player_effect_none': ('None', '表示しない'),
 'player_effect_snow': ('Snow', '雪'),
 'player_effect_rain': ('Rain', '雨'),
 'player_cover_animation': ('Cover transition', '曲切り替え時のカバーアニメーション'),
 'player_cover_card': ('Card', 'カード'),
 'player_cover_cover': ('Cover', 'カバー'),
 'player_cover_page': ('Page turn', 'ページめくり'),
 'player_cover_stack': ('Stack', 'スタック'),
 'player_cover_vertical_flip': ('Vertical flip', '縦回転'),
 'player_cover_horizontal_flip': ('Horizontal flip', '横回転'),
 'player_show_volume': ('Show volume controls', '音量コントロールを表示'),
 'player_functions': ('Features', '機能'),
 'player_open_notification': ('Open Now Playing from media notifications', 'メディア通知から再生画面を開く'),
 'player_lyrics_awake': ('Keep screen on while showing lyrics', '歌詞表示中は画面をオンに保つ'),
 'player_show_audio_info': ('Show audio information in Now Playing', '再生画面に音声情報を表示'),
 'player_swipe_tracks': ('Swipe to change tracks', '左右スワイプで曲を切り替え'),
 'player_lyrics_immersive': ('Auto-hide controls in lyrics', '歌詞表示中に操作を自動非表示'),
 'player_immersive_delay': ('Time before hiding controls', '操作を非表示にするまでの時間'),
})
extra.update({
 'tag_editor': ('Tag editor', 'タグエディター'),
 'tag_basic_info': ('Basic information', '基本情報'),
 'tag_album_info': ('Album information', 'アルバム情報'),
 'song': ('Song', '曲'),
 'other': ('Other', 'その他'),
 'tag_composer': ('Composer', '作曲者'),
 'tag_lyricist': ('Lyricist', '作詞者'),
 'tag_year': ('Year', '年'),
 'tag_track': ('Track number', 'トラック番号'),
 'tag_album_artist': ('Album artist', 'アルバムアーティスト'),
 'tag_release_date': ('Album release date', 'アルバム発売日'),
 'tag_comment': ('Comment', 'コメント'),
 'tag_not_set': ('Not set', '未設定'),
 'tag_track_digits': ('Track number accepts digits only', 'トラック番号には数字のみ入力できます'),
 'select_file': ('Choose file', 'ファイルを選択'),
 'tag_confirm_save': ('Save tag changes?', 'タグの変更を保存しますか？'),
 'tag_write_original': ('Changes will be written to this music file', '変更をこの音楽ファイルに書き込みます'),
 'tag_unsaved': ('You have unsaved changes', '未保存の変更があります'),
 'tag_discard_hint': ('Discard changes and go back?', '変更を破棄して戻りますか？'),
 'tag_keep_editing': ('Keep editing', '編集を続ける'),
 'tag_discard': ('Discard changes', '変更を破棄'),
 'tag_saved': ('Tags saved', 'タグを保存しました'),
 'tag_permission_denied': ('Write access was not granted. Tags have not been saved.', '書き込みが許可されていません。タグは保存されていません。'),
 'tag_read_failed': ('Could not read tags, or this audio format is not supported yet', 'タグを読み取れないか、この音声形式にはまだ対応していません'),
 'tag_save_failed': ('Could not save. Check for external file changes, free space, and audio format support.', '保存できませんでした。他のアプリによる変更、空き容量、音声形式への対応を確認してください。'),
 'tag_image_failed': ('Choose a valid image no larger than 32 MB', '32 MB以下の有効な画像を選択してください'),
})
for key, (english,japanese) in extra.items(): en[key],ja[key]=english,japanese
strings = {}
pattern = re.compile(r'tr\("((?:\\.|[^"\\])*)",\s*"((?:\\.|[^"\\])*)"\)')
for path in (root/'app/src/main/java').rglob('*.kt'):
    for key, value in pattern.findall(path.read_text(encoding='utf-8-sig')):
        strings[json.loads('"'+key+'"')] = json.loads('"'+value+'"')
for id_, name, _ in [('blue','澄空蓝',0),('cyan','清泉青',0),('teal','薄荷绿',0),('moss','苔庭绿',0),('amber','暖琥珀',0),('apricot','杏霞',0),('rose','玫瑰砂',0),('wisteria','藤烟紫',0),('indigo','暮靛',0),('slate','岩雾',0),('mono','黑白极简',0)]: strings['color_'+id_] = name
missing = set(strings)-set(en)
if missing: raise SystemExit('Missing translations: '+str(sorted(missing)))
for lang, dictionary in [('en',en),('ja',ja)]:
    path = root/f'app/src/main/assets/languages/{lang}.json'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps({'languageName':lang,'strings':{k:dictionary[k] for k in sorted(strings)}},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
path=root/'languages/example.json'
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps({'languageName':'简体中文','strings':dict(sorted(strings.items()))},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
for path in [root/'app/src/main/assets/languages/en.json',root/'app/src/main/assets/languages/ja.json',root/'languages/example.json']: json.loads(path.read_text(encoding='utf-8'))
print(f'Validated {len(strings)} synchronized strings in zh/en/ja.')
