package com.lavasec.app.platform

import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CatalogFilterSource
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.CustomBlocklistSourceAcquisitionException
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.filter.FilterSourcePayload
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.ManualDomainRules
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilterMutationContractTest {
    @Test fun `QA compilation needs native debug admission and commits exact threat-before-allow inputs`() = runTest {
        val base = seededConfiguration()
        val probes = com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED
        val desired = base.withQaProbeSet(probes)
        val authority = RealFilterArtifactFixture.authoritiesFor(base)
        val selected = authority.catalog.selectedSources(desired.profile(desired.activeProfileId)!!)
        val accepted = authority.acceptedSources.acquire(selected, false)
        val request = FilterMutationRequest(9L, desired)
        assertTrue(runCatching { AuthorizedFilterCompilePlan.create(request, authority.catalog, accepted,
            authority.catalog.maxRules) }.exceptionOrNull() is IllegalArgumentException)
        val plan = AuthorizedFilterCompilePlan.create(request, authority.catalog, accepted,
            authority.catalog.maxRules, allowQaProbeSets = true)
        assertEquals(probes.allowRules, plan.compileRequest.manualAllowRules)
        assertEquals(probes.blockRules, plan.compileRequest.manualBlockRules)
        assertEquals(probes.guardrailRules, plan.compileRequest.guardrailRules)
        assertTrue(filterRequiresCredentialUnlock(desired, 0))
        assertNotEquals(canonicalFilterConfigurationFingerprint(base, authority.catalog.revision, authority.catalog.sha256),
            plan.expectedConfiguration.fingerprint)
        assertEquals(canonicalFilterConfigurationFingerprint(base, authority.catalog.revision, authority.catalog.sha256),
            canonicalFilterConfigurationFingerprint(desired.withQaProbeSet(null), authority.catalog.revision, authority.catalog.sha256))
        RealFilterArtifactFixture.create(plan).use { artifact ->
            (CredentialFilterArtifactStagingVerifier.verify(artifact.file, artifact.compilationReceipt)
                as CredentialFilterArtifactStagingResult.Verified).use { verified ->
                assertEquals(com.lavasec.app.foundation.filter.FilterDecisionReason.THREAT_GUARDRAIL,
                    verified.artifact.snapshot.decide(probes.guardrailDomain).reason)
                assertEquals(com.lavasec.app.foundation.filter.FilterDecisionReason.LOCAL_ALLOWLIST,
                    verified.artifact.snapshot.decide(probes.exceptionDomain).reason)
            }
        }
    }

    @Test fun `a preparer cannot omit the protected QA tier while claiming the QA metadata fingerprint`() = runTest {
        val initialFixture = seededFixture()
        val base = seededConfiguration()
        val desired = base.withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)
        val trusted = RealFilterArtifactFixture.authoritiesFor(base)
        var publications = 0
        try {
            val previous = confirmed(base, initialFixture.verifiedArtifact())
            val coordinator = TransactionalFilterMutationCoordinator(
                initialState = previous, catalogCompileAuthority = trusted.catalog,
                acceptedSourceAuthority = trusted.acceptedSources, allowQaProbeSets = true,
                preparer = FilterMutationPreparer { plan ->
                    val incorrect = RealFilterArtifactFixture.create(plan.compileRequest.copy(guardrailRules = emptyList()))
                    FilterMutationPreparationResult.Prepared(PreparedFilterMutation(
                        confirmed(desired, incorrect.verifiedArtifact(), credentialUnlockRequired = true),
                        incorrect.compilationReceipt,
                    ) { incorrect.close() })
                },
                publisher = FilterMutationPublisher { _, _ -> publications++; FilterMutationPublishResult.Committed },
            )
            val result = coordinator.apply(FilterMutationRequest(9L, desired))
            assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
            assertEquals(FilterMutationFailure.IDENTITY_MISMATCH, (result as FilterMutationResult.FailedKeepingPrevious).failure)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(0, publications)
        } finally { initialFixture.close() }
    }

    @Test
    fun `only the exact native publication predicate may survive preparation and a retired visit never publishes`() = runTest {
        for (mode in listOf("current", "retired", "omitted", "substituted")) {
            val initialFixture = seededFixture()
            var current = true
            val admission = { current }
            var commits = 0
            var cleanups = 0
            try {
                val base = seededConfiguration()
                val owner = coordinator(
                    initialState = confirmed(base, initialFixture.verifiedArtifact()),
                    preparer = FilterMutationPreparer { plan ->
                        assertSame(admission, plan.mutationRequest.publicationAdmission)
                        val artifact = RealFilterArtifactFixture.create(plan)
                        if (mode == "retired") current = false
                        val returned = when (mode) {
                            "omitted" -> null
                            "substituted" -> ({ true })
                            else -> admission
                        }
                        FilterMutationPreparationResult.Prepared(PreparedFilterMutation(
                            candidate = confirmed(plan.mutationRequest.desired, artifact.verifiedArtifact()),
                            compilationReceipt = artifact.compilationReceipt,
                            publicationAdmission = returned,
                        ) { cleanups++; artifact.close() })
                    },
                    publisher = FilterMutationPublisher { _, mutation ->
                        assertSame(admission, mutation.publicationAdmission)
                        commits++
                        FilterMutationPublishResult.Committed
                    },
                )
                val result = owner.apply(FilterMutationRequest(9L, base.withActiveProfile("filter-essential"),
                    publicationAdmission = admission))
                assertEquals(1, cleanups)
                if (mode == "current") {
                    assertTrue(result is FilterMutationResult.Applied)
                    assertEquals(1, commits)
                } else {
                    assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
                    assertEquals(0, commits)
                    assertEquals(9L, owner.confirmedState.value.configurationIdentity.generation)
                    assertEquals(if (mode == "retired") FilterMutationFailure.COMMIT_FAILED else FilterMutationFailure.IDENTITY_MISMATCH,
                        (result as FilterMutationResult.FailedKeepingPrevious).failure)
                }
            } finally { initialFixture.close() }
        }
    }

    @Test fun `native final guard must survive compilation by identity and refuses retired durable authority`() = runTest {
        for (mode in listOf("current", "retired", "omitted", "substituted")) {
            val initialFixture = seededFixture()
            val base = seededConfiguration()
            var guardCalls = 0; var writes = 0
            val guard = NativeFilterPublicationGuard { write ->
                guardCalls++
                if (mode == "retired") FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED) else write()
            }
            try {
                val owner = coordinator(
                    initialState = confirmed(base, initialFixture.verifiedArtifact()),
                    preparer = FilterMutationPreparer { plan ->
                        assertSame(guard, plan.mutationRequest.publicationGuard)
                        val artifact = RealFilterArtifactFixture.create(plan)
                        val returned = when (mode) {
                            "omitted" -> null
                            "substituted" -> NativeFilterPublicationGuard { it() }
                            else -> guard
                        }
                        FilterMutationPreparationResult.Prepared(PreparedFilterMutation(
                            confirmed(plan.mutationRequest.desired, artifact.verifiedArtifact()), artifact.compilationReceipt,
                            publicationGuard = returned, cleanup = artifact::close,
                        ))
                    },
                    publisher = FilterMutationPublisher { _, candidate ->
                        assertSame(guard, candidate.publicationGuard)
                        candidate.publicationGuard!!.publish { writes++; FilterMutationPublishResult.Committed }
                    },
                )
                val result = owner.apply(FilterMutationRequest(9L, base.withActiveProfile("filter-essential"), publicationGuard = guard))
                assertEquals(if (mode == "current") 1 else 0, writes)
                assertEquals(if (mode in listOf("current", "retired")) 1 else 0, guardCalls)
                if (mode == "current") assertTrue(result is FilterMutationResult.Applied)
                else {
                    assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
                    assertEquals(9L, owner.confirmedState.value.configurationIdentity.generation)
                }
            } finally { initialFixture.close() }
        }
    }

    @Test
    fun `Direct Boot requires unlock for Plus-sized or private snapshots`() {
        val base = seededConfiguration()
        assertFalse(filterRequiresCredentialUnlock(base, FeatureLimits.FREE.maxFilterRules))
        assertTrue(filterRequiresCredentialUnlock(base, FeatureLimits.FREE.maxFilterRules + 1))
        assertTrue(filterRequiresCredentialUnlock(base, FeatureLimits.PLUS.maxFilterRules))
        val inactive = base.profiles.first { it.id != base.activeProfileId }
        val privateLibrary = base.withProfile(inactive.withManualRules(
            ManualDomainRules(blockedDomains = setOf("private.example")),
        ))
        assertTrue(filterRequiresCredentialUnlock(privateLibrary, 0))
        assertThrows(IllegalArgumentException::class.java) { filterRequiresCredentialUnlock(base, -1) }
    }

    @Test
    fun `admission budget survives acquisition and only its exact witness reaches publication`() = runTest {
        val limits = FeatureLimits.FREE.copy(maxFilterRules = 25)
        val admitted = FeatureLimitAdmission(limits)
        for (returned in listOf(admitted, FeatureLimitAdmission(limits), null)) {
            val initialFixture = seededFixture()
            try {
                val base = seededConfiguration()
                val authority = RealFilterArtifactFixture.authoritiesFor(base)
                var cleanups = 0
                var commits = 0
                val owner = TransactionalFilterMutationCoordinator(
                    initialState = confirmed(base, initialFixture.verifiedArtifact()),
                    catalogCompileAuthority = authority.catalog,
                    acceptedSourceAuthority = authority.acceptedSources,
                    currentRuleBudget = { error("must retain the admission-time budget") },
                    preparer = FilterMutationPreparer { plan ->
                        assertSame(admitted, plan.mutationRequest.admission)
                        assertEquals(25, plan.expectedMaxRules)
                        assertEquals(25, plan.compileRequest.maxRules)
                        val artifact = RealFilterArtifactFixture.create(plan)
                        FilterMutationPreparationResult.Prepared(
                            PreparedFilterMutation(
                                candidate = confirmed(plan.mutationRequest.desired, artifact.verifiedArtifact()),
                                compilationReceipt = artifact.compilationReceipt,
                                admission = returned,
                            ) {
                                cleanups += 1
                                artifact.close()
                            },
                        )
                    },
                    publisher = FilterMutationPublisher { _, mutation ->
                        assertSame(admitted, mutation.admission)
                        commits += 1
                        FilterMutationPublishResult.Committed
                    },
                )
                val result = owner.apply(FilterMutationRequest(
                    9, base.withActiveProfile("filter-essential"), admission = admitted,
                ))
                if (returned === admitted) {
                    assertTrue(result is FilterMutationResult.Applied)
                    assertEquals(1, commits)
                } else {
                    assertEquals(FilterMutationFailure.IDENTITY_MISMATCH,
                        (result as FilterMutationResult.FailedKeepingPrevious).failure)
                    assertEquals(0, commits)
                    assertEquals(9L, owner.confirmedState.value.configurationIdentity.generation)
                }
                assertEquals(1, cleanups)
            } finally { initialFixture.close() }
        }
    }

    @Test
    fun `each mutation captures the live tier budget without replacing catalog authority`() = runTest {
        val fixture = seededFixture()
        try {
            val base = seededConfiguration()
            val authority = RealFilterArtifactFixture.authoritiesFor(base)
            val plans = mutableListOf<AuthorizedFilterCompilePlan>()
            var budget = 100
            val owner = TransactionalFilterMutationCoordinator(
                initialState = confirmed(base, fixture.verifiedArtifact()),
                catalogCompileAuthority = authority.catalog,
                acceptedSourceAuthority = authority.acceptedSources,
                preparer = FilterMutationPreparer {
                    plans += it
                    FilterMutationPreparationResult.Failed(FilterMutationFailure.COMPILE_FAILED)
                },
                publisher = FilterMutationPublisher { _, _ -> error("failed preparation cannot publish") },
                currentRuleBudget = { budget },
            )
            val request = FilterMutationRequest(9, base.withActiveProfile("filter-essential"))
            owner.apply(request)
            budget = 25
            owner.apply(request)
            assertEquals(listOf(100, 25), plans.map { it.compileRequest.maxRules })
            assertEquals(listOf(100, 25), plans.map { it.expectedMaxRules })
            assertEquals(plans[0].expectedSources, plans[1].expectedSources)
            assertEquals(plans[0].expectedCompileInputsSha256, plans[1].expectedCompileInputsSha256)
            budget = 101
            assertTrue(owner.apply(request) is FilterMutationResult.FailedKeepingPrevious)
            assertEquals(2, plans.size)
            assertEquals(9L, owner.confirmedState.value.configurationIdentity.generation)
        } finally { fixture.close() }
    }

    @Test
    fun `whole profile switch compiles and publishes the exact target without touching sibling profiles`() = runTest {
        val previousFixture = seededFixture()
        try {
            val base = seededConfiguration()
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val desired = base.withActiveProfile("filter-essential")
            var cleanups = 0
            var commits = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer { plan ->
                    val fixture = RealFilterArtifactFixture.create(plan)
                    prepared(
                        confirmed(plan.mutationRequest.desired, fixture.verifiedArtifact()),
                        fixture,
                    ) {
                        cleanups += 1
                        fixture.close()
                    }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.Applied)
            val applied = result as FilterMutationResult.Applied
            assertEquals("filter-essential", applied.confirmedState.configuration.activeProfileId)
            assertEquals(10L, applied.confirmedState.configurationIdentity.generation)
            // The switch only replaces the active profile id: every sibling profile keeps its
            // exact seeded identity, sources, and empty manual rules (the durable adoption
            // validates this same property).
            val appliedNonTarget = applied.confirmedState.configuration.profiles
                .filter { it.id != "filter-essential" }
            base.profiles.filter { it.id != "filter-essential" }.forEach { original ->
                assertEquals(original, appliedNonTarget.first { it.id == original.id })
            }
            assertEquals(listOf(previous, applied.confirmedState), emissions)
            assertEquals(1, commits)
            assertEquals(1, cleanups)
            collection.cancel()
        } finally {
            previousFixture.close()
        }
    }

    @Test
    fun `confirmed state accepts only a witness opened for its exact identity`() = runTest {
        val fixture = seededFixture()
        try {
            val witness = fixture.verifiedArtifact()
            val confirmed = confirmed(
                configuration = seededConfiguration(),
                verifiedArtifact = witness,
            )

            assertSame(witness, confirmed.verifiedArtifact)
            assertThrows(IllegalArgumentException::class.java) {
                confirmed.copy(
                    configurationIdentity = confirmed.configurationIdentity.copy(generation = 10L),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                confirmed.copy(
                    configurationIdentity =
                        confirmed.configurationIdentity.copy(fingerprint = "0".repeat(64)),
                )
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `confirmed state derives credential lock requirement from manual rules in any profile`() = runTest {
        val base = seededConfiguration()
        val active = requireNotNull(base.profile(base.activeProfileId))
        val withManualRules = base.withProfile(
            active.withManualRules(
                ManualDomainRules(blockedDomains = setOf("tracking.example")),
            ),
        )
        val inactive = base.profiles.first { it.id != base.activeProfileId }
        val withInactiveManualRules = base.withProfile(
            inactive.withManualRules(
                ManualDomainRules(allowedDomains = setOf("private-inactive.example")),
            ),
        )
        val baseFixture = RealFilterArtifactFixture.createForConfiguration(base, 9L)
        val activeManualFixture =
            RealFilterArtifactFixture.createForConfiguration(withManualRules, 9L)
        val inactiveManualFixture =
            RealFilterArtifactFixture.createForConfiguration(withInactiveManualRules, 9L)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                confirmed(
                    configuration = withManualRules,
                    verifiedArtifact = activeManualFixture.verifiedArtifact(),
                    credentialUnlockRequired = false,
                )
            }
            val credentialOnly = confirmed(
                configuration = withManualRules,
                verifiedArtifact = activeManualFixture.verifiedArtifact(),
                credentialUnlockRequired = true,
            )
            assertTrue(credentialOnly.credentialUnlockRequired)
            assertThrows(IllegalArgumentException::class.java) {
                confirmed(
                    configuration = base,
                    verifiedArtifact = baseFixture.verifiedArtifact(),
                    credentialUnlockRequired = true,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                confirmed(
                    configuration = withInactiveManualRules,
                    verifiedArtifact = inactiveManualFixture.verifiedArtifact(),
                    credentialUnlockRequired = false,
                )
            }
            val inactiveCredentialOnly = confirmed(
                configuration = withInactiveManualRules,
                verifiedArtifact = inactiveManualFixture.verifiedArtifact(),
                credentialUnlockRequired = true,
            )
            assertTrue(inactiveCredentialOnly.credentialUnlockRequired)
        } finally {
            inactiveManualFixture.close()
            activeManualFixture.close()
            baseFixture.close()
        }
    }

    @Test
    fun `stale generation stops before preparation or durable commit and does not emit`() = runTest {
        val fixture = seededFixture()
        try {
            val previous = confirmed(seededConfiguration(), fixture.verifiedArtifact())
            var preparations = 0
            var commits = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    preparations += 1
                    FilterMutationPreparationResult.ProtectionUnavailable
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }

            val result = coordinator.apply(
                FilterMutationRequest(
                    expectedGeneration = 8,
                    desired = previous.configuration.withActiveProfile("filter-essential"),
                ),
            )

            assertTrue(result is FilterMutationResult.Superseded)
            assertSame(previous, result.confirmedState)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(listOf(previous), emissions)
            assertEquals(0, preparations)
            assertEquals(0, commits)
            collection.cancel()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `unchanged draft rejects before source acquisition unless recompilation is forced`() = runTest {
        val fixture = seededFixture()
        try {
            val previous = confirmed(seededConfiguration(), fixture.verifiedArtifact())
            val trusted = RealFilterArtifactFixture.authoritiesFor(previous.configuration)
            var acquisitions = 0
            var preparations = 0
            var commits = 0
            val coordinator = TransactionalFilterMutationCoordinator(
                initialState = previous,
                catalogCompileAuthority = trusted.catalog,
                acceptedSourceAuthority = AcceptedFilterSourceAuthority { sources, forceRefresh ->
                    acquisitions += 1
                    trusted.acceptedSources.acquire(sources, forceRefresh)
                },
                preparer = FilterMutationPreparer {
                    preparations += 1
                    FilterMutationPreparationResult.ProtectionUnavailable
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )

            val result = coordinator.apply(
                FilterMutationRequest(
                    expectedGeneration = 9,
                    desired = previous.configuration,
                ),
            )

            assertTrue(result is FilterMutationResult.Rejected)
            assertEquals(
                FilterMutationRejection.NO_CHANGE,
                (result as FilterMutationResult.Rejected).reason,
            )
            assertSame(previous, result.confirmedState)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(0, acquisitions)
            assertEquals(0, preparations)
            assertEquals(0, commits)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a failed custom source fetch settles as a named source refusal`() = runTest {
        val fixture = seededFixture()
        try {
            val base = seededConfiguration()
            val active = requireNotNull(base.profile(base.activeProfileId))
            val custom = CustomBlocklistSource(
                id = "custom-a",
                displayName = "My list",
                sourceUrl = "https://mine.example/hosts.txt",
                parseFormat = BlocklistFormat.HOSTS,
            )
            val desired = base.withProfile(
                active
                    .withCustomBlocklists(listOf(custom))
                    .withEnabledSourceIds(active.enabledSourceIds + "custom-a"),
            )
            val previous = confirmed(base, fixture.verifiedArtifact())
            val trusted = RealFilterArtifactFixture.authoritiesFor(previous.configuration)
            val coordinator = TransactionalFilterMutationCoordinator(
                initialState = previous,
                catalogCompileAuthority = trusted.catalog,
                acceptedSourceAuthority = AcceptedFilterSourceAuthority { _, _ ->
                    throw CustomBlocklistSourceAcquisitionException(
                        "custom-a",
                        CustomBlocklistSourceFailure.HTTP_STATUS,
                    )
                },
                preparer = FilterMutationPreparer { error("a refused source must not prepare") },
                publisher = FilterMutationPublisher { _, _ -> error("a refused source must not publish") },
            )

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.SourceUnavailable)
            result as FilterMutationResult.SourceUnavailable
            assertEquals("custom-a", result.sourceId)
            assertEquals("My list", result.sourceName)
            assertEquals(CustomBlocklistSourceFailure.HTTP_STATUS, result.failure)
            assertSame(previous, result.confirmedState)
            assertSame(previous, coordinator.confirmedState.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `forced unchanged draft recompiles and advances generation`() = runTest {
        val previousFixture = seededFixture()
        try {
            val configuration = seededConfiguration()
            val previous = confirmed(configuration, previousFixture.verifiedArtifact())
            var preparations = 0
            var commits = 0
            var cleanups = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer { plan ->
                    preparations += 1
                    val fixture = RealFilterArtifactFixture.create(plan)
                    prepared(
                        confirmed(configuration, fixture.verifiedArtifact()),
                        fixture,
                    ) {
                        cleanups += 1
                        fixture.close()
                    }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )

            val result = coordinator.apply(
                FilterMutationRequest(
                    expectedGeneration = 9,
                    desired = configuration,
                    forceRecompile = true,
                ),
            )

            assertTrue(result is FilterMutationResult.Applied)
            assertEquals(10L, result.confirmedState.configurationIdentity.generation)
            assertEquals(configuration, result.confirmedState.configuration)
            assertEquals(1, preparations)
            assertEquals(1, commits)
            assertEquals(1, cleanups)
        } finally {
            previousFixture.close()
        }
    }

    @Test
    fun `non-applied preparations retain exact state and never invoke durable commit`() = runTest {
        val fixture = seededFixture()
        try {
            val previous = confirmed(seededConfiguration(), fixture.verifiedArtifact())
            val desired = previous.configuration.withActiveProfile("filter-essential")
            val outcomes = listOf(
                FilterMutationPreparationResult.Rejected(FilterMutationRejection.INVALID_DRAFT),
                FilterMutationPreparationResult.Failed(FilterMutationFailure.COMPILE_FAILED),
                FilterMutationPreparationResult.ProtectionUnavailable,
            )

            for (outcome in outcomes) {
                var commits = 0
                val coordinator = coordinator(
                    initialState = previous,
                    preparer = FilterMutationPreparer { outcome },
                    publisher = FilterMutationPublisher { _, _ ->
                        commits += 1
                        FilterMutationPublishResult.Committed
                    },
                )
                val emissions = mutableListOf<ConfirmedFilterState>()
                val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    coordinator.confirmedState.collect { emissions += it }
                }

                val result = coordinator.apply(FilterMutationRequest(9, desired))

                assertTrue(result !is FilterMutationResult.Applied)
                assertSame(previous, result.confirmedState)
                assertSame(previous, coordinator.confirmedState.value)
                assertEquals(listOf(previous), emissions)
                assertEquals(0, commits)
                collection.cancel()
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `invalid prepared candidate is cleaned once without durable commit or emission`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val previousFixture = RealFilterArtifactFixture.createForConfiguration(base, 9L)
        val wrongFixture = RealFilterArtifactFixture.createForConfiguration(desired, 11L)
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val wrongCandidate = confirmed(desired, wrongFixture.verifiedArtifact())
            var cleanups = 0
            var commits = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    prepared(wrongCandidate, wrongFixture) { cleanups += 1 }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
            assertEquals(
                FilterMutationFailure.IDENTITY_MISMATCH,
                (result as FilterMutationResult.FailedKeepingPrevious).failure,
            )
            assertSame(previous, result.confirmedState)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(listOf(previous), emissions)
            assertEquals(0, commits)
            assertEquals(1, cleanups)
            collection.cancel()
        } finally {
            wrongFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `prepared artifact missing desired source coverage is rejected before durable commit`() = runTest {
        val previousFixture = seededFixture()
        val desired = seededConfiguration().withActiveProfile("filter-essential")
        val candidateFixture = RealFilterArtifactFixture.create(mutationIdentity(desired, 10))
        try {
            val previous = confirmed(seededConfiguration(), previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            var commits = 0
            var cleanups = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    prepared(candidate, candidateFixture) { cleanups += 1 }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
            assertEquals(
                FilterMutationFailure.IDENTITY_MISMATCH,
                (result as FilterMutationResult.FailedKeepingPrevious).failure,
            )
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(0, commits)
            assertEquals(1, cleanups)
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `prepared artifact compiled without desired manual rules is rejected before durable commit`() = runTest {
        val previousFixture = seededFixture()
        val base = seededConfiguration()
        val active = requireNotNull(base.profile(base.activeProfileId))
        val desired = base.withProfile(
            active.withManualRules(
                ManualDomainRules(blockedDomains = setOf("manual-only.example")),
            ),
        )
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            configuration = desired,
            identity = mutationIdentity(desired, 10),
            compiledManualRules = ManualDomainRules.EMPTY,
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(
                configuration = desired,
                verifiedArtifact = candidateFixture.verifiedArtifact(),
                credentialUnlockRequired = true,
            )
            var commits = 0
            var cleanups = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    prepared(candidate, candidateFixture) { cleanups += 1 }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
            assertEquals(
                FilterMutationFailure.IDENTITY_MISMATCH,
                (result as FilterMutationResult.FailedKeepingPrevious).failure,
            )
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(0, commits)
            assertEquals(1, cleanups)
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `coordinator authorized plan compiles and publishes exact manual rule candidate`() = runTest {
        val previousFixture = seededFixture()
        val base = seededConfiguration()
        val active = requireNotNull(base.profile(base.activeProfileId))
        val desired = base.withProfile(
            active.withManualRules(
                ManualDomainRules(blockedDomains = setOf("manual-only.example")),
            ),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            var commits = 0
            var cleanups = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer { plan ->
                    val fixture = RealFilterArtifactFixture.create(plan)
                    val candidate = confirmed(
                        configuration = plan.mutationRequest.desired,
                        verifiedArtifact = fixture.verifiedArtifact(),
                        credentialUnlockRequired = true,
                    )
                    prepared(candidate, fixture) {
                        cleanups += 1
                        fixture.close()
                    }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.Applied)
            assertEquals(desired, result.confirmedState.configuration)
            assertEquals(1, commits)
            assertEquals(1, cleanups)
        } finally {
            previousFixture.close()
        }
    }

    @Test
    fun `same source IDs cannot substitute digest or format`() = runTest {
        val previousFixture = seededFixture()
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val variants: List<(FilterCompileRequest) -> FilterCompileRequest> = listOf(
            { request ->
                request.copy(
                    selectedSources = request.selectedSources.map { source ->
                        sourcePayload(
                            id = source.id,
                            format = source.format,
                            content = sourceContent(source.format, "wrong-${source.id}.example"),
                        )
                    },
                )
            },
            { request ->
                request.copy(
                    selectedSources = request.selectedSources.map { source ->
                        source.copy(format = BlocklistFormat.AUTO)
                    },
                )
            },
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            for (variant in variants) {
                var commits = 0
                var cleanups = 0
                val coordinator = coordinator(
                    initialState = previous,
                    preparer = FilterMutationPreparer { plan ->
                        val fixture = RealFilterArtifactFixture.create(variant(plan.compileRequest))
                        val candidate = confirmed(desired, fixture.verifiedArtifact())
                        prepared(candidate, fixture) {
                            cleanups += 1
                            fixture.close()
                        }
                    },
                    publisher = FilterMutationPublisher { _, _ ->
                        commits += 1
                        FilterMutationPublishResult.Committed
                    },
                )

                val result = coordinator.apply(FilterMutationRequest(9, desired))

                assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
                assertEquals(
                    FilterMutationFailure.IDENTITY_MISMATCH,
                    (result as FilterMutationResult.FailedKeepingPrevious).failure,
                )
                assertSame(previous, coordinator.confirmedState.value)
                assertEquals(0, commits)
                assertEquals(1, cleanups)
            }
        } finally {
            previousFixture.close()
        }
    }

    @Test
    fun `confirmed state rejects catalog authority not used by its fingerprint`() = runTest {
        val desired = seededConfiguration().withActiveProfile("filter-essential")
        val expected = mutationIdentity(desired, 10L)
        val baseRequest = FilterCompileRequest(
            configurationIdentity = ArtifactConfigurationIdentity(
                expected.generation,
                expected.fingerprint,
            ),
            selectedSources = emptyList(),
            catalogRevision = "contract-catalog-revision",
            catalogSha256 = "b".repeat(64),
            maxRules = 0,
            intentionalEmpty = true,
        )
        val forgedRequests = listOf(
            baseRequest.copy(catalogRevision = "untrusted-catalog-revision"),
            baseRequest.copy(catalogSha256 = "c".repeat(64)),
        )

        for (request in forgedRequests) {
            val fixture = RealFilterArtifactFixture.create(request)
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    confirmed(desired, fixture.verifiedArtifact())
                }
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `preparer cannot raise the catalog authorized rule cap`() = runTest {
        val previousFixture = seededFixture()
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val trusted = RealFilterArtifactFixture.authoritiesFor(base)
        val zeroRuleCatalog = FilterCatalogCompileAuthority(
            revision = trusted.catalog.revision,
            sha256 = trusted.catalog.sha256,
            sources = trusted.catalog.sources,
            guardrailRules = trusted.catalog.guardrailRules,
            maxRules = 0,
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            var commits = 0
            var cleanups = 0
            val coordinator = TransactionalFilterMutationCoordinator(
                initialState = previous,
                catalogCompileAuthority = zeroRuleCatalog,
                acceptedSourceAuthority = trusted.acceptedSources,
                preparer = FilterMutationPreparer { plan ->
                    val fixture = RealFilterArtifactFixture.create(
                        plan.compileRequest.copy(maxRules = 100),
                    )
                    prepared(
                        confirmed(desired, fixture.verifiedArtifact()),
                        fixture,
                    ) {
                        cleanups += 1
                        fixture.close()
                    }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
            assertEquals(
                FilterMutationFailure.IDENTITY_MISMATCH,
                (result as FilterMutationResult.FailedKeepingPrevious).failure,
            )
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(0, commits)
            assertEquals(1, cleanups)
        } finally {
            previousFixture.close()
        }
    }

    @Test
    fun `artifact commitment cannot be relabeled with manual rules it did not compile`() = runTest {
        val previousFixture = seededFixture()
        val base = seededConfiguration()
        val active = requireNotNull(base.profile(base.activeProfileId))
        val desired = base.withProfile(
            active.withManualRules(
                ManualDomainRules(blockedDomains = setOf("manual-only.example")),
            ),
        )
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            configuration = desired,
            identity = mutationIdentity(desired, 10),
            compiledManualRules = ManualDomainRules.EMPTY,
        )
        val claimedFixture = RealFilterArtifactFixture.createForConfiguration(
            configuration = desired,
            identity = mutationIdentity(desired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val expectedCommitment =
                claimedFixture.reference.artifactIdentity.compileInputsSha256
            val candidate = confirmed(
                configuration = desired,
                verifiedArtifact = candidateFixture.verifiedArtifactWithRelabeledCompileInputs(
                    expectedCommitment,
                ),
                credentialUnlockRequired = true,
            )
            assertNotEquals(
                candidateFixture.reference.artifactIdentity.compileInputsSha256,
                candidate.verifiedArtifact.artifactIdentity.compileInputsSha256,
            )
            assertEquals(
                expectedCommitment,
                candidate.verifiedArtifact.artifactIdentity.compileInputsSha256,
            )
            var commits = 0
            var cleanups = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    FilterMutationPreparationResult.Prepared(
                        PreparedFilterMutation(
                            candidate,
                            candidateFixture.compilationReceipt,
                        ) { cleanups += 1 },
                    )
                },
                publisher = FilterMutationPublisher { _, _ ->
                    commits += 1
                    FilterMutationPublishResult.Committed
                },
            )

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
            assertEquals(
                FilterMutationFailure.IDENTITY_MISMATCH,
                (result as FilterMutationResult.FailedKeepingPrevious).failure,
            )
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(0, commits)
            assertEquals(1, cleanups)
        } finally {
            claimedFixture.close()
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `caller invented configuration fingerprint cannot become confirmed`() = runTest {
        val desired = seededConfiguration().withActiveProfile("filter-essential")
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            ConfigurationIdentity(10, "caller-controlled-fingerprint"),
        )
        try {
            assertThrows(IllegalArgumentException::class.java) {
                confirmed(desired, candidateFixture.verifiedArtifact())
            }
        } finally {
            candidateFixture.close()
        }
    }

    @Test
    fun `configuration fingerprint canonicalizes catalog digest casing`() {
        val configuration = seededConfiguration()
        val lowercaseDigest = "abcdef0123456789".repeat(4)

        assertEquals(
            canonicalFilterConfigurationFingerprint(
                configuration = configuration,
                catalogRevision = "contract-catalog-revision",
                catalogSha256 = lowercaseDigest,
            ),
            canonicalFilterConfigurationFingerprint(
                configuration = configuration,
                catalogRevision = "contract-catalog-revision",
                catalogSha256 = lowercaseDigest.uppercase(),
            ),
        )
    }

    @Test
    fun `ordered encrypted fingerprints bind fallback endpoint row order enablement and retained names`() {
        val first = com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
            com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
            com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOQ,
            customDoqEndpoint = "doq://first.example:8853")
        val second = com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
            com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
            com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOH,
            customDohUrl = "https://second.example/dns-query")
        val rows = listOf(com.lavasec.app.foundation.resolver.ResolverTier(first),
            com.lavasec.app.foundation.resolver.ResolverTier(second))
        fun fingerprint(tiers: List<com.lavasec.app.foundation.resolver.ResolverTier>): String {
            val plan = checkNotNull(com.lavasec.app.foundation.resolver.ResolverSelection(tiers).toFilterResolverPlan())
            return canonicalFilterConfigurationFingerprint(seededConfiguration().withResolverPlan(plan),
                "catalog-v1", "a".repeat(64))
        }
        val original = fingerprint(rows)
        assertNotEquals(original, fingerprint(rows.reversed()))
        assertNotEquals(original, fingerprint(listOf(rows.first(), rows.last().copy(isEnabled = false))))
        assertNotEquals(original, fingerprint(listOf(rows.first(), rows.last().copy(retainedName = "Fallback"))))
        assertNotEquals(original, fingerprint(listOf(rows.first(), rows.last().copy(entry = second.copy(
            customDohUrl = "https://other.example/dns-query")))))
    }

    @Test
    fun `configuration fingerprint supports catalog revisions above modified UTF limit`() {
        val configuration = seededConfiguration()
        val longRevision = "r".repeat(70_000)
        val first = canonicalFilterConfigurationFingerprint(
            configuration = configuration,
            catalogRevision = longRevision,
            catalogSha256 = "a".repeat(64),
        )
        val second = canonicalFilterConfigurationFingerprint(
            configuration = configuration,
            catalogRevision = longRevision,
            catalogSha256 = "a".repeat(64),
        )
        val changed = canonicalFilterConfigurationFingerprint(
            configuration = configuration,
            catalogRevision = "${longRevision.dropLast(1)}s",
            catalogSha256 = "a".repeat(64),
        )

        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `durable commit completes before flow publish and late generation loser is discarded`() = runTest {
        val base = seededConfiguration()
        val olderDesired = base.withActiveProfile("filter-essential")
        val newerDesired = base.withActiveProfile("filter-comprehensive")
        val previousFixture = seededFixture()
        val olderFixture = RealFilterArtifactFixture.createForConfiguration(
            olderDesired,
            mutationIdentity(olderDesired, 10),
        )
        val newerFixture = RealFilterArtifactFixture.createForConfiguration(
            newerDesired,
            mutationIdentity(newerDesired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val olderCandidate = confirmed(olderDesired, olderFixture.verifiedArtifact())
            val newerCandidate = confirmed(newerDesired, newerFixture.verifiedArtifact())
            var olderCleanups = 0
            var newerCleanups = 0
            val olderStarted = CompletableDeferred<Unit>()
            val releaseOlder = CompletableDeferred<Unit>()
            val durableCommitStarted = CompletableDeferred<Unit>()
            val releaseDurableCommit = CompletableDeferred<Unit>()
            val durableCommits = mutableListOf<Pair<Long, PreparedFilterMutation>>()
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer { plan ->
                    when (plan.mutationRequest.desired.activeProfileId) {
                        "filter-essential" -> {
                            olderStarted.complete(Unit)
                            releaseOlder.await()
                            prepared(olderCandidate, olderFixture) { olderCleanups += 1 }
                        }
                        "filter-comprehensive" ->
                            prepared(newerCandidate, newerFixture) { newerCleanups += 1 }
                        else -> error("unexpected request")
                    }
                },
                publisher = FilterMutationPublisher { expectedGeneration, mutation ->
                    durableCommits += expectedGeneration to mutation
                    durableCommitStarted.complete(Unit)
                    releaseDurableCommit.await()
                    FilterMutationPublishResult.Committed
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }

            val older = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.apply(FilterMutationRequest(9, olderDesired))
            }
            olderStarted.await()
            val newer = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.apply(FilterMutationRequest(9, newerDesired))
            }
            durableCommitStarted.await()

            assertEquals(1, durableCommits.size)
            assertEquals(9L, durableCommits.single().first)
            assertSame(newerCandidate, durableCommits.single().second.candidate)
            assertEquals(listOf(previous), emissions)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(0, newerCleanups)
            assertFalse(newer.isCompleted)

            releaseOlder.complete(Unit)
            runCurrent()
            assertEquals(1, durableCommits.size)
            assertEquals(listOf(previous), emissions)
            assertEquals(0, olderCleanups)
            assertFalse(older.isCompleted)

            releaseDurableCommit.complete(Unit)
            val newerResult = newer.await()
            val olderResult = older.await()

            assertTrue(newerResult is FilterMutationResult.Applied)
            assertSame(newerCandidate, newerResult.confirmedState)
            assertTrue(olderResult is FilterMutationResult.Superseded)
            assertSame(newerCandidate, olderResult.confirmedState)
            assertSame(newerCandidate, coordinator.confirmedState.value)
            assertEquals(listOf(previous, newerCandidate), emissions)
            assertEquals(1, durableCommits.size)
            assertEquals(1, newerCleanups)
            assertEquals(1, olderCleanups)
            collection.cancel()
        } finally {
            newerFixture.close()
            olderFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `durable commit failure retains state without emission and cleans staging once`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val previousFixture = seededFixture()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            mutationIdentity(desired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            var cleanups = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    prepared(candidate, candidateFixture) { cleanups += 1 }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
            assertEquals(
                FilterMutationFailure.COMMIT_FAILED,
                (result as FilterMutationResult.FailedKeepingPrevious).failure,
            )
            assertSame(previous, result.confirmedState)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(listOf(previous), emissions)
            assertEquals(1, cleanups)
            collection.cancel()
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `durable CAS loss adopts authoritative winner and permits its next generation`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val winnerConfiguration = base.withActiveProfile("filter-comprehensive")
        val nextDesired = winnerConfiguration.withActiveProfile("filter-essential")
        val previousFixture = seededFixture()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            mutationIdentity(desired, 10),
        )
        val winnerFixture = RealFilterArtifactFixture.createForConfiguration(
            winnerConfiguration,
            mutationIdentity(winnerConfiguration, 10),
        )
        val nextFixture = RealFilterArtifactFixture.createForConfiguration(
            nextDesired,
            mutationIdentity(nextDesired, 11),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            val winner = confirmed(winnerConfiguration, winnerFixture.verifiedArtifact())
            val nextCandidate = confirmed(nextDesired, nextFixture.verifiedArtifact())
            var preparations = 0
            var cleanups = 0
            var commits = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer { plan ->
                    preparations += 1
                    val expectedGeneration = plan.mutationRequest.expectedGeneration
                    prepared(
                        if (expectedGeneration == 9L) candidate else nextCandidate,
                        if (expectedGeneration == 9L) candidateFixture else nextFixture,
                    ) { cleanups += 1 }
                },
                publisher = FilterMutationPublisher { expectedGeneration, _ ->
                    commits += 1
                    if (expectedGeneration == 9L) {
                        FilterMutationPublishResult.Superseded(winner)
                    } else {
                        FilterMutationPublishResult.Committed
                    }
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }

            val superseded = coordinator.apply(FilterMutationRequest(9, desired))
            val applied = coordinator.apply(FilterMutationRequest(10, nextDesired))

            assertTrue(superseded is FilterMutationResult.Superseded)
            assertSame(winner, superseded.confirmedState)
            assertTrue(applied is FilterMutationResult.Applied)
            assertSame(nextCandidate, applied.confirmedState)
            assertSame(nextCandidate, coordinator.confirmedState.value)
            assertEquals(listOf(previous, winner, nextCandidate), emissions)
            assertEquals(2, preparations)
            assertEquals(2, commits)
            assertEquals(2, cleanups)
            collection.cancel()
        } finally {
            nextFixture.close()
            winnerFixture.close()
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `durable unavailability and exception never emit and always clean staging`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val previousFixture = seededFixture()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            mutationIdentity(desired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            val behaviors = listOf<suspend () -> FilterMutationPublishResult>(
                { FilterMutationPublishResult.ProtectionUnavailable },
                { throw IllegalStateException("commit failed") },
            )

            for ((index, behavior) in behaviors.withIndex()) {
                var cleanups = 0
                val coordinator = coordinator(
                    initialState = previous,
                    preparer = FilterMutationPreparer {
                        prepared(candidate, candidateFixture) { cleanups += 1 }
                    },
                    publisher = FilterMutationPublisher { _, _ -> behavior() },
                )
                val emissions = mutableListOf<ConfirmedFilterState>()
                val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    coordinator.confirmedState.collect { emissions += it }
                }

                val result = coordinator.apply(FilterMutationRequest(9, desired))

                when (index) {
                    0 -> assertTrue(result is FilterMutationResult.ProtectionUnavailable)
                    1 -> {
                        assertTrue(result is FilterMutationResult.FailedKeepingPrevious)
                        assertEquals(
                            FilterMutationFailure.COMMIT_FAILED,
                            (result as FilterMutationResult.FailedKeepingPrevious).failure,
                        )
                    }
                }
                assertSame(previous, result.confirmedState)
                assertSame(previous, coordinator.confirmedState.value)
                assertEquals(listOf(previous), emissions)
                assertEquals(1, cleanups)
                collection.cancel()
            }
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `cancellation after ambiguous admission leaves authority reload required`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val previousFixture = seededFixture()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            mutationIdentity(desired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            var preparations = 0
            var commits = 0
            var cleanups = 0
            val admitted = CompletableDeferred<Unit>()
            val releaseCommit = CompletableDeferred<Unit>()
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    preparations += 1
                    prepared(candidate, candidateFixture) { cleanups += 1 }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    withContext(NonCancellable) {
                        commits += 1
                        admitted.complete(Unit)
                        releaseCommit.await()
                        FilterMutationPublishResult.ProtectionUnavailable
                    }
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }
            val apply = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.apply(FilterMutationRequest(9, desired))
            }

            admitted.await()
            apply.cancel(CancellationException("cancel after ambiguous admission"))
            releaseCommit.complete(Unit)
            val cancellation = try {
                apply.await()
                null
            } catch (error: CancellationException) {
                error
            }

            assertEquals("cancel after ambiguous admission", cancellation?.message)
            val authority = coordinator.authorityState.value
            assertTrue(authority is FilterMutationAuthorityState.ReloadRequired)
            assertSame(previous, authority.lastVerifiedState)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(listOf(previous), emissions)
            assertEquals(1, preparations)
            assertEquals(1, commits)
            assertEquals(1, cleanups)

            val retry = coordinator.apply(FilterMutationRequest(9, desired))
            assertTrue(retry is FilterMutationResult.ProtectionUnavailable)
            assertEquals(1, preparations)
            assertEquals(1, commits)
            collection.cancel()
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `caller cancellation after durable commit still publishes exact candidate`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val previousFixture = seededFixture()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            mutationIdentity(desired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            var durable = previous
            var cleanups = 0
            val written = CompletableDeferred<Unit>()
            val releaseCommit = CompletableDeferred<Unit>()
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    prepared(candidate, candidateFixture) { cleanups += 1 }
                },
                publisher = FilterMutationPublisher { _, mutation ->
                    // Model a publisher that already crossed its durable-authority admission
                    // point: cancellation may be observed by the coordinator only after the
                    // publisher returns the exact committed outcome.
                    withContext(NonCancellable) {
                        durable = mutation.candidate
                        written.complete(Unit)
                        releaseCommit.await()
                        FilterMutationPublishResult.Committed
                    }
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }
            val apply = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.apply(FilterMutationRequest(9, desired))
            }

            written.await()
            apply.cancel(CancellationException("cancel after durable write"))
            releaseCommit.complete(Unit)
            val cancellation = try {
                apply.await()
                null
            } catch (error: CancellationException) {
                error
            }

            assertEquals("cancel after durable write", cancellation?.message)
            assertSame(candidate, durable)
            assertSame(candidate, coordinator.confirmedState.value)
            assertEquals(
                FilterMutationAuthorityState.Ready(candidate),
                coordinator.authorityState.value,
            )
            assertEquals(listOf(previous, candidate), emissions)
            assertEquals(1, cleanups)
            collection.cancel()
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `cleanup failure is reported without masking committed authority`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val previousFixture = seededFixture()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            mutationIdentity(desired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            val cleanupFailure = IllegalStateException("staging cleanup failed")
            val reported = mutableListOf<Exception>()
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    prepared(candidate, candidateFixture) { throw cleanupFailure }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    FilterMutationPublishResult.Committed
                },
                reportCleanupFailure = { reported += it },
            )

            val result = coordinator.apply(FilterMutationRequest(9, desired))

            assertTrue(result is FilterMutationResult.Applied)
            assertSame(candidate, result.confirmedState)
            assertSame(candidate, coordinator.confirmedState.value)
            assertEquals(listOf(cleanupFailure), reported)
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `cleanup failure never replaces durable commit cancellation`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val previousFixture = seededFixture()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            mutationIdentity(desired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            val cleanupFailure = IllegalStateException("staging cleanup failed")
            val reported = mutableListOf<Exception>()
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    prepared(candidate, candidateFixture) { throw cleanupFailure }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    throw CancellationException("cancel commit")
                },
                reportCleanupFailure = { reported += it },
            )

            val cancellation = try {
                coordinator.apply(FilterMutationRequest(9, desired))
                null
            } catch (error: CancellationException) {
                error
            }

            assertEquals("cancel commit", cancellation?.message)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(listOf(cleanupFailure), reported)
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `durable commit cancellation propagates without emission and cleans staging once`() = runTest {
        val base = seededConfiguration()
        val desired = base.withActiveProfile("filter-essential")
        val previousFixture = seededFixture()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            desired,
            mutationIdentity(desired, 10),
        )
        try {
            val previous = confirmed(base, previousFixture.verifiedArtifact())
            val candidate = confirmed(desired, candidateFixture.verifiedArtifact())
            var cleanups = 0
            val coordinator = coordinator(
                initialState = previous,
                preparer = FilterMutationPreparer {
                    prepared(candidate, candidateFixture) { cleanups += 1 }
                },
                publisher = FilterMutationPublisher { _, _ ->
                    throw CancellationException("cancel commit")
                },
            )
            val emissions = mutableListOf<ConfirmedFilterState>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.confirmedState.collect { emissions += it }
            }

            val cancellation = try {
                coordinator.apply(FilterMutationRequest(9, desired))
                null
            } catch (error: CancellationException) {
                error
            }

            assertEquals("cancel commit", cancellation?.message)
            assertSame(previous, coordinator.confirmedState.value)
            assertEquals(listOf(previous), emissions)
            assertEquals(1, cleanups)
            collection.cancel()
        } finally {
            candidateFixture.close()
            previousFixture.close()
        }
    }

    @Test
    fun `direct boot contract carries only identity and credential unlock requirement`() {
        val state = DirectBootAppState.safeDefault().copy(
            activeProfileId = "filter-balanced",
            configurationIdentity = ConfigurationIdentity(4, "fingerprint"),
            credentialUnlockRequired = true,
        )

        assertTrue(state.credentialUnlockRequired)
        val fields = DirectBootAppState::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(fields.none { "manual" in it.lowercase() })
        assertTrue(fields.none { "domain" in it.lowercase() })
    }

    @Test fun `warm plans use only the supplied cache authority and never prepare or publish`() = runTest {
        seededFixture().use { fixture ->
            val base = seededConfiguration()
            val initial = confirmed(base, fixture.verifiedArtifact())
            val authorities = RealFilterArtifactFixture.authoritiesFor(base)
            val owner = TransactionalFilterMutationCoordinator(initial, authorities.catalog,
                AcceptedFilterSourceAuthority { _, _ -> error("warm must never consult network authority") },
                FilterMutationPreparer { error("warm plan must not prepare") },
                FilterMutationPublisher { _, _ -> error("warm plan must not publish") })
            var cacheReads = 0
            val cache = AcceptedFilterSourceAuthority { sources, refresh ->
                cacheReads++; assertFalse(refresh); authorities.acceptedSources.acquire(sources, false)
            }
            val request = FilterMutationRequest(9, base.withActiveProfile("filter-essential"))
            val plan = owner.warmPlan(request, cache)!!
            assertEquals(10L, plan.expectedConfiguration.generation)
            assertEquals("filter-essential", plan.mutationRequest.desired.activeProfileId)
            assertSame(initial, owner.confirmedState.value)
            assertEquals(1, cacheReads)
            assertEquals(null, owner.warmPlan(request.copy(expectedGeneration = 8), cache))
            assertEquals(null, owner.warmPlan(FilterMutationRequest(9, base), cache))
            assertEquals(1, cacheReads)
        }
    }

    @Test fun `failed speculative sources are skipped but cancellation is retained`() = runTest {
        seededFixture().use { fixture ->
            val base = seededConfiguration(); val initial = confirmed(base, fixture.verifiedArtifact())
            val owner = coordinator(initial, FilterMutationPreparer { error("must not prepare") },
                FilterMutationPublisher { _, _ -> error("must not publish") })
            val request = FilterMutationRequest(9, base.withActiveProfile("filter-essential"))
            for (failure in listOf(IllegalArgumentException("missing source"), IllegalStateException("retired cache"))) {
                assertEquals(null, owner.warmPlan(request, AcceptedFilterSourceAuthority { _, _ -> throw failure }))
                assertSame(initial, owner.confirmedState.value)
            }
            val cancellation = CancellationException("retired warm visit")
            val thrown = try {
                owner.warmPlan(request, AcceptedFilterSourceAuthority { _, _ -> throw cancellation }); null
            } catch (caught: CancellationException) { caught }
            assertSame(cancellation, thrown)
            assertSame(initial, owner.confirmedState.value)
        }
    }

    @Test fun `a foreground commit overtakes a suspended warm plan`() = runTest {
        seededFixture().use { fixture ->
            val base = seededConfiguration(); val initial = confirmed(base, fixture.verifiedArtifact())
            val authorities = RealFilterArtifactFixture.authoritiesFor(base)
            val owner = coordinator(initial,
                FilterMutationPreparer { plan ->
                    val artifact = RealFilterArtifactFixture.create(plan)
                    FilterMutationPreparationResult.Prepared(PreparedFilterMutation(
                        confirmed(plan.mutationRequest.desired, artifact.verifiedArtifact()), artifact.compilationReceipt,
                        cleanup = artifact::close))
                }, FilterMutationPublisher { _, _ -> FilterMutationPublishResult.Committed })
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val request = FilterMutationRequest(9, base.withActiveProfile("filter-essential"))
            val warm = async { owner.warmPlan(request, AcceptedFilterSourceAuthority { sources, _ ->
                started.complete(Unit); release.await(); authorities.acceptedSources.acquire(sources, false)
            }) }
            started.await(); assertTrue(owner.apply(request) is FilterMutationResult.Applied)
            release.complete(Unit); assertEquals(null, warm.await())
            assertEquals(10L, owner.confirmedState.value.configurationIdentity.generation)
        }
    }

    private fun coordinator(
        initialState: ConfirmedFilterState,
        preparer: FilterMutationPreparer,
        publisher: FilterMutationPublisher,
        reportCleanupFailure: (Exception) -> Unit = {},
    ): TransactionalFilterMutationCoordinator {
        val authorities = RealFilterArtifactFixture.authoritiesFor(initialState.configuration)
        return TransactionalFilterMutationCoordinator(
            initialState = initialState,
            catalogCompileAuthority = authorities.catalog,
            acceptedSourceAuthority = authorities.acceptedSources,
            preparer = preparer,
            publisher = publisher,
            reportCleanupFailure = reportCleanupFailure,
        )
    }

    @Test
    fun `prepared mutation publication input must match its compiler receipt`() = runTest {
        val configuration = seededConfiguration()
        val candidateFixture = RealFilterArtifactFixture.createForConfiguration(
            configuration = configuration,
            generation = 10L,
        )
        val substitutedFixture = RealFilterArtifactFixture.createForConfiguration(
            configuration = configuration,
            generation = 11L,
        )
        try {
            val candidate = confirmed(
                configuration = configuration,
                verifiedArtifact = candidateFixture.verifiedArtifact(),
            )

            assertThrows(IllegalArgumentException::class.java) {
                PreparedFilterMutation(
                    candidate = candidate,
                    compilationReceipt = candidateFixture.compilationReceipt,
                    publicationArtifact = substitutedFixture.publicationArtifact,
                    cleanup = {},
                )
            }

            val owned = PreparedFilterMutation(
                candidate = candidate,
                compilationReceipt = candidateFixture.compilationReceipt,
                publicationArtifact = candidateFixture.publicationArtifact,
                cleanup = {},
            )
            assertSame(candidateFixture.publicationArtifact, owned.publicationArtifact)
        } finally {
            substitutedFixture.close()
            candidateFixture.close()
        }
    }

    private fun prepared(
        candidate: ConfirmedFilterState,
        fixture: RealFilterArtifactFixture,
        cleanup: () -> Unit,
    ): FilterMutationPreparationResult.Prepared {
        check(candidate.verifiedArtifact.artifactIdentity == fixture.reference.artifactIdentity)
        return FilterMutationPreparationResult.Prepared(
            PreparedFilterMutation(candidate, fixture.compilationReceipt, cleanup),
        )
    }

    private fun confirmed(
        configuration: FilterLibraryDraft,
        verifiedArtifact: VerifiedFilterArtifact,
        credentialUnlockRequired: Boolean = false,
    ): ConfirmedFilterState {
        val artifactConfiguration = verifiedArtifact.artifactIdentity.configuration
        return ConfirmedFilterState(
            configuration = configuration,
            configurationIdentity = ConfigurationIdentity(
                artifactConfiguration.generation,
                artifactConfiguration.fingerprint,
            ),
            credentialUnlockRequired = credentialUnlockRequired,
            verifiedArtifact = verifiedArtifact,
        )
    }

    private fun seededConfiguration(): FilterLibraryDraft {
        val catalog = FilterCatalogSnapshot(
            listOf(
                CatalogFilterSource("security", FilterSourceCategory.SECURITY, true),
                CatalogFilterSource("multi", FilterSourceCategory.MULTI_PURPOSE, true),
                CatalogFilterSource("ads", FilterSourceCategory.ADS_TRACKING, false),
            ),
        )
        return FilterLibraryDraft.seededDefaults(catalog)
    }

    private suspend fun seededFixture(
        generation: Long = 9L,
    ): RealFilterArtifactFixture =
        RealFilterArtifactFixture.createForConfiguration(
            configuration = seededConfiguration(),
            generation = generation,
        )

    private fun mutationIdentity(
        configuration: FilterLibraryDraft,
        generation: Long,
    ): ConfigurationIdentity = ConfigurationIdentity(
        generation = generation,
        fingerprint = canonicalFilterConfigurationFingerprint(
            configuration = configuration,
            catalogRevision = "contract-catalog-revision",
            catalogSha256 = "b".repeat(64),
        ),
    )

    private fun sourcePayload(
        id: String,
        format: BlocklistFormat,
        content: String,
    ): FilterSourcePayload {
        val bytes = content.encodeToByteArray()
        return FilterSourcePayload(
            id = id,
            format = format,
            acceptedSha256 = sha256(bytes),
            declaredByteSize = bytes.size.toLong(),
            openStream = { bytes.inputStream() },
        )
    }

    private fun sourceContent(format: BlocklistFormat, domain: String): String = when (format) {
        BlocklistFormat.AUTO,
        BlocklistFormat.PLAIN_DOMAINS,
        -> "$domain\n"
        BlocklistFormat.HOSTS -> "0.0.0.0 $domain\n"
        BlocklistFormat.ADBLOCK -> "||$domain^\n"
        BlocklistFormat.DNSMASQ -> "address=/$domain/\n"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
