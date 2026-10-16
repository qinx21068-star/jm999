package com.jmreader.ui.screen.reader

import com.jmreader.data.local.TapZoneMode

/** Tap coordinates are relative to the visible page, not an overlay that blocks image controls. */
internal fun readerTapDelta(fraction: Float, mode: TapZoneMode, reverse: Boolean): Int {
    val delta = when (mode) {
        TapZoneMode.DISABLED -> 0
        TapZoneMode.LEFT_RIGHT -> if (fraction < 0.5f) -1 else 1
        TapZoneMode.THIRD_THIRD -> when {
            fraction < 1f / 3f -> -1
            fraction > 2f / 3f -> 1
            else -> 0
        }
    }
    return if (reverse) -delta else delta
}
