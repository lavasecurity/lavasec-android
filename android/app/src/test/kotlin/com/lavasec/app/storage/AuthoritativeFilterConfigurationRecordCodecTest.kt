package com.lavasec.app.storage

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.LinkedHashSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthoritativeFilterConfigurationRecordCodecTest {
    @Test
    fun `QA exact hosts round trip independently of ordinary rules and resolver values`() {
        val original = validRecord()
        val probes = com.lavasec.app.foundation.filter.DeviceQaProbeSet.fromSuffix("qa.example")
        val saved = record(original.configuration.withQaProbeSet(probes))
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(saved))!!
        assertEquals(saved, decoded)
        assertEquals(original.configuration, decoded.configuration.withQaProbeSet(null))
        assertFalse(canonicalFilterConfigurationFingerprint(original.configuration, "catalog-v1", "b".repeat(64)) ==
            canonicalFilterConfigurationFingerprint(saved.configuration, "catalog-v1", "b".repeat(64)))
        for (schema in 1..7) {
            assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
                envelope(validRawRecord().copy(schema = schema)))!!.configuration.qaProbeSet)
        }
    }

    @Test
    fun `noncanonical invalid and publicly marked QA records fail closed`() {
        val hosted = com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED.allDomains
        for (domains in listOf(hosted.toMutableList().apply { this[0] = this[0].uppercase() },
                hosted.toMutableList().apply { this[3] = "localhost" })) {
            assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
                envelope(validRawRecord().copy(qaDomains = domains.map(::bytes)))))
        }
        val raw = validRawRecord().copy(credentialUnlockRequired = 0,
            profiles = validRawRecord().profiles.map { it.copy(allowedDomains = emptyList(), blockedDomains = emptyList()) },
            qaDomains = hosted.map(::bytes))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(raw)))
    }

    @Test
    fun `frozen stamp primary secondary and disabled rows survive authority codec with bootstrap bound fingerprint`() {
        for (fixture in com.lavasec.app.DnsStampFixture.frozen) {
            val tiers = listOf(ResolverTier(com.lavasec.app.foundation.resolver.ResolverSelectionPresets.DEVICE_PLAIN,
                false, "Retained network"), ResolverTier(fixture.entry(), true, "Raw stamp"))
            val plan = checkNotNull(com.lavasec.app.foundation.resolver.ResolverSelection(tiers).toFilterResolverPlan())
            val original = record(validDraft().withResolverPlan(plan))
            val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(AuthoritativeFilterConfigurationRecordCodec.encode(original))
            assertEquals(original, decoded)
            assertEquals(tiers, decoded?.configuration?.resolverTarget?.toSelection()?.tiers)
            if (fixture.transport == CatalogResolverTransport.PLAIN_DNS) continue
            val changedPrimary = com.lavasec.app.DnsStampFixture.changedBootstrap(fixture.primary)
            val changedTiers = listOf(tiers.first(), tiers.last().copy(entry = fixture.entry(primary = changedPrimary)))
            val changed = validDraft().withResolverPlan(checkNotNull(com.lavasec.app.foundation.resolver.ResolverSelection(changedTiers)
                .toFilterResolverPlan()))
            assertFalse(canonicalFilterConfigurationFingerprint(original.configuration, "catalog-v1", "b".repeat(64)) ==
                canonicalFilterConfigurationFingerprint(changed, "catalog-v1", "b".repeat(64)))
        }
    }

    @Test
    fun `DoQ authority retains exact named primary secondary and disabled ladder`() {
        val original = validRecord()
        val plan = com.lavasec.app.foundation.resolver.FilterResolverPlan(
            MilestoneResolverPlan.MULLVAD_ONLY,
            customDoqEndpoint = "doq://dns.example:8853", customName = "Private",
            customSecondaryEndpoint = "doq://secondary.example:853",
            savedTiers = listOf(
                ResolverTier(ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS), false),
                ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOQ,
                    customDoqEndpoint = "doq://dns.example:8853", customName = "Private",
                    customSecondaryEndpoint = "doq://secondary.example:853")),
            ),
        )
        val changed = record(original.configuration.withResolverPlan(plan))
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(AuthoritativeFilterConfigurationRecordCodec.encode(changed))
        assertEquals(changed, decoded)
        assertEquals(plan, decoded?.configuration?.resolverTarget)
        assertFalse(canonicalFilterConfigurationFingerprint(original.configuration, "catalog-v1", "b".repeat(64)) ==
            canonicalFilterConfigurationFingerprint(changed.configuration, "catalog-v1", "b".repeat(64)))
    }

    @Test
    fun `plain custom row authority preserves primary secondary and name across the record`() {
        val tiers = listOf(ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.PLAIN_DNS,
            customPlainEndpoint = "192.168.1.53", customName = "Office", customSecondaryEndpoint = "192.168.1.54")))
        val plan = com.lavasec.app.foundation.resolver.ResolverSelection(tiers).toFilterResolverPlan()!!
        val original = record(validDraft().withResolverPlan(plan))
        assertEquals(original, AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(AuthoritativeFilterConfigurationRecordCodec.encode(original)))
        val changed = validDraft().withResolverPlan(com.lavasec.app.foundation.resolver.ResolverSelection(listOf(
            ResolverTier(tiers.single().entry.copy(customPlainEndpoint = "192.168.1.55")))).toFilterResolverPlan()!!)
        assertFalse(canonicalFilterConfigurationFingerprint(original.configuration, "catalog-v1", "b".repeat(64)) ==
            canonicalFilterConfigurationFingerprint(changed, "catalog-v1", "b".repeat(64)))
    }

    @Test
    fun `full Plus library retains maximum length domains without truncation`() {
        fun domains(prefix: Char) = (0 until 1_000).mapTo(linkedSetOf()) { index ->
            listOf("$prefix${index.toString().padStart(4, '0')}" + "a".repeat(58),
                "b".repeat(63), "c".repeat(63), "d".repeat(61)).joinToString(".")
        }
        val rules = ManualDomainRules(domains('a'), domains('z'))
        assertTrue(rules.allowedDomains.all { it.length == 253 })
        val profiles = (0 until 50).map { index ->
            FilterProfileDraft("filter-$index", "Filter $index", null, emptySet(), rules)
        }
        val original = AuthoritativeFilterConfigurationRecord(
            configuration = FilterLibraryDraft(profiles, profiles.first().id, MilestoneResolverPlan.DEVICE_DNS_ONLY),
            configurationIdentity = ConfigurationIdentity(41L, "a".repeat(64)),
            credentialUnlockRequired = true,
            artifactSha256 = "b".repeat(64),
            artifactIdentitySha256 = "c".repeat(64),
        )
        val encoded = AuthoritativeFilterConfigurationRecordCodec.encode(original)
        assertTrue(encoded.size > 25_000_000)
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(encoded)
        assertEquals(original, decoded)
        assertEquals(50, decoded?.configuration?.profiles?.size)
    }

    @Test
    fun `round trip preserves the exact complete authoritative record`() {
        val original = validRecord()

        val encoded = AuthoritativeFilterConfigurationRecordCodec.encode(original)
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(encoded)

        assertEquals(original, decoded)
        assertEquals(validDraft(), decoded?.configuration)
        assertEquals(ConfigurationIdentity(41L, "a".repeat(64)), decoded?.configurationIdentity)
        assertEquals(true, decoded?.credentialUnlockRequired)
        assertEquals("b".repeat(64), decoded?.artifactSha256)
        assertEquals("c".repeat(64), decoded?.artifactIdentitySha256)
        assertTrue(encoded.size <= AuthoritativeFilterConfigurationRecordCodec.MAX_RECORD_BYTES)
    }

    @Test
    fun `round trip preserves FOSS domains beyond Plus quotas independently of the current tier`() {
        val allowed = LinkedHashSet(
            List(1_001) { "allowed-$it.example" },
        )
        val blocked = LinkedHashSet(
            List(1_001) { "blocked-$it.example" },
        )
        val configuration = validDraft().let { original ->
            original.withProfile(
                original.profiles.first().withManualRules(
                    ManualDomainRules(
                        allowedDomains = allowed,
                        blockedDomains = blocked,
                    ),
                ),
            )
        }
        val original = AuthoritativeFilterConfigurationRecord(
            configuration = configuration,
            configurationIdentity = ConfigurationIdentity(41L, "a".repeat(64)),
            credentialUnlockRequired = true,
            artifactSha256 = "b".repeat(64),
            artifactIdentitySha256 = "c".repeat(64),
        )

        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(original),
        )

        assertEquals(original, decoded)
        assertEquals(
            ManualDomainRules(allowed, blocked),
            decoded?.configuration?.profiles?.first()?.manualRules,
        )
    }

    @Test
    fun `codec is deterministic and checksums the complete envelope`() {
        val record = validRecord()

        val first = AuthoritativeFilterConfigurationRecordCodec.encode(record)
        val second = AuthoritativeFilterConfigurationRecordCodec.encode(record)
        val corrupt = first.copyOf().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 1).toByte() }

        assertTrue(first.contentEquals(second))
        assertTrue(envelope(validRawRecord()).contentEquals(first))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(corrupt))
    }

    @Test
    fun `a custom DoH endpoint round trips as a trailing field without moving built-in bytes`() {
        val builtIn = validRecord()
        val builtInBytes = AuthoritativeFilterConfigurationRecordCodec.encode(builtIn)
        // The independent current-schema fixture matches the production encoder's absence markers.
        assertTrue(envelope(validRawRecord()).contentEquals(builtInBytes))

        val url = "https://dns.google/dns-query"
        val custom = record(validDraft().let {
            FilterLibraryDraft(
                profiles = it.profiles,
                activeProfileId = it.activeProfileId,
                resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
                customDohUrl = url,
            )
        })

        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(custom),
        )

        assertEquals(custom, decoded)
        assertEquals(url, decoded?.configuration?.customDohUrl)
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, decoded?.configuration?.resolverPlan)
    }

    @Test
    fun `the configuration fingerprint moves when the custom DoH URL changes`() {
        fun fingerprint(url: String?): String = canonicalFilterConfigurationFingerprint(
            configuration = validDraft().let {
                FilterLibraryDraft(
                    profiles = it.profiles,
                    activeProfileId = it.activeProfileId,
                    resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
                    customDohUrl = url,
                )
            },
            catalogRevision = "catalog-v1",
            catalogSha256 = "b".repeat(64),
        )

        val builtIn = fingerprint(null)
        val first = fingerprint("https://a.example/query")
        val second = fingerprint("https://b.example/query")

        // The built-in fingerprint is untouched, and every distinct custom endpoint is its own
        // identity even though every custom plan shares the MULLVAD_ONLY enum name.
        assertFalse(builtIn == first)
        assertFalse(first == second)
    }

    @Test
    fun `a custom DoT endpoint round trips after the empty DoH marker`() {
        val builtIn = validRecord()
        val builtInBytes = AuthoritativeFilterConfigurationRecordCodec.encode(builtIn)
        assertTrue(envelope(validRawRecord()).contentEquals(builtInBytes))

        val endpoint = "tls://dns.google:853"
        val custom = record(validDraft().let {
            FilterLibraryDraft(
                profiles = it.profiles,
                activeProfileId = it.activeProfileId,
                resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
                customDotEndpoint = endpoint,
            )
        })

        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(custom),
        )

        assertEquals(custom, decoded)
        assertEquals(endpoint, decoded?.configuration?.customDotEndpoint)
        assertNull(decoded?.configuration?.customDohUrl)
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, decoded?.configuration?.resolverPlan)
    }

    @Test
    fun `the configuration fingerprint moves when the custom DoT endpoint changes`() {
        fun fingerprint(endpoint: String?): String = canonicalFilterConfigurationFingerprint(
            configuration = validDraft().let {
                FilterLibraryDraft(
                    profiles = it.profiles,
                    activeProfileId = it.activeProfileId,
                    resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
                    customDotEndpoint = endpoint,
                )
            },
            catalogRevision = "catalog-v1",
            catalogSha256 = "b".repeat(64),
        )

        val builtIn = fingerprint(null)
        val first = fingerprint("tls://a.example:853")
        val second = fingerprint("tls://b.example:853")

        assertFalse(builtIn == first)
        assertFalse(first == second)
    }

    @Test
    fun `a custom name and secondary endpoint round trip with the endpoint`() {
        val custom = record(validDraft().let {
            FilterLibraryDraft(
                profiles = it.profiles,
                activeProfileId = it.activeProfileId,
                resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
                customDohUrl = "https://dns.google/dns-query",
                customName = "Home Resolver",
                customSecondaryEndpoint = "https://cloudflare-dns.com/dns-query",
            )
        })

        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(custom),
        )

        assertEquals(custom, decoded)
        assertEquals("Home Resolver", decoded?.configuration?.customName)
        assertEquals("https://dns.google/dns-query", decoded?.configuration?.customDohUrl)
        assertEquals(
            "https://cloudflare-dns.com/dns-query",
            decoded?.configuration?.customSecondaryEndpoint,
        )
        assertNull(decoded?.configuration?.customDotEndpoint)
    }

    @Test
    fun `a schema two custom record still decodes with no name or secondary`() {
        val raw = validRawRecord().copy(
            schema = 2,
            customDohUrl = bytes("https://dns.google/dns-query"),
        )

        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(raw))

        assertEquals("https://dns.google/dns-query", decoded?.configuration?.customDohUrl)
        assertNull(decoded?.configuration?.customName)
        assertNull(decoded?.configuration?.customSecondaryEndpoint)
        assertNull(decoded?.configuration?.customDotEndpoint)
    }

    @Test
    fun `the configuration fingerprint moves when the custom name or secondary changes`() {
        fun fingerprint(name: String?, secondary: String?): String = canonicalFilterConfigurationFingerprint(
            configuration = validDraft().let {
                FilterLibraryDraft(
                    profiles = it.profiles,
                    activeProfileId = it.activeProfileId,
                    resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
                    customDohUrl = "https://a.example/query",
                    customName = name,
                    customSecondaryEndpoint = secondary,
                )
            },
            catalogRevision = "catalog-v1",
            catalogSha256 = "b".repeat(64),
        )

        val base = fingerprint(null, null)
        assertFalse(base == fingerprint("Home", null))
        assertFalse(base == fingerprint(null, "https://b.example/query"))
        assertFalse(fingerprint("Home", null) == fingerprint("Office", null))
    }

    @Test
    fun `unknown schema and malformed magic fail closed even with a valid checksum`() {
        val valid = AuthoritativeFilterConfigurationRecordCodec.encode(validRecord())
        val unknownSchema = valid.copyOf().also {
            ByteBuffer.wrap(it, MAGIC.size, Int.SIZE_BYTES).putInt(SCHEMA + 1)
            rewriteChecksum(it)
        }
        val unknownMagic = valid.copyOf().also {
            it[0] = 'X'.code.toByte()
            rewriteChecksum(it)
        }

        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(unknownSchema))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(unknownMagic))
    }

    @Test
    fun `invalid UTF-8 is rejected before value reconstruction`() {
        val raw = validRawRecord().copy(activeProfileId = byteArrayOf(0xc3.toByte(), 0x28))

        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(raw)))
    }

    @Test
    fun `encoder rejects malformed UTF-16 instead of persisting a replacement character`() {
        val malformed = validDraft().let { original ->
            original.withProfile(
                original.profiles.first().withEnabledSourceIds(linkedSetOf("\uD800")),
            )
        }

        assertIllegalArgument {
            AuthoritativeFilterConfigurationRecord(
                configuration = malformed,
                configurationIdentity = ConfigurationIdentity(41L, "a".repeat(64)),
                credentialUnlockRequired = true,
                artifactSha256 = "b".repeat(64),
                artifactIdentitySha256 = "c".repeat(64),
            )
        }
    }

    @Test
    fun `complete file string and collection bounds fail closed`() {
        val oversizedFile = ByteArray(AuthoritativeFilterConfigurationRecordCodec.MAX_RECORD_BYTES + 1)
        val oversizedString = validRawRecord().copy(
            activeProfileId = ByteArray(
                AuthoritativeFilterConfigurationRecordCodec.MAX_STRING_BYTES + 1,
            ) { 'a'.code.toByte() },
        )
        val oversizedCount = validRawRecord().copy(
            profiles = emptyList(),
            profileCountOverride = Int.MAX_VALUE,
        )
        val oversizedSources = validRawRecord().let { raw ->
            raw.copy(
                profiles = raw.profiles.mapIndexed { index, profile ->
                    if (index == 0) {
                        profile.copy(
                            sourceIds = List(
                                AuthoritativeFilterConfigurationRecordCodec.MAX_SOURCE_IDS_PER_PROFILE + 1,
                            ) { sourceIndex -> "source-$sourceIndex".encodeToByteArray() },
                        )
                    } else {
                        profile
                    }
                },
            )
        }

        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(oversizedFile))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(oversizedString)))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(oversizedCount)))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(oversizedSources)))
    }

    @Test
    fun `record construction enforces the persisted source count bound`() {
        val oversizedSources = LinkedHashSet(
            List(
                AuthoritativeFilterConfigurationRecordCodec.MAX_SOURCE_IDS_PER_PROFILE + 1,
            ) { "source-$it" },
        )
        val draft = validDraft().let { original ->
            original.withProfile(
                original.profiles.first().withEnabledSourceIds(oversizedSources),
            )
        }

        assertIllegalArgument {
            AuthoritativeFilterConfigurationRecord(
                configuration = draft,
                configurationIdentity = ConfigurationIdentity(41L, "a".repeat(64)),
                credentialUnlockRequired = true,
                artifactSha256 = "b".repeat(64),
                artifactIdentitySha256 = "c".repeat(64),
            )
        }
    }

    @Test
    fun `encoder rejects aggregate payload before growing beyond the record bound`() {
        val boundedStrings = LinkedHashSet(
            List(80) { index ->
                index.toString().padStart(8, '0') +
                    "x".repeat(
                        AuthoritativeFilterConfigurationRecordCodec.MAX_STRING_BYTES - 8,
                    )
            },
        )
        val original = validDraft()
        val draft = FilterLibraryDraft(
            profiles = (0 until 52).map { index ->
                FilterProfileDraft("filter-$index", "Filter $index", null, boundedStrings,
                    original.profiles[1].manualRules)
            },
            activeProfileId = "filter-0",
            resolverPlan = original.resolverPlan,
        )
        val record = AuthoritativeFilterConfigurationRecord(
            configuration = draft,
            configurationIdentity = ConfigurationIdentity(41L, "a".repeat(64)),
            credentialUnlockRequired = true,
            artifactSha256 = "b".repeat(64),
            artifactIdentitySha256 = "c".repeat(64),
        )

        assertIllegalArgument {
            AuthoritativeFilterConfigurationRecordCodec.encode(record)
        }
    }

    @Test
    fun `unknown resolver and protection enum names fail closed`() {
        val raw = validRawRecord()
        val invalidResolver = raw.copy(resolverPlan = "NOT_A_RESOLVER".encodeToByteArray())
        val invalidProtection = raw.copy(
            profiles = raw.profiles.mapIndexed { index, profile ->
                if (index == 0) profile.copy(protectionLevel = "NOT_A_LEVEL".encodeToByteArray()) else profile
            },
        )

        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(invalidResolver)))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(invalidProtection)))
    }

    @Test
    fun `duplicate profile source and manual entries fail closed`() {
        val raw = validRawRecord()
        val duplicateProfile = raw.copy(
            profiles = listOf(raw.profiles[0], raw.profiles[0], raw.profiles[2]),
        )
        val duplicateSource = raw.copy(
            profiles = raw.profiles.mapIndexed { index, profile ->
                if (index == 0) profile.copy(sourceIds = listOf(bytes("source-core"), bytes("source-core"))) else profile
            },
        )
        val duplicateManualRule = raw.copy(
            profiles = raw.profiles.mapIndexed { index, profile ->
                if (index == 1) {
                    profile.copy(allowedDomains = listOf(bytes("safe.example"), bytes("safe.example")))
                } else {
                    profile
                }
            },
        )
        val canonicalAliasDuplicate = raw.copy(
            profiles = raw.profiles.mapIndexed { index, profile ->
                if (index == 1) {
                    profile.copy(
                        allowedDomains = listOf(
                            bytes("SAFE.EXAMPLE"),
                            bytes("safe.example"),
                        ),
                    )
                } else {
                    profile
                }
            },
        )
        val noncanonicalManualRule = raw.copy(
            profiles = raw.profiles.mapIndexed { index, profile ->
                if (index == 1) {
                    profile.copy(allowedDomains = listOf(bytes("SAFE.EXAMPLE")))
                } else {
                    profile
                }
            },
        )

        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(duplicateProfile)))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(duplicateSource)))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(duplicateManualRule)))
        assertNull(
            AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
                envelope(canonicalAliasDuplicate),
            ),
        )
        assertNull(
            AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
                envelope(noncanonicalManualRule),
            ),
        )
    }

    @Test
    fun `noncanonical digests and nonpositive generations fail closed`() {
        val raw = validRawRecord()
        val invalidRecords = listOf(
            raw.copy(generation = 0L),
            raw.copy(configurationFingerprint = bytes("A".repeat(64))),
            raw.copy(artifactSha256 = bytes("B".repeat(64))),
            raw.copy(artifactIdentitySha256 = bytes("c".repeat(63))),
        )

        invalidRecords.forEach { invalid ->
            assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(invalid)))
        }
    }

    @Test
    fun `invalid boolean and incoherent unlock requirement fail closed`() {
        val raw = validRawRecord()

        assertNull(
            AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
                envelope(raw.copy(credentialUnlockRequired = 2)),
            ),
        )
        assertNull(
            AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
                envelope(raw.copy(credentialUnlockRequired = 0)),
            ),
        )
    }

    @Test
    fun `truncation trailing bytes and invalid length fail closed`() {
        val encoded = AuthoritativeFilterConfigurationRecordCodec.encode(validRecord())
        val truncated = encoded.copyOf(encoded.size - 1)
        val extended = encoded + byteArrayOf(0)
        val invalidLengthPayload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeLong(41L)
                output.writeInt(-1)
            }
            bytes.toByteArray()
        }

        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(truncated))
        assertNull(AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(extended))
        assertNull(
            AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
                envelope(invalidLengthPayload),
            ),
        )
    }

    @Test
    fun `record contains values only and never normalizes caller supplied digests`() {
        assertIllegalArgument {
            AuthoritativeFilterConfigurationRecord(
                configuration = validDraft(),
                configurationIdentity = ConfigurationIdentity(41L, "A".repeat(64)),
                credentialUnlockRequired = true,
                artifactSha256 = "b".repeat(64),
                artifactIdentitySha256 = "c".repeat(64),
            )
        }
        assertIllegalArgument {
            AuthoritativeFilterConfigurationRecord(
                configuration = validDraft(),
                configurationIdentity = ConfigurationIdentity(41L, "a".repeat(64)),
                credentialUnlockRequired = true,
                artifactSha256 = "B".repeat(64),
                artifactIdentitySha256 = "c".repeat(64),
            )
        }
    }

    @Test
    fun `committed emoji round trips and an invalid emoji falls back to the canonical default`() {
        val base = validDraft().profiles.first()
        val custom = validDraft().withProfile(
            FilterProfileDraft(
                id = base.id,
                name = base.name,
                protectionLevel = base.protectionLevel,
                enabledSourceIds = base.enabledSourceIds,
                manualRules = base.manualRules,
                emoji = "🚀",
            ),
        )
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(record(custom)),
        )

        assertEquals("🚀", decoded?.configuration?.profiles?.first { it.id == base.id }?.emoji)

        val invalid = FilterProfileDraft(
            id = "filter-x",
            name = "Custom",
            protectionLevel = null,
            enabledSourceIds = emptySet(),
            emoji = "not-an-emoji",
        )
        assertEquals(
            com.lavasec.app.foundation.filter.FilterIdentityPolicy
                .defaultEmoji(id = "filter-x", name = "Custom"),
            invalid.emoji,
        )
    }

    @Test
    fun `version one records decode with the canonical derived emoji and no loss`() {
        val legacy = validRawRecord().copy(schema = 1)

        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(legacy))

        assertEquals(validRecord(), decoded)
        assertEquals(
            com.lavasec.app.foundation.filter.FilterIdentityPolicy
                .defaultEmoji(id = "filter-essential", name = "Core"),
            decoded?.configuration?.profiles?.first()?.emoji,
        )
    }

    @Test
    fun `custom blocklists round trip through the current schema`() {
        val base = validDraft().profiles.first()
        val custom = validDraft().withProfile(
            FilterProfileDraft(
                id = base.id,
                name = base.name,
                protectionLevel = base.protectionLevel,
                enabledSourceIds = base.enabledSourceIds + "custom-a" + "custom-b" + "custom-c",
                manualRules = base.manualRules,
                emoji = base.emoji,
                customBlocklists = listOf(
                    CustomBlocklistSource(
                        id = "custom-a",
                        displayName = "A",
                        sourceUrl = "https://a.example.com/list.txt",
                        parseFormat = BlocklistFormat.HOSTS,
                        createdAtEpochMillis = 1_700_000_000_000L,
                        lastAcceptedHash = "a".repeat(64),
                    ),
                    CustomBlocklistSource(
                        id = "custom-b",
                        displayName = "B",
                        sourceUrl = "https://b.example.com/list.txt",
                        parseFormat = BlocklistFormat.AUTO,
                        createdAtEpochMillis = 0L,
                    ),
                    CustomBlocklistSource(
                        id = "custom-c",
                        displayName = "C",
                        sourceUrl = "https://c.example.com/list.txt",
                        parseFormat = BlocklistFormat.DNSMASQ,
                        createdAtEpochMillis = 0L,
                    ),
                ),
            ),
        )
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(record(custom)),
        )
        assertEquals(custom, decoded?.configuration)
        assertEquals(
            listOf("custom-a", "custom-b", "custom-c"),
            decoded?.configuration?.profiles?.first { it.id == base.id }?.customBlocklists?.map { it.id },
        )
        assertEquals(
            listOf(BlocklistFormat.HOSTS, BlocklistFormat.AUTO, BlocklistFormat.DNSMASQ),
            decoded?.configuration?.profiles?.first { it.id == base.id }?.customBlocklists?.map { it.parseFormat },
        )
    }

    @Test
    fun `version two records decode with no custom blocklists`() {
        val legacy = validRawRecord().copy(schema = 2)

        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(legacy))

        assertEquals(validRecord(), decoded)
        assertTrue(
            decoded?.configuration?.profiles?.all { it.customBlocklists.isEmpty() } == true,
        )
    }

    @Test
    fun `a duplicate persisted custom blocklist id fails closed`() {
        // The production encoder rejects the duplicate at construction; the decoder bound is
        // exercised indirectly by the model's unique-id require.
        val base = validDraft().profiles.first()
        assertIllegalArgument {
            FilterProfileDraft(
                id = base.id,
                name = base.name,
                protectionLevel = base.protectionLevel,
                enabledSourceIds = base.enabledSourceIds,
                customBlocklists = listOf(
                    CustomBlocklistSource("custom-a", "A", "https://a.example.com"),
                    CustomBlocklistSource("custom-a", "B", "https://b.example.com"),
                ),
            )
        }
    }

    @Test
    fun `a retained disabled ladder round trips through the current schema`() {
        val draft = validDraft().let {
            FilterLibraryDraft(
                profiles = it.profiles,
                activeProfileId = it.activeProfileId,
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
                savedTiers = listOf(
                    ResolverTier(
                        ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS),
                    ),
                    ResolverTier(
                        ResolverSelectionEntry(ResolverProviderId.MULLVAD, CatalogResolverTransport.DOH),
                        isEnabled = false,
                    ),
                ),
            )
        }
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(record(draft)),
        )
        assertEquals(draft, decoded?.configuration)
        assertEquals(
            listOf(true, false),
            decoded?.configuration?.savedTiers?.map { it.isEnabled },
        )
        assertEquals(
            listOf(ResolverProviderId.DEVICE_DNS, ResolverProviderId.MULLVAD),
            decoded?.configuration?.savedTiers?.map { it.entry.providerId },
        )
    }

    @Test
    fun `actual schema four and five records decode without inventing retained names`() {
        val row = RawTier(bytes(ResolverProviderId.MULLVAD.rawId), bytes("DoH"), bytes(""),
            bytes(""), bytes(""), bytes(""), 1)
        for (schema in listOf(4, 5, 6)) {
            val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
                envelope(validRawRecord().copy(schema = schema, savedTiers = listOf(row))),
            )
            assertEquals(listOf(ResolverTier(ResolverSelectionEntry(ResolverProviderId.MULLVAD,
                CatalogResolverTransport.DOH))), decoded?.configuration?.savedTiers)
            assertNull(decoded?.configuration?.customDoqEndpoint)
        }
        val doq = "doq://dns.example:8853"
        val legacyDoq = validRawRecord().copy(schema = 5, customDoqEndpoint = bytes(doq),
            savedTiers = listOf(RawTier(bytes(ResolverProviderId.CUSTOM.rawId), bytes("DoQ"), bytes(""),
                bytes(""), bytes(""), bytes(""), 1, doq = bytes(doq))))
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(legacyDoq))
        assertEquals(doq, decoded?.configuration?.customDoqEndpoint)
        assertEquals(doq, decoded?.configuration?.savedTiers?.single()?.entry?.customDoqEndpoint)
        assertNull(decoded?.configuration?.savedTiers?.single()?.retainedName)
    }

    @Test
    fun `retained built-in names and encrypted primary device fallback survive the authoritative record`() {
        val selection = com.lavasec.app.foundation.resolver.ResolverSelection(listOf(
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.MULLVAD, CatalogResolverTransport.DOH),
                true, "Private first"),
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS),
                true, "Network fallback"),
        ))
        val plan = checkNotNull(selection.toFilterResolverPlan())
        val original = record(validDraft().withResolverPlan(plan))
        val restored = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(
            AuthoritativeFilterConfigurationRecordCodec.encode(original))
        assertEquals(original, restored)
        assertEquals(selection, restored?.configuration?.resolverTarget?.toSelection())
    }

    @Test
    fun `a schema three record decodes with no retained ladder and the legacy projection`() {
        val raw = validRawRecord().copy(schema = 3)
        val decoded = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(envelope(raw))

        assertNull(decoded?.configuration?.savedTiers)
        assertEquals(
            listOf(ResolverProviderId.MULLVAD),
            decoded?.configuration?.resolverTarget?.configuration?.toSelection()?.tiers?.map { it.entry.providerId },
        )
    }

    private fun record(configuration: FilterLibraryDraft): AuthoritativeFilterConfigurationRecord =
        AuthoritativeFilterConfigurationRecord(
            configuration = configuration,
            configurationIdentity = ConfigurationIdentity(41L, "a".repeat(64)),
            credentialUnlockRequired = true,
            artifactSha256 = "b".repeat(64),
            artifactIdentitySha256 = "c".repeat(64),
        )

    private fun validRecord(): AuthoritativeFilterConfigurationRecord = record(validDraft())

    private fun validDraft(): FilterLibraryDraft {
        val profiles = ProtectionLevel.entries.map { level ->
            val rules = when (level) {
                ProtectionLevel.CORE -> ManualDomainRules.EMPTY
                ProtectionLevel.BALANCED -> ManualDomainRules(
                    allowedDomains = linkedSetOf("safe.example"),
                    blockedDomains = linkedSetOf("blocked.example"),
                )
                ProtectionLevel.EXTRA -> ManualDomainRules(
                    blockedDomains = linkedSetOf("extra.example"),
                )
            }
            FilterProfileDraft(
                id = FilterProfile.stableId(level),
                name = FilterProfile.displayName(level),
                protectionLevel = level,
                enabledSourceIds = linkedSetOf("source-${level.name.lowercase()}", "shared-source"),
                manualRules = rules,
            )
        }
        return FilterLibraryDraft(
            profiles = profiles,
            activeProfileId = FilterProfile.stableId(ProtectionLevel.BALANCED),
            resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
        )
    }

    private fun validRawRecord(): RawRecord {
        val draft = validDraft()
        return RawRecord(
            generation = 41L,
            configurationFingerprint = bytes("a".repeat(64)),
            credentialUnlockRequired = 1,
            artifactSha256 = bytes("b".repeat(64)),
            artifactIdentitySha256 = bytes("c".repeat(64)),
            activeProfileId = bytes(draft.activeProfileId),
            resolverPlan = bytes(draft.resolverPlan.name),
            profiles = draft.profiles.map { profile ->
                RawProfile(
                    id = bytes(profile.id),
                    name = bytes(profile.name),
                    protectionLevel = bytes(profile.protectionLevel?.name ?: ""),
                    emoji = bytes(profile.emoji),
                    sourceIds = profile.enabledSourceIds.map(::bytes),
                    allowedDomains = profile.manualRules.allowedDomains.map(::bytes),
                    blockedDomains = profile.manualRules.blockedDomains.map(::bytes),
                )
            },
        )
    }

    private fun envelope(raw: RawRecord): ByteArray = envelope(payload(raw), raw.schema)

    private fun envelope(payload: ByteArray, schema: Int = SCHEMA): ByteArray {
        val body = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(schema)
                output.writeInt(payload.size)
                output.write(payload)
            }
            bytes.toByteArray()
        }
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun payload(raw: RawRecord): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeLong(raw.generation)
            output.writeRawString(raw.configurationFingerprint)
            output.writeByte(raw.credentialUnlockRequired)
            output.writeRawString(raw.artifactSha256)
            output.writeRawString(raw.artifactIdentitySha256)
            output.writeRawString(raw.activeProfileId)
            output.writeRawString(raw.resolverPlan)
            output.writeInt(raw.profileCountOverride ?: raw.profiles.size)
            raw.profiles.forEach { profile ->
                output.writeRawString(profile.id)
                output.writeRawString(profile.name)
                output.writeRawString(profile.protectionLevel)
                if (raw.schema >= SCHEMA_EMOJI) {
                    output.writeRawString(profile.emoji ?: bytes(""))
                }
                output.writeRawStrings(profile.sourceIds)
                output.writeRawStrings(profile.allowedDomains)
                output.writeRawStrings(profile.blockedDomains)
                if (raw.schema >= SCHEMA_CUSTOM) {
                    output.writeInt(profile.customBlocklists.size)
                    profile.customBlocklists.forEach { source ->
                        output.writeRawString(bytes(source.id))
                        output.writeRawString(bytes(source.displayName))
                        output.writeRawString(bytes(source.sourceURL))
                        output.writeRawString(bytes(source.parseFormat.name))
                        output.writeLong(source.createdAtEpochMillis)
                        output.writeRawString(bytes(source.lastAcceptedHash ?: ""))
                    }
                }
            }
            // Schema three carries the custom resolver shape as four explicit trailing strings;
            // schema two carries only the endpoint shape (DoH URL, or an empty DoH marker then a
            // DoT endpoint), exactly as the pre-name production encoder wrote it.
            if (raw.schema >= SCHEMA_CUSTOM) {
                output.writeRawString(raw.customDohUrl ?: bytes(""))
                output.writeRawString(raw.customName ?: bytes(""))
                output.writeRawString(raw.customSecondaryEndpoint ?: bytes(""))
                output.writeRawString(raw.customDotEndpoint ?: bytes(""))
            } else if (raw.schema == SCHEMA_EMOJI && raw.customDohUrl != null) {
                output.writeRawString(raw.customDohUrl)
                if (raw.customDotEndpoint != null) output.writeRawString(raw.customDotEndpoint)
            } else if (raw.schema == SCHEMA_EMOJI && raw.customDotEndpoint != null) {
                output.writeRawString(bytes(""))
                output.writeRawString(raw.customDotEndpoint)
            }
            if (raw.schema >= SCHEMA_DOQ) output.writeRawString(raw.customDoqEndpoint ?: bytes(""))
            // Schema four appends the retained ladder; a count of zero is the absence marker.
            if (raw.schema >= SCHEMA_SAVED_TIERS) {
                output.writeInt(raw.savedTiers.size)
                raw.savedTiers.forEach { tier ->
                    output.writeRawString(tier.provider)
                    output.writeRawString(tier.transport)
                    output.writeRawString(tier.name)
                    output.writeRawString(tier.doh)
                    output.writeRawString(tier.dot)
                    output.writeRawString(tier.secondary)
                    output.writeByte(tier.enabled)
                    if (raw.schema >= SCHEMA_DOQ) output.writeRawString(tier.doq)
                    if (raw.schema >= SCHEMA_RETAINED_NAME) output.writeRawString(tier.retainedName)
                    if (raw.schema >= SCHEMA_PLAIN) output.writeRawString(tier.plain)
                }
            }
            if (raw.schema >= SCHEMA_QA_PROBES) {
                output.writeByte(if (raw.qaDomains == null) 0 else 1)
                raw.qaDomains?.forEach { output.writeRawString(it) }
            }
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeRawStrings(values: List<ByteArray>) {
        writeInt(values.size)
        values.forEach { writeRawString(it) }
    }

    private fun DataOutputStream.writeRawString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun rewriteChecksum(envelope: ByteArray) {
        val checksumOffset = envelope.size - SHA_256_BYTES
        val checksum = MessageDigest.getInstance("SHA-256").digest(envelope.copyOf(checksumOffset))
        checksum.copyInto(envelope, checksumOffset)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private data class RawRecord(
        val generation: Long,
        val configurationFingerprint: ByteArray,
        val credentialUnlockRequired: Int,
        val artifactSha256: ByteArray,
        val artifactIdentitySha256: ByteArray,
        val activeProfileId: ByteArray,
        val resolverPlan: ByteArray,
        val profiles: List<RawProfile>,
        val profileCountOverride: Int? = null,
        val schema: Int = SCHEMA,
        val customDohUrl: ByteArray? = null,
        val customName: ByteArray? = null,
        val customSecondaryEndpoint: ByteArray? = null,
        val customDotEndpoint: ByteArray? = null,
        val customDoqEndpoint: ByteArray? = null,
        val savedTiers: List<RawTier> = emptyList(),
        val qaDomains: List<ByteArray>? = null,
    )

    private data class RawTier(
        val provider: ByteArray,
        val transport: ByteArray,
        val name: ByteArray,
        val doh: ByteArray,
        val dot: ByteArray,
        val secondary: ByteArray,
        val enabled: Int,
        val doq: ByteArray = bytes(""),
        val retainedName: ByteArray = bytes(""),
        val plain: ByteArray = bytes(""),
    )

    private data class RawProfile(
        val id: ByteArray,
        val name: ByteArray,
        val protectionLevel: ByteArray,
        val emoji: ByteArray? = null,
        val sourceIds: List<ByteArray>,
        val allowedDomains: List<ByteArray>,
        val blockedDomains: List<ByteArray>,
        val customBlocklists: List<RawCustomBlocklist> = emptyList(),
    )

    private data class RawCustomBlocklist(
        val id: String,
        val displayName: String,
        val sourceURL: String,
        val parseFormat: BlocklistFormat,
        val createdAtEpochMillis: Long,
        val lastAcceptedHash: String?,
    )

    private companion object {
        val MAGIC = "LAVACFG1".encodeToByteArray()
        const val SCHEMA = 8
        const val SCHEMA_QA_PROBES = 8
        const val SCHEMA_PLAIN = 7
        const val SCHEMA_DOQ = 5
        const val SCHEMA_RETAINED_NAME = 6
        const val SCHEMA_CUSTOM = 3
        const val SCHEMA_SAVED_TIERS = 4
        const val SCHEMA_EMOJI = 2
        const val SHA_256_BYTES = 32

        fun bytes(value: String): ByteArray = value.encodeToByteArray()
    }
}
