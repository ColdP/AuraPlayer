package btm.m.auraplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.SongSort
import btm.m.auraplayer.ui.cascade.WindowIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun songSortLabel(sort: SongSort): String = when (sort) {
    SongSort.DEFAULT -> tr("sort_default", "默认")
    SongSort.TITLE_ASC -> tr("sort_az", "名称 A–Z")
    SongSort.TITLE_DESC -> tr("sort_za", "名称 Z–A")
    SongSort.ARTIST -> tr("sort_artist", "艺术家")
    SongSort.ALBUM -> tr("sort_album", "专辑")
    SongSort.YEAR -> tr("sort_year", "年份")
    SongSort.DATE_ADDED -> tr("sort_added", "添加日期")
    SongSort.DATE_MODIFIED -> tr("sort_modified", "修改日期")
    SongSort.COMPOSER -> tr("sort_composer", "作曲家")
    SongSort.ALBUM_ARTIST -> tr("sort_album_artist", "专辑艺术家")
    SongSort.DURATION -> tr("sort_duration", "时长")
}

@Composable
internal fun SongsToolbarMenu(model: AuraViewModel) {
    val iconColor = MiuixTheme.colorScheme.onSurface
    // Keep the popup's item instances stable while it is open. The cascade layout retains
    // anchor positions by item identity; rebuilding the tree from a sort callback can otherwise
    // leave the secondary popup pointing at a detached row.
    val entry = remember(model.songSort.order) {
        DropdownEntry(listOf(
            DropdownItem(tr("refresh", "刷新"), icon = { modifier -> Image(MiuixIcons.Regular.Refresh, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) },
                onClick = model::refreshLibrary),
            DropdownItem(tr("shuffle", "随机播放"),
                icon = { modifier -> Image(MiuixIcons.Regular.ConvertFile, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) },
                onClick = { if (model.sortedSongs.isNotEmpty()) model.play(model.sortedSongs, shuffle = true) }),
            DropdownItem(tr("sort", "排序"), icon = { modifier -> Image(MiuixIcons.Regular.Sort, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) },
                children = SongSort.entries.map { sort -> DropdownItem(songSortLabel(sort), selected = model.songSort.order == sort, onClick = {
                    model.sortSongs(sort)
                }) }),
        ))
    }
    WindowIconCascadingDropdownMenu(entry = entry, backdrop = LocalToolbarBackdrop.current,
        trigger = { onClick, _ ->
            GlassToolbarIconButton(MiuixIcons.Regular.More, tr("more", "更多"), onClick = onClick)
        }, content = {})
}
