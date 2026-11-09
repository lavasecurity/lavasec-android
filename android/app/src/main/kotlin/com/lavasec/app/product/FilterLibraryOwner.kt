package com.lavasec.app.product

import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CustomBlocklistEditResult
import com.lavasec.app.foundation.filter.CustomBlocklistEditor
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.foundation.share.ShareableCustomBlocklistSource
import com.lavasec.app.foundation.filter.FilterDraftPolicy
import com.lavasec.app.foundation.filter.FilterIdentityPolicy
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterLibraryEditResult
import com.lavasec.app.foundation.filter.FilterLibraryEditSession
import com.lavasec.app.foundation.filter.FilterLibraryEditor
import com.lavasec.app.foundation.filter.FilterLibraryPolicy
import com.lavasec.app.foundation.filter.FilterLibraryRejection
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.filter.ManualRuleEditor
import com.lavasec.app.foundation.filter.ManualRuleTarget
import com.lavasec.app.foundation.share.ShareableFilterConfiguration
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.platform.FilterMutationFailure
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One durable filter-library mutation committed through the shared coordinator.
 *
 * [Applied] means the desired library became authoritative (the coordinator adopted it and
 * the runtime reload, if one was due, ran its best-effort path). [Rejected] carries the exact
 * editor/owner refusal. [Failed] guarantees the previous library is still authoritative.
 * [Superseded] means another mutation (a switch, a domain review, another edit) landed first.
 * [ProtectionUnavailable] wedges this generation's coordinator and requires reconstruction.
 */
sealed interface FilterLibraryMutationResult {
    data class Applied(val generation: Long) : FilterLibraryMutationResult

    data class Rejected(val reason: FilterLibraryRejection) : FilterLibraryMutationResult

    data class Failed(val failure: FilterMutationFailure) : FilterLibraryMutationResult

    /** One custom blocklist's fetch failed; [sourceName] is the user's label, never the URL. */
    data class FailedSource(
        val sourceId: String,
        val sourceName: String?,
        val failure: CustomBlocklistSourceFailure,
    ) : FilterLibraryMutationResult

    data object Superseded : FilterLibraryMutationResult

    data object ProtectionUnavailable : FilterLibraryMutationResult
}

/** Immutable review baseline captured from one confirmed coordinator state. */
data class FilterLibraryReviewBasis(val library: FilterLibraryDraft, val generation: Long)

/**
 * The coordinator-backed capability the owner drives. [currentLibrary] reads the coordinator's
 * last verified state (never a stale snapshot captured at owner creation); [apply] commits one
 * exact desired library through the generation-fenced coordinator. [restoreDefaults] is a
 * separate port because the seeded defaults come from the authority's signed catalog — the
 * owner must not see catalog derivation policy.
 */
interface FilterLibraryPort {
    fun currentLibrary(): FilterLibraryDraft

    /** One coherent read of the durable library and its publication generation. */
    fun reviewBasis(): FilterLibraryReviewBasis? = null

    suspend fun apply(desired: FilterLibraryDraft, admission: FeatureLimitAdmission): FilterLibraryMutationResult

    /** Bind expected configuration and publication generation atomically; unsupported ports refuse. */
    suspend fun applyReviewed(
        desired: FilterLibraryDraft,
        expected: FilterLibraryReviewBasis,
        admission: FeatureLimitAdmission,
    ): FilterLibraryMutationResult = FilterLibraryMutationResult.Superseded

    /** Only FilterLibraryOwner's exact successful replacement receipt may use this compensation
     * port. The native adapter still compares the library and generation at publication. */
    suspend fun applyRetainedReplacement(
        desired: FilterLibraryDraft,
        expected: FilterLibraryReviewBasis,
        admission: FeatureLimitAdmission,
    ): FilterLibraryMutationResult = FilterLibraryMutationResult.Superseded

    /** Optional native QA visit fence; implementations must carry it to their final write. */
    suspend fun applyReviewedWhileCurrent(
        desired: FilterLibraryDraft,
        expected: FilterLibraryReviewBasis,
        admission: FeatureLimitAdmission,
        isCurrent: () -> Boolean,
    ): FilterLibraryMutationResult = FilterLibraryMutationResult.Superseded

    /** Typed policy-accepted resolver target, with its exact final native store guard. */
    suspend fun applyReviewedForQaComposite(
        desired: FilterLibraryDraft,
        expected: FilterLibraryReviewBasis,
        admission: FeatureLimitAdmission,
        isCurrent: () -> Boolean,
        publicationGuard: com.lavasec.app.platform.NativeFilterPublicationGuard,
    ): FilterLibraryMutationResult = FilterLibraryMutationResult.Superseded

    suspend fun restoreDefaults(admission: FeatureLimitAdmission): FilterLibraryMutationResult
}

/** One admitted (or retried) filter-library mutation, kept for the failure surface and retry. */
sealed interface FilterLibraryOperation {
    data class CommitDraft(val filterId: String, val review: FilterLibraryOwner.ReviewedDraft? = null) : FilterLibraryOperation

    data class CreateFilter(val name: String, val duplicateFromId: String?) : FilterLibraryOperation

    data class RenameFilter(val filterId: String, val name: String, val emoji: String? = null) : FilterLibraryOperation

    data class DeleteFilters(val filterIds: Set<String>) : FilterLibraryOperation

    /**
     * One whole-library batch committed from a [FilterLibraryEditSession] (canonical
     * `library.form` review/confirm). Carries the already-validated desired library so a
     * retry re-publishes the exact reviewed batch, never a re-derived one.
     */
    data class CommitLibraryEdit(val library: FilterLibraryDraft) : FilterLibraryOperation

    /**
     * One confirmed share/import write (canonical `FilterShareImportConfirmation`). The
     * configuration was already reconciled and validated by the share/import owner, so a retry
     * re-publishes the exact reviewed content, never a re-derived one. [targetFilterId] is null
     * for add-as-new and the replaced filter id otherwise.
     */
    data class CommitImported(
        val configuration: ShareableFilterConfiguration,
        val targetFilterId: String?,
    ) : FilterLibraryOperation

    data object RestoreDefaults : FilterLibraryOperation
}

/** Stable command-layer refusals for the canonical `library.*` whole-library edit session. */
enum class FilterLibraryEditRejection {
    NOT_EDITING,
    UNKNOWN_FILTER,
    ACTIVE_FILTER,
    FROZEN_FILTER,
    HAS_CHANGES,
    NO_CHANGES,
    LIMIT_REACHED,
    INVALID_NAME,
    INVALID_EMOJI,
    STALE_STAGING,
    COMMIT_FAILED,
}

/** The typed result of one canonical `library.*` command. */
sealed interface FilterLibraryEditOutcome {
    /** `library.form create` staged a new addition; [filterId] is its session-local identity. */
    data class Created(val filterId: String) : FilterLibraryEditOutcome

    data object Completed : FilterLibraryEditOutcome

    data class Rejected(val reason: FilterLibraryEditRejection) : FilterLibraryEditOutcome
}

/**
 * Lifecycle-fenced owner for every saved-filter-library mutation and the per-filter drafts.
 *
 * All durable mutations (draft commit, create, rename, staged delete, restore defaults) run
 * through the same generation-fenced coordinator as Apply and domain reviews, so a library edit
 * is one verified compile/publish — the previous active artifact is retained on any failure,
 * and a failed retry can never duplicate a write or leak a stale generation (the admission
 * epoch fence plus the coordinator's generation CAS are the only publishers).
 *
 * Drafts are independent by filter id: a draft belongs to exactly one filter, survives an
 * active-filter switch (which only moves `activeProfileId`), and commits back into its own
 * filter — switching filters can never save a draft into the newly active filter. A committed
 * draft is dropped; a filter deletion or restore-defaults drops the affected drafts.
 */
class FilterLibraryOwner(
    private val port: FilterLibraryPort,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val newFilterId: () -> String = { "filter-${UUID.randomUUID()}" },
    private val limits: () -> FeatureLimits = { FeatureLimits.FREE },
    private val captureAdmission: () -> FeatureLimitAdmission? = { FeatureLimitAdmission(limits()) },
    private val creationName: () -> String = { "Untitled" },
) : AutoCloseable {
    sealed interface State {
        data object Idle : State

        data class Mutating(
            val operation: FilterLibraryOperation,
            val filterId: String?,
        ) : State

        data class Failed(
            val operation: FilterLibraryOperation,
            val filterId: String?,
            val failure: Failure,
        ) : State

        /**
         * The operation's durable outcome landed. Sticky until the next admission so the
         * preparation cover can render Success even when the applying caller was cancelled
         * (the detached settle still publishes it).
         */
        data class Succeeded(
            val operation: FilterLibraryOperation,
            val filterId: String?,
        ) : State
    }

    sealed interface Failure {
        /** The exact editor/owner refusal; the UI shows its precise copy. */
        data class Rejected(val reason: FilterLibraryRejection) : Failure

        /** A compile/publish failure, supersession, or reconstruction wedge; nothing landed. */
        data object Commit : Failure

        /** A custom blocklist fetch failed; the owner names the source and the stable class. */
        data class Source(
            val sourceId: String,
            val sourceName: String?,
            val failure: CustomBlocklistSourceFailure,
        ) : Failure
    }

    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + dispatcher + ownerJob)
    private val admission = Mutex()
    private data class ReplacementReceipt(
        val before: FilterLibraryDraft,
        val after: FilterLibraryDraft,
        val afterGeneration: Long,
    )
    @Volatile private var replacementReceipt: ReplacementReceipt? = null

    private val publicationEpoch = AtomicLong(0L)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private val mutableDrafts = MutableStateFlow<Map<String, FilterProfileDraft>>(emptyMap())
    private val mutableDraftLeases = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutableNewFilter = MutableStateFlow<FilterProfileDraft?>(null)
    private val mutableEditSession = MutableStateFlow(FilterLibraryEditSession())
    private val mutableReconstructionRequired = MutableStateFlow(false)
    private var lastOperation: FilterLibraryOperation? = null

    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Per-filter drafts keyed by filter id. The VM derives the detail/review surface from this
     * flow; nothing in a composable ever mutates it.
     */
    val drafts: StateFlow<Map<String, FilterProfileDraft>> = mutableDrafts.asStateFlow()

    /** Editor-only identity. It never enters the saved library, active selection or backup. */
    val newFilter: StateFlow<FilterProfileDraft?> = mutableNewFilter.asStateFlow()

    fun isCreating(filterId: String): Boolean = mutableNewFilter.value?.id == filterId

    /** Includes unchanged rule editors, the unsaved identity, and an open library session. */
    fun hasOpenDrafts(): Boolean = mutableDrafts.value.isNotEmpty() ||
        mutableNewFilter.value != null || mutableEditSession.value.isEditing

    /**
     * Sticky once a [FilterLibraryMutationResult.ProtectionUnavailable] settles: the shared
     * coordinator cannot recover in this generation, so the product host rotates the credential
     * graph once per generation (same contract as the switch owner's wedge).
     */
    val reconstructionRequired: StateFlow<Boolean> = mutableReconstructionRequired.asStateFlow()

    /**
     * The canonical whole-library edit session (canonical `library.*`). It is the one owner of
     * the staged rename/emoji/deletion/addition batch; the saved library is written only when
     * [commitLibraryEdit] confirms the whole validated batch. Read-only projection for the
     * bridge snapshot (`libraryEditing` family).
     */
    val editSession: StateFlow<FilterLibraryEditSession> = mutableEditSession.asStateFlow()

    /** True while the active filter has a draft — the domain-action surface refuses edits. */
    fun hasUnsavedActiveFilterDraft(activeFilterId: String): Boolean =
        mutableDrafts.value.containsKey(activeFilterId)

    fun draftFor(filterId: String): FilterProfileDraft? = mutableDrafts.value[filterId]

    /** Stable across edits, retired after close/apply, and fresh when the same saved ID reopens. */
    fun draftLease(filterId: String): String? = mutableDraftLeases.value[filterId]
        ?.takeIf { mutableDrafts.value.containsKey(filterId) }

    /**
     * The live confirmed library of this owner's coordinator (never a cached snapshot captured at
     * construction). The reviewed backup-restore apply reads its checkpoint from the same authority
     * it later commits to.
     */
    fun currentLibrary(): FilterLibraryDraft = port.currentLibrary()

    /** The review gate for one draft against the current library, pure policy facts for the UI. */
    fun reviewRejectionFor(filterId: String): com.lavasec.app.foundation.filter.FilterDraftReviewRejection? {
        val draft = mutableDrafts.value[filterId] ?: return null
        val library = port.currentLibrary()
        mutableNewFilter.value?.takeIf { it.id == filterId }?.let { identity ->
            return FilterDraftPolicy.creationRejection(library, identity, draft, limits())
        }
        val saved = library.profile(filterId) ?: return null
        val currentLimits = limits()
        return FilterDraftPolicy.reviewRejection(
            library = library,
            draft = draft,
            saved = saved,
            maxFilters = currentLimits.maxFilters,
            limits = currentLimits,
        )
    }

    /** Native-only witness for the filter and resolver fields currently represented on Android. */
    class ReviewedDraft internal constructor(
        internal val owner: FilterLibraryOwner,
        val filterId: String,
        internal val baseline: FilterLibraryReviewBasis,
        internal val draft: FilterProfileDraft,
        internal val activeFilterId: String,
        internal val newIdentity: FilterProfileDraft? = null,
    )

    /** A typed review refusal never grants a review token or permits a durable write. */
    sealed interface ReviewAttempt {
        data class Captured(val review: ReviewedDraft) : ReviewAttempt
        data class Rejected(val reason: com.lavasec.app.foundation.filter.FilterDraftReviewRejection) : ReviewAttempt
        data object Unavailable : ReviewAttempt
    }

    suspend fun captureReview(filterId: String): ReviewedDraft? =
        (captureReviewAttempt(filterId) as? ReviewAttempt.Captured)?.review

    /** Capacity is discovered under the same lock and exact draft lease as a captured review. */
    suspend fun captureReviewAttempt(
        filterId: String,
        expectedDraft: FilterProfileDraft? = null,
        expectedLease: String? = null,
        isCurrent: (() -> Boolean)? = null,
    ): ReviewAttempt = admission.withLock {
        if (closed.get() || currentCoroutineContext()[Job]?.isActive == false || isCurrent?.invoke() == false)
            return@withLock ReviewAttempt.Unavailable
        val draft = mutableDrafts.value[filterId] ?: return@withLock ReviewAttempt.Unavailable
        if (expectedDraft != null && draft !== expectedDraft ||
            expectedLease != null && mutableDraftLeases.value[filterId] != expectedLease)
            return@withLock ReviewAttempt.Unavailable
        val baseline = port.reviewBasis() ?: return@withLock ReviewAttempt.Unavailable
        val library = baseline.library
        val newIdentity = mutableNewFilter.value?.takeIf { it.id == filterId }
        val currentLimits = limits()
        val rejection = if (newIdentity != null) {
            FilterDraftPolicy.creationRejection(library, newIdentity, draft, currentLimits)
        } else {
            val saved = library.profile(filterId) ?: return@withLock ReviewAttempt.Unavailable
            FilterDraftPolicy.reviewRejection(library, draft, saved, currentLimits.maxFilters, currentLimits)
        }
        if (isCurrent?.invoke() == false) return@withLock ReviewAttempt.Unavailable
        if (rejection != null) ReviewAttempt.Rejected(rejection)
        else ReviewAttempt.Captured(ReviewedDraft(this, filterId, baseline, draft, library.activeProfileId, newIdentity))
    }

    /** Finish an unchanged edit under the same admission lock used by draft mutations. */
    suspend fun discardUnchangedDraft(filterId: String): Boolean = admission.withLock {
        if (closed.get()) return@withLock false
        val draft = mutableDrafts.value[filterId] ?: return@withLock false
        val saved = port.currentLibrary().profile(filterId) ?: return@withLock false
        if (FilterDraftPolicy.diff(saved, draft).hasChanges) return@withLock false
        mutableDrafts.value -= filterId
        mutableDraftLeases.value -= filterId
        true
    }

    /**
     * Whether [filterId] currently holds an in-memory draft that differs from its saved profile.
     * Domain History uses this to refuse staging a domain action onto an active filter that still
     * has unsaved edits (the canonical `hasUnsavedActiveFilterDraft` guard) rather than merging the
     * new rule into — and silently overwriting the intent of — those edits.
     */
    suspend fun hasUnsavedDraft(filterId: String): Boolean = admission.withLock {
        if (closed.get()) return@withLock false
        if (isCreating(filterId)) return@withLock true
        val draft = mutableDrafts.value[filterId] ?: return@withLock false
        val saved = port.currentLibrary().profile(filterId) ?: return@withLock false
        FilterDraftPolicy.diff(saved, draft).hasChanges
    }

    // ---- Draft editing (in-memory, per-filter; never touches the coordinator) ----

    /** Repeated Edit callbacks preserve the retained draft, as canonical filter.edit does. */
    suspend fun beginEdit(filterId: String): Boolean = beginEdit(filterId, null)

    /** A suspended standalone stage cannot open a draft after its original host is retired. */
    suspend fun beginEdit(filterId: String, isCurrent: (() -> Boolean)?): Boolean = admission.withLock {
        if (closed.get() || isCurrent?.invoke() == false) return@withLock false
        if (isCreating(filterId)) return@withLock mutableDrafts.value.containsKey(filterId)
        val current = port.currentLibrary()
        val saved = current.profile(filterId) ?: return@withLock false
        if (FilterLibraryPolicy.isFrozen(current, filterId, limits().maxFilters)) return@withLock false
        if (mutableDrafts.value[filterId] == null) {
            mutableDraftLeases.value += filterId to UUID.randomUUID().toString()
            mutableDrafts.value = mutableDrafts.value + (filterId to saved)
        }
        true
    }

    /** Called only while holding admission, including after a queued mutation acquires it. */
    private fun editableDraft(filterId: String): FilterProfileDraft? {
        if (closed.get()) return null
        val library = port.currentLibrary()
        if (isCreating(filterId)) return mutableDrafts.value[filterId]
        if (library.profile(filterId) == null || FilterLibraryPolicy.isFrozen(library, filterId, limits().maxFilters)) return null
        return mutableDrafts.value[filterId]
    }

    private fun editBaseline(filterId: String): FilterProfileDraft? =
        port.currentLibrary().profile(filterId) ?: mutableNewFilter.value?.takeIf { it.id == filterId }

    suspend fun addDomainToDraft(
        filterId: String,
        rawDomain: String,
        target: ManualRuleTarget,
    ): ManualRuleEditResult? = addDomainToDraft(filterId, rawDomain, target, null, null)

    /** Native-owned staging rechecks its exact draft lease and original host at the write edge. */
    suspend fun addDomainToDraft(
        filterId: String, rawDomain: String, target: ManualRuleTarget,
        expectedLease: String?, isCurrent: (() -> Boolean)?,
    ): ManualRuleEditResult? {
        if (closed.get()) return null
        return admission.withLock {
            if (isCurrent?.invoke() == false ||
                expectedLease != null && mutableDraftLeases.value[filterId] != expectedLease) return null
            val current = editableDraft(filterId) ?: return null
            when (val result = ManualRuleEditor.add(rawDomain, target, current, limits())) {
                is ManualRuleEditResult.Applied -> {
                    mutableDrafts.value = mutableDrafts.value + (filterId to result.profile)
                    result
                }
                is ManualRuleEditResult.Rejected -> result
            }
        }
    }

    suspend fun removeDomainFromDraft(
        filterId: String,
        domain: String,
        target: ManualRuleTarget,
    ): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            val current = editableDraft(filterId) ?: return false
            mutableDrafts.value = mutableDrafts.value + (
                filterId to ManualRuleEditor.remove(domain, target, current)
                )
            true
        }
    }

    /** Domain checker proposals remove covering exceptions and add/remove the target atomically. */
    suspend fun stageDomainCheckInDraft(
        filterId: String, domain: String, action: com.lavasec.app.foundation.filter.DomainCheckRuleAction,
        expectedLease: String, isCurrent: () -> Boolean,
    ): ManualRuleEditResult? = admission.withLock {
        if (closed.get() || !isCurrent() || mutableDraftLeases.value[filterId] != expectedLease) return@withLock null
        val current = editableDraft(filterId) ?: return@withLock null
        when (val result = com.lavasec.app.foundation.filter.DomainCheckRuleEditor.apply(domain, action, current, limits())) {
            is ManualRuleEditResult.Applied -> {
                mutableDrafts.value = mutableDrafts.value + (filterId to result.profile)
                result
            }
            is ManualRuleEditResult.Rejected -> result
        }
    }

    /** One checkbox selection is one atomic draft update, never a partially applied loop. */
    suspend fun replaceSourcesInDraft(filterId: String, sourceIds: Set<String>): Boolean = admission.withLock {
        if (closed.get()) return@withLock false
        val current = editableDraft(filterId) ?: return@withLock false
        mutableDrafts.value = mutableDrafts.value + (filterId to current.withEnabledSourceIds(sourceIds))
        true
    }

    suspend fun toggleSourceInDraft(filterId: String, sourceId: String, enabled: Boolean): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            val current = editableDraft(filterId) ?: return false
            val updated = if (enabled) {
                current.enabledSourceIds + sourceId
            } else {
                current.enabledSourceIds - sourceId
            }
            mutableDrafts.value = mutableDrafts.value + (filterId to current.withEnabledSourceIds(updated))
            true
        }
    }

    /**
     * Restores one domain to the membership it has in the SAVED profile, the Android form of
     * canonical `filter.undoDomain`. The decision being undone is the shelf the draft currently
     * holds the domain on; the exclusive-rule model restores both shelves to the configured state.
     */
    suspend fun undoDomainInDraft(filterId: String, domain: String): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            val current = editableDraft(filterId) ?: return false
            val saved = editBaseline(filterId) ?: return false
            val target = if (domain in current.manualRules.blockedDomains) {
                ManualRuleTarget.BLOCKED
            } else {
                ManualRuleTarget.ALLOWED
            }
            mutableDrafts.value = mutableDrafts.value + (
                filterId to ManualRuleEditor.undo(domain, target, current, saved.manualRules)
                )
            true
        }
    }

    /**
     * Adds (and enables) one custom blocklist in the draft, mirroring iOS
     * `addCustomBlocklistToDraft`: Plus-gated, URL-validated, duplicate-URL/name rejected, and
     * collapsed to the matching curated source when [catalogSourceIdForUrl] recognizes the URL.
     * `null` means there is no editable draft for [filterId]; `Rejected` carries the precise reason.
     */
    suspend fun addCustomBlocklistToDraft(
        filterId: String,
        displayName: String,
        rawUrl: String,
        parseFormat: BlocklistFormat,
        catalogSourceIdForUrl: (String) -> String? = { null },
    ): CustomBlocklistEditResult? = addCustomBlocklistToDraft(filterId, displayName, rawUrl, parseFormat,
        null, catalogSourceIdForUrl)

    suspend fun addCustomBlocklistToDraft(
        filterId: String, displayName: String, rawUrl: String, parseFormat: BlocklistFormat,
        expectedLease: String?, catalogSourceIdForUrl: (String) -> String? = { null },
    ): CustomBlocklistEditResult? {
        if (closed.get()) return null
        return admission.withLock {
            if (expectedLease != null && mutableDraftLeases.value[filterId] != expectedLease) return@withLock null
            val current = editableDraft(filterId) ?: return@withLock null
            val result = CustomBlocklistEditor.add(
                profile = current,
                displayName = displayName,
                rawUrl = rawUrl,
                parseFormat = parseFormat,
                limits = limits(),
                createdAtEpochMillis = System.currentTimeMillis(),
                catalogSourceIdForUrl = catalogSourceIdForUrl,
            )
            if (result is CustomBlocklistEditResult.Applied) {
                mutableDrafts.value = mutableDrafts.value + (filterId to result.profile)
            }
            result
        }
    }

    /** Removes one custom blocklist from the draft, mirroring iOS `deleteCustomBlocklistFromDraft`. */
    suspend fun deleteCustomBlocklistFromDraft(filterId: String, sourceId: String): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            val current = editableDraft(filterId) ?: return@withLock false
            mutableDrafts.value = mutableDrafts.value + (
                filterId to CustomBlocklistEditor.delete(current, sourceId)
                )
            true
        }
    }

    /** Restores one custom blocklist to the SAVED profile, mirroring iOS `undoBlocklistDraftChange`. */
    suspend fun undoCustomBlocklistInDraft(filterId: String, sourceId: String): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            val current = editableDraft(filterId) ?: return@withLock false
            val saved = editBaseline(filterId) ?: return@withLock false
            mutableDrafts.value = mutableDrafts.value + (
                filterId to CustomBlocklistEditor.undo(current, sourceId, saved)
                )
            true
        }
    }

    /** Restores one source's enabled state to the SAVED profile, canonical `filter.undoList`. */
    suspend fun undoSourceInDraft(filterId: String, sourceId: String): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            val current = editableDraft(filterId) ?: return false
            val saved = editBaseline(filterId) ?: return false
            val updated = if (sourceId in saved.enabledSourceIds) {
                current.enabledSourceIds + sourceId
            } else {
                current.enabledSourceIds - sourceId
            }
            mutableDrafts.value = mutableDrafts.value + (filterId to current.withEnabledSourceIds(updated))
            true
        }
    }

    suspend fun discardDraft(filterId: String) {
        discardDraft(filterId, null, null)
    }

    /** Token-owned teardown never discards a replacement draft while awaiting admission. */
    suspend fun discardDraft(
        filterId: String, expectedDraft: FilterProfileDraft?, expectedNewFilter: FilterProfileDraft?,
        expectedLease: String? = null, isCurrent: (() -> Boolean)? = null,
    ): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            if (closed.get() || isCurrent?.invoke() == false) return@withLock false
            if (expectedLease != null && mutableDraftLeases.value[filterId] != expectedLease) return@withLock false
            if (expectedDraft != null && mutableDrafts.value[filterId] !== expectedDraft ||
                expectedNewFilter != null && mutableNewFilter.value !== expectedNewFilter) return@withLock false
            mutableDrafts.value = mutableDrafts.value - filterId
            mutableDraftLeases.value -= filterId
            if (isCreating(filterId)) mutableNewFilter.value = null
            true
        }
    }

    // ---- Durable library mutations ----

    suspend fun commitDraft(filterId: String, review: ReviewedDraft? = null): Boolean =
        commitDraft(filterId, review, null)

    /** Retry only the failed reviewed operation that the caller actually admitted. */
    suspend fun retryReviewed(failure: State.Failed): Boolean {
        if (failure.failure !is Failure.Commit && failure.failure !is Failure.Source) return false
        val operation = failure.operation as? FilterLibraryOperation.CommitDraft ?: return false
        val review = operation.review ?: return false
        if (review.owner !== this || review.filterId != operation.filterId) return false
        return commitDraft(operation.filterId, review, failure)
    }

    private suspend fun commitDraft(filterId: String, review: ReviewedDraft?, retryOf: State.Failed?): Boolean = admit(
        operation = FilterLibraryOperation.CommitDraft(filterId, review),
        filterId = filterId,
        expectedState = retryOf,
    ) { admitted ->
        val draft = mutableDrafts.value[filterId]
            ?: return@admit FilterLibraryMutationResult.Rejected(FilterLibraryRejection.UNKNOWN_FILTER)
        val basis = if (review != null) port.reviewBasis() else null
        val current = if (review != null) basis?.library ?: return@admit FilterLibraryMutationResult.Superseded
            else port.currentLibrary()
        val creating = mutableNewFilter.value?.takeIf { it.id == filterId }
        // First save requires the exact owned identity, rules and atomic library basis. Neither
        // an unreviewed call nor a review from a replaced/cancelled creation may publish it.
        if (creating != null || review?.newIdentity != null) {
            if (creating == null || review == null || review.owner !== this || review.filterId != filterId ||
                review.newIdentity !== creating || review.draft != draft || review.baseline != basis ||
                review.activeFilterId != current.activeProfileId) return@admit FilterLibraryMutationResult.Superseded
            val rejection = FilterDraftPolicy.creationRejection(current, creating, draft, admitted.limits)
            if (rejection != null) return@admit FilterLibraryMutationResult.Rejected(creationLibraryRejection(rejection))
            val profile = FilterProfileDraft(
                id = creating.id, name = creating.name, protectionLevel = null,
                enabledSourceIds = draft.enabledSourceIds, manualRules = draft.manualRules,
                emoji = creating.emoji, customBlocklists = draft.customBlocklists,
            )
            return@admit port.applyReviewed(current.withProfiles(current.profiles + profile), checkNotNull(basis), admitted)
        }
        if (review != null && (review.owner !== this || review.filterId != filterId ||
                review.baseline != basis ||
                FilterDraftPolicy.diff(review.draft, draft).hasChanges ||
                review.activeFilterId != current.activeProfileId)) {
            return@admit FilterLibraryMutationResult.Superseded
        }
        val currentLimits = admitted.limits
        val savedRules = current.profile(filterId)?.manualRules
        val retainsOnlySavedRules = draft.id == filterId && savedRules != null && draft.manualRules.isSubsetOf(savedRules)
        // The review gate re-verified here: a stale/gone/frozen target refuses before any
        // compile so the user gets the precise rejection instead of a compile failure.
        when {
            current.profile(filterId) == null ->
                FilterLibraryMutationResult.Rejected(FilterLibraryRejection.UNKNOWN_FILTER)
            FilterLibraryPolicy.isFrozen(current, filterId, currentLimits.maxFilters) ->
                FilterLibraryMutationResult.Rejected(FilterLibraryRejection.FROZEN_FILTER)
            !retainsOnlySavedRules && draft.manualRules.blockedDomains.size > currentLimits.maxBlockedDomains ->
                FilterLibraryMutationResult.Rejected(FilterLibraryRejection.BLOCKED_DOMAIN_LIMIT)
            !retainsOnlySavedRules && draft.manualRules.allowedDomains.size > currentLimits.maxAllowedDomains ->
                FilterLibraryMutationResult.Rejected(FilterLibraryRejection.ALLOWED_DOMAIN_LIMIT)
            else -> {
                // Merge only the draftable fields into the saved profile. The draft's name is
                // frozen at begin-edit time; a rename committed in the library after the draft
                // was created must survive (wholesale replacement would silently revert it or
                // fail the unique-name invariant if another filter took the old name).
                val saved = checkNotNull(current.profile(filterId))
                val desired = current.withProfile(
                    FilterProfileDraft(
                        id = saved.id,
                        name = saved.name,
                        protectionLevel = saved.protectionLevel,
                        enabledSourceIds = draft.enabledSourceIds,
                        manualRules = draft.manualRules,
                        emoji = saved.emoji,
                        customBlocklists = draft.customBlocklists,
                    ),
                )
                if (review == null) port.apply(desired, admitted)
                else port.applyReviewed(desired, checkNotNull(basis), admitted)
            }
        }
    }

    suspend fun createFilter(name: String, duplicateFromId: String?): Boolean = admit(
        operation = FilterLibraryOperation.CreateFilter(name, duplicateFromId),
        filterId = null,
    ) { admitted ->
        val result = FilterLibraryEditor.create(
            library = port.currentLibrary(),
            name = name,
            duplicateFromId = duplicateFromId,
            maxFilters = admitted.limits.maxFilters,
            newId = newFilterId(),
        )
        when (result) {
            is FilterLibraryEditResult.Rejected ->
                FilterLibraryMutationResult.Rejected(result.reason)
            is FilterLibraryEditResult.Applied -> port.apply(result.library, admitted)
        }
    }

    suspend fun renameFilter(
        filterId: String, name: String, emoji: String? = null,
        expectedDraft: FilterProfileDraft? = null, expectedNewFilter: FilterProfileDraft? = null,
        isCurrent: (() -> Boolean)? = null,
    ): Boolean {
        val caller = currentCoroutineContext()[Job]
        val canPublish = isCurrent?.let { current -> {
            caller?.isActive != false && !closed.get() && current() &&
                (expectedDraft == null || mutableDrafts.value[filterId] === expectedDraft) &&
                (expectedNewFilter == null || mutableNewFilter.value === expectedNewFilter)
        } }
        if (isCreating(filterId)) return admission.withLock {
            if (closed.get() || canPublish?.invoke() == false) return@withLock false
            if (expectedDraft != null && mutableDrafts.value[filterId] !== expectedDraft ||
                expectedNewFilter != null && mutableNewFilter.value !== expectedNewFilter) return@withLock false
            val identity = mutableNewFilter.value?.takeIf { it.id == filterId } ?: return@withLock false
            val resolved = FilterIdentityPolicy.nameForEdit(name, identity.name) ?: return@withLock false
            if (!FilterLibraryEditor.isNameAvailable(port.currentLibrary(), resolved, null) ||
                (emoji != null && !FilterIdentityPolicy.isValidEmoji(emoji))) return@withLock false
            val renamed = FilterProfileDraft(
                id = identity.id, name = resolved, protectionLevel = null,
                enabledSourceIds = identity.enabledSourceIds, manualRules = identity.manualRules,
                emoji = emoji ?: identity.emoji, customBlocklists = identity.customBlocklists,
            )
            if (canPublish != null && (mutableNewFilter.value !== identity || !canPublish())) return@withLock false
            mutableNewFilter.value = renamed
            true
        }
        return admit(
            operation = FilterLibraryOperation.RenameFilter(filterId, name, emoji),
            filterId = filterId,
            isCurrent = canPublish,
        ) { admitted ->
            if (expectedDraft != null && mutableDrafts.value[filterId] !== expectedDraft ||
                expectedNewFilter != null && mutableNewFilter.value !== expectedNewFilter) {
                return@admit FilterLibraryMutationResult.Superseded
            }
            // A host-bound form derives from the same coherent basis its final write CASes.
            // Ports without the reviewed publication capability fail closed on this path.
            val basis = if (canPublish != null) {
                port.reviewBasis() ?: return@admit FilterLibraryMutationResult.Superseded
            } else null
            val result = FilterLibraryEditor.rename(
                library = basis?.library ?: port.currentLibrary(),
                filterId = filterId,
                name = name,
                maxFilters = admitted.limits.maxFilters,
                emoji = emoji,
            )
            when (result) {
                is FilterLibraryEditResult.Rejected -> FilterLibraryMutationResult.Rejected(result.reason)
                is FilterLibraryEditResult.Applied -> if (canPublish == null) port.apply(result.library, admitted)
                    else port.applyReviewedWhileCurrent(result.library, checkNotNull(basis), admitted, canPublish)
            }
        }
    }

    suspend fun deleteFilters(filterIds: Set<String>): Boolean = admit(
        operation = FilterLibraryOperation.DeleteFilters(filterIds),
        filterId = null,
    ) { admitted ->
        val result = FilterLibraryEditor.delete(
            library = port.currentLibrary(),
            filterIds = filterIds,
            maxFilters = admitted.limits.maxFilters,
        )
        when (result) {
            is FilterLibraryEditResult.Rejected ->
                FilterLibraryMutationResult.Rejected(result.reason)
            is FilterLibraryEditResult.Applied -> port.apply(result.library, admitted)
        }
    }

    suspend fun restoreDefaults(): Boolean = admit(
        operation = FilterLibraryOperation.RestoreDefaults,
        filterId = null,
    ) { admitted ->
        port.restoreDefaults(admitted)
    }

    /**
     * Replaces the entire saved library in one generation-fenced commit. This is the reviewed
     * backup-restore apply: the desired library was already validated by `BackupRestorePlan`, so
     * the owner only routes it through the same coordinator/admission path as a staged library
     * edit. The coordinator's generation CAS still refuses a concurrent mutation, and a `false`
     * result guarantees the previous library remains authoritative (never a partial replacement).
     *
     * Reuses [FilterLibraryOperation.CommitLibraryEdit]: a wholesale replacement is exactly a
     * whole-library batch. On success every draft and the open whole-library edit session are
     * discarded — a restore replaces the state the drafts were written against, so no preserved
     * edit may resume over it (mirrors the canonical `filterDrafts.resetSessions()`).
     */
    suspend fun replaceAll(
        library: FilterLibraryDraft,
        clearEditing: Boolean = true,
        expectedLibrary: FilterLibraryDraft? = null,
        isCurrent: (() -> Boolean)? = null,
    ): Boolean {
        // Capture the pre-write drafts/session: settle() drops drafts for filters absent from the
        // committed library, so a preserving restore must re-apply them or a subsequent
        // applyPreferences failure + rollback can never return the user's unsaved work.
        val draftsBefore = mutableDrafts.value
        val leasesBefore = mutableDraftLeases.value
        val sessionBefore = mutableEditSession.value
        val caller = currentCoroutineContext()[Job]
        val canPublish = isCurrent?.let { witness -> {
            caller?.isActive != false && !closed.get() && witness()
        } }
        val result = admitResult(
            operation = FilterLibraryOperation.CommitLibraryEdit(library),
            filterId = null,
            isCurrent = canPublish,
        ) { admitted ->
            val before = port.reviewBasis()
            val published = if (expectedLibrary == null && canPublish == null) {
                port.apply(library, admitted)
            } else {
                // Atomic compare-and-replace: bind the expected library AND its publication
                // generation under the coordinator's serialization, so a filter mutation that
                // commits after the caller's checkpoint but before this write is refused rather
                // than overwritten. A separate re-read immediately before an unconditional
                // replacement would still race; the comparison must be part of the write.
                val basis = before ?: return@admitResult FilterLibraryMutationResult.Superseded
                if (expectedLibrary != null && basis.library != expectedLibrary) {
                    return@admitResult FilterLibraryMutationResult.Superseded
                }
                if (canPublish == null) port.applyReviewed(library, basis, admitted)
                else port.applyReviewedWhileCurrent(library, basis, admitted, canPublish)
            }
            if (published is FilterLibraryMutationResult.Applied) {
                replacementReceipt = if (before != null && published.generation == before.generation + 1L)
                    ReplacementReceipt(before.library, library, published.generation) else null
            }
            published
        }
        // A committed write stays reported committed if the host retires immediately afterward;
        // the restore publisher must know it owns a forward write that requires compensation.
        val applied = result is FilterLibraryMutationResult.Applied
        if (applied) {
            if (clearEditing) {
                discardEditingSessions()
            } else {
                mutableDrafts.value = draftsBefore
                mutableDraftLeases.value = leasesBefore
                mutableEditSession.value = sessionBefore
            }
        }
        return applied
    }

    /** Revert only this owner's exact forward restore, including its committed generation.
     * This does not authorize an arbitrary over-quota import or a later replay. A fresh current
     * entitlement witness still fences publication, while only the new manual-domain quota gate
     * is bypassed to preserve previously valid data after a lapse. */
    suspend fun rollbackReplacement(library: FilterLibraryDraft, expectedLibrary: FilterLibraryDraft?): Boolean {
        if (expectedLibrary == null) return false
        val draftsBefore = mutableDrafts.value
        val leasesBefore = mutableDraftLeases.value
        val sessionBefore = mutableEditSession.value
        val result = admitResult(
            operation = FilterLibraryOperation.CommitLibraryEdit(library),
            filterId = null,
            // Compensation is exact and cannot become a generic retryable library operation.
            isCurrent = { !closed.get() },
        ) { admitted ->
            val basis = port.reviewBasis() ?: return@admitResult FilterLibraryMutationResult.Superseded
            // A preferences-only restore may have reported an idempotent library apply. There
            // is no forward generation to compensate; acknowledge only an exact live no-op.
            if (library == expectedLibrary && basis.library == library) {
                return@admitResult FilterLibraryMutationResult.Applied(basis.generation)
            }
            val receipt = replacementReceipt ?: return@admitResult FilterLibraryMutationResult.Superseded
            if (receipt.before != library || receipt.after != expectedLibrary ||
                basis.library != receipt.after || basis.generation != receipt.afterGeneration) {
                return@admitResult FilterLibraryMutationResult.Superseded
            }
            port.applyRetainedReplacement(library, basis, admitted).also { published ->
                if (published is FilterLibraryMutationResult.Applied) replacementReceipt = null
            }
        }
        if (result !is FilterLibraryMutationResult.Applied) return false
        mutableDrafts.value = draftsBefore
        mutableDraftLeases.value = leasesBefore
        mutableEditSession.value = sessionBefore
        return true
    }

    /** QA uses a distinct, non-retryable admission; restore's preserving replacement is unchanged. */
    suspend fun replaceReviewedForQa(
        desired: FilterLibraryDraft,
        expectedLibrary: FilterLibraryDraft,
        isCurrent: () -> Boolean,
    ): Boolean = admission.withLock {
        if (closed.get() || hasOpenDrafts() || !isCurrent()) return@withLock false
        val basis = port.reviewBasis() ?: return@withLock false
        if (basis.library != expectedLibrary) return@withLock false
        val admitted = captureAdmission() ?: return@withLock false
        val saved = basis.library.profile(basis.library.activeProfileId) ?: return@withLock false
        val candidate = desired.profile(saved.id) ?: return@withLock false
        // QA may replace its isolated global exact-host overlay and the active filter only.
        // Normalization here leaves every other profile and DNS authority in the exact basis.
        if (desired.withQaProbeSet(basis.library.qaProbeSet) != basis.library.withProfile(candidate)) return@withLock false
        val rejection = FilterDraftPolicy.reviewRejection(
            basis.library, candidate, saved, admitted.limits.maxFilters, admitted.limits,
        )
        if (rejection != null && rejection != com.lavasec.app.foundation.filter.FilterDraftReviewRejection.NO_CHANGES) return@withLock false
        val caller = currentCoroutineContext()[Job]
        val canPublish = { caller?.isActive != false && !closed.get() && !hasOpenDrafts() && isCurrent() }
        // No owned detached retry: this capability belongs to the current QA visit. The final
        // predicate still fences a port that finishes preparation after its caller leaves.
        val result = port.applyReviewedWhileCurrent(desired, basis, admitted, canPublish)
        result is FilterLibraryMutationResult.Applied && canPublish()
    }

    suspend fun replaceReviewedCompositeForQa(
        desired: FilterLibraryDraft,
        expectedLibrary: FilterLibraryDraft,
        reviewedDns: ReviewedQaResolverEdit,
        isCurrent: () -> Boolean,
    ): FilterLibraryMutationResult = admission.withLock {
        if (closed.get() || hasOpenDrafts() || !isCurrent()) return@withLock FilterLibraryMutationResult.Superseded
        val basis = port.reviewBasis() ?: return@withLock FilterLibraryMutationResult.Superseded
        val admitted = captureAdmission() ?: return@withLock FilterLibraryMutationResult.Superseded
        val saved = basis.library.profile(basis.library.activeProfileId) ?: return@withLock FilterLibraryMutationResult.Superseded
        val candidate = desired.profile(saved.id) ?: return@withLock FilterLibraryMutationResult.Superseded
        if (basis.library != expectedLibrary || admitted.limits != reviewedDns.limits ||
            ResolverDnsContext.encode(basis.library.resolverTarget.toSelection()) != reviewedDns.expectedDnsContext ||
            desired.resolverTarget != reviewedDns.target ||
            desired.withResolverPlan(basis.library.resolverTarget).withQaProbeSet(basis.library.qaProbeSet) != basis.library.withProfile(candidate)
        ) return@withLock FilterLibraryMutationResult.Superseded
        val rejection = FilterDraftPolicy.reviewRejection(basis.library, candidate, saved, admitted.limits.maxFilters, admitted.limits)
        if (rejection != null && rejection != com.lavasec.app.foundation.filter.FilterDraftReviewRejection.NO_CHANGES)
            return@withLock FilterLibraryMutationResult.Superseded
        val caller = currentCoroutineContext()[Job] ?: return@withLock FilterLibraryMutationResult.Superseded
        val canPublish = { caller.isActive && !closed.get() && !hasOpenDrafts() && isCurrent() }
        try {
            port.applyReviewedForQaComposite(desired, basis, admitted, canPublish, reviewedDns.publicationGuard)
        } catch (cancelled: CancellationException) {
            // The coordinator may observe caller cancellation after a durable commit. Recover the
            // exact adopted successor instead of claiming the old library remains authoritative.
            val actual = port.reviewBasis()
            if (actual?.library == desired && actual.generation == basis.generation + 1L)
                FilterLibraryMutationResult.Applied(actual.generation)
            else throw cancelled
        }
    }

    /** Ends every in-memory draft and the whole-library edit session after a successful commit. */
    fun discardEditingSessions() {
        replacementReceipt = null
        mutableDrafts.value = emptyMap()
        mutableDraftLeases.value = emptyMap()
        mutableNewFilter.value = null
        mutableEditSession.value = mutableEditSession.value.endEditing()
    }

    /**
     * Persist one confirmed import through the same generation-fenced coordinator as every other
     * library mutation. Add-as-new appends a profile carrying the reconciled content; a replace
     * rewrites the target's enabled sources and manual rules while preserving its identity
     * (id/name/level/emoji). The content was already reconciled by the share/import owner, so
     * this only maps it into the library model; the coordinator still re-validates the whole
     * library at publication, so an over-cap or unknown-source write is refused, never partial.
     *
     * A legacy v1 payload carries no allowed set (`allowedDomains == null`): on replace that
     * means "keep the recipient's existing allowed exceptions" (the canonical preserved-rules
     * contract), never a silent clear.
     */
    suspend fun commitImported(
        configuration: ShareableFilterConfiguration,
        targetFilterId: String?,
        expectedLibrary: FilterLibraryDraft? = null,
        isCurrent: (() -> Boolean)? = null,
    ): FilterLibraryMutationResult? {
        val caller = currentCoroutineContext()[Job]
        val canPublish = isCurrent?.let { current -> {
            caller?.isActive != false && !closed.get() && current()
        } }
        return admitResult(
            operation = FilterLibraryOperation.CommitImported(configuration, targetFilterId),
            filterId = targetFilterId,
            isCurrent = canPublish,
        ) { admitted ->
            val basis = if (canPublish != null) port.reviewBasis()
                ?: return@admitResult FilterLibraryMutationResult.Superseded else null
            val library = basis?.library ?: port.currentLibrary()
            if (expectedLibrary != null && library != expectedLibrary)
                return@admitResult FilterLibraryMutationResult.Superseded
            val desired = if (targetFilterId == null) {
                if (library.profiles.size >= admitted.limits.maxFilters) {
                    return@admitResult FilterLibraryMutationResult.Rejected(FilterLibraryRejection.LIMIT_REACHED)
                }
                val profile = FilterProfileDraft(
                    id = newFilterId(),
                    name = uniqueLibraryName("Imported filter", library.profiles),
                    protectionLevel = null,
                    enabledSourceIds = configuration.enabledBlocklistIDs,
                    manualRules = importedRules(configuration, configuration.allowedDomains.orEmpty()),
                    emoji = configuration.emoji,
                    customBlocklists = importedCustomBlocklists(configuration.customBlocklists),
                )
                library.withProfiles(library.profiles + profile)
            } else {
                val target = library.profile(targetFilterId)
                    ?: return@admitResult FilterLibraryMutationResult.Rejected(FilterLibraryRejection.UNKNOWN_FILTER)
                if (FilterLibraryPolicy.isFrozen(library, targetFilterId, admitted.limits.maxFilters)) {
                    return@admitResult FilterLibraryMutationResult.Rejected(FilterLibraryRejection.FROZEN_FILTER)
                }
                library.withProfile(
                    FilterProfileDraft(
                        id = target.id,
                        name = target.name,
                        protectionLevel = target.protectionLevel,
                        enabledSourceIds = configuration.enabledBlocklistIDs,
                        manualRules = importedRules(
                            configuration,
                            configuration.allowedDomains ?: target.manualRules.allowedDomains,
                        ),
                        emoji = target.emoji,
                        customBlocklists = importedCustomBlocklists(configuration.customBlocklists),
                    ),
                )
            }
            if (canPublish == null) port.apply(desired, admitted)
            else port.applyReviewedWhileCurrent(desired, checkNotNull(basis), admitted, canPublish)
        }
    }

    /** Maps a share payload's custom definitions onto the durable model, re-validating each URL. */
    private fun importedCustomBlocklists(
        sources: List<ShareableCustomBlocklistSource>,
    ): List<CustomBlocklistSource> = sources.mapNotNull { source ->
        val format = BlocklistFormat.entries.firstOrNull {
            it.name.equals(source.parseFormat.wireValue, ignoreCase = true)
        } ?: BlocklistFormat.AUTO
        when (
            val created = CustomBlocklistSource.create(
                displayName = source.displayName,
                rawUrl = source.sourceUrl,
                parseFormat = format,
                id = source.id,
            )
        ) {
            is com.lavasec.app.foundation.filter.CustomBlocklistCreateResult.Created -> created.source
            is com.lavasec.app.foundation.filter.CustomBlocklistCreateResult.Rejected -> null
        }
    }

    /**
     * The imported manual rules. A crafted payload can name the same domain in both shelves; the
     * exclusive [ManualDomainRules] model forbids that, so an allowed domain that is also blocked
     * loses — block wins. (The share review already normalizes both sets, so this is defensive.)
     */
    private fun importedRules(
        configuration: ShareableFilterConfiguration,
        allowed: Set<String>,
    ): ManualDomainRules {
        val blocked = configuration.blockedDomains
        return ManualDomainRules(allowedDomains = allowed - blocked, blockedDomains = blocked)
    }

    // ---- Whole-library edit session (canonical library.* commands) ----
    //
    // The session is the single owner of the staged whole-library batch; every rule lives in
    // FilterLibraryEditSession. This layer only supplies the command-level validation the iOS
    // bridge performs before mutating (target exists, not the active filter, not frozen) and
    // publishes the session's validated result through the same generation-fenced coordinator
    // as every other library mutation. Nothing here writes the saved library before
    // [commitLibraryEdit].

    /**
     * Canonical `library.edit`: capture the baseline once. A repeated call while already
     * editing preserves the in-progress draft and its original baseline.
     */
    fun beginLibraryEditing(): FilterLibraryEditOutcome {
        if (closed.get()) return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        mutableEditSession.value = mutableEditSession.value.beginEditing(port.currentLibrary())
        return FilterLibraryEditOutcome.Completed
    }

    /** Canonical `library.cancel`: discard the whole staged batch (local teardown, no auth). */
    fun cancelLibraryEditing(): FilterLibraryEditOutcome {
        mutableEditSession.value = mutableEditSession.value.endEditing()
        return FilterLibraryEditOutcome.Completed
    }

    /**
     * Canonical `library.toggleDeletion`: stage (or unstage) one removal. A staged addition is
     * removed outright, discarding its staged rename/emoji. The active filter and frozen saved
     * filters are refused with the canonical command-layer rejections.
     */
    fun toggleStagedDeletion(filterId: String): FilterLibraryEditOutcome {
        if (closed.get()) return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        val session = mutableEditSession.value
        if (!session.isEditing) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        }
        val displayed = session.displayedFilters
        if (displayed.none { it.id == filterId }) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.UNKNOWN_FILTER)
        }
        if (filterId == port.currentLibrary().activeProfileId) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.ACTIVE_FILTER)
        }
        if (session.additions.none { it.id == filterId } && isFrozen(filterId)) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.FROZEN_FILTER)
        }
        mutableEditSession.value = session.toggleDeletion(filterId)
        return FilterLibraryEditOutcome.Completed
    }

    /**
     * Canonical Create opens an independent editor identity, rather than a library addition.
     * library.cancel may close its chooser session without discarding this detail-owned draft.
     */
    suspend fun beginCreatingFilter(
        name: String?, duplicateFromId: String?, expectedSession: FilterLibraryEditSession? = null,
        isCurrent: (() -> Boolean)? = null,
    ): FilterLibraryEditOutcome = admission.withLock {
        if (isCurrent?.invoke() == false) return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        if (expectedSession != null && mutableEditSession.value !== expectedSession) {
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        }
        if (closed.get() || !mutableEditSession.value.isEditing) {
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        }
        val session = mutableEditSession.value
        if (session.hasChanges) return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.HAS_CHANGES)
        val library = port.currentLibrary()
        if (session.baseline != library) return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        if (library.profiles.size >= limits().maxFilters) return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.LIMIT_REACHED)
        val source = duplicateFromId?.let(library::profile)
        if (duplicateFromId != null && source == null) return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.UNKNOWN_FILTER)
        val requested = name?.takeIf { it.isNotBlank() }
        if (requested != null && (!FilterIdentityPolicy.isValidName(requested) ||
                !FilterLibraryEditor.isNameAvailable(library, requested, null))) {
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.INVALID_NAME)
        }
        val id = newFilterId()
        if (id.isBlank() || library.profile(id) != null) return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        val profile = FilterProfileDraft(
            id = id, name = requested?.let(FilterIdentityPolicy::normalizedName)
                ?: uniqueLibraryName(creationName(), library.profiles, numberedFromOne = true),
            protectionLevel = null, enabledSourceIds = source?.enabledSourceIds.orEmpty(),
            manualRules = source?.manualRules ?: ManualDomainRules.EMPTY,
            customBlocklists = source?.customBlocklists.orEmpty(),
        )
        if (isCurrent != null && (mutableEditSession.value !== session || !isCurrent())) {
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        }
        mutableNewFilter.value?.let { mutableDrafts.value -= it.id; mutableDraftLeases.value -= it.id }
        mutableDraftLeases.value += id to UUID.randomUUID().toString()
        mutableDrafts.value += id to profile
        mutableNewFilter.value = profile
        lastOperation = null
        mutableState.value = State.Idle
        FilterLibraryEditOutcome.Created(id)
    }

    /**
     * Canonical `library.form create`: stage a new filter. Canonical creates before any staged
     * batch exists and respects the count cap; the name is derived uniquely when absent, and
     * an explicit name must pass the identity policy. The addition is draft-only until
     * [commitLibraryEdit].
     */
    fun stageLibraryCreate(name: String?, duplicateFromId: String?): FilterLibraryEditOutcome {
        if (closed.get()) return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        val session = mutableEditSession.value
        if (!session.isEditing) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        }
        if (session.hasChanges) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.HAS_CHANGES)
        }
        val displayed = session.displayedFilters
        if (displayed.size >= limits().maxFilters) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.LIMIT_REACHED)
        }
        val source = duplicateFromId?.let { id -> displayed.firstOrNull { it.id == id } }
        if (duplicateFromId != null && source == null) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.UNKNOWN_FILTER)
        }
        val requested = name?.takeIf { it.isNotBlank() }
        if (requested != null && !FilterIdentityPolicy.isValidName(requested)) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.INVALID_NAME)
        }
        val id = newFilterId()
        val resolvedName = requested?.let(FilterIdentityPolicy::normalizedName)
            ?: uniqueLibraryName("Untitled", displayed)
        val profile = FilterProfileDraft(
            id = id,
            name = resolvedName,
            protectionLevel = null,
            enabledSourceIds = source?.enabledSourceIds ?: emptySet(),
            manualRules = source?.manualRules ?: ManualDomainRules.EMPTY,
            customBlocklists = source?.customBlocklists.orEmpty(),
        )
        mutableEditSession.value = session.add(profile)
        return FilterLibraryEditOutcome.Created(id)
    }

    /**
     * Canonical `library.form rename`: stage a complete identity edit (name and, when supplied,
     * emoji) behind the same review boundary as the rest of the batch.
     */
    suspend fun stageLibraryRename(
        filterId: String, name: String, emoji: String?, expectedSession: FilterLibraryEditSession? = null,
        isCurrent: (() -> Boolean)? = null,
    ): FilterLibraryEditOutcome = admission.withLock {
        if (isCurrent?.invoke() == false) return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        if (expectedSession != null && (mutableEditSession.value !== expectedSession ||
                expectedSession.baseline != port.currentLibrary())) {
            // Authorization can suspend after the form captures its displayed rows. The same
            // session cannot acknowledge a rename over a concurrently changed saved library.
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        }
        if (closed.get()) return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        val session = mutableEditSession.value
        if (!session.isEditing) {
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        }
        val target = session.displayedFilters.firstOrNull { it.id == filterId }
            ?: return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.UNKNOWN_FILTER)
        if (session.additions.none { it.id == filterId } && isFrozen(filterId)) {
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.FROZEN_FILTER)
        }
        val canonicalName = FilterIdentityPolicy.nameForEdit(name, target.name)
            ?: return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.INVALID_NAME)
        if (emoji != null && !FilterIdentityPolicy.isValidEmoji(emoji)) {
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.INVALID_EMOJI)
        }
        val renamed = if (emoji == null) {
            session.rename(filterId, canonicalName)
        } else {
            session.rename(filterId, canonicalName, emoji)
        }
        if (isCurrent != null && (mutableEditSession.value !== session || !isCurrent())) {
            return@withLock FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        }
        mutableEditSession.value = renamed
        return@withLock FilterLibraryEditOutcome.Completed
    }

    /**
     * Canonical `library.form delete`: the one confirmation boundary. Validates the whole
     * staged batch against the live library and commits it atomically through the coordinator;
     * the session is discarded only after the durable apply. When [expectedIds] is supplied it
     * must match the session's staged deletions exactly, so a caller can never confirm a
     * different set than it rendered.
     */
    suspend fun commitLibraryEdit(
        expectedIds: Set<String>? = null, expectedSession: FilterLibraryEditSession? = null,
        isCurrent: (() -> Boolean)? = null,
    ): FilterLibraryEditOutcome {
        if (closed.get()) return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        if (isCurrent?.invoke() == false) return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        val session = mutableEditSession.value
        val caller = currentCoroutineContext()[Job]
        val canPublish = isCurrent?.let { current -> {
            caller?.isActive != false && !closed.get() && current() && mutableEditSession.value === session
        } }
        if (expectedSession != null && session !== expectedSession) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        }
        if (!session.isEditing) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NOT_EDITING)
        }
        if (expectedIds != null && expectedIds != session.stagedDeletions) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.STALE_STAGING)
        }
        if (!session.hasChanges) {
            return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.NO_CHANGES)
        }
        val desired = session.validatedLibrary(port.currentLibrary(), limits().maxFilters)
            ?: return FilterLibraryEditOutcome.Rejected(FilterLibraryEditRejection.COMMIT_FAILED)
        val completedSession = session.endEditing()
        val publication = admitResult(
            operation = FilterLibraryOperation.CommitLibraryEdit(desired),
            filterId = null,
            isCurrent = canPublish,
        ) { admitted ->
            // Compare the exact reviewed session under the same lock as the durable mutation.
            // A replacement editor with the same deletion IDs is still a different owner.
            if (mutableEditSession.value !== session || expectedSession != null && session !== expectedSession) {
                return@admitResult FilterLibraryMutationResult.Superseded
            }
            // Re-validate at the commit edge against the live library so a concurrent change
            // between staging and admission is superseded rather than published stale.
            val basis = if (canPublish != null) {
                port.reviewBasis() ?: return@admitResult FilterLibraryMutationResult.Superseded
            } else null
            val committed = session.validatedLibrary(basis?.library ?: port.currentLibrary(), admitted.limits.maxFilters)
                ?: return@admitResult FilterLibraryMutationResult.Superseded
            val result = if (canPublish == null) port.apply(committed, admitted)
            else port.applyReviewedWhileCurrent(committed, checkNotNull(basis), admitted, canPublish)
            // Durable completion retires its exact session even if the caller or host left
            // after publication. This runs in the owned task, including caller cancellation;
            // a replacement session never inherits this completed batch's cleanup.
            if (result is FilterLibraryMutationResult.Applied && mutableEditSession.value === session) {
                mutableEditSession.compareAndSet(session, completedSession)
            }
            result
        }
        // Keep the publication fact separate from admission of a late UI acknowledgment.
        // The write guard still checks the original session; only this exact completed
        // session may acknowledge a guarded write after its successful retirement.
        val acknowledged = publication is FilterLibraryMutationResult.Applied &&
            (isCurrent == null || caller?.isActive != false && !closed.get() &&
                isCurrent() && mutableEditSession.value === completedSession)
        return if (acknowledged) {
            FilterLibraryEditOutcome.Completed
        } else {
            val failure = mutableState.value as? State.Failed
            val reason = (failure?.failure as? Failure.Rejected)?.reason
            FilterLibraryEditOutcome.Rejected(
                when (reason) {
                    FilterLibraryRejection.UNKNOWN_FILTER -> FilterLibraryEditRejection.UNKNOWN_FILTER
                    FilterLibraryRejection.FROZEN_FILTER -> FilterLibraryEditRejection.FROZEN_FILTER
                    FilterLibraryRejection.LIMIT_REACHED -> FilterLibraryEditRejection.LIMIT_REACHED
                    FilterLibraryRejection.DUPLICATE_NAME,
                    FilterLibraryRejection.BLANK_NAME,
                    FilterLibraryRejection.INVALID_NAME,
                    -> FilterLibraryEditRejection.INVALID_NAME
                    else -> FilterLibraryEditRejection.COMMIT_FAILED
                },
            )
        }
    }

    private fun isFrozen(filterId: String): Boolean =
        FilterLibraryPolicy.isFrozen(port.currentLibrary(), filterId, limits().maxFilters)

    private fun uniqueLibraryName(base: String, displayed: List<FilterProfileDraft>, numberedFromOne: Boolean = false): String {
        val taken = displayed.mapTo(HashSet()) { FilterIdentityPolicy.normalizedName(it.name).lowercase(Locale.ROOT) }
        val root = FilterIdentityPolicy.normalizedName(base).ifEmpty { "Untitled" }
        if (!numberedFromOne && root.lowercase(Locale.ROOT) !in taken) return root
        var index = if (numberedFromOne) 1 else 2
        while (true) {
            val candidate = "$root $index"
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
            index++
        }
    }

    /**
     * Re-admits the last failed operation. The desired is re-derived from the CURRENT library
     * and the live draft store — never from a captured pre-failure value — so a retry cannot
     * duplicate a write (the generation CAS refuses a stale expected generation) or leak a
     * stale generation (a superseded retry fails honestly instead of publishing old content).
     * Reviewed commits additionally retain and recheck the original review witness.
     */
    suspend fun retryLast(): Boolean {
        val operation = lastOperation ?: return false
        return when (operation) {
            is FilterLibraryOperation.CommitDraft -> commitDraft(operation.filterId, operation.review)
            is FilterLibraryOperation.CreateFilter ->
                createFilter(operation.name, operation.duplicateFromId)
            is FilterLibraryOperation.RenameFilter ->
                renameFilter(operation.filterId, operation.name, operation.emoji)
            is FilterLibraryOperation.DeleteFilters -> deleteFilters(operation.filterIds)
            is FilterLibraryOperation.CommitLibraryEdit -> {
                // Re-admit the exact reviewed batch; the coordinator's generation CAS still
                // refuses a stale expected generation.
                admit(operation, null) { admitted -> port.apply(operation.library, admitted) }
            }
            is FilterLibraryOperation.CommitImported -> {
                // Re-admit the exact confirmed import; the coordinator re-validates the whole
                // library at publication, so a retry can neither duplicate nor leak a stale one.
                commitImported(operation.configuration, operation.targetFilterId)
                mutableState.value is FilterLibraryOwner.State.Succeeded
            }
            FilterLibraryOperation.RestoreDefaults -> restoreDefaults()
        }
    }

    private suspend fun admit(
        operation: FilterLibraryOperation,
        filterId: String?,
        expectedState: State? = null,
        isCurrent: (() -> Boolean)? = null,
        run: suspend (FeatureLimitAdmission) -> FilterLibraryMutationResult,
    ): Boolean = admitResult(operation, filterId, expectedState, isCurrent, run) is FilterLibraryMutationResult.Applied &&
        isCurrent?.invoke() != false

    /**
     * One admitted operation's typed result. Callers that only need success use [admit]; the
     * share/import confirmation needs the precise rejection (a stale/over-cap/frozen target is
     * not the same as a failure), so it reads the result directly. `null` means the operation
     * never ran (closed owner or a superseded expected state).
     */
    private suspend fun admitResult(
        operation: FilterLibraryOperation,
        filterId: String?,
        expectedState: State? = null,
        isCurrent: (() -> Boolean)? = null,
        run: suspend (FeatureLimitAdmission) -> FilterLibraryMutationResult,
    ): FilterLibraryMutationResult? {
        if (closed.get()) return null
        return admission.withLock {
            if (closed.get() || isCurrent?.invoke() == false) return@withLock null
            if (expectedState != null && mutableState.value !== expectedState) return@withLock null
            val preparation = currentCoroutineContext()[FilterPreparationProgress]
            // A foreground host capability cannot be replayed later without its witness.
            lastOperation = if (isCurrent == null && preparation == null) operation else null
            mutableState.value = State.Mutating(operation, filterId)
            val epoch = publicationEpoch.incrementAndGet()
            // A foreground preparation is an exact native capability. Carry only its
            // typed progress/retirement port into the durable owner, never the caller's Job.
            val task = ownerScope.async(preparation ?: kotlin.coroutines.EmptyCoroutineContext) {
                if (closed.get() || isCurrent?.invoke() == false || preparation?.isCurrent?.invoke() == false) null else {
                    val admitted = captureAdmission()
                    if (admitted == null) FilterLibraryMutationResult.Failed(FilterMutationFailure.COMMIT_FAILED)
                    else run(admitted)
                }
            }
            preparation?.bindWorker?.invoke(task)
            val result = try {
                task.await()
            } catch (cancelled: CancellationException) {
                if (closed.get()) {
                    settle(operation, filterId, null)
                    return@withLock null
                }
                // The caller left while the owned compile/publish is admitted in the owner job;
                // the task keeps running and settles from its actual outcome (same fence as the
                // switch owner — aborting mid-publication would strand the durable generation).
                settleWhenCompleted(epoch, operation, filterId, task)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = State.Failed(operation, filterId, Failure.Commit)
                return@withLock null
            }
            settle(operation, filterId, result)
            result
        }
    }

    private fun settle(
        operation: FilterLibraryOperation,
        filterId: String?,
        result: FilterLibraryMutationResult?,
    ): Boolean = when (result) {
        null -> {
            mutableState.value = State.Idle
            false
        }
        is FilterLibraryMutationResult.Applied -> {
            if (operation is FilterLibraryOperation.CommitDraft) {
                mutableDrafts.value = mutableDrafts.value - operation.filterId
                mutableDraftLeases.value -= operation.filterId
                if (isCreating(operation.filterId)) mutableNewFilter.value = null
            }
            if (operation is FilterLibraryOperation.DeleteFilters) {
                mutableDrafts.value = mutableDrafts.value.filterKeys { it !in operation.filterIds }
                mutableDraftLeases.value = mutableDraftLeases.value.filterKeys { it !in operation.filterIds }
            }
            if (operation is FilterLibraryOperation.CommitLibraryEdit) {
                // A batch that removed filters drops their drafts; the survivors keep theirs.
                val surviving = operation.library.profiles.mapTo(HashSet()) { it.id }
                mutableDrafts.value = mutableDrafts.value.filterKeys { it in surviving || isCreating(it) }
                mutableDraftLeases.value = mutableDraftLeases.value.filterKeys { it in mutableDrafts.value }
            }
            if (operation is FilterLibraryOperation.RestoreDefaults) {
                // A wholesale reseed invalidates every draft: no preserved edit may resume
                // over a freshly seeded filter with the same id.
                mutableDrafts.value = emptyMap()
                mutableDraftLeases.value = emptyMap()
                mutableNewFilter.value = null
                mutableEditSession.value = mutableEditSession.value.endEditing()
            }
            mutableState.value = State.Succeeded(operation, filterId)
            true
        }
        is FilterLibraryMutationResult.Rejected -> {
            mutableState.value = State.Failed(operation, filterId, Failure.Rejected(result.reason))
            false
        }
        is FilterLibraryMutationResult.FailedSource -> {
            mutableState.value = State.Failed(
                operation,
                filterId,
                Failure.Source(result.sourceId, result.sourceName, result.failure),
            )
            false
        }
        is FilterLibraryMutationResult.Failed,
        FilterLibraryMutationResult.Superseded,
        -> {
            mutableState.value = State.Failed(operation, filterId, Failure.Commit)
            false
        }
        FilterLibraryMutationResult.ProtectionUnavailable -> {
            mutableReconstructionRequired.value = true
            mutableState.value = State.Idle
            false
        }
    }

    private fun settleWhenCompleted(
        epoch: Long,
        operation: FilterLibraryOperation,
        filterId: String?,
        task: Deferred<FilterLibraryMutationResult?>,
    ) {
        ownerScope.launch {
            val result = try {
                task.await()
            } catch (cancelled: CancellationException) {
                if (epoch == publicationEpoch.get() && !closed.get()) settle(operation, filterId, null)
                return@launch
            } catch (_: Exception) {
                if (epoch == publicationEpoch.get() && !closed.get()) {
                    mutableState.value = State.Failed(operation, filterId, Failure.Commit)
                }
                return@launch
            }
            if (epoch == publicationEpoch.get() && !closed.get()) settle(operation, filterId, result)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ownerJob.cancel()
            mutableNewFilter.value = null
            mutableDrafts.value = emptyMap()
            mutableDraftLeases.value = emptyMap()
        }
    }

    private fun creationLibraryRejection(reason: com.lavasec.app.foundation.filter.FilterDraftReviewRejection): FilterLibraryRejection = when (reason) {
        com.lavasec.app.foundation.filter.FilterDraftReviewRejection.FILTER_LIMIT_REACHED -> FilterLibraryRejection.LIMIT_REACHED
        com.lavasec.app.foundation.filter.FilterDraftReviewRejection.INVALID_NAME -> FilterLibraryRejection.INVALID_NAME
        com.lavasec.app.foundation.filter.FilterDraftReviewRejection.DUPLICATE_NAME -> FilterLibraryRejection.DUPLICATE_NAME
        com.lavasec.app.foundation.filter.FilterDraftReviewRejection.INVALID_EMOJI -> FilterLibraryRejection.INVALID_EMOJI
        com.lavasec.app.foundation.filter.FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT -> FilterLibraryRejection.BLOCKED_DOMAIN_LIMIT
        com.lavasec.app.foundation.filter.FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT -> FilterLibraryRejection.ALLOWED_DOMAIN_LIMIT
        else -> FilterLibraryRejection.UNKNOWN_FILTER
    }
}
