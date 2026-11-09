package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.DomainFilterDecisionSource
import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.ManualDomainRules
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VerifiedFilterRuntimeLeaseTest {
    @Test
    fun `exact open artifact creates immutable runtime metadata and real mapped decisions`() = runTest {
        val opened = openedFixture(generation = 71L)
        try {
            val lease = requireNotNull(
                VerifiedFilterRuntimeLease.takeOpenedOrNull(
                    confirmedState = opened.confirmedState,
                    artifact = opened.artifact,
                ),
            )

            assertSame(opened.confirmedState, lease.confirmedState)
            assertEquals(opened.confirmedState.configurationIdentity, lease.configurationIdentity)
            assertEquals(opened.fixture.reference.sha256, lease.artifactDigest)
            assertEquals(opened.fixture.reference.artifactIdentity.ruleCounts, lease.ruleCounts)
            assertTrue(opened.artifact.channelIsOpenForTesting)

            val decisions = availableValue(lease.withDecisions { source ->
                assertEquals(1, source.blockRuleCount)
                assertEquals(1, source.allowRuleCount)
                assertEquals(1, source.guardrailRuleCount)
                listOf(
                    source.decide(ALLOW_DOMAIN),
                    source.decide(BLOCK_DOMAIN),
                    source.decide(GUARDRAIL_DOMAIN),
                    source.decide(DEFAULT_DOMAIN),
                )
            })

            assertEquals(
                listOf(
                    FilterDecision(FilterAction.ALLOW, FilterDecisionReason.LOCAL_ALLOWLIST),
                    FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
                    FilterDecision(FilterAction.BLOCK, FilterDecisionReason.THREAT_GUARDRAIL),
                    FilterDecision.DEFAULT_ALLOW,
                ),
                decisions,
            )

            lease.close()
            assertFalse(opened.artifact.channelIsOpenForTesting)
        } finally {
            opened.close()
        }
    }

    @Test
    fun `exclusive runtime ownership claim is atomic and cannot be repeated`() = runTest {
        val opened = openedFixture(generation = 711L)
        val lease = requireNotNull(
            VerifiedFilterRuntimeLease.takeOpenedOrNull(
                confirmedState = opened.confirmedState,
                artifact = opened.artifact,
            ),
        )
        val start = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val successfulClaims = AtomicInteger()
        val failures = listOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        val threads = failures.map { observedFailure ->
            Thread {
                check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                try {
                    lease.claimExclusiveRuntimeOwnership()
                    successfulClaims.incrementAndGet()
                } catch (failure: Throwable) {
                    observedFailure.set(failure)
                }
                completed.countDown()
            }
        }
        try {
            threads.forEach(Thread::start)
            start.countDown()
            assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            threads.forEach { it.joinOrFail() }

            assertEquals(1, successfulClaims.get())
            assertEquals(1, failures.count { it.get() is IllegalStateException })
            assertThrows(IllegalStateException::class.java) {
                lease.claimExclusiveRuntimeOwnership()
            }
            assertTrue(
                lease.withDecisions { it.decide(DEFAULT_DOMAIN) } is
                    FilterRuntimeLeaseRead.Available<*>,
            )
        } finally {
            start.countDown()
            threads.forEach { it.join(TIMEOUT_MILLIS) }
            lease.close()
            opened.close()
        }
    }

    @Test
    fun `substituted state is rejected without taking caller artifact ownership`() = runTest {
        val opened = openedFixture(generation = 72L)
        val substituted = openedFixture(generation = 73L)
        try {
            assertEquals(
                null,
                VerifiedFilterRuntimeLease.takeOpenedOrNull(
                    confirmedState = substituted.confirmedState,
                    artifact = opened.artifact,
                ),
            )
            assertTrue(opened.artifact.channelIsOpenForTesting)
        } finally {
            substituted.close()
            opened.close()
        }
    }

    @Test
    fun `closed artifact is rejected without minting a runtime lease`() = runTest {
        val opened = openedFixture(generation = 74L)
        opened.artifact.close()
        try {
            assertEquals(
                null,
                VerifiedFilterRuntimeLease.takeOpenedOrNull(
                    confirmedState = opened.confirmedState,
                    artifact = opened.artifact,
                ),
            )
        } finally {
            opened.close()
        }
    }

    @Test
    fun `close retires immediately then drains an admitted mapped decision before physical close`() =
        runTest {
            val opened = openedFixture(generation = 75L)
            val lease = requireNotNull(
                VerifiedFilterRuntimeLease.takeOpenedOrNull(
                    confirmedState = opened.confirmedState,
                    artifact = opened.artifact,
                ),
            )
            val decisionEntered = CountDownLatch(1)
            val releaseDecision = CountDownLatch(1)
            val decisionResult = AtomicReference<FilterRuntimeLeaseRead<FilterDecision>>()
            val decisionFailure = AtomicReference<Throwable?>()
            val closeFailure = AtomicReference<Throwable?>()
            val decisionThread = Thread {
                try {
                    decisionResult.set(
                        lease.withDecisions { source ->
                            decisionEntered.countDown()
                            check(releaseDecision.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            source.decide(BLOCK_DOMAIN)
                        },
                    )
                } catch (failure: Throwable) {
                    decisionFailure.set(failure)
                }
            }
            val closeThread = Thread {
                try {
                    lease.close()
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                }
            }
            try {
                decisionThread.start()
                assertTrue(decisionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                closeThread.start()
                awaitUnavailable(lease)

                assertTrue(closeThread.isAlive)
                assertTrue(opened.artifact.channelIsOpenForTesting)
                assertSame(
                    FilterRuntimeLeaseRead.Unavailable,
                    lease.withDecisions { fail("retired lease admitted a new decision") },
                )

                releaseDecision.countDown()
                decisionThread.joinOrFail()
                closeThread.joinOrFail()

                assertEquals(null, decisionFailure.get())
                assertEquals(null, closeFailure.get())
                assertEquals(
                    FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
                    availableValue(checkNotNull(decisionResult.get())),
                )
                assertFalse(opened.artifact.channelIsOpenForTesting)
                assertSame(
                    FilterRuntimeLeaseRead.Unavailable,
                    lease.withDecisions { fail("closed lease admitted a new decision") },
                )
            } finally {
                releaseDecision.countDown()
                decisionThread.join(TIMEOUT_MILLIS)
                closeThread.join(TIMEOUT_MILLIS)
                runCatching { lease.close() }
                opened.close()
            }
        }

    @Test
    fun `retirement drains a concurrent escaped decision and rejects every later mapped call`() =
        runTest {
            val opened = openedFixture(generation = 751L)
            val gated = GatedMappedDecisionSource(opened.artifact.snapshot)
            val lease = requireNotNull(
                VerifiedFilterRuntimeLease.takeOpenedOrNullForTesting(
                    confirmedState = opened.confirmedState,
                    artifact = opened.artifact,
                    decisions = gated,
                    closeArtifact = opened.artifact::close,
                ),
            )
            val escaped = AtomicReference<DomainFilterDecisionSource>()
            val callbackReturned = CountDownLatch(1)
            val scopeExited = CountDownLatch(1)
            val scopeFailure = AtomicReference<Throwable?>()
            val decisionFailure = AtomicReference<Throwable?>()
            val closeExited = CountDownLatch(1)
            val closeFailure = AtomicReference<Throwable?>()
            val decisionThread = Thread {
                try {
                    checkNotNull(escaped.get()).decide(BLOCK_DOMAIN)
                } catch (failure: Throwable) {
                    decisionFailure.set(failure)
                }
            }
            val scopeThread = Thread {
                try {
                    val read = lease.withDecisions { source ->
                        escaped.set(source)
                        decisionThread.start()
                        assertTrue(gated.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        callbackReturned.countDown()
                    }
                    assertTrue(read is FilterRuntimeLeaseRead.Available<*>)
                } catch (failure: Throwable) {
                    scopeFailure.set(failure)
                } finally {
                    scopeExited.countDown()
                }
            }
            val closeThread = Thread {
                try {
                    lease.close()
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                } finally {
                    closeExited.countDown()
                }
            }
            try {
                scopeThread.start()
                assertTrue(callbackReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                assertFalse(
                    "decision scope returned while an escaped mapped call was still admitted",
                    scopeExited.await(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS),
                )
                closeThread.start()
                assertFalse(
                    "lease closed while an escaped mapped call was still admitted",
                    closeExited.await(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS),
                )
                assertTrue(opened.artifact.channelIsOpenForTesting)

                gated.release.countDown()
                decisionThread.joinOrFail()
                scopeThread.joinOrFail()
                closeThread.joinOrFail()

                assertEquals(null, decisionFailure.get())
                assertEquals(null, scopeFailure.get())
                assertEquals(null, closeFailure.get())
                assertFalse(opened.artifact.channelIsOpenForTesting)
                assertEquals(
                    FilterDecision(FilterAction.BLOCK, FilterDecisionReason.PROTECTION_UNAVAILABLE),
                    checkNotNull(escaped.get()).decide(BLOCK_DOMAIN),
                )
                assertEquals(1, gated.calls.get())
            } finally {
                gated.release.countDown()
                decisionThread.join(TIMEOUT_MILLIS)
                scopeThread.join(TIMEOUT_MILLIS)
                closeThread.join(TIMEOUT_MILLIS)
                runCatching { lease.close() }
                opened.close()
            }
        }

    @Test
    fun `callback failure is rethrown exactly and releases its admission`() = runTest {
        val opened = openedFixture(generation = 76L)
        val lease = requireNotNull(
            VerifiedFilterRuntimeLease.takeOpenedOrNull(
                confirmedState = opened.confirmedState,
                artifact = opened.artifact,
            ),
        )
        val expected = IOException("decision callback failed")
        try {
            val observed = try {
                lease.withDecisions<Nothing> { throw expected }
                fail("decision callback failure was swallowed")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(expected, observed)
            lease.close()
            assertFalse(opened.artifact.channelIsOpenForTesting)
        } finally {
            runCatching { lease.close() }
            opened.close()
        }
    }

    @Test
    fun `callback close reentry fails fast without retiring the lease`() = runTest {
        val opened = openedFixture(generation = 77L)
        val lease = requireNotNull(
            VerifiedFilterRuntimeLease.takeOpenedOrNull(
                confirmedState = opened.confirmedState,
                artifact = opened.artifact,
            ),
        )
        try {
            val decision = availableValue(lease.withDecisions { source ->
                val failure: IllegalStateException = try {
                    lease.close()
                    throw AssertionError("callback close reentry did not fail")
                } catch (failure: IllegalStateException) {
                    failure
                }
                assertTrue(failure.message.orEmpty().contains("decision"))
                assertTrue(opened.artifact.channelIsOpenForTesting)
                source.decide(ALLOW_DOMAIN)
            })

            assertEquals(
                FilterDecision(FilterAction.ALLOW, FilterDecisionReason.LOCAL_ALLOWLIST),
                decision,
            )
            assertTrue(
                lease.withDecisions { it.decide(DEFAULT_DOMAIN) } is
                    FilterRuntimeLeaseRead.Available<*>,
            )

            lease.close()
            assertFalse(opened.artifact.channelIsOpenForTesting)
        } finally {
            runCatching { lease.close() }
            opened.close()
        }
    }

    @Test
    fun `repeated and concurrent close performs one physical artifact close`() = runTest {
        val opened = openedFixture(generation = 78L)
        val closeCalls = AtomicInteger()
        val physicalCloseEntered = CountDownLatch(1)
        val releasePhysicalClose = CountDownLatch(1)
        val lease = requireNotNull(
            VerifiedFilterRuntimeLease.takeOpenedOrNullForTesting(
                confirmedState = opened.confirmedState,
                artifact = opened.artifact,
                closeArtifact = {
                    closeCalls.incrementAndGet()
                    physicalCloseEntered.countDown()
                    check(releasePhysicalClose.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    opened.artifact.close()
                },
            ),
        )
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val first = closeThread(lease, firstFailure)
        val second = closeThread(lease, secondFailure)
        try {
            first.start()
            assertTrue(physicalCloseEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            second.start()
            assertTrue(second.isAlive)

            releasePhysicalClose.countDown()
            first.joinOrFail()
            second.joinOrFail()
            lease.close()

            assertEquals(null, firstFailure.get())
            assertEquals(null, secondFailure.get())
            assertEquals(1, closeCalls.get())
            assertFalse(opened.artifact.channelIsOpenForTesting)
        } finally {
            releasePhysicalClose.countDown()
            first.join(TIMEOUT_MILLIS)
            second.join(TIMEOUT_MILLIS)
            runCatching { lease.close() }
            opened.close()
        }
    }

    @Test
    fun `every close caller receives the same physical close failure`() = runTest {
        val opened = openedFixture(generation = 79L)
        val closeCalls = AtomicInteger()
        val physicalCloseEntered = CountDownLatch(1)
        val releasePhysicalClose = CountDownLatch(1)
        val expected = IOException("artifact close failed")
        val lease = requireNotNull(
            VerifiedFilterRuntimeLease.takeOpenedOrNullForTesting(
                confirmedState = opened.confirmedState,
                artifact = opened.artifact,
                closeArtifact = {
                    closeCalls.incrementAndGet()
                    physicalCloseEntered.countDown()
                    check(releasePhysicalClose.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    opened.artifact.close()
                    throw expected
                },
            ),
        )
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val first = closeThread(lease, firstFailure)
        val second = closeThread(lease, secondFailure)
        try {
            first.start()
            assertTrue(physicalCloseEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            second.start()

            releasePhysicalClose.countDown()
            first.joinOrFail()
            second.joinOrFail()
            val repeatedFailure = try {
                lease.close()
                fail("repeated close did not retain the physical failure")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(expected, firstFailure.get())
            assertSame(expected, secondFailure.get())
            assertSame(expected, repeatedFailure)
            assertEquals(1, closeCalls.get())
            assertFalse(opened.artifact.channelIsOpenForTesting)
            assertSame(
                FilterRuntimeLeaseRead.Unavailable,
                lease.withDecisions { fail("failed-close lease admitted a decision") },
            )
        } finally {
            releasePhysicalClose.countDown()
            first.join(TIMEOUT_MILLIS)
            second.join(TIMEOUT_MILLIS)
            opened.close()
        }
    }

    @Test
    fun `completed close releases mapped decisions and artifact callback after failure`() = runTest {
        val opened = openedFixture(generation = 791L)
        val expected = IOException("artifact close failed after descriptor release")
        val lease = requireNotNull(
            VerifiedFilterRuntimeLease.takeOpenedOrNullForTesting(
                confirmedState = opened.confirmedState,
                artifact = opened.artifact,
                closeArtifact = {
                    opened.artifact.close()
                    throw expected
                },
            ),
        )
        try {
            assertEquals(2, lease.retainedRuntimeResourceCountForTesting)

            val observed = try {
                lease.close()
                fail("physical close failure was not preserved")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(expected, observed)
            assertEquals(0, lease.retainedRuntimeResourceCountForTesting)
            assertFalse(opened.artifact.channelIsOpenForTesting)
            assertSame(
                expected,
                assertThrows(IOException::class.java) {
                    lease.close()
                },
            )
        } finally {
            opened.close()
        }
    }

    private suspend fun openedFixture(generation: Long): OpenedFixture {
        val configuration = filterConfiguration()
        val identity = ConfigurationIdentity(
            generation = generation,
            fingerprint = canonicalFilterConfigurationFingerprint(
                configuration = configuration,
                catalogRevision = CATALOG_REVISION,
                catalogSha256 = CATALOG_SHA256,
            ),
        )
        val fixture = RealFilterArtifactFixture.create(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(
                    generation = identity.generation,
                    fingerprint = identity.fingerprint,
                ),
                selectedSources = emptyList(),
                catalogRevision = CATALOG_REVISION,
                catalogSha256 = CATALOG_SHA256,
                maxRules = 100,
                manualAllowRules = listOf(DomainRule.parse(ALLOW_DOMAIN)),
                manualBlockRules = listOf(DomainRule.parse(BLOCK_DOMAIN)),
                guardrailRules = listOf(DomainRule.parse(GUARDRAIL_DOMAIN)),
            ),
        )
        return try {
            val artifact = FilterArtifact.open(
                file = fixture.file,
                expectedSha256 = fixture.reference.sha256,
                expectedIdentity = fixture.reference.artifactIdentity,
            )
            val confirmedState = ConfirmedFilterState(
                configuration = configuration,
                configurationIdentity = identity,
                credentialUnlockRequired = true,
                verifiedArtifact = fixture.verifiedArtifact(),
            )
            OpenedFixture(fixture, artifact, confirmedState)
        } catch (failure: Throwable) {
            fixture.close()
            throw failure
        }
    }

    private fun filterConfiguration(): FilterLibraryDraft {
        val initial = FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList()))
        val active = requireNotNull(initial.profile(initial.activeProfileId))
        return initial.withProfile(
            active.withManualRules(
                ManualDomainRules(
                    allowedDomains = setOf(ALLOW_DOMAIN),
                    blockedDomains = setOf(BLOCK_DOMAIN),
                ),
            ),
        )
    }

    private fun closeThread(
        lease: VerifiedFilterRuntimeLease,
        failure: AtomicReference<Throwable?>,
    ): Thread = Thread {
        try {
            lease.close()
        } catch (thrown: Throwable) {
            failure.set(thrown)
        }
    }

    private fun awaitUnavailable(lease: VerifiedFilterRuntimeLease) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (lease.withDecisions { Unit } === FilterRuntimeLeaseRead.Unavailable) return
            Thread.yield()
        }
        fail("lease did not retire before the timeout")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> availableValue(read: FilterRuntimeLeaseRead<T>): T {
        assertTrue("expected an available lease read, got $read", read is FilterRuntimeLeaseRead.Available<*>)
        return (read as FilterRuntimeLeaseRead.Available<T>).value
    }

    private fun Thread.joinOrFail() {
        join(TIMEOUT_MILLIS)
        assertFalse("thread did not complete before timeout", isAlive)
    }

    private class OpenedFixture(
        val fixture: RealFilterArtifactFixture,
        val artifact: FilterArtifact,
        val confirmedState: ConfirmedFilterState,
    ) : AutoCloseable {
        override fun close() {
            artifact.close()
            fixture.close()
        }
    }

    private class GatedMappedDecisionSource(
        private val delegate: DomainFilterDecisionSource,
    ) : DomainFilterDecisionSource {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()

        override val blockRuleCount: Int = delegate.blockRuleCount
        override val allowRuleCount: Int = delegate.allowRuleCount
        override val guardrailRuleCount: Int = delegate.guardrailRuleCount

        override fun decide(rawDomain: String): FilterDecision = gated {
            delegate.decide(rawDomain)
        }

        override fun decideNormalized(normalizedDomain: String): FilterDecision = gated {
            delegate.decideNormalized(normalizedDomain)
        }

        private fun gated(decide: () -> FilterDecision): FilterDecision {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            return decide()
        }
    }

    companion object {
        private const val ALLOW_DOMAIN = "allow.runtime.fixture.example"
        private const val BLOCK_DOMAIN = "block.runtime.fixture.example"
        private const val GUARDRAIL_DOMAIN = "guardrail.runtime.fixture.example"
        private const val DEFAULT_DOMAIN = "default.runtime.fixture.example"
        private const val CATALOG_REVISION = "contract-catalog-revision"
        private val CATALOG_SHA256 = "b".repeat(64)
        private const val TIMEOUT_SECONDS = 5L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val SHORT_WAIT_MILLIS = 100L
    }
}
