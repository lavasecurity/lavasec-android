package com.lavasec.app.product

import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.share.FilterShareEligibility
import com.lavasec.app.foundation.share.FilterShareEligibilityPolicy
import com.lavasec.app.foundation.share.ShareableCustomBlocklistSource
import com.lavasec.app.foundation.share.ShareableFilterCodeError
import com.lavasec.app.foundation.share.ShareableFilterCodec
import com.lavasec.app.foundation.share.ShareableFilterConfiguration
import com.lavasec.app.foundation.share.ShareableFilterImportCapabilities
import com.lavasec.app.foundation.share.ShareableFilterImportConfirmation
import com.lavasec.app.foundation.share.ShareableFilterImportRefusal
import com.lavasec.app.foundation.share.ShareableFilterImportReview
import com.lavasec.app.foundation.share.ShareableFilterInputError
import com.lavasec.app.foundation.share.ShareableFilterLink
import com.lavasec.app.foundation.share.ShareableFilterLinkCreationResult
import com.lavasec.app.foundation.share.ShareableFilterLinkResult
import com.lavasec.app.foundation.share.importPlan
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.FilterSharePayload

/**
 * The read-only, platform-neutral facts one share/import decision needs, supplied live by the
 * current credential generation. Android has no custom-blocklist store yet, so a production source
 * returns an empty list; the port keeps the canonical shape so a future store can supply them
 * without changing the owner.
 */
interface FilterShareSource {
    /** The saved filter profile, or null when the id is unknown or the graph is unavailable. */
    fun savedProfile(filterId: String): FilterProfileDraft?

    /** Custom blocklist definitions to enumerate in a share payload (empty on Android today). */
    fun customBlocklists(): List<ShareableCustomBlocklistSource> = emptyList()

    /** Curated blocklist ids present in this build's signed catalog. */
    fun availableCatalogSourceIds(): Set<String>

    /** Ids an imported custom source may not claim because the catalog/guardrail already owns it. */
    fun reservedBlocklistIds(): Set<String> = emptySet()

    /** The recipient's plan limits, used to reconcile an imported payload. */
    fun featureLimits(): FeatureLimits
}

/** Why a share could not produce shareable content. */
enum class FilterShareFailureReason {
    /** No saved filter has that id (or the credential graph is unavailable). */
    UNKNOWN_FILTER,

    /** The filter blocks nothing and has no exceptions — there is nothing to share. */
    EMPTY,

    /** A custom source carries a query, fragment, or userinfo that must not leave the device. */
    CONTAINS_PRIVATE_SOURCE_PARAMETERS,

    /** The setup exceeds the copyable-code capacity. */
    EXCEEDS_SHARE_CAPACITY,

    /** The canonical link wrapping the code was refused. */
    CODE_UNAVAILABLE,

    /** The complete payload cannot be rendered by the available native QR/card surface. */
    QR_RENDERER_UNAVAILABLE,

    /** No document/photo import adapter is wired. */
    DOCUMENT_PICKER_UNAVAILABLE,

    /** No camera scanner import adapter is wired. */
    CAMERA_SCANNER_UNAVAILABLE,
}

/** The canonical content one filter shares: the bare code and the canonical Universal Link. */
sealed interface FilterShareContent {
    data class Ready(
        val filterId: String,
        val code: String,
        val url: String,
        val configuration: ShareableFilterConfiguration,
    ) : FilterShareContent

    data class Unavailable(val reason: FilterShareFailureReason) : FilterShareContent
}

/** Why a raw share input could not be turned into a review. */
enum class FilterShareImportFailureReason {
    NOT_A_CODE,
    INVALID_LINK,
    UNSUPPORTED_VERSION,
    INTEGRITY_CHECK_FAILED,
    PAYLOAD_TOO_LARGE,
    MALFORMED_PAYLOAD,

    /** The reviewed target filter is no longer available. */
    UNKNOWN_FILTER,
}

/** A raw payload a platform document/camera adapter produced; never applied by the adapter. */
data class FilterShareImportCandidate(val rawInput: String)

/** Result of asking a platform adapter for a candidate payload. */
sealed interface FilterShareCandidateResult {
    data class Candidate(val input: FilterShareImportCandidate) : FilterShareCandidateResult

    data class Unavailable(val reason: FilterShareFailureReason) : FilterShareCandidateResult
}

/**
 * The upper bound on one raw acquired candidate. A legitimate candidate is either a bare `LF1-…`
 * code or the exact canonical Universal Link wrapping one, so the largest thing a picker/scanner
 * can hand back is the URL prefix plus the codec's encoded-code budget. Enforced before any decode
 * work, so an unbounded paste/scan cannot be walked by the review pipeline.
 */
object FilterShareAcquisitionBounds {
    val MAX_RAW_INPUT_CHARACTERS: Int =
        ShareableFilterLink.CANONICAL_URL_PREFIX.length +
            ShareableFilterCodec.CODE_PREFIX.length +
            ShareableFilterCodec.MAX_ENCODED_CODE_LENGTH
}

/**
 * Why a device acquisition produced no payload. Denial and cancellation are user/OS facts the
 * caller must tell apart from "this device has no adapter"; folding them into the share-failure
 * enum would make a dismissed picker look like a missing feature.
 */
enum class FilterShareAcquisitionRefusal {
    /** Camera/document permission was denied for this attempt. */
    PERMISSION_DENIED,

    /** The user dismissed the picker/scanner before choosing anything. */
    CANCELLED,

    /** No device adapter is registered (the production default until a real one lands). */
    ADAPTER_UNAVAILABLE,
}

/**
 * The bounded result of asking one thin device adapter for a raw payload. The adapter only ever
 * EXTRACTS — it never decodes, reviews, or mutates the library; every candidate funnels through
 * [FilterShareOwner.reviewImport] and then an explicit confirmation.
 */
sealed interface FilterShareAcquisitionResult {
    /** The adapter produced a payload within [FilterShareAcquisitionBounds]. */
    data class Candidate(val input: FilterShareImportCandidate) : FilterShareAcquisitionResult

    /** A payload was extracted but is outside the accepted shape/size before any review. */
    data class Rejected(val reason: FilterShareImportFailureReason) : FilterShareAcquisitionResult

    /** No payload was extracted: permission denied, cancelled, or no adapter registered. */
    data class Refused(val refusal: FilterShareAcquisitionRefusal) : FilterShareAcquisitionResult
}

/**
 * One thin, device-gated candidate source: the result of a document picker or a camera scan. The
 * asynchronous `ActivityResultContracts`/`Intent` launcher lives in the device adapter that owns
 * that result and simply injects it here; a source is synchronous because it only reports an
 * already-acquired (or deliberately withheld) result. A source must never fabricate a candidate.
 */
fun interface FilterShareCandidateSource {
    fun acquire(): FilterShareAcquisitionResult
}

/**
 * Platform document/camera adapter boundary. Implementations EXTRACT a raw payload only — they
 * never decode, review, or mutate the saved library; every candidate funnels through
 * [FilterShareOwner.reviewImport] and then an explicit confirmation.
 *
 * The legacy [documentCandidate]/[cameraCandidate] methods keep the canonical bridge compiling and
 * only distinguish "a candidate or not"; the rich [documentAcquisition]/[cameraAcquisition]
 * methods are the honest ones (denied and cancelled distinct) the bridge should adopt next.
 */
interface FilterShareInputPort {
    fun documentAcquisition(): FilterShareAcquisitionResult

    fun cameraAcquisition(): FilterShareAcquisitionResult

    fun documentCandidate(): FilterShareCandidateResult =
        documentAcquisition().asCandidateResult(FilterShareFailureReason.DOCUMENT_PICKER_UNAVAILABLE)

    fun cameraCandidate(): FilterShareCandidateResult =
        cameraAcquisition().asCandidateResult(FilterShareFailureReason.CAMERA_SCANNER_UNAVAILABLE)
}

/** The Android default: neither a photo picker nor a camera scanner is wired (no import UI). */
object UnavailableFilterShareInputPort : FilterShareInputPort {
    override fun documentAcquisition(): FilterShareAcquisitionResult =
        FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE)

    override fun cameraAcquisition(): FilterShareAcquisitionResult =
        FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE)
}

/**
 * Enforce [FilterShareAcquisitionBounds] on one raw acquisition result: a candidate longer than
 * [maxRawCharacters] is rejected before any decode, while a refusal/rejection passes through. This
 * is the single bound shared by the source-composed [InjectedFilterShareInputPort] and the device
 * picker/scanner adapter, so the two paths cannot drift.
 */
internal fun boundFilterShareAcquisition(
    result: FilterShareAcquisitionResult,
    maxRawCharacters: Int = FilterShareAcquisitionBounds.MAX_RAW_INPUT_CHARACTERS,
): FilterShareAcquisitionResult {
    if (result !is FilterShareAcquisitionResult.Candidate) return result
    return if (result.input.rawInput.length > maxRawCharacters) {
        FilterShareAcquisitionResult.Rejected(FilterShareImportFailureReason.PAYLOAD_TOO_LARGE)
    } else {
        result
    }
}

/**
 * The real injectable input boundary: two thin candidate sources (document picker result and
 * camera scan result) funnel through one place that enforces [FilterShareAcquisitionBounds].
 * Production keeps [UnavailableFilterShareInputPort] until a device adapter registers sources;
 * this class fabricates nothing and never mutates the library.
 */
class InjectedFilterShareInputPort(
    private val documentSource: FilterShareCandidateSource,
    private val cameraSource: FilterShareCandidateSource,
    private val maxRawCharacters: Int = FilterShareAcquisitionBounds.MAX_RAW_INPUT_CHARACTERS,
) : FilterShareInputPort {
    override fun documentAcquisition(): FilterShareAcquisitionResult =
        boundFilterShareAcquisition(documentSource.acquire(), maxRawCharacters)

    override fun cameraAcquisition(): FilterShareAcquisitionResult =
        boundFilterShareAcquisition(cameraSource.acquire(), maxRawCharacters)
}

/**
 * The device-launching capability the production picker/scanner adapter adds on top of the
 * synchronous [FilterShareInputPort]. The synchronous methods only ever report an already-acquired
 * (or deliberately withheld) result; a real `ActivityResultContracts` launch is asynchronous, so a
 * device adapter also exposes these `request*` methods that drive the launcher and suspend until the
 * user/OS returns. The owner prefers this port for the async requests and keeps the synchronous
 * methods for the bridge's legacy shape.
 *
 * [dispose] releases the registered launchers; a device host that holds an `ActivityResultRegistry`
 * must be torn down with the owning activity.
 */
interface FilterShareDeviceInputPort : FilterShareInputPort {
    /** Launch the document picker and suspend until it returns a bounded candidate or a refusal. */
    suspend fun requestDocumentAcquisition(): FilterShareAcquisitionResult

    /** Launch the camera scanner and suspend until it returns a bounded candidate or a refusal. */
    suspend fun requestCameraAcquisition(): FilterShareAcquisitionResult

    /** Release any registered launchers. The default is a no-op for a non-device port. */
    fun dispose() = Unit
}

private fun FilterShareAcquisitionResult.asCandidateResult(
    unavailable: FilterShareFailureReason,
): FilterShareCandidateResult = when (this) {
    is FilterShareAcquisitionResult.Candidate -> FilterShareCandidateResult.Candidate(input)
    is FilterShareAcquisitionResult.Rejected,
    is FilterShareAcquisitionResult.Refused,
    -> FilterShareCandidateResult.Unavailable(unavailable)
}

/** The decoded payload plus the catalog-membership fact the strict preflight produced. */
sealed interface FilterShareImportReviewResult {
    data class Reviewed(
        val review: ShareableFilterImportReview,
        /** Source ids the imported payload references that the signed catalog does not know. */
        val unknownSourceIds: Set<String>,
    ) : FilterShareImportReviewResult

    data class Unavailable(val reason: FilterShareImportFailureReason) : FilterShareImportReviewResult
}

/**
 * The outcome of acquiring and reviewing one device candidate. An acquisition refusal (denied,
 * cancelled, no adapter) is separated from a payload rejection so a dismissed picker is never
 * reported as a malformed payload, and neither is ever a success.
 */
sealed interface FilterShareAcquisitionReviewResult {
    /** A payload was acquired and funnelled through the ordinary reviewed-import preflight. */
    data class Acquired(val review: FilterShareImportReviewResult) : FilterShareAcquisitionReviewResult

    /** No payload was acquired: permission denied, cancelled, or no adapter registered. */
    data class Refused(val refusal: FilterShareAcquisitionRefusal) : FilterShareAcquisitionReviewResult
}

/**
 * The app-layer share/import owner, delegating every canonical rule to the pure
 * `com.lavasec.app.foundation.share` model ported from iOS (pinned `c4658d9`).
 *
 * It exposes the four `share.*` content computations and the reviewed import boundary. It performs
 * no platform I/O (clipboard, Intent, picker, camera) — the RN bridge owns those and only asks for
 * content or a review. An import is a *review plus an explicit confirmation*: confirmation returns
 * the content to write and the owner never mutates the saved library itself.
 */
class FilterShareOwner(
    private val source: FilterShareSource,
    private val inputPort: FilterShareInputPort = UnavailableFilterShareInputPort,
) {
    /** `share.query` / `share.copy` / `share.present` all need exactly this content. */
    fun content(filterId: String): FilterShareContent {
        val profile = source.savedProfile(filterId)
            ?: return FilterShareContent.Unavailable(FilterShareFailureReason.UNKNOWN_FILTER)
        // Export only this immutable profile's definitions, including its disabled sources.
        // The global source catalog is import context; it must never leak neighbouring filters.
        val configuration = ShareableFilterConfiguration.fromProfile(profile = profile)
        when (FilterShareEligibilityPolicy.evaluate(configuration)) {
            FilterShareEligibility.Empty ->
                return FilterShareContent.Unavailable(FilterShareFailureReason.EMPTY)
            FilterShareEligibility.ContainsPrivateSourceParameters ->
                return FilterShareContent.Unavailable(
                    FilterShareFailureReason.CONTAINS_PRIVATE_SOURCE_PARAMETERS,
                )
            FilterShareEligibility.ExceedsShareCapacity ->
                return FilterShareContent.Unavailable(FilterShareFailureReason.EXCEEDS_SHARE_CAPACITY)
            FilterShareEligibility.Shareable -> Unit
        }
        val code = ShareableFilterCodec.encode(configuration)
        return when (val link = ShareableFilterLink.urlForCode(code)) {
            is ShareableFilterLinkCreationResult.Created ->
                FilterShareContent.Ready(filterId, code, link.url, configuration)
            is ShareableFilterLinkCreationResult.Rejected ->
                FilterShareContent.Unavailable(FilterShareFailureReason.CODE_UNAVAILABLE)
        }
    }

    /** Eligibility is native; image readiness/capture belongs to the actual shared-RN adapter. */
    fun card(filterId: String): FilterShareContent = content(filterId)

    fun documentCandidate(): FilterShareCandidateResult = inputPort.documentCandidate()

    fun cameraCandidate(): FilterShareCandidateResult = inputPort.cameraCandidate()

    /** The honest acquisition outcome for a document picker attempt (denied/cancelled distinct). */
    fun documentAcquisition(): FilterShareAcquisitionResult = inputPort.documentAcquisition()

    /** The honest acquisition outcome for a camera scan attempt (denied/cancelled distinct). */
    fun cameraAcquisition(): FilterShareAcquisitionResult = inputPort.cameraAcquisition()

    /**
     * The async device request path: when a [FilterShareDeviceInputPort] is injected it drives the
     * real picker/scanner and awaits it; otherwise the synchronous port's already-acquired result
     * (the production default's typed refusal) is used unchanged.
     */
    suspend fun requestDocument(targetFilterId: String? = null): FilterShareAcquisitionReviewResult =
        reviewAcquisition(
            devicePort?.requestDocumentAcquisition() ?: inputPort.documentAcquisition(),
            targetFilterId,
        )

    /** The async camera-scan request path; identical fallback semantics to [requestDocument]. */
    suspend fun requestCamera(targetFilterId: String? = null): FilterShareAcquisitionReviewResult =
        reviewAcquisition(
            devicePort?.requestCameraAcquisition() ?: inputPort.cameraAcquisition(),
            targetFilterId,
        )

    /** The device-launching port, only present when the injected [inputPort] is a device adapter. */
    private val devicePort: FilterShareDeviceInputPort?
        get() = inputPort as? FilterShareDeviceInputPort

    /** Acquire a document candidate and review it in one bounded step; nothing is applied. */
    fun reviewDocument(targetFilterId: String? = null): FilterShareAcquisitionReviewResult =
        reviewAcquisition(inputPort.documentAcquisition(), targetFilterId)

    /** Acquire a camera candidate and review it in one bounded step; nothing is applied. */
    fun reviewCamera(targetFilterId: String? = null): FilterShareAcquisitionReviewResult =
        reviewAcquisition(inputPort.cameraAcquisition(), targetFilterId)

    /**
     * Funnel one already-acquired device result through the reviewed-import preflight. An
     * acquisition refusal (denied/cancelled/no adapter) and a payload rejection stay distinct, and
     * neither produces writeable content — only a full review can be confirmed later.
     */
    fun reviewAcquisition(
        acquisition: FilterShareAcquisitionResult,
        targetFilterId: String? = null,
    ): FilterShareAcquisitionReviewResult = when (acquisition) {
        is FilterShareAcquisitionResult.Candidate -> FilterShareAcquisitionReviewResult.Acquired(
            reviewImport(acquisition.input.rawInput, targetFilterId),
        )
        is FilterShareAcquisitionResult.Rejected -> FilterShareAcquisitionReviewResult.Acquired(
            FilterShareImportReviewResult.Unavailable(acquisition.reason),
        )
        is FilterShareAcquisitionResult.Refused ->
            FilterShareAcquisitionReviewResult.Refused(acquisition.refusal)
    }

    /**
     * Decodes one raw payload and builds the reviewed import preflight against [targetFilterId]
     * (null = add-as-new against an empty target). Decoding is pure; nothing is applied.
     *
     * The catalog-membership gate composes [FilterImportPreflight] over the decoded source ids:
     * that helper answers only "does every source resolve in the signed catalog", so its
     * domain-bearing payload field is intentionally empty here and the real content flows through
     * the review. Unknown ids are reported, not silently dropped.
     */
    fun reviewImport(
        rawInput: String,
        targetFilterId: String? = null,
    ): FilterShareImportReviewResult {
        val shared = when (val decoded = ShareableFilterLink.decode(rawInput)) {
            is ShareableFilterLinkResult.Decoded -> decoded.configuration
            is ShareableFilterLinkResult.Rejected ->
                return FilterShareImportReviewResult.Unavailable(inputFailureReason(decoded.error))
        }
        val target = if (targetFilterId == null) {
            ShareableFilterConfiguration()
        } else {
            val profile = source.savedProfile(targetFilterId)
                ?: return FilterShareImportReviewResult.Unavailable(
                    FilterShareImportFailureReason.UNKNOWN_FILTER,
                )
            ShareableFilterConfiguration.fromProfile(profile, source.customBlocklists())
        }
        val capabilities = currentCapabilities()
        val review = ShareableFilterImportReview.review(
            shared = shared,
            target = target,
            capabilities = capabilities,
        )
        val preview = FilterImportPreflight.preview(
            payload = FilterSharePayload(
                sourceIds = shared.enabledBlocklistIDs.toList(),
                blockedDomains = emptyList(),
            ),
            catalogSourceIds = source.availableCatalogSourceIds(),
            customSourceIds = shared.customBlocklists.mapTo(linkedSetOf()) { it.id },
        )
        val unknown = (preview as? FilterImportPreview.Rejected)?.unknownSourceIds.orEmpty()
        return FilterShareImportReviewResult.Reviewed(review, unknown)
    }

    /** Add-as-new confirmation: returns the content to write; the owner mutates nothing. */
    fun confirmAsNewFilter(review: ShareableFilterImportReview): ShareableFilterImportConfirmation =
        if (reviewStillMatchesCurrentFacts(review)) {
            review.confirmAddAsNewFilter()
        } else {
            ShareableFilterImportConfirmation.Refused(ShareableFilterImportRefusal.StaleReview)
        }

    /**
     * Replace confirmation: re-reads the live target so a target that changed after review
     * refuses with [ShareableFilterImportRefusal.StaleReview] rather than overwriting it, and
     * revalidates the current tier/configuration so a plan the user did not see is never applied.
     */
    fun confirmReplace(
        review: ShareableFilterImportReview,
        targetFilterId: String,
        targetIsActive: Boolean,
        confirmedActiveReplacement: Boolean,
    ): ShareableFilterImportConfirmation {
        val profile = source.savedProfile(targetFilterId)
            ?: return ShareableFilterImportConfirmation.Refused(
                ShareableFilterImportRefusal.StaleReview,
            )
        val currentTarget = ShareableFilterConfiguration.fromProfile(
            profile = profile,
            customBlocklists = source.customBlocklists(),
        )
        if (!reviewStillMatchesCurrentFacts(review)) {
            return ShareableFilterImportConfirmation.Refused(
                ShareableFilterImportRefusal.StaleReview,
            )
        }
        return review.confirmReplace(
            currentTarget = currentTarget,
            targetIsActive = targetIsActive,
            confirmedActiveReplacement = confirmedActiveReplacement,
        )
    }

    /** The live import capabilities derived from the current tier and signed catalog. */
    private fun currentCapabilities(): ShareableFilterImportCapabilities =
        ShareableFilterImportCapabilities.from(
            limits = source.featureLimits(),
            availableCuratedBlocklistIDs = source.availableCatalogSourceIds(),
            reservedBlocklistIDs = source.reservedBlocklistIds(),
        )

    /**
     * The reviewed plan must still be the plan the current tier/configuration would produce.
     * If the tier, plan limits, or signed catalog changed between acquisition and confirmation,
     * the plan is stale: the owner refuses rather than applying a plan (or a partial subset) the
     * user never reviewed.
     */
    private fun reviewStillMatchesCurrentFacts(review: ShareableFilterImportReview): Boolean =
        review.shared.importPlan(currentCapabilities()).applied == review.plan.applied

    private fun inputFailureReason(error: ShareableFilterInputError): FilterShareImportFailureReason =
        when (error) {
            ShareableFilterInputError.UnrecognizedFormat -> FilterShareImportFailureReason.NOT_A_CODE
            ShareableFilterInputError.InvalidUniversalLink ->
                FilterShareImportFailureReason.INVALID_LINK
            is ShareableFilterInputError.ConfigurationCode -> when (error.error) {
                is ShareableFilterCodeError.UnsupportedVersion ->
                    FilterShareImportFailureReason.UNSUPPORTED_VERSION
                ShareableFilterCodeError.IntegrityCheckFailed ->
                    FilterShareImportFailureReason.INTEGRITY_CHECK_FAILED
                ShareableFilterCodeError.PayloadTooLarge ->
                    FilterShareImportFailureReason.PAYLOAD_TOO_LARGE
                ShareableFilterCodeError.UnrecognizedFormat ->
                    FilterShareImportFailureReason.NOT_A_CODE
                else -> FilterShareImportFailureReason.MALFORMED_PAYLOAD
            }
        }
}
