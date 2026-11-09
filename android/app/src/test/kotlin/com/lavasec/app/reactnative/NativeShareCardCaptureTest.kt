package com.lavasec.app.reactnative

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeShareCardCaptureTest {
    @Test fun `capture waits for Fabric commitment then returns only that mounted result`() = runTest {
        var frames = 0
        val image = Any()
        assertSame(image, awaitMountedShareCard({ true }, { image.takeIf { frames == 3 } }, { frames++ }))
        assertEquals(3, frames)
    }
    @Test fun `owner retirement stops waiting and never captures the replacement`() = runTest {
        var frames = 0
        var attempts = 0
        assertNull(awaitMountedShareCard({ frames < 2 }, { attempts++; null as Any? }, { frames++ }))
        assertEquals(2, frames)
        assertEquals(2, attempts)
    }
    @Test fun `missing native surface has a bounded readiness deadline`() = runTest {
        var frames = 0
        var attempts = 0
        assertNull(awaitMountedShareCard({ true }, { attempts++; null as Any? }, { frames++ }))
        assertEquals(20, frames)
        assertEquals(21, attempts)
    }
}
