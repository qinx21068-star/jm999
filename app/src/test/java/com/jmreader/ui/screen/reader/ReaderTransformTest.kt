package com.jmreader.ui.screen.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.jmreader.data.local.TapZoneMode
import org.junit.Assert.*
import org.junit.Test

class ReaderTransformTest {
    private val viewport = IntSize(400, 800)
    private val center = Offset(200f, 400f)
    private fun near(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    @Test fun zoomAndVerticalPanAreAppliedInTheSameEvent() {
        val result = transformReader(ReaderTransform(), 2f, Offset(20f, 90f), center, viewport)
        assertEquals(2f, result.transform.scale, 0.001f)
        near(Offset(20f, 90f), result.transform.offset)
        near(Offset.Zero, result.remainingPan)
    }

    @Test fun zoomPreservesTheContentUnderAnOffCenterPinch() {
        val focus = Offset(300f, 500f)
        val result = transformReader(ReaderTransform(), 2f, Offset.Zero, focus, viewport)
        near(Offset(-100f, -100f), result.transform.offset)
        near(Offset.Zero, result.remainingPan)
    }

    @Test fun onlyPanPastTheEdgeFlowsIntoVerticalScrolling() {
        val result = transformReader(ReaderTransform(2f, Offset(0f, 390f)), 1f, Offset(0f, 40f), center, viewport)
        near(Offset(0f, 400f), result.transform.offset)
        near(Offset(0f, 30f), result.remainingPan)
        val opposite = transformReader(result.transform, 1f, Offset(0f, -25f), center, viewport)
        near(Offset(0f, 375f), opposite.transform.offset)
        near(Offset.Zero, opposite.remainingPan)
    }

    @Test fun twoFingerPanAtOriginalSizeStillScrollsTheContent() {
        val result = transformReader(ReaderTransform(), 1f, Offset(0f, -80f), center, viewport)
        near(Offset.Zero, result.transform.offset)
        near(Offset(0f, -80f), result.remainingPan)
    }

    @Test fun zoomClampingDoesNotInventScrollMotion() {
        val result = transformReader(ReaderTransform(3f, Offset(200f, 600f)), 0.1f, Offset.Zero, center, viewport)
        assertEquals(1f, result.transform.scale, 0.001f)
        near(Offset.Zero, result.transform.offset)
        near(Offset.Zero, result.remainingPan)
        assertEquals(5f, transformReader(ReaderTransform(), 99f, Offset.Zero, center, viewport).transform.scale, 0.001f)
    }

    @Test fun resizedViewportBoundsAreSafeIncludingZeroSize() {
        near(Offset.Zero, boundReaderOffset(Offset(100f, -100f), 2f, IntSize.Zero))
        near(Offset(100f, -200f), boundReaderOffset(Offset(999f, -999f), 2f, IntSize(200, 400)))
    }

    @Test fun tapZonesKeepReverseReadingAndCenterToolbarSemantics() {
        assertEquals(-1, readerTapDelta(0.1f, TapZoneMode.THIRD_THIRD, false))
        assertEquals(0, readerTapDelta(0.5f, TapZoneMode.THIRD_THIRD, true))
        assertEquals(-1, readerTapDelta(0.9f, TapZoneMode.THIRD_THIRD, true))
        assertEquals(1, readerTapDelta(0.1f, TapZoneMode.LEFT_RIGHT, true))
        assertEquals(0, readerTapDelta(0.1f, TapZoneMode.DISABLED, false))
    }
}
