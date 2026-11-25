package btm.m.auraplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.SongSort
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.SelectAll
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
internal fun SongsToolbarMenu(
    model: AuraViewModel,
    onEnterMultiSelect: () -> Unit = {},
) {
    val iconColor = MiuixTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val colors = glassDropdownColors()
    fun dismiss() {
        sortExpanded = false
        menuOpen = false
    }
    fun select(action: () -> Unit) {
        if (!menuOpen || pendingAction != null) return
        // Sorting replaces the list and menu's selected values. Apply it after the
        // exit animation, when the popup is no longer measuring the old content.
        pendingAction = action
        dismiss()
    }
    val items = listOf(
        DropdownItem(tr("refresh", "刷新"), icon = { modifier -> Image(MiuixIcons.Regular.Refresh, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) }),
        DropdownItem(tr("shuffle", "随机播放"), icon = { modifier -> Image(MiuixIcons.Regular.ConvertFile, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) }),
        DropdownItem(tr("multi_select", "多选"), icon = { modifier -> Image(MiuixIcons.Regular.SelectAll, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) }),
        DropdownItem(tr("sort", "排序"),
            icon = { modifier -> Image(MiuixIcons.Regular.Sort, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) }),
    )
    Box {
        GlassToolbarIconButton(AuraMoreHorizontalIcon, tr("more", "更多")) {
            if (pendingAction == null) {
                if (menuOpen) dismiss() else menuOpen = true
            }
        }
        GlassDropdownPopup(
            entries = emptyList(),
            show = menuOpen,
            onDismiss = ::dismiss,
            onBack = { if (sortExpanded) sortExpanded = false else dismiss() },
            onDismissFinished = {
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            },
            dropdownColors = colors,
            backdrop = LocalToolbarBackdrop.current,
            maxHeight = 440.dp,
            horizontalOffset = 0.dp,
            trailingScreenInset = 12.dp,
            growFromAnchor = true,
        ) {
            // One bounded scroll container and one glass surface. No secondary
            // popup, anchor-union measurement or duplicate Backdrop is needed.
            Column(Modifier.width(200.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { index, item ->
                    // DropdownImpl wraps its content in popup mode. Forward the
                    // full row width to its selectable node, including blank space.
                    Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                        DropdownImpl(
                            item = item, optionSize = items.size, index = index,
                            isSelected = false, dropdownColors = colors, enabled = menuOpen,
                            hasSubmenu = false,
                            isFirst = index == 0, isLast = index == items.lastIndex && !sortExpanded,
                            onSelectedIndexChange = {
                                when (index) {
                                    0 -> select(model::refreshLibrary)
                                    1 -> select { if (model.sortedSongs.isNotEmpty()) model.play(model.sortedSongs, shuffle = true) }
                                    2 -> select(onEnterMultiSelect)
                                    else -> sortExpanded = !sortExpanded
                                }
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = sortExpanded,
                    modifier = Modifier.fillMaxWidth(),
                    enter = expandVertically(tween(300, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                        fadeIn(tween(240)) + scaleIn(tween(300), initialScale = .9f, transformOrigin = TransformOrigin(.5f, 0f)),
                    exit = shrinkVertically(tween(260, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
                        fadeOut(tween(200)) + scaleOut(tween(260), targetScale = .9f, transformOrigin = TransformOrigin(.5f, 0f)),
                ) {
                    val blur by transition.animateFloat(transitionSpec = { tween(240) }, label = "sortOptionsBlur") { state ->
                        if (state == EnterExitState.Visible) 0f else 10f
                    }
                    Column(Modifier.fillMaxWidth().graphicsLayer {
                        val radius = blur.dp.toPx()
                        renderEffect = if (radius > .01f) BlurEffect(radius, radius, TileMode.Decal) else null
                    }) {
                        SongSort.entries.forEachIndexed { index, sort ->
                            key(sort) {
                                Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                                    DropdownImpl(
                                        item = DropdownItem(songSortLabel(sort)),
                                        optionSize = SongSort.entries.size, index = index,
                                        isSelected = model.songSort.order == sort,
                                        dropdownColors = colors, enabled = menuOpen && sortExpanded,
                                        isFirst = false, isLast = index == SongSort.entries.lastIndex,
                                        onSelectedIndexChange = { select { model.sortSongs(sort) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
