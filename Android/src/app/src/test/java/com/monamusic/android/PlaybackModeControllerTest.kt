package com.monamusic.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeControllerTest {
    @Test
    fun `列表模式下下一首和上一首会循环`() {
        var persisted = -1
        val controller = PlaybackModeController(0) { persisted = it }
        controller.setMode(0, queueSize = 3, currentIndex = 0)
        assertEquals(0, persisted)
        assertEquals(0, controller.mode())
        assertEquals(0, controller.resolveNextIndex(2, 3))
        assertEquals(2, controller.resolvePrevIndex(0, 3))
    }

    @Test
    fun `随机模式不会越界且单曲场景稳定`() {
        val controller = PlaybackModeController(2) { }
        controller.resetShuffleRound(queueSize = 1, currentIndex = 0, markCurrent = true)
        assertEquals(0, controller.resolveNextIndex(0, 1))
        assertEquals(0, controller.resolvePrevIndex(0, 1))

        val next = controller.resolveNextIndex(1, 4)
        assertTrue(next in 0..3)
        val prev = controller.resolvePrevIndex(1, 4)
        assertTrue(prev in 0..3)
    }
}
