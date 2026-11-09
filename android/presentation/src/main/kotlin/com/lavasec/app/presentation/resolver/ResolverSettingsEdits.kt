package com.lavasec.app.presentation.resolver

import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverProvider
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier

/** The row edited by the grouped provider/transport controls; all other rows remain retained. */
internal fun ResolverSelection.settingsProviderIndex(): Int = if (primary.providerId == ResolverProviderId.DEVICE_DNS) {
    tiers.indexOfFirst { it.entry.providerId != ResolverProviderId.DEVICE_DNS }.takeIf { it >= 0 } ?: tiers.size
} else tiers.indexOfFirst { it.entry == primary }

internal fun ResolverSelection.withSettingsProvider(provider: ResolverProvider,
    transport: CatalogResolverTransport? = null): ResolverSelection? {
    val index = settingsProviderIndex()
    val current = tiers.getOrNull(index)
    if (provider.id == ResolverProviderId.CUSTOM) return if (current?.entry?.providerId == provider.id &&
        (transport == null || transport == current.entry.transport)) this else null
    val served = ResolverCatalog.transportRows(provider).filter { it.second.available }.map { it.first }
    val requested = transport ?: current?.entry?.transport?.takeIf { it in served }
        ?: CatalogResolverTransport.DOH.takeIf { it in served } ?: served.firstOrNull() ?: return null
    if (requested !in served) return null
    if (current?.entry?.providerId == provider.id && current.entry.transport == requested) return this
    val replacement = ResolverTier(ResolverSelectionEntry(provider.id, requested), current?.isEnabled ?: true)
    return runCatching { ResolverSelection(tiers.toMutableList().apply {
        if (index == size) add(replacement) else this[index] = replacement
    }) }.getOrNull()
}

internal fun ResolverSelection.withSettingsDevicePrimary(enabled: Boolean): ResolverSelection? {
    if (enabled == (primary.providerId == ResolverProviderId.DEVICE_DNS)) return this
    val rows = tiers.toMutableList()
    val deviceIndex = rows.indexOfFirst { it.entry.providerId == ResolverProviderId.DEVICE_DNS }
    if (enabled) {
        if (deviceIndex >= 0) {
            val device = rows.removeAt(deviceIndex).copy(isEnabled = true)
            rows.add(0, device)
        } else {
            if (rows.size == 2) return null // The grouped switch cannot discard a retained row.
            rows.add(0, ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN))
        }
    } else {
        rows[deviceIndex] = rows[deviceIndex].copy(isEnabled = false)
        if (rows.none { it.isEnabled }) {
            if (rows.size == 2) {
                val otherIndex = if (deviceIndex == 0) 1 else 0
                rows[otherIndex] = rows[otherIndex].copy(isEnabled = true)
            }
            else rows.add(ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED,
                CatalogResolverTransport.DOH)))
        }
    }
    return runCatching { ResolverSelection(rows) }.getOrNull()
}

internal fun ResolverSelection.withSettingsFallback(enabled: Boolean): ResolverSelection? {
    val rows = tiers.toMutableList()
    if (rows.size == 2) {
        val index = if (enabled) rows.indexOfFirst { !it.isEnabled }.takeIf { it >= 0 } ?: 1 else 1
        rows[index] = rows[index].copy(isEnabled = enabled)
    }
    else if (enabled) rows.add(ResolverTier(if (primary.providerId == ResolverProviderId.DEVICE_DNS)
        ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED, CatalogResolverTransport.DOH)
        else ResolverSelectionPresets.DEVICE_PLAIN))
    return runCatching { ResolverSelection(rows) }.getOrNull()
}
