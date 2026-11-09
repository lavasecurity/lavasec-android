package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedConfException
import com.lavasec.app.foundation.chained.ChainedConfRefusal
import com.lavasec.app.foundation.chained.ChainedReadiness
import com.lavasec.app.foundation.chained.ChainedReadinessRefusal
import com.lavasec.app.foundation.chained.ChainedRoutingPolicy
import com.lavasec.app.foundation.chained.ChainedTunnelResolverSelection
import com.lavasec.app.foundation.chained.ChainedUpstreamConfParser
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * The configuration-only half of chained eligibility: what the stored file itself can support,
 * with the account, device and user-preference gates held neutral.
 *
 * This is deliberately narrower than the runtime readiness gate in
 * [com.lavasec.app.foundation.chained.ChainedReadiness.evaluate]. A review surface must be able to
 * tell the user "this upstream is complete and runnable once chaining is enabled and entitled"
 * without pretending to know the Plus verdict or the device's memory eligibility, and without
 * reporting `disabled-by-user` for a feature the user has not switched on yet. The gate is
 * evaluated with ordinary chaining plus a positive entitlement/device verdict, so the only
 * refusals it can carry are the configuration's own: no usable tunnel DNS, unrouted DNS, route
 * collisions, or a missing IPv4 route.
 */
data class ChainedConfigurationEligibility(
    /** Full or split, derived purely from the validated `AllowedIPs`. */
    val routingPolicy: ChainedRoutingPolicy,
    /**
     * The ordered `DNS =` entries the chained data path can actually speak to, from
     * [ChainedTunnelResolverSelection]. Empty means the file would claim its routes and resolve
     * nothing.
     */
    val usableTunnelResolvers: List<String>,
    /**
     * Why the configuration itself cannot run, or `null` when it can. Never the entitlement,
     * device or preference gates, which this type holds neutral by construction.
     */
    val refusal: ChainedReadinessRefusal?,
) {
    init {
        require(usableTunnelResolvers.distinct().size == usableTunnelResolvers.size) {
            "usable tunnel resolvers must be distinct"
        }
    }

    /** Whether the configuration itself is complete enough to run. */
    val isEligible: Boolean get() = refusal == null
}

/**
 * Display-safe, non-secret description of a chained WireGuard upstream.
 *
 * Every field here is safe to render in an editor and to carry across the native/JS boundary: the
 * endpoint, the client address, the peer's `AllowedIPs` and `DNS =`, the interface limits, the
 * peer's PUBLIC key (public by design) and the configuration-only eligibility. The private key and
 * any pre-shared key are deliberately absent — they live only in the opaque
 * [ChainedConfigurationKeyMaterial] a review carries, and only [ChainedConfigurationCommandOwner.save]
 * may consume them.
 *
 * Built from [ChainedUpstreamConfiguration], which by construction never carries key material.
 */
data class ChainedConfigurationMetadata(
    val endpointHost: String,
    val endpointPort: Int,
    val clientAddress: String,
    val allowedIPs: List<String>,
    val dnsAddresses: List<String>,
    val persistentKeepaliveSeconds: Int,
    /** `[Interface] MTU`, or `null` when the file carried none (the route plan uses 1280). */
    val interfaceMTU: Int?,
    /** The peer's public key. PUBLIC by design; never the private or pre-shared key. */
    val peerPublicKey: String,
    val eligibility: ChainedConfigurationEligibility,
) {
    init {
        require(endpointHost.isNotBlank()) { "chained endpoint host must not be blank" }
        require(endpointPort in 1..65535) { "chained endpoint port must be a valid UDP port" }
        require(clientAddress.isNotBlank()) { "chained client address must not be blank" }
        require(peerPublicKey.isNotBlank()) { "chained peer public key must not be blank" }
    }

    /** The endpoint rendered `host:port` for display. */
    val endpoint: String get() = "$endpointHost:$endpointPort"

    companion object {
        /** Projects the non-secret configuration into its display-safe descriptor. */
        fun from(configuration: ChainedUpstreamConfiguration): ChainedConfigurationMetadata =
            ChainedConfigurationMetadata(
                endpointHost = configuration.endpointHost,
                endpointPort = configuration.endpointPort,
                clientAddress = configuration.clientAddress,
                allowedIPs = configuration.allowedIPs.toList(),
                dnsAddresses = configuration.dnsAddresses.toList(),
                persistentKeepaliveSeconds = configuration.persistentKeepaliveSeconds,
                interfaceMTU = configuration.interfaceMTU,
                peerPublicKey = configuration.peerPublicKey,
                eligibility = ChainedConfigurationEligibility(
                    routingPolicy = configuration.routingPolicy,
                    usableTunnelResolvers = ChainedTunnelResolverSelection
                        .selectedResolvers(configuration),
                    refusal = configurationRefusalOrNull(configuration),
                ),
            )

        private fun configurationRefusalOrNull(
            configuration: ChainedUpstreamConfiguration,
        ): ChainedReadinessRefusal? =
            (
                ChainedReadiness.evaluate(
                    configuration = configuration,
                    hasEntitlement = true,
                    deviceEligible = true,
                    userEnabled = true,
                ) as? ChainedReadiness.NotReady
                )?.reason
    }
}

/**
 * The opaque, non-serializable handle to parsed key material that a review carries.
 *
 * It exists so [ChainedConfigurationReview] can hand key material back to exactly one consumer —
 * [ChainedConfigurationCommandOwner.save] — without ever exposing the parsed private or pre-shared
 * key through the review's public surface or the RN/native editor. A caller may hold the review,
 * copy it, or cross the JS bridge with its metadata; the only way to reach the rotation is
 * [consume], which is module-internal and is consumed by `save` alone.
 *
 * `consume` is a lease, not a one-way door: `save` returns the token for a non-committing outcome
 * (a rejected or failed repository write) so a retry from the same review is possible. A committed
 * save does not release it, so the review cannot be replayed into a second generation.
 *
 * Not `java.io.Serializable` and not `Parcelable`: there is no supported way to persist or marshal
 * it, which is the point. [toString] is redacted so a careless interpolation cannot log the
 * material through the rotation it wraps (whose own `toString` is redacted too).
 */
class ChainedConfigurationKeyMaterial internal constructor(
    private val rotation: ChainedUpstreamRotation,
) {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The rotation, once per lease. `null` after a committed save or a second concurrent call. */
    internal fun consume(): ChainedUpstreamRotation? {
        if (!consumed.compareAndSet(false, true)) return null
        return rotation
    }

    /** Returns an unconsumed lease after a non-committing save so the review can be retried. */
    internal fun release() {
        consumed.set(false)
    }

    override fun toString(): String = "ChainedConfigurationKeyMaterial(<redacted>)"
}

/**
 * The outcome of one [ChainedConfigurationCommandOwner.importReview].
 *
 * [Ready] carries the display-safe [metadata] and the opaque [keyMaterial] handle; [Rejected]
 * carries the parser's typed [ChainedConfRefusal] and no key material, because a refused import
 * stages nothing. The refusal vocabulary is a log/health identifier, never user copy.
 */
sealed interface ChainedConfigurationReview {
    /** A parsed, validated rotation ready to be committed. */
    class Ready internal constructor(
        val metadata: ChainedConfigurationMetadata,
        internal val keyMaterial: ChainedConfigurationKeyMaterial,
    ) : ChainedConfigurationReview {
        override fun toString(): String = "ChainedConfigurationReview.Ready(metadata=$metadata)"
    }

    /** The text was not a configuration this feature can stage. Nothing is stored. */
    data class Rejected(val refusal: ChainedConfRefusal) : ChainedConfigurationReview
}

/**
 * The outcome of one [ChainedConfigurationCommandOwner.save].
 *
 * The repository's own typed results are forwarded rather than collapsed: [Committed] carries the
 * new generation identity, [Rejected] the staging failure, and [AuthoritativeReadFailed] the
 * pre-staging refusal when the existing record could not be read. [NotReviewable] and
 * [AlreadyConsumed] describe saves that never reached the repository, and [Failed] an unexpected
 * storage exception. In every non-[Committed] case the previously committed configuration and key
 * are untouched.
 */
sealed interface ChainedConfigurationSaveResult {
    /** The rotation is durably committed at [identity]. */
    data class Committed(val identity: ChainedUpstreamIdentity) : ChainedConfigurationSaveResult

    /** The repository refused the rotation; the prior committed pair is intact. */
    data class Rejected(val failure: ChainedUpstreamSecretStaging.Failure) : ChainedConfigurationSaveResult

    /**
     * The caller's generation expectation (typically first-rotation absence) was no longer true
     * when the serialized repository write ran; a concurrent commit won. The prior committed pair
     * is intact and the supplied review must not be retried over it.
     */
    data object StaleGeneration : ChainedConfigurationSaveResult

    /** The authoritative record could not be read; nothing was staged. */
    data object AuthoritativeReadFailed : ChainedConfigurationSaveResult

    /** The review was a parse failure, so there was no rotation to commit. */
    data class NotReviewable(val refusal: ChainedConfRefusal) : ChainedConfigurationSaveResult

    /** The review's key lease was already consumed by a committed save. */
    data object AlreadyConsumed : ChainedConfigurationSaveResult

    /** Storage/settings failure; a non-null identity witnesses profiles already published. */
    data class Failed(val message: String) : ChainedConfigurationSaveResult {
        init {
            require(message.isNotBlank()) { "chained save failure message must not be blank" }
        }
    }
}

/** The outcome of one [ChainedConfigurationCommandOwner.remove]. */
sealed interface ChainedConfigurationRemovalResult {
    /** The configuration and every key generation were removed. */
    data object Removed : ChainedConfigurationRemovalResult

    /** Removal failed part-way; `remove` is safe to retry. */
    data class Failed(val message: String) : ChainedConfigurationRemovalResult {
        init {
            require(message.isNotBlank()) { "chained removal failure message must not be blank" }
        }
    }
}

/**
 * The durable `vpn.toggle enabled` preference, injected so the owner can commit it without owning
 * the process-wide credential graph (production wires `ChainedComposition.setEnabled`).
 */
fun interface ChainedEnabledPreferencePort {
    /** Commits the preference; returns `true` only when the write is durable. */
    suspend fun setEnabled(enabled: Boolean): Boolean

    /** A native mutation must remain authorized inside the preference's actual write edge. */
    suspend fun setEnabledWhileCurrent(enabled: Boolean, isCurrent: () -> Boolean): Boolean = false
}

/** The canonical `vpn.toggle` keys. */
enum class ChainedConfigurationToggleKey(val wireName: String) {
    SETUP("setup"),
    ENABLED("enabled"),
    FALLBACK("fallback"),
    ;

    companion object {
        fun fromWireName(name: String): ChainedConfigurationToggleKey? =
            entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Stable refusal codes for the ordered `vpn.*` editor. They are the wire contract JS maps to
 * copy; a code is never rendered directly. Legacy refusal names remain readable for old clients.
 */
enum class ChainedConfigurationEditorRefusal(val wireCode: String) {
    /** `vpn.swap`, a second-row `edit`/`remove`, or a larger reset: the rotation is one row. */
    MULTI_ROW_UNSUPPORTED("vpn-multi-row-unsupported"),

    /** The committed rotation changed since the editor began. */
    STALE_GENERATION("vpn-stale-generation"),

    /** The supplied id names no open draft (never opened, already committed, or superseded). */
    UNKNOWN_DRAFT("vpn-unknown-draft"),

    /** There is no committed rotation to edit. */
    NOT_CONFIGURED("vpn-not-configured"),

    /** `vpn.toggle` named a key Android cannot represent here (`setup`). */
    UNSUPPORTED_TOGGLE("vpn-unsupported-toggle"),

    /** `vpn.rowToggle`: Android's single rotation has no per-row enable bit. */
    ROW_TOGGLE_UNSUPPORTED("vpn-row-toggle-unsupported"),
    INVALID_ROW("vpn-invalid-row"),
    INVALID_COMPOSITION("vpn-invalid-composition"),
    BUSY("vpn-editor-busy"),
}

/** One display-safe row of a [ChainedConfigurationDraft]. Never carries key material. */
data class ChainedConfigurationDraftRow(
    val name: String,
    /** The canonical row mode label (`Full tunnel`/`Split tunnel`), derived from `AllowedIPs`. */
    val mode: String,
    val isEnabled: Boolean,
)

/**
 * The display-safe projection of the open ordered editor draft. Mirrors the canonical iOS
 * `VPNDraft` (`{id,revision,changed,containsFullTunnel,rows}`) plus [removeStaged]. It carries only public endpoint/route
 * facts — never the private or pre-shared key, which stay in the opaque key lease.
 */
data class ChainedConfigurationDraft(
    val id: String,
    val generation: Long,
    /** Monotonic per-draft revision so a screen can discard an out-of-order update. */
    val revision: Long,
    val changed: Boolean,
    val containsFullTunnel: Boolean,
    val removeStaged: Boolean,
    val rows: List<ChainedConfigurationDraftRow>,
)

/** The outcome of one editor mutation. */
sealed interface ChainedConfigurationEditorResult {
    /** The draft's new display-safe state. */
    data class Draft(val draft: ChainedConfigurationDraft) : ChainedConfigurationEditorResult

    /** A single-rotation projection refusal with a stable wire code. */
    data class Refused(val refusal: ChainedConfigurationEditorRefusal) : ChainedConfigurationEditorResult

    /** The supplied text was not a configuration this feature can stage. */
    data class Rejected(val refusal: ChainedConfRefusal) : ChainedConfigurationEditorResult
}

/** The outcome of [ChainedConfigurationCommandOwner.commitEditor]. */
sealed interface ChainedConfigurationEditorCommitResult {
    /** The staged review (or the unchanged rotation) is durably committed at [identity]. */
    data class Committed(val identity: ChainedUpstreamIdentity) : ChainedConfigurationEditorCommitResult

    /** The staged removal completed. */
    data object Removed : ChainedConfigurationEditorCommitResult

    /** A single-rotation projection refusal with a stable wire code. */
    data class Refused(val refusal: ChainedConfigurationEditorRefusal) : ChainedConfigurationEditorCommitResult

    /** The repository refused the staged rotation; the prior committed pair is intact. */
    data class Rejected(val failure: ChainedUpstreamSecretStaging.Failure) : ChainedConfigurationEditorCommitResult

    /** The authoritative record could not be read; nothing was staged. */
    data object AuthoritativeReadFailed : ChainedConfigurationEditorCommitResult

    /** Failure after or before publication; durable native facts do not authorize a retired UI. */
    data class Failed(
        val message: String,
        val committedIdentity: ChainedUpstreamIdentity? = null,
        /** The exact repository generation durably removed before this request retired. */
        val removedGeneration: Long? = null,
    ) : ChainedConfigurationEditorCommitResult {
        init {
            require(message.isNotBlank()) { "chained editor commit failure message must not be blank" }
            require(removedGeneration == null || removedGeneration > 0L)
            require(committedIdentity == null || removedGeneration == null)
        }
    }
}

/** The outcome of [ChainedConfigurationCommandOwner.toggle]. */
sealed interface ChainedConfigurationToggleResult {
    /** The preference was committed durably. */
    data object Applied : ChainedConfigurationToggleResult

    /** A key Android cannot represent here, with a stable wire code. */
    data class Refused(val refusal: ChainedConfigurationEditorRefusal) : ChainedConfigurationToggleResult

    /** The preference write or the owner delegation failed; the previous value stands. */
    data object Failed : ChainedConfigurationToggleResult
}

/**
 * Headless, UI-free command owner for the chained-upstream configuration lifecycle:
 * import → review → save → remove.
 *
 * It is the single seam the editor (native or RN) drives. It owns no UI, no Android API and no
 * engine state, so it is a pure JVM policy: it parses through
 * [ChainedUpstreamConfParser], projects display-safe metadata and configuration eligibility, and
 * commits the ordered metadata and matching credential rows through [ChainedUpstreamConfigurationRepository]. The
 * classified secret half is never read here — it is produced by the parser and handed straight
 * back to the repository's staging protocol, which is what makes an interrupted write leave the
 * prior configuration and key intact rather than a torn pair.
 *
 * [importReview] never returns key material: the parsed private and pre-shared keys ride an opaque
 * [ChainedConfigurationKeyMaterial] that only [save] may consume. This is the Android mirror of
 * iOS's "opaque review identities instead of returning a parsed private key to JavaScript" (plan
 * B09).
 *
 * @param repository the credential-protected authority that owns both halves. In production this
 *   is `CredentialChainedUpstreamConfigurationRepository`.
 * @param parseRotation the WireGuard `.conf` parser, injectable so the typed-refusal and
 *   display-projection behaviour is testable without real key material or storage.
 */
class ChainedConfigurationCommandOwner(
    private val repository: ChainedUpstreamConfigurationRepository,
    /**
     * The `vpn.toggle enabled` preference sink. `null` (the default) makes that key an explicit
     * `vpn-unsupported-toggle` refusal rather than a fabricated success.
     *
     * Declared before [parseRotation] so the ubiquitous trailing-lambda construction
     * (`ChainedConfigurationCommandOwner(repository) { rotation() }`) keeps binding the parser.
     */
    private val enabledPreference: ChainedEnabledPreferencePort? = null,
    private val loadEnabled: suspend () -> Boolean = { true },
    private val onFullTunnelCommitted: suspend (Long) -> Boolean = { true },
    private val dnsFallbackPreference: ChainedEnabledPreferencePort? = null,
    private val onFullTunnelCommittedWhileCurrent: (suspend (Long, () -> Boolean) -> Boolean)? = null,
    private val parseRotation: (String) -> ChainedUpstreamRotation =
        ChainedUpstreamConfParser::rotation,
) {
    /**
     * The one in-flight editor draft. Keyed by the caller-supplied opaque id so a late command for
     * a superseded visit cannot mutate the current draft. Held in memory only: the draft is a
     * projection over the committed ordered selection, and a process death simply discards it.
     */
    private var editor: EditorSession? = null
    private val editorLock = Any()
    private var rowChangeInFlight = false
    /**
     * Parses and validates [rawConfigurationText] into a display-safe review, or returns the
     * parser's typed refusal. Never mutates storage and never exposes key material to the caller.
     */
    fun importReview(rawConfigurationText: String): ChainedConfigurationReview =
        try {
            val rotation = parseRotation(rawConfigurationText)
            ChainedConfigurationReview.Ready(
                metadata = ChainedConfigurationMetadata.from(rotation.configuration),
                keyMaterial = ChainedConfigurationKeyMaterial(rotation),
            )
        } catch (refused: ChainedConfException) {
            ChainedConfigurationReview.Rejected(refused.refusal)
        }

    /**
     * Commits the whole rotation a review carries under one fresh generation.
     *
     * The review's key lease is consumed exactly once for the commit. A [ChainedUpstreamSaveResult.Rejected]
     * or [AuthoritativeReadFailed] from the repository releases the lease so the caller can retry
     * the same review; a committed outcome does not, so the review cannot be replayed into a second
     * generation.
     */
    suspend fun save(
        review: ChainedConfigurationReview,
        expectation: ChainedUpstreamSaveExpectation = ChainedUpstreamSaveExpectation.AnyCommitted,
        isCurrent: (() -> Boolean)? = null,
    ): ChainedConfigurationSaveResult =
        when (review) {
            is ChainedConfigurationReview.Rejected ->
                ChainedConfigurationSaveResult.NotReviewable(review.refusal)

            is ChainedConfigurationReview.Ready -> saveReady(review, expectation, isCurrent)
        }

    private suspend fun saveReady(
        review: ChainedConfigurationReview.Ready,
        expectation: ChainedUpstreamSaveExpectation,
        isCurrent: (() -> Boolean)?,
    ): ChainedConfigurationSaveResult {
        if (isCurrent?.invoke() == false) return ChainedConfigurationSaveResult.StaleGeneration
        val rotation = review.keyMaterial.consume()
            ?: return ChainedConfigurationSaveResult.AlreadyConsumed
        return try {
            val committed = if (isCurrent == null) repository.save(rotation, expectation)
                else repository.saveWhileCurrent(rotation, expectation, isCurrent)
            when (committed) {
                is ChainedUpstreamSaveResult.Committed ->
                    ChainedConfigurationSaveResult.Committed(committed.identity)

                is ChainedUpstreamSaveResult.Rejected -> {
                    review.keyMaterial.release()
                    ChainedConfigurationSaveResult.Rejected(committed.failure)
                }

                ChainedUpstreamSaveResult.Superseded -> {
                    review.keyMaterial.release()
                    ChainedConfigurationSaveResult.StaleGeneration
                }

                ChainedUpstreamSaveResult.AuthoritativeReadFailed -> {
                    review.keyMaterial.release()
                    ChainedConfigurationSaveResult.AuthoritativeReadFailed
                }
            }
        } catch (cancelled: CancellationException) {
            review.keyMaterial.release()
            throw cancelled
        } catch (failure: Exception) {
            review.keyMaterial.release()
            ChainedConfigurationSaveResult.Failed(
                failure.safeMessage("Chained configuration save failed"),
            )
        }
    }

    /** Removes the configuration and every key generation. Safe to retry after a failure. */
    suspend fun remove(): ChainedConfigurationRemovalResult =
        try {
            repository.removeAll()
            ChainedConfigurationRemovalResult.Removed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ChainedConfigurationRemovalResult.Failed(
                failure.safeMessage("Chained configuration removal failed"),
            )
        }

    /**
     * Display-safe metadata of the committed configuration, or `null` when none is stored. Never
     * reads or returns key material.
     */
    suspend fun storedMetadata(): ChainedConfigurationMetadata? =
        repository.loadConfiguration()?.let { stored ->
            ChainedConfigurationMetadata.from(stored.configuration)
        }

    /** Opens metadata only. Late native sheets and every commit are fenced to this generation. */
    suspend fun beginEditor(
        id: String,
        expectedGeneration: Long,
        isCurrent: (() -> Boolean)? = null,
    ): ChainedConfigurationEditorResult {
        require(id.isNotBlank())
        if (isCurrent?.invoke() == false) return ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
        val stored = repository.loadConfiguration() ?: return ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.NOT_CONFIGURED)
        if (stored.identity.generation != expectedGeneration || stored.pendingImportId != null && stored.pendingImportId != id) {
            return ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
        }
        return synchronized(editorLock) {
            // The authoritative read above can resume after a successor has opened its editor.
            // Validate original host ownership at the actual session publication edge.
            if (isCurrent?.invoke() == false) return@synchronized ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
            if (rowChangeInFlight || editor?.commitInFlight == true) return@synchronized ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.BUSY)
            val session = EditorSession(id, stored.identity.generation, stored.configuration.orderedHops.map { it.withoutEntryHop() })
            editor = session
            ChainedConfigurationEditorResult.Draft(session.snapshot())
        }
    }

    fun editorDraft(id: String): ChainedConfigurationDraft? = synchronized(editorLock) { editor?.takeIf { it.id == id }?.snapshot() }
    fun currentDraft(): ChainedConfigurationDraft? = synchronized(editorLock) { editor?.snapshot() }

    fun cancelEditor(id: String) {
        synchronized(editorLock) { if (editor?.id == id && editor?.commitInFlight != true) editor = null }
    }

    suspend fun cancelFirstImport(id: String, identity: ChainedUpstreamIdentity): ChainedUpstreamPendingRemovalResult {
        cancelEditor(id)
        return repository.removePendingImport(id, identity)
    }

    fun swapEditorRows(id: String): ChainedConfigurationEditorResult = mutateEditor(id) { session ->
        if (session.rows.size != 2) throw InvalidEditorRow()
        session.rows.reversed()
    }

    /** New content is parsed natively; renames retain the original row's credential reference. */
    fun stageEditorConfiguration(id: String, index: Int, rawConfigurationText: String,
        name: String? = null, expectedRevision: Long? = null): ChainedConfigurationEditorResult {
        val review = when (val parsed = importReview(rawConfigurationText)) {
            is ChainedConfigurationReview.Rejected -> return ChainedConfigurationEditorResult.Rejected(parsed.refusal)
            is ChainedConfigurationReview.Ready -> parsed
        }
        return mutateEditor(id, expectedRevision) { session ->
            if (index !in 0..session.rows.size || index >= 2) throw InvalidEditorRow()
            val old = session.rows.getOrNull(index)
            val m = review.metadata
            val configuration = ChainedUpstreamConfiguration.create(m.endpointHost, m.endpointPort, m.peerPublicKey,
                m.clientAddress, m.allowedIPs, m.persistentKeepaliveSeconds, m.interfaceMTU, m.dnsAddresses,
                displayName = name ?: old?.configuration?.displayName.orEmpty(), isEnabled = old?.configuration?.isEnabled ?: true)
            val replacement = EditorRow(old?.originalIndex, configuration, review)
            session.rows.toMutableList().apply { if (index == size) add(replacement) else this[index] = replacement }
        }
    }

    fun stageEditorName(id: String, index: Int, name: String, expectedRevision: Long? = null): ChainedConfigurationEditorResult =
        mutateEditor(id, expectedRevision) { session ->
            val row = session.rows.getOrNull(index) ?: throw InvalidEditorRow()
            session.rows.toMutableList().apply { this[index] = row.copy(configuration = row.configuration.named(name)) }
        }

    fun stageEditorRemoval(id: String, index: Int): ChainedConfigurationEditorResult = mutateEditor(id) { session ->
        if (index !in session.rows.indices) throw InvalidEditorRow()
        session.rows.filterIndexed { position, _ -> position != index }
    }

    /** Recovery reset stages delete-all; only the explicit page Save writes anything. */
    fun resetEditor(id: String): ChainedConfigurationEditorResult = mutateEditor(id) { emptyList() }

    private fun mutateEditor(id: String, expectedRevision: Long? = null,
        transform: (EditorSession) -> List<EditorRow>): ChainedConfigurationEditorResult = synchronized(editorLock) {
        val session = editor?.takeIf { it.id == id }
            ?: return@synchronized ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.UNKNOWN_DRAFT)
        if (session.commitInFlight) return@synchronized ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.BUSY)
        if (expectedRevision != null && session.revision != expectedRevision) {
            return@synchronized ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
        }
        try {
            val rows = transform(session)
            validateRows(rows)
            session.rows = rows.toList()
            session.revision++
            ChainedConfigurationEditorResult.Draft(session.snapshot())
        } catch (_: InvalidEditorRow) {
            ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.INVALID_ROW)
        } catch (_: IllegalArgumentException) {
            ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.INVALID_COMPOSITION)
        }
    }

    /** Per-row participation is a whole-selection CAS and never replaces a disabled row's key. */
    suspend fun setRowEnabled(index: Int, value: Boolean, expectedGeneration: Long): ChainedConfigurationEditorCommitResult {
        synchronized(editorLock) {
            if (editor != null || rowChangeInFlight) return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.BUSY)
            rowChangeInFlight = true
        }
        try {
            val stored = repository.loadConfiguration() ?: return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.NOT_CONFIGURED)
            if (stored.identity.generation != expectedGeneration || stored.pendingImportId != null) {
                return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
            }
            val original = stored.configuration.orderedHops.map { it.withoutEntryHop() }
            if (index !in original.indices) return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.INVALID_ROW)
            val wasEnabled = loadEnabled()
            val rows = original.mapIndexed { position, row ->
                ChainedUpstreamSelectionRow(row.enabled(if (position == index) value else wasEnabled && row.isEnabled), position)
            }
            try { if (rows.size == 2) rows.last().configuration.withEntryHop(rows.first().configuration) }
            catch (_: IllegalArgumentException) { return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.INVALID_COMPOSITION) }
            val wantsEnabled = rows.any { it.configuration.isEnabled }
            // OFF is durable before removing the final active row. ON follows the coherent row
            // publication, so a failed preference write cannot activate a partial selection.
            if (wasEnabled && !wantsEnabled && enabledPreference?.setEnabled(false) == false) {
                return ChainedConfigurationEditorCommitResult.Failed("Chained preference write failed")
            }
            val outcome = selectionResult(repository.saveSelection(rows, ChainedUpstreamSaveExpectation.Generation(expectedGeneration)))
            if (outcome is ChainedConfigurationEditorCommitResult.Committed && rows.any {
                    it.configuration.isEnabled && it.configuration.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL
                }) {
                val saved = try { onFullTunnelCommitted(outcome.identity.generation) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (!saved) return ChainedConfigurationEditorCommitResult.Failed("Chained fallback preference write failed", outcome.identity)
            }
            if (outcome is ChainedConfigurationEditorCommitResult.Committed && !wasEnabled && wantsEnabled &&
                enabledPreference?.setEnabled(true) == false) {
                return ChainedConfigurationEditorCommitResult.Failed("Chained preference write failed", outcome.identity)
            }
            return outcome
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return ChainedConfigurationEditorCommitResult.Failed("Chained configuration save failed") }
        finally { synchronized(editorLock) { rowChangeInFlight = false } }
    }

    /** Chain-only consent. Its mutation cannot interleave with a profile commit that clears it. */
    suspend fun setDnsFallbackEnabled(value: Boolean): ChainedConfigurationToggleResult {
        synchronized(editorLock) {
            if (rowChangeInFlight || editor?.commitInFlight == true) {
                return ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.BUSY)
            }
            rowChangeInFlight = true
        }
        try {
            val stored = repository.loadConfiguration()
                ?: return ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.NOT_CONFIGURED)
            if (value && (stored.pendingImportId != null || stored.configuration.containsFullTunnel)) {
                return ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.INVALID_COMPOSITION)
            }
            val sink = dnsFallbackPreference
                ?: return ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.UNSUPPORTED_TOGGLE)
            return if (sink.setEnabled(value)) ChainedConfigurationToggleResult.Applied else ChainedConfigurationToggleResult.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return ChainedConfigurationToggleResult.Failed }
        finally { synchronized(editorLock) { rowChangeInFlight = false } }
    }

    suspend fun commitEditor(
        id: String,
        forceNewGeneration: Boolean = false,
        isCurrent: (() -> Boolean)? = null,
    ): ChainedConfigurationEditorCommitResult {
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val session = synchronized(editorLock) {
            val current = editor?.takeIf { it.id == id }
                ?: return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.UNKNOWN_DRAFT)
            if (isCurrent?.invoke() == false) return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
            if (current.commitInFlight || rowChangeInFlight) return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.BUSY)
            current.commitInFlight = true
            current
        }
        val revision = java.util.concurrent.atomic.AtomicLong(session.revision)
        val canPublish = isCurrent?.let { current -> {
            caller?.isActive != false && current() && synchronized(editorLock) {
                editor === session && session.commitInFlight && session.revision == revision.get()
            }
        } }
        fun stale() = ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
        fun rebase(identity: ChainedUpstreamIdentity) = synchronized(editorLock) {
            if (editor === session && session.revision == revision.get()) {
                session.rebase(identity.generation)
                revision.set(session.revision)
            }
        }
        fun retired(identity: ChainedUpstreamIdentity?, removedGeneration: Long? = null) =
            ChainedConfigurationEditorCommitResult.Failed("Native VPN mutation authority retired", identity, removedGeneration)
        val leases = mutableListOf<ChainedConfigurationKeyMaterial>()
        var committed = false
        try {
            if (canPublish?.invoke() == false) return stale()
            val stored = repository.loadConfiguration() ?: return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.NOT_CONFIGURED)
            if (canPublish?.invoke() == false) return stale()
            if (stored.identity.generation != session.generation || stored.pendingImportId != null && stored.pendingImportId != id) {
                return stale()
            }
            if (session.rows.none { it.configuration.isEnabled }) {
                val enabled = loadEnabled()
                if (canPublish?.invoke() == false) return stale()
                if (enabled) {
                    val disabled = if (canPublish == null) enabledPreference?.setEnabled(false)
                        else enabledPreference?.setEnabledWhileCurrent(false, canPublish) ?: false
                    if (canPublish?.invoke() == false) return stale()
                    if (disabled == false) return ChainedConfigurationEditorCommitResult.Failed("Chained preference write failed")
                }
            }
            val outcome = if (session.rows.isEmpty()) {
                val removed = if (canPublish == null) repository.removeIfGeneration(session.generation)
                    else repository.removeIfGenerationWhileCurrent(session.generation, canPublish)
                when (removed) {
                    ChainedUpstreamPendingRemovalResult.Removed -> ChainedConfigurationEditorCommitResult.Removed
                    ChainedUpstreamPendingRemovalResult.Superseded -> stale()
                    ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed -> ChainedConfigurationEditorCommitResult.AuthoritativeReadFailed
                    is ChainedUpstreamPendingRemovalResult.Failed -> ChainedConfigurationEditorCommitResult.Failed(removed.message)
                }
            } else if (!session.hasProfileChanges) {
                if (!session.awaitingSettings && (forceNewGeneration || stored.pendingImportId != null))
                    commitUnchangedRotation(session, stored, canPublish)
                else ChainedConfigurationEditorCommitResult.Committed(stored.identity)
            } else {
                if (canPublish?.invoke() == false) return stale()
                val rows = session.rows.map { row ->
                    val rotation = row.review?.keyMaterial?.let { material ->
                        val value = material.consume() ?: return ChainedConfigurationEditorCommitResult.Failed("Chained configuration was already saved")
                        leases += material
                        value
                    }
                    ChainedUpstreamSelectionRow(row.configuration, row.originalIndex, rotation)
                }
                selectionResult(if (canPublish == null)
                    repository.saveSelection(rows, ChainedUpstreamSaveExpectation.Generation(session.generation))
                else repository.saveSelectionWhileCurrent(rows, ChainedUpstreamSaveExpectation.Generation(session.generation), canPublish))
            }
            committed = outcome is ChainedConfigurationEditorCommitResult.Committed || outcome is ChainedConfigurationEditorCommitResult.Removed
            if (!committed) return outcome
            val identity = (outcome as? ChainedConfigurationEditorCommitResult.Committed)?.identity
            val removedGeneration = session.generation.takeIf { outcome is ChainedConfigurationEditorCommitResult.Removed }
            if (canPublish?.invoke() == false) {
                // The guarded repository already published while admitted. Preserve its exact
                // successor/key lease, but a retired request cannot claim an authorized completion.
                if (identity != null) rebase(identity)
                return retired(identity, removedGeneration)
            }
            if (identity != null && session.rows.any {
                    it.configuration.isEnabled && it.configuration.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL
                }) {
                // Profiles committed before settings. Rebase only this editor; the caller's
                // authorization witness never refreshes merely because our native revision advances.
                rebase(identity)
                val settingsSaved = try {
                    if (canPublish == null) onFullTunnelCommitted(identity.generation)
                    else if (!canPublish()) false
                    else onFullTunnelCommittedWhileCurrent?.invoke(identity.generation, canPublish) ?: false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (canPublish?.invoke() == false) return retired(identity)
                if (!settingsSaved) return ChainedConfigurationEditorCommitResult.Failed(
                    "Chained fallback preference write failed", identity,
                )
            }
            val completed = synchronized(editorLock) {
                if (canPublish?.invoke() == false) false
                else { if (editor === session) editor = null; true }
            }
            if (!completed) {
                if (identity != null) rebase(identity)
                return retired(identity, removedGeneration)
            }
            return outcome
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return ChainedConfigurationEditorCommitResult.Failed("Chained configuration save failed") }
        finally {
            if (!committed) leases.forEach { it.release() }
            synchronized(editorLock) { session.commitInFlight = false }
        }
    }

    private fun selectionResult(result: ChainedUpstreamSaveResult): ChainedConfigurationEditorCommitResult = when (result) {
        is ChainedUpstreamSaveResult.Committed -> ChainedConfigurationEditorCommitResult.Committed(result.identity)
        is ChainedUpstreamSaveResult.Rejected -> ChainedConfigurationEditorCommitResult.Rejected(result.failure)
        ChainedUpstreamSaveResult.Superseded -> ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
        ChainedUpstreamSaveResult.AuthoritativeReadFailed -> ChainedConfigurationEditorCommitResult.AuthoritativeReadFailed
    }

    private fun validateRows(rows: List<EditorRow>) {
        require(rows.size <= 2)
        if (rows.size == 2) rows.last().configuration.withEntryHop(rows.first().configuration)
    }

    private class InvalidEditorRow : IllegalArgumentException()

    /**
     * A pending first import must have a durable Save boundary even when its row is unchanged.
     * Rotate the same metadata and secret pair through the ordinary repository CAS, so a restart
     * can distinguish accepted generation N+1 from the pending imported generation N. A failed
     * read or save leaves the editor open and the original pair intact for retry/cancellation.
     */
    private suspend fun commitUnchangedRotation(
        session: EditorSession,
        stored: StoredChainedUpstreamConfiguration,
        isCurrent: (() -> Boolean)?,
    ): ChainedConfigurationEditorCommitResult {
        if (isCurrent?.invoke() == false) return ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION)
        if (stored.configuration.orderedHops.size > 1) {
            val rows = session.rows.map { ChainedUpstreamSelectionRow(it.configuration, it.originalIndex) }
            return selectionResult(if (isCurrent == null)
                repository.saveSelection(rows, ChainedUpstreamSaveExpectation.Generation(session.generation))
            else repository.saveSelectionWhileCurrent(rows, ChainedUpstreamSaveExpectation.Generation(session.generation), isCurrent))
        }
        var secrets: ChainedUpstreamSecretMaterial? = null
        var privateKey: ByteArray? = null
        var presharedKey: ByteArray? = null
        return try {
            secrets = repository.loadSecrets()
                ?: return ChainedConfigurationEditorCommitResult.Failed("Chained configuration key is unavailable")
            if (isCurrent?.invoke() == false || secrets.generation != session.generation) {
                return ChainedConfigurationEditorCommitResult.Refused(
                    ChainedConfigurationEditorRefusal.STALE_GENERATION,
                )
            }
            privateKey = secrets.privateKeyCopy()
            presharedKey = secrets.presharedKeyCopy()
            val rotation = ChainedUpstreamRotation.create(
                configuration = stored.configuration,
                privateKey = Base64.getEncoder().encodeToString(privateKey),
                presharedKey = presharedKey?.let { Base64.getEncoder().encodeToString(it) },
            )
            val saved = if (isCurrent == null) repository.save(rotation, ChainedUpstreamSaveExpectation.Generation(session.generation))
                else repository.saveWhileCurrent(rotation, ChainedUpstreamSaveExpectation.Generation(session.generation), isCurrent)
            when (val result = saved) {
                is ChainedUpstreamSaveResult.Committed -> {
                    ChainedConfigurationEditorCommitResult.Committed(result.identity)
                }
                is ChainedUpstreamSaveResult.Rejected ->
                    ChainedConfigurationEditorCommitResult.Rejected(result.failure)
                ChainedUpstreamSaveResult.Superseded -> ChainedConfigurationEditorCommitResult.Refused(
                    ChainedConfigurationEditorRefusal.STALE_GENERATION,
                )
                ChainedUpstreamSaveResult.AuthoritativeReadFailed ->
                    ChainedConfigurationEditorCommitResult.AuthoritativeReadFailed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ChainedConfigurationEditorCommitResult.Failed(
                failure.safeMessage("Chained configuration save failed"),
            )
        } finally {
            privateKey?.fill(0)
            presharedKey?.fill(0)
            secrets?.close()
        }
    }

    /**
     * `vpn.toggle`. `enabled` commits the durable preference through [enabledPreference]; `setup`
     * and `fallback` have no Android single-rotation meaning (the setup gate is derived from the
     * stored rotation, and the DNS fallback is a resolver-owner setting) and are refused with the
     * stable unsupported-toggle code rather than silently reporting success.
     */
    suspend fun toggle(
        key: ChainedConfigurationToggleKey,
        value: Boolean,
    ): ChainedConfigurationToggleResult = when (key) {
        ChainedConfigurationToggleKey.ENABLED -> when (val sink = enabledPreference) {
            null -> ChainedConfigurationToggleResult.Refused(
                ChainedConfigurationEditorRefusal.UNSUPPORTED_TOGGLE,
            )
            else -> if (sink.setEnabled(value)) {
                ChainedConfigurationToggleResult.Applied
            } else {
                ChainedConfigurationToggleResult.Failed
            }
        }
        ChainedConfigurationToggleKey.SETUP,
        ChainedConfigurationToggleKey.FALLBACK,
        -> ChainedConfigurationToggleResult.Refused(
            ChainedConfigurationEditorRefusal.UNSUPPORTED_TOGGLE,
        )
    }

    private fun clearEditor(expected: EditorSession) {
        synchronized(editorLock) {
            if (editor === expected) editor = null
        }
    }

    private data class EditorRow(val originalIndex: Int?, val configuration: ChainedUpstreamConfiguration,
        val review: ChainedConfigurationReview.Ready? = null)

    /** Saved keys are referenced by original index; newly supplied credentials stay opaque. */
    private class EditorSession(val id: String, var generation: Long, var originalRows: List<ChainedUpstreamConfiguration>) {
        var rows: List<EditorRow> = originalRows.mapIndexed { index, row -> EditorRow(index, row) }
        var revision = 0L
        var commitInFlight = false
        var awaitingSettings = false
        fun rebase(committedGeneration: Long) {
            generation = committedGeneration
            originalRows = rows.map { it.configuration }
            rows = originalRows.mapIndexed { index, row -> EditorRow(index, row) }
            awaitingSettings = true
            revision++
        }
        val hasChanges: Boolean get() = awaitingSettings || hasProfileChanges
        val hasProfileChanges: Boolean get() = rows.any { it.review != null } || rows.map { it.configuration } != originalRows ||
            rows.map { it.originalIndex } != originalRows.indices.toList()

        fun snapshot() = ChainedConfigurationDraft(id, generation, revision, hasChanges,
            rows.any { it.configuration.isEnabled && it.configuration.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL },
            rows.isEmpty(), rows.map { row -> ChainedConfigurationDraftRow(
                row.configuration.displayName.ifEmpty { "${row.configuration.endpointHost}:${row.configuration.endpointPort}" },
                if (row.configuration.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL) "Full tunnel" else "Split tunnel",
                row.configuration.isEnabled) })
    }

}

private fun Exception.safeMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback
