package com.lavasec.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.DirectBootAppState
import com.lavasec.app.platform.DirectBootCommittedSnapshotPublication
import com.lavasec.app.platform.DirectBootMirrorResult
import com.lavasec.app.platform.DirectBootSnapshotAuthenticator
import com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority
import com.lavasec.app.platform.DirectBootSnapshotKeyRotationResult
import com.lavasec.app.platform.FilterMetadata
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.platform.PersistedAppState
import com.lavasec.app.platform.ProtectionIntentWriteResult
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialConfigurationRepositoryTest {
    private lateinit var context: Context
    private lateinit var storeName: String
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storeName = "credential-configuration-${UUID.randomUUID()}"
        scope = CoroutineScope(Job() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.preferencesDataStoreFile(storeName).delete()
    }

    @Test fun `historical seed adoption fences the old generation and preserves durable preferences`() = runTest {
        val catalogs = CatalogRepository(context.assets)
        val previousCatalog = catalogs.loadHistoricalBundled().first { it.revision == "d5b44dad805ee43e28dd19419b1340d557776aaf" }
        val currentCatalog = catalogs.loadBundled()
        val sharedStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(storeName) },
        )
        val previous = repository(catalog = previousCatalog, providedStore = sharedStore)
        previous.completeOnboarding(AppConfiguration.default())
        previous.setLogging(true, true, false, true)
        previous.setProtectionIntent(true)
        val saved = previous.state.first()
        val identity = checkNotNull(saved.configurationIdentity)
        val historical = SeededFilterConfiguration.from(checkNotNull(saved.configuration), previousCatalog, identity.generation)
        assertEquals(historical.identity, identity)

        val replacement = repository(catalog = currentCatalog, providedStore = sharedStore)
        val recovered = SeededFilterConfiguration.from(checkNotNull(saved.configuration), currentCatalog, identity.generation + 1)
        var staleRejected = false
        try {
            replacement.commitFilterConfiguration(
                expectedGeneration = identity.generation + 1,
                configuration = recovered.draft,
                identity = recovered.identity.copy(generation = identity.generation + 2),
                credentialUnlockRequired = false,
            )
        } catch (_: IllegalArgumentException) {
            staleRejected = true
        }
        assertTrue(staleRejected)
        assertEquals(saved, replacement.state.first())

        replacement.commitFilterConfiguration(
            expectedGeneration = identity.generation,
            configuration = recovered.draft,
            identity = recovered.identity,
            credentialUnlockRequired = false,
        )
        val adopted = replacement.state.first()
        assertEquals(recovered.identity, adopted.configurationIdentity)
        assertEquals(saved.configuration, adopted.configuration)
        assertEquals(saved.protectionIntent, adopted.protectionIntent)
        assertEquals(saved.protectionSessionId, adopted.protectionSessionId)
        assertEquals(saved.logging, adopted.logging)
        assertEquals(saved.pause, adopted.pause)
    }

    @Test fun `restore logging revalidates native authorization after DataStore wait and reports committed writes honestly`() = runTest {
        val backing = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = scope, produceFile = { context.preferencesDataStoreFile(storeName) },
        )
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var waitBeforeTransform = false
        var afterWrite: (() -> Unit)? = null
        val queuedStore = object : androidx.datastore.core.DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val committed = backing.updateData { previous ->
                    if (waitBeforeTransform) { entered.complete(Unit); release.await() }
                    transform(previous)
                }
                afterWrite?.invoke()
                return committed
            }
        }
        val subject = repository(providedStore = queuedStore)
        subject.completeOnboarding(AppConfiguration.default())
        val initial = subject.state.first()
        val desired = initial.logging.copy(keepCounts = false, keepHistory = false, keepNetworkActivity = false, keepLavaGuardProgress = false)
        var current = true
        waitBeforeTransform = true
        val pending = async { subject.setLoggingForRestore(desired) { current } }
        entered.await()
        current = false
        release.complete(Unit)
        assertFalse(pending.await())
        assertEquals(initial, subject.state.first())
        waitBeforeTransform = false
        current = true
        afterWrite = { current = false }
        assertTrue(subject.setLoggingForRestore(desired) { current })
        assertFalse(current)
        assertEquals(desired, subject.state.first().logging)
        assertEquals(initial.configurationIdentity, subject.state.first().configurationIdentity)
    }

    @Test fun `QA logging CAS rejects stale library logging and native visit before any preference change`() = runTest {
        val subject = repository()
        subject.completeOnboarding(AppConfiguration.default())
        val initial = subject.state.first()
        val generation = checkNotNull(initial.configurationIdentity).generation
        val desired = initial.logging.copy(keepHistory = false)
        assertFalse(subject.compareAndSetQaLogging(initial.logging, desired, generation + 1) { true })
        assertFalse(subject.compareAndSetQaLogging(initial.logging, desired, generation) { false })
        assertEquals(initial, subject.state.first())
        subject.setLogging(initial.logging.keepCounts, true, initial.logging.keepNetworkActivity, initial.logging.keepLavaGuardProgress)
        val enabled = subject.state.first().logging
        assertFalse(subject.compareAndSetQaLogging(initial.logging.copy(keepHistory = false), desired, generation) { true })
        assertEquals(enabled, subject.state.first().logging)
        assertTrue(subject.compareAndSetQaLogging(enabled, enabled.copy(keepHistory = false), generation) { true })
        assertFalse(subject.state.first().logging.keepHistory)
        assertEquals(initial.configurationIdentity, subject.state.first().configurationIdentity)
    }

    @Test
    fun `absent credential store is incomplete and protection off`() = runTest {
        val state = repository().state.first()

        assertFalse(state.onboardingCompleted)
        assertFalse(state.protectionIntent)
        assertEquals(0L, state.protectionSessionId)
        assertEquals(null, state.configuration)
        assertEquals(null, state.configurationIdentity)
    }

    @Test
    fun `pre-onboarding mutations fail before rotating Direct Boot freshness`() = runTest {
        val freshness = TestFreshnessAuthority()
        val repository = repository(freshnessAuthority = freshness)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.setProtectionIntent(true) }
        }

        assertEquals(0, freshness.rotations)
        assertFalse(repository.state.first().onboardingCompleted)
    }

    @Test
    fun `onboarding completion persists one coherent seeded configuration across recreation`() =
        runTest {
            val configuration = AppConfiguration.default().copy(
                protectionLevel = ProtectionLevel.EXTRA,
                activeFilterId = ProtectionLevel.EXTRA.seededFilterId,
            )
            var repository = repository()

            repository.completeOnboarding(configuration)
            val committed = repository.state.first { it.onboardingCompleted }

            assertEquals(configuration, committed.configuration)
            assertEquals(configuration.activeFilterId, committed.activeFilter.id)
            assertFalse(committed.protectionIntent)
            assertNotNull(committed.configurationIdentity)
            val catalog = CatalogRepository(context.assets).loadBundled()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, generation = 1L)
            assertEquals(
                canonicalFilterConfigurationFingerprint(
                    seeded.draft,
                    catalog.revision,
                    catalog.sha256,
                ),
                committed.configurationIdentity?.fingerprint,
            )

            scope.cancel()
            scope.coroutineContext[Job]?.join()
            scope = CoroutineScope(Job() + Dispatchers.IO)
            repository = repository()

            assertEquals(committed, repository.state.first())
        }

    @Test
    fun `protection intent advances session only for authoritative off to on`() = runTest {
        val published = mutableListOf<DirectBootAppState>()
        val repository = repository { state, _ ->
            published += state
            DirectBootMirrorResult.MirrorUnavailable
        }
        repository.completeOnboarding(AppConfiguration.default())

        val first = repository.setProtectionIntent(true)
        val firstState = repository.state.first()
        val repeated = repository.setProtectionIntent(true)
        val repeatedState = repository.state.first()
        val stopped = repository.setProtectionIntent(false)

        assertTrue(first is ProtectionIntentWriteResult.AuthoritativeCommitted)
        assertTrue(repeated is ProtectionIntentWriteResult.AuthoritativeCommitted)
        assertTrue(stopped is ProtectionIntentWriteResult.AuthoritativeCommitted)
        assertEquals(1L, firstState.protectionSessionId)
        assertEquals(firstState.protectionSessionId, repeatedState.protectionSessionId)
        assertFalse(repository.state.first().protectionIntent)
        assertEquals(listOf(false, true, true, false), published.map { it.protectionIntent })
    }

    @Test
    fun `exact manual filter identity is adopted across recreation and requires unlock`() =
        runTest {
            val published = mutableListOf<DirectBootAppState>()
            var repository = repository { state, _ ->
                published += state
                DirectBootMirrorResult.MirrorUnavailable
            }
            val configuration = AppConfiguration.default()
            repository.completeOnboarding(configuration)
            val first = repository.state.first()
            val catalog = CatalogRepository(context.assets).loadBundled()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1L)
            val active = requireNotNull(seeded.draft.profile(seeded.draft.activeProfileId))
            val desired = seeded.draft.withProfile(
                active.withManualRules(ManualDomainRules(blockedDomains = setOf("example.com"))),
            )
            val identity = ConfigurationIdentity(
                generation = 2L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    desired,
                    catalog.revision,
                    catalog.sha256,
                ),
            )

            val result = repository.commitFilterConfiguration(
                expectedGeneration = checkNotNull(first.configurationIdentity).generation,
                configuration = desired,
                identity = identity,
                credentialUnlockRequired = true,
            )

            assertTrue(result is ProtectionIntentWriteResult.AuthoritativeCommitted)
            assertEquals(identity, repository.state.first().configurationIdentity)
            assertTrue(published.last().credentialUnlockRequired)

            scope.cancel()
            scope.coroutineContext[Job]?.join()
            scope = CoroutineScope(Job() + Dispatchers.IO)
            repository = repository()
            assertEquals(identity, repository.state.first().configurationIdentity)
        }

    @Test
    fun `exact whole profile switch adopts the target level and filter across recreation`() =
        runTest {
            val published = mutableListOf<DirectBootAppState>()
            var repository = repository { state, _ ->
                published += state
                DirectBootMirrorResult.MirrorUnavailable
            }
            val configuration = AppConfiguration.default()
            repository.completeOnboarding(configuration)
            val first = repository.state.first()
            val catalog = CatalogRepository(context.assets).loadBundled()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1L)
            val desired = seeded.draft.withActiveProfile(ProtectionLevel.EXTRA.seededFilterId)
            val identity = ConfigurationIdentity(
                generation = 2L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    desired,
                    catalog.revision,
                    catalog.sha256,
                ),
            )

            val result = repository.commitFilterConfiguration(
                expectedGeneration = checkNotNull(first.configurationIdentity).generation,
                configuration = desired,
                identity = identity,
                credentialUnlockRequired = false,
            )

            assertTrue(result is ProtectionIntentWriteResult.AuthoritativeCommitted)
            val adopted = repository.state.first()
            assertEquals(identity, adopted.configurationIdentity)
            // The active filter moves; the onboarding protection level stays put — a filter
            // switch never rewrites the setup choice (iOS keeps the level separate from the
            // saved library the same way).
            assertEquals(ProtectionLevel.BALANCED, adopted.configuration?.protectionLevel)
            assertEquals(ProtectionLevel.EXTRA.seededFilterId, adopted.activeFilter.id)
            // The Direct Boot mirror follows the switched filter so the device runtime
            // cannot keep serving the old profile (DirectBootAppState.matches).
            assertEquals(ProtectionLevel.EXTRA.seededFilterId, published.last().activeProfileId)

            scope.cancel()
            scope.coroutineContext[Job]?.join()
            scope = CoroutineScope(Job() + Dispatchers.IO)
            repository = repository()
            val recreated = repository.state.first()
            assertEquals(identity, recreated.configurationIdentity)
            assertEquals(ProtectionLevel.EXTRA.seededFilterId, recreated.activeFilter.id)
            assertEquals(ProtectionLevel.BALANCED, recreated.configuration?.protectionLevel)
        }

    @Test
    fun `switch away from a rule-carrying active filter is adopted and keeps the rules with it`() =
        runTest {
            // Each saved filter carries its own rules; applying a filter loads them, so a
            // rule-carrying active filter no longer blocks a switch (iOS switchToFilter
            // semantics). The durable adoption moves the active pointer and the level stays.
            val repository = repository()
            val configuration = AppConfiguration.default()
            repository.completeOnboarding(configuration)
            val first = repository.state.first()
            val catalog = CatalogRepository(context.assets).loadBundled()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1L)
            val active = requireNotNull(seeded.draft.profile(seeded.draft.activeProfileId))
            val withRules = seeded.draft.withProfile(
                active.withManualRules(
                    ManualDomainRules(blockedDomains = setOf("manual.example")),
                ),
            )
            val rulesIdentity = ConfigurationIdentity(
                generation = 2L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    withRules,
                    catalog.revision,
                    catalog.sha256,
                ),
            )
            repository.commitFilterConfiguration(
                expectedGeneration = checkNotNull(first.configurationIdentity).generation,
                configuration = withRules,
                identity = rulesIdentity,
                credentialUnlockRequired = true,
            )
            val desired = withRules.withActiveProfile(ProtectionLevel.CORE.seededFilterId)
            val switchIdentity = ConfigurationIdentity(
                generation = 3L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    desired,
                    catalog.revision,
                    catalog.sha256,
                ),
            )
            val result = repository.commitFilterConfiguration(
                expectedGeneration = rulesIdentity.generation,
                configuration = desired,
                identity = switchIdentity,
                credentialUnlockRequired = true,
            )

            assertTrue(result is ProtectionIntentWriteResult.AuthoritativeCommitted)
            val retained = repository.state.first()
            assertEquals(switchIdentity, retained.configurationIdentity)
            assertEquals(ProtectionLevel.CORE.seededFilterId, retained.activeFilter.id)
            // The rules stay with their filter — they are not lost by the switch.
            assertEquals(
                setOf("manual.example"),
                desired.profile(ProtectionLevel.BALANCED.seededFilterId)?.manualRules?.blockedDomains,
            )
        }

    @Test
    fun `exact resolver switch adopts the new plan across recreation without moving the filter`() =
        runTest {
            val published = mutableListOf<DirectBootAppState>()
            var repository = repository { state, _ ->
                published += state
                DirectBootMirrorResult.MirrorUnavailable
            }
            val configuration = AppConfiguration.default()
            repository.completeOnboarding(configuration)
            val first = repository.state.first()
            val catalog = CatalogRepository(context.assets).loadBundled()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1L)
            val plan = MilestoneResolverPlan.MULLVAD_ONLY
            val desired = seeded.draft.withResolverPlan(plan)
            val identity = ConfigurationIdentity(
                generation = 2L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    desired,
                    catalog.revision,
                    catalog.sha256,
                ),
            )

            val result = repository.commitResolverConfiguration(
                expectedGeneration = checkNotNull(first.configurationIdentity).generation,
                plan = plan,
                configuration = desired,
                identity = identity,
                credentialUnlockRequired = false,
            )

            assertTrue(result is ProtectionIntentWriteResult.AuthoritativeCommitted)
            val adopted = repository.state.first()
            assertEquals(identity, adopted.configurationIdentity)
            assertEquals(plan.configuration, adopted.configuration?.resolver)
            // A resolver switch never moves the active profile or its protection level.
            assertEquals(first.activeFilter, adopted.activeFilter)
            assertEquals(first.configuration?.protectionLevel, adopted.configuration?.protectionLevel)
            assertEquals(plan.configuration.useDeviceDns, published.last().useDeviceDns)
            assertEquals(plan.configuration.useEncryptedFallback, published.last().useEncryptedFallback)
            assertEquals(plan.configuration.encryptedFallbackId, published.last().encryptedFallbackId)

            scope.cancel()
            scope.coroutineContext[Job]?.join()
            scope = CoroutineScope(Job() + Dispatchers.IO)
            repository = repository()
            val recreated = repository.state.first()
            assertEquals(identity, recreated.configurationIdentity)
            assertEquals(plan.configuration, recreated.configuration?.resolver)
        }

    @Test
    fun `custom DoH resolver switch adopts the URL into the record and the Direct Boot mirror`() =
        runTest {
            val published = mutableListOf<DirectBootAppState>()
            val repository = repository { state, _ ->
                published += state
                DirectBootMirrorResult.MirrorUnavailable
            }
            val configuration = AppConfiguration.default()
            repository.completeOnboarding(configuration)
            val first = repository.state.first()
            val catalog = CatalogRepository(context.assets).loadBundled()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1L)
            val url = "https://dns.google/dns-query"
            val desired = seeded.draft.withResolverPlan(
                FilterResolverPlan(MilestoneResolverPlan.MULLVAD_ONLY, url),
            )
            val identity = ConfigurationIdentity(
                generation = 2L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    desired,
                    catalog.revision,
                    catalog.sha256,
                ),
            )

            val result = repository.commitResolverConfiguration(
                expectedGeneration = checkNotNull(first.configurationIdentity).generation,
                plan = MilestoneResolverPlan.MULLVAD_ONLY,
                configuration = desired,
                identity = identity,
                credentialUnlockRequired = false,
                customDohUrl = url,
            )

            assertTrue(result is ProtectionIntentWriteResult.AuthoritativeCommitted)
            val adopted = repository.state.first()
            assertEquals(identity, adopted.configurationIdentity)
            assertEquals(url, adopted.configuration?.resolver?.customDohUrl)
            assertEquals(url, published.last().customDohUrl)
            assertEquals(false, published.last().useDeviceDns)
        }

    @Test
    fun `custom DoT resolver switch adopts the endpoint into the record and the Direct Boot mirror`() =
        runTest {
            val published = mutableListOf<DirectBootAppState>()
            val repository = repository { state, _ ->
                published += state
                DirectBootMirrorResult.MirrorUnavailable
            }
            val configuration = AppConfiguration.default()
            repository.completeOnboarding(configuration)
            val first = repository.state.first()
            val catalog = CatalogRepository(context.assets).loadBundled()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1L)
            val endpoint = "tls://dns.google:853"
            val desired = seeded.draft.withResolverPlan(
                FilterResolverPlan(
                    MilestoneResolverPlan.MULLVAD_ONLY,
                    customDotEndpoint = endpoint,
                ),
            )
            val identity = ConfigurationIdentity(
                generation = 2L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    desired,
                    catalog.revision,
                    catalog.sha256,
                ),
            )

            val result = repository.commitResolverConfiguration(
                expectedGeneration = checkNotNull(first.configurationIdentity).generation,
                plan = MilestoneResolverPlan.MULLVAD_ONLY,
                configuration = desired,
                identity = identity,
                credentialUnlockRequired = false,
                customDotEndpoint = endpoint,
            )

            assertTrue(result is ProtectionIntentWriteResult.AuthoritativeCommitted)
            val adopted = repository.state.first()
            assertEquals(identity, adopted.configurationIdentity)
            assertEquals(endpoint, adopted.configuration?.resolver?.customDotEndpoint)
            assertEquals(endpoint, published.last().customDotEndpoint)
            assertEquals(false, published.last().useDeviceDns)
        }

    @Test
    fun `resolver switch with a superseded generation is rejected and keeps the exact state`() =
        runTest {
            val repository = repository()
            val configuration = AppConfiguration.default()
            repository.completeOnboarding(configuration)
            val first = repository.state.first()
            val catalog = CatalogRepository(context.assets).loadBundled()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1L)
            val plan = MilestoneResolverPlan.DEVICE_DNS_ONLY
            val desired = seeded.draft.withResolverPlan(plan)
            val identity = ConfigurationIdentity(
                generation = 2L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    desired,
                    catalog.revision,
                    catalog.sha256,
                ),
            )

            val failure = runCatching {
                repository.commitResolverConfiguration(
                    // The authoritative generation is 1; fencing must reject a stale claim.
                    expectedGeneration = 3L,
                    plan = plan,
                    configuration = desired,
                    identity = identity,
                    credentialUnlockRequired = false,
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            val retained = repository.state.first()
            assertEquals(first.configurationIdentity, retained.configurationIdentity)
            assertEquals(first.configuration?.resolver, retained.configuration?.resolver)
        }

    @Test
    fun `resolver switch to the already authoritative plan is rejected`() = runTest {        val repository = repository()
        val configuration = AppConfiguration.default()
        repository.completeOnboarding(configuration)
        val first = repository.state.first()
        val catalog = CatalogRepository(context.assets).loadBundled()
        val seeded = SeededFilterConfiguration.from(configuration, catalog, 1L)
        val plan = checkNotNull(MilestoneResolverPlan.from(configuration.resolver))
        val desired = seeded.draft.withResolverPlan(plan)
        val identity = ConfigurationIdentity(
            generation = 2L,
            fingerprint = canonicalFilterConfigurationFingerprint(
                desired,
                catalog.revision,
                catalog.sha256,
            ),
        )

        val failure = runCatching {
            repository.commitResolverConfiguration(
                expectedGeneration = checkNotNull(first.configurationIdentity).generation,
                plan = plan,
                configuration = desired,
                identity = identity,
                credentialUnlockRequired = false,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(first.configurationIdentity, repository.state.first().configurationIdentity)
    }

    @Test
    fun `version-one records without the persisted filter name decode with the seeded name`() {
        val catalog = CatalogRepository(context.assets).loadBundled()
        // Every key the version-one encoder wrote, except the filter name added with the
        // filter-library record: an onboarded user upgrading the app must keep their state
        // instead of being rejected back to onboarding.
        val legacy: Preferences = preferencesOf(
            intPreferencesKey("schema_version") to 1,
            booleanPreferencesKey("onboarding_completed") to true,
            booleanPreferencesKey("protection_intent") to true,
            stringPreferencesKey("protection_level") to ProtectionLevel.BALANCED.name,
            booleanPreferencesKey("use_device_dns") to true,
            booleanPreferencesKey("use_encrypted_fallback") to true,
            stringPreferencesKey("encrypted_fallback_id") to BuiltInDohResolverIds.MULLVAD,
            stringPreferencesKey("active_profile_id") to ProtectionLevel.BALANCED.seededFilterId,
            booleanPreferencesKey("keep_counts") to true,
            booleanPreferencesKey("keep_history") to true,
            booleanPreferencesKey("keep_network_activity") to true,
            booleanPreferencesKey("keep_lava_guard_progress") to true,
            longPreferencesKey("protection_session_id") to 0L,
            longPreferencesKey("configuration_generation") to 1L,
            stringPreferencesKey("configuration_fingerprint") to "a".repeat(64),
        )

        val decoded = CredentialConfigurationCodec.decode(legacy, catalog)

        assertTrue(decoded.onboardingCompleted)
        assertEquals(
            FilterProfile.displayName(ProtectionLevel.BALANCED),
            decoded.activeFilter.name,
        )
        assertEquals(ProtectionLevel.BALANCED, decoded.activeFilter.protectionLevel)
    }

    @Test
    fun `a custom DoH resolver configuration round-trips through the credential codec`() {
        val catalog = CatalogRepository(context.assets).loadBundled()
        val identity = ConfigurationIdentity(generation = 1L, fingerprint = "a".repeat(64))
        val customConfiguration = AppConfiguration(
            protectionLevel = ProtectionLevel.BALANCED,
            resolver = ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDohUrl = "https://dns.google/dns-query",
            ),
            activeFilterId = ProtectionLevel.BALANCED.seededFilterId,
            keepCounts = true,
            keepHistory = true,
        )
        val state = PersistedAppState(
            onboardingCompleted = true,
            configuration = customConfiguration,
            protectionIntent = true,
            activeFilter = FilterMetadata.forId(ProtectionLevel.BALANCED.seededFilterId)!!,
            logging = LoggingSettings(),
            protectionSessionId = 0L,
            pauseDeadlineEpochMillis = null,
            pauseSessionId = null,
            configurationIdentity = identity,
        )
        val preferences = mutablePreferencesOf()

        CredentialConfigurationCodec.encode(preferences, state)
        val decoded = CredentialConfigurationCodec.decode(preferences, catalog)

        assertTrue(decoded.onboardingCompleted)
        assertEquals("https://dns.google/dns-query", decoded.configuration?.resolver?.customDohUrl)
        assertEquals(false, decoded.configuration?.resolver?.useDeviceDns)
        assertEquals(identity, decoded.configurationIdentity)
    }

    @Test
    fun `all frozen raw stamp pairs survive the credential owner codec with exact retained rows`() {
        val catalog = CatalogRepository(context.assets).loadBundled()
        val identity = ConfigurationIdentity(1L, "a".repeat(64))
        for (fixture in com.lavasec.app.DnsStampFixture.frozen) {
            val rows = listOf(com.lavasec.app.foundation.resolver.ResolverTier(
                com.lavasec.app.foundation.resolver.ResolverSelectionPresets.DEVICE_PLAIN, false, "Retained network"),
                com.lavasec.app.foundation.resolver.ResolverTier(fixture.entry(), true, "Raw stamp"))
            val resolver = checkNotNull(com.lavasec.app.foundation.resolver.ResolverSelection(rows).toFilterResolverPlan()).configuration
            val configuration = AppConfiguration(ProtectionLevel.BALANCED, resolver,
                activeFilterId = ProtectionLevel.BALANCED.seededFilterId, keepCounts = true, keepHistory = true)
            val state = PersistedAppState(onboardingCompleted = true, configuration = configuration,
                protectionIntent = true, activeFilter = FilterMetadata.forId(ProtectionLevel.BALANCED.seededFilterId)!!,
                logging = LoggingSettings(), protectionSessionId = 0L, pauseDeadlineEpochMillis = null,
                pauseSessionId = null, configurationIdentity = identity)
            val preferences = mutablePreferencesOf()
            CredentialConfigurationCodec.encode(preferences, state)
            val restored = CredentialConfigurationCodec.decode(preferences, catalog)
            assertEquals(configuration, restored.configuration)
            assertEquals(rows, restored.configuration?.resolver?.savedTiers)
            assertEquals(identity, restored.configurationIdentity)
        }
    }

    @Test
    fun `a custom DoT resolver configuration round-trips through the credential codec`() {
        val catalog = CatalogRepository(context.assets).loadBundled()
        val identity = ConfigurationIdentity(generation = 1L, fingerprint = "a".repeat(64))
        val customConfiguration = AppConfiguration(
            protectionLevel = ProtectionLevel.BALANCED,
            resolver = ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDotEndpoint = "tls://dns.google:853",
            ),
            activeFilterId = ProtectionLevel.BALANCED.seededFilterId,
            keepCounts = true,
            keepHistory = true,
        )
        val state = PersistedAppState(
            onboardingCompleted = true,
            configuration = customConfiguration,
            protectionIntent = true,
            activeFilter = FilterMetadata.forId(ProtectionLevel.BALANCED.seededFilterId)!!,
            logging = LoggingSettings(),
            protectionSessionId = 0L,
            pauseDeadlineEpochMillis = null,
            pauseSessionId = null,
            configurationIdentity = identity,
        )
        val preferences = mutablePreferencesOf()

        CredentialConfigurationCodec.encode(preferences, state)
        val decoded = CredentialConfigurationCodec.decode(preferences, catalog)

        assertTrue(decoded.onboardingCompleted)
        assertEquals("tls://dns.google:853", decoded.configuration?.resolver?.customDotEndpoint)
        assertEquals(false, decoded.configuration?.resolver?.useDeviceDns)
        assertEquals(identity, decoded.configurationIdentity)
    }

    private fun repository(
        freshnessAuthority: TestFreshnessAuthority = TestFreshnessAuthority(),
        catalog: BundledBlocklistCatalog = CatalogRepository(context.assets).loadBundled(),
        providedStore: androidx.datastore.core.DataStore<Preferences>? = null,
        publisher: suspend (
            DirectBootAppState,
            DirectBootCommittedSnapshotPublication,
        ) -> DirectBootMirrorResult = { _, _ -> DirectBootMirrorResult.MirrorUnavailable },
    ): CredentialConfigurationRepository = CredentialConfigurationRepository(
        credentialProtectedContext = context,
        storeName = storeName,
        scope = scope,
        freshnessAuthority = freshnessAuthority,
        mirrorPublisher = publisher,
        catalog = catalog,
        providedStore = providedStore,
    )

    private class TestFreshnessAuthority : DirectBootSnapshotFreshnessAuthority() {
        private var key = 0
        private var authenticator: DirectBootSnapshotAuthenticator? = null
        val rotations get() = key

        override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult {
            key += 1
            val current = key
            val replacement = DirectBootSnapshotAuthenticator { commitment ->
                ("%064x".format(current) + commitment).takeLast(64)
            }
            authenticator = replacement
            return rotated(replacement)
        }

        override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator? = authenticator
    }
}
