package com.lavasec.app.product

import com.lavasec.app.billing.AndroidPlusPlanPersistence
import com.lavasec.app.billing.ChainedDnsPreferenceReading
import com.lavasec.app.data.CredentialChainedUpstreamConfigurationRepository
import com.lavasec.app.foundation.chained.ChainedRoutingPolicy
import com.lavasec.app.foundation.resolver.ChainedDnsEditState
import com.lavasec.app.platform.FilterMutationFailure
import com.lavasec.app.platform.FilterMutationPublishResult
import com.lavasec.app.platform.NativeFilterPublicationGuard
import com.lavasec.app.platform.StoredChainedUpstreamConfiguration
import kotlinx.coroutines.CancellationException

/**
 * No state/cache of its own. The final lock order is Plus owner -> persisted preferences ->
 * chained repository -> filter store. Source acquisition and compilation occur outside this scope.
 */
internal class ChainedQaDnsPublicationAuthority(
    private val preferences: AndroidPlusPlanPersistence,
    private val repository: CredentialChainedUpstreamConfigurationRepository,
) {
    private data class Basis(val preferences: ChainedDnsPreferenceReading, val stored: StoredChainedUpstreamConfiguration?) {
        val gate: ChainedDnsEditState get() = ChainedDnsEditState(
            chainingEnabled = preferences.enabled && stored?.pendingImportId == null && stored?.configuration?.activeConfiguration != null,
            fallbackPreference = preferences.fallback,
            storedIsSplitTunnel = stored?.configuration?.activeConfiguration?.effectiveRoutingPolicy?.let { it == ChainedRoutingPolicy.SPLIT_TUNNEL },
        )
    }

    suspend fun capture(): QaDnsPublicationAdmission? = try {
        val basis = preferences.withChainedDnsReading { reading ->
            repository.withChainedDnsMetadata { Basis(reading, it) }
        }
        QaDnsPublicationAdmission(basis.gate, NativeFilterPublicationGuard { publication ->
            try {
                preferences.withChainedDnsReading { reading ->
                    repository.withChainedDnsMetadata { stored ->
                        val current = Basis(reading, stored)
                        if (current != basis || !current.gate.canEditDns) failed() else publication()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { FilterMutationPublishResult.ProtectionUnavailable }
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun failed() = FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
}
