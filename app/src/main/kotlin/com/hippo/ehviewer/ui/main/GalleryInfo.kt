package com.hippo.ehviewer.ui.main

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.model.WatchedTag
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.ElevatedCard
import com.ehviewer.core.ui.component.GalleryListCardRating
import com.ehviewer.core.ui.util.SharedElementBox
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.ehviewer.core.ui.util.listThumbGenerator
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.util.FavouriteStatusRouter

@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope)
fun GalleryInfoListItem(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    info: GalleryInfo,
    showPages: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
    isInFavScene: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) = CrystalCard(
    modifier = modifier,
    onClick = onClick,
    onLongClick = onLongClick,
    interactionSource = interactionSource,
) {
    Row {
        with(listThumbGenerator) {
            EhThumbCard(
                key = info,
                modifier = Modifier.aspectRatio(DEFAULT_RATIO),
            )
        }
        Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = EhUtils.getSuitableTitle(info),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            val showWatchedTags by Settings.showGalleryTags.collectAsState()
            if (showWatchedTags) {
                info.watchedTags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    WatchedTagsRow(
                        tags = tags,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = info.uploader.orEmpty(),
                        modifier = Modifier.alignByBaseline().alpha(if (info.disowned) 0.5f else 1f),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isInFavScene) {
                        info.favoriteNote?.let {
                            Text(text = it, modifier = Modifier.alignByBaseline(), fontStyle = FontStyle.Italic)
                        }
                    } else {
                        val showFav by FavouriteStatusRouter.collectAsState(info) { it != NOT_FAVORITED }
                        if (showFav) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).align(Alignment.CenterVertically),
                            )
                            info.favoriteName?.let {
                                Text(text = it, modifier = Modifier.alignByBaseline())
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Place the rating near the uploader text as there's more visual space
                    GalleryListCardRating(rating = info.rating, modifier = Modifier.padding(top = 1.dp, bottom = 3.dp))
                    Spacer(modifier = Modifier.weight(1f))
                    val downloaded by DownloadManager.collectContainDownloadInfo(info.gid)
                    if (downloaded) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    info.simpleLanguage?.let {
                        Text(text = it)
                    }
                    if (info.pages != 0 && showPages) {
                        val readProgress = if (showProgress) {
                            remember { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0).value
                        } else {
                            0
                        }
                        Text(text = if (readProgress > 0) "${readProgress + 1}/${info.pages}P" else "${info.pages}P")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val categoryColor = EhUtils.getCategoryColor(info.category)
                    val categoryText = EhUtils.getCategory(info.category).uppercase()
                    Text(
                        text = categoryText,
                        modifier = Modifier.clip(ShapeDefaults.Small).background(categoryColor).padding(vertical = 2.dp, horizontal = 8.dp),
                        color = EhUtils.getCategoryTextColor(categoryColor),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(text = info.posted.orEmpty())
                }
            }
        }
    }
}

@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope)
fun GalleryInfoGridItem(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    info: GalleryInfo,
    modifier: Modifier = Modifier,
    showLanguage: Boolean = true,
    showPages: Boolean = true,
    showProgress: Boolean = true,
    showFavoriteStatus: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) = ElevatedCard(
    modifier = modifier,
    onClick = onClick,
    onLongClick = onLongClick,
    interactionSource = interactionSource,
) {
    Box {
        with(listThumbGenerator) {
            SharedElementBox(key = "${info.gid}", shape = ShapeDefaults.Medium) {
                var ratio by remember(info) {
                    val ratio = if (info.thumbHeight != 0) {
                        (info.thumbWidth.toFloat() / info.thumbHeight).coerceIn(MIN_RATIO, MAX_RATIO)
                    } else {
                        DEFAULT_RATIO
                    }
                    mutableFloatStateOf(ratio)
                }
                AsyncImage(
                    model = requestOf(info),
                    contentDescription = null,
                    modifier = Modifier.aspectRatio(ratio),
                    onSuccess = {
                        ratio = (it.result.image.width.toFloat() / it.result.image.height).coerceIn(MIN_RATIO, MAX_RATIO)
                    },
                )
            }
        }
        val categoryColor = EhUtils.getCategoryColor(info.category)
        Badge(
            modifier = Modifier.align(Alignment.TopEnd).widthIn(min = 32.dp).height(24.dp),
            containerColor = categoryColor,
            contentColor = EhUtils.getCategoryTextColor(categoryColor),
        ) {
            val shouldShowLanguage = showLanguage && info.simpleLanguage != null
            if (showPages && info.pages > 0) {
                val readProgress = if (showProgress) {
                    remember { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0).value
                } else {
                    0
                }
                Text(text = if (readProgress > 0) "${readProgress + 1}/${info.pages}" else "${info.pages}")
                if (shouldShowLanguage) {
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
            if (shouldShowLanguage) {
                Text(text = info.simpleLanguage.orEmpty())
            }
        }
        if (showFavoriteStatus) {
            val isFavorited by FavouriteStatusRouter.collectAsState(info) { it != NOT_FAVORITED }
            if (isFavorited) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                    tint = EhUtils.favoriteIconColor,
                )
            }
        }
    }
}

@Composable
fun WatchedTagsRow(
    tags: List<WatchedTag>,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val baseFontSize = MaterialTheme.typography.labelSmall.fontSize
    val smallFontSize = baseFontSize * 0.75f
    val chipPaddingH = 8.dp
    val spacing = 4.dp
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availableWidth = with(density) { maxWidth.toPx() }
        fun measureWidth(candidates: List<String>, fontSize: TextUnit): Float {
            val style = MaterialTheme.typography.labelSmall.copy(fontSize = fontSize)
            val padH = with(density) { chipPaddingH.toPx() }
            val space = with(density) { spacing.toPx() }
            val chipsWidth = candidates.fold(0f) { total, candidate ->
                total + textMeasurer.measure(AnnotatedString(candidate), style).size.width + padH * 2
            }
            return chipsWidth + space * (candidates.size - 1).coerceAtLeast(0)
        }
        // Squeeze the font down when there are several tags, then fall back to abbreviated
        // (3-letter) text when they still cannot fit on a single row
        val texts = tags.map(WatchedTag::text)
        val (displayTexts, fontSize) = when {
            measureWidth(texts, baseFontSize) <= availableWidth -> texts to baseFontSize
            measureWidth(texts, smallFontSize) <= availableWidth -> texts to smallFontSize
            else -> texts.map(::abbreviateWatchedTag) to smallFontSize
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier.fillMaxWidth(),
        ) {
            displayTexts.forEachIndexed { index, text ->
                WatchedTagChip(
                    text = text,
                    color = tags[index].color,
                    fontSize = fontSize,
                )
            }
        }
    }
}

@Composable
private fun WatchedTagChip(
    text: String,
    color: String?,
    fontSize: TextUnit,
) {
    val bg = color?.parseHexColor()
    val bgColor = bg ?: MaterialTheme.colorScheme.tertiaryContainer
    val fgColor = bg?.contrastTextColor() ?: LocalContentColor.current
    Surface(
        color = bgColor,
        contentColor = fgColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = fontSize),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun String.parseHexColor(): Color? {
    val hex = removePrefix("#")
    if (hex.length != 6) return null
    val argb = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or argb)
}

private fun Color.contrastTextColor(): Color {
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (luminance > 0.6f) Color(0xFF090909) else Color(0xFFF1F1F1)
}

// Mimics the website: keep the namespace prefix (excluding the colon) plus the
// first two letters of the tag name, e.g. "f:futanari" -> "f:fu", "english" -> "eng"
private fun abbreviateWatchedTag(text: String): String {
    val colon = text.indexOf(':')
    return if (colon >= 0) {
        text.substring(0, colon + 1) + text.substring(colon + 1).take(2)
    } else {
        text.take(3).trimEnd()
    }
}

private const val MIN_RATIO = 0.5F
private const val MAX_RATIO = 1.5F
const val DEFAULT_RATIO = 0.67F
