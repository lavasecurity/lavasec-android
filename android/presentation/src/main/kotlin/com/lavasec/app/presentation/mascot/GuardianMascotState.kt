package com.lavasec.app.presentation.mascot

// Ported 1:1 from the pinned iOS baseline so the Android mascot obeys the
// exact same state graph as the shipped iOS Guardian:
//   lavasec-ios/Sources/LavaSecKit/GuardianMascotState.swift
// The pinned-baseline contract lives in docs/parity/first-build.md. Any state
// or transition added here without an iOS counterpart breaks mascot parity.
enum class GuardianMascotState {
    SLEEPING,
    WAKING,
    AWAKE,
    PAUSED,
    RETRYING,
    CONCERNED,
    GRATEFUL,
    ;

    /** The iOS `allowedNextStates` graph, verbatim: sleep exits only into wake. */
    val allowedNextStates: List<GuardianMascotState>
        get() = when (this) {
            SLEEPING -> listOf(WAKING)
            WAKING -> listOf(AWAKE, RETRYING, CONCERNED, SLEEPING)
            AWAKE -> listOf(SLEEPING, PAUSED, RETRYING, CONCERNED, GRATEFUL)
            PAUSED -> listOf(AWAKE, SLEEPING)
            RETRYING -> listOf(AWAKE, CONCERNED, SLEEPING)
            CONCERNED -> listOf(AWAKE, RETRYING, SLEEPING)
            GRATEFUL -> listOf(AWAKE)
        }
}
