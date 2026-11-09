package com.lavasec.app.platform

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.CatalogFilterSource
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationRepositoryContractTest {
    @Test
    fun `repository contract keeps every privacy setting independent`() = runTest {
        val repository = FakeConfigurationRepository()

        repository.setLogging(
            keepCounts = false,
            keepHistory = false,
            keepNetworkActivity = true,
            keepLavaGuardProgress = false,
        )

        assertEquals(
            LoggingSettings(false, false, true, false),
            repository.current().logging,
        )
    }

    @Test
    fun `four argument logging bridge delegates only when Lava Guard progress is unchanged`() = runTest {
        val repository = ThreeArgumentLoggingRepository(
            PersistedAppState.initial().copy(
                logging = LoggingSettings(keepLavaGuardProgress = false),
            ),
        )

        repository.setLogging(
            keepCounts = false,
            keepHistory = true,
            keepNetworkActivity = false,
            keepLavaGuardProgress = false,
        )

        assertEquals(1, repository.loggingUpdates)
        assertEquals(
            LoggingSettings(
                keepCounts = false,
                keepHistory = true,
                keepNetworkActivity = false,
                keepLavaGuardProgress = false,
            ),
            repository.current().logging,
        )

        val failure = try {
            repository.setLogging(
                keepCounts = true,
                keepHistory = true,
                keepNetworkActivity = true,
                keepLavaGuardProgress = true,
            )
            null
        } catch (error: Throwable) {
            error
        }
        assertTrue(failure is IllegalStateException)
        assertEquals(1, repository.loggingUpdates)
    }

    @Test
    fun `filter and original logging mutations have no interface defaults`() {
        assertFalse(hasInterfaceDefault("setFilter", interfaceParameterCount = 2))
        assertFalse(hasInterfaceDefault("setLogging", interfaceParameterCount = 4))
        assertTrue(hasInterfaceDefault("setLogging", interfaceParameterCount = 5))
    }

    @Test
    fun `initial contract state is incomplete off and has no configuration identity`() {
        val state = PersistedAppState.initial()

        assertFalse(state.onboardingCompleted)
        assertFalse(state.protectionIntent)
        assertNull(state.configuration)
        assertNull(state.configurationIdentity)
        assertEquals(LoggingSettings(), state.logging)
        assertEquals(0L, state.protectionSessionId)
        assertNull(state.pauseDeadlineEpochMillis)
        assertNull(state.pauseSessionId)
        assertNull(state.pause)
    }

    @Test
    fun `persisted pause requires an atomic pair for the current positive on session`() {
        val initial = PersistedAppState.initial()

        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(
                protectionIntent = true,
                protectionSessionId = 4L,
                pauseDeadlineEpochMillis = 5_000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(
                protectionIntent = true,
                protectionSessionId = 4L,
                pauseSessionId = 4L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(
                protectionIntent = false,
                protectionSessionId = 4L,
                pauseDeadlineEpochMillis = 5_000L,
                pauseSessionId = 4L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(
                protectionIntent = true,
                protectionSessionId = 4L,
                pauseDeadlineEpochMillis = 5_000L,
                pauseSessionId = 3L,
            )
        }

        val valid = initial.copy(
            protectionIntent = true,
            protectionSessionId = 4L,
            pauseDeadlineEpochMillis = 5_000L,
            pauseSessionId = 4L,
        )

        assertEquals(PauseState(5_000L, 4L), valid.pause)
    }

    @Test
    fun `production intent transition advances only off to on and stop clears pause atomically`() {
        val initial = PersistedAppState.initial()

        val firstOn = initial.transitionProtectionIntent(true)
        assertEquals(1L, firstOn.protectionSessionId)
        assertSame(firstOn, firstOn.transitionProtectionIntent(true))

        val paused = firstOn.transitionPause(
            PauseState(deadlineMillis = 10_000L, protectionSessionId = firstOn.protectionSessionId),
        )
        assertSame(paused, paused.transitionProtectionIntent(true))

        val stopped = paused.transitionProtectionIntent(false)
        assertEquals(firstOn.protectionSessionId, stopped.protectionSessionId)
        assertNull(stopped.pauseDeadlineEpochMillis)
        assertNull(stopped.pauseSessionId)
        assertSame(stopped, stopped.transitionProtectionIntent(false))

        val secondOn = stopped.transitionProtectionIntent(true)
        assertEquals(firstOn.protectionSessionId + 1L, secondOn.protectionSessionId)
        assertNull(secondOn.pause)
    }

    @Test
    fun `production pause transition accepts current session clear and rejects stale or overflow`() {
        val firstOn = PersistedAppState.initial().transitionProtectionIntent(true)
        val pause = PauseState(10_000L, firstOn.protectionSessionId)

        val paused = firstOn.transitionPause(pause)
        assertEquals(pause, paused.pause)
        val resumed = paused.transitionPause(null)
        assertNull(resumed.pauseDeadlineEpochMillis)
        assertNull(resumed.pauseSessionId)

        val secondOn = paused.transitionProtectionIntent(false).transitionProtectionIntent(true)
        assertThrows(IllegalArgumentException::class.java) {
            secondOn.transitionPause(pause)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersistedAppState.initial().transitionPause(pause)
        }

        val exhausted = PersistedAppState.initial().copy(protectionSessionId = Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) {
            exhausted.transitionProtectionIntent(true)
        }
    }

    @Test
    fun `pause compare and set is exact for session and observed pause`() = runTest {
        val repository = FakeConfigurationRepository()
        repository.setProtectionIntent(true)
        val sessionId = repository.current().protectionSessionId
        val first = PauseState(10_000L, sessionId)
        val replacement = PauseState(20_000L, sessionId)

        assertTrue(
            repository.compareAndSetPause(
                expectedProtectionSessionId = sessionId,
                expectedPause = null,
                replacement = first,
            ) is PauseMutationResult.Applied,
        )
        assertEquals(first, repository.current().pause)

        val staleStart = repository.compareAndSetPause(sessionId, null, replacement)
        assertEquals(PauseMutationRejection.EXPECTED_PAUSE_MISMATCH, (staleStart as PauseMutationResult.Rejected).reason)
        assertEquals(first, staleStart.authoritative.pause)

        assertTrue(repository.compareAndSetPause(sessionId, first, null) is PauseMutationResult.Applied)
        assertNull(repository.current().pause)

        repository.setProtectionIntent(false)
        repository.setProtectionIntent(true)
        val staleSession = repository.compareAndSetPause(sessionId, null, first)
        assertEquals(PauseMutationRejection.SESSION_MISMATCH, (staleSession as PauseMutationResult.Rejected).reason)
        assertNull(repository.current().pause)
    }

    @Test
    fun `filter metadata snapshots source membership and cannot be cast mutated`() {
        val sourceIds = linkedSetOf("source-a")
        val metadata = FilterMetadata(
            id = "filter",
            name = "Filter",
            protectionLevel = com.lavasec.app.foundation.config.ProtectionLevel.CORE,
            enabledSourceIds = sourceIds,
        )

        sourceIds += "source-b"

        assertEquals(setOf("source-a"), metadata.enabledSourceIds)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (metadata.enabledSourceIds as MutableSet<String>) += "source-c"
        }
        assertEquals(setOf("source-a"), metadata.enabledSourceIds)
    }

    @Test
    fun `confirmed filter state rejects configuration not bound to artifact fingerprint`() = runTest {
        val configuration = seededConfiguration()
        val identity = ConfigurationIdentity(
            generation = 7L,
            fingerprint = canonicalFilterConfigurationFingerprint(
                configuration = configuration,
                catalogRevision = "contract-catalog-revision",
                catalogSha256 = "b".repeat(64),
            ),
        )
        val fixture = RealFilterArtifactFixture.createForConfiguration(configuration, identity)
        try {
            val verifiedArtifact = fixture.verifiedArtifact()
            val exact = ConfirmedFilterState(
                configuration = configuration,
                configurationIdentity = identity,
                credentialUnlockRequired = false,
                verifiedArtifact = verifiedArtifact,
            )

            assertSame(configuration, exact.configuration)
            val active = requireNotNull(configuration.profile(configuration.activeProfileId))
            val inactive = configuration.profiles.first { it.id != active.id }
            val substitutedConfigurations = listOf(
                configuration.withResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY),
                configuration.withActiveProfile(inactive.id),
                configuration.withProfile(active.withEnabledSourceIds(emptySet())),
                configuration.withProfile(
                    active.withManualRules(
                        ManualDomainRules(blockedDomains = setOf("active-private.example")),
                    ),
                ),
                configuration.withProfile(
                    inactive.withManualRules(
                        ManualDomainRules(allowedDomains = setOf("inactive-private.example")),
                    ),
                ),
            )
            for (substituted in substitutedConfigurations) {
                val requiresCredential = substituted.profiles.any {
                    it.manualRules.allowedDomains.isNotEmpty() ||
                        it.manualRules.blockedDomains.isNotEmpty()
                }
                assertThrows(IllegalArgumentException::class.java) {
                    ConfirmedFilterState(
                        configuration = substituted,
                        configurationIdentity = identity,
                        credentialUnlockRequired = requiresCredential,
                        verifiedArtifact = verifiedArtifact,
                    )
                }
            }
        } finally {
            fixture.close()
        }
    }

    private fun seededConfiguration(): FilterLibraryDraft = FilterLibraryDraft.seededDefaults(
        FilterCatalogSnapshot(
            listOf(
                CatalogFilterSource("security", FilterSourceCategory.SECURITY, true),
                CatalogFilterSource("multi", FilterSourceCategory.MULTI_PURPOSE, true),
                CatalogFilterSource("ads", FilterSourceCategory.ADS_TRACKING, false),
            ),
        ),
    )

    private class FakeConfigurationRepository(
        initial: PersistedAppState = PersistedAppState.initial(),
    ) : ConfigurationRepository {
        private val mutableState = MutableStateFlow(initial)
        private var mirrorKeyGeneration = 0L
        private var mirrorAuthenticator: DirectBootSnapshotAuthenticator? = null
        private val freshnessAuthority = object : DirectBootSnapshotFreshnessAuthority() {
            override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult {
                mirrorAuthenticator = null
                mirrorKeyGeneration += 1
                val generation = mirrorKeyGeneration
                val authenticator = DirectBootSnapshotAuthenticator {
                    generation.toString(16).padStart(64, '0')
                }
                mirrorAuthenticator = authenticator
                return rotated(authenticator)
            }

            override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator? =
                mirrorAuthenticator
        }
        private val protectionIntentCoordinator = ProtectionIntentWriteCoordinator(
            freshnessAuthority = freshnessAuthority,
            authoritativeCommit = ProtectionIntentAuthoritativeCommit { enabled ->
                mutableState.value = mutableState.value.transitionProtectionIntent(enabled)
                DirectBootAppState.safeDefault().copy(protectionIntent = enabled)
            },
            mirrorPublisher = DirectBootProtectionIntentMirrorPublisher { _, _ ->
                DirectBootMirrorResult.MirrorUnavailable
            },
        )
        override val state: Flow<PersistedAppState> = mutableState

        fun current(): PersistedAppState = mutableState.value

        override suspend fun completeOnboarding(configuration: AppConfiguration) = Unit

        override suspend fun setProtectionIntent(enabled: Boolean): ProtectionIntentWriteResult =
            protectionIntentCoordinator.write(enabled)

        override suspend fun setFilter(filterId: String) = Unit

        override suspend fun setLogging(
            keepCounts: Boolean,
            keepHistory: Boolean,
            keepNetworkActivity: Boolean,
        ) {
            val lavaGuardProgress = mutableState.value.logging.keepLavaGuardProgress
            setLogging(keepCounts, keepHistory, keepNetworkActivity, lavaGuardProgress)
        }

        override suspend fun setLogging(
            keepCounts: Boolean,
            keepHistory: Boolean,
            keepNetworkActivity: Boolean,
            keepLavaGuardProgress: Boolean,
        ) {
            mutableState.value = mutableState.value.copy(
                logging = LoggingSettings(
                    keepCounts,
                    keepHistory,
                    keepNetworkActivity,
                    keepLavaGuardProgress,
                ),
            )
        }

        override suspend fun compareAndSetPause(
            expectedProtectionSessionId: Long,
            expectedPause: PauseState?,
            replacement: PauseState?,
        ): PauseMutationResult {
            val authoritative = mutableState.value
            val rejection = when {
                !authoritative.protectionIntent -> PauseMutationRejection.PROTECTION_OFF
                authoritative.protectionSessionId != expectedProtectionSessionId ->
                    PauseMutationRejection.SESSION_MISMATCH
                authoritative.pause != expectedPause -> PauseMutationRejection.EXPECTED_PAUSE_MISMATCH
                else -> null
            }
            if (rejection != null) return PauseMutationResult.Rejected(authoritative, rejection)
            val applied = authoritative.transitionPause(replacement)
            mutableState.value = applied
            return PauseMutationResult.Applied(applied)
        }
    }

    /** Implements the original abstract mutation and inherits only the guarded four-argument bridge. */
    private class ThreeArgumentLoggingRepository(
        initial: PersistedAppState,
    ) : ConfigurationRepository {
        private val mutableState = MutableStateFlow(initial)
        override val state: Flow<PersistedAppState> = mutableState
        var loggingUpdates: Int = 0

        fun current(): PersistedAppState = mutableState.value

        override suspend fun completeOnboarding(configuration: AppConfiguration) = Unit

        override suspend fun setProtectionIntent(enabled: Boolean): ProtectionIntentWriteResult =
            ProtectionIntentWriteResult.RejectedBeforeAuthoritativeCommit(enabled)

        override suspend fun setFilter(filterId: String) = Unit

        override suspend fun setLogging(
            keepCounts: Boolean,
            keepHistory: Boolean,
            keepNetworkActivity: Boolean,
        ) {
            loggingUpdates += 1
            mutableState.value = mutableState.value.copy(
                logging = mutableState.value.logging.copy(
                    keepCounts = keepCounts,
                    keepHistory = keepHistory,
                    keepNetworkActivity = keepNetworkActivity,
                ),
            )
        }

        override suspend fun compareAndSetPause(
            expectedProtectionSessionId: Long,
            expectedPause: PauseState?,
            replacement: PauseState?,
        ): PauseMutationResult = PauseMutationResult.Rejected(
            mutableState.value,
            PauseMutationRejection.PROTECTION_OFF,
        )
    }

    private fun hasInterfaceDefault(name: String, interfaceParameterCount: Int): Boolean {
        val interfaceMethod = ConfigurationRepository::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == interfaceParameterCount
        }
        if (!Modifier.isAbstract(interfaceMethod.modifiers)) return true

        val defaultMethods = runCatching {
            Class.forName("${ConfigurationRepository::class.java.name}\$DefaultImpls").declaredMethods
        }.getOrNull() ?: return false
        return defaultMethods.any {
            it.name == name && it.parameterCount == interfaceParameterCount + 1
        }
    }
}
