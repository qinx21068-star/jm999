package com.jmreader.ui.screen.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

internal data class ReaderTransform(
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
)

internal data class ReaderTransformDelta(
    val transform: ReaderTransform,
    /** Finger motion left over at the viewport edge, in screen pixels. */
    val remainingPan: Offset,
)

internal fun boundReaderOffset(offset: Offset, scale: Float, viewport: IntSize): Offset {
    val halfExtraWidth = viewport.width.coerceAtLeast(0) * (scale - 1f).coerceAtLeast(0f) / 2f
    val halfExtraHeight = viewport.height.coerceAtLeast(0) * (scale - 1f).coerceAtLeast(0f) / 2f
    return Offset(offset.x.coerceIn(-halfExtraWidth, halfExtraWidth), offset.y.coerceIn(-halfExtraHeight, halfExtraHeight))
}

/** Keep the point under the fingers stable while applying zoom and pan in the same event. */
internal fun transformReader(
    current: ReaderTransform,
    zoom: Float,
    pan: Offset,
    centroid: Offset,
    viewport: IntSize,
): ReaderTransformDelta {
    val scale = (current.scale * zoom).coerceIn(1f, 5f)
    val ratio = scale / current.scale
    val focus = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
    // Zoom-induced clamping is not a scroll gesture; only actual leftover pan is forwarded.
    val anchored = boundReaderOffset((current.offset - focus) * ratio + focus, scale, viewport)
    val offset = boundReaderOffset(anchored + pan, scale, viewport)
    return ReaderTransformDelta(ReaderTransform(scale, offset), pan - (offset - anchored))
}
