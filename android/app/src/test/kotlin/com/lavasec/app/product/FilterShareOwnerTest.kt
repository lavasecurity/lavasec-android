package com.lavasec.app.product

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.share.ShareableBlocklistParseFormat
import com.lavasec.app.foundation.share.ShareableCustomBlocklistSource
import com.lavasec.app.foundation.share.ShareableFilterCodec
import com.lavasec.app.foundation.share.ShareableFilterConfiguration
import com.lavasec.app.foundation.share.ShareableFilterImportConfirmation
import com.lavasec.app.foundation.share.ShareableFilterImportPlan
import com.lavasec.app.foundation.share.ShareableFilterImportRefusal
import com.lavasec.app.foundation.share.ShareableFilterLink
import com.lavasec.app.foundation.share.ShareableFilterLinkCreationResult
import com.lavasec.app.foundation.share.ShareableFilterLinkResult
import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM owner tests: the share/import owner is platform-free and its security-relevant
 * boundaries (eligibility, no-mutation review, explicit unavailable card/candidates) must be
 * provable on the aarch64 work host where Robolectric cannot run.
 */
class FilterShareOwnerTest {
    private class FakeSource(
        private val profiles: Map<String, FilterProfileDraft> = emptyMap(),
        var catalog: Set<String> = emptySet(),
        private val custom: List<ShareableCustomBlocklistSource> = emptyList(),
        var limits: FeatureLimits = FeatureLimits.FREE,
    ) : FilterShareSource {
        override fun savedProfile(filterId: String): FilterProfileDraft? = profiles[filterId]
        override fun customBlocklists(): List<ShareableCustomBlocklistSource> = custom
        override fun availableCatalogSourceIds(): Set<String> = catalog
        override fun featureLimits(): FeatureLimits = limits
    }

    private fun profile(
        id: String = "filter-balanced",
        sources: Set<String> = setOf("blocklistproject-basic"),
        blocked: Set<String> = emptySet(),
        allowed: Set<String> = emptySet(),
        custom: List<CustomBlocklistSource> = emptyList(),
    ) = FilterProfileDraft(
        id = id,
        name = "Balanced",
        protectionLevel = ProtectionLevel.BALANCED,
        enabledSourceIds = sources,
        manualRules = ManualDomainRules(allowedDomains = allowed, blockedDomains = blocked),
        customBlocklists = custom,
    )

    private fun urlFor(configuration: ShareableFilterConfiguration): String {
        val code = ShareableFilterCodec.encode(configuration)
        return (ShareableFilterLink.urlForCode(code) as ShareableFilterLinkCreationResult.Created).url
    }

    @Test
    fun `a shareable filter yields a canonical code, link, and round-trips`() {
        val filter = profile(blocked = setOf("ads.example.com"))
        val owner = FilterShareOwner(
            FakeSource(
                profiles = mapOf(filter.id to filter),
                catalog = setOf("blocklistproject-basic"),
            ),
        )

        val content = owner.content(filter.id)
        assertTrue(content is FilterShareContent.Ready)
        val ready = content as FilterShareContent.Ready
        assertTrue(ready.code.startsWith("LF1-"))
        assertTrue(ready.url.startsWith(ShareableFilterLink.CANONICAL_URL_PREFIX))

        val decoded = ShareableFilterLink.decode(ready.url) as ShareableFilterLinkResult.Decoded
        assertEquals(setOf("blocklistproject-basic"), decoded.configuration.enabledBlocklistIDs)
        assertEquals(setOf("ads.example.com"), decoded.configuration.blockedDomains)
    }

    @Test
    fun `an empty filter and an unknown filter are distinct honest refusals`() {
        val empty = FilterProfileDraft("filter-empty", "Empty", null, emptySet())
        val owner = FilterShareOwner(FakeSource(profiles = mapOf(empty.id to empty)))

        assertEquals(
            FilterShareContent.Unavailable(FilterShareFailureReason.EMPTY),
            owner.content(empty.id),
        )
        assertEquals(
            FilterShareContent.Unavailable(FilterShareFailureReason.UNKNOWN_FILTER),
            owner.content("missing"),
        )
    }

    @Test
    fun `a custom source with private parameters is never shared`() {
        val filter = profile(sources = setOf("custom-1"), custom = listOf(
            CustomBlocklistSource("custom-1", "Token list", "https://lists.example/block.txt?token=secret", BlocklistFormat.PLAIN_DOMAINS),
        ))
        val owner = FilterShareOwner(
            FakeSource(
                profiles = mapOf(filter.id to filter),
                custom = listOf(
                    ShareableCustomBlocklistSource(
                        id = "custom-1",
                        displayName = "Token list",
                        sourceUrl = "https://lists.example/block.txt?token=secret",
                        parseFormat = ShareableBlocklistParseFormat.PLAIN_DOMAINS,
                    ),
                ),
            ),
        )

        assertEquals(
            FilterShareContent.Unavailable(
                FilterShareFailureReason.CONTAINS_PRIVATE_SOURCE_PARAMETERS,
            ),
            owner.content(filter.id),
        )
    }

    @Test
    fun `selected filter export includes only its own disabled custom definitions`() {
        val selected = profile(custom = listOf(
            CustomBlocklistSource("mine.disabled", "My list", "https://lists.example/mine", BlocklistFormat.HOSTS),
        ))
        val neighbour = profile(id = "other-filter", custom = listOf(
            CustomBlocklistSource("other.private", "Other list", "https://lists.example/other?token=controlled"),
        ))
        val global = listOf(ShareableCustomBlocklistSource("other.private", "Other list", "https://lists.example/other?token=controlled"))
        val owner = FilterShareOwner(FakeSource(profiles = mapOf(selected.id to selected, neighbour.id to neighbour), custom = global))
        val content = owner.content(selected.id) as FilterShareContent.Ready
        val decoded = (ShareableFilterLink.decode(content.url) as ShareableFilterLinkResult.Decoded).configuration
        assertEquals(listOf(ShareableCustomBlocklistSource("mine.disabled", "My list", "https://lists.example/mine", ShareableBlocklistParseFormat.HOSTS)), decoded.customBlocklists)
        assertFalse(content.configuration.customBlocklists.any { it.id == "other.private" })
        assertEquals(FilterShareContent.Unavailable(FilterShareFailureReason.CONTAINS_PRIVATE_SOURCE_PARAMETERS), owner.content(neighbour.id))
    }

    @Test
    fun `share card eligibility uses the exact saved content without claiming an image render`() {
        val filter = profile()
        val owner = FilterShareOwner(FakeSource(profiles = mapOf(filter.id to filter)))

        assertEquals(
            owner.content(filter.id),
            owner.card(filter.id),
        )
        // A filter that cannot be shared at all still reports its own reason, not the card's.
        assertEquals(
            FilterShareContent.Unavailable(FilterShareFailureReason.UNKNOWN_FILTER),
            owner.card("missing"),
        )
    }

    @Test
    fun `document and camera adapters keep candidates unavailable`() {
        val owner = FilterShareOwner(FakeSource())

        assertEquals(
            FilterShareCandidateResult.Unavailable(
                FilterShareFailureReason.DOCUMENT_PICKER_UNAVAILABLE,
            ),
            owner.documentCandidate(),
        )
        assertEquals(
            FilterShareCandidateResult.Unavailable(
                FilterShareFailureReason.CAMERA_SCANNER_UNAVAILABLE,
            ),
            owner.cameraCandidate(),
        )
    }

    @Test
    fun `reviewing a canonical link produces a reviewed plan and writes nothing`() {
        val shared = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = setOf("ads.example.com"),
            allowedDomains = emptySet(),
        )
        val owner = FilterShareOwner(
            FakeSource(catalog = setOf("blocklistproject-basic")),
        )

        val result = owner.reviewImport(urlFor(shared))
        assertTrue(result is FilterShareImportReviewResult.Reviewed)
        val reviewed = result as FilterShareImportReviewResult.Reviewed
        assertTrue(reviewed.unknownSourceIds.isEmpty())
        assertTrue(reviewed.review.canConfirm)
        assertEquals(
            setOf("blocklistproject-basic"),
            reviewed.review.plan.applied.enabledBlocklistIDs,
        )

        // Confirmation hands the content back; the owner itself holds no library to mutate.
        val confirmation = owner.confirmAsNewFilter(reviewed.review)
        assertTrue(confirmation is ShareableFilterImportConfirmation.AddNewFilter)
        assertEquals(
            shared.enabledBlocklistIDs,
            (confirmation as ShareableFilterImportConfirmation.AddNewFilter).applied.enabledBlocklistIDs,
        )
    }

    @Test
    fun `an imported source outside the signed catalog is reported, not silently dropped`() {
        val shared = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("blocklistproject-basic", "not-in-catalog"),
            blockedDomains = emptySet(),
            allowedDomains = emptySet(),
        )
        val owner = FilterShareOwner(FakeSource(catalog = setOf("blocklistproject-basic")))

        val result = owner.reviewImport(urlFor(shared)) as FilterShareImportReviewResult.Reviewed

        assertEquals(setOf("not-in-catalog"), result.unknownSourceIds)
        assertEquals(
            1,
            result.review.plan.droppedCount(
                ShareableFilterImportPlan.DroppedEntry.Kind.UNAVAILABLE_BLOCKLIST,
            ),
        )
    }

    @Test
    fun `a malformed payload is a typed input refusal`() {
        val owner = FilterShareOwner(FakeSource())

        assertEquals(
            FilterShareImportReviewResult.Unavailable(FilterShareImportFailureReason.NOT_A_CODE),
            owner.reviewImport("this is not a Lava code"),
        )
    }

    @Test
    fun `replace confirmation re-reads the live target and refuses a stale review`() {
        val targetProfile = profile(id = "filter-target", blocked = setOf("ads.example.com"))
        val owner = FilterShareOwner(
            FakeSource(
                profiles = mapOf(targetProfile.id to targetProfile),
                catalog = setOf("blocklistproject-basic"),
            ),
        )
        val shared = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = setOf("ads.example.com"),
            allowedDomains = emptySet(),
        )
        val reviewed = owner.reviewImport(urlFor(shared), targetFilterId = targetProfile.id)
            as FilterShareImportReviewResult.Reviewed

        val confirmation = owner.confirmReplace(
            review = reviewed.review,
            targetFilterId = targetProfile.id,
            targetIsActive = false,
            confirmedActiveReplacement = false,
        )
        assertTrue(confirmation is ShareableFilterImportConfirmation.ReplaceContents)

        // A target whose live content no longer matches the reviewed commitment is stale.
        val changed = FilterShareOwner(
            FakeSource(
                profiles = mapOf(
                    targetProfile.id to profile(id = targetProfile.id, blocked = setOf("other.example.com")),
                ),
                catalog = setOf("blocklistproject-basic"),
            ),
        )
        val stale = changed.confirmReplace(
            review = reviewed.review,
            targetFilterId = targetProfile.id,
            targetIsActive = false,
            confirmedActiveReplacement = false,
        ) as ShareableFilterImportConfirmation.Refused
        assertEquals(ShareableFilterImportRefusal.StaleReview, stale.reason)
    }

    // ---- Wave 2d acquisition pipeline: bounded candidate boundary ----

    private fun acquiredOwner(
        document: FilterShareAcquisitionResult = FilterShareAcquisitionResult.Refused(
            FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE,
        ),
        camera: FilterShareAcquisitionResult = FilterShareAcquisitionResult.Refused(
            FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE,
        ),
        source: FilterShareSource = FakeSource(),
    ): FilterShareOwner = FilterShareOwner(
        source = source,
        inputPort = InjectedFilterShareInputPort(
            documentSource = FilterShareCandidateSource { document },
            cameraSource = FilterShareCandidateSource { camera },
        ),
    )

    private fun candidate(rawInput: String): FilterShareAcquisitionResult =
        FilterShareAcquisitionResult.Candidate(FilterShareImportCandidate(rawInput))

    @Test
    fun `the production input port refuses acquisition honestly, distinct from a missing UI`() {
        val owner = FilterShareOwner(FakeSource())

        assertEquals(
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE),
            owner.documentAcquisition(),
        )
        assertEquals(
            FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE),
            owner.cameraAcquisition(),
        )
    }

    @Test
    fun `the injectable port funnels a candidate through the legacy bridge view`() {
        val shared = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = emptySet(),
            allowedDomains = emptySet(),
        )
        val owner = acquiredOwner(document = candidate(urlFor(shared)))

        val legacy = owner.documentCandidate()
        assertTrue(legacy is FilterShareCandidateResult.Candidate)
        assertEquals(urlFor(shared), (legacy as FilterShareCandidateResult.Candidate).input.rawInput)
    }

    @Test
    fun `a malformed acquired payload is an input refusal, not a candidate`() {
        val owner = acquiredOwner(document = candidate("this is not a Lava code"))

        val outcome = owner.reviewDocument() as FilterShareAcquisitionReviewResult.Acquired
        assertEquals(
            FilterShareImportReviewResult.Unavailable(FilterShareImportFailureReason.NOT_A_CODE),
            outcome.review,
        )
    }

    @Test
    fun `an oversized acquired candidate is bounded before any decode`() {
        val oversized = "LF1-" + "A".repeat(FilterShareAcquisitionBounds.MAX_RAW_INPUT_CHARACTERS)
        val owner = acquiredOwner(document = candidate(oversized))

        val outcome = owner.reviewDocument() as FilterShareAcquisitionReviewResult.Acquired
        assertEquals(
            FilterShareImportReviewResult.Unavailable(FilterShareImportFailureReason.PAYLOAD_TOO_LARGE),
            outcome.review,
        )
    }

    @Test
    fun `an unsupported payload version is refused distinctly from malformed`() {
        val future = ShareableFilterConfiguration(schemaVersion = 3, allowedDomains = emptySet())
        val owner = acquiredOwner(document = candidate(urlFor(future)))

        val outcome = owner.reviewDocument() as FilterShareAcquisitionReviewResult.Acquired
        assertEquals(
            FilterShareImportReviewResult.Unavailable(FilterShareImportFailureReason.UNSUPPORTED_VERSION),
            outcome.review,
        )
    }

    @Test
    fun `a denied permission is distinct and never a success`() {
        val owner = acquiredOwner(
            camera = FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.PERMISSION_DENIED),
        )

        assertEquals(
            FilterShareAcquisitionReviewResult.Refused(FilterShareAcquisitionRefusal.PERMISSION_DENIED),
            owner.reviewCamera(),
        )
    }

    @Test
    fun `a cancelled picker is distinct and never a success`() {
        val owner = acquiredOwner(
            document = FilterShareAcquisitionResult.Refused(FilterShareAcquisitionRefusal.CANCELLED),
        )

        assertEquals(
            FilterShareAcquisitionReviewResult.Refused(FilterShareAcquisitionRefusal.CANCELLED),
            owner.reviewDocument(),
        )
    }

    @Test
    fun `a review whose catalog changed after acquisition is stale and applies nothing`() {
        val shared = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = setOf("ads.example.com"),
            allowedDomains = emptySet(),
        )
        val source = FakeSource(catalog = setOf("blocklistproject-basic"))
        val owner = FilterShareOwner(source = source)
        val reviewed = owner.reviewImport(urlFor(shared)) as FilterShareImportReviewResult.Reviewed

        // The signed catalog lost the list between acquisition and confirmation.
        source.catalog = emptySet()

        val confirmation = owner.confirmAsNewFilter(reviewed.review)
        assertEquals(
            ShareableFilterImportConfirmation.Refused(ShareableFilterImportRefusal.StaleReview),
            confirmation,
        )
        assertFalse(confirmation is ShareableFilterImportConfirmation.AddNewFilter)
    }

    @Test
    fun `a tier change after acquisition refuses rather than importing a partial subset`() {
        val shared = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = setOf("ads.example.com", "tracker.example.com"),
            allowedDomains = emptySet(),
        )
        val source = FakeSource(
            catalog = setOf("blocklistproject-basic"),
            limits = FeatureLimits(25, 25, 500_000, false, false),
        )
        val owner = FilterShareOwner(source = source)
        val reviewed = owner.reviewImport(urlFor(shared)) as FilterShareImportReviewResult.Reviewed
        assertEquals(2, reviewed.review.plan.applied.blockedDomains.size)

        // The plan the user reviewed allowed two domains; the live tier now allows one.
        source.limits = FeatureLimits(25, 1, 500_000, false, false)

        val confirmation = owner.confirmAsNewFilter(reviewed.review)
        assertTrue(confirmation is ShareableFilterImportConfirmation.Refused)
        assertEquals(
            ShareableFilterImportRefusal.StaleReview,
            (confirmation as ShareableFilterImportConfirmation.Refused).reason,
        )
    }

    @Test
    fun `reviewing an acquired payload leaves the saved library untouched until confirmation`() {
        val filter = profile(blocked = setOf("ads.example.com"))
        val shared = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = setOf("tracker.example.com"),
            allowedDomains = emptySet(),
        )
        val source = FakeSource(
            profiles = mapOf(filter.id to filter),
            catalog = setOf("blocklistproject-basic"),
        )
        val owner = acquiredOwner(document = candidate(urlFor(shared)), source = source)
        val before = source.savedProfile(filter.id)

        val outcome = owner.reviewDocument() as FilterShareAcquisitionReviewResult.Acquired
        val review = (outcome.review as FilterShareImportReviewResult.Reviewed).review
        owner.confirmReplace(
            review = review,
            targetFilterId = filter.id,
            targetIsActive = false,
            confirmedActiveReplacement = false,
        )

        assertEquals("a review never mutates the saved library", before, source.savedProfile(filter.id))
    }

    // ---- B08/F11 device adapter seam: owner behavior is unchanged without an adapter ----

    @Test
    fun `the owner's async request stays a typed refusal with no adapter injected`() {
        val owner = FilterShareOwner(FakeSource())

        assertEquals(
            FilterShareAcquisitionReviewResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE),
            runBlocking { owner.requestDocument() },
        )
        assertEquals(
            FilterShareAcquisitionReviewResult.Refused(FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE),
            runBlocking { owner.requestCamera() },
        )
    }

    @Test
    fun `an injected device adapter funnels a real document acquisition through review`() {
        val shared = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = setOf("ads.example.com"),
            allowedDomains = emptySet(),
        )
        val owner = FilterShareOwner(
            source = FakeSource(catalog = setOf("blocklistproject-basic")),
            inputPort = FilterShareDeviceAdapter(
                object : FilterShareDeviceHost {
                    override fun canLaunchDocument(): Boolean = true
                    override fun canLaunchCamera(): Boolean = false
                    override fun cameraPermissionGranted(): Boolean = true
                    override fun requestCameraPermission(completion: (Boolean) -> Unit) =
                        completion(false)
                    override fun launchDocument(completion: (FilterShareDevicePick) -> Unit) =
                        completion(FilterShareDevicePick.Acquired(urlFor(shared)))
                    override fun launchCamera(completion: (FilterShareDevicePick) -> Unit) =
                        completion(FilterShareDevicePick.Unavailable)
                },
            ),
        )

        val outcome = runBlocking { owner.requestDocument() }
            as FilterShareAcquisitionReviewResult.Acquired
        val reviewed = outcome.review as FilterShareImportReviewResult.Reviewed
        assertEquals(setOf("ads.example.com"), reviewed.review.plan.applied.blockedDomains)
    }
}
