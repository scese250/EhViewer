package com.hippo.ehviewer.translatortest

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One OCR fragment as returned by ML Kit.
 *
 * ML Kit usually returns one block per vertical text column (and sometimes one
 * block per horizontal line). A single speech bubble with two columns therefore
 * arrives as two independent blocks.
 */
data class RawBlock(
    val text: String,
    val rect: Rect,
)

/**
 * Tunables for grouping OCR fragments back into whole speech bubbles.
 * All ratios are relative to the size of the fragments, so they work at any
 * image resolution.
 *
 * @param enabled merge fragments at all
 * @param maxColumnGapRatio maximum white gap between two columns, relative to
 *   the shorter column height (0.35 = 35 %)
 * @param minColumnOverlap minimum vertical overlap between two columns,
 *   relative to the shorter column height
 * @param borderDarkRatio when a vertical/horizontal band between two fragments
 *   is dark for at least this fraction of its length it is treated as a bubble
 *   outline, so the fragments are NOT merged
 * @param maxRowGapRatio same as [maxColumnGapRatio] but for horizontal lines
 * @param minRowOverlap minimum horizontal overlap between two text lines
 */
data class BubbleGroupingConfig(
    val enabled: Boolean = true,
    val maxColumnGapRatio: Float = 0.35f,
    val minColumnOverlap: Float = 0.55f,
    val borderDarkRatio: Float = 0.40f,
    val maxRowGapRatio: Float = 0.55f,
    val minRowOverlap: Float = 0.60f,
)

/**
 * Rebuilds speech bubbles from ML Kit blocks.
 *
 * The old implementation translated every block on its own. A vertical Japanese
 * bubble is normally split into one block per column, so every balloon showed up
 * twice, with two white boxes overlapping each other. This detector groups
 * fragments that share the same white balloon interior and rejects pairs that
 * are separated by a real balloon outline.
 */
object BubbleDetector {
    private const val DARK_LUMA = 110
    private const val MAX_CORRIDOR_DARK_RATIO = 0.10f
    private const val MIN_CORRIDOR_MEAN_LUMA = 170f

    private enum class Mode {
        COLUMNS,
        ROWS,
    }

    private class BlockGroup(val parts: MutableList<RawBlock>) {
        var rect: Rect = Rect(parts.first().rect)
            private set

        fun add(block: RawBlock) {
            parts.add(block)
            rect = union(rect, block.rect)
        }
    }

    fun group(
        blocks: List<RawBlock>,
        bitmap: Bitmap,
        config: BubbleGroupingConfig = BubbleGroupingConfig(),
    ): List<RawBlock> {
        val cleaned = deduplicate(blocks)
        if (cleaned.size <= 1 || !config.enabled) return cleaned

        val columns = mutableListOf<RawBlock>()
        val rows = mutableListOf<RawBlock>()
        val standalone = mutableListOf<RawBlock>()

        cleaned.forEach { block ->
            val width = block.rect.width().toFloat()
            val height = block.rect.height().toFloat()
            if (width <= 0f || height <= 0f) return@forEach
            when {
                height > width * 1.10f -> columns.add(block)
                width > height * 1.10f -> rows.add(block)
                else -> standalone.add(block)
            }
        }

        // Japanese columns read right to left: visit the rightmost column first.
        columns.sortWith(compareByDescending<RawBlock> { it.rect.left }.thenBy { it.rect.top })
        // Horizontal lines read top to bottom.
        rows.sortBy { it.rect.top }

        val result = mutableListOf<RawBlock>()

        val columnGroups = mutableListOf<BlockGroup>()
        for (block in columns) {
            var best: BlockGroup? = null
            var bestGap = Float.MAX_VALUE

            for (group in columnGroups) {
                val gap = columnGapOrNull(group, block.rect, bitmap, config) ?: continue
                if (gap < bestGap) {
                    bestGap = gap
                    best = group
                }
            }

            if (best == null) {
                columnGroups.add(BlockGroup(mutableListOf(block)))
            } else {
                best.add(block)
            }
        }

        compactGroups(columnGroups, Mode.COLUMNS).forEach { result.add(it) }

        val rowGroups = mutableListOf<BlockGroup>()
        for (block in rows) {
            var best: BlockGroup? = null
            var bestGap = Float.MAX_VALUE

            for (group in rowGroups) {
                val gap = rowGapOrNull(group, block.rect, bitmap, config) ?: continue
                if (gap < bestGap) {
                    bestGap = gap
                    best = group
                }
            }

            if (best == null) {
                rowGroups.add(BlockGroup(mutableListOf(block)))
            } else {
                best.add(block)
            }
        }

        compactGroups(rowGroups, Mode.ROWS).forEach { result.add(it) }
        result.addAll(standalone)

        // Stable reading order for the translation request.
        return result.sortedWith(
            compareBy<RawBlock> { it.rect.top }.thenByDescending { it.rect.right },
        )
    }

    private fun compactGroups(groups: List<BlockGroup>, mode: Mode): List<RawBlock> = groups.map { group ->
        val ordered = when (mode) {
            Mode.COLUMNS -> group.parts.sortedWith(
                compareByDescending<RawBlock> { it.rect.left }.thenBy { it.rect.top },
            )
            Mode.ROWS -> group.parts.sortedWith(
                compareBy<RawBlock> { it.rect.top }.thenBy { it.rect.left },
            )
        }
        RawBlock(
            text = ordered.map { it.text.trim() }.filter { it.isNotBlank() }.joinToString(" "),
            rect = Rect(group.rect),
        )
    }

    /**
     * Drops fragments that report the same text over (nearly) the same area.
     * ML Kit occasionally returns a duplicated block for stylised lettering.
     */
    private fun deduplicate(blocks: List<RawBlock>): List<RawBlock> {
        val result = mutableListOf<RawBlock>()
        val sorted = blocks.sortedByDescending {
            it.rect.width().toLong() * it.rect.height().toLong()
        }

        for (block in sorted) {
            val text = block.text.trim()
            if (text.isBlank()) continue

            val area = block.rect.width().toLong() * block.rect.height().toLong()
            if (area <= 0L) continue

            val isDuplicate = result.any { existing ->
                existing.text == text &&
                    intersectionArea(existing.rect, block.rect) >= area * 0.5f
            }
            if (!isDuplicate) result.add(RawBlock(text, block.rect))
        }

        return result
    }

    private fun union(a: Rect, b: Rect): Rect = Rect(
        min(a.left, b.left),
        min(a.top, b.top),
        max(a.right, b.right),
        max(a.bottom, b.bottom),
    )

    private fun intersectionArea(a: Rect, b: Rect): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (width <= 0 || height <= 0) return 0f
        return width.toFloat() * height.toFloat()
    }

    /**
     * Returns the horizontal gap when [blockRect] is another column of the same
     * balloon, or null when it is a different balloon.
     */
    private fun columnGapOrNull(
        group: BlockGroup,
        blockRect: Rect,
        bitmap: Bitmap,
        config: BubbleGroupingConfig,
    ): Float? {
        // Compare against the most recently added column (the closest one in
        // right-to-left reading order), not the whole union of the group.
        val groupRect = group.parts.last().rect
        val minHeight = min(groupRect.height(), blockRect.height()).toFloat()
        val maxHeight = max(groupRect.height(), blockRect.height()).toFloat()
        if (minHeight <= 0f || maxHeight <= 0f) return null
        if (minHeight / maxHeight < 0.5f) return null

        val overlapTop = max(groupRect.top, blockRect.top)
        val overlapBottom = min(groupRect.bottom, blockRect.bottom)
        val overlap = (overlapBottom - overlapTop).toFloat()
        if (overlap < config.minColumnOverlap * minHeight) return null

        val centerDelta = abs(groupRect.centerY() - blockRect.centerY()).toFloat()
        if (centerDelta > maxHeight * 0.35f) return null

        val leftRect: Rect
        val rightRect: Rect
        if (groupRect.left <= blockRect.left) {
            leftRect = groupRect
            rightRect = blockRect
        } else {
            leftRect = blockRect
            rightRect = groupRect
        }

        val gap = (rightRect.left - leftRect.right).toFloat()
        if (gap < -2f) return null
        if (gap > config.maxColumnGapRatio * minHeight) return null

        val clean = corridorIsClean(
            bitmap = bitmap,
            xStart = leftRect.right + 1,
            xEnd = rightRect.left - 1,
            yStart = overlapTop + 2,
            yEnd = overlapBottom - 2,
            scanColumns = true,
            config = config,
        )
        return if (clean) gap else null
    }

    /**
     * Same as [columnGapOrNull] but for horizontal text lines stacked inside one
     * balloon.
     */
    private fun rowGapOrNull(
        group: BlockGroup,
        blockRect: Rect,
        bitmap: Bitmap,
        config: BubbleGroupingConfig,
    ): Float? {
        // Compare against the previous line in top-to-bottom reading order.
        val groupRect = group.parts.last().rect
        val minWidth = min(groupRect.width(), blockRect.width()).toFloat()
        val maxWidth = max(groupRect.width(), blockRect.width()).toFloat()
        if (minWidth <= 0f || maxWidth <= 0f) return null
        if (minWidth / maxWidth < 0.5f) return null

        val overlapLeft = max(groupRect.left, blockRect.left)
        val overlapRight = min(groupRect.right, blockRect.right)
        val overlap = (overlapRight - overlapLeft).toFloat()
        if (overlap < config.minRowOverlap * minWidth) return null

        val centerDelta = abs(groupRect.centerX() - blockRect.centerX()).toFloat()
        if (centerDelta > maxWidth * 0.35f) return null

        val topRect: Rect
        val bottomRect: Rect
        if (groupRect.top <= blockRect.top) {
            topRect = groupRect
            bottomRect = blockRect
        } else {
            topRect = blockRect
            bottomRect = groupRect
        }

        val gap = (bottomRect.top - topRect.bottom).toFloat()
        if (gap < -2f) return null

        val lineHeight = min(topRect.height(), bottomRect.height()).toFloat().coerceAtLeast(1f)
        if (gap > config.maxRowGapRatio * lineHeight) return null

        val clean = corridorIsClean(
            bitmap = bitmap,
            xStart = overlapLeft + 1,
            xEnd = overlapRight - 1,
            yStart = topRect.bottom + 1,
            yEnd = bottomRect.top - 1,
            scanColumns = false,
            config = config,
        )
        return if (clean) gap else null
    }

    /**
     * Samples the band between two fragments. The interior of a balloon is
     * white; a real balloon outline produces a mostly dark line running across
     * the band.
     */
    private fun corridorIsClean(
        bitmap: Bitmap,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        scanColumns: Boolean,
        config: BubbleGroupingConfig,
    ): Boolean {
        val left = xStart.coerceIn(0, bitmap.width - 1)
        val right = xEnd.coerceIn(0, bitmap.width - 1)
        val top = yStart.coerceIn(0, bitmap.height - 1)
        val bottom = yEnd.coerceIn(0, bitmap.height - 1)

        val width = right - left + 1
        val height = bottom - top + 1
        // A band thinner than a pixel cannot be judged; trust the geometry.
        if (width < 2 || height < 4) return true

        // One bulk read instead of thousands of getPixel() JNI calls.
        val pixels = IntArray(width * height)
        runCatching {
            bitmap.getPixels(pixels, 0, width, left, top, width, height)
        }.onFailure {
            return true
        }

        var total = 0
        var dark = 0
        var lumaSum = 0L
        var maxLineDarkRatio = 0f

        fun lumaAt(x: Int, y: Int): Int {
            val pixel = pixels[y * width + x]
            return (
                0.299f * ((pixel shr 16) and 0xFF) +
                    0.587f * ((pixel shr 8) and 0xFF) +
                    0.114f * (pixel and 0xFF)
                ).toInt()
        }

        if (scanColumns) {
            for (x in 0 until width) {
                var lineTotal = 0
                var lineDark = 0
                var y = 0
                while (y < height) {
                    val luma = lumaAt(x, y)
                    total++
                    lineTotal++
                    lumaSum += luma.toLong()
                    if (luma < DARK_LUMA) {
                        dark++
                        lineDark++
                    }
                    y += 2
                }
                if (lineTotal > 0) {
                    maxLineDarkRatio = max(maxLineDarkRatio, lineDark.toFloat() / lineTotal)
                }
            }
        } else {
            for (y in 0 until height) {
                var lineTotal = 0
                var lineDark = 0
                var x = 0
                while (x < width) {
                    val luma = lumaAt(x, y)
                    total++
                    lineTotal++
                    lumaSum += luma.toLong()
                    if (luma < DARK_LUMA) {
                        dark++
                        lineDark++
                    }
                    x += 2
                }
                if (lineTotal > 0) {
                    maxLineDarkRatio = max(maxLineDarkRatio, lineDark.toFloat() / lineTotal)
                }
            }
        }

        if (total == 0) return true
        val darkRatio = dark.toFloat() / total
        val meanLuma = lumaSum.toFloat() / total

        return darkRatio <= MAX_CORRIDOR_DARK_RATIO &&
            maxLineDarkRatio < config.borderDarkRatio &&
            meanLuma >= MIN_CORRIDOR_MEAN_LUMA
    }
}
