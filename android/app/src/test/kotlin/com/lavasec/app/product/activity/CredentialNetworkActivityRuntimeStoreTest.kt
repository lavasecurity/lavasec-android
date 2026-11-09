package com.lavasec.app.product.activity

import com.lavasec.app.foundation.activity.NetworkActivityCapturedState
import com.lavasec.app.foundation.activity.NetworkActivityConnectivityState
import com.lavasec.app.foundation.activity.NetworkActivityEvent
import com.lavasec.app.foundation.activity.NetworkActivityNetworkKind
import com.lavasec.app.foundation.activity.NetworkActivityProtectionState
import com.lavasec.app.foundation.activity.NetworkActivityResolverIdentity
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialNetworkActivityRuntimeStoreTest {
    @Test
    fun `frozen pages remain stable while live runtime records newer rows`() {
        var now = 20_000_000L
        val store = CredentialNetworkActivityRuntimeStore(nowMillis = { now })
        repeat(65) { index ->
            store.record(
                event = NetworkActivityEvent.NetworkChanged(
                    from = NetworkActivityNetworkKind.UNKNOWN,
                    to = NetworkActivityNetworkKind.WIFI,
                    pathSatisfied = index % 2 == 0,
                ),
                capturedState = state(),
                occurredAtMillis = now - index * 180_000L,
            )
        }

        val first = store.loadPage(NetworkActivityPageRequest())
        assertEquals(30, first.value.rows.size)
        val token = checkNotNull(first.value.next)
        val frozenRevision = first.revision

        now += 1L
        store.record(NetworkActivityEvent.ProtectionConnected, state(), now)
        val second = store.loadPage(NetworkActivityPageRequest(token = token))
        val third = store.loadPage(
            NetworkActivityPageRequest(token = checkNotNull(second.value.next)),
        )

        assertEquals(frozenRevision, second.revision)
        assertEquals(frozenRevision, third.revision)
        assertEquals(30, second.value.rows.size)
        assertEquals(5, third.value.rows.size)
        val frozenIds = first.value.rows + second.value.rows + third.value.rows
        assertEquals(65, frozenIds.map { it.id }.distinct().size)
        assertTrue(frozenIds.none { it.entry.event == NetworkActivityEvent.ProtectionConnected })

        val live = store.loadPage(NetworkActivityPageRequest())
        assertEquals(66, live.revision)
        assertEquals(NetworkActivityEvent.ProtectionConnected, live.value.rows.first().entry.event)
    }

    @Test
    fun `clear advances revision and invalidates every prior continuation`() {
        var now = 30_000_000L
        val store = CredentialNetworkActivityRuntimeStore(nowMillis = { now })
        repeat(31) { index ->
            store.record(
                NetworkActivityEvent.NetworkChanged(
                    null,
                    NetworkActivityNetworkKind.CELLULAR,
                    index % 2 == 0,
                ),
                state(),
                now - index * 180_000L,
            )
        }
        val token = checkNotNull(store.loadPage(NetworkActivityPageRequest()).value.next)

        val clearRevision = store.clear()

        assertEquals(32L, clearRevision)
        assertThrows(IllegalArgumentException::class.java) {
            store.loadPage(NetworkActivityPageRequest(token = token))
        }
        val empty = store.loadPage(NetworkActivityPageRequest())
        assertTrue(empty.value.rows.isEmpty())
        assertEquals(clearRevision, empty.revision)
    }

    @Test
    fun `matching occurrences roll up and store remains bounded to three hundred rows`() {
        var now = 100_000_000L
        val store = CredentialNetworkActivityRuntimeStore(nowMillis = { now })
        store.record(NetworkActivityEvent.ProtectionConnected, state(), now)
        now += 1_000L
        store.record(NetworkActivityEvent.ProtectionConnected, state(), now)
        assertEquals(2, store.loadPage(NetworkActivityPageRequest()).value.rows.single().entry.occurrenceCount)

        repeat(350) { index ->
            now += 180_000L
            store.record(
                NetworkActivityEvent.NetworkChanged(
                    NetworkActivityNetworkKind.WIFI,
                    NetworkActivityNetworkKind.CELLULAR,
                    index % 2 == 0,
                ),
                state(),
                now,
            )
        }
        assertEquals(300, store.sizeForTest())
    }

    private fun state() = NetworkActivityCapturedState(
        protection = NetworkActivityProtectionState.PROTECTED,
        connectivity = NetworkActivityConnectivityState.HEALTHY,
        networkKind = NetworkActivityNetworkKind.WIFI,
        networkPathSatisfied = true,
        resolver = NetworkActivityResolverIdentity.NONE,
        resolverTransport = null,
        fallbackCoverage = ResolverFallbackCoverage.NONE,
        activeFallback = ProtectionFallback.NONE,
    )
}
