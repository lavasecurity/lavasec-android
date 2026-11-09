package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.health.ProtectionHealthContext
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalDiagnosticsSnapshotV1Test {
    @Test
    fun `V1 has one exact domain-free snapshot schema`() {
        val instanceFields = OperationalDiagnosticsSnapshotV1::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapTo(linkedSetOf()) { it.name }

        assertEquals(
            linkedSetOf(
                "schemaVersion",
                "credentialOwnerGeneration",
                "healthSessionGeneration",
                "healthNetworkGeneration",
                "healthEngineGeneration",
                "healthObservationSequence",
                "sampledWallClockMillis",
                "sampledElapsedRealtimeMillis",
                "processGeneration",
                "runtimeGeneration",
                "serviceState",
                "tunnelState",
                "androidTransport",
                "androidPathState",
                "isConnected",
                "networkAvailable",
                "consecutiveResolverFailures",
                "consecutiveResolverRejections",
                "recentResponseLatenciesMillis",
                "activeFallback",
                "requiresReconnect",
                "recentResolverQueryIds",
                "resolverQueryIdHighWatermark",
            ),
            instanceFields,
        )
        assertEquals(1, OperationalDiagnosticsSnapshotV1.SCHEMA_VERSION)
        assertEquals(1, OperationalDiagnosticsSnapshotV1.MAX_SNAPSHOTS)

        val normalizedNames = instanceFields.map { it.lowercase() }
        listOf(
            "domain",
            "queryname",
            "answer",
            "destinationip",
            "packetpayload",
            "contactidentifier",
            "freetext",
            "cachehit",
            "cachemiss",
            "coalesced",
            "row",
        ).forEach { forbidden ->
            assertFalse("$forbidden leaked into V1", normalizedNames.any { forbidden in it })
        }
    }

    @Test
    fun `V1 projection carries exact bounded health evidence`() {
        val health = ProtectionHealthAccumulator(
            isConnected = true,
            networkAvailable = false,
            consecutiveResolverFailures = 3,
            consecutivePrimaryRejections = 2,
            recentResponseLatenciesMillis = listOf(11L, 29L, 47L),
            activeFallback = ProtectionFallback.ENCRYPTED_DNS,
            requiresReconnect = true,
            context = ProtectionHealthContext(
                sessionGeneration = 7L,
                networkGeneration = 8L,
                engineGeneration = 9L,
                observationSequence = 10L,
                observedAtMillis = 9_500L,
                primaryResolverIdentity = PrimaryResolverIdentity("runtime-only"),
                fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
            ),
            recentResolverQueryIds = listOf(40L, 41L, 42L),
            resolverQueryIdHighWatermark = 42L,
        )

        val snapshot = OperationalDiagnosticsSnapshotV1.fromHealth(
            credentialOwnerGeneration = 4L,
            sampledWallClockMillis = 10_000L,
            sampledElapsedRealtimeMillis = 5_000L,
            processGeneration = 2L,
            runtimeGeneration = 3L,
            serviceState = OperationalServiceState.RUNNING,
            tunnelState = OperationalTunnelState.CONNECTED,
            androidTransport = OperationalAndroidTransport.WIFI,
            androidPathState = OperationalAndroidPathState.VALIDATED,
            health = health,
        )

        assertEquals(7L, snapshot.healthSessionGeneration)
        assertEquals(8L, snapshot.healthNetworkGeneration)
        assertEquals(9L, snapshot.healthEngineGeneration)
        assertEquals(10L, snapshot.healthObservationSequence)
        assertEquals(listOf(11L, 29L, 47L), snapshot.recentResponseLatenciesMillis)
        assertEquals(listOf(40L, 41L, 42L), snapshot.recentResolverQueryIds)
        assertEquals(42L, snapshot.resolverQueryIdHighWatermark)
        assertEquals(3, snapshot.consecutiveResolverFailures)
        assertEquals(2, snapshot.consecutiveResolverRejections)
        assertTrue(snapshot.requiresReconnect)
        assertFalse(snapshot.toString().contains("runtime-only"))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.recentResponseLatenciesMillis as MutableList<Long>) += 99L
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.recentResolverQueryIds as MutableList<Long>) += 43L
        }
    }

    @Test
    fun `V1 constructor owns unmodifiable evidence copies`() {
        val sourceLatencies = mutableListOf(10L)
        val sourceQueryIds = mutableListOf(11L)
        val snapshot = snapshot(
            recentResponseLatenciesMillis = sourceLatencies,
            recentResolverQueryIds = sourceQueryIds,
        )

        sourceLatencies += 12L
        sourceQueryIds += 10L
        assertEquals(listOf(10L), snapshot.recentResponseLatenciesMillis)
        assertEquals(listOf(11L), snapshot.recentResolverQueryIds)
        assertEquals(11L, snapshot.resolverQueryIdHighWatermark)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.recentResponseLatenciesMillis as MutableList<Long>) += 13L
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.recentResolverQueryIds as MutableList<Long>) += 12L
        }
        assertEquals(listOf(10L), snapshot.recentResponseLatenciesMillis)
        assertEquals(listOf(11L), snapshot.recentResolverQueryIds)
    }

    @Test
    fun `V1 copy canonicalizes evidence while preserving value behavior`() {
        val original = snapshot()
        val sourceLatencies = mutableListOf(20L)
        val sourceQueryIds = mutableListOf(21L)
        val copied = original.copy(
            recentResponseLatenciesMillis = sourceLatencies,
            recentResolverQueryIds = sourceQueryIds,
            resolverQueryIdHighWatermark = 21L,
        )

        sourceLatencies += 22L
        sourceQueryIds += 22L
        assertEquals(listOf(20L), copied.recentResponseLatenciesMillis)
        assertEquals(listOf(21L), copied.recentResolverQueryIds)
        assertEquals(original, original.copy())
        assertEquals(original.hashCode(), original.copy().hashCode())
        assertEquals(original.toString(), original.copy().toString())
        assertFalse(original == copied)
    }

    @Test
    fun `V1 rejects absent context and evidence beyond reducer bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationalDiagnosticsSnapshotV1.fromHealth(
                credentialOwnerGeneration = 1L,
                sampledWallClockMillis = 1L,
                sampledElapsedRealtimeMillis = 1L,
                processGeneration = 1L,
                runtimeGeneration = 1L,
                serviceState = OperationalServiceState.STOPPED,
                tunnelState = OperationalTunnelState.DISCONNECTED,
                androidTransport = OperationalAndroidTransport.UNKNOWN,
                androidPathState = OperationalAndroidPathState.UNKNOWN,
                health = ProtectionHealthAccumulator(),
            )
        }

        val valid = snapshot()
        val exactLatencyBound = valid.copy(
            recentResponseLatenciesMillis = List(
                ProtectionHealthAccumulator.MAX_RECENT_RESPONSE_LATENCIES,
            ) { it.toLong() },
        )
        val exactQueryBound = valid.copy(
            recentResolverQueryIds = (
                0L until ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS.toLong()
                ).toList(),
            resolverQueryIdHighWatermark =
                ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS.toLong() - 1L,
        )
        assertEquals(
            ProtectionHealthAccumulator.MAX_RECENT_RESPONSE_LATENCIES,
            exactLatencyBound.recentResponseLatenciesMillis.size,
        )
        assertEquals(
            ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS,
            exactQueryBound.recentResolverQueryIds.size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(
                recentResponseLatenciesMillis = List(
                    ProtectionHealthAccumulator.MAX_RECENT_RESPONSE_LATENCIES + 1,
                ) { 1L },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(
                recentResolverQueryIds = (0L..ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS.toLong())
                    .toList(),
                resolverQueryIdHighWatermark =
                    ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS.toLong(),
            )
        }
    }

    private fun snapshot(
        recentResponseLatenciesMillis: List<Long> = listOf(10L),
        recentResolverQueryIds: List<Long> = listOf(11L),
        resolverQueryIdHighWatermark: Long? = 11L,
    ) = OperationalDiagnosticsSnapshotV1(
        credentialOwnerGeneration = 1L,
        healthSessionGeneration = 2L,
        healthNetworkGeneration = 3L,
        healthEngineGeneration = 4L,
        healthObservationSequence = 5L,
        sampledWallClockMillis = 6L,
        sampledElapsedRealtimeMillis = 7L,
        processGeneration = 8L,
        runtimeGeneration = 9L,
        serviceState = OperationalServiceState.RUNNING,
        tunnelState = OperationalTunnelState.CONNECTED,
        androidTransport = OperationalAndroidTransport.WIFI,
        androidPathState = OperationalAndroidPathState.VALIDATED,
        isConnected = true,
        networkAvailable = true,
        consecutiveResolverFailures = 0,
        consecutiveResolverRejections = 0,
        recentResponseLatenciesMillis = recentResponseLatenciesMillis,
        activeFallback = ProtectionFallback.NONE,
        requiresReconnect = false,
        recentResolverQueryIds = recentResolverQueryIds,
        resolverQueryIdHighWatermark = resolverQueryIdHighWatermark,
    )
}
