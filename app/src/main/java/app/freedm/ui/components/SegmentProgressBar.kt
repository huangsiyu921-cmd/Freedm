package app.freedm.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Segmented progress: the bar is split into `partCount` equal slices laid out in
 * file order, and each slice is filled by its own completion ratio.
 *
 * Geometry, for `partCount = n`:
 *  - every part is `width / n` wide, laid out left to right in **file order**;
 *  - a part is filled from its own left edge by `percent * partWidth`;
 *  - when the *previous* part is not yet complete, the first 1 px of this part is
 *    left empty, which reads as a thin seam;
 *  - when the previous part **is** complete, the seam is dropped so the two parts
 *    join into one continuous bar — which is why a finished row looks solid.
 *
 * With `partCount <= 1` it degenerates into an ordinary horizontal bar.
 */
@Composable
fun SegmentProgressBar(
    partCount: Int,
    percents: List<Float>,
    overall: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        if (partCount <= 1) {
            drawRect(
                color = color,
                topLeft = Offset(0f, 0f),
                size = Size((w * overall).coerceIn(0f, w), h),
            )
            return@Canvas
        }

        val partW = w / partCount
        for (i in 0 until partCount) {
            val pct = percents.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            if (pct <= 0f) continue

            val left = i * partW
            val seamless = i == 0 || percents.getOrElse(i - 1) { 0f } >= 1f
            // Non-seamless parts start 1 px in, leaving the seam visible.
            val x = if (seamless) left else left + 1f
            drawRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(pct * partW, h),
            )
        }
    }
}

/**
 * Renders a segmented bar as the **row background** — the progress sits behind the
 * whole row rather than in a thin strip — with content layered on top.
 */
@Composable
fun SegmentedProgressBackground(
    partCount: Int,
    percents: List<Float>,
    overall: Float,
    progressColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(trackColor, size = size)
        }
        SegmentProgressBar(
            partCount = partCount,
            percents = percents,
            overall = overall,
            color = progressColor,
            modifier = Modifier.fillMaxSize(),
        )
        content()
    }
}
