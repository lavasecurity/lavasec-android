package com.lavasec.app.vpn

import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceOwnershipTest {
    @Test
    fun `new credential graph replaces discovery without an old detach clearing it`() {
        val registry = CredentialVpnHostRegistry()
        val first = FakeCredentialHost(41L)
        val second = FakeCredentialHost(42L)

        val firstLease = registry.attach(first)
        val secondLease = registry.attach(second)
        firstLease.close()

        assertSame(second, registry.currentForRuntime()?.host)
        assertEquals(42L, registry.currentForRuntime()?.graphGeneration)

        secondLease.close()
        assertEquals(null, registry.currentForRuntime())
    }

    @Test
    fun `exact replacement permits Direct Boot fallback without stale detach clearing it`() {
        val registry = CredentialVpnHostRegistry()
        val credential = registry.attach(FakeCredentialHost(61L))
        val directBoot = registry.attach(FakeCredentialHost(7L))
        credential.close()

        assertEquals(7L, registry.currentForRuntime()?.graphGeneration)
        directBoot.close()
        assertEquals(null, registry.currentForRuntime())
    }

    @Test
    fun `service state accepts only the exact installed owner`() {
        val states = ExactVpnServiceState()
        val first = states.install(StartOrigin.VisibleUser)
        val second = states.install(StartOrigin.Reload)
        val identity = ConfigurationIdentity(7L, "identity")

        assertFalse(
            states.publish(
                first,
                ProtectionState.Protected(identity, VpnOwnership.User),
            ),
        )
        assertTrue(
            states.publish(
                second,
                ProtectionState.Protected(identity, VpnOwnership.User),
            ),
        )
        assertEquals(
            ProtectionState.Protected(identity, VpnOwnership.User),
            states.state.value,
        )
        assertFalse(states.retire(first, ProtectionState.Off))
        assertTrue(states.retire(second, ProtectionState.Off))
        assertEquals(ProtectionState.Off, states.state.value)
    }

    @Test
    fun `exact runtime attachment drops stale detached and backpressured telemetry`() {
        val host = FakeCredentialHost(51L)
        val attachment = host.openAttachment(
            CredentialVpnAttachRequest(
                serviceSessionGeneration = 9L,
                origin = StartOrigin.VisibleUser,
            ),
        )
        val gate = ExactCredentialRuntimeAttachment(
            expectedServiceSessionGeneration = 9L,
            expectedEngineGeneration = 3L,
            host = host,
            attachment = attachment,
        )

        assertFalse(gate.tryAccept(VpnTelemetry(8L, 3L, 51L)))
        assertFalse(gate.tryAccept(VpnTelemetry(9L, 2L, 51L)))
        assertTrue(gate.tryAccept(VpnTelemetry(9L, 3L, 51L)))
        host.acceptTelemetry = false
        assertFalse(gate.tryAccept(VpnTelemetry(9L, 3L, 51L)))
        gate.close()
        assertFalse(gate.tryAccept(VpnTelemetry(9L, 3L, 51L)))
        assertEquals(1, attachment.detachCount.get())
    }
}

private class FakeCredentialHost(
    override val graphGeneration: Long,
) : CredentialVpnHostPort {
    var acceptTelemetry = true

    override fun openAttachment(
        request: CredentialVpnAttachRequest,
    ): FakeCredentialAttachment = FakeCredentialAttachment(
        graphGeneration = graphGeneration,
        accept = { acceptTelemetry },
    )
}

private class FakeCredentialAttachment(
    override val graphGeneration: Long,
    private val accept: () -> Boolean,
) : CredentialVpnAttachment {
    val detachCount = AtomicInteger()

    override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean = accept()

    override fun detach() {
        detachCount.incrementAndGet()
    }
}
