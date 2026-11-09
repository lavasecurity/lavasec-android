package com.lavasec.app.data

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.FilterCompileException
import com.lavasec.app.foundation.filter.FilterCompileError
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.CredentialFilterArtifactStagingResult
import com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier
import com.lavasec.app.storage.CredentialFilterConfigurationInitializationResult
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import com.lavasec.app.storage.CredentialFilterConfigurationUnavailableReason
import kotlinx.coroutines.CancellationException

internal sealed interface InitialFilterProvisionResult {
    val state: ConfirmedFilterState?

    data class Initialized(override val state: ConfirmedFilterState) :
        InitialFilterProvisionResult

    data class Existing(override val state: ConfirmedFilterState) :
        InitialFilterProvisionResult

    data class Unavailable(
        val reason: Reason,
        override val state: ConfirmedFilterState? = null,
        /** Classified operational facts only; never retain response bodies, URLs, or exceptions. */
        val sourceFailure: SeededSourceCacheFailure? = null,
        val compileFailure: FilterCompileError? = null,
    ) : InitialFilterProvisionResult

    enum class Reason {
        AUTHORITY_INCOHERENT,
        SOURCE_OR_COMPILE_FAILED,
        ARTIFACT_VERIFICATION_FAILED,
        PUBLIC_STAGING_FAILED,
        AUTHORITY_COMMIT_FAILED,
    }
}

/** Initializes one exact signed-catalog filter authority and inert public artifact generation. */
internal class InitialFilterProvisioner(
    private val catalog: BundledBlocklistCatalog,
    private val configurationStore: CredentialFilterConfigurationStore,
    private val requestFactory: CatalogFilterCompileRequestFactory,
    private val compile: suspend (FilterCompileRequest) -> PreparedFilterArtifact,
    private val stagePublicArtifact: (PreparedFilterArtifact) -> Boolean,
    private val historicalCatalogs: (() -> List<BundledBlocklistCatalog>)? = null,
) {
    /** Rebase only when the persisted fingerprint proves the exact historical seed. */
    private fun seedForIdentity(
        configuration: AppConfiguration,
        identity: ConfigurationIdentity,
    ): SeededFilterConfiguration? {
        val current = SeededFilterConfiguration.from(configuration, catalog, identity.generation)
        if (current.identity == identity) return current
        val historical = historicalCatalogs?.invoke().orEmpty()
            .map { previous -> SeededFilterConfiguration.from(configuration, previous, identity.generation) }
            .firstOrNull { it.identity == identity } ?: return null
        // Retain the proven saved selections even if onboarding policy changed. Only explicit
        // retired IDs are removed; compile this reconstructed library against today's catalog.
        val recovered = historical.draft.withRetiredCatalogSelectionsRemoved()
        return SeededFilterConfiguration(
            draft = recovered,
            identity = ConfigurationIdentity(
                generation = Math.addExact(identity.generation, 1L),
                fingerprint = canonicalFilterConfigurationFingerprint(recovered, catalog.revision, catalog.sha256),
            ),
        )
    }

    suspend fun provision(
        configuration: AppConfiguration,
        identity: ConfigurationIdentity,
        refreshSources: Boolean = false,
    ): InitialFilterProvisionResult {
        val seeded = try {
            seedForIdentity(configuration, identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return InitialFilterProvisionResult.Unavailable(
            InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT,
        )

        when (val existing = configurationStore.readAuthoritative()) {
            is CredentialFilterConfigurationRead.Available -> {
                return if (
                    existing.state.configuration == seeded.draft &&
                    existing.state.configurationIdentity == seeded.identity
                ) {
                    InitialFilterProvisionResult.Existing(existing.state)
                } else {
                    InitialFilterProvisionResult.Unavailable(
                        reason = InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT,
                        state = existing.state,
                    )
                }
            }
            is CredentialFilterConfigurationRead.Unavailable -> if (
                existing.reason != CredentialFilterConfigurationUnavailableReason.MISSING
            ) {
                return InitialFilterProvisionResult.Unavailable(
                    InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT,
                )
            }
        }

        val prepared = try {
            val request = requestFactory.create(seeded.draft, seeded.identity, refreshSources)
            compile(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SeededSourceCacheException) {
            return InitialFilterProvisionResult.Unavailable(
                InitialFilterProvisionResult.Reason.SOURCE_OR_COMPILE_FAILED,
                sourceFailure = failure.reason,
            )
        } catch (failure: FilterCompileException) {
            return InitialFilterProvisionResult.Unavailable(
                InitialFilterProvisionResult.Reason.SOURCE_OR_COMPILE_FAILED,
                compileFailure = failure.reason,
            )
        } catch (_: Exception) {
            return InitialFilterProvisionResult.Unavailable(
                InitialFilterProvisionResult.Reason.SOURCE_OR_COMPILE_FAILED,
            )
        }

        prepared.use {
            val staged = CredentialFilterArtifactStagingVerifier.verify(
                prepared.file,
                prepared.compilationReceipt,
            )
            if (staged !is CredentialFilterArtifactStagingResult.Verified) {
                return InitialFilterProvisionResult.Unavailable(
                    InitialFilterProvisionResult.Reason.ARTIFACT_VERIFICATION_FAILED,
                )
            }
            staged.use {
                val confirmed = ConfirmedFilterState(
                    configuration = seeded.draft,
                    configurationIdentity = seeded.identity,
                    credentialUnlockRequired = false,
                    verifiedArtifact = staged.verifiedArtifact,
                )
                if (!stagePublicArtifact(prepared)) {
                    return InitialFilterProvisionResult.Unavailable(
                        InitialFilterProvisionResult.Reason.PUBLIC_STAGING_FAILED,
                    )
                }
                return when (val initialized = configurationStore.initialize(confirmed, prepared)) {
                    is CredentialFilterConfigurationInitializationResult.Initialized ->
                        InitialFilterProvisionResult.Initialized(initialized.state)
                    is CredentialFilterConfigurationInitializationResult.Existing ->
                        if (
                            initialized.state.configuration == seeded.draft &&
                            initialized.state.configurationIdentity == seeded.identity
                        ) {
                            InitialFilterProvisionResult.Existing(initialized.state)
                        } else {
                            InitialFilterProvisionResult.Unavailable(
                                reason = InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT,
                                state = initialized.state,
                            )
                        }
                    is CredentialFilterConfigurationInitializationResult.Failed ->
                        InitialFilterProvisionResult.Unavailable(
                            InitialFilterProvisionResult.Reason.AUTHORITY_COMMIT_FAILED,
                        )
                }
            }
        }
    }
}
