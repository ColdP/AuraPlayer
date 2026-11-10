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
login_summary|Log in with an SMS verification code|SMS認証コードでログイン
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
contributors|Contributors|貢献者
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
cloud_network_error|Connection failed. Check the service URL and network.|接続に失敗しました。サービスURLとネットワークを確認してください。
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
    'privacy_body': ('Aura Player reads audio metadata on your device and does not upload local music files. NetEase login information is sent through the API service you configure. Session cookies are encrypted using Android Keystore and cleared when you log out or change services. Artwork and streaming contact the corresponding network services. Only connect to a service you trust.', 'Aura Player は端末の音声情報を読み取り、ローカル音楽をアップロードしません。ログイン情報は設定したAPIサービスに送信されます。CookieはAndroid Keystoreで暗号化され、ログアウトや接続先変更で削除されます。画像と再生には通信を使用します。信頼できるサービスを利用してください。'),
    'agreement_body': ('Only play music you are entitled to access. NetEase account, regional and subscription restrictions still apply. This app does not unlock paid content or download music.', 'アクセス権のある音楽のみ再生してください。NetEaseの地域・会員制限が適用されます。有料コンテンツの解除や音楽ダウンロードは提供しません。'),
    'disclaimer_body': ('This software is provided as is. Availability of third-party services is not guaranteed. Music copyrights belong to their respective owners. Do not use this software to infringe the rights of others.', '本ソフトウェアは現状のまま提供されます。第三者サービスの可用性は保証されません。音楽の著作権は権利者に帰属します。他者の権利を侵害しないでください。'),
    'contributors_body': ('Aura Player: btm_m\n\nUI & glass components: HyperChanger / btm_m\nHome structure: Retro Music / Hemanth Savarala & contributors\nMIUIX: Yukonga & contributors\nBackdrop: Kyant & contributors\nNetEase API: NeteaseCloudMusicApiEnhanced & contributors', 'Aura Player: btm_m\n\nUIとガラス部品: HyperChanger / btm_m\nホーム構成: Retro Music / Hemanth Savarala と貢献者\nMIUIX: Yukonga と貢献者\nBackdrop: Kyant と貢献者\nNetEase API: NeteaseCloudMusicApiEnhanced と貢献者'),
}
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
