package com.lavasec.app.product

import com.lavasec.app.foundation.resolver.ChainedDnsEditState
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverEditRejection
import com.lavasec.app.foundation.resolver.ResolverEditRequest
import com.lavasec.app.foundation.resolver.ResolverEditResult
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionCommit
import com.lavasec.app.foundation.resolver.ResolverSelectionPolicy
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.resolver.SaveResolverTiers
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.FilterMutationFailure
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One apply of the exact coordinator-backed resolver-plan switch. The plan on every
 * non-Applied result is the requested one; after a [Superseded] outcome the durable
 * authority holds a newer generation than the coordinator, so callers must not treat
 * the coordinator's retained plan as the committed one.
 */
sealed interface ResolverPlanSwitchResult {
    val plan: MilestoneResolverPlan

    data class Applied(
        override val plan: MilestoneResolverPlan,
        val generation: Long,
    ) : ResolverPlanSwitchResult

    data class Rejected(
        override val plan: MilestoneResolverPlan,
        val reason: ResolverPlanSwitchRejection,
    ) : ResolverPlanSwitchResult

    data class Failed(
        override val plan: MilestoneResolverPlan,
        val failure: FilterMutationFailure,
    ) : ResolverPlanSwitchResult

    data class Superseded(
        override val plan: MilestoneResolverPlan,
    ) : ResolverPlanSwitchResult

    data class ProtectionUnavailable(
        override val plan: MilestoneResolverPlan,
    ) : ResolverPlanSwitchResult
}

enum class ResolverPlanSwitchRejection {
    /** The requested plan is already the authoritative plan. */
    NO_CHANGE,

    /** The coordinator could not authorize the exact draft against the verified catalog. */
    INVALID_DRAFT,

    /** The coordinator requires device unlock before admitting the mutation. */
    DEVICE_LOCKED,
}

/** The capability bound to one credential generation's coordinator. */
fun interface ResolverPlanSwitchPort {
    suspend fun apply(plan: FilterResolverPlan): ResolverPlanSwitchResult
}

/**
 * The confirmed facts one DNS edit is evaluated against. [current] is the committed
 * selection (never the requested one), so a policy decision is always against what is
 * actually stored; [editorContext] is the live editor context string the request's
 * `expectedEditorContext` is compared with (a mismatch is a stale editor).
 */
data class ResolverDnsEditContext(
    val current: ResolverSelection,
    val limits: FeatureLimits,
    val chained: ChainedDnsEditState,
    val editorContext: String? = null,
)

/**
 * One canonical `dns.*` command outcome. A policy refusal travels as its typed
 * [ResolverEditRejection] so the bridge maps a stable wire code and the UI never sees a
 * fabricated success for an unsupported provider/transport/custom/fallback edit.
 */
sealed interface ResolverDnsEditOutcome {
    /** The edit was accepted by policy and the mapped milestone plan committed. */
    data class Applied(val commit: ResolverSelectionCommit? = null) : ResolverDnsEditOutcome

    /** A custom-draft editor was opened; [token] identifies the editor session. */
    data class DraftOpened(val token: String) : ResolverDnsEditOutcome

    /** A validated local picker choice; no resolver configuration was committed. */
    data class DraftStaged(val choice: ResolverTierChoice) : ResolverDnsEditOutcome

    /** The policy refused the edit; the typed reason travels unchanged. */
    data class Refused(val rejection: ResolverEditRejection) : ResolverDnsEditOutcome

    /** Policy accepted but the coordinator/switch capability could not commit it. */
    data class CommitFailed(val commit: ResolverSelectionCommit) : ResolverDnsEditOutcome

    /** Policy accepted a selection the milestone runtime has no plan for. */
    data class Unserved(val commit: ResolverSelectionCommit) : ResolverDnsEditOutcome

    /** No live credential-generation owner exists to evaluate against. */
    data object Unavailable : ResolverDnsEditOutcome
}

/**
 * Stable editor-context encoding shared by the bridge snapshot and the owner's policy
 * evaluation. The format is opaque to JS (it round-trips verbatim); it exists only so a
 * `dns.tiers`/`dns.custom` command built against an older ladder is refused as stale.
 */
object ResolverDnsContext {
    private const val FIELD = '|'
    private const val TIER = ';'

    fun encode(selection: ResolverSelection): String = buildString {
        // Every retained row travels, including a disabled one and its enablement flag, so an
        // editor opened against a ladder is invalidated the moment any row's identity, value or
        // enablement changes (including a toggle of a disabled row back on).
        selection.tiers.forEachIndexed { index, tier ->
            if (index > 0) append(TIER)
            val entry = tier.entry
            append(entry.providerId.rawId).append(FIELD).append(entry.transport.token)
            // A custom DoH URL or DoT endpoint carries its normalized value in the context so
            // changing the endpoint invalidates an editor opened against the previous one.
            entry.customName?.let { append(FIELD).append(it) }
            entry.customDohUrl?.let { append(FIELD).append(it) }
            entry.customDotEndpoint?.let { append(FIELD).append(it) }
            entry.customDoqEndpoint?.let { append(FIELD).append("doq").append(FIELD).append(it) }
            entry.customSecondaryEndpoint?.let { append(FIELD).append(it) }
            tier.retainedName?.let { append(FIELD).append("retained-name").append(FIELD).append(it) }
            entry.customPlainEndpoint?.let { append(FIELD).append("plain").append(FIELD).append(it) }
            append(FIELD).append(if (tier.isEnabled) "1" else "0")
        }
    }

    fun encodeCustom(selection: ResolverSelection?, custom: com.lavasec.app.foundation.resolver.CustomResolverDraft): String =
        buildString {
            append(selection?.let(::encode) ?: "")
            append(TIER).append(custom.name).append(FIELD).append(custom.primaryAddress).append(FIELD).append(custom.secondaryAddress)
        }
}

/**
 * Lifecycle gate around the coordinator-backed resolver-plan switch capability.
 *
 * The owner mirrors [FilterProfileSwitchOwner] exactly: one admitted apply at a time,
 * in-flight publication before the slow compile/commit, sticky reconstruction arming on
 * a [ResolverPlanSwitchResult.ProtectionUnavailable] settle, and the owner-job detach so
 * a caller's death never aborts a durable publication mid-flight. The same coordinator
 * serializes profile switches and resolver switches, so both owners share one wedge
 * recovery path (the product host rotates the credential graph once per generation).
 */
class ResolverPlanSwitchOwner(
    private val port: ResolverPlanSwitchPort,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * The chained-mode DNS edit gate this generation's commands evaluate against. The
     * default is the ordinary editable state. Production supplies the generation's live
     * chained authority; the flow drives presentation while admission reloads durable facts.
     */
    val chainedDnsEdit: StateFlow<ChainedDnsEditState> = MutableStateFlow(ChainedDnsEditState.editable),
    private val loadChainedDnsEdit: (suspend () -> ChainedDnsEditState)? = null,
    private val captureQaDnsAdmission: (suspend () -> QaDnsPublicationAdmission?)? = null,
) : AutoCloseable {
    sealed interface State {
        data object Idle : State

        data class Applying(val plan: MilestoneResolverPlan) : State

        data class Failed(
            val plan: MilestoneResolverPlan,
            val failure: Failure,
        ) : State
    }

    enum class Failure {
        /** Any rejection, commit failure, or supersession; the switch did not land. */
        COMMIT,
    }

    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + dispatcher + ownerJob)
    private val admission = Mutex()
    private val publicationEpoch = AtomicLong(0L)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private val mutableReconstructionRequired = MutableStateFlow(false)

    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Sticky for the owner's lifetime once a [ResolverPlanSwitchResult.ProtectionUnavailable]
     * outcome settles, exactly like [FilterProfileSwitchOwner.reconstructionRequired]: the
     * shared coordinator's ReloadRequired authority state cannot be cleared by this
     * generation, so the product host reconstructs the credential graph once per generation.
     */
    val reconstructionRequired: StateFlow<Boolean> = mutableReconstructionRequired.asStateFlow()

    /**
     * Runs one resolver-plan switch and reports it through [state]. Returns whether the
     * requested plan is authoritative after the call. See [FilterProfileSwitchOwner.apply]
     * for the in-flight, failure, close-fence, and caller-cancellation semantics this
     * owner reproduces verbatim.
     */
    suspend fun apply(plan: MilestoneResolverPlan): Boolean =
        apply(FilterResolverPlan(plan))

    /** Runs one value-carrying resolver-plan switch and reports it through [state]. */
    suspend fun apply(target: FilterResolverPlan): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            if (closed.get()) return false
            applyLocked(target)
        }
    }

    /**
     * One canonical `dns.tiers` / `dns.custom` evaluation and (on acceptance) commit,
     * serialized on the SAME admission lock as [apply] so a policy decision and its commit
     * cannot interleave with another resolver switch. A refusal is returned as its typed
     * [ResolverEditRejection]; nothing is committed for a refused edit.
     */
    suspend fun applyDnsEdit(
        request: ResolverEditRequest,
        context: ResolverDnsEditContext,
    ): ResolverDnsEditOutcome {
        if (closed.get()) return ResolverDnsEditOutcome.Unavailable
        return admission.withLock {
            if (closed.get()) return ResolverDnsEditOutcome.Unavailable
            // A profile publication may precede its flow emission. Re-read the exact durable
            // chain consent/profile gate at admission, so a stale editable screen cannot write
            // a DNS ladder after a full-tunnel profile has committed.
            val admittedContext = try {
                loadChainedDnsEdit?.let { context.copy(chained = it()) } ?: context
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withLock ResolverDnsEditOutcome.Unavailable }
            if (closed.get()) return@withLock ResolverDnsEditOutcome.Unavailable
            when (val evaluated = evaluateDnsEdit(request, admittedContext)) {
                is ResolverEditResult.Rejected -> ResolverDnsEditOutcome.Refused(evaluated.rejection)
                is ResolverEditResult.Accepted -> {
                    val target = evaluated.commit.selection.toFilterResolverPlan()
                        ?: return@withLock ResolverDnsEditOutcome.Unserved(evaluated.commit)
                    if (applyLocked(target)) {
                        ResolverDnsEditOutcome.Applied(evaluated.commit)
                    } else {
                        ResolverDnsEditOutcome.CommitFailed(evaluated.commit)
                    }
                }
            }
        }
    }

    /** One real policy admission; the caller publishes DNS + sources + probes in one library CAS. */
    suspend fun applyReviewedDnsForQa(
        request: ResolverEditRequest,
        context: ResolverDnsEditContext,
        isCurrent: () -> Boolean,
        publish: suspend (ReviewedQaResolverEdit) -> FilterLibraryMutationResult,
    ): QaCompositeConfigurationResult {
        val caller = currentCoroutineContext()[Job] ?: return QaCompositeConfigurationResult.Unavailable
        val editorContext = context.editorContext ?: return QaCompositeConfigurationResult.Unavailable
        currentCoroutineContext().ensureActive()
        return admission.withLock {
            if (closed.get() || !caller.isActive || !isCurrent()) return@withLock QaCompositeConfigurationResult.Unavailable
            val native = try { captureQaDnsAdmission?.invoke() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                ?: return@withLock QaCompositeConfigurationResult.Unavailable
            val evaluated = evaluateDnsEdit(request, context.copy(chained = native.chained))
            if (evaluated is ResolverEditResult.Rejected) return@withLock QaCompositeConfigurationResult.Refused(evaluated.rejection)
            val accepted = evaluated as ResolverEditResult.Accepted
            val target = accepted.commit.selection.toFilterResolverPlan()
                ?: return@withLock QaCompositeConfigurationResult.Unavailable
            if (closed.get() || !caller.isActive || !isCurrent()) return@withLock QaCompositeConfigurationResult.Unavailable
            // Direct caller ownership: no ownerScope task can outlive this QA action.
            val result = publish(ReviewedQaResolverEdit(editorContext, target, context.limits, native.guard))
            when (result) {
                is FilterLibraryMutationResult.Applied -> QaCompositeConfigurationResult.Applied(result.generation)
                FilterLibraryMutationResult.ProtectionUnavailable -> QaCompositeConfigurationResult.Unavailable
                else -> QaCompositeConfigurationResult.Refused()
            }
        }
    }

    /** Validate one custom picker choice under admission without calling the durable port. */
    suspend fun stageCustomDns(
        choice: ResolverTierChoice,
        expectedEditorContext: String,
        context: ResolverDnsEditContext,
    ): ResolverDnsEditOutcome {
        currentCoroutineContext().ensureActive()
        if (closed.get()) return ResolverDnsEditOutcome.Unavailable
        return admission.withLock {
            if (closed.get()) return@withLock ResolverDnsEditOutcome.Unavailable
            val admittedContext = try {
                loadChainedDnsEdit?.let { context.copy(chained = it()) } ?: context
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withLock ResolverDnsEditOutcome.Unavailable }
            currentCoroutineContext().ensureActive()
            if (closed.get()) return@withLock ResolverDnsEditOutcome.Unavailable
            when (val evaluated = evaluateDnsEdit(
                SaveResolverTiers(listOf(choice), expectedEditorContext), admittedContext,
            )) {
                is ResolverEditResult.Rejected -> ResolverDnsEditOutcome.Refused(evaluated.rejection)
                is ResolverEditResult.Accepted -> {
                    val entry = evaluated.commit.selection.primary
                    ResolverDnsEditOutcome.DraftStaged(ResolverTierChoice(
                        providerId = entry.providerId.rawId, transportToken = entry.transport.token,
                        name = entry.customName ?: "",
                        primary = entry.customDohUrl ?: entry.customDotEndpoint ?: entry.customDoqEndpoint ?: entry.customPlainEndpoint ?: "",
                        secondary = entry.customSecondaryEndpoint ?: "",
                    ))
                }
            }
        }
    }

    /**
     * Pure policy evaluation with no runtime commitment. Custom draft commands use it to
     * surface the policy's typed refusal before reaching any durable commit path.
     */
    fun evaluateDnsEdit(request: ResolverEditRequest, context: ResolverDnsEditContext): ResolverEditResult =
        ResolverSelectionPolicy.evaluate(
            request = request,
            current = context.current,
            limits = context.limits,
            chained = context.chained,
            editorContext = context.editorContext,
        )

    /** The one commit body; callers must hold [admission]. */
    private suspend fun applyLocked(target: FilterResolverPlan): Boolean {
        mutableState.value = State.Applying(target.plan)
        val epoch = publicationEpoch.incrementAndGet()
        val task = ownerScope.async {
            if (closed.get()) null else port.apply(target)
        }
        val result = try {
            task.await()
        } catch (cancelled: CancellationException) {
            if (closed.get()) {
                settle(target.plan, null)
                return false
            }
            settleWhenCompleted(epoch, target.plan, task)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = State.Failed(target.plan, Failure.COMMIT)
            return false
        }
        return settle(target.plan, result)
    }

    private fun settle(plan: MilestoneResolverPlan, result: ResolverPlanSwitchResult?): Boolean =
        when (result) {
            null -> {
                mutableState.value = State.Idle
                false
            }
            is ResolverPlanSwitchResult.Applied -> {
                mutableState.value = State.Idle
                true
            }
            is ResolverPlanSwitchResult.ProtectionUnavailable -> {
                // The coordinator's ReloadRequired authority state is sticky for this
                // generation's lifetime, so the switch capability cannot recover by itself:
                // arm the reconstruction requirement for the product host instead of
                // pretending an Idle switch is usable again.
                mutableReconstructionRequired.value = true
                mutableState.value = State.Idle
                false
            }
            is ResolverPlanSwitchResult.Rejected,
            is ResolverPlanSwitchResult.Failed,
            is ResolverPlanSwitchResult.Superseded,
            -> {
                mutableState.value = State.Failed(plan, Failure.COMMIT)
                false
            }
        }

    /**
     * Settles the state from an owned task the caller has abandoned. The epoch fence keeps a
     * stale detached outcome from clobbering a newer apply that started after the caller
     * left: only the apply that owns the current [publicationEpoch] may publish.
     */
    private fun settleWhenCompleted(
        epoch: Long,
        plan: MilestoneResolverPlan,
        task: Deferred<ResolverPlanSwitchResult?>,
    ) {
        ownerScope.launch {
            val result = try {
                task.await()
            } catch (cancelled: CancellationException) {
                if (epoch == publicationEpoch.get() && !closed.get()) {
                    settle(plan, null)
                }
                return@launch
            } catch (_: Exception) {
                if (epoch == publicationEpoch.get() && !closed.get()) {
                    mutableState.value = State.Failed(plan, Failure.COMMIT)
                }
                return@launch
            }
            if (epoch == publicationEpoch.get() && !closed.get()) {
                settle(plan, result)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) ownerJob.cancel()
    }
}
