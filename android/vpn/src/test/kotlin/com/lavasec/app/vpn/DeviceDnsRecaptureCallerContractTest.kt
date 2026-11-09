package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Android disposition of iOS #824 (`78a0c3c5`), whose cold Device-DNS recapture
 * restart Android does not implement. See `docs/android-predevice-parity-decisions.md` (D1).
 *
 * The first test is the decision's machine check: `DEVICE_DNS_RECAPTURE` may only be named by
 * its own policy declaration. If a future slice wires a real Device-DNS recapture restart, it
 * must first port #824's independent confirmation (a repeated identical leg is not a new
 * failure; two independent real failures are required) and supersede the decision. The second
 * test proves the live owner never restarts on a single silent Device-DNS observation.
 */
class DeviceDnsRecaptureCallerContractTest {
    @Test
    fun `device DNS recapture restart reason has no production caller`() {
        val root = androidRoot()
        val offenders = listOf("app", "foundation", "vpn", "product", "platform-contracts", "presentation")
            .map { root.resolve(it).resolve("src/main") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }
            .filter { it.readText().contains("DEVICE_DNS_RECAPTURE") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()

        assertEquals(
            "only the policy may name the dormant recapture reason; a production caller must " +
                "first port #824's independent Device-DNS confirmation",
            listOf("foundation/src/main/kotlin/com/lavasec/app/foundation/health/TunnelSelfReconnectPolicy.kt"),
            offenders,
        )
    }

    @Test
    fun `a single silent Device DNS failure never requests a VPN restart`() {
        val reconnects = mutableListOf<Long>()
        val owner = ExactSessionHealthOwner(11, { 1_000L }, {}, reconnects::add)
        val identity = identity(11, 1)
        owner.installEngine(identity, devicePrimaryEpoch = epoch(identity))

        owner.recordResolverQuery(
            1,
            ResolverQueryHealthEvidence.primaryFailed(1L, ResolverFailureReason.TIMEOUT),
        )

        assertTrue(reconnects.isEmpty())
        assertEquals(ProtectionHealthSeverity.RECOVERING, owner.current().health.assessment.severity)
    }

    private fun androidRoot(): File {
        val workingDir = checkNotNull(System.getProperty("user.dir"))
        var dir: File? = File(workingDir).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "foundation").isDirectory) return dir
            dir = dir.parentFile
        }
        error("could not locate the Android root from $workingDir")
    }

    private fun identity(session: Long, engine: Long) = VpnHealthEngineIdentity(
        sessionGeneration = session,
        networkGeneration = 1L,
        engineGeneration = engine,
        primaryResolverIdentity = PrimaryResolverIdentity("device-dns:v4=01010101;v6="),
        fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
    )

    private fun epoch(identity: VpnHealthEngineIdentity) = EngineEpoch(
        identity.sessionGeneration, ConfigurationIdentity(1, "cfg"), "artifact", 1,
        identity.networkGeneration, identity.engineGeneration, identity.primaryResolverIdentity,
        identity.fallbackCoverage,
    )
}
