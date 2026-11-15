package btm.m.auraplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraApplication

@Composable
fun ArtistAlbumSettingsPage(padding: PaddingValues) {
    val context = LocalContext.current
    val store = (context.applicationContext as AuraApplication).nowPlaying
    val state by store.state.collectAsState()
    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Group(tr("artists", "艺术家")) {
                SwitchPreference(
                    title = tr("artist_apple_music_artwork", "从 Apple Music 获取艺术家图片"),
                    summary = tr("artist_apple_music_artwork_summary", "在歌手详情页自动获取高清歌手肖像封面"),
                    checked = state.appleMusicArtistArtwork,
                ) { value -> store.update { it.copy(appleMusicArtistArtwork = value) } }
                SwitchPreference(
                    title = tr("artist_animated_cover", "动态艺术家图片"),
                    summary = tr("artist_animated_cover_summary", "获取到动态肖像视频时在歌手页顶部循环播放"),
                    checked = state.artistAnimatedCover,
                ) { value -> store.update { it.copy(artistAnimatedCover = value) } }
                SwitchPreference(
                    title = tr("artist_wordmark_logo", "歌手艺术字"),
                    summary = tr("artist_wordmark_logo_summary", "在歌手详情页显示 Apple Music 定制艺术字标志"),
                    checked = state.artistWordmarkLogo,
                ) { value -> store.update { it.copy(artistWordmarkLogo = value) } }
            }
        }
        item {
            Group(tr("albums", "专辑")) {
                SwitchPreference(
                    title = tr("player_album_animated_cover", "专辑页面显示动态封面"),
                    summary = tr("player_album_animated_cover_summary", "获取到动态封面时强制使用沉浸式全高封面"),
                    checked = state.albumAnimatedCover,
                ) { value -> store.update { it.copy(albumAnimatedCover = value) } }
            }
        }
    }
}
