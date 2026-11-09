package com.lavasec.app.reactnative

import com.lavasec.app.security.SecurityOwnerState
import com.lavasec.app.security.SecurityPrivacyPolicy

/** Native launch/background custody. Layout may finish a boundary, never create one. */
internal class NativeAppPrivacyBoundary {
    private var generation: Long? = null
    private var classified = false
    private var pending = true
    private var cover = true
    @Volatile var required = true
        private set

    fun classify(generation: Long?, state: SecurityOwnerState?) {
        if (this.generation != generation) {
            this.generation = generation
            classified = false
            pending = true
            cover = true
        }
        if (!classified && state != null) {
            cover = SecurityPrivacyPolicy.requiresBackgroundCover(state)
            classified = state.appUnlockCoverChoice != null
        }
        if (state?.appUnlockCoverChoice == false) cover = false
        required = pending && cover
    }

    fun background(state: SecurityOwnerState?) {
        pending = true
        cover = SecurityPrivacyPolicy.requiresBackgroundCover(state)
        classified = state != null
        required = cover
    }

    fun acknowledge(authorizedAndReady: Boolean) {
        if (authorizedAndReady) pending = false
        required = pending && cover
    }
}
