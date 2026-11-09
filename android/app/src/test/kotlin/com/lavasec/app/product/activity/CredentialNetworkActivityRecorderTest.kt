package com.lavasec.app.product.activity

import com.lavasec.app.foundation.activity.NetworkActivityConnectivityState
import com.lavasec.app.foundation.activity.NetworkActivityEvent
import com.lavasec.app.foundation.activity.NetworkActivityFallbackActivationCause
import com.lavasec.app.foundation.activity.NetworkActivityNetworkKind
import com.lavasec.app.foundation.activity.NetworkActivityReconnectReason
import com.lavasec.app.foundation.activity.NetworkActivityRecoveryReason
import com.lavasec.app.foundation.activity.NetworkActivityResolverIdentity
import com.lavasec.app.foundation.activity.NetworkActivityResolverTransport
import com.lavasec.app.foundation.activity.NetworkActivityUserAction
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.health.ProtectionHealthContext
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ProtectionController
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.product.activity.NetworkActivityPageRequest
import com.lavasec.app.product.StablePage
import com.lavasec.app.vpn.VpnHealthStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialNetworkActivityRecorderTest {
    @Test
    fun `custom DoT captured state records TLS rather than HTTPS`() = recorderTest { fixture ->
        fixture.health.value = VpnHealthStatus(health = accumulator(
            context = engine(primary = "custom-dot:tls://dns.example:853"),
        ))
        advanceUntilIdle()
        fixture.recorder.recordingController(NoOpProtectionController()).start()
        advanceUntilIdle()
        val state = fixture.rows().last().entry.capturedState
        assertEquals(NetworkActivityResolverIdentity.ENCRYPTED_OTHER, state.resolver)
        assertEquals(NetworkActivityResolverTransport.DNS_OVER_TLS, state.resolverTransport)
    }

    @Test
    fun `catalog DoT captured state retains Quad9 unfiltered identity and encrypted fallback coverage`() = recorderTest { fixture ->
        fixture.health.value = VpnHealthStatus(health = accumulator(
            context = engine(primary = "catalog-dot:quad9-unfiltered:tls://dns10.quad9.net:853",
                coverage = ResolverFallbackCoverage.ENCRYPTED_DNS),
        ))
        advanceUntilIdle()
        fixture.recorder.recordingController(NoOpProtectionController()).start()
        advanceUntilIdle()
        val state = fixture.rows().last().entry.capturedState
        assertEquals(NetworkActivityResolverIdentity.QUAD9_UNFILTERED, state.resolver)
        assertEquals(NetworkActivityResolverTransport.DNS_OVER_TLS, state.resolverTransport)
        assertEquals(ResolverFallbackCoverage.ENCRYPTED_DNS, state.fallbackCoverage)
    }

    @Test
    fun `custom DoQ captured state records QUIC rather than HTTPS`() = recorderTest { fixture ->
        fixture.health.value = VpnHealthStatus(health = accumulator(
            context = engine(primary = "custom-doq:doq://dns.example:8853"),
        ))
        advanceUntilIdle()
        fixture.recorder.recordingController(NoOpProtectionController()).start()
        advanceUntilIdle()
        val state = fixture.rows().last().entry.capturedState
        assertEquals(NetworkActivityResolverIdentity.ENCRYPTED_OTHER, state.resolver)
        assertEquals(NetworkActivityResolverTransport.DNS_OVER_QUIC, state.resolverTransport)
    }

    @Test
    fun `user commands record the exact user action with the live captured state`() = recorderTest {
        val fixture = it
        fixture.protection.value = ProtectionState.Off
        fixture.network.value = wifiFacts(satisfied = true)
        advanceUntilIdle()

        val controller = fixture.recorder.recordingController(
            NoOpProtectionController(),
        )
        controller.start()
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        val entry = rows[0].entry
        assertEquals(NetworkActivityEvent.UserAction(NetworkActivityUserAction.TURN_PROTECTION_ON), entry.event)
        assertEquals(NetworkActivityNetworkKind.WIFI, entry.capturedState.networkKind)
        assertTrue(entry.capturedState.networkPathSatisfied)
        assertEquals(1, fixture.onRecorded.get())
    }

    @Test
    fun `pause and resume are not part of the user action taxonomy`() = recorderTest {
        val fixture = it
        advanceUntilIdle()

        val controller = fixture.recorder.recordingController(NoOpProtectionController())
        controller.pause(com.lavasec.app.foundation.pause.PauseDuration.FIFTEEN_MINUTES)
        controller.resume()
        advanceUntilIdle()

        assertTrue(fixture.rows().isEmpty())
        assertEquals(0, fixture.onRecorded.get())
    }

    @Test
    fun `identical user actions inside the roll-up window coalesce into one row`() = recorderTest {
        val fixture = it
        advanceUntilIdle()

        val controller = fixture.recorder.recordingController(NoOpProtectionController())
        controller.reconnect()
        controller.reconnect()
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].entry.occurrenceCount)
    }

    @Test
    fun `protection off to protected records one ProtectionConnected`() = recorderTest {
        val fixture = it
        fixture.protection.value = ProtectionState.Off
        advanceUntilIdle()

        fixture.protection.value = ProtectionState.Starting(StartOrigin.VisibleUser, 1L)
        advanceUntilIdle()
        assertTrue(fixture.rows().isEmpty())

        fixture.protection.value = protected()
        advanceUntilIdle()
        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(NetworkActivityEvent.ProtectionConnected, rows[0].entry.event)
        assertEquals(
            com.lavasec.app.foundation.activity.NetworkActivityProtectionState.PROTECTED,
            rows[0].entry.capturedState.protection,
        )

        // Already protected: no duplicate.
        fixture.protection.value = protected()
        advanceUntilIdle()
        assertEquals(1, fixture.rows().size)
    }

    @Test
    fun `the first emission of every flow anchors the baseline and records nothing`() = recorderTest {
        val fixture = it
        // Fallback already active, protection already protected, network already present:
        // a graph creation must not manufacture activation/connected/change history.
        fixture.protection.value = protected()
        fixture.health.value = VpnHealthStatus(
            health = accumulator(
                activeFallback = ProtectionFallback.DEVICE_DNS,
                context = engine(
                    primary = "mullvad-doh",
                    coverage = ResolverFallbackCoverage.DEVICE_DNS,
                ),
            ),
        )
        fixture.network.value = wifiFacts(satisfied = true)
        advanceUntilIdle()

        assertTrue(fixture.rows().isEmpty())
        assertEquals(0, fixture.onRecorded.get())
    }

    @Test
    fun `fallback activation maps to the honest ProducerUnspecified cause`() = recorderTest {
        val fixture = it
        fixture.protection.value = protected()
        fixture.health.value = VpnHealthStatus(
            health = accumulator(
                context = engine(
                    primary = "mullvad-doh",
                    coverage = ResolverFallbackCoverage.DEVICE_DNS,
                ),
            ),
        )
        advanceUntilIdle()

        fixture.health.value = VpnHealthStatus(
            health = accumulator(
                activeFallback = ProtectionFallback.DEVICE_DNS,
                context = engine(
                    primary = "mullvad-doh",
                    coverage = ResolverFallbackCoverage.DEVICE_DNS,
                ),
            ),
        )
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(
            NetworkActivityEvent.FallbackActivated(
                fallback = ProtectionFallback.DEVICE_DNS,
                cause = NetworkActivityFallbackActivationCause.ProducerUnspecified,
            ),
            rows[0].entry.event,
        )
        val captured = rows[0].entry.capturedState
        // The contract's exact coherence requirements hold: active fallback matches the
        // configured coverage, and the DoH primary resolves to DoH transport.
        assertEquals(ResolverFallbackCoverage.DEVICE_DNS, captured.fallbackCoverage)
        assertEquals(ProtectionFallback.DEVICE_DNS, captured.activeFallback)
        assertEquals(NetworkActivityResolverIdentity.MULLVAD, captured.resolver)
        assertEquals(NetworkActivityResolverTransport.DNS_OVER_HTTPS, captured.resolverTransport)
        assertEquals(NetworkActivityConnectivityState.DEVICE_DNS_FALLBACK, captured.connectivity)
    }

    @Test
    fun `encrypted fallback activation with a device primary captures device facts`() = recorderTest {
        val fixture = it
        fixture.protection.value = protected()
        fixture.health.value = VpnHealthStatus(
            health = accumulator(
                context = engine(
                    primary = "device-dns:v4=192.0.2.53;v6=",
                    coverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                ),
            ),
        )
        advanceUntilIdle()

        fixture.health.value = VpnHealthStatus(
            health = accumulator(
                activeFallback = ProtectionFallback.ENCRYPTED_DNS,
                context = engine(
                    primary = "device-dns:v4=192.0.2.53;v6=",
                    coverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                ),
            ),
        )
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(
            NetworkActivityEvent.FallbackActivated(
                fallback = ProtectionFallback.ENCRYPTED_DNS,
                cause = NetworkActivityFallbackActivationCause.ProducerUnspecified,
            ),
            rows[0].entry.event,
        )
        val captured = rows[0].entry.capturedState
        assertEquals(NetworkActivityResolverIdentity.DEVICE_DNS, captured.resolver)
        assertEquals(NetworkActivityResolverTransport.DEVICE_UDP, captured.resolverTransport)
        assertEquals(NetworkActivityConnectivityState.ENCRYPTED_FALLBACK, captured.connectivity)
    }

    @Test
    fun `device fallback deactivation on a stable engine records the dedicated recovery`() =
        recorderTest {
            val fixture = it
            fixture.protection.value = protected()
            fixture.health.value = VpnHealthStatus(
                health = accumulator(
                    activeFallback = ProtectionFallback.DEVICE_DNS,
                    context = engine(
                        primary = "mullvad-doh",
                        coverage = ResolverFallbackCoverage.DEVICE_DNS,
                    ),
                ),
            )
            advanceUntilIdle()

            fixture.health.value = VpnHealthStatus(
                health = accumulator(
                    context = engine(
                        primary = "mullvad-doh",
                        coverage = ResolverFallbackCoverage.DEVICE_DNS,
                    ),
                ),
            )
            advanceUntilIdle()

            val rows = fixture.rows()
            assertEquals(1, rows.size)
            assertEquals(NetworkActivityEvent.DeviceDnsFallbackRecovered, rows[0].entry.event)
        }

    @Test
    fun `encrypted fallback deactivation on a stable engine records primary recovery`() =
        recorderTest {
            val fixture = it
            fixture.protection.value = protected()
            fixture.health.value = VpnHealthStatus(
                health = accumulator(
                    activeFallback = ProtectionFallback.ENCRYPTED_DNS,
                    context = engine(
                        primary = "device-dns:v4=192.0.2.53;v6=",
                        coverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                    ),
                ),
            )
            advanceUntilIdle()

            fixture.health.value = VpnHealthStatus(
                health = accumulator(
                    context = engine(
                        primary = "device-dns:v4=192.0.2.53;v6=",
                        coverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                    ),
                ),
            )
            advanceUntilIdle()

            val rows = fixture.rows()
            assertEquals(1, rows.size)
            assertEquals(
                NetworkActivityEvent.ConnectivityRecovered(
                    NetworkActivityRecoveryReason.PRIMARY_RESOLVER,
                ),
                rows[0].entry.event,
            )
        }

    @Test
    fun `an engine advance is a reset so fallback clearing records no recovery`() = recorderTest {
        val fixture = it
        fixture.protection.value = protected()
        fixture.health.value = VpnHealthStatus(
            health = accumulator(
                activeFallback = ProtectionFallback.DEVICE_DNS,
                context = engine(
                    primary = "mullvad-doh",
                    coverage = ResolverFallbackCoverage.DEVICE_DNS,
                    engineGeneration = 1L,
                ),
            ),
        )
        advanceUntilIdle()

        fixture.health.value = VpnHealthStatus(
            health = accumulator(
                context = engine(
                    primary = "mullvad-doh",
                    coverage = ResolverFallbackCoverage.DEVICE_DNS,
                    engineGeneration = 2L,
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(fixture.rows().isEmpty())
    }

    @Test
    fun `requiresReconnect edges carry the honest runtime-unavailable reason`() = recorderTest {
        val fixture = it
        fixture.protection.value = protected()
        fixture.health.value = VpnHealthStatus(
            health = accumulator(context = engine()),
        )
        advanceUntilIdle()

        fixture.health.value = VpnHealthStatus(
            health = accumulator(requiresReconnect = true, context = engine()),
        )
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(
            NetworkActivityEvent.ReconnectNeeded(NetworkActivityReconnectReason.RuntimeUnavailable),
            rows[0].entry.event,
        )
        assertEquals(
            NetworkActivityConnectivityState.NEEDS_RECONNECT,
            rows[0].entry.capturedState.connectivity,
        )

        // Discharge through the same engine: severity leaves NEEDS_RECONNECT and records
        // exactly one recovery — the requiresReconnect edge itself is not an extra event.
        fixture.health.value = VpnHealthStatus(
            health = accumulator(context = engine()),
        )
        advanceUntilIdle()
        val after = fixture.rows()
        assertEquals(2, after.size)
        assertEquals(
            NetworkActivityEvent.ConnectivityRecovered(
                NetworkActivityRecoveryReason.PRIMARY_RESOLVER,
            ),
            after[0].entry.event,
        )
    }

    @Test
    fun `severity recovery from a resolver failure streak records primary recovery`() = recorderTest {
        val fixture = it
        fixture.protection.value = protected()
        fixture.health.value = VpnHealthStatus(
            health = accumulator(consecutiveResolverFailures = 1, context = engine()),
        )
        advanceUntilIdle()

        fixture.health.value = VpnHealthStatus(
            health = accumulator(context = engine()),
        )
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(
            NetworkActivityEvent.ConnectivityRecovered(
                NetworkActivityRecoveryReason.PRIMARY_RESOLVER,
            ),
            rows[0].entry.event,
        )
    }

    @Test
    fun `network facts transitions map to honest NetworkChanged events`() = recorderTest {
        val fixture = it
        fixture.network.value = wifiFacts(satisfied = true)
        advanceUntilIdle()

        // Kind change on a satisfied path.
        fixture.network.value = AndroidDefaultNetworkFacts(
            present = true,
            kind = NetworkActivityNetworkKind.CELLULAR,
            pathSatisfied = true,
        )
        advanceUntilIdle()
        var rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(
            NetworkActivityEvent.NetworkChanged(
                from = NetworkActivityNetworkKind.WIFI,
                to = NetworkActivityNetworkKind.CELLULAR,
                pathSatisfied = true,
            ),
            rows[0].entry.event,
        )

        // Loss keeps the last observed kind so the row reads "…: path lost".
        fixture.network.value = AndroidDefaultNetworkFacts()
        advanceUntilIdle()
        rows = fixture.rows()
        assertEquals(2, rows.size)
        assertEquals(
            NetworkActivityEvent.NetworkChanged(
                from = NetworkActivityNetworkKind.CELLULAR,
                to = NetworkActivityNetworkKind.CELLULAR,
                pathSatisfied = false,
            ),
            rows[0].entry.event,
        )

        // Regain has no previous network: from stays null.
        fixture.network.value = wifiFacts(satisfied = true)
        advanceUntilIdle()
        rows = fixture.rows()
        assertEquals(3, rows.size)
        assertEquals(
            NetworkActivityEvent.NetworkChanged(
                from = null,
                to = NetworkActivityNetworkKind.WIFI,
                pathSatisfied = true,
            ),
            rows[0].entry.event,
        )

        // Identical facts are not an event.
        fixture.network.value = wifiFacts(satisfied = true)
        advanceUntilIdle()
        assertEquals(3, fixture.rows().size)
    }

    @Test
    fun `the retention fence drops every event class until retention is on`() = recorderTest(retentionEnabled = false) {
        val fixture = it
        advanceUntilIdle()

        fixture.protection.value = ProtectionState.Off
        advanceUntilIdle()
        fixture.protection.value = protected()
        advanceUntilIdle()
        fixture.network.value = wifiFacts(satisfied = true)
        advanceUntilIdle()

        assertTrue(fixture.rows().isEmpty())
        assertEquals(0, fixture.onRecorded.get())

        fixture.retentionEnabledFlag.set(true)
        fixture.recorder.recordUserAction(NetworkActivityUserAction.RECONNECT_PROTECTION)
        advanceUntilIdle()
        assertEquals(1, fixture.rows().size)
    }

    @Test
    fun `a closed recorder accepts no further records`() = recorderTest {
        val fixture = it
        advanceUntilIdle()

        fixture.recorder.close()
        fixture.recorder.recordUserAction(NetworkActivityUserAction.RECONNECT_PROTECTION)
        advanceUntilIdle()

        assertTrue(fixture.rows().isEmpty())
    }

    @Test
    fun `an Applied resolver switch records the derived resolver user action`() = recorderTest {
        val fixture = it
        advanceUntilIdle()

        val port = fixture.recorder.recordingResolverSwitchPort(
            delegate = FakeResolverSwitchPort(
                com.lavasec.app.product.ResolverPlanSwitchResult.Applied(
                    com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY,
                    generation = 2L,
                ),
            ),
            currentPlan = {
                com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY
            },
        )
        port.apply(com.lavasec.app.foundation.resolver.FilterResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY))
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(
            NetworkActivityEvent.UserAction(NetworkActivityUserAction.CHANGE_RESOLVER),
            rows[0].entry.event,
        )
    }

    @Test
    fun `a fallback-only plan flip records the Device DNS fallback toggle`() = recorderTest {
        val fixture = it
        advanceUntilIdle()

        val port = fixture.recorder.recordingResolverSwitchPort(
            delegate = FakeResolverSwitchPort(
                com.lavasec.app.product.ResolverPlanSwitchResult.Applied(
                    com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY,
                    generation = 2L,
                ),
            ),
            currentPlan = {
                com.lavasec.app.foundation.resolver.MilestoneResolverPlan
                    .DEVICE_DNS_WITH_MULLVAD_FALLBACK
            },
        )
        port.apply(com.lavasec.app.foundation.resolver.FilterResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY))
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(
            NetworkActivityEvent.UserAction(NetworkActivityUserAction.TOGGLE_DEVICE_DNS_FALLBACK),
            rows[0].entry.event,
        )
    }

    @Test
    fun `rejected switches and an unknown prior plan record nothing`() = recorderTest {
        val fixture = it
        advanceUntilIdle()

        val rejected = fixture.recorder.recordingResolverSwitchPort(
            delegate = FakeResolverSwitchPort(
                com.lavasec.app.product.ResolverPlanSwitchResult.Rejected(
                    com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY,
                    reason = com.lavasec.app.product.ResolverPlanSwitchRejection.INVALID_DRAFT,
                ),
            ),
            currentPlan = {
                com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY
            },
        )
        rejected.apply(com.lavasec.app.foundation.resolver.FilterResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY))
        advanceUntilIdle()
        assertTrue(fixture.rows().isEmpty())

        val unknownPrior = fixture.recorder.recordingResolverSwitchPort(
            delegate = FakeResolverSwitchPort(
                com.lavasec.app.product.ResolverPlanSwitchResult.Applied(
                    com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY,
                    generation = 2L,
                ),
            ),
            currentPlan = { null },
        )
        unknownPrior.apply(com.lavasec.app.foundation.resolver.FilterResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY))
        advanceUntilIdle()
        assertTrue(fixture.rows().isEmpty())
    }

    @Test
    fun `an Applied profile switch records the filters changed user action`() = recorderTest {
        val fixture = it
        advanceUntilIdle()

        val port = fixture.recorder.recordingProfileSwitchPort(
            delegate = FakeProfileSwitchPort(
                com.lavasec.app.product.FilterProfileSwitchResult.Applied(
                    activeProfileId = "extra",
                    generation = 2L,
                ),
            ),
        )
        port.apply("extra")
        advanceUntilIdle()

        val rows = fixture.rows()
        assertEquals(1, rows.size)
        assertEquals(
            NetworkActivityEvent.UserAction(NetworkActivityUserAction.CHANGE_FILTERS),
            rows[0].entry.event,
        )
    }

    @Test
    fun `the resolver switch category is derived from exact plan facts only`() {
        assertNull(
            resolverSwitchUserAction(
                previous = null,
                next = com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY,
            ),
        )
        assertEquals(
            NetworkActivityUserAction.CHANGE_RESOLVER,
            resolverSwitchUserAction(
                previous = com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY,
                next = com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY,
            ),
        )
        assertEquals(
            NetworkActivityUserAction.TOGGLE_DEVICE_DNS_FALLBACK,
            resolverSwitchUserAction(
                previous = com.lavasec.app.foundation.resolver.MilestoneResolverPlan
                    .DEVICE_DNS_WITH_MULLVAD_FALLBACK,
                next = com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY,
            ),
        )
        assertNull(
            resolverSwitchUserAction(
                previous = com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY,
                next = com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY,
            ),
        )
    }

    @Test
    fun `library filtering change detection covers active id and active rule content only`() {
        val balanced = profile("balanced", "Balanced")
        val extra = profile("extra", "Extra")
        val library = com.lavasec.app.foundation.filter.FilterLibraryDraft(
            profiles = listOf(balanced, extra),
            activeProfileId = "balanced",
            resolverPlan = com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY,
        )

        assertFalse(libraryActiveFilteringChanged(library, library))
        assertTrue(libraryActiveFilteringChanged(library, library.withActiveProfile("extra")))

        val balancedEdited = balanced.withEnabledSourceIds(setOf("stevenblack-unified"))
        assertTrue(
            libraryActiveFilteringChanged(
                library,
                library.withProfile(balancedEdited),
            ),
        )

        val balancedWithRule = balanced.withManualRules(
            com.lavasec.app.foundation.filter.ManualDomainRules(
                allowedDomains = setOf("example.com"),
            ),
        )
        assertTrue(
            libraryActiveFilteringChanged(
                library,
                library.withProfile(balancedWithRule),
            ),
        )

        // A non-active rename does not change the filter in effect.
        val renamedExtra = com.lavasec.app.foundation.filter.FilterProfileDraft(
            id = extra.id,
            name = "Renamed",
            protectionLevel = extra.protectionLevel,
            enabledSourceIds = extra.enabledSourceIds,
            manualRules = extra.manualRules,
        )
        assertFalse(
            libraryActiveFilteringChanged(
                library,
                library.withProfile(renamedExtra),
            ),
        )
    }

    private fun profile(
        id: String,
        name: String,
    ) = com.lavasec.app.foundation.filter.FilterProfileDraft(
        id = id,
        name = name,
        protectionLevel = com.lavasec.app.foundation.config.ProtectionLevel.BALANCED,
        enabledSourceIds = emptySet(),
    )

    private class FakeResolverSwitchPort(
        private val result: com.lavasec.app.product.ResolverPlanSwitchResult,
    ) : com.lavasec.app.product.ResolverPlanSwitchPort {
        override suspend fun apply(
            plan: com.lavasec.app.foundation.resolver.FilterResolverPlan,
        ): com.lavasec.app.product.ResolverPlanSwitchResult = result
    }

    private class FakeProfileSwitchPort(
        private val result: com.lavasec.app.product.FilterProfileSwitchResult,
    ) : com.lavasec.app.product.FilterProfileSwitchPort {
        override suspend fun apply(
            targetProfileId: String,
        ): com.lavasec.app.product.FilterProfileSwitchResult = result
    }


    private fun recorderTest(
        retentionEnabled: Boolean = true,
        block: suspend kotlinx.coroutines.test.TestScope.(RecorderFixture) -> Unit,
    ) = runTest {
        val fixture = RecorderFixture(this, retentionEnabled)
        try {
            block(fixture)
        } finally {
            fixture.recorder.close()
        }
    }

    private fun wifiFacts(satisfied: Boolean) = AndroidDefaultNetworkFacts(
        present = true,
        kind = NetworkActivityNetworkKind.WIFI,
        pathSatisfied = satisfied,
    )

    private fun protected(): ProtectionState = ProtectionState.Protected(
        configurationIdentity = com.lavasec.app.platform.ConfigurationIdentity(
            generation = 1L,
            fingerprint = "fingerprint",
        ),
        ownership = com.lavasec.app.platform.VpnOwnership.User,
    )

    private fun engine(
        primary: String = "mullvad-doh",
        coverage: ResolverFallbackCoverage = ResolverFallbackCoverage.NONE,
        engineGeneration: Long = 1L,
    ): ProtectionHealthContext = ProtectionHealthContext(
        sessionGeneration = 1L,
        networkGeneration = 1L,
        engineGeneration = engineGeneration,
        observationSequence = engineGeneration,
        observedAtMillis = 0L,
        primaryResolverIdentity = PrimaryResolverIdentity(primary),
        fallbackCoverage = coverage,
    )

    private fun accumulator(
        activeFallback: ProtectionFallback = ProtectionFallback.NONE,
        requiresReconnect: Boolean = false,
        consecutiveResolverFailures: Int = 0,
        context: ProtectionHealthContext? = null,
    ): ProtectionHealthAccumulator = ProtectionHealthAccumulator(
        isConnected = true,
        networkAvailable = true,
        consecutiveResolverFailures = consecutiveResolverFailures,
        activeFallback = activeFallback,
        requiresReconnect = requiresReconnect,
        context = context,
    )

    private class NoOpProtectionController : ProtectionController {
        private val stateFlow = MutableStateFlow<ProtectionState>(ProtectionState.Off)
        override val state: kotlinx.coroutines.flow.StateFlow<ProtectionState> = stateFlow

        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun pause(duration: com.lavasec.app.foundation.pause.PauseDuration) = Unit
        override suspend fun resume() = Unit
        override suspend fun reconnect() = Unit
    }

    @Test
    fun `privacy clear excludes telemetry and disabled retention cannot resurrect a row`() = recorderTest { fixture ->
        advanceUntilIdle()
        fixture.recorder.recordUserAction(NetworkActivityUserAction.RECONNECT_PROTECTION)
        assertEquals(1, fixture.rows().size)
        fixture.privacyFence.withClear {
            fixture.store.clear()
            // Event arrives while clear owns the boundary: it must be dropped, never queued.
            fixture.recorder.recordUserAction(NetworkActivityUserAction.CHANGE_FILTERS)
            fixture.retentionEnabledFlag.set(false)
        }
        fixture.recorder.recordUserAction(NetworkActivityUserAction.TURN_PROTECTION_ON)
        assertTrue(fixture.rows().isEmpty())
        assertEquals(1, fixture.onRecorded.get())
        fixture.retentionEnabledFlag.set(true)
        fixture.recorder.recordUserAction(NetworkActivityUserAction.RECONNECT_PROTECTION)
        assertEquals(1, fixture.rows().size)
    }

    private class RecorderFixture(
        scope: kotlinx.coroutines.CoroutineScope,
        retentionEnabled: Boolean = true,
    ) {
        val protection = MutableStateFlow<ProtectionState>(ProtectionState.Off)
        val health = MutableStateFlow(VpnHealthStatus())
        val network = MutableStateFlow(AndroidDefaultNetworkFacts())
        val retentionEnabledFlag = AtomicBoolean(retentionEnabled)
        val onRecorded = AtomicInteger()
        val clock = AtomicInteger(1_000_000)
        val store = CredentialNetworkActivityRuntimeStore(
            nowMillis = { clock.get().toLong() },
        )
        val privacyFence = com.lavasec.app.product.privacy.CredentialPrivacyFence()
        val recorder = CredentialNetworkActivityRecorder(
            store = store,
            protection = protection,
            health = health,
            networkFacts = network,
            retentionEnabled = { retentionEnabledFlag.get() },
            privacyFence = privacyFence,
            onRecorded = { onRecorded.incrementAndGet() },
            scope = scope,
            nowMillis = { clock.get().toLong() },
        )

        fun rows(): List<com.lavasec.app.product.activity.NetworkActivityRow> {
            val page: StablePage<com.lavasec.app.product.activity.NetworkActivityRow> =
                store.loadPage(NetworkActivityPageRequest()).value
            return page.rows
        }
    }
}
