package com.lavasec.app.foundation.flows

/**
 * Canonical `native.flow` inventory, ported from the pinned iOS `LavaAppBridge`
 * (`c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`, `perform("native.flow")`) and from the
 * presentation switch in `LavaAppFlows.flowContent`.
 *
 * Exactly [NativeFlow] wire names may cross the bridge as `native.flow`. The
 * [NativeFlowInternalPresentation] names are first-party-only routes (filter preparation and
 * editing forms, sharing, the VPN configuration editor, custom blocklists, deep-link import, the
 * QA sheet); they are deliberately absent from the allowlist so a JS caller cannot open them by
 * name. The two vocabularies are disjoint and the model is pure so `foundation` stays
 * platform-free and testable on the JVM.
 */

/**
 * The build configurations that gate flow availability, modeled explicitly rather than read from a
 * platform `BuildConfig`. Mirrors the iOS preprocessor pair: a release build is neither `DEBUG` nor
 * `LAVA_QA_TOOLS`; a QA build is `DEBUG` plus `LAVA_QA_TOOLS`.
 */
enum class NativeFlowBuildConfiguration(
    val debugBuild: Boolean,
    val qaToolsBuild: Boolean,
) {
    /** `#if !DEBUG && !LAVA_QA_TOOLS`: the shipping configuration. */
    RELEASE(debugBuild = false, qaToolsBuild = false),

    /** `#if DEBUG` without QA tooling. */
    DEBUG(debugBuild = true, qaToolsBuild = false),

    /** `#if DEBUG || LAVA_QA_TOOLS`: debug plus QA tooling. */
    QA(debugBuild = true, qaToolsBuild = true),
    ;

    /** Whether the iOS `#if DEBUG || LAVA_QA_TOOLS` presentation gate is open. */
    val qaToolsEnabled: Boolean get() = debugBuild || qaToolsBuild
}

/**
 * Per-build availability of a canonical flow. iOS only distinguishes release
 * (`!DEBUG && !LAVA_QA_TOOLS`) from everything else, but each configuration is recorded explicitly
 * so a future debug-only or QA-only gate cannot be widened or narrowed silently.
 */
data class NativeFlowAvailability(
    val release: Boolean,
    val debug: Boolean,
    val qa: Boolean,
) {
    fun isAvailableIn(configuration: NativeFlowBuildConfiguration): Boolean = when (configuration) {
        NativeFlowBuildConfiguration.RELEASE -> release
        NativeFlowBuildConfiguration.DEBUG -> debug
        NativeFlowBuildConfiguration.QA -> qa
    }

    companion object {
        /** Present in every build: the ordinary native settings and read-only routes. */
        val Always = NativeFlowAvailability(release = true, debug = true, qa = true)

        /**
         * Present only when the QA gate is open. `phoneQA` is the only canonical flow with this
         * availability; iOS' release guard rejects it (and the internal `vpnChaining` editor).
         */
        val QaOnly = NativeFlowAvailability(release = false, debug = true, qa = true)
    }
}

/**
 * The exact bridge allowlist from `LavaAppBridge.perform("native.flow")`. Order is documentation
 * only; [wireNames] is the pinned contract. `phoneQA` is allowlisted but QA-gated, so the allowlist
 * and the release availability are two independent facts.
 */
enum class NativeFlow(
    val wireName: String,
    val availability: NativeFlowAvailability,
) {
    ACCOUNT("account", NativeFlowAvailability.Always),
    BACKUP_SETUP("backupSetup", NativeFlowAvailability.Always),
    BACKUP_RESTORE("backupRestore", NativeFlowAvailability.Always),
    PASSCODE("passcode", NativeFlowAvailability.Always),
    AUTOMATION("automation", NativeFlowAvailability.Always),
    IMPORT("import", NativeFlowAvailability.Always),
    IMPORT_CODE("importCode", NativeFlowAvailability.Always),
    IMPORT_SCAN("importScan", NativeFlowAvailability.Always),
    LICENSES("licenses", NativeFlowAvailability.Always),
    FEEDBACK("feedback", NativeFlowAvailability.Always),
    CUSTOM_DNS("customDNS", NativeFlowAvailability.Always),
    PHONE_QA("phoneQA", NativeFlowAvailability.QaOnly),
    ;

    val isAvailableInRelease: Boolean get() = availability.release
    val isAvailableInDebug: Boolean get() = availability.debug
    val isAvailableInQa: Boolean get() = availability.qa

    companion object {
        private val byWireName: Map<String, NativeFlow> = entries.associateBy(NativeFlow::wireName)

        fun fromWireName(name: String): NativeFlow? = byWireName[name]

        val wireNames: Set<String> = entries.mapTo(linkedSetOf(), NativeFlow::wireName)
    }
}

/**
 * Native-only presentations in `LavaAppFlows.flowContent` that must never be constructed by a
 * `native.flow` command. The task's named set (filter preparation, the create/rename/delete filter
 * forms, sharing, VPN configuration, custom blocklist) is extended here with the two remaining
 * first-party-only cases, `deepLinkImport` and `phoneQASheet`, so the boundary is complete: any
 * presentation the bridge cannot safely open is classified rather than left as an accidental
 * unknown.
 */
enum class NativeFlowInternalPresentation(val wireName: String) {
    FILTER_PREPARATION("preparation"),
    DEEP_LINK_IMPORT("deepLinkImport"),
    PHONE_QA_SHEET("phoneQASheet"),
    CREATE_FILTER("createFilter"),
    RENAME_FILTER("renameFilter"),
    DELETE_FILTERS("deleteFilters"),
    SHARE("share"),
    VPN_CONFIGURATION("vpnConfiguration"),
    CUSTOM_BLOCKLIST("customBlocklist"),
    ;

    companion object {
        private val byWireName: Map<String, NativeFlowInternalPresentation> =
            entries.associateBy(NativeFlowInternalPresentation::wireName)

        fun fromWireName(name: String): NativeFlowInternalPresentation? = byWireName[name]

        val wireNames: Set<String> =
            entries.mapTo(linkedSetOf(), NativeFlowInternalPresentation::wireName)
    }
}

/** Why a recognized name cannot be presented through the bridge. */
enum class NativeFlowUnavailableReason {
    /** A recognized internal presentation; never bridge-callable in any build. */
    INTERNAL_PRESENTATION,

    /** A canonical flow whose QA/testing gate excludes the requested release build. */
    QA_FLOW_IN_RELEASE,

    /** A canonical flow this build does not expose for a non-release reason. */
    NOT_AVAILABLE_IN_BUILD,
}

/** Build-independent classification of one `native.flow` name. */
sealed interface NativeFlowClassification {
    /** A canonical allowlist entry, whatever its per-build availability. */
    data class BridgeCallable(val flow: NativeFlow) : NativeFlowClassification

    /** A first-party-only presentation that the bridge must refuse. */
    data class InternalPresentation(
        val presentation: NativeFlowInternalPresentation,
    ) : NativeFlowClassification
}

/** Result of validating one `native.flow` name against a concrete build configuration. */
sealed interface NativeFlowValidation {
    /** The flow is canonical and this build exposes it. */
    data class Available(val flow: NativeFlow) : NativeFlowValidation

    /** A recognized name this build must refuse, with a typed reason. */
    data class Unavailable(val reason: NativeFlowUnavailableReason) : NativeFlowValidation

    /** Not part of the canonical flow vocabulary nor a recognized presentation. */
    data object Unknown : NativeFlowValidation
}

object NativeFlowInventory {
    val canonicalFlows: List<NativeFlow> = NativeFlow.entries

    val internalPresentations: List<NativeFlowInternalPresentation> =
        NativeFlowInternalPresentation.entries

    /**
     * Classifies a name without considering the build: bridge-callable canonical flows versus
     * internal presentations versus `null` for an unknown name.
     */
    fun classify(name: String): NativeFlowClassification? {
        NativeFlow.fromWireName(name)?.let { return NativeFlowClassification.BridgeCallable(it) }
        NativeFlowInternalPresentation.fromWireName(name)?.let {
            return NativeFlowClassification.InternalPresentation(it)
        }
        return null
    }

    /**
     * Validates a `native.flow` name for [configuration]. An internal presentation is always
     * [NativeFlowUnavailableReason.INTERNAL_PRESENTATION]; a QA-only flow is
     * [NativeFlowUnavailableReason.QA_FLOW_IN_RELEASE] in release and available otherwise; an
     * unknown name is [NativeFlowValidation.Unknown].
     */
    fun validate(name: String, configuration: NativeFlowBuildConfiguration): NativeFlowValidation {
        NativeFlow.fromWireName(name)?.let { flow ->
            if (flow.availability.isAvailableIn(configuration)) {
                return NativeFlowValidation.Available(flow)
            }
            val reason = if (configuration == NativeFlowBuildConfiguration.RELEASE) {
                NativeFlowUnavailableReason.QA_FLOW_IN_RELEASE
            } else {
                // No canonical flow is gated outside release today; a future non-release gate
                // still fails closed with a typed refusal rather than silently allowing it.
                NativeFlowUnavailableReason.NOT_AVAILABLE_IN_BUILD
            }
            return NativeFlowValidation.Unavailable(reason)
        }
        if (NativeFlowInternalPresentation.fromWireName(name) != null) {
            return NativeFlowValidation.Unavailable(NativeFlowUnavailableReason.INTERNAL_PRESENTATION)
        }
        return NativeFlowValidation.Unknown
    }

    /** Canonical flow names the bridge admits for the given build. */
    fun availableWireNames(configuration: NativeFlowBuildConfiguration): Set<String> =
        canonicalFlows.filter { it.availability.isAvailableIn(configuration) }
            .mapTo(linkedSetOf(), NativeFlow::wireName)

    /** `true` when the name is a canonical bridge flow, independent of build availability. */
    fun isCanonicalFlowName(name: String): Boolean = NativeFlow.fromWireName(name) != null

    /** `true` when the name is a recognized first-party-only presentation. */
    fun isInternalPresentationName(name: String): Boolean =
        NativeFlowInternalPresentation.fromWireName(name) != null
}
