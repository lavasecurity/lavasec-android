package com.lavasec.app.foundation.backup

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.CustomResolverValidator
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.toSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadMapperTest {
    @Test
    fun `native QA overlay never enters a portable backup or restored configuration`() {
        val library = orderedLibrary(listOf(ResolverTier(ResolverSelectionEntry(
            ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS))))
        val exported = BackupPayloadMapper.toPayload(library.withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED))
        assertEquals(BackupPayloadMapper.toPayload(library), exported)
        val json = BackupPayloadJson.encode(exported)
        assertFalse(json.contains("qa-probe"))
        val restored = BackupPayloadMapper.restore(BackupPayloadJson.decode(json)).library
        assertNull(restored.qaProbeSet)
        assertEquals(library, restored)
    }

    @Test
    fun `actual frozen Swift stamp pairs restore independent runtime routes and exact bootstrap peers`() {
        val expectations = mapOf(
            "plain" to listOf("9.9.9.9" to emptyList(), "149.112.112.112" to emptyList()),
            "doh" to listOf(
                "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"),
                "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888")),
            "dot" to listOf(
                "dns.adguard-dns.com:853" to listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff"),
                "dns.quad9.net:8853" to listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe")),
            "doq" to listOf(
                "dns.quad9.net:853" to listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe"),
                "secondary.resolver.test:8853" to listOf("192.168.1.53", "192.168.1.54", "fd00::53")),
        )
        for ((label, routes) in expectations) {
            val restored = BackupPayloadMapper.restore(stampPayload(label))
            val entry = restored.library.resolverTarget.toSelection().primary
            val primary = entry.customPlainEndpoint ?: entry.customDohUrl ?: entry.customDotEndpoint ?: entry.customDoqEndpoint!!
            val rawEndpoints = listOf(primary, entry.customSecondaryEndpoint!!)
            for ((index, raw) in rawEndpoints.withIndex()) {
                val (route, peers) = routes[index]
                val parsed = checkNotNull(CustomResolverValidator.parse(raw))
                assertEquals(label, raw, parsed.normalized)
                assertEquals(label, route, parsed.resolvedEndpoint)
                val canonicalPeers = peers.map { java.net.InetAddress.getByName(it).hostAddress!! }
                assertEquals(label, canonicalPeers, parsed.bootstrapAddresses)
                assertEquals(label, canonicalPeers, CustomResolverValidator.bootstrapAddresses(raw))
                when (label) {
                    "plain" -> assertEquals(route, CustomResolverValidator.resolvedPlainAddress(raw))
                    "doh" -> assertEquals(route, CustomResolverValidator.resolvedDohUrl(raw))
                    "dot" -> assertEquals(route, CustomResolverValidator.dotHostPort(raw)!!.let { "${it.first}:${it.second}" })
                    "doq" -> assertEquals(route, CustomResolverValidator.doqHostPort(raw)!!.let { "${it.first}:${it.second}" })
                }
            }
            assertFalse(label, CustomResolverValidator.endpointIdentity(rawEndpoints[0]) ==
                CustomResolverValidator.endpointIdentity(rawEndpoints[1]))
        }
    }

    @Test
    fun `actual frozen Swift DNS stamp pairs retain both raw endpoints through active and disabled row restore`() {
        for ((label, transport) in listOf("plain" to CatalogResolverTransport.PLAIN_DNS,
            "doh" to CatalogResolverTransport.DOH, "dot" to CatalogResolverTransport.DOT,
            "doq" to CatalogResolverTransport.DOQ)) {
            val payload = stampPayload(label)
            val expected = payload.savedDNSResolutionSelections!!
            val stampRow = expected.first()
            assertTrue(label, stampRow.primary.startsWith("sdns://"))
            assertTrue(label, stampRow.secondary.startsWith("sdns://"))
            assertFalse(label, stampRow.primary == stampRow.secondary)
            val restored = BackupPayloadMapper.restore(payload)
            val rows = restored.library.resolverTarget.toSelection().tiers
            assertEquals(label, listOf(ResolverProviderId.CUSTOM, ResolverProviderId.DEVICE_DNS), rows.map { it.entry.providerId })
            assertEquals(label, transport, rows.first().entry.transport)
            val primary = when (transport) {
                CatalogResolverTransport.PLAIN_DNS -> rows.first().entry.customPlainEndpoint
                CatalogResolverTransport.DOH -> rows.first().entry.customDohUrl
                CatalogResolverTransport.DOT -> rows.first().entry.customDotEndpoint
                CatalogResolverTransport.DOQ -> rows.first().entry.customDoqEndpoint
            }
            assertEquals(label, stampRow.primary, primary)
            assertEquals(label, stampRow.secondary, rows.first().entry.customSecondaryEndpoint)
            assertEquals(label, stampRow.name, rows.first().entry.customName)
            val exported = BackupPayloadMapper.toPayload(restored.library, restored.preferences, restored.lavaGuardUnlocks)
            assertEquals(label, expected, exported.savedDNSResolutionSelections)
            assertEquals(label, payload.customResolverAddress, exported.customResolverAddress)
            assertEquals(label, payload.customResolverSecondaryAddress, exported.customResolverSecondaryAddress)
            assertEquals(label, restored, BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(exported))))

            // The same actual Swift stamp must remain portable while its row is inactive and
            // the device tier owns the live headers. No endpoint/metadata may be dropped.
            val disabledRows = listOf(stampRow.copy(isEnabled = false), expected.last())
            val disabledPayload = payload.copy(schemaVersion = 3, resolverPresetID = "device-dns",
                customResolverAddress = null, customResolverSecondaryAddress = null, customResolverName = null,
                fallbackToDeviceDNS = false, usesEncryptedDeviceDNSFallback = false,
                savedDNSResolutionSelections = disabledRows)
            val disabled = BackupPayloadMapper.restore(disabledPayload)
            val reexported = BackupPayloadMapper.toPayload(disabled.library, disabled.preferences, disabled.lavaGuardUnlocks)
            assertEquals(label, 3, reexported.schemaVersion)
            assertEquals(label, disabledRows, reexported.savedDNSResolutionSelections)
            assertEquals(label, disabled, BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(reexported))))
        }
    }

    @Test
    fun `actual frozen Swift stamp backup refuses mismatched secondary protocol or malformed primary`() {
        val labels = listOf("plain", "doh", "dot", "doq")
        for ((index, label) in labels.withIndex()) {
            val payload = stampPayload(label)
            val row = payload.savedDNSResolutionSelections!!.first()
            val wrongSecondary = stampPayload(labels[(index + 1) % labels.size]).customResolverAddress!!
            for ((primary, secondary) in listOf(row.primary to wrongSecondary,
                "sdns://not-valid" to row.secondary)) {
                val candidate = payload.copy(customResolverAddress = primary, customResolverSecondaryAddress = secondary,
                    savedDNSResolutionSelections = listOf(row.copy(primary = primary, secondary = secondary),
                        payload.savedDNSResolutionSelections!!.last()))
                assertTrue(label, runCatching { BackupPayloadMapper.restore(candidate) }.exceptionOrNull() is BackupPayloadMappingException)
            }
        }
    }

    private fun stampPayload(label: String): BackupConfigurationPayload = BackupPayloadJson.decode(
        checkNotNull(javaClass.getResource("/backup/ios-3bd86116-stamp-$label.json")).readText(),
    )

    @Test
    fun `actual frozen Swift private IP and Guardian golden restores and reexports every portable value`() {
        // Actual frozen encoder/self-decode at 3bd86116, not an Android-authored JSON imitation.
        val json = checkNotNull(javaClass.getResource("/backup/ios-3bd86116-plain-guardian.json")).readText()
        val payload = BackupPayloadJson.decode(json)
        val restored = BackupPayloadMapper.restore(payload)
        val rows = restored.library.resolverTarget.toSelection().tiers
        assertEquals(listOf("custom-dns", "cloudflare-1111"), rows.map { ResolverCatalog.presetId(it.entry) })
        assertEquals(listOf(false, true), rows.map { it.isEnabled })
        assertEquals(listOf("Private IP pair", "Saved plain Cloudflare"), rows.map { it.retainedName ?: it.entry.customName })
        assertEquals("192.168.1.53", rows[0].entry.customPlainEndpoint)
        assertEquals("192.168.1.54", rows[0].entry.customSecondaryEndpoint)
        assertEquals(CatalogResolverTransport.PLAIN_DNS, rows[1].entry.transport)
        assertEquals(ResolverProviderId.CLOUDFLARE, rows[1].entry.providerId)
        assertEquals(listOf(BackupLavaGuardUnlockRecord("emerald", 10.0), BackupLavaGuardUnlockRecord("obsidian", 10.0)),
            restored.lavaGuardUnlocks.records)
        val exported = BackupPayloadMapper.toPayload(restored.library, restored.preferences, restored.lavaGuardUnlocks)
        assertEquals(3, exported.schemaVersion)
        assertEquals(payload.savedDNSResolutionSelections, exported.savedDNSResolutionSelections)
        assertEquals(payload.lavaGuardUnlocks, exported.lavaGuardUnlocks)
        val encoded = BackupPayloadJson.encode(exported)
        val reopened = BackupPayloadMapper.restore(BackupPayloadJson.decode(encoded))
        assertEquals(restored, reopened)
        for (field in listOf("usageByDay", "qualifiedUsageDay", "activeUsageStartedAt", "domainHistory", "networkActivity")) {
            assertFalse(field, encoded.contains("\"$field\""))
        }
    }

    @Test
    fun `custom literal DNS backups preserve exact ordered addresses secondary name and disabled rows`() {
        val primary = checkNotNull(CustomResolverValidator.normalizedPlainEndpoint("192.168.1.53"))
        val secondary = checkNotNull(CustomResolverValidator.normalizedPlainEndpoint("192.168.1.54"))
        val custom = ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.PLAIN_DNS,
            customPlainEndpoint = primary, customSecondaryEndpoint = secondary, customName = "Saved IP DNS"))
        val device = ResolverTier(ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS),
            retainedName = "Device fallback")
        val quad9 = ResolverTier(checkNotNull(ResolverCatalog.entryForPresetId("quad9-unfiltered")), retainedName = "Fixed Quad9")
        for (rows in listOf(listOf(custom), listOf(custom, device), listOf(custom.copy(isEnabled = false), quad9))) {
            val source = orderedLibrary(rows)
            val payload = BackupPayloadMapper.toPayload(source)
            assertEquals(rows.map { it.isEnabled }, payload.savedDNSResolutionSelections!!.map { it.isEnabled })
            assertEquals(primary, payload.savedDNSResolutionSelections!!.first().primary)
            assertEquals(secondary, payload.savedDNSResolutionSelections!!.first().secondary)
            assertEquals("Saved IP DNS", payload.savedDNSResolutionSelections!!.first().name)
            assertEquals(if (rows.any { !it.isEnabled }) 3 else 2, payload.schemaVersion)
            val restored = BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(payload))).library
            assertEquals(rows, restored.resolverTarget.toSelection().tiers)
            assertEquals(source, restored)
        }
        val legacy = BackupConfigurationPayload(resolverPresetID = "custom-dns", customResolverAddress = "192.168.1.53",
            customResolverSecondaryAddress = "192.168.1.54", customResolverName = "Saved IP DNS", fallbackToDeviceDNS = false)
        val selection = BackupPayloadMapper.restore(legacy).library.resolverTarget.toSelection()
        assertEquals(ResolverProviderId.CUSTOM, selection.primary.providerId)
        assertEquals(CatalogResolverTransport.PLAIN_DNS, selection.primary.transport)
        assertEquals(primary, selection.primary.customPlainEndpoint)
        assertEquals(secondary, selection.primary.customSecondaryEndpoint)
        assertEquals("Saved IP DNS", selection.primary.customName)
        assertEquals(null, selection.fallback)
    }

    @Test
    fun `plain built-in backups retain actual provider rather than device DNS`() {
        for (id in listOf("quad9-unfiltered", "cloudflare-1111", "hagezi-root", "google-public-dns")) {
            val entry = checkNotNull(ResolverCatalog.entryForPresetId(id, migrateRetired = false))
            val source = orderedLibrary(listOf(ResolverTier(entry, retainedName = "Saved fixed DNS")))
            val payload = BackupPayloadMapper.toPayload(source)
            assertEquals(id, payload.resolverPresetID)
            assertEquals(null, payload.customResolverAddress)
            assertEquals(source, BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(payload))).library)
            val legacy = BackupPayloadMapper.restore(BackupConfigurationPayload(resolverPresetID = id, fallbackToDeviceDNS = false))
                .library.resolverTarget.toSelection().primary
            assertEquals(entry, legacy)
            assertEquals(CatalogResolverTransport.PLAIN_DNS, legacy.transport)
            assertFalse(legacy.providerId == ResolverProviderId.DEVICE_DNS)
        }
    }

    @Test
    fun `malformed plaintext resolver inputs or mixed secondary are refused before adoption`() {
        for (address in listOf("resolver.test", "1.1.1.999", "udp://1.1.1.1", "tcp://1.1.1.1:53",
            "1.1.1.1,1.0.0.1", "192.0.2.1", "198.51.100.53", "203.0.113.53", "127.0.0.1",
            "169.254.1.53", "0.0.0.0", "224.0.0.53", "::1", "fe80::53", "2001:db8::53")) {
            assertTrue(address, runCatching { BackupPayloadMapper.restore(BackupConfigurationPayload(resolverPresetID = "custom-dns",
                customResolverAddress = address, fallbackToDeviceDNS = false)) }.exceptionOrNull() is BackupPayloadMappingException)
        }
        assertTrue(runCatching { BackupPayloadMapper.restore(BackupConfigurationPayload(resolverPresetID = "custom-dns",
            customResolverAddress = "1.1.1.1", customResolverSecondaryAddress = "tls://resolver.test:853")) }
            .exceptionOrNull() is BackupPayloadMappingException)
    }

    @Test
    fun `portable private IP and ULA endpoints stay separate and secondary shares frozen usable-address admission`() {
        for ((primary, secondary) in listOf("192.168.1.53" to "192.168.1.54", "fd12:3456::53" to "fd12:3456::54")) {
            val payload = BackupConfigurationPayload(resolverPresetID = "custom-dns", customResolverAddress = primary,
                customResolverSecondaryAddress = secondary, fallbackToDeviceDNS = false)
            val restored = BackupPayloadMapper.restore(payload).library
            val entry = restored.resolverTarget.toSelection().primary
            assertEquals(CustomResolverValidator.normalizedPlainEndpoint(primary), entry.customPlainEndpoint)
            assertEquals(CustomResolverValidator.normalizedPlainEndpoint(secondary), entry.customSecondaryEndpoint)
            val exported = BackupPayloadMapper.toPayload(restored)
            assertEquals(CustomResolverValidator.normalizedPlainEndpoint(primary), exported.customResolverAddress)
            assertEquals(CustomResolverValidator.normalizedPlainEndpoint(secondary), exported.customResolverSecondaryAddress)
            for (badSecondary in listOf("192.168.1.54,192.168.1.55", "192.0.2.54", "::1", "fe80::54")) {
                assertTrue(badSecondary, runCatching { BackupPayloadMapper.restore(payload.copy(customResolverSecondaryAddress = badSecondary)) }
                    .exceptionOrNull() is BackupPayloadMappingException)
            }
        }
    }

    @Test
    fun `portable ledger normalization keeps earliest unlocks and replacement never merges local achievements`() {
        val incoming = BackupLavaGuardAchievementLedger(listOf(
            BackupLavaGuardUnlockRecord("emerald", 20.0), BackupLavaGuardUnlockRecord("emberObsidian", 10.0),
            BackupLavaGuardUnlockRecord("emerald", 5.0), BackupLavaGuardUnlockRecord("futureGuard", 10.0)))
        val payload = BackupPayloadMapper.toPayload(orderedLibrary(listOf(ResolverTier(
            ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS)))),
            lavaGuardUnlocks = incoming)
        val restored = BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(payload)))
        assertEquals(listOf("emerald", "emberObsidian", "futureGuard"), restored.lavaGuardUnlocks.records.map { it.guardID })
        assertEquals(listOf(5.0, 10.0, 10.0), restored.lavaGuardUnlocks.records.map { it.unlockedAt })
        assertEquals(payload.lavaGuardUnlocks, restored.lavaGuardUnlocks)
        val json = BackupPayloadJson.encode(payload)
        assertFalse(json.contains("usageByDay"))
        assertFalse(json.contains("qualifiedUsageDay"))
        assertFalse(json.contains("activeUsageStartedAt"))
    }

    @Test
    fun `all actual frozen iOS encoder ladders restore served endpoints and complete retained rows`() {
        for (name in listOf("disabled-custom-doq", "disabled-device", "encrypted-pair", "doq-device")) {
            val json = requireNotNull(javaClass.getResource("/backup/ios-3bd86116-$name.json")).readText()
            val payload = BackupPayloadJson.decode(json)
            val restored = BackupPayloadMapper.restore(payload).library
            val rows = restored.resolverTarget.toSelection().tiers
            val expected = payload.savedDNSResolutionSelections!!
            assertEquals(name, expected.map { it.id }, rows.map { ResolverCatalog.presetId(it.entry) })
            assertEquals(name, expected.map { it.isEnabled }, rows.map { it.isEnabled })
            assertEquals(name, expected.map { it.name }, rows.map { it.retainedName ?: it.entry.customName.orEmpty() })
            val exported = BackupPayloadMapper.toPayload(restored)
            assertEquals(name, payload.schemaVersion, exported.schemaVersion)
            assertEquals(name, rows, BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(exported)))
                .library.resolverTarget.toSelection().tiers)
            val custom = rows.firstOrNull { it.entry.providerId == ResolverProviderId.CUSTOM }?.entry
            if (custom != null) {
                assertEquals("doq://resolver.test:8853", custom.customDoqEndpoint)
                assertEquals("doq://secondary.test:8853", custom.customSecondaryEndpoint)
            }
        }
    }

    @Test
    fun `legacy current provider headers keep exact transport and retired aliases migrate explicitly`() {
        for (provider in listOf("quad9-unfiltered", "cloudflare-1111", "hagezi-root", "google-public-dns",
            "mullvad", "dns-sb", "quad9-secure")) {
            for (transport in listOf("doh", "dot")) {
                val id = "$provider-$transport"
                val payload = BackupConfigurationPayload(resolverPresetID = id)
                val entry = BackupPayloadMapper.restore(payload).library.resolverTarget.toSelection().primary
                val expected = if (provider in listOf("mullvad", "dns-sb", "quad9-secure")) "quad9-unfiltered-$transport" else id
                assertEquals(id, expected, ResolverCatalog.presetId(entry))
                if (expected.startsWith("quad9-unfiltered")) {
                    assertEquals("https://dns10.quad9.net/dns-query", ResolverCatalog.dohUrl(entry.provider))
                    assertEquals("tls://dns10.quad9.net:853", ResolverCatalog.dotEndpoint(entry.provider))
                }
            }
        }
        val retired = BackupConfigurationPayload(schemaVersion = 2, resolverPresetID = "mullvad-doh", usesExplicitDNSTiers = true,
            savedDNSResolutionSelections = listOf(BackupDNSResolutionSelection("mullvad-doh")))
        assertTrue("retained rows must never silently change endpoint identity",
            runCatching { BackupPayloadMapper.restore(retired) }.exceptionOrNull() is BackupPayloadMappingException)
    }
    private fun orderedLibrary(rows: List<ResolverTier>): FilterLibraryDraft {
        val target = requireNotNull(ResolverSelection(rows).toFilterResolverPlan())
        // User-created filters have no seeded level. A seeded ID with null level is not a valid
        // source round-trip fixture: restoration derives its canonical ProtectionLevel from that ID.
        return FilterLibraryDraft(listOf(profile("filter-portable", "Portable")), "filter-portable", target.plan,
            customDohUrl = target.customDohUrl, customDotEndpoint = target.customDotEndpoint,
            customDoqEndpoint = target.customDoqEndpoint, customName = target.customName,
            customSecondaryEndpoint = target.customSecondaryEndpoint, savedTiers = target.savedTiers)
    }

    @Test
    fun `the actual iOS custom DoQ to Device fixture restores both active rows without losing portable values`() {
        val json = requireNotNull(javaClass.getResource("/backup/ios-3bd86116-doq-device.json")).readText()
        val payload = BackupPayloadJson.decode(json)
        val library = BackupPayloadMapper.restore(payload).library
        val rows = library.resolverTarget.toSelection().tiers
        assertEquals(2, rows.size)
        assertEquals(ResolverProviderId.CUSTOM, rows[0].entry.providerId)
        assertEquals(CatalogResolverTransport.DOQ, rows[0].entry.transport)
        assertEquals("doq://resolver.test:8853", rows[0].entry.customDoqEndpoint)
        assertEquals("doq://secondary.test:8853", rows[0].entry.customSecondaryEndpoint)
        assertEquals("Home QUIC", rows[0].entry.customName)
        assertEquals(ResolverProviderId.DEVICE_DNS, rows[1].entry.providerId)
        assertTrue(rows.all { it.isEnabled })
        val exported = BackupPayloadMapper.toPayload(library)
        assertEquals(2, exported.schemaVersion)
        assertTrue(exported.fallbackToDeviceDNS)
        assertFalse(exported.usesEncryptedDeviceDNSFallback)
        assertEquals("doq://resolver.test:8853", exported.customResolverAddress)
        assertEquals("doq://secondary.test:8853", exported.customResolverSecondaryAddress)
        assertEquals(library, BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(exported))).library)
    }

    @Test
    fun `disabled Device and two custom encrypted rows preserve order names and secondary endpoints`() {
        val device = ResolverTier(ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS),
            isEnabled = false, retainedName = "Saved device")
        val doh = ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
            customDohUrl = "https://primary.example/dns-query", customName = "First encrypted",
            customSecondaryEndpoint = "https://secondary.example/dns-query"))
        val dot = ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOT,
            customDotEndpoint = "tls://resolver.example:853", customName = "Second encrypted",
            customSecondaryEndpoint = "tls://secondary.example:853"))
        for (rows in listOf(listOf(device, doh), listOf(doh, dot))) {
            val library = orderedLibrary(rows)
            val exported = BackupPayloadMapper.toPayload(library)
            assertEquals(if (rows.any { !it.isEnabled }) 3 else 2, exported.schemaVersion)
            assertEquals(rows, BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(exported)))
                .library.resolverTarget.toSelection().tiers)
            assertEquals(rows.map { it.retainedName ?: it.entry.customName.orEmpty() },
                exported.savedDNSResolutionSelections!!.map { it.name })
        }
    }

    @Test
    fun `schema two without saved rows still restores its exact encrypted fallback`() {
        val wire = BackupConfigurationPayload(schemaVersion = 2, resolverPresetID = "custom-dns",
            customResolverAddress = "https://primary.example/dns-query", customResolverName = "Primary",
            usesExplicitDNSTiers = true, usesEncryptedDeviceDNSFallback = true,
            fallbackResolverPresetID = "custom-dns", fallbackCustomResolverAddress = "tls://resolver.example:853",
            fallbackCustomResolverSecondaryAddress = "tls://secondary.example:853", fallbackCustomResolverName = "Fallback")
        val rows = BackupPayloadMapper.restore(wire).library.resolverTarget.toSelection().tiers
        assertEquals(listOf(CatalogResolverTransport.DOH, CatalogResolverTransport.DOT), rows.map { it.entry.transport })
        assertEquals("Fallback", rows[1].entry.customName)
        assertEquals("tls://secondary.example:853", rows[1].entry.customSecondaryEndpoint)
        val exported = BackupPayloadMapper.toPayload(BackupPayloadMapper.restore(wire).library)
        assertEquals(wire.fallbackCustomResolverAddress, exported.fallbackCustomResolverAddress)
        assertEquals(wire.fallbackCustomResolverSecondaryAddress, exported.fallbackCustomResolverSecondaryAddress)
        assertEquals(wire.fallbackCustomResolverName, exported.fallbackCustomResolverName)
    }

    @Test
    fun `invalid ordered rows are refused rather than normalized to another provider or dropped`() {
        val custom = BackupDNSResolutionSelection("custom-dns", "Home", "doq://resolver.example:853")
        val base = BackupConfigurationPayload(resolverPresetID = "custom-dns", usesExplicitDNSTiers = true,
            customResolverAddress = custom.primary, customResolverName = custom.name,
            savedDNSResolutionSelections = listOf(custom))
        val bad = listOf(
            base.copy(savedDNSResolutionSelections = emptyList()),
            base.copy(savedDNSResolutionSelections = listOf(custom.copy(isEnabled = false))),
            base.copy(savedDNSResolutionSelections = listOf(custom, custom.copy(name = "Different label"))),
            base.copy(savedDNSResolutionSelections = listOf(custom.copy(primary = "doq://resolver.example:53"))),
            base.copy(savedDNSResolutionSelections = listOf(custom.copy(secondary = "tls://resolver.example:853"))),
            base.copy(resolverPresetID = "device-dns"),
            base.copy(savedDNSResolutionSelections = listOf(custom, BackupDNSResolutionSelection("future-provider-doh", isEnabled = false))),
            base.copy(savedDNSResolutionSelections = listOf(custom, BackupDNSResolutionSelection("cloudflare-1111-doq", isEnabled = false))),
        )
        for (payload in bad) {
            assertTrue(payload.toString(), runCatching { BackupPayloadMapper.restore(payload) }.exceptionOrNull()
                is BackupPayloadMappingException)
        }
    }

    @Test
    fun `a full fifty-filter library maps onto the payload and restores`() {
        val profiles = (0 until 50).map { index ->
            FilterProfileDraft(
                id = "filter-$index",
                name = "Filter $index",
                protectionLevel = null,
                enabledSourceIds = setOf("source-$index"),
                manualRules = ManualDomainRules(
                    allowedDomains = setOf("allow-$index.example"),
                    blockedDomains = setOf("block-$index.example"),
                ),
            )
        }
        val library = FilterLibraryDraft(
            profiles = profiles,
            activeProfileId = "filter-25",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )

        val payload = BackupPayloadMapper.toPayload(
            library,
            BackupRetainedPreferences(
                keepFilteringCounts = false,
                keepDomainDiagnostics = true,
                keepNetworkActivity = false,
                keepLavaGuardProgress = true,
                protectionEnabledHint = true,
            ),
        )

        val libraryWire = requireNotNull(payload.filterLibrary)
        assertEquals(50, libraryWire.filters.size)
        assertEquals("filter-25", libraryWire.activeFilterID)
        assertEquals(0, libraryWire.configurationGeneration)
        assertEquals(setOf("source-25"), payload.enabledBlocklistIDs)
        assertEquals(setOf("allow-25.example"), payload.allowedDomains)
        assertEquals(setOf("block-25.example"), payload.blockedDomains)
        assertFalse(payload.keepFilteringCounts)
        assertTrue(payload.keepDomainDiagnostics)

        val restored = BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(payload)))
        assertEquals(50, restored.library.profiles.size)
        assertEquals("filter-25", restored.library.activeProfileId)
        assertEquals(profiles, restored.library.profiles)
        assertEquals(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK, restored.library.resolverPlan)
        assertEquals(
            BackupRetainedPreferences(
                keepFilteringCounts = false,
                keepDomainDiagnostics = true,
                keepNetworkActivity = false,
                keepLavaGuardProgress = true,
                protectionEnabledHint = true,
            ),
            restored.preferences,
        )
    }

    @Test
    fun `the serialized payload carries no excluded fact by field inventory`() {
        val payload = BackupPayloadMapper.toPayload(
            FilterLibraryDraft(
                profiles = listOf(
                    FilterProfileDraft(
                        id = "filter-balanced",
                        name = "Balanced",
                        protectionLevel = null,
                        enabledSourceIds = setOf("blocklistproject-basic"),
                        manualRules = ManualDomainRules(
                            allowedDomains = setOf("school.example"),
                            blockedDomains = setOf("casino.example"),
                        ),
                    ),
                ),
                activeProfileId = "filter-balanced",
                resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            ),
        )
        val encoded = BackupPayloadJson.encode(payload)

        for (excluded in BackupPayloadExclusions.EXCLUDED_FIELD_NAMES) {
            assertFalse(
                "excluded fact '$excluded' must never appear in a portable payload",
                encoded.contains("\"$excluded\":"),
            )
        }
        // The inventory is the portable set, not a redaction of the excluded one.
        for (present in listOf(
            "schemaVersion", "enabledBlocklistIDs", "resolverPresetID", "keepFilteringCounts",
            "customBlocklists", "filterLibrary",
        )) {
            assertTrue("portable field '$present' must be present", encoded.contains("\"$present\":"))
        }
        assertFalse("device-local compile cache must not be carried", encoded.contains("lastCompiledToken"))
        assertFalse("device-local freshness cache must not be carried", encoded.contains("lastSyncedAt"))
    }

    @Test
    fun `legacy Android resolver headers restore the frozen canonical migration`() {
        for (plan in MilestoneResolverPlan.entries) {
            val library = FilterLibraryDraft(
                profiles = listOf(profile("filter-balanced", "Balanced")),
                activeProfileId = "filter-balanced",
                resolverPlan = plan,
            )
            val payload = BackupPayloadMapper.toPayload(library)
            assertFalse("legacy milestone exports retain schema one", payload.usesExplicitDNSTiers)
            assertEquals(
                "plan $plan must restore to itself",
                plan,
                BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(payload)))
                    .library.resolverPlan,
            )
            val entries = BackupPayloadMapper.restore(payload).library.resolverTarget.toSelection().activeTiers.map { it.entry }
            assertEquals(if (plan == MilestoneResolverPlan.DEVICE_DNS_ONLY) listOf("device-dns")
                else if (plan == MilestoneResolverPlan.MULLVAD_ONLY) listOf("quad9-unfiltered-doh")
                else listOf("device-dns", "quad9-unfiltered-doh"), entries.map { ResolverCatalog.presetId(it) })
        }
    }

    @Test
    fun `a custom DoH resolver round-trips through the portable payload`() {
        val url = "https://dns.home.example/dns-query"
        val library = FilterLibraryDraft(
            profiles = listOf(profile("filter-balanced", "Balanced")),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            customDohUrl = url,
            customSecondaryEndpoint = "https://secondary.home.example/dns-query",
            customName = "Home DNS",
        )

        val payload = BackupPayloadMapper.toPayload(library)
        assertEquals(BackupResolverPresetBridge.CUSTOM_DNS_ID, payload.resolverPresetID)
        assertEquals(url, payload.customResolverAddress)

        val restored = BackupPayloadMapper.restore(
            BackupPayloadJson.decode(BackupPayloadJson.encode(payload)),
        )
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, restored.library.resolverPlan)
        assertEquals(url, restored.library.customDohUrl)
        assertNull(restored.library.customDotEndpoint)
        assertNull(restored.library.customDoqEndpoint)
        assertEquals(library.customSecondaryEndpoint, restored.library.customSecondaryEndpoint)
        assertEquals(library.customName, restored.library.customName)
    }

    @Test
    fun `DoH backup rows preserve encoded query pairs and disabled positions and refuse fragments`() {
        val primary = "https://resolver.example/dns%2Fquery?mode=one%2Ftwo&empty=&mode=second&flag"
        val secondary = "https://secondary.example/dns%2Fquery?mode=%23two&empty=&flag"
        val custom = ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
            customDohUrl = primary, customSecondaryEndpoint = secondary, customName = "Saved HTTPS"))
        val device = ResolverTier(ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS))
        for (rows in listOf(listOf(custom, device.copy(isEnabled = false)), listOf(device, custom.copy(isEnabled = false)))) {
            val library = orderedLibrary(rows)
            val payload = BackupPayloadMapper.toPayload(library)
            val decoded = BackupPayloadJson.decode(BackupPayloadJson.encode(payload))
            val wireRows = decoded.savedDNSResolutionSelections!!
            val customIndex = rows.indexOfFirst { it.entry.providerId == ResolverProviderId.CUSTOM }
            assertEquals(primary, wireRows[customIndex].primary)
            assertEquals(secondary, wireRows[customIndex].secondary)
            assertEquals(rows.map { it.isEnabled }, wireRows.map { it.isEnabled })
            val restored = BackupPayloadMapper.restore(decoded).library
            assertEquals(library, restored)
            assertEquals(rows, restored.resolverTarget.toSelection().tiers)
            assertEquals(payload, BackupPayloadMapper.toPayload(restored))
            for (invalid in listOf(wireRows[customIndex].copy(primary = "$primary#unused"),
                wireRows[customIndex].copy(secondary = "$secondary#unused"))) {
                val refused = decoded.copy(savedDNSResolutionSelections = wireRows.mapIndexed { index, row ->
                    if (index == customIndex) invalid else row
                })
                assertTrue(runCatching { BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(refused))) }
                    .exceptionOrNull() is BackupPayloadMappingException)
            }
        }
    }

    @Test
    fun `a custom DoT resolver round-trips through the portable payload`() {
        val endpoint = "tls://resolver.home.example:853"
        val library = FilterLibraryDraft(
            profiles = listOf(profile("filter-balanced", "Balanced")),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            customDotEndpoint = endpoint,
            customSecondaryEndpoint = "tls://secondary.home.example:853",
            customName = "Home DNS",
        )

        val payload = BackupPayloadMapper.toPayload(library)
        assertEquals(BackupResolverPresetBridge.CUSTOM_DNS_ID, payload.resolverPresetID)
        assertEquals(endpoint, payload.customResolverAddress)

        val restored = BackupPayloadMapper.restore(
            BackupPayloadJson.decode(BackupPayloadJson.encode(payload)),
        )
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, restored.library.resolverPlan)
        assertEquals(endpoint, restored.library.customDotEndpoint)
        assertNull(restored.library.customDohUrl)
        assertNull(restored.library.customDoqEndpoint)
        assertEquals(library.customSecondaryEndpoint, restored.library.customSecondaryEndpoint)
        assertEquals(library.customName, restored.library.customName)
    }

    @Test
    fun `custom DoQ primary secondary and name survive portable encode restore and export`() {
        val library = FilterLibraryDraft(
            profiles = listOf(profile("filter-balanced", "Balanced")), activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            customDoqEndpoint = "doq://dns.home.example:853",
            customSecondaryEndpoint = "doq://secondary.home.example:8853", customName = "Home DNS",
        )
        val payload = BackupPayloadMapper.toPayload(library)
        assertEquals(BackupResolverPresetBridge.CUSTOM_DNS_ID, payload.resolverPresetID)
        assertEquals(library.customDoqEndpoint, payload.customResolverAddress)
        assertEquals(library.customSecondaryEndpoint, payload.customResolverSecondaryAddress)
        assertEquals(library.customName, payload.customResolverName)
        val restored = BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(payload))).library
        assertEquals(library.customDoqEndpoint, restored.customDoqEndpoint)
        assertEquals(library.customSecondaryEndpoint, restored.customSecondaryEndpoint)
        assertEquals(library.customName, restored.customName)
        assertNull(restored.customDohUrl)
        assertNull(restored.customDotEndpoint)
        assertEquals(payload, BackupPayloadMapper.toPayload(restored))
    }

    @Test
    fun `iOS DoQ aliases and resolver name normalize without dropping a secondary`() {
        val payload = BackupConfigurationPayload(resolverPresetID = BackupResolverPresetBridge.CUSTOM_DNS_ID,
            customResolverAddress = "quic://dns.home.example", customResolverSecondaryAddress = "quic://secondary.home.example:8853",
            customResolverName = "  Home DNS  ")
        val restored = BackupPayloadMapper.restore(payload).library
        assertEquals("doq://dns.home.example:853", restored.customDoqEndpoint)
        assertEquals("doq://secondary.home.example:8853", restored.customSecondaryEndpoint)
        assertEquals("Home DNS", restored.customName)
        assertTrue(runCatching { BackupPayloadMapper.restore(payload.copy(customResolverSecondaryAddress = "https://dns.example/dns-query")) }
            .exceptionOrNull() is BackupPayloadMappingException)
    }

    @Test
    fun `a malformed custom resolver is refused without substituting another endpoint`() {
        for (address in listOf(null, "doq://dns.example:53", "http://dns.example/x")) {
            val payload = BackupConfigurationPayload(
                enabledBlocklistIDs = emptySet(),
                allowedDomains = emptySet(),
                blockedDomains = emptySet(),
                resolverPresetID = BackupResolverPresetBridge.CUSTOM_DNS_ID,
                customResolverAddress = address,
                keepDomainDiagnostics = false,
                protectionEnabledHint = false,
            )
            assertTrue("address '$address' must not silently change the selected resolver",
                runCatching { BackupPayloadMapper.restore(payload) }.exceptionOrNull() is BackupPayloadMappingException)
        }
    }

    @Test
    fun `device with encrypted fallback writes the Mullvad DoH canonical id`() {
        val payload = BackupPayloadMapper.toPayload(
            FilterLibraryDraft(
                profiles = listOf(profile("filter-balanced", "Balanced")),
                activeProfileId = "filter-balanced",
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            ),
        )
        assertEquals(BackupResolverPresetBridge.DEVICE_DNS_ID, payload.resolverPresetID)
        assertTrue(payload.fallbackToDeviceDNS)
        assertTrue(payload.usesEncryptedDeviceDNSFallback)
        assertEquals(BackupResolverPresetBridge.ANDROID_MULLVAD_DOH_ID, payload.fallbackResolverPresetID)
    }

    @Test
    fun `a pre-multi-filter payload restores as a single Default filter`() {
        val payload = BackupConfigurationPayload(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            allowedDomains = setOf("school.example"),
            blockedDomains = setOf("casino.example"),
            resolverPresetID = BackupResolverPresetBridge.DEVICE_DNS_ID,
            keepDomainDiagnostics = false,
            protectionEnabledHint = true,
        )

        val restored = BackupPayloadMapper.restore(payload)
        assertEquals(1, restored.library.profiles.size)
        val filter = restored.library.profiles.single()
        assertEquals(BackupPayloadMapper.DEFAULT_FILTER_ID, filter.id)
        assertEquals(BackupPayloadMapper.DEFAULT_FILTER_NAME, filter.name)
        assertNull(filter.protectionLevel)
        assertEquals(setOf("blocklistproject-basic"), filter.enabledSourceIds)
        assertEquals(setOf("school.example"), filter.manualRules.allowedDomains)
        assertEquals(setOf("casino.example"), filter.manualRules.blockedDomains)
    }

    @Test
    fun `a seeded id restores its protection level and a foreign id does not`() {
        val payload = BackupConfigurationPayload(
            enabledBlocklistIDs = emptySet(),
            allowedDomains = emptySet(),
            blockedDomains = emptySet(),
            resolverPresetID = BackupResolverPresetBridge.DEVICE_DNS_ID,
            keepDomainDiagnostics = false,
            protectionEnabledHint = false,
            filterLibrary = BackupFilterLibrary(
                filters = listOf(
                    BackupFilter(id = "filter-balanced", name = "Balanced", emoji = "🪴"),
                    BackupFilter(id = "ios-uuid", name = "Imported", emoji = "🌿"),
                ),
                activeFilterID = "ios-uuid",
            ),
        )

        val restored = BackupPayloadMapper.restore(payload)
        assertEquals("filter-balanced", restored.library.profiles[0].id)
        assertEquals(ProtectionLevel.BALANCED, restored.library.profiles[0].protectionLevel)
        assertNull(restored.library.profiles[1].protectionLevel)
        assertEquals("ios-uuid", restored.library.activeProfileId)
    }

    @Test
    fun `invalid or ambiguous restored filters never silently produce settings`() {
        val invalidDomain = payloadWithFilter(
            BackupFilter(
                id = "filter-x",
                name = "X",
                emoji = "🌿",
                blockedDomains = setOf("1.2.3.4"),
            ),
        )
        assertTrue(runCatching { BackupPayloadMapper.restore(invalidDomain) }.isFailure)

        val duplicateNames = BackupConfigurationPayload(
            enabledBlocklistIDs = emptySet(),
            allowedDomains = emptySet(),
            blockedDomains = emptySet(),
            resolverPresetID = BackupResolverPresetBridge.DEVICE_DNS_ID,
            keepDomainDiagnostics = false,
            protectionEnabledHint = false,
            filterLibrary = BackupFilterLibrary(
                filters = listOf(
                    BackupFilter(id = "a", name = "Same", emoji = "🌿"),
                    BackupFilter(id = "b", name = "same", emoji = "🪴"),
                ),
                activeFilterID = "a",
            ),
        )
        assertTrue(runCatching { BackupPayloadMapper.restore(duplicateNames) }.isFailure)
    }

    @Test
    fun `a schema newer than supported is refused by the mapper too`() {
        val payload = BackupConfigurationPayload(
            schemaVersion = BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION + 1,
            enabledBlocklistIDs = emptySet(),
            allowedDomains = emptySet(),
            blockedDomains = emptySet(),
            resolverPresetID = BackupResolverPresetBridge.DEVICE_DNS_ID,
            keepDomainDiagnostics = false,
            protectionEnabledHint = false,
        )
        val error = runCatching { BackupPayloadMapper.restore(payload) }.exceptionOrNull()
        assertEquals(
            BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION + 1,
            (error as BackupSchemaUnsupportedException).schemaVersion,
        )
    }

    @Test
    fun `custom blocklists round trip through backup and an invalid one is refused`() {
        val source = com.lavasec.app.foundation.filter.CustomBlocklistSource(
            id = "custom-a",
            displayName = "My list",
            sourceUrl = "https://mine.example/hosts.txt",
            parseFormat = com.lavasec.app.foundation.filter.BlocklistFormat.HOSTS,
            createdAtEpochMillis = 1_700_000_000_000L,
            lastAcceptedHash = "a".repeat(64),
        )
        val library = FilterLibraryDraft(
            profiles = listOf(
                FilterProfileDraft(
                    id = "filter-a",
                    name = "A",
                    protectionLevel = null,
                    enabledSourceIds = setOf("custom-a"),
                    customBlocklists = listOf(source),
                ),
            ),
            activeProfileId = "filter-a",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
        )
        val payload = BackupPayloadMapper.toPayload(library)
        val restored = BackupPayloadMapper.restore(BackupPayloadJson.decode(BackupPayloadJson.encode(payload)))
        assertEquals(listOf(source), restored.library.profiles.single().customBlocklists)
        assertEquals(source.createdAtEpochMillis, restored.library.profiles.single().customBlocklists.single().createdAtEpochMillis)

        val invalid = payload.filterLibrary!!.filters.single().copy(
            customBlocklists = listOf(
                BackupCustomBlocklistSource(id = "custom-b", displayName = "Bad", sourceURL = "http://private.example"),
            ),
        )
        val error = runCatching {
            BackupPayloadMapper.restore(payload.copy(filterLibrary = payload.filterLibrary.copy(filters = listOf(invalid))))
        }.exceptionOrNull()
        assertTrue(error is BackupPayloadMappingException)
    }

    @Test
    fun `unknown and malformed explicit custom source formats refuse actual restore preparation`() {
        val previous = FilterLibraryDraft(profiles = listOf(profile("before", "Before")),
            activeProfileId = "before", resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY)
        for (format in listOf("future_format_v2", "", " ", "plain-domains", "plainDomains", "hosts ", "hosts,adblock")) {
            val payload = BackupPayloadJson.decode(BackupPayloadJson.encode(payloadWithFilter(
                BackupFilter(id = "filter-a", name = "A", emoji = "🌿", enabledBlocklistIDs = setOf("custom-a"),
                    customBlocklists = listOf(BackupCustomBlocklistSource(id = "custom-a", displayName = "My list",
                        sourceURL = "https://mine.example/list.txt", parseFormat = format))))))
            assertEquals(format, payload.filterLibrary!!.filters.single().customBlocklists.single().parseFormat)
            assertTrue(format, runCatching { BackupPayloadMapper.restore(payload) }.exceptionOrNull()
                is BackupPayloadMappingException)
            assertEquals(format, BackupRestorePlanResult.Refused(BackupRestoreRefusal.NotRestorable),
                BackupRestorePlan.prepare(payload, previous, BackupRetainedPreferences.DEFAULT,
                    BackupRestoreBasis(accountID = "account-a", configurationGeneration = 7L)))
        }
    }

    @Test
    fun `all known custom source formats retain their parser and metadata through restore and export`() {
        for (format in BlocklistFormat.entries) {
            // Retain the existing case-insensitive Android reader as well as the frozen wire form.
            for (wireFormat in listOf(format.name.lowercase(java.util.Locale.ROOT), format.name)) {
                val source = BackupCustomBlocklistSource(id = "custom-a", displayName = "My list",
                    sourceURL = "https://mine.example/list.txt", parseFormat = wireFormat,
                    createdAt = 123.5, lastAcceptedHash = "a".repeat(64))
                val payload = BackupPayloadJson.decode(BackupPayloadJson.encode(payloadWithFilter(
                    BackupFilter(id = "filter-a", name = "A", emoji = "🌿", enabledBlocklistIDs = setOf(source.id),
                        customBlocklists = listOf(source)))))
                val restored = BackupPayloadMapper.restore(payload).library
                val durableSource = restored.profiles.single().customBlocklists.single()
                assertEquals(wireFormat, format, durableSource.parseFormat)
                assertEquals(source.id, durableSource.id)
                assertEquals(source.displayName, durableSource.displayName)
                assertEquals(source.sourceURL, durableSource.sourceUrl)
                assertEquals(source.lastAcceptedHash, durableSource.lastAcceptedHash)
                assertEquals((source.createdAt + 978_307_200.0).times(1000.0).toLong(), durableSource.createdAtEpochMillis)
                val exported = BackupPayloadMapper.toPayload(restored).filterLibrary!!.filters.single()
                    .customBlocklists.single()
                assertEquals(source.copy(parseFormat = format.name.lowercase(java.util.Locale.ROOT)), exported)
            }
        }
    }

    @Test
    fun `legacy absent and null custom source formats still restore as auto`() {
        for (formatMember in listOf("", ", \"parseFormat\": null")) {
            val payload = BackupPayloadJson.decode("""
                {"enabledBlocklistIDs": [], "allowedDomains": [], "blockedDomains": [],
                 "resolverPresetID": "device-dns", "keepDomainDiagnostics": false, "protectionEnabledHint": false,
                 "filterLibrary": {"activeFilterID": "filter-a", "filters": [
                    {"id": "filter-a", "name": "A", "enabledBlocklistIDs": ["custom-a"],
                     "customBlocklists": [{"id": "custom-a", "displayName": "My list",
                         "sourceURL": "https://mine.example/list.txt"$formatMember}]}]}}
            """.trimIndent())
            assertEquals("auto", payload.filterLibrary!!.filters.single().customBlocklists.single().parseFormat)
            val restored = BackupPayloadMapper.restore(payload).library
            assertEquals(BlocklistFormat.AUTO, restored.profiles.single().customBlocklists.single().parseFormat)
            assertEquals("auto", BackupPayloadMapper.toPayload(restored).filterLibrary!!.filters.single()
                .customBlocklists.single().parseFormat)
        }
    }

    private fun profile(id: String, name: String): FilterProfileDraft = FilterProfileDraft(
        id = id,
        name = name,
        protectionLevel = null,
        enabledSourceIds = setOf("blocklistproject-basic"),
        manualRules = ManualDomainRules(
            allowedDomains = setOf("school.example"),
            blockedDomains = setOf("casino.example"),
        ),
    )

    private fun payloadWithFilter(filter: BackupFilter): BackupConfigurationPayload =
        BackupConfigurationPayload(
            enabledBlocklistIDs = emptySet(),
            allowedDomains = emptySet(),
            blockedDomains = emptySet(),
            resolverPresetID = BackupResolverPresetBridge.DEVICE_DNS_ID,
            keepDomainDiagnostics = false,
            protectionEnabledHint = false,
            filterLibrary = BackupFilterLibrary(filters = listOf(filter), activeFilterID = filter.id),
        )
}
