package com.lavasec.app.storage

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.ExpectedArtifactSource
import com.lavasec.app.foundation.filter.FilterArtifactExpectation
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.foundation.filter.canonicalFilterArtifactIdentitySha256
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CredentialFilterArtifactPublicationStoreTest {
    @Test
    fun `descriptor close failure replaces a successful read`() {
        val descriptor = java.io.FileDescriptor()
        val closeFailure = IOException("injected record descriptor close failure")
        var closeAttempts = 0

        val thrown = assertThrows(IOException::class.java) {
            withDescriptorClose(
                descriptor = descriptor,
                closer = AndroidOsDescriptorCloser {
                    closeAttempts += 1
                    throw closeFailure
                },
            ) {
                byteArrayOf(1, 2, 3)
            }
        }

        assertSame(closeFailure, thrown)
        assertEquals(1, closeAttempts)
    }

    @Test
    fun `descriptor close failure is suppressed onto a primary read failure`() {
        val descriptor = java.io.FileDescriptor()
        val readFailure = IOException("injected record read failure")
        val closeFailure = IOException("injected record descriptor close failure")
        var closeAttempts = 0

        val thrown = assertThrows(IOException::class.java) {
            withDescriptorClose(
                descriptor = descriptor,
                closer = AndroidOsDescriptorCloser {
                    closeAttempts += 1
                    throw closeFailure
                },
            ) {
                throw readFailure
            }
        }

        assertSame(readFailure, thrown)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
        assertEquals(1, closeAttempts)
    }

    @Test
    fun `bounded host reads return exact regular bytes and reject overflow or symlinks`() {
        val root = Files.createTempDirectory("artifact-publication-bounded-read-").toFile()
        try {
            val regular = root.resolve("record").also {
                it.writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4),
                JvmArtifactPublicationFileSystem.readBoundedRegularFile(
                    regular,
                    maxBytes = 4,
                ),
            )
            assertThrows(IOException::class.java) {
                JvmArtifactPublicationFileSystem.readBoundedRegularFile(
                    regular,
                    maxBytes = 3,
                )
            }

            val link = root.resolve("record-link")
            Files.createSymbolicLink(link.toPath(), regular.toPath())
            assertThrows(IOException::class.java) {
                JvmArtifactPublicationFileSystem.readBoundedRegularFile(
                    link,
                    maxBytes = 4,
                )
            }
            assertThrows(IOException::class.java) {
                JvmArtifactPublicationFileSystem.readBoundedRegularFile(
                    root,
                    maxBytes = 4,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `install writes an exact generation without creating a pointer or closing caller output`() =
        runStore { root, authority ->
            val prepared = compile(
                root,
                generation = 1,
                blockedRules = listOf(DomainRule.parse("blocked.example")),
            )
            try {
                val phases = mutableListOf<CredentialFilterArtifactPublicationPhase>()
                val store = store(root, authority) { phases += it }

                val reference = install(store, prepared)

                assertEquals(
                    listOf(
                        CredentialFilterArtifactPublicationPhase.STAGING_DIRECTORY_CREATED,
                        CredentialFilterArtifactPublicationPhase.ARTIFACT_COPIED_AND_SYNCED,
                        CredentialFilterArtifactPublicationPhase.STAGED_ARTIFACT_VERIFIED,
                        CredentialFilterArtifactPublicationPhase.MANIFEST_WRITTEN_AND_SYNCED,
                        CredentialFilterArtifactPublicationPhase.WAITING_FOR_AUTHORITY,
                        CredentialFilterArtifactPublicationPhase.AUTHORITY_ACQUIRED,
                        CredentialFilterArtifactPublicationPhase.GENERATION_INSTALLED,
                        CredentialFilterArtifactPublicationPhase.FINAL_GENERATION_VERIFIED,
                    ),
                    phases,
                )
                assertEquals(prepared.sha256, reference.sha256)
                assertEquals(prepared.identity, reference.artifactIdentity)
                assertFalse(store.currentPointerFileForTesting.exists())
                assertTrue(prepared.file.exists())

                val exact = store.readExactAvailable(prepared, maxRules = 1)
                try {
                    assertEquals(reference, exact.reference)
                    assertEquals(prepared.identity, exact.artifact.identity)
                    assertTrue(exact.artifact.channelIsOpenForTesting)
                } finally {
                    exact.close()
                }
            } finally {
                prepared.close()
            }
        }

    @Test
    fun `install preserves a live pointer and exact reads ignore later pointer movement`() =
        runStore { root, authority ->
            val selected = compile(root, generation = 1)
            val installedOnly = compile(root, generation = 2)
            val laterSelected = compile(root, generation = 3)
            try {
                val store = store(root, authority)
                publish(store, selected, expectedCurrentSha256 = null)
                val originalPointer = store.currentPointerFileForTesting.readBytes()

                install(store, installedOnly)

                assertTrue(
                    originalPointer.contentEquals(store.currentPointerFileForTesting.readBytes()),
                )
                publish(store, laterSelected, expectedCurrentSha256 = selected.sha256)
                val laterPointer = store.currentPointerFileForTesting.readBytes()
                store.currentPointerFileForTesting.writeBytes(byteArrayOf(0))
                store.readExactAvailable(installedOnly, maxRules = 0).close()
                store.currentPointerFileForTesting.writeBytes(laterPointer)
                store.readAvailable(expectation(laterSelected), maxRules = 0).close()
            } finally {
                laterSelected.close()
                installedOnly.close()
                selected.close()
            }
        }

    @Test
    fun `install reuses an exact immutable generation without rewriting it`() =
        runStore { root, authority ->
            val prepared = compile(root, generation = 1)
            try {
                val store = store(root, authority)
                install(store, prepared)
                val artifact = File(
                    store.generationDirectoryForTesting(prepared.sha256),
                    "artifact.lsfa",
                )
                val originalBytes = artifact.readBytes()
                assertTrue(artifact.setLastModified(1_234_567_890L))
                val preservedTimestamp = artifact.lastModified()

                install(store, prepared)

                assertEquals(preservedTimestamp, artifact.lastModified())
                assertTrue(originalBytes.contentEquals(artifact.readBytes()))
                assertFalse(store.currentPointerFileForTesting.exists())
            } finally {
                prepared.close()
            }
        }

    @Test
    fun `install retry durably admits a generation after one parent sync failure`() =
        runStore { root, authority ->
            val prepared = compile(root, generation = 1)
            val fileSystem = GenerationSyncFailingFileSystem(root, failures = 1)
            try {
                val store = store(root, authority, fileSystem = fileSystem)

                val first = store.install(prepared)
                val retry = store.install(prepared)

                assertTrue(first is CredentialFilterArtifactInstallResult.Failed)
                assertTrue(retry is CredentialFilterArtifactInstallResult.Installed)
                assertEquals(2, fileSystem.generationSyncAttempts)
                assertTrue(store.generationDirectoryForTesting(prepared.sha256).isDirectory)
                assertFalse(store.currentPointerFileForTesting.exists())
            } finally {
                prepared.close()
            }
        }

    @Test
    fun `reused generation stays unavailable while parent durability cannot be proved`() =
        runStore { root, authority ->
            val prepared = compile(root, generation = 1)
            val fileSystem = GenerationSyncFailingFileSystem(root, failures = 2)
            try {
                val store = store(root, authority, fileSystem = fileSystem)

                val first = store.install(prepared)
                val retry = store.install(prepared)

                assertTrue(first is CredentialFilterArtifactInstallResult.Failed)
                assertTrue(retry is CredentialFilterArtifactInstallResult.Failed)
                assertEquals(2, fileSystem.generationSyncAttempts)
                assertTrue(store.generationDirectoryForTesting(prepared.sha256).isDirectory)
                assertFalse(store.currentPointerFileForTesting.exists())
            } finally {
                prepared.close()
            }
        }

    @Test
    fun `install rejects a corrupt existing generation and leaves pointer untouched`() =
        runStore { root, authority ->
            val selected = compile(root, generation = 1)
            val installedOnly = compile(root, generation = 2)
            try {
                val store = store(root, authority)
                publish(store, selected, expectedCurrentSha256 = null)
                install(store, installedOnly)
                val originalPointer = store.currentPointerFileForTesting.readBytes()
                File(
                    store.generationDirectoryForTesting(installedOnly.sha256),
                    "artifact.lsfa",
                ).appendBytes(byteArrayOf(0))

                val result = store.install(installedOnly)

                assertTrue(result is CredentialFilterArtifactInstallResult.Failed)
                assertTrue(
                    originalPointer.contentEquals(store.currentPointerFileForTesting.readBytes()),
                )
                store.readAvailable(expectation(selected), maxRules = 0).close()
            } finally {
                installedOnly.close()
                selected.close()
            }
        }

    @Test
    fun `install failure cleans staging without taking caller ownership`() =
        runStore { root, authority ->
            val prepared = compile(root, generation = 1)
            try {
                val store = store(root, authority) { phase ->
                    if (
                        phase ==
                        CredentialFilterArtifactPublicationPhase.STAGED_ARTIFACT_VERIFIED
                    ) {
                        throw IOException("injected staging failure")
                    }
                }

                val result = store.install(prepared)

                assertTrue(result is CredentialFilterArtifactInstallResult.Failed)
                assertTrue(root.resolve("staging").listFiles().orEmpty().isEmpty())
                assertFalse(
                    store.generationDirectoryForTesting(prepared.sha256).exists(),
                )
                assertFalse(store.currentPointerFileForTesting.exists())
                assertTrue(prepared.file.exists())
            } finally {
                prepared.close()
            }
        }

    @Test
    fun `cancelled install waiting for authority leaves no generation or pointer`() =
        runStore { root, authority ->
            val prepared = compile(root, generation = 1)
            val waiting = CompletableDeferred<Unit>()
            try {
                val store = store(root, authority) { phase ->
                    if (
                        phase ==
                        CredentialFilterArtifactPublicationPhase.WAITING_FOR_AUTHORITY
                    ) {
                        waiting.complete(Unit)
                    }
                }
                authority.mutex.lock()
                try {
                    coroutineScope {
                        val installation = async(Dispatchers.Default) {
                            store.install(prepared)
                        }
                        withTimeout(5_000) { waiting.await() }

                        installation.cancelAndJoin()

                        assertTrue(installation.isCancelled)
                    }
                } finally {
                    authority.mutex.unlock()
                }

                assertTrue(root.resolve("staging").listFiles().orEmpty().isEmpty())
                assertFalse(
                    store.generationDirectoryForTesting(prepared.sha256).exists(),
                )
                assertFalse(store.currentPointerFileForTesting.exists())
                assertTrue(prepared.file.exists())
            } finally {
                if (authority.mutex.isLocked) {
                    runCatching { authority.mutex.unlock() }
                }
                prepared.close()
            }
        }

    @Test
    fun `exact read rejects substituted identity commitment and enforces trusted rule budget`() =
        runStore { root, authority ->
            val prepared = compile(
                root,
                generation = 1,
                blockedRules = listOf(DomainRule.parse("blocked.example")),
            )
            try {
                val store = store(root, authority)
                install(store, prepared)

                assertUnavailable(
                    FilterArtifactUnavailableReason.IDENTITY_MISMATCH,
                    store.readExactGeneration(
                        artifactSha256 = prepared.sha256,
                        identitySha256 = "0".repeat(64),
                        maxRules = 1,
                    ),
                )
                assertUnavailable(
                    FilterArtifactUnavailableReason.ARTIFACT_MISSING,
                    store.readExactGeneration(
                        artifactSha256 = "0".repeat(64),
                        identitySha256 =
                            canonicalFilterArtifactIdentitySha256(prepared.identity),
                        maxRules = 1,
                    ),
                )
                assertUnavailable(
                    FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
                    store.readExactGeneration(
                        artifactSha256 = prepared.sha256,
                        identitySha256 =
                            canonicalFilterArtifactIdentitySha256(prepared.identity),
                        maxRules = 0,
                    ),
                )
                assertUnavailable(
                    FilterArtifactUnavailableReason.POINTER_INVALID,
                    store.readExactGeneration(
                        artifactSha256 = prepared.sha256.uppercase(),
                        identitySha256 =
                            canonicalFilterArtifactIdentitySha256(prepared.identity),
                        maxRules = 1,
                    ),
                )
                File(
                    store.generationDirectoryForTesting(prepared.sha256),
                    "artifact.lsfa",
                ).appendBytes(byteArrayOf(0))
                assertUnavailable(
                    FilterArtifactUnavailableReason.DIGEST_MISMATCH,
                    store.readExactGeneration(
                        artifactSha256 = prepared.sha256,
                        identitySha256 =
                            canonicalFilterArtifactIdentitySha256(prepared.identity),
                        maxRules = 1,
                    ),
                )
                assertFalse(store.currentPointerFileForTesting.exists())
            } finally {
                prepared.close()
            }
        }

    @Test
    fun `publish installs an exact readable generation in phase order without closing caller output`() =
        runStore { root, authority ->
            val prepared = compile(root, generation = 1)
            try {
                val phases = mutableListOf<CredentialFilterArtifactPublicationPhase>()
                val store = store(root, authority) { phases += it }

                val reference = publish(store, prepared, expectedCurrentSha256 = null)

                assertEquals(CredentialFilterArtifactPublicationPhase.entries.toList(), phases)
                assertEquals(prepared.sha256, reference.sha256)
                assertEquals(prepared.identity, reference.artifactIdentity)
                assertTrue(prepared.file.exists())
                val available = store.readAvailable(expectation(prepared), maxRules = 0)
                try {
                    assertEquals(reference, available.reference)
                    assertEquals(prepared.identity, available.artifact.identity)
                    assertTrue(available.artifact.channelIsOpenForTesting)
                } finally {
                    available.close()
                }
            } finally {
                prepared.close()
            }
        }

    @Test
    fun `missing malformed oversized and unknown-schema pointers are unavailable`() = runStore { root, authority ->
        val prepared = compile(root, generation = 1)
        try {
            val store = store(root, authority)
            assertUnavailable(
                FilterArtifactUnavailableReason.POINTER_MISSING,
                store.readCurrent(expectation(prepared), maxRules = 0),
            )

            publish(store, prepared, expectedCurrentSha256 = null)
            val pointer = store.currentPointerFileForTesting
            val originalPointer = pointer.readBytes()
            pointer.writeBytes(byteArrayOf(0x4c))
            assertUnavailable(store.readCurrent(expectation(prepared), maxRules = 0))
            pointer.writeBytes(originalPointer)

            pointer.writeBytes(originalPointer + byteArrayOf(0))
            assertUnavailable(store.readCurrent(expectation(prepared), maxRules = 0))
            pointer.writeBytes(originalPointer)

            assertUnknownPointerSchemaIsRejected(store, expectation(prepared))
        } finally {
            prepared.close()
        }
    }

    @Test
    fun `missing generation artifact is unavailable`() = runStore { root, authority ->
        val prepared = compile(root, generation = 1)
        try {
            val store = store(root, authority)
            publish(store, prepared, expectedCurrentSha256 = null)
            val generation = store.generationDirectoryForTesting(prepared.sha256)
            val artifact = File(generation, "artifact.lsfa")

            assertTrue(artifact.delete())
            assertUnavailable(
                FilterArtifactUnavailableReason.ARTIFACT_MISSING,
                store.readCurrent(expectation(prepared), maxRules = 0),
            )
        } finally {
            prepared.close()
        }
    }

    @Test
    fun `tampered generation artifact is unavailable`() = runStore { root, authority ->
        val prepared = compile(root, generation = 1)
        try {
            val store = store(root, authority)
            publish(store, prepared, expectedCurrentSha256 = null)
            File(store.generationDirectoryForTesting(prepared.sha256), "artifact.lsfa")
                .appendBytes(byteArrayOf(0))

            assertUnavailable(
                FilterArtifactUnavailableReason.DIGEST_MISMATCH,
                store.readCurrent(expectation(prepared), maxRules = 0),
            )
        } finally {
            prepared.close()
        }
    }

    @Test
    fun `corrupt generation manifest is unavailable`() = runStore { root, authority ->
        val prepared = compile(root, generation = 1)
        try {
            val store = store(root, authority)
            publish(store, prepared, expectedCurrentSha256 = null)
            File(store.generationDirectoryForTesting(prepared.sha256), "complete.manifest")
                .writeBytes(byteArrayOf())

            assertUnavailable(store.readCurrent(expectation(prepared), maxRules = 0))
        } finally {
            prepared.close()
        }
    }

    @Test
    fun `identity expectation mismatch and rule budget excess are unavailable`() = runStore { root, authority ->
        val prepared = compile(
            root,
            generation = 1,
            blockedRules = listOf(DomainRule.parse("blocked.example")),
        )
        try {
            val store = store(root, authority)
            publish(store, prepared, expectedCurrentSha256 = null)

            val mismatchedExpectation = expectation(prepared).copy(
                configuration = ArtifactConfigurationIdentity(2, "configuration-2"),
            )
            assertUnavailable(
                FilterArtifactUnavailableReason.IDENTITY_MISMATCH,
                store.readCurrent(mismatchedExpectation, maxRules = 1),
            )
            assertUnavailable(store.readCurrent(expectation(prepared), maxRules = 0))
        } finally {
            prepared.close()
        }
    }

    @Test
    fun `stale expected current cannot replace a live pointer`() = runStore { root, authority ->
        val initial = compile(root, generation = 1)
        val candidate = compile(root, generation = 2)
        try {
            val store = store(root, authority)
            publish(store, initial, expectedCurrentSha256 = null)

            val result = store.publish(candidate, expectedCurrentSha256 = null)

            assertTrue(result is CredentialFilterArtifactPublicationResult.Superseded)
            store.readAvailable(expectation(initial), maxRules = 0).close()
        } finally {
            candidate.close()
            initial.close()
        }
    }

    @Test
    fun `noncanonical expected current is rejected without moving the live pointer`() = runStore { root, authority ->
        val initial = compile(root, generation = 1)
        val candidate = compile(root, generation = 2)
        try {
            val phases = mutableListOf<CredentialFilterArtifactPublicationPhase>()
            val store = store(root, authority) { phases += it }
            publish(store, initial, expectedCurrentSha256 = null)
            phases.clear()
            val stagingEntries = root.resolve("staging").listFiles()?.map(File::getName).orEmpty()

            val result = store.publish(candidate, expectedCurrentSha256 = "A".repeat(64))

            assertTrue(result is CredentialFilterArtifactPublicationResult.Failed)
            assertTrue(phases.isEmpty())
            assertEquals(
                stagingEntries,
                root.resolve("staging").listFiles()?.map(File::getName).orEmpty(),
            )
            store.readAvailable(expectation(initial), maxRules = 0).close()
        } finally {
            candidate.close()
            initial.close()
        }
    }

    @Test
    fun `independent stores sharing one authority publish one winner from a common basis`() =
        runStore { root, authority ->
            val initial = compile(root, generation = 1)
            val firstCandidate = compile(root, generation = 2)
            val secondCandidate = compile(root, generation = 3)
            val candidatesStaged = CountDownLatch(2)
            val releaseCandidates = CountDownLatch(1)
            try {
                val initialStore = store(root, authority)
                publish(initialStore, initial, expectedCurrentSha256 = null)
                val stagingBarrier = CredentialFilterArtifactPublicationCheckpoint { phase ->
                    if (
                        phase ==
                        CredentialFilterArtifactPublicationPhase.MANIFEST_WRITTEN_AND_SYNCED
                    ) {
                        candidatesStaged.countDown()
                        assertTrue(releaseCandidates.await(5, TimeUnit.SECONDS))
                    }
                }
                val firstStore = store(root, authority, checkpoint = stagingBarrier)
                val secondStore = store(root, authority, checkpoint = stagingBarrier)
                val start = CompletableDeferred<Unit>()

                val results = coroutineScope {
                    val first = async(Dispatchers.Default) {
                        start.await()
                        firstStore.publish(firstCandidate, expectedCurrentSha256 = initial.sha256)
                    }
                    val second = async(Dispatchers.Default) {
                        start.await()
                        secondStore.publish(secondCandidate, expectedCurrentSha256 = initial.sha256)
                    }
                    start.complete(Unit)
                    assertTrue(candidatesStaged.await(5, TimeUnit.SECONDS))
                    releaseCandidates.countDown()
                    listOf(first.await(), second.await())
                }

                assertEquals(1, results.count { it is CredentialFilterArtifactPublicationResult.Published })
                assertEquals(1, results.count { it is CredentialFilterArtifactPublicationResult.Superseded })
                val published = results.single {
                    it is CredentialFilterArtifactPublicationResult.Published
                } as CredentialFilterArtifactPublicationResult.Published
                val winner = listOf(firstCandidate, secondCandidate).single {
                    it.sha256 == published.reference.sha256
                }
                initialStore.readAvailable(expectation(winner), maxRules = 0).close()
            } finally {
                releaseCandidates.countDown()
                secondCandidate.close()
                firstCandidate.close()
                initial.close()
            }
        }

    @Test
    fun `publisher cancelled while waiting for authority cannot move the pointer`() =
        runStore { root, authority ->
            val initial = compile(root, generation = 1)
            val winner = compile(root, generation = 2)
            val cancelled = compile(root, generation = 3)
            val authorityEntered = CountDownLatch(1)
            val releaseAuthority = CountDownLatch(1)
            val cancelledCandidateWaiting = CompletableDeferred<Unit>()
            try {
                val initialStore = store(root, authority)
                publish(initialStore, initial, expectedCurrentSha256 = null)
                val winningStore = store(root, authority) { phase ->
                    if (phase == CredentialFilterArtifactPublicationPhase.AUTHORITY_ACQUIRED) {
                        authorityEntered.countDown()
                        assertTrue(releaseAuthority.await(5, TimeUnit.SECONDS))
                    }
                }
                val cancelledStore = store(root, authority) { phase ->
                    if (
                        phase ==
                        CredentialFilterArtifactPublicationPhase.WAITING_FOR_AUTHORITY
                    ) {
                        cancelledCandidateWaiting.complete(Unit)
                    }
                }

                coroutineScope {
                    val winningPublication = async(Dispatchers.Default) {
                        winningStore.publish(winner, expectedCurrentSha256 = initial.sha256)
                    }
                    assertTrue(authorityEntered.await(5, TimeUnit.SECONDS))
                    val cancelledPublication = async(Dispatchers.Default) {
                        cancelledStore.publish(cancelled, expectedCurrentSha256 = initial.sha256)
                    }
                    withTimeout(5_000) {
                        cancelledCandidateWaiting.await()
                    }
                    cancelledPublication.cancelAndJoin()
                    releaseAuthority.countDown()

                    assertTrue(
                        winningPublication.await() is
                            CredentialFilterArtifactPublicationResult.Published,
                    )
                    assertTrue(cancelledPublication.isCancelled)
                }
                initialStore.readAvailable(expectation(winner), maxRules = 0).close()
            } finally {
                releaseAuthority.countDown()
                cancelled.close()
                winner.close()
                initial.close()
            }
        }

    @Test
    fun `existing exact generation is reused without rewriting its artifact`() = runStore { root, authority ->
        val prepared = compile(root, generation = 1)
        try {
            val store = store(root, authority)
            publish(store, prepared, expectedCurrentSha256 = null)
            val artifact = File(store.generationDirectoryForTesting(prepared.sha256), "artifact.lsfa")
            val originalBytes = artifact.readBytes()
            assertTrue(artifact.setLastModified(1_234_567_890L))
            val preservedTimestamp = artifact.lastModified()

            publish(store, prepared, expectedCurrentSha256 = prepared.sha256)

            assertEquals(preservedTimestamp, artifact.lastModified())
            assertTrue(originalBytes.contentEquals(artifact.readBytes()))
        } finally {
            prepared.close()
        }
    }

    @Test
    fun `corrupt existing generation is rejected without moving the old pointer`() = runStore { root, authority ->
        val initial = compile(root, generation = 1)
        val candidate = compile(root, generation = 2)
        try {
            val store = store(root, authority)
            publish(store, initial, expectedCurrentSha256 = null)
            publish(store, candidate, expectedCurrentSha256 = initial.sha256)
            publish(store, initial, expectedCurrentSha256 = candidate.sha256)
            File(store.generationDirectoryForTesting(candidate.sha256), "artifact.lsfa")
                .appendBytes(byteArrayOf(0))

            val result = store.publish(candidate, expectedCurrentSha256 = initial.sha256)

            assertTrue(result is CredentialFilterArtifactPublicationResult.Failed)
            store.readAvailable(expectation(initial), maxRules = 0).close()
        } finally {
            candidate.close()
            initial.close()
        }
    }

    @Test
    fun `every pre-pointer failure preserves the previous pointer`() = runStore { root, authority ->
        val initial = compile(root, generation = 1)
        try {
            val authorityStore = store(root, authority)
            publish(authorityStore, initial, expectedCurrentSha256 = null)
            val prePointerPhases = CredentialFilterArtifactPublicationPhase.entries
                .takeWhile { it != CredentialFilterArtifactPublicationPhase.POINTER_REPLACED }
            assertTrue(prePointerPhases.isNotEmpty())
            prePointerPhases.forEachIndexed { index, failingPhase ->
                val candidate = compile(root, generation = (index + 2).toLong())
                try {
                    val failingStore = store(root, authority) { phase ->
                        if (phase == failingPhase) {
                            throw IOException("injected $failingPhase failure")
                        }
                    }

                    val result = failingStore.publish(candidate, expectedCurrentSha256 = initial.sha256)

                    assertTrue(result is CredentialFilterArtifactPublicationResult.Failed)
                    authorityStore.readAvailable(expectation(initial), maxRules = 0).close()
                } finally {
                    candidate.close()
                }
            }
        } finally {
            initial.close()
        }
    }

    @Test
    fun `post-pointer fault reconciles through exact read back before reporting published`() =
        runStore { root, authority ->
            val initial = compile(root, generation = 1)
            val candidate = compile(root, generation = 2)
            try {
                val initialStore = store(root, authority)
                publish(initialStore, initial, expectedCurrentSha256 = null)
                val reconcilingStore = store(root, authority) { phase ->
                    if (phase == CredentialFilterArtifactPublicationPhase.POINTER_REPLACED) {
                        throw IOException("injected post-pointer failure")
                    }
                }

                val result = reconcilingStore.publish(candidate, expectedCurrentSha256 = initial.sha256)

                assertTrue(result is CredentialFilterArtifactPublicationResult.Published)
                assertEquals(
                    candidate.sha256,
                    (result as CredentialFilterArtifactPublicationResult.Published).reference.sha256,
                )
                initialStore.readAvailable(expectation(candidate), maxRules = 0).close()
            } finally {
                candidate.close()
                initial.close()
            }
        }

    @Test
    fun `reader keeps its immutable old artifact open across a later pointer flip`() = runStore { root, authority ->
        val initial = compile(root, generation = 1)
        val candidate = compile(root, generation = 2)
        try {
            val store = store(root, authority)
            publish(store, initial, expectedCurrentSha256 = null)
            val oldRead = store.readAvailable(expectation(initial), maxRules = 0)
            try {
                publish(store, candidate, expectedCurrentSha256 = initial.sha256)

                assertTrue(oldRead.artifact.channelIsOpenForTesting)
                assertEquals(initial.identity, oldRead.artifact.identity)
                store.readAvailable(expectation(candidate), maxRules = 0).close()
            } finally {
                oldRead.close()
            }
        } finally {
            candidate.close()
            initial.close()
        }
    }

    @Test
    fun `symlinked generation artifact is unavailable even when its bytes are valid`() =
        runStore { root, authority ->
            val prepared = compile(root, generation = 1)
            try {
                val store = store(root, authority)
                publish(store, prepared, expectedCurrentSha256 = null)
                val artifact = File(store.generationDirectoryForTesting(prepared.sha256), "artifact.lsfa")
                val external = root.resolve("external-artifact.lsfa")
                artifact.copyTo(external)
                assertTrue(artifact.delete())
                Files.createSymbolicLink(artifact.toPath(), external.toPath())

                assertUnavailable(store.readCurrent(expectation(prepared), maxRules = 0))
            } finally {
                prepared.close()
            }
        }

    @Test
    fun `symlinked compiler output is rejected before copy`() = runStore { root, authority ->
        val prepared = compile(root, generation = 1)
        val actual = root.resolve("compiler-output-target.lsfa")
        val originalPath = prepared.file.toPath()
        try {
            Files.move(originalPath, actual.toPath())
            Files.createSymbolicLink(originalPath, actual.toPath())
            val phases = mutableListOf<CredentialFilterArtifactPublicationPhase>()
            val store = store(root, authority) { phases += it }

            val result = store.publish(prepared, expectedCurrentSha256 = null)

            assertTrue(result is CredentialFilterArtifactPublicationResult.Failed)
            assertEquals(
                listOf(CredentialFilterArtifactPublicationPhase.STAGING_DIRECTORY_CREATED),
                phases,
            )
            assertUnavailable(
                FilterArtifactUnavailableReason.POINTER_MISSING,
                store.readCurrent(expectation(prepared), maxRules = 0),
            )
        } finally {
            Files.deleteIfExists(originalPath)
            actual.delete()
            prepared.close()
        }
    }

    @Test
    fun `arbitrary root constructor rejects a symbolic link root`() {
        val parent = Files.createTempDirectory("artifact-publication-root-link-").toFile()
        try {
            val target = parent.resolve("target").also { assertTrue(it.mkdir()) }
            val link = parent.resolve("link")
            Files.createSymbolicLink(link.toPath(), target.toPath())

            assertThrows(IOException::class.java) {
                CredentialFilterArtifactPublicationStore(
                    link,
                    CredentialFilterArtifactPublicationAuthority(),
                )
            }
            assertFalse(link.isDirectory && !Files.isSymbolicLink(link.toPath()))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `first bootstrap syncs the new root through its parent before creating child directories`() {
        val parent = Files.createTempDirectory("artifact-publication-bootstrap-").toFile()
        val root = parent.resolve("artifact-root")
        try {
            val fileSystem = BootstrapSyncRecordingFileSystem(root)

            store(
                root = root,
                authority = CredentialFilterArtifactPublicationAuthority(),
                fileSystem = fileSystem,
            )

            assertEquals(
                listOf(
                    "ensure:root",
                    "sync:parent",
                    "ensure:generations",
                    "ensure:staging",
                    "sync:root",
                ),
                fileSystem.events,
            )
            assertEquals(
                listOf(
                    BootstrapSyncSnapshot(
                        directory = "parent",
                        rootExists = true,
                        generationsExist = false,
                        stagingExists = false,
                    ),
                    BootstrapSyncSnapshot(
                        directory = "root",
                        rootExists = true,
                        generationsExist = true,
                        stagingExists = true,
                    ),
                ),
                fileSystem.syncSnapshots,
            )
            assertTrue(root.isDirectory)
            assertTrue(root.resolve("generations").isDirectory)
            assertTrue(root.resolve("staging").isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `parent sync failure aborts first bootstrap and retry completes every durability sync`() {
        val parent = Files.createTempDirectory("artifact-publication-bootstrap-retry-").toFile()
        val root = parent.resolve("artifact-root")
        val authority = CredentialFilterArtifactPublicationAuthority()
        try {
            val fileSystem = BootstrapSyncRecordingFileSystem(
                root = root,
                parentSyncFailures = 1,
            )

            assertThrows(IOException::class.java) {
                store(
                    root = root,
                    authority = authority,
                    fileSystem = fileSystem,
                )
            }
            assertTrue(root.isDirectory)
            assertFalse(root.resolve("generations").exists())
            assertFalse(root.resolve("staging").exists())

            store(
                root = root,
                authority = authority,
                fileSystem = fileSystem,
            )

            assertEquals(
                listOf(
                    "ensure:root",
                    "sync:parent",
                    "ensure:root",
                    "sync:parent",
                    "ensure:generations",
                    "ensure:staging",
                    "sync:root",
                ),
                fileSystem.events,
            )
            assertEquals(
                listOf(
                    BootstrapSyncSnapshot(
                        directory = "parent",
                        rootExists = true,
                        generationsExist = false,
                        stagingExists = false,
                    ),
                    BootstrapSyncSnapshot(
                        directory = "parent",
                        rootExists = true,
                        generationsExist = false,
                        stagingExists = false,
                    ),
                    BootstrapSyncSnapshot(
                        directory = "root",
                        rootExists = true,
                        generationsExist = true,
                        stagingExists = true,
                    ),
                ),
                fileSystem.syncSnapshots,
            )
            assertEquals(2, fileSystem.parentSyncAttempts)
            assertTrue(root.resolve("generations").isDirectory)
            assertTrue(root.resolve("staging").isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun store(
        root: File,
        authority: CredentialFilterArtifactPublicationAuthority,
        fileSystem: ArtifactPublicationFileSystem =
            JvmArtifactPublicationFileSystem,
        checkpoint: CredentialFilterArtifactPublicationCheckpoint =
            CredentialFilterArtifactPublicationCheckpoint { },
    ) = CredentialFilterArtifactPublicationStore(
        rootDirectory = root,
        authority = authority,
        checkpoint = checkpoint,
        fileSystem = fileSystem,
    )

    private data class BootstrapSyncSnapshot(
        val directory: String,
        val rootExists: Boolean,
        val generationsExist: Boolean,
        val stagingExists: Boolean,
    )

    private class BootstrapSyncRecordingFileSystem(
        root: File,
        parentSyncFailures: Int = 0,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val normalizedRoot = root.absoluteFile.toPath().normalize().toFile()
        private val normalizedParent = requireNotNull(normalizedRoot.parentFile)
        private val generations = normalizedRoot.resolve("generations")
        private val staging = normalizedRoot.resolve("staging")
        private var parentSyncFailuresRemaining = parentSyncFailures

        val events = mutableListOf<String>()
        val syncSnapshots = mutableListOf<BootstrapSyncSnapshot>()
        var parentSyncAttempts = 0
            private set

        override fun ensureDirectory(directory: File) {
            JvmArtifactPublicationFileSystem.ensureDirectory(directory)
            events += "ensure:${label(directory)}"
        }

        override fun syncDirectory(directory: File) {
            val label = label(directory)
            events += "sync:$label"
            syncSnapshots += BootstrapSyncSnapshot(
                directory = label,
                rootExists = normalizedRoot.isDirectory,
                generationsExist = generations.isDirectory,
                stagingExists = staging.isDirectory,
            )
            if (label == "parent") {
                parentSyncAttempts += 1
                if (parentSyncFailuresRemaining > 0) {
                    parentSyncFailuresRemaining -= 1
                    throw IOException("injected parent-directory sync failure")
                }
            }
            JvmArtifactPublicationFileSystem.syncDirectory(directory)
        }

        private fun label(directory: File): String =
            when (directory.absoluteFile.toPath().normalize().toFile()) {
                normalizedParent -> "parent"
                normalizedRoot -> "root"
                generations -> "generations"
                staging -> "staging"
                else -> directory.path
            }
    }

    private class GenerationSyncFailingFileSystem(
        root: File,
        failures: Int,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val generations =
            root.resolve("generations").absoluteFile.toPath().normalize().toFile()
        private var failuresRemaining = failures
        var generationSyncAttempts = 0
            private set

        override fun syncDirectory(directory: File) {
            if (
                directory.absoluteFile.toPath().normalize().toFile() ==
                generations
            ) {
                generationSyncAttempts += 1
                if (failuresRemaining > 0) {
                    failuresRemaining -= 1
                    throw IOException("injected generation-directory sync failure")
                }
            }
            JvmArtifactPublicationFileSystem.syncDirectory(directory)
        }
    }

    private suspend fun publish(
        store: CredentialFilterArtifactPublicationStore,
        prepared: PreparedFilterArtifact,
        expectedCurrentSha256: String?,
    ) = store.publish(prepared, expectedCurrentSha256).let { result ->
        val published = result as? CredentialFilterArtifactPublicationResult.Published
            ?: throw AssertionError("expected publish success but got $result")
        published.reference
    }

    private suspend fun install(
        store: CredentialFilterArtifactPublicationStore,
        prepared: PreparedFilterArtifact,
    ) = store.install(prepared).let { result ->
        val installed = result as? CredentialFilterArtifactInstallResult.Installed
            ?: throw AssertionError("expected install success but got $result")
        installed.reference
    }

    private fun CredentialFilterArtifactPublicationStore.readExactAvailable(
        prepared: PreparedFilterArtifact,
        maxRules: Int,
    ): CredentialFilterArtifactExactRead.Available {
        val read = readExactGeneration(
            artifactSha256 = prepared.sha256,
            identitySha256 = canonicalFilterArtifactIdentitySha256(prepared.identity),
            maxRules = maxRules,
        )
        return read as? CredentialFilterArtifactExactRead.Available
            ?: throw AssertionError("expected an available exact artifact but got $read")
    }

    private fun CredentialFilterArtifactPublicationStore.readAvailable(
        expectation: FilterArtifactExpectation,
        maxRules: Int,
    ): CredentialFilterArtifactCurrentRead.Available {
        val read = readCurrent(expectation, maxRules)
        return read as? CredentialFilterArtifactCurrentRead.Available
            ?: throw AssertionError("expected an available artifact but got $read")
    }

    private fun assertUnavailable(
        expected: FilterArtifactUnavailableReason? = null,
        read: CredentialFilterArtifactCurrentRead,
    ) {
        val unavailable = read as? CredentialFilterArtifactCurrentRead.Unavailable
        if (unavailable == null) {
            if (read is CredentialFilterArtifactCurrentRead.Available) read.close()
            fail("expected unavailable artifact but got $read")
            return
        }
        expected?.let { assertEquals(it, unavailable.reason) }
    }

    private fun assertUnavailable(read: CredentialFilterArtifactCurrentRead) =
        assertUnavailable(expected = null, read = read)

    private fun assertUnavailable(
        expected: FilterArtifactUnavailableReason,
        read: CredentialFilterArtifactExactRead,
    ) {
        val unavailable = read as? CredentialFilterArtifactExactRead.Unavailable
        if (unavailable == null) {
            if (read is CredentialFilterArtifactExactRead.Available) read.close()
            fail("expected unavailable exact artifact but got $read")
            return
        }
        assertEquals(expected, unavailable.reason)
    }

    private fun assertUnknownPointerSchemaIsRejected(
        store: CredentialFilterArtifactPublicationStore,
        expectation: FilterArtifactExpectation,
    ) {
        val pointer = store.currentPointerFileForTesting
        val original = pointer.readBytes()
        var schemaWasRejected = false
        try {
            for (index in original.indices) {
                for (replacement in byteArrayOf(0, 0x7f, 0xff.toByte())) {
                    if (replacement == original[index]) continue
                    val mutated = original.copyOf()
                    mutated[index] = replacement
                    pointer.writeBytes(mutated)
                    if (
                        unavailableReason(store.readCurrent(expectation, maxRules = 0)) ==
                        FilterArtifactUnavailableReason.POINTER_SCHEMA_MISMATCH
                    ) {
                        schemaWasRejected = true
                        return
                    }
                }
            }
        } finally {
            pointer.writeBytes(original)
        }
        assertTrue("a pointer with an unknown schema must be rejected as a schema mismatch", schemaWasRejected)
    }

    private fun unavailableReason(read: CredentialFilterArtifactCurrentRead): FilterArtifactUnavailableReason? =
        when (read) {
            is CredentialFilterArtifactCurrentRead.Available -> {
                read.close()
                null
            }
            is CredentialFilterArtifactCurrentRead.Unavailable -> read.reason
        }

    private suspend fun compile(
        root: File,
        generation: Long,
        blockedRules: List<DomainRule> = emptyList(),
    ): PreparedFilterArtifact = FilterCompiler(root.resolve("compiler-$generation")).compile(
        FilterCompileRequest(
            configurationIdentity = ArtifactConfigurationIdentity(
                generation = generation,
                fingerprint = "configuration-$generation",
            ),
            selectedSources = emptyList(),
            catalogRevision = "catalog-revision",
            catalogSha256 = "b".repeat(64),
            maxRules = 4,
            intentionalEmpty = blockedRules.isEmpty(),
            manualBlockRules = blockedRules,
        ),
    )

    private fun expectation(prepared: PreparedFilterArtifact) = FilterArtifactExpectation(
        configuration = prepared.identity.configuration,
        selectedSources = prepared.identity.selectedSources.map { source ->
            ExpectedArtifactSource(source.id, source.format)
        },
        catalogRevision = prepared.identity.catalogRevision,
        catalogSha256 = prepared.identity.catalogSha256,
    )

    private fun runStore(
        block: suspend (root: File, authority: CredentialFilterArtifactPublicationAuthority) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("credential-filter-artifact-publication-").toFile()
        try {
            block(root, CredentialFilterArtifactPublicationAuthority())
        } finally {
            root.deleteRecursively()
        }
    }
}
