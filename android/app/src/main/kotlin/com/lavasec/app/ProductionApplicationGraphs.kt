package com.lavasec.app

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.ActivityRevisionBus
import com.lavasec.app.data.BundledBlocklistCatalog
import com.lavasec.app.data.CatalogRepository
import com.lavasec.app.data.CredentialActivityWriter
import com.lavasec.app.data.CredentialChainedUpstreamConfigurationRepository
import com.lavasec.app.data.CredentialProtectionActivitySink
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lavasec.app.data.CredentialConfigurationRepository
import com.lavasec.app.data.InitialFilterProvisionResult
import com.lavasec.app.data.SeededFilterConfiguration
import com.lavasec.app.diagnostics.CredentialOperationalDiagnosticsOwner
import com.lavasec.app.diagnostics.CredentialOperationalDiagnosticsStore
import com.lavasec.app.chained.AndroidChainedDeviceEligibilityStore
import com.lavasec.app.directboot.AndroidDirectBootFreshnessAuthority
import com.lavasec.app.directboot.DeviceProtectedArtifactPublisher
import com.lavasec.app.directboot.DeviceProtectedConfigurationReader
import com.lavasec.app.directboot.readVerifiedDeviceProtectedSnapshotOrNull
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.PausePolicy
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.chained.ChainedUpstreamConfigurationValidator
import com.lavasec.app.notification.CredentialConnectivityNotificationHistoryStore
import com.lavasec.app.notification.CredentialConnectivityNotificationOwner
import com.lavasec.app.notification.ProtectionAmbientNotificationFactory
import com.lavasec.app.notification.ProtectionAmbientNotificationOwner
import com.lavasec.app.notification.ProtectionProblemNotificationFactory
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.DirectBootAppState
import com.lavasec.app.platform.DirectBootArtifactReadResult
import com.lavasec.app.platform.DirectBootArtifactReader
import com.lavasec.app.platform.DirectBootConfigurationSnapshot
import com.lavasec.app.platform.DirectBootMirrorResult
import com.lavasec.app.platform.FilterArtifactReference
import com.lavasec.app.platform.PauseMutationResult
import com.lavasec.app.platform.PersistedAppState
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import com.lavasec.app.platform.ChainedComposition
import com.lavasec.app.platform.ChainedDeviceEligibilityRead
import com.lavasec.app.platform.ChainedEntitlementPort
import com.lavasec.app.billing.NativePlusBackend
import com.lavasec.app.billing.NativePlusEntitlementOwner
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.resolveChainedHandoff
import com.lavasec.app.security.AndroidKeystoreChainedKeyItemStore
import com.lavasec.app.product.activity.CredentialNetworkActivityRuntimeStore
import com.lavasec.app.product.privacy.CredentialPrivacyFence
import com.lavasec.app.presentation.filters.FilterSourceCatalogEntry
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationRuntimeRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import com.lavasec.app.vpn.CredentialVpnAttachRequest
import com.lavasec.app.vpn.CredentialVpnAttachment
import com.lavasec.app.vpn.CredentialVpnHostPort
import com.lavasec.app.vpn.CredentialVpnRuntimeAttachment
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import com.lavasec.app.vpn.VpnProtectionEvidence
import com.lavasec.app.vpn.VpnTelemetry
import com.lavasec.app.vpn.chained.ChainedSessionEligibilityLifecycle
import com.lavasec.app.vpn.chained.ChainedVpnCredentials
import kotlin.concurrent.withLock
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Small Android factory shared by the process device graph and each unlocked generation. */
internal class ProductionApplicationGraphFactory(
    context: Context,
    private val appStarted: Flow<Boolean>,
    /**
     * Supplies the current foreground Activity for platform surfaces that prefer an Activity host
     * (the biometric prompt). Defaults to "none"; the adapter then falls back to the application
     * context.
     */
    private val currentActivity: () -> Activity? = { null },
) {
    private val applicationContext = context.applicationContext
    // One owner across credential graph rotations; never constructed for Release/Direct Boot.
    private val dnsLeakCanaryOwner by lazy {
        if (BuildConfig.DEBUG) com.lavasec.app.data.CredentialDnsLeakCanaryOwner(applicationContext) else null
    }
    private val accountBackend by lazy { com.lavasec.app.account.AccountBackend(applicationContext) }
    private val appServices by lazy { com.lavasec.app.backend.NativeApplicationServices(applicationContext) }
    private val plusBackend by lazy { NativePlusBackend(applicationContext, appStarted, appServices.primary) }
    // Created only after credential unlock, shared across RN/native screens and graph rotations.
    private val securityOwner by lazy {
        com.lavasec.app.security.SecurityOwner(
            com.lavasec.app.security.AndroidKeystorePasscodeVerifier(applicationContext),
            com.lavasec.app.security.SecurityPreferenceStore(applicationContext),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            biometricAuthenticator = biometricAuthenticator(),
        )
    }

    /**
     * The real framework biometric adapter when this build has an Application context; any
     * construction failure keeps the honest fail-closed default, so a broken platform surface is
     * never mistaken for an available authenticator.
     */
    private fun biometricAuthenticator(): com.lavasec.app.security.BiometricAuthenticatorPort =
        runCatching {
            com.lavasec.app.security.AndroidBiometricAuthenticatorPort(
                applicationContext,
                currentActivity,
            )
        }.getOrDefault(com.lavasec.app.security.UnavailableBiometricAuthenticatorPort)
    private val freshnessAuthority = AndroidDirectBootFreshnessAuthority()
    // One DataStore per process: graph rotation replaces the repository, not the
    // underlying store (DataStore rejects two active instances on one file).
    private val configurationStore: androidx.datastore.core.DataStore<
        androidx.datastore.preferences.core.Preferences
    > by lazy {
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = {
                applicationContext.preferencesDataStoreFile(
                    CredentialConfigurationRepository.CREDENTIAL_STORE_NAME,
                )
            },
        )
    }
    private val deviceContext = applicationContext.createDeviceProtectedStorageContext()
    private val deviceRoot = File(deviceContext.noBackupFilesDir, DEVICE_RUNTIME_DIRECTORY)
    private val devicePublisher = DeviceProtectedArtifactPublisher(deviceRoot)

    // One chained-configuration DataStore per process, for the same reason as the product store:
    // graph rotation replaces the repository, never the underlying store (DataStore rejects two
    // active instances on one file).
    private val chainedConfigurationStore: androidx.datastore.core.DataStore<
        androidx.datastore.preferences.core.Preferences
    > by lazy {
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = {
                applicationContext.preferencesDataStoreFile(
                    CredentialChainedUpstreamConfigurationRepository.CHAINED_STORE_NAME,
                )
            },
        )
    }

    fun createDevice(): DeviceApplicationGraph {
        require(deviceContext.isDeviceProtectedStorage)
        // Cold start authenticates only the small public envelope. The host reopens and verifies
        // the exact artifact on IO when a VPN service start actually requests an attachment.
        val envelope = DeviceProtectedConfigurationReader(deviceRoot, freshnessAuthority).readOrNull()
        val host = envelope?.takeIf { it.state.isDirectBootRuntimeEligible() }?.let {
            ProductionDeviceVpnHost(
                context = deviceContext,
                graphGeneration = DEVICE_GRAPH_GENERATION,
                root = deviceRoot,
                freshnessAuthority = freshnessAuthority,
            )
        }
        return ProductionDeviceApplicationGraph(host)
    }

    suspend fun createCredential(generation: Long): CredentialApplicationGraph {
        require(generation > 0L)
        require(!applicationContext.isDeviceProtectedStorage)
        val userManager = applicationContext.getSystemService(UserManager::class.java)
        check(userManager.isUserUnlocked) {
            "credential graph cannot be created before user unlock"
        }

        if (com.lavasec.app.edition.AppEdition.current.accounts) accountBackend.sessions.initialize()
        plusBackend.entitlement.initialize()
        return com.lavasec.app.data.CatalogInstallationGate.withStableCatalog {
            createCredentialFromInstalledCatalog(generation)
        }
    }

    private suspend fun createCredentialFromInstalledCatalog(generation: Long): CredentialApplicationGraph {
        val bundledCatalog = CatalogRepository(applicationContext.assets).loadBundled()
        // The graph runs against the last successfully installed remote catalog, or the pinned
        // bundle when none was installed. This is what makes a catalog refresh survive a process
        // restart and a credential-graph rotation.
        val catalog = com.lavasec.app.data.CatalogRuntimeCatalog.resolveInstalled(
            applicationContext,
            bundledCatalog,
        )
        val filterStore = CredentialFilterConfigurationStore.credentialProtected(
            applicationContext,
            com.lavasec.app.foundation.filter.FilterCompiler.MAX_SOURCE_RULES,
        )
        val configurationRepository = CredentialConfigurationRepository(
            credentialProtectedContext = applicationContext,
            catalog = catalog,
            freshnessAuthority = freshnessAuthority,
            mirrorPublisher = { state, publication ->
                publishExactMirror(filterStore, state, publication)
            },
            providedStore = configurationStore,
        )
        val filterAuthority = ProductionFilterAuthority(
            context = applicationContext,
            initialCatalog = catalog,
            configurationRepository = configurationRepository,
            filterStore = filterStore,
            devicePublisher = devicePublisher,
            plusOwner = plusBackend.entitlement,
        )
        var persisted = configurationRepository.state.first()
        var authoritativeFilter =
            (filterStore.readAuthoritative() as? CredentialFilterConfigurationRead.Available)?.state
        val persistedIdentity = persisted.configurationIdentity
        if (
            persisted.onboardingCompleted &&
            persistedIdentity != null &&
            authoritativeFilter?.configurationIdentity?.generation ==
            Math.addExact(persistedIdentity.generation, 1L)
        ) {
            val candidate = checkNotNull(authoritativeFilter)
            runCatching {
                configurationRepository.commitFilterConfiguration(
                    expectedGeneration = persistedIdentity.generation,
                    configuration = candidate.configuration,
                    identity = candidate.configurationIdentity,
                    credentialUnlockRequired = candidate.credentialUnlockRequired,
                )
            }
            persisted = configurationRepository.state.first()
        }
        if (!BuildConfig.DEBUG && authoritativeFilter?.configuration?.qaProbeSet != null) {
            // Removing metadata alone would leave the QA rules in the retained compiled artifact.
            // Recompile and commit a fresh ordinary generation before any runtime admission.
            filterAuthority.removeRetainedQaForRelease(checkNotNull(authoritativeFilter))
            persisted = configurationRepository.state.first()
            authoritativeFilter =
                (filterStore.readAuthoritative() as? CredentialFilterConfigurationRead.Available)?.state
        }
        var confirmed = coherentFilterState(persisted, catalog, filterStore)

        if (
            persisted.onboardingCompleted &&
            confirmed == null &&
            persisted.configuration != null &&
            persisted.configurationIdentity != null &&
            authoritativeFilter == null
        ) {
            when (val provisioned =
                filterAuthority.provisionInitial(
                    checkNotNull(persisted.configuration),
                    checkNotNull(persisted.configurationIdentity),
                )
            ) {
                is InitialFilterProvisionResult.Initialized,
                is InitialFilterProvisionResult.Existing,
                -> {
                    try {
                        val recovered = checkNotNull(provisioned.state)
                        if (recovered.configurationIdentity != persisted.configurationIdentity) {
                            configurationRepository.commitFilterConfiguration(
                                expectedGeneration = checkNotNull(persisted.configurationIdentity).generation,
                                configuration = recovered.configuration,
                                identity = recovered.configurationIdentity,
                                credentialUnlockRequired = recovered.credentialUnlockRequired,
                            )
                        }
                        configurationRepository.setProtectionIntent(persisted.protectionIntent)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep the old pointer on a failed adoption. A durable next-generation
                        // record is reconciled through the existing CAS on the next graph.
                    }
                    persisted = configurationRepository.state.first()
                    authoritativeFilter =
                        (filterStore.readAuthoritative() as?
                            CredentialFilterConfigurationRead.Available)?.state
                    confirmed = coherentFilterState(persisted, catalog, filterStore)
                }
                is InitialFilterProvisionResult.Unavailable -> Unit
            }
        }

        var filterMutationsAvailable = true
        val migrationBasis = confirmed
        if (migrationBasis != null) {
            val migration = reconcileCatalogMigration(
                initial = migrationBasis,
                migrate = filterAuthority::migrateRetiredCatalogSelections,
                readConfirmed = {
                    persisted = configurationRepository.state.first()
                    coherentFilterState(persisted, catalog, filterStore)
                },
            )
            confirmed = migration.confirmed
            filterMutationsAvailable = migration.mutationsAvailable
            persisted = configurationRepository.state.first()
        }

        if (
            confirmed != null &&
            requiresFreshDeviceMirrorPublication(
                persisted = persisted,
                credentialArtifact = FilterArtifactReference(
                    confirmed.verifiedArtifact.sha256,
                    confirmed.verifiedArtifact.artifactIdentity,
                ),
            ) {
                withContext(Dispatchers.IO) {
                    readVerifiedDeviceProtectedSnapshotOrNull(
                        root = deviceRoot,
                        freshnessAuthority = freshnessAuthority,
                    )?.let { snapshot ->
                        VerifiedDeviceMirror(snapshot.state, snapshot.artifact)
                    }
                }
            }
        ) {
            // A no-op logical write still performs the required fresh-key publication sequence.
            try {
                configurationRepository.setProtectionIntent(persisted.protectionIntent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Credential authority remains the only restoration source; an unavailable
                // public republish stays fail closed and can be reconstructed on a later graph.
            }
            persisted = configurationRepository.state.first()
            confirmed = coherentFilterState(persisted, catalog, filterStore)
        }

        // Initial catalog names/IDs seed the live owner flow. Successful in-process
        // catalog installation updates that same flow alongside the filter authority.
        val filterCatalogEntries = confirmed?.let {
            catalog.sources.map { source ->
                FilterSourceCatalogEntry(
                    id = source.id,
                    name = source.name,
                    category = source.category,
                    sourceUrl = source.sourceUrl,
                    ruleCount = source.entryCount,
                )
            }
        }

        return ProductionCredentialApplicationGraph(
            context = applicationContext,
            graphGeneration = generation,
            initialState = persisted,
            initialConfirmedFilter = confirmed,
            initialFilterMutationsAvailable = filterMutationsAvailable,
            initialFilterCatalogEntries = filterCatalogEntries,
            configurationRepository = configurationRepository,
            filterStore = filterStore,
            filterAuthority = filterAuthority,
            appStarted = appStarted,
            chainedComposition = createChainedComposition(),
            accountBackend = if (com.lavasec.app.edition.AppEdition.current.accounts) accountBackend else null,
            plusBackend = plusBackend,
            reportService = appServices.reports,
            processSecurity = securityOwner,
            dnsLeakCanaryOwner = dnsLeakCanaryOwner,
        )
    }

    /**
     * Builds the per-generation chained-upstream composition.
     *
     * Device Plus and chaining share one atomic persistence owner across graph rotations.
     * A transient unreadable plan refuses activation without erasing saved preferences or keys.
     * A confirmed lapse clears chaining atomically with the plan, without a reload signal.
     *
     * `clientDnsOverTunnelAvailable` is `true`: the chained data path now wires the intercepted
     * client-DNS sink to the T0 resolver egress (`ChainedT0ResolverEgress`), which rebuilds each
     * query as an inner datagram for the configuration's own `DNS =` resolver and carries it
     * through the WireGuard session — no physical socket. The readiness gate still refuses a
     * configuration with no usable, routed T0 (`ChainedTunnelResolverSelection`), so a latch is
     * only possible when the egress has somewhere to send.
     */
    private fun createChainedComposition(): ChainedComposition = ChainedComposition.production(
        entitlement = ChainedEntitlementPort {
            val state = plusBackend.entitlement.state.value
            check(state.available) { "Device plan is unavailable" }
            state.localFeaturesAvailable
        },
        eligibilityStore = AndroidChainedDeviceEligibilityStore(applicationContext),
        configurationRepository = CredentialChainedUpstreamConfigurationRepository(
            credentialProtectedContext = applicationContext,
            keyItemStore = AndroidKeystoreChainedKeyItemStore(applicationContext),
            providedStore = chainedConfigurationStore,
        ),
        preferenceStore = plusBackend.persistence,
        clientDnsOverTunnelAvailable = true,
    )

    private suspend fun publishExactMirror(
        filterStore: CredentialFilterConfigurationStore,
        state: DirectBootAppState,
        publication: com.lavasec.app.platform.DirectBootCommittedSnapshotPublication,
    ): DirectBootMirrorResult {
        val read = filterStore.readAuthoritative()
        val confirmed = (read as? CredentialFilterConfigurationRead.Available)?.state
            ?: return DirectBootMirrorResult.MirrorUnavailable
        if (confirmed.configurationIdentity != state.configurationIdentity) {
            return DirectBootMirrorResult.MirrorUnavailable
        }
        val reference = FilterArtifactReference(
            confirmed.verifiedArtifact.sha256,
            confirmed.verifiedArtifact.artifactIdentity,
        )
        return if (devicePublisher.publishReference(state, reference, publication)) {
            DirectBootMirrorResult.deviceProtectedPublished(confirmed)
        } else {
            DirectBootMirrorResult.MirrorUnavailable
        }
    }

    private companion object {
        const val DEVICE_RUNTIME_DIRECTORY = "direct-boot-runtime"
        val DEVICE_GRAPH_GENERATION = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
    }
}

private class ProductionDeviceApplicationGraph(
    override val vpnHost: ProductionDeviceVpnHost?,
) : DeviceApplicationGraph {
    override fun close() {
        vpnHost?.close()
    }
}

/** Credential-free exact host backed only by the authenticated device-protected mirror. */
private class ProductionDeviceVpnHost(
    private val context: Context,
    override val graphGeneration: Long,
    private val root: File,
    private val freshnessAuthority: com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority,
) : CredentialVpnHostPort, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val attachments = CopyOnWriteArraySet<ProductionDeviceVpnAttachment>()

    override fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment? {
        if (closed.get()) return null
        val snapshot = runBlocking(Dispatchers.IO) {
            readVerifiedDeviceProtectedSnapshotOrNull(root, freshnessAuthority)
        }?.takeIf { it.state.isDirectBootRuntimeEligible() } ?: return null
        val resolver = snapshot.state.directBootResolverConfigurationOrNull() ?: return null
        val attachment = ProductionDeviceVpnAttachment(
            request = request,
            graphGeneration = graphGeneration,
            snapshot = snapshot,
            resolverConfiguration = resolver,
            root = root,
            freshnessAuthority = freshnessAuthority,
            configureIntent = PendingIntent.getActivity(
                context,
                DIRECT_BOOT_CONFIGURE_INTENT_REQUEST_CODE,
                Intent(context, com.lavasec.app.reactnative.LavaReactActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            onDetach = attachments::remove,
        )
        attachments += attachment
        if (closed.get()) {
            attachment.detach()
            return null
        }
        return attachment
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        attachments.toList().forEach(ProductionDeviceVpnAttachment::detach)
        attachments.clear()
    }

    private companion object {
        const val DIRECT_BOOT_CONFIGURE_INTENT_REQUEST_CODE = 1_002
    }
}

private class ProductionDeviceVpnAttachment(
    private val request: CredentialVpnAttachRequest,
    override val graphGeneration: Long,
    private val snapshot: DirectBootConfigurationSnapshot,
    override val resolverConfiguration: ResolverConfiguration,
    private val root: File,
    private val freshnessAuthority: com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority,
    override val configureIntent: PendingIntent,
    private val onDetach: (ProductionDeviceVpnAttachment) -> Unit,
) : CredentialVpnRuntimeAttachment {
    private val detached = AtomicBoolean(false)

    override val configurationIdentity = checkNotNull(snapshot.state.configurationIdentity)
    override val activeProtectionSessionId: Long = configurationIdentity.generation

    override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean = false

    override fun acceptProtectionEvidence(evidence: VpnProtectionEvidence): Boolean = false

    override fun openFilterRuntimeLease(): VerifiedFilterRuntimeLease {
        check(!detached.get()) { "Direct Boot VPN attachment is detached" }
        val current = runBlocking(Dispatchers.IO) {
            readVerifiedDeviceProtectedSnapshotOrNull(root, freshnessAuthority)
        } ?: error("Direct Boot authority is unavailable")
        check(current.state.configurationIdentity == configurationIdentity)
        check(current.artifact == snapshot.artifact)
        val opened = runBlocking(Dispatchers.IO) {
            DirectBootArtifactReader.verifying { reference ->
                com.lavasec.app.directboot.DirectBootStorageLayout.artifact(root, reference.sha256)
            }.read(current)
        }
        val available = opened as? DirectBootArtifactReadResult.Available
            ?: error("Direct Boot filter artifact is unavailable")
        val lease = VerifiedFilterRuntimeLease.takeDirectBootOpenedOrNull(
            snapshot = current,
            artifact = available.artifact,
        )
        if (lease == null) available.close()
        val exactLease = checkNotNull(lease) { "Direct Boot filter artifact is incoherent" }
        if (detached.get()) {
            exactLease.close()
            error("Direct Boot VPN attachment was detached during filter open")
        }
        return exactLease
    }

    override fun currentPause(): RuntimePauseDirective = RuntimePauseDirective.Filtering

    override fun requestPause(duration: PauseDuration): PauseState? = null

    override fun requestResume(expectedPause: PauseState?): Boolean = false

    override fun detach() {
        if (!detached.compareAndSet(false, true)) return
        onDetach(this)
    }
}

private fun DirectBootAppState.isDirectBootRuntimeEligible(): Boolean =
    onboardingCompleted &&
        protectionIntent &&
        !credentialUnlockRequired &&
        configurationIdentity?.generation?.let { it > 0L } == true &&
        activeProfileId != null

private fun DirectBootAppState.directBootResolverConfigurationOrNull(): ResolverConfiguration? =
    runCatching {
        ResolverConfiguration(
            useDeviceDns = useDeviceDns,
            useEncryptedFallback = useEncryptedFallback,
            encryptedFallbackId = checkNotNull(encryptedFallbackId),
            // Carry the custom endpoint through the credential-free mirror too; without it the
            // encrypted-only shape would be reinterpreted as the pinned Mullvad resolver, so
            // locked-device traffic would silently use a different provider than the user chose.
            customDohUrl = customDohUrl,
            customDotEndpoint = customDotEndpoint,
            customDoqEndpoint = customDoqEndpoint,
            customName = customName,
            customSecondaryEndpoint = customSecondaryEndpoint,
            savedTiers = savedTiers,
        )
    }.getOrNull()

/** One unlocked resource graph. Registration is owned by the outer lifecycle owner. */
internal class ProductionCredentialApplicationGraph private constructor(
    override val graphGeneration: Long,
    override val accountBackend: com.lavasec.app.account.AccountBackend?,
    override val plusBackend: NativePlusBackend?,
    override val authoritativeProtectionIntent: Boolean?,
    override val vpnHost: ProductionCredentialVpnHost,
    override val dnsLeakCanaryOwner: com.lavasec.app.data.CredentialDnsLeakCanaryOwner?,
    private val database: ActivityDatabase,
    private val networkActivity: CredentialNetworkActivityRuntimeStore,
    private val diagnosticsOwner: CredentialOperationalDiagnosticsOwner,
    private val dnsFallbackHistory: com.lavasec.app.diagnostics.CredentialDnsFallbackHistoryStore,
    private val notificationOwner: CredentialConnectivityNotificationOwner,
    private val ambientNotificationOwner: ProtectionAmbientNotificationOwner,
    override val productOwnerFactory: CredentialProductOwnerFactory,
    override val chainedComposition: ChainedComposition?,
    private val privacyFence: CredentialPrivacyFence,
    private val scope: CoroutineScope,
) : CredentialApplicationGraph {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        vpnHost.close()
        runBlocking {
            privacyFence.withGraphRotation {
                notificationOwner.close()
                ambientNotificationOwner.close()
                diagnosticsOwner.close()
                runCatching { dnsFallbackHistory.close() }
                productOwnerFactory.close()
                scope.cancel()
                networkActivity.close()
                database.close()
            }
        }
    }

    companion object {
        operator fun invoke(
            context: Context,
            graphGeneration: Long,
            initialState: PersistedAppState,
            initialConfirmedFilter: ConfirmedFilterState?,
            initialFilterCatalogEntries: List<FilterSourceCatalogEntry>?,
            configurationRepository: CredentialConfigurationRepository,
            filterStore: CredentialFilterConfigurationStore,
            filterAuthority: ProductionFilterAuthority,
            appStarted: Flow<Boolean>,
            chainedComposition: ChainedComposition?,
            accountBackend: com.lavasec.app.account.AccountBackend? = null,
            plusBackend: NativePlusBackend? = null,
            reportService: com.lavasec.app.diagnostics.NativeBugReportService? = null,
            processSecurity: com.lavasec.app.security.SecurityOwner? = null,
            dnsLeakCanaryOwner: com.lavasec.app.data.CredentialDnsLeakCanaryOwner? = null,
            initialFilterMutationsAvailable: Boolean = true,
        ): ProductionCredentialApplicationGraph {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val database = ActivityDatabase(context)
            database.recoverAbandonedProtectionSessions()
            val activityRepository = ActivityRepository(database)
            val activityRevisionBus = ActivityRevisionBus()
            val activityWriter = CredentialActivityWriter(activityRepository, activityRevisionBus,
                com.lavasec.app.data.GuardianProgressStore.get(context))
            val privacyFence = CredentialPrivacyFence()
            runBlocking(Dispatchers.IO) {
                privacyFence.withGraphRotation {
                    activityWriter.applyLoggingPolicy(initialState.logging)
                }
            }
            val networkActivity = runBlocking(Dispatchers.IO) {
                val persistence = com.lavasec.app.product.activity.AndroidNetworkActivityPersistence(context)
                CredentialNetworkActivityRuntimeStore(persistence = persistence,
                    retainExisting = initialState.logging.keepNetworkActivity)
            }
            val dnsFallbackFlushes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            val dnsFallbackHistory = com.lavasec.app.diagnostics.CredentialDnsFallbackHistoryStore(
                context, onDirty = { dnsFallbackFlushes.trySend(Unit) },
            )
            scope.launch(Dispatchers.IO) {
                for (ignored in dnsFallbackFlushes) {
                    kotlinx.coroutines.delay(2_000L)
                    while (dnsFallbackFlushes.tryReceive().isSuccess) { /* batch the current burst */ }
                    runCatching { dnsFallbackHistory.flush() }
                }
            }
            val host = ProductionCredentialVpnHost(
                context = context,
                graphGeneration = graphGeneration,
                configurationRepository = configurationRepository,
                filterStore = filterStore,
                activityWriter = activityWriter,
                privacyFence = privacyFence,
                chainedComposition = chainedComposition,
                plusOwner = plusBackend?.entitlement,
                dnsLeakCanaryOwner = if (BuildConfig.DEBUG) dnsLeakCanaryOwner else null,
                dnsFallbackHistory = dnsFallbackHistory,
            )
            val credentialAvailable = {
                context.getSystemService(UserManager::class.java).isUserUnlocked
            }
            val diagnosticsStore = CredentialOperationalDiagnosticsStore(
                credentialContext = context,
                credentialAvailable = credentialAvailable,
            )
            val diagnosticsOwner = CredentialOperationalDiagnosticsOwner(
                credentialOwnerGeneration = graphGeneration,
                processGeneration = PROCESS_GENERATION,
                runtimeGeneration = graphGeneration,
                health = LavaVpnRuntimeBridge.health,
                protection = LavaVpnRuntimeBridge.state,
                sink = diagnosticsStore,
                scope = scope,
                elapsedRealtimeMillis = SystemClock::elapsedRealtime,
            )
            val notificationOwner = CredentialConnectivityNotificationOwner(
                health = LavaVpnRuntimeBridge.health,
                historyStore = CredentialConnectivityNotificationHistoryStore(context),
                presenter = ProtectionProblemNotificationFactory(context),
                scope = scope,
            )
            // The Android-native ambient protection surface (iOS Live Activity equivalent):
            // one ongoing status notification derived from the exact runtime flows and the
            // live confirmed filter, posting only while protection is active. It extends the
            // same credential-graph notification anchors as the connectivity owner above and
            // is retired with this graph.
            val ambientNotificationOwner = ProtectionAmbientNotificationOwner(
                protection = LavaVpnRuntimeBridge.state,
                health = LavaVpnRuntimeBridge.health,
                configuration = filterAuthority.confirmedState
                    .map { confirmed -> confirmed?.configuration },
                presenter = ProtectionAmbientNotificationFactory(context),
                scope = scope,
            )
            val restoration = when {
                !initialState.onboardingCompleted || !initialState.protectionIntent -> false
                initialState.configuration == null ||
                    initialState.configurationIdentity == null ||
                    initialConfirmedFilter == null -> null
                initialConfirmedFilter.configurationIdentity !=
                    initialState.configurationIdentity -> null
                else -> true
            }
            val productOwnerFactory = ProductionCredentialProductOwnerFactory(
                context = context,
                graphGeneration = graphGeneration,
                initialState = initialState,
                initialConfirmedFilter = initialConfirmedFilter,
                initialFilterMutationsAvailable = initialFilterMutationsAvailable,
                initialFilterCatalogEntries = initialFilterCatalogEntries,
                configurationRepository = configurationRepository,
                filterAuthority = filterAuthority,
                appStarted = appStarted,
                activityRepository = activityRepository,
                activityWriter = activityWriter,
                activityRevisionBus = activityRevisionBus,
                networkActivity = networkActivity,
                privacyFence = privacyFence,
                diagnosticsStore = diagnosticsStore,
                dnsFallbackHistory = dnsFallbackHistory,
                ambientNotificationOwner = ambientNotificationOwner,
                reportService = reportService,
                validateChainedConfigurationForStart = {
                    if (chainedComposition == null) com.lavasec.app.foundation.chained.ChainedConfigurationIssue.UNAVAILABLE
                    else chainedComposition.configurationIssueForStart()
                },
                chainedComposition = chainedComposition,
                processSecurity = processSecurity,
                featureLimits = plusBackend?.limits ?: kotlinx.coroutines.flow.MutableStateFlow(FeatureLimits.FREE),
            )
            return ProductionCredentialApplicationGraph(
                accountBackend = accountBackend,
                plusBackend = plusBackend,
                graphGeneration = graphGeneration,
                authoritativeProtectionIntent = restoration,
                vpnHost = host,
                dnsLeakCanaryOwner = if (BuildConfig.DEBUG) dnsLeakCanaryOwner else null,
                database = database,
                networkActivity = networkActivity,
                diagnosticsOwner = diagnosticsOwner,
                dnsFallbackHistory = dnsFallbackHistory,
                notificationOwner = notificationOwner,
                ambientNotificationOwner = ambientNotificationOwner,
                productOwnerFactory = productOwnerFactory,
                chainedComposition = chainedComposition,
                privacyFence = privacyFence,
                scope = scope,
            )
        }

        private val PROCESS_GENERATION = SystemClock.elapsedRealtimeNanos().coerceAtLeast(0L)
    }
}

/** Exact-generation synchronous service port backed only by current durable credential authority. */
internal class ProductionCredentialVpnHost(
    private val context: Context,
    override val graphGeneration: Long,
    private val configurationRepository: CredentialConfigurationRepository,
    private val filterStore: CredentialFilterConfigurationStore,
    private val activityWriter: CredentialActivityWriter,
    private val privacyFence: CredentialPrivacyFence,
    private val chainedComposition: ChainedComposition? = null,
    private val plusOwner: NativePlusEntitlementOwner? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val dnsLeakCanaryOwner: com.lavasec.app.data.CredentialDnsLeakCanaryOwner? = null,
    private val dnsFallbackHistory: com.lavasec.app.diagnostics.CredentialDnsFallbackHistoryStore? = null,
) : CredentialVpnHostPort, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val attachments = CopyOnWriteArraySet<ProductionCredentialVpnAttachment>()

    override fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment? {
        if (closed.get()) return null
        val snapshot = readCoherentRuntimeSnapshot() ?: return null
        val attachment = ProductionCredentialVpnAttachment(
            request = request,
            graphGeneration = graphGeneration,
            snapshot = snapshot,
            configurationRepository = configurationRepository,
            filterStore = filterStore,
            activityWriter = activityWriter,
            privacyFence = privacyFence,
            chainedComposition = chainedComposition,
            plusOwner = plusOwner,
            dnsLeakCanaryOwner = dnsLeakCanaryOwner,
            dnsFallbackHistory = dnsFallbackHistory,
            protectionActivitySink = CredentialProtectionActivitySink(
                writer = activityWriter,
                privacyFence = privacyFence,
            ),
            configureIntent = configureIntent(),
            nowMillis = nowMillis,
            onDetach = attachments::remove,
        )
        attachments += attachment
        if (closed.get()) {
            attachment.detach()
            return null
        }
        return attachment
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        attachments.toList().forEach(ProductionCredentialVpnAttachment::detach)
        attachments.clear()
    }

    private fun readCoherentRuntimeSnapshot(): RuntimeCredentialSnapshot? = runBlocking(Dispatchers.IO) {
        val state = configurationRepository.state.first()
        val configuration = state.configuration ?: return@runBlocking null
        val identity = state.configurationIdentity ?: return@runBlocking null
        if (!state.onboardingCompleted || !state.protectionIntent || state.protectionSessionId <= 0L) {
            return@runBlocking null
        }
        val filter = (filterStore.readAuthoritative() as? CredentialFilterConfigurationRead.Available)
            ?.state ?: return@runBlocking null
        if (filter.configurationIdentity != identity) return@runBlocking null
        val plan = plusOwner?.state?.value
        if (plan != null && !plan.available) return@runBlocking null
        val limits = plan?.limits ?: FeatureLimits.FREE
        if (filter.verifiedArtifact.artifactIdentity.ruleCounts.total > limits.maxFilterRules) return@runBlocking null
        RuntimeCredentialSnapshot(
            configurationIdentity = identity,
            resolverConfiguration = configuration.resolver,
            protectionSessionId = state.protectionSessionId,
        )
    }

    private fun configureIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        CONFIGURE_INTENT_REQUEST_CODE,
        Intent(context, com.lavasec.app.reactnative.LavaReactActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CONFIGURE_INTENT_REQUEST_CODE = 1_001
    }
}

private data class RuntimeCredentialSnapshot(
    val configurationIdentity: com.lavasec.app.platform.ConfigurationIdentity,
    val resolverConfiguration: com.lavasec.app.foundation.resolver.ResolverConfiguration,
    val protectionSessionId: Long,
)

private class ProductionCredentialVpnAttachment(
    private val request: CredentialVpnAttachRequest,
    override val graphGeneration: Long,
    private val snapshot: RuntimeCredentialSnapshot,
    private val configurationRepository: CredentialConfigurationRepository,
    private val filterStore: CredentialFilterConfigurationStore,
    private val activityWriter: CredentialActivityWriter,
    private val privacyFence: CredentialPrivacyFence,
    private val protectionActivitySink: CredentialProtectionActivitySink,
    private val chainedComposition: ChainedComposition?,
    private val plusOwner: NativePlusEntitlementOwner?,
    private val dnsLeakCanaryOwner: com.lavasec.app.data.CredentialDnsLeakCanaryOwner?,
    private val dnsFallbackHistory: com.lavasec.app.diagnostics.CredentialDnsFallbackHistoryStore?,
    override val configureIntent: PendingIntent,
    private val nowMillis: () -> Long,
    private val onDetach: (ProductionCredentialVpnAttachment) -> Unit,
) : CredentialVpnRuntimeAttachment {
    private val detached = AtomicBoolean(false)

    // Detachment and final send share this short lock. The owner lock separately serializes
    // durable Arm/Disarm with the nonblocking syscall; neither lock spans socket preparation.
    private val canaryLock = java.util.concurrent.locks.ReentrantLock()
    override val dnsLeakCanaryChannel: com.lavasec.app.vpn.qa.DnsLeakCanaryChannel? =
        dnsLeakCanaryOwner?.takeIf { BuildConfig.DEBUG }?.let { owner ->
            object : com.lavasec.app.vpn.qa.DnsLeakCanaryChannel {
                override fun captureArm() = canaryLock.withLock {
                    if (detached.get()) null else owner.captureArm()
                }
                override fun sendIfCurrent(capturedArm: com.lavasec.app.vpn.qa.DnsLeakCanaryArm,
                    isCurrent: () -> Boolean, send: () -> com.lavasec.app.vpn.qa.DnsLeakCanarySend) =
                    canaryLock.withLock {
                        owner.sendIfCurrent(capturedArm, { !detached.get() && isCurrent() }, send)
                    }
            }
        }

    override val configurationIdentity get() = snapshot.configurationIdentity
    override val resolverConfiguration get() = snapshot.resolverConfiguration
    override val activeProtectionSessionId get() = snapshot.protectionSessionId

    /**
     * Opens the credential-protected chained material, or `null` for DNS-only.
     *
     * This is where the composition root's gate is consulted: [ChainedComposition.resolveActivation]
     * reads the committed configuration, the user preference, the live authority, and only then
     * the key half, returning `null` for any refusal so the runtime stays on the unchanged
     * DNS-only path. Because that read is slow, detachment is rechecked after it (via
     * [resolveChainedHandoff]): a graph rotation that detached this attachment mid-read closes the
     * resolved secret half and refuses the handoff instead of serving a retired generation. The
     * lifecycle adapter is narrowed to the two session-witness writes so the runtime never gains
     * access to the memory facts or the exclusion.
     */
    override fun openChainedCredentials(): ChainedVpnCredentials? {
        if (detached.get()) return null
        val composition = chainedComposition ?: return null
        return runBlocking(Dispatchers.IO) {
            val open: suspend () -> ChainedVpnCredentials? = opening@ {
                // The resolve performs slow DataStore/Keystore reads, so a graph rotation can detach
                // this attachment after the pre-resolve check above. `resolveChainedHandoff` rechecks
                // detachment after the read and closes the secret half rather than handing a retired
                // generation's material to the engine.
                val resolved = resolveChainedHandoff(
                    isDetached = { detached.get() },
                    resolve = { composition.resolveActivation() },
                ) ?: return@opening null
                val peerPublicKey = ChainedUpstreamConfigurationValidator
                    .decodedKey(resolved.configuration.peerPublicKey)
                    ?: run {
                        resolved.secrets.close()
                        return@opening null
                    }
                // createFromMaterial transfers the copies into the credentials and zeroizes every
                // intermediate — including the store's own arrays — so no secret lingers past transfer.
                ChainedVpnCredentials.createFromMaterial(
                    configuration = resolved.configuration,
                    material = resolved.secrets,
                    dnsFallbackEnabled = resolved.dnsFallbackEnabled,
                    peerPublicKey = peerPublicKey,
                    availability = composition.availability,
                    lifecycle = object : ChainedSessionEligibilityLifecycle {
                        override fun markChainedSessionStarted(nowEpochMillis: Long): Boolean =
                            composition.eligibilityStore.markChainedSessionStarted(nowEpochMillis) is
                                ChainedDeviceEligibilityRead.Available

                        override fun markChainedSessionStoppedCleanly(
                            nowEpochMillis: Long,
                            owningStartEpochMillis: Long,
                        ) {
                            composition.eligibilityStore.markChainedSessionStoppedCleanly(
                                nowEpochMillis,
                                owningStartEpochMillis,
                            )
                        }
                    },
                )
            }
            // The plan cannot lapse between eligibility and private-key handoff. Like a
            // filter lease, the accepted resident session remains valid after a later lapse.
            if (plusOwner == null) open() else plusOwner.withAvailableLimits { open() }
        }
    }

    override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean {
        if (
            detached.get() ||
            telemetry.serviceSessionGeneration != request.serviceSessionGeneration ||
            telemetry.graphGeneration != graphGeneration
        ) {
            return false
        }
        return privacyFence.tryTelemetryWrite {
            val startedAt = telemetry.activity?.timestampMillis
            if (startedAt != null) telemetry.resolverQueryEvidence?.let { evidence ->
                dnsFallbackHistory?.record(telemetry.serviceSessionGeneration, telemetry.engineGeneration, startedAt, evidence)
            }
            telemetry.activity?.let(activityWriter::recordRuntime)
        }
    }

    override fun acceptProtectionEvidence(evidence: VpnProtectionEvidence): Boolean {
        if (
            detached.get() ||
            evidence.serviceSessionGeneration != request.serviceSessionGeneration ||
            evidence.graphGeneration != graphGeneration
        ) {
            return false
        }
        return protectionActivitySink.accept(evidence)
    }

    override fun openFilterRuntimeLease(): VerifiedFilterRuntimeLease = runBlocking(Dispatchers.IO) {
        com.lavasec.app.billing.openPlanCheckedFilterLease(
            plan = plusOwner,
            expectedIdentity = configurationIdentity,
            isDetached = detached::get,
            read = filterStore::readAuthoritativeRuntime,
        )
    }

    override fun currentPause(): RuntimePauseDirective {
        check(!detached.get()) { "credential VPN attachment is detached" }
        val state = currentState()
        val restored = PausePolicy.restore(
            persistedDeadlineMillis = state.pauseDeadlineEpochMillis,
            persistedSessionId = state.pauseSessionId,
            activeSessionId = activeProtectionSessionId,
            nowMillis = nowMillis(),
        )
        return restored?.let(RuntimePauseDirective::Paused) ?: RuntimePauseDirective.Filtering
    }

    override fun requestPause(duration: PauseDuration): PauseState? {
        if (detached.get()) return null
        val current = currentState()
        if (
            current.protectionSessionId != activeProtectionSessionId ||
            !current.protectionIntent
        ) {
            return null
        }
        val replacement = PausePolicy.start(nowMillis(), duration, activeProtectionSessionId)
        return when (
            val result = runBlocking(Dispatchers.IO) {
                configurationRepository.compareAndSetPause(
                    expectedProtectionSessionId = activeProtectionSessionId,
                    expectedPause = current.pause,
                    replacement = replacement,
                )
            }
        ) {
            is PauseMutationResult.Applied -> result.authoritative.pause
            is PauseMutationResult.Rejected -> null
        }
    }

    override fun requestResume(expectedPause: PauseState?): Boolean {
        if (detached.get()) return false
        val current = currentState()
        if (
            current.protectionSessionId != activeProtectionSessionId ||
            !current.protectionIntent
        ) {
            return false
        }
        if (expectedPause != null && current.pause != expectedPause) return false
        return runBlocking(Dispatchers.IO) {
            configurationRepository.compareAndSetPause(
                expectedProtectionSessionId = activeProtectionSessionId,
                expectedPause = current.pause,
                replacement = null,
            )
        } is PauseMutationResult.Applied
    }

    override fun detach() {
        val detach = canaryLock.withLock { detached.compareAndSet(false, true) }
        if (detach) onDetach(this)
    }

    private fun currentState(): PersistedAppState = runBlocking(Dispatchers.IO) {
        configurationRepository.state.first()
    }
}

/** Failed retirement keeps the durable artifact but withholds incompatible mutation hosts. */
internal data class CatalogMigrationReconciliation(
    val confirmed: ConfirmedFilterState?,
    val mutationsAvailable: Boolean,
)

internal suspend fun reconcileCatalogMigration(
    initial: ConfirmedFilterState,
    migrate: suspend (ConfirmedFilterState) -> Boolean,
    readConfirmed: suspend () -> ConfirmedFilterState?,
): CatalogMigrationReconciliation {
    val migrated = try {
        migrate(initial)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    // A failed transaction may retain a coherent prior artifact, or may require reconciliation.
    // Re-read authority in both cases. Only the former may keep ordinary protection available.
    val confirmed = readConfirmed()
    return CatalogMigrationReconciliation(confirmed, migrated && confirmed != null)
}

private suspend fun coherentFilterState(
    persisted: PersistedAppState,
    catalog: BundledBlocklistCatalog,
    store: CredentialFilterConfigurationStore,
): ConfirmedFilterState? {
    val configuration = persisted.configuration ?: return null
    val identity = persisted.configurationIdentity ?: return null
    val confirmed = (store.readAuthoritative() as? CredentialFilterConfigurationRead.Available)
        ?.state ?: return null
    if (!BuildConfig.DEBUG && confirmed.configuration.qaProbeSet != null) return null
    // The record is the library's sole durable truth, and ConfirmedFilterState's construction
    // already derived the fingerprint from the complete configuration (validated against the
    // artifact identity), so coherence is the exact pointer agreement: same identity, the
    // persisted active filter id, and the persisted resolver authority. The seeded derivation
    // only ever applied to the pre-library three-profile model; arbitrary saved filters are
    // validated by the record itself.
    val resolverPlan = FilterResolverPlan.from(configuration.resolver) ?: return null
    return confirmed.takeIf {
        it.configurationIdentity == identity &&
            it.configuration.activeProfileId == configuration.activeFilterId &&
            it.configuration.resolverTarget == resolverPlan
    }
}

internal data class VerifiedDeviceMirror(
    val state: DirectBootAppState,
    val artifact: FilterArtifactReference,
)

internal suspend fun requiresFreshDeviceMirrorPublication(
    persisted: PersistedAppState,
    credentialArtifact: FilterArtifactReference,
    readVerifiedDeviceMirror: suspend () -> VerifiedDeviceMirror?,
): Boolean {
    val mirror = readVerifiedDeviceMirror() ?: return true
    return !mirror.state.matches(persisted) || mirror.artifact != credentialArtifact
}

private fun DirectBootAppState?.matches(persisted: PersistedAppState): Boolean {
    val device = this ?: return false
    val configuration = persisted.configuration ?: return false
    return device.onboardingCompleted == persisted.onboardingCompleted &&
        device.protectionIntent == persisted.protectionIntent &&
        device.useDeviceDns == configuration.resolver.useDeviceDns &&
        device.useEncryptedFallback == configuration.resolver.useEncryptedFallback &&
        device.encryptedFallbackId == configuration.resolver.encryptedFallbackId &&
        device.customDohUrl == configuration.resolver.customDohUrl &&
        device.customDotEndpoint == configuration.resolver.customDotEndpoint &&
        device.customDoqEndpoint == configuration.resolver.customDoqEndpoint &&
        device.customName == configuration.resolver.customName &&
        device.customSecondaryEndpoint == configuration.resolver.customSecondaryEndpoint &&
        device.savedTiers == configuration.resolver.savedTiers &&
        device.activeProfileId == persisted.activeFilter.id &&
        device.configurationIdentity == persisted.configurationIdentity &&
        !device.credentialUnlockRequired
}
