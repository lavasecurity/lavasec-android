package com.lavasec.app.presentation.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LocalLavaReducedMotion
import com.lavasec.app.foundation.preferences.GuardianLook
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stateful driver for the ported Lava Guardian mascot.
 *
 * Ported from the pinned iOS view driver:
 *   lavasec-ios/Shared/SoftShieldGuardian.swift
 * (`activePlan` / `runPlan(_:animated:)` / `startTransition(from:to:animated:)`,
 * `onAppear` waking sequence, `onChange(of: state)` and `onChange(of: blinkTrigger)`)
 * and the tap behavior in:
 *   lavasec-ios/LavaSecApp/GuardView.swift
 * (`ProtectionStatusPanel.playGuardianTapGratitude()`).
 *
 * Compose mapping of the SwiftUI state machine: [activePlan] + an
 * [Animatable] elapsed value replace `@State activePlan/transitionElapsed`;
 * `withAnimation(.linear(duration:))` becomes an `Animatable.animateTo` with a
 * linear [tween] over the plan duration; the two `onChange` handlers become
 * keyed [LaunchedEffect]s whose previous animation coroutine is cancelled by
 * the key change exactly like SwiftUI replacing an in-flight `withAnimation`.
 */
@Composable
fun GuardianMascotView(
    state: GuardianMascotState,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    animates: Boolean = true,
    blinkTrigger: Int = 0,
    minimumFeatureScale: Float = 1f,
    onTap: (() -> Unit)? = null,
    guardianDescription: String = "",
    look: GuardianLook = GuardianLook.ORIGINAL,
) {
    // iOS gates every runPlan on `animates`. Android additionally folds the
    // reduced-motion setting into the same flag: a motion-free environment
    // renders stable frames only, the closest Compose equivalent of SwiftUI's
    // `animates == false` snap-to-end behavior.
    val reducedMotion = LocalLavaReducedMotion.current
    val effectiveAnimated = animates && !reducedMotion

    // iOS SoftShieldGuardian.init: a waking mascot boots from the sleeping pose
    // and (when animating) starts elapsed at 0 instead of the plan duration.
    val initialStartState =
        if (state == GuardianMascotState.WAKING) GuardianMascotState.SLEEPING else state
    val initialPlan = GuardianMascotAnimationPlan.animation(from = initialStartState, to = state)
    var activePlan by remember { mutableStateOf(initialPlan) }
    val elapsed = remember {
        // Animatable's default converter only understands Float; the elapsed
        // Double travels through a Float animation vector, which is fine because
        // it only drives frame(at:) and the plan's own math stays exact Double.
        Animatable(
            initialValue = if (effectiveAnimated && state == GuardianMascotState.WAKING) 0.0 else initialPlan.duration,
            typeConverter = DoubleVectorConverter,
        )
    }

    // iOS ProtectionStatusPanel: guardianOverrideState + isGuardianTapAnimationRunning.
    var overrideState by remember { mutableStateOf<GuardianMascotState?>(null) }
    var tapAnimationRunning by remember { mutableStateOf(false) }
    val tapScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // iOS SoftShieldGuardian.onAppear: a waking state runs the sleeping->waking
    // sequence (transition + hold + blink on awake). The first-composition
    // `LaunchedEffect(effectiveState)` below is a no-op for waking because the
    // initial plan's end state already IS the requested state.
    LaunchedEffect(Unit) {
        if (state == GuardianMascotState.WAKING) {
            startTransition(
                elapsed = elapsed,
                from = GuardianMascotState.SLEEPING,
                to = GuardianMascotState.WAKING,
                animated = effectiveAnimated,
                onPlanChanged = { activePlan = it },
            )
        }
    }

    // iOS `.onChange(of: state)` with `state = guardianOverrideState ?? guardianState`:
    // every effective-state change transitions from the CURRENT plan's end state so
    // interrupted transitions keep moving from where they actually stopped. SwiftUI
    // onChange fires only on CHANGES, never for the initial value — the remembered
    // last-value guard reproduces that, so the first composition cannot start a
    // competing transition that would cancel the initial sleeping->waking sequence
    // (whose plan legally ends on AWAKE after the hold + blink). When an animation is
    // interrupted, the visible pose is captured and the new transition blends from it
    // (SwiftUI withAnimation semantics) instead of snapping to the new plan's start.
    val effectiveState = overrideState ?: state
    var lastEffectiveState by remember { mutableStateOf(effectiveState) }
    var interruptedFrom by remember { mutableStateOf<GuardianMascotFrame?>(null) }

    // The one rendered-frame computation, shared by the renderer and by the
    // interruption captures below, so a second interruption samples the pose the
    // user actually sees (blend included) instead of the raw plan frame.
    val renderedFrame: GuardianMascotFrame = if (effectiveAnimated) {
        val sampled = activePlan.frame(elapsed.value)
        val blendActive = interruptedFrom != null &&
            activePlan.duration > 0 &&
            elapsed.value < activePlan.duration - EPSILON
        if (blendActive) {
            GuardianMascotFrame.interpolated(
                from = checkNotNull(interruptedFrom),
                to = sampled,
                progress = elapsed.value / activePlan.duration,
            )
        } else {
            sampled
        }
    } else {
        // iOS with `animates == false` snaps elapsed to plan.duration, which for
        // every plan style renders the end state's stable frame.
        GuardianMascotAnimationPlan.stableFrame(effectiveState)
    }

    LaunchedEffect(effectiveState) {
        if (effectiveState != lastEffectiveState) {
            lastEffectiveState = effectiveState
            if (effectiveState != activePlan.endState) {
                // Capture the RENDERED (possibly blended) pose before the plan is
                // replaced so the next transition — even a second interruption —
                // blends from what the user actually sees.
                // Only capture when the outgoing plan is actually mid-flight;
                // after a completed plan the replacement follows its own easing
                // (double-easing would distort staged curves like awake->grateful).
                interruptedFrom = if (effectiveAnimated && elapsed.value < activePlan.duration - EPSILON) {
                    renderedFrame
                } else {
                    null
                }
                startTransition(
                    elapsed = elapsed,
                    from = activePlan.endState,
                    to = effectiveState,
                    animated = effectiveAnimated,
                    onPlanChanged = { plan -> activePlan = plan },
                )
            }
        }
    }

    // iOS `.onChange(of: blinkTrigger)` -> runPlan(blink(on: activePlan.endState)).
    // SwiftUI onChange never fires for the initial value, hence the > 0 guard
    // (a keyed LaunchedEffect DOES run on first composition).
    LaunchedEffect(blinkTrigger) {
        if (blinkTrigger > 0) {
            // A blink replaces the active plan from its exact end state; the
            // interrupted-blend capture is stale by then and must clear so the
            // blink renders on the plan's own curve.
            runPlan(
                elapsed = elapsed,
                plan = GuardianMascotAnimationPlan.blink(on = activePlan.endState),
                animated = effectiveAnimated,
                onPlanChanged = { plan ->
                    activePlan = plan
                    interruptedFrom = null
                },
            )
        }
    }

    // iOS GuardView.playGuardianTapGratitude() (GuardView.swift ~172-209): a tap on
    // the awake mascot plays a brief awake -> grateful -> awake thank-you. The light
    // haptic fires on EVERY awake tap — intentionally NOT gated by the animation so
    // rapid repeated taps keep returning tactile feedback. The visual sequence is
    // still de-duped (ignored while one is already running) so it doesn't restart and
    // stutter. Neither fires from states that communicate real protection status
    // (sleeping/waking/paused/retrying/concerned).
    fun handleTap() {
        // Android-only hook so hosts and tests can observe the container tap; iOS
        // exposes no callback here.
        onTap?.invoke()

        if (state != GuardianMascotState.AWAKE) return

        // Haptic first and ungated: a tap during an in-flight animation still gives
        // feedback. iOS plays ProtectionHapticFeedback.play(.guardianTapAcknowledged);
        // HapticFeedbackType.LongPress is the lightest Compose stand-in.
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        if (tapAnimationRunning) return
        tapAnimationRunning = true

        tapScope.launch {
            // iOS dwell: reduceMotion ? 0.20 : 0.35.
            val dwell = if (reducedMotion) 0.20 else 0.35
            val stateChange = GuardianMascotAnimationPlan.stateChangeDuration

            overrideState = GuardianMascotState.GRATEFUL
            delay(((stateChange + dwell) * 1000.0).roundToLong())

            // Clear the override so the mascot animates grateful -> the live base state.
            // If protection status changed mid-tap, this stays honest instead of forcing
            // awake (iOS comment, GuardView.swift).
            overrideState = null
            delay((stateChange * 1000.0).roundToLong())

            tapAnimationRunning = false
        }
    }

    // Only the awake mascot has a meaningful tap action; in every other state the
    // clickable modifier is omitted entirely so TalkBack meets a decorative image,
    // never a disabled control.
    Box(
        modifier = modifier
            .size(size)
            .testTag("guard_mascot")
            .then(
                if (state == GuardianMascotState.AWAKE) {
                    Modifier.clickable(onClick = ::handleTap)
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = guardianDescription
            },
    ) {
        GuardianMascotRenderer(
            frame = renderedFrame,
            modifier = Modifier.fillMaxSize(),
            minimumFeatureScale = minimumFeatureScale,
            look = look,
        )
    }
}

/**
 * iOS SoftShieldGuardian.runPlan(_:animated:), verbatim: rebuild the active plan,
 * then either snap to its end (no animation) or animate elapsed 0 -> duration on
 * a LINEAR curve (iOS `withAnimation(.linear(duration: plan.duration))`).
 */
private suspend fun runPlan(
    elapsed: Animatable<Double, *>,
    plan: GuardianMascotAnimationPlan,
    animated: Boolean,
    onPlanChanged: (GuardianMascotAnimationPlan) -> Unit,
) {
    onPlanChanged(plan)
    if (!animated) {
        elapsed.snapTo(plan.duration)
        return
    }

    elapsed.snapTo(0.0)
    elapsed.animateTo(
        targetValue = plan.duration,
        animationSpec = tween(
            durationMillis = (plan.duration * 1000.0).roundToInt(),
            easing = LinearEasing,
        ),
    )
}

/** iOS SoftShieldGuardian.startTransition(from:to:animated:): animation plan + runPlan. */
private suspend fun startTransition(
    elapsed: Animatable<Double, *>,
    from: GuardianMascotState,
    to: GuardianMascotState,
    animated: Boolean,
    onPlanChanged: (GuardianMascotAnimationPlan) -> Unit,
) {
    // Interrupted transitions blend from the rendered pose captured by the
    // caller; the plan still carries the honest state endpoints so completion
    // always lands exactly.
    runPlan(
        elapsed = elapsed,
        plan = GuardianMascotAnimationPlan.animation(from = from, to = to),
        animated = animated,
        onPlanChanged = onPlanChanged,
    )
}

/** Tolerance for "animation completed" checks: the Float converter round-trip
 * can leave elapsed a hair below the Double plan duration (0.44f -> 0.4399999976). */
private const val EPSILON = 1e-6

/** Animatable vector converter for Double elapsed values (default only supports Float). */
private val DoubleVectorConverter = TwoWayConverter<Double, AnimationVector1D>(
    convertToVector = { AnimationVector1D(it.toFloat()) },
    convertFromVector = { it.value.toDouble() },
)
