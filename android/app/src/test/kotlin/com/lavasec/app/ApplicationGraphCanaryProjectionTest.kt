package com.lavasec.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.data.CredentialDnsLeakCanaryOwner
import com.lavasec.app.vpn.CredentialVpnAttachRequest
import com.lavasec.app.vpn.CredentialVpnAttachment
import com.lavasec.app.vpn.CredentialVpnHostPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApplicationGraphCanaryProjectionTest {
    @Test fun `projection attaches the existing owner with exact generation and nulls before every retirement`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val canary = CredentialDnsLeakCanaryOwner(context.getSharedPreferences("projection-${System.nanoTime()}", Context.MODE_PRIVATE), true)
        lateinit var lifecycle: ApplicationGraphLifecycleOwner
        var created = 0
        val retired = mutableListOf<Long>()
        lifecycle = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = { object : DeviceApplicationGraph { override fun close() = Unit } },
            credentialGraphFactory = { generation ->
                created++
                graph(generation, canary) {
                    assertNull("Projection is revoked before graph resources close", lifecycle.dnsLeakCanaryBinding.value)
                    retired += generation
                }
            },
            attachCredentialHost = { AutoCloseable {} }, restoreProtection = {},
        )
        assertNull(lifecycle.dnsLeakCanaryBinding.value)
        lifecycle.reconcile(false)
        assertEquals(0, created)
        lifecycle.reconcile(true)
        val first = checkNotNull(lifecycle.dnsLeakCanaryBinding.value)
        assertSame(canary, first.owner); assertEquals(1L, first.graphGeneration)
        lifecycle.reconcile(true); assertSame(first, lifecycle.dnsLeakCanaryBinding.value)
        lifecycle.rotateCredentialGraph(1)
        val second = checkNotNull(lifecycle.dnsLeakCanaryBinding.value)
        assertNotSame(first, second); assertSame(canary, second.owner); assertEquals(2L, second.graphGeneration)
        lifecycle.rotateCredentialGraph(1); assertSame(second, lifecycle.dnsLeakCanaryBinding.value)
        lifecycle.reconcile(false); assertNull(lifecycle.dnsLeakCanaryBinding.value)
        lifecycle.reconcile(true); assertEquals(3L, lifecycle.dnsLeakCanaryBinding.value!!.graphGeneration)
        lifecycle.close(); assertNull(lifecycle.dnsLeakCanaryBinding.value)
        assertEquals(listOf(1L, 2L, 3L), retired)
        Unit
    }

    @Test fun `failed attachment and replacement never expose candidate or retired canary owners`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val canary = CredentialDnsLeakCanaryOwner(context.getSharedPreferences("failed-projection-${System.nanoTime()}", Context.MODE_PRIVATE), true)
        var rejectAttachment = true
        var closedCandidates = 0
        val lifecycle = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = { object : DeviceApplicationGraph { override fun close() = Unit } },
            credentialGraphFactory = { generation -> graph(generation, canary) { closedCandidates++ } },
            attachCredentialHost = { if (rejectAttachment) error("controlled attachment rejection") else AutoCloseable {} },
            restoreProtection = {},
        )
        assertTrue(runCatching { lifecycle.reconcile(true) }.isFailure)
        assertNull(lifecycle.dnsLeakCanaryBinding.value); assertEquals(1, closedCandidates)
        rejectAttachment = false; lifecycle.reconcile(true)
        assertSame(canary, lifecycle.dnsLeakCanaryBinding.value!!.owner)
        val generation = lifecycle.dnsLeakCanaryBinding.value!!.graphGeneration
        rejectAttachment = true
        assertTrue(runCatching { lifecycle.rotateCredentialGraph(generation) }.isFailure)
        assertNull(lifecycle.dnsLeakCanaryBinding.value)
        lifecycle.close(); assertNull(lifecycle.dnsLeakCanaryBinding.value)
        Unit
    }

    @Test fun `credential graph without debug owner projects no canary`() = runTest {
        val lifecycle = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = { object : DeviceApplicationGraph { override fun close() = Unit } },
            credentialGraphFactory = { generation -> graph(generation, null) {} },
            attachCredentialHost = { AutoCloseable {} }, restoreProtection = {},
        )
        lifecycle.reconcile(true); assertNull(lifecycle.dnsLeakCanaryBinding.value)
        lifecycle.close()
        Unit
    }

    private fun graph(generation: Long, canary: CredentialDnsLeakCanaryOwner?, closed: () -> Unit) =
        object : CredentialApplicationGraph, CredentialVpnHostPort {
            override val graphGeneration = generation
            override val dnsLeakCanaryOwner = canary
            override val authoritativeProtectionIntent: Boolean = false
            override val vpnHost: CredentialVpnHostPort get() = this
            override fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment? = null
            override fun close() = closed()
        }
}
