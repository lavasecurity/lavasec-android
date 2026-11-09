package com.lavasec.app.reactnative

/** Immutable Activity witness for a native form visit; a successor cannot adopt its draft. */
internal class NativeVisitHost(
    val owner: Any,
    val nativeHostID: String,
    private val alive: () -> Boolean,
) {
    fun sameHost(other: NativeVisitHost): Boolean = owner === other.owner && nativeHostID == other.nativeHostID

    /** A temporarily absent context may retain a live original visit, never expose or edit it. */
    fun retains(current: NativeVisitHost?): Boolean = alive() &&
        (current == null || current.alive() && sameHost(current))

    /** Private reads and writes require the original live host to be the actual current host. */
    fun matches(current: NativeVisitHost?): Boolean = current != null && alive() &&
        current.alive() && sameHost(current)
}
