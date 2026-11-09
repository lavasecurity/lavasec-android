package com.lavasec.app.platform

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM tests for the composed [AppConfigurationWitness]. */
class AppConfigurationWitnessTest {

    private fun profiles(
        balancedName: String = "Balanced",
        balancedSources: Set<String> = setOf("sec-basic", "multi"),
        balancedAllowed: Set<String> = emptySet(),
        balancedBlocked: Set<String> = emptySet(),
        balancedEmoji: String? = null,
    ): List<FilterProfileDraft> = listOf(
        FilterProfileDraft("filter-essential", "Core", ProtectionLevel.CORE, setOf("sec-basic")),
        FilterProfileDraft(
            id = "filter-balanced",
            name = balancedName,
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = balancedSources,
            manualRules = ManualDomainRules(balancedAllowed, balancedBlocked),
            emoji = balancedEmoji,
        ),
        FilterProfileDraft(
            "filter-comprehensive",
            "Extra",
            ProtectionLevel.EXTRA,
            setOf("sec-basic", "multi", "ads"),
        ),
    )

    private fun library(
        profiles: List<FilterProfileDraft> = profiles(),
        activeProfileId: String = "filter-balanced",
        resolverPlan: MilestoneResolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
    ): FilterLibraryDraft = FilterLibraryDraft(profiles, activeProfileId, resolverPlan)

    private fun capture(
        library: FilterLibraryDraft = library(),
        protectionLevel: ProtectionLevel = ProtectionLevel.BALANCED,
        privacy: PrivacyRetentionChoices = PrivacyRetentionChoices.Known(true, true, true, true),
        entitlement: EntitlementTierFact = EntitlementTierFact.Known(SubscriptionTier.FREE),
        generation: Long = 7L,
    ): AppConfigurationWitness = AppConfigurationWitness.capture(
        AppConfigurationWitnessFacts(library, protectionLevel, privacy, entitlement),
        generation,
    )

    @Test
    fun `identical captures match and are canonical sha256`() {
        val first = capture()
        val second = capture()

        assertEquals(first.sha256, second.sha256)
        assertTrue(first.matches(second))
        assertTrue(first.unchangedSince(second))
        assertTrue(first.contentMatches(second))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(64, first.sha256.length)
        assertTrue(first.sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `retained built-in row names invalidate review independently of endpoint identity`() {
        val rows = listOf(com.lavasec.app.foundation.resolver.ResolverTier(
            com.lavasec.app.foundation.resolver.ResolverSelectionPresets.DEVICE_PLAIN, true, "Network"),
            com.lavasec.app.foundation.resolver.ResolverTier(
                com.lavasec.app.foundation.resolver.ResolverSelectionPresets.MULLVAD_DOH, false, "Fallback"))
        fun witness(tiers: List<com.lavasec.app.foundation.resolver.ResolverTier>) = capture(library = library()
            .withResolverPlan(checkNotNull(com.lavasec.app.foundation.resolver.ResolverSelection(tiers).toFilterResolverPlan())))
        val initial = witness(rows)
        val changed = witness(listOf(rows.first(), rows.last().copy(retainedName = "Other label")))
        assertFalse(initial.contentMatches(changed))
    }

    @Test
    fun `review witness binds fixed plain primary and secondary without inventing scalar custom authority`() {
        fun witness(primary: String = "192.168.1.53", secondary: String = "192.168.1.54"): AppConfigurationWitness {
            val entry = com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
                com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
                com.lavasec.app.foundation.resolver.CatalogResolverTransport.PLAIN_DNS,
                customPlainEndpoint = primary, customSecondaryEndpoint = secondary, customName = "Private resolver")
            val plan = checkNotNull(com.lavasec.app.foundation.resolver.ResolverSelection(listOf(
                com.lavasec.app.foundation.resolver.ResolverTier(entry))).toFilterResolverPlan())
            assertNull(plan.customName)
            assertNull(plan.customSecondaryEndpoint)
            return capture(library = library().withResolverPlan(plan))
        }
        val original = witness()
        assertTrue(original.contentMatches(witness()))
        assertFalse(original.contentMatches(witness(primary = "192.168.1.55")))
        assertFalse(original.contentMatches(witness(secondary = "192.168.1.55")))
    }

    @Test
    fun `review witness binds stamp bootstrap changes even when HTTPS endpoints stay equal`() {
        val primary = "sdns://AgAAAAAAAAAABzEuMS4xLjEAEmNsb3VkZmxhcmUtZG5zLmNvbQovZG5zLXF1ZXJ5hzEuMC4wLjEUMjYwNjo0NzAwOjQ3MDA6OjExMTE"
        val secondary = "sdns://AgAAAAAAAAAABzguOC44LjgACmRucy5nb29nbGUKL2Rucy1xdWVyeYc4LjguNC40FDIwMDE6NDg2MDo0ODYwOjo4ODg4"
        fun changedBootstrap(raw: String): String {
            val bytes = java.util.Base64.getUrlDecoder().decode(raw.substring(7))
            bytes[bytes.lastIndex] = '2'.code.toByte()
            return "sdns://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        fun witness(first: String, second: String): AppConfigurationWitness {
            val entry = com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
                com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
                com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOH,
                customDohUrl = first, customSecondaryEndpoint = second)
            val rows = listOf(com.lavasec.app.foundation.resolver.ResolverTier(entry),
                com.lavasec.app.foundation.resolver.ResolverTier(
                    com.lavasec.app.foundation.resolver.ResolverSelectionPresets.DEVICE_PLAIN, false, "Retained network"))
            return capture(library = library().withResolverPlan(checkNotNull(
                com.lavasec.app.foundation.resolver.ResolverSelection(rows).toFilterResolverPlan())))
        }
        val changedPrimary = changedBootstrap(primary)
        val changedSecondary = changedBootstrap(secondary)
        assertEquals(com.lavasec.app.foundation.resolver.CustomResolverValidator.resolvedDohUrl(primary),
            com.lavasec.app.foundation.resolver.CustomResolverValidator.resolvedDohUrl(changedPrimary))
        assertNotEquals(com.lavasec.app.foundation.resolver.CustomResolverValidator.bootstrapAddresses(primary),
            com.lavasec.app.foundation.resolver.CustomResolverValidator.bootstrapAddresses(changedPrimary))
        val original = witness(primary, secondary)
        assertTrue(original.contentMatches(witness(primary, secondary)))
        assertFalse(original.contentMatches(witness(changedPrimary, secondary)))
        assertFalse(original.contentMatches(witness(primary, changedSecondary)))
    }

    @Test
    fun `a filter name change changes the witness`() {
        val renamed = capture(library = library(profiles = profiles(balancedName = "Balanced Renamed")))
        assertWitnessChanged(renamed)
    }

    @Test
    fun `a filter content change changes the witness`() {
        val added = capture(library = library(profiles = profiles(balancedSources = setOf("sec-basic", "multi", "ads"))))
        assertWitnessChanged(added)
    }

    @Test
    fun `a manual allowed or blocked rule change changes the witness`() {
        assertWitnessChanged(
            capture(library = library(profiles = profiles(balancedAllowed = setOf("allowed.example.com")))),
        )
        assertWitnessChanged(
            capture(library = library(profiles = profiles(balancedBlocked = setOf("blocked.example.com")))),
        )
    }

    @Test
    fun `a filter emoji change changes the witness`() {
        assertWitnessChanged(capture(library = library(profiles = profiles(balancedEmoji = "\uD83D\uDD25"))))
    }

    @Test
    fun `a filter id change changes the witness`() {
        val renamedId = profiles().map { profile ->
            if (profile.id == "filter-balanced") {
                FilterProfileDraft(
                    "filter-balanced-v2",
                    profile.name,
                    profile.protectionLevel,
                    profile.enabledSourceIds,
                    profile.manualRules,
                    profile.emoji,
                )
            } else {
                profile
            }
        }
        val rebound = library(profiles = renamedId, activeProfileId = "filter-balanced-v2")
        assertWitnessChanged(capture(library = rebound))
    }

    @Test
    fun `a resolver plan change changes the witness`() {
        assertWitnessChanged(
            capture(library = library(resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY)),
        )
        assertWitnessChanged(
            capture(library = library(resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY)),
        )
    }

    @Test
    fun `an active filter change changes the witness`() {
        assertWitnessChanged(capture(library = library(activeProfileId = "filter-essential")))
    }

    @Test
    fun `a protection level change changes the witness`() {
        assertWitnessChanged(capture(protectionLevel = ProtectionLevel.EXTRA))
    }

    @Test
    fun `each privacy retention choice changes the witness`() {
        val base = PrivacyRetentionChoices.Known(true, true, true, true)
        assertWitnessChanged(capture(privacy = base.copy(keepCounts = false)))
        assertWitnessChanged(capture(privacy = base.copy(keepHistory = false)))
        assertWitnessChanged(capture(privacy = base.copy(keepNetworkActivity = false)))
        assertWitnessChanged(capture(privacy = base.copy(keepLavaGuardProgress = false)))
    }

    @Test
    fun `an unknown privacy or entitlement fact is not equal to a default`() {
        assertWitnessChanged(capture(privacy = PrivacyRetentionChoices.Unknown))
        assertWitnessChanged(capture(entitlement = EntitlementTierFact.Unknown))
    }

    @Test
    fun `a tier change changes the witness`() {
        assertWitnessChanged(capture(entitlement = EntitlementTierFact.Known(SubscriptionTier.PLUS)))
    }

    @Test
    fun `profile and source ordering is canonical`() {
        val ascending = capture(
            library = library(
                profiles = listOf(
                    FilterProfileDraft("filter-essential", "Core", ProtectionLevel.CORE, setOf("a", "b", "c")),
                    FilterProfileDraft("filter-balanced", "Balanced", ProtectionLevel.BALANCED, setOf("d", "e")),
                ),
                activeProfileId = "filter-balanced",
            ),
        )
        val reordered = capture(
            library = library(
                profiles = listOf(
                    FilterProfileDraft("filter-balanced", "Balanced", ProtectionLevel.BALANCED, setOf("e", "d")),
                    FilterProfileDraft("filter-essential", "Core", ProtectionLevel.CORE, setOf("c", "a", "b")),
                ),
                activeProfileId = "filter-balanced",
            ),
        )

        assertEquals(ascending.sha256, reordered.sha256)
        assertTrue(ascending.matches(reordered))
    }

    @Test
    fun `a version prefix prevents cross-version acceptance`() {
        val facts = AppConfigurationWitnessFacts(
            library(),
            ProtectionLevel.BALANCED,
            PrivacyRetentionChoices.Known(true, true, true, true),
            EntitlementTierFact.Known(SubscriptionTier.FREE),
        )
        val v1 = AppConfigurationWitness.captureAtVersion(facts, generation = 7L, version = 1)
        val v2 = AppConfigurationWitness.captureAtVersion(facts, generation = 7L, version = 2)

        assertNotEquals(v1.sha256, v2.sha256)
        assertFalse(v1.matches(v2))
        assertFalse(v1.contentMatches(v2))
        assertFalse(v1.unchangedSince(v2))
    }

    @Test
    fun `a generation bump supersedes a content-equal capture`() {
        val before = capture(generation = 7L)
        val after = capture(generation = 8L)

        assertEquals(before.sha256, after.sha256)
        assertTrue(before.contentMatches(after))
        assertFalse(before.matches(after))
        assertFalse(before.unchangedSince(after))
    }

    @Test
    fun `a fifty filter library hashes deterministically and a tail change moves it`() {
        fun largeLibrary(tailSource: String = "tail-source-a"): FilterLibraryDraft {
            val profiles = (0 until 50).map { index ->
                FilterProfileDraft(
                    id = "user-filter-$index",
                    name = "Filter $index",
                    protectionLevel = null,
                    enabledSourceIds = if (index == 49) setOf(tailSource) else setOf("source-$index"),
                )
            }
            return FilterLibraryDraft(profiles, activeProfileId = "user-filter-0", resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY)
        }

        val first = capture(library = largeLibrary())
        val second = capture(library = largeLibrary())

        assertEquals(first.sha256, second.sha256)
        assertEquals(64, first.sha256.length)

        assertWitnessChanged(capture(library = largeLibrary(tailSource = "tail-source-b")))
    }

    // --- B02 production capture, parity fixtures and atomic publication ---

    @Test
    fun `production capture reflects the persisted configuration logging resolver and tier`() {
        val state = persistedState(
            configuration = AppConfiguration.default().copy(
                protectionLevel = ProtectionLevel.EXTRA,
                resolver = MilestoneResolverPlan.DEVICE_DNS_ONLY.configuration,
            ),
            logging = LoggingSettings(
                keepCounts = false,
                keepHistory = true,
                keepNetworkActivity = false,
                keepLavaGuardProgress = true,
            ),
        )
        val library = library(resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val facts = AppConfigurationWitnessFacts.fromPersisted(
            state = state,
            library = library,
            entitlement = EntitlementTierFact.Known(SubscriptionTier.PLUS),
        )
        val production = AppConfigurationWitness.capture(facts, generation = 42L)

        val expected = capture(
            library = library,
            protectionLevel = ProtectionLevel.EXTRA,
            privacy = PrivacyRetentionChoices.Known(false, true, false, true),
            entitlement = EntitlementTierFact.Known(SubscriptionTier.PLUS),
            generation = 42L,
        )
        assertEquals(expected.sha256, production.sha256)
        assertEquals(expected.generation, production.generation)

        // Every production fact moves the witness; each is asserted against the captured value.
        val toggled = AppConfigurationWitness.capture(
            AppConfigurationWitnessFacts.fromPersisted(
                state.copy(logging = state.logging.copy(keepCounts = true)),
                library,
                EntitlementTierFact.Known(SubscriptionTier.PLUS),
            ),
            generation = 42L,
        )
        assertNotEquals(production.sha256, toggled.sha256)

        val resolverMoved = AppConfigurationWitness.capture(
            AppConfigurationWitnessFacts.fromPersisted(
                state.copy(
                    configuration = state.configuration!!.copy(resolver = MilestoneResolverPlan.MULLVAD_ONLY.configuration),
                ),
                library(resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY),
                EntitlementTierFact.Known(SubscriptionTier.PLUS),
            ),
            generation = 42L,
        )
        assertNotEquals(production.sha256, resolverMoved.sha256)

        val tierMoved = AppConfigurationWitness.capture(
            AppConfigurationWitnessFacts.fromPersisted(state, library, EntitlementTierFact.Known(SubscriptionTier.FREE)),
            generation = 42L,
        )
        assertNotEquals(production.sha256, tierMoved.sha256)
    }

    @Test
    fun `production capture binds the custom DoH endpoint and moves when the URL changes`() {
        val url = "https://dns.google/dns-query"
        fun customLibrary(endpoint: String) = FilterLibraryDraft(
            profiles = profiles(),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            customDohUrl = endpoint,
        )
        fun customState(endpoint: String) = persistedState(
            configuration = AppConfiguration.default().copy(
                resolver = ResolverConfiguration(
                    useDeviceDns = false,
                    useEncryptedFallback = true,
                    customDohUrl = endpoint,
                ),
            ),
        )

        val captured = AppConfigurationWitness.capture(
            AppConfigurationWitnessFacts.fromPersisted(
                customState(url),
                customLibrary(url),
                EntitlementTierFact.Known(SubscriptionTier.PLUS),
            ),
            generation = 7L,
        )
        val moved = AppConfigurationWitness.capture(
            AppConfigurationWitnessFacts.fromPersisted(
                customState("https://other.example/query"),
                customLibrary("https://other.example/query"),
                EntitlementTierFact.Known(SubscriptionTier.PLUS),
            ),
            generation = 7L,
        )

        assertNotEquals(captured.sha256, moved.sha256)

        // A torn read (persisted URL disagrees with the library plan) is refused, never witnessed.
        assertThrowsIllegalArgument {
            AppConfigurationWitnessFacts.fromPersisted(
                customState(url),
                customLibrary("https://other.example/query"),
                EntitlementTierFact.Known(SubscriptionTier.PLUS),
            )
        }
    }

    @Test
    fun `production capture binds the custom DoT endpoint and moves when it changes`() {
        val endpoint = "tls://dns.google:853"
        fun customLibrary(value: String) = FilterLibraryDraft(
            profiles = profiles(),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            customDotEndpoint = value,
        )
        fun customState(value: String) = persistedState(
            configuration = AppConfiguration.default().copy(
                resolver = ResolverConfiguration(
                    useDeviceDns = false,
                    useEncryptedFallback = true,
                    customDotEndpoint = value,
                ),
            ),
        )

        val captured = AppConfigurationWitness.capture(
            AppConfigurationWitnessFacts.fromPersisted(
                customState(endpoint),
                customLibrary(endpoint),
                EntitlementTierFact.Known(SubscriptionTier.PLUS),
            ),
            generation = 7L,
        )
        val moved = AppConfigurationWitness.capture(
            AppConfigurationWitnessFacts.fromPersisted(
                customState("tls://dns.quad9.net:853"),
                customLibrary("tls://dns.quad9.net:853"),
                EntitlementTierFact.Known(SubscriptionTier.PLUS),
            ),
            generation = 7L,
        )

        assertNotEquals(captured.sha256, moved.sha256)

        // A torn read (persisted endpoint disagrees with the library plan) is refused, never witnessed.
        assertThrowsIllegalArgument {
            AppConfigurationWitnessFacts.fromPersisted(
                customState(endpoint),
                customLibrary("tls://dns.quad9.net:853"),
                EntitlementTierFact.Known(SubscriptionTier.PLUS),
            )
        }
    }

    @Test
    fun `a prior-version capture is rejected by the current encoding`() {
        val facts = AppConfigurationWitnessFacts(
            library(),
            ProtectionLevel.BALANCED,
            PrivacyRetentionChoices.Known(true, true, true, true),
            EntitlementTierFact.Known(SubscriptionTier.FREE),
        )
        val prior = AppConfigurationWitness.captureAtVersion(facts, generation = 7L, version = 1)
        val current = AppConfigurationWitness.capture(facts, generation = 7L)

        assertEquals(1, prior.version)
        assertEquals(AppConfigurationWitness.CURRENT_VERSION, current.version)
        assertEquals(2, current.version)
        assertNotEquals(prior.sha256, current.sha256)
        assertFalse(current.matches(prior))
        assertFalse(current.contentMatches(prior))
        assertFalse(current.unchangedSince(prior))
        assertNotEquals(prior, current)
        // The prior-version capture remains a valid witness in its own identity space.
        assertTrue(
            prior.contentMatches(AppConfigurationWitness.captureAtVersion(facts, generation = 9L, version = 1)),
        )
    }

    @Test
    fun `interrupted write is refused and leaves the prior witness visible`() {
        val slot = AppConfigurationWitnessSlot()
        val coherentState = persistedState()
        val coherentConfiguration = checkNotNull(coherentState.configuration)
        val coherentLibrary = library(activeProfileId = "filter-balanced")
        val published = slot.publish(
            AppConfigurationWitnessFacts.fromPersisted(
                coherentState,
                coherentLibrary,
                EntitlementTierFact.Known(SubscriptionTier.FREE),
            ),
            generation = 7L,
        )

        // Torn pointer: the persisted active filter advanced but the library did not.
        val tornPointer = coherentState.copy(
            configuration = coherentConfiguration.copy(activeFilterId = "filter-essential"),
            activeFilter = FilterMetadata("filter-essential", "Core", ProtectionLevel.CORE),
        )
        assertThrowsIllegalArgument {
            AppConfigurationWitnessFacts.fromPersisted(
                tornPointer,
                coherentLibrary,
                EntitlementTierFact.Known(SubscriptionTier.FREE),
            )
        }
        assertEquals(published, slot.current)
        assertEquals(7L, slot.current!!.generation)

        // Torn resolver: the persisted resolver moved but the library plan did not.
        val tornResolver = coherentState.copy(
            configuration = coherentConfiguration.copy(
                resolver = MilestoneResolverPlan.DEVICE_DNS_ONLY.configuration,
            ),
        )
        assertThrowsIllegalArgument {
            AppConfigurationWitnessFacts.fromPersisted(
                tornResolver,
                coherentLibrary,
                EntitlementTierFact.Known(SubscriptionTier.FREE),
            )
        }
        assertEquals(published, slot.current)

        // The completed write publishes one new coherent witness, never a partial one.
        val completed = slot.publish(
            AppConfigurationWitnessFacts.fromPersisted(
                tornPointer,
                library(activeProfileId = "filter-essential"),
                EntitlementTierFact.Known(SubscriptionTier.FREE),
            ),
            generation = 8L,
        )
        assertNotEquals(published.sha256, completed.sha256)
        assertEquals(completed, slot.current)
        assertEquals(8L, slot.current!!.generation)
    }

    @Test
    fun `a full-capacity library of fifty filters with a thousand rules each hashes and moves on a tail change`() {
        fun fullLibrary(tailRule: String = "allowed-49-999.example.com"): FilterLibraryDraft {
            val profiles = (0 until 50).map { index ->
                val allowed = (0 until 1_000).map { "allowed-$index-$it.example.com" }.toSet()
                val blocked = (0 until 1_000).map { "blocked-$index-$it.example.com" }.toSet()
                FilterProfileDraft(
                    id = "user-filter-$index",
                    name = "Filter $index",
                    protectionLevel = null,
                    enabledSourceIds = setOf("source-$index"),
                    manualRules = if (index == 49) {
                        // Replace one allowed rule so the library stays exactly at capacity while a
                        // single tail edit still moves the witness.
                        ManualDomainRules((allowed - "allowed-49-999.example.com") + tailRule, blocked)
                    } else {
                        ManualDomainRules(allowed, blocked)
                    },
                )
            }
            return FilterLibraryDraft(
                profiles,
                activeProfileId = "user-filter-0",
                resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            )
        }

        val full = fullLibrary()
        assertEquals(50, full.profiles.size)
        assertEquals(1_000, full.profiles.first().manualRules.allowedDomains.size)
        assertEquals(1_000, full.profiles.first().manualRules.blockedDomains.size)
        assertEquals(1_000, full.profile("user-filter-49")!!.manualRules.allowedDomains.size)

        val first = capture(library = full)
        val second = capture(library = fullLibrary())
        assertEquals(first.sha256, second.sha256)
        assertEquals(64, first.sha256.length)

        val moved = capture(library = fullLibrary(tailRule = "allowed-49-999b.example.com"))
        assertNotEquals(first.sha256, moved.sha256)
        assertFalse(first.contentMatches(moved))
    }

    @Test
    fun `canonically equivalent names share the Swift identity while a trailing space does not`() {
        // Swift FilterIdentityPolicyTests: ("Café", …) and ("Cafe\u{301}", …) are not a change.
        val nfc = capture(library = library(profiles = profiles(balancedName = "Café")))
        val nfd = capture(library = library(profiles = profiles(balancedName = "Cafe\u0301")))
        assertEquals(nfc.sha256, nfd.sha256)
        assertTrue(nfc.contentMatches(nfd))

        // ("Café ", …) IS a change in Swift, so it must move the witness.
        val trailing = capture(library = library(profiles = profiles(balancedName = "Café ")))
        assertNotEquals(nfc.sha256, trailing.sha256)

        // A real rename moves the witness.
        val renamed = capture(library = library(profiles = profiles(balancedName = "Home")))
        assertNotEquals(nfc.sha256, renamed.sha256)
    }

    @Test
    fun `emoji identity falls back exactly as the Swift fixtures require`() {
        val valid = capture(library = library(profiles = profiles(balancedEmoji = "👩🏽‍💻")))
        assertEquals(valid.sha256, capture(library = library(profiles = profiles(balancedEmoji = "👩🏽‍💻"))).sha256)

        // Swift rejects "❤" (no emoji presentation) and "🏽" (a bare modifier); FilterProfileDraft
        // replaces them with the deterministic default, exactly like the absent value.
        val absent = capture(library = library(profiles = profiles(balancedEmoji = null)))
        assertEquals(absent.sha256, capture(library = library(profiles = profiles(balancedEmoji = "❤"))).sha256)
        assertEquals(absent.sha256, capture(library = library(profiles = profiles(balancedEmoji = "🏽"))).sha256)

        // A genuine emoji change moves the identity.
        assertNotEquals(valid.sha256, capture(library = library(profiles = profiles(balancedEmoji = "🌿"))).sha256)
    }

    @Test
    fun `publication is an atomic complete swap and a refused capture leaves the prior witness`() {
        val slot = AppConfigurationWitnessSlot()
        assertNull(slot.current)

        val firstFacts = AppConfigurationWitnessFacts(
            library(),
            ProtectionLevel.BALANCED,
            PrivacyRetentionChoices.Known(true, true, true, true),
            EntitlementTierFact.Known(SubscriptionTier.FREE),
        )
        val first = slot.publish(firstFacts, generation = 7L)
        assertEquals(first, slot.current)
        assertEquals(7L, slot.current!!.generation)

        // A torn production read throws while composing the facts, before any publication.
        assertThrowsIllegalArgument {
            val torn = persistedState().copy(
                configuration = AppConfiguration.default().copy(activeFilterId = "filter-essential"),
            )
            val facts = AppConfigurationWitnessFacts.fromPersisted(
                torn,
                library(activeProfileId = "filter-balanced"),
                EntitlementTierFact.Known(SubscriptionTier.FREE),
            )
            slot.publish(facts, generation = 8L)
        }
        assertEquals(first, slot.current)

        // Two writers composing against the same observed witness: exactly one CAS wins.
        val observed = slot.current
        val secondFacts = firstFacts.copy(library = library(activeProfileId = "filter-essential"))
        assertTrue(slot.publishIfCurrent(observed, secondFacts, generation = 8L))
        assertFalse(slot.publishIfCurrent(observed, firstFacts, generation = 9L))
        assertEquals(8L, slot.current!!.generation)
        assertNotEquals(first.sha256, slot.current!!.sha256)

        // An already-captured witness publishes whole, and clearing leaves no stale identity.
        val preCaptured = AppConfigurationWitness.capture(firstFacts, generation = 11L)
        assertEquals(preCaptured, slot.publish(preCaptured))
        assertEquals(preCaptured, slot.current)
        slot.clear()
        assertNull(slot.current)
    }

    private fun persistedState(
        configuration: AppConfiguration = AppConfiguration.default(),
        logging: LoggingSettings = LoggingSettings(),
        activeFilter: FilterMetadata = FilterMetadata("filter-balanced", "Balanced", ProtectionLevel.BALANCED),
    ): PersistedAppState = PersistedAppState(
        onboardingCompleted = true,
        configuration = configuration,
        protectionIntent = false,
        activeFilter = activeFilter,
        logging = logging,
    )

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected: the witness refuses a torn/incoherent production read
        }
    }

    private fun assertWitnessChanged(changed: AppConfigurationWitness) {
        val baseline = capture()
        assertNotEquals(baseline.sha256, changed.sha256)
        assertFalse(baseline.matches(changed))
        assertFalse(baseline.unchangedSince(changed))
    }
}
