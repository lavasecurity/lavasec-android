package com.lavasec.app.reactnative

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.lavasec.app.product.FilterShareDeviceAdapter
import com.lavasec.app.product.FilterShareDeviceHost
import com.lavasec.app.product.FilterShareDevicePick

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeImportVisitTest {
    @Test fun `retiring permission camera or picker acquisition releases its mutation lane without a platform callback`() = runTest {
        for (stage in listOf("permission", "camera", "document")) {
            val host = NativeVisitHost(Any(), "host") { true }
            val visit = NativeImportVisit(host, 7L)
            val lane = NativeCommandAdmission()
            var late: (() -> Unit)? = null
            var disposed = false
            val adapter = FilterShareDeviceAdapter(object : FilterShareDeviceHost {
                override fun canLaunchDocument() = true
                override fun canLaunchCamera() = true
                override fun cameraPermissionGranted() = stage != "permission"
                override fun requestCameraPermission(completion: (Boolean) -> Unit) { late = { completion(true) } }
                override fun launchDocument(completion: (FilterShareDevicePick) -> Unit) { late = { completion(FilterShareDevicePick.Acquired("late")) } }
                override fun launchCamera(completion: (FilterShareDevicePick) -> Unit) { late = { completion(FilterShareDevicePick.Acquired("late")) } }
                override fun dispose() { disposed = true }
            })
            val pending = async { runCatching { lane.run("native.flow") {
                try { visit.acquire {
                    if (stage == "document") adapter.requestDocumentAcquisition() else adapter.requestCameraAcquisition()
                } } finally { adapter.dispose() }
            } } }
            runCurrent(); assertNotNull(late)
            assertNull(lane.run("filter.apply") { true })
            assertTrue(visit.retireHost(host.owner, host.nativeHostID))
            runCurrent(); assertTrue(pending.await().isFailure); assertTrue(disposed)
            late?.invoke(); runCurrent()
            val successor = NativeImportVisit(host, 7L)
            assertEquals(true, lane.run("native.flow") { successor.acquire { true } })
            assertTrue(successor.isCurrent(host, 7L))
            var launched = false
            assertTrue(runCatching { visit.acquire { launched = true } }.isFailure)
            assertFalse(launched)
        }
    }

    @Test fun `retired visit cannot acquire or publish and cannot retire its successor`() {
        val host = NativeVisitHost(Any(), "host") { true }
        val old = NativeImportVisit(host, 7L)
        val next = NativeImportVisit(host, 7L)
        old.retire()
        assertFalse(old.isCurrent(host, 7L))
        assertTrue(next.isCurrent(host, 7L))
        assertNotEquals(old.id, next.id)
    }
    @Test fun `same host label cannot adopt another activity or graph`() {
        val owner = Any()
        val host = NativeVisitHost(owner, "host") { true }
        val visit = NativeImportVisit(host, 7L)
        assertFalse(visit.isCurrent(NativeVisitHost(Any(), "host") { true }, 7L))
        assertFalse(visit.isCurrent(host, 8L))
        assertFalse(visit.isCurrent(null, 7L))
        assertTrue(visit.isCurrent(NativeVisitHost(owner, "host") { true }, 7L))
    }
    @Test fun `late destruction of another host cannot retire the current visit`() {
        val owner = Any()
        val host = NativeVisitHost(owner, "host") { true }
        val visit = NativeImportVisit(host, 7L)
        assertFalse(visit.retireHost(Any(), "host"))
        assertFalse(visit.retireHost(owner, "previous-window"))
        assertTrue(visit.isCurrent(host, 7L))
        assertTrue(visit.retireHost(owner, "host"))
        assertFalse(visit.isCurrent(host, 7L))
    }
}
