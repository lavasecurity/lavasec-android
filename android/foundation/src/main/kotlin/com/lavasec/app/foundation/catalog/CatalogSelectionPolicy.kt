package com.lavasec.app.foundation.catalog

/** Why a fetched candidate was not selected. Parse failures are surfaced verbatim. */
sealed interface CatalogCandidateRejection {
    data class Invalid(val failure: CatalogParseFailure) : CatalogCandidateRejection

    /** The candidate is older than the installed catalog (a rollback/downgrade attempt). */
    data object StaleRollback : CatalogCandidateRejection
}

/** What the selection policy actually did with the inputs. */
enum class CatalogSelectionOutcome {
    /** A newer, valid candidate was accepted (and sanitized) and is now effective. */
    SELECTED_CANDIDATE,

    /** The candidate did not represent a change; the installed catalog is retained. */
    KEPT_INSTALLED,

    /** The candidate was rejected and the last-known-good cache is still effective. */
    KEPT_LAST_KNOWN_GOOD,

    /** The candidate was rejected and there is no last-known-good cache; the bundle is effective. */
    FELL_BACK_TO_BUNDLED,

    /** There was no candidate and no cache; the bundled catalog is effective. */
    BUNDLED_ONLY,
}

/** The effective catalog plus how it was chosen. */
data class CatalogSelection(
    val artifact: CatalogArtifact,
    val outcome: CatalogSelectionOutcome,
    val rejection: CatalogCandidateRejection? = null,
)

/**
 * Pure refresh/selection policy over a bundled catalog, a last-known-good cache and a freshly
 * fetched candidate.
 *
 * Rules, in order:
 *  1. A candidate must decode under every bound; otherwise the last-known-good (or bundled)
 *     catalog stays effective and the parse failure is reported.
 *  2. The candidate must be at least as new as what is installed. An older `generated_at` is a
 *     rollback and is refused; an equal timestamp with different bytes is treated as "no change"
 *     (identity is not re-stamped from an ambiguous tie).
 *  3. Identical candidate bytes never replace the installed artifact.
 *  4. Otherwise the newest valid catalog wins, sanitized through [CatalogProvenancePolicy].
 *
 * A valid installed artifact is never discarded: every rejection path returns the cache (or the
 * bundle) unchanged.
 */
object CatalogSelectionPolicy {
    fun select(
        bundled: CatalogArtifact,
        lastKnownGood: CatalogArtifact?,
        candidate: CatalogFetchEnvelope?,
        nowEpochSeconds: Long,
    ): CatalogSelection {
        require(bundled.provenance == CatalogProvenance.BUNDLED) {
            "the bundled catalog must carry BUNDLED provenance"
        }
        val approved = CatalogProvenancePolicy.approvedSourceIds(bundled.manifest)
        val installed = lastKnownGood?.let { cached ->
            cached.copy(
                provenance = CatalogProvenance.LAST_KNOWN_GOOD,
                manifest = CatalogProvenancePolicy.sanitized(
                    cached.manifest,
                    CatalogProvenance.LAST_KNOWN_GOOD,
                    approved,
                ),
            )
        }
        val fallback = installed ?: bundled
        val fallbackOutcome =
            if (installed != null) CatalogSelectionOutcome.KEPT_LAST_KNOWN_GOOD
            else CatalogSelectionOutcome.FELL_BACK_TO_BUNDLED

        if (candidate == null) {
            return if (installed != null) {
                CatalogSelection(installed, CatalogSelectionOutcome.KEPT_LAST_KNOWN_GOOD)
            } else {
                CatalogSelection(bundled, CatalogSelectionOutcome.BUNDLED_ONLY)
            }
        }

        val parsed = CatalogManifestParser.parseEnvelope(candidate)
        if (parsed is CatalogParseResult.Rejected) {
            return CatalogSelection(
                fallback,
                fallbackOutcome,
                CatalogCandidateRejection.Invalid(parsed.failure),
            )
        }
        val manifest = (parsed as CatalogParseResult.Accepted).manifest
        val candidateSha = sha256Hex(candidate.bytes)
        // With no cache the bundle is still the installed baseline, so a candidate older than it
        // must not roll it back.
        val baseline = installed ?: bundled

        // A far-future `generated_at` would out-rank every later legitimate catalog, so it is
        // refused rather than allowed to pin the device forward. Only checked when the caller
        // supplies a real clock.
        if (nowEpochSeconds > 0 &&
            manifest.generatedAtEpochSeconds > nowEpochSeconds + MAX_FUTURE_GENERATED_AT_SKEW_SECONDS
        ) {
            return CatalogSelection(
                fallback,
                fallbackOutcome,
                CatalogCandidateRejection.Invalid(CatalogParseFailure.INVALID_FIELD_VALUE),
            )
        }

        if (candidateSha == baseline.contentSha256) {
            return CatalogSelection(baseline, CatalogSelectionOutcome.KEPT_INSTALLED)
        }
        if (manifest.generatedAtEpochSeconds < baseline.generatedAtEpochSeconds) {
            return CatalogSelection(
                fallback,
                fallbackOutcome,
                CatalogCandidateRejection.StaleRollback,
            )
        }
        if (manifest.generatedAtEpochSeconds == baseline.generatedAtEpochSeconds) {
            return CatalogSelection(baseline, CatalogSelectionOutcome.KEPT_INSTALLED)
        }

        val sanitized = CatalogProvenancePolicy.sanitized(
            manifest,
            CatalogProvenance.REMOTE,
            approved,
        )
        return CatalogSelection(
            artifact = CatalogArtifact(sanitized, CatalogProvenance.REMOTE, candidateSha),
            outcome = CatalogSelectionOutcome.SELECTED_CANDIDATE,
        )
    }

    /** Tolerance for a candidate stamped slightly ahead of the device clock. */
    const val MAX_FUTURE_GENERATED_AT_SKEW_SECONDS = 24L * 60L * 60L
}

/**
 * Restores a persisted catalog artifact from bytes. A corrupt cache decodes to `null` so the
 * caller falls back to the bundle rather than serving partially trusted state.
 */
object CatalogArtifactLoader {
    fun load(
        bytes: ByteArray,
        provenance: CatalogProvenance,
        approvedSourceIds: Set<String>,
    ): CatalogArtifact? {
        val parsed = CatalogManifestParser.parse(bytes)
        if (parsed is CatalogParseResult.Rejected) return null
        val manifest = (parsed as CatalogParseResult.Accepted).manifest
        // The bundled catalog is the trust anchor and approves its own ids; every other
        // provenance must be approved by the bundle the caller supplies.
        val approved = if (provenance == CatalogProvenance.BUNDLED) {
            CatalogProvenancePolicy.approvedSourceIds(manifest)
        } else {
            approvedSourceIds
        }
        return CatalogArtifact(
            manifest = CatalogProvenancePolicy.sanitized(manifest, provenance, approved),
            provenance = provenance,
            contentSha256 = sha256Hex(bytes),
        )
    }
}
