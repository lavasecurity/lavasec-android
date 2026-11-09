package com.lavasec.app.platform

import com.lavasec.app.foundation.pause.PauseState

/** Applies the authoritative protection-intent state machine without touching persistence. */
fun PersistedAppState.transitionProtectionIntent(enabled: Boolean): PersistedAppState {
    if (enabled == protectionIntent) return this
    return if (enabled) {
        copy(
            protectionIntent = true,
            protectionSessionId = Math.addExact(protectionSessionId, 1L),
            pauseDeadlineEpochMillis = null,
            pauseSessionId = null,
        )
    } else {
        copy(
            protectionIntent = false,
            pauseDeadlineEpochMillis = null,
            pauseSessionId = null,
        )
    }
}

/** Atomically applies or clears a pause owned by the current authoritative on-session. */
fun PersistedAppState.transitionPause(pause: PauseState?): PersistedAppState {
    if (pause == null) {
        if (this.pause == null) return this
        return copy(
            pauseDeadlineEpochMillis = null,
            pauseSessionId = null,
        )
    }
    require(protectionIntent) { "pause requires protection intent" }
    require(protectionSessionId > 0L) { "pause requires a positive protection session" }
    require(pause.protectionSessionId == protectionSessionId) {
        "pause must belong to the current protection session"
    }
    if (pause == this.pause) return this
    return copy(
        pauseDeadlineEpochMillis = pause.deadlineMillis,
        pauseSessionId = pause.protectionSessionId,
    )
}
