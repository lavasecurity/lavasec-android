package com.lavasec.app.foundation.activity

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkActivityContractTest {
    private val now = 1_800_000_000_000L
    private val healthyState = NetworkActivityCapturedState(
        protection = NetworkActivityProtectionState.PROTECTED,
        connectivity = NetworkActivityConnectivityState.HEALTHY,
        networkKind = NetworkActivityNetworkKind.WIFI,
        networkPathSatisfied = true,
        resolver = NetworkActivityResolverIdentity.DEVICE_DNS,
        resolverTransport = NetworkActivityResolverTransport.DEVICE_UDP,
        fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
        activeFallback = ProtectionFallback.NONE,
    )

    @Test
    fun `normalization caps rows and orders newest first with higher id breaking ties`() {
        val entries = (1L..301L).map { id ->
            entry(
                id = id,
                latestOccurrenceEpochMillis = now - id,
                event = NetworkActivityEvent.ProtectionConnected,
            )
        } + listOf(
            entry(
                id = 700L,
                latestOccurrenceEpochMillis = now,
                event = networkChanged(to = NetworkActivityNetworkKind.CELLULAR),
            ),
            entry(
                id = 900L,
                latestOccurrenceEpochMillis = now,
                event = NetworkActivityEvent.ReconnectNeeded(
                    NetworkActivityReconnectReason.ResolverFailure(
                        ResolverFailureReason.TIMEOUT,
                    ),
                ),
            ),
        )

        val normalized = NetworkActivityContract.normalize(entries, now)

        assertEquals(300, normalized.size)
        assertEquals(listOf(900L, 700L), normalized.take(2).map { it.id })
        assertFalse(normalized.any { it.id == 301L })
    }

    @Test
    fun `matching typed event and captured state roll up over a rolling 120 second window`() {
        val event = NetworkActivityEvent.DnsSmokeProbeFailed(
            NetworkActivityProbeFailure.ResolverFailure(ResolverFailureReason.TIMEOUT),
        )
        val first = entry(
            id = 41L,
            latestOccurrenceEpochMillis = now - 240_000L,
            event = event,
        )
        val second = entry(
            id = 42L,
            latestOccurrenceEpochMillis = now - 120_000L,
            event = event,
        )
        val third = entry(
            id = 43L,
            latestOccurrenceEpochMillis = now,
            event = event,
        )

        val afterSecond = NetworkActivityContract.record(listOf(first), second, now)
        val afterThird = NetworkActivityContract.record(afterSecond, third, now)

        assertEquals(1, afterThird.size)
        assertEquals(41L, afterThird.single().id)
        assertEquals(3, afterThird.single().occurrenceCount)
        assertEquals(now - 240_000L, afterThird.single().firstOccurrenceEpochMillis)
        assertEquals(now, afterThird.single().latestOccurrenceEpochMillis)
    }

    @Test
    fun `distinct user actions stay separate while an exact action rolls up`() {
        val turnOn = NetworkActivityEvent.UserAction(NetworkActivityUserAction.TURN_PROTECTION_ON)
        val changeResolver = NetworkActivityEvent.UserAction(NetworkActivityUserAction.CHANGE_RESOLVER)

        assertEquals(2, recordedPair(turnOn, changeResolver).size)
        val rolled = recordedPair(turnOn, turnOn)
        assertEquals(1, rolled.size)
        assertEquals(1L, rolled.single().id)
        assertEquals(2, rolled.single().occurrenceCount)
    }

    @Test
    fun `distinct network transitions stay separate while an exact transition rolls up`() {
        val cellular = networkChanged(to = NetworkActivityNetworkKind.CELLULAR)
        val wired = networkChanged(to = NetworkActivityNetworkKind.WIRED)

        assertEquals(2, recordedPair(cellular, wired).size)
        assertEquals(1, recordedPair(cellular, cellular).size)
    }

    @Test
    fun `closed failure recovery reconnect and settings reasons retain identity`() {
        assertEquals(
            2,
            recordedPair(
                NetworkActivityEvent.DnsSmokeProbeFailed(
                    NetworkActivityProbeFailure.ResolverFailure(ResolverFailureReason.TIMEOUT),
                ),
                NetworkActivityEvent.DnsSmokeProbeFailed(
                    NetworkActivityProbeFailure.RejectedResponse,
                ),
            ).size,
        )
        assertEquals(
            2,
            recordedPair(
                NetworkActivityEvent.FallbackActivated(
                    fallback = ProtectionFallback.DEVICE_DNS,
                    cause = NetworkActivityFallbackActivationCause.PrimaryFailure(
                        ResolverFailureReason.TIMEOUT,
                    ),
                ),
                NetworkActivityEvent.FallbackActivated(
                    fallback = ProtectionFallback.DEVICE_DNS,
                    cause = NetworkActivityFallbackActivationCause.QueryFallback,
                ),
            ).size,
        )
        assertEquals(
            2,
            recordedPair(
                NetworkActivityEvent.ReconnectNeeded(
                    NetworkActivityReconnectReason.ResolverFailure(
                        ResolverFailureReason.TIMEOUT,
                    ),
                ),
                NetworkActivityEvent.ReconnectNeeded(
                    NetworkActivityReconnectReason.RejectedResponse,
                ),
            ).size,
        )
        assertEquals(
            2,
            recordedPair(
                NetworkActivityEvent.ConnectivityRecovered(
                    NetworkActivityRecoveryReason.PRIMARY_RESOLVER,
                ),
                NetworkActivityEvent.ConnectivityRecovered(
                    NetworkActivityRecoveryReason.DEVICE_DNS_FALLBACK,
                ),
            ).size,
        )
        assertEquals(
            2,
            recordedPair(
                NetworkActivityEvent.NetworkSettingsReapplyFailed(
                    NetworkActivitySettingsFailureReason.PLATFORM_REJECTED,
                ),
                NetworkActivityEvent.NetworkSettingsReapplyFailed(
                    NetworkActivitySettingsFailureReason.STALE_SESSION,
                ),
            ).size,
        )
    }

    @Test
    fun `pinned producer values preserve rejected response query fallback and fallback check`() {
        val rejectedResponse = NetworkActivityEvent.DnsSmokeProbeFailed(
            NetworkActivityProbeFailure.RejectedResponse,
        )
        val timeout = NetworkActivityEvent.DnsSmokeProbeFailed(
            NetworkActivityProbeFailure.ResolverFailure(ResolverFailureReason.TIMEOUT),
        )
        val queryFallback = NetworkActivityEvent.FallbackActivated(
            fallback = ProtectionFallback.DEVICE_DNS,
            cause = NetworkActivityFallbackActivationCause.QueryFallback,
        )
        val fallbackCheck = NetworkActivityEvent.FallbackActivated(
            fallback = ProtectionFallback.DEVICE_DNS,
            cause = NetworkActivityFallbackActivationCause.FallbackCheck,
        )
        val encryptedFallback = NetworkActivityEvent.FallbackActivated(
            fallback = ProtectionFallback.ENCRYPTED_DNS,
            cause = NetworkActivityFallbackActivationCause.QueryFallback,
        )

        assertEquals(2, recordedPair(rejectedResponse, timeout).size)
        assertEquals(2, recordedPair(queryFallback, fallbackCheck).size)
        assertEquals(2, recordedPair(queryFallback, encryptedFallback).size)
        assertThrows(IllegalArgumentException::class.java) {
            NetworkActivityEvent.FallbackActivated(
                fallback = ProtectionFallback.NONE,
                cause = NetworkActivityFallbackActivationCause.QueryFallback,
            )
        }
    }

    @Test
    fun `frozen Android fallback activation maps without inventing producer evidence`() {
        val deviceFallback =
            DnsHealthEvent.FallbackActivated(ResolverKind.DEVICE)
                .toNetworkActivityFallbackEvent()
        val encryptedFallback =
            DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH)
                .toNetworkActivityFallbackEvent()

        assertEquals(
            NetworkActivityEvent.FallbackActivated(
                fallback = ProtectionFallback.DEVICE_DNS,
                cause = NetworkActivityFallbackActivationCause.ProducerUnspecified,
            ),
            deviceFallback,
        )
        assertEquals(
            NetworkActivityEvent.FallbackActivated(
                fallback = ProtectionFallback.ENCRYPTED_DNS,
                cause = NetworkActivityFallbackActivationCause.ProducerUnspecified,
            ),
            encryptedFallback,
        )

        val evidenceBackedCauses = listOf(
            NetworkActivityFallbackActivationCause.QueryFallback,
            NetworkActivityFallbackActivationCause.FallbackCheck,
            NetworkActivityFallbackActivationCause.PrimaryFailure(
                ResolverFailureReason.TIMEOUT,
            ),
        )
        evidenceBackedCauses.forEach { cause ->
            assertEquals(
                2,
                recordedPair(
                    deviceFallback,
                    NetworkActivityEvent.FallbackActivated(
                        fallback = ProtectionFallback.DEVICE_DNS,
                        cause = cause,
                    ),
                ).size,
            )
        }
    }

    @Test
    fun `captured state preserves an encrypted primary with a different encrypted fallback`() {
        val state = NetworkActivityCapturedState(NetworkActivityProtectionState.PROTECTED,
            NetworkActivityConnectivityState.ENCRYPTED_FALLBACK, NetworkActivityNetworkKind.WIFI, true,
            NetworkActivityResolverIdentity.QUAD9_UNFILTERED, NetworkActivityResolverTransport.DNS_OVER_TLS,
            ResolverFallbackCoverage.ENCRYPTED_DNS, ProtectionFallback.ENCRYPTED_DNS)
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, state.activeFallback)
        assertEquals(NetworkActivityResolverIdentity.QUAD9_UNFILTERED,
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId(BuiltInDohResolverIds.QUAD9_UNFILTERED))
    }

    @Test
    fun `plain provider evidence is distinct from device and encrypted fallback`() {
        val state = healthyState.copy(resolver = NetworkActivityResolverIdentity.CLOUDFLARE,
            resolverTransport = NetworkActivityResolverTransport.PLAIN_UDP,
            fallbackCoverage = ResolverFallbackCoverage.PLAIN_DNS, activeFallback = ProtectionFallback.PLAIN_DNS,
            connectivity = NetworkActivityConnectivityState.PLAIN_DNS_FALLBACK)
        assertEquals(ProtectionFallback.PLAIN_DNS, state.activeFallback)
        assertEquals(ProtectionFallback.PLAIN_DNS,
            DnsHealthEvent.FallbackActivated(ResolverKind.PLAIN_DNS).toNetworkActivityFallbackEvent().fallback)
        assertThrows(IllegalArgumentException::class.java) { state.copy(activeFallback = ProtectionFallback.DEVICE_DNS) }
        assertThrows(IllegalArgumentException::class.java) { state.copy(resolver = NetworkActivityResolverIdentity.DEVICE_DNS) }
        assertThrows(IllegalArgumentException::class.java) { state.copy(resolver = NetworkActivityResolverIdentity.ENCRYPTED_OTHER) }
        val udp = probeSuccess(NetworkActivityResolverIdentity.PLAIN_OTHER, NetworkActivityResolverTransport.PLAIN_UDP)
        val tcp = probeSuccess(NetworkActivityResolverIdentity.PLAIN_OTHER, NetworkActivityResolverTransport.PLAIN_TCP)
        assertEquals(2, recordedPair(udp, tcp).size)
    }

    @Test
    fun `activity resolver identity maps every built in without treating encrypted as Mullvad`() {
        assertEquals(
            setOf(
                NetworkActivityResolverIdentity.NONE,
                NetworkActivityResolverIdentity.DEVICE_DNS,
                NetworkActivityResolverIdentity.MULLVAD,
                NetworkActivityResolverIdentity.CLOUDFLARE,
                NetworkActivityResolverIdentity.QUAD9_SECURE,
                NetworkActivityResolverIdentity.QUAD9_UNFILTERED,
                NetworkActivityResolverIdentity.PLAIN_OTHER,
                NetworkActivityResolverIdentity.HAGEZI_ROOT,
                NetworkActivityResolverIdentity.GOOGLE_PUBLIC_DNS,
                NetworkActivityResolverIdentity.ENCRYPTED_OTHER,
                NetworkActivityResolverIdentity.WIREGUARD_T0,
            ),
            NetworkActivityResolverIdentity.values().toSet(),
        )
        assertEquals(
            NetworkActivityResolverIdentity.MULLVAD,
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId(
                BuiltInDohResolverIds.MULLVAD,
            ),
        )
        assertEquals(
            NetworkActivityResolverIdentity.CLOUDFLARE,
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId(
                BuiltInDohResolverIds.CLOUDFLARE,
            ),
        )
        assertEquals(
            NetworkActivityResolverIdentity.QUAD9_SECURE,
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId(
                BuiltInDohResolverIds.QUAD9_SECURE,
            ),
        )
        assertEquals(
            NetworkActivityResolverIdentity.HAGEZI_ROOT,
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId(
                BuiltInDohResolverIds.HAGEZI_ROOT,
            ),
        )
        assertEquals(
            NetworkActivityResolverIdentity.GOOGLE_PUBLIC_DNS,
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId(
                BuiltInDohResolverIds.GOOGLE_PUBLIC_DNS,
            ),
        )
        assertEquals(
            NetworkActivityResolverIdentity.ENCRYPTED_OTHER,
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId("future-safe-resolver"),
        )
    }

    @Test
    fun `chained resolver state permits only tunnel transport without physical fallback`() {
        val chained = healthyState.copy(
            resolver = NetworkActivityResolverIdentity.WIREGUARD_T0,
            resolverTransport = NetworkActivityResolverTransport.WIREGUARD_UDP,
            fallbackCoverage = ResolverFallbackCoverage.NONE,
        )
        assertEquals(NetworkActivityResolverIdentity.WIREGUARD_T0, chained.resolver)
        assertThrows(IllegalArgumentException::class.java) {
            chained.copy(resolverTransport = NetworkActivityResolverTransport.DEVICE_UDP)
        }
        assertThrows(IllegalArgumentException::class.java) {
            chained.copy(activeFallback = ProtectionFallback.DEVICE_DNS)
        }
        val consentedMixedRoute = chained.copy(fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
            activeFallback = ProtectionFallback.PLAIN_DNS)
        assertEquals(ProtectionFallback.PLAIN_DNS, consentedMixedRoute.activeFallback)
        assertEquals(NetworkActivityResolverTransport.WIREGUARD_UDP, consentedMixedRoute.resolverTransport)
    }

    @Test
    fun `probe success resolver transport and observed DoH version retain identity`() {
        val deviceUdp = probeSuccess(
            NetworkActivityResolverIdentity.DEVICE_DNS,
            NetworkActivityResolverTransport.DEVICE_UDP,
        )
        val deviceTcp = probeSuccess(
            NetworkActivityResolverIdentity.DEVICE_DNS,
            NetworkActivityResolverTransport.DEVICE_TCP,
        )
        val dohHttp2 = probeSuccess(
            NetworkActivityResolverIdentity.MULLVAD,
            NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            NetworkActivityDohHttpVersion.HTTP_2,
        )
        val dohHttp3 = probeSuccess(
            NetworkActivityResolverIdentity.MULLVAD,
            NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            NetworkActivityDohHttpVersion.HTTP_3,
        )
        val cloudflareHttp2 = probeSuccess(
            NetworkActivityResolverIdentity.CLOUDFLARE,
            NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            NetworkActivityDohHttpVersion.HTTP_2,
        )

        assertEquals(2, recordedPair(deviceUdp, deviceTcp).size)
        assertEquals(2, recordedPair(deviceUdp, dohHttp2).size)
        assertEquals(2, recordedPair(dohHttp2, dohHttp3).size)
        assertEquals(2, recordedPair(dohHttp2, cloudflareHttp2).size)
        assertEquals(1, recordedPair(dohHttp2, dohHttp2).size)
    }

    @Test
    fun `every captured state dimension participates in roll up identity`() {
        val baseDeviceState = healthyState.copy(
            fallbackCoverage = ResolverFallbackCoverage.NONE,
            activeFallback = ProtectionFallback.NONE,
        )
        val variants = listOf(
            baseDeviceState.copy(
                protection = NetworkActivityProtectionState.PAUSED,
            ),
            baseDeviceState.copy(
                connectivity = NetworkActivityConnectivityState.DNS_SLOW,
            ),
            baseDeviceState.copy(
                networkKind = NetworkActivityNetworkKind.CELLULAR,
            ),
            baseDeviceState.copy(
                networkPathSatisfied = false,
            ),
            baseDeviceState.copy(
                resolverTransport = NetworkActivityResolverTransport.DEVICE_TCP,
            ),
            baseDeviceState.copy(
                resolver = NetworkActivityResolverIdentity.MULLVAD,
                resolverTransport = NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            ),
            baseDeviceState.copy(
                fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
            ),
        )

        variants.forEach { changed ->
            assertEquals(
                2,
                recordedPair(
                    firstEvent = NetworkActivityEvent.ProtectionConnected,
                    secondEvent = NetworkActivityEvent.ProtectionConnected,
                    firstState = baseDeviceState,
                    secondState = changed,
                ).size,
            )
        }
        assertEquals(
            1,
            recordedPair(
                firstEvent = NetworkActivityEvent.ProtectionConnected,
                secondEvent = NetworkActivityEvent.ProtectionConnected,
                firstState = baseDeviceState,
                secondState = baseDeviceState.copy(),
            ).size,
        )
        assertEquals(
            2,
            recordedPair(
                firstEvent = NetworkActivityEvent.ProtectionConnected,
                secondEvent = NetworkActivityEvent.ProtectionConnected,
                firstState = baseDeviceState.copy(
                    fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                    activeFallback = ProtectionFallback.NONE,
                ),
                secondState = baseDeviceState.copy(
                    fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                    activeFallback = ProtectionFallback.ENCRYPTED_DNS,
                ),
            ).size,
        )
    }

    @Test
    fun `encrypted and device fallback off idle and active states retain identity`() {
        val fallbackOff = healthyState.copy(
            fallbackCoverage = ResolverFallbackCoverage.NONE,
            activeFallback = ProtectionFallback.NONE,
        )
        val encryptedIdle = healthyState.copy(
            fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
            activeFallback = ProtectionFallback.NONE,
        )
        val encryptedActive = healthyState.copy(
            fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
            activeFallback = ProtectionFallback.ENCRYPTED_DNS,
        )
        val deviceIdle = healthyState.copy(
            resolver = NetworkActivityResolverIdentity.MULLVAD,
            resolverTransport = NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            fallbackCoverage = ResolverFallbackCoverage.DEVICE_DNS,
            activeFallback = ProtectionFallback.NONE,
        )
        val deviceActive = healthyState.copy(
            resolver = NetworkActivityResolverIdentity.MULLVAD,
            resolverTransport = NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            fallbackCoverage = ResolverFallbackCoverage.DEVICE_DNS,
            activeFallback = ProtectionFallback.DEVICE_DNS,
        )
        val states = listOf(
            fallbackOff,
            encryptedIdle,
            encryptedActive,
            deviceIdle,
            deviceActive,
        )

        states.forEachIndexed { firstIndex, first ->
            states.drop(firstIndex + 1).forEach { second ->
                assertEquals(
                    2,
                    recordedPair(
                        firstEvent = NetworkActivityEvent.ProtectionConnected,
                        secondEvent = NetworkActivityEvent.ProtectionConnected,
                        firstState = first,
                        secondState = second,
                    ).size,
                )
            }
        }
        assertEquals(ResolverFallbackCoverage.NONE, fallbackOff.fallbackCoverage)
        assertEquals(ProtectionFallback.NONE, fallbackOff.activeFallback)
        assertEquals(ResolverFallbackCoverage.ENCRYPTED_DNS, encryptedIdle.fallbackCoverage)
        assertEquals(ProtectionFallback.NONE, encryptedIdle.activeFallback)
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, encryptedActive.activeFallback)
        assertEquals(ResolverFallbackCoverage.DEVICE_DNS, deviceIdle.fallbackCoverage)
        assertEquals(ProtectionFallback.NONE, deviceIdle.activeFallback)
        assertEquals(ProtectionFallback.DEVICE_DNS, deviceActive.activeFallback)
    }

    @Test
    fun `captured resolver transport and fallback values must be coherent`() {
        assertThrows(IllegalArgumentException::class.java) {
            healthyState.copy(resolver = NetworkActivityResolverIdentity.NONE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            healthyState.copy(resolverTransport = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            healthyState.copy(
                resolverTransport = NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            healthyState.copy(
                resolver = NetworkActivityResolverIdentity.MULLVAD,
                resolverTransport = NetworkActivityResolverTransport.DEVICE_UDP,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            healthyState.copy(
                fallbackCoverage = ResolverFallbackCoverage.NONE,
                activeFallback = ProtectionFallback.ENCRYPTED_DNS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            healthyState.copy(
                fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                activeFallback = ProtectionFallback.DEVICE_DNS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            healthyState.copy(
                fallbackCoverage = ResolverFallbackCoverage.DEVICE_DNS,
                activeFallback = ProtectionFallback.NONE,
            )
        }
        val encryptedLadder = healthyState.copy(
            resolver = NetworkActivityResolverIdentity.MULLVAD,
            resolverTransport = NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
            activeFallback = ProtectionFallback.NONE,
        )
        assertEquals(ResolverFallbackCoverage.ENCRYPTED_DNS, encryptedLadder.fallbackCoverage)
        assertThrows(IllegalArgumentException::class.java) {
            healthyState.copy(
                resolver = NetworkActivityResolverIdentity.NONE,
                resolverTransport = null,
                fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                activeFallback = ProtectionFallback.NONE,
            )
        }

        val noResolver = healthyState.copy(
            resolver = NetworkActivityResolverIdentity.NONE,
            resolverTransport = null,
            fallbackCoverage = ResolverFallbackCoverage.NONE,
            activeFallback = ProtectionFallback.NONE,
        )
        assertEquals(NetworkActivityResolverIdentity.NONE, noResolver.resolver)
        assertEquals(null, noResolver.resolverTransport)
    }

    @Test
    fun `probe success resolver transport and DoH version must be coherent`() {
        assertThrows(IllegalArgumentException::class.java) {
            probeSuccess(
                NetworkActivityResolverIdentity.NONE,
                NetworkActivityResolverTransport.DEVICE_UDP,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            probeSuccess(
                NetworkActivityResolverIdentity.DEVICE_DNS,
                NetworkActivityResolverTransport.DNS_OVER_HTTPS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            probeSuccess(
                NetworkActivityResolverIdentity.DEVICE_DNS,
                NetworkActivityResolverTransport.DEVICE_UDP,
                NetworkActivityDohHttpVersion.HTTP_2,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            probeSuccess(
                NetworkActivityResolverIdentity.MULLVAD,
                NetworkActivityResolverTransport.DEVICE_TCP,
            )
        }
    }

    @Test
    fun `durable activity entry is a closed domain free payload tree`() {
        val violations = privacyViolations(
            root = NetworkActivityEntry::class.java,
            allowedDirectFields = mapOf(
                NetworkActivityEntry::class.java to setOf(
                    "id",
                    "latestOccurrenceEpochMillis",
                    "firstOccurrenceEpochMillis",
                    "event",
                    "capturedState",
                    "occurrenceCount",
                ),
                NetworkActivityCapturedState::class.java to setOf(
                    "protection",
                    "connectivity",
                    "networkKind",
                    "networkPathSatisfied",
                    "resolver",
                    "resolverTransport",
                    "fallbackCoverage",
                    "activeFallback",
                ),
            ),
            expectedClosedDescendants = activityClosedDescendants(),
        )

        assertTrue(violations.joinToString(separator = "\n"), violations.isEmpty())
    }

    @Test
    fun `privacy guard rejects direct nested inherited and enum backed arbitrary text`() {
        val directViolations = privacyViolations(
            root = UnsafeDirectEntry::class.java,
            allowedDirectFields = mapOf(
                UnsafeDirectEntry::class.java to setOf("id"),
            ),
        )
        val nestedViolations = privacyViolations(root = UnsafeNestedEntry::class.java)
        val enumViolations = privacyViolations(root = UnsafeEnumEntry::class.java)

        assertTrue(directViolations.any { "domain" in it })
        assertTrue(nestedViolations.any { "arbitraryText" in it })
        assertTrue(enumViolations.any { "UnsafeDataBearingReason" in it })
    }

    private fun privacyViolations(
        root: Class<*>,
        allowedDirectFields: Map<Class<*>, Set<String>> = emptyMap(),
        expectedClosedDescendants: Map<Class<*>, Set<Class<*>>> = emptyMap(),
    ): List<String> {
        val violations = mutableListOf<String>()
        val visited = mutableSetOf<Class<*>>()
        val approvedExternalScalarTypes = setOf(
            Boolean::class.javaObjectType,
            Byte::class.javaObjectType,
            Char::class.javaObjectType,
            Double::class.javaObjectType,
            Float::class.javaObjectType,
            Int::class.javaObjectType,
            Long::class.javaObjectType,
            Short::class.javaObjectType,
        )
        val forbiddenFieldNameFragments =
            listOf("domain", "address", "ssid", "packet", "payload", "raw", "text", "detail")

        fun declaredInstanceFields(type: Class<*>): List<Field> =
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }

        fun instanceFieldsIncludingInherited(type: Class<*>): List<Field> {
            val fields = mutableListOf<Field>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java && current != Enum::class.java) {
                fields += declaredInstanceFields(current)
                current = current.superclass
            }
            return fields
        }

        expectedClosedDescendants.forEach { (closedRoot, expectedDescendants) ->
            if (expectedDescendants.isEmpty()) {
                violations += "${closedRoot.name} has no pinned descendants"
            }
            if (!closedRoot.isSealed) {
                violations += "${closedRoot.name} is no longer sealed"
            }
            val actualDescendants = closedRoot.permittedSubclasses.orEmpty().toSet()
            if (actualDescendants.isEmpty()) {
                violations += "${closedRoot.name} has no permitted descendants"
            }
            if (actualDescendants != expectedDescendants) {
                violations +=
                    "${closedRoot.name} descendants changed: " +
                    "expected=${expectedDescendants.map { it.name }.sorted()}, " +
                    "actual=${actualDescendants.map { it.name }.sorted()}"
            }
        }

        fun visit(type: Class<*>) {
            if (!visited.add(type)) return

            allowedDirectFields[type]?.let { expectedFields ->
                val actualFields = declaredInstanceFields(type).mapTo(linkedSetOf()) { it.name }
                if (actualFields != expectedFields) {
                    violations +=
                        "${type.name} direct fields changed: " +
                        "expected=${expectedFields.sorted()}, actual=${actualFields.sorted()}"
                }
            }

            if (type.isEnum) {
                declaredInstanceFields(type).forEach { field ->
                    violations +=
                        "Payload enum ${type.name} carries instance field ${field.name}"
                }
                return
            }

            instanceFieldsIncludingInherited(type).forEach { field ->
                val fieldPath = "${field.declaringClass.name}.${field.name}"
                if (
                    forbiddenFieldNameFragments.any { forbidden ->
                        forbidden in field.name.lowercase()
                    }
                ) {
                    violations += "$fieldPath uses a forbidden privacy-sensitive name"
                }

                val fieldType = field.type
                when {
                    fieldType == String::class.java ->
                        violations += "$fieldPath can carry arbitrary text"
                    fieldType == ByteArray::class.java ->
                        violations += "$fieldPath can carry raw bytes"
                    fieldType.isPrimitive || fieldType in approvedExternalScalarTypes -> Unit
                    fieldType.isEnum -> visit(fieldType)
                    isFoundationValueType(fieldType) -> visit(fieldType)
                    else ->
                        violations +=
                            "$fieldPath uses unapproved payload type ${fieldType.name}"
                }
            }

            if (type.isSealed) {
                type.permittedSubclasses.orEmpty().forEach(::visit)
            }
        }

        visit(root)
        expectedClosedDescendants.keys
            .filterNot(visited::contains)
            .forEach { missing ->
                violations += "${missing.name} is no longer reachable from ${root.name}"
            }
        return violations.distinct()
    }

    private fun isFoundationValueType(type: Class<*>): Boolean =
        type.packageName == "com.lavasec.app.foundation" ||
            type.packageName.startsWith("com.lavasec.app.foundation.")

    private fun activityClosedDescendants(): Map<Class<*>, Set<Class<*>>> = mapOf(
        NetworkActivityEvent::class.java to setOf(
            NetworkActivityEvent.NetworkChanged::class.java,
            NetworkActivityEvent.ProtectionConnected::class.java,
            NetworkActivityEvent.UserAction::class.java,
            NetworkActivityEvent.DnsSmokeProbeSucceeded::class.java,
            NetworkActivityEvent.DnsSmokeProbeFailed::class.java,
            NetworkActivityEvent.FallbackActivated::class.java,
            NetworkActivityEvent.DeviceDnsFallbackRecovered::class.java,
            NetworkActivityEvent.ReconnectNeeded::class.java,
            NetworkActivityEvent.ConnectivityRecovered::class.java,
            NetworkActivityEvent.NetworkSettingsReapplyFailed::class.java,
        ),
        NetworkActivityProbeFailure::class.java to setOf(
            NetworkActivityProbeFailure.ResolverFailure::class.java,
            NetworkActivityProbeFailure.RejectedResponse::class.java,
        ),
        NetworkActivityFallbackActivationCause::class.java to setOf(
            NetworkActivityFallbackActivationCause.ProducerUnspecified::class.java,
            NetworkActivityFallbackActivationCause.QueryFallback::class.java,
            NetworkActivityFallbackActivationCause.FallbackCheck::class.java,
            NetworkActivityFallbackActivationCause.PrimaryFailure::class.java,
        ),
        NetworkActivityReconnectReason::class.java to setOf(
            NetworkActivityReconnectReason.ResolverFailure::class.java,
            NetworkActivityReconnectReason.RejectedResponse::class.java,
            NetworkActivityReconnectReason.DeviceDnsRecapture::class.java,
            NetworkActivityReconnectReason.RuntimeUnavailable::class.java,
        ),
    )

    private data class UnsafeDirectEntry(
        val id: Long,
        val domain: String,
    )

    private data class UnsafeNestedEntry(
        val evidence: UnsafeNestedValue,
    )

    private open class UnsafeNestedBase(
        val arbitraryText: String,
    )

    private class UnsafeNestedValue(
        arbitraryText: String,
    ) : UnsafeNestedBase(arbitraryText)

    private data class UnsafeEnumEntry(
        val reason: UnsafeDataBearingReason,
    )

    private enum class UnsafeDataBearingReason(
        val rawText: String,
    ) {
        REJECTED("unsafe"),
    }

    @Test
    fun `roll up excludes wider gaps`() {
        val event = NetworkActivityEvent.ReconnectNeeded(
            NetworkActivityReconnectReason.ResolverFailure(
                ResolverFailureReason.TIMEOUT,
            ),
        )
        val first = entry(
            id = 1L,
            latestOccurrenceEpochMillis = now - 120_001L,
            event = event,
        )

        val result = NetworkActivityContract.record(
            entries = listOf(first),
            incoming = entry(
                id = 2L,
                latestOccurrenceEpochMillis = now,
                event = event,
            ),
            nowEpochMillis = now,
        )

        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    @Test
    fun `duplicate retained row identities are rejected by normalize and record`() {
        val first = entry(
            id = 7L,
            latestOccurrenceEpochMillis = now - 1L,
            event = NetworkActivityEvent.ProtectionConnected,
        )
        val collision = entry(
            id = 7L,
            latestOccurrenceEpochMillis = now,
            event = networkChanged(to = NetworkActivityNetworkKind.CELLULAR),
        )

        assertThrows(IllegalArgumentException::class.java) {
            NetworkActivityContract.normalize(listOf(first, collision), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkActivityContract.record(listOf(first), collision, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkActivityContract.record(listOf(first, collision), entry(8L, now), now)
        }
    }

    @Test
    fun `retention is anchored to first occurrence even when latest occurrence advances`() {
        val inclusiveCutoff = now - 7L * 24L * 60L * 60L * 1_000L
        val retained = entry(
            id = 1L,
            latestOccurrenceEpochMillis = now,
            firstOccurrenceEpochMillis = inclusiveCutoff,
            event = NetworkActivityEvent.ConnectivityRecovered(
                NetworkActivityRecoveryReason.PRIMARY_RESOLVER,
            ),
            occurrenceCount = 10,
        )
        val expired = retained.copy(
            id = 2L,
            firstOccurrenceEpochMillis = inclusiveCutoff - 1L,
        )

        assertEquals(listOf(1L), NetworkActivityContract.normalize(listOf(expired, retained), now).map { it.id })
    }

    @Test
    fun `expired and implausibly future incoming evidence is rejected without poisoning live rows`() {
        val live = entry(
            id = 1L,
            latestOccurrenceEpochMillis = now - 60_000L,
        )
        val expiredIncoming = entry(
            id = 2L,
            latestOccurrenceEpochMillis = now - 60_000L,
            firstOccurrenceEpochMillis = now - 7L * 24L * 60L * 60L * 1_000L - 1L,
        )
        val futureIncoming = entry(
            id = 3L,
            latestOccurrenceEpochMillis = now + 5L * 60L * 1_000L + 1L,
            event = networkChanged(to = NetworkActivityNetworkKind.CELLULAR),
        )

        assertFalse(NetworkActivityContract.isRetainable(expiredIncoming, now))
        assertFalse(NetworkActivityContract.isRetainable(futureIncoming, now))
        assertEquals(listOf(live), NetworkActivityContract.record(listOf(live), expiredIncoming, now))
        assertEquals(listOf(live), NetworkActivityContract.record(listOf(live), futureIncoming, now))
    }

    @Test
    fun `inclusive retention and future bounds remain retainable`() {
        val cutoff = now - 7L * 24L * 60L * 60L * 1_000L
        val futureBound = now + 5L * 60L * 1_000L

        assertTrue(
            NetworkActivityContract.isRetainable(
                entry(
                    id = 1L,
                    latestOccurrenceEpochMillis = cutoff,
                    event = NetworkActivityEvent.ProtectionConnected,
                ),
                now,
            ),
        )
        assertTrue(
            NetworkActivityContract.isRetainable(
                entry(
                    id = 2L,
                    latestOccurrenceEpochMillis = futureBound,
                    event = networkChanged(to = NetworkActivityNetworkKind.CELLULAR),
                ),
                now,
            ),
        )
    }

    private fun recordedPair(
        firstEvent: NetworkActivityEvent,
        secondEvent: NetworkActivityEvent,
        firstState: NetworkActivityCapturedState = healthyState,
        secondState: NetworkActivityCapturedState = healthyState,
    ): List<NetworkActivityEntry> = NetworkActivityContract.record(
        entries = listOf(
            entry(
                id = 1L,
                latestOccurrenceEpochMillis = now - 60_000L,
                event = firstEvent,
                state = firstState,
            ),
        ),
        incoming = entry(
            id = 2L,
            latestOccurrenceEpochMillis = now,
            event = secondEvent,
            state = secondState,
        ),
        nowEpochMillis = now,
    )

    private fun networkChanged(
        to: NetworkActivityNetworkKind,
    ) = NetworkActivityEvent.NetworkChanged(
        from = NetworkActivityNetworkKind.WIFI,
        to = to,
        pathSatisfied = true,
    )

    private fun probeSuccess(
        resolver: NetworkActivityResolverIdentity,
        transport: NetworkActivityResolverTransport,
        dohHttpVersion: NetworkActivityDohHttpVersion? = null,
    ) = NetworkActivityEvent.DnsSmokeProbeSucceeded(
        resolver = resolver,
        transport = transport,
        dohHttpVersion = dohHttpVersion,
    )

    private fun entry(
        id: Long,
        latestOccurrenceEpochMillis: Long,
        event: NetworkActivityEvent =
            NetworkActivityEvent.DnsSmokeProbeFailed(
                NetworkActivityProbeFailure.ResolverFailure(
                    ResolverFailureReason.TIMEOUT,
                ),
            ),
        state: NetworkActivityCapturedState = healthyState,
        occurrenceCount: Int = 1,
        firstOccurrenceEpochMillis: Long = latestOccurrenceEpochMillis,
    ) = NetworkActivityEntry(
        id = id,
        latestOccurrenceEpochMillis = latestOccurrenceEpochMillis,
        firstOccurrenceEpochMillis = firstOccurrenceEpochMillis,
        event = event,
        capturedState = state,
        occurrenceCount = occurrenceCount,
    )
}
