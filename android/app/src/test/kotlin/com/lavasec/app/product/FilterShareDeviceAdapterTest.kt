package com.lavasec.app.product

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for the production device picker/scanner seam. The `ActivityResultContracts`
 * plumbing lives behind [FilterShareDeviceHost] (unexecutable on the aarch64 work host when
 * Robolectric cannot run), so these tests drive a fake host and assert the adapter's real
 * classification, bounding, permission handling, and typed refusals — never a fabricated
 * camera/QR success.
 */
class FilterShareDeviceAdapterTest {
    private class FakeHost(
        private val documentLaunchable: Boolean = true,
        private val cameraLaunchable: Boolean = true,
        private val cameraGranted: Boolean = true,
        private val permissionResult: Boolean = true,
        private val documentPick: FilterShareDevicePick = FilterShareDevicePick.Cancelled,
        private val cameraPick: FilterShareDevicePick = FilterShareDevicePick.Cancelled,
    ) : FilterShareDeviceHost {
        var disposed = false
        var permissionRequests = 0
        override fun canLaunchDocument(): Boolean = documentLaunchable
        override fun canLaunchCamera(): Boolean = cameraLaunchable
        override fun cameraPermissionGranted(): Boolean = cameraGranted
        override fun requestCameraPermission(completion: (Boolean) -> Unit) {
            permissionRequests++
            completion(permissionResult)
        }
        override fun launchDocument(completion: (FilterShareDevicePick) -> Unit) {
            completion(documentPick)
        }
        override fun launchCamera(completion: (FilterShareDevicePick) -> Unit) {
            completion(cameraPick)
        }
        override fun dispose() {
            disposed = true
        }
    }

    private fun adapter(host: FakeHost): FilterShareDeviceAdapter = FilterShareDeviceAdapter(host)

    @Test
    fun `an unavailable host refuses both acquisitions with the existing typed refusal`() {
        val adapter = adapter(FakeHost(documentLaunchable = false, cameraLaunchable = false))

        val unavailable = FilterShareAcquisitionResult.Refused(
            FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE,
        )
        assertEquals(unavailable, adapter.documentAcquisition())
        assertEquals(unavailable, adapter.cameraAcquisition())
        assertEquals(unavailable, runBlocking { adapter.requestDocumentAcquisition() })
        assertEquals(unavailable, runBlocking { adapter.requestCameraAcquisition() })
    }

    @Test
    fun `an acquired document maps to a bounded candidate and is consumed once`() {
        val raw = "LF1-ABC123"
        val adapter = adapter(FakeHost(documentPick = FilterShareDevicePick.Acquired(raw)))

        adapter.launchDocument()

        assertEquals(
            FilterShareAcquisitionResult.Candidate(FilterShareImportCandidate(raw)),
            adapter.documentAcquisition(),
        )
        // A one-shot store: the same launch is never reported twice.
        assertEquals(
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE),
            adapter.documentAcquisition(),
        )
    }

    @Test
    fun `an async document request awaits the host and returns the candidate`() {
        val raw = "LF1-ASYNC"
        val adapter = adapter(FakeHost(documentPick = FilterShareDevicePick.Acquired(raw)))

        val result = runBlocking { adapter.requestDocumentAcquisition() }

        assertEquals(
            FilterShareAcquisitionResult.Candidate(FilterShareImportCandidate(raw)),
            result,
        )
    }

    @Test
    fun `an oversized document is rejected before any review`() {
        val oversized = "A".repeat(FilterShareAcquisitionBounds.MAX_RAW_INPUT_CHARACTERS + 1)
        val adapter = adapter(FakeHost(documentPick = FilterShareDevicePick.Acquired(oversized)))

        adapter.launchDocument()

        assertEquals(
            FilterShareAcquisitionResult.Rejected(FilterShareImportFailureReason.PAYLOAD_TOO_LARGE),
            adapter.documentAcquisition(),
        )
    }

    @Test
    fun `a cancelled picker is a typed refusal and never a candidate`() {
        val adapter = adapter(FakeHost(documentPick = FilterShareDevicePick.Cancelled))

        adapter.launchDocument()

        assertEquals(
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.CANCELLED),
            adapter.documentAcquisition(),
        )
    }

    @Test
    fun `a denied camera permission is a typed refusal and never a candidate`() {
        val host = FakeHost(
            cameraGranted = false,
            permissionResult = false,
            cameraPick = FilterShareDevicePick.Acquired("LF1-SHOULD_NOT_BE_USED"),
        )
        val adapter = adapter(host)

        adapter.launchCamera()

        assertEquals(1, host.permissionRequests)
        assertEquals(
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.PERMISSION_DENIED),
            adapter.cameraAcquisition(),
        )
    }

    @Test
    fun `a granted camera permission proceeds to the camera capture result`() {
        val raw = "LF1-CAMERA"
        val host = FakeHost(
            cameraGranted = false,
            permissionResult = true,
            cameraPick = FilterShareDevicePick.Acquired(raw),
        )
        val adapter = adapter(host)

        adapter.launchCamera()

        assertEquals(1, host.permissionRequests)
        assertEquals(
            FilterShareAcquisitionResult.Candidate(FilterShareImportCandidate(raw)),
            adapter.cameraAcquisition(),
        )
    }

    @Test
    fun `a camera host without an output decoder is unavailable before permission is requested`() {
        val host = FakeHost(
            cameraLaunchable = false,
            cameraGranted = false,
            permissionResult = true,
            cameraPick = FilterShareDevicePick.Acquired("LF1-UNREACHABLE"),
        )
        val adapter = adapter(host)

        adapter.launchCamera()

        assertEquals(0, host.permissionRequests)
        assertEquals(
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE),
            adapter.cameraAcquisition(),
        )
    }

    @Test
    fun `an async permission request path mirrors the synchronous decision`() {
        val host = FakeHost(
            cameraGranted = false,
            permissionResult = false,
            cameraPick = FilterShareDevicePick.Acquired("LF1-UNREACHABLE"),
        )
        val adapter = adapter(host)

        val result = runBlocking { adapter.requestCameraAcquisition() }

        assertEquals(1, host.permissionRequests)
        assertEquals(
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.PERMISSION_DENIED),
            result,
        )
    }

    @Test
    fun `dispose releases the host and clears any pending result`() {
        val host = FakeHost(documentPick = FilterShareDevicePick.Acquired("LF1-PENDING"))
        val adapter = adapter(host)
        adapter.launchDocument()
        assertTrue(adapter.documentAcquisition() is FilterShareAcquisitionResult.Candidate)

        adapter.launchDocument()
        adapter.dispose()

        assertTrue(host.disposed)
        assertEquals(
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE),
            adapter.documentAcquisition(),
        )
        assertFalse(adapter.documentAcquisition() is FilterShareAcquisitionResult.Candidate)
    }
}
