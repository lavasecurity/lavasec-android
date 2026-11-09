package com.lavasec.app.product

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The outcome of one device picker/scanner launch, before any bound or decode work. A host reports
 * the raw platform fact; the adapter classifies it into [FilterShareAcquisitionResult]. This mirrors
 * the [FilterShareCandidateSource] contract's "report an already-acquired result, never fabricate
 * one" rule, but keeps the cancellation/permission facts the Android contracts can produce.
 */
sealed interface FilterShareDevicePick {
    /** The picker/scanner produced text. It has not been decoded, bounded, or reviewed yet. */
    data class Acquired(val rawInput: String) : FilterShareDevicePick

    /** The user dismissed the picker/scanner. */
    data object Cancelled : FilterShareDevicePick

    /** The runtime permission this attempt needed was denied. */
    data object PermissionDenied : FilterShareDevicePick

    /** The host could not launch (no registry, no camera output/decoder, read failure). */
    data object Unavailable : FilterShareDevicePick
}

/**
 * The lifecycle-safe Android launch seam the device adapter drives. The production implementation
 * binds an `ActivityResultRegistry` with `ActivityResultContracts.GetContent`/`TakePicture`; tests
 * and non-device builds supply a fake. A host reports its own availability BEFORE any launch so the
 * adapter can return a typed refusal instead of launching into a dead registry, and it never
 * fabricates a pick: an absent camera/decoder is [FilterShareDevicePick.Unavailable].
 */
interface FilterShareDeviceHost {
    /** True when a live registry can launch the document picker. */
    fun canLaunchDocument(): Boolean

    /** True when the host can launch a camera capture AND decode it into a code payload. */
    fun canLaunchCamera(): Boolean

    /** `CAMERA` runtime-permission state; true when granted or the manifest never requested it. */
    fun cameraPermissionGranted(): Boolean

    /** Requests `CAMERA`; completion reports the granted state. Only called when not already granted. */
    fun requestCameraPermission(completion: (Boolean) -> Unit)

    /** Launches the document picker; completion carries the raw result. */
    fun launchDocument(completion: (FilterShareDevicePick) -> Unit)

    /** Launches the camera capture; completion carries the raw result. */
    fun launchCamera(completion: (FilterShareDevicePick) -> Unit)

    /** Releases any registered launchers. The default is a no-op for a stateless fake. */
    fun dispose() = Unit
}

/**
 * The production device picker/scanner adapter. It implements the existing [FilterShareInputPort]
 * (so it injects into [FilterShareOwner] with no new wiring) and the async
 * [FilterShareDeviceInputPort] (so the bridge can await a real launch). Everything here is pure:
 * the Android `ActivityResultContracts` plumbing lives behind [FilterShareDeviceHost], and every
 * produced `Acquired` is bounded by [boundFilterShareAcquisition] before it can reach review.
 *
 * The synchronous [documentAcquisition]/[cameraAcquisition] consume the one-shot result stored by a
 * prior [launchDocument]/[launchCamera]; when nothing was launched (or the launch was unavailable)
 * they return the existing typed [FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE] refusal — never
 * a fabricated candidate.
 */
class FilterShareDeviceAdapter(
    private val host: FilterShareDeviceHost,
    private val maxRawCharacters: Int = FilterShareAcquisitionBounds.MAX_RAW_INPUT_CHARACTERS,
) : FilterShareDeviceInputPort {
    private val lock = Any()
    private var pendingDocument: FilterShareAcquisitionResult? = null
    private var pendingCamera: FilterShareAcquisitionResult? = null

    /**
     * Launch the document picker and store its bounded outcome for the next
     * [documentAcquisition]. A host that cannot launch stores the typed refusal synchronously.
     */
    fun launchDocument() {
        if (!host.canLaunchDocument()) {
            store(isCamera = false, result = unavailable())
            return
        }
        host.launchDocument { pick -> store(isCamera = false, result = classify(pick)) }
    }

    /**
     * Launch the camera scanner, requesting `CAMERA` first when the host needs it, and store its
     * bounded outcome for the next [cameraAcquisition]. An absent camera capability, a denied
     * permission, or a cancelled capture is stored as its own refusal.
     */
    fun launchCamera() {
        if (!host.canLaunchCamera()) {
            store(isCamera = true, result = unavailable())
            return
        }
        if (!host.cameraPermissionGranted()) {
            host.requestCameraPermission { granted ->
                if (granted) {
                    host.launchCamera { pick -> store(isCamera = true, result = classify(pick)) }
                } else {
                    store(isCamera = true, result = FilterShareAcquisitionResult.Refused(
                        FilterShareAcquisitionRefusal.PERMISSION_DENIED,
                    ))
                }
            }
            return
        }
        host.launchCamera { pick -> store(isCamera = true, result = classify(pick)) }
    }

    override fun documentAcquisition(): FilterShareAcquisitionResult = consume(isCamera = false)

    override fun cameraAcquisition(): FilterShareAcquisitionResult = consume(isCamera = true)

    override suspend fun requestDocumentAcquisition(): FilterShareAcquisitionResult {
        if (!host.canLaunchDocument()) return unavailable()
        return classify(awaitPick(host::launchDocument))
    }

    override suspend fun requestCameraAcquisition(): FilterShareAcquisitionResult {
        if (!host.canLaunchCamera()) return unavailable()
        if (!host.cameraPermissionGranted() && !awaitPermission(host::requestCameraPermission)) {
            return FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.PERMISSION_DENIED)
        }
        return classify(awaitPick(host::launchCamera))
    }

    override fun dispose() {
        host.dispose()
        synchronized(lock) {
            pendingDocument = null
            pendingCamera = null
        }
    }

    private fun classify(pick: FilterShareDevicePick): FilterShareAcquisitionResult = when (pick) {
        is FilterShareDevicePick.Acquired -> boundFilterShareAcquisition(
            result = FilterShareAcquisitionResult.Candidate(
                FilterShareImportCandidate(pick.rawInput),
            ),
            maxRawCharacters = maxRawCharacters,
        )
        FilterShareDevicePick.Cancelled ->
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.CANCELLED)
        FilterShareDevicePick.PermissionDenied ->
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.PERMISSION_DENIED)
        FilterShareDevicePick.Unavailable -> unavailable()
    }

    private fun unavailable(): FilterShareAcquisitionResult =
        FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE)

    private fun store(isCamera: Boolean, result: FilterShareAcquisitionResult) {
        synchronized(lock) {
            if (isCamera) pendingCamera = result else pendingDocument = result
        }
    }

    private fun consume(isCamera: Boolean): FilterShareAcquisitionResult = synchronized(lock) {
        val stored = if (isCamera) pendingCamera else pendingDocument
        if (isCamera) pendingCamera = null else pendingDocument = null
        stored ?: unavailable()
    }

    private suspend fun awaitPick(
        launch: ((FilterShareDevicePick) -> Unit) -> Unit,
    ): FilterShareDevicePick = suspendCancellableCoroutine { continuation ->
        launch { pick -> if (continuation.isActive) continuation.resume(pick) }
    }

    private suspend fun awaitPermission(
        request: ((Boolean) -> Unit) -> Unit,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        request { granted -> if (continuation.isActive) continuation.resume(granted) }
    }
}
