package com.lavasec.app.product

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The production [FilterShareDeviceHost]: binds `ActivityResultContracts.GetContent` (document),
 * `TakePicture` (camera), bounded image QR decoding (photo), and `RequestPermission` (`CAMERA`) onto an [ActivityResultRegistry].
 *
 * Lifecycle safety: the launchers are registered once against the registry (no `LifecycleOwner`
 * form, which throws once the owner is STARTED) and [dispose] unregisters them, so a host created
 * when the RN module first asks for a launch is torn down with its activity. Every callback is
 * one-shot and cleared before it runs, so a late result after [dispose] cannot reach the adapter.
 *
 * The camera path stays honestly unavailable unless both a fresh output [Uri] factory and a code
 * decoder are supplied: a captured image that cannot be decoded into a code is
 * [FilterShareDevicePick.Unavailable], never a fabricated candidate. A document is read bounded to
 * [FilterShareAcquisitionBounds] characters, so an oversized file reaches the adapter's rejection
 * path instead of being buffered whole.
 */
class AndroidFilterShareDeviceHost(
    private val context: Context,
    registry: ActivityResultRegistry,
    private val documentMimeType: String = "text/plain",
    private val cameraOutputUri: (() -> Uri?)? = null,
    private val decodeCameraImage: ((Uri) -> String?)? = null,
    private val decodeDocumentImage: ((Uri) -> String?)? = null,
) : FilterShareDeviceHost {

    private var documentCompletion: ((FilterShareDevicePick) -> Unit)? = null
    private var cameraCompletion: ((FilterShareDevicePick) -> Unit)? = null
    private var permissionCompletion: ((Boolean) -> Unit)? = null
    private var lastCameraUri: Uri? = null

    // Decoding a camera capture is CPU/IO-heavy; run it off the main thread and deliver the result
    // back on it. `disposed` stops a late delivery after the activity/registry is torn down.
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var disposed = false

    private val documentLauncher: ActivityResultLauncher<String> = registry.register(
        DOCUMENT_REQUEST_KEY,
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val completion = documentCompletion ?: return@register
        documentCompletion = null
        when {
            uri == null -> completion(FilterShareDevicePick.Cancelled)
            decodeDocumentImage == null -> completion(readDocument(uri))
            else -> decodeExecutor.execute {
                val pick = decodeImage(uri, decodeDocumentImage)
                mainHandler.post { if (!disposed) completion(pick) }
            }
        }
    }

    private val cameraLauncher: ActivityResultLauncher<Uri> = registry.register(
        CAMERA_REQUEST_KEY,
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val completion = cameraCompletion ?: return@register
        cameraCompletion = null
        val uri = lastCameraUri
        lastCameraUri = null
        when {
            !saved -> completion(FilterShareDevicePick.Cancelled)
            uri == null -> completion(FilterShareDevicePick.Unavailable)
            else -> decodeExecutor.execute {
                val pick = decodeCaptured(uri)
                // Deliver off the decode thread, and never after dispose.
                mainHandler.post { if (!disposed) completion(pick) }
            }
        }
    }

    private val permissionLauncher: ActivityResultLauncher<String> = registry.register(
        CAMERA_PERMISSION_REQUEST_KEY,
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val completion = permissionCompletion ?: return@register
        permissionCompletion = null
        completion(granted)
    }

    override fun canLaunchDocument(): Boolean = true

    override fun canLaunchCamera(): Boolean = cameraOutputUri != null && decodeCameraImage != null

    override fun cameraPermissionGranted(): Boolean {
        if (!cameraPermissionRequested()) return true
        return context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun requestCameraPermission(completion: (Boolean) -> Unit) {
        if (permissionCompletion != null) {
            completion(false)
            return
        }
        permissionCompletion = completion
        try {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } catch (_: Exception) {
            permissionCompletion = null
            completion(false)
        }
    }

    override fun launchDocument(completion: (FilterShareDevicePick) -> Unit) {
        if (documentCompletion != null) {
            completion(FilterShareDevicePick.Cancelled)
            return
        }
        documentCompletion = completion
        try {
            documentLauncher.launch(documentMimeType)
        } catch (_: Exception) {
            documentCompletion = null
            completion(FilterShareDevicePick.Unavailable)
        }
    }

    override fun launchCamera(completion: (FilterShareDevicePick) -> Unit) {
        val output = cameraOutputUri?.invoke() ?: run {
            completion(FilterShareDevicePick.Unavailable)
            return
        }
        if (cameraCompletion != null) {
            completion(FilterShareDevicePick.Cancelled)
            return
        }
        lastCameraUri = output
        cameraCompletion = completion
        try {
            cameraLauncher.launch(output)
        } catch (_: Exception) {
            cameraCompletion = null
            lastCameraUri = null
            completion(FilterShareDevicePick.Unavailable)
        }
    }

    override fun dispose() {
        disposed = true
        decodeExecutor.shutdownNow()
        runCatching { documentLauncher.unregister() }
        runCatching { cameraLauncher.unregister() }
        runCatching { permissionLauncher.unregister() }
        documentCompletion = null
        cameraCompletion = null
        permissionCompletion = null
        lastCameraUri = null
    }

    private fun readDocument(uri: Uri): FilterShareDevicePick =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                readBoundedText(stream.bufferedReader())
            }
        }.getOrNull()?.let(FilterShareDevicePick::Acquired)
            ?: FilterShareDevicePick.Unavailable

    private fun decodeCaptured(uri: Uri): FilterShareDevicePick {
        val decoder = decodeCameraImage ?: return FilterShareDevicePick.Unavailable
        return decodeImage(uri, decoder)
    }

    private fun decodeImage(uri: Uri, decoder: (Uri) -> String?): FilterShareDevicePick {
        val decoded = runCatching { decoder(uri) }.getOrNull()
        return if (decoded == null) {
            FilterShareDevicePick.Unavailable
        } else {
            FilterShareDevicePick.Acquired(decoded)
        }
    }

    @Suppress("DEPRECATION")
    private fun cameraPermissionRequested(): Boolean {
        val requested = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
        }.getOrNull()
        return requested?.contains(Manifest.permission.CAMERA) == true
    }

    private companion object {
        const val DOCUMENT_REQUEST_KEY = "lavasec.filter_share.document"
        const val CAMERA_REQUEST_KEY = "lavasec.filter_share.camera"
        const val CAMERA_PERMISSION_REQUEST_KEY = "lavasec.filter_share.camera_permission"

        /**
         * Read at most one character past the acquisition bound: a legitimate candidate is never
         * larger, so anything over the bound is reported whole enough for the adapter to reject
         * without buffering an unbounded file.
         */
        fun readBoundedText(reader: java.io.BufferedReader): String {
            val limit = FilterShareAcquisitionBounds.MAX_RAW_INPUT_CHARACTERS + 1
            val buffer = CharArray(limit)
            var total = 0
            while (total < limit) {
                val read = reader.read(buffer, total, limit - total)
                if (read <= 0) break
                total += read
            }
            return String(buffer, 0, total)
        }
    }
}
