package com.lavasec.app.foundation.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadJsonTest {
    @Test
    fun `actual frozen Swift encoder DNS stamp fixtures keep primary secondary and raw metadata in the codec`() {
        for (label in listOf("plain", "doh", "dot", "doq")) {
            val json = checkNotNull(javaClass.getResource("/backup/ios-3bd86116-stamp-$label.json")).readText()
            val payload = BackupPayloadJson.decode(json)
            assertEquals(label, 2, payload.schemaVersion)
            val row = payload.savedDNSResolutionSelections!!.first()
            assertEquals(label, "custom-dns", row.id)
            assertEquals(label, "Stamp $label pair", row.name)
            assertTrue(label, row.isEnabled)
            assertTrue(label, row.primary.startsWith("sdns://"))
            assertTrue(label, row.secondary.startsWith("sdns://"))
            assertEquals(label, row.primary, payload.customResolverAddress)
            assertEquals(label, row.secondary, payload.customResolverSecondaryAddress)
            assertEquals(label, listOf("custom-dns", "device-dns"), payload.savedDNSResolutionSelections!!.map { it.id })
            assertEquals(label, payload, BackupPayloadJson.decode(BackupPayloadJson.encode(payload)))
        }
    }

    @Test
    fun `actual frozen iOS encoder fixtures preserve complete DNS rows through the Android codec`() {
        // ExportBackup.swift linked the actual LavaSecKit/AppServices objects at
        // 3bd86116f0d12595965df03cf634066d393b4896 and self-decoded these payloads on iOS.
        val cases = listOf(
            Triple("disabled-custom-doq", 3, listOf(
                BackupDNSResolutionSelection("custom-dns", "Home QUIC", "quic://resolver.test:8853",
                    "quic://secondary.test:8853", false),
                BackupDNSResolutionSelection("quad9-unfiltered-dot", "Retained Quad9"),
            )),
            Triple("plain-guardian", 3, listOf(
                BackupDNSResolutionSelection("custom-dns", "Private IP pair", "192.168.1.53", "192.168.1.54", false),
                BackupDNSResolutionSelection("cloudflare-1111", "Saved plain Cloudflare"),
            )),
            Triple("disabled-device", 3, listOf(
                BackupDNSResolutionSelection("device-dns", "Saved device", isEnabled = false),
                BackupDNSResolutionSelection("cloudflare-1111-doh", "Saved Cloudflare"),
            )),
            Triple("encrypted-pair", 2, listOf(
                BackupDNSResolutionSelection("cloudflare-1111-doh"),
                BackupDNSResolutionSelection("quad9-unfiltered-dot"),
            )),
            Triple("doq-device", 2, listOf(
                BackupDNSResolutionSelection("custom-dns", "Home QUIC", "quic://resolver.test:8853",
                    "quic://secondary.test:8853"),
                BackupDNSResolutionSelection("device-dns"),
            )),
        )
        for ((name, schema, rows) in cases) {
            val json = requireNotNull(javaClass.getResource("/backup/ios-3bd86116-$name.json"))
                .readText(Charsets.UTF_8)
            val payload = BackupPayloadJson.decode(json)
            assertEquals(name, schema, payload.schemaVersion)
            assertEquals(name, rows, payload.savedDNSResolutionSelections)
            assertTrue(name, payload.usesExplicitDNSTiers)
            assertEquals(name, payload, BackupPayloadJson.decode(BackupPayloadJson.encode(payload)))
        }
    }

    @Test
    fun `full payload round-trips exactly and the encoding is stable`() {
        val payload = fullPayload()
        val encoded = BackupPayloadJson.encode(payload)
        val decoded = BackupPayloadJson.decode(encoded)

        assertEquals(payload, decoded)
        assertEquals("encoding must be deterministic", encoded, BackupPayloadJson.encode(payload))
        assertEquals("decode/encode must be a fixed point", encoded, BackupPayloadJson.encode(decoded))
    }

    @Test
    fun `an iOS-shaped payload decodes into the canonical model`() {
        // Shape produced by the canonical iOS encoder: camelCase keys, `.sortedKeys`, dates as
        // seconds since the Apple 2001-01-01 reference date, set fields as arrays, and the
        // device-local `configurationGeneration` stamp pinned to 0 by `strippingLocalCacheState`.
        val iosPayload = """
            {
              "allowedDomains": ["school.example"],
              "blockedDomains": ["casino.example"],
              "catalogVersionHint": "catalog-42",
              "customBlocklists": [
                {
                  "createdAt": 1600,
                  "displayName": "Sensitive",
                  "id": "custom-sensitive",
                  "lastAcceptedHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "parseFormat": "hosts",
                  "sourceURL": "https://sensitive.example.com/private-list.txt"
                }
              ],
              "enabledBlocklistIDs": ["blocklistproject-basic", "custom-sensitive"],
              "fallbackResolverPresetID": "quad9-unfiltered-doh",
              "fallbackToDeviceDNS": true,
              "filterLibrary": {
                "activeFilterID": "filter-balanced",
                "configurationGeneration": 0,
                "filters": [
                  {
                    "allowedDomains": ["school.example"],
                    "blockedDomains": ["casino.example"],
                    "createdAt": 1500.5,
                    "emoji": "🪴",
                    "enabledBlocklistIDs": ["blocklistproject-basic"],
                    "id": "filter-balanced",
                    "name": "Balanced"
                  }
                ],
                "schemaVersion": 2
              },
              "keepDomainDiagnostics": true,
              "keepFilteringCounts": false,
              "keepLavaGuardProgress": true,
              "keepNetworkActivity": false,
              "lavaGuardUnlocks": {
                "records": [
                  { "guardID": "obsidian", "unlockedAt": 1500 }
                ]
              },
              "protectionEnabledHint": true,
              "resolverPresetID": "device-dns",
              "schemaVersion": 2,
              "usesEncryptedDeviceDNSFallback": true
            }
        """.trimIndent()

        val payload = BackupPayloadJson.decode(iosPayload)
        assertEquals(2, payload.schemaVersion)
        assertEquals(setOf("blocklistproject-basic", "custom-sensitive"), payload.enabledBlocklistIDs)
        assertEquals("quad9-unfiltered-doh", payload.fallbackResolverPresetID)
        assertTrue(payload.usesEncryptedDeviceDNSFallback)
        assertFalse(payload.keepNetworkActivity)
        assertEquals("catalog-42", payload.catalogVersionHint)
        assertEquals(1, payload.customBlocklists.size)
        assertEquals("hosts", payload.customBlocklists.single().parseFormat)
        assertEquals(1_600.0, payload.customBlocklists.single().createdAt, 0.0)
        assertEquals(1, payload.lavaGuardUnlocks.records.size)
        assertEquals("obsidian", payload.lavaGuardUnlocks.records.single().guardID)
        val library = requireNotNull(payload.filterLibrary)
        assertEquals("filter-balanced", library.activeFilterID)
        assertEquals(1, library.filters.size)
        assertEquals("🪴", library.filters.single().emoji)
        assertEquals(1_500.5, library.filters.single().createdAt, 0.0)

        // ...and the model immediately re-encodes to a payload the same decoder reads back.
        assertEquals(payload, BackupPayloadJson.decode(BackupPayloadJson.encode(payload)))
    }

    @Test
    fun `a custom resolver address is carried verbatim and absent stays optional`() {
        val withDot = fullPayload().copy(
            customResolverAddress = "tls://resolver.home.example:853",
            customResolverName = "Home DoT",
        )
        val decoded = BackupPayloadJson.decode(BackupPayloadJson.encode(withDot))
        assertEquals("tls://resolver.home.example:853", decoded.customResolverAddress)
        assertEquals("Home DoT", decoded.customResolverName)

        // A pre-custom payload omits the fields entirely and still decodes to null.
        val legacy = fullPayload().copy(
            resolverPresetID = "device-dns",
            customResolverAddress = null,
            customResolverSecondaryAddress = null,
            customResolverName = null,
        )
        val legacyDecoded = BackupPayloadJson.decode(BackupPayloadJson.encode(legacy))
        assertNull(legacyDecoded.customResolverAddress)
        assertNull(legacyDecoded.customResolverName)
    }

    @Test
    fun `object keys are sorted and set-backed arrays are emitted sorted`() {
        val encoded = BackupPayloadJson.encode(
            fullPayload().copy(
                enabledBlocklistIDs = linkedSetOf("zeta", "alpha", "mid"),
                blockedDomains = linkedSetOf("z.example", "a.example"),
            ),
        )
        assertTrue(
            "top-level object keys must be lexicographic",
            encoded.indexOf("\"allowedDomains\"") < encoded.indexOf("\"blockedDomains\"") &&
                encoded.indexOf("\"blockedDomains\"") < encoded.indexOf("\"schemaVersion\""),
        )
        assertTrue(encoded.contains("\"enabledBlocklistIDs\":[\"alpha\",\"mid\",\"zeta\"]"))
        assertTrue(encoded.contains("\"blockedDomains\":[\"a.example\",\"z.example\"]"))
    }

    @Test
    fun `unknown fields are ignored and absent optionals decode to the canonical defaults`() {
        val json = """
            {
              "schemaVersion": 1,
              "enabledBlocklistIDs": ["blocklistproject-basic"],
              "allowedDomains": [],
              "blockedDomains": ["casino.example"],
              "resolverPresetID": "google-public-dns",
              "keepDomainDiagnostics": false,
              "protectionEnabledHint": true,
              "someFutureField": "ignored",
              "playsHapticFeedback": false
            }
        """.trimIndent()

        val payload = BackupPayloadJson.decode(json)
        assertEquals(1, payload.schemaVersion)
        assertNull(payload.customResolverAddress)
        assertNull(payload.customResolverSecondaryAddress)
        assertNull(payload.customResolverName)
        assertNull(payload.catalogVersionHint)
        assertEquals(BackupConfigurationPayload.DEFAULT_FALLBACK_RESOLVER_PRESET_ID, payload.fallbackResolverPresetID)
        assertFalse(payload.fallbackToDeviceDNS)
        assertFalse(payload.usesExplicitDNSTiers)
        assertFalse(payload.usesEncryptedDeviceDNSFallback)
        assertTrue(payload.keepFilteringCounts)
        assertTrue(payload.keepNetworkActivity)
        assertTrue(payload.keepLavaGuardProgress)
        assertTrue(payload.lavaGuardUnlocks.records.isEmpty())
        assertTrue(payload.customBlocklists.isEmpty())
        assertNull(payload.filterLibrary)
    }

    @Test
    fun `explicit DNS tiers imply schema 2 and a future schema is rejected before any other field`() {
        val tiered = BackupPayloadJson.decode(
            """
            {
              "usesExplicitDNSTiers": true,
              "enabledBlocklistIDs": [],
              "allowedDomains": [],
              "blockedDomains": [],
              "resolverPresetID": "device-dns",
              "keepDomainDiagnostics": false,
              "protectionEnabledHint": false
            }
            """.trimIndent(),
        )
        assertEquals(2, tiered.schemaVersion)

        val future = BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION + 1
        val error = runCatching {
            BackupPayloadJson.decode(
                """{"schemaVersion":$future,"enabledBlocklistIDs":[],"allowedDomains":[],"blockedDomains":[],""" +
                    """"resolverPresetID":"device-dns","keepDomainDiagnostics":false,"protectionEnabledHint":false}""",
            )
        }.exceptionOrNull()
        assertEquals(future, (error as BackupSchemaUnsupportedException).schemaVersion)
    }

    @Test
    fun `frozen iOS schema 3 DNS rows retain order disabled state and custom fields`() {
        // Independent literal shape from frozen 3bd86116 BackupConfigurationPayload and
        // DNSResolutionSelection Codable contracts; the actual Swift encoder fixture is separate.
        val wire = """
            {"schemaVersion":3,"enabledBlocklistIDs":[],"allowedDomains":[],"blockedDomains":[],
             "resolverPresetID":"custom-dns","customResolverAddress":"doq://dns.example:853",
             "customResolverSecondaryAddress":"doq://secondary.example:853","customResolverName":"Home DNS",
             "usesExplicitDNSTiers":true,"keepDomainDiagnostics":false,"protectionEnabledHint":false,
             "savedDNSResolutionSelections":[
               {"id":"device-dns","name":"Device DNS","primary":"","secondary":"","isEnabled":false},
               {"id":"custom-dns","name":"Home DNS","primary":"doq://dns.example:853",
                "secondary":"doq://secondary.example:853","isEnabled":true}]}
        """.trimIndent()
        val payload = BackupPayloadJson.decode(wire)
        assertEquals(3, payload.schemaVersion)
        assertEquals(listOf(
            BackupDNSResolutionSelection("device-dns", "Device DNS", isEnabled = false),
            BackupDNSResolutionSelection("custom-dns", "Home DNS", "doq://dns.example:853", "doq://secondary.example:853"),
        ), payload.savedDNSResolutionSelections)
        assertEquals(payload, BackupPayloadJson.decode(BackupPayloadJson.encode(payload)))
        assertEquals(BackupPayloadJson.encode(payload), BackupPayloadJson.encode(BackupPayloadJson.decode(BackupPayloadJson.encode(payload))))
    }

    @Test
    fun `only inactive saved rows promote the required schema beyond legacy explicit tiers`() {
        val legacy = fullPayload().copy(schemaVersion = 1, usesExplicitDNSTiers = false)
        assertEquals(1, BackupPayloadJson.decode(BackupPayloadJson.encode(legacy)).schemaVersion)
        assertFalse(BackupPayloadJson.encode(legacy).contains("savedDNSResolutionSelections"))
        val enabled = legacy.copy(usesExplicitDNSTiers = true,
            savedDNSResolutionSelections = listOf(BackupDNSResolutionSelection("device-dns")))
        assertEquals(2, BackupPayloadJson.decode(BackupPayloadJson.encode(enabled)).schemaVersion)
        val disabled = enabled.copy(savedDNSResolutionSelections = listOf(
            BackupDNSResolutionSelection("device-dns", isEnabled = false),
            BackupDNSResolutionSelection("custom-dns", primary = "doq://dns.example:853"),
        ))
        assertEquals(3, BackupPayloadJson.decode(BackupPayloadJson.encode(disabled)).schemaVersion)
    }

    @Test
    fun `DNS row optional defaults match iOS and wrong field types never default away`() {
        val base = """{"enabledBlocklistIDs":[],"allowedDomains":[],"blockedDomains":[],"resolverPresetID":"device-dns",
            "keepDomainDiagnostics":false,"protectionEnabledHint":false,"savedDNSResolutionSelections":ROWS}"""
        val payload = BackupPayloadJson.decode(base.replace("ROWS", """[{"id":"device-dns"}]"""))
        assertEquals(listOf(BackupDNSResolutionSelection("device-dns")), payload.savedDNSResolutionSelections)
        for (rows in listOf("{}", "[{}]", """[{"id":"device-dns","isEnabled":"false"}]""",
            """[{"id":"custom-dns","primary":7}]""")) {
            assertTrue(rows, runCatching { BackupPayloadJson.decode(base.replace("ROWS", rows)) }.isFailure)
        }
    }

    @Test
    fun `malformed, wrongly typed and incomplete input never produce settings`() {
        assertTrue(runCatching { BackupPayloadJson.decode("not json") }.isFailure)
        assertTrue(runCatching { BackupPayloadJson.decode("[]") }.isFailure)
        assertTrue(
            runCatching {
                BackupPayloadJson.decode(
                    """{"schemaVersion":"one","enabledBlocklistIDs":[],"allowedDomains":[],""" +
                        """"blockedDomains":[],"resolverPresetID":"device-dns","keepDomainDiagnostics":false,""" +
                        """"protectionEnabledHint":false}""",
                )
            }.isFailure,
        )
        assertTrue(
            "a required boolean must not default away",
            runCatching {
                BackupPayloadJson.decode(
                    """{"enabledBlocklistIDs":[],"allowedDomains":[],"blockedDomains":[],""" +
                        """"resolverPresetID":"device-dns","protectionEnabledHint":false}""",
                )
            }.isFailure,
        )
        assertTrue(
            "non-string array elements must be rejected",
            runCatching {
                BackupPayloadJson.decode(
                    """{"enabledBlocklistIDs":["a",7],"allowedDomains":[],"blockedDomains":[],""" +
                        """"resolverPresetID":"device-dns","keepDomainDiagnostics":false,""" +
                        """"protectionEnabledHint":false}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `oversized input is refused by the character budget`() {
        val oversized = "{\"padding\":\"" + "a".repeat(BackupJsonCodec.MAX_STRING_LENGTH + 1) + "\"}"
        val error = runCatching { BackupPayloadJson.decode(oversized) }.exceptionOrNull()
        assertTrue(error is BackupJsonSyntaxException)
    }

    @Test
    fun `deeply nested input is refused by the depth budget`() {
        val nested = "[".repeat(BackupJsonCodec.MAX_DEPTH + 2) + "]".repeat(BackupJsonCodec.MAX_DEPTH + 2)
        assertTrue(runCatching { BackupJsonCodec.parse(nested) }.isFailure)
    }

    private fun fullPayload(): BackupConfigurationPayload = BackupConfigurationPayload(
        schemaVersion = 2,
        enabledBlocklistIDs = linkedSetOf("blocklistproject-basic", "custom-sensitive"),
        allowedDomains = linkedSetOf("school.example"),
        blockedDomains = linkedSetOf("casino.example"),
        resolverPresetID = "custom-dns",
        customResolverAddress = "https://dns.example/dns-query",
        customResolverSecondaryAddress = "https://backup.example/dns-query",
        customResolverName = "Home DNS",
        fallbackToDeviceDNS = true,
        usesExplicitDNSTiers = true,
        usesEncryptedDeviceDNSFallback = true,
        fallbackResolverPresetID = "cloudflare-1111-doh",
        fallbackCustomResolverAddress = "https://fallback.example/dns-query",
        fallbackCustomResolverSecondaryAddress = "https://fallback2.example/dns-query",
        fallbackCustomResolverName = "Fallback DNS",
        keepFilteringCounts = false,
        keepDomainDiagnostics = true,
        keepNetworkActivity = false,
        keepLavaGuardProgress = false,
        lavaGuardUnlocks = BackupLavaGuardAchievementLedger(
            listOf(
                BackupLavaGuardUnlockRecord("obsidian", 1_500.0),
                BackupLavaGuardUnlockRecord("emerald", 1_600.5),
            ),
        ),
        protectionEnabledHint = true,
        catalogVersionHint = "catalog-1",
        customBlocklists = listOf(
            BackupCustomBlocklistSource(
                id = "custom-sensitive",
                displayName = "Sensitive",
                sourceURL = "https://sensitive.example.com/private-list.txt",
                parseFormat = "hosts",
                createdAt = 1_700.0,
                lastAcceptedHash = "a".repeat(64),
            ),
        ),
        filterLibrary = BackupFilterLibrary(
            schemaVersion = 2,
            filters = listOf(
                BackupFilter(
                    id = "default",
                    name = "Default",
                    emoji = "🌿",
                    enabledBlocklistIDs = linkedSetOf("blocklistproject-basic"),
                    customBlocklists = listOf(
                        BackupCustomBlocklistSource(
                            id = "custom-child",
                            displayName = "Child",
                            sourceURL = "https://child.example/list.txt",
                        ),
                    ),
                    blockedDomains = linkedSetOf("casino.example"),
                    allowedDomains = linkedSetOf("school.example"),
                    createdAt = 1_000.25,
                ),
                BackupFilter(
                    id = "filter-balanced",
                    name = "Balanced",
                    emoji = "🪴",
                    enabledBlocklistIDs = linkedSetOf("stevenblack-unified"),
                    blockedDomains = linkedSetOf("ads.example"),
                    allowedDomains = linkedSetOf("bank.example"),
                    createdAt = 0.0,
                ),
            ),
            activeFilterID = "filter-balanced",
            configurationGeneration = 0,
        ),
    )
}
