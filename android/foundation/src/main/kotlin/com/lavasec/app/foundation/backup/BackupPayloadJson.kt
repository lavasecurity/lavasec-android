package com.lavasec.app.foundation.backup

/**
 * Deterministic JSON (de)serialization for [BackupConfigurationPayload].
 *
 * The output mirrors the canonical iOS payload encoder: camelCase keys, object keys in
 * lexicographic order, optional fields omitted when `null`, set-backed fields emitted as sorted
 * arrays, and dates as seconds since the Apple 2001-01-01 reference date. Unknown fields are
 * ignored on decode (forward compatibility), while absent optional fields take the same defaults
 * as the iOS decoder.
 *
 * Malformed, oversized, wrongly-typed or future-schema input always throws; it never silently
 * produces a defaulted settings object.
 */
object BackupPayloadJson {
    private const val SCHEMA_VERSION = "schemaVersion"
    private const val ENABLED_BLOCKLIST_IDS = "enabledBlocklistIDs"
    private const val ALLOWED_DOMAINS = "allowedDomains"
    private const val BLOCKED_DOMAINS = "blockedDomains"
    private const val RESOLVER_PRESET_ID = "resolverPresetID"
    private const val CUSTOM_RESOLVER_ADDRESS = "customResolverAddress"
    private const val CUSTOM_RESOLVER_SECONDARY_ADDRESS = "customResolverSecondaryAddress"
    private const val CUSTOM_RESOLVER_NAME = "customResolverName"
    private const val FALLBACK_TO_DEVICE_DNS = "fallbackToDeviceDNS"
    private const val USES_EXPLICIT_DNS_TIERS = "usesExplicitDNSTiers"
    private const val SAVED_DNS_RESOLUTION_SELECTIONS = "savedDNSResolutionSelections"
    private const val USES_ENCRYPTED_DEVICE_DNS_FALLBACK = "usesEncryptedDeviceDNSFallback"
    private const val FALLBACK_RESOLVER_PRESET_ID = "fallbackResolverPresetID"
    private const val FALLBACK_CUSTOM_RESOLVER_ADDRESS = "fallbackCustomResolverAddress"
    private const val FALLBACK_CUSTOM_RESOLVER_SECONDARY_ADDRESS =
        "fallbackCustomResolverSecondaryAddress"
    private const val FALLBACK_CUSTOM_RESOLVER_NAME = "fallbackCustomResolverName"
    private const val KEEP_FILTERING_COUNTS = "keepFilteringCounts"
    private const val KEEP_DOMAIN_DIAGNOSTICS = "keepDomainDiagnostics"
    private const val KEEP_NETWORK_ACTIVITY = "keepNetworkActivity"
    private const val KEEP_LAVA_GUARD_PROGRESS = "keepLavaGuardProgress"
    private const val LAVA_GUARD_UNLOCKS = "lavaGuardUnlocks"
    private const val PROTECTION_ENABLED_HINT = "protectionEnabledHint"
    private const val CATALOG_VERSION_HINT = "catalogVersionHint"
    private const val CUSTOM_BLOCKLISTS = "customBlocklists"
    private const val FILTER_LIBRARY = "filterLibrary"

    private const val LIBRARY_SCHEMA_VERSION = "schemaVersion"
    private const val LIBRARY_FILTERS = "filters"
    private const val LIBRARY_ACTIVE_FILTER_ID = "activeFilterID"
    private const val LIBRARY_CONFIGURATION_GENERATION = "configurationGeneration"

    private const val FILTER_ID = "id"
    private const val FILTER_NAME = "name"
    private const val FILTER_EMOJI = "emoji"
    private const val FILTER_CREATED_AT = "createdAt"
    private const val FILTER_LAST_COMPILED_TOKEN = "lastCompiledToken"
    private const val FILTER_LAST_SYNCED_AT = "lastSyncedAt"

    private const val RECORDS = "records"
    private const val UNLOCK_GUARD_ID = "guardID"
    private const val UNLOCK_UNLOCKED_AT = "unlockedAt"

    private const val SOURCE_ID = "id"
    private const val SOURCE_DISPLAY_NAME = "displayName"
    private const val SOURCE_URL = "sourceURL"
    private const val SOURCE_PARSE_FORMAT = "parseFormat"
    private const val SOURCE_CREATED_AT = "createdAt"
    private const val SOURCE_LAST_ACCEPTED_HASH = "lastAcceptedHash"

    fun encode(payload: BackupConfigurationPayload): String =
        BackupJsonCodec.print(encodeValue(payload))

    fun decode(text: String): BackupConfigurationPayload = decodeValue(BackupJsonCodec.parse(text))

    internal fun decodeValue(root: BackupJsonValue): BackupConfigurationPayload {
        val json = root.requireObject("payload")
        val decodedSchema = json.optionalInt(SCHEMA_VERSION) ?: 1
        // Reject a future schema BEFORE reading any other field (mirrors the iOS decoder).
        if (decodedSchema > BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION) {
            throw BackupSchemaUnsupportedException(decodedSchema)
        }
        val usesExplicitDNSTiers = json.optionalBoolean(USES_EXPLICIT_DNS_TIERS) ?: false
        val savedDNSResolutionSelections = json.optionalValue(SAVED_DNS_RESOLUTION_SELECTIONS)
            ?.let(::decodeDNSSelections)
        return BackupConfigurationPayload(
            schemaVersion = maxOf(decodedSchema, requiredSchema(usesExplicitDNSTiers, savedDNSResolutionSelections)),
            enabledBlocklistIDs = json.requiredStringSet(ENABLED_BLOCKLIST_IDS),
            allowedDomains = json.requiredStringSet(ALLOWED_DOMAINS),
            blockedDomains = json.requiredStringSet(BLOCKED_DOMAINS),
            resolverPresetID = json.requiredString(RESOLVER_PRESET_ID),
            customResolverAddress = json.optionalString(CUSTOM_RESOLVER_ADDRESS),
            customResolverSecondaryAddress = json.optionalString(CUSTOM_RESOLVER_SECONDARY_ADDRESS),
            customResolverName = json.optionalString(CUSTOM_RESOLVER_NAME),
            fallbackToDeviceDNS = json.optionalBoolean(FALLBACK_TO_DEVICE_DNS) ?: false,
            usesExplicitDNSTiers = usesExplicitDNSTiers,
            usesEncryptedDeviceDNSFallback =
                json.optionalBoolean(USES_ENCRYPTED_DEVICE_DNS_FALLBACK) ?: false,
            fallbackResolverPresetID = json.optionalString(FALLBACK_RESOLVER_PRESET_ID)
                ?: BackupConfigurationPayload.DEFAULT_FALLBACK_RESOLVER_PRESET_ID,
            fallbackCustomResolverAddress = json.optionalString(FALLBACK_CUSTOM_RESOLVER_ADDRESS),
            fallbackCustomResolverSecondaryAddress =
                json.optionalString(FALLBACK_CUSTOM_RESOLVER_SECONDARY_ADDRESS),
            fallbackCustomResolverName = json.optionalString(FALLBACK_CUSTOM_RESOLVER_NAME),
            keepFilteringCounts = json.optionalBoolean(KEEP_FILTERING_COUNTS) ?: true,
            keepDomainDiagnostics = json.requiredBoolean(KEEP_DOMAIN_DIAGNOSTICS),
            keepNetworkActivity = json.optionalBoolean(KEEP_NETWORK_ACTIVITY) ?: true,
            keepLavaGuardProgress = json.optionalBoolean(KEEP_LAVA_GUARD_PROGRESS) ?: true,
            lavaGuardUnlocks = json.optionalValue(LAVA_GUARD_UNLOCKS)
                ?.let { decodeLedger(it) }
                ?: BackupLavaGuardAchievementLedger(),
            protectionEnabledHint = json.requiredBoolean(PROTECTION_ENABLED_HINT),
            catalogVersionHint = json.optionalString(CATALOG_VERSION_HINT),
            customBlocklists = json.optionalValue(CUSTOM_BLOCKLISTS)
                ?.let { decodeCustomBlocklists(it) }
                ?: emptyList(),
            filterLibrary = json.optionalValue(FILTER_LIBRARY)?.let { decodeLibrary(it) },
            savedDNSResolutionSelections = savedDNSResolutionSelections,
        )
    }

    private fun encodeValue(payload: BackupConfigurationPayload): BackupJsonValue {
        val members = LinkedHashMap<String, BackupJsonValue>()
        members[SCHEMA_VERSION] = number(maxOf(payload.schemaVersion,
            requiredSchema(payload.usesExplicitDNSTiers, payload.savedDNSResolutionSelections)))
        members[ENABLED_BLOCKLIST_IDS] = stringArray(payload.enabledBlocklistIDs)
        members[ALLOWED_DOMAINS] = stringArray(payload.allowedDomains)
        members[BLOCKED_DOMAINS] = stringArray(payload.blockedDomains)
        members[RESOLVER_PRESET_ID] = BackupJsonValue.JsonString(payload.resolverPresetID)
        payload.customResolverAddress?.let { members[CUSTOM_RESOLVER_ADDRESS] = BackupJsonValue.JsonString(it) }
        payload.customResolverSecondaryAddress?.let {
            members[CUSTOM_RESOLVER_SECONDARY_ADDRESS] = BackupJsonValue.JsonString(it)
        }
        payload.customResolverName?.let { members[CUSTOM_RESOLVER_NAME] = BackupJsonValue.JsonString(it) }
        members[FALLBACK_TO_DEVICE_DNS] = BackupJsonValue.JsonBoolean(payload.fallbackToDeviceDNS)
        members[USES_EXPLICIT_DNS_TIERS] = BackupJsonValue.JsonBoolean(payload.usesExplicitDNSTiers)
        payload.savedDNSResolutionSelections?.let { members[SAVED_DNS_RESOLUTION_SELECTIONS] = encodeDNSSelections(it) }
        members[USES_ENCRYPTED_DEVICE_DNS_FALLBACK] =
            BackupJsonValue.JsonBoolean(payload.usesEncryptedDeviceDNSFallback)
        members[FALLBACK_RESOLVER_PRESET_ID] = BackupJsonValue.JsonString(payload.fallbackResolverPresetID)
        payload.fallbackCustomResolverAddress?.let {
            members[FALLBACK_CUSTOM_RESOLVER_ADDRESS] = BackupJsonValue.JsonString(it)
        }
        payload.fallbackCustomResolverSecondaryAddress?.let {
            members[FALLBACK_CUSTOM_RESOLVER_SECONDARY_ADDRESS] = BackupJsonValue.JsonString(it)
        }
        payload.fallbackCustomResolverName?.let {
            members[FALLBACK_CUSTOM_RESOLVER_NAME] = BackupJsonValue.JsonString(it)
        }
        members[KEEP_FILTERING_COUNTS] = BackupJsonValue.JsonBoolean(payload.keepFilteringCounts)
        members[KEEP_DOMAIN_DIAGNOSTICS] = BackupJsonValue.JsonBoolean(payload.keepDomainDiagnostics)
        members[KEEP_NETWORK_ACTIVITY] = BackupJsonValue.JsonBoolean(payload.keepNetworkActivity)
        members[KEEP_LAVA_GUARD_PROGRESS] = BackupJsonValue.JsonBoolean(payload.keepLavaGuardProgress)
        members[LAVA_GUARD_UNLOCKS] = encodeLedger(payload.lavaGuardUnlocks)
        members[PROTECTION_ENABLED_HINT] = BackupJsonValue.JsonBoolean(payload.protectionEnabledHint)
        payload.catalogVersionHint?.let { members[CATALOG_VERSION_HINT] = BackupJsonValue.JsonString(it) }
        members[CUSTOM_BLOCKLISTS] = encodeCustomBlocklists(payload.customBlocklists)
        payload.filterLibrary?.let { members[FILTER_LIBRARY] = encodeLibrary(it) }
        return BackupJsonValue.JsonObject(members)
    }

    private fun requiredSchema(explicit: Boolean, rows: List<BackupDNSResolutionSelection>?): Int = when {
        rows?.any { !it.isEnabled } == true -> 3
        explicit -> 2
        else -> 1
    }

    private fun decodeDNSSelections(value: BackupJsonValue): List<BackupDNSResolutionSelection> =
        value.requireArray(SAVED_DNS_RESOLUTION_SELECTIONS).values.map { row ->
            val json = row.requireObject("DNSResolutionSelection")
            BackupDNSResolutionSelection(
                id = json.requiredString("id"), name = json.optionalString("name") ?: "",
                primary = json.optionalString("primary") ?: "", secondary = json.optionalString("secondary") ?: "",
                isEnabled = json.optionalBoolean("isEnabled") ?: true,
            )
        }

    private fun encodeDNSSelections(rows: List<BackupDNSResolutionSelection>): BackupJsonValue =
        BackupJsonValue.JsonArray(rows.map { row ->
            BackupJsonValue.JsonObject(linkedMapOf(
                "id" to BackupJsonValue.JsonString(row.id), "name" to BackupJsonValue.JsonString(row.name),
                "primary" to BackupJsonValue.JsonString(row.primary), "secondary" to BackupJsonValue.JsonString(row.secondary),
                "isEnabled" to BackupJsonValue.JsonBoolean(row.isEnabled),
            ))
        })

    private fun decodeLibrary(value: BackupJsonValue): BackupFilterLibrary {
        val json = value.requireObject(FILTER_LIBRARY)
        val filters = json.optionalValue(LIBRARY_FILTERS)?.let { decodeFilters(it) } ?: emptyList()
        val activeFilterID = json.optionalString(LIBRARY_ACTIVE_FILTER_ID)
            ?: filters.firstOrNull()?.id
            ?: "default"
        return BackupFilterLibrary(
            schemaVersion = json.optionalInt(LIBRARY_SCHEMA_VERSION)
                ?: BackupFilterLibrary.CURRENT_SCHEMA_VERSION,
            filters = filters,
            activeFilterID = activeFilterID,
            configurationGeneration = json.optionalInt(LIBRARY_CONFIGURATION_GENERATION) ?: 0,
        )
    }

    private fun encodeLibrary(library: BackupFilterLibrary): BackupJsonValue =
        BackupJsonValue.JsonObject(
            linkedMapOf(
                LIBRARY_SCHEMA_VERSION to number(library.schemaVersion),
                LIBRARY_FILTERS to BackupJsonValue.JsonArray(library.filters.map(::encodeFilter)),
                LIBRARY_ACTIVE_FILTER_ID to BackupJsonValue.JsonString(library.activeFilterID),
                LIBRARY_CONFIGURATION_GENERATION to number(library.configurationGeneration),
            ),
        )

    private fun decodeFilters(value: BackupJsonValue): List<BackupFilter> =
        value.requireArray(LIBRARY_FILTERS).values.map(::decodeFilter)

    private fun decodeFilter(value: BackupJsonValue): BackupFilter {
        val json = value.requireObject("filter")
        return BackupFilter(
            id = json.optionalString(FILTER_ID) ?: "default",
            name = json.optionalString(FILTER_NAME) ?: "",
            emoji = json.optionalString(FILTER_EMOJI) ?: "",
            enabledBlocklistIDs = json.optionalStringSet(ENABLED_BLOCKLIST_IDS) ?: emptySet(),
            customBlocklists = json.optionalValue(CUSTOM_BLOCKLISTS)
                ?.let { decodeCustomBlocklists(it) }
                ?: emptyList(),
            blockedDomains = json.optionalStringSet(BLOCKED_DOMAINS) ?: emptySet(),
            allowedDomains = json.optionalStringSet(ALLOWED_DOMAINS) ?: emptySet(),
            createdAt = json.optionalDouble(FILTER_CREATED_AT) ?: 0.0,
            lastCompiledToken = json.optionalString(FILTER_LAST_COMPILED_TOKEN),
            lastSyncedAt = json.optionalDouble(FILTER_LAST_SYNCED_AT),
        )
    }

    private fun encodeFilter(filter: BackupFilter): BackupJsonValue {
        val members = LinkedHashMap<String, BackupJsonValue>()
        members[FILTER_ID] = BackupJsonValue.JsonString(filter.id)
        members[FILTER_NAME] = BackupJsonValue.JsonString(filter.name)
        members[FILTER_EMOJI] = BackupJsonValue.JsonString(filter.emoji)
        members[ENABLED_BLOCKLIST_IDS] = stringArray(filter.enabledBlocklistIDs)
        members[CUSTOM_BLOCKLISTS] = encodeCustomBlocklists(filter.customBlocklists)
        members[BLOCKED_DOMAINS] = stringArray(filter.blockedDomains)
        members[ALLOWED_DOMAINS] = stringArray(filter.allowedDomains)
        members[FILTER_CREATED_AT] = double(filter.createdAt)
        filter.lastCompiledToken?.let {
            members[FILTER_LAST_COMPILED_TOKEN] = BackupJsonValue.JsonString(it)
        }
        filter.lastSyncedAt?.let { members[FILTER_LAST_SYNCED_AT] = double(it) }
        return BackupJsonValue.JsonObject(members)
    }

    private fun decodeLedger(value: BackupJsonValue): BackupLavaGuardAchievementLedger {
        val json = value.requireObject(LAVA_GUARD_UNLOCKS)
        val records = json.optionalValue(RECORDS)?.requireArray(RECORDS)?.values?.map { record ->
            val recordJson = record.requireObject("lavaGuardUnlockRecord")
            BackupLavaGuardUnlockRecord(
                guardID = recordJson.requiredString(UNLOCK_GUARD_ID),
                unlockedAt = recordJson.requiredDouble(UNLOCK_UNLOCKED_AT),
            )
        } ?: emptyList()
        return BackupLavaGuardAchievementLedger(records)
    }

    private fun encodeLedger(ledger: BackupLavaGuardAchievementLedger): BackupJsonValue =
        BackupJsonValue.JsonObject(
            linkedMapOf(
                RECORDS to BackupJsonValue.JsonArray(
                    ledger.records.map { record ->
                        BackupJsonValue.JsonObject(
                            linkedMapOf(
                                UNLOCK_GUARD_ID to BackupJsonValue.JsonString(record.guardID),
                                UNLOCK_UNLOCKED_AT to double(record.unlockedAt),
                            ),
                        )
                    },
                ),
            ),
        )

    private fun decodeCustomBlocklists(value: BackupJsonValue): List<BackupCustomBlocklistSource> =
        value.requireArray(CUSTOM_BLOCKLISTS).values.map { source ->
            val json = source.requireObject("customBlocklistSource")
            BackupCustomBlocklistSource(
                id = json.requiredString(SOURCE_ID),
                displayName = json.requiredString(SOURCE_DISPLAY_NAME),
                sourceURL = json.requiredString(SOURCE_URL),
                parseFormat = json.optionalString(SOURCE_PARSE_FORMAT) ?: "auto",
                createdAt = json.optionalDouble(SOURCE_CREATED_AT) ?: 0.0,
                lastAcceptedHash = json.optionalString(SOURCE_LAST_ACCEPTED_HASH),
            )
        }

    private fun encodeCustomBlocklists(
        sources: List<BackupCustomBlocklistSource>,
    ): BackupJsonValue = BackupJsonValue.JsonArray(
        sources.map { source ->
            val members = LinkedHashMap<String, BackupJsonValue>()
            members[SOURCE_ID] = BackupJsonValue.JsonString(source.id)
            members[SOURCE_DISPLAY_NAME] = BackupJsonValue.JsonString(source.displayName)
            members[SOURCE_URL] = BackupJsonValue.JsonString(source.sourceURL)
            members[SOURCE_PARSE_FORMAT] = BackupJsonValue.JsonString(source.parseFormat)
            members[SOURCE_CREATED_AT] = double(source.createdAt)
            source.lastAcceptedHash?.let {
                members[SOURCE_LAST_ACCEPTED_HASH] = BackupJsonValue.JsonString(it)
            }
            BackupJsonValue.JsonObject(members)
        },
    )

    private fun stringArray(values: Set<String>): BackupJsonValue =
        BackupJsonValue.JsonArray(values.sorted().map { BackupJsonValue.JsonString(it) })

    private fun number(value: Int): BackupJsonValue =
        BackupJsonValue.JsonNumber(value.toString())

    private fun double(value: Double): BackupJsonValue {
        if (!value.isFinite()) {
            throw BackupPayloadFormatException("backup JSON numbers must be finite")
        }
        val integral = value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble() &&
            value == value.toLong().toDouble()
        return BackupJsonValue.JsonNumber(if (integral) value.toLong().toString() else value.toString())
    }
}

private fun BackupJsonValue.requireObject(where: String): BackupJsonValue.JsonObject =
    asObjectOrNull() ?: throw BackupPayloadFormatException("$where must be a JSON object")

private fun BackupJsonValue.requireArray(where: String): BackupJsonValue.JsonArray =
    asArrayOrNull() ?: throw BackupPayloadFormatException("$where must be a JSON array")

private fun BackupJsonValue.JsonObject.member(key: String): BackupJsonValue? =
    members[key]?.takeUnless { it is BackupJsonValue.JsonNull }

private fun BackupJsonValue.JsonObject.optionalValue(key: String): BackupJsonValue? = member(key)

private fun BackupJsonValue.JsonObject.requiredString(key: String): String =
    optionalString(key) ?: throw BackupPayloadFormatException("required string '$key' is missing")

private fun BackupJsonValue.JsonObject.optionalString(key: String): String? {
    val value = member(key) ?: return null
    return value.asStringOrNull()
        ?: throw BackupPayloadFormatException("'$key' must be a string")
}

private fun BackupJsonValue.JsonObject.requiredBoolean(key: String): Boolean =
    optionalBoolean(key) ?: throw BackupPayloadFormatException("required boolean '$key' is missing")

private fun BackupJsonValue.JsonObject.optionalBoolean(key: String): Boolean? {
    val value = member(key) ?: return null
    return value.asBooleanOrNull()
        ?: throw BackupPayloadFormatException("'$key' must be a boolean")
}

private fun BackupJsonValue.JsonObject.optionalInt(key: String): Int? {
    val value = member(key) ?: return null
    return value.asIntOrNull()
        ?: throw BackupPayloadFormatException("'$key' must be an integer")
}

private fun BackupJsonValue.JsonObject.requiredDouble(key: String): Double =
    optionalDouble(key) ?: throw BackupPayloadFormatException("required number '$key' is missing")

private fun BackupJsonValue.JsonObject.optionalDouble(key: String): Double? {
    val value = member(key) ?: return null
    val parsed = value.asDoubleOrNull()
    if (parsed == null || !parsed.isFinite()) {
        throw BackupPayloadFormatException("'$key' must be a finite number")
    }
    return parsed
}

private fun BackupJsonValue.JsonObject.requiredStringSet(key: String): Set<String> =
    optionalStringSet(key) ?: throw BackupPayloadFormatException("required string array '$key' is missing")

private fun BackupJsonValue.JsonObject.optionalStringSet(key: String): Set<String>? {
    val value = member(key) ?: return null
    val array = value.asArrayOrNull()
        ?: throw BackupPayloadFormatException("'$key' must be an array")
    val result = LinkedHashSet<String>(array.values.size)
    for (element in array.values) {
        result.add(
            element.asStringOrNull()
                ?: throw BackupPayloadFormatException("'$key' elements must be strings"),
        )
    }
    return result
}
