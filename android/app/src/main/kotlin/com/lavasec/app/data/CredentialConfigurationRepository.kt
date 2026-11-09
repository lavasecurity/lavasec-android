package com.lavasec.app.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ConfigurationRepository
import com.lavasec.app.platform.DirectBootAppState
import com.lavasec.app.platform.DirectBootCommittedSnapshotPublication
import com.lavasec.app.platform.DirectBootMirrorResult
import com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority
import com.lavasec.app.platform.FilterMetadata
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.platform.PauseMutationRejection
import com.lavasec.app.platform.PauseMutationResult
import com.lavasec.app.platform.PersistedAppState
import com.lavasec.app.platform.ProtectionIntentWriteCoordinator
import com.lavasec.app.platform.ProtectionIntentWriteResult
import com.lavasec.app.platform.transitionProtectionIntent
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Credential-protected authority for product configuration and logical protection intent.
 *
 * Every behavior mutation that can affect Direct Boot is committed through
 * [ProtectionIntentWriteCoordinator]. The coordinator rotates freshness before the credential
 * write and exposes a one-shot publication capability only after exact durable read-back.
 */
class CredentialConfigurationRepository internal constructor(
    credentialProtectedContext: Context,
    storeName: String = CREDENTIAL_STORE_NAME,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    @Volatile private var catalog: BundledBlocklistCatalog =
        CatalogRepository(credentialProtectedContext.assets).loadBundled(),
    private val freshnessAuthority: DirectBootSnapshotFreshnessAuthority,
    private val mirrorPublisher: suspend (
        DirectBootAppState,
        DirectBootCommittedSnapshotPublication,
    ) -> DirectBootMirrorResult,
    providedStore: DataStore<Preferences>? = null,
) : ConfigurationRepository {
    private val mutationMutex = Mutex()
    // The DataStore is injectable so the production graph factory can share ONE
    // process-wide store across credential generations. DataStore's FileStorage
    // rejects a second active instance on the same file, so a per-generation
    // store dead-ends graph rotation with "multiple DataStores active".
    private val store: DataStore<Preferences> = providedStore ?: PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { credentialProtectedContext.preferencesDataStoreFile(storeName) },
    )

    override val state: Flow<PersistedAppState> = store.safeData()
        .map { CredentialConfigurationCodec.decode(it, catalog) }

    /**
     * Adopts the installed runtime catalog. The repository only reads the catalog to re-derive the
     * identity fingerprint of a filter adoption ([validateManualFilterConfiguration]); it never
     * decodes persisted state from it ([inspect] is catalog-free), so swapping it cannot invalidate
     * an already-durable configuration. The authority calls this immediately before a catalog
     * recompile commit and restores the previous catalog on any failure.
     */
    internal fun installCatalog(catalog: BundledBlocklistCatalog) {
        this.catalog = catalog
    }

    override suspend fun completeOnboarding(configuration: AppConfiguration) {
        validateConfiguration(configuration)
        val activeFilter = requireNotNull(FilterMetadata.forId(configuration.activeFilterId)) {
            "onboarding must select a seeded filter"
        }
        require(activeFilter.protectionLevel == configuration.protectionLevel) {
            "protection level and active filter must match"
        }

        val result = mutateAndPublish(
            desiredProtectionIntent = false,
            requireCompletedOnboarding = false,
        ) { previous ->
            if (previous.onboardingCompleted) {
                require(previous.configuration == configuration) {
                    "onboarding is already complete with a different configuration"
                }
                previous
            } else {
                PersistedAppState(
                    onboardingCompleted = true,
                    configuration = configuration,
                    protectionIntent = false,
                    activeFilter = activeFilter,
                    logging = LoggingSettings(
                        keepCounts = configuration.keepCounts,
                        keepHistory = configuration.keepHistory,
                        keepNetworkActivity = true,
                    ),
                    configurationIdentity = null,
                ).withNextConfigurationIdentity(previous, catalog)
            }
        }
        check(result is ProtectionIntentWriteResult.AuthoritativeCommitted) {
            "onboarding could not establish fresh Direct Boot authority"
        }
    }

    override suspend fun setProtectionIntent(enabled: Boolean): ProtectionIntentWriteResult =
        mutateAndPublish(enabled) { it.transitionProtectionIntent(enabled) }

    override suspend fun setFilter(filterId: String) {
        val filter = requireNotNull(FilterMetadata.forId(filterId)) { "unknown filter: $filterId" }
        val result = mutateAndPublish(desiredProtectionIntent = null) { previous ->
            val configuration = checkNotNull(previous.configuration) {
                "filter cannot change before onboarding"
            }
            if (previous.activeFilter == filter) {
                previous
            } else {
                previous.copy(
                    configuration = configuration.copy(
                        // The legacy seeded-switch path only ever targets seeded filters, whose
                        // level is pinned; user-created filters are opaque to this pre-record path.
                        protectionLevel = checkNotNull(filter.protectionLevel),
                        activeFilterId = filter.id,
                    ),
                    activeFilter = filter,
                    configurationIdentity = null,
                ).withNextConfigurationIdentity(previous, catalog)
            }
        }
        check(result is ProtectionIntentWriteResult.AuthoritativeCommitted) {
            "filter change could not establish fresh Direct Boot authority"
        }
    }

    /**
     * Adopts one exact next filter generation after its credential artifact is authoritative.
     *
     * The persisted product configuration records the active filter as an opaque id + name
     * pair derived from the desired configuration's active profile; the onboarding protection
     * level stays put because a user-created filter has no level (iOS keeps the level separate
     * from the saved library the same way). The filter library itself lives in the
     * authoritative filter-configuration record, whose generation CAS already fenced this
     * adoption, so the validation here is the exact pointer + identity check, not a re-derivation
     * of the library. The resolver authority stays pinned unchanged on this path; a plan change
     * routes through [commitResolverConfiguration].
     */
    internal suspend fun commitFilterConfiguration(
        expectedGeneration: Long,
        configuration: FilterLibraryDraft,
        identity: ConfigurationIdentity,
        credentialUnlockRequired: Boolean,
    ): ProtectionIntentWriteResult {
        require(expectedGeneration > 0L)
        require(identity.generation == Math.addExact(expectedGeneration, 1L))
        return mutateAndPublish(
            desiredProtectionIntent = null,
            credentialUnlockRequired = credentialUnlockRequired,
        ) { previous ->
            val appConfiguration = checkNotNull(previous.configuration) {
                "filter configuration cannot change before onboarding"
            }
            require(previous.configurationIdentity?.generation == expectedGeneration) {
                "filter identity was superseded"
            }
            val targetProfile = requireNotNull(configuration.profile(configuration.activeProfileId)) {
                "desired configuration must contain its active profile"
            }
            val targetConfiguration = appConfiguration.copy(
                activeFilterId = configuration.activeProfileId,
            )
            validateManualFilterConfiguration(
                appConfiguration = targetConfiguration,
                desired = configuration,
                identity = identity,
                credentialUnlockRequired = credentialUnlockRequired,
            )
            previous.copy(
                configuration = targetConfiguration,
                activeFilter = FilterMetadata(
                    id = targetProfile.id,
                    name = targetProfile.name,
                    protectionLevel = targetProfile.protectionLevel,
                ),
                configurationIdentity = identity,
            )
        }
    }

    /**
     * Adopts one exact resolver-plan switch after its credential artifact is authoritative.
     *
     * [commitFilterConfiguration] deliberately pins the resolver authority unchanged, so a
     * resolver change needs its own adoption: the persisted product configuration's resolver
     * moves to the plan's exact [ResolverConfiguration], and the seeded authority is re-derived
     * from that new resolver exactly like onboarding derives the initial seeded configuration
     * (SeededFilterConfiguration.from), so the fingerprint and generation cover the new plan.
     * The active filter and its protection level never move with a resolver switch, matching
     * iOS `setResolver` (AppViewModel.swift), which changes the resolver preset while the
     * loaded filter stays put. The expected-generation CAS inside [mutateAndPublish] fences
     * the adoption against any interleaved filter/profile commit.
     */
    internal suspend fun commitResolverConfiguration(
        expectedGeneration: Long,
        plan: MilestoneResolverPlan,
        configuration: FilterLibraryDraft,
        identity: ConfigurationIdentity,
        credentialUnlockRequired: Boolean,
        /**
         * The normalized custom DoH endpoint when the adopted plan is a custom selection, or null
         * for every built-in plan. It is carried alongside the plan so the persisted resolver is
         * the exact value the library names.
         */
        customDohUrl: String? = null,
        /**
         * The normalized custom DoT endpoint when the adopted plan is a custom DoT selection, or
         * null for every built-in/custom-DoH plan. At most one custom endpoint is ever set.
         */
        customDotEndpoint: String? = null,
        /**
         * The committed custom display name when the adopted plan is a custom selection, or null.
         * Carried alongside the endpoint so the persisted resolver round-trips the exact name.
         */
        customName: String? = null,
        /**
         * The committed optional same-transport secondary endpoint when the adopted plan is a
         * custom selection, or null.
         */
        customSecondaryEndpoint: String? = null,
        /**
         * The retained ordered ladder (including disabled rows) the adopted plan is projected
         * from, or null for a legacy milestone-only adoption.
         */
        savedTiers: List<ResolverTier>? = null,
        customDoqEndpoint: String? = null,
    ): ProtectionIntentWriteResult {
        require(expectedGeneration > 0L)
        require(identity.generation == Math.addExact(expectedGeneration, 1L))
        val target = FilterResolverPlan(
            plan,
            customDohUrl,
            customDotEndpoint,
            customName,
            customSecondaryEndpoint,
            savedTiers,
            customDoqEndpoint,
        )
        return mutateAndPublish(
            desiredProtectionIntent = null,
            credentialUnlockRequired = credentialUnlockRequired,
        ) { previous ->
            val appConfiguration = checkNotNull(previous.configuration) {
                "resolver configuration cannot change before onboarding"
            }
            require(previous.configurationIdentity?.generation == expectedGeneration) {
                "resolver identity was superseded"
            }
            // The coordinator's NO_CHANGE gate normally prevents a same-plan apply; this
            // fence keeps the durable adoption from silently consuming an identity bump
            // that changed nothing.
            require(FilterResolverPlan.from(appConfiguration.resolver) != target) {
                "resolver plan already matches"
            }
            val targetConfiguration = appConfiguration.copy(resolver = target.configuration)
            validateManualFilterConfiguration(
                appConfiguration = targetConfiguration,
                desired = configuration,
                identity = identity,
                credentialUnlockRequired = credentialUnlockRequired,
            )
            previous.copy(
                configuration = targetConfiguration,
                configurationIdentity = identity,
            )
        }
    }

    override suspend fun setLogging(
        keepCounts: Boolean,
        keepHistory: Boolean,
        keepNetworkActivity: Boolean,
    ) {
        mutationMutex.withLock {
            updateLogging(
                keepCounts = keepCounts,
                keepHistory = keepHistory,
                keepNetworkActivity = keepNetworkActivity,
                keepLavaGuardProgress = null,
            )
        }
    }

    override suspend fun setLogging(
        keepCounts: Boolean,
        keepHistory: Boolean,
        keepNetworkActivity: Boolean,
        keepLavaGuardProgress: Boolean,
    ) {
        mutationMutex.withLock {
            updateLogging(
                keepCounts = keepCounts,
                keepHistory = keepHistory,
                keepNetworkActivity = keepNetworkActivity,
                keepLavaGuardProgress = keepLavaGuardProgress,
            )
        }
    }

    /** Restore carries its native lease through mutex/DataStore waits to the final transform. */
    internal suspend fun setLoggingForRestore(
        desired: LoggingSettings,
        isCurrent: () -> Boolean,
    ): Boolean = mutationMutex.withLock {
        var claimed = false
        var identity: ConfigurationIdentity? = null
        fun matches(actual: PersistedAppState) = actual.configurationIdentity == identity && actual.logging == desired
        try {
            val actual = store.edit { preferences ->
                val previous = CredentialConfigurationCodec.requireWritable(preferences, catalog)
                val configuration = previous.configuration ?: return@edit
                if (!isCurrent()) return@edit
                identity = previous.configurationIdentity
                claimed = true
                CredentialConfigurationCodec.encode(preferences, previous.copy(
                    configuration = configuration.copy(keepCounts = desired.keepCounts, keepHistory = desired.keepHistory),
                    logging = desired,
                ))
            }
            // Retirement after a durable write must not masquerade as an uncommitted refusal.
            claimed && matches(CredentialConfigurationCodec.requireWritable(actual, catalog))
        } catch (_: Exception) {
            claimed && runCatching { matches(CredentialConfigurationCodec.requireWritable(store.data.first(), catalog)) }.getOrDefault(false)
        }
    }

    /** QA privacy's final CAS: reject a newer library/logging choice before deleting any data. */
    internal suspend fun compareAndSetQaLogging(
        expected: LoggingSettings,
        desired: LoggingSettings,
        expectedConfigurationGeneration: Long,
        isCurrent: () -> Boolean,
    ): Boolean = mutationMutex.withLock {
        var claimed = false
        fun matches(actual: PersistedAppState) = actual.configurationIdentity?.generation == expectedConfigurationGeneration && actual.logging == desired
        try {
            val actual = store.edit { preferences ->
                val previous = CredentialConfigurationCodec.requireWritable(preferences, catalog)
                if (previous.configurationIdentity?.generation != expectedConfigurationGeneration || previous.logging != expected || !isCurrent()) return@edit
                val configuration = previous.configuration ?: return@edit
                claimed = true
                CredentialConfigurationCodec.encode(preferences, previous.copy(
                    configuration = configuration.copy(keepCounts = desired.keepCounts, keepHistory = desired.keepHistory),
                    logging = desired,
                ))
            }
            claimed && matches(CredentialConfigurationCodec.requireWritable(actual, catalog))
        } catch (_: Exception) {
            // A completed write followed by an acknowledgement failure is still committed.
            // An unreadable outcome requests reconciliation and never authorizes deletion.
            claimed && runCatching { matches(CredentialConfigurationCodec.requireWritable(store.data.first(), catalog)) }.getOrDefault(false)
        }
    }

    private suspend fun updateLogging(
        keepCounts: Boolean,
        keepHistory: Boolean,
        keepNetworkActivity: Boolean,
        keepLavaGuardProgress: Boolean?,
    ) {
        store.edit { preferences ->
            val previous = CredentialConfigurationCodec.requireWritable(preferences, catalog)
            val configuration = checkNotNull(previous.configuration) {
                "logging cannot change before onboarding"
            }
            CredentialConfigurationCodec.encode(
                preferences,
                previous.copy(
                    configuration = configuration.copy(
                        keepCounts = keepCounts,
                        keepHistory = keepHistory,
                    ),
                    logging = LoggingSettings(
                        keepCounts = keepCounts,
                        keepHistory = keepHistory,
                        keepNetworkActivity = keepNetworkActivity,
                        keepLavaGuardProgress = keepLavaGuardProgress
                            ?: previous.logging.keepLavaGuardProgress,
                    ),
                ),
            )
        }
    }

    override suspend fun compareAndSetPause(
        expectedProtectionSessionId: Long,
        expectedPause: PauseState?,
        replacement: PauseState?,
    ): PauseMutationResult {
        require(expectedProtectionSessionId > 0L) {
            "expected protection session must be positive"
        }
        require(expectedPause == null || expectedPause.protectionSessionId == expectedProtectionSessionId)
        require(replacement == null || replacement.protectionSessionId == expectedProtectionSessionId)
        require(replacement == null || replacement.deadlineMillis >= 0L)

        return mutationMutex.withLock {
            var result: PauseMutationResult? = null
            store.edit { preferences ->
                val authoritative = CredentialConfigurationCodec.requireWritable(preferences, catalog)
                val rejection = when {
                    !authoritative.protectionIntent -> PauseMutationRejection.PROTECTION_OFF
                    authoritative.protectionSessionId != expectedProtectionSessionId ->
                        PauseMutationRejection.SESSION_MISMATCH
                    authoritative.pause != expectedPause ->
                        PauseMutationRejection.EXPECTED_PAUSE_MISMATCH
                    else -> null
                }
                if (rejection != null) {
                    result = PauseMutationResult.Rejected(authoritative, rejection)
                    return@edit
                }
                val updated = authoritative.copy(
                    pauseDeadlineEpochMillis = replacement?.deadlineMillis,
                    pauseSessionId = replacement?.protectionSessionId,
                )
                CredentialConfigurationCodec.encode(preferences, updated)
                result = PauseMutationResult.Applied(updated)
            }
            checkNotNull(result)
        }
    }

    private suspend fun mutateAndPublish(
        desiredProtectionIntent: Boolean?,
        credentialUnlockRequired: Boolean = false,
        requireCompletedOnboarding: Boolean = true,
        mutation: (PersistedAppState) -> PersistedAppState,
    ): ProtectionIntentWriteResult = mutationMutex.withLock {
        val previous = state.first()
        if (requireCompletedOnboarding) {
            require(previous.onboardingCompleted) {
                "credential mutation requires completed onboarding"
            }
        }
        val desired = desiredProtectionIntent ?: previous.protectionIntent
        object : ProtectionIntentWriteCoordinator(freshnessAuthority) {
            override suspend fun commitAndReadBack(desired: Boolean): DirectBootAppState? {
                val committed = store.edit { preferences ->
                    val previous = CredentialConfigurationCodec.requireWritable(preferences, catalog)
                    val updated = mutation(previous)
                    check(updated.protectionIntent == desired) {
                        "credential mutation did not commit requested protection intent"
                    }
                    CredentialConfigurationCodec.encode(preferences, updated)
                }.let { CredentialConfigurationCodec.decode(it, catalog) }
                return committed.toDirectBootState(credentialUnlockRequired)
            }

            override suspend fun publishMirror(
                state: DirectBootAppState,
                committedPublication: DirectBootCommittedSnapshotPublication,
            ): DirectBootMirrorResult = mirrorPublisher(state, committedPublication)
        }.write(desired)
    }

    private fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { error ->
        if (error is IOException || error is CorruptionException) {
            emit(androidx.datastore.preferences.core.emptyPreferences())
        } else {
            throw error
        }
    }

    internal companion object {
        internal const val CREDENTIAL_STORE_NAME = "lavasec_configuration"
    }

    private fun validateManualFilterConfiguration(
        appConfiguration: AppConfiguration,
        desired: FilterLibraryDraft,
        identity: ConfigurationIdentity,
        credentialUnlockRequired: Boolean,
    ) {
        // The filter library is the authoritative filter-configuration record's truth (its
        // generation CAS fenced this adoption), so the adoption validates the exact pointer
        // facts this repository persists: the active filter exists, the resolver authority it
        // persists matches the desired plan, manual rules require credential unlock, and
        // the identity fingerprint covers the complete
        // desired configuration. Manual rules on non-active filters are legal now — each saved
        // filter carries its own rules and applying a filter loads them, exactly like iOS.
        requireNotNull(desired.profile(desired.activeProfileId)) {
            "desired configuration must contain its active profile"
        }
        // Compare the VALUE-carrying plan, not the milestone enum: a valid custom DoH/DoT
        // selection carries customDohUrl/customDotEndpoint, for which MilestoneResolverPlan.from
        // is always null. Comparing the value plan keeps a custom adoption committable (the
        // resolver authority and the library must name the exact same endpoint).
        require(FilterResolverPlan.from(appConfiguration.resolver) == desired.resolverTarget) {
            "resolver authority must match the exact desired configuration"
        }
        val containsManualRules = desired.profiles.any { profile ->
            profile.manualRules.allowedDomains.isNotEmpty() ||
                profile.manualRules.blockedDomains.isNotEmpty()
        }
        // Plus-only compiled artifacts also require unlock. This pointer repository has
        // no compiled counts; the authoritative artifact store validates that exact flag.
        require(!containsManualRules || credentialUnlockRequired)
        require(
            identity.fingerprint == canonicalFilterConfigurationFingerprint(
                desired,
                catalog.revision,
                catalog.sha256,
            ),
        ) { "filter identity does not match the exact desired configuration" }
    }
}

internal object CredentialConfigurationCodec {
    private const val SCHEMA_VERSION = 1
    private val schemaVersion = intPreferencesKey("schema_version")
    private val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
    private val protectionIntent = booleanPreferencesKey("protection_intent")
    private val protectionLevel = stringPreferencesKey("protection_level")
    private val useDeviceDns = booleanPreferencesKey("use_device_dns")
    private val useEncryptedFallback = booleanPreferencesKey("use_encrypted_fallback")
    private val encryptedFallbackId = stringPreferencesKey("encrypted_fallback_id")
    private val customDohUrl = stringPreferencesKey("custom_doh_url")
    private val customDotEndpoint = stringPreferencesKey("custom_dot_endpoint")
    private val customDoqEndpoint = stringPreferencesKey("custom_doq_endpoint")
    private val customName = stringPreferencesKey("custom_name")
    private val customSecondaryEndpoint = stringPreferencesKey("custom_secondary_endpoint")
    private val savedTiers = stringPreferencesKey("saved_tiers")
    private val activeProfileId = stringPreferencesKey("active_profile_id")
    private val activeFilterName = stringPreferencesKey("active_filter_name")
    private val keepCounts = booleanPreferencesKey("keep_counts")
    private val keepHistory = booleanPreferencesKey("keep_history")
    private val keepNetworkActivity = booleanPreferencesKey("keep_network_activity")
    private val keepLavaGuardProgress = booleanPreferencesKey("keep_lava_guard_progress")
    private val protectionSessionId = longPreferencesKey("protection_session_id")
    private val pauseDeadline = longPreferencesKey("pause_deadline_epoch_millis")
    private val pauseSessionId = longPreferencesKey("pause_session_id")
    private val configurationGeneration = longPreferencesKey("configuration_generation")
    private val configurationFingerprint = stringPreferencesKey("configuration_fingerprint")

    private val expectedValueTypes = mapOf(
        schemaVersion.name to Int::class,
        onboardingCompleted.name to Boolean::class,
        protectionIntent.name to Boolean::class,
        protectionLevel.name to String::class,
        useDeviceDns.name to Boolean::class,
        useEncryptedFallback.name to Boolean::class,
        encryptedFallbackId.name to String::class,
        customDohUrl.name to String::class,
        customDotEndpoint.name to String::class,
        customDoqEndpoint.name to String::class,
        customName.name to String::class,
        customSecondaryEndpoint.name to String::class,
        savedTiers.name to String::class,
        activeProfileId.name to String::class,
        activeFilterName.name to String::class,
        keepCounts.name to Boolean::class,
        keepHistory.name to Boolean::class,
        keepNetworkActivity.name to Boolean::class,
        keepLavaGuardProgress.name to Boolean::class,
        protectionSessionId.name to Long::class,
        pauseDeadline.name to Long::class,
        pauseSessionId.name to Long::class,
        configurationGeneration.name to Long::class,
        configurationFingerprint.name to String::class,
    )

    fun decode(
        preferences: Preferences,
        catalog: BundledBlocklistCatalog,
    ): PersistedAppState = when (val read = inspect(preferences, catalog)) {
        CredentialConfigurationRead.Empty -> PersistedAppState.initial()
        is CredentialConfigurationRead.Valid -> read.state
        is CredentialConfigurationRead.Rejected -> PersistedAppState.initial()
    }

    fun requireWritable(
        preferences: Preferences,
        catalog: BundledBlocklistCatalog,
    ): PersistedAppState =
        when (val read = inspect(preferences, catalog)) {
            CredentialConfigurationRead.Empty -> PersistedAppState.initial()
            is CredentialConfigurationRead.Valid -> read.state
            is CredentialConfigurationRead.Rejected -> error(
                "credential state is not writable: ${read.reason}",
            )
        }

    fun encode(preferences: MutablePreferences, state: PersistedAppState) {
        preferences.clear()
        if (!state.onboardingCompleted) return
        val configuration = checkNotNull(state.configuration)
        val identity = checkNotNull(state.configurationIdentity)
        preferences[schemaVersion] = SCHEMA_VERSION
        preferences[onboardingCompleted] = true
        preferences[protectionIntent] = state.protectionIntent
        preferences[protectionLevel] = configuration.protectionLevel.name
        preferences[useDeviceDns] = configuration.resolver.useDeviceDns
        preferences[useEncryptedFallback] = configuration.resolver.useEncryptedFallback
        preferences[encryptedFallbackId] = configuration.resolver.encryptedFallbackId
        configuration.resolver.customDohUrl?.let { preferences[customDohUrl] = it }
        configuration.resolver.customDotEndpoint?.let { preferences[customDotEndpoint] = it }
        configuration.resolver.customDoqEndpoint?.let { preferences[customDoqEndpoint] = it }
        configuration.resolver.customName?.let { preferences[customName] = it }
        configuration.resolver.customSecondaryEndpoint?.let { preferences[customSecondaryEndpoint] = it }
        configuration.resolver.savedTiers?.let { preferences[savedTiers] = encodeSavedTiers(it) }
        preferences[activeProfileId] = state.activeFilter.id
        preferences[activeFilterName] = state.activeFilter.name
        preferences[keepCounts] = state.logging.keepCounts
        preferences[keepHistory] = state.logging.keepHistory
        preferences[keepNetworkActivity] = state.logging.keepNetworkActivity
        preferences[keepLavaGuardProgress] = state.logging.keepLavaGuardProgress
        preferences[protectionSessionId] = state.protectionSessionId
        state.pauseDeadlineEpochMillis?.let { preferences[pauseDeadline] = it }
        state.pauseSessionId?.let { preferences[pauseSessionId] = it }
        preferences[configurationGeneration] = identity.generation
        preferences[configurationFingerprint] = identity.fingerprint
    }

    private fun inspect(
        preferences: Preferences,
        catalog: BundledBlocklistCatalog,
    ): CredentialConfigurationRead {
        if (preferences.asMap().isEmpty()) return CredentialConfigurationRead.Empty
        if (!preferences.hasOnlyExpectedValueTypes(expectedValueTypes)) {
            return CredentialConfigurationRead.Rejected("unexpected or mistyped preference")
        }
        if (preferences[schemaVersion] != SCHEMA_VERSION) {
            return CredentialConfigurationRead.Rejected("unsupported schema")
        }
        if (preferences[onboardingCompleted] != true) {
            return CredentialConfigurationRead.Rejected("nonempty incomplete state")
        }
        val intent = preferences[protectionIntent]
            ?: return CredentialConfigurationRead.Rejected("missing protection intent")
        val level = preferences[protectionLevel]
            ?.let { runCatching { ProtectionLevel.valueOf(it) }.getOrNull() }
            ?: return CredentialConfigurationRead.Rejected("invalid protection level")
        val activeId = preferences[activeProfileId]
            ?: return CredentialConfigurationRead.Rejected("missing active profile")
        val levelForActive = ProtectionLevel.entries.firstOrNull { it.seededFilterId == activeId }
        // The active filter is an opaque id + name pair now — the library truth is the
        // authoritative filter-configuration record, and a user-created filter id cannot be
        // resolved against the seeded catalog. The name is persisted so the product surfaces
        // can label the active filter without reopening the record. Version-one credential
        // stores predate the persisted name; for those, the seeded display name is derived so
        // an onboarded user upgrades in place instead of being rejected back to onboarding.
        val activeName = preferences[activeFilterName]
            ?.takeIf { it.isNotBlank() }
            ?: levelForActive?.let(FilterProfile::displayName)
            ?: return CredentialConfigurationRead.Rejected("missing active filter name")
        val active = FilterMetadata(
            id = activeId,
            name = activeName,
            protectionLevel = levelForActive,
        )
        val deviceDns = preferences[useDeviceDns]
            ?: return CredentialConfigurationRead.Rejected("missing Device DNS choice")
        val encrypted = preferences[useEncryptedFallback]
            ?: return CredentialConfigurationRead.Rejected("missing encrypted choice")
        val fallbackId = preferences[encryptedFallbackId]
            ?.takeIf { it in BuiltInDohResolverIds.all }
            ?: return CredentialConfigurationRead.Rejected("unsupported encrypted resolver")
        val customDoh = preferences[customDohUrl]?.takeIf { it.isNotBlank() }
        val customDot = preferences[customDotEndpoint]?.takeIf { it.isNotBlank() }
        val customDoq = preferences[customDoqEndpoint]?.takeIf { it.isNotBlank() }
        val customNameValue = preferences[customName]?.takeIf { it.isNotBlank() }
        val customSecondary = preferences[customSecondaryEndpoint]?.takeIf { it.isNotBlank() }
        val savedResolverTiers = preferences[savedTiers]?.let { encoded ->
            try {
                decodeSavedTiers(encoded)
            } catch (_: Exception) {
                return CredentialConfigurationRead.Rejected("invalid saved resolver tiers")
            }
        }
        val counts = preferences[keepCounts]
            ?: return CredentialConfigurationRead.Rejected("missing counts choice")
        val history = preferences[keepHistory]
            ?: return CredentialConfigurationRead.Rejected("missing history choice")
        val network = preferences[keepNetworkActivity]
            ?: return CredentialConfigurationRead.Rejected("missing network choice")
        val progress = preferences[keepLavaGuardProgress]
            ?: return CredentialConfigurationRead.Rejected("missing progress choice")
        val session = preferences[protectionSessionId]
            ?.takeIf { it >= 0L }
            ?: return CredentialConfigurationRead.Rejected("invalid protection session")
        val deadline = preferences[pauseDeadline]?.takeIf { it >= 0L }
        val pauseSession = preferences[pauseSessionId]?.takeIf { it > 0L }
        if ((deadline == null) != (pauseSession == null)) {
            return CredentialConfigurationRead.Rejected("incomplete pause")
        }
        if (deadline != null && (!intent || pauseSession != session)) {
            return CredentialConfigurationRead.Rejected("pause session mismatch")
        }
        val generation = preferences[configurationGeneration]
            ?.takeIf { it > 0L }
            ?: return CredentialConfigurationRead.Rejected("invalid configuration generation")
        val fingerprint = preferences[configurationFingerprint]
            ?.takeIf(::isCanonicalSha256)
            ?: return CredentialConfigurationRead.Rejected("invalid configuration fingerprint")
        val configuration = try {
            AppConfiguration(
                protectionLevel = level,
                resolver = ResolverConfiguration(
                    deviceDns,
                    encrypted,
                    fallbackId,
                    customDohUrl = customDoh,
                    customDotEndpoint = customDot,
                    customDoqEndpoint = customDoq,
                    customName = customNameValue,
                    customSecondaryEndpoint = customSecondary,
                    savedTiers = savedResolverTiers,
                ),
                activeFilterId = activeId,
                keepCounts = counts,
                keepHistory = history,
            )
        } catch (_: IllegalArgumentException) {
            return CredentialConfigurationRead.Rejected("invalid configuration")
        }
        val decoded = PersistedAppState(
            onboardingCompleted = true,
            configuration = configuration,
            protectionIntent = intent,
            activeFilter = active,
            logging = LoggingSettings(counts, history, network, progress),
            protectionSessionId = session,
            pauseDeadlineEpochMillis = deadline,
            pauseSessionId = pauseSession,
            configurationIdentity = ConfigurationIdentity(generation, fingerprint),
        )
        return CredentialConfigurationRead.Valid(decoded)
    }
}

private sealed interface CredentialConfigurationRead {
    data object Empty : CredentialConfigurationRead
    data class Valid(val state: PersistedAppState) : CredentialConfigurationRead
    data class Rejected(val reason: String) : CredentialConfigurationRead
}

private fun PersistedAppState.withNextConfigurationIdentity(
    previous: PersistedAppState,
    catalog: BundledBlocklistCatalog,
): PersistedAppState {
    val nextGeneration = Math.addExact(previous.configurationIdentity?.generation ?: 0L, 1L)
    val withoutIdentity = copy(configurationIdentity = null)
    return withoutIdentity.copy(
        configurationIdentity = SeededFilterConfiguration.from(
            configuration = checkNotNull(withoutIdentity.configuration),
            catalog = catalog,
            generation = nextGeneration,
        ).identity,
    )
}

private fun PersistedAppState.toDirectBootState(
    credentialUnlockRequired: Boolean = false,
): DirectBootAppState {
    val configuration = checkNotNull(configuration)
    return DirectBootAppState(
        onboardingCompleted = onboardingCompleted,
        protectionIntent = protectionIntent,
        useDeviceDns = configuration.resolver.useDeviceDns,
        useEncryptedFallback = configuration.resolver.useEncryptedFallback,
        encryptedFallbackId = configuration.resolver.encryptedFallbackId,
        customDohUrl = configuration.resolver.customDohUrl,
        customDotEndpoint = configuration.resolver.customDotEndpoint,
        customDoqEndpoint = configuration.resolver.customDoqEndpoint,
        customName = configuration.resolver.customName,
        customSecondaryEndpoint = configuration.resolver.customSecondaryEndpoint,
        savedTiers = configuration.resolver.savedTiers,
        activeProfileId = activeFilter.id,
        configurationIdentity = configurationIdentity,
        credentialUnlockRequired = credentialUnlockRequired,
    )
}

private fun Preferences.hasOnlyExpectedValueTypes(
    expected: Map<String, KClass<*>>,
): Boolean = asMap().all { (key, value) -> expected[key.name]?.isInstance(value) == true }

private fun validateConfiguration(configuration: AppConfiguration) {
    require(configuration.resolver.encryptedFallbackId in BuiltInDohResolverIds.all) {
        "unsupported encrypted resolver: ${configuration.resolver.encryptedFallbackId}"
    }
}

private fun isCanonicalSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

/**
 * The retained resolver ladder, including disabled rows, as a base64-encoded field list. Base64
 * avoids any delimiter collision with a user-supplied name or endpoint, and decoding is plain JVM
 * (no `org.json`), so the store is testable without an Android runtime.
 */
private fun encodeSavedTiers(tiers: List<ResolverTier>): String = tiers.joinToString(TIER_ROW_SEPARATOR) { tier ->
    listOf(
        tier.entry.providerId.rawId,
        tier.entry.transport.token,
        tier.entry.customName ?: "",
        tier.entry.customDohUrl ?: "",
        tier.entry.customDotEndpoint ?: "",
        tier.entry.customSecondaryEndpoint ?: "",
        if (tier.isEnabled) "1" else "0",
        tier.entry.customDoqEndpoint ?: "",
        tier.retainedName ?: "",
        tier.entry.customPlainEndpoint ?: "",
    ).joinToString(TIER_FIELD_SEPARATOR, transform = ::encodeTierField)
}

/** Rebuilds the retained ladder, rejecting an unknown provider/transport or an incoherent row. */
private fun decodeSavedTiers(value: String): List<ResolverTier>? {
    if (value.isEmpty()) return null
    return value.split(TIER_ROW_SEPARATOR).map { row ->
        val parts = row.split(TIER_FIELD_SEPARATOR)
        require(parts.size in 7..SAVED_TIER_FIELDS) { "malformed saved resolver tier" }
        val providerId = ResolverProviderId.entries
            .singleOrNull { it.rawId == decodeTierField(parts[0]) }
            ?: throw IllegalArgumentException("unknown resolver provider")
        val transport = CatalogResolverTransport.entries
            .singleOrNull { it.token == decodeTierField(parts[1]) }
            ?: throw IllegalArgumentException("unknown resolver transport")
        val entry = ResolverSelectionEntry(
            providerId = providerId,
            transport = transport,
            customDohUrl = decodeTierField(parts[3]).ifEmpty { null },
            customDotEndpoint = decodeTierField(parts[4]).ifEmpty { null },
            customDoqEndpoint = parts.getOrNull(7)?.let(::decodeTierField)?.ifEmpty { null },
            customPlainEndpoint = parts.getOrNull(9)?.let(::decodeTierField)?.ifEmpty { null },
            customName = decodeTierField(parts[2]).ifEmpty { null },
            customSecondaryEndpoint = decodeTierField(parts[5]).ifEmpty { null },
        )
        val enabled = when (decodeTierField(parts[6])) {
            "0" -> false
            "1" -> true
            else -> throw IllegalArgumentException("unknown resolver row enablement")
        }
        ResolverTier(entry, enabled, parts.getOrNull(8)?.let(::decodeTierField)?.ifEmpty { null })
    }
}

private const val TIER_ROW_SEPARATOR = "|"
private const val TIER_FIELD_SEPARATOR = ";"
private const val SAVED_TIER_FIELDS = 10

private fun encodeTierField(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeTierField(value: String): String =
    if (value.isEmpty()) "" else String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
