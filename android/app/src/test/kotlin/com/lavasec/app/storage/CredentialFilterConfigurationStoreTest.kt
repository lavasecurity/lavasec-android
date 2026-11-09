package com.lavasec.app.storage

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.AcceptedFilterSourceAuthority
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.CredentialFilterArtifactStagingResult
import com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier
import com.lavasec.app.platform.FilterCatalogCompileAuthority
import com.lavasec.app.platform.FilterMutationAuthorityState
import com.lavasec.app.platform.FilterMutationFailure
import com.lavasec.app.platform.FilterMutationPreparationResult
import com.lavasec.app.platform.FilterMutationPreparer
import com.lavasec.app.platform.FilterMutationPublishResult
import com.lavasec.app.platform.FilterMutationRequest
import com.lavasec.app.platform.FilterMutationResult
import com.lavasec.app.platform.PreparedFilterMutation
import com.lavasec.app.platform.TransactionalFilterMutationCoordinator
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialFilterConfigurationStoreTest {
    @Test
    fun `restart preserves a full Plus library through exact authoritative storage`() = runBlocking {
        withRoot { root ->
            fun domains(prefix: Char) = (0 until 1_000).mapTo(linkedSetOf()) { index ->
                listOf("$prefix${index.toString().padStart(4, '0')}" + "a".repeat(58),
                    "b".repeat(63), "c".repeat(63), "d".repeat(61)).joinToString(".")
            }
            val rules = com.lavasec.app.foundation.filter.ManualDomainRules(domains('a'), domains('z'))
            val configuration = FilterLibraryDraft(
                profiles = (0 until 50).map { index ->
                    com.lavasec.app.foundation.filter.FilterProfileDraft(
                        "filter-$index", "Filter $index", null, emptySet(), rules)
                },
                activeProfileId = "filter-0",
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
            )
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), configuration, 1L, maxRules = 500_000)
            try {
                val first = authorities.store(maxRules = 500_000)
                assertTrue(first.initialize(initial.state, initial.prepared) is
                    CredentialFilterConfigurationInitializationResult.Initialized)
                assertTrue(first.authoritativeRecordFileForTesting.length() > 25_000_000L)
                val restored = authorities.store(maxRules = 500_000).readAuthoritative()
                assertTrue(restored is CredentialFilterConfigurationRead.Available)
                restored as CredentialFilterConfigurationRead.Available
                assertEquals(configuration, restored.state.configuration)
                assertEquals(initial.state.configurationIdentity, restored.state.configurationIdentity)
                assertEquals(2_000, restored.state.verifiedArtifact.artifactIdentity.ruleCounts.total)
            } finally { initial.close() }
        }
    }

    @Test
    fun `bootstrap and restart recover exact state without publishing a pointer`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val first = authorities.store()
                val initialized = first.initialize(initial.state, initial.prepared)

                assertTrue(initialized is CredentialFilterConfigurationInitializationResult.Initialized)
                assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())

                val restarted = authorities.store()
                val read = restarted.readAuthoritative()
                assertTrue(read is CredentialFilterConfigurationRead.Available)
                read as CredentialFilterConfigurationRead.Available
                assertEquals(initial.state.configuration, read.state.configuration)
                assertEquals(initial.state.configurationIdentity, read.state.configurationIdentity)
                assertEquals(initial.state.verifiedArtifact, read.state.verifiedArtifact)
                assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `existing unreadable authority is never replaced as a fresh bootstrap`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val store = authorities.store()
                store.authoritativeRecordFileForTesting.writeBytes(byteArrayOf(1, 2, 3))

                val result = store.initialize(initial.state, initial.prepared)

                assertTrue(result is CredentialFilterConfigurationInitializationResult.Failed)
                assertEquals(
                    byteArrayOf(1, 2, 3).toList(),
                    store.authoritativeRecordFileForTesting.readBytes().toList(),
                )
                assertFalse(
                    authorities.artifactStore.generationDirectoryForTesting(
                        initial.prepared.sha256,
                    ).exists(),
                )
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `existing healthy authority is returned before candidate artifact installation`() =
        runBlocking {
            withRoot { root ->
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    baseConfiguration().withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    1L,
                )
                val fileSystem =
                    ArtifactCopyCountingFileSystem(candidate.prepared.file)
                val authorities = Authorities(root, fileSystem)
                try {
                    val store = authorities.store()
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )

                    val result = store.initialize(candidate.state, candidate.prepared)

                    assertTrue(
                        result is CredentialFilterConfigurationInitializationResult.Existing,
                    )
                    result as CredentialFilterConfigurationInitializationResult.Existing
                    assertEquals(initial.state, result.state)
                    assertEquals(0, fileSystem.copyAttempts)
                    assertFalse(
                        authorities.artifactStore.generationDirectoryForTesting(
                            candidate.prepared.sha256,
                        ).exists(),
                    )
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `bootstrap install failure adopts a winner initialized after preflight`() =
        runBlocking {
            withRoot { root ->
                val winner = fixture(root.resolve("winner"), baseConfiguration(), 1L)
                val loser = fixture(
                    root.resolve("loser"),
                    baseConfiguration().withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    1L,
                )
                val fileSystem =
                    BlockingFailingArtifactCopyFileSystem(loser.prepared.file)
                val authorities = Authorities(root, fileSystem)
                val loserDispatcher =
                    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                try {
                    val store = authorities.store()
                    val loserInitialization = async(loserDispatcher) {
                        store.initialize(loser.state, loser.prepared)
                    }
                    assertTrue(fileSystem.copyStarted.await(5, TimeUnit.SECONDS))

                    assertTrue(
                        store.initialize(winner.state, winner.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    fileSystem.releaseCopy.countDown()
                    val result = loserInitialization.await()

                    assertTrue(
                        result is CredentialFilterConfigurationInitializationResult.Existing,
                    )
                    result as CredentialFilterConfigurationInitializationResult.Existing
                    assertEquals(winner.state, result.state)
                    assertFalse(
                        authorities.artifactStore.generationDirectoryForTesting(
                            loser.prepared.sha256,
                        ).exists(),
                    )
                } finally {
                    fileSystem.releaseCopy.countDown()
                    loserDispatcher.close()
                    loser.close()
                    winner.close()
                }
            }
        }

    @Test
    fun `coordinator emits applied only after exact durable candidate readback`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val desired = initial.state.configuration.withResolverPlan(
                MilestoneResolverPlan.DEVICE_DNS_ONLY,
            )
            val candidate = fixture(root.resolve("candidate"), desired, 2L)
            var cleanups = 0
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                val coordinator = coordinator(
                    initial = initial.state,
                    candidate = candidate,
                    publisher = store,
                ) {
                    cleanups += 1
                }

                val result = coordinator.apply(FilterMutationRequest(1L, desired))

                assertTrue(result is FilterMutationResult.Applied)
                assertEquals(candidate.state, result.confirmedState)
                assertEquals(candidate.state, coordinator.confirmedState.value)
                assertEquals(1, cleanups)
                assertTrue(
                    (authorities.store().readAuthoritative() as
                        CredentialFilterConfigurationRead.Available).state == candidate.state,
                )
                assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())
            } finally {
                candidate.close()
                initial.close()
            }
        }
    }

    @Test
    fun `two repository instances return the exact durable winner after CAS loss`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val firstCandidate = fixture(
                root.resolve("first"),
                initial.state.configuration.withResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY),
                2L,
            )
            val secondCandidate = fixture(
                root.resolve("second"),
                initial.state.configuration.withResolverPlan(MilestoneResolverPlan.MULLVAD_ONLY),
                2L,
            )
            try {
                val firstStore = authorities.store()
                val secondStore = authorities.store()
                firstStore.initialize(initial.state, initial.prepared)

                val committed = firstStore.commit(1L, mutation(firstCandidate))
                val stale = secondStore.commit(1L, mutation(secondCandidate))

                assertEquals(FilterMutationPublishResult.Committed, committed)
                assertTrue(stale is FilterMutationPublishResult.Superseded)
                stale as FilterMutationPublishResult.Superseded
                assertEquals(firstCandidate.state, stale.authoritative)
                assertEquals(
                    firstCandidate.state,
                    (secondStore.readAuthoritative() as
                        CredentialFilterConfigurationRead.Available).state,
                )
            } finally {
                secondCandidate.close()
                firstCandidate.close()
                initial.close()
            }
        }
    }

    @Test
    fun `obviously stale commit returns durable winner before candidate artifact installation`() =
        runBlocking {
            withRoot { root ->
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val winner = fixture(
                    root.resolve("winner"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                val stale = fixture(
                    root.resolve("stale"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.MULLVAD_ONLY,
                    ),
                    2L,
                )
                val fileSystem =
                    ArtifactCopyCountingFileSystem(stale.prepared.file)
                val authorities = Authorities(root, fileSystem)
                try {
                    val store = authorities.store()
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    assertEquals(
                        FilterMutationPublishResult.Committed,
                        store.commit(1L, mutation(winner)),
                    )

                    val result = store.commit(1L, mutation(stale))

                    assertTrue(result is FilterMutationPublishResult.Superseded)
                    result as FilterMutationPublishResult.Superseded
                    assertEquals(winner.state, result.authoritative)
                    assertEquals(0, fileSystem.copyAttempts)
                    assertFalse(
                        authorities.artifactStore.generationDirectoryForTesting(
                            stale.prepared.sha256,
                        ).exists(),
                    )
                } finally {
                    stale.close()
                    winner.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `candidate install failure reconciles a winner committed after preflight`() =
        runBlocking {
            withRoot { root ->
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val winner = fixture(
                    root.resolve("winner"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                val stale = fixture(
                    root.resolve("stale"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.MULLVAD_ONLY,
                    ),
                    2L,
                )
                val fileSystem =
                    BlockingFailingArtifactCopyFileSystem(stale.prepared.file)
                val authorities = Authorities(root, fileSystem)
                val staleDispatcher =
                    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                try {
                    val store = authorities.store()
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val staleCommit = async(staleDispatcher) {
                        store.commit(1L, mutation(stale))
                    }
                    assertTrue(fileSystem.copyStarted.await(5, TimeUnit.SECONDS))

                    assertEquals(
                        FilterMutationPublishResult.Committed,
                        store.commit(1L, mutation(winner)),
                    )
                    fileSystem.releaseCopy.countDown()
                    val result = staleCommit.await()

                    assertTrue(result is FilterMutationPublishResult.Superseded)
                    result as FilterMutationPublishResult.Superseded
                    assertEquals(winner.state, result.authoritative)
                    assertFalse(
                        authorities.artifactStore.generationDirectoryForTesting(
                            stale.prepared.sha256,
                        ).exists(),
                    )
                } finally {
                    fileSystem.releaseCopy.countDown()
                    staleDispatcher.close()
                    stale.close()
                    winner.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `candidate install failure with unchanged authority remains publish failed`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                try {
                    val store = authorities.store()
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    assertTrue(candidate.prepared.file.delete())

                    val result = store.commit(1L, mutation(candidate))

                    assertEquals(
                        FilterMutationPublishResult.Failed(
                            FilterMutationFailure.PUBLISH_FAILED,
                        ),
                        result,
                    )
                    assertEquals(
                        initial.state,
                        (store.readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `durable generation below expected generation requires an authority reload`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val candidate = fixture(
                root.resolve("candidate"),
                initial.state.configuration.withResolverPlan(
                    MilestoneResolverPlan.DEVICE_DNS_ONLY,
                ),
                3L,
            )
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )

                val result = store.commit(
                    expectedGeneration = 2L,
                    mutation = mutation(candidate),
                )

                assertTrue(result is FilterMutationPublishResult.ProtectionUnavailable)
                assertEquals(
                    initial.state,
                    (store.readAuthoritative() as
                        CredentialFilterConfigurationRead.Available).state,
                )
            } finally {
                candidate.close()
                initial.close()
            }
        }
    }

    @Test
    fun `concurrent coordinators produce one applied result and adopt one exact winner`() =
        runBlocking {
            withRoot { root ->
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val firstCandidate = fixture(
                    root.resolve("first"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                val secondCandidate = fixture(
                    root.resolve("second"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.MULLVAD_ONLY,
                    ),
                    2L,
                )
                val fileSystem = CandidateCopyBarrierFileSystem(
                    setOf(
                        firstCandidate.prepared.file,
                        secondCandidate.prepared.file,
                    ),
                )
                val authorities = Authorities(root, fileSystem)
                try {
                    authorities.store().initialize(initial.state, initial.prepared)
                    val first = coordinator(
                        initial = initial.state,
                        candidate = firstCandidate,
                        publisher = authorities.store(),
                        cleanup = {},
                    )
                    val second = coordinator(
                        initial = initial.state,
                        candidate = secondCandidate,
                        publisher = authorities.store(),
                        cleanup = {},
                    )

                    val firstResult = async {
                        first.apply(
                            FilterMutationRequest(
                                1L,
                                firstCandidate.state.configuration,
                            ),
                        )
                    }
                    val secondResult = async {
                        second.apply(
                            FilterMutationRequest(
                                1L,
                                secondCandidate.state.configuration,
                            ),
                        )
                    }
                    val results = awaitAll(firstResult, secondResult)

                    val applied = results.single { it is FilterMutationResult.Applied }
                        as FilterMutationResult.Applied
                    val superseded = results.single { it is FilterMutationResult.Superseded }
                        as FilterMutationResult.Superseded
                    assertEquals(applied.confirmedState, superseded.confirmedState)
                    assertEquals(applied.confirmedState, first.confirmedState.value)
                    assertEquals(applied.confirmedState, second.confirmedState.value)
                    assertEquals(
                        applied.confirmedState,
                        (authorities.store().readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                } finally {
                    secondCandidate.close()
                    firstCandidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `failure before record replacement retains old authority and leaves only orphan bytes`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                try {
                    authorities.store().initialize(initial.state, initial.prepared)
                    val failing = authorities.store(
                        checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                            if (phase == CredentialFilterConfigurationPhase.RECORD_TEMP_SYNCED) {
                                throw java.io.IOException("injected before replace")
                            }
                        },
                    )

                    val result = failing.commit(1L, mutation(candidate))

                    assertTrue(result is FilterMutationPublishResult.Failed)
                    assertEquals(
                        initial.state,
                        (authorities.store().readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                    assertTrue(
                        authorities.artifactStore.generationDirectoryForTesting(
                            candidate.prepared.sha256,
                        ).isDirectory,
                    )
                    assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `error after record replacement reconciles to committed candidate`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val candidate = fixture(
                root.resolve("candidate"),
                initial.state.configuration.withResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY),
                2L,
            )
            try {
                authorities.store().initialize(initial.state, initial.prepared)
                val ambiguous = authorities.store(
                    checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                        if (phase == CredentialFilterConfigurationPhase.RECORD_REPLACED) {
                            throw java.io.IOException("injected after replace")
                        }
                    },
                )

                val result = ambiguous.commit(1L, mutation(candidate))

                assertEquals(FilterMutationPublishResult.Committed, result)
                assertEquals(
                    candidate.state,
                    (authorities.store().readAuthoritative() as
                        CredentialFilterConfigurationRead.Available).state,
                )
            } finally {
                candidate.close()
                initial.close()
            }
        }
    }

    @Test
    fun `artifact descriptor close failure after replacement fails closed and remains recoverable`() =
        runBlocking {
            withRoot { root ->
                val artifactFileSystem = CloseFailingArtifactFileSystem()
                val authorities = Authorities(root, artifactFileSystem)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                try {
                    assertTrue(
                        authorities.store().initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val store = authorities.store(
                        checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                            if (phase == CredentialFilterConfigurationPhase.RECORD_REPLACED) {
                                artifactFileSystem.closeFailuresRemaining = 1
                            }
                        },
                    )

                    val result = store.commit(1L, mutation(candidate))

                    assertTrue(result is FilterMutationPublishResult.ProtectionUnavailable)
                    assertEquals(0, artifactFileSystem.closeFailuresRemaining)
                    assertEquals(
                        candidate.state,
                        (authorities.store().readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `authoritative record post-read IO failure fails public read closed and recovers`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val fileSystem = PostReadFailingAuthoritativeRecordFileSystem()
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                try {
                    val store = authorities.store(fileSystem = fileSystem)
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    fileSystem.postReadFailuresRemaining = 1

                    val failed = store.readAuthoritative()

                    assertTrue(failed is CredentialFilterConfigurationRead.Unavailable)
                    failed as CredentialFilterConfigurationRead.Unavailable
                    assertEquals(
                        CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
                        failed.reason,
                    )
                    assertEquals(0, fileSystem.postReadFailuresRemaining)
                    assertEquals(
                        initial.state,
                        (store.readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                } finally {
                    initial.close()
                }
            }
        }

    @Test
    fun `authoritative record post-read IO failure before CAS cannot replace authority`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val fileSystem = PostReadFailingAuthoritativeRecordFileSystem()
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                try {
                    val store = authorities.store(fileSystem = fileSystem)
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val recordBefore = store.authoritativeRecordFileForTesting.readBytes()
                    fileSystem.postReadFailuresRemaining = 1

                    val result = store.commit(1L, mutation(candidate))

                    assertEquals(FilterMutationPublishResult.ProtectionUnavailable, result)
                    assertEquals(0, fileSystem.postReadFailuresRemaining)
                    assertTrue(
                        recordBefore.contentEquals(
                            store.authoritativeRecordFileForTesting.readBytes(),
                        ),
                    )
                    assertEquals(
                        initial.state,
                        (store.readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `authoritative record post-read IO failure after replacement fails closed and recovers`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val fileSystem = PostReadFailingAuthoritativeRecordFileSystem()
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                try {
                    assertTrue(
                        authorities.store().initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val store = authorities.store(
                        checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                            if (phase == CredentialFilterConfigurationPhase.RECORD_REPLACED) {
                                fileSystem.postReadFailuresRemaining = 1
                            }
                        },
                        fileSystem = fileSystem,
                    )

                    val result = store.commit(1L, mutation(candidate))

                    assertEquals(FilterMutationPublishResult.ProtectionUnavailable, result)
                    assertEquals(0, fileSystem.postReadFailuresRemaining)
                    assertEquals(
                        candidate.state,
                        (store.readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `public read cannot cross a writer rename before its durability barrier`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val candidate = fixture(
                root.resolve("candidate"),
                initial.state.configuration.withResolverPlan(
                    MilestoneResolverPlan.DEVICE_DNS_ONLY,
                ),
                2L,
            )
            val recordReplaced = CountDownLatch(1)
            val releaseWriter = CountDownLatch(1)
            val writerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                authorities.store().initialize(initial.state, initial.prepared)
                val pausingWriter = authorities.store(
                    checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                        if (phase == CredentialFilterConfigurationPhase.RECORD_REPLACED) {
                            recordReplaced.countDown()
                            check(releaseWriter.await(5, TimeUnit.SECONDS)) {
                                "timed out waiting to release record durability"
                            }
                        }
                    },
                )
                val commit = async(writerDispatcher) {
                    pausingWriter.commit(1L, mutation(candidate))
                }
                assertTrue(recordReplaced.await(5, TimeUnit.SECONDS))

                val read = async(start = CoroutineStart.UNDISPATCHED) {
                    authorities.store().readAuthoritative()
                }

                assertFalse(read.isCompleted)
                assertTrue(authorities.configurationAuthority.mutex.isLocked)
                releaseWriter.countDown()
                assertEquals(FilterMutationPublishResult.Committed, commit.await())
                assertEquals(
                    candidate.state,
                    (read.await() as CredentialFilterConfigurationRead.Available).state,
                )
                assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())
            } finally {
                releaseWriter.countDown()
                writerDispatcher.close()
                candidate.close()
                initial.close()
            }
        }
    }

    @Test
    fun `post-replace directory sync failure is retried before committed is reported`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                val fileSystem = RootSyncFailingFileSystem(root)
                try {
                    authorities.store().initialize(initial.state, initial.prepared)
                    fileSystem.failuresRemaining = 1

                    val result = authorities.store(fileSystem = fileSystem)
                        .commit(1L, mutation(candidate))

                    assertEquals(FilterMutationPublishResult.Committed, result)
                    // Initial failure followed by one explicit recovery barrier.
                    assertEquals(2, fileSystem.rootSyncAttempts)
                    assertEquals(
                        candidate.state,
                        (authorities.store().readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `successful record durability avoids redundant same-process directory syncs`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val fileSystem = RootSyncFailingFileSystem(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                try {
                    val store = authorities.store(fileSystem = fileSystem)
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val syncsAfterInitialization = fileSystem.rootSyncAttempts

                    repeat(2) {
                        assertEquals(
                            initial.state,
                            (store.readAuthoritative() as
                                CredentialFilterConfigurationRead.Available).state,
                        )
                    }

                    assertEquals(syncsAfterInitialization, fileSystem.rootSyncAttempts)
                } finally {
                    initial.close()
                }
            }
        }

    @Test
    fun `new process authority proves existing record durability exactly once`() =
        runBlocking {
            withRoot { root ->
                val initialAuthorities = Authorities(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                try {
                    assertTrue(
                        initialAuthorities.store().initialize(
                            initial.state,
                            initial.prepared,
                        ) is CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val restartedFileSystem = RootSyncCountingFileSystem(root)
                    val restarted = Authorities(root).store(
                        fileSystem = restartedFileSystem,
                    )

                    repeat(2) {
                        assertEquals(
                            initial.state,
                            (restarted.readAuthoritative() as
                                CredentialFilterConfigurationRead.Available).state,
                        )
                    }

                    assertEquals(1, restartedFileSystem.rootSyncAttempts)
                } finally {
                    initial.close()
                }
            }
        }

    @Test
    fun `unreconciled post-replace directory sync fails closed`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val candidate = fixture(
                root.resolve("candidate"),
                initial.state.configuration.withResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY),
                2L,
            )
            val fileSystem = RootSyncFailingFileSystem(root)
            try {
                authorities.store().initialize(initial.state, initial.prepared)
                fileSystem.failuresRemaining = 2

                val store = authorities.store(fileSystem = fileSystem)
                val result = store.commit(1L, mutation(candidate))

                assertEquals(FilterMutationPublishResult.ProtectionUnavailable, result)
                assertEquals(2, fileSystem.rootSyncAttempts)
                assertEquals(
                    candidate.state,
                    (store.readAuthoritative() as
                        CredentialFilterConfigurationRead.Available).state,
                )
                assertEquals(3, fileSystem.rootSyncAttempts)
            } finally {
                candidate.close()
                initial.close()
            }
        }
    }

    @Test
    fun `record existence IO failure is returned as storage unavailable`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val fileSystem = RecordExistenceFailingFileSystem(root)
            val read = authorities.store(fileSystem = fileSystem).readAuthoritative()

            assertTrue(read is CredentialFilterConfigurationRead.Unavailable)
            read as CredentialFilterConfigurationRead.Unavailable
            assertEquals(
                CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
                read.reason,
            )
        }
    }

    @Test
    fun `checkpoint 18 pointer movement cannot change authoritative recovery`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val orphan = fixture(
                root.resolve("orphan"),
                initial.state.configuration.withResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY),
                2L,
            )
            try {
                val store = authorities.store()
                store.initialize(initial.state, initial.prepared)

                val pointerPublish = authorities.artifactStore.publish(
                    prepared = orphan.prepared,
                    expectedCurrentSha256 = null,
                )
                assertTrue(pointerPublish is CredentialFilterArtifactPublicationResult.Published)

                val read = store.readAuthoritative()
                assertTrue(read is CredentialFilterConfigurationRead.Available)
                assertEquals(initial.state, (read as CredentialFilterConfigurationRead.Available).state)
            } finally {
                orphan.close()
                initial.close()
            }
        }
    }

    @Test
    fun `metadata-only mutation cannot reach authoritative commit`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val candidate = fixture(
                root.resolve("candidate"),
                initial.state.configuration.withResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY),
                2L,
            )
            try {
                val store = authorities.store()
                store.initialize(initial.state, initial.prepared)
                val metadataOnly = PreparedFilterMutation(
                    candidate = candidate.state,
                    compilationReceipt = candidate.prepared.compilationReceipt,
                    cleanup = {},
                )

                val result = store.commit(1L, metadataOnly)

                assertTrue(result is FilterMutationPublishResult.Failed)
                result as FilterMutationPublishResult.Failed
                assertEquals(FilterMutationFailure.IDENTITY_MISMATCH, result.failure)
                assertEquals(
                    initial.state,
                    (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state,
                )
            } finally {
                candidate.close()
                initial.close()
            }
        }
    }

    @Test
    fun `cancellation before configuration preflight leaves no orphan generation`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                try {
                    val store = authorities.store()
                    store.initialize(initial.state, initial.prepared)
                    authorities.configurationAuthority.mutex.lock()
                    try {
                        val commit = launch(start = CoroutineStart.UNDISPATCHED) {
                            store.commit(1L, mutation(candidate))
                        }
                        commit.cancelAndJoin()
                    } finally {
                        authorities.configurationAuthority.mutex.unlock()
                    }

                    assertEquals(
                        initial.state,
                        (store.readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                    assertFalse(
                        authorities.artifactStore.generationDirectoryForTesting(
                            candidate.prepared.sha256,
                        ).isDirectory,
                    )
                    assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `coordinator cancellation before configuration preflight retains old state`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                var cleanups = 0
                try {
                    val store = authorities.store()
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val coordinator = coordinator(
                        initial = initial.state,
                        candidate = candidate,
                        publisher = store,
                    ) {
                        cleanups += 1
                    }

                    authorities.configurationAuthority.mutex.lock()
                    try {
                        val apply = launch(start = CoroutineStart.UNDISPATCHED) {
                            coordinator.apply(
                                FilterMutationRequest(
                                    expectedGeneration = 1L,
                                    desired = candidate.state.configuration,
                                ),
                            )
                        }
                        withTimeout(5_000L) {
                            apply.cancelAndJoin()
                        }
                        assertTrue(apply.isCompleted)
                        assertTrue(authorities.configurationAuthority.mutex.isLocked)
                    } finally {
                        authorities.configurationAuthority.mutex.unlock()
                    }

                    assertEquals(1, cleanups)
                    assertEquals(initial.state, coordinator.confirmedState.value)
                    assertEquals(
                        initial.state,
                        (store.readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                    assertEquals(
                        setOf(initial.prepared.sha256),
                        root.resolve("generations")
                            .listFiles()
                            .orEmpty()
                            .filter(File::isDirectory)
                            .map(File::getName)
                            .toSet(),
                    )
                    assertFalse(
                        authorities.artifactStore.generationDirectoryForTesting(
                            candidate.prepared.sha256,
                        ).isDirectory,
                    )
                    assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())
                } finally {
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `cancellation after artifact install before final configuration admission leaves only an orphan`() =
        runBlocking {
            withRoot { root ->
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                val fileSystem =
                    BlockingArtifactCopyFileSystem(candidate.prepared.file)
                val authorities = Authorities(root, fileSystem)
                val commitDispatcher =
                    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                val finalAdmissionReached = CountDownLatch(1)
                try {
                    val bootstrapStore = authorities.store()
                    assertTrue(
                        bootstrapStore.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val store = authorities.store(
                        checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                            if (
                                phase ==
                                CredentialFilterConfigurationPhase.WAITING_FOR_FINAL_AUTHORITY
                            ) {
                                finalAdmissionReached.countDown()
                            }
                        },
                    )
                    val commit = async(commitDispatcher) {
                        store.commit(1L, mutation(candidate))
                    }
                    assertTrue(fileSystem.copyStarted.await(5, TimeUnit.SECONDS))
                    authorities.configurationAuthority.mutex.lock()
                    try {
                        fileSystem.releaseCopy.countDown()
                        assertTrue(finalAdmissionReached.await(5, TimeUnit.SECONDS))

                        commit.cancelAndJoin()
                        assertTrue(commit.isCancelled)
                        assertTrue(authorities.configurationAuthority.mutex.isLocked)
                    } finally {
                        authorities.configurationAuthority.mutex.unlock()
                    }

                    assertEquals(
                        initial.state,
                        (store.readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                    assertTrue(
                        authorities.artifactStore.generationDirectoryForTesting(
                            candidate.prepared.sha256,
                        ).isDirectory,
                    )
                    assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())
                } finally {
                    fileSystem.releaseCopy.countDown()
                    commitDispatcher.close()
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `coordinator cancellation after artifact install before final configuration admission retains ready state`() =
        runBlocking {
            withRoot { root ->
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                val fileSystem =
                    BlockingArtifactCopyFileSystem(candidate.prepared.file)
                val authorities = Authorities(root, fileSystem)
                val applyDispatcher =
                    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                val finalAdmissionReached = CountDownLatch(1)
                var cleanups = 0
                try {
                    val bootstrapStore = authorities.store()
                    assertTrue(
                        bootstrapStore.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val store = authorities.store(
                        checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                            if (
                                phase ==
                                CredentialFilterConfigurationPhase.WAITING_FOR_FINAL_AUTHORITY
                            ) {
                                finalAdmissionReached.countDown()
                            }
                        },
                    )
                    val coordinator = coordinator(
                        initial = initial.state,
                        candidate = candidate,
                        publisher = store,
                    ) {
                        cleanups += 1
                    }
                    val apply = async(applyDispatcher) {
                        coordinator.apply(
                            FilterMutationRequest(
                                expectedGeneration = 1L,
                                desired = candidate.state.configuration,
                            ),
                        )
                    }
                    assertTrue(fileSystem.copyStarted.await(5, TimeUnit.SECONDS))
                    authorities.configurationAuthority.mutex.lock()
                    try {
                        fileSystem.releaseCopy.countDown()
                        assertTrue(finalAdmissionReached.await(5, TimeUnit.SECONDS))

                        apply.cancelAndJoin()
                        assertTrue(apply.isCancelled)
                        assertTrue(authorities.configurationAuthority.mutex.isLocked)
                    } finally {
                        authorities.configurationAuthority.mutex.unlock()
                    }

                    assertEquals(1, cleanups)
                    assertEquals(initial.state, coordinator.confirmedState.value)
                    assertEquals(
                        FilterMutationAuthorityState.Ready(initial.state),
                        coordinator.authorityState.value,
                    )
                    assertEquals(
                        initial.state,
                        (store.readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                    assertTrue(
                        authorities.artifactStore.generationDirectoryForTesting(
                            candidate.prepared.sha256,
                        ).isDirectory,
                    )
                    assertFalse(authorities.artifactStore.currentPointerFileForTesting.exists())
                } finally {
                    fileSystem.releaseCopy.countDown()
                    applyDispatcher.close()
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `caller cancellation after durable commit keeps candidate recoverable and cleans once`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                val candidate = fixture(
                    root.resolve("candidate"),
                    initial.state.configuration.withResolverPlan(
                        MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    ),
                    2L,
                )
                val caller = Job()
                var cleanups = 0
                try {
                    authorities.store().initialize(initial.state, initial.prepared)
                    val store = authorities.store(
                        checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                            if (
                                phase ==
                                CredentialFilterConfigurationPhase.RECORD_DIRECTORY_SYNCED
                            ) {
                                caller.cancel()
                            }
                        },
                    )
                    val coordinator = coordinator(
                        initial = initial.state,
                        candidate = candidate,
                        publisher = store,
                    ) {
                        cleanups += 1
                    }

                    var cancelled = false
                    try {
                        withContext(caller) {
                            coordinator.apply(FilterMutationRequest(1L, candidate.state.configuration))
                        }
                    } catch (_: CancellationException) {
                        cancelled = true
                    }

                    assertTrue(cancelled)
                    assertEquals(1, cleanups)
                    assertEquals(candidate.state, coordinator.confirmedState.value)
                    assertEquals(
                        candidate.state,
                        (authorities.store().readAuthoritative() as
                            CredentialFilterConfigurationRead.Available).state,
                    )
                } finally {
                    caller.cancel()
                    candidate.close()
                    initial.close()
                }
            }
        }

    @Test
    fun `QA publication predicate is checked under final authority after installation before record replacement`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val candidate = fixture(root.resolve("candidate"), initial.state.configuration.withResolverPlan(
                MilestoneResolverPlan.DEVICE_DNS_ONLY), 2L)
            var current = true
            var checks = 0
            try {
                authorities.store().initialize(initial.state, initial.prepared)
                val before = authorities.store().authoritativeRecordFileForTesting.readBytes().toList()
                val fenced = authorities.store(checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                    if (phase == CredentialFilterConfigurationPhase.WAITING_FOR_FINAL_AUTHORITY) current = false
                })
                val result = fenced.commit(1L, mutation(candidate, publicationAdmission = { checks++; current }))
                assertEquals(FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED), result)
                assertEquals(1, checks)
                assertEquals(before, fenced.authoritativeRecordFileForTesting.readBytes().toList())
                assertEquals(initial.state, (fenced.readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
                // Candidate installation is not publication; a later independently admitted
                // mutation may still use the same verified immutable artifact.
                assertTrue(authorities.artifactStore.generationDirectoryForTesting(candidate.prepared.sha256).isDirectory)
                assertEquals(FilterMutationPublishResult.Committed,
                    authorities.store().commit(1L, mutation(candidate, publicationAdmission = { true })))
                assertEquals(candidate.state, (authorities.store().readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
            } finally { candidate.close(); initial.close() }
        }
    }

    @Test
    fun `QA predicate retirement at final authority keeps coordinator and restarted record unchanged and cleans staging once`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val candidate = fixture(root.resolve("candidate"), initial.state.configuration.withResolverPlan(
                MilestoneResolverPlan.DEVICE_DNS_ONLY), 2L)
            var current = true
            var cleanups = 0
            try {
                authorities.store().initialize(initial.state, initial.prepared)
                val fenced = authorities.store(checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                    if (phase == CredentialFilterConfigurationPhase.WAITING_FOR_FINAL_AUTHORITY) current = false
                })
                val owner = coordinator(initial.state, candidate, fenced) { cleanups++ }
                val result = owner.apply(FilterMutationRequest(1L, candidate.state.configuration,
                    publicationAdmission = { current }))
                assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
                assertEquals(initial.state, owner.confirmedState.value)
                assertEquals(initial.state, (authorities.store().readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
                assertEquals(1, cleanups)
            } finally { candidate.close(); initial.close() }
        }
    }

    private fun coordinator(
        initial: ConfirmedFilterState,
        candidate: Fixture,
        publisher: CredentialFilterConfigurationStore,
        cleanup: () -> Unit,
    ): TransactionalFilterMutationCoordinator =
        TransactionalFilterMutationCoordinator(
            initialState = initial,
            catalogCompileAuthority = FilterCatalogCompileAuthority(
                revision = CATALOG_REVISION,
                sha256 = CATALOG_SHA256,
                sources = emptyList(),
                maxRules = 0,
            ),
            acceptedSourceAuthority = AcceptedFilterSourceAuthority { sources, _ ->
                check(sources.isEmpty())
                emptyList()
            },
            preparer = FilterMutationPreparer { plan ->
                FilterMutationPreparationResult.Prepared(mutation(candidate, cleanup,
                    publicationAdmission = plan.mutationRequest.publicationAdmission))
            },
            publisher = publisher,
        )

    private fun mutation(
        fixture: Fixture,
        cleanup: () -> Unit = {},
        publicationAdmission: (() -> Boolean)? = null,
    ): PreparedFilterMutation =
        PreparedFilterMutation(
            candidate = fixture.state,
            compilationReceipt = fixture.prepared.compilationReceipt,
            publicationArtifact = fixture.prepared,
            publicationAdmission = publicationAdmission,
            cleanup = cleanup,
        )

    private suspend fun fixture(
        root: File,
        configuration: FilterLibraryDraft,
        generation: Long,
        maxRules: Int = 0,
    ): Fixture {
        val identity = ConfigurationIdentity(
            generation = generation,
            fingerprint = canonicalFilterConfigurationFingerprint(
                configuration = configuration,
                catalogRevision = CATALOG_REVISION,
                catalogSha256 = CATALOG_SHA256,
            ),
        )
        val manual = requireNotNull(configuration.profile(configuration.activeProfileId)).manualRules
        val allow = manual.allowedDomains.map { com.lavasec.app.foundation.filter.DomainRule.parse(it) }
        val block = manual.blockedDomains.map { com.lavasec.app.foundation.filter.DomainRule.parse(it) }
        val prepared = FilterCompiler(root.resolve("scratch")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(
                    identity.generation,
                    identity.fingerprint,
                ),
                selectedSources = emptyList(),
                catalogRevision = CATALOG_REVISION,
                catalogSha256 = CATALOG_SHA256,
                maxRules = maxRules,
                intentionalEmpty = allow.isEmpty() && block.isEmpty(),
                manualAllowRules = allow,
                manualBlockRules = block,
            ),
        )
        val verified = CredentialFilterArtifactStagingVerifier.verify(
            prepared.file,
            prepared.compilationReceipt,
        ) as CredentialFilterArtifactStagingResult.Verified
        val state = ConfirmedFilterState(
            configuration = configuration,
            configurationIdentity = identity,
            credentialUnlockRequired = configuration.profiles.any {
                it.manualRules.allowedDomains.isNotEmpty() || it.manualRules.blockedDomains.isNotEmpty()
            },
            verifiedArtifact = verified.verifiedArtifact,
        )
        return Fixture(prepared, verified, state)
    }

    private fun baseConfiguration(): FilterLibraryDraft =
        FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList()))

    private suspend fun withRoot(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("authoritative-filter-config-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class Authorities(
        private val root: File,
        artifactFileSystem: ArtifactPublicationFileSystem =
            JvmArtifactPublicationFileSystem,
    ) {
        private val artifactAuthority = CredentialFilterArtifactPublicationAuthority()
        val configurationAuthority = CredentialFilterConfigurationAuthority()

        val artifactStore = CredentialFilterArtifactPublicationStore(
            rootDirectory = root,
            authority = artifactAuthority,
            fileSystem = artifactFileSystem,
        )

        fun store(
            checkpoint: CredentialFilterConfigurationCheckpoint =
                CredentialFilterConfigurationCheckpoint { },
            fileSystem: ArtifactPublicationFileSystem =
                JvmArtifactPublicationFileSystem,
            maxRules: Int = 0,
        ): CredentialFilterConfigurationStore =
            CredentialFilterConfigurationStore(
                rootDirectory = root,
                authority = configurationAuthority,
                artifactStore = artifactStore,
                fileSystem = fileSystem,
                maxRules = maxRules,
                checkpoint = checkpoint,
            )
    }

    private class BlockingFailingArtifactCopyFileSystem(
        candidateSource: File,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val normalizedCandidateSource =
            candidateSource.absoluteFile.toPath().normalize().toFile()
        val copyStarted = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)

        override fun copyCreateNewAndSync(
            source: File,
            target: File,
            maxBytes: Long,
        ) {
            if (
                source.absoluteFile.toPath().normalize().toFile() ==
                normalizedCandidateSource
            ) {
                copyStarted.countDown()
                check(releaseCopy.await(5, TimeUnit.SECONDS)) {
                    "timed out waiting to fail candidate artifact copy"
                }
                throw IOException("injected candidate artifact copy failure")
            }
            JvmArtifactPublicationFileSystem.copyCreateNewAndSync(
                source,
                target,
                maxBytes,
            )
        }
    }

    private class BlockingArtifactCopyFileSystem(
        candidateSource: File,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val normalizedCandidateSource =
            candidateSource.absoluteFile.toPath().normalize().toFile()
        val copyStarted = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)

        override fun copyCreateNewAndSync(
            source: File,
            target: File,
            maxBytes: Long,
        ) {
            if (
                source.absoluteFile.toPath().normalize().toFile() ==
                normalizedCandidateSource
            ) {
                copyStarted.countDown()
                check(releaseCopy.await(5, TimeUnit.SECONDS)) {
                    "timed out waiting to release candidate artifact copy"
                }
            }
            JvmArtifactPublicationFileSystem.copyCreateNewAndSync(
                source,
                target,
                maxBytes,
            )
        }
    }

    private class ArtifactCopyCountingFileSystem(
        candidateSource: File,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val normalizedCandidateSource =
            candidateSource.absoluteFile.toPath().normalize().toFile()
        var copyAttempts: Int = 0
            private set

        override fun copyCreateNewAndSync(
            source: File,
            target: File,
            maxBytes: Long,
        ) {
            if (
                source.absoluteFile.toPath().normalize().toFile() ==
                normalizedCandidateSource
            ) {
                copyAttempts += 1
            }
            JvmArtifactPublicationFileSystem.copyCreateNewAndSync(
                source,
                target,
                maxBytes,
            )
        }
    }

    private class CandidateCopyBarrierFileSystem(
        candidateSources: Set<File>,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val normalizedCandidateSources = candidateSources.mapTo(mutableSetOf()) {
            it.absoluteFile.toPath().normalize().toFile()
        }
        private val candidateCopiesStarted = CountDownLatch(candidateSources.size)

        override fun copyCreateNewAndSync(
            source: File,
            target: File,
            maxBytes: Long,
        ) {
            if (
                source.absoluteFile.toPath().normalize().toFile() in
                normalizedCandidateSources
            ) {
                candidateCopiesStarted.countDown()
                check(candidateCopiesStarted.await(5, TimeUnit.SECONDS)) {
                    "timed out waiting for candidate artifact copy preflights"
                }
            }
            JvmArtifactPublicationFileSystem.copyCreateNewAndSync(
                source,
                target,
                maxBytes,
            )
        }
    }

    private class CloseFailingArtifactFileSystem :
        ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        var closeFailuresRemaining: Int = 0

        override fun openRegularFile(file: File, maxBytes: Long): FileChannel =
            CloseFailingFileChannel(
                delegate = JvmArtifactPublicationFileSystem.openRegularFile(file, maxBytes),
                shouldFail = {
                    if (closeFailuresRemaining > 0) {
                        closeFailuresRemaining -= 1
                        true
                    } else {
                        false
                    }
                },
            )
    }

    /**
     * Injects an IOException after the JVM backend has completed and closed its read.
     *
     * Direct descriptor-close behavior is covered by the close helper tests and API-28
     * instrumentation; these cases exercise store handling at the post-read boundary.
     */
    private class PostReadFailingAuthoritativeRecordFileSystem :
        ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        var postReadFailuresRemaining: Int = 0

        override fun readBoundedRegularFile(file: File, maxBytes: Int): ByteArray {
            val bytes = JvmArtifactPublicationFileSystem.readBoundedRegularFile(
                file,
                maxBytes,
            )
            if (postReadFailuresRemaining > 0) {
                postReadFailuresRemaining -= 1
                throw IOException("injected authoritative-record post-read I/O failure")
            }
            return bytes
        }
    }

    private class CloseFailingFileChannel(
        private val delegate: FileChannel,
        private val shouldFail: () -> Boolean,
    ) : FileChannel() {
        override fun read(destination: ByteBuffer): Int = delegate.read(destination)

        override fun read(
            destinations: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
        ): Long = delegate.read(destinations, offset, length)

        override fun write(source: ByteBuffer): Int = delegate.write(source)

        override fun write(
            sources: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
        ): Long = delegate.write(sources, offset, length)

        override fun position(): Long = delegate.position()

        override fun position(newPosition: Long): FileChannel {
            delegate.position(newPosition)
            return this
        }

        override fun size(): Long = delegate.size()

        override fun truncate(size: Long): FileChannel {
            delegate.truncate(size)
            return this
        }

        override fun force(metaData: Boolean) = delegate.force(metaData)

        override fun transferTo(
            position: Long,
            count: Long,
            target: WritableByteChannel,
        ): Long = delegate.transferTo(position, count, target)

        override fun transferFrom(
            source: ReadableByteChannel,
            position: Long,
            count: Long,
        ): Long = delegate.transferFrom(source, position, count)

        override fun read(destination: ByteBuffer, position: Long): Int =
            delegate.read(destination, position)

        override fun write(source: ByteBuffer, position: Long): Int =
            delegate.write(source, position)

        override fun map(
            mode: FileChannel.MapMode,
            position: Long,
            size: Long,
        ): MappedByteBuffer = delegate.map(mode, position, size)

        override fun lock(
            position: Long,
            size: Long,
            shared: Boolean,
        ): FileLock = delegate.lock(position, size, shared)

        override fun tryLock(
            position: Long,
            size: Long,
            shared: Boolean,
        ): FileLock? = delegate.tryLock(position, size, shared)

        override fun implCloseChannel() {
            var delegateFailure: IOException? = null
            try {
                delegate.close()
            } catch (error: IOException) {
                delegateFailure = error
            }
            if (shouldFail()) {
                throw IOException("injected artifact descriptor close failure", delegateFailure)
            }
            delegateFailure?.let { throw it }
        }
    }

    private class RootSyncFailingFileSystem(
        root: File,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val normalizedRoot = root.absoluteFile.toPath().normalize().toFile()
        var failuresRemaining: Int = 0
        var rootSyncAttempts: Int = 0
        private var replacementAttempted = false

        override fun replaceFile(source: File, target: File) {
            JvmArtifactPublicationFileSystem.replaceFile(source, target)
            replacementAttempted = true
        }

        override fun syncDirectory(directory: File) {
            if (
                replacementAttempted &&
                directory.absoluteFile.toPath().normalize().toFile() ==
                normalizedRoot
            ) {
                rootSyncAttempts += 1
                if (failuresRemaining > 0) {
                    failuresRemaining -= 1
                    throw IOException("injected authoritative-directory sync failure")
                }
            }
            JvmArtifactPublicationFileSystem.syncDirectory(directory)
        }
    }

    private class RootSyncCountingFileSystem(
        root: File,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val normalizedRoot = root.absoluteFile.toPath().normalize().toFile()
        var rootSyncAttempts: Int = 0
            private set

        override fun syncDirectory(directory: File) {
            if (
                directory.absoluteFile.toPath().normalize().toFile() ==
                normalizedRoot
            ) {
                rootSyncAttempts += 1
            }
            JvmArtifactPublicationFileSystem.syncDirectory(directory)
        }
    }

    private class RecordExistenceFailingFileSystem(
        root: File,
    ) : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        private val authoritativeRecord =
            root.resolve("authoritative.filter-configuration")
                .absoluteFile
                .toPath()
                .normalize()
                .toFile()

        override fun existsNoFollow(file: File): Boolean {
            if (
                file.absoluteFile.toPath().normalize().toFile() ==
                authoritativeRecord
            ) {
                throw IOException("injected authoritative-record lstat failure")
            }
            return JvmArtifactPublicationFileSystem.existsNoFollow(file)
        }
    }

    private class Fixture(
        val prepared: PreparedFilterArtifact,
        val verified: CredentialFilterArtifactStagingResult.Verified,
        val state: ConfirmedFilterState,
    ) : AutoCloseable {
        override fun close() {
            if (verified.artifact.channelIsOpenForTesting) verified.close()
            prepared.close()
        }
    }

    private companion object {
        const val CATALOG_REVISION = "authoritative-config-catalog"
        val CATALOG_SHA256 = "c".repeat(64)
    }
}
