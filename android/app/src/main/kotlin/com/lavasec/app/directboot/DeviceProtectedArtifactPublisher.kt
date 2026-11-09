package com.lavasec.app.directboot

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactOpenResult
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.platform.DirectBootAppState
import com.lavasec.app.platform.DirectBootCommittedSnapshotPublication
import com.lavasec.app.platform.DirectBootConfigurationSnapshot
import com.lavasec.app.platform.FilterArtifactReference
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Fail-closed publisher for exact filter bytes and their authenticated public behavior snapshot.
 *
 * The final snapshot rename is the only availability point. Every failure removes that snapshot;
 * immutable artifact generations without a snapshot are inert.
 */
internal class DeviceProtectedArtifactPublisher(
    private val root: File,
    private val directorySync: (File) -> Unit = ::forceDirectory,
) {
    fun publish(
        state: DirectBootAppState,
        prepared: PreparedFilterArtifact,
        committedPublication: DirectBootCommittedSnapshotPublication,
    ): Boolean = synchronized(DirectBootRootLocks.forRoot(root)) {
        if (state.credentialUnlockRequired) {
            invalidateLocked()
            return@synchronized false
        }
        val reference = stageLocked(prepared) ?: return@synchronized false
        publishReferenceLocked(state, reference, committedPublication)
    }

    /** Copies and exactly verifies an immutable public artifact without making it available. */
    fun stage(prepared: PreparedFilterArtifact): FilterArtifactReference? =
        synchronized(DirectBootRootLocks.forRoot(root)) { stageLocked(prepared) }

    /** Publishes one already-staged exact generation through the fresh one-shot capability. */
    fun publishReference(
        state: DirectBootAppState,
        reference: FilterArtifactReference,
        committedPublication: DirectBootCommittedSnapshotPublication,
    ): Boolean = synchronized(DirectBootRootLocks.forRoot(root)) {
        publishReferenceLocked(state, reference, committedPublication)
    }

    fun invalidate() = synchronized(DirectBootRootLocks.forRoot(root)) { invalidateLocked() }

    private fun stageLocked(prepared: PreparedFilterArtifact): FilterArtifactReference? {
        requireDirectories()

        val temporaryArtifact = File(
            DirectBootStorageLayout.artifacts(root),
            ".artifact-${UUID.randomUUID()}.tmp",
        )
        return try {
            copyAndSync(prepared.file, temporaryArtifact)
            if (FilterArtifact.sha256(temporaryArtifact) != prepared.sha256) {
                return null
            }
            when (val opened = FilterArtifact.openResult(temporaryArtifact, prepared.sha256)) {
                is FilterArtifactOpenResult.Available -> opened.artifact.use { artifact ->
                    if (artifact.identity != prepared.identity) return null
                }
                is FilterArtifactOpenResult.Unavailable -> return null
            }

            val artifactTarget = DirectBootStorageLayout.artifact(root, prepared.sha256)
            atomicReplace(temporaryArtifact, artifactTarget)
            directorySync(DirectBootStorageLayout.artifacts(root))
            FilterArtifactReference(prepared.sha256, prepared.identity)
        } catch (_: Exception) {
            null
        } finally {
            temporaryArtifact.delete()
            cleanupOwnedTemporaries()
        }
    }

    private fun publishReferenceLocked(
        state: DirectBootAppState,
        reference: FilterArtifactReference,
        committedPublication: DirectBootCommittedSnapshotPublication,
    ): Boolean {
        if (state.credentialUnlockRequired) {
            invalidateLocked()
            return false
        }
        requireDirectories()
        invalidateLocked()
        var temporarySnapshot: File? = null
        return try {
            val configuration = state.configurationIdentity ?: return false
            val expectedConfiguration = ArtifactConfigurationIdentity(
                configuration.generation,
                configuration.fingerprint,
            )
            if (reference.artifactIdentity.configuration != expectedConfiguration) return false
            val artifactFile = DirectBootStorageLayout.artifact(root, reference.sha256)
            when (val opened = FilterArtifact.openResult(artifactFile, reference.sha256)) {
                is FilterArtifactOpenResult.Available -> opened.artifact.use { artifact ->
                    if (artifact.identity != reference.artifactIdentity) return false
                }
                is FilterArtifactOpenResult.Unavailable -> return false
            }
            val persisted = DirectBootConfigurationSnapshot.sealForPublication(
                state = state,
                artifact = reference,
                committedPublication = committedPublication,
            )
            val snapshotBytes = DirectBootSnapshotCodec.encode(persisted)
            temporarySnapshot = File(root, ".snapshot-${UUID.randomUUID()}.tmp")
            writeAndSync(checkNotNull(temporarySnapshot), snapshotBytes)
            atomicReplace(checkNotNull(temporarySnapshot), DirectBootStorageLayout.snapshot(root))
            temporarySnapshot = null
            directorySync(root)
            true
        } catch (_: Exception) {
            invalidateLocked()
            false
        } finally {
            temporarySnapshot?.delete()
            cleanupOwnedTemporaries()
        }
    }

    private fun requireDirectories() {
        check((root.isDirectory || root.mkdirs()) && root.isDirectory)
        val artifacts = DirectBootStorageLayout.artifacts(root)
        check((artifacts.isDirectory || artifacts.mkdirs()) && artifacts.isDirectory)
    }

    private fun invalidateLocked() {
        val snapshot = DirectBootStorageLayout.snapshot(root)
        if (Files.deleteIfExists(snapshot.toPath()) && root.isDirectory) directorySync(root)
    }

    private fun copyAndSync(source: File, target: File) {
        FileOutputStream(target).use { output ->
            source.inputStream().buffered(64 * 1024).use { input ->
                input.copyTo(output, 64 * 1024)
            }
            output.flush()
            output.fd.sync()
        }
    }

    private fun writeAndSync(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun cleanupOwnedTemporaries() {
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".tmp") }
            .forEach(File::delete)
    }
}

private fun forceDirectory(directory: File) {
    check(directory.isDirectory)
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
