package com.lavasec.app.product.activity

/**
 * Minimal Android owner for the canonical `activity.visibility` review-moment dwell token.
 *
 * iOS turns a short visible dwell on the Activity screen into a review-prompt moment
 * (`AppViewModel.noteActivityViewingReviewMoment`). Android has no equivalent review prompt, so this
 * owner does not fabricate one: it tracks the currently visible moment and records a completed dwell
 * once it has lasted [minDwellMillis]. The signal is therefore consumed by a real owner instead of
 * being silently dropped, and no durable user data is written.
 */
internal class ActivityVisibilityOwner(
    private val nowMillis: () -> Long,
    private val minDwellMillis: Long = MIN_DWELL_MILLIS,
) {
    /** Observable owner effect: the live moment (if any) and how many dwells have completed. */
    internal data class Snapshot(
        val visibleToken: String?,
        val visibleStartSeconds: Long?,
        val visibleEndSeconds: Long?,
        val completedDwells: Long,
    )

    private data class Visible(
        val token: String,
        val startSeconds: Long?,
        val endSeconds: Long?,
        val sinceMillis: Long,
    )

    private var visible: Visible? = null
    private var completedDwells = 0L

    /**
     * Sets or clears the visible review moment. A hide completes the dwell only when it targets the
     * current token and the moment lasted at least [minDwellMillis]; a stale hide never clears a
     * newer token and never records a dwell.
     */
    @Synchronized
    fun setVisible(token: String, visible: Boolean, startSeconds: Long? = null, endSeconds: Long? = null) {
        if (visible) {
            this.visible = Visible(token, startSeconds, endSeconds, nowMillis())
            return
        }
        val current = this.visible ?: return
        if (current.token != token) return
        if (nowMillis() - current.sinceMillis >= minDwellMillis) completedDwells++
        this.visible = null
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        visibleToken = visible?.token,
        visibleStartSeconds = visible?.startSeconds,
        visibleEndSeconds = visible?.endSeconds,
        completedDwells = completedDwells,
    )

    companion object {
        /** iOS `ReviewPromptPolicy.activityMinDwellSeconds` = 3 seconds. */
        const val MIN_DWELL_MILLIS = 3_000L
    }
}
