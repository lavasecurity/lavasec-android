package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.filter.canonicalSha256

/**
 * Canonical SHA-256 fingerprint of the non-secret chained-upstream configuration.
 *
 * This is the chained analogue of [canonicalFilterConfigurationFingerprint]: it commits every
 * persisted behavior-bearing field, in a fixed order, so two records that differ in any of them have
 * different identities and a record rewritten byte-for-byte has the same identity. It deliberately
 * does NOT cover the private or pre-shared key: those live in the secret half and a key-only
 * rotation is a new generation with an unchanged configuration fingerprint.
 *
 * The endpoint host is committed in its validated ASCII (A-label) wire form, so the same
 * internationalized host cannot produce two fingerprints through two Unicode spellings.
 *
 * Mirrors iOS `ChainedUpstreamConfiguration`, which stores the whole non-secret value in one
 * envelope and relies on value equality; Android persists fields individually, so the identity needs
 * an explicit canonical commitment.
 */
fun canonicalChainedUpstreamConfigurationFingerprint(
    configuration: ChainedUpstreamConfiguration,
): String = if (configuration.precedingHops.isNotEmpty() || configuration.displayName.isNotEmpty() || !configuration.isEnabled) {
    canonicalSha256("chained-upstream-configuration-v2") {
        writeInt(configuration.orderedHops.size)
        configuration.orderedHops.forEach { row ->
            writeString(canonicalChainedUpstreamConfigurationFingerprint(row.withoutEntryHop().named("").enabled(true)))
            writeString(row.displayName)
            writeInt(if (row.isEnabled) 1 else 0)
        }
    }
} else canonicalSha256("chained-upstream-configuration-v1") {
    writeString(configuration.endpointHostWireForm)
    writeInt(configuration.endpointPort)
    writeString(configuration.peerPublicKey)
    writeString(configuration.clientAddress)
    writeInt(configuration.allowedIPs.size)
    configuration.allowedIPs.forEach(::writeString)
    writeInt(configuration.persistentKeepaliveSeconds)
    // -1 is outside the validated MTU range (>= 1280 once present), so absent is unambiguous.
    writeInt(configuration.interfaceMTU ?: -1)
    writeInt(configuration.dnsAddresses.size)
    configuration.dnsAddresses.forEach(::writeString)
}
