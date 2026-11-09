package com.lavasec.app.reactnative

/** Native-only projection: configuration bytes must never enter Fabric props or events. */
internal data class LavaWireGuardInputState(
    val generation: Long,
    val authorized: Boolean,
    val revealed: Boolean,
    val retainAcceptedDisplay: Boolean = false,
    val text: String? = null,
)

/** The real current foreground visit/draft owner implements this bounded editor port. */
internal interface LavaWireGuardInputPort {
    fun snapshot(ownerID: String): LavaWireGuardInputState?
    fun observe(ownerID: String, onChanged: () -> Unit): () -> Unit
    /** Rechecks visit, parent draft/revision and native authorization at the write edge. */
    fun replace(ownerID: String, text: String): Boolean
    fun canEditName(ownerID: String): Boolean
    /** Explicit native-only reveal of this unsaved same-owner buffer. */
    fun reveal(ownerID: String): Boolean
}
