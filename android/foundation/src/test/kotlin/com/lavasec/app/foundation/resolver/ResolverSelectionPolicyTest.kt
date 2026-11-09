package com.lavasec.app.foundation.resolver

import com.lavasec.app.foundation.subscription.FeatureLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverSelectionPolicyTest {
    private val plus = FeatureLimits.PLUS
    private val free = FeatureLimits.FREE
    private val editable = ChainedDnsEditState.editable
    private val catalogue = ResolverCatalogue.canonical

    private fun evaluate(
        request: ResolverEditRequest,
        current: ResolverSelection = ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        limits: FeatureLimits = plus,
        chained: ChainedDnsEditState = editable,
        editorContext: String? = null,
    ): ResolverEditResult = ResolverSelectionPolicy.evaluate(
        request = request,
        current = current,
        limits = limits,
        chained = chained,
        catalogue = catalogue,
        editorContext = editorContext,
    )

    private fun accepted(result: ResolverEditResult): ResolverSelectionCommit {
        assertTrue("expected an accepted edit but was $result", result is ResolverEditResult.Accepted)
        return (result as ResolverEditResult.Accepted).commit
    }

    private fun assertRejected(reason: ResolverEditRejection, result: ResolverEditResult) {
        assertTrue("expected rejection $reason but was $result", result is ResolverEditResult.Rejected)
        assertEquals(reason, (result as ResolverEditResult.Rejected).rejection)
    }

    private fun tier(providerRawId: String, transportToken: String, isEnabled: Boolean = true) =
        ResolverTierChoice(providerRawId, transportToken, isEnabled = isEnabled)

    @Test
    fun `saving another tier preserves unchanged custom row name and separate retained label`() {
        val custom = ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOT,
            customDotEndpoint = "tls://dns.example:8853", customName = "Resolver name",
            customSecondaryEndpoint = "tls://backup.example:8853"), false, "Retained row label")
        val current = ResolverSelection(listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), custom))
        val result = accepted(evaluate(SaveResolverTiers(listOf(
            ResolverTierChoice(ResolverProviderId.CLOUDFLARE.rawId, "DoH", "Changed first row"), custom.toChoice())), current = current))
        assertEquals(custom, result.selection.tiers[1])
        // Reusing metadata must not bypass the live custom-resolver entitlement gate.
        assertRejected(ResolverEditRejection.CustomRequiresPlus,
            evaluate(SaveResolverTiers(listOf(current.tiers[0].toChoice(), custom.toChoice())), current = current, limits = free))
    }

    // -- provider / transport / fallback combinations -------------------------------

    @Test
    fun `device primary with mullvad doh fallback is the canonical accepted ladder`() {
        val commit = accepted(
            evaluate(SaveResolverTiers(listOf(tier("device-dns", "IP"), tier("mullvad", "DoH")))),
        )
        assertEquals(ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK, commit.selection)
        assertEquals(listOf("device-dns", "mullvad"), commit.tiers.map { it.providerId })
        assertEquals(listOf("IP", "DoH"), commit.tiers.map { it.transportToken })
    }

    @Test
    fun `a single primary tier is accepted`() {
        val commit = accepted(evaluate(SaveResolverTiers(listOf(tier("mullvad", "DoH")))))
        assertEquals(ResolverSelectionPresets.MULLVAD_ONLY, commit.selection)
        assertEquals(listOf(tier("mullvad", "DoH")), commit.tiers)
    }

    @Test
    fun `row enable toggles preserve retained built-in names and positions`() {
        val original = ResolverSelection(listOf(
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, true, "Hotel network"),
            ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, false, "Private fallback"),
        ))
        val enabled = accepted(evaluate(SetResolverTierEnabled(1, true), current = original)).selection
        assertEquals(listOf("Hotel network", "Private fallback"), enabled.tiers.map { it.retainedName })
        assertEquals(original, accepted(evaluate(SetResolverTierEnabled(1, false), current = enabled)).selection)
    }

    @Test
    fun `an empty or three tier ladder is rejected`() {
        assertRejected(ResolverEditRejection.InvalidTierCount, evaluate(SaveResolverTiers(emptyList())))
        assertRejected(
            ResolverEditRejection.InvalidTierCount,
            evaluate(SaveResolverTiers(listOf(tier("mullvad", "DoH"), tier("mullvad", "DoH"), tier("mullvad", "DoH")))),
        )
    }

    @Test
    fun `a ladder of only disabled rows is rejected as the last-active guard`() {
        assertRejected(
            ResolverEditRejection.InvalidTierCount,
            evaluate(SaveResolverTiers(listOf(tier("device-dns", "IP", isEnabled = false)))),
        )
        assertRejected(
            ResolverEditRejection.InvalidTierCount,
            evaluate(
                SaveResolverTiers(
                    listOf(tier("device-dns", "IP", isEnabled = false), tier("mullvad", "DoH", isEnabled = false)),
                ),
            ),
        )
    }

    @Test
    fun `a disabled second tier is retained in position`() {
        val commit = accepted(
            evaluate(
                SaveResolverTiers(
                    listOf(tier("device-dns", "IP"), tier("mullvad", "DoH", isEnabled = false)),
                ),
            ),
        )
        assertEquals(listOf(true, false), commit.selection.tiers.map { it.isEnabled })
        assertEquals(listOf("device-dns", "mullvad"), commit.selection.tiers.map { it.entry.providerId.rawId })
        assertEquals(ResolverSelectionPresets.DEVICE_PLAIN, commit.selection.primary)
        assertFalse(commit.selection.fallbackEnabled)
        assertEquals(listOf(true, false), commit.tiers.map { it.isEnabled })
    }

    @Test
    fun `the second tier may be device DNS or a custom encrypted route`() {
        // Device DNS is runtime-served, so it is a valid (if runtime-unserved) second tier.
        val deviceSecond = accepted(
            evaluate(SaveResolverTiers(listOf(tier("mullvad", "DoH"), tier("device-dns", "IP")))),
        )
        assertEquals(ResolverProviderId.DEVICE_DNS, deviceSecond.selection.fallback?.providerId)

        val customSecond = accepted(
            evaluate(
                SaveResolverTiers(
                    listOf(
                        tier("device-dns", "IP"),
                        ResolverTierChoice("custom-dns", "DoH", primary = "https://dns.google/dns-query"),
                    ),
                ),
            ),
        )
        assertEquals(ResolverProviderId.CUSTOM, customSecond.selection.fallback?.providerId)
        assertEquals("https://dns.google/dns-query", customSecond.selection.fallback?.customDohUrl)
    }

    @Test
    fun `a duplicate second tier is rejected`() {
        assertRejected(
            ResolverEditRejection.DuplicateTier,
            evaluate(SaveResolverTiers(listOf(tier("mullvad", "DoH"), tier("mullvad", "DoH")))),
        )
    }

    @Test
    fun `catalog encrypted provider endpoints produce exact retained plans`() {
        val commit = accepted(evaluate(SaveResolverTiers(listOf(tier("cloudflare-1111", "DoH"), tier("quad9-unfiltered", "DoT")))))
        assertEquals(listOf(ResolverProviderId.CLOUDFLARE, ResolverProviderId.QUAD9_UNFILTERED), commit.selection.tiers.map { it.entry.providerId })
        assertEquals(commit.selection, commit.selection.toFilterResolverPlan()!!.configuration.toSelection())
    }

    @Test
    fun `duplicate comparison ignores names and preset aliases`() {
        assertRejected(ResolverEditRejection.DuplicateTier, evaluate(SaveResolverTiers(listOf(
            ResolverTierChoice("custom-dns", "DoT", name = "First", primary = "tls://dns.google:853"),
            ResolverTierChoice("custom-dns", "DoT", name = "Second", primary = "tls://dns.google:853"),
        ))))
        val differingBootstrap = accepted(evaluate(SaveResolverTiers(listOf(
            tier("google-public-dns", "DoH"),
            ResolverTierChoice("custom-dns", "DoH", name = "Same endpoint", primary = "https://dns.google/dns-query"),
        ))))
        assertEquals(2, differingBootstrap.selection.tiers.size)
    }

    @Test
    fun `fixed provider plain selection preserves its identity rather than device DNS`() {
        val commit = accepted(evaluate(SaveResolverTiers(listOf(tier("cloudflare-1111", "IP")))))
        assertEquals(ResolverProviderId.CLOUDFLARE, commit.selection.primary.providerId)
        assertEquals(commit.selection, commit.selection.toFilterResolverPlan()!!.toSelection())
    }

    @Test
    fun `a transport the provider does not offer is rejected`() {
        assertRejected(
            ResolverEditRejection.TransportNotOffered(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.DOH),
            evaluate(SaveResolverTiers(listOf(tier("device-dns", "DoH")))),
        )
    }

    // -- unknown ids: never silently device DNS or Mullvad --------------------------

    @Test
    fun `unknown provider ids are rejected and never defaulted`() {
        for (rawId in listOf("device", "mullvad-doh", "nope", "")) {
            val result = evaluate(SaveResolverTiers(listOf(tier(rawId, "DoH"))))
            assertRejected(ResolverEditRejection.UnknownProvider(rawId), result)
        }
    }

    @Test
    fun `unknown transport tokens are rejected`() {
        for (token in listOf("QUIC", "ip", "", "https")) {
            assertRejected(
                ResolverEditRejection.UnknownTransport(token),
                evaluate(SaveResolverTiers(listOf(tier("mullvad", token)))),
            )
        }
    }

    // -- custom tier: plus, validation, coherence, runtime absence ------------------

    @Test
    fun `custom requires plus before it is validated`() {
        assertRejected(
            ResolverEditRejection.CustomRequiresPlus,
            evaluate(SaveResolverTiers(listOf(tier("custom-dns", "DoH"))), limits = free),
        )
        assertRejected(
            ResolverEditRejection.CustomRequiresPlus,
            evaluate(
                SaveResolverTiers(
                    listOf(ResolverTierChoice("custom-dns", "DoH", primary = "https://dns.google/dns-query")),
                ),
                limits = free,
            ),
        )
    }

    @Test
    fun `a tier lapse during edit is reported at save time`() {
        val draft = listOf(ResolverTierChoice("custom-dns", "DoH", primary = "https://dns.google/dns-query"))
        val commit = accepted(evaluate(SaveResolverTiers(draft), limits = plus))
        assertEquals(ResolverProviderId.CUSTOM, commit.selection.primary.providerId)
        assertEquals(CatalogResolverTransport.DOH, commit.selection.primary.transport)
        assertEquals("https://dns.google/dns-query", commit.selection.primary.customDohUrl)
        assertRejected(
            ResolverEditRejection.CustomRequiresPlus,
            evaluate(SaveResolverTiers(draft), limits = free),
        )
    }

    @Test
    fun `invalid custom host port and scheme are rejected with their precise failure`() {
        assertRejected(
            ResolverEditRejection.CustomInvalid(CustomResolverValidationFailure.EMPTY_PRIMARY),
            evaluate(SaveResolverTiers(listOf(tier("custom-dns", "DoH")))),
        )
        assertRejected(
            ResolverEditRejection.CustomInvalid(CustomResolverValidationFailure.MISSING_DOH_PATH),
            evaluate(
                SaveResolverTiers(
                    listOf(ResolverTierChoice("custom-dns", "DoH", primary = "https://dns.google")),
                ),
            ),
        )
        assertRejected(
            ResolverEditRejection.CustomInvalid(CustomResolverValidationFailure.UNSUPPORTED_FORMAT),
            evaluate(
                SaveResolverTiers(
                    listOf(ResolverTierChoice("custom-dns", "DoT", primary = "tls://dns.google:0")),
                ),
            ),
        )
        assertRejected(
            ResolverEditRejection.CustomInvalid(CustomResolverValidationFailure.UNSUPPORTED_FORMAT),
            evaluate(
                SaveResolverTiers(
                    listOf(ResolverTierChoice("custom-dns", "IP", primary = "dns.google")),
                ),
            ),
        )
    }

    @Test
    fun `a custom value whose transport differs from the selection is rejected`() {
        assertRejected(
            ResolverEditRejection.CustomTransportMismatch,
            evaluate(
                SaveResolverTiers(
                    listOf(ResolverTierChoice("custom-dns", "DoT", primary = "https://dns.google/dns-query")),
                ),
            ),
        )
    }

    @Test
    fun `the secondary custom value is validated with the primary`() {
        assertRejected(
            ResolverEditRejection.CustomInvalid(CustomResolverValidationFailure.UNSUPPORTED_FORMAT),
            evaluate(
                SaveResolverTiers(
                    listOf(
                        ResolverTierChoice(
                            "custom-dns",
                            "DoH",
                            primary = "https://dns.google/dns-query",
                            secondary = "not a resolver",
                        ),
                    ),
                ),
            ),
        )
        assertRejected(
            ResolverEditRejection.CustomInvalid(CustomResolverValidationFailure.SECONDARY_TRANSPORT_MISMATCH),
            evaluate(
                SaveResolverTiers(
                    listOf(
                        ResolverTierChoice(
                            "custom-dns",
                            "DoH",
                            primary = "https://dns.google/dns-query",
                            secondary = "tls://dns.google",
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a valid same-transport custom secondary is accepted and carried`() {
        val commit = accepted(
            evaluate(
                SaveResolverTiers(
                    listOf(
                        ResolverTierChoice(
                            "custom-dns",
                            "DoH",
                            name = "Home Resolver",
                            primary = "https://dns.google/dns-query",
                            secondary = "https://cloudflare-dns.com/dns-query",
                        ),
                    ),
                ),
            ),
        )
        val primary = commit.selection.primary
        assertEquals(ResolverProviderId.CUSTOM, primary.providerId)
        assertEquals("Home Resolver", primary.customName)
        assertEquals("https://dns.google/dns-query", primary.customDohUrl)
        assertEquals("https://cloudflare-dns.com/dns-query", primary.customSecondaryEndpoint)
    }

    @Test
    fun `a valid custom DoH tier is accepted and carries the normalized URL`() {
        val commit = accepted(
            evaluate(
                SaveResolverTiers(
                    listOf(
                        ResolverTierChoice(
                            "custom-dns",
                            "DoH",
                            name = "  My Resolver  ",
                            primary = "  https://dns.google/dns-query  ",
                        ),
                    ),
                ),
            ),
        )
        val primary = commit.selection.primary
        assertEquals(ResolverProviderId.CUSTOM, primary.providerId)
        assertEquals(CatalogResolverTransport.DOH, primary.transport)
        assertEquals("https://dns.google/dns-query", primary.customDohUrl)
        assertEquals("My Resolver", commit.tiers.single().name)
        assertEquals("https://dns.google/dns-query", commit.tiers.single().primary)
    }

    @Test
    fun `a valid custom DoT tier is accepted and carries the normalized endpoint`() {
        val commit = accepted(
            evaluate(
                SaveResolverTiers(
                    listOf(
                        ResolverTierChoice(
                            "custom-dns",
                            "DoT",
                            primary = "  tls://dns.google:853  ",
                        ),
                    ),
                ),
            ),
        )
        val primary = commit.selection.primary
        assertEquals(ResolverProviderId.CUSTOM, primary.providerId)
        assertEquals(CatalogResolverTransport.DOT, primary.transport)
        assertEquals("tls://dns.google:853", primary.customDotEndpoint)
        assertNull(primary.customDohUrl)
        val defaultPort = accepted(
            evaluate(
                SaveResolverTiers(
                    listOf(ResolverTierChoice("custom-dns", "DoT", primary = "dot://dns.quad9.net")),
                ),
            ),
        )
        assertEquals("tls://dns.quad9.net:853", defaultPort.selection.primary.customDotEndpoint)
    }

    @Test
    fun `custom DoQ carries exact primary secondary and display name into the plan`() {
        val commit = accepted(evaluate(SaveResolverTiers(listOf(
            ResolverTierChoice("custom-dns", "DoQ", name = "Private", primary = "quic://dns.adguard.com",
                secondary = "doq://dns.example:8853"),
        ))))
        assertEquals("doq://dns.adguard.com:853", commit.selection.primary.customDoqEndpoint)
        assertEquals("doq://dns.example:8853", commit.selection.primary.customSecondaryEndpoint)
        assertEquals("Private", commit.selection.primary.customName)
        val plan = requireNotNull(commit.selection.toFilterResolverPlan())
        assertEquals(commit.selection, plan.toSelection())
        assertEquals("doq://dns.adguard.com:853", plan.configuration.customDoqEndpoint)
    }

    @Test
    fun `custom plain primary and secondary keep canonical endpoint values and metadata`() {
        val commit = accepted(evaluate(SaveResolverTiers(listOf(
            ResolverTierChoice("custom-dns", "IP", "Office", "192.168.1.53", "192.168.1.54"),
        ))))
        assertEquals("192.168.1.53", commit.selection.primary.customPlainEndpoint)
        assertEquals("192.168.1.54", commit.selection.primary.customSecondaryEndpoint)
        assertEquals("Office", commit.selection.primary.customName)
        val plan = commit.selection.toFilterResolverPlan()!!
        assertEquals(commit.selection, plan.toSelection())
        assertNull(plan.customDohUrl)
        assertNull(plan.customName) // The retained row is the exact authority; compatibility fields carry no fake encrypted endpoint.
    }

    // -- per-tier toggle ------------------------------------------------------------

    @Test
    fun `dns toggle disables one retained row without dropping it`() {
        val commit = accepted(
            evaluate(
                SetResolverTierEnabled(index = 1, enabled = false),
                current = ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            ),
        )
        assertEquals(listOf(true, false), commit.selection.tiers.map { it.isEnabled })
        assertEquals(ResolverSelectionPresets.DEVICE_PLAIN, commit.selection.primary)
        assertFalse(commit.selection.fallbackEnabled)
    }

    @Test
    fun `dns toggle re-enables a retained disabled row`() {
        val disabled = ResolverSelection(
            listOf(
                ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
                ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, isEnabled = false),
            ),
        )
        val commit = accepted(evaluate(SetResolverTierEnabled(index = 1, enabled = true), current = disabled))
        assertEquals(ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK, commit.selection)
    }

    @Test
    fun `dns toggle of a disabled first row moves the active primary`() {
        val disabledFirst = ResolverSelection(
            listOf(
                ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, isEnabled = false),
                ResolverTier(ResolverSelectionPresets.MULLVAD_DOH),
            ),
        )
        val commit = accepted(evaluate(SetResolverTierEnabled(index = 0, enabled = true), current = disabledFirst))
        assertEquals(listOf(true, true), commit.selection.tiers.map { it.isEnabled })
        assertEquals(ResolverSelectionPresets.DEVICE_PLAIN, commit.selection.primary)
    }

    @Test
    fun `dns toggle refuses to disable the last active row`() {
        assertRejected(
            ResolverEditRejection.InvalidTierCount,
            evaluate(SetResolverTierEnabled(index = 0, enabled = false), current = ResolverSelectionPresets.MULLVAD_ONLY),
        )
    }

    @Test
    fun `dns toggle refuses an out of range index`() {
        assertRejected(
            ResolverEditRejection.InvalidTierCount,
            evaluate(SetResolverTierEnabled(index = 2, enabled = true)),
        )
    }

    // -- fallback toggle (legacy vpn.toggle fallback) -------------------------------

    @Test
    fun `disabling the fallback retains the second tier disabled`() {
        val commit = accepted(
            evaluate(
                SetResolverFallbackEnabled(false),
                current = ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            ),
        )
        assertEquals(
            ResolverSelection(
                listOf(
                    ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
                    ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, isEnabled = false),
                ),
            ),
            commit.selection,
        )
    }

    @Test
    fun `enabling the fallback installs the pinned mullvad doh tier`() {
        val commit = accepted(
            evaluate(
                SetResolverFallbackEnabled(true),
                current = ResolverSelectionPresets.DEVICE_DNS_ONLY,
            ),
        )
        assertEquals(ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK, commit.selection)
    }

    @Test
    fun `enabling an already enabled fallback is a no-op`() {
        val commit = accepted(
            evaluate(
                SetResolverFallbackEnabled(true),
                current = ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            ),
        )
        assertEquals(ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK, commit.selection)
    }

    @Test
    fun `enabling the fallback under a mullvad primary has no eligible second tier`() {
        assertRejected(
            ResolverEditRejection.DuplicateTier,
            evaluate(SetResolverFallbackEnabled(true), current = ResolverSelectionPresets.MULLVAD_ONLY),
        )
    }

    // -- chained-mode edit restriction ---------------------------------------------

    @Test
    fun `a full tunnel chained session locks every dns edit`() {
        val locked = ChainedDnsEditState(chainingEnabled = true, fallbackPreference = true, storedIsSplitTunnel = false)
        assertRejected(
            ResolverEditRejection.ChainedEditLocked,
            evaluate(SaveResolverTiers(listOf(tier("device-dns", "IP"))), chained = locked),
        )
        assertRejected(
            ResolverEditRejection.ChainedEditLocked,
            evaluate(SetResolverFallbackEnabled(false), chained = locked),
        )
        assertRejected(
            ResolverEditRejection.ChainedEditLocked,
            evaluate(SetResolverTierEnabled(1, false), chained = locked),
        )
    }

    @Test
    fun `a chained session with fallback off locks every dns edit`() {
        val locked = ChainedDnsEditState(chainingEnabled = true, fallbackPreference = false, storedIsSplitTunnel = null)
        assertRejected(
            ResolverEditRejection.ChainedEditLocked,
            evaluate(SaveResolverTiers(listOf(tier("device-dns", "IP"))), chained = locked),
        )
    }

    @Test
    fun `a split tunnel chained session stays editable`() {
        val split = ChainedDnsEditState(chainingEnabled = true, fallbackPreference = true, storedIsSplitTunnel = true)
        accepted(evaluate(SaveResolverTiers(listOf(tier("device-dns", "IP"))), chained = split))
    }

    // -- editor staleness -----------------------------------------------------------

    @Test
    fun `a stale editor context is rejected before any rule runs`() {
        assertRejected(
            ResolverEditRejection.StaleEditor,
            evaluate(
                SaveResolverTiers(listOf(tier("device-dns", "IP")), expectedEditorContext = "a"),
                editorContext = "b",
            ),
        )
    }

    @Test
    fun `a matching editor context is accepted and an unbound request is skipped`() {
        accepted(
            evaluate(
                SaveResolverTiers(listOf(tier("device-dns", "IP")), expectedEditorContext = "a"),
                editorContext = "a",
            ),
        )
        accepted(
            evaluate(
                SaveResolverTiers(listOf(tier("device-dns", "IP")), expectedEditorContext = null),
                editorContext = "a",
            ),
        )
        assertRejected(
            ResolverEditRejection.StaleEditor,
            evaluate(
                SaveResolverTiers(listOf(tier("device-dns", "IP")), expectedEditorContext = "a"),
                editorContext = null,
            ),
        )
    }

    // -- primary/secondary values ---------------------------------------------------

    @Test
    fun `built in endpoints are normalized away while the row label is retained`() {
        val commit = accepted(
            evaluate(
                SaveResolverTiers(
                    listOf(ResolverTierChoice("mullvad", "DoH", name = "Name", primary = "1.1.1.1", secondary = "2.2.2.2")),
                ),
            ),
        )
        assertEquals(listOf(ResolverTierChoice("mullvad", "DoH", name = "Name")), commit.tiers)
        assertEquals("Name", commit.selection.tiers.single().retainedName)
        assertNull(commit.selection.primary.customDohUrl)
        assertNull(commit.selection.primary.customSecondaryEndpoint)
    }
}
