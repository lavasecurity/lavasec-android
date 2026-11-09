package com.lavasec.app.reactnative.components

import android.app.Activity
import android.content.Intent
import com.lavasec.app.LavasecApplication
import com.lavasec.app.MainActivity
import com.lavasec.app.MainProductViewModel
import com.lavasec.app.foundation.flows.NativeFlowBuildConfiguration
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.reactnative.LavaReactActivity
import com.lavasec.app.security.presentPasscodeAuthentication
import java.util.concurrent.atomic.AtomicReference

/**
 * The one native flow host.
 *
 * System prompts and modal native forms are presented through exactly one owner so two sensitive
 * presentations can never overlap. Every launch is typed and bound to its [LavaNativeFlowRequest]
 * — the canonical route, the credential-graph generation and the account that initiated it — and a
 * returned result is only delivered when that binding still holds. The host itself is Android-free
 * apart from [LavaNativeFlowProduction]; the refusal vocabulary lives in [LavaNativeFlowPolicy].
 */

/** The seam the host presents through. A fake implements this in JVM tests; production wires
 * `presentNative` and the SecurityOwner credential prompt. */
internal interface LavaNativeFlowLauncher {
    /** Presents a canonical `native.flow` route. */
    suspend fun presentRoute(request: LavaNativeFlowRequest): LavaNativeFlowLaunchResult

    /** Runs the passcode/credential flow; the SecurityOwner decides whether a prompt is required. */
    suspend fun authorizeCredential(
        request: LavaNativeFlowRequest,
        surface: ProtectedSurface?,
    ): LavaNativeFlowLaunchResult
}

/**
 * One active presentation. `presentRoute` and `authorizeCredential` share the same slot, so a
 * system prompt cannot open on top of a credential prompt (or vice versa) for the same host.
 * Graph/account are sampled after the launch returns and compared to the captured request.
 */
internal class LavaNativeFlowHost(
    private val configuration: NativeFlowBuildConfiguration,
    private val launcher: LavaNativeFlowLauncher,
    private val currentGraphGeneration: () -> Long?,
    private val currentAccountId: () -> String?,
) {
    private val active = AtomicReference<LavaNativeFlowRequest?>(null)

    /** The request currently owning the host, or `null`. */
    val activeRequestId: Long? get() = active.get()?.requestId

    suspend fun launchRoute(request: LavaNativeFlowRequest): LavaNativeFlowOutcome {
        LavaNativeFlowPolicy.refusal(request, configuration)?.let { reason ->
            return LavaNativeFlowOutcome.Refused(request.requestId, reason)
        }
        return withActive(request) { launcher.presentRoute(request) }
    }

    suspend fun authorizeCredential(
        request: LavaNativeFlowRequest,
        surface: ProtectedSurface?,
    ): LavaNativeFlowOutcome = withActive(request) { launcher.authorizeCredential(request, surface) }

    private suspend inline fun withActive(
        request: LavaNativeFlowRequest,
        present: () -> LavaNativeFlowLaunchResult,
    ): LavaNativeFlowOutcome {
        if (!active.compareAndSet(null, request)) {
            return LavaNativeFlowOutcome.Refused(request.requestId, LavaNativeFlowReason.BUSY)
        }
        return try {
            LavaNativeFlowPolicy.bindResult(
                request,
                present(),
                currentGraphGeneration(),
                currentAccountId(),
            )
        } finally {
            active.compareAndSet(request, null)
        }
    }
}

/**
 * Production launchers. These are the *real* reachable seams:
 *
 *  - route flows start the native flow activity the existing `NativeProductBridge` route handler
 *    already uses (`MainActivity.NATIVE_FLOW_ROUTE` + `LavaReactActivity.presentNative`), whose
 *    single `pendingFlow` continuation is the activity-level one-presentation guard;
 *  - the credential flow calls the live `SecurityOwner` through
 *    `MainProductViewModel.securityAuthorize` and presents the canonical passcode dialog, exactly
 *    as `NativeProductBridge` does — no new auth authority is introduced.
 */
internal object LavaNativeFlowProduction {
    /** A request flow name for the credential prompt; never sent to the canonical route allowlist. */
    const val CREDENTIAL_FLOW = "credentials"

    fun host(
        activity: LavaReactActivity,
        configuration: NativeFlowBuildConfiguration = NativeFlowBuildConfiguration.RELEASE,
        credentialSurface: ProtectedSurface? = null,
        product: MainProductViewModel = (activity.application as LavasecApplication).foregroundProduct,
    ): LavaNativeFlowHost {
        val application = activity.application as LavasecApplication
        return LavaNativeFlowHost(
            configuration = configuration,
            launcher = object : LavaNativeFlowLauncher {
                override suspend fun presentRoute(request: LavaNativeFlowRequest): LavaNativeFlowLaunchResult {
                    val intent = Intent(activity, MainActivity::class.java)
                        .putExtra(MainActivity.NATIVE_FLOW_ROUTE, request.flow)
                    val code = activity.presentNative(intent)
                    return LavaNativeFlowLaunchResult.Launched(completed = code == Activity.RESULT_OK)
                }

                override suspend fun authorizeCredential(
                    request: LavaNativeFlowRequest,
                    surface: ProtectedSurface?,
                ): LavaNativeFlowLaunchResult {
                    val accepted = product.securityAuthorize(
                        expectedGeneration = request.graphGeneration,
                        surface = surface ?: credentialSurface,
                    ) { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }
                    return LavaNativeFlowLaunchResult.Launched(completed = accepted)
                }
            },
            currentGraphGeneration = { product.state.value.graphGeneration },
            currentAccountId = { application.accountBackend.value?.sessions?.state?.value?.userId },
        )
    }
}
