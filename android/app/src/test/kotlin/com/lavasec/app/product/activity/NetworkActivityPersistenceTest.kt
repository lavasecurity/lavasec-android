package com.lavasec.app.product.activity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.activity.*
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkActivityPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun clearFiles() {
        context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith("network-activity-v1") }?.forEach { it.delete() }
    }

    @Test fun `retained events survive graph close roll up after restart and clear durably`() {
        var now = 1_000_000L
        fun open() = CredentialNetworkActivityRuntimeStore({ now }, AndroidNetworkActivityPersistence(context))
        val first = open()
        first.record(NetworkActivityEvent.ProtectionConnected, state(), now)
        first.close()
        now += 1000
        val second = open()
        second.record(NetworkActivityEvent.ProtectionConnected, state(), now)
        val entry = second.loadPage(NetworkActivityPageRequest()).value.rows.single().entry
        assertEquals(1, entry.id)
        assertEquals(2, entry.occurrenceCount)
        assertEquals(listOf(entry), NetworkActivityJson.decode(second.exportSnapshot()))
        assertEquals(1_000_000L, entry.firstOccurrenceEpochMillis)
        second.clear()
        second.close()
        val third = open()
        assertTrue(third.loadPage(NetworkActivityPageRequest()).value.rows.isEmpty())
        third.close()
    }

    @Test fun `restart prunes expired rows and retention disabled launch erases saved data`() {
        val disk = AndroidNetworkActivityPersistence(context)
        disk.save(listOf(NetworkActivityEntry(1, 1000, event = NetworkActivityEvent.ProtectionConnected, capturedState = state())))
        val expired = CredentialNetworkActivityRuntimeStore({ NetworkActivityContract.RETENTION_MILLIS + 1001 }, disk)
        assertTrue(expired.loadPage(NetworkActivityPageRequest()).value.rows.isEmpty())
        assertTrue(disk.load().isEmpty())
        expired.close()
        disk.save(listOf(NetworkActivityEntry(2, 2000, event = NetworkActivityEvent.ProtectionConnected, capturedState = state())))
        val disabled = CredentialNetworkActivityRuntimeStore({ 2000 }, disk, retainExisting = false)
        assertTrue(disabled.loadPage(NetworkActivityPageRequest()).value.rows.isEmpty())
        assertTrue(disk.load().isEmpty())
        disabled.close()
    }

    @Test fun `failed record and clear do not publish an uncommitted snapshot`() {
        var fail = false
        var saved = emptyList<NetworkActivityEntry>()
        val disk = object : NetworkActivityPersistence {
            override fun load() = saved
            override fun save(entries: List<NetworkActivityEntry>) { if (fail) throw IOException("disk"); saved = entries.toList() }
        }
        val store = CredentialNetworkActivityRuntimeStore({ 10_000 }, disk)
        store.record(NetworkActivityEvent.ProtectionConnected, state(), 1000)
        fail = true
        assertThrows(IOException::class.java) { store.record(NetworkActivityEvent.DeviceDnsFallbackRecovered, state(), 2000) }
        assertThrows(IOException::class.java) { store.clear() }
        assertEquals(1, store.loadPage(NetworkActivityPageRequest()).value.rows.size)
        assertEquals(saved.single(), store.loadPage(NetworkActivityPageRequest()).value.rows.single().entry)
    }

    @Test fun `corruption leaves only Activity unavailable and explicit clear recovers`() {
        File(context.noBackupFilesDir, "network-activity-v1.json").writeText("broken")
        val disk = AndroidNetworkActivityPersistence(context)
        val store = CredentialNetworkActivityRuntimeStore({ 1000 }, disk)
        assertThrows(IOException::class.java) { store.loadPage(NetworkActivityPageRequest()) }
        store.clear()
        store.record(NetworkActivityEvent.ProtectionConnected, state(), 1000)
        assertEquals(1, disk.load().size)
    }

    @Test fun `closed event vocabulary retains failure causes and refuses future or injected tags`() {
        val events = listOf(
            NetworkActivityEvent.NetworkChanged(null, NetworkActivityNetworkKind.WIFI, true),
            NetworkActivityEvent.ProtectionConnected,
            NetworkActivityEvent.UserAction(NetworkActivityUserAction.CHANGE_FILTERS),
            NetworkActivityEvent.DnsSmokeProbeSucceeded(NetworkActivityResolverIdentity.MULLVAD, NetworkActivityResolverTransport.DNS_OVER_HTTPS, NetworkActivityDohHttpVersion.HTTP_2),
            NetworkActivityEvent.DnsSmokeProbeFailed(NetworkActivityProbeFailure.RejectedResponse),
            NetworkActivityEvent.DnsSmokeProbeFailed(NetworkActivityProbeFailure.ResolverFailure(ResolverFailureReason.TIMEOUT)),
            NetworkActivityEvent.FallbackActivated(ProtectionFallback.ENCRYPTED_DNS, NetworkActivityFallbackActivationCause.ProducerUnspecified),
            NetworkActivityEvent.FallbackActivated(ProtectionFallback.ENCRYPTED_DNS, NetworkActivityFallbackActivationCause.QueryFallback),
            NetworkActivityEvent.FallbackActivated(ProtectionFallback.ENCRYPTED_DNS, NetworkActivityFallbackActivationCause.FallbackCheck),
            NetworkActivityEvent.FallbackActivated(ProtectionFallback.ENCRYPTED_DNS, NetworkActivityFallbackActivationCause.PrimaryFailure(ResolverFailureReason.TLS_FAILED)),
            NetworkActivityEvent.DeviceDnsFallbackRecovered,
            NetworkActivityEvent.ReconnectNeeded(NetworkActivityReconnectReason.RejectedResponse),
            NetworkActivityEvent.ReconnectNeeded(NetworkActivityReconnectReason.DeviceDnsRecapture),
            NetworkActivityEvent.ReconnectNeeded(NetworkActivityReconnectReason.RuntimeUnavailable),
            NetworkActivityEvent.ReconnectNeeded(NetworkActivityReconnectReason.ResolverFailure(ResolverFailureReason.BIND_FAILED)),
            NetworkActivityEvent.ConnectivityRecovered(NetworkActivityRecoveryReason.PRIMARY_RESOLVER),
            NetworkActivityEvent.NetworkSettingsReapplyFailed(NetworkActivitySettingsFailureReason.PLATFORM_REJECTED),
        )
        val entries = events.mapIndexed { i, event -> NetworkActivityEntry(i.toLong() + 1, 1000, event = event, capturedState = state()) }
        val bytes = NetworkActivityJson.encode(entries)
        assertEquals(entries, NetworkActivityJson.decode(bytes))
        val future = JSONObject(bytes.toString(Charsets.UTF_8)).put("version", 2)
        assertTrue(runCatching { NetworkActivityJson.decode(future.toString().toByteArray()) }.isFailure)
        future.put("version", 1).getJSONArray("rows").getJSONObject(0).getJSONObject("event").put("type", "java.lang.Runtime")
        assertTrue(runCatching { NetworkActivityJson.decode(future.toString().toByteArray()) }.isFailure)
    }

    private fun state() = NetworkActivityCapturedState(NetworkActivityProtectionState.PROTECTED,
        NetworkActivityConnectivityState.HEALTHY, NetworkActivityNetworkKind.WIFI, true,
        NetworkActivityResolverIdentity.NONE, null, ResolverFallbackCoverage.NONE, ProtectionFallback.NONE)
}
