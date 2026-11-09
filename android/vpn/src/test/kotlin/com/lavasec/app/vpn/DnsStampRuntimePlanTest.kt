package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.toSelection
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DnsStampRuntimePlanTest {
    private val network = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(19)
    private val context = NetworkContext(network, 7, emptyList(), false)

    @Test
    fun `raw stamp rows round trip and build exact primary and secondary route facts`() {
        for ((protocol, transport) in listOf(0 to CatalogResolverTransport.PLAIN_DNS, 2 to CatalogResolverTransport.DOH,
            3 to CatalogResolverTransport.DOT, 4 to CatalogResolverTransport.DOQ)) {
            val primary = stamp(protocol, "192.168.1.53", "primary.example")
            val secondary = stamp(protocol, "192.168.1.54:53".takeIf { protocol == 0 } ?: "192.168.1.54:8853", "secondary.example:8853")
            val selection = ResolverSelection(listOf(ResolverTier(entry(transport, primary, secondary), retainedName = "Raw stamp")))
            val configuration = checkNotNull(selection.toFilterResolverPlan()).configuration
            assertEquals(selection, configuration.toSelection())
            val plan = build(configuration)
            assertTrue(plan.isCanonicalReadyRoute())
            if (protocol == 0) {
                val leaf = checkNotNull(plan.orderedRoutes).single()
                assertEquals(ResolverKind.PLAIN_DNS, leaf.primaryResolverKind)
                assertEquals(listOf(ip("192.168.1.53"), ip("192.168.1.54")), leaf.fixedDnsServers)
                assertEquals(primary, leaf.catalogEntry!!.customPlainEndpoint)
                assertEquals(secondary, leaf.catalogEntry!!.customSecondaryEndpoint)
            } else {
                assertEquals(primary, plan.customEndpointValue)
                assertEquals(secondary, plan.customSecondaryEndpointValue)
                assertEquals(listOf(ip("192.168.1.53")), plan.encryptedBootstrapAddresses)
                assertEquals(listOf(ip("192.168.1.54")), plan.encryptedSecondaryBootstrapAddresses)
                assertTrue(plan.primaryResolverIdentity.value.contains(primary))
                assertTrue(plan.primaryResolverIdentity.value.contains(secondary))
                when (protocol) {
                    2 -> {
                        assertEquals("https://primary.example/dns-query", plan.dohEndpointUrl)
                        assertEquals("https://secondary.example:8853/dns-query", plan.dohSecondaryEndpointUrl)
                    }
                    3 -> {
                        assertEquals(CustomDotEndpoint("primary.example", 853), plan.dotEndpoint)
                        assertEquals(CustomDotEndpoint("secondary.example", 8853), plan.dotSecondaryEndpoint)
                    }
                    4 -> {
                        assertEquals(CustomDoqEndpoint("primary.example", 853), plan.doqEndpoint)
                        assertEquals(CustomDoqEndpoint("secondary.example", 8853), plan.doqSecondaryEndpoint)
                    }
                }
            }
        }
    }

    @Test
    fun `production composition gives each endpoint only its own bootstrap list`() {
        for ((protocol, transport) in listOf(2 to CatalogResolverTransport.DOH, 3 to CatalogResolverTransport.DOT,
            4 to CatalogResolverTransport.DOQ)) {
            val primary = stamp(protocol, "192.168.1.53", "primary.example")
            val secondary = stamp(protocol, "192.168.1.54:8853", "secondary.example:8853")
            val selection = ResolverSelection(listOf(ResolverTier(entry(transport, primary, secondary))))
            val plan = build(checkNotNull(selection.toFilterResolverPlan()).configuration)
            val calls = mutableListOf<Pair<String, List<InetAddress>>>()
            var closes = 0
            fun capture(target: String, bootstrap: List<InetAddress>): ReadyDnsMullvadRuntime {
                calls += target to bootstrap
                return runtime { closes++ }
            }
            val ownedRuntime = buildReadyEncryptedRuntime(plan,
                doh = { target, bootstrap -> capture(checkNotNull(target), bootstrap) },
                dot = { target, bootstrap -> capture(target.normalized, bootstrap) },
                doq = { target, bootstrap -> capture(target.normalized, bootstrap) })
            assertEquals(listOf(listOf(ip("192.168.1.53")), listOf(ip("192.168.1.54"))), calls.map { it.second })
            assertTrue(calls.first().first.contains("primary.example"))
            assertTrue(calls.last().first.contains("secondary.example:8853"))
            ownedRuntime.close()
            assertEquals(2, closes)
        }
    }

    @Test
    fun `bootstrap edits and secondary edits cannot inherit a prior stamp runtime identity`() {
        fun plan(primaryIp: String, secondaryIp: String): ResolverPlan = build(ResolverConfiguration(
            useDeviceDns = false, useEncryptedFallback = true,
            customDohUrl = stamp(2, primaryIp, "resolver.example"),
            customSecondaryEndpoint = stamp(2, secondaryIp, "secondary.example")))
        val original = plan("192.168.1.53", "192.168.1.54")
        assertNotEquals(original.primaryResolverIdentity, plan("192.168.1.55", "192.168.1.54").primaryResolverIdentity)
        assertNotEquals(original.primaryResolverIdentity, plan("192.168.1.53", "192.168.1.55").primaryResolverIdentity)
    }

    @Test
    fun `a plan with swapped stamp bootstrap is refused before becoming ready`() {
        val primary = stamp(2, "192.168.1.53", "primary.example")
        val secondary = stamp(2, "192.168.1.54", "secondary.example")
        val plan = build(ResolverConfiguration(useDeviceDns = false, useEncryptedFallback = true,
            customDohUrl = primary, customSecondaryEndpoint = secondary))
        val tampered = ResolverPlan(plan.network, plan.networkGeneration, plan.isCellular, emptyList(), true,
            plan.primaryResolverKind, false, plan.primaryResolverIdentity, plan.fallbackCoverage,
            dohEndpointUrl = plan.dohEndpointUrl, dohSecondaryEndpointUrl = plan.dohSecondaryEndpointUrl,
            customEndpointValue = primary, customSecondaryEndpointValue = secondary,
            encryptedBootstrapAddresses = plan.encryptedSecondaryBootstrapAddresses,
            encryptedSecondaryBootstrapAddresses = plan.encryptedBootstrapAddresses)
        assertFalse(tampered.isCanonicalReadyRoute())
    }

    @Test
    fun `failed secondary construction releases the already constructed primary`() {
        val plan = build(ResolverConfiguration(useDeviceDns = false, useEncryptedFallback = true,
            customDohUrl = stamp(2, "192.168.1.53", "primary.example"),
            customSecondaryEndpoint = stamp(2, "192.168.1.54", "secondary.example")))
        var closed = false
        val failure = IllegalStateException("controlled construction refusal")
        try {
            buildReadyEncryptedRuntime(plan, doh = { target, _ ->
                if (target!!.contains("secondary")) throw failure else runtime { closed = true }
            }, dot = { _, _ -> error("wrong transport") }, doq = { _, _ -> error("wrong transport") })
            fail("secondary failure must propagate")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertTrue(closed)
    }

    private fun runtime(onClose: () -> Unit) = object : ReadyDnsMullvadRuntime {
        override val resolver = object : DnsResolver {
            override suspend fun resolve(request: ResolverRequest): ResolverResult = ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
        }
        override fun close() = onClose.invoke()
    }
    private fun build(configuration: ResolverConfiguration): ResolverPlan =
        (ResolverPlanFactory.build(configuration, context) as ResolverPlanBuildResult.Ready).plan
    private fun ip(value: String) = InetAddress.getByName(value)
    private fun entry(transport: CatalogResolverTransport, primary: String, secondary: String) = ResolverSelectionEntry(
        ResolverProviderId.CUSTOM, transport, customDohUrl = primary.takeIf { transport == CatalogResolverTransport.DOH },
        customDotEndpoint = primary.takeIf { transport == CatalogResolverTransport.DOT },
        customDoqEndpoint = primary.takeIf { transport == CatalogResolverTransport.DOQ },
        customPlainEndpoint = primary.takeIf { transport == CatalogResolverTransport.PLAIN_DNS }, customSecondaryEndpoint = secondary)
    private fun stamp(protocol: Int, address: String, host: String): String {
        val out = ByteArrayOutputStream()
        out.write(protocol); out.write(ByteArray(8))
        fun lp(value: String) { val bytes = value.toByteArray(); out.write(bytes.size); out.write(bytes) }
        lp(address)
        if (protocol != 0) {
            lp(""); lp(host)
            if (protocol == 2) lp("/dns-query")
        }
        return "sdns://" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }
}
